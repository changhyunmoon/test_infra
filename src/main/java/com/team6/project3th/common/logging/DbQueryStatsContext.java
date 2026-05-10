package com.team6.project3th.common.logging;

public final class DbQueryStatsContext {

    private static final ThreadLocal<DbQueryStats> CONTEXT =
            ThreadLocal.withInitial(DbQueryStats::new);

    private DbQueryStatsContext() {
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static void addQuery(long elapsedMs) {
        DbQueryStats stats = CONTEXT.get();
        stats.queryCount++;
        stats.totalElapsedMs += elapsedMs;
    }

    public static DbQueryStats snapshot() {
        DbQueryStats stats = CONTEXT.get();
        return new DbQueryStats(stats.queryCount, stats.totalElapsedMs);
    }

    public static class DbQueryStats {
        private int queryCount;
        private long totalElapsedMs;

        public DbQueryStats() {
        }

        public DbQueryStats(int queryCount, long totalElapsedMs) {
            this.queryCount = queryCount;
            this.totalElapsedMs = totalElapsedMs;
        }

        public int getQueryCount() {
            return queryCount;
        }

        public long getTotalElapsedMs() {
            return totalElapsedMs;
        }
    }
}
