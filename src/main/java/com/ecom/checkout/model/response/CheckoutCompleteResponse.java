package com.ecom.checkout.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for completed checkout
 */
public record CheckoutCompleteResponse(
    /**
     * Order ID
     */
    @JsonProperty("order_id")
    UUID orderId,
    
    /**
     * Order number (human-readable)
     */
    @JsonProperty("order_number")
    String orderNumber,
    
    /**
     * Payment ID
     */
    @JsonProperty("payment_id")
    UUID paymentId,
    
    /**
     * Order total
     */
    BigDecimal total,
    
    /**
     * Currency code
     */
    String currency,
    
    /**
     * Order status
     */
    String status,
    
    /**
     * Order creation timestamp
     */
    @JsonProperty("created_at")
    LocalDateTime createdAt
) {
}

