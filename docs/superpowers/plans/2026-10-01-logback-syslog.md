# Red5 Logback Syslog Appender Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one jar that gives Red5 a Logback syslog appender (papertrail-compatible) backed by a modernized port of syslog4j 0.9.46 under `org.red5`.

**Architecture:** Mechanically port syslog4j's sources into `org.red5.syslog`, drop log4j, replace JNA, commons-pool and the bundled Base64 with JDK 21 facilities, then add `org.red5.logback.syslog.SyslogAppender`, which formats events with `PatternLayout` and sends them through a `SyslogIF` via an async bounded queue drained by one virtual thread.

**Tech Stack:** JDK 21, Maven 3.9, logback-classic 1.5.x, slf4j-api 2.x (both `provided`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-01-logback-syslog-design.md`
**Reference source:** `refs/syslog4j-0.9.46-src/org/productivity/java/syslog4j` (call this `$REF` below). Project root is `/media/mondain/terrorbyte/workspace/github-red5/logback-syslog` (call this `$ROOT`).

## Global Constraints

- JDK 21 minimum: `maven.compiler.release` = `21`.
- Single jar; runtime dependencies limited to `ch.qos.logback:logback-classic` and `org.slf4j:slf4j-api`, both scope `provided`.
- Library package root `org.red5.syslog`; appender package `org.red5.logback.syslog`.
- No references to log4j, JNA (`com.sun.jna`), commons-pool, or the bundled `Base64.java` anywhere in `src/`.
- Unix sockets use `java.net.UnixDomainSocketAddress` (JDK 16+).
- `Automatic-Module-Name: org.red5.syslog`.
- License is LGPL 2.1; keep the original copyright/license notices in every ported file and ship `LICENSE.txt` (copy of `$REF/../../../../META-INF/LICENSE.txt`).
- Appender property names compatible with papertrail logback-syslog4j: `syslogHost`, `port`, `facility`, `suffixPattern`, `stackTracePattern`, `throwableExcluded`, `sendLocalName`, `sendLocalTimestamp`, `maxMessageLength`.
- The logging path never blocks request threads and never throws.
- Use `import` statements, not fully qualified names inline (unless names clash).
- Commit messages: no emoji, no Claude attribution line. The project folder is not yet a git repo: Task 1 runs `git init`.

## Review Focus

1. Message longer than `maxMessageLength` over UDP and TCP: expect truncation or split, never an exception or a dropped connection. (Task 5)
2. Syslog server unreachable at start, then comes up: expect the appender to start, buffer in the backlog, and replay on reconnect. (Task 8)
3. Event logged with a multi-line stack trace or a message containing newlines: expect one syslog message per line for the stack trace and no framing corruption on TCP. (Task 6)
4. Log storm exceeding `queueSize`: expect no blocking of the caller, a dropped-event count reported once via status, and no OOM. (Task 7)
5. Appender stopped while events are queued, or stopped twice, or logged to after stop: expect a bounded drain, no exception, no leaked threads or sockets. (Task 7)

---

## File Structure

```
pom.xml
LICENSE.txt
src/main/java/org/red5/syslog/...                  ported library (Task 1-4)
src/main/java/org/red5/syslog/impl/pool/SyslogWriterPool.java   internal pool (Task 2)
src/main/java/org/red5/syslog/impl/unix/...        UnixDomainSocket based (Task 3)
src/main/java/org/red5/syslog/SyslogFacility.java  enum (Task 4)
src/main/java/org/red5/syslog/SyslogLevel.java     enum (Task 4)
src/main/java/org/red5/syslog/impl/backlog/RingBufferBackLogHandler.java (Task 8)
src/main/java/org/red5/logback/syslog/SyslogAppender.java        (Task 6-7, 9)
src/main/java/org/red5/logback/syslog/Protocol.java              (Task 6)
src/test/java/org/red5/syslog/...                  transport tests
src/test/java/org/red5/logback/syslog/...          appender tests
src/test/java/org/red5/syslog/testsupport/CapturingServer.java   shared test server helper (Task 5)
```

---

### Task 1: Scaffold build and mechanical port (no unix, no pool)

**Files:**
- Create: `pom.xml`, `LICENSE.txt`, `src/main/java/org/red5/syslog/**` (copied), `src/test/java/org/red5/syslog/PortSmokeTest.java`

**Interfaces:**
- Produces: package `org.red5.syslog` with the original class names (`Syslog`, `SyslogIF`, `SyslogConfigIF`, `SyslogConstants`, `SyslogRuntimeException`, `SyslogMessageIF`, `SyslogBackLogHandlerIF`, `impl.net.udp.UDPNetSyslogConfig`, `impl.net.tcp.TCPNetSyslogConfig`, `impl.net.tcp.ssl.SSLTCPNetSyslogConfig`, `impl.multiple.MultipleSyslogConfig`, `server.SyslogServer`, ...). `Syslog.createInstance(String name, SyslogConfigIF cfg): SyslogIF`, `Syslog.destroyInstance(String)`.

- [ ] **Step 1: git init and write pom.xml**

```bash
cd $ROOT && git init
```

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.red5</groupId>
  <artifactId>red5-logback-syslog</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <name>Red5 Logback Syslog</name>
  <licenses>
    <license><name>LGPL-2.1</name><url>https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html</url></license>
  </licenses>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <logback.version>1.5.26</logback.version>
    <slf4j.version>2.0.17</slf4j.version>
    <junit.version>5.11.4</junit.version>
  </properties>
  <dependencies>
    <dependency><groupId>ch.qos.logback</groupId><artifactId>logback-classic</artifactId><version>${logback.version}</version><scope>provided</scope></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>${slf4j.version}</version><scope>provided</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>
      </plugin>
      <plugin>
        <artifactId>maven-jar-plugin</artifactId><version>3.4.2</version>
        <configuration>
          <archive><manifestEntries><Automatic-Module-Name>org.red5.syslog</Automatic-Module-Name></manifestEntries></archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Copy and rename sources**

```bash
cd $ROOT
REF=refs/syslog4j-0.9.46-src/org/productivity/java/syslog4j
DST=src/main/java/org/red5/syslog
mkdir -p $DST src/test/java
cp -r $REF/. $DST/
cp refs/syslog4j-0.9.46-src/META-INF/LICENSE.txt LICENSE.txt
# excluded by spec (log4j) or replaced later (JNA, commons-pool, Base64)
rm -rf $DST/impl/log4j $DST/impl/backlog/log4j $DST/util/Base64.java \
       $DST/impl/unix $DST/impl/pool $DST/impl/net/tcp/pool $DST/impl/net/tcp/ssl/pool
find $DST -name '*.java' -print0 | xargs -0 sed -i \
  -e 's/org\.productivity\.java\.syslog4j/org.red5.syslog/g'
```

- [ ] **Step 3: Fix the leftovers the compiler will report**

Replace the three Base64 call sites with `java.util.Base64`:
- `impl/message/modifier/hash/HashSyslogMessageModifier.java:107` -> `Base64.getEncoder().encodeToString(digestBytes)`; line 119 -> `Base64.getDecoder().decode(base64Hash)`.
- `impl/message/modifier/mac/MacSyslogMessageModifier.java:95` -> `Base64.getEncoder().encodeToString(macBytes)`; line 106 -> `Base64.getDecoder().decode(base64Signature)`.
- `impl/message/modifier/mac/MacSyslogMessageModifierConfig.java:51` -> `Base64.getDecoder().decode(base64Key)`.
- Add `import java.util.Base64;` to each, remove the old `org.red5.syslog.util.Base64` import.

In `Syslog.java` remove the `UnixSyslogConfig` / `UnixSocketSyslogConfig` imports and the two `createInstance(UNIX_*...)` lines (and the `JNA_NATIVE_CLASS` condition in `initialize()`); Task 3 re-adds unix registration. Remove `SyslogUtility.isClassExists` use if now unused (keep the method).

- [ ] **Step 4: Write the failing smoke test**

`src/test/java/org/red5/syslog/PortSmokeTest.java`:

```java
package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.server.SyslogServer;
import org.red5.syslog.server.SyslogServerEventIF;
import org.red5.syslog.server.SyslogServerIF;
import org.red5.syslog.server.SyslogServerSessionlessEventHandlerIF;

class PortSmokeTest {

    @Test
    void udpClientReachesUdpServer() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        SyslogServerIF server = SyslogServer.getThreadedInstance("udp");
        server.getConfig().setHost("127.0.0.1");
        server.getConfig().setPort(15140);
        server.getConfig().addEventHandler(
                (SyslogServerSessionlessEventHandlerIF) (s, addr, ev) -> got.add(ev.getMessage()));
        try {
            Thread.sleep(200);
            UDPNetSyslogConfig cfg = new UDPNetSyslogConfig();
            cfg.setHost("127.0.0.1");
            cfg.setPort(15140);
            SyslogIF client = Syslog.createInstance("smoke", cfg);
            client.info("hello");
            String msg = got.poll(3, TimeUnit.SECONDS);
            assertTrue(msg != null && msg.contains("hello"), "got: " + msg);
        } finally {
            Syslog.destroyInstance("smoke");
            SyslogServer.shutdown();
        }
    }
}
```

(Verify `SyslogServerSessionlessEventHandlerIF.event(SyslogServerIF, SocketAddress, SyslogServerEventIF)` in `$REF/server/` and adjust the lambda parameter list to the real signature.)

- [ ] **Step 5: Run to see compile errors, fix until green**

Run: `mvn -q test -Dtest=PortSmokeTest`
Expected first: compile errors from leftovers; fix each (generics warnings are fine). Final: PASS.

- [ ] **Step 6: Verify nothing excluded remains**

Run: `grep -rnE "log4j|com\.sun\.jna|org\.apache\.commons" src/ ; echo exit=$?`
Expected: no matches (`exit=1`).

- [ ] **Step 7: Commit**

```bash
git add pom.xml LICENSE.txt src docs
git commit -m "Port syslog4j client and server into org.red5.syslog without log4j, JNA and commons-pool"
```

(`refs/` stays untracked; add it to `.gitignore`.)

---

### Task 2: Internal writer pool replaces commons-pool

**Files:**
- Create: `src/main/java/org/red5/syslog/impl/pool/SyslogWriterPool.java`
- Create (re-port): `impl/net/tcp/pool/PooledTCPNetSyslog.java`, `PooledTCPNetSyslogConfig.java`, `impl/net/tcp/ssl/pool/PooledSSLTCPNetSyslogConfig.java`, `impl/net/tcp/ssl/pool/PooledSSLTCPNetSyslog.java` (if present in ref)
- Test: `src/test/java/org/red5/syslog/impl/pool/SyslogWriterPoolTest.java`

**Interfaces:**
- Consumes: `AbstractSyslog.createWriter(): AbstractSyslogWriter`, `AbstractSyslogWriter.shutdown()`, `SyslogPoolConfigIF.getMaxActive()/getMaxWait()`.
- Produces: `SyslogWriterPool(Supplier<AbstractSyslogWriter> factory, int maxActive, long maxWaitMillis)` with `AbstractSyslogWriter borrow() throws InterruptedException`, `void release(AbstractSyslogWriter)`, `void clear()`, `void close()`.

- [ ] **Step 1: Failing test**

```java
package org.red5.syslog.impl.pool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.red5.syslog.impl.AbstractSyslogWriter;

class SyslogWriterPoolTest {

    static class StubWriter extends AbstractSyslogWriter {
        boolean shut;
        public void write(byte[] m) { }
        public void flush() { }
        public void shutdown() { shut = true; }
    }

    @Test
    void reusesReleasedWriterAndBoundsActive() throws Exception {
        AtomicInteger made = new AtomicInteger();
        SyslogWriterPool pool = new SyslogWriterPool(() -> { made.incrementAndGet(); return new StubWriter(); }, 1, 100);
        AbstractSyslogWriter a = pool.borrow();
        assertNull(pool.borrow(), "second borrow times out when maxActive=1");
        pool.release(a);
        assertSame(a, pool.borrow());
        assertEquals(1, made.get());
        pool.close();
        assertTrue(((StubWriter) a).shut);
    }
}
```

- [ ] **Step 2: Run, expect FAIL (class missing)** `mvn -q test -Dtest=SyslogWriterPoolTest`

- [ ] **Step 3: Implement**

```java
package org.red5.syslog.impl.pool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.red5.syslog.impl.AbstractSyslogWriter;

/**
 * Bounded pool of syslog writers. Replaces Apache Commons Pool.
 */
public final class SyslogWriterPool {

    private final Supplier<AbstractSyslogWriter> factory;
    private final BlockingQueue<AbstractSyslogWriter> idle;
    private final Semaphore permits;
    private final long maxWaitMillis;
    private volatile boolean closed;

    public SyslogWriterPool(Supplier<AbstractSyslogWriter> factory, int maxActive, long maxWaitMillis) {
        int cap = maxActive > 0 ? maxActive : Integer.MAX_VALUE >> 4;
        this.factory = factory;
        this.idle = new ArrayBlockingQueue<>(Math.min(cap, 1024));
        this.permits = new Semaphore(cap);
        this.maxWaitMillis = maxWaitMillis;
    }

    /** Returns a writer, or null if none became available within maxWait. */
    public AbstractSyslogWriter borrow() throws InterruptedException {
        if (closed || !permits.tryAcquire(maxWaitMillis < 0 ? Long.MAX_VALUE : maxWaitMillis, TimeUnit.MILLISECONDS)) {
            return null;
        }
        AbstractSyslogWriter w = idle.poll();
        if (w == null) {
            try {
                w = factory.get();
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
        }
        return w;
    }

    public void release(AbstractSyslogWriter w) {
        if (w == null) {
            return;
        }
        permits.release();
        if (closed || !idle.offer(w)) {
            w.shutdown();
        }
    }

    public void clear() {
        AbstractSyslogWriter w;
        while ((w = idle.poll()) != null) {
            w.shutdown();
        }
    }

    public void close() {
        closed = true;
        clear();
    }
}
```

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Re-port pooled classes**

```bash
cd $ROOT; REF=refs/syslog4j-0.9.46-src/org/productivity/java/syslog4j; DST=src/main/java/org/red5/syslog
mkdir -p $DST/impl/net/tcp/pool $DST/impl/net/tcp/ssl/pool
cp $REF/impl/net/tcp/pool/*.java $DST/impl/net/tcp/pool/
cp $REF/impl/net/tcp/ssl/pool/*.java $DST/impl/net/tcp/ssl/pool/
find $DST/impl/net/tcp -name '*.java' -print0 | xargs -0 sed -i 's/org\.productivity\.java\.syslog4j/org.red5.syslog/g'
```

Edit `PooledTCPNetSyslog`: delete `poolFactory` and `GenericSyslogPoolFactory`/`AbstractSyslogPoolFactory` imports; add field `private SyslogWriterPool pool;`. In `initialize()` call `super.initialize()` then:

```java
SyslogPoolConfigIF pc = (SyslogPoolConfigIF) this.syslogConfig;
this.pool = new SyslogWriterPool(() -> {
    AbstractSyslogWriter w = createWriter();
    if (((AbstractSyslogConfigIF) syslogConfig).isThreaded()) { createWriterThread(w); }
    return w;
}, pc.getMaxActive(), pc.getMaxWait());
```

`getWriter()` -> `pool.borrow()` (throw `SyslogRuntimeException` if null), `returnWriter(w)` -> `pool.release(w)`, `flush()` -> `pool.clear()`, `shutdown()` -> `pool.close()`. Apply the same to the SSL pooled variant. Keep the `SyslogPoolConfigIF` getters that no longer apply (idle, eviction) as documented no-ops: add `@Deprecated` Javadoc "ignored; kept for syslog4j API compatibility".

- [ ] **Step 6:** `mvn -q test` PASS; `grep -rn "commons" src/` empty. Commit: `git commit -am "Replace commons-pool with internal bounded writer pool"` (use `git add -A src`).

---

### Task 3: Unix socket transport without JNA

**Files:**
- Create: `src/main/java/org/red5/syslog/impl/unix/socket/UnixSocketSyslog.java`, `UnixSocketSyslogConfig.java`, `impl/unix/UnixSyslogConfig.java`, `impl/unix/UnixSyslog.java`
- Modify: `Syslog.java` (re-register `UNIX_SOCKET` and `UNIX_SYSLOG`)
- Test: `src/test/java/org/red5/syslog/impl/unix/UnixSocketSyslogTest.java`

**Interfaces:**
- Consumes: `AbstractSyslog` (`write(int level, byte[] message)`, `flush()`, `shutdown()`, `getWriter()`), `AbstractSyslogConfig`.
- Produces: `UnixSocketSyslogConfig` with `getPath()/setPath(String)`, default `SyslogConstants.SYSLOG_PATH_DEFAULT` (`/dev/log`). `UnixSyslog` is a thin subclass (the original called libc `syslog()`; JDK 21 has no stable FFM, so it now writes to the same socket, documented in class Javadoc).

- [ ] **Step 1: Failing test** (skipped off-Linux)

```java
package org.red5.syslog.impl.unix;

import static org.junit.jupiter.api.Assertions.*;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;

@EnabledOnOs({ OS.LINUX, OS.MAC })
class UnixSocketSyslogTest {

    @Test
    void writesDatagramToUnixSocket() throws Exception {
        Path dir = Files.createTempDirectory("sl");
        Path sock = dir.resolve("log.sock");
        try (DatagramChannel server = DatagramChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            UnixSocketSyslogConfig cfg = new UnixSocketSyslogConfig();
            cfg.setPath(sock.toString());
            SyslogIF syslog = Syslog.createInstance("unix-test", cfg);
            syslog.info("over-unix");
            server.configureBlocking(false);
            ByteBuffer buf = ByteBuffer.allocate(2048);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.receive(buf) == null && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            buf.flip();
            assertTrue(new String(buf.array(), 0, buf.limit()).contains("over-unix"));
        } finally {
            Syslog.destroyInstance("unix-test");
            Files.deleteIfExists(sock);
            Files.deleteIfExists(dir);
        }
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (class missing).

- [ ] **Step 3: Re-port config + implement transport**

```bash
mkdir -p $DST/impl/unix/socket
cp $REF/impl/unix/*.java $DST/impl/unix/; cp $REF/impl/unix/socket/*Config.java $DST/impl/unix/socket/
find $DST/impl/unix -name '*.java' -print0 | xargs -0 sed -i 's/org\.productivity\.java\.syslog4j/org.red5.syslog/g'
```

Keep the two config classes as ported (family/type/protocol getters stay: `getType()` selects datagram vs stream; default SOCK_DGRAM=2). `UnixSocketSyslog` is rewritten:

```java
package org.red5.syslog.impl.unix.socket;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.AbstractSyslog;
import org.red5.syslog.impl.AbstractSyslogWriter;

/** Syslog over a unix domain socket (default /dev/log). Uses JDK 16+ native support, no JNA. */
public class UnixSocketSyslog extends AbstractSyslog {

    private static final long serialVersionUID = 1L;
    private static final int SOCK_STREAM = 1;

    protected UnixSocketSyslogConfig unixConfig;
    private transient WritableByteChannel channel;

    @Override
    public void initialize() throws SyslogRuntimeException {
        try {
            unixConfig = (UnixSocketSyslogConfig) this.syslogConfig;
        } catch (ClassCastException e) {
            throw new SyslogRuntimeException("config must be of type UnixSocketSyslogConfig");
        }
    }

    private synchronized WritableByteChannel channel() throws IOException {
        if (channel == null) {
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(Path.of(unixConfig.getPath()));
            if (unixConfig.getType() == SOCK_STREAM) {
                channel = SocketChannel.open(addr);
            } else {
                DatagramChannel dc = DatagramChannel.open(StandardProtocolFamily.UNIX);
                dc.connect(addr);
                channel = dc;
            }
        }
        return channel;
    }

    @Override
    protected synchronized void write(int level, byte[] message) throws SyslogRuntimeException {
        try {
            channel().write(ByteBuffer.wrap(message));
        } catch (IOException e) {
            closeQuietly();
            throw new SyslogRuntimeException(e);
        }
    }

    private void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // nothing to do
        }
        channel = null;
    }

    @Override
    public synchronized void flush() throws SyslogRuntimeException {
        closeQuietly();
    }

    @Override
    public synchronized void shutdown() throws SyslogRuntimeException {
        closeQuietly();
    }

    @Override
    public AbstractSyslogWriter getWriter() {
        return null;
    }

    @Override
    public void returnWriter(AbstractSyslogWriter syslogWriter) {
        // no writers: writes are direct
    }
}
```

(Check `AbstractSyslog`'s abstract method list in `$REF/impl/AbstractSyslog.java` and match exactly; the original `UnixSocketSyslog` shows `getWriter` returning `null` and a no-op `returnWriter`.) `UnixSyslog extends UnixSocketSyslog` with the Javadoc note; its config's `getSyslogClass()` returns `UnixSyslog.class`. In `Syslog.initialize()` restore the two `createInstance(UNIX_*...)` lines guarded only by `OSDetectUtility.isUnix()`.

- [ ] **Step 4:** `mvn -q test` PASS. `grep -rn jna src/` empty.
- [ ] **Step 5: Commit** `git add -A src && git commit -m "Implement unix socket syslog with UnixDomainSocketAddress, drop JNA"`

---

### Task 4: Facility and level enums

**Files:**
- Create: `src/main/java/org/red5/syslog/SyslogFacility.java`, `SyslogLevel.java`
- Test: `src/test/java/org/red5/syslog/SyslogEnumsTest.java`

**Interfaces:**
- Produces: `enum SyslogFacility { KERN(0), USER(1<<3), MAIL, DAEMON, AUTH, SYSLOG, LPR, NEWS, UUCP, CRON, AUTHPRIV, FTP, LOCAL0..LOCAL7 }` with `int code()` and `static SyslogFacility parse(String)` (case-insensitive, throws `IllegalArgumentException` on unknown). `enum SyslogLevel { EMERGENCY(0), ALERT, CRITICAL, ERROR, WARN, NOTICE, INFO, DEBUG(7) }` with `int code()`. The `int` constants in `SyslogConstants` remain (API compatibility); enum codes must equal them.

- [ ] **Step 1: Failing test**

```java
package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SyslogEnumsTest {

    @Test
    void codesMatchConstants() {
        assertEquals(SyslogConstants.FACILITY_LOCAL0, SyslogFacility.LOCAL0.code());
        assertEquals(SyslogConstants.FACILITY_USER, SyslogFacility.USER.code());
        assertEquals(SyslogConstants.LEVEL_WARN, SyslogLevel.WARN.code());
        assertEquals(SyslogConstants.LEVEL_DEBUG, SyslogLevel.DEBUG.code());
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(SyslogFacility.LOCAL3, SyslogFacility.parse(" local3 "));
        assertThrows(IllegalArgumentException.class, () -> SyslogFacility.parse("nope"));
    }
}
```

- [ ] **Step 2:** run, FAIL. **Step 3: Implement**

```java
package org.red5.syslog;

