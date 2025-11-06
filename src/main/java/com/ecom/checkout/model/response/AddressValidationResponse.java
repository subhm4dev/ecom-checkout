package com.ecom.checkout.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for address validation
 */
public record AddressValidationResponse(
    /**
     * Whether address is valid
     */
    Boolean valid,
    
    /**
     * Suggested corrections (if address is invalid)
     */
    @JsonProperty("suggested_corrections")
    String suggestedCorrections,
    
    /**
     * Validation message
     */
    String message
) {
}

