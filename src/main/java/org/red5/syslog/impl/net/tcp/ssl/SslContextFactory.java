package org.red5.syslog.impl.net.tcp.ssl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.red5.syslog.SyslogRuntimeException;

/**
 * Builds a private {@link SSLContext} from explicit key and trust store settings. Nothing here reads or writes the
 * JVM-wide {@code javax.net.ssl.*} system properties or the default SSLContext, so several clients and servers with
 * different stores can coexist in one JVM and the host application's TLS settings are left alone.
 *
 * <p>With no trust store the platform default trust managers are used; with no key store no key managers are used.
 * Store passwords are never included in exception messages.</p>
 */
public final class SslContextFactory {

    private SslContextFactory() {
    }

    public static SSLContext create(String keyStore, String keyStorePassword, String trustStore, String trustStorePassword) {
        try {
            KeyManager[] keyManagers = null;
            if (!isBlank(keyStore)) {
                char[] pw = toChars(keyStorePassword);
                KeyStore ks = load(keyStore, pw);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pw == null ? new char[0] : pw);
                keyManagers = kmf.getKeyManagers();
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (!isBlank(trustStore)) {
                tmf.init(load(trustStore, toChars(trustStorePassword)));
            } else {
                tmf.init((KeyStore) null);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new SyslogRuntimeException("cannot initialize TLS: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Loads a store with automatic type detection, falling back to JKS and then PKCS12. */
    static KeyStore load(String path, char[] password) throws GeneralSecurityException, IOException {
        File file = new File(path);
        try {
            return KeyStore.getInstance(file, password);
        } catch (KeyStoreException detectFailed) {
            GeneralSecurityException last = detectFailed;
            for (String type : new String[] { "JKS", "PKCS12" }) {
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    KeyStore ks = KeyStore.getInstance(type);
                    ks.load(in, password);
                    return ks;
                } catch (GeneralSecurityException e) {
                    last = e;
                } catch (IOException e) {
                    if (e.getCause() instanceof GeneralSecurityException gse) {
                        last = gse;
                    } else {
                        throw e;
                    }
                }
            }
            throw last;
        }
    }

    private static char[] toChars(String password) {
        return password == null ? null : password.toCharArray();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
