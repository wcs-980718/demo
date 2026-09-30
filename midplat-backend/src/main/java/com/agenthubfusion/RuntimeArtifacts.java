package com.agenthubfusion;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Retain the actual executable by digest so a new server build cannot rewrite historical runtimes. */
@Component
public class RuntimeArtifacts {
    private final Path cache;
    private final String digest;
    private final String imageDigest;
    public RuntimeArtifacts(@Value("${fusion.runtime-directory:${java.io.tmpdir}/agenthub-fusion-runtime}") String root,
            @Value("${fusion.runtime-jar:}") String jar,
            @Value("${fusion.runtime-image-digest:local-jvm}") String imageDigest) {
        this.cache=Path.of(root).toAbsolutePath().resolve("_artifacts"); this.imageDigest=imageDigest;
        if(jar.isBlank()) { digest="development-classpath"; return; }
        try {
            Files.createDirectories(cache);Files.setPosixFilePermissions(cache,PosixFilePermissions.fromString("rwx------"));
            Path source=Path.of(jar);digest=hash(source);Path target=cache.resolve(digest+".jar");
            if(!Files.exists(target)) {
                Path staging=Files.createTempFile(cache,".runtime-",".jar");
                try { Files.copy(source,staging,StandardCopyOption.REPLACE_EXISTING);Files.setPosixFilePermissions(staging,PosixFilePermissions.fromString("r--------"));
                    try { Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE); }
                    catch(FileAlreadyExistsException ignored) { }
                } finally { Files.deleteIfExists(staging); }
            }
            if(!hash(target).equals(digest))throw new IllegalStateException("运行程序缓存摘要不一致");
        } catch(Exception ex) { throw new IllegalStateException("无法保存运行程序的不可变副本",ex); }
    }
    public String digest(){return digest;}
    public String imageDigest(){return imageDigest;}
    public String executable(JsonNode snapshot) {
        String selected=snapshot.path("runtimeArtifactDigest").asText(digest);
        String image=snapshot.path("runtimeImageDigest").asText(imageDigest);
        if(!image.equals(imageDigest))throw new FusionFault(503,"历史运行镜像与当前环境不匹配，请恢复对应镜像后执行");
        if(selected.equals("development-classpath")&&digest.equals(selected))return "";
        if(!selected.matches("[a-f0-9]{64}"))throw new FusionFault(503,"运行程序摘要无效");
        Path path=cache.resolve(selected+".jar");
        try { if(!Files.isRegularFile(path)||!hash(path).equals(selected))throw new Exception(); }
        catch(Exception ex) { throw new FusionFault(503,"历史运行程序缺失或损坏，禁止换用其他程序执行"); }
        return path.toString();
    }
    static String hash(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
