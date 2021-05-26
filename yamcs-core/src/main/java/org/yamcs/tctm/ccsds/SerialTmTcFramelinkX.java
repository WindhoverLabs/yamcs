package org.yamcs.tctm.ccsds;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.openmuc.jrxtx.SerialPort;
import org.yamcs.ConfigurationException;
import org.yamcs.TmPacket;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryPublisher.AckStatus;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.tctm.AbstractLink;
import org.yamcs.tctm.AggregatedDataLink;
import org.yamcs.tctm.CommandPostprocessor;
import org.yamcs.tctm.GenericCommandPostprocessor;
import org.yamcs.tctm.Link;
import org.yamcs.tctm.TcDataLink;
import org.yamcs.tctm.ccsds.error.BchCltuGenerator;
import org.yamcs.tctm.ccsds.error.CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc256CltuGenerator;
import org.yamcs.tctm.ccsds.error.Ldpc64CltuGenerator;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.YObjectLoader;

import com.google.common.util.concurrent.RateLimiter;

public class SerialTmTcFramelinkX extends AbstractLink implements Runnable, Link {
//    public SerialTmTcFramelinkX(SerialPort newSerialPort) {
//        // TODO Auto-generated constructor stub
//    }
    
    SerialTmFrameLink TmLink = null;
    SerialTcFrameLink TcLink = null;
    SerialPort serialPort = null;
    
    Thread thread;

    @Override
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        super.init(instance, name, config);
        TmLink = new SerialTmFrameLink(serialPort);
        TcLink = new SerialTcFrameLink(serialPort);
        TmLink.init(instance, name, config);
        TcLink.init(instance, name, config);
    }

    @Override
    protected void doEnable() throws Exception {
        TmLink.doEnable();
        TcLink.doEnable();
        new Thread(this).start();
    }

    @Override
    public void doStart() {
        TmLink.startAsync();
//        TmLink.doStart();
        TcLink.startAsync();
//        TcLink.doStart();
        if (!isDisabled()) {
            new Thread(this).start();
        }
        notifyStarted();
    }

    @Override
    public void doStop() {
        TmLink.doStop();
        TcLink.doStop();
        
    }

    @Override
    public long getDataInCount() {
        // TODO Auto-generated method stub
        return 0;
    }


    @Override
    public long getDataOutCount() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void resetCounters() {        
    }

    @Override
    public void run() {
        while(true);        
    }


    @Override
    protected Status connectionStatus() {
        // TODO Auto-generated method stub
        return Status.OK;
    }
}
