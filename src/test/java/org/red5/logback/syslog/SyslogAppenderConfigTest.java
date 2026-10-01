package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

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
    void papertrailConfigSurvivesMessagesLargerThanOneDatagram() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15170, null)) {
            LoggerContext ctx = configure("/papertrail-style.xml");
            try {
                // maxMessageLength=128000 exceeds the 65507 byte UDP limit; the oversized datagram must fail quietly
                ctx.getLogger("conf.Big").info("x".repeat(70000));
                ctx.getLogger("conf.Big").info("after-big");
                String m = s.poll(3000);
                while (m != null && !m.contains("after-big")) {
                    m = s.poll(1000);
                }
                assertNotNull(m, "later small message must still arrive");
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
    void unknownModifierClassIsAnErrorStatusAndNeverThrows() throws Exception {
        LoggerContext ctx = configure("/unknown-modifier.xml");
        try {
            assertTrue(hasError(ctx));
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
}
