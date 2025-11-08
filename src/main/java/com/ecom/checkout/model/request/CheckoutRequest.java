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
     * Payment method ID (optional, for saved payment methods)
     */
    @JsonProperty("payment_method_id")
    UUID paymentMethodId,
    
    /**
     * Payment gateway transaction ID (e.g., Razorpay payment_id)
     * Required when payment is processed client-side
     */
    @JsonProperty("payment_gateway_transaction_id")
    String paymentGatewayTransactionId,
    
    /**
     * Cart ID (optional, defaults to user's current cart)
     */
    @JsonProperty("cart_id")
    UUID cartId
) {
}

