package org.yamcs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

import org.yamcs.alarms.EventAlarmServer;
import org.yamcs.cmdhistory.CommandHistoryProvider;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryRequestManager;
import org.yamcs.cmdhistory.StreamCommandHistoryProvider;
import org.yamcs.cmdhistory.StreamCommandHistoryPublisher;
import org.yamcs.commanding.CommandReleaser;
import org.yamcs.commanding.CommandingManager;
import org.yamcs.container.ContainerRequestManager;
import org.yamcs.logging.Log;
import org.yamcs.parameter.LastValueCache;
import org.yamcs.parameter.ParameterCache;
import org.yamcs.parameter.ParameterCacheConfig;
import org.yamcs.parameter.ParameterRequestManager;
import org.yamcs.protobuf.ServiceState;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplaySpeed;
import org.yamcs.protobuf.Yamcs.ReplaySpeed.ReplaySpeedType;
import org.yamcs.protobuf.Yamcs.ReplayStatus.ReplayState;
import org.yamcs.tctm.ArchiveTmPacketProvider;
import org.yamcs.tctm.StreamParameterSender;
import org.yamcs.time.TimeService;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.ProcessorData;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.xtceproc.XtceTmProcessor;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;

/**
 * 
 * This class helps keeping track of the different objects used in a Yamcs Processor - i.e. all the objects required to
 * have a TM/TC processing chain (either realtime or playback).
 *
 *
 */
public class Processor extends AbstractService {
    private static final String CONFIG_KEY_TM_PROCESSOR = "tmProcessor";
    private static final String CONFIG_KEY_PARAMETER_CACHE = "parameterCache";
    private static final String CONFIG_KEY_ALARM = "alarm";
    private static final String CONFIG_KEY_GENERATE_EVENTS = "generateEvents";
    private static final String CONFIG_KEY_SUBSCRIBE_ALL = "subscribeAll";
    private static final String CONFIG_KEY_RECORD_INITIAL_VALUES = "recordInitialValues";
    private static final String CONFIG_KEY_RECORD_LOCAL_VALUES = "recordLocalValues";

    public static final String PROC_PARAMETERS_STREAM = "proc_param";

    // handles subscriptions to parameters
    private ParameterRequestManager parameterRequestManager;
    // handles subscriptions to containers
    private ContainerRequestManager containerRequestManager;
    // handles subscriptions to command history
    private CommandHistoryRequestManager commandHistoryRequestManager;

    // handles command building and queues
    private CommandingManager commandingManager;

    // publishes events to command history
    private CommandHistoryPublisher commandHistoryPublisher;

    // these are services defined in the processor.yaml.
    // They have to register themselves to the processor in their init method
    private TmPacketProvider tmPacketProvider;
    private CommandHistoryProvider commandHistoryProvider;
    private CommandReleaser commandReleaser;

    private static Map<String, Processor> instances = Collections.synchronizedMap(new LinkedHashMap<>());

    private XtceDb xtcedb;

    private String name;
    private String type;
    private final String yamcsInstance;

    private boolean checkParameterAlarms = true;
    private boolean parameterAlarmServerEnabled = false;
    private boolean eventAlarmServerEnabled = false;

    private String creator = "system";
    private boolean persistent = false;

    ParameterCacheConfig parameterCacheConfig = new ParameterCacheConfig(false, false, 0, 0);

    final Log log;
    static Set<ProcessorListener> listeners = new CopyOnWriteArraySet<>(); // send notifications for added and removed
    // processors to this

    private boolean quitting;
    // a synchronous processor waits for all the clients to deliver tm packets and parameters
    private boolean synchronous = false;

    // if the PRM should subscribe to all parameters at startup
    boolean subscribeAll = false;
    XtceTmProcessor tmProcessor;

    // unless very good performance reasons, we should try to serialize all the processing in this thread
    private final ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

    TimeService timeService;

    ProcessorData processorData;
    @GuardedBy("this")
    HashSet<ConnectedClient> connectedClients = new HashSet<>();
    List<ServiceWithConfig> serviceList;
    boolean recordInitialValues;
    boolean recordLocalValues;
    StreamParameterSender streamParameterSender;
    EventAlarmServer eventAlarmServer;

    public Processor(String yamcsInstance, String name, String type, String creator) throws ProcessorException {
        if ((name == null) || "".equals(name)) {
            throw new ProcessorException("The processor name must not be empty");
        }
        this.yamcsInstance = yamcsInstance;
        this.name = name;
        this.creator = creator;
        this.type = type;
        log = new Log(Processor.class, yamcsInstance);
        log.info("Creating new processor '{}' of type '{}'", name, type);
        log.setContext(name);
    }

