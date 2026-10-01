package org.red5.logback.syslog;

/**
 * Socket type for protocol UNIX.
 *
 * <ul>
 * <li>{@link #DATAGRAM}: one datagram per message. The local syslog sockets /dev/log (journald, rsyslog) and
 * /var/run/syslog (macOS) are datagram sockets.</li>
 * <li>{@link #STREAM}: a stream connection with newline-terminated messages, for stream listeners such as syslog-ng
 * unix-stream.</li>
 * </ul>
 */
public enum UnixSocketType {
    DATAGRAM, STREAM
}
