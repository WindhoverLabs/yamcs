package org.yamcs.tctm;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.yamcs.utils.FileUtils;
import org.yamcs.xtceproc.XtceDbFactory;

public class UdpTcDataLinkTest {

    @BeforeClass
    public static void beforeClass() throws IOException, PluginException, ValidationException, InitException {
        EventProducerFactory.setMockup(false);

        YConfiguration.setupTest("tctm");
        YamcsServer.getServer().prepareStart();
        YamcsServer.getServer().start();
    }

    @Test
    public void testConfig1() throws IOException, ValidationException, InitException, PluginException {
        Map<String, Object> config = new HashMap<>();
        config.put("host", "localhost");
        config.put("port", 10025);
        UdpTcDataLink tcuplink = new UdpTcDataLink();
        config.put("commandPostprocessorClassName", IssCommandPostprocessor.class.getName());
        YamcsServer.setMockupTimeService(null);
        
        YamcsServer y = YamcsServer.getServer();
        
        System.out.println("instance" + y.getInstance("udp-link"));
        
        System.out.println( tcuplink.getConfig());
        
        y.createInstance("cfs", null, config, null, config);
        
//        YamcsServerInstance ysi = new YamcsServerInstance("cfs", new InstanceMetadata());

        tcuplink.init("testinst", "name0", YConfiguration.wrap(config));        
        IssCommandPostprocessor icpp = (IssCommandPostprocessor) tcuplink.cmdPostProcessor;
        assertEquals(-1, icpp.getMiniminimumTcPacketLength());
    }

}
