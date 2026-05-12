package com.team6.project3th.common.datasource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String method = request.getMethod();
        String uri = normalizeUri(request.getRequestURI());

        try {
            ApiRequestContext.set(method, uri);
            filterChain.doFilter(request, response);
        } finally {
            ApiRequestContext.clear();
        }
    }

    private String normalizeUri(String uri) {
        return uri.replaceAll("/\\d+", "/{id}");
    }
}