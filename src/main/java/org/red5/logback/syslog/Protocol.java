package org.red5.logback.syslog;

/** Transport used by the appender. */
public enum Protocol {
    UDP, TCP, TLS,
    /**
     * A local unix socket (see {@link UnixSocketType}); the default datagram socket /dev/log takes RFC 3164 frames, since
     * journald and rsyslog do not parse RFC 5424 there.
     */
    UNIX
}
