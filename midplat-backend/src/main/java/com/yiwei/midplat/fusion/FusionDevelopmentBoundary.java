package com.yiwei.midplat.fusion;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** A copied production database must never restart or invoke production workloads during management development. */
@Component @Profile("fusion-local")
public class FusionDevelopmentBoundary extends OncePerRequestFilter {
 @org.springframework.beans.factory.annotation.Value("${midplat.fusion.execution-enabled:false}") private boolean executionEnabled;
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws IOException,ServletException{
  if(executionEnabled){chain.doFilter(req,res);return;}
  String path=req.getRequestURI();boolean blocked=path.startsWith("/api/runtime/")||path.startsWith("/api/open/")||path.matches("/api/models/[^/]+/ping")||(!req.getMethod().equals("GET")&&path.startsWith("/api/evaluation/"));
  if(blocked){res.setStatus(409);res.setContentType("application/problem+json;charset=UTF-8");res.getWriter().write("{\"detail\":\"融合开发环境当前仅开放配置管理，执行尚未接入\"}");return;}
  chain.doFilter(req,res);
 }
}
