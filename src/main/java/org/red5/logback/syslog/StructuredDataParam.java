package org.red5.logback.syslog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One RFC 5424 SD-ELEMENT: an SD-ID and its parameters. In XML:
 * <pre>
 * &lt;structuredData&gt;
 *   &lt;id&gt;meta@1234&lt;/id&gt;
 *   &lt;entry&gt;&lt;name&gt;k&lt;/name&gt;&lt;value&gt;v&lt;/value&gt;&lt;/entry&gt;
 * &lt;/structuredData&gt;
 * </pre>
 * Joran only sets properties from the {@code class} attribute and child elements, so the id is a child element and
 * the parameters are {@code entry} children (the {@code param} element name is reserved by Joran for property setting).
 */
public class StructuredDataParam {

    /** One parameter of an element; nested in XML as {@code <entry><name>..</name><value>..</value></entry>}. */
    public static class Entry {
        private String name;
        private String value;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    private String id;
    private final Map<String, String> params = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Map<String, String> getParams() { return params; }

    public void addParam(String name, String value) {
        params.put(name, value);
    }

    /** Joran entry point. A repeated name keeps the last value. */
    public void addEntry(Entry e) {
        addParam(e.getName(), e.getValue());
    }
}
