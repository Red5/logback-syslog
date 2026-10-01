package org.red5.syslog.impl.backlog;

/**
 * Observer of a {@link RingBufferBackLogHandler}. Callbacks are made outside the handler's lock, on the thread that
 * caused the event, and must not block.
 */
public interface BackLogListener {

    /** The first message of an outage was backlogged; reason describes the failure. Called once per outage. */
    void down(String reason);

    /** A replay drained the whole backlog after an outage; replayed is the number of messages it delivered. */
    void up(int replayed);

    /** count messages were discarded: evicted to make room, not restored after a failed replay, or offered after close. */
    void evicted(int count);
}
