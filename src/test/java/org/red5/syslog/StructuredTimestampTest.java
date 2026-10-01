package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetAddress;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.red5.syslog.server.impl.event.structured.StructuredSyslogServerEvent;

class StructuredTimestampTest {

    private StructuredSyslogServerEvent parse(String ts) throws Exception {
        return new StructuredSyslogServerEvent("<34>1 " + ts + " mymachine su 123 ID47 - body", InetAddress.getLoopbackAddress());
    }

    @Test
    void parsesUtcTimestamp() throws Exception {
        StructuredSyslogServerEvent ev = parse("2003-10-11T22:14:15.003Z");
        assertNotNull(ev.getDateTime());
        assertEquals(Instant.parse("2003-10-11T22:14:15.003Z"), ev.getDate().toInstant());
        assertEquals("mymachine", ev.getHost());
    }

    @Test
    void parsesOffsetTimestamp() throws Exception {
        StructuredSyslogServerEvent ev = parse("2003-08-24T05:14:15.000003-07:00");
        assertNotNull(ev.getDateTime());
        assertEquals(Instant.parse("2003-08-24T12:14:15.000003Z"), ev.getDateTime().toInstant());
    }
}
