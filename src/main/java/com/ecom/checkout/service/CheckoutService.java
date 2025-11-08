package com.ecom.checkout.service;

import com.ecom.checkout.model.request.AddressValidationRequest;
import com.ecom.checkout.model.request.CheckoutRequest;
import com.ecom.checkout.model.request.ShippingCalculationRequest;
import com.ecom.checkout.model.response.AddressValidationResponse;
import com.ecom.checkout.model.response.CheckoutCompleteResponse;
import com.ecom.checkout.model.response.CheckoutSummaryResponse;
import com.ecom.checkout.model.response.ShippingCalculationResponse;

import java.util.UUID;

/**
 * Service interface for checkout operations
 */
public interface CheckoutService {
    
    /**
     * Initiate checkout (validate and prepare order summary)
     */
    CheckoutSummaryResponse initiateCheckout(UUID userId, UUID tenantId, CheckoutRequest request);
    
    /**
     * Complete checkout and create order (Saga pattern)
     */
    CheckoutCompleteResponse completeCheckout(UUID userId, UUID tenantId, CheckoutRequest request, String jwtToken);
    
    /**
     * Cancel checkout and release resources
     */
    void cancelCheckout(UUID userId, UUID tenantId, UUID reservationId);
    
    /**
     * Validate shipping address
     */
    AddressValidationResponse validateAddress(UUID userId, UUID tenantId, AddressValidationRequest request);
    
    /**
     * Calculate shipping cost
     */
    ShippingCalculationResponse calculateShipping(UUID userId, UUID tenantId, ShippingCalculationRequest request);
}

