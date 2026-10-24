import type { ProjectFlow, ProjectFlowCatalog } from "@/lib/project-flow-model";
import { demoOrderMethodRange, demoOrderSourcePath } from "@/fixtures/demo-order-source";

function sourceFor(methodName: string) {
  const range = demoOrderMethodRange(methodName);
  return range ? { file: demoOrderSourcePath, range } : undefined;
}

const demoFlows: Record<string, ProjectFlow> = {
  project: {
    id: "project",
    title: "Обработка заказа",
    scope: "Весь проект",
    description: "Упрощённый обзор демонстрационного Java-монолита. Откройте крупный блок, чтобы перейти к деталям.",
    nodes: [
      { id: "start", label: "Начало", kind: "start", description: "Запуск приложения." },
      { id: "request", label: "Получить заказ", kind: "input_output", description: "Приложение получает данные заказа.", source: sourceFor("main") },
      { id: "orders", label: "Сервис заказов", kind: "subprocess", description: "Проверка и сохранение заказа.", childFlowId: "orders", source: sourceFor("processOrder") },
      { id: "billing", label: "Сервис оплаты", kind: "subprocess", description: "Расчёт суммы и обработка оплаты.", childFlowId: "billing", source: sourceFor("processPayment") },
      { id: "notify", label: "Отправить уведомление", kind: "process", description: "Сообщить клиенту результат.", source: sourceFor("sendNotification") },
      { id: "result", label: "Вернуть результат", kind: "input_output", description: "Вернуть статус заказа вызывающей стороне.", source: sourceFor("processOrder") },
      { id: "end", label: "Конец", kind: "end", description: "Завершение обработки заказа." },
    ],
    edges: [
      { from: "start", to: "request" },
      { from: "request", to: "orders" },
      { from: "orders", to: "billing" },
      { from: "billing", to: "notify" },
      { from: "notify", to: "result" },
      { from: "result", to: "end" },
    ],
  },
  orders: {
    id: "orders",
    title: "Проверить и сохранить заказ",
    scope: "Сервис заказов",
    description: "На этом уровне видны проверки и развилка по результату валидации.",
    nodes: [
      { id: "start", label: "Начало", kind: "start", description: "Получен новый заказ." },
      { id: "validate", label: "Проверить поля заказа", kind: "process", description: "Проверить обязательные поля и состав заказа.", source: sourceFor("validateOrder") },
      { id: "valid", label: "Данные верны?", kind: "decision", description: "Условие отделяет корректные заказы от ошибочных.", source: sourceFor("validateOrder") },
      { id: "reject", label: "Вернуть ошибки проверки", kind: "input_output", description: "Передать клиенту список проблем заказа.", source: sourceFor("rejectOrder") },
      { id: "save", label: "Сохранить заказ", kind: "process", description: "Записать заказ и присвоить ему номер.", source: sourceFor("saveOrder") },
      { id: "payment", label: "Обработать оплату", kind: "subprocess", description: "Перейти к подробной схеме оплаты.", childFlowId: "billing", source: sourceFor("processPayment") },
      { id: "end", label: "Конец", kind: "end", description: "Заказ отклонён или передан в оплату." },
    ],
    edges: [
      { from: "start", to: "validate" },
      { from: "validate", to: "valid" },
      { from: "valid", to: "reject", label: "Нет" },
      { from: "valid", to: "save", label: "Да" },
      { from: "reject", to: "end" },
      { from: "save", to: "payment" },
      { from: "payment", to: "end" },
    ],
  },
  billing: {
    id: "billing",
    title: "Обработать оплату",
    scope: "Сервис оплаты",
    description: "Схему можно раскрыть до конкретной операции, которую выполняет код.",
    nodes: [
      { id: "start", label: "Начало", kind: "start", description: "Получен сохранённый заказ." },
      { id: "total", label: "Рассчитать сумму", kind: "process", description: "Суммировать позиции и применить скидки.", source: sourceFor("calculateTotal") },
      { id: "provider", label: "Выбрать провайдера", kind: "decision", description: "Определить доступный способ оплаты.", source: sourceFor("isPaymentProviderAvailable") },
      { id: "charge", label: "Списать средства", kind: "subprocess", description: "Перейти к схеме операции списания.", childFlowId: "charge", source: sourceFor("chargePayment") },
      { id: "failed", label: "Зафиксировать отказ", kind: "process", description: "Сохранить причину отказа и статус платежа.", source: sourceFor("recordPaymentFailure") },
      { id: "result", label: "Вернуть статус оплаты", kind: "input_output", description: "Передать заказу результат обработки." },
      { id: "end", label: "Конец", kind: "end", description: "Оплата обработана." },
    ],
    edges: [
      { from: "start", to: "total" },
      { from: "total", to: "provider" },
      { from: "provider", to: "charge", label: "Доступен" },
      { from: "provider", to: "failed", label: "Недоступен" },
      { from: "charge", to: "result" },
      { from: "failed", to: "result" },
      { from: "result", to: "end" },
    ],
  },
  charge: {
    id: "charge",
    title: "Списать средства",
    scope: "Операция оплаты",
    description: "Детальный уровень: защита от повторного списания и вызов платёжного провайдера.",
    nodes: [
      { id: "start", label: "Начало", kind: "start", description: "Начать обработку платежа." },
      { id: "idempotency", label: "Найти предыдущую операцию", kind: "process", description: "Проверить ключ идемпотентности.", source: sourceFor("findPreviousPayment") },
      { id: "exists", label: "Платёж уже был?", kind: "decision", description: "Не выполнять повторное списание.", source: sourceFor("chargePayment") },
      { id: "reuse", label: "Вернуть сохранённый ответ", kind: "input_output", description: "Использовать результат предыдущей попытки.", source: sourceFor("chargePayment") },
      { id: "provider", label: "Запросить провайдера", kind: "process", description: "Отправить запрос на списание.", source: sourceFor("requestPaymentProvider") },
      { id: "record", label: "Сохранить результат", kind: "process", description: "Записать ответ провайдера и статус операции.", source: sourceFor("recordPayment") },
      { id: "end", label: "Конец", kind: "end", description: "Вернуть результат списания." },
    ],
    edges: [
      { from: "start", to: "idempotency" },
      { from: "idempotency", to: "exists" },
      { from: "exists", to: "reuse", label: "Да" },
      { from: "exists", to: "provider", label: "Нет" },
      { from: "reuse", to: "end" },
      { from: "provider", to: "record" },
      { from: "record", to: "end" },
    ],
  },
};

export const demoProjectFlowCatalog: ProjectFlowCatalog = {
  rootFlowId: "project",
  flows: demoFlows,
};
