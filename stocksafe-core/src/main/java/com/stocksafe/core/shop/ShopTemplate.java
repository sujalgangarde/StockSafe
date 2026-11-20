package com.stocksafe.core.shop;

import java.util.List;
import java.util.Map;

/** Starter settings per shop type. Products (Stage 2) read these. */
public enum ShopTemplate {
    MEDICAL(true, List.of("salt_composition", "manufacturer", "schedule", "prescription_required")),
    GROCERY(true, List.of("brand", "weight", "veg_nonveg")),
    HARDWARE(false, List.of("brand", "size", "material")),
    CUSTOM(false, List.of());

    private final boolean expiryRequired;
    private final List<String> customFields;

    ShopTemplate(boolean expiryRequired, List<String> customFields) {
        this.expiryRequired = expiryRequired;
        this.customFields = customFields;
    }

    public Map<String, Object> defaultSettings() {
        return Map.of("expiryRequired", expiryRequired, "customFields", customFields);
    }
}
