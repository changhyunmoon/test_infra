package com.team6.project3th.common.datasource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class QueryMetricsListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(QueryMetricsListener.class);

    private static final long SLOW_QUERY_THRESHOLD_MS = 300;

    private final MeterRegistry meterRegistry;

    public QueryMetricsListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        String uri = ApiRequestContext.getUri();
        String method = ApiRequestContext.getMethod();

        long elapsedTimeMs = execInfo.getElapsedTime();

        meterRegistry.counter(
                "db.query.count",
                "uri", uri,
                "method", method
        ).increment(queryInfoList.size());

        Timer.builder("db.query.time")
                .description("DB query execution time")
                .tag("uri", uri)
                .tag("method", method)
                .register(meterRegistry)
                .record(elapsedTimeMs, TimeUnit.MILLISECONDS);

        for (QueryInfo queryInfo : queryInfoList) {
            log.info(
                    "event=db_query method={}, uri={}, elapsedMs={}, query={}",
                    method,
                    uri,
                    elapsedTimeMs,
                    queryInfo.getQuery()
            );
        }

        if (elapsedTimeMs >= SLOW_QUERY_THRESHOLD_MS) {
            for (QueryInfo queryInfo : queryInfoList) {
                log.warn(
                        "[SLOW QUERY] method={}, uri={}, elapsedMs={}, query={}",
                        method,
                        uri,
                        elapsedTimeMs,
                        queryInfo.getQuery()
                );
            }
        }
    }
}
