package dev.toansh.rag;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid vector search service that combines pgvector HNSW cosine similarity
 * with PostgreSQL full-text search (BM25 approximation via {@code ts_rank})
 * to retrieve contextually relevant knowledge-base documents.
 *
 * <p>The final ranking score is a weighted linear combination:
 * <pre>
 *   final_score = vectorWeight * vector_similarity + (1 - vectorWeight) * bm25_score
 * </pre>
 */
@Slf4j
@Service
public class VectorStoreService {

    private static final String HYBRID_SEARCH_SQL = """
            WITH vector_scores AS (
                SELECT
                    id,
                    content,
                    metadata,
                    source,
                    1 - (embedding <=> ?::vector)          AS vector_similarity
                FROM knowledge_base
                ORDER BY embedding <=> ?::vector
                LIMIT ?
            ),
            fts_scores AS (
                SELECT
                    id,
                    ts_rank(
                        to_tsvector('english', content),
                        plainto_tsquery('english', ?)
                    )                                       AS bm25_score
                FROM knowledge_base
                WHERE to_tsvector('english', content)
                      @@ plainto_tsquery('english', ?)
            )
            SELECT
                v.id,
                v.content,
                v.metadata,
                v.source,
                v.vector_similarity,
                COALESCE(f.bm25_score, 0)                AS bm25_score,
                (? * v.vector_similarity +
                 (1 - ?) * COALESCE(f.bm25_score, 0))   AS final_score
            FROM vector_scores v
            LEFT JOIN fts_scores f USING (id)
            ORDER BY final_score DESC
            LIMIT ?;
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO knowledge_base (id, content, metadata, source, embedding)
            VALUES (gen_random_uuid(), ?, ?::jsonb, ?, ?::vector)
            ON CONFLICT DO NOTHING;
            """;

    private static final String CREATE_HNSW_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS knowledge_base_embedding_hnsw_idx
                ON knowledge_base
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64);
            """;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    @Value("${app.rag.vector-weight:0.7}")
    private double defaultVectorWeight;

    @Value("${app.rag.top-k:5}")
    private int defaultTopK;

    public VectorStoreService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate   = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void ensureHnswIndex() {
        log.info("Ensuring HNSW index on knowledge_base.embedding");
        try {
            jdbcTemplate.execute(CREATE_HNSW_INDEX_SQL);
            log.info("HNSW index ready (m=16, ef_construction=64, cosine)");
        } catch (Exception ex) {
            log.warn("Could not create HNSW index (non-fatal): {}", ex.getMessage());
        }
    }

    public List<SearchResult> hybridSearch(String query, int topK, double vectorWeight) {
        log.info("Hybrid search: query='{}' topK={} vectorWeight={}", query, topK, vectorWeight);

        float[] queryEmbedding = embeddingModel.embed(query);
        String  pgVectorLiteral = toPgVectorLiteral(queryEmbedding);

        List<SearchResult> results = new ArrayList<>();

        try {
            var rows = jdbcTemplate.queryForList(
                    HYBRID_SEARCH_SQL,
                    pgVectorLiteral,
                    pgVectorLiteral,
                    topK * 2,
                    query,
                    query,
                    vectorWeight,
                    vectorWeight,
                    topK
            );

            for (var row : rows) {
                var metadata = parseMetadata(row.get("metadata"));
                results.add(new SearchResult(
                        (String) row.get("content"),
                        toDouble(row.get("final_score")),
                        metadata,
                        (String) row.get("source")
                ));
            }
        } catch (Exception ex) {
            log.error("Hybrid search failed for query='{}': {}", query, ex.getMessage(), ex);
        }

        return results;
    }

    public List<SearchResult> hybridSearch(String query) {
        return hybridSearch(query, defaultTopK, defaultVectorWeight);
    }

    public void addDocuments(List<Document> documents) {
        log.info("Indexing {} documents into knowledge_base", documents.size());
        int indexed = 0;
        for (Document doc : documents) {
            try {
                float[] embedding = embeddingModel.embed(doc);
                String metadataJson = toJsonb(doc.getMetadata());
                String source = (String) doc.getMetadata().getOrDefault("source", "unknown");

                jdbcTemplate.update(UPSERT_SQL,
                        doc.getText(),
                        metadataJson,
                        source,
                        toPgVectorLiteral(embedding)
                );
                indexed++;
            } catch (Exception ex) {
                log.error("Failed to index document id={}: {}", doc.getId(), ex.getMessage(), ex);
            }
        }
        log.info("Successfully indexed {}/{} documents", indexed, documents.size());
    }

    public record SearchResult(
            String content,
            double score,
            Map<String, Object> metadata,
            String source
    ) {}

    private String toPgVectorLiteral(float[] embedding) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(Object raw) {
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private String toJsonb(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : metadata.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(entry.getKey()).append("\":\"")
              .append(entry.getValue()).append('"');
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }
}
