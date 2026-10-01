package org.red5.syslog.impl.unix.socket;

import java.io.File;

/** Public bridge to the package-private availability seam of {@link UnixDatagramSocket}, for tests in other packages. */
public final class UnixDatagramTestSeam {

    private UnixDatagramTestSeam() {
    }

    /** Makes {@link UnixDatagramSocket#isAvailable()} report false with the given reason until {@link #reset()}. */
    public static void forceUnavailable(String reason) {
        UnixDatagramSocket.forceUnavailable(reason);
    }

    public static void reset() {
        UnixDatagramSocket.reset();
    }

    /** Number of open file descriptors of this process (Linux /proc/self/fd), or -1 when unknown. */
    public static int openFdCount() {
        String[] fds = new File("/proc/self/fd").list();
        return fds == null ? -1 : fds.length;
    }
}
