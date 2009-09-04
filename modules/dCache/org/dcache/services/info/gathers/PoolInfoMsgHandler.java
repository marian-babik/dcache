package org.dcache.services.info.gathers;

import org.apache.log4j.Logger;
import org.dcache.services.info.base.BooleanStateValue;
import org.dcache.services.info.base.IntegerStateValue;
import org.dcache.services.info.base.StatePath;
import org.dcache.services.info.base.StateUpdate;
import org.dcache.services.info.base.StateUpdateManager;

public class PoolInfoMsgHandler extends CellMessageHandlerSkel {
	
	private static Logger _log = Logger.getLogger( PoolInfoMsgHandler.class);

	private static final StatePath POOLS_PATH = new StatePath( "pools");

	public PoolInfoMsgHandler(StateUpdateManager sum) {
		super(sum);
	}

	@Override
    public void process(Object msgPayload, long metricLifetime) {

		StateUpdate update = new StateUpdate();
			
		if( !msgPayload.getClass().isArray()) {
			_log.error( "received a message that isn't an array");
			return;
		}
			
		Object[] array = (Object []) msgPayload;
		
		if( array.length != 6) {
			_log.error( "unexpected array size: "+array.length);
			return;
		}

		Boolean isEnabled = (Boolean) array[3];
		Long heartBeat = (Long) array[4];
		Boolean isReadOnly = (Boolean) array [5];
		
		String poolName = array[0].toString();

		StatePath thisPoolPath = POOLS_PATH.newChild( poolName);
			
		addItems( update, thisPoolPath.newChild("poolgroups"), (Object []) array [1], metricLifetime); 
		addItems( update, thisPoolPath.newChild("links"), (Object []) array [2], metricLifetime); 

		update.appendUpdate( thisPoolPath.newChild("read-only"),
				new BooleanStateValue( isReadOnly.booleanValue(), metricLifetime));

		update.appendUpdate( thisPoolPath.newChild("enabled"),
				new BooleanStateValue( isEnabled.booleanValue(), metricLifetime));

		update.appendUpdate( thisPoolPath.newChild("last-heartbeat"),
				new IntegerStateValue( heartBeat.intValue(), metricLifetime));

		applyUpdates( update);
	}
		
}
