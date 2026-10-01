# Red5 Logback Syslog

A [Logback](https://logback.qos.ch/) appender that sends application logs to syslog, packaged with the
[syslog4j](http://www.syslog4j.org/) feature set in a single jar for Red5. Applications keep logging through
[SLF4J](https://www.slf4j.org/); only the destination changes.

- Requires **Java 21** or later, Logback 1.5.x and SLF4J 2.x.
- The only dependencies are `logback-classic` and `slf4j-api`, both `provided` (Red5 already ships them).
- The syslog4j client, message layer and server are included, repackaged under `org.red5.syslog`.

Status: pre-release (`1.0.0-SNAPSHOT`). It is not yet published to a public repository; build it from source.

## Features

- **Transports:** UDP, TCP, TLS and unix domain sockets (stream).
- **TLS done carefully:** a private TLS context per appender (the JVM-wide `javax.net.ssl.*` properties are never touched),
  host name verification on by default, bounded connect and handshake.
- **Formats:** RFC 3164 (default) and RFC 5424, including structured data and message modifiers.
- **Non-blocking by default:** events go to a bounded queue and one daemon thread does the network work.
- **No silent loss:** events that cannot be sent are kept in a bounded backlog, replayed in order once the server
  answers, and retried with a growing delay. Every event that is lost is counted and reported through Logback's status system.
- **Safe shutdown:** bounded wait for queued events, then the connection is aborted so the writer thread always ends.
- **The syslog library:** use `org.red5.syslog` directly, or run the bundled UDP, TCP and TLS syslog server.

## Quick start

Build and install the jar:

```
git clone https://github.com/Red5/logback-syslog.git
cd logback-syslog
mvn -DskipTests install
```

Depend on it:

```xml
<dependency>
  <groupId>org.red5</groupId>
  <artifactId>red5-logback-syslog</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For a Red5 server, copy `target/red5-logback-syslog-1.0.0-SNAPSHOT.jar` into the server's `lib/` directory.

Configure the appender in `logback.xml`:

```xml
<configuration>
  <appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
    <syslogHost>logs.example.com</syslogHost>
    <port>514</port>
    <facility>LOCAL0</facility>
    <appName>red5</appName>
  </appender>

  <root level="INFO">
    <appender-ref ref="SYSLOG"/>
  </root>
</configuration>
```

To check that messages arrive, run the bundled server as a listener:

```
java -cp red5-logback-syslog-1.0.0-SNAPSHOT.jar \
     org.red5.syslog.server.SyslogServerMain -h 127.0.0.1 -p 514 udp
```

### TLS with RFC 5424 and structured data

```xml
<appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
  <syslogHost>logs.example.com</syslogHost>
  <port>6514</port>
  <protocol>TLS</protocol>
  <sslTrustStore>/etc/red5/syslog-truststore.p12</sslTrustStore>
  <sslTrustStorePassword>changeit</sslTrustStorePassword>

  <rfc5424>true</rfc5424>
  <appName>red5</appName>
  <structuredData>
    <id>origin@32473</id>
    <entry><name>app</name><value>live</value></entry>
  </structuredData>
</appender>
```

## Configuration at a glance

| Property | Default | Meaning |
|---|---|---|
| `syslogHost`, `port` | `localhost`, `514` | Where to send |
| `protocol` | `UDP` | `UDP`, `TCP`, `TLS` or `UNIX` |
| `facility` | `USER` | Syslog facility (`LOCAL0` to `LOCAL7`, ...) |
| `appName` | none | Tag (RFC 3164) or APP-NAME (RFC 5424) |
| `suffixPattern` | `[%thread] %logger %msg` | Logback pattern for the message text |
| `stackTracePattern`, `throwableExcluded` | `%ex{full}`, `false` | How exceptions are sent, one syslog message per line |
| `rfc5424` | `false` | Send RFC 5424 frames |
| `maxMessageLength` | `1024` (`2048` with RFC 5424) | Message size limit including header, at least 128 |
| `queueSize`, `blockWhenFull` | `4096`, `false` | Async queue and overflow policy |
| `backlogSize` | `1000` | Messages kept while the server is unreachable (0 disables) |
| `shutdownTimeoutMs` | `2000` | How long `stop()` waits for the queue |
| `sslTrustStore`, `sslKeyStore`, `sslVerifyHostname` | none, none, `true` | TLS settings |

The complete reference, with every property and the nested `modifier` and `structuredData` elements, is in the manual.

## Documentation

The user manual is static HTML in [`docs/manual/`](docs/manual/index.html); open `docs/manual/index.html` in a browser.

| Chapter | Topic |
|---|---|
| 1 | Introduction and migration from Logback's `SyslogAppender` and the papertrail appender |
| 2 | Getting started |
| 3 | Appender configuration |
| 4 | Transports and TLS |
| 5 | Message formats |
| 6 | Reliability |
| 7 | The syslog library and server |
| 8 | Troubleshooting and limitations |

The design and implementation plan are in [`docs/superpowers/`](docs/superpowers/).

## Migrating

- **From Logback's own `SyslogAppender`:** change the class to `org.red5.logback.syslog.SyslogAppender`; the core
  properties (`syslogHost`, `port`, `facility`, `suffixPattern`, `stackTracePattern`, `throwableExcluded`) keep their meaning.
- **From the papertrail `Syslog4jAppender`:** its nested `<layout>` and `<syslogConfig>` form is not accepted. Map the
  settings to flat properties (`host` to `syslogHost`, `ident` to `appName`, the config class to `protocol`); the manual has a table.

## Building and testing

```
mvn clean verify
```

This compiles for Java 21, runs the test suite (about 90 tests, around three minutes because the ported shutdown code
sleeps) and builds `target/red5-logback-syslog-1.0.0-SNAPSHOT.jar`. The tests start real UDP, TCP and TLS servers on
local ports in the 15140 to 15199 range, so do not run two builds at the same time on one machine.

## Known limitations

- Unix sockets are stream-only, because Java 21 has no unix datagram sockets. Datagram-only `/dev/log` (journald,
  default rsyslog) is not supported; use UDP or TCP to `127.0.0.1` instead.
- TCP frames are LF-delimited in both formats; octet-counted framing (RFC 6587) is not implemented.
- The appender requires a TLS trust store or key store to be configured; for public certificate authorities point
  `sslTrustStore` at the JDK's `cacerts`.
- The backlog is in memory and is lost when the application stops. Replayed messages carry the replay time in the header.

## Project layout

| Path | Contents |
|---|---|
| `src/main/java/org/red5/logback/syslog` | The Logback appender |
| `src/main/java/org/red5/syslog` | The ported syslog4j client, message layer and server |
| `src/test` | Unit and integration tests (including in-process syslog servers) |
| `docs/manual` | User manual |
| `docs/superpowers` | Design spec and implementation plan |

## License

This project is a derivative of syslog4j 0.9.46 and is licensed under the
[GNU Lesser General Public License, version 2.1](LICENSE.txt). The original copyright and license notices are
retained in the ported source files.
