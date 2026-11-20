package com.stocksafe.core.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {
    private final ShopService service;

    public ShopController(ShopService service) {
        this.service = service;
    }

    public record CreateShopRequest(@NotBlank @Size(max = 120) String name, @NotNull ShopTemplate template) {}

    public record ShopResponse(UUID id, String name, ShopTemplate template, Map<String, Object> settings,
                               Instant createdAt) {
        static ShopResponse from(Shop s) {
            return new ShopResponse(s.getId(), s.getName(), s.getTemplate(), s.getSettings(), s.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public ShopResponse create(@Valid @RequestBody CreateShopRequest r) {
        return ShopResponse.from(service.create(r.name(), r.template()));
    }

    @GetMapping
    public List<ShopResponse> list() {
        return service.list().stream().map(ShopResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ShopResponse get(@PathVariable UUID id) {
        return ShopResponse.from(service.get(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
