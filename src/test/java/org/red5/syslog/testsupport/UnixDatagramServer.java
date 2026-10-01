package org.red5.syslog.testsupport;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Test receiver bound to a unix datagram socket path. The JDK has no API to bind a unix datagram socket, so this uses
 * the same reflective java.lang.foreign technique as UnixDatagramSocket: socket(AF_UNIX, SOCK_DGRAM, 0), bind, and a
 * non-blocking recv loop with a bounded deadline. Linux only (sockaddr_un layout and MSG_DONTWAIT value).
 */
public final class UnixDatagramServer implements AutoCloseable {

    private static final int AF_UNIX = 1;
    private static final int SOCK_DGRAM = 2;
    private static final int MSG_DONTWAIT = 0x40;
    private static final int SOL_SOCKET = 1;
    private static final int SO_RCVBUF = 8;
    private static final int SOCKADDR_SIZE = 110;
    private static final int MAX_DATAGRAM = 65536;

    private static Libc libc;

    private final Path path;
    private int fd;

    private UnixDatagramServer(Path path, int fd) {
        this.path = path;
        this.fd = fd;
    }

    public static UnixDatagramServer bind(Path path) throws IOException {
        return bind(path, 0);
    }

    /** Binds a receiver at path; rcvBuf greater than 0 sets SO_RCVBUF (the kernel doubles it and applies a minimum). */
    public static synchronized UnixDatagramServer bind(Path path, int rcvBuf) throws IOException {
        if (libc == null) {
            libc = new Libc();
        }
        Files.deleteIfExists(path);
        int fd = (int) libc.call(libc.socket, AF_UNIX, SOCK_DGRAM, 0);
        if (fd < 0) {
            throw new IOException("socket() failed");
        }
        try (Libc.Scope s = libc.scope()) {
            if (rcvBuf > 0) {
                Object v = s.alloc(4);
                s.buffer(v).putInt(0, rcvBuf);
                if ((int) libc.call(libc.setsockopt, fd, SOL_SOCKET, SO_RCVBUF, v, 4) != 0) {
                    throw new IOException("setsockopt(SO_RCVBUF) failed");
                }
            }
            byte[] p = path.toString().getBytes(StandardCharsets.UTF_8);
            Object addr = s.alloc(SOCKADDR_SIZE);
            ByteBuffer b = s.buffer(addr);
            b.putShort(0, (short) AF_UNIX);
            b.put(2, p);
            if ((int) libc.call(libc.bind, fd, addr, SOCKADDR_SIZE) != 0) {
                throw new IOException("bind(" + path + ") failed");
            }
        } catch (IOException | RuntimeException e) {
            libc.call(libc.close, fd);
            throw e;
        }
        return new UnixDatagramServer(path, fd);
    }

    public Path path() {
        return path;
    }

    /** Next datagram as UTF-8 text, or null when none arrives within ms. */
    public String poll(long ms) throws IOException, InterruptedException {
        byte[] b = pollBytes(ms);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Next datagram's exact bytes, or null when none arrives within ms. */
    public byte[] pollBytes(long ms) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (true) {
            byte[] b = recvOnce();
            if (b != null) {
                return b;
            }
            if (System.nanoTime() - deadline >= 0) {
                return null;
            }
            Thread.sleep(5);
        }
    }

    /** Reads every datagram currently queued and returns how many there were. */
    public int drain() throws IOException {
        int n = 0;
        while (recvOnce() != null) {
            n++;
        }
        return n;
    }

