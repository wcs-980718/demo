package com.agenthubfusion;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One independent JVM and read-only package for each deployment/release; no shared mutable profile. */
@Component
public class RuntimeUnits {
    @org.springframework.beans.factory.annotation.Autowired private RuntimeArtifacts artifacts;
    public record Unit(String releaseId,String hash,String url,String token,Process process) {}
    private final ObjectMapper json;
    private final Path root;
    private final String gateway, jar;
    @Value("${fusion.self-url:http://127.0.0.1:8742}") private String selfUrl;
    private final int maxUnits;
    private final ConcurrentMap<String,Unit> units=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Long> preparingUntil=new ConcurrentHashMap<>();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public RuntimeUnits(ObjectMapper json,@Value("${fusion.runtime-directory:${java.io.tmpdir}/agenthub-fusion-runtime}") String directory,
            @Value("${fusion.midplat-url}") String gateway,@Value("${fusion.runtime-jar:}") String jar,
            @Value("${fusion.max-runtime-units:24}") int maxUnits){
        this.json=json;this.root=Path.of(directory).toAbsolutePath().normalize();this.gateway=gateway;this.jar=jar;this.maxUnits=maxUnits;
    }
    public synchronized Unit prepare(String release,String hash,JsonNode snapshot){
        DeploymentService.identifier(release);
        preparingUntil.put(release,System.nanoTime()+Duration.ofSeconds(60).toNanos());
        Unit existing=units.get(release);
        if(existing!=null&&existing.process().isAlive()){
            if(!existing.hash().equals(hash))throw new FusionFault(500,"运行版本摘要不匹配");
            check(existing);return existing;
        }
        units.entrySet().removeIf(entry->!entry.getValue().process().isAlive());
        if(units.size()>=maxUnits)throw new FusionFault(503,"运行单元达到容量限制，请结束旧会话后回收运行单元");
        Process process=null;
        try{
            Path dir=root.resolve(release);Files.createDirectories(dir);Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));
            Path content=dir.resolve("release.json");String body=json.writeValueAsString(snapshot);
            if(!EffectiveConfigCompiler.hash(body).equals(hash))throw new FusionFault(500,"发布包摘要校验失败");
            if(Files.exists(content)){if(!EffectiveConfigCompiler.hash(Files.readString(content)).equals(hash))throw new FusionFault(500,"磁盘发布包已损坏");}
            else {Files.writeString(content,body,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(content,PosixFilePermissions.fromString("r--------"));}
            String token=UUID.randomUUID()+"-"+UUID.randomUUID();
            List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xms24m","-Xmx192m","-XX:MaxDirectMemorySize=32m","-XX:ActiveProcessorCount=2"));
            String executable=artifacts==null?jar:artifacts.executable(snapshot);
            if(!executable.isBlank()) command.addAll(List.of("-Dloader.main=com.agenthubfusion.runtime.RuntimeMain","-cp",executable,"org.springframework.boot.loader.launch.PropertiesLauncher"));
            else command.addAll(List.of("-cp",System.getProperty("java.class.path"),"com.agenthubfusion.runtime.RuntimeMain"));
            ProcessBuilder builder=new ProcessBuilder(command).directory(dir.toFile());
            // Child receives neither database passwords nor signing/provider keys from the parent environment.
            builder.environment().clear();
            builder.environment().putAll(Map.of("FUSION_RUNTIME_RELEASE",release,"FUSION_RUNTIME_HASH",hash,"FUSION_RUNTIME_PACKAGE",content.toString(),"FUSION_RUNTIME_TOKEN",token,"FUSION_RUNTIME_GATEWAY",gateway,"FUSION_RUNTIME_AGENT",selfUrl));
            builder.redirectError(dir.resolve("runtime.log").toFile());
            process=builder.start();Process started=process;
            ExecutorService reader=Executors.newSingleThreadExecutor();String line;
            try{line=reader.submit(()->new BufferedReader(new InputStreamReader(started.getInputStream())).readLine()).get(20,TimeUnit.SECONDS);}finally{reader.shutdownNow();}
            if(line==null||!line.matches("READY [0-9]{1,5}"))throw new IOException("Missing readiness handshake");
            int port=Integer.parseInt(line.substring(6));if(port<1||port>65535)throw new IOException("Invalid runtime port");
            Unit unit=new Unit(release,hash,"http://127.0.0.1:"+port,token,process);check(unit);units.put(release,unit);return unit;
        }catch(FusionFault e){if(process!=null)process.destroyForcibly();throw e;}
        catch(Exception e){if(process!=null)process.destroyForcibly();throw new FusionFault(503,"运行单元准备失败，当前运行版本保持不变");}
    }
    public void check(Unit unit){
        try{
            var response=http.send(HttpRequest.newBuilder(URI.create(unit.url()+"/ready")).header("Authorization","Bearer "+unit.token()).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            JsonNode status=json.readTree(response.body());
            if(response.statusCode()!=200||!status.path("ready").asBoolean()||!unit.releaseId().equals(status.path("releaseId").asText())||!unit.hash().equals(status.path("hash").asText()))throw new Exception();
        }catch(Exception e){throw new FusionFault(503,"运行单元健康或摘要校验失败");}
    }
    public HttpResponse<InputStream> run(Unit unit,Object body)throws Exception{
        return http.send(HttpRequest.newBuilder(URI.create(unit.url()+"/run")).header("Authorization","Bearer "+unit.token()).header("Content-Type","application/json")
            .timeout(Duration.ofSeconds(600)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofInputStream());
    }
    public void cancel(String release,String run){
        Unit unit=units.get(release);if(unit==null)return;
        try{http.send(HttpRequest.newBuilder(URI.create(unit.url()+"/cancel")).header("Authorization","Bearer "+unit.token()).header("Content-Type","application/json").timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("runId",run)))).build(),HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){}
    }
    public synchronized void retireExcept(Set<String> retained){units.entrySet().removeIf(entry->{if(retained.contains(entry.getKey())||preparingUntil.getOrDefault(entry.getKey(),0L)>System.nanoTime())return false;entry.getValue().process().destroy();preparingUntil.remove(entry.getKey());return true;});}
    @PreDestroy public void stop(){units.values().forEach(unit->unit.process().destroy());units.clear();}
}
