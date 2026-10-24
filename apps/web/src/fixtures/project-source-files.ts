/** Synthetic multi-file Java sources used only by the standalone project demo. */
export const demoProjectSourceFiles: Record<string, string> = {
  "src/main/java/com/acme/api/OrderController.java": `package com.acme.api;

import com.acme.billing.OrderService;

public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    public String createOrder(String id) {
        return orders.place(id);
    }

    public String getOrder(String id) {
        return orders.find(id);
    }
}`,
  "src/main/java/com/acme/billing/OrderService.java": `package com.acme.billing;

public class OrderService {
    private final PaymentGateway gateway;

    public String place(String id) {
        gateway.charge(id);
        return id;
    }

    public String find(String id) {
        events.publish(id);
        return id;
    }
}`,
  "src/main/java/com/acme/billing/PaymentGateway.java": `package com.acme.billing;

public interface PaymentGateway {
    void charge(String orderId);
}`,
  "src/main/java/com/acme/persistence/JdbcPaymentGateway.java": `package com.acme.persistence;

import com.acme.billing.PaymentGateway;

public class JdbcPaymentGateway implements PaymentGateway {
    @Override
    public void charge(String orderId) {
        // Store a payment record.
    }
}`,
};
