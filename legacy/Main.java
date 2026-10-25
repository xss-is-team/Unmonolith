import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;

/**
 * Legacy-монолит обработки заказов, анализа транзакций и складского учета.
 * Спецификация: бриф Axenix «Java Blueprints» (слайды 2, 5, 6, 7).
 *
 * Совместимость: Java 1.8 (без lambdas в ядре, без var, без records, классические циклы и коллекции).
 * Структура программы:
 *  1. Парсинг входных данных (Parser phase)
 *  2. Анализ и валидация (Analyzer phase)
 *  3. Оптимизация и сопоставление (Optimizer phase — содержит дефект O(n²) со слайда 5)
 *  4. Форматированный консольный вывод (Output phase)
 */
public class Main {

    // =========================================================================
    // 0. Интерфейсы и базовые сущности предметной области
    // =========================================================================

    public interface Identifiable {
        String getId();
    }

    public interface Formattable {
        String toDisplayString();
    }

    public interface OrderFilter {
        boolean accept(Order order);
    }

    /** Базовое проверяемое исключение приложения */
    public static class AppException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int errorCode;

        public AppException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public AppException(String message, int errorCode, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }

    /** Ошибка валидации структуры данных */
    public static class ValidationException extends AppException {
        private static final long serialVersionUID = 1L;

        public ValidationException(String message) {
            super(message, 400);
        }
    }

    /** Сущность не найдена */
    public static class EntityNotFoundException extends AppException {
        private static final long serialVersionUID = 1L;

        public EntityNotFoundException(String entityType, String id) {
            super("Сущность " + entityType + " с ID '" + id + "' не найдена", 404);
        }
    }

    // =========================================================================
    // 1. Модели данных (Java 1.8 POJO)
    // =========================================================================

    public static class Item implements Identifiable, Formattable {
        private final String id;
        private String name;
        private String category;
        private double price;
        private int quantity;

        public Item(String id, String name, String category, double price, int quantity) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.price = price;
            this.quantity = quantity;
        }

        @Override
        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCategory() {
            return category;
        }

        public double getPrice() {
            return price;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public void mergeWith(Item other) {
            if (other != null && this.id.equals(other.id)) {
                this.quantity += other.quantity;
                if (other.price > 0) {
                    this.price = Math.max(this.price, other.price);
                }
            }
        }

        @Override
        public String toDisplayString() {
            return String.format("[%s] %s (%s) — %.2f руб. x %d шт.", id, name, category, price, quantity);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Item item = (Item) o;
            return Objects.equals(id, item.id);
        }

        @Override
        public int hashCode() {
            return id != null ? id.hashCode() : 0;
        }
    }

    public static class Customer implements Identifiable {
        private final String id;
        private final String fullName;
        private final String email;
        private final String tier;

        public Customer(String id, String fullName, String email, String tier) {
            this.id = id;
            this.fullName = fullName;
            this.email = email;
            this.tier = tier != null ? tier : "STANDARD";
        }

        @Override
        public String getId() {
            return id;
        }

        public String getFullName() {
            return fullName;
        }

        public String getEmail() {
            return email;
        }

        public String getTier() {
            return tier;
        }

        public double getDiscountFactor() {
            if ("VIP".equalsIgnoreCase(tier)) {
                return 0.85;
            } else if ("PREMIUM".equalsIgnoreCase(tier)) {
                return 0.90;
            } else {
                return 1.00;
            }
        }
    }

    public static class OrderLine {
        private final String itemId;
        private final int quantity;
        private final double unitPrice;

        public OrderLine(String itemId, int quantity, double unitPrice) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        public String getItemId() {
            return itemId;
        }

        public int getQuantity() {
            return quantity;
        }

        public double getUnitPrice() {
            return unitPrice;
        }

        public double getTotalPrice() {
            return quantity * unitPrice;
        }
    }

    public static class Order implements Identifiable, Formattable {
        private final String id;
        private final String customerId;
        private final List<OrderLine> lines;
        private String status;
        private double totalAmount;

