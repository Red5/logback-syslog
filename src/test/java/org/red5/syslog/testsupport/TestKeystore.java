package org.red5.syslog.testsupport;

import java.io.File;
import java.nio.file.Path;

public final class TestKeystore {

    private TestKeystore() {
    }

    private static String keytool() {
        File f = new File(System.getProperty("java.home"), "bin/keytool");
        return f.canExecute() ? f.getPath() : "keytool";
    }

    /** Self-signed CN=localhost with SAN dns:localhost,ip:127.0.0.1, stored as dir/test.jks. */
    public static Path create(Path dir, String password) throws Exception {
        return create(dir, "test.jks", password, "dns:localhost,ip:127.0.0.1");
    }

    /** Self-signed key pair with the given subjectAltName (keytool syntax, e.g. "dns:other.example"), stored as dir/fileName. */
    public static Path create(Path dir, String fileName, String password, String san) throws Exception {
        Path ks = dir.resolve(fileName);
        Process p = new ProcessBuilder(keytool(), "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "san=" + san, "-validity", "2",
                "-keystore", ks.toString(), "-storepass", password, "-keypass", password, "-storetype", "JKS")
                .redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException(new String(out));
        }
        return ks;
    }
}
