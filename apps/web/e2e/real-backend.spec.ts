import { expect, test } from "@playwright/test";
import { zipSync } from "fflate";

const PROJECT_FILES: Record<string, string> = {
  "src/main/java/shop/Main.java": `package shop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Scanner;
import shop.model.Customer;
import shop.model.Order;
import shop.service.OrderLinker;
public class Main {
  public static void main(String[] args) {
    Scanner in = new Scanner(System.in);
    int n = in.nextInt();
    int m = in.nextInt();
    int mod = in.nextInt();
    List<Order> orders = new ArrayList<Order>();
    List<Customer> customers = new ArrayList<Customer>();
    for (int i = 0; i < n; i++) orders.add(new Order(i, (i * 11 + 1) % (mod + 2)));
    for (int i = 0; i < m; i++) customers.add(new Customer((i * 7 + 3) % mod));
    Map<Integer, Customer> owners = new TreeMap<Integer, Customer>();
    System.out.println(new OrderLinker().link(orders, customers, owners));
  }
}
`,
  "src/main/java/shop/model/Customer.java": `package shop.model;
public class Customer {
  private final int id;
  public Customer(int id) { this.id = id; }
  public int getId() { return id; }
}
`,
  "src/main/java/shop/model/Order.java": `package shop.model;
public class Order {
  private final int id;
  private final int customerId;
  public Order(int id, int customerId) { this.id = id; this.customerId = customerId; }
  public int getId() { return id; }
  public int getCustomerId() { return customerId; }
}
`,
  "src/main/java/shop/service/OrderLinker.java": `package shop.service;
import java.util.List;
import java.util.Map;
import shop.model.Customer;
import shop.model.Order;
public class OrderLinker {
  public int link(List<Order> orders, List<Customer> customers, Map<Integer, Customer> owners) {
    int linked = 0;
    for (Order order : orders) {
      for (Customer customer : customers) {
        if (customer.getId() == order.getCustomerId()) {
          owners.put(order.getId(), customer);
          linked++;
          break;
        }
      }
    }
    return linked;
  }
}
`,
  "src/main/java/calc/Pricing.java": `package calc;
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
`,
  "src/main/java/calc/UnsafeLookup.java": `package calc;
import java.util.List;
public class UnsafeLookup {
  public boolean containsIdentity(List<Object> values, Object target) {
    for (Object value : values) {
      for (Object candidate : values) {
        if (candidate == target && value == candidate) return true;
      }
    }
    return false;
  }
}
`,
  "src/main/java/risk/Item.java": `package risk;
public class Item {
  private final int id;
  private final String code;
  public Item(int id, String code) { this.id = id; this.code = code; }
  public int getId() { return id; }
  public String getCode() { return code; }
}
`,
  "src/main/java/risk/Rules.java": `package risk;
import java.util.List;
import java.util.Map;
public class Rules {
  public int matchByCodeIdentity(List<Item> left, List<Item> right, Map<Integer, Item> out) {
    int count = 0;
    for (Item a : left) {
      for (Item b : right) {
        if (a.getCode() == b.getCode()) {
          out.put(a.getId(), b);
          count++;
          break;
        }
      }
    }
    return count;
  }
}
`,
};

function zipProject(files = PROJECT_FILES) {
  const entries = Object.fromEntries(Object.entries(files).map(([path, source]) => [path, new TextEncoder().encode(source)]));
  return Buffer.from(zipSync(entries, { level: 6 }));
}

async function uploadZip(page: import("@playwright/test").Page, name = "blueprints.zip", files = PROJECT_FILES) {
  await page.goto("/");
  await page.getByLabel("Выбрать файл Java").setInputFiles({ name, mimeType: "application/zip", buffer: zipProject(files) });
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/);
  await expect(page.getByTestId("revision")).toContainText("REV 1");
}

test.describe.configure({ mode: "serial" });

