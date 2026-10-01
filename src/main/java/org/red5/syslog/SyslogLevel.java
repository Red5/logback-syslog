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
