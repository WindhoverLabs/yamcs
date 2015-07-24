package org.yamcs.archive;

import static org.yamcs.api.Protocol.*;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.StreamConfig;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.YarchDatabase;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientMessage;

import com.google.common.util.concurrent.AbstractExecutionThreadService;

import org.yamcs.YamcsException;
import org.yamcs.StreamConfig.StandardStreamType;
import org.yamcs.StreamConfig.StreamConfigEntry;
import org.yamcs.api.Protocol;
import org.yamcs.api.YamcsApiException;
import org.yamcs.api.YamcsClient;
import org.yamcs.api.YamcsSession;
import org.yamcs.protobuf.Yamcs.IndexRequest;

/**
 * Handles index retrievals and tags
 *
 */
public class IndexServer extends AbstractExecutionThreadService implements Runnable {
    static Logger log=LoggerFactory.getLogger(IndexServer.class.getName());

    TmIndex tmIndexer;

    final String yamcsInstance;

    final YamcsClient msgClient;
    YamcsSession ys;
    volatile boolean quitting=false;
    ThreadPoolExecutor executor=new ThreadPoolExecutor(10,10,10,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(10));

    final TagDb tagDb;

    /**
     * Maps instance names to archive directories
     */
    final HashSet<String> instances=new HashSet<String>();
    public IndexServer(String instance) throws HornetQException, IOException, ConfigurationException, YamcsApiException {
        this(instance, null);
    }
    
    public IndexServer(String yamcsInstance, Map<String, Object> config) throws HornetQException, IOException, ConfigurationException, YamcsApiException {
	boolean readonly=false;
	this.yamcsInstance=yamcsInstance;
	YConfiguration c=YConfiguration.getConfiguration("yamcs."+yamcsInstance);

	if(c.containsKey("tmIndexer")) {
	    String icn=c.getString("tmIndexer");
	    tmIndexer=loadIndexerFromClass(icn, yamcsInstance, readonly);
	} else {
	    tmIndexer=new CccsdsTmIndex(yamcsInstance, readonly);
	}
	YarchDatabase dict=YarchDatabase.getInstance(yamcsInstance);

	if(!readonly) {
	    StreamConfig sc = StreamConfig.getInstance(yamcsInstance);
	        if(config==null) {
	            List<StreamConfigEntry> sceList = sc.getEntries(StandardStreamType.tm);
	            for(StreamConfigEntry sce: sceList){
	                subscribe(sce);
	            }
	        } else {
	            List<String> streamNames = YConfiguration.getList(config, "streams");
	            for(String sn: streamNames) {
	                StreamConfigEntry sce = sc.getEntry(StandardStreamType.tm, sn);
	                if(sce==null) {
	                    throw new ConfigurationException("No stream config found for '"+sn+"'");
	                }
	                subscribe(sce);
	            }
	        }
	}

	tagDb=new TagDb(yamcsInstance, readonly);
	ys=YamcsSession.newBuilder().build();
	msgClient=ys.newClientBuilder().setRpcAddress(Protocol.getYarchIndexControlAddress(yamcsInstance)).
		setDataProducer(true).build();
	executor.allowCoreThreadTimeOut(true);
    }

    private void subscribe(StreamConfigEntry sce) {
        YarchDatabase ydb = YarchDatabase.getInstance(yamcsInstance);
        Stream realtimeTmStream=ydb.getStream(XtceTmRecorder.REALTIME_TM_STREAM_NAME);
        if(realtimeTmStream==null) throw new ConfigurationException("There is no stream named "+XtceTmRecorder.REALTIME_TM_STREAM_NAME);
        realtimeTmStream.addSubscriber(tmIndexer);
    }
    private static TmIndex loadIndexerFromClass(String icn, String instance, boolean readonly) throws ConfigurationException, IOException {
	try {
	    Class<TmIndex> ic=(Class<TmIndex>)Class.forName(icn);
	    Constructor<TmIndex> c=ic.getConstructor(String.class, Boolean.TYPE);
	    return c.newInstance(instance, readonly);
	} catch (InvocationTargetException e) {
	    Throwable t=e.getCause();
	    if(t instanceof ConfigurationException) {
		throw (ConfigurationException)t;
	    } else if(t instanceof IOException) {
		throw (IOException)t;
	    } else {
		throw new ConfigurationException(t.toString());
	    }

	} catch (Exception e) {
	    throw new ConfigurationException("Cannot create indexer from class "+icn+": "+e);
	}
    }

    @Override
    protected void startUp() {
	Thread.currentThread().setName(this.getClass().getSimpleName()+"["+yamcsInstance+"]");
    }

    @Override
    protected void triggerShutdown() {
	try {
	    quit();
	} catch (HornetQException e) {
	    e.printStackTrace();
	}
    }

    @Override
    public void run() {
	try {
	    while(!quitting) {
		ClientMessage msg=msgClient.rpcConsumer.receive();
		if(msg==null) {
		    if(quitting) break;
		    log.warn("null message received from the control queue");
		    continue;
		}
		SimpleString replyto=msg.getSimpleStringProperty(REPLYTO_HEADER_NAME);
		SimpleString dataAddress=msg.getSimpleStringProperty(DATA_TO_HEADER_NAME);
		if(replyto==null) {
		    log.warn("did not receive a replyto header. Ignoring the request");
		    continue;
		}
		try {
		    String request=msg.getStringProperty(REQUEST_TYPE_HEADER_NAME);
		    if("getIndex".equalsIgnoreCase(request)){
			if(dataAddress==null) {
			    log.warn("received a getIndex without a dataAddress. Ignoring the request");
			    continue;
			}
			getIndex(msg,dataAddress);
		    } else if("rebuildIndex".equalsIgnoreCase(request)){
			//   rebuildIndex(msg,replyto);
		    } else if("getTag".equalsIgnoreCase(request)){
			if(dataAddress==null) {
			    log.warn("received a getTag without a dataAddress. Ignoring the request");
			    continue;
			}
			tagDb.getTag(msgClient, msg, dataAddress);
		    } else if("upsertTag".equalsIgnoreCase(request)){
			tagDb.upsertTag(msgClient, msg,replyto);
		    } else if("deleteTag".equalsIgnoreCase(request)){
			tagDb.deleteTag(msgClient, msg,replyto);
		    } else {
			throw new YamcsException("Unknown request '"+request+"'");
		    }
		} catch(YamcsException e) {
		    msgClient.sendErrorReply(replyto, e.getMessage());
		} 
	    }
	} catch (Exception e) {
	    log.error("got exception while processing the requests ", e);
	    e.printStackTrace();
	}
    }


    public void getIndex(ClientMessage msg, SimpleString dataAddress) throws Exception {
	IndexRequest req;
	try {
	    req=(IndexRequest)decode(msg, IndexRequest.newBuilder());
	    if(!YamcsServer.hasInstance(req.getInstance())) {
		throw new YamcsException("Invalid instance "+req.getInstance());
	    }
	} catch (YamcsApiException e) {
	    log.warn("failed to decode the message: ", e);
	    return;
	}
	IndexRequestProcessor p=new IndexRequestProcessor(tmIndexer, req, dataAddress);
	executor.submit(p);
    }

    public void quit() throws HornetQException {
	quitting=true;
	msgClient.close();
	ys.close();
    }
}
