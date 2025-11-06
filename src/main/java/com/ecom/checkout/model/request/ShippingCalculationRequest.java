package com.ecom.checkout.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for shipping cost calculation
 */
public record ShippingCalculationRequest(
    /**
     * Shipping address ID
     */
    @JsonProperty("address_id")
    UUID addressId,
    
    /**
     * Shipping method (STANDARD, EXPRESS, etc.)
     */
    @JsonProperty("shipping_method")
    String shippingMethod
) {
}

