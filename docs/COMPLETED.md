# Java Blueprints — Что уже сделано (Реализованный скоуп)

> **Статус проекта:** 88% обязательного ядра кейса, 100% ключевых архитектурных контрактов.  
> **Дата аудита:** Октябрь 2026 г.  
> **Официальный бриф:** Axenix «Java Blueprints» (Визуальная среда программирования).

Документ содержит исчерпывающее подтверждение всех реализованных требований с прямыми ссылками на исходный код, компоненты и тесты.

---

## 1. Архитектура и Quality Gate (Ключевой контракт)

| Требование брифа | Статус | Где реализовано в коде | Подтверждение и тесты |
|---|:---:|---|---|
| **Клиент-серверная архитектура** (Java как код и как граф) | **100%** | • Фронтенд: [`apps/web`](file:///home/badland/tmp/java-blueprints/apps/web) (Next.js 16, React 19)<br>• Бэкенд: [`apps/api`](file:///home/badland/tmp/java-blueprints/apps/api) (Rust, Axum, Tokio)<br>• Парсер: [`apps/parser`](file:///home/badland/tmp/java-blueprints/apps/parser) (Java 21, Eclipse JDT CLI)<br>• Раннер: [`apps/runner`](file:///home/badland/tmp/java-blueprints/apps/runner) (Docker) | Запуск через единый скрипт [`./dev up`](file:///home/badland/tmp/java-blueprints/dev). Запросы проксируются через Next.js с сохранением Same-Origin сессионных cookie ([`session.rs`](file:///home/badland/tmp/java-blueprints/apps/api/src/session.rs)). |
| **Quality Gate: Сохранение поведения** (*«Сборка + автоматические тесты = поведение сохранено»*) | **100%** | • Раннер: [`apps/api/src/runner.rs`](file:///home/badland/tmp/java-blueprints/apps/api/src/runner.rs)<br>• Докер-образ: [`apps/runner/Dockerfile`](file:///home/badland/tmp/java-blueprints/apps/runner/Dockerfile)<br>• API: `POST /projects/{id}/verify`<br>• UI: вкладка «Проверка» в [`project-workspace.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/project-workspace.tsx#L596) | [`JavaRunner::verify`](file:///home/badland/tmp/java-blueprints/apps/api/src/runner.rs#L125) компилирует baseline и candidate ревизии в изолированном Docker под UID 65532, запускает `stdin`-тесты с таймаутами и побайтово сравнивает `stdout`, `stderr` и `exit_code` ([`compare_observations`](file:///home/badland/tmp/java-blueprints/apps/api/src/runner.rs#L348)). Тесты: 8 тестов в `api.rs` + `real_container_http_verification`. |

---

## 2. Сквозной пайплайн (End-to-End Pipeline)

* **Шаг 01: Загрузить .java (100%):**
  * UI Drag-and-drop: [`upload-panel.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/upload-panel.tsx).
  * Multipart API: эндпоинт `POST /api/v1/projects` в [`api.rs`](file:///home/badland/tmp/java-blueprints/apps/api/src/api.rs#L425) (валидация расширения `.java`, санитизация имени, лимит 2 МиБ, генерация ревизии 1).
* **Шаг 02: Разобрать код (100%):**
  * Eclipse JDT AST: [`Main.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/Main.java#L70), [`AstJson.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/AstJson.java). Полное разрешение типов и связей стандартной библиотеки Java 21 (`setResolveBindings(true)`, `setStatementsRecovery(true)`).
  * Сохранение точных текстовых координат `source_range {start, length, line, end_line}`.
* **Шаг 03: Построить граф (100%):**
  * Внутрипроцедурный CFG: [`CfgBuilder.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/CfgBuilder.java) строит узлы (`entry`, `exit`, `return`, `branch`, `statement`, `switch`, `case`, `try`, `catch`, `finally`, `throw`) и ребра (`flow`, `true_branch`, `false_branch`, `exceptional`).
  * Граф проекта: [`ProjectGraph.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/ProjectGraph.java) строит связи типов (`calls`, `constructs`, `depends_on`, `extends`, `implements`).
  * API: `GET /projects/{id}/methods/{sel}/cfg` и `GET /projects/{id}/graph`. 26 тестов в `CfgTest.java` и `ProjectGraphTest.java`.
* **Шаг 04: Исследовать и править (100% для условий):**
  * Интерактивный холст: [`cfg-viewer.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/cfg-viewer.tsx) и [`project-graph-viewer.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/project-graph-viewer.tsx) на React Flow (`@xyflow/react`).
  * Подсветка связанных строк кода в редакторе CodeMirror при клике на узел.
  * Редактирование условий ветвления (`if`, `while`, `for`, `do`) через инлайн-форму с вызовом `POST /transform`.
  * Двусторонняя синхронизация: правка в CodeMirror с автосохранением (`PUT /source`) вызывает SSE стриминг перепарсинга (`POST /analyze/stream`) и перерисовку графа.
* **Шаг 05: Собрать Java (100%):**
  * Модуль [`Transform.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/Transform.java) применяет правки через `org.eclipse.jdt.core.dom.rewrite.ASTRewrite`.
  * Валидация: результат проверяется повторным прогоном компилятора JDT на отсутствие синтаксических ошибок до записи на диск.
  * Сохранение в строгую ревизию Revision N+1. 19 тестов в `TransformTest.java`.
* **Шаг 06: Запустить и сравнить (100%):**
  * Исполнение в Docker раннере, сравнение вывода и кодов возврата, отображение диффов и бейджей статуса в UI.

---

## 3. Поддержка обязательного Java Scope

* **Раздел 1: Язык и java.lang (100%):**
  * Пользовательские классы, интерфейсы, `record`, `enum`, примитивы, обертки, массивы, касты, арифметические/логические операции.
  * Все конструкции управления: `if/else`, `switch` (со стрелками и pattern matching), `for`, `foreach`, `while`, `do-while`, `break`, `continue`, `return`.
  * Поля, методы, конструкторы, `static`, блоки инициализации.
  * Базовые типы: `Object`, `String`, `Math`, `System`.
  * Проверено на монолите [`Organizer.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/test/resources/acceptance/Organizer.java) (976 строк) и 12 тестах в `SemanticTest.java`.
* **Раздел 2: Консольный ввод-вывод (100%):**
  * `System.in`, `System.out`, `System.err`, `Scanner`, `Formatter`, `String.format`, `printf`.
  * Корректная кириллица UTF-8: `LANG=C.UTF-8` в Dockerfile, `StandardCharsets.UTF_8` в парсере и раннере.
* **Раздел 3: Коллекции java.util (100%):**
  * `Collection`, `Iterable`, `List`, `Set`, `Map` (ArrayList, LinkedList, HashSet, TreeSet, HashMap, TreeMap).
  * `Iterator`, `Collections`, `Arrays`, `Comparator`, `Optional`, фабрики `List.of()`, `Set.of()`, `Map.of()`.
* **Раздел 4: Исключения (100%):**
  * Проверяемые и непроверяемые, `try/catch/finally`, `try-with-resources`, `throw`, кастомные классы исключений.
* **Бонусный уровень (70%):**
  * `record` и `enum` поддержаны на 100% в AST, CFG и графе проекта.
  * Дженерики поддержаны на 100% с выводом типов в AST.
  * `lambdas` и `Stream API` компилируются и исполняются в Temurin 21.

---

## 4. Диагностика дефектов и Big O

* **Математическая модель сложности (100%):**
  * Класс [`ComplexityAnalyzer.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/main/java/org/example/ComplexityAnalyzer.java) использует мономы размерностей (`Mono(TreeMap<String, int[]> vars)`).
  * Корректно вычисляет формулы: $O(1)$, $O(n)$, $O(n^2)$, $O(n \log n)$, $O(n \cdot m)$ для метода и отдельных фрагментов.
* **Детекция алгоритмических дефектов $O(n^2)$ (100%):**
  * Детектор `repeated_collection_scan` (warning) — поиск в цикле по списку (`.contains()`, `.indexOf()`).
  * Детектор `nested_collection_iteration` (info) — квадратичные вложенные циклы.
  * Полная привязка диагноза к строкам (`source_range`) и узлам CFG (`node_ids`).
  * Вывод понятного объяснения, риска и конкретной рекомендации (напр. *«Индексируйте коллекцию через Map/Set для O(1) поиска»*). 17 тестов в `AnalysisTest.java`.
* **Визуальная подсветка:**
  * Узлы с дефектами выделяются розовым контуром `ring-rose-400/30` и бейджем сложности в [`cfg-viewer.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/cfg-viewer.tsx). Клик скроллит редактор к проблемной строке.

---

## 5. Элементы веб-интерфейса (IDE Workspace)

* **Единое рабочее пространство ([`project-workspace.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/project-workspace.tsx)):**
  * Полноценная темная IDE-тема с изменяемыми размерами панелей.
  * Левый сайдбар: список методов монолита с быстрым поиском.
  * Верхние вкладки переключения видов: «Обзор проекта» (граф классов/пакетов) и «CFG» (поток управления выбранного метода).
  * Нижняя док-панель со вкладками:
    * «Код Java» — редактор CodeMirror 6 ([`java-editor.tsx`](file:///home/badland/tmp/java-blueprints/apps/web/src/components/java-editor.tsx)) с gutter-маркерами ошибок компилятора JDT.
    * «Ревизии» — история версий с пофайловым `diff` изменений между ревизиями.
    * «Проверка» — панель тест-сьютов Quality Gate со статусами прохождения и выводом консоли.
* **Чистый код без заглушек:**
  * Удалены синтетические демонстрационные мок-файлы (`/demo`, синтетический `project-graph.json`). Все данные в UI на 100% реальные и поступают из бэкенда.

---

## 6. Тестовая база и стабильность

* **Rust API:** 40 тестов пройдено (0 ошибок) через `cargo test`.
* **Java Parser:** 94 теста пройдено (0 ошибок) через `mvn test`.
* **Интеграционные тесты:** `real_ast_rewrite_http_end_to_end` и `real_container_http_verification` в Docker пройдены успешно.
* **Референсный монолит:** [`apps/parser/src/test/resources/acceptance/Organizer.java`](file:///home/badland/tmp/java-blueprints/apps/parser/src/test/resources/acceptance/Organizer.java) (976 строк).
