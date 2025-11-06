package com.ecom.checkout.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request DTO for checkout operations
 */
public record CheckoutRequest(
    /**
     * Shipping address ID
     */
    @NotNull(message = "Shipping address ID is required")
    @JsonProperty("shipping_address_id")
    UUID shippingAddressId,
    
    /**
     * Payment method ID
     */
    @JsonProperty("payment_method_id")
    UUID paymentMethodId,
    
    /**
     * Cart ID (optional, defaults to user's current cart)
     */
    @JsonProperty("cart_id")
    UUID cartId
) {
}

