package org.yamcs.commanding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.GuardedBy;
import org.yamcs.Processor;
import org.yamcs.ThreadSafe;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.logging.Log;
import org.yamcs.parameter.LastValueCache;
import org.yamcs.parameter.ParameterConsumer;
import org.yamcs.parameter.ParameterRequestManager;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.ParameterValueList;
import org.yamcs.parameter.SystemParametersCollector;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.protobuf.Commanding.QueueState;
import org.yamcs.security.ObjectPrivilege;
import org.yamcs.security.ObjectPrivilegeType;
import org.yamcs.security.User;
import org.yamcs.xtce.CriteriaEvaluator;
import org.yamcs.xtce.MatchCriteria;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.TransmissionConstraint;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.CriteriaEvaluatorImpl;

import com.google.common.util.concurrent.AbstractService;

/**
 * @author nm Implements the management of the control queues for one processor: - for each command that is sent, based
 *         on the sender it finds the queue where the command should go - depending on the queue state the command can
 *         be immediately sent, stored in the queue or rejected - when the command is immediately sent or rejected, the
 *         command queue monitor is not notified - if the command has transmissionConstraints with timeout &gt; 0, the
 *         command can sit in the queue even if the queue is not blocked
 *
 *         Note: the update of the command monitors is done in the same thread. That means that if the connection to one
 *         of the monitors is lost, there may be a delay of a few seconds. As the monitoring clients will be priviledged
 *         users most likely connected in the same LAN, I don't consider this to be an issue.
 */
@ThreadSafe
public class CommandQueueManager extends AbstractService implements ParameterConsumer, SystemParametersProducer {
    @GuardedBy("this")
    private HashMap<String, CommandQueue> queues = new HashMap<>();
    CommandReleaser commandReleaser;
    CommandHistoryPublisher commandHistoryPublisher;
    CommandingManager commandingManager;
    ConcurrentLinkedQueue<CommandQueueListener> monitoringClients = new ConcurrentLinkedQueue<>();
    private final Log log;

    private Set<TransmissionConstraintChecker> pendingTcCheckers = new HashSet<>();

    private final String instance;
    private final String processorName;

    ParameterValueList pvList = new ParameterValueList();

    Processor yproc;
    int paramSubscriptionRequestId = -1;

    private final ScheduledThreadPoolExecutor timer;
    private final LastValueCache lastValueCache;

