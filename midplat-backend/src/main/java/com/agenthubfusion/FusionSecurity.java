package com.agenthubfusion;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
public class FusionSecurity extends OncePerRequestFilter {
 private final byte[] credential;
 public FusionSecurity(@Value("${fusion.service-token}") String token){
  if(token.length()<32)throw new IllegalStateException("fusion.service-token must contain at least 32 characters");
  credential=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
  if(req.getMethod().equals("GET")&&java.util.Set.of("/internal/fusion/health/live","/internal/fusion/health/ready").contains(req.getRequestURI())){chain.doFilter(req,res);return;}
  if(req.getRequestURI().equals("/internal/fusion/tool-invocations")){chain.doFilter(req,res);return;}
  String auth=req.getHeader("Authorization");
  if(auth==null||!MessageDigest.isEqual(credential,auth.getBytes(StandardCharsets.UTF_8))){res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"detail\":\"Service authentication required\"}");return;}
  chain.doFilter(req,res);
 }
}
