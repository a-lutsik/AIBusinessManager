package com.aibusinessmanager.app.web;

import com.aibusinessmanager.platform.error.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/public/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String ip = OptionalIp.of(request);
        try {
            if (path.contains("/slots")) {
                check(ip + ":slots", 5, 60_000);
            }
            if ("POST".equals(request.getMethod()) && path.contains("/bookings")) {
                check(ip + ":book", 20, 3_600_000);
            }
        } catch (DomainException ex) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"RATE_LIMITED\",\"status\":429,\"code\":\"RATE_LIMITED\",\"detail\":\""
                    + ex.getMessage() + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void check(String key, int max, long windowMs) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.removeFirst();
            }
            if (q.size() >= max) {
                throw DomainException.tooManyRequests("Too many booking requests, try later");
            }
            q.addLast(now);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        long now = Instant.now().toEpochMilli();
        hits.entrySet().removeIf(e -> {
            Deque<Long> q = e.getValue();
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() > 3_600_000) {
                    q.removeFirst();
                }
                return q.isEmpty();
            }
        });
    }

    private static final class OptionalIp {
        static String of(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
    }
}
