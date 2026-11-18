package dev.toansh.agent.evaluation;

import dev.toansh.agent.TelemetryTriageAgent.TriageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * LLM-as-Judge evaluation harness for offline quality assessment of the
 * triage agent. Each {@link TriageResult} is evaluated by Claude against a
 * human-curated {@link GoldStandardCase} and assigned numeric scores for
 * root-cause accuracy and action correctness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMJudgeEvaluator {

    private static final double PASS_THRESHOLD = 0.7;

    private static final String JUDGE_PROMPT_TEMPLATE = """
            You are an expert automotive diagnostics evaluator. Your task is to assess
            how accurately an AI triage agent diagnosed a vehicle issue.

            ## Gold Standard (Expected)
            - Root Cause   : %s
            - Recommended Action: %s
            - Escalation Required: %s

            ## Agent Output (Actual)
            - Root Cause   : %s
            - Recommended Action: %s
            - Escalation Required: %s
            - Confidence   : %.2f

            ## Scoring Instructions
            Score the agent's output on two dimensions (each 0.0–1.0):
            1. rootCauseScore  — semantic similarity of the root cause diagnosis
            2. actionScore     — quality and relevance of the recommended action

            Respond with ONLY valid JSON in this exact schema:
            {
              "rootCauseScore": <0.0–1.0>,
              "actionScore":    <0.0–1.0>,
              "reasoning":      "<one or two sentences explaining the scores>"
            }

            Do not include any text outside the JSON block.
            """;

    private final ChatClient chatClient;

    public EvaluationResult evaluate(TriageResult actual, GoldStandardCase expected) {
        log.info("Evaluating caseId={} vehicleId={}", expected.caseId(), expected.vehicleId());
        long startMs = System.currentTimeMillis();

        String prompt = JUDGE_PROMPT_TEMPLATE.formatted(
                expected.expectedRootCause(),
                expected.expectedAction(),
                expected.expectedEscalation(),
                actual.rootCause(),
                actual.recommendedAction(),
                actual.escalationRequired(),
                actual.confidence()
        );

        String judgeResponse;
        try {
            judgeResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
        } catch (Exception ex) {
            log.error("Judge LLM call failed for caseId={}: {}", expected.caseId(), ex.getMessage(), ex);
            judgeResponse = "{\"rootCauseScore\":0.0,\"actionScore\":0.0,\"reasoning\":\"Evaluation failed: " + ex.getMessage() + "\"}";
        }

        double rootCauseScore = extractDouble(judgeResponse, "rootCauseScore", 0.0);
        double actionScore    = extractDouble(judgeResponse, "actionScore",    0.0);
        String reasoning      = extractString(judgeResponse, "reasoning",      "No reasoning provided");
        boolean escalationOk  = actual.escalationRequired() == expected.expectedEscalation();
        boolean passed        = rootCauseScore >= PASS_THRESHOLD
                             && actionScore    >= PASS_THRESHOLD
                             && escalationOk;

        long processingTimeMs = System.currentTimeMillis() - startMs;

        return new EvaluationResult(
                expected.caseId(), passed,
                rootCauseScore, actionScore,
                escalationOk, reasoning, processingTimeMs
        );
    }

    public EvaluationSummary runFullEvaluation(List<GoldStandardCase> testCases,
                                               List<TriageResult> actuals) {
        if (testCases.size() != actuals.size()) {
            throw new IllegalArgumentException(
                    "testCases (%d) and actuals (%d) must have the same size"
                            .formatted(testCases.size(), actuals.size()));
        }

        log.info("Starting full evaluation run: {} cases", testCases.size());
        List<EvaluationResult> results = new ArrayList<>(testCases.size());

        for (int i = 0; i < testCases.size(); i++) {
            results.add(evaluate(actuals.get(i), testCases.get(i)));
        }

        long passedCases  = results.stream().filter(EvaluationResult::passed).count();
        double avgRootCause = average(results.stream().mapToDouble(EvaluationResult::rootCauseScore));
        double avgAction    = average(results.stream().mapToDouble(EvaluationResult::actionScore));
        double escalationAcc = results.stream()
                .filter(EvaluationResult::escalationCorrect)
                .count() / (double) results.size();

        return new EvaluationSummary(
                testCases.size(), (int) passedCases,
                avgRootCause, avgAction,
                escalationAcc, results
        );
    }

    public record GoldStandardCase(
            String caseId,
            String vehicleId,
            String description,
            String expectedRootCause,
            String expectedAction,
            boolean expectedEscalation
    ) {}

    public record EvaluationResult(
            String caseId,
            boolean passed,
            double rootCauseScore,
            double actionScore,
            boolean escalationCorrect,
            String judgeReasoning,
            long processingTimeMs
    ) {}

    public record EvaluationSummary(
            int totalCases,
            int passedCases,
            double avgRootCauseScore,
            double avgActionScore,
            double escalationAccuracy,
            List<EvaluationResult> results
    ) {}

    private double extractDouble(String json, String key, double fallback) {
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

    private String extractString(String json, String key, String fallback) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return fallback;
        int colon = json.indexOf(':', idx);
        int start = json.indexOf('"', colon + 1);
        int end   = json.indexOf('"', start + 1);
        return (start == -1 || end == -1) ? fallback : json.substring(start + 1, end);
    }

    private double average(java.util.stream.DoubleStream stream) {
        OptionalDouble opt = stream.average();
        return opt.isPresent() ? opt.getAsDouble() : 0.0;
    }
}
