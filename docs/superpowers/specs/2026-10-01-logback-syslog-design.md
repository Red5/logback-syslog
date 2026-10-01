# Red5 Logback Syslog Appender - Design

Date: 2026-10-01
Status: Draft for review

## 1. Goal

A single jar for Red5 that ships logs to syslog through Logback. It provides an
appender equivalent to papertrail's logback-syslog4j, backed by a port of the
syslog4j 0.9.46 client and server feature set, repackaged under `org.red5`.

Success criteria:
- One jar, runtime dependencies limited to `logback-classic` and `slf4j-api` (both `provided`).
- JDK 21 minimum (`release=21`).
- Existing papertrail `logback.xml` appender configs work with only the class name changed.
- The logging path never blocks request threads and never throws.
- All syslog4j features are present except the log4j integration.

Reference source: `refs/syslog4j-0.9.46-src`.

## 2. Scope

In scope (ported from syslog4j):
- Client transports: UDP, TCP, TLS (SSL), unix socket, multiple (fan-out), pooled TCP.
- Message layer: RFC 3164 and RFC 5424 including structured data, PCI messages,
  message modifiers (checksum, hash, MAC, sequential, prefix, suffix, case, HTML escape),
  message processors.
- Backlog handlers (fallback when a destination is unavailable), print-stream variant included.
- Syslog server: UDP, TCP and TLS listeners with event handlers.
- Optional `main` classes for the client and server CLIs.

Out of scope:
- All log4j classes (`Syslog4jAppenderSkeleton`, `Log4jSyslogBackLogHandler`, log4j logger factory).
- JNA, commons-pool, and the bundled Base64 implementation (replaced by JDK facilities).

## 3. Licensing

syslog4j is LGPL 2.1. The port is a derivative work: the jar must be distributed
under LGPL 2.1 and the original copyright and license notices retained in the
ported files. Red5 maintainers must confirm this is acceptable before release.

## 4. Approach

Port the source and modernize it (chosen over Maven shade relocation, which keeps
2011-era code and its dependencies, and over a clean rewrite, which drops features
and moves away from the reference).

Modernization rules:
- Generics, enums for facility and level, records where a type is a plain value.
- `java.util.Base64` replaces the bundled `Base64.java`.
- `UnixDomainSocketAddress` (JDK 16+) replaces JNA.
- A small internal bounded connection pool replaces commons-pool.
- Virtual threads for the server and async send.
- No log4j or `LogLog` references; diagnostics go through Logback `Context` status.
- Imports instead of fully qualified names; consistent with existing Red5 code style.

## 5. Build and layout

Maven, single module, one jar. `Automatic-Module-Name: org.red5.syslog`.

```
org.red5.syslog                      Syslog, SyslogIF, SyslogConfigIF, SyslogConstants
org.red5.syslog.impl                 AbstractSyslog, AbstractSyslogConfig
org.red5.syslog.impl.net.udp
org.red5.syslog.impl.net.tcp         + pool (internal bounded pool)
org.red5.syslog.impl.net.tcp.ssl
org.red5.syslog.impl.unix            UnixDomainSocketAddress based
org.red5.syslog.impl.multiple
org.red5.syslog.impl.message         structured, pci, modifier.*, processor
org.red5.syslog.impl.backlog         handlers, print-stream
org.red5.syslog.server               server API and impl (udp, tcp, tcp.ssl)
org.red5.syslog.util
org.red5.logback.syslog              SyslogAppender (new)
```

Dependencies: `ch.qos.logback:logback-classic` and `org.slf4j:slf4j-api`, scope `provided`.
Test dependencies: JUnit 5.

## 6. Transports

Each transport implements `SyslogIF` on top of a small `AbstractSyslog` base.

- **UDP:** `DatagramChannel` per instance. Messages over `maxMessageLength`
  (default 1024 for RFC 3164, 2048 for RFC 5424) are truncated, or split when
  `splitMessageBeforeSend` is enabled.
