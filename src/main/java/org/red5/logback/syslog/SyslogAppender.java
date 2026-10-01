package org.red5.logback.syslog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogFacility;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogLevel;
import org.red5.syslog.SyslogMessageIF;
import org.red5.syslog.SyslogMessageModifierIF;
import org.red5.syslog.impl.AbstractSyslog;
import org.red5.syslog.impl.AbstractSyslogConfig;
import org.red5.syslog.impl.backlog.RingBufferBackLogHandler;
import org.red5.syslog.impl.message.processor.structured.StructuredSyslogMessageProcessor;
import org.red5.syslog.impl.message.structured.StructuredSyslogMessage;
import org.red5.syslog.impl.net.tcp.TCPNetSyslogConfig;
import org.red5.syslog.impl.net.tcp.ssl.SSLTCPNetSyslogConfig;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;

/** Logback appender that ships events to syslog through org.red5.syslog. */
public class SyslogAppender extends AppenderBase<ILoggingEvent> {

    private String syslogHost = "localhost";
    private int port = 514;
    private Protocol protocol = Protocol.UDP;
    private String facility = "USER";
    private String suffixPattern = "[%thread] %logger %msg";
    private String stackTracePattern = "%ex{full}";
    private boolean throwableExcluded;
    private boolean sendLocalName = true;
    private boolean sendLocalTimestamp = true;
    private int maxMessageLength = 1024;
    private String appName;
    private boolean rfc5424;
    private String unixSocketPath = "/dev/log";
    private String sslKeyStore, sslKeyStorePassword, sslTrustStore, sslTrustStorePassword;
    private boolean sync;
    private int queueSize = 4096;
    private boolean blockWhenFull;
    private long shutdownTimeoutMs = 2000;
    private int backlogSize = 1000;

    private final List<SyslogMessageModifierIF> modifiers = new ArrayList<>();
    private final List<StructuredDataParam> structuredData = new ArrayList<>();
    private volatile Map<String, Map<String, String>> structuredDataMap;   // built at start; null when none configured
    private volatile List<SyslogMessageModifierIF> activeModifiers = List.of();

    private static final int UDP_MAX_PAYLOAD = 65507;
    private static final AtomicLong COUNTER = new AtomicLong();

    private PatternLayout layout;
    private PatternLayout stackTraceLayout;
    private SyslogIF syslog;
    private RingBufferBackLogHandler backlog;
    private String instanceName;
    private volatile int facilityCode;

    /** State of one start/stop cycle; an old writer only ever sees its own generation, so a restart cannot revive it. */
    private static final class Generation {
        final BlockingQueue<ILoggingEvent> queue;
        volatile boolean running = true;
        volatile boolean abandoned;   // set when stop() gives up waiting; the writer must exit without sending more
        Thread writer;

        Generation(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }
    }

    private volatile Generation gen;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedWhileStopping = new AtomicLong();
    private final AtomicLong lastDropReport = new AtomicLong();
    private final AtomicLong lastFailureReport = new AtomicLong();
    private final Object stopLock = new Object();
    long dropReportIntervalMs = 10_000;   // package-private for tests

