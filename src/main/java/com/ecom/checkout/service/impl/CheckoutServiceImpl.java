package com.ecom.checkout.service.impl;

import com.ecom.checkout.model.request.AddressValidationRequest;
import com.ecom.checkout.model.request.CheckoutRequest;
import com.ecom.checkout.model.request.ShippingCalculationRequest;
import com.ecom.checkout.model.response.AddressValidationResponse;
import com.ecom.checkout.model.response.CheckoutCompleteResponse;
import com.ecom.checkout.model.response.CheckoutSummaryResponse;
import com.ecom.checkout.model.response.ShippingCalculationResponse;
import com.ecom.checkout.security.JwtAuthenticationToken;
import com.ecom.checkout.service.CheckoutService;
import com.ecom.error.exception.BusinessException;
import com.ecom.error.model.ErrorCode;
import com.ecom.httpclient.client.ResilientWebClient;
import com.ecom.response.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of CheckoutService with Saga pattern orchestration
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {
    
    private final ResilientWebClient resilientWebClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    // Store JWT token for service-to-service calls
    private String currentJwtToken;
    
    @Value("${services.cart.url:http://localhost:8087}")
    private String cartServiceUrl;
    
    @Value("${services.catalog.url:http://localhost:8084}")
    private String catalogServiceUrl;
    
    @Value("${services.inventory.url:http://localhost:8085}")
    private String inventoryServiceUrl;
    
    @Value("${services.promo.url:http://localhost:8086}")
    private String promoServiceUrl;
    
    @Value("${services.payment.url:http://localhost:8089}")
    private String paymentServiceUrl;
    
    @Value("${services.order.url:http://localhost:8090}")
    private String orderServiceUrl;
    
    @Value("${services.address.url:http://localhost:8083}")
    private String addressServiceUrl;
    
    private static final String ORDER_CREATED_TOPIC = "order-created";
    
    @Override
    public CheckoutSummaryResponse initiateCheckout(UUID userId, UUID tenantId, CheckoutRequest request) {
        log.debug("Initiating checkout: userId={}, shippingAddressId={}", userId, request.shippingAddressId());
        
        // 1. Get cart from Cart service
        CartInfo cart = getCartFromService(userId, tenantId);
        
        if (cart.items().isEmpty()) {
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Cart is empty");
        }
        
        // 2. Validate shipping address (required for checkout)
        if (request.shippingAddressId() == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Shipping address is required");
        }
        AddressInfo address = getAddressFromService(request.shippingAddressId(), userId, tenantId);
        
        // 3. Calculate final prices with promotions
        BigDecimal subtotal = cart.subtotal();
        BigDecimal discountAmount = cart.discountAmount();
        BigDecimal taxAmount = BigDecimal.ZERO; // Placeholder
        BigDecimal shippingCost = calculateShippingCost(address, cart);
        BigDecimal total = subtotal.subtract(discountAmount).add(taxAmount).add(shippingCost);
        
        // 4. Validate inventory availability
        validateInventoryAvailability(cart.items(), tenantId);
        
        // 5. Build response
        List<CheckoutSummaryResponse.CheckoutItemResponse> items = cart.items().stream()
            .map(item -> new CheckoutSummaryResponse.CheckoutItemResponse(
                item.productId(),
                item.name(),
                item.sku(),
                item.quantity(),
                item.unitPrice(),
                item.totalPrice()
            ))
            .toList();
        
        CheckoutSummaryResponse.AddressResponse addressResponse = new CheckoutSummaryResponse.AddressResponse(
            address.id(),
            address.line1(),
            address.city(),
            address.state(),
            address.postcode(),
            address.country()
        );
        
        return new CheckoutSummaryResponse(
            items,
            addressResponse,
            subtotal,
            discountAmount,
            taxAmount,
            shippingCost,
            total,
            cart.currency()
        );
    }
    
    @Override
    public CheckoutCompleteResponse completeCheckout(UUID userId, UUID tenantId, CheckoutRequest request, String jwtToken) {
        // Store token for use in service-to-service calls
        this.currentJwtToken = jwtToken;
        log.info("Completing checkout: userId={}, shippingAddressId={}", userId, request.shippingAddressId());
        
        UUID reservationId = null;
        UUID paymentId = null;
        UUID orderId = null;
        
        try {
            // Step 1: Validate and prepare
            CartInfo cart = getCartFromService(userId, tenantId);
            
            // Idempotency check: If cart is empty but we have a payment transaction ID,
            // check if an order already exists for this payment
            if (cart.items().isEmpty() && request.paymentGatewayTransactionId() != null && 
                !request.paymentGatewayTransactionId().isEmpty()) {
                log.info("Cart is empty but payment transaction ID provided. Checking for existing order: transactionId={}", 
                    request.paymentGatewayTransactionId());
                
                Map<String, Object> existingOrderData = findOrderByPaymentTransactionId(userId, tenantId, request.paymentGatewayTransactionId());
                if (existingOrderData != null && !existingOrderData.isEmpty()) {
                    try {
                        UUID existingOrderId = null;
                        Object orderIdObj = existingOrderData.get("id");
                        if (orderIdObj != null) {
                            if (orderIdObj instanceof UUID) {
                                existingOrderId = (UUID) orderIdObj;
                            } else if (orderIdObj instanceof String) {
                                existingOrderId = UUID.fromString((String) orderIdObj);
                            }
                        }
                        
                        if (existingOrderId == null) {
                            log.error("Failed to extract order ID from existing order data: orderIdObj={}, orderData={}", 
                                orderIdObj, existingOrderData);
                            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Failed to retrieve existing order details");
                        }
                        
                        UUID existingPaymentId = null;
                        Object paymentIdObj = existingOrderData.get("payment_id");
                        if (paymentIdObj != null) {
                            if (paymentIdObj instanceof UUID) {
                                existingPaymentId = (UUID) paymentIdObj;
                            } else if (paymentIdObj instanceof String) {
                                existingPaymentId = UUID.fromString((String) paymentIdObj);
                            }
                        }
                        
                        // Extract order details from the order data
                        BigDecimal total = BigDecimal.ZERO;
                        Object totalObj = existingOrderData.get("total");
                        if (totalObj != null) {
                            if (totalObj instanceof Number) {
                                total = new BigDecimal(totalObj.toString());
                            } else if (totalObj instanceof String) {
                                total = new BigDecimal((String) totalObj);
                            }
                        }
                        
                        String currency = (String) existingOrderData.getOrDefault("currency", "INR");
                        if (currency == null) {
                            currency = "INR";
                        }
                        
                        // Extract order number
                        String orderNumber = null;
                        Object orderNumberObj = existingOrderData.get("order_number");
                        if (orderNumberObj != null) {
                            orderNumber = orderNumberObj.toString();
                        }
                        if (orderNumber == null || orderNumber.isEmpty()) {
                            log.warn("Order number not found in existing order data: transactionId={}, orderId={}", 
                                request.paymentGatewayTransactionId(), existingOrderId);
                            // Try alternative field name
                            orderNumberObj = existingOrderData.get("orderNumber");
                            if (orderNumberObj != null) {
                                orderNumber = orderNumberObj.toString();
                            }
                        }
                        
                        if (orderNumber == null || orderNumber.isEmpty()) {
                            log.error("Failed to extract order number from existing order data: transactionId={}, orderId={}, orderData={}", 
                                request.paymentGatewayTransactionId(), existingOrderId, existingOrderData.keySet());
                            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Failed to retrieve order number from existing order");
                        }
                        
                        log.info("Found existing order for payment transaction: transactionId={}, orderId={}, orderNumber={}, paymentId={}, total={}, currency={}", 
                            request.paymentGatewayTransactionId(), existingOrderId, orderNumber, existingPaymentId, total, currency);
                        
                        CheckoutCompleteResponse response = new CheckoutCompleteResponse(
                            existingOrderId,
                            orderNumber,
                            existingPaymentId,
                            total,
                            currency,
                            "PLACED",
                            LocalDateTime.now()
                        );
                        
                        log.info("Successfully returning existing order response: orderId={}", existingOrderId);
                        return response;
                    } catch (BusinessException e) {
                        throw e; // Re-throw business exceptions
                    } catch (Exception e) {
                        log.error("Error constructing response from existing order data: transactionId={}", 
                            request.paymentGatewayTransactionId(), e);
                        throw new BusinessException(ErrorCode.SKU_REQUIRED, 
                            "Failed to retrieve existing order: " + e.getMessage());
                    }
                } else {
                    log.warn("Cart is empty and no existing order found for payment transaction: transactionId={}", 
                        request.paymentGatewayTransactionId());
                    throw new BusinessException(ErrorCode.SKU_REQUIRED, "Cart is empty");
                }
            }
            
            if (cart.items().isEmpty()) {
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Cart is empty");
            }
            
            AddressInfo address = getAddressFromService(request.shippingAddressId(), userId, tenantId);
            
            // Calculate totals
            BigDecimal subtotal = cart.subtotal();
            BigDecimal discountAmount = cart.discountAmount();
            BigDecimal taxAmount = BigDecimal.ZERO;
            BigDecimal shippingCost = calculateShippingCost(address, cart);
            BigDecimal total = subtotal.subtract(discountAmount).add(taxAmount).add(shippingCost);
            
            // Step 2: Reserve inventory
            reservationId = reserveInventory(cart.items(), tenantId);
            log.info("Inventory reserved: reservationId={}", reservationId);
            
            // Step 3: Process/verify payment
            try {
                paymentId = processPayment(userId, tenantId, total, cart.currency(), request.paymentMethodId(), request.paymentGatewayTransactionId());
                log.info("Payment processed: paymentId={}", paymentId);
            } catch (Exception e) {
                log.error("Payment processing failed: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed: " + e.getMessage());
            }
            
            // Step 4: Create order
            String orderNumber = null;
            try {
                OrderCreationResult orderResult = createOrder(userId, tenantId, cart, address, paymentId, subtotal, discountAmount, taxAmount, shippingCost, total);
                orderId = orderResult.orderId();
                orderNumber = orderResult.orderNumber();
                log.info("Order created: orderId={}, orderNumber={}", orderId, orderNumber);
                log.info("DEBUG: orderNumber value before response creation: '{}' (null? {}, empty? {})", 
                    orderNumber, orderNumber == null, orderNumber != null && orderNumber.isEmpty());
            } catch (Exception e) {
                log.error("Order creation failed after payment: paymentId={}, error={}", paymentId, e.getMessage(), e);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed after payment: " + e.getMessage());
            }
            
            // Step 5: Clear cart (non-critical, don't fail if this errors)
            try {
                clearCart(userId, tenantId);
                log.info("Cart cleared successfully");
            } catch (Exception e) {
                log.warn("Cart clearing failed (non-critical): orderId={}, error={}", orderId, e.getMessage());
                // Don't throw - cart clearing is not critical for order completion
            }
            
            // Step 6: Publish OrderCreated event (non-critical, don't fail if this errors)
            try {
                publishOrderCreatedEvent(orderId, userId, tenantId);
                log.info("OrderCreated event published successfully");
            } catch (Exception e) {
                log.warn("Event publishing failed (non-critical): orderId={}, error={}", orderId, e.getMessage());
                // Don't throw - event publishing is not critical for order completion
            }
            
            String finalOrderNumber = orderNumber != null && !orderNumber.isEmpty() ? orderNumber : "N/A";
            log.info("DEBUG: Creating CheckoutCompleteResponse with orderNumber='{}' (original was '{}')", 
                finalOrderNumber, orderNumber);
            
            CheckoutCompleteResponse response = new CheckoutCompleteResponse(
                orderId,
                finalOrderNumber,
                paymentId,
                total,
                cart.currency(),
                "PLACED",
                LocalDateTime.now()
            );
            
            log.info("DEBUG: CheckoutCompleteResponse created - orderId={}, orderNumber={}", 
                response.orderId(), response.orderNumber());
            
            return response;
            
        } catch (BusinessException e) {
            // Re-throw business exceptions as-is (they already have proper error messages)
            log.error("Checkout failed with business exception: {}", e.getMessage(), e);
            
            // Compensation actions (Saga pattern)
            if (orderId != null) {
                // Order was created but something failed after - would need to cancel order
                log.warn("Order created but checkout failed: orderId={}", orderId);
            }
            
            if (paymentId != null && orderId == null) {
                // Only refund if order wasn't created (payment succeeded but order creation failed)
                try {
                    refundPayment(paymentId, tenantId);
                    log.info("Payment refunded: paymentId={}", paymentId);
                } catch (Exception refundError) {
                    log.error("Failed to refund payment: paymentId={}", paymentId, refundError);
                }
            }
            
            if (reservationId != null) {
                // Release inventory reservation
                try {
                    releaseInventory(reservationId, tenantId);
                    log.info("Inventory reservation released: reservationId={}", reservationId);
                } catch (Exception releaseError) {
                    log.error("Failed to release inventory: reservationId={}", reservationId, releaseError);
                }
            }
            
            throw e; // Re-throw the original business exception
        } catch (Exception e) {
            log.error("Checkout failed with unexpected error: {}", e.getMessage(), e);
            
            // Compensation actions (Saga pattern)
            if (orderId != null) {
                // Order was created but something failed after - would need to cancel order
                log.warn("Order created but checkout failed: orderId={}", orderId);
            }
            
            if (paymentId != null && orderId == null) {
                // Only refund if order wasn't created (payment succeeded but order creation failed)
                try {
                    refundPayment(paymentId, tenantId);
                    log.info("Payment refunded: paymentId={}", paymentId);
                } catch (Exception refundError) {
                    log.error("Failed to refund payment: paymentId={}", paymentId, refundError);
                }
            }
            
            if (reservationId != null) {
                // Release inventory reservation
                try {
                    releaseInventory(reservationId, tenantId);
                    log.info("Inventory reservation released: reservationId={}", reservationId);
                } catch (Exception releaseError) {
                    log.error("Failed to release inventory: reservationId={}", reservationId, releaseError);
                }
            }
            
            // Provide more specific error message based on what succeeded
            String errorMessage = "Checkout failed";
            if (paymentId != null && orderId == null) {
                errorMessage = "Payment processed successfully but order creation failed. Please contact support with payment ID: " + paymentId;
            } else if (paymentId != null && orderId != null) {
                errorMessage = "Order created but checkout completion failed. Order ID: " + orderId + ". Please contact support.";
            } else if (reservationId != null) {
                errorMessage = "Inventory reserved but payment processing failed. Please try again.";
            }
            
            throw new BusinessException(ErrorCode.SKU_REQUIRED, errorMessage + ": " + e.getMessage());
        }
    }
    
    @Override
    public void cancelCheckout(UUID userId, UUID tenantId, UUID reservationId) {
        log.info("Cancelling checkout: userId={}, reservationId={}", userId, reservationId);
        
        if (reservationId != null) {
            releaseInventory(reservationId, tenantId);
        }
    }
    
    @Override
    public AddressValidationResponse validateAddress(UUID userId, UUID tenantId, AddressValidationRequest request) {
        log.debug("Validating address: userId={}", userId);
        
        // Basic validation - in production, would call external address validation service
        boolean valid = request.street() != null && !request.street().isEmpty() &&
                       request.city() != null && !request.city().isEmpty() &&
                       request.country() != null && !request.country().isEmpty();
        
        return new AddressValidationResponse(
            valid,
            valid ? null : "Please provide complete address details",
            valid ? "Address is valid" : "Address validation failed"
        );
    }
    
    @Override
    public ShippingCalculationResponse calculateShipping(UUID userId, UUID tenantId, ShippingCalculationRequest request) {
        log.debug("Calculating shipping: userId={}, addressId={}", userId, request.addressId());
        
        // Get address
        AddressInfo address = getAddressFromService(request.addressId(), userId, tenantId);
        
        // Get cart to calculate weight/dimensions
        CartInfo cart = getCartFromService(userId, tenantId);
        
        // Simple shipping calculation (placeholder)
        // In production, would integrate with shipping provider API
        List<ShippingCalculationResponse.ShippingOption> options = new ArrayList<>();
        
        // Standard shipping
        BigDecimal standardCost = calculateStandardShipping(address, cart);
        options.add(new ShippingCalculationResponse.ShippingOption(
            "STANDARD",
            "Standard Shipping",
            5, // estimated days
            standardCost,
            cart.currency()
        ));
        
        // Express shipping
        BigDecimal expressCost = standardCost.multiply(BigDecimal.valueOf(1.5));
        options.add(new ShippingCalculationResponse.ShippingOption(
            "EXPRESS",
            "Express Shipping",
            2, // estimated days
            expressCost,
            cart.currency()
        ));
        
        return new ShippingCalculationResponse(options);
    }
    
    // Helper methods for service calls
    
    private CartInfo getCartFromService(UUID userId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("cart-service", cartServiceUrl);
            String token = getJwtToken();
            
            if (token == null || token.isEmpty()) {
                log.error("JWT token is null or empty when fetching cart. userId={}, tenantId={}", userId, tenantId);
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Authentication token not available");
            }
            
            log.debug("Fetching cart: userId={}, tenantId={}, tokenPresent={}", userId, tenantId, token != null);
            
            ApiResponse<?> response = webClient
                .get()
                .uri("/api/v1/cart")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                log.warn("Cart service returned null response. userId={}, tenantId={}", userId, tenantId);
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Cart not found");
            }
            
            CartInfo cart = parseCartResponse(response.data());
            
            log.debug("Cart fetched: userId={}, itemCount={}, subtotal={}", userId, cart.items().size(), cart.subtotal());
            
            if (cart.items().isEmpty()) {
                log.warn("Cart is empty after fetching from service. userId={}, tenantId={}", userId, tenantId);
            }
            
            return cart;
            
        } catch (WebClientResponseException.NotFound e) {
            log.error("Cart not found (404) for userId={}, tenantId={}", userId, tenantId);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Cart not found. Please add items to your cart.");
        } catch (Exception e) {
            log.error("Error fetching cart: userId={}, tenantId={}", userId, tenantId, e);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Failed to fetch cart: " + e.getMessage());
        }
    }
    
    private AddressInfo getAddressFromService(UUID addressId, UUID userId, UUID tenantId) {
        if (addressId == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address ID is required");
        }
        
        try {
            WebClient webClient = resilientWebClient.create("address-service", addressServiceUrl);
            String token = getJwtToken();
            
            if (token == null) {
                log.error("JWT token is null when fetching address. userId={}, tenantId={}, addressId={}", 
                    userId, tenantId, addressId);
                throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Authentication token not found");
            }
            
            log.debug("Fetching address from address service: addressId={}, userId={}, tenantId={}", 
                addressId, userId, tenantId);
            
            String uri = "/api/v1/address/" + addressId;
            log.debug("Calling address service: GET {}", uri);
            
            ApiResponse<?> response = webClient
                .get()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                log.warn("Address service returned null response: addressId={}", addressId);
                throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found: " + addressId);
            }
            
            return parseAddressResponse(response.data());
            
        } catch (WebClientResponseException.Forbidden e) {
            log.error("403 Forbidden when fetching address. addressId={}, userId={}, tenantId={}, responseBody={}", 
                addressId, userId, tenantId, e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, 
                "Access denied to address: " + addressId + ". Please ensure the address belongs to you.");
        } catch (WebClientResponseException.NotFound e) {
            log.warn("Address not found: addressId={}, userId={}", addressId, userId);
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found: " + addressId);
        } catch (WebClientResponseException e) {
            log.error("Error fetching address from address service. addressId={}, userId={}, status={}, responseBody={}", 
                addressId, userId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, 
                "Failed to fetch address: " + e.getStatusCode() + " - " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error fetching address: addressId={}, userId={}", addressId, userId, e);
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Failed to fetch address: " + e.getMessage());
        }
    }
    
    private UUID reserveInventory(List<CartItemInfo> items, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("inventory-service", inventoryServiceUrl);
            String token = getJwtToken();
            
            if (token == null || token.isEmpty()) {
                log.error("JWT token is null or empty when reserving inventory. tenantId={}", tenantId);
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Authentication token not available");
            }
            
            log.debug("Reserving inventory: items={}, tenantId={}, tokenPresent={}, tokenLength={}", 
                items.size(), tenantId, token != null, token != null ? token.length() : 0);
            log.debug("Token preview (first 50 chars): {}", token != null && token.length() > 50 ? token.substring(0, 50) + "..." : token);
            
            // Build reservation items with dynamically fetched locations
            List<Map<String, Object>> reservationItems = new ArrayList<>();
            UUID tempOrderId = UUID.randomUUID();
            
            for (CartItemInfo item : items) {
                // Fetch locations with stock for this SKU
                UUID locationId = findLocationWithStock(webClient, token, tenantId, item.sku(), item.quantity());
                
                if (locationId == null) {
                    throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, 
                        "No location found with sufficient stock for SKU: " + item.sku());
                }
                
                log.debug("Selected location {} for SKU {} (quantity: {})", locationId, item.sku(), item.quantity());
                
                reservationItems.add(Map.of(
                    "sku", item.sku(),
                    "location_id", locationId.toString(),
                    "quantity", item.quantity()
                ));
            }
            
            // Build reservation request
            Map<String, Object> reservationRequest = Map.of(
                "order_id", tempOrderId.toString(),
                "items", reservationItems
            );
            
            // Call inventory service to reserve
            webClient
                .post()
                .uri("/api/v1/inventory/reserve")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .bodyValue(reservationRequest)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
            log.info("Inventory reserved successfully for order: {}", tempOrderId);
            return tempOrderId;
            
        } catch (WebClientResponseException.Forbidden e) {
            log.error("403 Forbidden when reserving inventory. Response: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Failed to reserve inventory: Authentication failed (403)");
        } catch (BusinessException e) {
            // Re-throw business exceptions as-is
            throw e;
        } catch (Exception e) {
            log.error("Error reserving inventory", e);
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Failed to reserve inventory: " + e.getMessage());
        }
    }
    
    /**
     * Finds a location with sufficient stock for the given SKU and quantity.
     * 
     * @param webClient WebClient instance for inventory service
     * @param token JWT token for authentication
     * @param tenantId Tenant ID
     * @param sku Product SKU
     * @param requiredQuantity Required quantity
     * @return Location ID with sufficient stock, or null if none found
     */
    private UUID findLocationWithStock(WebClient webClient, String token, UUID tenantId, String sku, Integer requiredQuantity) {
        try {
            log.debug("Fetching locations with stock for SKU: {}, required quantity: {}", sku, requiredQuantity);
            
            // Call inventory service to get locations with stock for this SKU
            ApiResponse<List<Map<String, Object>>> response = webClient
                .get()
                .uri("/api/v1/inventory/stock/{sku}/locations", sku)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>>() {})
                .block();
            
            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("No locations found with stock for SKU: {}", sku);
                return null;
            }
            
            // Find first location with sufficient available stock
            for (Map<String, Object> stockInfo : response.data()) {
                Object availableQtyObj = stockInfo.get("available_qty");
                Object locationIdObj = stockInfo.get("location_id");
                
                if (availableQtyObj == null || locationIdObj == null) {
                    continue;
                }
                
                Integer availableQty;
                UUID locationId;
                
                // Handle different number types
                if (availableQtyObj instanceof Number) {
                    availableQty = ((Number) availableQtyObj).intValue();
                } else if (availableQtyObj instanceof String) {
                    availableQty = Integer.parseInt((String) availableQtyObj);
                } else {
                    log.warn("Invalid available_qty type for SKU {}: {}", sku, availableQtyObj.getClass());
                    continue;
                }
                
                // Handle UUID parsing
                if (locationIdObj instanceof UUID) {
                    locationId = (UUID) locationIdObj;
                } else if (locationIdObj instanceof String) {
                    locationId = UUID.fromString((String) locationIdObj);
                } else {
                    log.warn("Invalid location_id type for SKU {}: {}", sku, locationIdObj.getClass());
                    continue;
                }
                
                if (availableQty >= requiredQuantity) {
                    log.debug("Found location {} with sufficient stock: {} >= {}", locationId, availableQty, requiredQuantity);
                    return locationId;
                } else {
                    log.debug("Location {} has insufficient stock: {} < {}", locationId, availableQty, requiredQuantity);
                }
            }
            
            log.warn("No location found with sufficient stock for SKU: {} (required: {})", sku, requiredQuantity);
            return null;
            
        } catch (WebClientResponseException.NotFound e) {
            log.warn("SKU not found in inventory: {}", sku);
            return null;
        } catch (Exception e) {
            log.error("Error fetching locations for SKU: {}", sku, e);
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, 
                "Failed to find location with stock for SKU: " + sku + " - " + e.getMessage());
        }
    }
    
    private void releaseInventory(UUID reservationId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("inventory-service", inventoryServiceUrl);
            String token = getJwtToken();
            
            Map<String, Object> releaseRequest = Map.of(
                "reservation_id", reservationId.toString()
            );
            
            webClient
                .post()
                .uri("/api/v1/inventory/release")
                .header("Authorization", "Bearer " + token)
                .bodyValue(releaseRequest)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
        } catch (Exception e) {
            log.error("Error releasing inventory reservation", e);
            // Don't throw - this is compensation action
        }
    }
    
    private UUID processPayment(UUID userId, UUID tenantId, BigDecimal amount, String currency, UUID paymentMethodId, String paymentGatewayTransactionId) {
        try {
            WebClient webClient = resilientWebClient.create("payment-service", paymentServiceUrl);
            String token = getJwtToken();
            
            if (token == null || token.isEmpty()) {
                log.error("JWT token is null or empty when processing payment. tenantId={}", tenantId);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Authentication token not available");
            }
            
            // Build payment request map
            Map<String, Object> paymentRequestMap = new java.util.HashMap<>();
            paymentRequestMap.put("amount", amount.toString());
            paymentRequestMap.put("currency", currency);
            paymentRequestMap.put("order_id", UUID.randomUUID().toString()); // Temporary order ID
            
            if (paymentMethodId != null) {
                paymentRequestMap.put("payment_method_id", paymentMethodId.toString());
            }
            
            // If payment was processed client-side (e.g., Razorpay), pass the transaction ID for verification
            if (paymentGatewayTransactionId != null && !paymentGatewayTransactionId.isEmpty()) {
                paymentRequestMap.put("payment_gateway_transaction_id", paymentGatewayTransactionId);
                log.info("Verifying client-side payment: transactionId={}, amount={}, currency={}", 
                    paymentGatewayTransactionId, amount, currency);
            }
            
            Map<String, Object> paymentRequest = Map.copyOf(paymentRequestMap);
            
            log.debug("Processing payment: userId={}, tenantId={}, transactionId={}", 
                userId, tenantId, paymentGatewayTransactionId);
            
            ApiResponse<?> response = webClient
                .post()
                .uri("/api/v1/payment/process")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .bodyValue(paymentRequest)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed: No response from payment service");
            }
            
            // Extract payment ID from response
            @SuppressWarnings("unchecked")
            Map<String, Object> paymentData = (Map<String, Object>) response.data();
            
            // Try different field names (id, payment_id, paymentId)
            Object paymentIdObj = paymentData.get("id");
            if (paymentIdObj == null) {
                paymentIdObj = paymentData.get("payment_id");
            }
            if (paymentIdObj == null) {
                paymentIdObj = paymentData.get("paymentId");
            }
            
            if (paymentIdObj == null) {
                log.error("Payment response missing ID field. Response data: {}", paymentData);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed: Payment ID not found in response");
            }
            
            UUID paymentId = UUID.fromString(paymentIdObj.toString());
            log.info("Payment processed successfully: paymentId={}, transactionId={}", 
                paymentId, paymentGatewayTransactionId);
            
            return paymentId;
            
        } catch (WebClientRequestException e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException || 
                e.getCause() instanceof io.netty.handler.timeout.ReadTimeoutException) {
                log.error("Payment service timeout: transactionId={}, error={}", 
                    paymentGatewayTransactionId, e.getMessage());
                throw new BusinessException(ErrorCode.SKU_REQUIRED, 
                    "Payment verification timed out. The payment may still be processing. Please check your payment status.");
            }
            log.error("Payment service request error: transactionId={}", paymentGatewayTransactionId, e);
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed: " + e.getMessage());
        } catch (WebClientResponseException e) {
            log.error("Payment service HTTP error: status={}, transactionId={}, response={}", 
                e.getStatusCode(), paymentGatewayTransactionId, e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SKU_REQUIRED, 
                "Payment processing failed: " + e.getStatusCode() + " - " + e.getMessage());
        } catch (Exception e) {
            log.error("Error processing payment: transactionId={}", paymentGatewayTransactionId, e);
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed: " + e.getMessage());
        }
    }
    
    private void refundPayment(UUID paymentId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("payment-service", paymentServiceUrl);
            String token = getJwtToken();
            
            Map<String, Object> refundRequest = Map.of(
                "payment_id", paymentId.toString(),
                "reason", "Checkout failed"
            );
            
            webClient
                .post()
                .uri("/api/v1/payment/refund")
                .header("Authorization", "Bearer " + token)
                .bodyValue(refundRequest)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
        } catch (Exception e) {
            log.error("Error refunding payment", e);
            // Don't throw - this is compensation action
        }
    }
    
    /**
     * Result of order creation containing both orderId and orderNumber
     */
    private record OrderCreationResult(UUID orderId, String orderNumber) {}
    
    private OrderCreationResult createOrder(UUID userId, UUID tenantId, CartInfo cart, AddressInfo address, 
                             UUID paymentId, BigDecimal subtotal, BigDecimal discountAmount, 
                             BigDecimal taxAmount, BigDecimal shippingCost, BigDecimal total) {
        try {
            WebClient webClient = resilientWebClient.create("order-service", orderServiceUrl);
            String token = getJwtToken();
            
            if (token == null || token.isEmpty()) {
                log.error("JWT token is null or empty when creating order. tenantId={}", tenantId);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Authentication token not available");
            }
            
            // Build order items with productName
            List<Map<String, Object>> orderItems = cart.items().stream()
                .map(item -> {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("product_id", item.productId().toString());
                    itemMap.put("sku", item.sku());
                    itemMap.put("product_name", item.name() != null ? item.name() : "Unknown Product");
                    itemMap.put("quantity", item.quantity());
                    itemMap.put("unit_price", item.unitPrice().toString());
                    itemMap.put("total_price", item.totalPrice().toString());
                    return Map.copyOf(itemMap);
                })
                .toList();
            
            // Build order request map
            Map<String, Object> orderRequestMap = new HashMap<>();
            orderRequestMap.put("shipping_address_id", address.id().toString());
            orderRequestMap.put("payment_id", paymentId.toString());
            orderRequestMap.put("items", orderItems);
            orderRequestMap.put("subtotal", subtotal.toString());
            orderRequestMap.put("discount_amount", discountAmount.toString());
            orderRequestMap.put("tax_amount", taxAmount.toString());
            orderRequestMap.put("shipping_cost", shippingCost.toString());
            orderRequestMap.put("total", total.toString());
            orderRequestMap.put("currency", cart.currency());
            
            Map<String, Object> orderRequest = Map.copyOf(orderRequestMap);
            
            log.debug("Creating order: userId={}, tenantId={}, tokenPresent={}, tokenLength={}", 
                userId, tenantId, token != null, token != null ? token.length() : 0);
            
            ApiResponse<?> response = webClient
                .post()
                .uri("/api/v1/order")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .bodyValue(orderRequest)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed");
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> orderData = (Map<String, Object>) response.data();
            
            // DEBUG: Log the order data to see what we're receiving
            log.info("=== ORDER SERVICE RESPONSE DEBUG ===");
            log.info("orderData type: {}", orderData != null ? orderData.getClass().getName() : "null");
            log.info("orderData keys: {}", orderData != null ? orderData.keySet() : "null");
            log.info("orderData full content: {}", orderData);
            if (orderData != null) {
                log.info("order_number value: {}", orderData.get("order_number"));
                log.info("orderNumber value: {}", orderData.get("orderNumber"));
            }
            log.info("===================================");
            
            // Try different field names for order ID
            Object orderIdObj = orderData.get("order_id");
            if (orderIdObj == null) {
                orderIdObj = orderData.get("id");
            }
            if (orderIdObj == null) {
                orderIdObj = orderData.get("orderId");
            }
            
            if (orderIdObj == null) {
                log.error("Order response missing ID field. Response data: {}", orderData);
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed: Order ID not found in response");
            }
            
            UUID orderId = UUID.fromString(orderIdObj.toString());
            
            // Extract order number
            String orderNumber = null;
            Object orderNumberObj = orderData.get("order_number");
            if (orderNumberObj != null) {
                orderNumber = orderNumberObj.toString();
            }
            if (orderNumber == null || orderNumber.isEmpty()) {
                // Try alternative field name
                orderNumberObj = orderData.get("orderNumber");
                if (orderNumberObj != null) {
                    orderNumber = orderNumberObj.toString();
                }
            }
            
            if (orderNumber == null || orderNumber.isEmpty()) {
                log.error("Order response missing order_number field. Response data: {}", orderData.keySet());
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed: Order number not found in response");
            }
            
            return new OrderCreationResult(orderId, orderNumber);
            
        } catch (WebClientResponseException.Forbidden e) {
            log.error("403 Forbidden when creating order. Response: {}", e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed: Authentication failed (403)");
        } catch (Exception e) {
            log.error("Error creating order", e);
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed: " + e.getMessage());
        }
    }
    
    private void clearCart(UUID userId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("cart-service", cartServiceUrl);
            String token = getJwtToken();
            
            webClient
                .delete()
                .uri("/api/v1/cart")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
        } catch (Exception e) {
            log.error("Error clearing cart", e);
            // Don't throw - cart clearing is not critical
        }
    }
    
    private void publishOrderCreatedEvent(UUID orderId, UUID userId, UUID tenantId) {
        try {
            Map<String, Object> event = Map.of(
                "order_id", orderId.toString(),
                "user_id", userId.toString(),
                "tenant_id", tenantId.toString(),
                "timestamp", LocalDateTime.now().toString()
            );
            
            kafkaTemplate.send(ORDER_CREATED_TOPIC, orderId.toString(), event);
            log.info("Published OrderCreated event: orderId={}", orderId);
            
        } catch (Exception e) {
            log.error("Failed to publish OrderCreated event", e);
            // Don't throw - event publishing is not critical for checkout completion
        }
    }
    
    private void validateInventoryAvailability(List<CartItemInfo> items, UUID tenantId) {
        // Placeholder - would call inventory service to validate stock
        log.debug("Validating inventory availability for {} items", items.size());
    }
    
    /**
     * Find order by payment transaction ID (for idempotency)
     * 
     * Strategy: Get payment ID from transaction ID, then directly query order service
     * by payment ID. This is much more efficient than querying a list of orders.
     * 
     * Uses retry mechanism with delay to handle timing issues where order
     * was just created but not yet committed to database.
     * 
     * @return Map containing order data, or null if not found
     */
    private Map<String, Object> findOrderByPaymentTransactionId(UUID userId, UUID tenantId, String paymentGatewayTransactionId) {
        UUID paymentId = getPaymentIdByTransactionId(userId, tenantId, paymentGatewayTransactionId);
        if (paymentId == null) {
            log.debug("No payment found for transaction ID: {}", paymentGatewayTransactionId);
            return null;
        }
        
        log.debug("Found payment ID {} for transaction ID {}, querying order service directly by payment ID", 
            paymentId, paymentGatewayTransactionId);
        
        // Retry mechanism: Try up to 3 times with increasing delays
        // This handles timing issues where order was just created but not yet committed
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Map<String, Object> order = queryOrderByPaymentIdDirect(userId, tenantId, paymentId, attempt);
                if (order != null) {
                    return order;
                }
                
                // If not found and not last attempt, wait before retrying
                if (attempt < 3) {
                    long delayMs = attempt * 200; // 200ms, 400ms delays
                    log.debug("Order not found on attempt {}, waiting {}ms before retry", attempt, delayMs);
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting to retry order lookup", e);
                return null;
            } catch (Exception e) {
                log.warn("Error finding order by payment ID on attempt {}: paymentId={}", attempt, paymentId, e);
                // Continue to next attempt
            }
        }
        
        log.warn("No order found with payment ID after 3 attempts: paymentId={}", paymentId);
        return null;
    }
    
    /**
     * Query order service directly by payment ID
     * This is much more efficient than querying a list and searching through it
     */
    private Map<String, Object> queryOrderByPaymentIdDirect(UUID userId, UUID tenantId, UUID paymentId, int attempt) {
        WebClient webClient = resilientWebClient.create("order-service", orderServiceUrl);
        String token = getJwtToken();
        
        log.debug("Attempt {}: Querying order service directly by payment ID: paymentId={}", attempt, paymentId);
        
        try {
            ApiResponse<?> response = webClient
                .get()
                .uri("/api/v1/order/by-payment/{paymentId}", paymentId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response != null && response.data() != null) {
                Object data = response.data();
                log.debug("Attempt {}: Received response data type: {}, value: {}", attempt, 
                    data.getClass().getName(), data);
                
                Map<String, Object> order;
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> orderMap = (Map<String, Object>) data;
                    order = orderMap;
                } else {
                    // If data is not a Map, try to convert it using ObjectMapper
                    log.warn("Attempt {}: Response data is not a Map, type: {}", attempt, data.getClass().getName());
                    // For now, return null and let retry mechanism handle it
                    return null;
                }
                
                if (order != null && !order.isEmpty()) {
                    Object orderIdObj = order.get("id");
                    log.info("Found order by payment ID on attempt {}: orderId={}, paymentId={}, orderKeys={}", 
                        attempt, orderIdObj, paymentId, order.keySet());
                    return order;
                } else {
                    log.debug("Attempt {}: Order map is null or empty", attempt);
                }
            } else {
                log.debug("Attempt {}: Response or response.data() is null", attempt);
            }
        } catch (Exception e) {
            log.warn("Attempt {}: Error querying order by payment ID: paymentId={}", attempt, paymentId, e);
            // Don't throw - let retry mechanism handle it
        }
        
        return null;
    }
    
    /**
     * Get payment ID by gateway transaction ID
     */
    private UUID getPaymentIdByTransactionId(UUID userId, UUID tenantId, String paymentGatewayTransactionId) {
        try {
            // First, try to process payment which should return existing payment if it exists
            // This is a workaround since we don't have a direct "get payment by transaction ID" endpoint
            WebClient webClient = resilientWebClient.create("payment-service", paymentServiceUrl);
            String token = getJwtToken();
            
            // Call process payment with the transaction ID - it should return existing payment if already processed
            Map<String, Object> paymentRequest = Map.of(
                "payment_gateway_transaction_id", paymentGatewayTransactionId,
                "amount", "0", // Dummy amount for lookup
                "currency", "INR"
            );
            
            try {
                ApiResponse<?> response = webClient
                    .post()
                    .uri("/api/v1/payment/process")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                    .bodyValue(paymentRequest)
                    .retrieve()
                    .bodyToMono(ApiResponse.class)
                    .block();
                
                if (response != null && response.data() != null) {
                    Map<String, Object> paymentData = (Map<String, Object>) response.data();
                    Object paymentIdObj = paymentData.get("id");
                    if (paymentIdObj != null) {
                        if (paymentIdObj instanceof UUID) {
                            return (UUID) paymentIdObj;
                        } else if (paymentIdObj instanceof String) {
                            return UUID.fromString((String) paymentIdObj);
                        }
                    }
                }
            } catch (WebClientResponseException e) {
                // If payment doesn't exist or other error, return null
                log.debug("Payment not found for transaction ID: {}", paymentGatewayTransactionId);
                return null;
            }
            
            return null;
        } catch (Exception e) {
            log.warn("Error getting payment ID by transaction ID: transactionId={}", paymentGatewayTransactionId, e);
            return null;
        }
    }
    
    /**
     * Get order details by order ID
     */
    private Map<String, Object> getOrderDetails(UUID orderId, UUID userId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("order-service", orderServiceUrl);
            String token = getJwtToken();
            
            ApiResponse<?> response = webClient
                .get()
                .uri("/api/v1/order/{orderId}", orderId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response != null && response.data() != null) {
                return (Map<String, Object>) response.data();
            }
            
            return Map.of();
        } catch (Exception e) {
            log.warn("Error getting order details: orderId={}", orderId, e);
            return Map.of();
        }
    }
    
    private BigDecimal calculateShippingCost(AddressInfo address, CartInfo cart) {
        // Simple calculation - in production would use shipping provider API
        return BigDecimal.valueOf(10.00); // Fixed $10 shipping
    }
    
    private BigDecimal calculateStandardShipping(AddressInfo address, CartInfo cart) {
        return BigDecimal.valueOf(10.00);
    }
    
    private String getJwtToken() {
        // First try to use the token passed from controller
        if (currentJwtToken != null && !currentJwtToken.isEmpty()) {
            return currentJwtToken;
        }
        
        // Fallback to SecurityContext
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            String token = jwtToken.getToken();
            if (token == null || token.isEmpty()) {
                log.warn("JWT token is null or empty in SecurityContext");
            }
            return token;
        }
        log.warn("Authentication is not JwtAuthenticationToken. Type: {}, Principal: {}", 
            authentication != null ? authentication.getClass().getName() : "null",
            authentication != null ? authentication.getPrincipal() : "null");
        return null;
    }
    
    // Helper record classes for parsing responses
    
    private record CartInfo(
        List<CartItemInfo> items,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        String currency
    ) {}
    
    private record CartItemInfo(
        UUID productId,
        String name,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice
    ) {}
    
    private record AddressInfo(
        UUID id,
        String line1,
        String city,
        String state,
        String postcode,
        String country
    ) {}
    
    @SuppressWarnings("unchecked")
    private CartInfo parseCartResponse(Object data) {
        Map<String, Object> cartMap = (Map<String, Object>) data;
        
        // Handle items - might be null or empty
        Object itemsObj = cartMap.get("items");
        List<CartItemInfo> items = new ArrayList<>();
        
        if (itemsObj != null) {
            if (itemsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> itemsList = (List<Map<String, Object>>) itemsObj;
                items = itemsList.stream()
                    .map(item -> {
                        try {
                            return new CartItemInfo(
                UUID.fromString(item.get("product_id").toString()),
                                (String) item.getOrDefault("name", "Unknown Product"),
                (String) item.get("sku"),
                                item.get("quantity") instanceof Integer ? 
                                    (Integer) item.get("quantity") : 
                                    Integer.parseInt(item.get("quantity").toString()),
                new BigDecimal(item.get("unit_price").toString()),
                new BigDecimal(item.get("total_price").toString())
                            );
                        } catch (Exception e) {
                            log.error("Error parsing cart item: {}", item, e);
                            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Invalid cart item format: " + e.getMessage());
                        }
                    })
            .toList();
            } else {
                log.warn("Cart items is not a List, type: {}", itemsObj.getClass().getName());
            }
        } else {
            log.warn("Cart items is null in response");
        }
        
        // Handle subtotal and discount_amount - might be null
        BigDecimal subtotal = BigDecimal.ZERO;
        if (cartMap.get("subtotal") != null) {
            subtotal = new BigDecimal(cartMap.get("subtotal").toString());
        }
        
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (cartMap.get("discount_amount") != null) {
            discountAmount = new BigDecimal(cartMap.get("discount_amount").toString());
        }
        
        String currency = (String) cartMap.getOrDefault("currency", "INR");
        
        log.debug("Parsed cart: itemCount={}, subtotal={}, discountAmount={}, currency={}", 
            items.size(), subtotal, discountAmount, currency);
        
        return new CartInfo(
            items,
            subtotal,
            discountAmount,
            currency
        );
    }
    
    @SuppressWarnings("unchecked")
    private AddressInfo parseAddressResponse(Object data) {
        Map<String, Object> addressMap = (Map<String, Object>) data;
        return new AddressInfo(
            UUID.fromString(addressMap.get("id").toString()),
            (String) addressMap.get("line1"),
            (String) addressMap.get("city"),
            (String) addressMap.getOrDefault("state", ""),
            (String) addressMap.get("postcode"),
            (String) addressMap.get("country")
        );
    }
}

