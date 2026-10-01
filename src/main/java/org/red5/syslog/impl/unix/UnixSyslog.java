package org.red5.syslog.impl.unix;

import org.red5.syslog.impl.unix.socket.UnixSocketSyslog;

/**
* UnixSyslog is the Unix-based syslog client. The original Syslog4j
* implementation called libc <code>syslog()</code> through JNA. This port has
* no native dependencies (and the JDK has no stable foreign function API in
* version 21), so it now writes to the local syslog unix domain socket
* (<code>/dev/log</code> by default), exactly like {@link UnixSocketSyslog}.
* 
* <p>Limitation: stream sockets only, so it works with stream listeners
* (syslog-ng unix-stream, rsyslog stream input and similar) but not with
* datagram-only /dev/log.</p>
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
