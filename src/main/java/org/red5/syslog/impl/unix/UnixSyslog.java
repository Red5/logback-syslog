package org.red5.syslog.impl.unix;

import org.red5.syslog.impl.unix.socket.UnixSocketSyslog;

/**
* UnixSyslog is the Unix-based syslog client. The original Syslog4j
* implementation called libc <code>syslog()</code> through JNA. This port has
* no JNA dependency; it writes to the local syslog unix domain socket
* (<code>/dev/log</code> by default) and shares the implementation of
* {@link UnixSocketSyslog}.
* 
* <p>The default socket type is datagram, which is what /dev/log (journald,
* rsyslog) and macOS /var/run/syslog are; it uses java.lang.foreign reflectively
* and works on Linux and macOS/BSD, not on Windows. The stream type serves stream
* listeners (syslog-ng unix-stream, rsyslog stream input and similar).</p>
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: UnixSyslog.java,v 1.27 2010/10/25 04:21:19 cvs Exp $
*/
public class UnixSyslog extends UnixSocketSyslog {
	private static final long serialVersionUID = 4973353204252276740L;
}
