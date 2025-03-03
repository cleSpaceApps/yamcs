package org.yamcs.xtceproc;

import java.util.List;

import org.yamcs.ConfigurationException;
import org.yamcs.ContainerExtractionResult;
import org.yamcs.InvalidIdentification;
import org.yamcs.Processor;
import org.yamcs.TmProcessor;
import org.yamcs.YConfiguration;
import org.yamcs.archive.PacketWithTime;
import org.yamcs.container.ContainerProvider;
import org.yamcs.logging.Log;
import org.yamcs.parameter.ParameterListener;
import org.yamcs.parameter.ParameterProvider;
import org.yamcs.parameter.ParameterValueList;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Container;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SequenceContainer;
import org.yamcs.xtce.XtceDb;

import com.google.common.util.concurrent.AbstractService;

/**
 * 
 * Does the job of getting containers and transforming them into parameters which are then sent to the parameter request
 * manager for the distribution to the requesters.
 * 
 * Relies on {@link XtceTmExtractor} for extracting the parameters out of containers
 * 
 * @author mache
 * 
 */

public class XtceTmProcessor extends AbstractService implements TmProcessor, ParameterProvider, ContainerProvider {

    Log log;
    private ParameterListener parameterRequestManager;
    private ContainerListener containerRequestManager;

    public final Processor processor;
    public final XtceDb xtcedb;
    final XtceTmExtractor tmExtractor;
    final String CONFIG_KEY_ignoreOutOfContainerEntries = "ignoreOutOfContainerEntries";
    final String CONFIG_KEY_expirationTolerance = "expirationTolerance";

    public XtceTmProcessor(Processor processor, YConfiguration tmProcessorConfig) {
        this.processor = processor;
        this.xtcedb = processor.getXtceDb();

        log = new Log(getClass(), processor.getInstance());
        log.setContext(processor.getName());

        tmExtractor = new XtceTmExtractor(xtcedb, processor.getProcessorData());

        if (tmProcessorConfig != null) {
            ContainerProcessingOptions opts = new ContainerProcessingOptions();
            opts.setIgnoreOutOfContainerEntries(
                    tmProcessorConfig.getBoolean(CONFIG_KEY_ignoreOutOfContainerEntries, false));
            opts.setExpirationTolerance(
                    tmProcessorConfig.getDouble(CONFIG_KEY_expirationTolerance, opts.expirationTolerance));
            tmExtractor.setOptions(opts);
        }
    }

    public XtceTmProcessor(Processor processor) {
        this.processor = processor;
        this.xtcedb = processor.getXtceDb();
        log = new Log(getClass(), processor.getInstance());
        log.setContext(processor.getName());
        tmExtractor = new XtceTmExtractor(xtcedb, processor.getProcessorData());
    }

    /**
     * Creates a TmProcessor to be used in "standalone" mode, outside of any processor
     */
    public XtceTmProcessor(XtceDb xtcedb) {
        this.processor = null;
        this.xtcedb = xtcedb;
        log = new Log(getClass());
        tmExtractor = new XtceTmExtractor(xtcedb);
    }

    @Override
    public void init(Processor processor) throws ConfigurationException {
        // do nothing, we already know the processor
    }

    @Override
    public void setParameterListener(ParameterListener p) {
        this.parameterRequestManager = p;
    }

    @Override
    public void setContainerListener(ContainerListener c) {
        this.containerRequestManager = c;
    }

    /**
     * Adds a parameter to the current subscription list: finds all the SequenceContainers in which this parameter may
     * appear and adds them to the list. also for each sequence container adds the parameter needed to instantiate the
     * sequence container.
     * 
     * @param param
     *            parameter to be added to the current subscription list
     */
    @Override
    public void startProviding(Parameter param) {
        tmExtractor.startProviding(param);
    }

    /**
     * adds all parameters to the subscription
     */
    @Override
    public void startProvidingAll() {
        tmExtractor.provideAll();
    }

