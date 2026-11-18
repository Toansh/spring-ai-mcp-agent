-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- telemetry_events: raw vehicle telemetry ingested by the agent
CREATE TABLE IF NOT EXISTS telemetry_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id   VARCHAR(64)  NOT NULL,
    metric_type  VARCHAR(64)  NOT NULL,
    value        DOUBLE PRECISION NOT NULL,
    threshold    DOUBLE PRECISION NOT NULL,
    severity     VARCHAR(16)  NOT NULL,
    recorded_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw_payload  JSONB
);

CREATE INDEX IF NOT EXISTS idx_telemetry_vehicle_time
    ON telemetry_events (vehicle_id, recorded_at DESC);

-- knowledge_base: RAG document store with hybrid search support
CREATE TABLE IF NOT EXISTS knowledge_base (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content       TEXT         NOT NULL,
    embedding     vector(1536),
    metadata      JSONB,
    source        VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS
                      (to_tsvector('english', content)) STORED
);

-- HNSW index for fast approximate nearest-neighbour search
CREATE INDEX IF NOT EXISTS idx_knowledge_hnsw
    ON knowledge_base
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- GIN index for full-text search on search_vector
CREATE INDEX IF NOT EXISTS idx_knowledge_fts
    ON knowledge_base USING gin (search_vector);

-- Sample telemetry data for testing
INSERT INTO telemetry_events (vehicle_id, metric_type, value, threshold, severity, recorded_at, raw_payload)
VALUES
    ('VH-001', 'battery_voltage',  11.8, 12.0, 'WARNING',  now() - interval '5 minutes',
     '{"cell_count": 6, "chemistry": "lead_acid"}'::jsonb),

    ('VH-002', 'engine_temp_c',   103.0, 95.0, 'CRITICAL', now() - interval '2 minutes',
     '{"coolant_level": "low", "fan_status": "off"}'::jsonb),

    ('VH-003', 'tire_pressure_psi', 28.0, 32.0, 'INFO',    now() - interval '10 minutes',
     '{"position": "front_left", "ambient_temp_c": 22}'::jsonb);

-- Sample knowledge-base entries
INSERT INTO knowledge_base (content, embedding, metadata, source)
VALUES
    (
        'Battery voltage below 12 V on a 12 V lead-acid system indicates undercharge or cell degradation. Recommend load test and charging cycle.',
        ('[' || repeat('0,', 1535) || '0]')::vector(1536),
        '{"category": "battery", "severity_range": ["WARNING", "CRITICAL"]}'::jsonb,
        'maintenance_manual_v3.pdf'
    ),
    (
        'Engine coolant temperature exceeding 100 °C requires immediate attention. Check coolant level, thermostat operation, and radiator fan.',
        ('[' || repeat('0,', 1535) || '0]')::vector(1536),
        '{"category": "engine", "severity_range": ["CRITICAL"]}'::jsonb,
        'obd_fault_guide_2024.pdf'
    );