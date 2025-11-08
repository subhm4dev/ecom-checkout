package com.ecom.checkout.controller;

import com.ecom.checkout.model.request.AddressValidationRequest;
import com.ecom.checkout.model.request.CheckoutRequest;
import com.ecom.checkout.model.request.ShippingCalculationRequest;
import com.ecom.checkout.model.response.AddressValidationResponse;
import com.ecom.checkout.model.response.CheckoutCompleteResponse;
import com.ecom.checkout.model.response.CheckoutSummaryResponse;
import com.ecom.checkout.model.response.ShippingCalculationResponse;
import com.ecom.checkout.security.JwtAuthenticationToken;
import com.ecom.checkout.service.CheckoutService;
import com.ecom.response.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Checkout Controller
 * 
 * <p>This controller orchestrates the checkout process, coordinating multiple
 * services to complete an order. It handles payment processing, inventory reservation,
 * order creation, and cart clearing in a transactional manner.
 * 
 * <p>Why we need these APIs:
 * <ul>
 *   <li><b>Order Orchestration:</b> Coordinates Cart, Inventory, Payment, and Order
 *       services to complete purchases. Ensures data consistency across services.</li>
 *   <li><b>Transaction Management:</b> Implements saga pattern or distributed transactions
 *       to ensure atomicity: if any step fails, all changes are rolled back.</li>
 *   <li><b>Inventory Reservation:</b> Reserves inventory during checkout to prevent
 *       overselling. Releases reservation if payment fails or checkout is cancelled.</li>
 *   <li><b>Payment Processing:</b> Initiates payment through Payment service and
 *       handles payment gateway integration.</li>
 *   <li><b>Order Creation:</b> Creates order in Order service after successful payment
 *       and inventory reservation.</li>
 *   <li><b>Error Recovery:</b> Handles partial failures gracefully, ensuring inventory
 *       is released if payment fails, and payment is refunded if order creation fails.</li>
 * </ul>
 * 
 * <p>The checkout flow follows a distributed transaction pattern (Saga) to ensure
 * eventual consistency across services while maintaining performance and scalability.
 */