    /**
     * If recording to the archive initial values and local parameters is enabled, this class can be used to do it.
     * 
     * Otherwise it will return null.
     * 
     * @return the stream parameter sender that can be used to send data on the {@link #PROC_PARAMETERS_STREAM} stream
     *         to be recorded in the archive
     */
    public StreamParameterSender getStreamParameterSender() {
        return streamParameterSender;
    }

    /**
     * 
     * @param serviceList
     * @param config
     *            - configuration from processor.yaml
     * @param spec
     *            - configuration passed from the client when creating the processor
     * @throws ProcessorException
     * @throws ConfigurationException
     */
    void init(List<ServiceWithConfig> serviceList, YConfiguration config, Object spec)
            throws ProcessorException, ConfigurationException {
        xtcedb = XtceDbFactory.getInstance(yamcsInstance);
        boolean generateEvents = false;
        if (config != null) {
            generateEvents = config.getBoolean(CONFIG_KEY_GENERATE_EVENTS, true);
        }

        processorData = new ProcessorData(this, generateEvents);
        this.serviceList = serviceList;

        timeService = YamcsServer.getTimeService(yamcsInstance);
        YConfiguration tmProcessorConfig = null;

        synchronized (instances) {
            if (instances.containsKey(key(yamcsInstance, name))) {
                throw new ProcessorException(
                        "A processor named '" + name + "' already exists in instance " + yamcsInstance);
            }
            if (config != null) {
                for (String key : config.getRoot().keySet()) {
                    if (CONFIG_KEY_ALARM.equals(key)) {
                        configureAlarms(config.getConfig(key));
                    } else if (CONFIG_KEY_SUBSCRIBE_ALL.equals(key)) {
                        subscribeAll = config.getBoolean(CONFIG_KEY_SUBSCRIBE_ALL);
                    } else if (CONFIG_KEY_PARAMETER_CACHE.equals(key)) {
                        configureParameterCache(config.getConfig(key));
                    } else if (CONFIG_KEY_TM_PROCESSOR.equals(key)) {
                        tmProcessorConfig = config.getConfig(key);
                    } else if (CONFIG_KEY_RECORD_INITIAL_VALUES.equals(key)) {
                        recordInitialValues = config.getBoolean(key);
                    } else if (CONFIG_KEY_RECORD_LOCAL_VALUES.equals(key)) {
                        recordLocalValues = config.getBoolean(key);
                    } else {
                        log.warn("Ignoring unknown config key '{}'", key);
                    }
                }
            }

            YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
            Stream pps = ydb.getStream(PROC_PARAMETERS_STREAM);
            if (pps != null) {
                streamParameterSender = new StreamParameterSender(yamcsInstance, pps);
            }
            if (recordInitialValues || recordLocalValues) {
                if (pps == null) {
                    throw new ConfigurationException("recordInitialValue is set to true but the stream '"
                            + PROC_PARAMETERS_STREAM + "' does not exist");
                }
                streamParameterSender.sendParameters(processorData.getLastValueCache().getValues());
            }
            // Shared between prm and crm
            tmProcessor = new XtceTmProcessor(this, tmProcessorConfig);
            containerRequestManager = new ContainerRequestManager(this, tmProcessor);
            parameterRequestManager = new ParameterRequestManager(this, tmProcessor);

            for (ServiceWithConfig swc : serviceList) {
                ProcessorService service = (ProcessorService) swc.service;
                if (spec == null) {
                    service.init(this);
                } else {
                    service.init(this, spec);
                }
            }

            parameterRequestManager.init();

            instances.put(key(yamcsInstance, name), this);
            listeners.forEach(l -> l.processorAdded(this));
        }
    }

    public void setPacketProvider(TmPacketProvider tpp) {
        if (tmPacketProvider != null) {
            throw new IllegalStateException("There is already a packet provider");
        }
        tmPacketProvider = tpp;
    }

    public void setCommandReleaser(CommandReleaser cr) {
        if (commandReleaser != null) {
            throw new IllegalStateException("There is already a command releaser");
        }
        commandReleaser = cr;
        // maybe we should un-hardcode these two and make them services
        commandHistoryPublisher = new StreamCommandHistoryPublisher(yamcsInstance);
        setCommandHistoryProvider(new StreamCommandHistoryProvider(yamcsInstance));

        commandingManager = new CommandingManager(this);
        commandReleaser.setCommandHistory(commandHistoryPublisher);

    }

