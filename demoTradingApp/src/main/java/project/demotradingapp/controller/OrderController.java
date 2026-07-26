package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import project.demotradingapp.dto.order.CreateOrderRequest;
import project.demotradingapp.dto.order.OrderResponse;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.service.OrderService;

import java.util.List;

@RequestMapping("/api/orders")
@RequiredArgsConstructor
@RestController
public class OrderController {
    private final OrderService orderService;

    // GET    /orders
    @GetMapping("/")
    public ResponseEntity<List<OrderResponse>> getAllOrders(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(orderService.getAllOrders(userAccountDetails.getUser()));
    }

    // POST   /orders
    @PostMapping("/createOrders")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(orderService.placeOrder(request, userAccountDetails.getUser()));
    }
    // GET    /orders/{id}
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long orderId, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(orderService.getOrderById(orderId, userAccountDetails.getUser()));
    }
    // DELETE /orders/{id}
    @DeleteMapping("/deleteOrder/{orderId}")
    public ResponseEntity<Void> deleteOrderById(@PathVariable Long orderId, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        orderService.deleteOrderById(orderId, userAccountDetails.getUser());
        return ResponseEntity.noContent().build();
    }
    // GET /orders/open
    @GetMapping("/open")
    public ResponseEntity<List<OrderResponse>> getOpenOrders(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(orderService.getOpenOrders(userAccountDetails.getUser()));
    }

    // GET /orders/history
    @GetMapping("/history")
    public ResponseEntity<List<OrderResponse>> getOrderHistory(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(orderService.getOrderHistory(userAccountDetails.getUser()));
    }
}
