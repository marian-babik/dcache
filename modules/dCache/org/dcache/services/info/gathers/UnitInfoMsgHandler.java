package org.dcache.services.info.gathers;

import org.apache.log4j.Logger;
import org.dcache.services.info.base.StatePath;
import org.dcache.services.info.base.StateUpdate;
import org.dcache.services.info.base.StateUpdateManager;
import org.dcache.services.info.base.StringStateValue;


/**
 * Process incoming Object array (*shudder*) and update state.
 *  
 * @author Paul Millar <paul.millar@desy.de>
 */
public class UnitInfoMsgHandler extends CellMessageHandlerSkel {

	private static Logger _log = Logger.getLogger( UnitInfoMsgHandler.class);

	private static final StatePath UNITS_PATH = new StatePath( "units");
	
	public UnitInfoMsgHandler(StateUpdateManager sum) {
		super(sum);
	}

	@Override
    public void process(Object msgPayload, long metricLifetime) {

		if( !msgPayload.getClass().isArray()) {
			_log.error( "unexpected received non-array payload");
			return;			
		}
		
		Object array[] = (Object []) msgPayload;
		
		if( array.length != 3) {
			_log.error( "Unexpected array size: "+array.length);
			return;
		}

		/**
		 * array[0] = name
		 * array[1] = type
		 * array[2] = list of unitgroups.
		 */
		
		String unitName = array[0].toString();
		String unitType = array[1].toString();
		
		StatePath thisUnitPath = UNITS_PATH.newChild( unitName);

		StateUpdate update = new StateUpdate();
		
		update.appendUpdate( thisUnitPath.newChild("type"),
					new StringStateValue( unitType, metricLifetime));
		
		addItems( update, thisUnitPath.newChild("unitgroups"), (Object []) array [2], metricLifetime);

		applyUpdates( update);
	}

}
