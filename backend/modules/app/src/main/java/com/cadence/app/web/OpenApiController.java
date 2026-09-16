package com.cadence.app.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class OpenApiController {

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public String yaml() throws IOException {
        return new String(new ClassPathResource("static/openapi.yaml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/v3/api-docs", produces = MediaType.APPLICATION_JSON_VALUE)
    public String json() {
        return """
                {"openapi":"3.0.3","info":{"title":"Cadence API","version":"0.1.0"},"paths":{"/api/public/health":{"get":{"summary":"Public health"}}}}
                """;
    }
}
