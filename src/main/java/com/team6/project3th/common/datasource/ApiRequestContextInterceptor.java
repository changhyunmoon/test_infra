package com.team6.project3th.common.datasource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;


import java.util.UUID;

@Component
public class ApiRequestContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        String requestId = UUID.randomUUID().toString();
        String testRunId = request.getHeader("X-Test-Run-Id");
        String scenario = request.getHeader("X-Scenario");

        String method = request.getMethod();
        String uri = getUriPattern(request);

        ApiRequestContext.set(method, uri);

        MDC.put("requestId", requestId);
        MDC.put("method", method);
        MDC.put("uri", uri);

        putIfNotBlank("testRunId", testRunId);
        putIfNotBlank("scenario", scenario);

        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        ApiRequestContext.clear();
        MDC.clear();
    }

    private String getUriPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
        );

        if (pattern != null) {
            return pattern.toString();
        }

        return request.getRequestURI();
    }

    private void putIfNotBlank(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}