import java.util.Locale;

/** RFC 3164 / 5424 facilities; codes equal the SyslogConstants.FACILITY_* values. */
public enum SyslogFacility {
    KERN(0), USER(1), MAIL(2), DAEMON(3), AUTH(4), SYSLOG(5), LPR(6), NEWS(7), UUCP(8), CRON(9),
    AUTHPRIV(10), FTP(11),
    LOCAL0(16), LOCAL1(17), LOCAL2(18), LOCAL3(19), LOCAL4(20), LOCAL5(21), LOCAL6(22), LOCAL7(23);

    private final int code;

    SyslogFacility(int number) {
        this.code = number << 3;
    }

    public int code() {
        return code;
    }

    public static SyslogFacility parse(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
```

```java
package org.red5.syslog;

/** Syslog severities; codes equal the SyslogConstants.LEVEL_* values. */
public enum SyslogLevel {
    EMERGENCY(0), ALERT(1), CRITICAL(2), ERROR(3), WARN(4), NOTICE(5), INFO(6), DEBUG(7);

    private final int code;

    SyslogLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

(`valueOf` throws `IllegalArgumentException` for unknown names, satisfying the test.)

- [ ] **Step 4:** PASS. **Step 5:** `git add -A src && git commit -m "Add SyslogFacility and SyslogLevel enums"`

---

### Task 5: Transport tests (UDP, TCP, TLS, truncation, split)

**Files:**
- Create: `src/test/java/org/red5/syslog/testsupport/CapturingServer.java`, `.../testsupport/TestKeystore.java`
- Test: `src/test/java/org/red5/syslog/impl/net/TransportTest.java`
- Modify (only if a test exposes a defect): ported transport classes under `impl/net/**`

**Interfaces:**
- Produces (test support): `CapturingServer.start(String protocol, int port, SyslogServerConfigIF customizer): CapturingServer` with `String poll(long ms)`, `void close()`; `TestKeystore.create(Path dir, String password): Path` generating a self-signed JKS via `keytool`.

- [ ] **Step 1: Write CapturingServer**

```java
package org.red5.syslog.testsupport;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.red5.syslog.server.SyslogServer;
import org.red5.syslog.server.SyslogServerConfigIF;
import org.red5.syslog.server.SyslogServerEventHandlerIF;
import org.red5.syslog.server.SyslogServerIF;

public final class CapturingServer implements AutoCloseable {

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final String protocol;

    private CapturingServer(String protocol) {
        this.protocol = protocol;
    }

    public static CapturingServer start(String protocol, int port, Consumer<SyslogServerConfigIF> customizer) throws Exception {
        CapturingServer cs = new CapturingServer(protocol);
        SyslogServerConfigIF cfg = SyslogServer.getInstance(protocol).getConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        if (customizer != null) {
            customizer.accept(cfg);
        }
        cfg.addEventHandler((SyslogServerEventHandlerIF) (server, event) -> cs.messages.add(event.getMessage()));
        SyslogServerIF s = SyslogServer.getThreadedInstance(protocol);
        Thread.sleep(300);
        return cs;
    }

    public String poll(long ms) throws InterruptedException {
        return messages.poll(ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        SyslogServer.destroyInstance(protocol);
    }
}
```

(Verify handler interface names against `$REF/server/SyslogServerEventHandlerIF.java` / `SyslogServerSessionEventHandlerIF` / `...SessionlessEventHandlerIF`: UDP uses the sessionless one, TCP the session one. Make the captured-handler an adapter implementing both, registered once; `getInstance(protocol)` then `getThreadedInstance` may need to be replaced by a single `createThreadedInstance(protocol, cfg)` — use whichever the reference supports and keep the helper's public signature.)

`TestKeystore.create`:

```java
package org.red5.syslog.testsupport;

import java.nio.file.Path;

public final class TestKeystore {

    private TestKeystore() { }

    public static Path create(Path dir, String password) throws Exception {
        Path ks = dir.resolve("test.jks");
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-keystore", ks.toString(), "-storepass", password, "-keypass", password, "-storetype", "JKS")
                .redirectErrorStream(true).start();
        if (p.waitFor() != 0) {
            throw new IllegalStateException(new String(p.getInputStream().readAllBytes()));
        }
        return ks;
    }
}
```

- [ ] **Step 2: Failing tests**

```java
package org.red5.syslog.impl.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.impl.net.tcp.TCPNetSyslogConfig;
import org.red5.syslog.impl.net.tcp.ssl.SSLTCPNetSyslogConfig;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.testsupport.CapturingServer;
import org.red5.syslog.testsupport.TestKeystore;

class TransportTest {

    @Test
    void udpDelivers() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15141, null)) {
            UDPNetSyslogConfig c = new UDPNetSyslogConfig();
            c.setHost("127.0.0.1"); c.setPort(15141);
            SyslogIF log = Syslog.createInstance("t-udp", c);
            try { log.info("udp-ok"); assertTrue(s.poll(3000).contains("udp-ok")); }
            finally { Syslog.destroyInstance("t-udp"); }
        }
    }

    @Test
    void tcpDeliversMultipleLinesAndSurvivesReconnect() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15142, null)) {
            TCPNetSyslogConfig c = new TCPNetSyslogConfig();
            c.setHost("127.0.0.1"); c.setPort(15142);
            SyslogIF log = Syslog.createInstance("t-tcp", c);
            try {
                log.info("one"); log.info("two");
                assertTrue(s.poll(3000).contains("one"));
                assertTrue(s.poll(3000).contains("two"));
                log.flush();   // forces a fresh connection
                log.info("three");
                assertTrue(s.poll(3000).contains("three"));
            } finally { Syslog.destroyInstance("t-tcp"); }
        }
    }

    @Test
    void oversizedUdpMessageIsTruncatedNotDropped() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15143, null)) {
            UDPNetSyslogConfig c = new UDPNetSyslogConfig();
            c.setHost("127.0.0.1"); c.setPort(15143);
            c.setMaxMessageLength(200); c.setTruncateMessage(true);
            SyslogIF log = Syslog.createInstance("t-trunc", c);
            try {
                log.info("x".repeat(5000));
                String m = s.poll(3000);
                assertNotNull(m);
                assertTrue(m.length() <= 200);
            } finally { Syslog.destroyInstance("t-trunc"); }
        }
    }

    @Test
    void tlsDelivers(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "changeit");
        try (CapturingServer s = CapturingServer.start("ssl", 15144, cfg -> {
            var ssl = (org.red5.syslog.server.impl.net.tcp.ssl.SSLTCPNetSyslogServerConfigIF) cfg;
            ssl.setKeyStore(ks.toString()); ssl.setKeyStorePassword("changeit");
        })) {
            SSLTCPNetSyslogConfig c = new SSLTCPNetSyslogConfig();
            c.setHost("127.0.0.1"); c.setPort(15144);
            c.setTrustStore(ks.toString()); c.setTrustStorePassword("changeit");
            SyslogIF log = Syslog.createInstance("t-tls", c);
            try { log.info("tls-ok"); assertTrue(s.poll(5000).contains("tls-ok")); }
            finally { Syslog.destroyInstance("t-tls"); }
        }
    }
}
```

(Replace the inline FQN cast with an import if class names are unambiguous; the server SSL config interface name must be confirmed in `$REF/server/impl/net/tcp/ssl/`. The `"ssl"` protocol key is whatever `SyslogServer` registers; check `SyslogServer.initialize()`.)

- [ ] **Step 3: Run** `mvn -q test -Dtest=TransportTest`. Fix helper/API mismatches first. Any real transport defect found (e.g. truncation not applied, reconnect failing) is fixed in the ported class, with the failing test left as the regression.
- [ ] **Step 4:** All PASS. **Step 5:** `git add -A src && git commit -m "Add transport tests for UDP, TCP, TLS, truncation and reconnect"`

---

### Task 6: SyslogAppender core (synchronous path)

**Files:**
- Create: `src/main/java/org/red5/logback/syslog/Protocol.java`, `SyslogAppender.java`
- Test: `src/test/java/org/red5/logback/syslog/SyslogAppenderTest.java`

**Interfaces:**
- Consumes: `Syslog.createInstance(String, SyslogConfigIF)`, `Syslog.destroyInstance(String)`, `SyslogIF.log(int, String)`, `SyslogFacility.parse`, `SyslogLevel`, `UDPNetSyslogConfig`, `TCPNetSyslogConfig`, `SSLTCPNetSyslogConfig`, `UnixSocketSyslogConfig`, `AbstractSyslogConfigIF.setThreaded(boolean)`, test `CapturingServer`.
- Produces: `enum Protocol { UDP, TCP, TLS, UNIX }`. `SyslogAppender extends AppenderBase<ILoggingEvent>` with setters `setSyslogHost(String)`, `setPort(int)`, `setProtocol(Protocol)`, `setFacility(String)`, `setSuffixPattern(String)`, `setStackTracePattern(String)`, `setThrowableExcluded(boolean)`, `setSendLocalName(boolean)`, `setSendLocalTimestamp(boolean)`, `setMaxMessageLength(int)`, `setAppName(String)`, `setUnixSocketPath(String)`, `setSslKeyStore/KeyStorePassword/TrustStore/TrustStorePassword(String)`, `setRfc5424(boolean)`. Protected seam used by Task 7: `protected void send(ILoggingEvent e)` (format and write one event).

- [ ] **Step 1: Failing tests**

```java
package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

class SyslogAppenderTest {

    private SyslogAppender appender(LoggerContext ctx, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setProtocol(Protocol.UDP);
        a.setFacility("LOCAL0");
        a.setSuffixPattern("[%thread] %logger %msg");
        a.setStackTracePattern("%ex{full}");
        a.setSync(true);
        return a;
    }

    @Test
    void mapsLevelAndFormatsSuffix() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15150, null)) {
            SyslogAppender a = appender(ctx, 15150);
            a.start();
            assertTrue(a.isStarted());
            Logger l = ctx.getLogger("t.Logger");
            l.addAppender(a); l.setLevel(Level.DEBUG);
            l.warn("careful");
            String m = s.poll(3000);
            assertNotNull(m);
            assertTrue(m.contains("t.Logger careful"), m);
            a.stop();
        }
    }

    @Test
    void stackTraceSentAsOneMessagePerLine() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15151, null)) {
            SyslogAppender a = appender(ctx, 15151);
            a.start();
            Logger l = ctx.getLogger("t.Trace");
            l.addAppender(a);
            l.error("boom", new IllegalStateException("bad\nmultiline"));
            String first = s.poll(3000);
            assertTrue(first.contains("boom"));
            String next = s.poll(3000);
            assertNotNull(next);
            assertFalse(next.contains("\n"), "no embedded newline: " + next);
            a.stop();
        }
    }

    @Test
    void throwableExcludedSendsOnlyTheMessage() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15152, null)) {
            SyslogAppender a = appender(ctx, 15152);
            a.setThrowableExcluded(true);
            a.start();
            Logger l = ctx.getLogger("t.Ex");
            l.addAppender(a);
            l.error("only", new RuntimeException("x"));
            assertTrue(s.poll(3000).contains("only"));
            assertNull(s.poll(500));
            a.stop();
        }
    }

    @Test
    void invalidConfigLeavesAppenderInactive() {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setFacility("NOPE");
        a.start();
        assertFalse(a.isStarted());
    }
}
```

(Task 6 introduces `setSync(boolean)` as part of the appender; Task 7 gives the `false` branch its meaning. In Task 6 only the sync branch exists and `sync` defaults to `true`; Task 7 flips the default to `false`.)

- [ ] **Step 2:** run, FAIL (classes missing).

- [ ] **Step 3: Implement**

```java
package org.red5.logback.syslog;

/** Transport used by the appender. */
public enum Protocol {
    UDP, TCP, TLS, UNIX
}
```

```java
package org.red5.logback.syslog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogFacility;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogLevel;
import org.red5.syslog.SyslogRuntimeException;
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
    private boolean sync = true;

    private PatternLayout layout;
    private PatternLayout stackTraceLayout;
    private SyslogIF syslog;
    private String instanceName;

    @Override
    public void start() {
        try {
            SyslogFacility fac = SyslogFacility.parse(facility);
            layout = layout(suffixPattern);
            stackTraceLayout = layout(stackTracePattern);
            AbstractSyslogConfig cfg = newConfig();
            cfg.setFacility(fac.code());
            cfg.setThreaded(false);               // queueing is done by this appender
            cfg.setSendLocalName(sendLocalName);
            cfg.setSendLocalTimestamp(sendLocalTimestamp);
            cfg.setMaxMessageLength(maxMessageLength);
            cfg.setUseStructuredData(rfc5424);
            if (appName != null) {
                cfg.setIdent(appName);
            }
            instanceName = "red5-" + (getName() != null ? getName() : Integer.toHexString(System.identityHashCode(this)));
            syslog = Syslog.createInstance(instanceName, cfg);
        } catch (RuntimeException e) {
            addError("syslog appender [" + getName() + "] not started: " + e.getMessage(), e);
            return;
        }
        super.start();
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
        send(event);
    }

    /** Formats and writes one event; never throws. */
    protected void send(ILoggingEvent event) {
        try {
            int level = toSyslogLevel(event.getLevel()).code();
            syslog.log(level, layout.doLayout(event));
            if (!throwableExcluded && event.getThrowableProxy() != null) {
                for (String line : stackTraceLayout.doLayout(event).split("\\R")) {
                    if (!line.isBlank()) {
                        syslog.log(level, line);
                    }
                }
            }
        } catch (SyslogRuntimeException | IllegalStateException e) {
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

    @Override
    public void stop() {
        super.stop();
        if (instanceName != null) {
            Syslog.destroyInstance(instanceName);
            instanceName = null;
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
}
```

Note: `Level.ERROR_INT`, etc. are `public static final int` constants on `ch.qos.logback.classic.Level`, valid as `case` labels. The facility test sets `"NOPE"`, which makes `SyslogFacility.parse` throw `IllegalArgumentException` (a `RuntimeException`), caught in `start()`.

Newline handling for TCP framing: `SyslogUtility`/the TCP writer in syslog4j uses a delimiter sequence (`LF`). Messages containing a raw newline would break framing; `send()` must therefore split the *message* layout output on `\R` too. Apply that in Step 4 below with its test.

- [ ] **Step 4: Add a test and fix for embedded newlines in the message itself**

```java
    @Test
    void messageWithNewlineIsSplitIntoSeparateSyslogMessages() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("tcp", 15153, null)) {
            SyslogAppender a = appender(ctx, 15153);
            a.setProtocol(Protocol.TCP);
            a.start();
            Logger l = ctx.getLogger("t.Nl");
            l.addAppender(a);
            l.info("line1\nline2");
            assertTrue(s.poll(3000).contains("line1"));
            assertTrue(s.poll(3000).contains("line2"));
            a.stop();
        }
    }
```

Change `send()` to iterate `layout.doLayout(event).split("\\R")` (skipping blanks) instead of a single `syslog.log` call. The suffix-pattern line prefix (`[thread] logger`) appears only on the first line; later lines are sent as-is.

- [ ] **Step 5:** `mvn -q test -Dtest=SyslogAppenderTest` PASS.
- [ ] **Step 6:** `git add -A src && git commit -m "Add SyslogAppender with synchronous send, level mapping and stack trace splitting"`

---

### Task 7: Async queue, overflow policy, bounded shutdown

**Files:**
- Modify: `src/main/java/org/red5/logback/syslog/SyslogAppender.java`
- Test: `src/test/java/org/red5/logback/syslog/SyslogAppenderAsyncTest.java`

**Interfaces:**
- Consumes: `send(ILoggingEvent)` from Task 6.
- Produces: setters `setQueueSize(int)` (default 4096), `setBlockWhenFull(boolean)` (default false), `setShutdownTimeoutMs(long)` (default 2000); `sync` default becomes `false`; `getDroppedCount(): long`.

- [ ] **Step 1: Failing tests**

```java
package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

class SyslogAppenderAsyncTest {

    @Test
    void appendReturnsImmediatelyAndDeliversInOrder() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("tcp", 15160, null)) {
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx); a.setSyslogHost("127.0.0.1"); a.setPort(15160);
            a.setProtocol(Protocol.TCP); a.setSuffixPattern("%msg");
            a.start();
            Logger l = ctx.getLogger("t.Async"); l.addAppender(a);
            for (int i = 0; i < 50; i++) { l.info("m" + i); }
            for (int i = 0; i < 50; i++) {
                assertTrue(s.poll(3000).contains("m" + i));
            }
            a.stop();
        }
    }

    @Test
    void overflowDropsAndCountsWithoutBlocking() throws Exception {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx); a.setSyslogHost("10.255.255.1"); a.setPort(9); // unroutable, writer stalls
        a.setProtocol(Protocol.TCP); a.setQueueSize(10);
        a.start();
        Logger l = ctx.getLogger("t.Over"); l.addAppender(a);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) { l.info("x" + i); }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1000, "caller must not block");
        assertTrue(a.getDroppedCount() > 0);
        a.stop();
    }

    @Test
    void stopDrainsWithinTimeoutAndIsIdempotent() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15161, null)) {
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx); a.setSyslogHost("127.0.0.1"); a.setPort(15161);
            a.setSuffixPattern("%msg");
            a.start();
            Logger l = ctx.getLogger("t.Stop"); l.addAppender(a);
            l.info("last");
            a.stop();
            a.stop();                       // second stop must be harmless
            assertTrue(s.poll(2000).contains("last"));
            l.info("after-stop");           // must not throw
        }
    }
}
```

- [ ] **Step 2:** run, FAIL (missing setters). **Step 3: Implement** in `SyslogAppender`:

Fields/imports (add `java.util.concurrent.ArrayBlockingQueue`, `BlockingQueue`, `TimeUnit`, `atomic.AtomicLong`):

```java
private int queueSize = 4096;
private boolean blockWhenFull;
private long shutdownTimeoutMs = 2000;
private BlockingQueue<ILoggingEvent> queue;
private Thread writer;
private final AtomicLong dropped = new AtomicLong();
private volatile boolean running;
private long lastDropReport;
```

Change `private boolean sync = true;` to `false`. Start the writer at the end of `start()` (after `super.start()` guard, inside the success path):

```java
if (!sync) {
    queue = new ArrayBlockingQueue<>(queueSize);
    running = true;
    writer = Thread.ofVirtual().name("red5-syslog-" + getName()).start(this::drain);
}
```

Replace `append`:

```java
@Override
protected void append(ILoggingEvent event) {
    if (sync) {
        send(event);
        return;
    }
    event.prepareForDeferredProcessing();
    try {
        if (blockWhenFull) {
            queue.put(event);
        } else if (!queue.offer(event)) {
            reportDrop();
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}

private void reportDrop() {
    long n = dropped.incrementAndGet();
    long now = System.currentTimeMillis();
    if (now - lastDropReport > 10_000) {
        lastDropReport = now;
        addWarn("syslog queue full; " + n + " events dropped so far");
    }
}

private void drain() {
    try {
        while (running || !queue.isEmpty()) {
            ILoggingEvent e = queue.poll(100, TimeUnit.MILLISECONDS);
            if (e != null) {
                send(e);
            }
        }
    } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
    }
}

public long getDroppedCount() { return dropped.get(); }
```

Replace `stop()`:

```java
@Override
public synchronized void stop() {
    if (!isStarted()) {
        return;
    }
    running = false;
    if (writer != null) {
        try {
            writer.join(shutdownTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            writer.interrupt();
        }
        writer = null;
    }
    super.stop();     // AppenderBase.append() ignores events once stopped
    if (instanceName != null) {
        Syslog.destroyInstance(instanceName);
        instanceName = null;
    }
}
```

Add setters `setQueueSize`, `setBlockWhenFull`, `setShutdownTimeoutMs`. `AppenderBase.doAppend` already refuses to append after `stop()` (it logs a status warning once), which covers "log after stop".

Because `send` can stall on connect for an unroutable host in test 2, set a connect timeout on the TCP config in `newConfig()` if the ported `TCPNetSyslogConfig` exposes one (check `$REF/impl/net/tcp/TCPNetSyslogConfig.java`; if absent add `connectTimeoutMillis` default 3000 to the ported writer's `Socket.connect`). The test's unroutable address then stalls the writer thread only.

- [ ] **Step 4:** `mvn -q test -Dtest='SyslogAppender*Test'` PASS. **Step 5:** commit `Add async queue, overflow policy and bounded shutdown to SyslogAppender`.

---

### Task 8: Backlog ring buffer and replay

**Files:**
- Create: `src/main/java/org/red5/syslog/impl/backlog/RingBufferBackLogHandler.java`
- Modify: `SyslogAppender.java` (register handler, add `backlogSize` setter default 1000, `0` disables)
- Test: `src/test/java/org/red5/syslog/impl/backlog/RingBufferBackLogHandlerTest.java`, extend `SyslogAppenderAsyncTest`

**Interfaces:**
- Consumes: `SyslogBackLogHandlerIF { void initialize(); void down(SyslogIF, String); void up(SyslogIF); void log(SyslogIF, int level, String message, String reason) }`, `AbstractSyslogConfigIF.addBackLogHandler`.
- Produces: `RingBufferBackLogHandler(int capacity)` with `int size()`.

- [ ] **Step 1: Failing tests**

```java
package org.red5.syslog.impl.backlog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RingBufferBackLogHandlerTest {

    @Test
    void keepsNewestWhenFullAndReplaysOnUp() {
        RingBufferBackLogHandler h = new RingBufferBackLogHandler(2);
        h.log(null, 6, "a", "down");
        h.log(null, 6, "b", "down");
        h.log(null, 6, "c", "down");
        assertEquals(2, h.size());
        List<String> replayed = new ArrayList<>();
        h.replay((level, msg) -> replayed.add(msg));
        assertEquals(List.of("b", "c"), replayed);
        assertEquals(0, h.size());
    }
}
```

Appender test (add to `SyslogAppenderAsyncTest`): start appender (TCP) pointing at a port where no server runs, log `"early"`, then `CapturingServer.start("tcp", port, null)`, log `"later"`, and assert both arrive (poll up to 5s each; order `early` then `later`).

- [ ] **Step 2: Implement**

```java
package org.red5.syslog.impl.backlog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiConsumer;

import org.red5.syslog.SyslogBackLogHandlerIF;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;

/** Bounded in-memory backlog: keeps the newest messages while the destination is down and replays them on up(). */
public class RingBufferBackLogHandler implements SyslogBackLogHandlerIF {

    private record Entry(int level, String message) { }

    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final int capacity;

    public RingBufferBackLogHandler(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void initialize() throws SyslogRuntimeException {
        // nothing to prepare
    }

    @Override
    public void down(SyslogIF syslog, String reason) {
        // state is implicit: entries accumulate through log()
    }

    @Override
    public synchronized void log(SyslogIF syslog, int level, String message, String reason) {
        if (buffer.size() == capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(new Entry(level, message));
    }

    @Override
    public void up(SyslogIF syslog) {
        replay(syslog::log);
    }

    /** Drains the buffer oldest-first into the consumer (level, message). */
    public void replay(BiConsumer<Integer, String> sink) {
        Entry e;
        while ((e = pollFirst()) != null) {
            sink.accept(e.level(), e.message());
        }
    }

    private synchronized Entry pollFirst() {
        return buffer.pollFirst();
    }

    public synchronized int size() {
        return buffer.size();
    }
}
```

`syslog::log` as `BiConsumer<Integer,String>` resolves to `SyslogIF.log(int, String)` via unboxing. In the appender, when `backlogSize > 0`: `cfg.addBackLogHandler(new RingBufferBackLogHandler(backlogSize));` in `start()`. Re-entrancy: `up()` calls `syslog.log`, and a failing replay lands back in `log()` of the same handler, which is safe because replay drains to a fresh poll loop; if the destination goes down again mid-replay the remaining and re-failed messages are re-buffered (the loop terminates when the buffer is empty or refilled by at most capacity entries; guard with a count of `size()` taken at replay start in `replay` — implement as `for (int n = size(); n > 0; n--)` instead of `while`).

Also set `cfg.setThrowExceptionOnWrite(false)` in the appender so failures route to the backlog handlers rather than exceptions (the ported default is already `false`; assert it in the test).

- [ ] **Step 3:** `mvn -q test` PASS. **Step 4:** commit `Add ring buffer backlog handler and wire it into the appender`.

---

### Task 9: Structured data, modifiers, Joran configuration

**Files:**
- Modify: `SyslogAppender.java`
- Create: `src/test/java/org/red5/logback/syslog/SyslogAppenderConfigTest.java`, `src/test/resources/papertrail-style.xml`, `src/test/resources/full-featured.xml`

**Interfaces:**
- Consumes: `AbstractSyslogConfig.addMessageModifier(SyslogMessageModifierIF)`, `SyslogMessageModifierIF` implementations (`PrefixSyslogMessageModifier`, `SuffixSyslogMessageModifier`, `StringCaseSyslogMessageModifier`, `SequentialSyslogMessageModifier`, `HashSyslogMessageModifier`, `MacSyslogMessageModifier`, `ChecksumSyslogMessageModifier`, `HTMLEntityEscapeSyslogMessageModifier`), `StructuredSyslogMessageProcessor` (check its package under `impl/message/processor/structured`).
- Produces: `addModifier(SyslogMessageModifierIF)` on the appender (Joran nested `<modifier class="...">` support via standard `NestedComplexProperty` handling) and `addStructuredData(StructuredDataParam)` where `StructuredDataParam` is a small bean `{ String id; Map<String,String> params }` nested as `<structuredData id="meta@1234"><param name=... value=.../></structuredData>`.

- [ ] **Step 1: Failing tests**

`papertrail-style.xml` (a config copied from a papertrail logback-syslog4j setup with only the class swapped):

```xml
<configuration>
  <appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
    <syslogHost>127.0.0.1</syslogHost>
    <port>15170</port>
    <facility>LOCAL0</facility>
    <suffixPattern>[%thread] %logger %msg</suffixPattern>
    <stackTracePattern>%ex</stackTracePattern>
    <throwableExcluded>false</throwableExcluded>
    <sendLocalName>true</sendLocalName>
    <sendLocalTimestamp>true</sendLocalTimestamp>
    <maxMessageLength>128000</maxMessageLength>
  </appender>
  <root level="INFO"><appender-ref ref="SYSLOG"/></root>
</configuration>
```

`full-featured.xml` adds `<protocol>TCP</protocol><rfc5424>true</rfc5424><appName>red5</appName><queueSize>100</queueSize>` and `<modifier class="org.red5.syslog.impl.message.modifier.text.PrefixSyslogMessageModifier"><prefix>[r5] </prefix></modifier>` (use the real config nesting the ported modifier requires; check its constructor/config class in the ref and adjust the XML to match).

```java
package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;

class SyslogAppenderConfigTest {

    private LoggerContext configure(String resource) throws Exception {
        LoggerContext ctx = new LoggerContext();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(ctx);
        jc.doConfigure(getClass().getResource(resource));
        return ctx;
    }

    @Test
    void papertrailConfigWorksUnchanged() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15170, null)) {
            LoggerContext ctx = configure("/papertrail-style.xml");
            ctx.getLogger("conf.Test").info("from-xml");
            assertTrue(s.poll(3000).contains("conf.Test from-xml"));
            ctx.stop();
        }
    }

    @Test
    void fullFeaturedConfigStartsAndAppliesModifier() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15171, null)) {
            LoggerContext ctx = configure("/full-featured.xml");
            ctx.getLogger("conf.Full").info("hello");
            String m = s.poll(3000);
            assertNotNull(m);
            assertTrue(m.contains("[r5] "), m);
            ctx.stop();
        }
    }
}
```

(Port in `full-featured.xml` must be 15171. Remove the unused `LoggerFactory` import if not needed.)

- [ ] **Step 2:** run; the papertrail test should already pass, the full-featured one fails (no `addModifier`).
- [ ] **Step 3: Implement**

Add to `SyslogAppender`:

```java
private final List<SyslogMessageModifierIF> modifiers = new ArrayList<>();

public void addModifier(SyslogMessageModifierIF m) {
    modifiers.add(m);
}
```

and in `start()` before `Syslog.createInstance`: `modifiers.forEach(cfg::addMessageModifier);`. For structured data add the `StructuredDataParam` bean and `addStructuredData(...)`; in `start()`, when `rfc5424` is true and any structured data was supplied, install a `StructuredSyslogMessageProcessor` configured with `appName` and the supplied elements via the ported API (read `$REF/impl/message/structured/StructuredSyslogMessage.java` for the constructor `StructuredSyslogMessage(String message, Map structuredData)`; build one message per event with the configured elements and call `syslog.log(level, SyslogMessageIF)`). Add a test case asserting the line contains `[meta@1234 k="v"]` when RFC 5424 is on.

Joran handles `addModifier` (nested component via `add*` method) and instantiates the `class=` attribute automatically; `Protocol` enum values are converted by Joran's `StringToObjectConverter`.

- [ ] **Step 4:** `mvn -q test` PASS. **Step 5:** commit `Support modifiers and RFC 5424 structured data via Joran`.

---

### Task 10: CLI mains, final verification, packaging check

**Files:**
- Verify: `SyslogMain` and `SyslogServerMain` compile and are renamed to `org.red5.syslog.*`
- Create: `src/test/java/org/red5/syslog/PackagingTest.java`

- [ ] **Step 1: Failing packaging guard test**

```java
package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PackagingTest {

    @Test
    void sourcesContainNoForbiddenReferences() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String s = Files.readString(p);
                    for (String bad : new String[] { "org.apache.log4j", "com.sun.jna", "org.apache.commons.pool", "org.productivity" }) {
                        assertFalse(s.contains(bad), p + " references " + bad);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
```

- [ ] **Step 2:** run `mvn -q test -Dtest=PackagingTest`; fix any match (e.g. leftover Javadoc references to `org.productivity` in comments: update to `org.red5.syslog`; keep the copyright text).
- [ ] **Step 3: Full verification**

```bash
mvn -q clean verify
unzip -l target/red5-logback-syslog-1.0.0-SNAPSHOT.jar | grep -E "log4j|jna|commons" ; echo "forbidden entries exit=$?"   # expect exit=1
unzip -p target/red5-logback-syslog-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Automatic-Module-Name
jdeps --multi-release 21 --print-module-deps target/red5-logback-syslog-1.0.0-SNAPSHOT.jar 2>&1 | head
```

Expected: all tests PASS, no forbidden entries, manifest has `Automatic-Module-Name: org.red5.syslog`, `jdeps` reports only JDK modules plus logback/slf4j.

- [ ] **Step 4:** Smoke the CLIs: `java -cp target/classes org.red5.syslog.server.SyslogServerMain -h` prints usage; same for `org.red5.syslog.SyslogMain`.
- [ ] **Step 5: Commit** `git add -A src pom.xml && git commit -m "Add packaging guard and verify single-jar build"`

---

## Self-Review

**Spec coverage:** transports UDP/TCP/TLS/unix/multiple/pooled (Tasks 1-3, 5); RFC 3164/5424, structured data, PCI, modifiers, processors (ported in Task 1, configured in Task 9); backlog handlers incl. print-stream (Task 1 port, ring buffer Task 8); server (Task 1, used by every test); appender property compatibility (Tasks 6, 9); async/virtual-thread writer and overflow/shutdown (Task 7); error handling via `addError` and start validation (Tasks 6, 7); enums (Task 4); JDK 21 and no forbidden dependencies (Tasks 1, 10); LGPL retention (Task 1). CLI mains retained (Task 10). The `Syslog` static registry is retained in Task 1 and not used by the appender beyond `createInstance`/`destroyInstance`.

**Known deviation to confirm:** `UNIX_SYSLOG` previously called libc `syslog()` through JNA. JDK 21 has no stable foreign-function API (FFM is preview in 21), so it now shares the `/dev/log` socket implementation (Task 3).

**Verification risk:** several reference signatures (event handler interfaces, SSL server config interface, `AbstractSyslog` abstract methods, `TCPNetSyslogConfig` connect timeout, structured processor package) were not read in full. Each task names the file to confirm, and the failing-test-first order surfaces any mismatch at the first compile.
