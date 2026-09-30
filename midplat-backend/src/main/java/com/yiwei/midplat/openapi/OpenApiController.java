package com.yiwei.midplat.openapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

@RestController
class OpenApiController {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private final OpenApiGatewayService gateway;

    OpenApiController(OpenApiGatewayService gateway) {
        this.gateway = gateway;
    }

    @RequestMapping(
            value = {"/api/open/{ownerPlatformId}/{apiId}", "/api/open/{ownerPlatformId}/{apiId}/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    ResponseEntity<byte[]> open(
            @PathVariable String ownerPlatformId,
            @PathVariable String apiId,
            HttpServletRequest request) throws Exception {
        return gateway.forward(ownerPlatformId, apiId, extraPath(request), request);
    }

    private static String extraPath(HttpServletRequest request) {
        Object path = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(path instanceof String pathValue) || !(pattern instanceof String patternValue)) {
            return "";
        }
        return PATH_MATCHER.extractPathWithinPattern(patternValue, pathValue);
    }
}
