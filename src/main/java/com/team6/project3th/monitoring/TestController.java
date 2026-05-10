package com.team6.project3th.monitoring;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/monitoring/tests")
public class TestController {

    private final TestService testService;

    public TestController(TestService testService) {
        this.testService = testService;
    }

    @PostMapping
    public ResponseEntity<TestResponse> create(@RequestBody(required = false) TestCreateRequest request) {
        String name = request != null && request.name() != null && !request.name().isBlank()
                ? request.name()
                : "load-test-" + UUID.randomUUID();

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(testService.create(name));
    }

    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("count", testService.count());
    }
}
