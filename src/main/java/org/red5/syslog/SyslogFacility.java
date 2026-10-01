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
