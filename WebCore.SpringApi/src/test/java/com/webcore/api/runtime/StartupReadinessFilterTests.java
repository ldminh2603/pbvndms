package com.webcore.api.runtime;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupReadinessFilterTests {
    @Test
    void rejectsBusinessRequestsWhileTheRuntimeIsWarming() throws Exception {
        StartupReadinessFilter filter = new StartupReadinessFilter(gate(false));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/module/03L43.MMN"), response, recordingChain(chainCalled));

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("APPLICATION_STARTING"));
        assertFalse(chainCalled.get());
    }

    @Test
    void permitsBusinessRequestsAfterTheRuntimeIsReady() throws Exception {
        StartupReadinessFilter filter = new StartupReadinessFilter(gate(true));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/module/03L43.MMN"), response, recordingChain(chainCalled));

        assertTrue(chainCalled.get());
    }

    @Test
    void keepsReadinessEndpointAvailableDuringWarmup() throws Exception {
        StartupReadinessFilter filter = new StartupReadinessFilter(gate(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health/ready");

        assertTrue(filter.shouldNotFilter(request));
    }

    private static FilterChain recordingChain(AtomicBoolean chainCalled) {
        return (request, response) -> chainCalled.set(true);
    }

    private static StartupGate gate(boolean ready) {
        return new StartupGate(null, null) {
            @Override
            public State state() {
                return new State(ready, Instant.now(), List.of());
            }
        };
    }
}