    public void setCommandHistoryProvider(CommandHistoryProvider chp) {
        if (commandHistoryProvider != null) {
            throw new IllegalStateException("There is already a command history provider");
        }
        commandHistoryProvider = chp;
        commandHistoryRequestManager = new CommandHistoryRequestManager(this);
        commandHistoryProvider.setCommandHistoryRequestManager(commandHistoryRequestManager);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    private void configureAlarms(YConfiguration alarmConfig) {
        if (alarmConfig.containsKey("check")) {
            log.warn(
                    "Deprectiation: in processor.yaml, please replace config -> alarm -> check with config -> alarm -> parameterCheck");
            checkParameterAlarms = alarmConfig.getBoolean("check");
        }
        checkParameterAlarms = alarmConfig.getBoolean("parameterCheck", checkParameterAlarms);
        if (alarmConfig.containsKey("server")) {
            log.warn(
                    "Deprectiation: in processor.yaml, please replace config -> alarm -> server with config -> alarm -> parameterServer");
            parameterAlarmServerEnabled = "enabled".equalsIgnoreCase(alarmConfig.getString("server", null));
        }
        if (alarmConfig.containsKey("parameterServer")) {
            parameterAlarmServerEnabled = "enabled".equalsIgnoreCase(alarmConfig.getString("parameterServer"));
        }
        if (parameterAlarmServerEnabled) {
            checkParameterAlarms = true;
        }

        eventAlarmServerEnabled = "enabled".equalsIgnoreCase(alarmConfig.getString("eventServer", null));

        if (eventAlarmServerEnabled) {
            eventAlarmServer = new EventAlarmServer(yamcsInstance, alarmConfig, timer);
        }
    }

    private void configureParameterCache(YConfiguration cacheConfig) {
        boolean enabled = cacheConfig.getBoolean("enabled", false);

        if (!enabled) { // this is the default but print a warning if there are some things configured
            Set<String> keySet = cacheConfig.getRoot().keySet();
            keySet.remove("enabled");
            if (!keySet.isEmpty()) {
                log.warn(
                        "Parmeter cache is disabled, the following keys are ignored: {}, use enable: true to enable the parameter cache",
                        keySet);
            }
            return;
        }
        boolean cacheAll = cacheConfig.getBoolean("cacheAll", false);
        if (cacheAll) {
            enabled = true;
        }
        long duration = 1000L * cacheConfig.getInt("duration", 300);
        int maxNumEntries = cacheConfig.getInt("maxNumEntries", 512);

        parameterCacheConfig = new ParameterCacheConfig(enabled, cacheAll, duration, maxNumEntries);
    }

    private static String key(String instance, String name) {
        return instance + "." + name;
    }

    public CommandHistoryPublisher getCommandHistoryPublisher() {
        return commandHistoryPublisher;
    }

    public ParameterRequestManager getParameterRequestManager() {
        return parameterRequestManager;
    }

    public ContainerRequestManager getContainerRequestManager() {
        return containerRequestManager;
    }

    public XtceTmProcessor getTmProcessor() {
        return tmProcessor;
    }

    /**
     * starts processing by invoking the start method for all the associated components
     *
     */
    @Override
    public void doStart() {
        try {
            tmProcessor.startAsync();
            tmProcessor.addListener(new Listener() {
                @Override
                public void terminated(State from) {
                    stopAsync();
                }
            }, MoreExecutors.directExecutor());
            startIfNecessary(commandHistoryRequestManager);
            startIfNecessary(commandHistoryProvider);
            startIfNecessary(parameterRequestManager);
            startIfNecessary(tmPacketProvider);
            startIfNecessary(commandingManager);
            startIfNecessary(eventAlarmServer);

            for (ServiceWithConfig swc : serviceList) {
                startIfNecessary(swc.service);
            }

            tmProcessor.awaitRunning();

            awaitIfNecessary(commandHistoryRequestManager);
            awaitIfNecessary(commandHistoryProvider);
            awaitIfNecessary(parameterRequestManager);
            awaitIfNecessary(tmPacketProvider);
            awaitIfNecessary(commandingManager);
            awaitIfNecessary(eventAlarmServer);

            for (ServiceWithConfig swc : serviceList) {
                swc.service.awaitRunning();
            }

            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e.getCause());
        }
        propagateProcessorStateChange();
    }

    public List<ServiceWithConfig> getServices() {
        return serviceList.stream().collect(Collectors.toList());
    }

    private void startIfNecessary(Service service) {
        if (service != null) {
            if (service.state() == State.NEW) {
                service.startAsync();
            }
        }
    }

