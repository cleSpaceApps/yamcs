package org.yamcs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yamcs.Spec.OptionType;
import org.yamcs.logging.Log;
import org.yamcs.protobuf.Mdb.MissionDatabase;
import org.yamcs.protobuf.YamcsInstance;
import org.yamcs.protobuf.YamcsInstance.InstanceState;
import org.yamcs.time.RealtimeTimeService;
import org.yamcs.time.TimeService;
import org.yamcs.utils.ServiceUtil;
import org.yamcs.utils.YObjectLoader;
import org.yamcs.xtce.DatabaseLoadException;
import org.yamcs.xtce.Header;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * Represents a Yamcs instance together with the instance specific services
 * 
 * @author nm
 *
 */
public class YamcsServerInstance extends YamcsInstanceService {

    private String name;
    Log log;
    TimeService timeService;
    private CrashHandler crashHandler;
    List<ServiceWithConfig> services;
    private XtceDb xtceDb;

    InstanceMetadata metadata;
    YConfiguration config;

    YamcsServerInstance(String name) {
        this(name, new InstanceMetadata());
    }

    YamcsServerInstance(String name, InstanceMetadata metadata) {
        this.name = name;
        this.metadata = metadata;
        log = new Log(getClass(), name);
    }

    void init(YConfiguration config) {
        Spec serviceSpec = new Spec();
        serviceSpec.addOption("class", OptionType.STRING).withRequired(true);
        serviceSpec.addOption("args", OptionType.ANY);

        Spec spec = new Spec();
        spec.addOption("services", OptionType.LIST).withElementType(OptionType.MAP).withSpec(serviceSpec);

        // Detailed validation on these is done
        // in DataLinkInitialiser, XtceDbFactory, and StreamInitializer
        spec.addOption("dataLinks", OptionType.LIST).withElementType(OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("streamConfig", OptionType.MAP).withSpec(Spec.ANY);

        /*
         *  TODO not possible to activate this validation because mdb is being used
         *  ambiguously as both LIST and STRING with completely different meanings.
         */
        // spec.addOption("mdb", OptionType.LIST).withElementType(OptionType.MAP).withSpec(Spec.ANY);
        spec.addOption("mdb", OptionType.ANY);

        try {
            this.config = spec.validate(config);
        } catch (ValidationException e) {
            // Don't care about stacktrace inside spec
            throw new UncheckedExecutionException(new ValidationException(
                    e.getContext(), e.getMessage()));
        }

        initAsync();
        try {
            awaitInitialized();
        } catch (IllegalStateException e) {
            throw new UncheckedExecutionException(e.getCause());
        }
    }

    @Override
    public void doInit() {
        try {
            loadTimeService();
            loadCrashHandler();

            // first load the XtceDB (if there is an error in it, we don't want to load any other service)
            xtceDb = XtceDbFactory.getInstance(name);
            StreamInitializer.createStreams(name);
            List<YConfiguration> serviceConfigs = config.getServiceConfigList("services");
            services = YamcsServer.createServices(name, serviceConfigs, log);
            notifyInitialized();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStart() {
        for (ServiceWithConfig swc : services) {
            log.debug("Starting service {}", swc.getName());
            swc.service.startAsync();
        }
        for (ServiceWithConfig swc : services) {
            log.info("Awaiting start of service {}", swc.getName());
            ServiceUtil.awaitServiceRunning(swc.service);
        }
        notifyStarted();
    }

    @Override
    protected void doStop() {
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceWithConfig swc = services.get(i);
            swc.service.stopAsync();
        }
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceWithConfig swc = services.get(i);
            ServiceUtil.awaitServiceTerminated(swc.service, YamcsServer.SERVICE_STOP_GRACE_TIME, log);
        }

        YarchDatabaseInstance ydb = YarchDatabase.getInstance(name);
        ydb.close();
        YarchDatabase.removeInstance(name);
        notifyStopped();
    }

    public XtceDb getXtceDb() {
        return xtceDb;
    }

    /**
     * Stops this instance, and waits until it terminates
     * 
     * @throws IllegalStateException
     *             if the instance fails to do a clean stop
     */
    public void stop() throws IllegalStateException {
        stopAsync();
        awaitOffline();

        // set to null to free some memory
        xtceDb = null;
        services = null;
    }

    public void loadTimeService() {
        if (config.containsKey("timeService")) {
            YConfiguration m = config.getConfig("timeService");
            String servclass = m.getString("class");
            Object args = m.get("args");
            try {
                if (args == null) {
                    timeService = YObjectLoader.loadObject(servclass, name);
                } else {
                    timeService = YObjectLoader.loadObject(servclass, name, args);
                }
            } catch (IOException e) {
                throw new ConfigurationException("Failed to load time service:" + e.getMessage(), e);
            }
        } else {
            timeService = new RealtimeTimeService();
        }
    }

    private void loadCrashHandler() throws IOException {
        if (config.containsKey("crashHandler")) {
            if (config.containsKey("crashHandler", "args")) {
                crashHandler = YObjectLoader.loadObject(config.getSubString("crashHandler", "class"),
                        config.getSubMap("crashHandler", "args"));
            } else {
                crashHandler = YObjectLoader.loadObject(config.getSubString("crashHandler", "class"));
            }
        } else {
            crashHandler = YamcsServer.getServer().getGlobalCrashHandler();
        }
    }

    /**
     * Returns the main configuration for this Yamcs instance
     */
    public YConfiguration getConfig() {
        return config;
    }

    public ServiceWithConfig getServiceWithConfig(String serviceName) {
        if (services == null) {
            return null;
        }

        for (ServiceWithConfig swc : services) {
            if (swc.getName().equals(serviceName)) {
                return swc;
            }
        }
        return null;
    }

    public Service getService(String serviceName) {
        ServiceWithConfig serviceWithConfig = getServiceWithConfig(serviceName);
        return serviceWithConfig != null ? serviceWithConfig.getService() : null;
    }

    @SuppressWarnings("unchecked")
    public <T extends Service> List<T> getServices(Class<T> serviceClass) {
        List<T> result = new ArrayList<>();
        if (services != null) {
            for (ServiceWithConfig swc : services) {
                if (serviceClass.isInstance(swc.service)) {
                    result.add((T) swc.service);
                }
            }
        }
        return result;
    }

    public TimeService getTimeService() {
        return timeService;
    }

    public List<ServiceWithConfig> getServices() {
        return new ArrayList<>(services);
    }

    public void startService(String serviceName) throws ConfigurationException, ValidationException, IOException {
        YamcsServer.startService(name, serviceName, services);
    }

    CrashHandler getCrashHandler() {
        return crashHandler;
    }

    /**
     * Returns the name of this Yamcs instance
     */
    public String getName() {
        return name;
    }

    public YamcsInstance getInstanceInfo() {
        YamcsInstance.Builder aib = YamcsInstance.newBuilder().setName(name);
        InstanceState state = state();
        aib.setState(state);
        if (state == InstanceState.FAILED) {
            aib.setFailureCause(failureCause().toString());
        }
        if (config != null) { // Can be null for an offline instance
            try {
                MissionDatabase.Builder mdb = MissionDatabase.newBuilder();
                if (!config.isList("mdb")) {
                    String configName = config.getString("mdb");
                    mdb.setConfigName(configName);
                }
                XtceDb xtcedb = getXtceDb();
                if (xtcedb != null) { // if the instance is in a failed state, it could be that it doesn't have a XtceDB
                                      // (the failure might be due to the load of the XtceDb)
                    mdb.setName(xtcedb.getRootSpaceSystem().getName());
                    Header h = xtcedb.getRootSpaceSystem().getHeader();
                    if ((h != null) && (h.getVersion() != null)) {
                        mdb.setVersion(h.getVersion());
                    }
                }
                aib.setMissionDatabase(mdb.build());
            } catch (ConfigurationException | DatabaseLoadException e) {
                log.warn("Got error when finding the mission database for instance {}", name, e);
            }
        }
        aib.putAllLabels(getLabels());
        return aib.build();
    }

    public Object getMetadata(Object key) {
        return metadata.get(key);
    }

    public Map<String, String> getLabels() {
        return metadata.getLabels();
    }
}
