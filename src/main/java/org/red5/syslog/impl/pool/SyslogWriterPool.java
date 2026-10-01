package org.red5.syslog.impl.pool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.red5.syslog.impl.AbstractSyslogWriter;

/**
 * Bounded pool of syslog writers. Replaces Apache Commons Pool.
 */
public final class SyslogWriterPool {

    private final Supplier<AbstractSyslogWriter> factory;
    private final BlockingQueue<AbstractSyslogWriter> idle;
    private final Semaphore permits;
    private final long maxWaitMillis;
    private volatile boolean closed;

    public SyslogWriterPool(Supplier<AbstractSyslogWriter> factory, int maxActive, long maxWaitMillis) {
        int cap = maxActive > 0 ? maxActive : Integer.MAX_VALUE >> 4;
        this.factory = factory;
        this.idle = new ArrayBlockingQueue<>(Math.min(cap, 1024));
        this.permits = new Semaphore(cap);
        this.maxWaitMillis = maxWaitMillis;
    }

    /** Returns a writer, or null if none became available within maxWait. */
    public AbstractSyslogWriter borrow() throws InterruptedException {
        if (closed || !permits.tryAcquire(maxWaitMillis < 0 ? Long.MAX_VALUE : maxWaitMillis, TimeUnit.MILLISECONDS)) {
            return null;
        }
        AbstractSyslogWriter w = idle.poll();
        if (w == null) {
            try {
                w = factory.get();
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
        }
        return w;
    }

    public void release(AbstractSyslogWriter w) {
        if (w == null) {
            return;
        }
        permits.release();
        if (closed || !idle.offer(w)) {
            w.shutdown();
        }
    }

    public void clear() {
        AbstractSyslogWriter w;
        while ((w = idle.poll()) != null) {
            w.shutdown();
        }
    }

    public void close() {
        closed = true;
        clear();
    }
}
