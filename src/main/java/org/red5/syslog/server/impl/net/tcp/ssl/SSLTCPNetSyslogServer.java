package org.red5.syslog.server.impl.net.tcp.ssl;

import java.io.IOException;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLContext;

import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.net.tcp.ssl.SslContextFactory;
import org.red5.syslog.server.impl.net.tcp.TCPNetSyslogServer;

/**
* SSLTCPNetSyslogServer provides a simple threaded TCP/IP server implementation
* which uses SSL/TLS.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: SSLTCPNetSyslogServer.java,v 1.1 2009/03/29 17:38:58 cvs Exp $
*/
public class SSLTCPNetSyslogServer extends TCPNetSyslogServer {
	/** Private to this server: built from the configured stores, never from or into javax.net.ssl.* system properties. */
	protected volatile SSLContext sslContext = null;

	public void initialize() throws SyslogRuntimeException {
		super.initialize();
		
		SSLTCPNetSyslogServerConfigIF sslTcpNetSyslogServerConfig = (SSLTCPNetSyslogServerConfigIF) this.tcpNetSyslogServerConfig;
		
		this.sslContext = SslContextFactory.create(
			sslTcpNetSyslogServerConfig.getKeyStore(),sslTcpNetSyslogServerConfig.getKeyStorePassword(),
			sslTcpNetSyslogServerConfig.getTrustStore(),sslTcpNetSyslogServerConfig.getTrustStorePassword());
	}

	protected ServerSocketFactory getServerSocketFactory() throws IOException {
		return this.sslContext.getServerSocketFactory();
	}
}
