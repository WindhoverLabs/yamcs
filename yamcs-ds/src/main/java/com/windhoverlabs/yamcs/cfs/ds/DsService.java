package com.windhoverlabs.yamcs.cfs.ds;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.yamcs.AbstractYamcsService;
import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.Spec.OptionType;
import org.yamcs.events.EventProducer;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.StreamSubscriber;
import org.yamcs.yarch.Tuple;

import org.yamcs.client.filetransfer.FileTransferClient;
import org.yamcs.client.filetransfer.FileTransferClient.UploadOptions;

public class DsService extends AbstractYamcsService 
	    implements StreamSubscriber {
	
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    EventProducer eventProducer;
    private FileTransferClient cfdpClient;

    @Override
    public void init(String yamcsInstance, String serviceName, YConfiguration config) throws InitException {
        super.init(yamcsInstance, serviceName, config);

        //YarchDatabaseInstance ydb = YarchDatabase.getInstance(yamcsInstance);       
        
        System.out.println("DS: init(" + yamcsInstance + ", " + serviceName + ", <config>)");

        //cfdpClient = new FileTransferClient(client, yamcsInstance, "CfdpService");
    }

	@Override
	public void onTuple(Stream stream, Tuple tuple) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void doStart() {
        notifyStarted();
	}

	@Override
	protected void doStop() {
        executor.shutdown();
        notifyStopped();
	}

    @Override
    public void streamClosed(Stream stream) {
        if (isRunning()) {
            log.debug("Stream {} closed", stream.getName());
            notifyFailed(new Exception("Stream " + stream.getName() + " cloased"));
        }
    }

    @Override
    public Spec getSpec() {
        Spec spec = new Spec();
        
        spec.addOption("inactivityTimeout", OptionType.INTEGER).withDefault(10000);
        
        return spec;
    }
    
}