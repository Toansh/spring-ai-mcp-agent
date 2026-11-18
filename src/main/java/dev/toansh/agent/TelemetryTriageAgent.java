package dev.toansh.agent;

import dev.toansh.agent.tools.TelemetryQueryTool;
import dev.toansh.agent.tools.TelemetryQueryTool.TelemetrySummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct (Reason + Act) orchestration agent for vehicle telemetry triage.
 *
 * <p>Implements the Reason-Act-Observe loop using Spring AI's {@link ChatClient}
 * and tool-calling capabilities backed by Anthropic Claude. Each iteration
 * allows the LLM to reason over tool observations before committing to a final
 * triage verdict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryTriageAgent {

    private static final int MAX_ITERATIONS = 5;

    private static final String SYSTEM_PROMPT = """
            You are an AI-powered vehicle telemetry triage agent responsible for diagnosing
            anomalies in real-time sensor data streams. Your goal is to identify root causes
            of vehicle issues and recommend actionable remediation steps.

            ## Available Tools
            - queryTelemetrySummary: Retrieve aggregated telemetry metrics and anomaly records
              for a given vehicle and time window. Use this to gather observational evidence
              before forming a diagnosis.

            ## ReAct Protocol
            You MUST follow this loop:
              Thought: <your internal reasoning about what to do next>
              Action: <tool call with arguments>
              Observation: <tool result — provided by the system>
              ... (repeat up to 5 times)
              Final Answer: <JSON block conforming to the schema below>

            ## Output Schema (Final Answer only)
            {
              "rootCause":          "<concise root-cause description>",
              "confidence":         <0.0–1.0>,
              "recommendedAction":  "<actionable remediation step>",
              "escalationRequired": <true|false>
            }

            ## Escalation Rules
            - escalationRequired = true when: confidence < 0.6 OR anomalyCount > 10 OR
              any anomaly has severity == "CRITICAL".
            - Never escalate for LOW-severity events with confidence > 0.85.

            Be precise, concise, and evidence-based. Do not hallucinate sensor readings.
            """;

    private final ChatClient chatClient;
    private final TelemetryQueryTool telemetryQueryTool;

    /**
     * Executes a full ReAct triage loop for the supplied {@link TriageRequest}.
     *
     * @param request the triage request containing vehicle context
     * @return a completed {@link TriageResult} with root-cause analysis
     * @throws TriageException if the agent fails to converge within the iteration limit
     */
    public TriageResult triage(TriageRequest request) {
        log.info("Starting triage for vehicleId={} severity={} window={}",
                request.vehicleId(), request.severity(), request.eventWindow());

        long startMs = System.currentTimeMillis();
        List<Message> conversationHistory = new ArrayList<>();
        conversationHistory.add(new SystemMessage(SYSTEM_PROMPT));
        conversationHistory.add(new UserMessage(buildUserPrompt(request)));

        int iteration = 0;
        String finalJsonPayload = null;

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("[ReAct iter={}] Sending {} messages to LLM", iteration, conversationHistory.size());

            ChatResponse response = chatClient.prompt()
                    .messages(conversationHistory)
                    .tools(this)   // registers @Tool methods on this bean
                    .call()
                    .chatResponse();

            String assistantText = response.getResult().getOutput().getText();
            log.info("[ReAct iter={}] LLM response:\n{}", iteration, assistantText);

            conversationHistory.add(new AssistantMessage(assistantText));

            if (assistantText != null && assistantText.contains("Final Answer:")) {
                finalJsonPayload = extractFinalAnswer(assistantText);
                log.info("[ReAct iter={}] Convergence reached. Final payload: {}", iteration, finalJsonPayload);
                break;
            }

            if (response.getResult().getOutput().getToolCalls() == null ||
                response.getResult().getOutput().getToolCalls().isEmpty()) {
                log.warn("[ReAct iter={}] No tool call and no Final Answer — nudging LLM", iteration);
                conversationHistory.add(new UserMessage(
                        "Continue your reasoning. Either call a tool or provide the Final Answer."));
            }
        }

        if (finalJsonPayload == null) {
            log.error("Agent did not converge within {} iterations for vehicleId={}", MAX_ITERATIONS, request.vehicleId());
            throw new TriageException("Agent failed to converge within " + MAX_ITERATIONS + " iterations");
        }

        long processingTimeMs = System.currentTimeMillis() - startMs;
        return parseTriageResult(request.vehicleId(), finalJsonPayload, processingTimeMs, iteration);
    }

    /**
     * Tool: query telemetry summary for a vehicle within a time window.
     */
    @Tool(description = """
            Query aggregated telemetry metrics and anomaly records for a vehicle.
            Returns event counts, averages per metric type, and top anomalies within the window.
            """)
    public String queryTelemetrySummary(String vehicleId, int windowMinutes, String metricTypes) {
        log.info("[Tool] queryTelemetrySummary vehicleId={} windowMinutes={} metrics={}",
                vehicleId, windowMinutes, metricTypes);

        var queryRequest = new TelemetryQueryTool.TelemetryQueryRequest(
                vehicleId,
                windowMinutes,
                metricTypes == null || metricTypes.isBlank() ? List.of() : List.of(metricTypes.split(","))
        );

        TelemetrySummary summary = telemetryQueryTool.queryTelemetrySummary(queryRequest);
        return summary.toString();
    }

    private String buildUserPrompt(TriageRequest request) {
        int windowMinutes = (int) request.eventWindow().toMinutes();
        return """
                ## Triage Request
                - Vehicle ID      : %s
                - Event Window    : %d minutes
                - Declared Severity: %s

                Begin your ReAct loop. Query telemetry data, reason about anomalies,
                and return a Final Answer JSON once confident.
                """.formatted(request.vehicleId(), windowMinutes, request.severity());
    }

    private String extractFinalAnswer(String llmText) {
        int idx = llmText.indexOf("Final Answer:");
        if (idx == -1) return "{}";
        String afterLabel = llmText.substring(idx + "Final Answer:".length()).strip();
        int jsonStart = afterLabel.indexOf('{');
        int jsonEnd = afterLabel.lastIndexOf('}');
        if (jsonStart == -1 || jsonEnd == -1) return "{}";
        return afterLabel.substring(jsonStart, jsonEnd + 1);
    }

    private TriageResult parseTriageResult(String vehicleId, String json,
                                           long processingTimeMs, int iterations) {
        String rootCause         = extractJsonString(json, "rootCause", "Unknown");
        double confidence        = extractJsonDouble(json, "confidence", 0.5);
        String recommendedAction = extractJsonString(json, "recommendedAction", "Manual inspection required");
        boolean escalation       = extractJsonBool(json, "escalationRequired", true);

        return new TriageResult(
                vehicleId, rootCause, confidence, recommendedAction,
                escalation, processingTimeMs, iterations);
    }

    private String extractJsonString(String json, String key, String fallback) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return fallback;
        int colon = json.indexOf(':', idx);
        int start = json.indexOf('"', colon + 1);
        int end   = json.indexOf('"', start + 1);
        return (start == -1 || end == -1) ? fallback : json.substring(start + 1, end);
    }

    private double extractJsonDouble(String json, String key, double fallback) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return fallback;
        int colon = json.indexOf(':', idx);
        String remainder = json.substring(colon + 1).stripLeading();
        int end = 0;
        while (end < remainder.length() &&
               (Character.isDigit(remainder.charAt(end)) || remainder.charAt(end) == '.')) {
            end++;
        }
        try {
            return end > 0 ? Double.parseDouble(remainder.substring(0, end)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean extractJsonBool(String json, String key, boolean fallback) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return fallback;
        int colon = json.indexOf(':', idx);
        String remainder = json.substring(colon + 1).stripLeading();
        if (remainder.startsWith("true"))  return true;
        if (remainder.startsWith("false")) return false;
        return fallback;
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public record TriageRequest(
            String vehicleId,
            Duration eventWindow,
            Severity severity
    ) {}

    public record TriageResult(
            String vehicleId,
            String rootCause,
            double confidence,
            String recommendedAction,
            boolean escalationRequired,
            long processingTimeMs,
            int llmIterations
    ) {}

    public static final class TriageException extends RuntimeException {
        public TriageException(String message) { super(message); }
    }
}
