import Models.Income;
import Models.Purchases;
import Models.Stores;

import java.sql.ClientInfoStatus;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Scanner;

public class UserIntarface {

    public int _balance;

    DataManager db;

    public UserIntarface() throws SQLException {

        db = new DataManager();

        db.connect();

        _balance = 0;

        this.init();
    }

    private void init() throws SQLException {

        int month = LocalDate.now().getMonthValue();

        var incomes = db.getIncomes(month);
        var purchases = db.getPurchases(month);

        Integer AllMoneyMonth = 0;
        Integer AllPurchasesMonth = 0;

        for(Income temp: incomes ){
            AllMoneyMonth += temp.getMoney();
        }

        for(Purchases temp: purchases ){
            AllPurchasesMonth += temp.getProductPrice() * temp.getProductQuantity();
        }

        _balance = AllMoneyMonth - AllPurchasesMonth;

        System.out.println("Текущий баланс: " + _balance);
    }

    private void AddPurchaseModel(String productName, Integer price, Integer quantity, Integer storeId) throws SQLException {

        Purchases purchases = new Purchases();

        LocalDate today = LocalDate.now();

        String date = today.toString();

        purchases.setDate(date);

        purchases.setStoreId(storeId);

        purchases.setProductName(productName);

        purchases.setProductPrice(price);

        purchases.setProductQuantity(quantity);

        db.addPurchase(purchases);

    }

    private void AddIncomeModel(Integer money, String description) throws SQLException {

        Income income = new Income();

        LocalDate today = LocalDate.now();

        String date = today.toString();

        income.setDate(date);

        income.setMoney(money);

        income.setDescription(description);

        db.addIncome(income);

    }

    public void AddIncome(){

        Scanner scanner = new Scanner(System.in);

        System.out.print("Введите сумму дохода: ");
        int money = scanner.nextInt();

        scanner.nextLine();

        System.out.print("Введите описание дохода: ");
        String description = scanner.nextLine();

        try {
            AddIncomeModel(money, description);

            System.out.println("Доход успешно добавлен!");
            this.init();

        } catch (SQLException e) {
            System.out.println("Ошибка при добавлении дохода: " + e.getMessage());
        }
    }

    private void AddStoreModel(String storeName) throws SQLException {

        Stores store = new Stores();

        store.setName(storeName);

        db.addStore(store);
    }

    public void AddStore() {

        Scanner scanner = new Scanner(System.in);

        System.out.print("Введите название магазина: ");
        String storeName = scanner.nextLine();

        try {
            AddStoreModel(storeName);

            System.out.println("Магазин успешно добавлен!");

        } catch (SQLException e) {
            System.out.println("Ошибка при добавлении магазина: " + e.getMessage());
        }
    }

    public void DeleteIncome(){
        try {
            List<Income> incomes = db.getIncomes();

            if (incomes.isEmpty()) {
                System.out.println("Список доходов пуст.");
                return;
            }

            System.out.println("Список доходов:");

            for (Income income : incomes) {
                System.out.println(
                        "ID: " + income.getId() +
                                " | Сумма: " + income.getMoney() +
                                " | Дата: " + income.getDate() +
                                " | Описание: " + income.getDescription()
                );
            }

            Scanner scanner = new Scanner(System.in);

            System.out.print("Введите ID дохода для удаления: ");
            int incomeId = scanner.nextInt();

            db.DeleteIncome(incomeId);

            System.out.println("Доход успешно удалён!");

            this.init();

        } catch (SQLException e) {
            System.out.println("Ошибка при удалении дохода: " + e.getMessage());
        }
    }

