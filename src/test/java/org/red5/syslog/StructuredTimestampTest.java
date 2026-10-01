package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Instant;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.red5.syslog.impl.message.processor.structured.StructuredSyslogMessageProcessor;
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

    @Test
    void processorPrintsAtMostSixFractionDigits() throws Exception {
        Pattern p = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d{1,6})?(Z|[+-]\\d\\d:\\d\\d)$");
        StructuredSyslogMessageProcessor proc = new StructuredSyslogMessageProcessor("app");
        for (int i = 0; i < 50; i++) {
            String header = proc.createSyslogHeader(1, 6, "host", true, true);
            String ts = header.split(" ")[1];
            assertTrue(p.matcher(ts).matches(), ts);
            // round trip: the server event parses what the processor prints
            assertNotNull(parse(ts).getDateTime(), ts);
        }
    }

    @Test
    void serverStillParsesSixAndNineDigitFractions() throws Exception {
        assertEquals(Instant.parse("2003-08-24T12:14:15.123456Z"), parse("2003-08-24T05:14:15.123456-07:00").getDateTime().toInstant());
        assertEquals(Instant.parse("2003-08-24T12:14:15.123456789Z"), parse("2003-08-24T12:14:15.123456789Z").getDateTime().toInstant());
    }
}