    private void awaitIfNecessary(Service service) {
        if (service != null) {
            service.awaitRunning();
        }
    }

    public void pause() {
        ((ArchiveTmPacketProvider) tmPacketProvider).pause();
        propagateProcessorStateChange();
    }

    public void resume() {
        ArchiveTmPacketProvider provider = (ArchiveTmPacketProvider) tmPacketProvider;
        provider.resume();
        if (provider.getSpeed() != null
                && provider.getSpeed().getType() != ReplaySpeedType.STEP_BY_STEP) {
            propagateProcessorStateChange();
        }
    }

    private void propagateProcessorStateChange() {
        listeners.forEach(l -> l.processorStateChanged(this));
    }

    public void seek(long instant) {
        getTmProcessor().resetStatistics();
        ((ArchiveTmPacketProvider) tmPacketProvider).seek(instant);
        propagateProcessorStateChange();
    }

    public void changeSpeed(ReplaySpeed speed) {
        ((ArchiveTmPacketProvider) tmPacketProvider).changeSpeed(speed);
        propagateProcessorStateChange();
    }

    /**
     * @return the tcUplinker
     */
    public CommandReleaser getCommandReleaser() {
        return commandReleaser;
    }

    /**
     * @return the tmPacketProvider
     */
    public TmPacketProvider getTmPacketProvider() {
        return tmPacketProvider;
    }