    private synchronized byte[] recvOnce() throws IOException {
        if (fd < 0) {
            throw new IOException("server closed");
        }
        try (Libc.Scope s = libc.scope()) {
            Object buf = s.alloc(MAX_DATAGRAM);
            long n = (long) libc.call(libc.recv, fd, buf, (long) MAX_DATAGRAM, MSG_DONTWAIT);
            if (n < 0) {
                return null;   // EAGAIN: nothing queued
            }
            byte[] out = new byte[(int) n];
            s.buffer(buf).get(0, out);
            return out;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (fd >= 0) {
            libc.call(libc.close, fd);
            fd = -1;
        }
        Files.deleteIfExists(path);
    }

    /** Reflective libc bindings, without errno capture (a -1 from recv simply means nothing is queued). */
    private static final class Libc {
        final MethodHandle socket, bind, setsockopt, recv, close;
        final Method ofConfined, allocate, asByteBuffer, arenaClose;

        Libc() throws IOException {
            try {
                Class<?> linkerC = Class.forName("java.lang.foreign.Linker");
                Class<?> layoutC = Class.forName("java.lang.foreign.MemoryLayout");
                Class<?> valueLayoutC = Class.forName("java.lang.foreign.ValueLayout");
                Class<?> fdC = Class.forName("java.lang.foreign.FunctionDescriptor");
                Class<?> segC = Class.forName("java.lang.foreign.MemorySegment");
                Class<?> lookupC = Class.forName("java.lang.foreign.SymbolLookup");
                Class<?> arenaC = Class.forName("java.lang.foreign.Arena");
                Class<?> optC = Class.forName("java.lang.foreign.Linker$Option");
                Object linker = linkerC.getMethod("nativeLinker").invoke(null);
                Object lookup = linkerC.getMethod("defaultLookup").invoke(linker);
                Object i = valueLayoutC.getField("JAVA_INT").get(null);
                Object l = valueLayoutC.getField("JAVA_LONG").get(null);
                Object a = valueLayoutC.getField("ADDRESS").get(null);
                Method find = lookupC.getMethod("find", String.class);
                Method of = fdC.getMethod("of", layoutC, Array.newInstance(layoutC, 0).getClass());
                Method down = linkerC.getMethod("downcallHandle", segC, fdC, Array.newInstance(optC, 0).getClass());
                Object noOpts = Array.newInstance(optC, 0);
                Object[][] sigs = {
                        { "socket", i, i, i, i },
                        { "bind", i, i, a, i },
                        { "setsockopt", i, i, i, i, a, i },
                        { "recv", l, i, a, l, i },
                        { "close", i, i } };
                MethodHandle[] h = new MethodHandle[sigs.length];
                for (int k = 0; k < sigs.length; k++) {
                    Object[] sig = sigs[k];
                    Object sym = ((Optional<?>) find.invoke(lookup, sig[0])).orElseThrow();
                    Object args = Array.newInstance(layoutC, sig.length - 2);
                    for (int j = 2; j < sig.length; j++) {
                        Array.set(args, j - 2, sig[j]);
                    }
                    h[k] = (MethodHandle) down.invoke(linker, sym, of.invoke(null, sig[1], args), noOpts);
                }
                socket = h[0];
                bind = h[1];
                setsockopt = h[2];
                recv = h[3];
                close = h[4];
                ofConfined = arenaC.getMethod("ofConfined");
                allocate = arenaC.getMethod("allocate", long.class);
                arenaClose = arenaC.getMethod("close");
                asByteBuffer = segC.getMethod("asByteBuffer");
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IOException("java.lang.foreign unavailable: " + e, e);
            }
        }

        Object call(MethodHandle h, Object... args) throws IOException {
            try {
                return h.invokeWithArguments(args);
            } catch (Error | RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException(t);
            }
        }

        Scope scope() throws IOException {
            try {
                return new Scope(ofConfined.invoke(null));
            } catch (ReflectiveOperationException e) {
                throw new IOException(e);
            }
        }

        final class Scope implements AutoCloseable {
            private final Object arena;

            Scope(Object arena) {
                this.arena = arena;
            }

            Object alloc(long size) throws IOException {
                try {
                    return allocate.invoke(arena, size);
                } catch (ReflectiveOperationException e) {
                    throw new IOException(e);
                }
            }

            ByteBuffer buffer(Object segment) throws IOException {
                try {
                    return ((ByteBuffer) asByteBuffer.invoke(segment)).order(ByteOrder.nativeOrder());
                } catch (ReflectiveOperationException e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void close() {
                try {
                    arenaClose.invoke(arena);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
