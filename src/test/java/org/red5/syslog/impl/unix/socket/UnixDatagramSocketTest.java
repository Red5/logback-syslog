package org.red5.syslog.impl.unix.socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.testsupport.UnixDatagramServer;

/** Unix datagram socket through reflective java.lang.foreign; the socket tests run on Linux only. */
class UnixDatagramSocketTest {

    @TempDir
    Path dir;

    @BeforeEach
    @AfterEach
    void resetSeam() {
        UnixDatagramSocket.reset();
    }

    private static void assumeLinuxAvailable() {
        Assumptions.assumeTrue(UnixDatagramSocket.isAvailable(), () -> "unavailable: " + UnixDatagramSocket.unavailableReason());
    }

    // ---- availability (all platforms) -------------------------------------------------

    @Test
    void platformDetection() {
        assertEquals(UnixDatagramSocket.Platform.LINUX, UnixDatagramSocket.Platform.detect("Linux"));
        assertEquals(UnixDatagramSocket.Platform.BSD, UnixDatagramSocket.Platform.detect("Mac OS X"));
        assertEquals(UnixDatagramSocket.Platform.BSD, UnixDatagramSocket.Platform.detect("FreeBSD"));
        assertNull(UnixDatagramSocket.Platform.detect("Windows 11"));
        assertNull(UnixDatagramSocket.Platform.detect("SunOS"));
        assertNull(UnixDatagramSocket.Platform.detect(null));
        assertTrue(UnixDatagramSocket.unsupportedOsReason("Windows 11").contains("Windows is not supported"));
        assertTrue(UnixDatagramSocket.unsupportedOsReason("SunOS").contains("SunOS"));
    }

    @Test
    void sockaddrLayouts() {
        UnixDatagramSocket.Platform linux = UnixDatagramSocket.Platform.LINUX;
        assertEquals(110, linux.sockaddrSize);
        assertEquals(107, linux.maxPathBytes);
        assertEquals(0x40, linux.msgDontWait);
        UnixDatagramSocket.Platform bsd = UnixDatagramSocket.Platform.BSD;
        assertEquals(106, bsd.sockaddrSize);
        assertEquals(103, bsd.maxPathBytes);
        assertEquals(0x80, bsd.msgDontWait);
        byte[] b = new byte[bsd.sockaddrSize];
        bsd.writeSockaddr(ByteBuffer.wrap(b), "/x".getBytes(StandardCharsets.US_ASCII));
        assertEquals(106, b[0]);   // sun_len
        assertEquals(1, b[1]);     // sun_family AF_UNIX
        assertEquals('/', b[2]);
        assertEquals('x', b[3]);
        assertEquals(0, b[4]);     // NUL terminated
    }

