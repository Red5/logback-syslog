package org.red5.syslog.server;

import java.io.Serializable;

public abstract interface SyslogServerEventHandlerIF extends Serializable {
	public void initialize(SyslogServerIF syslogServer);
	public void destroy(SyslogServerIF syslogServer);
}