- **TCP:** `SocketChannel`. Newline framing for RFC 3164, octet-counting for
  RFC 5424. Keep-alive and optional `persistConnection`. A failed write triggers
  one reconnect attempt, then the message goes to the backlog.
- **TLS:** the TCP implementation over an `SSLSocketFactory`. Configurable
  keystore and truststore paths and passwords. Hostname verification on by default.
- **Unix socket:** `SocketChannel` over `UnixDomainSocketAddress`, default path
  `/dev/log`, datagram or stream per config. No JNA.
- **Multiple:** fan-out over several `SyslogIF` instances; each fails independently.
- **Pooled TCP:** bounded `ArrayBlockingQueue` of connections, keeping the original
  pool settings (max active, max wait).

## 7. Threading

- `doAppend` must not block Red5 request threads.
- Default is async: events go on a bounded queue (`queueSize`, default 4096)
  drained by a single virtual-thread writer.
- Overflow policy: `discardWhenFull` (default, drops and counts) or `blockWhenFull`.
- `sync` flag bypasses the queue.
- `stop()` drains the queue up to `shutdownTimeoutMs`, then closes transports.
- The ported server runs its listeners on virtual threads, one per TCP connection.

## 8. Errors and backlog

- The appender never throws into the logging path.
- Failures are reported through Logback `addError`, rate-limited.
- Failed messages go to the configured backlog handler. Default is a bounded
  in-memory ring buffer replayed on reconnect. The print-stream handler remains
  available as an alternative.
- Configuration is validated in `start()`. Invalid config calls `addError` and
  leaves the appender inactive instead of throwing.

## 9. Appender configuration

`org.red5.logback.syslog.SyslogAppender` extends `AppenderBase<ILoggingEvent>`.
Each event is formatted with a `PatternLayout`, mapped from Logback level to
syslog severity, and sent through the configured `SyslogIF`.

```xml
<appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
  <syslogHost>logs.example.com</syslogHost>
  <port>6514</port>
  <protocol>TLS</protocol>              <!-- UDP | TCP | TLS | UNIX -->
  <facility>LOCAL0</facility>
  <rfc5424>true</rfc5424>
  <appName>red5</appName>
  <suffixPattern>[%thread] %logger %msg</suffixPattern>
  <stackTracePattern>%ex</stackTracePattern>
  <throwableExcluded>false</throwableExcluded>
  <sendLocalName>true</sendLocalName>
  <sendLocalTimestamp>true</sendLocalTimestamp>
  <maxMessageLength>2048</maxMessageLength>
  <queueSize>4096</queueSize>
  <sslTrustStore>...</sslTrustStore>
</appender>
```

- Papertrail-compatible names: `syslogHost`, `port`, `facility`, `suffixPattern`,
  `stackTracePattern`, `throwableExcluded`, `sendLocalName`, `sendLocalTimestamp`,
  `maxMessageLength`.
- Additional: `protocol`, `unixSocketPath`, `rfc5424`, `appName`, `queueSize`,
  `sync`, overflow policy, TLS store settings, backlog selection.
- Structured data via nested `<structuredData>` elements; modifiers via
  `<modifier class="...">`, using Joran nested-component support.

## 10. Retained but off the appender path

- `SyslogMain` and `SyslogServerMain` stay as optional CLI `main` classes.
- The `Syslog` static registry stays for API familiarity; the appender does not use it.

## 11. Testing

JUnit 5. Tests start the ported `SyslogServer` in-process and verify the appender
over UDP, TCP and TLS, including reconnect, backlog replay, queue overflow, message
splitting and truncation, RFC 5424 structured data, and modifier behavior.
Unix socket tests run on Linux only (skipped elsewhere).

## 12. Open items

- LGPL 2.1 acceptability for Red5 distribution (section 3).
- Target Logback version (assumed 1.5.x with slf4j 2).
