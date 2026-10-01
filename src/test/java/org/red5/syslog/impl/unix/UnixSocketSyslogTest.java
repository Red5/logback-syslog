package org.red5.syslog.impl.unix;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogConstants;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class UnixSocketSyslogTest {

    @Test
    void writesLineToUnixStreamSocket() throws Exception {
        Path dir = Files.createTempDirectory("sl");
        Path sock = dir.resolve("log.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
            cfg.setPath(sock.toString());
            SyslogIF syslog = Syslog.createInstance("unix-test", cfg);
            syslog.info("over-unix");
            try (SocketChannel client = server.accept();
                    BufferedReader r = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                assertNotNull(line);
                assertTrue(line.contains("over-unix"), line);
            }
        } finally {
            Syslog.destroyInstance("unix-test");
            Files.deleteIfExists(sock);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void datagramTypeIsRejected() {
        UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
        cfg.setType(SyslogConstants.SOCK_DGRAM);
        assertThrows(SyslogRuntimeException.class, () -> Syslog.createInstance("unix-dgram", cfg));
    }
}
