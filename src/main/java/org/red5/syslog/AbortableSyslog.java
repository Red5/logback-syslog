package org.red5.syslog;

/**
 * Optional capability of a {@link SyslogIF} transport: tear the connection down from another thread while a writer may
 * be blocked in connect, handshake or write.
 *
 * <p>The ported writers hold a monitor around blocking socket I/O and interrupting a platform thread does not unblock
 * java.net socket I/O, so {@link SyslogIF#shutdown()} can wait behind a stuck writer. {@link #abort()} takes no lock:
 * it closes the underlying socket or channel, which makes the blocked call fail promptly, and makes the transport
 * refuse to open new connections. It is meant for shutdown; the instance is not usable afterwards.</p>
 */
public interface AbortableSyslog {

    /** Closes the transport's socket or channel without locking and refuses new connections. Idempotent. */
    void abort();
}
