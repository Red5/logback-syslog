package org.red5.syslog.impl.message.modifier.mac;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Mac;

import org.red5.syslog.SyslogIF;
import org.red5.syslog.SyslogRuntimeException;
import org.red5.syslog.impl.message.modifier.AbstractSyslogMessageModifier;
import java.util.Base64;
import org.red5.syslog.util.SyslogUtility;

/**
* MacSyslogMessageModifier is an implementation of SyslogMessageModifierIF
* that provides support for Java Cryptographic signed hashes (HmacSHA1, etc.)
* 
* <p>Syslog4j is licensed under the Lesser GNU Public License v2.1.  A copy
* of the LGPL license is available in the META-INF folder in all
* distributions of Syslog4j and in the base directory of the "doc" ZIP.</p>
* 
* @author &lt;syslog4j@productivity.org&gt;
* @version $Id: MacSyslogMessageModifier.java,v 1.5 2010/10/28 05:10:57 cvs Exp $
*/
public class MacSyslogMessageModifier extends AbstractSyslogMessageModifier {
	private static final long serialVersionUID = 5054979194802197540L;

	protected MacSyslogMessageModifierConfig config = null;
	
	protected Mac mac = null;
	
	public MacSyslogMessageModifier(MacSyslogMessageModifierConfig config) throws SyslogRuntimeException {
		super(config);
		
		this.config = config;

		try {
			this.mac = Mac.getInstance(config.getMacAlgorithm());
			this.mac.init(config.getKey());
			
		} catch (NoSuchAlgorithmException nsae) {
			throw new SyslogRuntimeException(nsae);
			
		} catch (InvalidKeyException ike) {
			throw new SyslogRuntimeException(ike);
		}
	}

	public static MacSyslogMessageModifier createHmacSHA1(Key key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA1(key));
	}

	public static MacSyslogMessageModifier createHmacSHA1(String base64Key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA1(base64Key));
	}

	public static MacSyslogMessageModifier createHmacSHA256(Key key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA256(key));
	}

	public static MacSyslogMessageModifier createHmacSHA256(String base64Key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA256(base64Key));
	}

	public static MacSyslogMessageModifier createHmacSHA512(Key key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA512(key));
	}

	public static MacSyslogMessageModifier createHmacSHA512(String base64Key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacSHA512(base64Key));
	}

	public static MacSyslogMessageModifier createHmacMD5(Key key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacMD5(key));
	}

	public static MacSyslogMessageModifier createHmacMD5(String base64Key) {
		return new MacSyslogMessageModifier(MacSyslogMessageModifierConfig.createHmacMD5(base64Key));
	}

	public MacSyslogMessageModifierConfig getConfig() {
		return this.config;
	}

	public String modify(SyslogIF syslog, int facility, int level, String message) {
		synchronized(this.mac) {
			byte[] messageBytes = SyslogUtility.getBytes(syslog.getConfig(),message);
			
			StringBuffer buffer = new StringBuffer(message);
			
			byte[] macBytes = this.mac.doFinal(messageBytes);
			
			String macString = Base64.getEncoder().encodeToString(macBytes);
			
			buffer.append(this.config.getPrefix());
			buffer.append(macString);
			buffer.append(this.config.getSuffix());
			
			return buffer.toString();
		}
	}
	
	public boolean verify(String message, String base64Signature) {
		byte[] signature = Base64.getDecoder().decode(base64Signature);
		
		return verify(message,signature);
	}
	
	public boolean verify(String message, byte[] signature) {
		synchronized(this.mac) {
			byte[] messageBytes = SyslogUtility.getBytes(this.config,message);
			
			byte[] macBytes = this.mac.doFinal(messageBytes);
			
			return Arrays.equals(macBytes,signature);
		}
	}
}
