package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.yiwei.midplat.common.api.ForbiddenException;

@Component
public class FusionAccess extends OncePerRequestFilter {
 public record Identity(String principal,Set<String> projects,boolean writable,boolean admin){}
 private final boolean enabled,local,intranet;private final byte[] serviceToken;private final Set<String> origins;private final JwtDecoder decoder;private final String audience;private final ObjectMapper json;
 private final List<IntranetNetwork> intranetNetworks;
 public FusionAccess(@Value("${midplat.fusion.enabled:false}") boolean enabled,@Value("${midplat.fusion.local-auth:false}") boolean local,
  @Value("${midplat.fusion.service-token:}") String token,@Value("${midplat.fusion.issuer:}") String issuer,@Value("${midplat.fusion.jwk-set-uri:}") String jwks,
  @Value("${midplat.fusion.audience:midplat-fusion}") String audience,@Value("${midplat.fusion.web-origins:http://127.0.0.1:8000,http://localhost:8000}") String origins,Environment env,ObjectMapper json){
  this.enabled=enabled;this.local=local;this.audience=audience;this.json=json;this.origins=Set.of(origins.split(","));
  this.intranet=env.getProperty("midplat.fusion.intranet-management",Boolean.class,false);
  intranetNetworks=Arrays.stream(env.getProperty("midplat.fusion.intranet-networks","").split(",")).map(String::trim).filter(value->!value.isEmpty()).map(IntranetNetwork::parse).toList();
  if(enabled&&intranet&&(local||this.origins.isEmpty()||this.origins.contains("*")||this.origins.contains("")))throw new IllegalStateException("Intranet management requires explicit web origins and cannot use local authentication");
  if(enabled&&token.length()<32)throw new IllegalStateException("Fusion service token must contain at least 32 characters");
  if(enabled&&local&&(!Arrays.asList(env.getActiveProfiles()).contains("fusion-local")||!Set.of("127.0.0.1","::1").contains(env.getProperty("server.address",""))))throw new IllegalStateException("Local fusion authentication requires fusion-local profile and loopback binding");
  serviceToken=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
  if(enabled&&!local&&!intranet){if(issuer.isBlank()||jwks.isBlank())throw new IllegalStateException("Fusion JWT issuer and JWK set URI are required");NimbusJwtDecoder d=NimbusJwtDecoder.withJwkSetUri(jwks).build();d.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));decoder=d;}else decoder=null;
 }
 public boolean enabled(){return enabled;}public boolean local(){return local;}
 public boolean intranet(){return intranet;}
 public static Identity identity(HttpServletRequest req){return (Identity)req.getAttribute("fusion.identity");}
 public static void project(HttpServletRequest req,String id,boolean write){Identity i=identity(req);if(i==null||(!i.admin()&&!i.projects().contains(id))||(write&&!i.writable()))throw new ForbiddenException("没有该项目的操作权限");}
 public static void admin(HttpServletRequest req){Identity i=identity(req);if(i==null||!i.admin())throw new ForbiddenException("需要融合管理权限");}
 private boolean legacyManagement(String path){return path.matches("/api/(models|prompts|platforms|menus|evaluation)(/.*)?");}
 // W1: exception-report GET is a management read and must pass the management identity filter;
 // POST stays outside because machine callers authenticate with their own credential.
 private boolean exceptionManagement(String path,String method){return path.startsWith("/api/exception-reports")&&!method.equals("POST");}
 // 管理域路径集中登记：/api/access（客户与凭证）、/api/capabilities（能力目录）与
 // /api/runtime/v2/* 除外——后者是机器 key 端点，自带 Bearer 认证，不得进入本过滤器。
 private boolean accessManagement(String path){
  return path.startsWith("/api/access")||path.startsWith("/api/capabilities");
 }
 @Override protected boolean shouldNotFilter(HttpServletRequest req){return !(req.getRequestURI().startsWith("/api/fusion/")||req.getRequestURI().startsWith("/api/fusion-internal/")||exceptionManagement(req.getRequestURI(),req.getMethod())||accessManagement(req.getRequestURI())||(enabled&&legacyManagement(req.getRequestURI())));}
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  if(req.getRequestURI().equals("/api/fusion/status")&&req.getMethod().equals("GET")){chain.doFilter(req,res);return;}
  if(!enabled){reject(res,404,"融合管理尚未启用");return;}
  if(req.getRequestURI().startsWith("/api/fusion-internal/")){
   if(req.getRequestURI().equals("/api/fusion-internal/model-invocations")||req.getRequestURI().equals("/api/fusion-internal/model-invocations/cancel")){chain.doFilter(req,res);return;}
   String auth=req.getHeader("Authorization");if(auth==null||!MessageDigest.isEqual(serviceToken,auth.getBytes(StandardCharsets.UTF_8))){reject(res,401,"Service authentication required");return;}chain.doFilter(req,res);return;
  }
  boolean write=!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod());
  if(local||intranet){
   if(local&&!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(req.getRemoteAddr())){reject(res,403,"Local authentication is loopback only");return;}
   if(intranet&&!privateAddress(req.getRemoteAddr())){reject(res,403,"当前管理入口仅用于内网访问");return;}
   String origin=req.getHeader("Origin");if((origin!=null&&!origins.contains(origin))||"cross-site".equals(req.getHeader("Sec-Fetch-Site"))){reject(res,403,"Untrusted browser origin");return;}
   res.setHeader("Cache-Control","no-store");
   if(req.getMethod().equals("OPTIONS")&&origin!=null){chain.doFilter(req,res);return;}
   HttpSession session=req.getSession(false);
   if(req.getRequestURI().equals("/api/fusion/session")&&req.getMethod().equals("GET")){
    session=req.getSession(true);if(session.getAttribute("fusion.csrf")==null)session.setAttribute("fusion.csrf",UUID.randomUUID().toString());
   }
   if(session==null||session.getAttribute("fusion.csrf")==null){reject(res,401,"请先建立融合管理会话");return;}
   if(write&&!Objects.equals(session.getAttribute("fusion.csrf"),req.getHeader("X-Fusion-CSRF"))){reject(res,403,"Invalid CSRF token");return;}
   req.setAttribute("fusion.identity",new Identity(local?"local-developer":"midplat-intranet",Set.of(),true,true));
  }else{
   try{
    String auth=req.getHeader("Authorization");if(auth==null||!auth.startsWith("Bearer "))throw new IllegalArgumentException();
    Jwt jwt=decoder.decode(auth.substring(7));if(jwt.getExpiresAt()==null||!jwt.getAudience().contains(audience)||jwt.getSubject()==null||jwt.getSubject().length()>128)throw new IllegalArgumentException();
    Set<String> scopes=new HashSet<>(Arrays.asList(Optional.ofNullable(jwt.getClaimAsString("scope")).orElse("").split(" ")));
    boolean admin=scopes.contains("fusion:admin");boolean writable=admin||scopes.contains("fusion:write");
    if(!admin&&!writable&&!scopes.contains("fusion:read")){reject(res,403,"Missing fusion scope");return;}
    List<String> projects=jwt.getClaimAsStringList("project_ids");
    req.setAttribute("fusion.identity",new Identity(jwt.getSubject(),projects==null?Set.of():Set.copyOf(projects),writable,admin));
    if(write&&!writable){reject(res,403,"Read-only identity");return;}
   }catch(Exception e){reject(res,401,"Invalid fusion access token");return;}
  }
  if(legacyManagement(req.getRequestURI())&&!identity(req).admin()){reject(res,403,"Legacy management pages require fusion admin permission");return;}
  chain.doFilter(req,res);
 }
 private boolean privateAddress(String address){
  try{var ip=java.net.InetAddress.getByName(address);return ip.isLoopbackAddress()||ip.isSiteLocalAddress()||intranetNetworks.stream().anyMatch(network->network.contains(ip.getAddress()));}
  catch(Exception invalid){return false;}
 }
 private record IntranetNetwork(byte[] address,int prefix){
  static IntranetNetwork parse(String value){
   try{String[] pieces=value.split("/",-1);if(pieces.length!=2||!pieces[0].matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException();
    byte[] address=java.net.InetAddress.getByName(pieces[0]).getAddress();int prefix=Integer.parseInt(pieces[1]);
    if(prefix<8||prefix>address.length*8)throw new IllegalArgumentException();return new IntranetNetwork(address,prefix);
   }catch(Exception invalid){throw new IllegalStateException("Invalid explicitly trusted intranet network",invalid);}
  }
  boolean contains(byte[] candidate){
   if(candidate.length!=address.length)return false;
   for(int bit=0;bit<prefix;bit++)if((candidate[bit/8]&(1<<(7-bit%8)))!=(address[bit/8]&(1<<(7-bit%8))))return false;
   return true;
  }
 }
 private void reject(HttpServletResponse res,int code,String detail)throws IOException{res.setStatus(code);res.setContentType("application/problem+json;charset=UTF-8");json.writeValue(res.getWriter(),Map.of("status",code,"detail",detail));}
}
