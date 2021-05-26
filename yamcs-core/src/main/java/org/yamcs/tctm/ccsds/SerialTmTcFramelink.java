package org.yamcs.tctm.ccsds;

import static org.yamcs.cmdhistory.CommandHistoryPublisher.AcknowledgeSent;

import java.io.IOException;
import java.io.OutputStream;
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

public class SerialTmTcFramelink extends SerialTmFrameLink implements Runnable, TcDataLink {
    public SerialTmTcFramelink(SerialPort newSerialPort) {
        super(newSerialPort);
        // TODO Auto-generated constructor stub
    }

    protected int burstRate;
    protected int burstDelay;

    protected CommandPostprocessor cmdPostProcessor;
    protected CommandHistoryPublisher commandHistoryPublisher;
    protected AtomicLong dataOutCount = new AtomicLong();
    private AggregatedDataLink parent = null;
    
    SerialTcFrameLink serialTcFrameLink = null;

    OutputStream outputStream = null;
    
    protected int frameCount;
    boolean sendCltu;
    protected MasterChannelFrameMultiplexer multiplexer;
    List<Link> subLinks;

    protected CltuGenerator cltuGenerator;
    final static String CLTU_START_SEQ_KEY = "cltuStartSequence";
    final static String CLTU_TAIL_SEQ_KEY = "cltuTailSequence";
    
    RateLimiter rateLimiter;  
    
    Thread thread;


