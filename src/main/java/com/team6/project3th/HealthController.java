package com.team6.project3th;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping({"/api/health", "/health"})
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
