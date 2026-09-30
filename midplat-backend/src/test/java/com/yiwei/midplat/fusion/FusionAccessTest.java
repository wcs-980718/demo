package com.yiwei.midplat.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import com.yiwei.midplat.common.api.ForbiddenException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FusionAccessTest {
 static RSAKey key;static HttpServer jwks;FusionAccess access;
 @BeforeAll static void start()throws Exception{
  key=new RSAKeyGenerator(2048).keyID("fusion-test").generate();jwks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
  jwks.createContext("/keys",e->{e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});jwks.start();
 }
 @AfterAll static void stop(){jwks.stop(0);}
 @BeforeEach void setup(){access=new FusionAccess(true,false,"012345678901234567890123456789012345","https://identity.test","http://127.0.0.1:"+jwks.getAddress().getPort()+"/keys","midplat-fusion","http://localhost:8000",new MockEnvironment(),new ObjectMapper());}
 String token(String scope,String audience,Instant expires)throws Exception{
  var claims=new JWTClaimsSet.Builder().subject("user-a").issuer("https://identity.test").audience(audience).expirationTime(expires==null?null:Date.from(expires)).issueTime(Date.from(Instant.now())).claim("scope",scope).claim("project_ids",List.of("project-a")).build();
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();
 }
 MockHttpServletRequest request(String method,String path,String token){var req=new MockHttpServletRequest(method,path);req.addHeader("Authorization","Bearer "+token);return req;}
 @Test void readOnlyJwtCannotWriteAndCannotReadAnotherProject()throws Exception{
  String token=token("fusion:read","midplat-fusion",Instant.now().plusSeconds(300));var req=request("GET","/api/fusion/projects",token);var res=new MockHttpServletResponse();access.doFilter(req,res,new MockFilterChain());
  assertNotNull(FusionAccess.identity(req));FusionAccess.project(req,"project-a",false);assertThrows(ForbiddenException.class,()->FusionAccess.project(req,"project-b",false));
  var write=request("PUT","/api/fusion/projects/project-a/draft",token);var denied=new MockHttpServletResponse();access.doFilter(write,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
 }
 @Test void rejectsInvalidAudienceExpiredAndForgedIdentity()throws Exception{
  for(String bearer:List.of(token("fusion:write","other",Instant.now().plusSeconds(300)),token("fusion:write","midplat-fusion",Instant.now().minusSeconds(180)),token("fusion:write","midplat-fusion",null),"forged")){
   var req=request("GET","/api/fusion/projects",bearer);req.addHeader("X-Project-Id","project-a");req.addHeader("X-User-Id","admin");var res=new MockHttpServletResponse();access.doFilter(req,res,new MockFilterChain());assertEquals(401,res.getStatus());
  }
 }
 @Test void internalServiceCredentialDoesNotAuthorizeBrowserManagement()throws Exception{
  var req=request("GET","/api/fusion/projects","012345678901234567890123456789012345");var res=new MockHttpServletResponse();access.doFilter(req,res,new MockFilterChain());assertEquals(401,res.getStatus());
 }
 @Test void localSessionsRequireLoopbackOriginAndCsrf()throws Exception{
  MockEnvironment env=new MockEnvironment().withProperty("server.address","127.0.0.1");env.setActiveProfiles("fusion-local");
  var local=new FusionAccess(true,true,"012345678901234567890123456789012345","","","midplat-fusion","http://localhost:8000",env,new ObjectMapper());
  var session=new MockHttpServletRequest("GET","/api/fusion/session");session.setRemoteAddr("127.0.0.1");local.doFilter(session,new MockHttpServletResponse(),new MockFilterChain());
  var write=new MockHttpServletRequest("PUT","/api/fusion/projects/project-a/draft");write.setRemoteAddr("127.0.0.1");write.setSession((MockHttpSession)session.getSession());
  var denied=new MockHttpServletResponse();local.doFilter(write,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
  write.addHeader("X-Fusion-CSRF",session.getSession().getAttribute("fusion.csrf"));write.addHeader("Origin","https://evil.invalid");denied=new MockHttpServletResponse();local.doFilter(write,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
 }
 @Test void existingIntranetManagementNeedsNoLoginAndStillChecksRequestOriginAndCsrf()throws Exception{
  var env=new MockEnvironment().withProperty("midplat.fusion.intranet-management","true").withProperty("midplat.fusion.intranet-networks","172.14.0.0/16");
  var intranet=new FusionAccess(true,false,"012345678901234567890123456789012345","","","midplat-fusion","http://portal.example.com:31010",env,new ObjectMapper());
  var session=new MockHttpServletRequest("GET","/api/fusion/session");session.setRemoteAddr("10.42.1.2");session.addHeader("Origin","http://portal.example.com:31010");
  intranet.doFilter(session,new MockHttpServletResponse(),new MockFilterChain());
  assertEquals("midplat-intranet",FusionAccess.identity(session).principal());
  var clusterSession=new MockHttpServletRequest("GET","/api/fusion/session");clusterSession.setRemoteAddr("172.14.0.188");
  intranet.doFilter(clusterSession,new MockHttpServletResponse(),new MockFilterChain());assertNotNull(FusionAccess.identity(clusterSession));
  var write=new MockHttpServletRequest("PUT","/api/fusion/projects/project-a/draft");write.setRemoteAddr("10.42.1.2");write.setSession((MockHttpSession)session.getSession());
  var rejected=new MockHttpServletResponse();intranet.doFilter(write,rejected,new MockFilterChain());assertEquals(403,rejected.getStatus());
  write.addHeader("X-Fusion-CSRF",session.getSession().getAttribute("fusion.csrf"));
  var accepted=new MockFilterChain();intranet.doFilter(write,new MockHttpServletResponse(),accepted);assertNotNull(accepted.getRequest());
  write.addHeader("Origin","https://untrusted.invalid");rejected=new MockHttpServletResponse();intranet.doFilter(write,rejected,new MockFilterChain());assertEquals(403,rejected.getStatus());
  var publicClient=new MockHttpServletRequest("GET","/api/fusion/session");publicClient.setRemoteAddr("203.0.113.12");rejected=new MockHttpServletResponse();intranet.doFilter(publicClient,rejected,new MockFilterChain());assertEquals(403,rejected.getStatus());
  var callback=new MockHttpServletRequest("POST","/api/fusion-internal/catalog/resolve");callback.setRemoteAddr("127.0.0.1");rejected=new MockHttpServletResponse();intranet.doFilter(callback,rejected,new MockFilterChain());assertEquals(401,rejected.getStatus());
 }
 @Test void intranetManagementDoesNotRelaxDefaultJwtOrLoopbackDevelopmentConfiguration(){
  assertThrows(IllegalStateException.class,()->new FusionAccess(true,false,"012345678901234567890123456789012345","","","midplat-fusion","http://localhost:8000",new MockEnvironment(),new ObjectMapper()));
  var env=new MockEnvironment().withProperty("midplat.fusion.intranet-management","true");
  assertThrows(IllegalStateException.class,()->new FusionAccess(true,false,"012345678901234567890123456789012345","","","midplat-fusion","*",env,new ObjectMapper()));
 }
}
