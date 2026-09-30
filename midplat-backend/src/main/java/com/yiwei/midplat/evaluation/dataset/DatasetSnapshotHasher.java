package com.yiwei.midplat.evaluation.dataset;

import com.yiwei.midplat.common.domain.DomainAssertions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class DatasetSnapshotHasher {

    private DatasetSnapshotHasher() {}

    public static String hash(List<Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Entry entry : entries) {
                updateSegment(digest, entry.caseId());
                updateSegment(digest, entry.contentHash());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void updateSegment(MessageDigest digest, String value) {
        byte[] bytes = DomainAssertions.requireText(value, "哈希字段不能为空").getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public record Entry(String caseId, String contentHash) {}
}
