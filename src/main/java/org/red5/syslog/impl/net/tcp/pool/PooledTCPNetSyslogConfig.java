package org.red5.syslog.impl.net.tcp.pool;

import org.red5.syslog.SyslogPoolConfigIF;
import org.red5.syslog.impl.net.tcp.TCPNetSyslogConfig;

/**
* NetSyslogPoolFactory is an implementation of SyslogPoolConfigIF
* which provides configuration support for the internal writer pool.
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: PooledTCPNetSyslogConfig.java,v 1.3 2008/11/26 15:01:47 cvs Exp $
*/
public class PooledTCPNetSyslogConfig extends TCPNetSyslogConfig implements SyslogPoolConfigIF {
	private static final long serialVersionUID = 2283355983363422888L;
	
	/** Block until a writer is available (value of the former commons-pool WHEN_EXHAUSTED_BLOCK). */
	public static final byte WHEN_EXHAUSTED_BLOCK = 1;

	protected int maxActive							= SYSLOG_POOL_CONFIG_MAX_ACTIVE_DEFAULT;
	protected int maxIdle							= SYSLOG_POOL_CONFIG_MAX_IDLE_DEFAULT;
	protected long maxWait							= SYSLOG_POOL_CONFIG_MAX_WAIT_DEFAULT;
	protected long minEvictableIdleTimeMillis		= SYSLOG_POOL_CONFIG_MIN_EVICTABLE_IDLE_TIME_MILLIS_DEFAULT;
	protected int minIdle							= SYSLOG_POOL_CONFIG_MIN_IDLE_DEFAULT;
	protected int numTestsPerEvictionRun			= SYSLOG_POOL_CONFIG_NUM_TESTS_PER_EVICTION_RUN_DEFAULT;
	protected long softMinEvictableIdleTimeMillis	= SYSLOG_POOL_CONFIG_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS_DEFAULT;
	protected long timeBetweenEvictionRunsMillis	= SYSLOG_POOL_CONFIG_TIME_BETWEEN_EVICTION_RUNS_MILLIS_DEFAULT;
	protected byte whenExhaustedAction				= WHEN_EXHAUSTED_BLOCK;
	protected boolean testOnBorrow					= SYSLOG_POOL_CONFIG_TEST_ON_BORROW_DEFAULT;
	protected boolean testOnReturn					= SYSLOG_POOL_CONFIG_TEST_ON_RETURN_DEFAULT;
	protected boolean testWhileIdle					= SYSLOG_POOL_CONFIG_TEST_WHILE_IDLE_DEFAULT;
	
	public PooledTCPNetSyslogConfig() {
		//
	}

	public PooledTCPNetSyslogConfig(int facility, String host, int port) {
		super(facility, host, port);
	}

	public PooledTCPNetSyslogConfig(int facility, String host) {
		super(facility, host);
	}

	public PooledTCPNetSyslogConfig(int facility) {
		super(facility);
	}

	public PooledTCPNetSyslogConfig(String host, int port) {
		super(host, port);
	}

	public PooledTCPNetSyslogConfig(String host) {
		super(host);
	}
	
	protected void configureThreadedValues(int value) {
		if (isThreaded()) {
			this.minIdle = value;
			this.maxIdle = value;
			this.maxActive = value;
		}
	}
	
	public int getMaxActive() {
		return this.maxActive;
	}

	public void setMaxActive(int maxActive) {
		configureThreadedValues(maxActive);
		
		this.maxActive = maxActive;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public int getMaxIdle() {
		return this.maxIdle;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setMaxIdle(int maxIdle) {
		configureThreadedValues(maxIdle);
		
		this.maxIdle = maxIdle;
	}

	public long getMaxWait() {
		return this.maxWait;
	}

	public void setMaxWait(long maxWait) {
		this.maxWait = maxWait;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public long getMinEvictableIdleTimeMillis() {
		return this.minEvictableIdleTimeMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setMinEvictableIdleTimeMillis(long minEvictableIdleTimeMillis) {
		this.minEvictableIdleTimeMillis = minEvictableIdleTimeMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public int getMinIdle() {
		return this.minIdle;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setMinIdle(int minIdle) {
		configureThreadedValues(minIdle);
		
		this.minIdle = minIdle;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public int getNumTestsPerEvictionRun() {
		return this.numTestsPerEvictionRun;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setNumTestsPerEvictionRun(int numTestsPerEvictionRun) {
		this.numTestsPerEvictionRun = numTestsPerEvictionRun;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public long getSoftMinEvictableIdleTimeMillis() {
		return this.softMinEvictableIdleTimeMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setSoftMinEvictableIdleTimeMillis(
			long softMinEvictableIdleTimeMillis) {
		this.softMinEvictableIdleTimeMillis = softMinEvictableIdleTimeMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public long getTimeBetweenEvictionRunsMillis() {
		return this.timeBetweenEvictionRunsMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
		this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public byte getWhenExhaustedAction() {
		return this.whenExhaustedAction;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setWhenExhaustedAction(byte whenExhaustedAction) {
		this.whenExhaustedAction = whenExhaustedAction;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public boolean isTestOnBorrow() {
		return this.testOnBorrow;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setTestOnBorrow(boolean testOnBorrow) {
		this.testOnBorrow = testOnBorrow;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public boolean isTestOnReturn() {
		return this.testOnReturn;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setTestOnReturn(boolean testOnReturn) {
		this.testOnReturn = testOnReturn;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public boolean isTestWhileIdle() {
		return this.testWhileIdle;
	}

	/** @deprecated ignored; kept for syslog4j API compatibility. */
	@Deprecated
	public void setTestWhileIdle(boolean testWhileIdle) {
		this.testWhileIdle = testWhileIdle;
	}

	public Class getSyslogClass() {
		return PooledTCPNetSyslog.class;
	}
}
