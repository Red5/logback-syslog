package org.red5.syslog.impl.net.tcp.ssl;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import org.red5.syslog.impl.net.tcp.TCPNetSyslogWriter;

/**
* SSLTCPNetSyslogWriter is an implementation of Runnable that supports sending
* TCP/IP-based (over SSL/TLS) messages within a separate Thread.
* 
* <p>When used in "threaded" mode (see TCPNetSyslogConfig for the option),
* a queuing mechanism is used (via LinkedList).</p>
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: SSLTCPNetSyslogWriter.java,v 1.4 2009/03/29 17:38:58 cvs Exp $
*/
public class SSLTCPNetSyslogWriter extends TCPNetSyslogWriter {
	private static final long serialVersionUID = 8944446235285662244L;

	private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	/**
	 * Layers TLS over the connected plain socket using this instance's private SSLContext. The configured host name
	 * (not the resolved address) is used for SNI and for certificate verification, which is on unless
	 * sslVerifyHostname is false. The handshake runs under SO_TIMEOUT = connectTimeoutMillis so a peer that accepts
	 * TCP but never answers cannot block the writer; the original read timeout is restored afterwards.
	 */
	protected Socket wrapSocket(Socket connectedSocket) throws IOException {
		SSLTCPNetSyslogConfigIF sslConfig = (SSLTCPNetSyslogConfigIF) this.tcpNetSyslogConfig;
		SSLContext sslContext = ((SSLTCPNetSyslog) this.tcpNetSyslog).getSSLContext();
		
		String host = sslConfig.getHost();
		int port = sslConfig.getPort();
		
		SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(connectedSocket,host,port,true);
		
		try {
			SSLParameters params = sslSocket.getSSLParameters();
			
			if (sslConfig.isSslVerifyHostname()) {
				params.setEndpointIdentificationAlgorithm("HTTPS");
			}
			
			if (host != null && !isIpLiteral(host)) {
				try {
					params.setServerNames(List.<SNIServerName>of(new SNIHostName(host)));
					
				} catch (IllegalArgumentException notAHostName) {
					// leave SNI out rather than fail the connection
				}
			}
			
			sslSocket.setSSLParameters(params);
			
			int originalTimeout = sslSocket.getSoTimeout();
			sslSocket.setSoTimeout(Math.max(0,sslConfig.getConnectTimeoutMillis()));
			sslSocket.startHandshake();
			sslSocket.setSoTimeout(originalTimeout);
			
			return sslSocket;
			
		} catch (IOException | RuntimeException e) {
			try {
				sslSocket.close();
			} catch (IOException ignore) {
				//
			}
			throw e;
		}
	}

	private static boolean isIpLiteral(String host) {
		return host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches();
	}
}
