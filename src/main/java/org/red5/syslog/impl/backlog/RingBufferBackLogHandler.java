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
 * <p>
 * An optional {@link BackLogListener} hears about the start of an outage (the first message backlogged), its end (a
 * replay that drained everything) and every message discarded (evicted for room, lost on restore, or offered after
 * {@link #close()}), so an owner can report outages and count loss exactly. Callbacks run outside the lock.
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
    private volatile BackLogListener listener;
    private boolean down;     // guarded by this: an outage is in progress (something was backlogged since the last full replay)
    private boolean closed;   // guarded by this: close() was called; nothing is kept any more

    public RingBufferBackLogHandler(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Sets the listener notified of outages, recoveries and discarded messages; null for none. */
    public void setListener(BackLogListener listener) {
        this.listener = listener;
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
        int discarded = 0;
        boolean wentDown = false;
        synchronized (this) {
            if (closed) {
                discarded = 1;
            } else {
                if (buffer.size() == capacity) {
                    buffer.pollFirst();
                    discarded = 1;
                }
                buffer.addLast(new Entry(level, message));
                if (!down) {
                    down = true;
                    wentDown = true;
                }
            }
        }
        BackLogListener l = listener;
        if (l != null) {
            if (wentDown) {
                l.down(reason);
            }
            if (discarded > 0) {
                l.evicted(discarded);
            }
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
        boolean recovered = false;
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
            } else if (!batch.isEmpty()) {
                synchronized (this) {
                    if (buffer.isEmpty()) {
                        down = false;
                        recovered = true;
                    }
                }
            }
        }
        BackLogListener l = listener;
        if (recovered && l != null) {
            l.up(done);
        }
        return done == batch.size();
    }

    /** Puts undelivered entries back ahead of anything logged meanwhile, dropping the oldest if capacity would be exceeded. */
    private void restore(List<Entry> undelivered) {
        int kept = 0;
        synchronized (this) {
            if (!closed) {
                for (int i = undelivered.size() - 1; i >= 0 && buffer.size() < capacity; i--) {
                    buffer.addFirst(undelivered.get(i));
                    kept++;
                }
            }
        }
        int discarded = undelivered.size() - kept;
        BackLogListener l = listener;
        if (discarded > 0 && l != null) {
            l.evicted(discarded);
        }
    }

    /**
     * Empties the backlog for good: returns the number of messages still waiting, which the caller now owns as lost,
     * and from here on every message offered to {@link #log} is reported through {@link BackLogListener#evicted}.
     */
    public int close() {
        synchronized (this) {
            closed = true;
            int n = buffer.size();
            buffer.clear();
            return n;
        }
    }

    /** The backlogged messages, oldest first; for tests and diagnostics. */
    public synchronized List<String> messages() {
        List<String> out = new ArrayList<>(buffer.size());
        for (Entry e : buffer) {
            out.add(e.message());
        }
        return out;
    }

    public synchronized int size() {
        return buffer.size();
    }
}
