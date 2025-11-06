package com.ecom.checkout.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for address validation
 */
public record AddressValidationRequest(
    @NotBlank(message = "Street is required")
    String street,
    
    String city,
    
    String state,
    
    @JsonProperty("zip_code")
    String zipCode,
    
    String country
) {
}

