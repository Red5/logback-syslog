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
}
