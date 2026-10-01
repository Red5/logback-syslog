package org.red5.syslog.impl.unix.socket;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A connected unix domain datagram socket (SOCK_DGRAM), the kind the local syslog daemon listens on at /dev/log
 * (journald, rsyslog) or /var/run/syslog (macOS).
 *
 * <p>The JDK has no unix datagram channel, so this calls libc ({@code socket}, {@code connect}, {@code send},
 * {@code close}) through the foreign function API in {@code java.lang.foreign}. That API is a preview API in JDK 21 and
 * final from JDK 22; it is used only through reflection, so this class compiles for release 21 without
 * {@code --enable-preview} and runs unchanged on JDK 21 and 22+. The first native call makes the JDK print a one-time
 * warning about a restricted method; run with {@code --enable-native-access=ALL-UNNAMED} to silence it.</p>
 *
 * <p>Sends use {@code MSG_DONTWAIT} and never block: when the receiver's buffer is full the send fails with an
 * IOException (EAGAIN or ENOBUFS), as does a send after the receiver went away (ECONNREFUSED). Error messages carry
 * the errno name and number and the {@code strerror} text; errno is captured by the linker with
 * {@code Linker.Option.captureCallState("errno")}, so nothing the JVM does between calls can clobber it.</p>
 *
 * <p>Platforms: Linux, and macOS and the BSDs. The macOS/BSD values (sockaddr_un with sun_len, MSG_DONTWAIT 0x80,
 * BSD errno numbers) are taken from the platform headers and are untested here; only Linux is tested. Windows and other
 * systems are unsupported, and only 64-bit platforms are supported. See {@link #isAvailable()}.</p>
 *
 * <p>{@link #send} and {@link #close} are thread-safe; {@link #close} may be called from any thread and is
 * idempotent. Native memory is allocated in a confined arena per call and freed before the call returns.</p>
 */
public final class UnixDatagramSocket implements Closeable {

    private static final int AF_UNIX = 1;
    private static final int SOCK_DGRAM = 2;

    /** Constants that differ between the supported operating systems. */
    enum Platform {
        /** sockaddr_un: sun_family (short, native order) at 0, sun_path from 2; 110 bytes in all. SOCK_CLOEXEC 0x80000. */
        LINUX(0x40, 110, false, 0x80000),
        /**
         * macOS, FreeBSD, OpenBSD, NetBSD: sun_len (byte) at 0, sun_family (byte) at 1, sun_path from 2; 106 bytes in all.
         * No SOCK_CLOEXEC type flag (macOS lacks it). Untested.
         */
        BSD(0x80, 106, true, 0);

        final int msgDontWait;
        /** Flag or-ed into the socket type so the descriptor is not inherited by exec'd children; 0 where unsupported. */
        final int sockCloexec;
        final int sockaddrSize;
        /** Longest path in bytes: the path array less its terminating NUL. */
        final int maxPathBytes;
        private final boolean sunLen;

        Platform(int msgDontWait, int sockaddrSize, boolean sunLen, int sockCloexec) {
            this.msgDontWait = msgDontWait;
            this.sockCloexec = sockCloexec;
            this.sockaddrSize = sockaddrSize;
            this.maxPathBytes = sockaddrSize - 2 - 1;
            this.sunLen = sunLen;
        }

        /** The platform for an os.name value, or null when unsupported. */
        static Platform detect(String osName) {
            if (osName == null) {
                return null;
            }
            String os = osName.toLowerCase(Locale.ROOT);
            if (os.startsWith("linux")) {
                return LINUX;
            }
            if (os.startsWith("mac") || os.startsWith("darwin") || os.contains("bsd")) {
                return BSD;
            }
            return null;
        }

        /** Writes a NUL-terminated sockaddr_un for path into b, which must hold {@link #sockaddrSize} bytes. */
        void writeSockaddr(ByteBuffer b, byte[] path) {
            b.order(ByteOrder.nativeOrder());
            if (sunLen) {
                b.put(0, (byte) sockaddrSize);
                b.put(1, (byte) AF_UNIX);
            } else {
                b.putShort(0, (short) AF_UNIX);
            }
            b.put(2, path);
            for (int i = 2 + path.length; i < sockaddrSize; i++) {
                b.put(i, (byte) 0);
            }
        }

        String errnoName(int errno) {
            return this == LINUX ? linuxErrno(errno) : bsdErrno(errno);
        }

        private static String linuxErrno(int e) {
            return switch (e) {
                case 1 -> "EPERM";
                case 2 -> "ENOENT";
                case 4 -> "EINTR";
                case 5 -> "EIO";
                case 9 -> "EBADF";
                case 11 -> "EAGAIN";
                case 12 -> "ENOMEM";
                case 13 -> "EACCES";
                case 14 -> "EFAULT";
                case 20 -> "ENOTDIR";
                case 22 -> "EINVAL";
                case 23 -> "ENFILE";
                case 24 -> "EMFILE";
                case 32 -> "EPIPE";
                case 36 -> "ENAMETOOLONG";
                case 40 -> "ELOOP";
                case 88 -> "ENOTSOCK";
                case 89 -> "EDESTADDRREQ";
                case 90 -> "EMSGSIZE";
                case 91 -> "EPROTOTYPE";
                case 93 -> "EPROTONOSUPPORT";
                case 97 -> "EAFNOSUPPORT";
                case 104 -> "ECONNRESET";
                case 105 -> "ENOBUFS";
                case 106 -> "EISCONN";
                case 107 -> "ENOTCONN";
                case 111 -> "ECONNREFUSED";
                default -> null;
            };
        }

        private static String bsdErrno(int e) {
            return switch (e) {
                case 1 -> "EPERM";
                case 2 -> "ENOENT";
                case 4 -> "EINTR";
                case 5 -> "EIO";
                case 9 -> "EBADF";
                case 12 -> "ENOMEM";
                case 13 -> "EACCES";
                case 14 -> "EFAULT";
                case 20 -> "ENOTDIR";
                case 22 -> "EINVAL";
                case 23 -> "ENFILE";
                case 24 -> "EMFILE";
                case 32 -> "EPIPE";
                case 35 -> "EAGAIN";
                case 38 -> "ENOTSOCK";
                case 39 -> "EDESTADDRREQ";
                case 40 -> "EMSGSIZE";
                case 41 -> "EPROTOTYPE";
                case 43 -> "EPROTONOSUPPORT";
                case 47 -> "EAFNOSUPPORT";
                case 54 -> "ECONNRESET";
                case 55 -> "ENOBUFS";
                case 56 -> "EISCONN";
                case 57 -> "ENOTCONN";
                case 61 -> "ECONNREFUSED";
                case 62 -> "ELOOP";
                case 63 -> "ENAMETOOLONG";
                default -> null;
            };
        }
    }

    /** Set by tests to simulate a JVM or OS without native access; null in production. */
    private static volatile String forcedReason;

    /** Resolves the native bindings once, on first use; class initialization makes this thread-safe. */
    private static final class Holder {
        static final Native NATIVE;
        static final String REASON;

        static {
            Native n = null;
            String reason = null;
            String os = System.getProperty("os.name");
            Platform p = Platform.detect(os);
            if (p == null) {
                reason = unsupportedOsReason(os);
            } else {
                try {
                    n = new Native(p);
                } catch (ClassNotFoundException e) {
                    reason = "java.lang.foreign is not available in this JVM (" + System.getProperty("java.version") + ")";
                } catch (Throwable t) {
                    reason = failureReason(t);
                }
            }
            NATIVE = n;
            REASON = reason;
        }
    }

    private final Native nat;
    private final String path;
    private final Object lock = new Object();
    private volatile int fd;

    private UnixDatagramSocket(Native nat, int fd, String path) {
        this.nat = nat;
        this.fd = fd;
        this.path = path;
    }

    /**
     * Whether unix datagram sockets can be used: Linux, macOS or a BSD on a 64-bit JVM that has
     * {@code java.lang.foreign} and allows native access, with the libc functions found. Never throws.
     */
    public static boolean isAvailable() {
        return forcedReason == null && Holder.NATIVE != null;
    }

    /** Why {@link #isAvailable()} is false, or null when it is true. */
    public static String unavailableReason() {
        String forced = forcedReason;
        return forced != null ? forced : Holder.REASON;
    }

    static String unsupportedOsReason(String osName) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return "Windows is not supported (os.name " + osName + ")";
        }
        return "unsupported operating system: " + osName + " (Linux and macOS/BSD only)";
    }

    /** The unavailable reason for a failure while binding libc. */
    static String failureReason(Throwable t) {
        Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        String reason = "native access to libc failed: " + c;
        if (c instanceof IllegalCallerException) {
            // the JVM restricts native access (e.g. --enable-native-access names other modules, or a future default)
            reason += "; start the JVM with --enable-native-access=ALL-UNNAMED (or --enable-native-access=org.red5.syslog"
                    + " when the jar is on the module path)";
        }
        return reason;
    }

    /** Test seam: makes {@link #isAvailable()} false with the given reason. */
    static void forceUnavailable(String reason) {
        forcedReason = Objects.requireNonNull(reason);
    }

    /** Test seam: undoes {@link #forceUnavailable(String)}. */
    static void reset() {
        forcedReason = null;
    }

    /**
     * Creates a datagram socket connected to the unix socket at path.
     *
     * @throws IOException when datagram sockets are unavailable, the path is empty, contains NUL or is too long, or
     *         socket() or connect() fails (the message names the errno, e.g. ENOENT, ECONNREFUSED, EACCES, EPROTOTYPE)
     */
    public static UnixDatagramSocket open(String path) throws IOException {
        if (!isAvailable()) {
            throw new IOException("unix datagram sockets are unavailable: " + unavailableReason());
        }
        Native n = Holder.NATIVE;
        if (path == null || path.isEmpty()) {
            throw new IOException("unix socket path is empty");
        }
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        for (byte c : p) {
            if (c == 0) {
                throw new IOException("unix socket path contains a NUL character: " + path);
            }
        }
        if (p.length > n.platform.maxPathBytes) {
            throw new IOException("unix socket path too long (" + p.length + " bytes, at most " + n.platform.maxPathBytes + "): " + path);
        }
        try (Native.Scope s = n.scope()) {
            Object state = s.allocState();
            int fd = (int) n.call(n.socket, state, AF_UNIX, SOCK_DGRAM | n.platform.sockCloexec, 0);
            if (fd < 0) {
                throw new IOException("cannot create unix datagram socket for " + path + ": " + n.describe(s.errno(state)));
            }
            try {
                Object addr = s.alloc(n.platform.sockaddrSize);
                n.platform.writeSockaddr(s.buffer(addr), p);
                int rc = (int) n.call(n.connect, state, fd, addr, n.platform.sockaddrSize);
                if (rc != 0) {
                    throw new IOException("cannot connect to unix datagram socket " + path + ": " + n.describe(s.errno(state)));
                }
            } catch (IOException | RuntimeException | Error e) {
                n.closeQuietly(fd);
                throw e;
            }
            return new UnixDatagramSocket(n, fd, path);
        }
    }

    /**
     * Sends bytes {@code off .. off+len-1} of data as one datagram, without blocking.
     *
     * @throws IOException when the socket is closed, the receiver's buffer is full (EAGAIN, ENOBUFS), the receiver is
     *         gone (ECONNREFUSED), or the datagram was not sent whole
     */
    public void send(byte[] data, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, data.length);
        synchronized (lock) {
            int f = fd;
            if (f < 0) {
                throw new IOException("unix datagram socket closed: " + path);
            }
            try (Native.Scope s = nat.scope()) {
                Object state = s.allocState();
                Object buf = s.alloc(Math.max(1, len));
                s.buffer(buf).put(0, data, off, len);
                long n = (long) nat.call(nat.send, state, f, buf, (long) len, nat.platform.msgDontWait);
                if (n < 0) {
                    throw new IOException("cannot send to unix datagram socket " + path + ": " + nat.describe(s.errno(state)));
                }
                if (n != len) {
                    throw new IOException("short send to unix datagram socket " + path + ": " + n + " of " + len + " bytes");
                }
            }
        }
    }

    /** Whether the socket is still open. */
    public boolean isOpen() {
        return fd >= 0;
    }

    /** The file descriptor, or -1 when closed; for tests. */
    int fd() {
        return fd;
    }

    /** The path this socket is connected to. */
    public String path() {
        return path;
    }

    /** Closes the socket; idempotent and safe from any thread (a send never blocks, so this never waits long). */
    @Override
    public void close() {
        synchronized (lock) {
            int f = fd;
            fd = -1;
            if (f >= 0) {
                nat.closeQuietly(f);
            }
        }
    }

    /** Same as {@link #close()}. */
    public void abort() {
        close();
    }

    /** Reflective bindings to libc through java.lang.foreign. */
    private static final class Native {
        final Platform platform;
        final MethodHandle socket, connect, send, close, strerror;
        final long errnoOffset;
        private final Object stateLayout;
        private final Method ofConfined, allocate, allocateLayout, arenaClose, asByteBuffer, reinterpret, address;

        Native(Platform platform) throws ReflectiveOperationException {
            this.platform = platform;
            Class<?> linkerC = Class.forName("java.lang.foreign.Linker");
            Class<?> optC = Class.forName("java.lang.foreign.Linker$Option");
            Class<?> layoutC = Class.forName("java.lang.foreign.MemoryLayout");
            Class<?> pathC = Class.forName("java.lang.foreign.MemoryLayout$PathElement");
            Class<?> valueLayoutC = Class.forName("java.lang.foreign.ValueLayout");
            Class<?> fdC = Class.forName("java.lang.foreign.FunctionDescriptor");
            Class<?> segC = Class.forName("java.lang.foreign.MemorySegment");
            Class<?> lookupC = Class.forName("java.lang.foreign.SymbolLookup");
            Class<?> arenaC = Class.forName("java.lang.foreign.Arena");

            Object jint = valueLayoutC.getField("JAVA_INT").get(null);
            Object jlong = valueLayoutC.getField("JAVA_LONG").get(null);
            Object addr = valueLayoutC.getField("ADDRESS").get(null);
            long addrSize = (long) layoutC.getMethod("byteSize").invoke(addr);
            if (addrSize != 8) {
                // size_t and ssize_t are bound as 64-bit values
                throw new UnsupportedOperationException("only 64-bit platforms are supported");
            }

            Object linker = linkerC.getMethod("nativeLinker").invoke(null);
            Object lookup = linkerC.getMethod("defaultLookup").invoke(linker);
            Method find = lookupC.getMethod("find", String.class);
            Method of = fdC.getMethod("of", layoutC, Array.newInstance(layoutC, 0).getClass());
            Class<?> optArrayC = Array.newInstance(optC, 0).getClass();
            Method downcall = linkerC.getMethod("downcallHandle", segC, fdC, optArrayC);

            // errno is copied by the downcall stub right after the call into a segment passed as an extra leading argument
            Object capture = optC.getMethod("captureCallState", String[].class).invoke(null, (Object) new String[] { "errno" });
            stateLayout = optC.getMethod("captureStateLayout").invoke(null);
            Object errnoPath = Array.newInstance(pathC, 1);
            Array.set(errnoPath, 0, pathC.getMethod("groupElement", String.class).invoke(null, "errno"));
            errnoOffset = (long) layoutC.getMethod("byteOffset", errnoPath.getClass()).invoke(stateLayout, errnoPath);
            Object withErrno = Array.newInstance(optC, 1);
            Array.set(withErrno, 0, capture);
            Object none = Array.newInstance(optC, 0);

            socket = bind(downcall, of, find, linker, lookup, layoutC, "socket", withErrno, jint, jint, jint, jint);
            connect = bind(downcall, of, find, linker, lookup, layoutC, "connect", withErrno, jint, jint, addr, jint);
            send = bind(downcall, of, find, linker, lookup, layoutC, "send", withErrno, jlong, jint, addr, jlong, jint);
            close = bind(downcall, of, find, linker, lookup, layoutC, "close", none, jint, jint);
            strerror = bind(downcall, of, find, linker, lookup, layoutC, "strerror", none, addr, jint);

            ofConfined = arenaC.getMethod("ofConfined");
            allocate = arenaC.getMethod("allocate", long.class);
            allocateLayout = arenaC.getMethod("allocate", layoutC);
            arenaClose = arenaC.getMethod("close");
            asByteBuffer = segC.getMethod("asByteBuffer");
            reinterpret = segC.getMethod("reinterpret", long.class);
            address = segC.getMethod("address");
        }

        private static MethodHandle bind(Method downcall, Method of, Method find, Object linker, Object lookup, Class<?> layoutC,
                String name, Object options, Object ret, Object... args) throws ReflectiveOperationException {
            Object symbol = ((Optional<?>) find.invoke(lookup, name))
                    .orElseThrow(() -> new UnsupportedOperationException("libc symbol not found: " + name));
            Object argLayouts = Array.newInstance(layoutC, args.length);
            for (int i = 0; i < args.length; i++) {
                Array.set(argLayouts, i, args[i]);
            }
            return (MethodHandle) downcall.invoke(linker, symbol, of.invoke(null, ret, argLayouts), options);
        }

        Object call(MethodHandle h, Object... args) throws IOException {
            try {
                return h.invokeWithArguments(args);
            } catch (Error | IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("native call failed: " + t, t);
            }
        }

        void closeQuietly(int fd) {
            try {
                close.invokeWithArguments(fd);
            } catch (Error e) {
                throw e;
            } catch (Throwable ignored) {
                // nothing more to do
            }
        }

        /** "NAME (errno N): strerror text", with whatever parts are known. */
        String describe(int errno) {
            String name = platform.errnoName(errno);
            String text = strerror(errno);
            StringBuilder sb = new StringBuilder(name != null ? name + " (errno " + errno + ")" : "errno " + errno);
            if (text != null && !text.isEmpty()) {
                sb.append(": ").append(text);
            }
            if ("EAGAIN".equals(name) || "ENOBUFS".equals(name)) {
                sb.append(" (receiver buffer full)");
            } else if ("EPROTOTYPE".equals(name)) {
                sb.append(" (not a datagram socket; a stream listener needs the STREAM socket type)");
            }
            return sb.toString();
        }

        private String strerror(int errno) {
            try {
                Object p = strerror.invokeWithArguments(errno);
                if ((long) address.invoke(p) == 0L) {
                    return null;
                }
                // read byte by byte up to the NUL, never past it
                ByteBuffer b = (ByteBuffer) asByteBuffer.invoke(reinterpret.invoke(p, 1024L));
                int n = 0;
                while (n < 1024 && b.get(n) != 0) {
                    n++;
                }
                byte[] out = new byte[n];
                b.get(0, out);
                return new String(out, StandardCharsets.UTF_8);
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                return null;
            }
        }

        Scope scope() throws IOException {
            return new Scope(invoke(ofConfined, null));
        }

        private static Object invoke(Method m, Object target, Object... args) throws IOException {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof Error err) {
                    throw err;
                }
                throw new IOException("native memory operation failed: " + c, c);
            } catch (IllegalAccessException e) {
                throw new IOException(e);
            }
        }

        /** A confined arena: every segment allocated in it is freed by {@link #close()}. */
        final class Scope implements AutoCloseable {
            private final Object arena;

            Scope(Object arena) {
                this.arena = arena;
            }

            Object alloc(long size) throws IOException {
                return invoke(allocate, arena, size);
            }

            /** A call-state segment with the size and alignment of the capture layout. */
            Object allocState() throws IOException {
                return invoke(allocateLayout, arena, stateLayout);
            }

            ByteBuffer buffer(Object segment) throws IOException {
                return ((ByteBuffer) invoke(asByteBuffer, segment)).order(ByteOrder.nativeOrder());
            }

            int errno(Object state) throws IOException {
                return buffer(state).getInt((int) errnoOffset);
            }

            @Override
            public void close() {
                try {
                    invoke(arenaClose, arena);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
}