    @Test
    void forcedUnavailableIsReportedAndResetRestores() {
        boolean before = UnixDatagramSocket.isAvailable();
        UnixDatagramSocket.forceUnavailable("forced for test");
        assertFalse(UnixDatagramSocket.isAvailable());
        assertEquals("forced for test", UnixDatagramSocket.unavailableReason());
        IOException e = assertThrows(IOException.class, () -> UnixDatagramSocket.open(dir.resolve("x.sock").toString()));
        assertTrue(e.getMessage().contains("forced for test"), e.getMessage());
        UnixDatagramSocket.reset();
        assertEquals(before, UnixDatagramSocket.isAvailable());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unavailableOnWindows() {
        assertFalse(UnixDatagramSocket.isAvailable());
        assertTrue(UnixDatagramSocket.unavailableReason().contains("Windows"));
    }

    // ---- Linux socket behaviour ---------------------------------------------------------

    @Test
    @EnabledOnOs(OS.LINUX)
    void availableOnLinux() {
        assertTrue(UnixDatagramSocket.isAvailable(), UnixDatagramSocket.unavailableReason());
        assertNull(UnixDatagramSocket.unavailableReason());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void roundTripExactBytes() throws Exception {
        assumeLinuxAvailable();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("rt.sock"));
                UnixDatagramSocket s = UnixDatagramSocket.open(server.path().toString())) {
            byte[] ascii = "<134>hello".getBytes(StandardCharsets.UTF_8);
            byte[] utf8 = "<134>grüße 日本 ✓ 😀".getBytes(StandardCharsets.UTF_8);
            s.send(ascii, 0, ascii.length);
            s.send(utf8, 0, utf8.length);
            s.send(utf8, 5, 3);
            assertArrayEquals(ascii, server.pollBytes(2000));
            assertArrayEquals(utf8, server.pollBytes(2000));
            assertArrayEquals(Arrays.copyOfRange(utf8, 5, 8), server.pollBytes(2000));
            assertNull(server.pollBytes(100), "one datagram per send, no extra framing");
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingPathReportsEnoent() {
        assumeLinuxAvailable();
        IOException e = assertThrows(IOException.class, () -> UnixDatagramSocket.open(dir.resolve("absent.sock").toString()));
        assertTrue(e.getMessage().contains("ENOENT"), e.getMessage());
        assertTrue(e.getMessage().contains("No such file or directory"), e.getMessage());
        assertTrue(e.getMessage().contains("absent.sock"), e.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void regularFileIsRefused() throws Exception {
        assumeLinuxAvailable();
        Path f = Files.writeString(dir.resolve("plain.txt"), "not a socket");
        IOException e = assertThrows(IOException.class, () -> UnixDatagramSocket.open(f.toString()));
        assertTrue(e.getMessage().matches("(?s).*(ECONNREFUSED|ENOTSOCK|EPROTOTYPE).*"), e.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void streamSocketIsWrongType() throws Exception {
        assumeLinuxAvailable();
        Path p = dir.resolve("stream.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(p));
            IOException e = assertThrows(IOException.class, () -> UnixDatagramSocket.open(p.toString()));
            assertTrue(e.getMessage().contains("EPROTOTYPE"), e.getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void tooLongPathIsRejected() {
        assumeLinuxAvailable();
        String longest = "/" + "a".repeat(106);   // 107 bytes: the Linux maximum, absent so ENOENT
        IOException ok = assertThrows(IOException.class, () -> UnixDatagramSocket.open(longest));
        assertTrue(ok.getMessage().contains("ENOENT"), ok.getMessage());
        String tooLong = "/" + "a".repeat(107);   // 108 bytes
        IOException e = assertThrows(IOException.class, () -> UnixDatagramSocket.open(tooLong));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
        IOException empty = assertThrows(IOException.class, () -> UnixDatagramSocket.open(""));
        assertTrue(empty.getMessage().contains("empty"), empty.getMessage());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void closeIsIdempotentAndSendAfterCloseFails() throws Exception {
        assumeLinuxAvailable();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("close.sock"))) {
            UnixDatagramSocket s = UnixDatagramSocket.open(server.path().toString());
            assertTrue(s.isOpen());
            s.close();
            s.close();
            s.abort();
            assertFalse(s.isOpen());
            byte[] b = "x".getBytes(StandardCharsets.US_ASCII);
            IOException e = assertThrows(IOException.class, () -> s.send(b, 0, 1));
            assertTrue(e.getMessage().contains("closed"), e.getMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void noDescriptorLeakOver200Cycles() throws Exception {
        assumeLinuxAvailable();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("leak.sock"))) {
            byte[] b = "cycle".getBytes(StandardCharsets.US_ASCII);
            cycle(server, b);   // warm up: lazy native setup
            int before = UnixDatagramTestSeam.openFdCount();
            for (int i = 0; i < 200; i++) {
                cycle(server, b);
            }
            int after = UnixDatagramTestSeam.openFdCount();
            assertTrue(after - before <= 2, "fd count grew from " + before + " to " + after);
            server.drain();
        }
    }

    private static void cycle(UnixDatagramServer server, byte[] b) throws IOException {
        UnixDatagramSocket s = UnixDatagramSocket.open(server.path().toString());
        try {
            s.send(b, 0, b.length);
        } finally {
            s.close();
        }
        server.drain();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void neverBlocksWhenReceiverIsFull() throws Exception {
        assumeLinuxAvailable();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("full.sock"), 1024);
                UnixDatagramSocket s = UnixDatagramSocket.open(server.path().toString())) {
            byte[] b = new byte[512];
            Arrays.fill(b, (byte) 'z');
            long start = System.nanoTime();
            IOException failure = null;
            int sent = 0;
            for (int i = 0; i < 20_000 && failure == null; i++) {
                try {
                    s.send(b, 0, b.length);
                    sent++;
                } catch (IOException e) {
                    failure = e;
                }
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertNotNull(failure, "the receiver was never read, yet " + sent + " sends succeeded");
            assertTrue(ms < 5000, "sending took " + ms + " ms");
            assertTrue(failure.getMessage().matches("(?s).*(EAGAIN|EWOULDBLOCK|ENOBUFS).*"), failure.getMessage());
            assertTrue(server.drain() > 0);
            s.send(b, 0, b.length);   // room again
            assertArrayEquals(b, server.pollBytes(2000));
        }
    }
}