        public Order(String id, String customerId) {
            this.id = id;
            this.customerId = customerId;
            this.lines = new ArrayList<OrderLine>();
            this.status = "NEW";
            this.totalAmount = 0.0;
        }

        @Override
        public String getId() {
            return id;
        }

        public String getCustomerId() {
            return customerId;
        }

        public List<OrderLine> getLines() {
            return lines;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getTotalAmount() {
            return totalAmount;
        }

        public void addLine(OrderLine line) {
            if (line != null) {
                this.lines.add(line);
                this.totalAmount += line.getTotalPrice();
            }
        }

        @Override
        public String toDisplayString() {
            return String.format("Заказ #%s (Клиент: %s, Позиций: %d, Итого: %.2f руб., Статус: %s)",
                    id, customerId, lines.size(), totalAmount, status);
        }
    }

    public static class Transaction implements Identifiable {
        private final String id;
        private final String orderId;
        private final double amount;
        private final String type;
        private final boolean success;

        public Transaction(String id, String orderId, double amount, String type, boolean success) {
            this.id = id;
            this.orderId = orderId;
            this.amount = amount;
            this.type = type;
            this.success = success;
        }

        @Override
        public String getId() {
            return id;
        }

        public String getOrderId() {
            return orderId;
        }

        public double getAmount() {
            return amount;
        }