    public String getName() {
        return name;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public int getConnectedClients() {
        return connectedClients.size();
    }

    public static Processor getInstance(String yamcsInstance, String name) {
        return instances.get(key(yamcsInstance, name));
    }

    /**
     * Returns the first register processor for the given instance or null if there is no processor registered.
     * 
     * @param yamcsInstance
     *            - instance name for which the processor has to be returned.
     * @return the first registered processor for the given instance
     */
    public static Processor getFirstProcessor(String yamcsInstance) {
        for (Map.Entry<String, Processor> me : instances.entrySet()) {
            if (me.getKey().startsWith(yamcsInstance + ".")) {
                return me.getValue();
            }
        }
        return null;
    }

    /**
     * Increase with one the number of connected clients to the named processor and return the processor.
     * 
     * @param yamcsInstance
     * @param name
     * @param s
     * @return the processor with the given name
     * @throws ProcessorException
     */
    public static Processor connect(String yamcsInstance, String name, ConnectedClient s) throws ProcessorException {
        Processor ds = instances.get(key(yamcsInstance, name));
        if (ds == null) {
            throw new ProcessorException("There is no processor named '" + name + "'");
        }
        ds.connect(s);
        return ds;
    }

    /**
     * Increase with one the number of connected clients
     */
    public synchronized void connect(ConnectedClient s) throws ProcessorException {
        log.debug("Processor {} has one more user: {}", name, s);
        if (quitting) {
            throw new ProcessorException("This processor has been closed");
        }
        connectedClients.add(s);
    }

    /**
     * Disconnects a client from this processor. If the processor has no more clients, quit.
     *
     */
    public void disconnect(ConnectedClient s) {
        if (quitting) {
            return;
        }
        boolean hasToQuit = false;
        synchronized (this) {
            if (connectedClients.remove(s)) {
                log.info("Processor {} has one less user: connectedUsers: {}", name, connectedClients.size());
                if ((connectedClients.isEmpty()) && (!persistent)) {
                    hasToQuit = true;
                }
            }
        }
        if (hasToQuit) {
            stopAsync();
        }
    }

    public static Collection<Processor> getProcessors() {
        return instances.values();
    }

    public static Collection<Processor> getProcessors(String instance) {
        List<Processor> processors = new ArrayList<>();
        for (Processor processor : instances.values()) {
            if (instance.equals(processor.getInstance())) {
                processors.add(processor);
            }
        }
        return processors;
    }

    /**
     * Closes the processor by stoping the tm/pp and tc It can be that there are still clients connected, but they will
     * not get any data and new clients can not connect to these processors anymore. Once it is closed, you can create a
     * processor with the same name which will make it maybe a bit confusing :(
     *
     */
    @Override
    public void doStop() {
        if (quitting) {
            return;
        }

        log.info("Processor {} quitting", name);
        quitting = true;
        timer.shutdown();
        // first send a STOPPING event
        listeners.forEach(l -> l.processorStateChanged(this));

        for (ServiceWithConfig swc : serviceList) {
            swc.service.stopAsync();
        }

        instances.remove(key(yamcsInstance, name));

        if (commandReleaser != null) {
            commandReleaser.stopAsync();
            commandingManager.stopAsync();
        }
        if (tmProcessor != null) {
            tmProcessor.stopAsync();
        }
        if (tmPacketProvider != null) {
            tmPacketProvider.stopAsync();
        }
        if (eventAlarmServer != null) {
            eventAlarmServer.stopAsync();
        }
        log.info("Processor {} is out of business", name);

        if (getState() == ServiceState.RUNNING || getState() == ServiceState.STOPPING) {
            notifyStopped();
        }
        // and now a CLOSED event
        listeners.forEach(l -> l.processorClosed(this));
        synchronized (this) {
            for (ConnectedClient s : connectedClients) {
                s.processorQuit();
            }
        }
    }

    public static void addProcessorListener(ProcessorListener processorListener) {
        listeners.add(processorListener);
    }

    public static void removeProcessorListener(ProcessorListener processorListener) {
        listeners.remove(processorListener);
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean systemSession) {
        this.persistent = systemSession;
    }

    public boolean isSynchronous() {
        return synchronous;
    }

    public boolean hasCommanding() {
        return commandingManager != null;
    }

    public void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    public boolean isReplay() {
        if (tmPacketProvider == null) {
            return false;
        }

        return tmPacketProvider.isArchiveReplay();
    }

    /**
     * valid only if isArchiveReplay returns true
     * 
     * @return
     */
    public ReplayRequest getReplayRequest() {
        return ((ArchiveTmPacketProvider) tmPacketProvider).getReplayRequest();
    }

    /**
     * valid only if isArchiveReplay returns true
     * 
     * @return
     */
    public ReplayState getReplayState() {
        return ((ArchiveTmPacketProvider) tmPacketProvider).getReplayState();
    }

    public ServiceState getState() {
        return ServiceState.valueOf(state().name());
    }

    public CommandingManager getCommandingManager() {
        return commandingManager;
    }

    @Override
    public String toString() {
        return "name: " + name + " type: " + type + " connectedClients:" + connectedClients.size();
    }

    /**
     *
     * @return the yamcs instance this processor is part of
     */
    public String getInstance() {
        return yamcsInstance;
    }

    public XtceDb getXtceDb() {
        return xtcedb;
    }

    public CommandHistoryRequestManager getCommandHistoryManager() {
        return commandHistoryRequestManager;
    }

    public boolean hasAlarmChecker() {
        return checkParameterAlarms;
    }

    public boolean hasAlarmServer() {
        return parameterAlarmServerEnabled;
    }

    public ScheduledThreadPoolExecutor getTimer() {
        return timer;
    }

    /**
     * Returns the processor time
     * 
     * for realtime processors it is the mission time or simulation time for replay processors it is the replay time
     * 
     * @return
     */
    public long getCurrentTime() {
        if (isReplay()) {
            return ((ArchiveTmPacketProvider) tmPacketProvider).getReplayTime();
        } else {
            return timeService.getMissionTime();
        }
    }

    public void quit() {
        stopAsync();
        awaitTerminated();
    }

    public void start() {
        startAsync();
        awaitRunning();
    }

    public void notifyStateChange() {
        propagateProcessorStateChange();
    }

    /**
     * returns a list of all processors names
     * 
     * @return all processors names as a list of instance.processorName
     */
    public static List<String> getAllProcessors() {
        List<String> l = new ArrayList<>(instances.size());
        l.addAll(instances.keySet());
        return l;
    }

    public ParameterCacheConfig getPameterCacheConfig() {
        return parameterCacheConfig;
    }

    public ParameterCache getParameterCache() {
        return parameterRequestManager.getParameterCache();
    }

    /**
     * Returns the processor data used to store processor specific calibration, alarms
     * 
     * @return processor specific data
     */
    public ProcessorData getProcessorData() {
        return processorData;
    }

    public LastValueCache getLastValueCache() {
        return processorData.getLastValueCache();
    }

    public boolean isSubscribeAll() {
        return subscribeAll;
    }

    @SuppressWarnings("unchecked")
    public <T extends ProcessorService> List<T> getServices(Class<T> serviceClass) {
        List<T> services = new ArrayList<>();
        if (serviceList != null) {
            for (ServiceWithConfig swc : serviceList) {
                if (swc.getServiceClass().equals(serviceClass.getName())) {
                    services.add((T) swc.service);
                }
            }
        }
        return services;
    }

    public boolean recordLocalValues() {
        return recordLocalValues;
    }

    public EventAlarmServer getEventAlarmServer() {
        return eventAlarmServer;
    }
}
