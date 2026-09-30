package com.yiwei.midplat.evaluation.run;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Internal body-free fingerprint used to reject a stale gate decision snapshot. */
public final class EvaluationRunDecisionStateFingerprint {

    private static final Comparator<EvaluationRunComparisonReader.ResultComparisonSnapshot> RESULT_ORDER =
            Comparator.comparingInt(EvaluationRunComparisonReader.ResultComparisonSnapshot::orderNo)
                    .thenComparing(EvaluationRunComparisonReader.ResultComparisonSnapshot::resultId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    private EvaluationRunDecisionStateFingerprint() {}

    public static String from(EvaluationRunComparisonReader.RunComparisonSnapshot snapshot) {
        if (snapshot == null || snapshot.results() == null) {
            throw new IllegalArgumentException("候选决策状态无效");
        }
        Encoder encoder = new Encoder();
        encoder.text("candidate-decision-state-v1");
        encoder.text(snapshot.runId());
        encoder.text(snapshot.status() == null ? null : snapshot.status().name());
        encoder.text(snapshot.platformId());
        encoder.number(snapshot.version());
        encoder.text(snapshot.datasetSnapshotHash());
        encoder.bool(snapshot.qualityAvailable());
        encoder.decimal(snapshot.qualityRate());
        encoder.decimal(snapshot.totalCost());
        encoder.number(snapshot.averageLatencyMs());
        encoder.number(snapshot.p95LatencyMs());
        encoder.number(snapshot.results().size());
        List<EvaluationRunComparisonReader.ResultComparisonSnapshot> results = snapshot.results().stream()
                .peek(value -> {
                    if (value == null) throw new IllegalArgumentException("候选决策状态无效");
                })
                .sorted(RESULT_ORDER).toList();
        for (var result : results) {
            encoder.number(result.orderNo());
            encoder.text(result.resultId());
            encoder.text(result.caseId());
            encoder.normalizedName(result.name());
            encoder.text(result.snapshotCategory());
            encoder.text(result.severity());
            encoder.text(result.status() == null ? null : result.status().name());
            encoder.bool(result.reviewRequired());
            encoder.number(result.version());
            encoder.decimal(result.score());
            encoder.text(result.snapshotContentHash());
        }
        return encoder.finish();
    }

    private static final class Encoder {
        private final MessageDigest digest;

        private Encoder() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("无法初始化候选决策状态指纹", exception);
            }
        }

        private void text(String value) {
            if (value == null) {
                length(-1);
                return;
            }
            byte[] bytes = Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
            length(bytes.length);
            digest.update(bytes);
        }

        private void normalizedName(String value) {
            text(value == null ? null : value.strip());
        }

        private void decimal(BigDecimal value) {
            text(value == null ? null : value.stripTrailingZeros().toPlainString());
        }

        private void bool(boolean value) {
            text(value ? "1" : "0");
        }

        private void number(long value) {
            text(Long.toString(value));
        }

        private void length(int length) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
