package org.red5.syslog.impl.backlog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RingBufferBackLogHandlerTest {

    @Test
    void keepsNewestWhenFullAndReplaysOnUp() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(2);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        h.log(null, 6, "c", "down");
        assertEquals(2, h.size());
        List<String> replayed = new ArrayList<>();
        h.replay((level, msg) -> replayed.add(msg));
        assertEquals(List.of("b", "c"), replayed);
        assertEquals(0, h.size());
    }

    @Test
    void failedReplayStopsAtFirstFailureAndKeepsOrder() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(10);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        h.log(null, 6, "c", "down");
        List<String> sent = new ArrayList<>();
        boolean drained = h.replay((level, msg) -> {
            if (msg.equals("b")) {
                // a failing write lands back in log() on the replaying thread
                h.log(null, level, msg, "still down");
            } else {
                sent.add(msg);
            }
        });
        assertFalse(drained);
        assertEquals(List.of("a"), sent);
        List<String> rest = new ArrayList<>();
        assertTrue(h.replay((level, msg) -> rest.add(msg)));
        assertEquals(List.of("b", "c"), rest, "failed entry is not duplicated and later entries are not lost");
    }

    @Test
    void nestedUpDuringReplayIsIgnored() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(10);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        List<String> sent = new ArrayList<>();
        h.replay((level, msg) -> {
            h.up(null);   // the ported syslog calls up() from inside a successful write
            sent.add(msg);
        });
        assertEquals(List.of("a", "b"), sent);
    }

    @Test
    void failureOnFirstEntryRestoresAllInOrder() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(10);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        h.replay((level, msg) -> {
            if (msg.equals("a")) {
                h.log(null, level, msg, "still down");
            }
        });
        // replay failed on "a"; "a","b" restored at the front
        List<String> rest = new ArrayList<>();
        h.replay((level, msg) -> rest.add(msg));
        assertEquals(List.of("a", "b"), rest);
    }

    /** Records listener callbacks. */
    private static final class Events implements BackLogListener {
        final List<String> calls = new ArrayList<>();
        int evicted;

        @Override
        public void down(String reason) {
            calls.add("down:" + reason);
        }

        @Override
        public void up(int replayed) {
            calls.add("up:" + replayed);
        }

        @Override
        public void evicted(int count) {
            evicted += count;
        }
    }

    @Test
    void listenerSeesOneDownPerOutageEvictionsAndUpWithReplayCount() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(3);
        Events ev = new Events();
        h.setListener(ev);
        for (int i = 0; i < 10; i++) {
            h.log(null, 6, "m" + i, "refused");
        }
        assertEquals(List.of("down:refused"), ev.calls, "one down per outage, not one per message");
        assertEquals(7, ev.evicted);
        assertEquals(3, h.size());
        assertTrue(h.replay((level, msg) -> { }));
        assertEquals(List.of("down:refused", "up:3"), ev.calls);
        h.log(null, 6, "again", "reset");
        assertEquals(List.of("down:refused", "up:3", "down:reset"), ev.calls, "a new outage is reported again");
    }

    @Test
    void failedReplayIsNotAnUpAndRestoreOverflowIsCounted() throws Exception {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(2);
        Events ev = new Events();
        h.setListener(ev);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        boolean drained = h.replay((level, msg) -> {
            // another thread backlogs a newer message while the replay runs
            Thread other = Thread.ofPlatform().start(() -> h.log(null, 6, "c", "down"));
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            h.log(null, level, msg, "still down");   // and "a" fails again
        });
        assertFalse(drained);
        assertEquals(List.of("down:down"), ev.calls, "a failed replay is not a recovery");
        assertEquals(1, ev.evicted, "restoring a and b next to c exceeds capacity 2 by one");
        List<String> rest = new ArrayList<>();
        h.replay((level, msg) -> rest.add(msg));
        assertEquals(List.of("b", "c"), rest);
    }

    @Test
    void closeReturnsLeftoversAndCountsLateMessages() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(5);
        Events ev = new Events();
        h.setListener(ev);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        assertEquals(2, h.close());
        assertEquals(0, h.size());
        h.log(null, 6, "late", "down");
        assertEquals(0, h.size(), "a closed backlog keeps nothing");
        assertEquals(1, ev.evicted, "a message offered after close is counted, not silently lost");
        assertEquals(0, h.close());
    }
}
