package com.yiwei.midplat.fusion;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
@Order(-10)
public class FusionExceptionHandler {
 @ExceptionHandler(FusionUpstreamFault.class) public ResponseEntity<ProblemDetail> upstream(FusionUpstreamFault e){int status=java.util.Set.of(400,403,404,409,410,422,503,504).contains(e.status())?e.status():502;return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),e.getMessage()));}
}
