package com.enterprise.compliance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP method={} status={} elapsedMs={}", request.getMethod(),
                    response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove("requestId");
        }
    }
}