@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Checkout orchestration and order creation endpoints")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {
    
    private final CheckoutService checkoutService;

    /**
     * Initiate checkout
     * 
     * <p>Starts the checkout process by validating cart contents, calculating final
     * prices, and preparing order summary. This is a read-only operation that doesn't
     * modify any state, allowing customers to review order before confirmation.
     * 
     * <p>This endpoint validates:
     * <ul>
     *   <li>Cart contents are valid and items are still available</li>
     *   <li>Inventory availability for all items</li>
     *   <li>Final prices with promotions and coupons</li>
     *   <li>Shipping address is valid</li>
     * </ul>
     * 
     * <p>This endpoint is protected and requires authentication.
     * RBAC: CUSTOMER role required.
     */
    @PostMapping("/initiate")
    @Operation(
        summary = "Initiate checkout",
        description = "Validates cart, calculates final prices, and prepares order summary for review"
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CheckoutSummaryResponse>> initiateCheckout(
            @Valid @RequestBody CheckoutRequest checkoutRequest,
            Authentication authentication) {
        
        log.info("Initiating checkout: shippingAddressId={}", checkoutRequest.shippingAddressId());
        
        UUID userId = getUserIdFromAuthentication(authentication);
        UUID tenantId = getTenantIdFromAuthentication(authentication);
        
        CheckoutSummaryResponse response = checkoutService.initiateCheckout(userId, tenantId, checkoutRequest);
        return ResponseEntity.ok(ApiResponse.success(response, "Checkout initiated successfully"));
    }

    /**
     * Complete checkout and create order
     * 
     * <p>This is the main checkout endpoint that orchestrates the entire purchase flow:
     * <ol>
     *   <li>Reserves inventory</li>
     *   <li>Processes payment</li>
     *   <li>Creates order</li>
     *   <li>Clears cart</li>
     * </ol>
     * 
     * <p>Implements saga pattern for distributed transaction management:
     * <ul>
     *   <li>If inventory reservation fails → return error (no changes made)</li>
     *   <li>If payment fails → release inventory reservation</li>
     *   <li>If order creation fails → refund payment and release inventory</li>
     * </ul>
     * 
     * <p>This endpoint is protected and requires authentication.
     * RBAC: CUSTOMER role required.
     */
    @PostMapping("/complete")
    @Operation(
        summary = "Complete checkout and create order",
        description = "Orchestrates inventory reservation, payment processing, and order creation. Implements saga pattern for transaction management."
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CheckoutCompleteResponse>> completeCheckout(
            @Valid @RequestBody CheckoutRequest checkoutRequest,
            Authentication authentication,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        
        log.info("Completing checkout: shippingAddressId={}", checkoutRequest.shippingAddressId());
        
        UUID userId = getUserIdFromAuthentication(authentication);
        UUID tenantId = getTenantIdFromAuthentication(authentication);
        
        // Extract token from Authorization header or Authentication object
        String token = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            token = authorizationHeader.substring(7);
        } else if (authentication instanceof JwtAuthenticationToken jwtToken) {
            token = jwtToken.getToken();
        }
        
        if (token == null) {
            log.error("JWT token not found in request headers or authentication");
            throw new IllegalStateException("JWT token not available");
        }
        
        CheckoutCompleteResponse response = checkoutService.completeCheckout(userId, tenantId, checkoutRequest, token);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response, "Checkout completed successfully"));
    }

    /**
     * Cancel checkout
     * 
     * <p>Cancels an in-progress checkout, releasing any reserved inventory and
     * cleaning up temporary checkout state. Used when user abandons checkout or
     * session expires.
     * 
     * <p>This endpoint is protected and requires authentication.
     * RBAC: CUSTOMER role required.
     */
    @PostMapping("/cancel")
    @Operation(
        summary = "Cancel checkout",
        description = "Cancels in-progress checkout and releases reserved inventory"
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Void> cancelCheckout(
            @RequestParam(required = false) UUID reservationId,
            Authentication authentication) {
        
        log.info("Cancelling checkout: reservationId={}", reservationId);
        
        UUID userId = getUserIdFromAuthentication(authentication);
        UUID tenantId = getTenantIdFromAuthentication(authentication);
        
        checkoutService.cancelCheckout(userId, tenantId, reservationId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Validate shipping address
     * 
     * <p>Validates that a shipping address is valid and deliverable. May integrate
     * with address validation services to ensure accurate delivery information.
     * 
     * <p>This endpoint is protected and requires authentication.
     * RBAC: CUSTOMER role required.
     */
    @PostMapping("/address/validate")
    @Operation(
        summary = "Validate shipping address",
        description = "Validates shipping address for deliverability and accuracy"
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<AddressValidationResponse>> validateAddress(
            @Valid @RequestBody AddressValidationRequest addressRequest,
            Authentication authentication) {
        
        log.info("Validating address");
        
        UUID userId = getUserIdFromAuthentication(authentication);
        UUID tenantId = getTenantIdFromAuthentication(authentication);
        
        AddressValidationResponse response = checkoutService.validateAddress(userId, tenantId, addressRequest);
        return ResponseEntity.ok(ApiResponse.success(response, "Address validated successfully"));
    }

    /**
     * Calculate shipping cost
     * 
     * <p>Calculates shipping cost based on cart contents, shipping address, and
     * shipping method. Used to display shipping options and total cost during checkout.
     * 
     * <p>This endpoint is protected and requires authentication.
     * RBAC: CUSTOMER role required.
     */
    @PostMapping("/shipping/calculate")
    @Operation(
        summary = "Calculate shipping cost",
        description = "Calculates shipping cost based on cart contents, address, and shipping method"
    )
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<ShippingCalculationResponse>> calculateShipping(
            @Valid @RequestBody ShippingCalculationRequest shippingRequest,
            Authentication authentication) {
        
        log.info("Calculating shipping: addressId={}, method={}", 
            shippingRequest.addressId(), shippingRequest.shippingMethod());
        
        UUID userId = getUserIdFromAuthentication(authentication);
        UUID tenantId = getTenantIdFromAuthentication(authentication);
        
        ShippingCalculationResponse response = checkoutService.calculateShipping(userId, tenantId, shippingRequest);
        return ResponseEntity.ok(ApiResponse.success(response, "Shipping calculated successfully"));
    }
    
    /**
     * Extract user ID from JWT authentication token
     */
    private UUID getUserIdFromAuthentication(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return UUID.fromString(jwtToken.getUserId());
        }
        throw new IllegalStateException("Invalid authentication token");
    }
    
    /**
     * Extract tenant ID from JWT authentication token
     */
    private UUID getTenantIdFromAuthentication(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return UUID.fromString(jwtToken.getTenantId());
        }
        throw new IllegalStateException("Invalid authentication token");
    }
}