    @Override
    public void start() {
        if (isStarted()) {
            return;
        }
        try {
            String problem = validate();
            if (problem != null) {
                addError("syslog appender [" + getName() + "] not started: " + problem);
                return;
            }
            SyslogFacility fac = SyslogFacility.parse(facility);
            layout = layout(suffixPattern);
            stackTraceLayout = throwableExcluded ? null : layout(stackTracePattern);
            AbstractSyslogConfig cfg = newConfig();
            facilityCode = fac.code();
            cfg.setFacility(fac.code());
            cfg.setThreaded(false);               // queueing is done by this appender
            cfg.setSendLocalName(sendLocalName);
            cfg.setSendLocalTimestamp(sendLocalTimestamp);
            // a UDP datagram carries at most 65507 bytes: a larger limit would make an oversized datagram fail on send, and the
            // failed line would then sit at the head of the backlog and block every later line; capped, the ported splitter
            // breaks long messages into several datagrams instead
            cfg.setMaxMessageLength(protocol == Protocol.UDP ? Math.min(maxMessageLength, UDP_MAX_PAYLOAD) : maxMessageLength);
            cfg.setUseStructuredData(rfc5424);
            if (rfc5424) {
                // RFC 5424 carries the application name in the header (APP-NAME) and the modifiers act on the MSG text
                // only; the ported ident prefix and cfg-level modifiers would land in MSGID and corrupt the frame.
                activeModifiers = List.copyOf(modifiers);
                structuredDataMap = buildStructuredDataMap();
            } else {
                activeModifiers = List.of();
                structuredDataMap = null;
                if (!structuredData.isEmpty()) {
                    addWarn("syslog appender [" + getName() + "]: structuredData is ignored unless rfc5424 is true");
                }
                if (appName != null && !appName.isEmpty()) {
                    cfg.setIdent(appName);
                }
                modifiers.forEach(cfg::addMessageModifier);
            }
            cfg.setThrowExceptionOnWrite(false);  // failures go to the backlog handlers, never into the logging path
            backlog = null;
            if (backlogSize > 0) {
                backlog = new RingBufferBackLogHandler(backlogSize);
                cfg.addBackLogHandler(backlog);
                // one reconnect covers a stale persistent connection; more only multiplies connect timeouts while the server is down
                cfg.setWriteRetries(1);
            }
            // the Syslog registry is JVM-static and case-insensitive, so the name must be unique per appender
            instanceName = "red5-" + (getName() != null ? getName() + "-" : "") + COUNTER.incrementAndGet();
            syslog = Syslog.createInstance(instanceName, cfg);
            if (rfc5424 && syslog instanceof AbstractSyslog as) {
                // per instance: the ported default processor is JVM-wide and would leak one appender's APP-NAME to all
                as.setStructuredMessageProcessor(new StructuredSyslogMessageProcessor(isEmpty(appName) ? null : appName));
            }
        } catch (RuntimeException e) {
            addError("syslog appender [" + getName() + "] not started: " + e.getMessage(), e);
            instanceName = null;
            stopLayouts();
            return;
        }
        if (!sync) {
            Generation g = new Generation(queueSize);
            droppedWhileStopping.set(0);
            gen = g;
            g.writer = Thread.ofVirtual().name("red5-syslog-" + getName()).start(() -> drain(g));
        }
        super.start();
    }

    private String validate() {
        if (protocol == null) {
            return "protocol is null";
        }
        if (suffixPattern == null || suffixPattern.isEmpty()) {
            return "suffixPattern must not be empty";
        }
        if (!throwableExcluded && (stackTracePattern == null || stackTracePattern.isEmpty())) {
            return "stackTracePattern must not be empty unless throwableExcluded is true";
        }
        if (queueSize <= 0) {
            return "queueSize must be positive: " + queueSize;
        }
        if (shutdownTimeoutMs < 0) {
            return "shutdownTimeoutMs must not be negative: " + shutdownTimeoutMs;
        }
        if (backlogSize < 0) {
            return "backlogSize must not be negative: " + backlogSize;
        }
        if (maxMessageLength <= 0) {
            return "maxMessageLength must be positive: " + maxMessageLength;
        }
        if (protocol != Protocol.UNIX) {
            if (syslogHost == null || syslogHost.isEmpty()) {
                return "syslogHost must not be empty";
            }
            if (port < 1 || port > 65535) {
                return "port out of range 1..65535: " + port;
            }
        }
        if (protocol == Protocol.TLS && isEmpty(sslTrustStore) && isEmpty(sslKeyStore)) {
            return "TLS requires sslTrustStore and/or sslKeyStore";
        }
        if (rfc5424) {
            if (!isEmpty(appName) && !isSdName(appName, 48)) {
                return "appName must be 1..48 printable ASCII characters without spaces for rfc5424: " + appName;
            }
            Set<String> ids = new HashSet<>();
            for (StructuredDataParam sd : structuredData) {
                String id = sd.getId();
                if (isEmpty(id) || id.isBlank()) {
                    return "structuredData id must not be blank";
                }
                if (!isSdName(id, 32)) {
                    return "structuredData id must be 1..32 printable ASCII characters without space, '=', ']' or '\"': " + id;
                }
                if (!ids.add(id)) {
                    return "duplicate structuredData id: " + id;
                }
                for (Map.Entry<String, String> p : sd.getParams().entrySet()) {
                    if (p.getKey() == null || p.getKey().isBlank()) {
                        return "structuredData [" + id + "] has a param with a blank name";
                    }
                    if (!isSdName(p.getKey(), 32)) {
                        return "structuredData [" + id + "] param name must be 1..32 printable ASCII characters without space, '=', ']' or '\"': " + p.getKey();
                    }
                    if (p.getValue() == null) {
                        return "structuredData [" + id + "] param [" + p.getKey() + "] has no value";
                    }
                }
            }
        }
        return null;
    }

