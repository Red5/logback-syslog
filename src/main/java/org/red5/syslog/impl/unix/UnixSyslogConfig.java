package org.red5.syslog.impl.unix;

import org.red5.syslog.impl.unix.socket.UnixSocketSyslogConfig;

/**
* UnixSyslogConfig is the configuration for {@link UnixSyslog}. It shares the
* socket settings (path, type) of {@link UnixSocketSyslogConfig}.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: UnixSyslogConfig.java,v 1.10 2010/10/25 04:21:19 cvs Exp $
*/
public class UnixSyslogConfig extends UnixSocketSyslogConfig {
	private static final long serialVersionUID = -4805767812011660656L;

	/** Retained for configuration compatibility; no effect on the socket transport. */
	protected int option = OPTION_NONE;

	public UnixSyslogConfig() {
		super();
	}

	public Class getSyslogClass() {
		return UnixSyslog.class;
	}

	public int getOption() {
		return this.option;
	}

	public void setOption(int option) {
		this.option = option;
	}
}