    @Override
    public void stopProviding(Parameter param) {
        tmExtractor.stopProviding(param);
    }

    @Override
    public boolean canProvide(NamedObjectId paraId) {
        Parameter p = xtcedb.getParameter(paraId);
        if (p == null) {
            return false;
        }

        return xtcedb.getParameterEntries(p) != null;
    }

    @Override
    public boolean canProvide(Parameter para) {
        return xtcedb.getParameterEntries(para) != null;
    }

    @Override
    public Parameter getParameter(NamedObjectId paraId) throws InvalidIdentification {
        Parameter p = xtcedb.getParameter(paraId);
        if (p == null) {
            throw new InvalidIdentification(paraId);
        }
        return p;
    }

    private long getCurrentTime() {
        if (processor != null) {
            return processor.getCurrentTime();
        } else {
            return TimeEncoding.getWallclockTime();
        }
    }

    /**
     * Process telemetry packets
     *
     */
    @Override
    public void processPacket(PacketWithTime pwrt) {
        try {
            long rectime = pwrt.getReceptionTime();
            if (rectime == TimeEncoding.INVALID_INSTANT) {
                rectime = getCurrentTime();
            }
            tmExtractor.processPacket(pwrt.getPacket(), pwrt.getGenerationTime(), rectime);

            ParameterValueList paramResult = tmExtractor.getParameterResult();
            List<ContainerExtractionResult> containerResult = tmExtractor.getContainerResult();

            if ((parameterRequestManager != null) && (paramResult.size() > 0)) {
                parameterRequestManager.update(paramResult);
            }

            if ((containerRequestManager != null) && (containerResult.size() > 0)) {
                containerRequestManager.update(containerResult);
            }

        } catch (Exception e) {
            log.error("got exception in tmprocessor ", e);
        }
    }

    @Override
    public void processPacket(PacketWithTime pwrt, SequenceContainer sc) {
        try {
            long rectime = pwrt.getReceptionTime();
            if (rectime == TimeEncoding.INVALID_INSTANT) {
                rectime = TimeEncoding.getWallclockTime();
            }
            tmExtractor.processPacket(pwrt.getPacket(), pwrt.getGenerationTime(), rectime, sc);

            ParameterValueList paramResult = tmExtractor.getParameterResult();
            List<ContainerExtractionResult> containerResult = tmExtractor.getContainerResult();

            if ((parameterRequestManager != null) && (paramResult.size() > 0)) {
                parameterRequestManager.update(paramResult);
            }

            if ((containerRequestManager != null) && (containerResult.size() > 0)) {
                containerRequestManager.update(containerResult);
            }

        } catch (Exception e) {
            log.error("got exception in tmprocessor ", e);
        }
    }

    @Override
    public void finished() {
        stopAsync();
    }

    public void resetStatistics() {
        tmExtractor.resetStatistics();
    }

    public ProcessingStatistics getStatistics() {
        return tmExtractor.getStatistics();
    }

    @Override
    public boolean canProvideContainer(NamedObjectId containerId) {
        return xtcedb.getSequenceContainer(containerId) != null;
    }

    @Override
    public void startProviding(SequenceContainer container) {
        tmExtractor.startProviding(container);
    }

    @Override
    public void stopProviding(SequenceContainer container) {
        tmExtractor.stopProviding(container);
    }

    @Override
    public void startProvidingAllContainers() {
        tmExtractor.provideAll();
    }

    @Override
    public Container getContainer(NamedObjectId containerId) throws InvalidIdentification {
        SequenceContainer c = xtcedb.getSequenceContainer(containerId);
        if (c == null) {
            throw new InvalidIdentification(containerId);
        }
        return c;
    }

    public Subscription getSubscription() {
        return tmExtractor.getSubscription();
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }

    public XtceDb getXtceDb() {
        return xtcedb;
    }
}
