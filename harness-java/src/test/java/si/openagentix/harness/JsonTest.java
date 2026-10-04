package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test void roundTripsAllTypesWithSortedKeys() {
        String text = "{\"b\":[1,2.5,-3,true,false,null,\"x\"],\"a\":{\"z\":1,\"y\":\"q\"}}";
        assertEquals("{\"a\":{\"y\":\"q\",\"z\":1},\"b\":[1,2.5,-3,true,false,null,\"x\"]}", Json.write(Json.parse(text)));
    }

    @Test void parsesIntegersAsLongAndOthersAsDouble() {
        assertEquals(7L, Json.parse("7"));
        assertEquals(1.5e3, Json.parse("1.5e3"));
    }

    @Test void handlesEscapesAndWhitespace() {
        assertEquals("a\"b\\c\n\t\r\b\f/é", Json.parse(" \"a\\\"b\\\\c\\n\\t\\r\\b\\f\\/\\u00e9\" "));
        assertEquals("\"\\u0001\\n\\\"\\\\\\t\\r\\b\\f é\"", Json.write("\u0001\n\"\\\t\r\b\f é"));
        assertEquals(List.of(), Json.parse("[ ]"));
        assertEquals(Map.of(), Json.parse("{ }"));
    }

    @Test void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "[1,", "{\"a\"}", "{\"a\":1} x", "\"abc", "\"\\q\"", "tru", "-", "{1:2}", "[1 2]")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
    }
}
