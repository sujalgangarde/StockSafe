package com.stocksafe.core.product;

import com.stocksafe.core.product.ProductService.Product;
import com.stocksafe.core.product.ProductService.ProductRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Product create(@PathVariable UUID shopId, @Valid @RequestBody ProductRequest r) {
        return service.create(shopId, r);
    }

    @GetMapping
    public List<Product> list(@PathVariable UUID shopId,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String barcode,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return service.list(shopId, q, barcode, page, size);
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable UUID shopId, @PathVariable UUID id) {
        return service.get(shopId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public void delete(@PathVariable UUID shopId, @PathVariable UUID id) {
        service.delete(shopId, id);
    }
}
