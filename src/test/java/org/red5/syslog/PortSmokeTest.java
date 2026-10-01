package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.server.SyslogServer;
import org.red5.syslog.server.SyslogServerEventIF;
import org.red5.syslog.server.SyslogServerIF;
import org.red5.syslog.server.SyslogServerSessionlessEventHandlerIF;

class PortSmokeTest {

    @Test
    void udpClientReachesUdpServer() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        SyslogServerIF server = SyslogServer.getThreadedInstance("udp");
        server.getConfig().setHost("127.0.0.1");
        server.getConfig().setPort(15140);
        server.getConfig().addEventHandler(new SyslogServerSessionlessEventHandlerIF() {
            @Override
            public void event(SyslogServerIF s, SocketAddress addr, SyslogServerEventIF ev) {
                got.add(ev.getMessage());
            }

            @Override
            public void initialize(SyslogServerIF s) {
            }

            @Override
            public void destroy(SyslogServerIF s) {
            }

            @Override
            public void exception(SyslogServerIF s, SocketAddress addr, Exception e) {
            }
        });
        try {
            Thread.sleep(200);
            UDPNetSyslogConfig cfg = new UDPNetSyslogConfig();
            cfg.setHost("127.0.0.1");
            cfg.setPort(15140);
            SyslogIF client = Syslog.createInstance("smoke", cfg);
            client.info("hello");
            String msg = got.poll(3, TimeUnit.SECONDS);
            assertTrue(msg != null && msg.contains("hello"), "got: " + msg);
        } finally {
            Syslog.destroyInstance("smoke");
            SyslogServer.shutdown();
        }
    }
}
