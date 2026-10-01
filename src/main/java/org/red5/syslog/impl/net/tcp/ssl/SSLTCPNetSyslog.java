package org.red5.syslog.impl.net.tcp.ssl;

import javax.net.ssl.SSLContext;

import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.net.tcp.TCPNetSyslog;

/**
* SSLTCPNetSyslog is an extension of AbstractSyslog that provides support for
* TCP/IP-based (over SSL/TLS) syslog clients.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: SSLTCPNetSyslog.java,v 1.1 2009/03/29 17:38:58 cvs Exp $
*/
public class SSLTCPNetSyslog extends TCPNetSyslog {
	private static final long serialVersionUID = 2766654802524487317L;

	/** Private to this instance: built from the configured stores, never from or into javax.net.ssl.* system properties. */
	protected transient volatile SSLContext sslContext = null;

	public void initialize() throws SyslogRuntimeException {
		super.initialize();
		
		SSLTCPNetSyslogConfigIF sslTcpNetSyslogConfig = (SSLTCPNetSyslogConfigIF) this.tcpNetSyslogConfig;
		
		this.sslContext = SslContextFactory.create(
			sslTcpNetSyslogConfig.getKeyStore(),sslTcpNetSyslogConfig.getKeyStorePassword(),
			sslTcpNetSyslogConfig.getTrustStore(),sslTcpNetSyslogConfig.getTrustStorePassword());
	}

	/**
	 * @return the SSLContext of this instance
	 */
	public SSLContext getSSLContext() {
		return this.sslContext;
	}
}
