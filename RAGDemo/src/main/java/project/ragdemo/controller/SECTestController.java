package project.ragdemo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.ragdemo.sec.SECClient;

@RestController
@RequiredArgsConstructor
public class SECTestController {
    private final SECClient secClient;

    @GetMapping("/test-sec")
    public String testSEC() {
        return secClient.getSubmissions("0000320193");
    }
}
