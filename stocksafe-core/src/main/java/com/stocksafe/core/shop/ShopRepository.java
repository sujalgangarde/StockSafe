package com.stocksafe.core.shop;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** No tenant filter here on purpose. RLS adds it in the database. */
public interface ShopRepository extends JpaRepository<Shop, UUID> {
    List<Shop> findByDeletedAtIsNullOrderByCreatedAtAsc();

    Optional<Shop> findByIdAndDeletedAtIsNull(UUID id);
}
