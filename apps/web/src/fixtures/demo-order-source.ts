import type { SourceRange } from "@/lib/api";

export const demoOrderSourcePath = "src/main/java/demo/LegacyOrderApp.java";

export const demoOrderSource = `package demo;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

final class LegacyOrderApp {
  private final Map<String, String> payments = new HashMap<>();

  private void main(Order order) {
    processOrder(order);
  }

  private void processOrder(Order order) {
    if (!validateOrder(order)) {
      rejectOrder(order);
      return;
    }
    saveOrder(order);
    processPayment(order);
    sendNotification(order);
  }

  private boolean validateOrder(Order order) {
    return order != null && order.id() != null && !order.items().isEmpty();
  }

  private void rejectOrder(Order order) {
    System.out.println("Order rejected: " + order.id());
  }

  private void saveOrder(Order order) {
    System.out.println("Order saved: " + order.id());
  }

  private void processPayment(Order order) {
    BigDecimal total = calculateTotal(order);
    if (!isPaymentProviderAvailable()) {
      recordPaymentFailure(order.id());
      return;
    }
    chargePayment(order.id(), total);
  }

  private BigDecimal calculateTotal(Order order) {
    return order.items().stream()
        .map(LineItem::subtotal)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private boolean isPaymentProviderAvailable() {
    return true;
  }

  private void chargePayment(String orderId, BigDecimal total) {
    String previous = findPreviousPayment(orderId);
    if (previous != null) {
      return;
    }
    String response = requestPaymentProvider(orderId, total);
    recordPayment(orderId, response);
  }

  private String findPreviousPayment(String orderId) {
    return payments.get(orderId);
  }

  private String requestPaymentProvider(String orderId, BigDecimal total) {
    return "approved:" + orderId + ":" + total;
  }

  private void recordPayment(String orderId, String response) {
    payments.put(orderId, response);
  }

  private void recordPaymentFailure(String orderId) {
    payments.put(orderId, "failed");
  }

  private void sendNotification(Order order) {
    System.out.println("Order processed: " + order.id());
  }
}
`;

export function demoOrderMethodRange(methodName: string): SourceRange | undefined {
  const signature = new RegExp(`^[ \\t]*(?:public|private|protected)[ \\t]+[^\\n]*\\b${methodName}[ \\t]*\\(`, "m");
  const match = signature.exec(demoOrderSource);
  if (!match) return undefined;

  const start = demoOrderSource.lastIndexOf("\n", match.index) + 1;
  const openBrace = demoOrderSource.indexOf("{", match.index + match[0].length);
  if (openBrace < 0) return undefined;

  let depth = 0;
  let end = openBrace;
  for (; end < demoOrderSource.length; end += 1) {
    if (demoOrderSource[end] === "{") depth += 1;
    if (demoOrderSource[end] === "}") {
      depth -= 1;
      if (depth === 0) {
        end += 1;
        break;
      }
    }
  }
  if (depth !== 0) return undefined;

  const line = (offset: number) => demoOrderSource.slice(0, offset).split("\n").length;
  return { start, length: end - start, line: line(start), end_line: line(end - 1) };
}
