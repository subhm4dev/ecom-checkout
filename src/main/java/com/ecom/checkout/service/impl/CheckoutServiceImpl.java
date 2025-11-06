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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
    
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
        
        // 2. Validate shipping address
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
    public CheckoutCompleteResponse completeCheckout(UUID userId, UUID tenantId, CheckoutRequest request) {
        log.info("Completing checkout: userId={}, shippingAddressId={}", userId, request.shippingAddressId());
        
        UUID reservationId = null;
        UUID paymentId = null;
        UUID orderId = null;
        
        try {
            // Step 1: Validate and prepare
            CartInfo cart = getCartFromService(userId, tenantId);
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
            
            // Step 3: Process payment
            paymentId = processPayment(userId, tenantId, total, cart.currency(), request.paymentMethodId());
            log.info("Payment processed: paymentId={}", paymentId);
            
            // Step 4: Create order
            orderId = createOrder(userId, tenantId, cart, address, paymentId, subtotal, discountAmount, taxAmount, shippingCost, total);
            log.info("Order created: orderId={}", orderId);
            
            // Step 5: Clear cart
            clearCart(userId, tenantId);
            
            // Step 6: Publish OrderCreated event
            publishOrderCreatedEvent(orderId, userId, tenantId);
            
            return new CheckoutCompleteResponse(
                orderId,
                paymentId,
                total,
                cart.currency(),
                "PLACED",
                LocalDateTime.now()
            );
            
        } catch (Exception e) {
            log.error("Checkout failed, rolling back: {}", e.getMessage(), e);
            
            // Compensation actions (Saga pattern)
            if (orderId != null) {
                // Order was created but something failed after - would need to cancel order
                log.warn("Order created but checkout failed: orderId={}", orderId);
            }
            
            if (paymentId != null) {
                // Refund payment
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
            
            throw new BusinessException(ErrorCode.SKU_REQUIRED, "Checkout failed: " + e.getMessage());
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
            
            ApiResponse<?> response = webClient
                .get()
                .uri("/api/v1/cart")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Cart not found");
            }
            
            return parseCartResponse(response.data());
            
        } catch (Exception e) {
            log.error("Error fetching cart", e);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "Failed to fetch cart: " + e.getMessage());
        }
    }
    
    private AddressInfo getAddressFromService(UUID addressId, UUID userId, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("address-service", addressServiceUrl);
            String token = getJwtToken();
            
            ApiResponse<?> response = webClient
                .get()
                .uri("/api/v1/address/{id}", addressId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found: " + addressId);
            }
            
            return parseAddressResponse(response.data());
            
        } catch (WebClientResponseException.NotFound e) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Address not found: " + addressId);
        } catch (Exception e) {
            log.error("Error fetching address", e);
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND, "Failed to fetch address: " + e.getMessage());
        }
    }
    
    private UUID reserveInventory(List<CartItemInfo> items, UUID tenantId) {
        try {
            WebClient webClient = resilientWebClient.create("inventory-service", inventoryServiceUrl);
            String token = getJwtToken();
            
            // Build reservation request
            Map<String, Object> reservationRequest = Map.of(
                "order_id", UUID.randomUUID().toString(), // Temporary order ID
                "items", items.stream()
                    .map(item -> Map.of(
                        "sku", item.sku(),
                        "location_id", UUID.randomUUID().toString(), // Would get from inventory
                        "quantity", item.quantity()
                    ))
                    .toList()
            );
            
            // Call inventory service to reserve
            webClient
                .post()
                .uri("/api/v1/inventory/reserve")
                .header("Authorization", "Bearer " + token)
                .bodyValue(reservationRequest)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
            
            // In production, reservation service would return reservationId
            return UUID.randomUUID();
            
        } catch (Exception e) {
            log.error("Error reserving inventory", e);
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "Failed to reserve inventory: " + e.getMessage());
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
    
    private UUID processPayment(UUID userId, UUID tenantId, BigDecimal amount, String currency, UUID paymentMethodId) {
        try {
            WebClient webClient = resilientWebClient.create("payment-service", paymentServiceUrl);
            String token = getJwtToken();
            
            Map<String, Object> paymentRequest = Map.of(
                "amount", amount.toString(),
                "currency", currency,
                "payment_method_id", paymentMethodId != null ? paymentMethodId.toString() : "",
                "order_id", UUID.randomUUID().toString() // Temporary
            );
            
            ApiResponse<?> response = webClient
                .post()
                .uri("/api/v1/payment/process")
                .header("Authorization", "Bearer " + token)
                .bodyValue(paymentRequest)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Payment processing failed");
            }
            
            // Extract payment ID from response
            @SuppressWarnings("unchecked")
            Map<String, Object> paymentData = (Map<String, Object>) response.data();
            return UUID.fromString(paymentData.get("payment_id").toString());
            
        } catch (Exception e) {
            log.error("Error processing payment", e);
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
    
    private UUID createOrder(UUID userId, UUID tenantId, CartInfo cart, AddressInfo address, 
                             UUID paymentId, BigDecimal subtotal, BigDecimal discountAmount, 
                             BigDecimal taxAmount, BigDecimal shippingCost, BigDecimal total) {
        try {
            WebClient webClient = resilientWebClient.create("order-service", orderServiceUrl);
            String token = getJwtToken();
            
            Map<String, Object> orderRequest = Map.of(
                "user_id", userId.toString(),
                "items", cart.items().stream()
                    .map(item -> Map.of(
                        "product_id", item.productId().toString(),
                        "sku", item.sku(),
                        "quantity", item.quantity(),
                        "unit_price", item.unitPrice().toString(),
                        "total_price", item.totalPrice().toString()
                    ))
                    .toList(),
                "shipping_address_id", address.id().toString(),
                "payment_id", paymentId.toString(),
                "subtotal", subtotal.toString(),
                "discount_amount", discountAmount.toString(),
                "tax_amount", taxAmount.toString(),
                "shipping_cost", shippingCost.toString(),
                "total", total.toString(),
                "currency", cart.currency()
            );
            
            ApiResponse<?> response = webClient
                .post()
                .uri("/api/v1/order")
                .header("Authorization", "Bearer " + token)
                .bodyValue(orderRequest)
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .block();
            
            if (response == null || response.data() == null) {
                throw new BusinessException(ErrorCode.SKU_REQUIRED, "Order creation failed");
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> orderData = (Map<String, Object>) response.data();
            return UUID.fromString(orderData.get("order_id").toString());
            
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
    
    private BigDecimal calculateShippingCost(AddressInfo address, CartInfo cart) {
        // Simple calculation - in production would use shipping provider API
        return BigDecimal.valueOf(10.00); // Fixed $10 shipping
    }
    
    private BigDecimal calculateStandardShipping(AddressInfo address, CartInfo cart) {
        return BigDecimal.valueOf(10.00);
    }
    
    private String getJwtToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            return jwtToken.getToken();
        }
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
        
        List<Map<String, Object>> itemsList = (List<Map<String, Object>>) cartMap.get("items");
        List<CartItemInfo> items = itemsList.stream()
            .map(item -> new CartItemInfo(
                UUID.fromString(item.get("product_id").toString()),
                (String) item.get("name"),
                (String) item.get("sku"),
                (Integer) item.get("quantity"),
                new BigDecimal(item.get("unit_price").toString()),
                new BigDecimal(item.get("total_price").toString())
            ))
            .toList();
        
        return new CartInfo(
            items,
            new BigDecimal(cartMap.get("subtotal").toString()),
            new BigDecimal(cartMap.get("discount_amount").toString()),
            (String) cartMap.get("currency")
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

