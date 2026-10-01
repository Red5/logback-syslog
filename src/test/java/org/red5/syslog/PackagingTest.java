package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PackagingTest {

    @Test
    void sourcesContainNoForbiddenReferences() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String s = Files.readString(p);
                    for (String bad : new String[] { "org.apache.log4j", "com.sun.jna", "org.apache.commons.pool", "org.productivity" }) {
                        assertFalse(s.contains(bad), p + " references " + bad);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
