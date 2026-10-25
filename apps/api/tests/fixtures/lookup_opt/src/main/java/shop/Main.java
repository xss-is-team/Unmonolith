package shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

import shop.model.Customer;
import shop.model.Order;
import shop.service.OrderLinker;

/** stdin: orders customers idModulus. A small modulus produces duplicate customer ids. */
public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt();
        int m = in.nextInt();
        int mod = in.nextInt();
        List<Customer> customers = new ArrayList<Customer>();
        for (int i = 0; i < m; i++) {
            customers.add(new Customer((i * 7 + 3) % mod, i % 5 == 4 ? null : "customer-" + i));
        }
        List<Order> orders = new ArrayList<Order>();
        for (int i = 0; i < n; i++) {
            orders.add(new Order(i, (i * 11 + 1) % (mod + 2), i * 3 % 101));
        }
        OrderLinker linker = new OrderLinker();
        Map<Integer, String> owners = new TreeMap<Integer, String>();
        int linked = linker.link(orders, customers, owners);
        List<String> lines = linker.describeAll(orders, customers);
        long hash = 7;
        for (String line : lines) {
            hash = hash * 31 + line.hashCode();
        }
        System.out.println("linked=" + linked + " owners=" + owners.size() + " lines=" + lines.size() + " hash=" + hash);
        if (!lines.isEmpty()) {
            System.out.println("first=" + lines.get(0) + " last=" + lines.get(lines.size() - 1));
        }
        int shown = 0;
        for (Map.Entry<Integer, String> entry : owners.entrySet()) {
            if (shown++ == 4) {
                break;
            }
            System.out.println(entry.getKey() + "->" + entry.getValue());
        }
    }
}
