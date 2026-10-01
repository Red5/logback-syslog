package org.red5.syslog.impl.net.tcp.pool;

import org.red5.syslog.SyslogPoolConfigIF;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.AbstractSyslogConfigIF;
import org.red5.syslog.impl.AbstractSyslogWriter;
import org.red5.syslog.impl.net.tcp.TCPNetSyslog;
import org.red5.syslog.impl.pool.SyslogWriterPool;

/**
* PooledTCPNetSyslog is an extension of TCPNetSyslog which provides support
* for pooled writers using a small internal bounded pool. Only maxActive and
* maxWait of {@link SyslogPoolConfigIF} are honored; the idle, eviction and
* test settings are ignored and kept for syslog4j API compatibility.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: PooledTCPNetSyslog.java,v 1.5 2008/12/10 04:30:15 cvs Exp $
*/
public class PooledTCPNetSyslog extends TCPNetSyslog {
	private static final long serialVersionUID = 4279960451141784200L;
	
	protected SyslogWriterPool pool = null;

	public void initialize() throws SyslogRuntimeException {
		super.initialize();
		
		SyslogPoolConfigIF poolConfig = (SyslogPoolConfigIF) this.syslogConfig;
		
		this.pool = new SyslogWriterPool(() -> {
			AbstractSyslogWriter writer = createWriter();
			
			if (((AbstractSyslogConfigIF) this.syslogConfig).isThreaded()) {
				createWriterThread(writer);
			}
			
			return writer;
		}, poolConfig.getMaxActive(), poolConfig.getMaxWait());
	}

	public AbstractSyslogWriter getWriter() {
		try {
			AbstractSyslogWriter syslogWriter = this.pool.borrow();
			
			if (syslogWriter == null) {
				throw new SyslogRuntimeException("Timed out waiting for a pooled syslog writer");
			}
		
			return syslogWriter;
			
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SyslogRuntimeException(e);
		}
	}

	public void returnWriter(AbstractSyslogWriter syslogWriter) {
		this.pool.release(syslogWriter);
	}

	public void flush() throws SyslogRuntimeException {
		this.pool.clear();
	}

	public void shutdown() throws SyslogRuntimeException {
		this.pool.close();
	}
}
