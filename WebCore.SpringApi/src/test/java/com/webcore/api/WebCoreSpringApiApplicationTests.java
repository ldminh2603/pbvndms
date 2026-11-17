package com.webcore.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCoreSpringApiApplicationTests {
    @Test
    void applicationTargetsJava21() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
