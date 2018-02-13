package org.yamcs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.yamcs.AbstractIntegrationTest.MyWsListener;
import org.yamcs.api.YamcsConnectionProperties;
import org.yamcs.api.ws.WebSocketClient;
import org.yamcs.api.ws.WebSocketRequest;
import org.yamcs.protobuf.Web.ParameterSubscriptionRequest;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.web.HttpServer;
import org.yamcs.web.websocket.ParameterResource;


public class LongWebsocketFrameTest {
    YamcsConnectionProperties ycp = new YamcsConnectionProperties("localhost", 9191, "LongWebsocketFrameTest");
    
    @BeforeClass
    public static void beforeClass() throws Exception {
        LoggingUtils.enableLogging();
        YConfiguration.setup("LongWebsocketFrameTest");
        new HttpServer().startServer();
        YamcsServer.setupYamcsServer();
        LoggingUtils.enableLogging();
    }
    
    @AfterClass
    public static void shutDownYamcs()  throws Exception {
        YamcsServer.shutDown();
    }

    
    @Test
    public void testWithSmallFrame() throws Exception {
        assertNotNull(testit(65536));
    }
    
    @Test
    public void testWithBigFrame() throws Exception {
        assertNull(testit(1024*1024));
    }
    
    

    private TimeoutException testit(int frameSize) throws Exception {
        MyWsListener wsListener = new MyWsListener();
        WebSocketClient wsClient = new WebSocketClient(ycp, wsListener);
        wsClient.enableReconnection(false);
        wsClient.setMaxFramePayloadLength(frameSize);
        
        wsClient.connect();
        
        assertTrue(wsListener.onConnect.tryAcquire(5, TimeUnit.SECONDS));
        ParameterSubscriptionRequest req = getRequest();
        
        assertTrue(req.toByteArray().length>65535);
        
        WebSocketRequest wsr = new WebSocketRequest("parameter", ParameterResource.WSR_SUBSCRIBE, req);
        TimeoutException timeout = null;
        try {
            wsClient.sendRequest(wsr).get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timeout = e;
        }
        
        wsClient.disconnect();
        
        return timeout;
    }
    
    
    private ParameterSubscriptionRequest getRequest() {
        
        ParameterSubscriptionRequest.Builder b = ParameterSubscriptionRequest.newBuilder();
        for(int i = 0; i<10000; i++) {
            b.addId(NamedObjectId.newBuilder().setName("/very/long/parameter/name"+i).build());
        }
        b.setAbortOnInvalid(false);
        return b.build();
    }
    
}
