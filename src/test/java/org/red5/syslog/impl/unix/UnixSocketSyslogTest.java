package org.red5.syslog.impl.unix;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogConstants;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.unix.socket.UnixDatagramSocket;
import org.red5.syslog.impl.unix.socket.UnixDatagramTestSeam;
import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;
import org.red5.syslog.testsupport.UnixDatagramServer;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class UnixSocketSyslogTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetSeam() {
        UnixDatagramTestSeam.reset();
    }

    @Test
    void writesLineToUnixStreamSocket() throws Exception {
        Path dir = Files.createTempDirectory("sl");
        Path sock = dir.resolve("log.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
            cfg.setType(SyslogConstants.SOCK_STREAM);
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
    @EnabledOnOs(OS.LINUX)
    void datagramIsTheDefaultAndSendsOneDatagramPerMessage() throws Exception {
        Assumptions.assumeTrue(UnixDatagramSocket.isAvailable(), UnixDatagramSocket::unavailableReason);
        UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
        assertEquals(SyslogConstants.SOCK_DGRAM, cfg.getType());
        assertEquals("/dev/log", cfg.getPath());
        try (UnixDatagramServer server = UnixDatagramServer.bind(tmp.resolve("dgram.sock"))) {
            cfg.setPath(server.path().toString());
            SyslogIF syslog = Syslog.createInstance("unix-dgram", cfg);
            try {
                syslog.info("over-unix-dgram");
                syslog.info("second \u00fc");
                String m = server.poll(3000);
                assertNotNull(m);
                assertTrue(m.endsWith("over-unix-dgram"), "no newline framing: [" + m + "]");
                assertTrue(m.startsWith("<14>"), m);   // USER + INFO
                String m2 = server.poll(3000);
                assertNotNull(m2);
                assertTrue(m2.endsWith("second \u00fc"), m2);
                assertNull(server.poll(100));
            } finally {
                Syslog.destroyInstance("unix-dgram");
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void datagramFailureThrowsAndReconnectsLazily() throws Exception {
        Assumptions.assumeTrue(UnixDatagramSocket.isAvailable(), UnixDatagramSocket::unavailableReason);
        Path sock = tmp.resolve("later.sock");
        UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
        cfg.setPath(sock.toString());
        cfg.setThrowExceptionOnWrite(true);
        SyslogIF syslog = Syslog.createInstance("unix-dgram-fail", cfg);
        try {
            SyslogRuntimeException e = assertThrows(SyslogRuntimeException.class, () -> syslog.info("lost"));
            assertInstanceOf(IOException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("ENOENT"), e.getCause().getMessage());
            try (UnixDatagramServer server = UnixDatagramServer.bind(sock)) {
                syslog.info("after-bind");
                String m = server.poll(3000);
                assertNotNull(m);
                assertTrue(m.endsWith("after-bind"), m);
            }
        } finally {
            Syslog.destroyInstance("unix-dgram-fail");
        }
    }

    @Test
    void datagramUnavailableNamesReasonAndAlternatives() {
        UnixDatagramTestSeam.forceUnavailable("no foreign function API here");
        UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
        cfg.setType(SyslogConstants.SOCK_DGRAM);
        SyslogRuntimeException e = assertThrows(SyslogRuntimeException.class, () -> Syslog.createInstance("unix-dgram-na", cfg));
        String m = e.getMessage();
        assertTrue(m.contains("no foreign function API here"), m);
        assertTrue(m.contains("STREAM"), m);
        assertTrue(m.contains("UDP/TCP to 127.0.0.1"), m);
        assertFalse(Syslog.exists("unix-dgram-na"));
    }

    @Test
    void registryDefaultsSkipUnixInstancesWhenDatagramIsUnavailable() {
        Syslog.shutdown();
        try {
            UnixDatagramTestSeam.forceUnavailable("forced for the registry test");
            assertDoesNotThrow(Syslog::initialize);
            assertTrue(Syslog.exists(SyslogConstants.UDP));
            assertTrue(Syslog.exists(SyslogConstants.TCP));
            assertFalse(Syslog.exists(SyslogConstants.UNIX_SOCKET));
            assertFalse(Syslog.exists(SyslogConstants.UNIX_SYSLOG));
        } finally {
            UnixDatagramTestSeam.reset();
            Syslog.shutdown();
            Syslog.initialize();
        }
        assertEquals(UnixDatagramSocket.isAvailable(), Syslog.exists(SyslogConstants.UNIX_SOCKET));
    }
}
