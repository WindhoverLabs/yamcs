package org.yamcs.web.websocket;

import org.yamcs.Processor;
import org.yamcs.ProcessorException;
import org.yamcs.commanding.CommandQueue;
import org.yamcs.commanding.CommandQueueListener;
import org.yamcs.commanding.CommandQueueManager;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.management.ManagementGpbHelper;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Commanding.CommandQueueEntry;
import org.yamcs.protobuf.Commanding.CommandQueueEvent;
import org.yamcs.protobuf.Commanding.CommandQueueEvent.Type;
import org.yamcs.protobuf.Commanding.CommandQueueInfo;
import org.yamcs.protobuf.Yamcs.ProtoDataType;
import org.yamcs.security.SystemPrivilege;

/**
 * Provides realtime command queue subscription via web.
 */
public class CommandQueueResource implements WebSocketResource, CommandQueueListener {

    public static final String RESOURCE_NAME = "cqueues";

    public static final String OP_subscribe = "subscribe";
    public static final String OP_unsubscribe = "unsubscribe";

    private ConnectedWebSocketClient client;

    private Processor processor;
    private volatile boolean subscribed = false;

    public CommandQueueResource(ConnectedWebSocketClient client) {
        this.client = client;
        processor = client.getProcessor();
    }

    @Override
    public WebSocketReply processRequest(WebSocketDecodeContext ctx, WebSocketDecoder decoder)
            throws WebSocketException {

        client.checkSystemPrivilege(ctx.getRequestId(), SystemPrivilege.ControlCommandQueue);

        switch (ctx.getOperation()) {
        case OP_subscribe:
            return subscribe(ctx.getRequestId());
        case OP_unsubscribe:
            return unsubscribe(ctx.getRequestId());
        default:
            throw new WebSocketException(ctx.getRequestId(), "Unsupported operation '" + ctx.getOperation() + "'");
        }
    }

    private WebSocketReply subscribe(int requestId) throws WebSocketException {
        WebSocketReply reply = WebSocketReply.ack(requestId);
        client.sendReply(reply);

        subscribed = true;
        ManagementService mservice = ManagementService.getInstance();
        CommandQueueManager cqueueManager = mservice.getCommandQueueManager(processor);
        if (cqueueManager != null) {
            cqueueManager.registerListener(this);
            for (CommandQueue q : cqueueManager.getQueues()) {
                sendInitialUpdateQueue(q);
            }
        }
        return null;
    }

    private WebSocketReply unsubscribe(int requestId) throws WebSocketException {
        ManagementService mservice = ManagementService.getInstance();
        CommandQueueManager cqueueManager = mservice.getCommandQueueManager(processor);
        if (cqueueManager != null) {
            cqueueManager.removeListener(this);
        }
        subscribed = false;
        return WebSocketReply.ack(requestId);
    }

    @Override
    public void socketClosed() {
        ManagementService mservice = ManagementService.getInstance();
        CommandQueueManager cqueueManager = mservice.getCommandQueueManager(processor);
        if (cqueueManager != null) {
            cqueueManager.removeListener(this);
        }
    }

    @Override
    public void unselectProcessor() {
        ManagementService mservice = ManagementService.getInstance();
        CommandQueueManager cqueueManager = mservice.getCommandQueueManager(processor);
        if (cqueueManager != null) {
            cqueueManager.removeListener(this);
        }
        processor = null;
    }

    @Override
    public void selectProcessor(Processor processor) throws ProcessorException {
        this.processor = processor;
        if (subscribed) {
            ManagementService mservice = ManagementService.getInstance();
            CommandQueueManager cqueueManager = mservice.getCommandQueueManager(processor);
            if (cqueueManager != null) {
                cqueueManager.registerListener(this);
                for (CommandQueue q : cqueueManager.getQueues()) {
                    sendInitialUpdateQueue(q);
                }
            }
        }
    }

    /**
     * right after subcription send the full queue content (commands included). Afterwards the clients get notified by
     * command added/command removed when the queue gets modified.
     */
    private void sendInitialUpdateQueue(CommandQueue q) {
        CommandQueueInfo info = ManagementGpbHelper.toCommandQueueInfo(q, true);
        client.sendData(ProtoDataType.COMMAND_QUEUE_INFO, info);
    }

    @Override
    public void updateQueue(CommandQueue q) {
        CommandQueueInfo info = ManagementGpbHelper.toCommandQueueInfo(q, false);
        client.sendData(ProtoDataType.COMMAND_QUEUE_INFO, info);
    }

    @Override
    public void commandAdded(CommandQueue q, PreparedCommand pc) {
        CommandQueueEntry data = ManagementGpbHelper.toCommandQueueEntry(q, pc);
        CommandQueueEvent.Builder evtb = CommandQueueEvent.newBuilder();
        evtb.setType(Type.COMMAND_ADDED);
        evtb.setData(data);
        client.sendData(ProtoDataType.COMMAND_QUEUE_EVENT, evtb.build());
    }

    @Override
    public void commandRejected(CommandQueue q, PreparedCommand pc) {
        CommandQueueEntry data = ManagementGpbHelper.toCommandQueueEntry(q, pc);
        CommandQueueEvent.Builder evtb = CommandQueueEvent.newBuilder();
        evtb.setType(Type.COMMAND_REJECTED);
        evtb.setData(data);
        client.sendData(ProtoDataType.COMMAND_QUEUE_EVENT, evtb.build());
    }

    @Override
    public void commandSent(CommandQueue q, PreparedCommand pc) {
        CommandQueueEntry data = ManagementGpbHelper.toCommandQueueEntry(q, pc);
        CommandQueueEvent.Builder evtb = CommandQueueEvent.newBuilder();
        evtb.setType(Type.COMMAND_SENT);
        evtb.setData(data);
        client.sendData(ProtoDataType.COMMAND_QUEUE_EVENT, evtb.build());
    }
}
