package shop.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import shop.model.Customer;
import shop.model.Order;

public class OrderLinker {
    /** First customer with a matching id wins; customers without a name map to null. */
    public int link(List<Order> orders, List<Customer> customers, Map<Integer, String> owners) {
        int linked = 0;
        for (Order order : orders) {
            for (Customer customer : customers) {
                if (customer.getId() == order.getCustomerId()) {
                    owners.put(order.getId(), customer.getName());
                    linked++;
                    break;
                }
            }
        }
        return linked;
    }

    /** Every customer with a matching id produces a line, in customer order. */
    public List<String> describeAll(List<Order> orders, List<Customer> customers) {
        List<String> lines = new ArrayList<String>();
        for (Order order : orders) {
            for (Customer customer : customers) {
                if (customer.getId() == order.getCustomerId()) {
                    lines.add(order.getId() + ":" + customer.getName() + ":" + order.getCents());
                }
            }
        }
        return lines;
    }
}
