package org.red5.logback.syslog;

import java.time.Instant;
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
import java.util.function.LongSupplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import org.red5.syslog.AbortableSyslog;
import org.red5.syslog.SyslogConstants;
import org.red5.syslog.SyslogFacility;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogLevel;
import org.red5.syslog.SyslogMessageIF;
import org.red5.syslog.SyslogMessageModifierIF;
import org.red5.syslog.impl.AbstractSyslog;
import org.red5.syslog.impl.AbstractSyslogConfig;
import org.red5.syslog.impl.backlog.BackLogListener;
import org.red5.syslog.impl.backlog.NullSyslogBackLogHandler;
import org.red5.syslog.impl.backlog.RingBufferBackLogHandler;
import org.red5.syslog.impl.message.processor.structured.StructuredSyslogMessageProcessor;
import org.red5.syslog.impl.message.structured.StructuredSyslogMessage;
import org.red5.syslog.impl.net.tcp.TCPNetSyslogConfig;
import org.red5.syslog.impl.net.tcp.ssl.SSLTCPNetSyslogConfig;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.impl.unix.socket.UnixDatagramSocket;
import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;

/**
 * Logback appender that ships events to syslog through org.red5.syslog.
 *
 * <p>By default events are queued and written by one platform daemon thread per appender ({@code sync=false}); the
 * ported transports synchronize around blocking socket I/O, which would pin a virtual thread's carrier on JDK 21.
 * While the destination is unreachable formatted messages wait in a bounded in-memory backlog ({@code backlogSize})
 * and are replayed in order on recovery; reconnects back off from 1 s doubling to 30 s. An outage is reported as one
 * ERROR status per rate-limit window, the recovery as an INFO status, and every message lost on the way is counted in
 * {@link #getDroppedCount()}.</p>
 *
 * <p>Replayed messages carry the replay time, not the event time, in the syslog header timestamp. When the exact event
 * time matters, put it in the message, e.g. {@code <suffixPattern>%d{ISO8601} [%thread] %logger %msg</suffixPattern>}.</p>
 */
public class SyslogAppender extends AppenderBase<ILoggingEvent> {

    private String syslogHost = "localhost";
    private int port = 514;
    private Protocol protocol = Protocol.UDP;
    private String facility = "USER";
    private String suffixPattern = "[%thread] %logger %msg";
    private String stackTracePattern = "%ex{full}";
    private boolean throwableExcluded;
    private boolean sendLocalName = true;
    private boolean sendLocalNameSet;
    private boolean sendLocalTimestamp = true;
    private int maxMessageLength;
    private String appName;
    private boolean rfc5424;
    private String unixSocketPath = "/dev/log";
    private UnixSocketType unixSocketType = UnixSocketType.DATAGRAM;
    private String sslKeyStore, sslKeyStorePassword, sslTrustStore, sslTrustStorePassword;
    private boolean sslVerifyHostname = true;
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
    static final int MIN_MESSAGE_LENGTH = 128;
    private static final AtomicLong COUNTER = new AtomicLong();

    private PatternLayout layout;
    private PatternLayout stackTraceLayout;
    private volatile SyslogIF syslog;
    private RingBufferBackLogHandler backlog;
    private String instanceName;
    private volatile int facilityCode;
    private boolean maxMessageLengthSet;

    /** The limit in effect: the configured value, else 2048 for rfc5424 (the RFC 5424 minimum receivers must support) or 1024 for plain syslog. */
    int effectiveMaxMessageLength() {
        return maxMessageLengthSet ? maxMessageLength : (rfc5424 ? 2048 : 1024);
    }

    /** Frame body of an RFC 5424 message; with no structured data the field is the NILVALUE "-" instead of the library's "[0@0]". */
    private static final class AppenderStructuredMessage extends StructuredSyslogMessage {
        private static final long serialVersionUID = 1L;

        AppenderStructuredMessage(Map<String, Map<String, String>> sd, String message) {
            super(null, sd, message);
        }

        @Override
        public String createMessage() {
            if (getStructuredData() != null && !getStructuredData().isEmpty()) {
                return super.createMessage();
            }
            return getMessage() == null || getMessage().isBlank() ? "- -" : "- - " + getMessage();
        }
    }

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
    LongSupplier nanoClock = System::nanoTime;   // package-private for tests: drives the reconnect backoff
    long backoffInitialMs = 1000;   // package-private for tests
    long backoffMaxMs = 30_000;     // package-private for tests
    final AtomicLong transportAttempts = new AtomicLong();   // for tests: replays and direct writes tried on the transport
    private volatile Backoff backoff;

