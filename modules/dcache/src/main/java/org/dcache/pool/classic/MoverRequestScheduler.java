package org.dcache.pool.classic;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.joining;
import static org.dcache.pool.classic.IoRequestState.CANCELED;
import static org.dcache.pool.classic.IoRequestState.DONE;
import static org.dcache.pool.classic.IoRequestState.NEW;
import static org.dcache.pool.classic.IoRequestState.QUEUED;
import static org.dcache.pool.classic.IoRequestState.RUNNING;

import diskCacheV111.pools.PoolCostInfo.NamedPoolQueueInfo;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.DiskErrorCacheException;
import diskCacheV111.vehicles.IoJobInfo;
import diskCacheV111.vehicles.JobInfo;
import diskCacheV111.vehicles.ProtocolInfo;
import dmg.cells.nucleus.CDC;
import java.io.InterruptedIOException;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.dcache.pool.FaultAction;
import org.dcache.pool.FaultEvent;
import org.dcache.pool.FaultListener;
import org.dcache.pool.movers.Mover;
import org.dcache.pool.movers.json.MoverData;
import org.dcache.pool.repository.FileStore;
import org.dcache.pool.repository.OutOfDiskException;
import org.dcache.util.AdjustableSemaphore;
import org.dcache.util.IoPrioritizable;
import org.dcache.util.IoPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoverRequestScheduler {

    private static final Logger LOGGER =
          LoggerFactory.getLogger(MoverRequestScheduler.class);

    private static final long DEFAULT_LAST_ACCESSED = 0;
    private static final long DEFAULT_TOTAL = 0;

    /**
     * A RuntimeException that wraps a CacheException.
     */
    private static class UncheckedCacheException extends RuntimeException {

        private UncheckedCacheException(CacheException cause) {
            super(cause.getMessage(), cause);
        }

        private CacheException getCacheException() {
            return (CacheException) getCause();
        }
    }

    /**
     * The name of IoScheduler.
     */
    private final String _name;

    /**
     * All movers, both queued and running, managed by the scheduler.
     */
    private final Map<Integer, PrioritizedRequest> _jobs =
          new ConcurrentHashMap<>(128);

    /**
     * Requests by door unique request id.
     */
    private final Map<String, PrioritizedRequest> _moverByRequests
          = new ConcurrentHashMap<>(128);

    /**
     * ID of the current queue. Used to identify queue in {@link IoQueueManager}.
     */
    private final int _queueId;

    private final List<FaultListener> _faultListeners =
          new CopyOnWriteArrayList<>();

    /**
     * Number of free job slots.
     */
    private final AdjustableSemaphore _semaphore = new AdjustableSemaphore();

    /**
     * JTM timeout since last activity.
     */
    private long _lastAccessed = DEFAULT_LAST_ACCESSED;

    /**
     * JTM timeout since transfer start.
     */
    private long _total = DEFAULT_TOTAL;

    /**
     * Current queue order.
     */
    private Order _order;

    /**
     * Queued movers.
     */
    private BlockingQueue<PrioritizedRequest> _queue;

    /**
     * Job id generator
     */
    private int _nextId;

    /**
     * True when scheduler has been terminated.
     */
    private volatile boolean _isShutdown;

    private boolean _loggedQueuingMovers;

    public enum Order {
        FIFO, LIFO
    }

    public MoverRequestScheduler(String name, int queueId, Order order) {
        _name = name;
        _queueId = queueId;
        _order = order;
        _queue = createQueue(order);
        _semaphore.setMaxPermits(2);
    }

    public void addFaultListener(FaultListener listener) {
        _faultListeners.add(listener);
    }

    public void removeFaultListener(FaultListener listener) {
        _faultListeners.remove(listener);
    }

    private PriorityBlockingQueue<PrioritizedRequest> createQueue(Order order) {
        /* PriorityBlockingQueue returns the least elements first, that is, the
         * the highest priority requests have to be first in the ordering.
         */
        Comparator<IoPrioritizable> comparator =
              order == Order.FIFO
                    ? Comparator
                    .comparing(IoPrioritizable::getPriority)
                    .reversed()
                    .thenComparingLong(IoPrioritizable::getCreateTime)
                    : Comparator
                          .comparing(IoPrioritizable::getPriority)
                          .thenComparingLong(IoPrioritizable::getCreateTime)
                          .reversed();

        return new PriorityBlockingQueue<>(16, comparator);
    }

    public Order getOrder() {
        return _order;
    }

    public synchronized void setOrder(Order order) {
        if (order != _order) {
            PriorityBlockingQueue<PrioritizedRequest> queue = createQueue(order);
            _queue.drainTo(queue);
            _queue = queue;
            _order = order;
        }
    }

    /**
     * Get mover id for given door request. If there is no mover associated with {@code
     * doorUniqueueRequest} a new mover will be created by using provided {@code moverSupplier}.
     * <p>
     * The returned mover id generated with following encoding: | 31- queue id -24|23- job id -0|
     *
     * @param moverSupplier {@link MoverSupplier} which can create a mover for given requests.
     * @param doorUniqueId  unique request identifier generated by the door.
     * @param priority
     * @return mover id
     */
    public int getOrCreateMover(MoverSupplier moverSupplier, String doorUniqueId,
          IoPriority priority) throws CacheException {
        checkState(!_isShutdown);

        try {
            /* Create the request if it doesn't already exists.
             */
            PrioritizedRequest request =
                  _moverByRequests.computeIfAbsent(doorUniqueId,
                        key -> {
                            try {
                                return createRequest(moverSupplier, key, priority);
                            } catch (CacheException e) {
                                throw new UncheckedCacheException(e);
                            }
                        });

            /* If not already queued, submit it.
             */
            if (request.queue()) {
                if (submit(request)) {
                    /* There was a free slot in the queue so we submit directly to execution.
                     */
                    sendToExecution(request);
                } else if (_semaphore.getMaxPermits() <= 0) {
                    LOGGER.warn("A task was added to queue '{}', however the queue is not " +
                          "configured to execute any tasks.", _name);
                }
            }

            return request.getId();
        } catch (UncheckedCacheException e) {
            throw e.getCacheException();
        }
    }

    private PrioritizedRequest createRequest(MoverSupplier moverSupplier,
          String doorUniqueId,
          IoPriority priority) throws CacheException {
        return new PrioritizedRequest(_queueId << 24 | nextId(),
              doorUniqueId,
              moverSupplier.createMover(),
              priority);
    }

    /**
     * Add a request to the scheduler.
     * <p>
     * Returns true if the caller acquired a job slot and must send the job to execution.
     *
     * @param request
     * @return
     */
    private synchronized boolean submit(PrioritizedRequest request) {
        if (_jobs.put(request.getId(), request) != null) {
            throw new RuntimeException(
                  "Duplicate mover id detected. Please report to support@dcache.org.");
        }

        if (_semaphore.tryAcquire()) {
            return true;
        } else {
            _queue.add(request);
            if (!_loggedQueuingMovers) {
                LOGGER.warn("Mover queue \"{}\" is now queuing movers", _name);
                _loggedQueuingMovers = true;
            }
            return false;
        }
    }

    /**
     * Returns the next job or releases a job slot. If a non-null value is returned, the caller must
     * submit the job to execution. Should only be caller by a caller than currently holds a job
     * slot.
     *
     * @return
     */
    private synchronized PrioritizedRequest nextOrRelease() {
        PrioritizedRequest request = _queue.poll();
        if (request == null) {
            _semaphore.release();

            /* We now have (at least) one "mover slot" free.  Therefore, the
             * pool will accept the next mover (for this queue) without queuing.
             */
            if (_loggedQueuingMovers) {
                LOGGER.warn("Next mover on mover queue \"{}\" will not be queued", _name);
                _loggedQueuingMovers = false;
            }
        }
        return request;
    }

    private synchronized int nextId() {
        if (_nextId == 0x00FFFFFF) {
            _nextId = 0;
        } else {
            _nextId++;
        }
        return _nextId;
    }

    /**
     * Get current number of concurrently running jobs.
     *
     * @return number of running jobs.
     */
    public synchronized int getActiveJobs() {
        return _jobs.size() - _queue.size();
    }

    /**
     * Get job information.
     */
    public Optional<JobInfo> getJobInfo(int id) throws NoSuchElementException {
        PrioritizedRequest request = _jobs.get(id);
        return request == null ? Optional.empty() : Optional.of(request.toJobInfo());
    }

    /**
     * Get list of all jobs in this queue.
     *
     * @return list of all jobs
     */
    public List<IoJobInfo> getJobInfos() {
        return Collections.unmodifiableList(_jobs.values().stream()
              .map(PrioritizedRequest::toJobInfo)
              .collect(Collectors.toList())
        );
    }

    /**
     * This method is necessary because IoJobInfo does not give all the info that the toString on
     * the Mover class does.
     */
    public List<MoverData> getMoverData(Predicate<MoverData> filter,
          Comparator<MoverData> sorter) {
        return _jobs.values()
              .stream()
              .map(PrioritizedRequest::toMoverData)
              .filter(filter)
              .sorted(sorter)
              .collect(Collectors.toList());
    }

    /**
     * Get a {@link Stream} of all jobs in this queue.
     *
     * @return list of all jobs
     */
    Stream<PrioritizedRequest> getJobs() {

        return _jobs.values().stream();
    }

    /**
     * Get the maximal number allowed of concurrently running jobs by this scheduler.
     *
     * @return maximal number of jobs.
     */
    public int getMaxActiveJobs() {
        return _semaphore.getMaxPermits();
    }

    /**
     * Set maximal number of concurrently running jobs by this scheduler. All pending jobs will be
     * executed.
     *
     * @param maxJobs
     */
    public void setMaxActiveJobs(int maxJobs) {
        synchronized (this) {
            _semaphore.setMaxPermits(maxJobs);
        }
        PrioritizedRequest request;
        while (_semaphore.tryAcquire() && (request = nextOrRelease()) != null) {
            sendToExecution(request);
        }
    }

    /**
     * Get number of requests waiting for execution.
     *
     * @return number of pending requests.
     */
    public synchronized int getQueueSize() {
        return _queue.size();
    }

    /**
     * @return object containing queue name and statistics.
     */
    public NamedPoolQueueInfo getQueueInfo() {
        int jobs;
        int queued;
        int writes;
        int max_active;
        synchronized (this) {
            jobs = _jobs.size();
            writes = (int) _jobs.values().stream().filter(PrioritizedRequest::isWrite).count();
            queued = _queue.size();
            max_active = _semaphore.getMaxPermits();
        }
        int active = jobs - queued;
        int reads = jobs - writes;
        return new NamedPoolQueueInfo(_name, active, max_active, queued, reads, writes);
    }

    /**
     * Get the name of this scheduler.
     *
     * @return name of the scheduler
     */
    public String getName() {
        return _name;
    }

    public int getId() {
        return _queueId;
    }

    /**
     * Cancel the request. Any IO in progress will be interrupted.
     *
     * @param id
     * @param explanation A reason to log
     * @return true if a job was killed, false otherwise
     */
    public synchronized boolean cancel(int id, @Nullable String explanation) {
        boolean killed = false;
        PrioritizedRequest request = _jobs.get(id);
        if (request != null) {
            request.kill(explanation);
            if (_queue.remove(request)) {
                postprocessWithoutJobSlot(request);
            }
            killed = true;
        }
        return killed;
    }

    private void postprocessWithoutJobSlot(PrioritizedRequest request) {
        try (CDC ignore = request.getCdc().restore()) {
            request.getMover().close(
                  new CompletionHandler<Void, Void>() {
                      @Override
                      public void completed(Void result, Void attachment) {
                          release();
                      }

                      private void release() {
                          request.done();
                          _jobs.remove(request.getId());
                          _moverByRequests.remove(request.getDoorUniqueId());
                      }

                      @Override
                      public void failed(Throwable exc, Void attachment) {
                          release();
                      }


                  });
        }
    }

    /**
     * Shutdown the scheduler. All subsequent execution request will be rejected.
     */
    public void shutdown() throws InterruptedException {
        checkState(!_isShutdown);
        _isShutdown = true;

        /* Drain jobs from the queue so they will never be started. Has to be done
         * before killing jobs as otherwise the queued jobs will immediatley fill
         * the freed job slot.
         */
        Collection<PrioritizedRequest> toBeCancelled = new ArrayList<>();
        _queue.drainTo(toBeCancelled);

        /* Kill both the jobs that were queued and which are running. */
        _jobs.values().forEach(j -> j.kill("shutdown"));

        /* Jobs that were queued were never submitted for execution and thus we
         * manually trigger postprocessing.
         */
        toBeCancelled.forEach(this::postprocessWithoutJobSlot);

        LOGGER.info("Waiting for movers on queue '{}' to finish", _name);
        if (!_semaphore.tryAcquire(_semaphore.getMaxPermits(), 2, TimeUnit.SECONDS)) {
            // This is often due to a mover not reacting to interrupt or the transfer
            // doing a lengthy checksum calculation during post processing.
            String versions =
                  _jobs.values().stream()
                        .map(PrioritizedRequest::getMover)
                        .map(Mover::getProtocolInfo)
                        .map(ProtocolInfo::getVersionString)
                        .collect(joining(","));
            LOGGER.warn("Failed to terminate some movers prior to shutdown: {}", versions);
        }
    }

    private void sendToExecution(final PrioritizedRequest request) {
        try (CDC ignore = request.getCdc().restore()) {
            request.transfer(
                  new CompletionHandler<Void, Void>() {
                      @Override
                      public void completed(Void result, Void attachment) {
                          postprocess();
                      }

                      @Override
                      public void failed(Throwable exc, Void attachment) {
                          if (exc instanceof InterruptedException
                                || exc instanceof InterruptedIOException) {
                              request.getMover()
                                    .setTransferStatus(CacheException.DEFAULT_ERROR_CODE,
                                          "Transfer was killed");
                          } else if (exc instanceof DiskErrorCacheException) {
                              FaultAction faultAction = null;
                              //TODO this is done because the FileStoreState is in another module
                              // to be improved
                              switch (((DiskErrorCacheException) exc).checkStatus(exc.getMessage())){
                                  case READ_ONLY:
                                      faultAction = FaultAction.READONLY;
                                  break;
                                  default:
                                      faultAction = FaultAction.DISABLED;
                                  break;
                              }
                              FaultEvent faultEvent = new FaultEvent("transfer",
                                      faultAction, exc.getMessage(), exc);
                              _faultListeners.forEach(l -> l.faultOccurred(faultEvent));
                          } else if (exc instanceof OutOfDiskException) {
                              FaultEvent faultEvent = new FaultEvent(
                                    "post-processing",
                                    FaultAction.READONLY,
                                    exc.getMessage(), exc);
                              _faultListeners.forEach(
                                    l -> l.faultOccurred(faultEvent));
                          }
                          postprocess();
                      }

                      private void postprocess() {
                          try (CDC ignore = request.getCdc().restore()) {
                              request.getMover().close(
                                    new CompletionHandler<Void, Void>() {
                                        @Override
                                        public void completed(Void result, Void attachment) {
                                            release();
                                        }

                                        @Override
                                        public void failed(Throwable exc, Void attachment) {
                                            if (exc instanceof DiskErrorCacheException) {
                                                FaultAction faultAction = null;
                                                switch (((DiskErrorCacheException) exc).checkStatus(exc.getMessage())){
                                                    case READ_ONLY:
                                                        faultAction = FaultAction.READONLY;
                                                        break;
                                                    default:
                                                        faultAction = FaultAction.DISABLED;
                                                        break;
                                                }
                                                FaultEvent faultEvent = new FaultEvent(
                                                      "post-processing",
                                                        faultAction,
                                                      exc.getMessage(), exc);
                                                _faultListeners.forEach(
                                                      l -> l.faultOccurred(faultEvent));
                                            } else if (exc instanceof OutOfDiskException) {
                                                FaultEvent faultEvent = new FaultEvent(
                                                      "post-processing",
                                                      FaultAction.READONLY,
                                                      exc.getMessage(), exc);
                                                _faultListeners.forEach(
                                                      l -> l.faultOccurred(faultEvent));
                                            }
                                            release();
                                        }

                                        private void release() {
                                            request.done();
                                            _jobs.remove(request.getId());
                                            _moverByRequests.remove(request.getDoorUniqueId());
                                            PrioritizedRequest nextRequest = nextOrRelease();
                                            if (nextRequest != null) {
                                                sendToExecution(nextRequest);
                                            }
                                        }
                                    });
                          }
                      }
                  });
        }
    }

    public synchronized boolean isExpired(JobInfo job, long now) {
        long started = job.getStartTime();
        long lastAccessed =
              job instanceof IoJobInfo ?
                    ((IoJobInfo) job).getLastTransferred() :
                    now;

        return
              ((getLastAccessed() > 0L) && (lastAccessed > 0L) &&
                    ((now - lastAccessed) > getLastAccessed())) ||
                    ((getTotal() > 0L) && (started > 0L) &&
                          ((now - started) > getTotal()));
    }

    public synchronized long getLastAccessed() {
        return _lastAccessed;
    }

    public synchronized void setLastAccessed(long lastAccessed) {
        checkArgument(lastAccessed >= 0L,
              "The lastAccess timeout must be greater than or equal to 0.");
        _lastAccessed = lastAccessed;
    }

    public boolean hasNonDefaultLastAccessed() {
        return _lastAccessed != DEFAULT_LAST_ACCESSED;
    }

    public synchronized long getTotal() {
        return _total;
    }

    public synchronized void setTotal(long total) {
        checkArgument(total >= 0L, "The total timeout must be greater than or equal to 0.");
        _total = total;
    }

    public boolean hasNonDefaultTotal() {
        return _total != DEFAULT_TOTAL;
    }

    static class PrioritizedRequest implements IoPrioritizable, Comparable<PrioritizedRequest> {

        private final Mover<?> _mover;
        private final IoPriority _priority;
        private final long _ctime;
        private final int _id;
        private final CDC _cdc;

        /**
         * Request creation time.
         */
        private final long _submitTime;

        private final String _doorUniqueId;

        private IoRequestState _state;

        /**
         * Transfer start time.
         */
        private long _startTime;

        private Cancellable _cancellable;

        PrioritizedRequest(int id, String doorUniqueId, Mover<?> mover, IoPriority p) {
            _id = id;
            _mover = mover;
            _priority = p;
            _ctime = System.nanoTime();
            _submitTime = System.currentTimeMillis();
            _state = NEW;
            _doorUniqueId = doorUniqueId;
            _cdc = new CDC();
        }

        @Override
        public int compareTo(PrioritizedRequest o) {
            return Integer.compare(_id, o._id);
        }

        public Mover<?> getMover() {
            return _mover;
        }

        public CDC getCdc() {
            return _cdc;
        }

        public int getId() {
            return _id;
        }

        public String getDoorUniqueId() {
            return _doorUniqueId;
        }

        @Override
        public IoPriority getPriority() {
            return _priority;
        }

        @Override
        public long getCreateTime() {
            return _ctime;
        }

        public boolean isRead() {
            return _mover.getIoMode().equals(FileStore.O_READ);
        }

        public boolean isWrite() {
            return _mover.getIoMode().equals(FileStore.O_RW);
        }

        @Override
        public int hashCode() {
            return _id;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (!(o instanceof PrioritizedRequest)) {
                return false;
            }

            final PrioritizedRequest other = (PrioritizedRequest) o;
            return _id == other._id;
        }

        @Override
        public synchronized String toString() {
            return _state + " : " + _mover.toString() + " si={" + _mover.getFileAttributes()
                  .getStorageClass() + "}";
        }

        public synchronized IoJobInfo toJobInfo() {
            return new IoJobInfo(_submitTime, _startTime, _state.toString(), _id,
                  _mover.getPathToDoor().getDestinationAddress().toString(), _mover.getClientId(),
                  _mover.getFileAttributes().getPnfsId(), _mover.getBytesTransferred(),
                  _mover.getBytesExpected(),
                  _mover.getTransferTime(), _mover.getLastTransferred(),
                  _mover.remoteConnections());
        }

        public synchronized MoverData toMoverData() {
            MoverData data = new MoverData();
            data.setPnfsId(_mover.getFileAttributes().getPnfsId().toString());
            data.setQueue(_mover.getQueueName());
            data.setMode(_mover.getIoMode().toString());
            data.setStorageClass(_mover.getFileAttributes().getStorageClass());
            data.setDoor(_mover.getPathToDoor().getDestinationAddress().toString());
            data.setState(_state.toString());
            data.setBytes(_mover.getBytesTransferred());
            data.setTimeInSeconds(_mover.getTransferTime());
            data.setStartTime(_startTime);
            data.setSubmitTime(_submitTime);
            data.setLastModified(_mover.getLastTransferred());
            data.setMoverId(_id);
            return data;
        }

        public synchronized boolean queue() {
            if (_state == NEW) {
                _state = QUEUED;
                return true;
            }
            return false;
        }

        public synchronized void transfer(CompletionHandler<Void, Void> completionHandler) {
            try {
                if (_state != QUEUED) {
                    completionHandler.failed(new InterruptedException("Transfer cancelled"), null);
                }
                _state = RUNNING;
                _startTime = System.currentTimeMillis();
                _cancellable = _mover.execute(completionHandler);
            } catch (RuntimeException e) {
                completionHandler.failed(e, null);
            }
        }

        public synchronized void kill(@Nullable String explanation) {
            if (_state == CANCELED || _state == DONE) {
                return;
            }

            if (_cancellable != null) {
                String why = explanation == null
                      ? "Active transfer cancelled"
                      : ("Active transfer cancelled: " + explanation);
                _cancellable.cancel(why);
            } else {
                String why = explanation == null
                      ? "Queued transfer cancelled"
                      : ("Queued transfer cancelled: " + explanation);
                _mover.setTransferStatus(CacheException.DEFAULT_ERROR_CODE, why);
            }
            _state = CANCELED;
        }

        public synchronized void done() {
            _state = DONE;
        }
    }
}
