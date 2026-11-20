package com.stocksafe.alerts.api;

import com.stocksafe.alerts.common.ApiException;
import com.stocksafe.alerts.messaging.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Operator tool: send every message in the dead-letter queue back to the main queue (after you fixed the bug). */
@RestController
@RequestMapping("/internal/dlq")
public class DlqAdminController {
    private final RabbitTemplate rabbit;
    private final String token;

    public DlqAdminController(RabbitTemplate rabbit, @Value("${stocksafe.admin.token}") String token) {
        this.rabbit = rabbit;
        this.token = token;
    }

    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestHeader(value = "X-Admin-Token", required = false) String given,
                                      @RequestParam(defaultValue = "1000") int max) {
        if (token.isBlank() || given == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        int n = 0;
        while (n < max) {
            Message m = rabbit.receive(RabbitConfig.DLQ);
            if (m == null) break;
            m.getMessageProperties().getHeaders().remove("x-death");
            rabbit.send("", RabbitConfig.QUEUE, m);       // straight to the main queue; consumers ignore duplicates
            n++;
        }
        return Map.of("replayed", n);
    }
}