    /**
     * Constructs a Command Queue Manager.
     * 
     * @param commandingManager
     *
     * @throws ConfigurationException
     *             in case there is an error in the configuration file. Note: if the configuration file doesn't exist,
     *             this exception is not thrown.
     */
    public CommandQueueManager(CommandingManager commandingManager) throws ConfigurationException {
        this.commandingManager = commandingManager;

        yproc = commandingManager.getChannel();
        log = new Log(this.getClass(), yproc.getInstance());
        log.setContext(yproc.getName());
        this.commandHistoryPublisher = yproc.getCommandHistoryPublisher();
        this.commandReleaser = yproc.getCommandReleaser();
        this.instance = yproc.getInstance();
        this.processorName = yproc.getName();
        this.timer = yproc.getTimer();
        this.lastValueCache = yproc.getLastValueCache();

        CommandQueue cq = new CommandQueue(yproc, "default", QueueState.BLOCKED);
        queues.put("default", cq);

        if (YConfiguration.isDefined("command-queue")) {
            YConfiguration config = YConfiguration.getConfiguration("command-queue");
            for (String queueName : config.getKeys()) {
                if (queueName.equals("queueNames")) {
                    log.warn("DEPRECATION: Remove key 'queueNames' from command-queue.yaml. "
                            + "This property is no longer read.");
                    continue;
                }
                if (!queues.containsKey(queueName)) {
                    queues.put(queueName, new CommandQueue(yproc, queueName, QueueState.BLOCKED));
                }
                CommandQueue q = queues.get(queueName);
                String state = config.getSubString(queueName, "state");
                q.state = CommandQueueManager.stringToQueueState(state);
                q.defaultState = q.state;
                if (config.containsKey(queueName, "stateExpirationTimeS")) {
                    q.stateExpirationTimeS = config.getInt(queueName, "stateExpirationTimeS");
                }
                if (config.containsKey(queueName, "significances")) {
                    q.significances = config.getSubList(queueName, "significances");
                }
            }
        }

        // schedule timer update to client
        timer.scheduleAtFixedRate(() -> {
            for (CommandQueue q : queues.values()) {
                if (q.state == q.defaultState && q.stateExpirationRemainingS > 0) {
                    q.stateExpirationRemainingS = 0;
                    notifyUpdateQueue(q);
                } else if (q.stateExpirationRemainingS >= 0) {
                    log.trace("notifying update queue with new remaining seconds: {}", q.stateExpirationRemainingS);
                    q.stateExpirationRemainingS--;
                    notifyUpdateQueue(q);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * called at processor startup, subscribe all parameters required for checking command constraints
     */
    @Override
    public void doStart() {
        XtceDb xtcedb = yproc.getXtceDb();
        Set<Parameter> paramsToSubscribe = new HashSet<>();
        for (MetaCommand mc : xtcedb.getMetaCommands()) {
            if (mc.hasTransmissionConstraints()) {
                List<TransmissionConstraint> tcList = mc.getTransmissionConstraintList();
                for (TransmissionConstraint tc : tcList) {
                    paramsToSubscribe.addAll(tc.getMatchCriteria().getDependentParameters());
                }
            }
        }
        if (!paramsToSubscribe.isEmpty()) {
            ParameterRequestManager prm = yproc.getParameterRequestManager();
            paramSubscriptionRequestId = prm.addRequest(new ArrayList<>(paramsToSubscribe), this);
        } else {
            log.debug("No parameter required for post transmission contraint check");
        }

        SystemParametersCollector sysParamCollector = SystemParametersCollector.getInstance(yproc.getInstance());
        if (sysParamCollector != null) {
            for (CommandQueue cq : queues.values()) {
                cq.setupSysParameters();
            }
            sysParamCollector.registerProducer(this);
        }
        notifyStarted();
    }

    @Override
    public void doStop() {
        SystemParametersCollector sysParamCollector = SystemParametersCollector.getInstance(yproc.getInstance());
        if (sysParamCollector != null) {
            sysParamCollector.unregisterProducer(this);
        }
        if (paramSubscriptionRequestId != -1) {
            ParameterRequestManager prm = yproc.getParameterRequestManager();
            prm.removeRequest(paramSubscriptionRequestId);
        }
        notifyStopped();
    }

    private static QueueState stringToQueueState(String state) throws ConfigurationException {
        if ("enabled".equalsIgnoreCase(state)) {
            return QueueState.ENABLED;
        }
        if ("disabled".equalsIgnoreCase(state)) {
            return QueueState.DISABLED;
        }
        if ("blocked".equalsIgnoreCase(state)) {
            return QueueState.BLOCKED;
        }
        throw new ConfigurationException(
                "'" + state + "' is not a valid queue state. Use one of enabled, disabled or blocked");
    }

    public Collection<CommandQueue> getQueues() {
        return queues.values();
    }

    public CommandQueue getQueue(String name) {
        return queues.get(name);
    }

    /**
     * Called from the CommandingImpl to add a command to the queue First the command is added to the command history
     * Depending on the status of the queue, the command is rejected by setting the CommandFailed in the command history
     * added to the queue or directly sent using the command releaser
     * 
     * @param user
     * @param pc
     * @return the queue the command was added to
     */
    public synchronized CommandQueue addCommand(User user, PreparedCommand pc) {
        commandHistoryPublisher.addCommand(pc);

        CommandQueue q = getQueue(user, pc);
        q.add(pc);
        notifyAdded(q, pc);

        if (q.state == QueueState.DISABLED) {
            q.remove(pc, false);
            failedCommand(q, pc, "Commanding Queue disabled", true);
            notifyUpdateQueue(q);
        } else if (q.state == QueueState.BLOCKED) {
            // notifyAdded(q, pc);
        } else if (q.state == QueueState.ENABLED) {
            if (pc.getMetaCommand().hasTransmissionConstraints()) {
                startTransmissionConstraintChecker(q, pc);
            } else {
                addToCommandHistory(pc.getCommandId(), CommandHistoryPublisher.TransmissionContraints_KEY, "NA");
                q.remove(pc, true);
                releaseCommand(q, pc, true, false);
            }
        }

        return q;
    }

    private void startTransmissionConstraintChecker(CommandQueue q, PreparedCommand pc) {
        TransmissionConstraintChecker constraintChecker = new TransmissionConstraintChecker(q, pc);
        pendingTcCheckers.add(constraintChecker);

        scheduleImmediateCheck(constraintChecker);
    }

    private void onTransmissionContraintCheckPending(TransmissionConstraintChecker tcChecker) {
        addToCommandHistory(tcChecker.pc.getCommandId(), CommandHistoryPublisher.TransmissionContraints_KEY, "PENDING");
    }

    private void onTransmissionContraintCheckFinished(TransmissionConstraintChecker tcChecker) {
        PreparedCommand pc = tcChecker.pc;
        CommandQueue q = tcChecker.queue;
        TCStatus status = tcChecker.aggregateStatus;
        log.info("transmission constraint finished for {} status: {}", pc.getCmdName(), status);

        pendingTcCheckers.remove(tcChecker);

        if (q.getState() == QueueState.BLOCKED) {
            log.debug("Command queue for command {} is blocked, leaving command in the queue", pc);
            return;
        }
        if (q.getState() == QueueState.DISABLED) {
            log.debug("Command queue for command {} is disabled, dropping command", pc);
            q.remove(pc, false);
        }

        if (!q.remove(pc, true)) {
            return; // command has been removed in the meanwhile
        }
        if (status == TCStatus.OK) {
            addToCommandHistory(pc.getCommandId(), CommandHistoryPublisher.TransmissionContraints_KEY, "OK");
            releaseCommand(q, pc, true, false);
        } else if (status == TCStatus.TIMED_OUT) {
            addToCommandHistory(pc.getCommandId(), CommandHistoryPublisher.TransmissionContraints_KEY, "NOK");
            failedCommand(q, pc, "Transmission constraints check failed", true);
        }
    }

    // Notify the monitoring clients
    private void notifyAdded(CommandQueue q, PreparedCommand pc) {
        for (CommandQueueListener m : monitoringClients) {
            try {
                m.commandAdded(q, pc);
            } catch (Exception e) {
                log.warn("got exception when notifying a monitor, removing it from the list", e);
                monitoringClients.remove(m);
            }
        }
        notifyUpdateQueue(q);
    }

    // Notify the monitoring clients
    private void notifySent(CommandQueue q, PreparedCommand pc) {
        for (CommandQueueListener m : monitoringClients) {
            try {
                m.commandSent(q, pc);
            } catch (Exception e) {
                log.warn("got exception when notifying a monitor, removing it from the list", e);
                monitoringClients.remove(m);
            }
        }
        notifyUpdateQueue(q);
    }

    private void notifyUpdateQueue(CommandQueue q) {
        for (CommandQueueListener m : monitoringClients) {
            try {
                m.updateQueue(q);
            } catch (Exception e) {
                log.warn("got exception when notifying a monitor, removing it from the list", e);
                monitoringClients.remove(m);
            }
        }
    }

    public void addToCommandHistory(CommandId commandId, String key, String value) {
        commandHistoryPublisher.publish(commandId, key, value);
    }

    /**
     * send a negative ack for a command.
     * 
     * @param pc
     *            the prepared command for which the negative ack is sent
     * @param notify
     *            notify or not the monitoring clients.
     */
    private void failedCommand(CommandQueue cq, PreparedCommand pc, String reason, boolean notify) {
        addToCommandHistory(pc.getCommandId(), CommandHistoryPublisher.CommandFailed_KEY, reason);
        addToCommandHistory(pc.getCommandId(), CommandHistoryPublisher.CommandComplete_KEY, "NOK");
        // Notify the monitoring clients
        if (notify) {
            for (CommandQueueListener m : monitoringClients) {
                try {
                    m.commandRejected(cq, pc);
                } catch (Exception e) {
                    log.warn("got exception when notifying a monitor, removing it from the list", e);
                    monitoringClients.remove(m);
                }
            }
        }
    }

    private void releaseCommand(CommandQueue q, PreparedCommand pc, boolean notify, boolean rebuild) {
        // start the verifiers
        MetaCommand mc = pc.getMetaCommand();
        if (mc.hasCommandVerifiers()) {
            log.debug("Starting command verification for {}", pc);
            CommandVerificationHandler cvh = new CommandVerificationHandler(yproc, pc);
            cvh.start();
        }

        commandReleaser.releaseCommand(pc);
        // Notify the monitoring clients
        if (notify) {
            notifySent(q, pc);
        }
    }

    /**
     * @param user
     * @param pc
     * @return the queue where the command should be placed.
     */
    public CommandQueue getQueue(User user, PreparedCommand pc) {
        Set<ObjectPrivilege> queuePrivileges = user.getObjectPrivileges(ObjectPrivilegeType.InsertCommandQueue);
        for (CommandQueue cq : queues.values()) {
            for (ObjectPrivilege privilege : queuePrivileges) {
                if (privilege.getObject().equals(cq.getName())) {
                    if (cq.significances == null
                            || (pc.getMetaCommand().getDefaultSignificance() == null
                                    && cq.significances.contains("none"))
                            || (pc.getMetaCommand().getDefaultSignificance() != null && cq.significances.contains(
                                    pc.getMetaCommand().getDefaultSignificance().getConsequenceLevel().name()))) {
                        // return first queue that the user can insert into and that matches the significance of the
                        // command
                        return cq;
                    }
                }
            }
        }

        return queues.get("default");
    }

    /**
     * Called by external clients to remove a command from the queue
     * 
     * @param commandId
     * @param username
     *            - the username rejecting the command
     * @return the command removed from the queeu
     */
    public synchronized PreparedCommand rejectCommand(CommandId commandId, String username) {
        log.info("called to remove command: {}", commandId);
        PreparedCommand pc = null;
        CommandQueue queue = null;
        for (CommandQueue q : queues.values()) {
            for (PreparedCommand c : q.getCommands()) {
                if (c.getCommandId().equals(commandId)) {
                    pc = c;
                    queue = q;
                    break;
                }
            }
        }
        if (pc != null) {
            queue.remove(pc, false);
            failedCommand(queue, pc, "Rejected by " + username, true);
            notifyUpdateQueue(queue);
        } else {
            log.warn("command not found in any queue");
        }
        return pc;
    }

    // Used by REST API as a simpler identifier
    public synchronized PreparedCommand rejectCommand(UUID uuid, String username) {
        for (CommandQueue q : queues.values()) {
            for (PreparedCommand pc : q.getCommands()) {
                if (pc.getUUID().equals(uuid)) {
                    return rejectCommand(pc.getCommandId(), username);
                }
            }
        }
        log.warn("no prepared command found for uuid {}", uuid);
        return null;
    }

    /**
     * Called from external client to release a command from the queue
     * 
     * @param commandId
     * @param rebuild
     *            - if to rebuild the command binary from the source
     * @return the prepared command sent
     */
    public synchronized PreparedCommand sendCommand(CommandId commandId, boolean rebuild) {
        PreparedCommand command = null;
        CommandQueue queue = null;
        for (CommandQueue q : queues.values()) {
            for (PreparedCommand pc : q.getCommands()) {
                if (pc.getCommandId().equals(commandId)) {
                    command = pc;
                    queue = q;
                    break;
                }
            }
        }
        if (command != null) {
            queue.remove(command, true);
            releaseCommand(queue, command, true, rebuild);
        }
        return command;
    }

    // Used by REST API as a simpler identifier
    public synchronized PreparedCommand sendCommand(UUID uuid, boolean rebuild) {
        for (CommandQueue q : queues.values()) {
            for (PreparedCommand pc : q.getCommands()) {
                if (pc.getUUID().equals(uuid)) {
                    return sendCommand(pc.getCommandId(), rebuild);
                }
            }
        }
        log.warn("no prepared command found for uuid {}", uuid);
        return null;
    }

    /**
     * Called from external clients to change the state of the queue
     * 
     * @param queueName
     *            the queue whose state has to be set
     * @param newState
     *            the new state of the queue
     * @return the queue whose state has been changed or null if no queue by the name exists
     */
    public synchronized CommandQueue setQueueState(String queueName, QueueState newState/*, boolean rebuild*/) {
        CommandQueue queue = null;
        for (CommandQueue q : queues.values()) {
            if (q.name.equals(queueName)) {
                queue = q;
                break;
            }
        }
        if (queue == null) {
            return null;
        }

        if (queue.state == newState) {
            if (queue.stateExpirationJob != null && newState != queue.defaultState) {
                log.debug("same state selected, resetting expiration time");
                // reset state expiration date
                scheduleStateExpiration(queue);
                // Notify the monitoring clients
                notifyUpdateQueue(queue);
            }
            return queue;
        }

        queue.state = newState;
        if (queue.state == QueueState.ENABLED) {
            for (PreparedCommand pc : queue.getCommands()) {
                if (pc.getMetaCommand().hasTransmissionConstraints()) {
                    startTransmissionConstraintChecker(queue, pc);
                } else {
                    releaseCommand(queue, pc, true, false);
                }
            }
            queue.clear(true);
        }
        if (queue.state == QueueState.DISABLED) {
            for (PreparedCommand pc : queue.getCommands()) {
                failedCommand(queue, pc, "Queue disabled", true);
            }
            queue.clear(false);
        }

        if (queue.stateExpirationTimeS > 0 && newState != queue.defaultState) {
            log.info("scheduling expiration state for new state {} for queue {}", newState, queue.name);
            scheduleStateExpiration(queue);
        }

        // Notify the monitoring clients
        notifyUpdateQueue(queue);
        return queue;
    }

    private void scheduleStateExpiration(final CommandQueue queue) {

        if (queue.stateExpirationJob != null) {
            log.debug("expiration job existing, removing...");
            queue.stateExpirationJob.cancel(false);
            queue.stateExpirationJob = null;
        }
        Runnable r = () -> {
            log.info("executing epiration state, reverting to {}", queue.defaultState);
            setQueueState(queue.name, queue.defaultState);
            queue.stateExpirationJob = null;
        };

        log.info("sceduling expiration time in {}", queue.stateExpirationTimeS);
        queue.stateExpirationRemainingS = queue.stateExpirationTimeS;
        queue.stateExpirationJob = timer.schedule(r, queue.stateExpirationTimeS, TimeUnit.SECONDS);
    }

    /**
     * Called from a queue monitor to register itself in order to be notified when new commands are added/removed from
     * the queue.
     * 
     * @param cqm
     *            the callback which will be called with updates
     */
    public void registerListener(CommandQueueListener cqm) {
        monitoringClients.add(cqm);
    }

    public boolean removeListener(CommandQueueListener cqm) {
        return monitoringClients.remove(cqm);
    }

    public String getInstance() {
        return instance;
    }

    public String getChannelName() {
        return processorName;
    }

    private void doUpdateItems(final List<ParameterValue> items) {
        pvList.addAll(items);
        // remove old parameter values
        for (ParameterValue pv : items) {
            Parameter p = pv.getParameter();
            int c = pvList.count(p);
            for (int i = 0; i < c - 1; i++) {
                pvList.removeFirst(p);
            }
        }
        for (TransmissionConstraintChecker tcc : pendingTcCheckers) {
            scheduleImmediateCheck(tcc);
        }
    }

    private void scheduleImmediateCheck(final TransmissionConstraintChecker tcc) {
        timer.execute(tcc::check);
    }

    private void scheduleCheck(final TransmissionConstraintChecker tcc, long millisec) {
        timer.schedule(tcc::check, millisec, TimeUnit.MILLISECONDS);
    }

    @Override
    public void updateItems(int subscriptionId, final List<ParameterValue> items) {
        timer.execute(() -> doUpdateItems(items));
    }

    enum TCStatus {
        INIT, PENDING, OK, TIMED_OUT
    }

    class TransmissionConstraintChecker {
        List<TransmissionConstraintStatus> tcsList = new ArrayList<>();
        final PreparedCommand pc;
        final CommandQueue queue;
        TCStatus aggregateStatus = TCStatus.INIT;

        public TransmissionConstraintChecker(CommandQueue queue, PreparedCommand pc) {
            this.pc = pc;
            this.queue = queue;
            List<TransmissionConstraint> constraints = pc.getMetaCommand().getTransmissionConstraintList();
            for (TransmissionConstraint tc : constraints) {
                TransmissionConstraintStatus tcs = new TransmissionConstraintStatus(tc);
                tcsList.add(tcs);
            }
        }

        public void check() {
            long now = System.currentTimeMillis();
            if (aggregateStatus == TCStatus.INIT) {
                // make sure that if timeout=0, the first check will not appear to be too late
                for (TransmissionConstraintStatus tcs : tcsList) {
                    tcs.expirationTime = now + tcs.constraint.getTimeout();
                }
                aggregateStatus = TCStatus.PENDING;
            }

            if (aggregateStatus != TCStatus.PENDING) {
                return;
            }

            CriteriaEvaluator condEvaluator = new CriteriaEvaluatorImpl(pvList, lastValueCache);

            aggregateStatus = TCStatus.OK;
            long scheduleNextCheck = Long.MAX_VALUE;

            for (TransmissionConstraintStatus tcs : tcsList) {
                if (tcs.status == TCStatus.OK) {
                    continue;
                }

                if (tcs.status == TCStatus.PENDING) {
                    long timeRemaining = tcs.expirationTime - now;
                    if (timeRemaining < 0) {
                        tcs.status = TCStatus.TIMED_OUT;
                        aggregateStatus = TCStatus.TIMED_OUT;
                        break;
                    } else {
                        MatchCriteria mc = tcs.constraint.getMatchCriteria();
                        try {
                            if (!mc.isMet(condEvaluator)) {
                                if (timeRemaining > 0) {
                                    aggregateStatus = TCStatus.PENDING;
                                    if (timeRemaining < scheduleNextCheck) {
                                        scheduleNextCheck = timeRemaining;
                                    }
                                } else {
                                    aggregateStatus = TCStatus.TIMED_OUT;
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                }
            }
            if (aggregateStatus == TCStatus.PENDING) {
                onTransmissionContraintCheckPending(this);
                scheduleCheck(this, scheduleNextCheck);
            } else {
                onTransmissionContraintCheckFinished(this);
            }
        }
    }

    static class TransmissionConstraintStatus {
        TransmissionConstraint constraint;
        TCStatus status;
        long expirationTime;

        public TransmissionConstraintStatus(TransmissionConstraint tc) {
            this.constraint = tc;
            status = TCStatus.PENDING;
        }
    }

    @Override
    public Collection<ParameterValue> getSystemParameters() {
        List<ParameterValue> pvlist = new ArrayList<>();
        long time = yproc.getCurrentTime();
        for (CommandQueue cq : queues.values()) {
            cq.fillInSystemParameters(pvlist, time);
        }
        return pvlist;
    }
}
