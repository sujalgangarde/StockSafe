package com.stocksafe.core.shop;

import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.tenant.TenantContext;
import com.stocksafe.core.usage.UsageService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShopService {
    private final ShopRepository repo;
    private final UsageService usage;

    public ShopService(ShopRepository repo, UsageService usage) {
        this.repo = repo;
        this.usage = usage;
    }

    @Transactional
    public Shop create(String name, ShopTemplate template) {
        usage.consumeOrThrow("shops");   // same transaction: if the insert fails, the counter rolls back
        return repo.save(new Shop(UUID.randomUUID(), TenantContext.require(), name, template,
                template.defaultSettings()));
    }

    @Transactional(readOnly = true)
    public List<Shop> list() {
        return repo.findByDeletedAtIsNullOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public Shop get(UUID id) {
        // Another tenant's shop is invisible (RLS), so it looks like "not found".
        return repo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Shop not found"));
    }

    @Transactional
    public void delete(UUID id) {
        Shop shop = repo.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Shop not found"));
        shop.markDeleted();
        usage.release("shops");
    }
}
