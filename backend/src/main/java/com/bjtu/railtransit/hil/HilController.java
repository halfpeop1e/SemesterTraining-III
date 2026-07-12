package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/hil")
public class HilController {
    private final LabHilGateway gateway;

    public HilController(LabHilGateway gateway) { this.gateway = gateway; }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok("HIL gateway status", gateway.status());
    }
}

