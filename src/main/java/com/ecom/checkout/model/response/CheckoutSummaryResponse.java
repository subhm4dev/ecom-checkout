package com.ecom.checkout.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for checkout summary (initiate checkout)
 */
public record CheckoutSummaryResponse(
    /**
     * Cart items
     */
    List<CheckoutItemResponse> items,
    
    /**
     * Shipping address
     */
    @JsonProperty("shipping_address")
    AddressResponse shippingAddress,
    
    /**
     * Subtotal
     */
    BigDecimal subtotal,
    
    /**
     * Discount amount
     */
    @JsonProperty("discount_amount")
    BigDecimal discountAmount,
    
    /**
     * Tax amount
     */
    @JsonProperty("tax_amount")
    BigDecimal taxAmount,
    
    /**
     * Shipping cost
     */
    @JsonProperty("shipping_cost")
    BigDecimal shippingCost,
    
    /**
     * Final total
     */
    BigDecimal total,
    
    /**
     * Currency code
     */
    String currency
) {
    /**
     * Checkout item response
     */
    public record CheckoutItemResponse(
        @JsonProperty("product_id")
        UUID productId,
        
        String name,
        
        String sku,
        
        Integer quantity,
        
        @JsonProperty("unit_price")
        BigDecimal unitPrice,
        
        @JsonProperty("total_price")
        BigDecimal totalPrice
    ) {}
    
    /**
     * Address response
     */
    public record AddressResponse(
        UUID id,
        String street,
        String city,
        String state,
        @JsonProperty("zip_code")
        String zipCode,
        String country
    ) {}
}

