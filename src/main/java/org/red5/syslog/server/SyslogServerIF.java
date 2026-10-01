package org.red5.syslog.server;

import org.red5.syslog.SyslogRuntimeException;

/**
* SyslogServerIF provides a common interface for all Syslog4j server implementations.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: SyslogServerIF.java,v 1.5 2008/11/07 15:15:41 cvs Exp $
*/
public interface SyslogServerIF extends Runnable {
	public void initialize(String protocol, SyslogServerConfigIF config) throws SyslogRuntimeException;
	
	public String getProtocol();
	public SyslogServerConfigIF getConfig();

	public void run();
	
	public Thread getThread();
	public void setThread(Thread thread);

	public void shutdown();
}
