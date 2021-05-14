package org.yamcs.tctm;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;
import org.yamcs.InitException;
import org.yamcs.PluginException;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducerFactory;

public class UdpTcDataLinkTest {

    @BeforeClass
    public static void beforeClass() throws IOException, PluginException, ValidationException, InitException {
        EventProducerFactory.setMockup(false);

        YConfiguration.setupTest("tctm");
        YamcsServer.getServer().prepareStart();
        YamcsServer.getServer().start();
    }

    @Test
    public void testConfig() throws IOException, ValidationException, InitException, PluginException {
        Map<String, Object> config = new HashMap<>();
        config.put("host", "localhost");
        config.put("port", 10025);
        UdpTcDataLink tcUpLink = new UdpTcDataLink();
        
        tcUpLink.init("udp-link", "tc_realtime", YConfiguration.wrap(config));
        
        assertEquals(tcUpLink.getConfig().getInt("port"), 10025);
        assertEquals(tcUpLink.getConfig().getString("host"), "localhost");
    }
    
    @Test
    public void testFalseConfig() throws IOException, ValidationException, InitException, PluginException {
        Map<String, Object> config = new HashMap<>();
        config.put("host", "localhost2");
        config.put("port", 10026);
        UdpTcDataLink tcUpLink = new UdpTcDataLink();        
        tcUpLink.init("udp-link", "tc_realtime", YConfiguration.wrap(config));
        
        assertFalse(null, tcUpLink.getConfig().getInt("port") == 10025);
        assertFalse(null, tcUpLink.getConfig().getString("host").equals("localhost"));
    }

}
