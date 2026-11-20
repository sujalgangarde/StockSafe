package com.stocksafe.core.stock;

import com.stocksafe.core.stock.StockService.AdjustRequest;
import com.stocksafe.core.stock.StockService.Allocation;
import com.stocksafe.core.stock.StockService.ReceiveRequest;
import com.stocksafe.core.stock.StockService.SellRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shops/{shopId}")
public class StockController {
    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @PostMapping("/stock/receive")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, UUID> receive(@PathVariable UUID shopId, @Valid @RequestBody ReceiveRequest r) {
        return Map.of("batchId", service.receive(shopId, r));
    }

    @PostMapping("/stock/sell")
    public List<Allocation> sell(@PathVariable UUID shopId, @Valid @RequestBody SellRequest r) {
        return service.sell(shopId, r);
    }

    @PostMapping("/stock/adjust")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void adjust(@PathVariable UUID shopId, @Valid @RequestBody AdjustRequest r) {
        service.adjust(shopId, r);
    }

    @GetMapping("/products/{productId}/batches")
    public List<Map<String, Object>> batches(@PathVariable UUID shopId, @PathVariable UUID productId) {
        return service.batches(shopId, productId);
    }

    @GetMapping("/products/{productId}/movements")
    public List<Map<String, Object>> movements(@PathVariable UUID shopId, @PathVariable UUID productId,
                                               @RequestParam(defaultValue = "100") int limit) {
        return service.movements(shopId, productId, limit);
    }

    @GetMapping("/reports/near-expiry")
    public List<Map<String, Object>> nearExpiry(@PathVariable UUID shopId, @RequestParam(defaultValue = "30") int days) {
        return service.nearExpiry(shopId, days);
    }

    @GetMapping("/reports/low-stock")
    public List<Map<String, Object>> lowStock(@PathVariable UUID shopId) {
        return service.lowStock(shopId);
    }

    @GetMapping("/reports/dead-stock")
    public List<Map<String, Object>> deadStock(@PathVariable UUID shopId, @RequestParam(defaultValue = "90") int days) {
        return service.deadStock(shopId, days);
    }
}
