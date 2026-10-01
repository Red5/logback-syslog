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
