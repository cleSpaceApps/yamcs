package org.yamcs.alarms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.protobuf.Pvalue.MonitoringResult;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Parameter;

public class AlarmServerTest {
    Parameter p1 = new Parameter("p1");
    Parameter p2 = new Parameter("p2");
    AlarmServer<Parameter, ParameterValue> alarmServer;
    ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    
    
    @BeforeClass
    static public void setupBeforeClass() {
        EventProducerFactory.setMockup(true);
        TimeEncoding.setUp();
    }

    ParameterValue getParameterValue(Parameter p, MonitoringResult mr) {
        ParameterValue pv = new ParameterValue(p);
        pv.setMonitoringResult(mr);

        return pv;
    }
    
    @Before
    public void before() {
        alarmServer = new AlarmServer<>("toto", timer);
    }

    @Test
    public void test1() {
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);
        ParameterValue pv1_0 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_0, 1);

        ActiveAlarm<ParameterValue> aa = l.triggered.remove();
        assertEquals(pv1_0, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        ParameterValue pv1_1 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_1, 1);
        assertTrue(l.triggered.isEmpty());
        aa = l.valueUpdates.remove();
        assertEquals(pv1_1, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        ParameterValue pv1_2 = getParameterValue(p1, MonitoringResult.CRITICAL);
        alarmServer.update(pv1_2, 1);
        assertTrue(l.triggered.isEmpty());
        assertFalse(l.valueUpdates.isEmpty());
        aa = l.severityIncreased.remove();
        assertEquals(pv1_2, aa.currentValue);
        assertEquals(pv1_2, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        long ackTime = 123L;
        alarmServer.acknowledge(aa, "test1", ackTime, "bla");
        assertTrue(l.cleared.isEmpty());

        assertEquals(1, l.acknowledged.size());
        assertEquals(aa, l.acknowledged.remove());

        ParameterValue pv1_3 = getParameterValue(p1, MonitoringResult.IN_LIMITS);
        alarmServer.update(pv1_3, 1);
        aa = l.cleared.remove();
        assertEquals(pv1_3, aa.currentValue);
        assertEquals(pv1_2, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);
        assertEquals("test1", aa.usernameThatAcknowledged);
        assertEquals(ackTime, aa.acknowledgeTime);
        assertEquals("bla", aa.getAckMessage());
    }

    @Test
    public void test2() {
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);
        ParameterValue pv1_0 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_0, 1);

        ActiveAlarm<ParameterValue> aa = l.triggered.remove();
        assertEquals(pv1_0, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        ParameterValue pv1_1 = getParameterValue(p1, MonitoringResult.IN_LIMITS);
        alarmServer.update(pv1_1, 1);
        assertTrue(l.cleared.isEmpty());
        aa = l.valueUpdates.remove();
        assertEquals(pv1_1, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        assertEquals(1, l.rtn.size());
        
        long ackTime = 123L;
        alarmServer.acknowledge(aa, "test2", ackTime, "bla");


        aa = l.cleared.remove();
        assertEquals(pv1_1, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);
        assertEquals("test2", aa.usernameThatAcknowledged);
        assertEquals(ackTime, aa.acknowledgeTime);
        assertEquals("bla", aa.getAckMessage());
    }

    
    @Test
    public void testShelve() throws InterruptedException {
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);
        ParameterValue pv1_0 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_0, 1);

        ActiveAlarm<ParameterValue> aa = l.triggered.remove();
        assertEquals(pv1_0, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        alarmServer.shelve(aa, "cucu", "looking at it later", 500);
        assertEquals(1, l.shelved.size());
        assertEquals(aa, l.shelved.remove());
        
        Thread.sleep(1000);
        assertEquals(1, l.unshelved.size());
        assertEquals(aa, l.unshelved.remove());
        assertFalse(aa.isShelved());
    }

    @Test
    public void testAutoAck() {
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);
        ParameterValue pv1_0 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_0, 1, true, false);

        ActiveAlarm<ParameterValue> aa = l.triggered.remove();
        assertEquals(pv1_0, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        ParameterValue pv1_1 = getParameterValue(p1, MonitoringResult.IN_LIMITS);
        alarmServer.update(pv1_1, 1, true, false);

        aa = l.cleared.remove();
        assertEquals(pv1_1, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);
    }

    @Test
    public void testGetActiveAlarmWithNoAlarm() throws AlarmSequenceException {
       
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);

        assertNull(alarmServer.getActiveAlarm(p1, 1));
    }

    @Test(expected = AlarmSequenceException.class)
    public void testGetActiveAlarmWithInvalidId() throws AlarmSequenceException {
        MyListener l = new MyListener();
        alarmServer.addAlarmListener(l);
        ParameterValue pv1_0 = getParameterValue(p1, MonitoringResult.WARNING);
        alarmServer.update(pv1_0, 1, true, false);

        ActiveAlarm<ParameterValue> aa = l.triggered.remove();
        assertEquals(pv1_0, aa.currentValue);
        assertEquals(pv1_0, aa.mostSevereValue);
        assertEquals(pv1_0, aa.triggerValue);

        alarmServer.getActiveAlarm(p1, 123 /* wrong id */);
    }

    @Test
    public void testMoreSevere() {
        assertTrue(AlarmServer.moreSevere(MonitoringResult.CRITICAL, MonitoringResult.WARNING));
        assertFalse(AlarmServer.moreSevere(MonitoringResult.WARNING, MonitoringResult.CRITICAL));
        assertFalse(AlarmServer.moreSevere(MonitoringResult.CRITICAL, MonitoringResult.CRITICAL));
    }

    class MyListener implements AlarmListener<ParameterValue> {
        Queue<ActiveAlarm<ParameterValue>> valueUpdates = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> severityIncreased = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> triggered = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> acknowledged = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> cleared = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> rtn = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> shelved = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> unshelved = new LinkedList<>();
        Queue<ActiveAlarm<ParameterValue>> reset = new LinkedList<>();

        @Override
        public void notifyValueUpdate(ActiveAlarm<ParameterValue> activeAlarm) {
            valueUpdates.add(activeAlarm);
        }

        @Override
        public void notifySeverityIncrease(ActiveAlarm<ParameterValue> activeAlarm) {
            severityIncreased.add(activeAlarm);
        }

        @Override
        public void notifyUpdate(AlarmNotificationType notificationType, ActiveAlarm<ParameterValue> activeAlarm) {
            switch (notificationType) {
            case TRIGGERED:
                triggered.add(activeAlarm);
                break;
            case ACKNOWLEDGED:
                acknowledged.add(activeAlarm);
                break;
            case CLEARED:
                cleared.add(activeAlarm);
                break;
            case RTN:
                rtn.add(activeAlarm);
                break;
            case RESET:
                rtn.add(activeAlarm);
                break;
            case SHELVED:
                shelved.add(activeAlarm);
                break;
            case UNSHELVED:
                unshelved.add(activeAlarm);
                break;
            default:
                throw new IllegalStateException();
            }
        }
    }
}
