package org.yamcs.tctm.ccsds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;

import org.yamcs.ConfigurationException;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.tctm.AbstractTcDataLink;
import org.yamcs.tctm.ccsds.TcManagedParameters.TcVcManagedParameters;
import org.yamcs.utils.StringConverter;
import org.yamcs.utils.TimeEncoding;

/**
 * Assembles command packets into TC frames as per CCSDS 232.0-B-3.
 * <p>
 * All frames have the bypass flag set (i.e. they are BD frames).
 * 
 */
public class TcPacketHandler extends AbstractTcDataLink implements VcUplinkHandler {
    protected BlockingQueue<PreparedCommand> commandQueue;
    final TcVcManagedParameters vmp;
    private TcFrameFactory frameFactory;
    boolean blockSenderOnQueueFull;
    private Semaphore dataAvailableSemaphore;

    public TcPacketHandler(String yamcsInstance, String linkName, TcVcManagedParameters vmp)
            throws ConfigurationException {
        super.init(yamcsInstance, linkName, vmp.config);
        this.vmp = vmp;
        this.frameFactory = vmp.getFrameFactory();

        int queueSize = vmp.config.getInt("tcQueueSize", 10);
        blockSenderOnQueueFull = vmp.config.getBoolean("blockSenderOnQueueFull", false);
        commandQueue = new ArrayBlockingQueue<>(queueSize);
    }

    @Override
    public void sendTc(PreparedCommand preparedCommand) {
        System.out.println("TcPacketHandler:sendTc-------------");
        System.out.println("Thread id for TcPacketHandler:" + Thread.currentThread().getId());
        int framingLength = frameFactory.getFramingLength(vmp.vcId);
        System.out.println("TcPacketHandler:sendTc2-------------");

        int pcLength = cmdPostProcessor.getBinaryLength(preparedCommand);
        
        System.out.println("TcPacketHandler:sendTc3-------------");

        System.out.println("binary command:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));
        
        System.out.println("pcLength:" + pcLength);
        
        System.out.println("framingLength:" + framingLength);
        
        if (framingLength + pcLength > vmp.maxFrameLength) {
            log.warn("Command {} does not fit into frame ({} + {} > {})", preparedCommand.getLoggingId(), framingLength,
                    pcLength, vmp.maxFrameLength);
            failedCommand(preparedCommand.getCommandId(),
                    "Command too large to fit in a frame; cmd size: " + pcLength + "; max frame length: "
                            + vmp.maxFrameLength + "; frame overhead: " + framingLength);
            System.out.println("binary command2:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));
            return;
        }

        if (blockSenderOnQueueFull) {
            
            System.out.println("binary command3:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));

            try {
                commandQueue.put(preparedCommand);
                System.out.println("binary command4:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));

                dataAvailableSemaphore.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedCommand(preparedCommand.getCommandId(), "Interrupted");
            }
        } else {
            System.out.println("binary command5:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));

            if (commandQueue.offer(preparedCommand)) {
                System.out.println("binary command6:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));
                dataAvailableSemaphore.release();
            } else {
                System.out.println("binary command7:" + StringConverter.arrayToHexString(preparedCommand.getBinary()));
                failedCommand(preparedCommand.getCommandId(), "queue full");
            }
        }
    }

    @Override
    public TcTransferFrame getFrame() {

        if (commandQueue.isEmpty()) {
            return null;
        }
        int framingLength = frameFactory.getFramingLength(vmp.vcId);

        int dataLength = 0;
        List<PreparedCommand> l = new ArrayList<>();
        PreparedCommand pc;
        while ((pc = commandQueue.peek()) != null) {
            int pcLength = cmdPostProcessor.getBinaryLength(pc);
            if (framingLength + dataLength + pcLength <= vmp.maxFrameLength) {
                pc = commandQueue.poll();
                if (pc == null) {
                    break;
                }
                System.out.println("(getFrame)binary command:" + StringConverter.arrayToHexString(pc.getBinary()));

                l.add(pc);
                dataLength += pcLength;
                if (!vmp.multiplePacketsPerFrame) {
                    break;
                }
            } else { // command doesn't fit into frame
                break;
            }
        }

        if (l.isEmpty()) {
            return null;
        }
        TcTransferFrame tf = frameFactory.makeFrame(vmp.vcId, dataLength);
        tf.setBypass(true);
        tf.setCommands(l);

        byte[] data = tf.getData();
        int offset = tf.getDataStart();
        for (PreparedCommand pc1 : l) {
            System.out.println("TcTransferFrame:getFrame1");
            byte[] binary = cmdPostProcessor.process(pc1);
            System.out.println("(getFrame2)binary command:" + StringConverter.arrayToHexString(binary));

            if (binary == null) {
                log.warn("command postprocessor did not process the command");
                continue;
            }
            int length = binary.length;
            System.out.println("(getFrame3)binary command:" + StringConverter.arrayToHexString(binary));
            System.out.println("(getFrame4)data command:" + StringConverter.arrayToHexString(data));

            System.arraycopy(binary, 0, data, offset, length);
            System.out.println("(getFrame5)data command:" + StringConverter.arrayToHexString(data));

            System.out.println("(getFrame6)binary command:" + StringConverter.arrayToHexString(binary));
            offset += length;
        }
        dataCount.getAndAdd(l.size());
        frameFactory.encodeFrame(tf);
        return tf;
    }

    @Override
    public long getFirstFrameTimestamp() {
        if (commandQueue.isEmpty()) {
            return TimeEncoding.INVALID_INSTANT;
        }
        return commandQueue.peek().getGenerationTime();
    }

    @Override
    public VcUplinkManagedParameters getParameters() {
        return vmp;
    }

    @Override
    protected void doStart() {
        notifyStarted();
    }

    @Override
    protected void doStop() {
        notifyStopped();
    }

    @Override
    public void setDataAvailableSemaphore(Semaphore dataAvailableSemaphore) {
        this.dataAvailableSemaphore = dataAvailableSemaphore;

    }

    @Override
    protected Status connectionStatus() {
        return Status.OK;
    }
}
