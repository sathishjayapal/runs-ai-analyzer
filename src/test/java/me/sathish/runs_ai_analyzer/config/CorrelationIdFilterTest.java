package me.sathish.runs_ai_analyzer.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAnIdPutsItInMdcDuringTheRequestAndEchoesItBack() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/run-journal"), response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            }
        });

        assertNotNull(seenInChain.get());
        assertEquals(seenInChain.get(), response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "must not leak to the next request on this thread");
    }

    @Test
    void honorsAnIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("abc-123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }
}
