package org.red5.syslog.impl.backlog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

import org.red5.syslog.SyslogBackLogHandlerIF;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.AbstractSyslog;

/**
 * Bounded in-memory backlog: keeps the newest messages while the destination is down and replays them oldest-first.
 * <p>
 * The ported syslog calls {@link #up(SyslogIF)} from inside the first write that succeeds after an outage, i.e. after
 * that write has already reached the server, so an owner that needs strict ordering calls {@link #replay} itself before
 * writing new messages. Replay is re-entrancy safe: it works on an atomically drained snapshot, never holds the lock
 * while calling out, ignores a nested {@code up()} on the replaying thread, and stops at the first message that fails
 * again, restoring it and everything after it to the front of the buffer in their original order.
 */
public class RingBufferBackLogHandler implements SyslogBackLogHandlerIF {

    private record Entry(int level, String message) { }

    /** Per-thread replay marker: set while this thread is inside replay(). */
    private static final class Replay {
        boolean failed;
    }

    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final int capacity;
    private final ThreadLocal<Replay> replaying = new ThreadLocal<>();

    public RingBufferBackLogHandler(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void initialize() throws SyslogRuntimeException {
        // nothing to prepare
    }

    @Override
    public void down(SyslogIF syslog, String reason) {
        // state is implicit: entries accumulate through log()
    }

    @Override
    public void log(SyslogIF syslog, int level, String message, String reason) {
        Replay r = replaying.get();
        if (r != null) {
            // a message being replayed failed again; replay() restores it in order, so do not buffer it a second time
            r.failed = true;
            return;
        }
        synchronized (this) {
            if (buffer.size() == capacity) {
                buffer.pollFirst();
            }
            buffer.addLast(new Entry(level, message));
        }
    }

    @Override
    public void up(SyslogIF syslog) {
        if (syslog instanceof AbstractSyslog as) {
            replay(as::logPrepared);   // buffered messages are already prefixed and wrapped
        } else if (syslog != null) {
            replay(syslog::log);
        }
    }

    /**
     * Drains the buffer oldest-first into the sink (level, message), bounded to the entries present when it starts.
     * Stops at the first message whose delivery fails (the sink reports that by landing in {@link #log}, or by throwing)
     * and puts it and the rest back at the front.
     *
     * @return true if everything drained, false if delivery failed again or a replay is already running on this thread
     */
    public boolean replay(BiConsumer<Integer, String> sink) {
        if (replaying.get() != null) {
            return false;
        }
        List<Entry> batch = new ArrayList<>();
        synchronized (this) {
            for (int n = buffer.size(); n > 0; n--) {
                batch.add(buffer.pollFirst());
            }
        }
        Replay state = new Replay();
        replaying.set(state);
        int done = 0;
        try {
            for (Entry e : batch) {
                state.failed = false;
                try {
                    sink.accept(e.level(), e.message());
                } catch (RuntimeException ex) {
                    state.failed = true;
                }
                if (state.failed) {
                    break;
                }
                done++;
            }
        } finally {
            replaying.remove();
            if (done < batch.size()) {
                restore(batch.subList(done, batch.size()));
            }
        }
        return done == batch.size();
    }

    /** Puts undelivered entries back ahead of anything logged meanwhile, dropping the oldest if capacity would be exceeded. */
    private synchronized void restore(List<Entry> undelivered) {
        for (int i = undelivered.size() - 1; i >= 0 && buffer.size() < capacity; i--) {
            buffer.addFirst(undelivered.get(i));
        }
    }

    public synchronized int size() {
        return buffer.size();
    }
}