    /**
     * Reconnect backoff for one start cycle. After a failed write or replay no connection is attempted until the
     * deadline passes; the delay starts at the initial value and doubles up to the maximum, and a success resets it.
     */
    static final class Backoff {
        private final long initialNanos;
        private final long maxNanos;
        private final LongSupplier clock;
        private long delayNanos;
        private long deadline;
        private boolean waiting;

        Backoff(long initialMs, long maxMs, LongSupplier clock) {
            this.initialNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, initialMs));
            this.maxNanos = Math.max(initialNanos, TimeUnit.MILLISECONDS.toNanos(maxMs));
            this.clock = clock;
        }

        synchronized boolean due() {
            return !waiting || clock.getAsLong() - deadline >= 0;
        }

        synchronized void failed() {
            if (delayNanos == 0) {
                delayNanos = initialNanos;
            }
            deadline = clock.getAsLong() + delayNanos;
            waiting = true;
            delayNanos = Math.min(delayNanos * 2, maxNanos);
        }

        synchronized void succeeded() {
            waiting = false;
            delayNanos = 0;
        }
    }

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
            // local sockets expect <PRI>TIMESTAMP TAG: MSG; a hostname there would be read as the tag
            cfg.setSendLocalName(sendLocalNameSet || protocol != Protocol.UNIX ? sendLocalName : false);
            cfg.setSendLocalTimestamp(sendLocalTimestamp);
            // a UDP datagram carries at most 65507 bytes: a larger limit would make an oversized datagram fail on send, and the
            // failed line would then sit at the head of the backlog and block every later line; capped, the ported splitter
            // breaks long messages into several datagrams instead. Unix datagrams have a similar (system-dependent) limit
            // and get the same cap.
            int limit = effectiveMaxMessageLength();
            boolean unixDatagram = protocol == Protocol.UNIX && unixSocketType == UnixSocketType.DATAGRAM;
            if ((protocol == Protocol.UDP || unixDatagram) && limit > UDP_MAX_PAYLOAD) {
                addWarn("syslog appender [" + getName() + "]: maxMessageLength " + limit + " lowered to " + UDP_MAX_PAYLOAD
                        + (unixDatagram ? " (unix datagram limit)" : " (UDP datagram limit)"));
                limit = UDP_MAX_PAYLOAD;
            }
            cfg.setMaxMessageLength(limit);
            if (rfc5424) {
                cfg.setTruncateMessage(true);   // RFC 5424 section 6.1: a message over the limit is truncated, not split into continuation frames
            }
            cfg.setUseStructuredData(rfc5424);
            if (rfc5424) {
                // RFC 5424 carries the application name in the header (APP-NAME) and the modifiers act on the MSG text
                // only; the ported ident prefix and cfg-level modifiers would land in MSGID and corrupt the frame. Clear any
                // ident a config class sets by default (UnixSocketSyslogConfig sets "java").
                cfg.setIdent(null);
                activeModifiers = List.copyOf(modifiers);
                structuredDataMap = buildStructuredDataMap();
                if (unixDatagram) {
                    addWarn("syslog appender [" + getName() + "]: local syslog daemons reading /dev/log (journald, rsyslog) do"
                            + " not parse RFC 5424 and log the header as text; use RFC 3164 (rfc5424 false) for the local socket,"
                            + " and RFC 5424 with network collectors or stream listeners that parse it");
                }
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
            backlog = null;
            backoff = null;
            if (backlogSize > 0) {
                cfg.setThrowExceptionOnWrite(false);  // failures go to the backlog, which reports and counts them
                backoff = new Backoff(backoffInitialMs, backoffMaxMs, nanoClock);
                backlog = new RingBufferBackLogHandler(backlogSize);
                backlog.setListener(new BacklogReporter());
                cfg.addBackLogHandler(backlog);
                // one reconnect covers a stale persistent connection; more only multiplies connect timeouts while the server is down
                cfg.setWriteRetries(1);
            } else {
                // no backlog: a failed write surfaces in emit(), which counts and reports it; the null handler keeps the
                // ported default (print to System.err) out of the way
                cfg.setThrowExceptionOnWrite(true);
                cfg.addBackLogHandler(NullSyslogBackLogHandler.INSTANCE);
            }
            // a label only (the transport's protocol name); the static Syslog registry is not used
            instanceName = "red5-" + (getName() != null ? getName() + "-" : "") + COUNTER.incrementAndGet();
            syslog = newSyslog(instanceName, cfg);
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
            g.writer = Thread.ofPlatform().daemon().name("red5-syslog-" + getName()).start(() -> drain(g));
        }
        super.start();
    }

    /**
     * Creates and initializes the transport directly, as the static Syslog registry would but without registering it:
     * the registry is JVM-wide, opens default instances on first use and sleeps on every destroy.
     */
    private static SyslogIF newSyslog(String label, AbstractSyslogConfig cfg) {
        SyslogIF s;
        try {
            s = (SyslogIF) cfg.getSyslogClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create syslog transport " + cfg.getSyslogClass().getName() + ": " + e, e);
        }
        s.initialize(label, cfg);
        return s;
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
        if (effectiveMaxMessageLength() < MIN_MESSAGE_LENGTH) {
            // below this the header alone can exhaust the budget and every write fails ("Message length < 0"),
            // leaving a poison entry at the head of the backlog
            return "maxMessageLength must be at least " + MIN_MESSAGE_LENGTH + ": " + maxMessageLength;
        }
        if (protocol == Protocol.UNIX) {
            if (unixSocketPath == null || unixSocketPath.isBlank()) {
                return "unixSocketPath must not be empty for protocol UNIX";
            }
            if (unixSocketType == null) {
                return "unixSocketType must not be null for protocol UNIX";
            }
            if (unixSocketType == UnixSocketType.DATAGRAM && !UnixDatagramSocket.isAvailable()) {
                return "unix datagram sockets are unavailable: " + UnixDatagramSocket.unavailableReason()
                        + "; use unixSocketType STREAM, or UDP/TCP to 127.0.0.1";
            }
        } else {
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
            if (!isEmpty(appName) && !isAppName(appName)) {
                return "appName must be 1..48 printable ASCII characters without spaces for rfc5424: " + appName;
            }
            Set<String> ids = new HashSet<>();
            for (StructuredDataParam sd : structuredData) {
                String id = sd.getId();
                if (id == null || id.isBlank()) {
                    return "structuredData id must not be blank";
                }
                if (!isSdName(id)) {
                    return "structuredData id must be 1..32 printable ASCII characters without space, '=', ']' or '\"': " + id;
                }
                if (!ids.add(id)) {
                    return "duplicate structuredData id: " + id;
                }
                for (Map.Entry<String, String> p : sd.getParams().entrySet()) {
                    if (p.getKey() == null || p.getKey().isBlank()) {
                        return "structuredData [" + id + "] has a param with a blank name";
                    }
                    if (!isSdName(p.getKey())) {
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

    /** RFC 5424 APP-NAME: 1..48 printable US-ASCII characters. */
    private static boolean isAppName(String v) {
        return printable(v, 48, false);
    }

    /** RFC 5424 SD-NAME: 1..32 printable US-ASCII characters, none of '=', ']' or '"'. */
    private static boolean isSdName(String v) {
        return printable(v, 32, true);
    }

    private static boolean printable(String v, int maxLen, boolean sdName) {
        if (v.isEmpty() || v.length() > maxLen) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 33 || c > 126 || (sdName && (c == '=' || c == ']' || c == '"'))) {
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
        return new ThrowableFreeEvent(event);
    }

    /**
     * Read-only view of an event without its throwable. It extends LoggingEvent (rather than only implementing
     * ILoggingEvent) so converters that cast to LoggingEvent keep working; every getter delegates to the wrapped event.
     */
    static final class ThrowableFreeEvent extends LoggingEvent {
        private final ILoggingEvent e;

        ThrowableFreeEvent(ILoggingEvent e) {
            this.e = e;
        }

        @Override public IThrowableProxy getThrowableProxy() { return null; }
        @Override public String getThreadName() { return e.getThreadName(); }
        @Override public Level getLevel() { return e.getLevel(); }
        @Override public String getMessage() { return e.getMessage(); }
        @Override public Object[] getArgumentArray() { return e.getArgumentArray(); }
        @Override public String getFormattedMessage() { return e.getFormattedMessage(); }
        @Override public String getLoggerName() { return e.getLoggerName(); }
        @Override public LoggerContextVO getLoggerContextVO() { return e.getLoggerContextVO(); }
        @Override public StackTraceElement[] getCallerData() { return e.getCallerData(); }
        @Override public boolean hasCallerData() { return e.hasCallerData(); }
        @SuppressWarnings("deprecation")
        @Override public Marker getMarker() { return e.getMarker(); }
        @Override public List<Marker> getMarkerList() { return e.getMarkerList(); }
        @Override public Map<String, String> getMDCPropertyMap() { return e.getMDCPropertyMap(); }
        @SuppressWarnings("deprecation")
        @Override public Map<String, String> getMdc() { return e.getMdc(); }
        @Override public long getTimeStamp() { return e.getTimeStamp(); }
        @Override public int getNanoseconds() { return e.getNanoseconds(); }
        @Override public Instant getInstant() { return e.getInstant(); }
        @Override public long getSequenceNumber() { return e.getSequenceNumber(); }
        @Override public List<KeyValuePair> getKeyValuePairs() { return e.getKeyValuePairs(); }
        @Override public void prepareForDeferredProcessing() { e.prepareForDeferredProcessing(); }

        @Override
        public long getContextBirthTime() {
            if (e instanceof LoggingEvent le) {
                return le.getContextBirthTime();
            }
            LoggerContextVO vo = e.getLoggerContextVO();
            return vo == null ? 0 : vo.getBirthTime();
        }

        @Override
        public String toString() {
            return e.toString();
        }
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
                c.setSslVerifyHostname(sslVerifyHostname);
                return c;
            }
            case UNIX -> {
                UnixSocketSyslogConfig c = new UnixSocketSyslogConfig();
                c.setPath(unixSocketPath);
                c.setType(unixSocketType == UnixSocketType.STREAM ? SyslogConstants.SOCK_STREAM : SyslogConstants.SOCK_DGRAM);
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
                if (e == null) {
                    if (g.running && !g.abandoned) {
                        replayIfDue();
                    }
                } else {
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

    /** Turns backlog events into status messages and dropped counts. */
    private final class BacklogReporter implements BackLogListener {
        @Override
        public void down(String reason) {
            reportFailure("syslog destination unavailable: " + reason);
        }

        @Override
        public void up(int replayed) {
            addInfo("syslog destination recovered; replayed " + replayed + " backlogged messages");
        }

        @Override
        public void evicted(int count) {
            dropped.addAndGet(count);
        }
    }

    /**
     * Number of events or messages lost: events discarded because the queue was full, the appender was stopping, or
     * enqueueing or formatting failed; backlogged messages evicted to make room or still waiting when the appender
     * stopped; and failed writes when the backlog is disabled. Delivered + dropped + still backlogged equals submitted
     * (one message per event line).
     * <p>
     * In-flight semantics at abandonment: when stop() gives up waiting ({@code shutdownTimeoutMs}) the transport is
     * aborted. The event the writer was writing at that moment may or may not have reached the server; it is counted
     * only if its write fails into the backlog (a closed backlog counts it as dropped), possibly after the final stop
     * warning was issued.
     */
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
            dropped.incrementAndGet();   // formatting failed: the rest of the event is lost
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
            structured = new AppenderStructuredMessage(structuredDataMap, text);
        }
        RingBufferBackLogHandler h = backlog;
        if (h == null) {
            try {
                if (structured != null) {
                    out.log(level, structured);
                } else {
                    out.log(level, line);
                }
            } catch (RuntimeException e) {
                dropped.incrementAndGet();
                reportFailure("syslog write failed: " + e.getMessage());
            }
            return;
        }
        Backoff b = backoff;
        if (out instanceof AbstractSyslog as && h.size() > 0) {
            boolean replayed = false;
            if (b == null || b.due()) {
                transportAttempts.incrementAndGet();
                replayed = h.replay(as::logPrepared);
                if (b != null) {
                    if (replayed) {
                        b.succeeded();
                    } else {
                        b.failed();
                    }
                }
            }
            if (!replayed) {
                // still down, or inside the backoff window: queue behind the backlog without a connect attempt
                if (structured != null) {
                    as.logToBackLog(level, structured);
                } else {
                    as.logToBackLog(level, line);
                }
                return;
            }
        }
        transportAttempts.incrementAndGet();
        if (structured != null) {
            out.log(level, structured);
        } else {
            out.log(level, line);
        }
        if (b != null) {
            // the backlog was empty before this write, so anything in it now means the write failed
            if (h.size() > 0) {
                b.failed();
            } else {
                b.succeeded();
            }
        }
    }

    /**
     * Called by the async writer when the queue is idle: replays the backlog once the backoff window has passed, so
     * recovery does not wait for the next event.
     */
    private void replayIfDue() {
        RingBufferBackLogHandler h = backlog;
        SyslogIF out = syslog;
        Backoff b = backoff;
        if (h == null || b == null || !(out instanceof AbstractSyslog as) || h.size() == 0 || !b.due()) {
            return;
        }
        try {
            transportAttempts.incrementAndGet();
            if (h.replay(as::logPrepared)) {
                b.succeeded();
            } else {
                b.failed();
            }
        } catch (RuntimeException e) {
            b.failed();
            reportFailure("syslog replay failed: " + e.getMessage());
        }
    }

    /** The underlying syslog instance of the current start, or null; for tests. */
    SyslogIF syslogInstance() {
        return syslog;
    }

    /** The messages currently waiting in the backlog, oldest first; for tests. */
    List<String> backlogMessages() {
        RingBufferBackLogHandler h = backlog;
        return h == null ? List.of() : h.messages();
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
            boolean writerStuck = g != null && shutdownWriter(g);
            stopLayouts();
            destroySyslog(writerStuck);
            // whatever is still waiting in the backlog is lost now; count it, and anything an abandoned writer adds later
            RingBufferBackLogHandler h = backlog;
            int leftInBacklog = h == null ? 0 : h.close();
            dropped.addAndGet(leftInBacklog);
            long total = dropped.get();
            if (total > 0) {
                addWarn("syslog appender [" + getName() + "] stopped; " + total + " events dropped in total ("
                        + droppedWhileStopping.get() + " while stopping)"
                        + (leftInBacklog > 0 ? "; " + leftInBacklog + " messages were still in the backlog" : ""));
            }
        }
    }

    /** @return true if the writer thread is still alive after it was abandoned and aborted */
    private boolean shutdownWriter(Generation g) {
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
            // a platform thread blocked in socket I/O ignores interrupts, and the ported writers hold a monitor around
            // that I/O: close the socket or channel without locking so the blocked call fails now
            if (syslog instanceof AbortableSyslog ab) {
                ab.abort();
            }
            // interrupts still release a writer waiting elsewhere (e.g. in an overridden send()); keep interrupting
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
        return w.isAlive();
    }

    private void destroySyslog(boolean writerStuck) {
        SyslogIF s = syslog;
        syslog = null;
        instanceName = null;
        if (s instanceof AbortableSyslog ab && writerStuck) {
            // shutdown() would wait for the monitor the stuck writer holds; abort() already closed the transport
            ab.abort();
            addWarn("syslog appender [" + getName() + "]: writer thread did not stop; transport aborted");
            return;
        }
        if (s != null) {
            try {
                s.shutdown();   // closes the transport's socket or channel
            } catch (RuntimeException e) {
                addError("syslog transport shutdown failed: " + e.getMessage());
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
     * only be added programmatically. An unknown modifier class is reported by Joran as an error status and ignored;
     * the appender still starts, without that modifier.
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
    /**
     * Whether the plain syslog header carries the local hostname. Default true, except for protocol UNIX, where it
     * defaults to false: the local socket format is {@code <PRI>TIMESTAMP TAG: MSG}, and journald and rsyslog would take a
     * hostname there for the tag. Ignored when rfc5424 is true: the RFC 5424 header always carries timestamp and hostname.
     */
    public void setSendLocalName(boolean v) { this.sendLocalName = v; this.sendLocalNameSet = true; }
    /** Ignored when rfc5424 is true: the RFC 5424 header always carries timestamp and hostname. */
    public void setSendLocalTimestamp(boolean v) { this.sendLocalTimestamp = v; }
    /**
     * Maximum size in bytes of one syslog message including its header. Default 1024, or 2048 when rfc5424 is true.
     * With rfc5424 an over-long message is truncated (RFC 5424 section 6.1) at a UTF-8 character boundary; otherwise
     * it is split into several messages. For UDP the limit is capped at 65507 (the datagram limit) with a warning.
     * Must be at least 128: a smaller budget leaves no room for the message after the header.
     */
    public void setMaxMessageLength(int v) { this.maxMessageLength = v; this.maxMessageLengthSet = true; }
    public void setAppName(String v) { this.appName = v; }
    /**
     * Sends RFC 5424 frames: {@code <PRI>1 TIMESTAMP HOST APP-NAME - - STRUCTURED-DATA MSG}. The timestamp has at most 6
     * fractional digits. appName is the APP-NAME (default "-"), PROCID and MSGID are always "-". Timestamp and hostname are
     * always sent, so sendLocalName and sendLocalTimestamp are ignored. STRUCTURED-DATA is built from the configured
     * structuredData elements, or is the NILVALUE "-" when there are none. Modifiers act on the MSG text only. Over-long
     * messages are truncated, not split (see maxMessageLength). TCP frames are LF-delimited (RFC 6587 non-transparent
     * framing), not octet-counted.
     */
    public void setRfc5424(boolean v) { this.rfc5424 = v; }
    /** Path of the unix socket for protocol UNIX (default /dev/log; on macOS the local syslog socket is /var/run/syslog). */
    public void setUnixSocketPath(String v) { this.unixSocketPath = v; }
    /**
     * Socket type for protocol UNIX (default DATAGRAM). DATAGRAM sends one datagram per message; /dev/log (journald,
     * rsyslog) and /var/run/syslog (macOS) are datagram sockets. STREAM writes newline-terminated messages on a stream
     * connection, for stream listeners such as syslog-ng unix-stream. DATAGRAM calls libc through java.lang.foreign
     * (used reflectively: a preview API in JDK 21, final from JDK 22, no flag needed); the JDK prints a one-time warning
     * about a restricted method unless the JVM runs with {@code --enable-native-access=ALL-UNNAMED}. DATAGRAM works on
     * Linux and macOS; Windows is unsupported. If it is unavailable the appender does not start and reports why. Must
     * not be null.
     */
    public void setUnixSocketType(UnixSocketType v) { this.unixSocketType = v; }
    public UnixSocketType getUnixSocketType() { return unixSocketType; }
    public void setSslKeyStore(String v) { this.sslKeyStore = v; }
    public void setSslKeyStorePassword(String v) { this.sslKeyStorePassword = v; }
    public void setSslTrustStore(String v) { this.sslTrustStore = v; }
    public void setSslTrustStorePassword(String v) { this.sslTrustStorePassword = v; }
    /**
     * TLS only: whether the server certificate must match {@code syslogHost} (default true). Setting it to false is
     * INSECURE: any certificate from a trusted issuer is then accepted for any host, which allows man-in-the-middle
     * attacks. Use it only for testing or when the trust store holds nothing but the one server certificate.
     */
    public void setSslVerifyHostname(boolean v) { this.sslVerifyHostname = v; }
    public void setSync(boolean v) { this.sync = v; }
    public void setQueueSize(int v) { this.queueSize = v; }
    public void setBlockWhenFull(boolean v) { this.blockWhenFull = v; }
    /**
     * Number of formatted messages kept in memory while the syslog server cannot be reached (default 1000, newest kept
     * when full); they are replayed in order once the destination answers again. 0 disables the backlog (failed writes
     * are then counted as dropped). Evicted messages and those still waiting at stop() count as dropped. Replayed
     * messages carry the replay time in the syslog header timestamp; use %d in suffixPattern when the exact event time
     * matters. Applies to the protocols that report a missing receiver: TCP, TLS and UNIX (stream, and datagram, where a
     * missing, full or vanished local receiver fails the send). A UDP send that throws an IOException is backlogged and
     * replayed too, but UDP gives no delivery feedback, so an unreachable UDP listener usually means silent loss
     * rather than a backlog.
     */
    public void setBacklogSize(int v) { this.backlogSize = v; }
    /** Maximum time stop() waits for queued events to be written; 0 means do not wait. Must not be negative. */
    public void setShutdownTimeoutMs(long v) { this.shutdownTimeoutMs = v; }
}