test("real ZIP workflow: tree, graph/CFG, optimization, verification and measured benchmark", async ({ page }) => {
  test.setTimeout(180_000);
  await uploadZip(page);

  await expect(page.getByRole("treeitem", { name: "src" })).toBeVisible();
  await expect(page.getByTestId("file-src/main/java/shop/service/OrderLinker.java")).toBeVisible();
  await page.getByTestId("file-src/main/java/shop/service/OrderLinker.java").click();
  await expect(page.locator(".cm-content")).toContainText("public int link");

  await page.getByRole("button", { name: "Обзор", exact: true }).click();
  await page.getByRole("button", { name: /Выбрать класс shop\.service\.OrderLinker/ }).click();
  await page.getByRole("button", { name: /Открыть CFG метода link/ }).click();
  await expect(page.getByLabel("Граф потока управления")).toBeVisible();
  await expect(page.locator(".cm-content")).toContainText("public int link");

  const optimization = page.getByRole("region", { name: "Оптимизации" });
  const unsupported = optimization.getByRole("button").filter({ hasText: "risk.Rules.matchByCodeIdentity" });
  await expect(unsupported).toBeVisible();
  await unsupported.click();
  await expect(optimization).toContainText("Не применимо");
  await expect(optimization.getByRole("button", { name: "Предпросмотр diff" })).toHaveCount(0);
  const linkProposal = optimization.getByRole("button").filter({ hasText: "OrderLinker.link" });
  await expect(linkProposal).toBeVisible();
  await linkProposal.click();
  await optimization.getByRole("button", { name: "Предпросмотр diff" }).click();
  await expect(optimization.getByLabel("Предпросмотр оптимизации")).toContainText("HashMap");
  await expect(optimization.getByLabel("Предпросмотр оптимизации")).toContainText("OrderLinker.java");
  const assumptions = optimization.getByRole("checkbox");
  await expect(assumptions).toHaveCount(2);
  await assumptions.nth(0).check();
  await assumptions.nth(1).check();
  await optimization.getByRole("button", { name: /Применить · REV 2/ }).click();
  await expect(page.getByTestId("revision")).toContainText("REV 2");
  await page.getByTestId("file-src/main/java/shop/service/OrderLinker.java").click();
  await expect(page.locator(".cm-content")).toContainText("HashMap");

  await page.getByRole("button", { name: "Проверка", exact: true }).click();
  await page.getByLabel("Главный класс Java").fill("shop.Main");
  await page.getByLabel("Имя сценария 1").fill("обычный ввод");
  await page.getByLabel("stdin сценария 1").fill("30\n18\n37\n");
  const verificationPanel = page.locator('[aria-label="Поведенческая проверка"]');
  await expect(verificationPanel).toBeVisible();
  await page.getByRole("button", { name: "Запустить реальные сценарии" }).click();
  await expect(verificationPanel).toBeVisible({ timeout: 60_000 });
  const verification = page.getByRole("region", { name: "Результат функциональной проверки" });
  await expect(verification).toContainText("Сценарии совпали", { timeout: 60_000 });
  await expect(verification).toContainText("REV 1 → REV 2");

  await page.getByRole("button", { name: "Бенчмарк", exact: true }).click();
  await page.getByLabel("Главный класс для бенчмарка").fill("shop.Main");
  await page.getByLabel("Нагрузка").fill("n=1200");
  await page.getByLabel("Размер").fill("1200");
  await page.getByLabel("stdin").fill("1200\n800\n997\n");
  await page.getByRole("button", { name: "Измерить ревизии" }).click();
  const benchmark = page.getByRole("region", { name: "Результат runtime benchmark" });
  await expect(benchmark).toContainText("samples:", { timeout: 120_000 });
  await expect(benchmark).toContainText("Статическая сложность (Big O)");
  await expect(benchmark).toContainText("Функционально:");
  await expect(benchmark).toContainText("JVM");
});

