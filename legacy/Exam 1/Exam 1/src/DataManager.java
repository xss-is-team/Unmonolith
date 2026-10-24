import Models.Income;
import Models.Stores;
import Models.Purchases;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DataManager {

    private static final String URL =
            "jdbc:sqlite:ExamJava.db";

    private Connection connection;

    public void connect() throws SQLException {
        connection = DriverManager.getConnection(URL);
    }

    public void disconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void addStore(Stores store) throws SQLException {
        String sql =
                "INSERT INTO Stores(Store_name) VALUES (?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, store.getName());

            ps.executeUpdate();
        }
    }

    public List<Stores> getStores() throws SQLException {

        List<Stores> stores = new ArrayList<>();

        String sql = "SELECT * FROM Stores";

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {

                Stores store = new Stores();

                store.setId(
                        rs.getInt("Store_id"));

                store.setName(
                        rs.getString("Store_name"));

                stores.add(store);
            }
        }

        return stores;
    }

    public Stores getStores(int id) throws SQLException {

        String sql = "SELECT * FROM Stores WHERE Store_id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            try (ResultSet rs = statement.executeQuery()) {

                if (rs.next()) {

                    Stores store = new Stores();

                    store.setId(
                            rs.getInt("Store_id"));

                    store.setName(
                            rs.getString("Store_name"));

                    return store;
                }
            }
        }

        return null;
    }

    public void DeleteStore(int storeId) throws SQLException {

        String sql = "DELETE FROM Stores WHERE Store_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setInt(1, storeId);

            ps.executeUpdate();
        }
    }


    public void addPurchase(Purchases purchase) throws SQLException {

        String sql = """
            INSERT INTO Purchases
            (
                Purchase_date,
                Store_id,
                Product_name,
                Product_price,
                Product_quantity
            )
            VALUES (?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setString(1, purchase.getDate());
            ps.setInt(2, purchase.getStoreId());
            ps.setString(3, purchase.getProductName());
            ps.setInt(4, purchase.getProductPrice());
            ps.setInt(5, purchase.getProductQuantity());

            ps.executeUpdate();
        }
    }

    public List<Purchases> getPurchases() throws SQLException {

        List<Purchases> purchases = new ArrayList<>();

        String sql = "SELECT * FROM Purchases";

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {

                Purchases purchase = new Purchases();

                purchase.setId(
                        rs.getInt("Purchases_id"));

                purchase.setDate(
                        rs.getString("Purchase_date"));

                purchase.setStoreId(
                        rs.getInt("Store_id"));

                purchase.setProductName(
                        rs.getString("Product_name"));

                purchase.setProductPrice(
                        rs.getInt("Product_price"));

                purchase.setProductQuantity(
                        rs.getInt("Product_quantity"));

                purchases.add(purchase);
            }
        }

        return purchases;
    }

    public void DeletePurchase(int purchaseId) throws SQLException {

        String sql = "DELETE FROM Purchases WHERE Purchases_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setInt(1, purchaseId);

            ps.executeUpdate();
        }
    }


    public List<Purchases> getPurchases(int month)
            throws SQLException {

        List<Purchases> purchases = new ArrayList<>();

        String sql = """
                SELECT *
                FROM Purchases
                WHERE CAST(
                    strftime('%m', Purchase_date)
                    AS INTEGER
                ) = ?
                """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setInt(1, month);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    Purchases purchase = new Purchases();

                    purchase.setId(
                            rs.getInt("Purchases_id"));

                    purchase.setDate(
                            rs.getString("Purchase_date"));

                    purchase.setStoreId(
                            rs.getInt("Store_id"));

                    purchase.setProductName(
                            rs.getString("Product_name"));

                    purchase.setProductPrice(
                            rs.getInt("Product_price"));

                    purchase.setProductQuantity(
                            rs.getInt("Product_quantity"));

                    purchases.add(purchase);
                }
            }
        }

        return purchases;
    }

    public void addIncome(Income income) throws SQLException {

        String sql = """
            INSERT INTO Income
            (Money, Date, Description)
            VALUES (?, ?, ?)
            """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setInt(1, income.getMoney());
            ps.setString(2, income.getDate());
            ps.setString(3, income.getDescription());

            ps.executeUpdate();
        }
    }

    public List<Income> getIncomes() throws SQLException {

        List<Income> incomes = new ArrayList<>();

        String sql = "SELECT * FROM Income";

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            while (rs.next()) {

                Income income = new Income();

                income.setId(
                        rs.getInt("Income_id"));

                income.setMoney(
                        rs.getInt("Money"));

                income.setDate(
                        rs.getString("Date"));

                income.setDescription(
                        rs.getString("Description"));

                incomes.add(income);
            }
        }
        return incomes;
    }

    public void DeleteIncome(int incomeId) throws SQLException {

        String sql = "DELETE FROM Income WHERE Income_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setInt(1, incomeId);

            ps.executeUpdate();
        }
    }


    public List<Income> getIncomes(int month) throws SQLException {

        List<Income> incomes = new ArrayList<>();

        String sql = """
            SELECT *
            FROM Income
            WHERE CAST(strftime('%m', Date) AS INTEGER) = ?
            """;

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setInt(1, month);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    Income income = new Income();

                    income.setId(
                            rs.getInt("Income_id"));

                    income.setMoney(
                            rs.getInt("Money"));

                    income.setDate(
                            rs.getString("Date"));

                    income.setDescription(
                            rs.getString("Description"));

                    incomes.add(income);
                }
            }
        }

        return incomes;
    }

}