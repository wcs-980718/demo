package com.yiwei.midplat.evaluation.casecenter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EvaluationCaseHasher {

    private EvaluationCaseHasher() {}

    static String hash(
            String platformId,
            String name,
            String category,
            CaseSeverity severity,
            CaseSourceType sourceType,
            String sourceRef,
            String inputText,
            String expectedJson,
            EvaluatorType evaluatorType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, platformId);
            updateField(digest, name);
            updateField(digest, category);
            updateField(digest, severity.name());
            updateField(digest, sourceType.name());
            updateField(digest, sourceRef);
            updateField(digest, inputText);
            updateField(digest, expectedJson);
            updateField(digest, evaluatorType.name());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = normalize(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").trim();
    }
}
