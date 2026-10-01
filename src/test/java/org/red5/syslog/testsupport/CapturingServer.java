package org.red5.syslog.testsupport;

import java.net.SocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.red5.syslog.server.SyslogServer;
import org.red5.syslog.server.SyslogServerConfigIF;
import org.red5.syslog.server.SyslogServerEventIF;
import org.red5.syslog.server.SyslogServerIF;
import org.red5.syslog.server.SyslogServerSessionEventHandlerIF;
import org.red5.syslog.server.SyslogServerSessionlessEventHandlerIF;
import org.red5.syslog.server.impl.net.tcp.TCPNetSyslogServerConfig;
import org.red5.syslog.server.impl.net.tcp.ssl.SSLTCPNetSyslogServerConfig;
import org.red5.syslog.server.impl.net.udp.UDPNetSyslogServerConfig;

/**
 * Test helper that runs a real ported SyslogServer on a local port and captures the message
 * text of every received event. Protocols: "udp", "tcp", "ssl".
 */
public final class CapturingServer implements AutoCloseable {

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final String protocol;

    private CapturingServer(String protocol) {
        this.protocol = protocol;
    }

    @SuppressWarnings("serial")
    private final class Handler implements SyslogServerSessionEventHandlerIF, SyslogServerSessionlessEventHandlerIF {

        final transient CountDownLatch ready = new CountDownLatch(1);

        @Override
        public void initialize(SyslogServerIF syslogServer) {
            ready.countDown();
        }

        @Override
        public void destroy(SyslogServerIF syslogServer) {
        }

        @Override
        public Object sessionOpened(SyslogServerIF syslogServer, SocketAddress socketAddress) {
            return null;
        }

        @Override
        public void event(Object session, SyslogServerIF syslogServer, SocketAddress socketAddress, SyslogServerEventIF event) {
            messages.add(event.getMessage());
        }

        @Override
        public void exception(Object session, SyslogServerIF syslogServer, SocketAddress socketAddress, Exception exception) {
        }

        @Override
        public void sessionClosed(Object session, SyslogServerIF syslogServer, SocketAddress socketAddress, boolean timeout) {
        }

        @Override
        public void event(SyslogServerIF syslogServer, SocketAddress socketAddress, SyslogServerEventIF event) {
            messages.add(event.getMessage());
        }

        @Override
        public void exception(SyslogServerIF syslogServer, SocketAddress socketAddress, Exception exception) {
        }
    }

    private static SyslogServerConfigIF newConfig(String protocol) {
        switch (protocol.toLowerCase()) {
            case "udp":
                return new UDPNetSyslogServerConfig();
            case "tcp":
                return new TCPNetSyslogServerConfig();
            case "ssl":
                return new SSLTCPNetSyslogServerConfig();
            default:
                throw new IllegalArgumentException("Unknown protocol " + protocol);
        }
    }

    public static CapturingServer start(String protocol, int port, Consumer<SyslogServerConfigIF> customizer) throws Exception {
        CapturingServer cs = new CapturingServer(protocol);
        // replace any registered instance (including the built-in defaults) with a fresh one
        if (SyslogServer.exists(protocol)) {
            SyslogServer.destroyInstance(protocol);
        }
        SyslogServerConfigIF cfg = newConfig(protocol);
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        if (customizer != null) {
            customizer.accept(cfg);
        }
        Handler handler = cs.new Handler();
        cfg.addEventHandler(handler);
        SyslogServer.createThreadedInstance(protocol, cfg);
        // the server calls initialize() on its handlers once the socket is bound
        if (!handler.ready.await(5, TimeUnit.SECONDS)) {
            SyslogServer.destroyInstance(protocol);
            restoreDefault(protocol);
            throw new IllegalStateException("Syslog server " + protocol + " did not start on port " + port);
        }
        return cs;
    }

    public String poll(long ms) throws InterruptedException {
        return messages.poll(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (SyslogServer.exists(protocol)) {
            SyslogServer.destroyInstance(protocol);
        }
        restoreDefault(protocol);
    }

    /** Re-register the built-in udp/tcp instances so later users of the static registry still find them. */
    private static void restoreDefault(String protocol) {
        if (("udp".equalsIgnoreCase(protocol) || "tcp".equalsIgnoreCase(protocol)) && !SyslogServer.exists(protocol)) {
            SyslogServer.createInstance(protocol, newConfig(protocol));
        }
    }
}
