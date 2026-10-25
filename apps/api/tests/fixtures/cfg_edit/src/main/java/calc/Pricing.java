package calc;

public class Pricing {
    public int price(int base, int qty, int discount) {
        int subtotal = base * qty;
        int tax = base / 5;
        int bonus = qty * 2;
        int total = subtotal + tax - discount;
        return total + bonus;
    }

    public static void main(String[] args) {
        System.out.println(new Pricing().price(100, 3, 7));
    }
}
