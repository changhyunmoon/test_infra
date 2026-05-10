package com.team6.project3th.common.logging;

import com.team6.project3th.common.metrics.DbMetricsRecorder;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SqlLoggingListener implements QueryExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("SQL_LOG");

    private final DbMetricsRecorder dbMetricsRecorder;

    public SqlLoggingListener(DbMetricsRecorder dbMetricsRecorder) {
        this.dbMetricsRecorder = dbMetricsRecorder;
    }

    @Override
    public void beforeQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
    }

    @Override
    public void afterQuery(ExecutionInfo executionInfo, List<QueryInfo> queryInfoList) {
        long elapsedMs = executionInfo.getElapsedTime();

        DbQueryStatsContext.addQuery(elapsedMs);

        String method = MDC.get("method");
        String uri = MDC.get("uri");

        dbMetricsRecorder.recordQueryDuration(
                method,
                uri,
                elapsedMs,
                executionInfo.isSuccess()
        );

        for (QueryInfo queryInfo : queryInfoList) {
            log.info(
                    "event=db_query datasource={} elapsedMs={} success={} querySize={} sql=\"{}\"",
                    executionInfo.getDataSourceName(),
                    elapsedMs,
                    executionInfo.isSuccess(),
                    queryInfoList.size(),
                    normalizeSql(queryInfo.getQuery())
            );
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }

        return sql.replaceAll("\\s+", " ").trim();
    }
}