    @Override
    public void init(String instance, String name, YConfiguration config) throws ConfigurationException {
        // Read arguments
        super.init(instance, name, config);

        this.burstRate = config.getInt("burstRate", 0);
        this.burstDelay = config.getInt("burstDelay", 0);        
        
        
        
        String cltuEncoding = config.getString("cltuEncoding", null);
        if (cltuEncoding != null) {
            if ("BCH".equals(cltuEncoding)) {
                byte[] startSeq = config.getBinary(CLTU_START_SEQ_KEY, BchCltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = config.getBinary(CLTU_TAIL_SEQ_KEY, BchCltuGenerator.CCSDS_TAIL_SEQ);
                boolean randomize = config.getBoolean("randomizeCltu", false);
                cltuGenerator = new BchCltuGenerator(randomize, startSeq, tailSeq);
            } else if ("LDPC64".equals(cltuEncoding)) {
                byte[] startSeq = config.getBinary(CLTU_START_SEQ_KEY, Ldpc64CltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = config.getBinary(CLTU_TAIL_SEQ_KEY, CltuGenerator.EMPTY_SEQ);
                cltuGenerator = new Ldpc64CltuGenerator(startSeq, tailSeq);
            } else if ("LDPC256".equals(cltuEncoding)) {
                byte[] startSeq = config.getBinary(CLTU_START_SEQ_KEY, Ldpc256CltuGenerator.CCSDS_START_SEQ);
                byte[] tailSeq = config.getBinary(CLTU_TAIL_SEQ_KEY, CltuGenerator.EMPTY_SEQ);
                cltuGenerator = new Ldpc256CltuGenerator(startSeq, tailSeq);
            } else {
                throw new ConfigurationException(
                        "Invalid value '" + cltuEncoding + " for cltu. Valid values are BCH, LDPC64 or LDPC256");
            }
        }

        multiplexer = new MasterChannelFrameMultiplexer(yamcsInstance, linkName, config);
        subLinks = new ArrayList<>();
        for (VcUplinkHandler vch : multiplexer.getVcHandlers()) {
            if (vch instanceof Link) {
                Link l = (Link) vch;
                subLinks.add(l);
                l.setParent(this);
            }
        }
        

        // Setup tc postprocessor
        initPostprocessor(yamcsInstance, config);
    }

    @Override
    protected void openDevice() {
        if (serialPort == null) {
            super.openDevice();
            try {            
                serialTcFrameLink = new SerialTcFrameLink(this.serialPort);
                serialTcFrameLink.init(yamcsInstance, linkName + "2", config);
                Thread thread = new Thread(serialTcFrameLink);
                thread.start();
                this.outputStream = serialPort.getOutputStream();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    protected void initPostprocessor(String instance, YConfiguration config) throws ConfigurationException {
        String commandPostprocessorClassName = GenericCommandPostprocessor.class.getName();
        YConfiguration commandPostprocessorArgs = null;

        // The GenericCommandPostprocessor class does nothing if there are no arguments, which is what we want.
        if (config != null) {
            commandPostprocessorClassName = config.getString("commandPostprocessorClassName",
                    GenericCommandPostprocessor.class.getName());
            if (config.containsKey("commandPostprocessorArgs")) {
                commandPostprocessorArgs = config.getConfig("commandPostprocessorArgs");
            }
        }

        // Instantiate
        try {
            if (commandPostprocessorArgs != null) {
                cmdPostProcessor = YObjectLoader.loadObject(commandPostprocessorClassName, instance,
                        commandPostprocessorArgs);
            } else {
                cmdPostProcessor = YObjectLoader.loadObject(commandPostprocessorClassName, instance);
            }
        } catch (ConfigurationException e) {
            log.error("Cannot instantiate the command postprocessor", e);
            throw e;
        }
    }

    @Override
    public long getDataOutCount() {
        return dataOutCount.get();
    }

    @Override
    public void resetCounters() {
        super.resetCounters();
        dataOutCount.set(0);
    }

    @Override
    public AggregatedDataLink getParent() {
        return parent;
    }

    @Override
    public void setParent(AggregatedDataLink parent) {
        this.parent = parent;
    }

    @Override
    protected long getCurrentTime() {
        if (timeService != null) {
            return timeService.getMissionTime();
        }
        return TimeEncoding.getWallclockTime();
    }

    // @Override
    // public void sendTc(PreparedCommand pc) {
    // byte[] binary = cmdPostProcessor.process(pc);
    // if (binary == null) {
    // log.warn("command postprocessor did not process the command");
    // return;
    // }
    //
    // int retries = 5;
    // boolean sent = false;
    // int stride = 0;
    //
    // if(this.burstRate > 0) {
    // stride = this.burstRate;
    // } else {
    // stride = binary.length;
    // }
    //
    // String reason = null;
    // int bytesWritten = 0;
    // while (!sent && (retries > 0)) {
    // try {
    // if(serialPort == null) {
    // openDevice();
    // }
    // WritableByteChannel channel = Channels.newChannel(outputStream);
    //
    // while(bytesWritten < binary.length) {
    // byte[] fragment = new byte[stride];
    // System.arraycopy(binary, bytesWritten, fragment, 0, stride);
    // ByteBuffer bb = ByteBuffer.wrap(fragment);
    // // Must do this as this can become a quirk in some java versions.
    // // Read https://github.com/eclipse/jetty.project/issues/3244 for details
    // ((Buffer)bb).rewind();
    //
    // bytesWritten += channel.write(bb);
    //
    // if(this.burstDelay > 0) {
    // Thread.sleep(this.burstDelay);
    // }
    //
    // }
    //
    // dataOutCount.getAndIncrement();
    // sent = true;
    // } catch (IOException e) {
    // reason = String.format("Error writing to TC device to %s : %s", deviceName, e.getMessage());
    // log.warn(reason);
    // try {
    // if (serialPort != null) {
    // serialPort.close();
    // }
    // serialPort = null;
    // } catch (IOException e1) {
    // e1.printStackTrace();
    // }
    // } catch (InterruptedException e) {
    // log.warn("exception {} thrown when sleeping for burstDelay", e.toString());
    // Thread.currentThread().interrupt();
    // }
    // retries--;
    // if (!sent && (retries > 0)) {
    // try {
    // log.warn("Command not sent, retrying in 2 seconds");
    // Thread.sleep(2000);
    // } catch (InterruptedException e) {
    // log.warn("exception {} thrown when sleeping 2 sec", e.toString());
    // Thread.currentThread().interrupt();
    // }
    // }
    // }
    // if (sent) {
    // ackCommand(pc.getCommandId());
    // } else {
    // failedCommand(pc.getCommandId(), reason);
    // }
    //
    // }

    //
    // @Override
    // public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryListener) {
    // this.commandHistoryPublisher = commandHistoryListener;
    // cmdPostProcessor.setCommandHistoryPublisher(commandHistoryListener);
    // }

    /** Send to command history the failed command */
    protected void failedCommand(CommandId commandId, String reason) {
        log.debug("Failing command {}: {}", commandId, reason);
        long currentTime = getCurrentTime();
        commandHistoryPublisher.publishAck(commandId, AcknowledgeSent, currentTime, AckStatus.NOK, reason);
        commandHistoryPublisher.commandFailed(commandId, currentTime, reason);
    }

    /**
     * send an ack in the command history that the command has been sent out of the link
     * 
     * @param commandId
     */
    protected void ackCommand(CommandId commandId) {
        commandHistoryPublisher.publishAck(commandId, AcknowledgeSent, getCurrentTime(), AckStatus.OK);
    }

//    @Override
//    public void run() {
//        if (initialDelay > 0) {
//            try {
//                Thread.sleep(initialDelay);
//                initialDelay = -1;
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                return;
//            }
//        }
//
//        while (isRunningAndEnabled()) {
//            // TmPacket tmpkt = getNextPacket();
//            // if (tmpkt == null) {
//            // break;
//            // }
//            // processPacket(tmpkt);
//        }
//    }

//    @Override
//    public void run() {
//        while (isRunningAndEnabled()) {
//            System.out.println("run");
//            if (rateLimiter != null) {
//                rateLimiter.acquire();
//            }
//            System.out.println("run2");
//            TcTransferFrame tf = multiplexer.getFrame();
//            System.out.println("run3");
//
//            if (tf != null) {
//                System.out.println("run4");
//
//                byte[] data = tf.getData();
//                if (log.isTraceEnabled()) {
//                    log.trace("Outgoing frame data: {}", StringConverter.arrayToHexString(data, true));
//                }
//
//                if (cltuGenerator != null) {
//                    System.out.println("$$makeCltu$$");
//                    System.out.println("before:"+ StringConverter.arrayToHexString(data));
//                    data = cltuGenerator.makeCltu(data);
//                    System.out.println("after:"+ StringConverter.arrayToHexString(data));
//                    if (log.isTraceEnabled()) {
//                        log.trace("Outgoing CLTU: {}", StringConverter.arrayToHexString(data, true));
//                    }
//                }
//
//                if (tf.isBypass()) {
//                    System.out.println("tf.isBypass():" + tf.isBypass());
//                    ackBypassFrame(tf);
//                }
//
//                OutputStream outStream = null;
//                try {
//                    outStream = serialPort.getOutputStream();
//                } catch (IOException e1) {
//                    // TODO Auto-generated catch block
//                    e1.printStackTrace();
//                }
//                try {
//                    System.out.println("cltuStart Token:" + getConfig().getString("cltuStartSequence"));
//                    byte[] beginSyncSymbol = getConfig().getString("cltuStartSequence").getBytes();
////                    beginSyncSymbol[0] = (byte) 0xEB;
////                    beginSyncSymbol[1] = (byte) 0x90;
//                    
//                    System.out.println("cltuTail Token:" + getConfig().getString("cltuTailSequence"));
//
//
//                    byte[] endSyncSymbol = getConfig().getString("cltuTailSequence").getBytes();
////                        { (byte) 0xc5, (byte) 0xc5, (byte) 0xc5, (byte) 0xc5, (byte) 0xc5,
////                            (byte) 0xc5, (byte) 0xc5, (byte) 0x79 };
////                    getConfig().get("cltuStartSequence");
//
////                    byte[] commandBinary = Bytes.concat(beginSyncSymbol, data, endSyncSymbol);
//                    
//                    System.out.println("beginSyncSymbol:" + StringConverter.arrayToHexString(beginSyncSymbol));
//                    System.out.println("endSyncSymbol:" + StringConverter.arrayToHexString(endSyncSymbol));
//
//                    
//                    System.out.println(StringConverter.arrayToHexString(data));
//
//                    // outStream.write(beginSyncSymbol);
//                    // outStream.write(binary);
//
//                    System.out.println("isRunningAndEnabled");
//                    outStream.write(data);
//
//                    // commandHistoryPublisher.publishAck(pc.getCommandId(), "$$Sent$$", getCurrentTime(),
//                    // CommandHistoryPublisher.AckStatus.OK);
//
//                    // if (sent) {
//                    // ackCommand(pc.getCommandId());
//                    // } else {
//                    // failedCommand(pc.getCommandId(), reason);
//                    // }
//
//                } catch (IOException e) {
//                    // TODO Auto-generated catch block
//                    e.printStackTrace();
//                }
//                frameCount++;
//                System.out.println("run5");
//
//            }
//        }
//    }
    
    @Override
    protected void doDisable() {
        if (thread != null) {
            thread.interrupt();
        }

        if (serialPort != null) {
            try {
                log.info("Closing {}", deviceName);
                serialPort.close();
            } catch (IOException e) {
                log.warn("Exception raised closing the serial port:", e);
            }
            serialPort = null;
        }
    }

    @Override
    protected void doEnable() {
        if (serialPort == null) {
            openDevice();
            log.info("Listening on {}", deviceName);
        }
        thread = new Thread(this);
        thread.start();
    }

    @Override
    public void doStart() {
        try {
            if (!isDisabled()) {
                if (serialPort == null) {
                    openDevice();
                    log.info("Bound to {}", deviceName);
                }
            }

            doEnable();
            notifyStarted();
        } catch (Exception e) {
            log.warn("Exception starting link", e);
            notifyFailed(e);
        }
    }

    @Override
    public void doStop() {
        try {
            if (serialPort != null) {
                try {
                    log.info("Closing {}", deviceName);
                    serialPort.close();
                } catch (IOException e) {
                    log.warn("Exception raised closing the serial port:", e);
                }
                serialPort = null;
            }

            doDisable();
            multiplexer.quit();
            notifyStopped();
        } catch (Exception e) {
            log.warn("Exception stopping link", e);
            notifyFailed(e);
        }
    }
    
    
    @Override
    public void sendTc(PreparedCommand preparedCommand) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void setCommandHistoryPublisher(CommandHistoryPublisher commandHistoryPublisher) {
        // TODO Auto-generated method stub
        
    }
    
    
    /**
     * Ack the BD frames
     * Note: the AD frames are acknowledged in the when the COP1 ack is received
     * 
     * @param tf
     */
    protected void ackBypassFrame(TcTransferFrame tf) {
        System.out.println("ackBypassFrame");
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CommandHistoryPublisher.AcknowledgeSent,
                        timeService.getMissionTime(), AckStatus.OK);
            }
        }
    }

    protected void failBypassFrame(TcTransferFrame tf, String reason) {
        if (tf.getCommands() != null) {
            for (PreparedCommand pc : tf.getCommands()) {
                commandHistoryPublisher.publishAck(pc.getCommandId(), CommandHistoryPublisher.AcknowledgeSent,
                        TimeEncoding.getWallclockTime(), AckStatus.NOK, reason);

                commandHistoryPublisher.commandFailed(pc.getCommandId(), timeService.getMissionTime(), reason);
            }
        }
    }
}
