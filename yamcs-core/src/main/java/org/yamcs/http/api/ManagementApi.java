package org.yamcs.http.api;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.yamcs.InstanceMetadata;
import org.yamcs.ServiceWithConfig;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.YamcsVersion;
import org.yamcs.api.Observer;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.ForbiddenException;
import org.yamcs.http.HttpException;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.http.NotFoundException;
import org.yamcs.logging.Log;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.AbstractManagementApi;
import org.yamcs.protobuf.CreateInstanceRequest;
import org.yamcs.protobuf.EditInstanceRequest;
import org.yamcs.protobuf.EditLinkRequest;
import org.yamcs.protobuf.EditServiceRequest;
import org.yamcs.protobuf.GetInstanceRequest;
import org.yamcs.protobuf.GetInstanceTemplateRequest;
import org.yamcs.protobuf.GetLinkRequest;
import org.yamcs.protobuf.GetServiceRequest;
import org.yamcs.protobuf.InstanceTemplate;
import org.yamcs.protobuf.LeapSecondsTable;
import org.yamcs.protobuf.LeapSecondsTable.ValidityRange;
import org.yamcs.protobuf.LinkInfo;
import org.yamcs.protobuf.ListInstanceTemplatesResponse;
import org.yamcs.protobuf.ListInstancesRequest;
import org.yamcs.protobuf.ListInstancesResponse;
import org.yamcs.protobuf.ListLinksRequest;
import org.yamcs.protobuf.ListLinksResponse;
import org.yamcs.protobuf.ListServicesRequest;
import org.yamcs.protobuf.ListServicesResponse;
import org.yamcs.protobuf.RestartInstanceRequest;
import org.yamcs.protobuf.RootDirectory;
import org.yamcs.protobuf.ServiceInfo;
import org.yamcs.protobuf.ServiceState;
import org.yamcs.protobuf.StartInstanceRequest;
import org.yamcs.protobuf.StartServiceRequest;
import org.yamcs.protobuf.StopInstanceRequest;
import org.yamcs.protobuf.StopServiceRequest;
import org.yamcs.protobuf.SystemInfo;
import org.yamcs.protobuf.YamcsInstance;
import org.yamcs.protobuf.YamcsInstance.InstanceState;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.utils.ExceptionUtil;
import org.yamcs.utils.TaiUtcConverter.ValidityLine;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.parser.FilterParser;
import org.yamcs.utils.parser.FilterParser.Result;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.utils.parser.TokenMgrError;

import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.protobuf.Empty;

public class ManagementApi extends AbstractManagementApi<Context> {

    private static final Log log = new Log(ManagementApi.class);

    public static Pattern ALLOWED_INSTANCE_NAMES = Pattern.compile("\\w[\\w\\.-]*");

    @Override
    public void getSystemInfo(Context ctx, Empty request, Observer<SystemInfo> observer) {
        if (!ctx.user.isSuperuser()) {
            throw new ForbiddenException("Access is limited to superusers");
        }

        YamcsServer yamcs = YamcsServer.getServer();

        SystemInfo.Builder b = SystemInfo.newBuilder()
                .setYamcsVersion(YamcsVersion.VERSION)
                .setRevision(YamcsVersion.REVISION)
                .setServerId(yamcs.getServerId());

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        b.setUptime(runtime.getUptime());
        b.setJvm(runtime.getVmName() + " " + runtime.getVmVersion() + " (" + runtime.getVmVendor() + ")");
        b.setWorkingDirectory(new File("").getAbsolutePath());
        b.setConfigDirectory(yamcs.getConfigDirectory().toAbsolutePath().toString());
        b.setDataDirectory(yamcs.getDataDirectory().toAbsolutePath().toString());
        b.setCacheDirectory(yamcs.getCacheDirectory().toAbsolutePath().toString());
        b.setJvmThreadCount(Thread.activeCount());

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        b.setHeapMemory(heap.getCommitted());
        b.setUsedHeapMemory(heap.getUsed());
        if (heap.getMax() != -1) {
            b.setMaxHeapMemory(heap.getMax());
        }
        MemoryUsage nonheap = memory.getNonHeapMemoryUsage();
        b.setNonHeapMemory(nonheap.getCommitted());
        b.setUsedNonHeapMemory(nonheap.getUsed());
        if (nonheap.getMax() != -1) {
            b.setMaxNonHeapMemory(nonheap.getMax());
        }

        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        b.setOs(os.getName() + " " + os.getVersion());
        b.setArch(os.getArch());
        b.setAvailableProcessors(os.getAvailableProcessors());
        b.setLoadAverage(os.getSystemLoadAverage());

        try {
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                FileStore store = Files.getFileStore(root);
                b.addRootDirectories(RootDirectory.newBuilder()
                        .setDirectory(root.toString())
                        .setType(store.type())
                        .setTotalSpace(store.getTotalSpace())
                        .setUnallocatedSpace(store.getUnallocatedSpace())
                        .setUsableSpace(store.getUsableSpace()));
            }
        } catch (IOException e) {
            throw new InternalServerErrorException(e);
        }

