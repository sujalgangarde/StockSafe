package com.stocksafe.core.supplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {
    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    public record SupplierRequest(@NotBlank @Size(max = 120) String name, @Size(max = 30) String phone,
                                  @Email @Size(max = 200) String email) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, UUID> create(@Valid @RequestBody SupplierRequest r) {
        return Map.of("id", service.create(r.name(), r.phone(), r.email()));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
