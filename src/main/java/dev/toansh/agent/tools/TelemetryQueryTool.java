package dev.toansh.agent.tools;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP-style tool that surfaces pre-aggregated telemetry data from PostgreSQL
 * to the LLM orchestration layer.
 *
 * <p>All queries operate on the {@code telemetry_events} table using a
 * sliding time window so that the agent always works with fresh, bounded data.
 */
@Slf4j
@Component
@Validated
@RequiredArgsConstructor
public class TelemetryQueryTool {

    private static final String AGGREGATION_SQL = """
            WITH window_events AS (
                SELECT
                    vehicle_id,
                    metric_type,
                    value,
                    threshold,
                    severity,
                    recorded_at,
                    CASE WHEN value > threshold THEN 1 ELSE 0 END AS is_anomaly
                FROM telemetry_events
                WHERE vehicle_id    = ?
                  AND recorded_at  >= NOW() - (? * INTERVAL '1 minute')
                  AND (COALESCE(array_length(?::varchar[], 1), 0) = 0 OR metric_type = ANY(?::varchar[]))
            ),
            aggregated AS (
                SELECT
                    metric_type,
                    COUNT(*)                    AS event_count,
                    AVG(value)                  AS avg_value,
                    MAX(value)                  AS max_value,
                    SUM(is_anomaly)             AS anomaly_count
                FROM window_events
                GROUP BY metric_type
            ),
            top_anomalies AS (
                SELECT
                    metric_type,
                    value,
                    threshold,
                    severity,
                    recorded_at
                FROM window_events
                WHERE is_anomaly = 1
                ORDER BY value - threshold DESC
                LIMIT 10
            )
            SELECT
                a.metric_type,
                a.event_count,
                a.avg_value,
                a.max_value,
                a.anomaly_count,
                ta.value         AS anomaly_value,
                ta.threshold     AS anomaly_threshold,
                ta.severity      AS anomaly_severity,
                ta.recorded_at   AS anomaly_ts
            FROM aggregated a
            LEFT JOIN top_anomalies ta USING (metric_type)
            ORDER BY a.anomaly_count DESC, a.metric_type;
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns a pre-aggregated telemetry summary for the requested vehicle and
     * time window, optionally filtered to specific metric types.
     *
     * @param request validated query parameters
     * @return aggregated summary including anomaly records
     */
    public TelemetrySummary queryTelemetrySummary(@Valid TelemetryQueryRequest request) {
        log.info("Querying telemetry: vehicleId={} windowMinutes={} metrics={}",
                request.vehicleId(), request.windowMinutes(), request.metricTypes());

        String[] metricArray = request.metricTypes() == null || request.metricTypes().isEmpty()
                ? new String[0]
                : request.metricTypes().toArray(String[]::new);

        Instant windowStart = Instant.now().minusSeconds(request.windowMinutes() * 60L);
        Instant windowEnd   = Instant.now();

        long totalEvents       = 0;
        int  totalAnomalies    = 0;
        Map<String, Double> avgValues = new LinkedHashMap<>();
        List<AnomalyRecord>  anomalies = new ArrayList<>();

        try {
            var rows = jdbcTemplate.queryForList(
                    AGGREGATION_SQL,
                    request.vehicleId(),
                    request.windowMinutes(),
                    metricArray,
                    metricArray
            );

            for (var row : rows) {
                String metricType  = (String) row.get("metric_type");
                long   eventCount  = toLong(row.get("event_count"));
                double avgValue    = toDouble(row.get("avg_value"));
                long   anomalyCount = toLong(row.get("anomaly_count"));

                totalEvents    += eventCount;
                totalAnomalies += (int) anomalyCount;
                avgValues.put(metricType, avgValue);

                Object anomalyVal = row.get("anomaly_value");
                if (anomalyVal != null) {
                    anomalies.add(new AnomalyRecord(
                            metricType,
                            toDouble(anomalyVal),
                            toDouble(row.get("anomaly_threshold")),
                            (String) row.get("anomaly_severity"),
                            toInstant(row.get("anomaly_ts"))
                    ));
                }
            }
        } catch (Exception ex) {
            log.error("Telemetry query failed for vehicleId={}: {}", request.vehicleId(), ex.getMessage(), ex);
        }

        log.debug("Telemetry summary: events={} anomalies={}", totalEvents, totalAnomalies);
        return new TelemetrySummary(
                request.vehicleId(),
                windowStart,
                windowEnd,
                totalEvents,
                avgValues,
                totalAnomalies,
                anomalies
        );
    }

    public record TelemetryQueryRequest(
            @NotBlank String vehicleId,
            @Min(1) @Max(1440) int windowMinutes,
            List<String> metricTypes
    ) {}

    public record TelemetrySummary(
            String vehicleId,
            Instant windowStart,
            Instant windowEnd,
            long eventCount,
            Map<String, Double> avgValue,
            int anomalyCount,
            List<AnomalyRecord> topAnomalies
    ) {}

    public record AnomalyRecord(
            String metricType,
            double value,
            double threshold,
            String severity,
            Instant timestamp
    ) {}

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private Instant toInstant(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof Instant i)            return i;
        return Instant.EPOCH;
    }
}
