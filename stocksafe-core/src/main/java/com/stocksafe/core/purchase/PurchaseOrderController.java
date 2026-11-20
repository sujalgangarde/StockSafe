package com.stocksafe.core.purchase;

import com.stocksafe.core.purchase.PurchaseOrderService.CreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/purchase-orders")
public class PurchaseOrderController {
    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    public record StatusRequest(@NotBlank String status) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, UUID> create(@PathVariable UUID shopId, @Valid @RequestBody CreateRequest r) {
        return Map.of("id", service.create(shopId, r));
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID shopId) {
        return service.list(shopId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID shopId, @PathVariable UUID id) {
        return service.get(shopId, id);
    }

    @PostMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void status(@PathVariable UUID shopId, @PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        service.setStatus(shopId, id, r.status());
    }
}
