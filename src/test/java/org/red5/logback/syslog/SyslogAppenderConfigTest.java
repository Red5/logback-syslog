package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;

class SyslogAppenderConfigTest {

    private LoggerContext configure(String resource) throws Exception {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(ctx);
        jc.doConfigure(getClass().getResource(resource));
        return ctx;
    }

    private static boolean hasError(LoggerContext ctx) {
        return ctx.getStatusManager().getCopyOfStatusList().stream().anyMatch(s -> s.getLevel() == Status.ERROR);
    }

    private static SyslogAppender appenderOf(LoggerContext ctx) {
        return (SyslogAppender) ctx.getLogger("ROOT").getAppender("SYSLOG");
    }

    @Test
    void papertrailConfigWorksUnchanged() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15170, null)) {
            LoggerContext ctx = configure("/papertrail-style.xml");
            try {
                assertFalse(hasError(ctx));
                ctx.getLogger("conf.Test").info("from-xml");
                String m = s.poll(3000);
                assertNotNull(m);
                assertTrue(m.contains("conf.Test from-xml"), m);
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    void papertrailConfigSplitsMessagesLargerThanOneDatagram() throws Exception {
        // raw socket: the CapturingServer's receive buffer is smaller than a full datagram
        try (DatagramSocket ds = new DatagramSocket(15170)) {
            LoggerContext ctx = configure("/papertrail-style.xml");
            try {
                // maxMessageLength=128000 exceeds the 65507 byte UDP limit: it is capped and plain syslog splits the line
                assertTrue(ctx.getStatusManager().getCopyOfStatusList().stream().anyMatch(st -> st.getLevel() == Status.WARN
                        && st.getMessage().contains("lowered to 65507")));
                ctx.getLogger("conf.Big").info("x".repeat(70000));
                ctx.getLogger("conf.Big").info("after-big");
                int xs = 0;
                boolean after = false;
                byte[] b = receive(ds, 3000);
                while (b != null && !(after && xs >= 70000)) {
                    String m = new String(b, StandardCharsets.UTF_8);
                    xs += (int) m.chars().filter(c -> c == 'x').count();
                    after |= m.contains("after-big");
                    b = receive(ds, 1000);
                }
                assertTrue(xs >= 70000, "all chunks of the large payload must arrive, got " + xs);
                assertTrue(after, "later small message must still arrive");
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    void fullFeaturedConfigStartsAndAppliesModifier() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15171, null)) {
            LoggerContext ctx = configure("/full-featured.xml");
            try {
                assertTrue(appenderOf(ctx).isStarted());
                ctx.getLogger("conf.Full").info("hello");
                String m = s.poll(3000);
                assertNotNull(m);
                // the modifier applies to the MSG text, never to MSGID or STRUCTURED-DATA
                assertTrue(m.contains("[r5] [") && m.endsWith("conf.Full hello"), m);
                assertTrue(m.contains("red5"), m);
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    void genericModifiersLoadFromXml() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15174, null)) {
            LoggerContext ctx = configure("/modifiers-generic.xml");
            try {
                assertTrue(appenderOf(ctx).isStarted());
                ctx.getLogger("conf.Mod").info("<b>");
                String m = s.poll(3000);
                assertNotNull(m);
                assertTrue(m.contains("&lt;b&gt;"), m);
                assertTrue(m.endsWith("END"), m);
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    void structuredDataIsSentAsRfc5424Elements() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15172, null)) {
            LoggerContext ctx = configure("/structured-data.xml");
            try {
                assertFalse(hasError(ctx));
                ctx.getLogger("conf.Sd").info("payload");
                String m = s.poll(3000);
                assertNotNull(m);
                assertTrue(m.contains("[meta@1234 k=\"v\" tricky=\"a\\\"b\\\\c\\]d\"]"), m);
                assertTrue(m.contains("[origin@1234 ip=\"10.0.0.1\"]"), m);
                assertTrue(m.endsWith(" payload"), m);
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    void unknownModifierClassIsAnErrorStatusAndTheAppenderStillStarts() throws Exception {
        LoggerContext ctx = configure("/unknown-modifier.xml");
        try {
            assertTrue(hasError(ctx));
            assertTrue(appenderOf(ctx).isStarted());
            ctx.getLogger("conf.Unknown").info("still fine for the application");
        } finally {
            ctx.stop();
        }
    }

    private static StructuredDataParam sd(String id, String name, String value) {
        StructuredDataParam p = new StructuredDataParam();
        p.setId(id);
        if (name != null) {
            p.addParam(name, value);
        }
        return p;
    }

    private void assertRejected(SyslogAppender a, LoggerContext ctx) {
        a.start();
        assertFalse(a.isStarted());
        assertTrue(hasError(ctx));
    }

    private SyslogAppender rfcAppender(LoggerContext ctx) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName("SD");
        a.setSyslogHost("127.0.0.1");
        a.setPort(15175);
        a.setSync(true);
        a.setRfc5424(true);
        return a;
    }

    @Test
    void invalidStructuredDataLeavesAppenderInactive() {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender blankId = rfcAppender(ctx);
        blankId.addStructuredData(sd(" ", "k", "v"));
        assertRejected(blankId, ctx);

        ctx = new LoggerContext();
        SyslogAppender dup = rfcAppender(ctx);
        dup.addStructuredData(sd("a@1", "k", "v"));
        dup.addStructuredData(sd("a@1", "k2", "v2"));
        assertRejected(dup, ctx);

        ctx = new LoggerContext();
        SyslogAppender blankName = rfcAppender(ctx);
        blankName.addStructuredData(sd("a@1", " ", "v"));
        assertRejected(blankName, ctx);

        ctx = new LoggerContext();
        SyslogAppender spaceInId = rfcAppender(ctx);
        spaceInId.addStructuredData(sd("a b", "k", "v"));
        assertRejected(spaceInId, ctx);

        for (String bad : new String[] { "a=b", "a]b", "a\"b", "n".repeat(33) }) {
            ctx = new LoggerContext();
            SyslogAppender badName = rfcAppender(ctx);
            badName.addStructuredData(sd("a@1", bad, "v"));
            assertRejected(badName, ctx);
        }

        ctx = new LoggerContext();
        SyslogAppender nullValue = rfcAppender(ctx);
        nullValue.addStructuredData(sd("a@1", "k", null));
        assertRejected(nullValue, ctx);

        ctx = new LoggerContext();
        SyslogAppender badApp = rfcAppender(ctx);
        badApp.setAppName("has space");
        assertRejected(badApp, ctx);

        ctx = new LoggerContext();
        SyslogAppender longApp = rfcAppender(ctx);
        longApp.setAppName("a".repeat(49));
        assertRejected(longApp, ctx);
    }

    @Test
    void structuredParamKeepsInsertionOrder() {
        StructuredDataParam p = sd("a@1", "z", "1");
        p.addParam("a", "2");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("z", "1");
        expected.put("a", "2");
        assertEquals(expected, p.getParams());
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(p.getParams().keySet()));
    }

    // ---- frame level checks over a raw UDP socket -----------------------------------------

    private SyslogAppender rawAppender(LoggerContext ctx, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName("RAW");
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setSync(true);
        a.setRfc5424(true);
        a.setAppName("red5");
        a.setSuffixPattern("%msg");
        return a;
    }

    private static byte[] receive(DatagramSocket ds, int timeoutMs) throws Exception {
        ds.setSoTimeout(timeoutMs);
        DatagramPacket pk = new DatagramPacket(new byte[70000], 70000);
        try {
            ds.receive(pk);
        } catch (SocketTimeoutException e) {
            return null;
        }
        byte[] out = new byte[pk.getLength()];
        System.arraycopy(pk.getData(), 0, out, 0, out.length);
        return out;
    }

    private static String send(SyslogAppender a, LoggerContext ctx, DatagramSocket ds, String msg) throws Exception {
        a.start();
        assertTrue(a.isStarted());
        Logger l = ctx.getLogger("raw." + System.nanoTime());
        l.addAppender(a);
        l.info(msg);
        byte[] b = receive(ds, 3000);
        assertNotNull(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void frameWithoutStructuredDataUsesNilValue() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (DatagramSocket ds = new DatagramSocket(15176)) {
            SyslogAppender a = rawAppender(ctx, 15176);
            String frame = send(a, ctx, ds, "plain");
            assertTrue(Pattern.matches("<\\d+>1 \\S+ \\S+ red5 - - - plain", frame), frame);
            a.stop();
        }
    }

    @Test
    void frameWithStructuredDataHasFullHeader() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (DatagramSocket ds = new DatagramSocket(15177)) {
            SyslogAppender a = rawAppender(ctx, 15177);
            a.addStructuredData(sd("meta@1234", "k", "v"));
            String frame = send(a, ctx, ds, "hello");
            assertTrue(Pattern.matches("<\\d+>1 \\S+ \\S+ red5 - - \\[meta@1234 k=\"v\"\\] hello", frame), frame);
            String ts = frame.split(" ")[1];
            assertTrue(Pattern.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d{1,6})?(Z|[+-]\\d\\d:\\d\\d)", ts), ts);
            a.stop();
        }
    }

    @Test
    void defaultMaxMessageLengthDependsOnMode() {
        SyslogAppender plain = new SyslogAppender();
        assertEquals(1024, plain.effectiveMaxMessageLength());
        SyslogAppender rfc = new SyslogAppender();
        rfc.setRfc5424(true);
        assertEquals(2048, rfc.effectiveMaxMessageLength());
        rfc.setMaxMessageLength(500);
        assertEquals(500, rfc.effectiveMaxMessageLength());
    }

    @Test
    void rfc5424DefaultLimitTruncatesToASingleFrame() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (DatagramSocket ds = new DatagramSocket(15178)) {
            SyslogAppender a = rawAppender(ctx, 15178);
            String frame = send(a, ctx, ds, "a".repeat(5000));
            assertTrue(Pattern.matches("<\\d+>1 \\S+ \\S+ red5 - - - a+", frame), frame.substring(0, 80));
            assertEquals(2048, frame.getBytes(StandardCharsets.UTF_8).length);
            assertNull(receive(ds, 500), "truncation must not produce a continuation frame");
            a.stop();
        }
    }

    @Test
    void truncationNeverCutsInsideAMultiByteCharacter() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (DatagramSocket ds = new DatagramSocket(15179)) {
            for (int pad = 0; pad < 2; pad++) {   // both parities, so one of them puts the cut inside a 2-byte character
                SyslogAppender a = rawAppender(ctx, 15179);
                a.setName("RAW" + pad);
                a.setMaxMessageLength(200);
                a.start();
                Logger l = ctx.getLogger("mb" + pad);
                l.addAppender(a);
                l.info("a".repeat(pad) + "\u00e9".repeat(300));
                byte[] b = receive(ds, 3000);
                assertNotNull(b);
                assertTrue(b.length <= 200, "length " + b.length);
                assertTrue(b.length >= 198, "length " + b.length);
                try {
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b));
                } catch (CharacterCodingException e) {
                    fail("frame is not valid UTF-8 (pad " + pad + ")");
                }
                assertNull(receive(ds, 300));
                a.stop();
            }
        }
    }
}
