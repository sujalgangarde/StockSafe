package com.stocksafe.core.sse;

import com.stocksafe.core.shop.ShopAccess;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GET /api/v1/shops/{shopId}/events. The browser sends Last-Event-ID after a reconnect. */
@RestController
public class SseController {
    private final SseHub hub;
    private final ShopAccess shops;

    public SseController(SseHub hub, ShopAccess shops) {
        this.hub = hub;
        this.shops = shops;
    }

    @GetMapping(value = "/api/v1/shops/{shopId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID shopId,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        shops.require(shopId);      // 404 for another tenant's shop
        return hub.subscribe(shopId, lastEventId);
    }
}
