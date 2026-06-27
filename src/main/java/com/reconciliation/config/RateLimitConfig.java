package com.reconciliation.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RateLimitConfig implements Filter {

    private final Cache<String, Bucket> webhookBuckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    private final Cache<String, Bucket> authBuckets = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    private static final String[] AUTH_PATHS = {
            "/api/v1/merchants/login",
            "/api/v1/merchants/auth",
            "/api/v1/merchants/register",
            "/api/v1/merchants/reset-key",
            "/api/v1/auth/"
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();
        String ipAddress = httpRequest.getRemoteAddr();

        if (path.startsWith("/webhooks/")) {
            Bucket bucket = webhookBuckets.get(ipAddress, this::newWebhookBucket);
            if (!bucket.tryConsume(1)) {
                reject(response);
                return;
            }
        } else if (isAuthPath(path)) {
            Bucket bucket = authBuckets.get(ipAddress, this::newAuthBucket);
            if (!bucket.tryConsume(1)) {
                reject(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isAuthPath(String path) {
        for (String authPath : AUTH_PATHS) {
            if (path.startsWith(authPath)) return true;
        }
        return false;
    }

    private void reject(ServletResponse response) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write("{\"error\":\"Too many requests\"}");
    }

    private Bucket newWebhookBucket(String key) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(1000, Refill.intervally(1000, Duration.ofMinutes(1))))
                .build();
    }

    private Bucket newAuthBucket(String key) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(20, Refill.intervally(20, Duration.ofMinutes(1))))
                .build();
    }
}