    /** RFC 5424 SD-NAME / APP-NAME shape: printable US-ASCII (33..126), at most maxLen, and for names also no '=', ']' or '"'. */
    private static boolean isSdName(String v, int maxLen) {
        if (v.isEmpty() || v.length() > maxLen) {
            return false;
        }
        boolean strict = maxLen == 32;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 33 || c > 126 || (strict && (c == '=' || c == ']' || c == '"'))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Map<String, String>> buildStructuredDataMap() {
        if (structuredData.isEmpty()) {
            return null;
        }
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        for (StructuredDataParam sd : structuredData) {
            m.put(sd.getId(), Collections.unmodifiableMap(new LinkedHashMap<>(sd.getParams())));
        }
        return Collections.unmodifiableMap(m);
    }

    private static boolean isEmpty(String v) {
        return v == null || v.isEmpty();
    }

    private void stopLayouts() {
        if (layout != null) {
            layout.stop();
            layout = null;
        }
        if (stackTraceLayout != null) {
            stackTraceLayout.stop();
            stackTraceLayout = null;
        }
    }

    /** Splits layout output into individual syslog messages: one per line, blank lines skipped. */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /** View of an event that reports no throwable, so the message layout can never print one. */
    static ILoggingEvent withoutThrowable(ILoggingEvent event) {
        return (ILoggingEvent) Proxy.newProxyInstance(ILoggingEvent.class.getClassLoader(), new Class<?>[] { ILoggingEvent.class }, (proxy, method, args) -> {
            if ("getThrowableProxy".equals(method.getName()) && method.getParameterCount() == 0) {
                return null;
            }
            try {
                return method.invoke(event, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private PatternLayout layout(String pattern) {
        PatternLayout l = new PatternLayout();
        l.setContext(getContext());
        l.setPattern(pattern);
        l.start();
        return l;
    }

    private AbstractSyslogConfig newConfig() {
        switch (protocol) {
            case UDP -> {
                UDPNetSyslogConfig c = new UDPNetSyslogConfig();
                c.setHost(syslogHost);
                c.setPort(port);
                return c;
            }
            case TCP -> {
                TCPNetSyslogConfig c = new TCPNetSyslogConfig();
                c.setHost(syslogHost);
                c.setPort(port);
                return c;
            }
            case TLS -> {
                SSLTCPNetSyslogConfig c = new SSLTCPNetSyslogConfig();
                c.setHost(syslogHost);
                c.setPort(port);
                c.setKeyStore(sslKeyStore);
                c.setKeyStorePassword(sslKeyStorePassword);
                c.setTrustStore(sslTrustStore);
                c.setTrustStorePassword(sslTrustStorePassword);
                return c;
            }
            case UNIX -> {
                UnixSocketSyslogConfig c = new UnixSocketSyslogConfig();
                c.setPath(unixSocketPath);
                return c;
            }
            default -> throw new IllegalArgumentException("protocol " + protocol);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (sync) {
            send(event);
            return;
        }
        Generation g = gen;
        try {
            event.prepareForDeferredProcessing();
            if (g == null || !g.running) {
                dropWhileStopping();
                return;
            }
            if (blockWhenFull) {
                boolean queued = false;
                while (g.running && !(queued = g.queue.offer(event, 100, TimeUnit.MILLISECONDS))) {
                    // wait for room, but re-check running so stop() releases blocked producers
                }
                if (!queued) {
                    dropWhileStopping();
                    return;
                }
            } else if (!g.queue.offer(event)) {
                reportDrop();
                return;
            }
            // stop() may have begun after the running check above; take the event back so it is counted, not lost.
            // Every queued event ends up delivered (writer poll) XOR counted (this remove, or stop()'s drain).
            if (!g.running && g.queue.remove(event)) {
                dropWhileStopping();
            }
        } catch (InterruptedException e) {
            dropped.incrementAndGet();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
            reportFailure("syslog enqueue failed: " + e);
        }
    }

    private void dropWhileStopping() {
        dropped.incrementAndGet();
        droppedWhileStopping.incrementAndGet();
    }

    private void reportDrop() {
        long n = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropReport.get();
        if (now - last > dropReportIntervalMs && lastDropReport.compareAndSet(last, now)) {
            addWarn("syslog queue full; " + n + " events dropped so far");
        }
    }

    /** Reports a per-event failure at most once per interval, so a persistent fault cannot flood the status list. */
    private void reportFailure(String message) {
        long now = System.currentTimeMillis();
        long last = lastFailureReport.get();
        if (now - last > dropReportIntervalMs && lastFailureReport.compareAndSet(last, now)) {
            addError(message);
        }
    }

    private void drain(Generation g) {
        try {
            while (!g.abandoned && (g.running || !g.queue.isEmpty())) {
                ILoggingEvent e = g.queue.poll(100, TimeUnit.MILLISECONDS);
                if (e != null) {
                    try {
                        send(e);
                    } catch (Throwable t) {
                        dropped.incrementAndGet();
                        reportFailure("syslog writer failed: " + t);
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Number of events discarded because the queue was full or the appender was stopping. */
    public long getDroppedCount() {
        return dropped.get();
    }

    /** The writer thread of the most recent start, or null in sync mode; for tests. */
    Thread writerThread() {
        Generation g = gen;
        return g == null ? null : g.writer;
    }

    /** Formats and writes one event; never throws. Overridable as a test seam. */
    protected void send(ILoggingEvent event) {
        try {
            PatternLayout msgLayout = layout;
            PatternLayout exLayout = stackTraceLayout;
            SyslogIF out = syslog;
            if (msgLayout == null || out == null) {
                return;
            }
            int level = toSyslogLevel(event.getLevel()).code();
            for (String line : splitLines(msgLayout.doLayout(withoutThrowable(event)))) {
                emit(out, level, line);
            }
            if (!throwableExcluded && exLayout != null && event.getThrowableProxy() != null) {
                for (String line : splitLines(exLayout.doLayout(event))) {
                    emit(out, level, line);
                }
            }
        } catch (RuntimeException e) {
            reportFailure("syslog write failed: " + e.getMessage());
        }
    }

    /**
     * Writes one line. While the backlog holds earlier messages they are replayed first, so order is kept; the ported
     * syslog only signals recovery after the write that succeeded, which would put the new line ahead of the backlog.
     * If the replay fails again the destination is still down, so the line goes straight to the backlog without
     * another connect attempt.
     */
    private void emit(SyslogIF out, int level, String line) {
        SyslogMessageIF structured = null;
        if (rfc5424) {
            String text = line;
            for (SyslogMessageModifierIF m : activeModifiers) {
                text = m.modify(out, facilityCode, level, text);
            }
            structured = new StructuredSyslogMessage(null, structuredDataMap, text);
        }
        RingBufferBackLogHandler h = backlog;
        if (h != null && out instanceof AbstractSyslog as && h.size() > 0) {
            if (!h.replay(as::logPrepared)) {
                if (structured != null) {
                    as.logToBackLog(level, structured);
                } else {
                    as.logToBackLog(level, line);
                }
                return;
            }
        }
        if (structured != null) {
            out.log(level, structured);
        } else {
            out.log(level, line);
        }
    }

    /** The underlying syslog instance of the current start, or null; for tests. */
    SyslogIF syslogInstance() {
        return syslog;
    }

    /** Number of messages currently waiting in the backlog; for tests. */
    int backlogSize() {
        RingBufferBackLogHandler h = backlog;
        return h == null ? 0 : h.size();
    }

    static SyslogLevel toSyslogLevel(Level l) {
        return switch (l.toInt()) {
            case Level.ERROR_INT -> SyslogLevel.ERROR;
            case Level.WARN_INT -> SyslogLevel.WARN;
            case Level.INFO_INT -> SyslogLevel.INFO;
            default -> SyslogLevel.DEBUG;
        };
    }

    /**
     * Not synchronized on the appender: AppenderBase.doAppend holds the appender monitor while a producer may be
     * blocked in append() (blockWhenFull), so stop() first flips {@code running}, which releases such producers,
     * and only then serializes the teardown on a private lock.
     */
    @Override
    public void stop() {
        if (!isStarted()) {
            return;
        }
        Generation g = gen;
        if (g != null) {
            g.running = false;
        }
        synchronized (stopLock) {
            if (!isStarted()) {
                return;
            }
            super.stop();   // AppenderBase.doAppend ignores events from here on
            if (g != null) {
                shutdownWriter(g);
            }
            stopLayouts();
            destroySyslog();
            long total = dropped.get();
            if (total > 0) {
                addWarn("syslog appender [" + getName() + "] stopped; " + total + " events dropped in total ("
                        + droppedWhileStopping.get() + " while stopping)");
            }
        }
    }

    private void shutdownWriter(Generation g) {
        Thread w = g.writer;
        try {
            if (shutdownTimeoutMs > 0) {
                w.join(shutdownTimeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (w.isAlive()) {
            // timeout 0, timeout expired, or the caller was interrupted: stop waiting for the drain
            g.abandoned = true;
            // the ported TCP writer retries a failed write; each retry swallows one interrupt, so keep interrupting
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
            boolean callerInterrupted = false;
            while (w.isAlive() && System.nanoTime() < until) {
                w.interrupt();
                try {
                    w.join(50);
                } catch (InterruptedException e) {
                    callerInterrupted = true;
                }
            }
            if (callerInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // atomic hand-off: whatever is still queued is counted exactly once, here
        List<ILoggingEvent> left = new ArrayList<>();
        g.queue.drainTo(left);
        if (!left.isEmpty()) {
            dropped.addAndGet(left.size());
            droppedWhileStopping.addAndGet(left.size());
        }
    }

    private void destroySyslog() {
        if (instanceName != null) {
            String name = instanceName;
            instanceName = null;
            try {
                Syslog.destroyInstance(name);   // also closes a socket the writer may still be blocked on
            } catch (RuntimeException e) {
                addError("syslog instance destroy failed: " + e.getMessage());
            }
        }
    }

    // ---- bean setters used by Joran -----------------------------------------------------

    /**
     * Adds a message modifier, nested in XML as {@code <modifier class="..."/>}. With rfc5424 the modifiers act on the
     * MSG text only. Joran instantiates the class through a public no-argument constructor and sets its properties
     * through setters, so only modifiers that are configurable that way work from XML: PrefixSyslogMessageModifier
     * (property prefix), SuffixSyslogMessageModifier (property suffix) and HTMLEntityEscapeSyslogMessageModifier.
     * StringCase, Checksum, Hash, Mac and Sequential modifiers need constructor arguments or a config object and can
     * only be added programmatically.
     */
    public void addModifier(SyslogMessageModifierIF m) { modifiers.add(m); }

    /** Adds an RFC 5424 structured data element, nested in XML as {@code <structuredData><id>..</id><entry>..</entry></structuredData>} (see {@link StructuredDataParam}); used only when rfc5424 is true. */
    public void addStructuredData(StructuredDataParam sd) { structuredData.add(sd); }
    public void setSyslogHost(String v) { this.syslogHost = v; }
    public void setPort(int v) { this.port = v; }
    public void setProtocol(Protocol v) { this.protocol = v; }
    public void setFacility(String v) { this.facility = v; }
    public void setSuffixPattern(String v) { this.suffixPattern = v; }
    public void setStackTracePattern(String v) { this.stackTracePattern = v; }
    public void setThrowableExcluded(boolean v) { this.throwableExcluded = v; }
    public void setSendLocalName(boolean v) { this.sendLocalName = v; }
    public void setSendLocalTimestamp(boolean v) { this.sendLocalTimestamp = v; }
    public void setMaxMessageLength(int v) { this.maxMessageLength = v; }
    public void setAppName(String v) { this.appName = v; }
    public void setRfc5424(boolean v) { this.rfc5424 = v; }
    public void setUnixSocketPath(String v) { this.unixSocketPath = v; }
    public void setSslKeyStore(String v) { this.sslKeyStore = v; }
    public void setSslKeyStorePassword(String v) { this.sslKeyStorePassword = v; }
    public void setSslTrustStore(String v) { this.sslTrustStore = v; }
    public void setSslTrustStorePassword(String v) { this.sslTrustStorePassword = v; }
    public void setSync(boolean v) { this.sync = v; }
    public void setQueueSize(int v) { this.queueSize = v; }
    public void setBlockWhenFull(boolean v) { this.blockWhenFull = v; }
    /**
     * Number of formatted messages kept in memory while the syslog server cannot be reached (default 1000, newest kept
     * when full); they are replayed in order once a write succeeds again. 0 disables the backlog. Applies to the
     * connection-oriented protocols (TCP, TLS, UNIX socket). UDP has no connection, so an unreachable server is
     * silent loss: datagrams are sent without any acknowledgement and are never backlogged or replayed.
     */
    public void setBacklogSize(int v) { this.backlogSize = v; }
    /** Maximum time stop() waits for queued events to be written; 0 means do not wait. Must not be negative. */
    public void setShutdownTimeoutMs(long v) { this.shutdownTimeoutMs = v; }
}
