package org.red5.syslog.impl.net.udp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;

/** Regression: a persistent send failure must end in SyslogRuntimeException, not loop forever. */
class UDPNetSyslogRetryTest {

    @Test
    void persistentSendFailureThrowsAfterRetries() {
        UDPNetSyslogConfig c = new UDPNetSyslogConfig();
        c.setHost("127.0.0.1");
        c.setPort(15148);
        c.setWriteRetries(2);
        c.setThrowExceptionOnWrite(true);
        SyslogIF log = Syslog.createInstance("t-udp-retry", c);
        try {
            // a closed socket makes every send() throw an IOException
            ((UDPNetSyslog) log).socket.close();
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThrows(SyslogRuntimeException.class, () -> log.info("fails")));
        } finally {
            Syslog.destroyInstance("t-udp-retry");
        }
    }
}
