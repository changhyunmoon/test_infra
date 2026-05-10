package com.team6.project3th.common.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class DbMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public DbMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordQueryDuration(String method, String uri, long elapsedMs, boolean success) {
        Timer.builder("app_db_query_duration")
                .description("DB query execution duration")
                .tag("method", normalize(method))
                .tag("uri", normalize(uri))
                .tag("success", String.valueOf(success))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    public void recordRequestDbTime(String method, String uri, long dbTotalMs) {
        Timer.builder("app_http_request_db_time")
                .description("Total DB time per HTTP request")
                .tag("method", normalize(method))
                .tag("uri", normalize(uri))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(dbTotalMs, TimeUnit.MILLISECONDS);
    }

    public void recordRequestDbQueryCount(String method, String uri, int queryCount) {
        DistributionSummary.builder("app_http_request_db_query_count")
                .description("DB query count per HTTP request")
                .tag("method", normalize(method))
                .tag("uri", normalize(uri))
                .baseUnit("queries")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(queryCount);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }
}
