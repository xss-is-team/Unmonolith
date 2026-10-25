package shop.model;

public class Order {
    private final int id;
    private final int customerId;
    private final int cents;

    public Order(int id, int customerId, int cents) {
        this.id = id;
        this.customerId = customerId;
        this.cents = cents;
    }

    public int getId() {
        return id;
    }

    public int getCustomerId() {
        return customerId;
    }

    public int getCents() {
        return cents;
    }
}
