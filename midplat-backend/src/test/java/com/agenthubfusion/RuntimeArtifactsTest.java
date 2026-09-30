package com.agenthubfusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeArtifactsTest {
    @TempDir Path directory;
    @Test void oldReleaseKeepsItsBinaryAfterServerUpgradeAndCorruptionIsRejected() throws Exception {
        Path source=directory.resolve("server.jar");Files.writeString(source,"first-runtime-binary");
        var first=new RuntimeArtifacts(directory.toString(),source.toString(),"sha256:image-one");
        var snapshot=new ObjectMapper().createObjectNode().put("runtimeArtifactDigest",first.digest()).put("runtimeImageDigest",first.imageDigest());
        Files.writeString(source,"second-runtime-binary");var second=new RuntimeArtifacts(directory.toString(),source.toString(),"sha256:image-one");
        assertNotEquals(first.digest(),second.digest());assertEquals("first-runtime-binary",Files.readString(Path.of(second.executable(snapshot))));
        assertThrows(FusionFault.class,()->second.executable(snapshot.deepCopy().put("runtimeImageDigest","sha256:wrong-image")));
        Path retained=Path.of(second.executable(snapshot));retained.toFile().setWritable(true);Files.writeString(retained,"corrupted");
        assertThrows(FusionFault.class,()->second.executable(snapshot));
    }
}
