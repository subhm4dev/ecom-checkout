package com.ecom.checkout.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for shipping cost calculation
 */
public record ShippingCalculationResponse(
    /**
     * Available shipping options
     */
    List<ShippingOption> shippingOptions
) {
    /**
     * Shipping option
     */
    public record ShippingOption(
        String method, // STANDARD, EXPRESS, etc.
        
        String name,
        
        @JsonProperty("estimated_days")
        Integer estimatedDays,
        
        BigDecimal cost,
        
        String currency
    ) {}
}

