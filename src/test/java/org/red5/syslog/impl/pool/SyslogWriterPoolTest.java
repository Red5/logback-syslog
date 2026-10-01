package org.red5.syslog.impl.pool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.red5.syslog.impl.AbstractSyslogWriter;

class SyslogWriterPoolTest {

    static class StubWriter extends AbstractSyslogWriter {
        boolean shut;
        public void write(byte[] m) { }
        public void flush() { }
        public void shutdown() { shut = true; }
        public void runCompleted() { }
    }

    @Test
    void reusesReleasedWriterAndBoundsActive() throws Exception {
        AtomicInteger made = new AtomicInteger();
        SyslogWriterPool pool = new SyslogWriterPool(() -> { made.incrementAndGet(); return new StubWriter(); }, 1, 100);
        AbstractSyslogWriter a = pool.borrow();
        assertNull(pool.borrow(), "second borrow times out when maxActive=1");
        pool.release(a);
        AbstractSyslogWriter b = pool.borrow();
        assertSame(a, b);
        pool.release(b);
        assertEquals(1, made.get());
        pool.close();
        assertTrue(((StubWriter) a).shut);
    }
}
