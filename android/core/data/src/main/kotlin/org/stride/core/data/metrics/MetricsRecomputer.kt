package org.stride.core.data.metrics

/** Recomputes per-activity metrics and the daily load series after data changes (MVP-012). */
fun interface MetricsRecomputer {
    /**
     * @param changedActivityIds activities that are new or changed (null = everything).
     * @param fromDate earliest ISO date whose daily load may have changed (null = full rebuild).
     */
    suspend fun recompute(changedActivityIds: Set<Long>?, fromDate: String?)
}
