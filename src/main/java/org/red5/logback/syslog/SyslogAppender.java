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

    private BlockingQueue<ILoggingEvent> queue;
    private Thread writer;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastDropReport = new AtomicLong();
    private volatile boolean running;
    private final Object stopLock = new Object();
    private volatile boolean abandoned;   // set when stop() gives up waiting; the writer must exit without sending more

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
            queue = new ArrayBlockingQueue<>(queueSize);
            running = true;
            abandoned = false;
            writer = Thread.ofVirtual().name("red5-syslog-" + getName()).start(this::drain);
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
        try {
            event.prepareForDeferredProcessing();
            if (!running) {
                dropped.incrementAndGet();
                return;
            }
            if (blockWhenFull) {
                boolean queued = false;
                while (running && !(queued = queue.offer(event, 100, TimeUnit.MILLISECONDS))) {
                    // wait for room, but re-check running so stop() releases blocked producers
                }
                if (!queued) {
                    dropped.incrementAndGet();
                    return;
                }
            } else if (!queue.offer(event)) {
                reportDrop();
                return;
            }
            // stop() may have begun after the running check above; take the event back so it is counted, not lost
            if (!running && queue.remove(event)) {
                dropped.incrementAndGet();
            }
        } catch (InterruptedException e) {
            dropped.incrementAndGet();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
            addError("syslog enqueue failed: " + e.getMessage());
        }
    }

    private void reportDrop() {
        long n = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropReport.get();
        if (now - last > 10_000 && lastDropReport.compareAndSet(last, now)) {
            addWarn("syslog queue full; " + n + " events dropped so far");
        }
    }

    private void drain() {
        try {
            while (!abandoned && (running || !queue.isEmpty())) {
                ILoggingEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
                if (e != null) {
                    send(e);
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
        return writer;
    }

    /** Formats and writes one event; never throws. */
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
            addError("syslog write failed: " + e.getMessage());
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
        running = false;
        synchronized (stopLock) {
            if (!isStarted()) {
                return;
            }
            super.stop();   // AppenderBase.doAppend ignores events from here on
            shutdownWriter();
            stopLayouts();
            destroySyslog();
        }
    }

    private void shutdownWriter() {
        Thread w = writer;
        if (w != null) {
            try {
                w.join(shutdownTimeoutMs);
                if (w.isAlive()) {
                    abandoned = true;
                    // the ported TCP writer retries a failed write; each retry swallows one interrupt, so keep interrupting
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
                    while (w.isAlive() && System.nanoTime() < until) {
                        w.interrupt();
                        w.join(50);
                    }
                }
            } catch (InterruptedException e) {
                w.interrupt();
                Thread.currentThread().interrupt();
            }
        }
        if (queue != null) {
            int left = queue.size();
            if (left > 0) {
                queue.clear();
                dropped.addAndGet(left);
                addWarn("syslog appender stopped with " + left + " undelivered events");
            }
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
    public void setShutdownTimeoutMs(long v) { this.shutdownTimeoutMs = v; }
}