test("real CFG move previews Java, confirms explicitly, and refreshes source and CFG", async ({ page }) => {
  test.setTimeout(120_000);
  await uploadZip(page);
  const priceMethod = page.locator('button[title="calc.Pricing.price(int,int,int)"]');
  await expect(priceMethod).toBeVisible();
  await priceMethod.click();
  await expect(page.getByLabel("Перемещение оператора в исходном коде")).toBeVisible();
  const cfg = page.getByRole("region", { name: "Граф потока управления" });
  const target = page.getByLabel("Целевой оператор");
  await cfg.getByText("int subtotal = base * qty;", { exact: true }).click();
  const dependentTarget = target.locator("option").filter({ hasText: "int total = subtotal + tax - discount;" }).first();
  await target.selectOption({ label: (await dependentTarget.textContent()) ?? "" });
  await page.getByLabel("Позиция перемещения").selectOption("after");
  await page.getByRole("button", { name: "Предпросмотр" }).click();
  const movePanel = page.getByLabel("Перемещение оператора в исходном коде");
  await expect(movePanel.locator('[role="alert"]')).toContainText("depend on each other");

  await cfg.getByText("int tax = base / 5;", { exact: true }).click();
  const bonus = target.locator("option").filter({ hasText: "int bonus = qty * 2;" }).first();
  await target.selectOption({ label: (await bonus.textContent()) ?? "" });
  await page.getByLabel("Позиция перемещения").selectOption("after");
  await page.getByRole("button", { name: "Предпросмотр" }).click();
  await expect(page.getByLabel("Предпросмотр перемещения")).toContainText("int tax = base / 5;");
  await expect(page.getByRole("button", { name: "Подтвердить перемещение" })).toBeVisible();
  await page.getByRole("button", { name: "Подтвердить перемещение" }).click();
  await expect(page.getByTestId("revision")).toContainText("REV 2");
  await page.getByTestId("file-src/main/java/calc/Pricing.java").click();
  const editor = page.locator(".cm-content");
  await expect(editor).toContainText("int bonus = qty * 2;");
  await expect(editor).toContainText("int tax = base / 5;");
  const code = await editor.innerText();
  expect(code.indexOf("int bonus = qty * 2;")).toBeLessThan(code.indexOf("int tax = base / 5;"));
  await expect(page.getByLabel("Граф потока управления")).toBeVisible();
});

test("main_class_required, unsupported proposal, stale revision and real single-file Organizer path", async ({ page }) => {
  test.setTimeout(150_000);
  const multipleMains = {
    "src/main/java/shop/Main.java": `package shop; public class Main { public static void main(String[] args) { System.out.println("main"); } }`,
    "src/main/java/calc/OtherMain.java": `package calc; public class OtherMain { public static void main(String[] args) { System.out.println("other"); } }`,
  };
  await uploadZip(page, "multiple-mains.zip", multipleMains);
  await page.getByRole("button", { name: "Проверка", exact: true }).click();
  await page.getByRole("button", { name: "Запустить реальные сценарии" }).click();
  await expect(page.locator('[role="alert"]').filter({ hasText: "main_class_required" })).toBeVisible();
  await page.getByLabel("Главный класс Java").fill("shop.Main");
  await page.getByRole("button", { name: "Запустить реальные сценарии" }).click();
  await expect(page.getByRole("region", { name: "Результат функциональной проверки" })).toContainText("Сценарии совпали", { timeout: 60_000 });

  const editor = page.locator(".cm-content");
  const externalWrite = await page.evaluate(async (projectId) => {
    const filePath = "src/main/java/shop/Main.java";
    const response = await fetch(`/api/v1/projects/${projectId}/files/${filePath}`);
    const source = await response.text();
    const revision = Number(response.headers.get("X-Source-Revision"));
    const updated = await fetch(`/api/v1/projects/${projectId}/files/${filePath}`, {
      method: "PUT", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ source: `${source}\n// external revision\n`, expected_revision: revision }),
    });
    return updated.status;
  }, new URL(page.url()).pathname.split("/").at(-1)!);
  expect(externalWrite).toBe(200);
  await page.getByRole("button", { name: "Код", exact: true }).click();
  await editor.click();
  await page.keyboard.press("Control+End");
  await page.keyboard.type("\n// local stale edit");
  await page.getByRole("button", { name: "Сохранить" }).click();
  await expect(page.locator('[role="alert"]').filter({ hasText: "Ревизия изменилась на сервере" })).toBeVisible();

  const organizer = await import("node:fs/promises").then((fs) => fs.readFile("../parser/src/test/resources/acceptance/Organizer.java"));
  await page.goto("/");
  await page.getByLabel("Выбрать файл Java").setInputFiles({ name: "Organizer.java", mimeType: "text/x-java-source", buffer: organizer });
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]+$/);
  await expect(page.getByText("Анализ завершён")).toBeVisible({ timeout: 60_000 });
  await expect(page.locator(".cm-content")).toContainText("class Organizer");
  await expect(page.getByRole("button", { name: "CFG", exact: true })).toBeEnabled();
});