    public void DeleteStore(){
        try {
            List<Stores> stores = db.getStores();

            if (stores.isEmpty()) {
                System.out.println("Список магазинов пуст.");
                return;
            }

            System.out.println("Список магазинов:");

            for (Stores store : stores) {
                System.out.println(
                        "ID: " + store.getId() +
                                " | Название: " + store.getName()
                );
            }

            Scanner scanner = new Scanner(System.in);

            System.out.print("Введите ID магазина для удаления: ");
            int storeId = scanner.nextInt();

            db.DeleteStore(storeId);

            System.out.println("Магазин успешно удалён!");


        } catch (SQLException e) {
            System.out.println("Ошибка при удалении магазина: " + e.getMessage());
        }
    }

    public void AddPurchases() throws SQLException {

        Scanner scanner = new Scanner(System.in);

        System.out.print("\n\nВведите название товара: ");
        String productName = scanner.nextLine();

        System.out.print("Введите цену за штуку товара: ");
        int price = scanner.nextInt();

        System.out.print("Введите количество товара: ");
        int quantity = scanner.nextInt();

        var stores = db.getStores();

        if (stores.isEmpty()) {
            System.out.println("Список магазинов пуст!");
            return;
        }

        System.out.println("\nВыберите магазин:");

        for (int i = 0; i < stores.size(); i++) {
            System.out.println(
                    (i + 1) + ". " + stores.get(i).getName()
            );
        }

        System.out.print("\nВведите номер магазина: ");
        int storeChoice;

        while (true) {

            System.out.print("Введите номер магазина: ");
            storeChoice = scanner.nextInt();

            if (storeChoice >= 1 && storeChoice <= stores.size()) {
                break;
            }

            System.out.println("Неверный номер магазина! Попробуйте ещё раз.");
        }

        var selectedStore = stores.get(storeChoice - 1);

        AddPurchaseModel(
                productName,
                price,
                quantity,
                selectedStore.getId()
        );

        System.out.println("Покупка успешно добавлена!");

        this.init();
    }

    public void AllPurchases() throws SQLException {

        System.out.println("\nВсе покупки:");

        var purchases = db.getPurchases();

        Integer counter = 0;

        for(Purchases temp: purchases){
            counter++;

            Integer sum = temp.getProductPrice()*temp.getProductQuantity();

            String storeName = db.getStores(temp.getStoreId()).getName();

            System.out.println(temp.getId() +") Товар: "+ temp.getProductName() + "\n   Цена: "+ sum.toString() + "\n   Дата: " + temp.getDate() + "\n   Магазин: " + storeName + "\n\n");

        }

    }

    public void DeletePurchase() throws SQLException {

        this.AllPurchases();

        Scanner scanner = new Scanner(System.in);

        System.out.print("Введите ID покупки для удаления: ");
        int purchaseId = scanner.nextInt();

        db.DeletePurchase(purchaseId);

        System.out.println("Покупка успешно удалена!");

        this.init();
    }

    public void AllTransaction() throws SQLException {

        System.out.println("\nВсе транзакции:");

        var incomes = db.getIncomes();
        var purchases = db.getPurchases();

        if (incomes.isEmpty() && purchases.isEmpty()) {
            System.out.println("Список транзакций пуст.");
            return;
        }

        System.out.println("\n--- ДОХОДЫ ---");

        for (Income income : incomes) {

            System.out.println(
                    income.getId() + ") Доход: " + income.getMoney() +
                            "\n   Дата: " + income.getDate() +
                            "\n   Описание: " + income.getDescription() +
                            "\n"
            );
        }

        System.out.println("\n--- ПОКУПКИ ---");

        for (Purchases purchase : purchases) {

            Integer sum =
                    purchase.getProductPrice() *
                            purchase.getProductQuantity();

            String storeName =
                    db.getStores(purchase.getStoreId()).getName();

            System.out.println(
                    purchase.getId() + ") Расход: " + sum +
                            "\n   Товар: " + purchase.getProductName() +
                            "\n   Количество: " + purchase.getProductQuantity() +
                            "\n   Дата: " + purchase.getDate() +
                            "\n   Магазин: " + storeName +
                            "\n"
            );
        }
    }


}
