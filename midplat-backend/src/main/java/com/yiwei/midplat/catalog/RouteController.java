package com.yiwei.midplat.catalog;

import com.yiwei.midplat.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
class RouteController {

    @GetMapping
    ApiResponse<List<RouteCatalog.RouteView>> list() {
        return ApiResponse.ok(RouteCatalog.list());
    }
}
