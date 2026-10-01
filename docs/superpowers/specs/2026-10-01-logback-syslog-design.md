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
- Existing `logback.xml` configs for Logback's built-in `SyslogAppender` work with only the class name changed. Configs for the papertrail `Syslog4jAppender` (nested `<layout>` and `<syslogConfig>`) do not: they are migrated by mapping settings to flat properties (see the manual, chapter 1). Accepting the papertrail nested form is a possible follow-up.
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
- `UnixDomainSocketAddress` (JDK 16+, stream) and reflective `java.lang.foreign` (datagram) replace JNA.
- A small internal bounded connection pool replaces commons-pool.
- Platform threads for the async writer (one daemon thread per appender) and the
  server listeners: the ported writers synchronize around blocking socket I/O, which
  pins a virtual thread's carrier on JDK 21 (JDK 24+, JEP 491, would lift this).
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
org.red5.syslog.impl.unix            stream: UnixDomainSocketAddress; datagram: reflective java.lang.foreign
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

- **UDP:** `java.net.DatagramSocket` per instance. Messages over `maxMessageLength`
  (default 1024 for RFC 3164, 2048 for RFC 5424) are truncated, or split when
  `splitMessageBeforeSend` is enabled.
- **TCP:** `java.net.Socket`. LF-delimited framing in both RFC 3164 and RFC 5424
  modes (RFC 6587 non-transparent framing; octet-counting is not implemented).
  Keep-alive and optional `persistConnection`. A failed write triggers
  one reconnect attempt, then the message goes to the backlog.
- **TLS:** the TCP implementation with TLS layered over the connected socket.
  Configurable keystore and truststore paths and passwords. Each client and server
  instance builds a private `SSLContext` from its own stores (platform default trust
  managers when no truststore is set) and never reads or sets the JVM-wide
  `javax.net.ssl.*` properties. Hostname verification is on by default (the
  configured host name is used for SNI and endpoint identification); the
  `sslVerifyHostname` property turns it off, which is insecure. The handshake is
  bounded by the connect timeout.
- **Unix socket:** default path `/dev/log`, no JNA, two types. Datagram (the
  default, `SOCK_DGRAM`; what `/dev/log` is for journald and rsyslog, and
  `/var/run/syslog` on macOS): one datagram per message, no framing, through
  `UnixDatagramSocket`, which calls libc `socket`/`connect`/`send`/`close`
  through `java.lang.foreign` by reflection (preview in JDK 21, final in 22+;
  no compile-time preview dependency, no `--enable-preview`). Sends use
  `MSG_DONTWAIT` and never block; errno is captured with
  `Linker.Option.captureCallState("errno")` and reported by name and
  `strerror` text. Available on Linux and macOS/BSD (macOS layout untested)
  with a 64-bit JVM that has `java.lang.foreign` and allows native access;
  Windows and other systems are unavailable, and selecting datagram there fails
  at initialization with the reason and the alternatives (stream, or UDP/TCP to
  `127.0.0.1`). The JDK prints a one-time restricted-method warning unless the
  JVM runs with `--enable-native-access=ALL-UNNAMED` (`--enable-native-access=org.red5.syslog` when the jar is on the module path). Stream (`SOCK_STREAM`):
  `SocketChannel` over `UnixDomainSocketAddress`, LF-terminated frames. Both
  connect lazily and reconnect after a failure; failures go to the backlog. The
  appender selects the type with `unixSocketType` (`DATAGRAM` default, `STREAM`)
  and omits the host name from UNIX frames unless `sendLocalName` is set.
- **Multiple:** fan-out over several `SyslogIF` instances; each fails independently.
- **Pooled TCP:** bounded `ArrayBlockingQueue` of connections, keeping the original
  pool settings (max active, max wait).

## 7. Threading

- `doAppend` must not block Red5 request threads.
- Default is async: events go on a bounded queue (`queueSize`, default 4096)
  drained by a single platform daemon thread per appender (see section 4).
- Overflow policy: `discardWhenFull` (default, drops and counts) or `blockWhenFull`.
- `sync` flag bypasses the queue.
- `stop()` drains the queue up to `shutdownTimeoutMs`, then closes transports; a
  writer still blocked in socket I/O is released by closing its socket without
  taking the writer's lock.
- The ported server runs its listeners on platform threads, one per TCP connection
  (off the appender path).

## 8. Errors and backlog

- The appender never throws into the logging path.
- Failures are reported through Logback `addError`, rate-limited: an outage is one
  ERROR status per window, the recovery an INFO status with the replayed count.
- Failed messages go to a bounded in-memory ring buffer (`backlogSize`, default
  1000, 0 disables it) replayed in order on reconnect. The other library backlog
  handlers (print-stream and others) remain available programmatically but are not
  selectable from XML.
- While the destination is down, reconnects back off: after a failed write or
  replay no connection is attempted for 1 s, doubling to at most 30 s, reset on
  success; lines arriving meanwhile go straight to the backlog.
- Every lost message is counted in the dropped count (queue overflow, stop,
  backlog eviction, backlog leftovers at stop, failed writes without a backlog)
  and the total is reported in the stop warning.
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

- Names shared with Logback's built-in `SyslogAppender`: `syslogHost`, `port`, `facility`, `suffixPattern`,
  `stackTracePattern`, `throwableExcluded`, `sendLocalName`, `sendLocalTimestamp`,
  `maxMessageLength`.
- Additional: `protocol`, `unixSocketPath`, `unixSocketType`, `rfc5424`, `appName`, `queueSize`,
  `sync`, overflow policy, TLS store settings, `sslVerifyHostname`, `backlogSize`.
- Structured data via nested `<structuredData>` elements
  (`<id>`, then `<entry><name/><value/></entry>`; Joran ignores attributes on
  nested components); modifiers via `<modifier class="...">`, using Joran
  nested-component support. Only modifiers with a public no-arg constructor
  and setters are loadable from XML (Prefix, Suffix, HTMLEntityEscape).
- RFC 5424 mode: no structured data is sent as NILVALUE `-`; messages are
  truncated rather than split; timestamps carry at most 6 fractional digits;
  the default `maxMessageLength` is 2048 (1024 in RFC 3164 mode). For UDP the
  effective limit is capped at 65507 bytes.

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
