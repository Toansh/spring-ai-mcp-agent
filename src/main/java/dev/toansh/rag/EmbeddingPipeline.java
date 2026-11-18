package dev.toansh.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Document ingestion and embedding pipeline for the RAG knowledge base.
 *
 * <p>Applies a fixed-size sliding-window chunking strategy (512 tokens /
 * ~2048 characters, 50-token / ~200-character overlap) before computing
 * embeddings via Spring AI's {@link EmbeddingModel} and delegating
 * persistence to {@link VectorStoreService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingPipeline {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int CHUNK_SIZE_CHARS = 512 * CHARS_PER_TOKEN;   // ~2048
    private static final int OVERLAP_CHARS    =  50 * CHARS_PER_TOKEN;   //  ~200

    private final EmbeddingModel    embeddingModel;
    private final VectorStoreService vectorStoreService;

    public IngestResult ingestDocument(String content, Map<String, Object> metadata) {
        log.info("Ingesting document: source={} length={}",
                metadata.getOrDefault("source", "unknown"), content.length());

        long embeddingStart = System.currentTimeMillis();
        List<String> chunks = chunk(content);

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var chunkMeta = new HashMap<>(metadata);
            chunkMeta.put("chunkIndex", i);
            chunkMeta.put("totalChunks", chunks.size());
            documents.add(new Document(chunks.get(i), chunkMeta));
        }

        vectorStoreService.addDocuments(documents);

        long embeddingTimeMs = System.currentTimeMillis() - embeddingStart;
        log.info("Ingestion complete: chunks={} embeddingTimeMs={}", chunks.size(), embeddingTimeMs);

        return new IngestResult(1, chunks.size(), embeddingTimeMs);
    }

    public IngestResult ingestFromFile(Path filePath) throws IOException {
        log.info("Reading file for ingestion: {}", filePath);
        String content = Files.readString(filePath);
        Map<String, Object> metadata = Map.of(
                "source",   filePath.getFileName().toString(),
                "fullPath", filePath.toAbsolutePath().toString(),
                "ingestedAt", java.time.Instant.now().toString()
        );
        return ingestDocument(content, metadata);
    }

    public record IngestResult(
            int documentsProcessed,
            int chunksCreated,
            long embeddingTimeMs
    ) {}

    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, length);

            if (end < length) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > start) {
                    end = boundary;
                }
            }

            chunks.add(text.substring(start, end).strip());
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }

        return chunks;
    }
}
