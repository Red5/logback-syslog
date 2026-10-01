package org.red5.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class PackagingTest {

    private static final String[] FORBIDDEN = { "org.apache.log4j", "com.sun.jna", "org.apache.commons.pool", "org.productivity", "org.joda" };

    @Test
    void sourcesContainNoForbiddenReferences() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(sources.isEmpty(), "the scan must find the main sources");
        for (Path p : sources) {
            String s = Files.readString(p);
            for (String bad : FORBIDDEN) {
                assertFalse(s.contains(bad), p + " references " + bad);
            }
        }
    }
}