        observer.complete(b.build());
    }

    @Override
    public void getLeapSeconds(Context ctx, Empty request, Observer<LeapSecondsTable> observer) {
        LeapSecondsTable.Builder b = LeapSecondsTable.newBuilder();
        List<ValidityLine> lines = TimeEncoding.getTaiUtcConversionTable();
        for (int i = 0; i < lines.size(); i++) {
            ValidityLine line = lines.get(i);
            long instant = TimeEncoding.fromUnixMillisec(line.unixMillis);
            ValidityRange.Builder rangeb = ValidityRange.newBuilder()
                    .setStart(TimeEncoding.toString(instant))
                    .setLeapSeconds(line.seconds - 10)
                    .setTaiDifference(line.seconds);
            if (i != lines.size() - 1) {
                ValidityLine next = lines.get(i + 1);
                instant = TimeEncoding.fromUnixMillisec(next.unixMillis);
                rangeb.setStop(TimeEncoding.toString(instant));
            }
            b.addRanges(rangeb);
        }
        observer.complete(b.build());
    }

    @Override
    public void listInstanceTemplates(Context ctx, Empty request,
            Observer<ListInstanceTemplatesResponse> observer) {
        ListInstanceTemplatesResponse.Builder templatesb = ListInstanceTemplatesResponse.newBuilder();

        List<InstanceTemplate> templates = new ArrayList<>(YamcsServer.getInstanceTemplates());
        templates.sort((t1, t2) -> t1.getName().compareToIgnoreCase(t2.getName()));

        for (InstanceTemplate template : templates) {
            templatesb.addTemplates(template);
        }
        observer.complete(templatesb.build());
    }

    @Override
    public void getInstanceTemplate(Context ctx, GetInstanceTemplateRequest request,
            Observer<InstanceTemplate> observer) {
        YamcsServer yamcs = YamcsServer.getServer();
        String name = request.getTemplate();
        if (!YamcsServer.hasInstanceTemplate(name)) {
            throw new NotFoundException("No template named '" + name + "'");
        }

        InstanceTemplate template = yamcs.getInstanceTemplate(name);
        observer.complete(template);
    }

    @Override
    public void listInstances(Context ctx, ListInstancesRequest request,
            Observer<ListInstancesResponse> observer) {
        Predicate<YamcsServerInstance> filter = getFilter(request.getFilterList());
        ListInstancesResponse.Builder instancesb = ListInstancesResponse.newBuilder();
        for (YamcsServerInstance instance : YamcsServer.getInstances()) {
            if (filter.test(instance)) {
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(instance.getInstanceInfo());
                instancesb.addInstances(enriched);
            }
        }
        observer.complete(instancesb.build());
    }

    @Override
    public void getInstance(Context ctx, GetInstanceRequest request, Observer<YamcsInstance> observer) {
        String instanceName = RestHandler.verifyInstance(request.getInstance());
        YamcsServerInstance instance = YamcsServer.getServer().getInstance(instanceName);
        YamcsInstance instanceInfo = instance.getInstanceInfo();
        YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(instanceInfo);
        observer.complete(enriched);
    }

    @Override
    public void updateInstance(Context ctx, EditInstanceRequest request, Observer<YamcsInstance> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        String instance = RestHandler.verifyInstance(request.getInstance());

        String state = request.hasState() ? request.getState() : null;
        YamcsServer yamcs = YamcsServer.getServer();

        CompletableFuture<Void> cf = CompletableFuture.completedFuture(null);
        if (state != null) {
            switch (state.toLowerCase()) {
            case "stop":
            case "stopped":
                if (yamcs.getInstance(instance) == null) {
                    throw new BadRequestException("No instance named '" + instance + "'");
                }
                cf = CompletableFuture.supplyAsync(() -> {
                    log.info("Stopping instance {}", instance);
                    try {
                        yamcs.stopInstance(instance);
                        return null;
                    } catch (IOException e) {
                        throw new UncheckedExecutionException(e);
                    }
                });
                break;
            case "restarted":
                cf = CompletableFuture.supplyAsync(() -> {
                    log.info("Restarting instance {}", instance);
                    try {
                        yamcs.restartInstance(instance);
                        return null;
                    } catch (IOException e) {
                        throw new UncheckedExecutionException(e);
                    }
                });
                break;
            case "running":
                cf = CompletableFuture.supplyAsync(() -> {
                    log.info("Starting instance {}", instance);
                    try {
                        yamcs.startInstance(instance);
                        return null;
                    } catch (IOException e) {
                        throw new UncheckedExecutionException(e);
                    }
                });
                break;
            default:
                throw new BadRequestException("Unsupported service state '" + state + "'");
            }
        }

        cf.whenComplete((v, error) -> {
            YamcsServerInstance ysi = YamcsServer.getServer().getInstance(instance);
            if (error == null) {
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(ysi.getInstanceInfo());
                observer.complete(enriched);
            } else {
                Throwable t = ExceptionUtil.unwind(error);
                observer.completeExceptionally(new InternalServerErrorException(t));
            }
        });
    }

    @Override
    public void createInstance(Context ctx, CreateInstanceRequest request, Observer<YamcsInstance> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.CreateInstances);
        YamcsServer yamcs = YamcsServer.getServer();

        if (!request.hasName()) {
            throw new BadRequestException("No instance name was specified");
        }
        String instanceName = request.getName();
        if (!ALLOWED_INSTANCE_NAMES.matcher(instanceName).matches()) {
            throw new BadRequestException("Invalid instance name");
        }
        if (!request.hasTemplate()) {
            throw new BadRequestException("No template was specified");
        }
        if (yamcs.getInstance(instanceName) != null) {
            throw new BadRequestException("An instance named '" + instanceName + "' already exists");
        }

        String template = request.getTemplate();
        Map<String, Object> templateArgs = new HashMap<>(request.getTemplateArgsMap());
        Map<String, String> labels = request.getLabelsMap();
        // Not (yet) supported via HTTP. If we do, should probably use JSON
        Map<String, Object> customMetadata = Collections.emptyMap();
        InstanceMetadata metadata = new InstanceMetadata();
        request.getLabelsMap().forEach((k, v) -> metadata.putLabel(k, v));

        CompletableFuture<YamcsServerInstance> cf = CompletableFuture.supplyAsync(() -> {
            try {
                yamcs.createInstance(instanceName, template, templateArgs, labels, customMetadata);
                return yamcs.startInstance(instanceName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        cf.whenComplete((v, error) -> {
            if (error == null) {
                YamcsInstance instanceInfo = v.getInstanceInfo();
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(instanceInfo);
                observer.complete(enriched);
            } else {
                Throwable t = ExceptionUtil.unwind(error);
                log.error("Error when creating instance {}", instanceName, t);
                observer.completeExceptionally(new InternalServerErrorException(t));
            }
        });
    }

    @Override
    public void startInstance(Context ctx, StartInstanceRequest request, Observer<YamcsInstance> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        String instance = RestHandler.verifyInstance(request.getInstance());

        CompletableFuture.supplyAsync(() -> {
            try {
                YamcsServer.getServer().startInstance(instance);
                return null;
            } catch (IOException e) {
                throw new UncheckedExecutionException(e);
            }
        }).whenComplete((v, error) -> {
            YamcsServerInstance ysi = YamcsServer.getServer().getInstance(instance);
            if (error == null) {
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(ysi.getInstanceInfo());
                observer.complete(enriched);
            } else {
                Throwable t = ExceptionUtil.unwind(error);
                observer.completeExceptionally(t);
            }
        });
    }

    @Override
    public void stopInstance(Context ctx, StopInstanceRequest request, Observer<YamcsInstance> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        String instance = RestHandler.verifyInstance(request.getInstance());
        YamcsServer yamcs = YamcsServer.getServer();
        if (yamcs.getInstance(instance) == null) {
            throw new BadRequestException("No instance named '" + instance + "'");
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                yamcs.stopInstance(instance);
                return null;
            } catch (IOException e) {
                throw new UncheckedExecutionException(e);
            }
        }).whenComplete((v, error) -> {
            YamcsServerInstance ysi = YamcsServer.getServer().getInstance(instance);
            if (error == null) {
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(ysi.getInstanceInfo());
                observer.complete(enriched);
            } else {
                Throwable t = ExceptionUtil.unwind(error);
                observer.completeExceptionally(t);
            }
        });
    }

    @Override
    public void restartInstance(Context ctx, RestartInstanceRequest request, Observer<YamcsInstance> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        String instance = RestHandler.verifyInstance(request.getInstance());
        YamcsServer yamcs = YamcsServer.getServer();

        CompletableFuture.supplyAsync(() -> {
            try {
                yamcs.restartInstance(instance);
                return null;
            } catch (IOException e) {
                throw new UncheckedExecutionException(e);
            }
        }).whenComplete((v, error) -> {
            YamcsServerInstance ysi = YamcsServer.getServer().getInstance(instance);
            if (error == null) {
                YamcsInstance enriched = YamcsToGpbAssembler.enrichYamcsInstance(ysi.getInstanceInfo());
                observer.complete(enriched);
            } else {
                Throwable t = ExceptionUtil.unwind(error);
                observer.completeExceptionally(t);
            }
        });
    }

    @Override
    public void listServices(Context ctx, ListServicesRequest request, Observer<ListServicesResponse> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        YamcsServer yamcs = YamcsServer.getServer();

        String instance = request.getInstance();
        boolean global = false;
        if (YamcsServer.GLOBAL_INSTANCE.equals(instance)) {
            global = true;
        } else {
            RestHandler.verifyInstance(instance);
        }

        ListServicesResponse.Builder responseb = ListServicesResponse.newBuilder();

        if (global) {
            for (ServiceWithConfig serviceWithConfig : yamcs.getGlobalServices()) {
                responseb.addServices(toServiceInfo(serviceWithConfig, null, null));
            }
        } else {
            YamcsServerInstance ysi = yamcs.getInstance(instance);
            for (ServiceWithConfig serviceWithConfig : ysi.getServices()) {
                responseb.addServices(toServiceInfo(serviceWithConfig, instance, null));
            }
        }
        observer.complete(responseb.build());
    }

    @Override
    public void getService(Context ctx, GetServiceRequest request, Observer<ServiceInfo> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        YamcsServer yamcs = YamcsServer.getServer();

        String instance = request.getInstance();
        boolean global = false;
        if (YamcsServer.GLOBAL_INSTANCE.equals(instance)) {
            global = true;
        } else {
            RestHandler.verifyInstance(instance);
        }
        String serviceName = request.getName();
        if (global) {
            ServiceWithConfig serviceWithConfig = yamcs.getGlobalServiceWithConfig(serviceName);
            if (serviceWithConfig == null) {
                throw new NotFoundException();
            }

            ServiceInfo serviceInfo = toServiceInfo(serviceWithConfig, null, null);
            observer.complete(serviceInfo);
        } else {
            YamcsServerInstance ysi = yamcs.getInstance(instance);
            ServiceWithConfig serviceWithConfig = ysi.getServiceWithConfig(serviceName);
            if (serviceWithConfig == null) {
                throw new NotFoundException();
            }

            ServiceInfo serviceInfo = toServiceInfo(serviceWithConfig, instance, null);
            observer.complete(serviceInfo);
        }
    }

    @Override
    public void startService(Context ctx, StartServiceRequest request, Observer<Empty> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        YamcsServer yamcs = YamcsServer.getServer();

        String instance = request.getInstance();
        String serviceName = request.getName();

        boolean global = false;
        if (YamcsServer.GLOBAL_INSTANCE.equals(instance)) {
            global = true;
        } else {
            RestHandler.verifyInstance(instance);
        }

        try {
            if (global) {
                ServiceWithConfig service = yamcs.getGlobalServiceWithConfig(serviceName);
                yamcs.startGlobalService(service.getName());
            } else {
                ServiceWithConfig service = yamcs.getInstance(instance)
                        .getServiceWithConfig(serviceName);
                yamcs.getInstance(instance).startService(service.getName());
            }
            observer.complete(Empty.getDefaultInstance());
        } catch (Exception e) {
            observer.completeExceptionally(e);
        }
    }

    @Override
    public void stopService(Context ctx, StopServiceRequest request, Observer<Empty> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        YamcsServer yamcs = YamcsServer.getServer();

        String instance = request.getInstance();
        String serviceName = request.getName();

        boolean global = false;
        if (YamcsServer.GLOBAL_INSTANCE.equals(instance)) {
            global = true;
        } else {
            RestHandler.verifyInstance(instance);
        }

        try {
            Service s;
            if (global) {
                s = yamcs.getGlobalService(serviceName);
            } else {
                s = yamcs.getInstance(instance).getService(serviceName);
            }
            if (s == null) {
                throw new NotFoundException("No service by name '" + serviceName + "'");
            }

            s.stopAsync();
            observer.complete(Empty.getDefaultInstance());
        } catch (Exception e) {
            observer.completeExceptionally(e);
        }
    }

    @Override
    public void updateService(Context ctx, EditServiceRequest request, Observer<Empty> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlServices);
        YamcsServer yamcs = YamcsServer.getServer();

        String instance = request.getInstance();

        boolean global = false;
        if (YamcsServer.GLOBAL_INSTANCE.equals(instance)) {
            global = true;
        } else {
            RestHandler.verifyInstance(instance);
        }

        String serviceName = request.getName();
        String state = request.hasState() ? request.getState() : null;

        if (serviceName == null) {
            throw new BadRequestException("No service name specified");
        }

        if (state != null) {
            switch (state.toLowerCase()) {
            case "stop":
            case "stopped":
                Service s;
                if (global) {
                    s = yamcs.getGlobalService(serviceName);
                } else {
                    s = yamcs.getInstance(instance).getService(serviceName);
                }
                if (s == null) {
                    throw new NotFoundException("No service by name '" + serviceName + "'");
                }

                s.stopAsync();
                observer.complete(Empty.getDefaultInstance());
                return;
            case "running":
                try {
                    if (global) {
                        ServiceWithConfig service = yamcs.getGlobalServiceWithConfig(serviceName);
                        yamcs.startGlobalService(service.getName());
                    } else {
                        ServiceWithConfig service = yamcs.getInstance(instance)
                                .getServiceWithConfig(serviceName);
                        yamcs.getInstance(instance).startService(service.getName());
                    }
                    observer.complete(Empty.getDefaultInstance());
                } catch (Exception e) {
                    observer.completeExceptionally(e);
                }
                return;
            default:
                throw new BadRequestException("Unsupported service state '" + state + "'");
            }
        } else {
            observer.complete(Empty.getDefaultInstance());
        }
    }

    @Override
    public void listLinks(Context ctx, ListLinksRequest request, Observer<ListLinksResponse> observer) {
        String instance = null;
        if (request.hasInstance()) {
            instance = RestHandler.verifyInstance(request.getInstance());
        }

        List<LinkInfo> links = ManagementService.getInstance().getLinkInfo();
        ListLinksResponse.Builder responseb = ListLinksResponse.newBuilder();

        for (LinkInfo link : links) {
            if (instance == null || instance.equals(link.getInstance())) {
                responseb.addLinks(link);
            }
        }
        observer.complete(responseb.build());
    }

    @Override
    public void getLink(Context ctx, GetLinkRequest request, Observer<LinkInfo> observer) {
        LinkInfo linkInfo = RestHandler.verifyLink(request.getInstance(), request.getName());
        observer.complete(linkInfo);
    }

    @Override
    public void updateLink(Context ctx, EditLinkRequest request, Observer<LinkInfo> observer) {
        RestHandler.checkSystemPrivilege(ctx.user, SystemPrivilege.ControlLinks);

        LinkInfo linkInfo = RestHandler.verifyLink(request.getInstance(), request.getName());

        String state = null;
        if (request.hasState()) {
            state = request.getState();
        }

        ManagementService mservice = ManagementService.getInstance();
        if (state != null) {
            switch (state.toLowerCase()) {
            case "enabled":
                try {
                    mservice.enableLink(linkInfo.getInstance(), linkInfo.getName());
                } catch (IllegalArgumentException e) {
                    throw new NotFoundException(e);
                }
                break;
            case "disabled":
                try {
                    mservice.disableLink(linkInfo.getInstance(), linkInfo.getName());
                } catch (IllegalArgumentException e) {
                    throw new NotFoundException(e);
                }
                break;
            default:
                throw new BadRequestException("Unsupported link state '" + state + "'");
            }
        }

        if (request.hasResetCounters() && request.getResetCounters()) {
            try {
                mservice.resetCounters(linkInfo.getInstance(), linkInfo.getName());
            } catch (IllegalArgumentException e) {
                throw new NotFoundException(e);
            }
        }

        linkInfo = ManagementService.getInstance().getLinkInfo(request.getInstance(), request.getName());
        observer.complete(linkInfo);
    }

    private Predicate<YamcsServerInstance> getFilter(List<String> flist) throws HttpException {
        if (flist == null) {
            return ysi -> true;
        }

        FilterParser fp = new FilterParser((StringReader) null);

        Predicate<YamcsServerInstance> pred = ysi -> true;
        for (String filter : flist) {
            fp.ReInit(new StringReader(filter));
            FilterParser.Result pr;
            try {
                pr = fp.parse();
                pred = pred.and(getPredicate(pr));
            } catch (ParseException | TokenMgrError e) {
                throw new BadRequestException("Cannot parse the filter '" + filter + "': " + e.getMessage());
            }

        }
        return pred;
    }

    private Predicate<YamcsServerInstance> getPredicate(Result pr) throws HttpException {
        if ("state".equals(pr.key)) {
            try {
                InstanceState state = InstanceState.valueOf(pr.value.toUpperCase());
                switch (pr.op) {
                case EQUAL:
                    return ysi -> ysi.state() == state;
                case NOT_EQUAL:
                    return ysi -> ysi.state() != state;
                default:
                    throw new IllegalStateException("Unknown operator " + pr.op);
                }
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(
                        "Unknown state '" + pr.value + "'. Valid values are: " + Arrays.asList(InstanceState.values()));
            }
        } else if (pr.key.startsWith("label:")) {
            String labelKey = pr.key.substring(6);
            return ysi -> {
                Map<String, ?> labels = ysi.getLabels();
                if (labels == null) {
                    return false;
                }
                Object o = labels.get(labelKey);
                if (o == null) {
                    return false;
                }
                switch (pr.op) {
                case EQUAL:
                    return pr.value.equals(o);
                case NOT_EQUAL:
                    return !pr.value.equals(o);
                default:
                    throw new IllegalStateException("Unknown operator " + pr.op);
                }
            };
        } else {
            throw new BadRequestException("Unknown filter key '" + pr.key + "'");
        }
    }

    public static ServiceInfo toServiceInfo(ServiceWithConfig serviceWithConfig, String instance, String processor) {
        ServiceInfo.Builder serviceb = ServiceInfo.newBuilder()
                .setName(serviceWithConfig.getName())
                .setClassName(serviceWithConfig.getServiceClass())
                .setState(ServiceState.valueOf(serviceWithConfig.getService().state().name()));
        if (instance != null) {
            serviceb.setInstance(instance);
        }
        if (processor != null) {
            serviceb.setProcessor(processor);
        }
        return serviceb.build();
    }
}
