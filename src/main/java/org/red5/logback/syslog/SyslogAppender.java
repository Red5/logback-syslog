package org.red5.logback.syslog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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
import org.red5.syslog.impl.AbstractSyslogConfig;
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

    private static final AtomicLong COUNTER = new AtomicLong();

    private PatternLayout layout;
    private PatternLayout stackTraceLayout;
    private SyslogIF syslog;
    private String instanceName;

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
            cfg.setFacility(fac.code());
            cfg.setThreaded(false);               // queueing is done by this appender
            cfg.setSendLocalName(sendLocalName);
            cfg.setSendLocalTimestamp(sendLocalTimestamp);
            cfg.setMaxMessageLength(maxMessageLength);
            cfg.setUseStructuredData(rfc5424);
            if (appName != null && !appName.isEmpty()) {
                cfg.setIdent(appName);
            }
            // the Syslog registry is JVM-static and case-insensitive, so the name must be unique per appender
            instanceName = "red5-" + (getName() != null ? getName() + "-" : "") + COUNTER.incrementAndGet();
            syslog = Syslog.createInstance(instanceName, cfg);
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
        return null;
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
                out.log(level, line);
            }
            if (!throwableExcluded && exLayout != null && event.getThrowableProxy() != null) {
                for (String line : splitLines(exLayout.doLayout(event))) {
                    out.log(level, line);
                }
            }
        } catch (RuntimeException e) {
            reportFailure("syslog write failed: " + e.getMessage());
        }
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
    /** Maximum time stop() waits for queued events to be written; 0 means do not wait. Must not be negative. */
    public void setShutdownTimeoutMs(long v) { this.shutdownTimeoutMs = v; }
}
