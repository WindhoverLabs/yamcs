package org.yamcs.tctm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.InvalidIdentification;
import org.yamcs.Processor;
import org.yamcs.StreamConfig;
import org.yamcs.StreamConfig.StandardStreamType;
import org.yamcs.YConfiguration;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterListener;
import org.yamcs.parameter.ParameterProvider;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.utils.AggregateUtil;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.ParameterTypeProcessor;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.common.util.concurrent.AbstractService;

/**
 * Provides parameters from yarch streams (pp_realtime) to ParameterRequestManager.
 * 
 * @author nm
 *
 */
public class StreamParameterProvider extends AbstractService implements StreamSubscriber, ParameterProvider {
    List<Stream> streams = new ArrayList<>();
    ParameterListener paraListener;
    XtceDb xtceDb;
    private static final Logger log = LoggerFactory.getLogger(StreamParameterProvider.class);

    ParameterTypeProcessor ptypeProcessor;


    public void init(String yamcsInstance, YConfiguration config) throws ConfigurationException {
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);
        xtceDb = XtceDbFactory.getInstance(yamcsInstance);

        List<String> streamNames;
        if (config.containsKey("stream")) {
            streamNames = Arrays.asList(config.getString("stream"));
        } else if (config.containsKey("streams")) {
            streamNames = config.getList("streams");
        } else {
            streamNames = StreamConfig.getInstance(yamcsInstance).getEntries(StandardStreamType.param).stream()
                    .map(sce -> sce.getName()).collect(Collectors.toList());
        }

        log.debug("Subscribing to streams {} ", streamNames);

        for (String streamName : streamNames) {
            Stream stream = ydb.getStream(streamName);
            if (stream == null) {
                throw new ConfigurationException("Cannot find a stream named " + streamName);
            }
            streams.add(stream);
        }
    }

    @Override
    public void init(Processor processor) {
        ptypeProcessor = processor.getProcessorData().getParameterTypeProcessor();
        processor.getParameterRequestManager().addParameterProvider(this);
        streams.forEach(s -> s.addSubscriber(this));
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        streams.forEach(s -> s.removeSubscriber(this));
        notifyStopped();
    }

    /**
     * Make sure all parameters are defined in the XtceDB, otherwise the PRM will choke
     */
    @Override
    public void onTuple(Stream s, Tuple tuple) {// the definition of the tuple is in PpProviderAdapter
        List<ParameterValue> params = new ArrayList<>();
        for (int i = 4; i < tuple.size(); i++) {
            Object o = tuple.getColumn(i);
            ParameterValue pv;
            if (o instanceof org.yamcs.protobuf.Pvalue.ParameterValue) {
                org.yamcs.protobuf.Pvalue.ParameterValue gpv = (org.yamcs.protobuf.Pvalue.ParameterValue) tuple
                        .getColumn(i);
                String name = tuple.getColumnDefinition(i).getName();
                Parameter ppdef = xtceDb.getParameter(name);
                if (ppdef == null) {
                    continue;
                }
                pv = ParameterValue.fromGpb(ppdef, gpv);
            } else if (o instanceof ParameterValue) {
                pv = (ParameterValue) o;
                if (pv.getParameter() == null) {
                    String fqn = pv.getParameterQualifiedNamed();
                    Parameter ppdef = xtceDb.getParameter(fqn);
                    if (ppdef == null) {
                        if (XtceDb.isSystemParameter(fqn)) {
                            ppdef = xtceDb.createSystemParameter(fqn);
                            if(pv.getEngValue() instanceof AggregateValue) {
                                //we have to set a type for the parameter, otherwise it won't be possible to subscribe to individual members
                                ppdef.setParameterType(AggregateUtil.createParameterType(ppdef.getName(), (AggregateValue) pv.getEngValue()));    
                            }
                        } else {
                            log.trace("Ignoring unknown parameter {}", fqn);
                            continue;
                        }
                    }
                    pv.setParameter(ppdef);
                }
            } else {
                log.warn("Received data that is not parameter value but {}", o.getClass());
                continue;
            }

            if (pv.getEngValue() == null && pv.getRawValue() != null) {
                ptypeProcessor.calibrate(pv);
            }
            params.add(pv);
        }
        paraListener.update(params);
    }

    @Override
    public void streamClosed(Stream s) {
        stopAsync();
    }

    @Override
    public void setParameterListener(ParameterListener paraListener) {
        this.paraListener = paraListener;
    }

    @Override
    public void stopProviding(Parameter paramDef) {

    }

    @Override
    public boolean canProvide(NamedObjectId id) {
        if (xtceDb.getParameter(id) != null) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean canProvide(Parameter p) {
        return xtceDb.getParameter(p.getQualifiedName()) != null;
    }

    @Override
    public Parameter getParameter(NamedObjectId id) throws InvalidIdentification {
        Parameter p = xtceDb.getParameter(id);
        if (p == null) {
            throw new InvalidIdentification();
        } else {
            return p;
        }
    }

    @Override
    public void startProviding(Parameter paramDef) {
    }

    @Override
    public void startProvidingAll() {
    }
}