        public String getType() {
            return type;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    // =========================================================================
    // 2. Хранилище состояния монолита (State Storage)
    // =========================================================================

    public static class AppContext {
        public final Map<String, Customer> customers = new HashMap<String, Customer>();
        public final List<Item> inventory = new ArrayList<Item>();
        public final List<Order> orders = new ArrayList<Order>();
        public final List<Transaction> transactions = new ArrayList<Transaction>();
        public final Set<String> blacklistedCustomerIds = new HashSet<String>();
    }

    // =========================================================================
    // 3. Фаза парсинга (Parser Phase)
    // =========================================================================

    public static class InputParser {

        public static Item parseItemCsv(String line) throws ValidationException {
            if (line == null || line.trim().isEmpty()) {
                throw new ValidationException("Пустая строка описания товара");
            }
            String[] parts = line.split(";");
            if (parts.length < 5) {
                throw new ValidationException("Недостаточно полей в описании товара (ожидалось 5): " + line);
            }
            String id = parts[0].trim();
            String name = parts[1].trim();
            String category = parts[2].trim();
            double price;
            int quantity;
            try {
                price = Double.parseDouble(parts[3].trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Некорректная цена товара: " + parts[3]);
            }
            try {
                quantity = Integer.parseInt(parts[4].trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Некорректное количество товара: " + parts[4]);
            }
            return new Item(id, name, category, price, quantity);
        }

        public static Customer parseCustomerCsv(String line) throws ValidationException {
            if (line == null || line.trim().isEmpty()) {
                throw new ValidationException("Пустая строка данных клиента");
            }
            String[] parts = line.split(";");
            if (parts.length < 4) {
                throw new ValidationException("Недостаточно полей в описании клиента (ожидалось 4): " + line);
            }
            return new Customer(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim());
        }

        public static Order parseOrderHeader(String line) throws ValidationException {
            if (line == null || line.trim().isEmpty()) {
                throw new ValidationException("Пустой заголовок заказа");
            }
            String[] parts = line.split(";");
            if (parts.length < 2) {
                throw new ValidationException("Некорректный заголовок заказа: " + line);
            }
            return new Order(parts[0].trim(), parts[1].trim());
        }

        public static OrderLine parseOrderLine(String line) throws ValidationException {
            if (line == null || line.trim().isEmpty()) {
                throw new ValidationException("Пустая строка позиции заказа");
            }
            String[] parts = line.split(";");
            if (parts.length < 3) {
                throw new ValidationException("Некорректная позиция заказа: " + line);
            }
            String itemId = parts[0].trim();
            int qty;
            double price;
            try {
                qty = Integer.parseInt(parts[1].trim());
                price = Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Ошибка числового формата в позиции заказа: " + line);
            }
            return new OrderLine(itemId, qty, price);
        }

        public static Transaction parseTransactionCsv(String line) throws ValidationException {
            if (line == null || line.trim().isEmpty()) {
                throw new ValidationException("Пустая строка транзакции");
            }
            String[] parts = line.split(";");
            if (parts.length < 5) {
                throw new ValidationException("Недостаточно полей транзакции: " + line);
            }
            String id = parts[0].trim();
            String orderId = parts[1].trim();
            double amount;
            try {
                amount = Double.parseDouble(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("Некорректная сумма транзакции: " + parts[2]);
            }
            String type = parts[3].trim();
            boolean success = Boolean.parseBoolean(parts[4].trim()) || "1".equals(parts[4].trim()) || "SUCCESS".equalsIgnoreCase(parts[4].trim());
            return new Transaction(id, orderId, amount, type, success);
        }
    }

    // =========================================================================
    // 4. Фаза анализа и валидации (Analyzer Phase)
    // =========================================================================

    public static class OrderAnalyzer {

        public static double calculateTotalRevenue(List<Order> orders) {
            double total = 0.0;
            for (int i = 0; i < orders.size(); i++) {
                Order order = orders.get(i);
                if ("PAID".equalsIgnoreCase(order.getStatus()) || "COMPLETED".equalsIgnoreCase(order.getStatus())) {
                    total += order.getTotalAmount();
                }
            }
            return total;
        }

        public static Map<String, Integer> countItemsSoldByCategory(List<Order> orders, List<Item> inventory) {
            Map<String, String> itemToCategory = new HashMap<String, String>();
            for (Item it : inventory) {
                itemToCategory.put(it.getId(), it.getCategory());
            }

            Map<String, Integer> categoryCounts = new TreeMap<String, Integer>();
            for (Order o : orders) {
                for (OrderLine line : o.getLines()) {
                    String cat = itemToCategory.get(line.getItemId());
                    if (cat == null) {
                        cat = "UNKNOWN";
                    }
                    Integer current = categoryCounts.get(cat);
                    int updated = (current != null ? current.intValue() : 0) + line.getQuantity();
                    categoryCounts.put(cat, Integer.valueOf(updated));
                }
            }
            return categoryCounts;
        }

        public static List<Order> filterOrders(List<Order> orders, OrderFilter filter) {
            List<Order> result = new ArrayList<Order>();
            for (Order order : orders) {
                if (filter.accept(order)) {
                    result.add(order);
                }
            }
            return result;
        }

        public static boolean isCustomerEligible(Customer customer, Set<String> blacklist) {
            if (customer == null) {
                return false;
            }
            if (blacklist.contains(customer.getId())) {
                return false;
            }
            return true;
        }
    }

    // =========================================================================
    // 5. Фаза оптимизации и алгоритмической обработки (Optimizer Phase)
    // =========================================================================

    public static class DataOptimizer {

        /**
         * Дефект со слайда 5: Квадратичный вложенный перебор списка для объединения одинаковых позиций.
         *
         * Оригинальный вид из брифа:
         *   for (Item a : items) {
         *       for (Item b : items) {
         *           if (a.id().equals(b.id())) {
         *               merge(a, b);
         *           }
         *       }
         *   }
         *
         * Анализатор фиксирует сложность O(n²) и дефект repeated_collection_scan.
         */
        public static List<Item> mergeDuplicateItemsQuadratic(List<Item> items) {
            List<Item> result = new ArrayList<Item>();
            Set<String> processedIds = new HashSet<String>();

            // Классический вложенный цикл O(n²)
            for (Item a : items) {
                if (processedIds.contains(a.getId())) {
                    continue;
                }
                Item combined = new Item(a.getId(), a.getName(), a.getCategory(), a.getPrice(), 0);
                for (Item b : items) {
                    if (a.getId().equals(b.getId())) {
                        combined.setQuantity(combined.getQuantity() + b.getQuantity());
                        if (b.getPrice() > combined.getPrice()) {
                            combined = new Item(combined.getId(), b.getName(), b.getCategory(), b.getPrice(), combined.getQuantity());
                        }
                    }
                }
                result.add(combined);
                processedIds.add(a.getId());
            }
            return result;
        }

        /**
         * Дефект со слайда 5 (вариант сверки): квадратичный поиск соответствия заказов и транзакций.
         */
        public static List<Transaction> reconcileOrdersWithTransactions(List<Order> orders, List<Transaction> transactions) {
            List<Transaction> successfulPayments = new ArrayList<Transaction>();

            for (Order order : orders) {
                for (Transaction tx : transactions) {
                    if (order.getId().equals(tx.getOrderId())) {
                        if (tx.isSuccess() && Math.abs(tx.getAmount() - order.getTotalAmount()) < 0.01) {
                            order.setStatus("PAID");
                            successfulPayments.add(tx);
                        }
                    }
                }
            }
            return successfulPayments;
        }

        /**
         * Метод повторного линейного сканирования (repeated collection scan).
         */
        public static List<Item> filterUnavailableInventory(List<Item> inventory, List<String> discontinuedIds) {
            List<Item> available = new ArrayList<Item>();
            for (Item item : inventory) {
                // Вызов contains() на ArrayList внутри цикла дает O(n * m)
                if (!discontinuedIds.contains(item.getId())) {
                    available.add(item);
                }
            }
            return available;
        }

        /**
         * Оптимизированная версия объединения за O(n) через HashMap (целевая рекомендация).
         */
        public static List<Item> mergeDuplicateItemsOptimized(List<Item> items) {
            Map<String, Item> map = new LinkedHashMap<String, Item>();
            for (Item item : items) {
                Item existing = map.get(item.getId());
                if (existing == null) {
                    map.put(item.getId(), new Item(item.getId(), item.getName(), item.getCategory(), item.getPrice(), item.getQuantity()));
                } else {
                    existing.setQuantity(existing.getQuantity() + item.getQuantity());
                    if (item.getPrice() > existing.getPrice()) {
                        map.put(item.getId(), new Item(item.getId(), item.getName(), item.getCategory(), item.getPrice(), existing.getQuantity()));
                    }
                }
            }
            return new ArrayList<Item>(map.values());
        }
    }

    // =========================================================================
    // 6. Фаза консольного вывода и отчетов (Output Phase)
    // =========================================================================

    public static class OutputFormatter {

        public static void printHeader(PrintStream out, String title) {
            out.println("================================================================================");
            out.println("  " + title);
            out.println("================================================================================");
        }

        public static void printInventoryTable(PrintStream out, List<Item> items) {
            printHeader(out, "СКЛАДСКИЕ ОСТАТКИ ТОВАРОВ");
            out.printf("%-10s | %-25s | %-15s | %-10s | %-8s%n", "ID", "Наименование", "Категория", "Цена", "Кол-во");
            out.println("--------------------------------------------------------------------------------");
            for (Item item : items) {
                out.printf("%-10s | %-25s | %-15s | %10.2f | %8d%n",
                        item.getId(), item.getName(), item.getCategory(), item.getPrice(), item.getQuantity());
            }
            out.println("--------------------------------------------------------------------------------");
            out.printf("Всего позиций на складе: %d%n%n", items.size());
        }

        public static void printOrdersSummary(PrintStream out, List<Order> orders) {
            printHeader(out, "СВОДКА ПО ЗАКАЗАМ");
            double totalRevenue = 0.0;
            int paidCount = 0;
            for (Order o : orders) {
                out.println(o.toDisplayString());
                if ("PAID".equalsIgnoreCase(o.getStatus())) {
                    totalRevenue += o.getTotalAmount();
                    paidCount++;
                }
            }
            out.println("--------------------------------------------------------------------------------");
            out.printf("Всего заказов: %d | Оплачено: %d | Выручка: %.2f руб.%n%n",
                    orders.size(), paidCount, totalRevenue);
        }

        public static void printCategoryStatistics(PrintStream out, Map<String, Integer> stats) {
            printHeader(out, "ПРОДАЖИ ПО КАТЕГОРИЯМ");
            for (Map.Entry<String, Integer> entry : stats.entrySet()) {
                out.printf("  * %-20s : %d шт.%n", entry.getKey(), entry.getValue());
            }
            out.println();
        }
    }

    // =========================================================================
    // 7. Точка входа монолита (CLI Runtime & Menu Loop)
    // =========================================================================

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Scanner scanner = new Scanner(System.in, "UTF-8");
        AppContext context = new AppContext();

        // Загрузка начальных демо-данных склада
        initSeedData(context);

        out.println("Java Blueprints — Консольный монолит обработки заказов v1.0 [Java 1.8]");
        out.println("Введите 'help' для просмотра команд или 'exit' для завершения.");

        boolean running = true;
        while (running) {
            out.print("app> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String commandLine = scanner.nextLine().trim();
            if (commandLine.isEmpty()) {
                continue;
            }

            String[] tokens = commandLine.split("\\s+");
            String command = tokens[0].toLowerCase();

            try {
                if ("exit".equals(command) || "quit".equals(command)) {
                    out.println("Завершение работы программы.");
                    running = false;
                } else if ("help".equals(command)) {
                    printHelp(out);
                } else if ("inventory".equals(command)) {
                    OutputFormatter.printInventoryTable(out, context.inventory);
                } else if ("orders".equals(command)) {
                    OutputFormatter.printOrdersSummary(out, context.orders);
                } else if ("add_item".equals(command)) {
                    // Формат: add_item id;name;category;price;quantity
                    if (tokens.length < 2) {
                        out.println("Использование: add_item <ID;Имя;Категория;Цена;Количество>");
                    } else {
                        String payload = commandLine.substring("add_item".length()).trim();
                        Item item = InputParser.parseItemCsv(payload);
                        context.inventory.add(item);
                        out.println("Товар добавлен: " + item.toDisplayString());
                    }
                } else if ("merge_inventory".equals(command)) {
                    // Запуск того самого дефектного O(n²) метода
                    long startTime = System.currentTimeMillis();
                    List<Item> merged = DataOptimizer.mergeDuplicateItemsQuadratic(context.inventory);
                    long elapsed = System.currentTimeMillis() - startTime;
                    context.inventory.clear();
                    context.inventory.addAll(merged);
                    out.printf("Слияние завершено за %d мс. Уникальных позиций: %d%n", elapsed, context.inventory.size());
                } else if ("add_order".equals(command)) {
                    // Формат: add_order orderId;customerId;itemId:qty:price,itemId:qty:price
                    if (tokens.length < 2) {
                        out.println("Использование: add_order <OrderId;CustomerId;Item1:Qty:Price,...>");
                    } else {
                        handleCreateOrder(commandLine.substring("add_order".length()).trim(), context, out);
                    }
                } else if ("add_tx".equals(command)) {
                    // Формат: add_tx txId;orderId;amount;type;success
                    if (tokens.length < 2) {
                        out.println("Использование: add_tx <TxId;OrderId;Amount;Type;Success>");
                    } else {
                        String payload = commandLine.substring("add_tx".length()).trim();
                        Transaction tx = InputParser.parseTransactionCsv(payload);
                        context.transactions.add(tx);
                        out.println("Транзакция принята: " + tx.getId() + " для заказа #" + tx.getOrderId());
                    }
                } else if ("reconcile".equals(command)) {
                    List<Transaction> matched = DataOptimizer.reconcileOrdersWithTransactions(context.orders, context.transactions);
                    out.printf("Сверка завершена. Успешно оплачено заказов: %d%n", matched.size());
                } else if ("report".equals(command)) {
                    OutputFormatter.printOrdersSummary(out, context.orders);
                    Map<String, Integer> categorySales = OrderAnalyzer.countItemsSoldByCategory(context.orders, context.inventory);
                    OutputFormatter.printCategoryStatistics(out, categorySales);
                } else {
                    out.println("Неизвестная команда '" + command + "'. Введите 'help' для списка.");
                }
            } catch (ValidationException e) {
                out.println("[ОШИБКА ВАЛИДАЦИИ] " + e.getMessage());
            } catch (AppException e) {
                out.println("[ОШИБКА ПРИЛОЖЕНИЯ (" + e.getErrorCode() + ")] " + e.getMessage());
            } catch (Exception e) {
                out.println("[ВНУТРЕННИЙ СБОЙ] " + e.getMessage());
            } finally {
                // Гарантированная очистка/сброс буферов
                out.flush();
            }
        }
    }

    private static void handleCreateOrder(String payload, AppContext context, PrintStream out) throws AppException {
        String[] parts = payload.split(";");
        if (parts.length < 3) {
            throw new ValidationException("Формат заказа: OrderId;CustomerId;ItemId:Qty:Price,...");
        }
        String orderId = parts[0].trim();
        String customerId = parts[1].trim();

        Customer customer = context.customers.get(customerId);
        if (customer != null && !OrderAnalyzer.isCustomerEligible(customer, context.blacklistedCustomerIds)) {
            throw new ValidationException("Клиент '" + customerId + "' находится в черном списке.");
        }

        Order order = new Order(orderId, customerId);
        String linesPart = parts[2].trim();
        String[] rawLines = linesPart.split(",");
        for (String raw : rawLines) {
            String[] fields = raw.split(":");
            if (fields.length < 3) {
                throw new ValidationException("Некорректная позиция в заказе: " + raw);
            }
            String itemId = fields[0].trim();
            int qty = Integer.parseInt(fields[1].trim());
            double price = Double.parseDouble(fields[2].trim());
            order.addLine(new OrderLine(itemId, qty, price));
        }

        context.orders.add(order);
        out.println("Заказ успешно зарегистрирован: " + order.toDisplayString());
    }

    private static void printHelp(PrintStream out) {
        out.println("Доступные команды консоли:");
        out.println("  inventory          - Просмотреть текущий список складских товаров");
        out.println("  orders             - Просмотреть список всех заказов");
        out.println("  add_item <CSV>     - Добавить товар (id;name;category;price;quantity)");
        out.println("  merge_inventory    - Запустить объединение дубликатов склада (алгоритм со слайда 5)");
        out.println("  add_order <CSV>    - Создать заказ (orderId;customerId;item1:qty:price,item2:qty:price)");
        out.println("  add_tx <CSV>       - Добавить платеж (txId;orderId;amount;type;success)");
        out.println("  reconcile          - Сверить оплату заказов с транзакциями");
        out.println("  report             - Вывести итоговый финансовый отчет");
        out.println("  help               - Показать эту справку");
        out.println("  exit               - Завершить сеанс");
    }

    private static void initSeedData(AppContext ctx) {
        ctx.customers.put("CUST-001", new Customer("CUST-001", "Иван Петров", "ivan@example.ru", "VIP"));
        ctx.customers.put("CUST-002", new Customer("CUST-002", "Анна Смирнова", "anna@example.ru", "STANDARD"));
        ctx.customers.put("CUST-003", new Customer("CUST-003", "Сергей Сидоров", "sid@example.ru", "PREMIUM"));

        // Создаем товары с повторяющимися позициями для демонстрации дефекта слияния
        ctx.inventory.add(new Item("ITM-101", "Ноутбук Pro 15", "Электроника", 85000.0, 5));
        ctx.inventory.add(new Item("ITM-102", "Мышь беспроводная", "Аксессуары", 2500.0, 20));
        ctx.inventory.add(new Item("ITM-101", "Ноутбук Pro 15", "Электроника", 85000.0, 3)); // дубликат для merge
        ctx.inventory.add(new Item("ITM-103", "Клавиатура Механика", "Аксессуары", 7200.0, 10));
        ctx.inventory.add(new Item("ITM-102", "Мышь беспроводная", "Аксессуары", 2400.0, 15)); // дубликат для merge
        ctx.inventory.add(new Item("ITM-104", "Монитор 27 4K", "Электроника", 35000.0, 8));

        Order o1 = new Order("ORD-501", "CUST-001");
        o1.addLine(new OrderLine("ITM-101", 1, 85000.0));
        o1.addLine(new OrderLine("ITM-102", 2, 2500.0));
        ctx.orders.add(o1);

        Order o2 = new Order("ORD-502", "CUST-002");
        o2.addLine(new OrderLine("ITM-103", 1, 7200.0));
        ctx.orders.add(o2);
    }
}
