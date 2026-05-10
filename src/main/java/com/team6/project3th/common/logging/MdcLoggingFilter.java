package com.team6.project3th.common.logging;

import com.team6.project3th.common.metrics.DbMetricsRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("HTTP_DB_SUMMARY");

    private static final String TEST_RUN_ID_HEADER = "X-Test-Run-Id";
    private static final String SCENARIO_HEADER = "X-Scenario";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final DbMetricsRecorder dbMetricsRecorder;

    public MdcLoggingFilter(DbMetricsRecorder dbMetricsRecorder) {
        this.dbMetricsRecorder = dbMetricsRecorder;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startTime = System.currentTimeMillis();

        try {
            String testRunId = Optional.ofNullable(request.getHeader(TEST_RUN_ID_HEADER))
                    .filter(value -> !value.isBlank())
                    .orElse("-");

            String scenario = Optional.ofNullable(request.getHeader(SCENARIO_HEADER))
                    .filter(value -> !value.isBlank())
                    .orElse("-");

            String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_HEADER))
                    .filter(value -> !value.isBlank())
                    .orElse(UUID.randomUUID().toString());

            MDC.put("testRunId", testRunId);
            MDC.put("scenario", scenario);
            MDC.put("requestId", requestId);
            MDC.put("method", request.getMethod());
            MDC.put("uri", request.getRequestURI());

            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            long httpElapsedMs = System.currentTimeMillis() - startTime;
            DbQueryStatsContext.DbQueryStats stats = DbQueryStatsContext.snapshot();
            String method = MDC.get("method");
            String uri = MDC.get("uri");

            dbMetricsRecorder.recordRequestDbTime(method, uri, stats.getTotalElapsedMs());
            dbMetricsRecorder.recordRequestDbQueryCount(method, uri, stats.getQueryCount());

            log.info(
                    "event=http_request_db_summary status={} httpElapsedMs={} dbQueryCount={} dbTotalMs={}",
                    response.getStatus(),
                    httpElapsedMs,
                    stats.getQueryCount(),
                    stats.getTotalElapsedMs()
            );

            DbQueryStatsContext.clear();
            MDC.clear();
        }
    }
}
