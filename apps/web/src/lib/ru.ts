/**
 * Russian wording for the texts the analyzer and the runner produce in English.
 * The backend keeps stable ids and English text for its contract; the UI shows Russian: by id where one exists,
 * by sentence or pattern otherwise, and falls back to the original text for anything unknown.
 */

const quoted = (text: string) => [...text.matchAll(/'([^']+)'/g)].map((match) => match[1]);
const line = (text: string) => /line (\d+)/.exec(text)?.[1] ?? "";

export function ruFinding(finding: { id: string; message: string; explanation: string; recommendation: string }) {
  const names = quoted(`${finding.explanation} ${finding.recommendation}`);
  const name = names[0] ?? "коллекция";
  const row = line(finding.explanation);
  switch (finding.id) {
    case "repeated_collection_scan":
      return {
        title: "Повторный линейный перебор коллекции в цикле",
        explanation: `Цикл по «${name}»${row ? ` (строка ${row})` : ""} ищет подходящий элемент и выполняется на каждой итерации внешнего цикла. Каждый поиск стоит O(n), поэтому вложенная работа растёт как O(n·m), а при переборе одних и тех же данных — как O(n²).`,
        recommendation: /does not stop at the first match/.test(finding.recommendation)
          ? `Внутренний цикл не прерывается на первом совпадении, поэтому один раз перед внешним циклом постройте индекс «${name}» в виде Map<Ключ, List<Элемент>> (группировка по сравниваемому значению). Нужны согласованные equals()/hashCode() у ключей, и «${name}» не должна меняться за это время.`
          : `Один раз перед внешним циклом постройте индекс «${name}» в виде Map<Ключ, Элемент> и ищите по ключу вместо перебора. Нужны согласованные equals()/hashCode() у ключей, и «${name}» не должна меняться за это время.`,
      };
    case "nested_collection_iteration":
      return {
        title: "Вложенный перебор одной и той же коллекции",
        explanation: `Коллекция «${name}» перебирается внутри цикла по ней же, поэтому работа растёт квадратично, O(n²).`,
        recommendation: "Если во внутреннем цикле нужен поиск по значению, заранее постройте индекс (Map или Set) и обращайтесь к нему.",
      };
    case "string_concat_in_loop":
      return {
        title: "Склейка строки в цикле: каждый раз копируется всё накопленное",
        explanation: `Строки неизменяемы: каждая склейка${row ? ` на строке ${row}` : ""} копирует все накопленные символы, поэтому построение результата занимает время, квадратичное по его длине.`,
        recommendation: "Собирайте результат в StringBuilder и превращайте в строку один раз после цикла.",
      };
    default:
      return { title: finding.message, explanation: finding.explanation, recommendation: finding.recommendation };
  }
}

export function ruAssumption(assumption: { id: string; text: string }) {
  const name = quoted(assumption.text)[0] ?? "коллекция";
  switch (assumption.id) {
    case "null_and_exception_timing":
      return `Если элемент «${name}» равен null или его метод ключа бросает исключение, исходный код падает только тогда, когда перебор доходит до этого элемента. Оптимизированный код читает ключ каждого элемента при построении индекса, поэтому может упасть раньше или даже когда внешний цикл не выполнялся.`;
    case "outer_key_non_null":
      return "Ключ поиска не равен null: исходный код вызывает для него equals() и получил бы NullPointerException, а поиск по индексу трактует null как обычный промах.";
    case "single_threaded":
      return `Никакой другой поток не меняет «${name}» и ключи элементов, пока работает внешний цикл.`;
    case "list_not_changed_through_alias":
      return `Никакая другая ссылка на «${name}» не меняет список, пока идёт цикл (это параметр или список покидает метод, поэтому внешний код мог бы держать вторую ссылку).`;
    case "list_not_null_before_loop":
      return `«${name}» не равен null к моменту цикла: множество строится до цикла, а исходный код обращается к списку, только если тело цикла выполняется.`;
    case "string_never_null":
      return `«${name}» не равна null в начале цикла: исходный код напечатал бы «null» и добавленный текст, а new StringBuilder(null) бросает NullPointerException.`;
    default:
      return assumption.text;
  }
}

const PRECONDITIONS: Record<string, string> = {
  collection_not_modified: "Перебираемая коллекция не меняется в цикле",
  collection_reference: "Коллекция — переменная или параметр, а не результат вызова",
  control_flow: "Поток управления цикла позволяет вынести построение индекса",
  insertion_point: "Перед внешним циклом есть место для индекса",
  key_equality: "Сравнение ключей согласовано с equals()/hashCode()",
  key_purity: "Получение ключа не имеет побочных эффектов",
  lookup_shape: "Цикл имеет вид поиска по ключу",
  only_read_in_loop: "В цикле список только читается",
  element_equality_is_consistent: "equals() и hashCode() элементов согласованы",
  loop_in_block: "Цикл — самостоятельный оператор блока",
  no_project_calls_when_aliasable: "Цикл не вызывает методы проекта, способные изменить список",
  only_appended_in_loop: "Внутри цикла строка только дополняется",
  no_observable_partial_result: "Недостроенная строка не видна снаружи (нет try/catch вокруг)",
  operands_append_like_concatenation: "Склеиваемые значения превращаются в текст одинаково",
  string_non_null: "Строка не равна null до цикла",
  not_private: "Тип виден пакету и может стать самостоятельным",
  name_free: "Имя и файл свободны",
  no_private_access_from_outside: "Снаружи не используются приватные члены",
  self_contained: "Не использует члены внешнего класса по простому имени",
  referenced_only_in_this_file: "На тип не ссылаются другие файлы",
  siblings_extractable: "Зависимые типы выносятся вместе с ним",
  no_shared_private_state: "Нет приватного состояния, общего с остальным классом",
  references_repointable: "Все вызовы можно перенаправить на новый класс",
};

export function ruPrecondition(precondition: { id: string; detail: string }) {
  return PRECONDITIONS[precondition.id] ?? precondition.detail;
}

export function ruRuleTitle(rule: string, fallback?: string) {
  const titles: Record<string, string> = {
    nested_scan_to_hashmap: "Заменить вложенный перебор индексом HashMap",
    contains_to_hashset: "Искать в HashSet вместо перебора списка в цикле",
    string_concat_to_builder: "Собирать строку в StringBuilder вместо склейки в цикле",
  };
  return titles[rule] ?? fallback ?? rule;
}

export function ruProposalSummary(proposal: { rule: string; summary?: string; applicability: string; not_applicable_reasons?: string[] }) {
  const names = quoted(proposal.summary ?? "");
  const name = names[0] ?? "коллекция";
  if (proposal.applicability !== "applicable") return "Не применимо: " + (proposal.not_applicable_reasons ?? []).join(" ");
  switch (proposal.rule) {
    case "nested_scan_to_hashmap":
      return `Один раз перед внешним циклом строится HashMap из «${name}», а вместо перебора выполняется поиск по ключу${names[1] ? ` «${names[1]}»` : ""}.`;
    case "contains_to_hashset":
      return `Перед циклом строится HashSet из «${names[1] ?? name}», а contains() вызывается уже у него.`;
    case "string_concat_to_builder":
      return `Перед циклом создаётся StringBuilder, внутри цикла идут append(), а после цикла строка присваивается один раз.`;
    default:
      return proposal.summary ?? "";
  }
}

export function ruComplexityNote(rule: string, note?: string) {
  switch (rule) {
    case "nested_scan_to_hashmap":
      return "n — число итераций внешнего цикла, m — число элементов коллекции. Построение индекса стоит O(m) один раз, поиск по HashMap в среднем O(1) (ожидаемое время, не худший случай). Меняется только заменяемый перебор, остальная стоимость метода остаётся.";
    case "contains_to_hashset":
      return "n — число итераций цикла, m — размер списка. Построение множества стоит O(m) один раз, contains() в среднем O(1) вместо O(m). Меняются только заменяемые поиски.";
    case "string_concat_to_builder":
      return "L — длина итоговой строки, k — число дополнений. Каждая склейка копирует всё накопленное; StringBuilder добавляет в среднем за O(1) на символ. Меняется только этот цикл.";
    default:
      return note ?? "";
  }
}

const SENTENCES: Record<string, string> = {
  "No loop depends on input size; only constant-cost operations were found.": "Нет циклов, зависящих от размера входа: найдены только операции постоянной стоимости.",
  "List.get/set/add are treated as O(1), assuming a random-access list such as ArrayList.": "List.get/set/add считаются O(1) в предположении списка с произвольным доступом (ArrayList).",
  "Hash-based Map/Set operations are treated as O(1) on average.": "Операции хеш-коллекций Map/Set считаются O(1) в среднем.",
  "Static estimate from the source; no execution, no profiling.": "Статическая оценка по исходному коду: без запуска и профилирования.",
  "Loop bounds come from recognised counter, collection-size and midpoint-search patterns; anything else is unknown.": "Границы циклов берутся из распознанных шаблонов (счётчик, размер коллекции, деление пополам); всё остальное — unknown.",
  "A callee is trusted only when it is proven O(1); a callee's cost as a function of its arguments is not tracked.": "Вызываемый метод учитывается, только если доказано O(1); зависимость его стоимости от аргументов не отслеживается.",
  "Standard-library costs assume typical implementations (see assumptions); user-defined generics and streams are unknown.": "Стоимость стандартной библиотеки берётся для типичных реализаций (см. допущения); пользовательские дженерики и стримы — unknown.",
  "Space complexity is not estimated.": "Сложность по памяти не оценивается.",
  "all provided test cases produced matching observations; this is not a proof of behavioral equivalence": "Все заданные сценарии дали одинаковые наблюдаемые результаты; это не доказательство эквивалентности поведения.",
  "one or more provided test cases produced different observable behavior or missed their expectations": "В одном или нескольких сценариях поведение отличается или не совпало с ожиданием.",
  "Identical stdout and outcome on the supplied workloads; this is not a proof of equivalence.": "Вывод и результат совпали на заданных нагрузках; это не доказательство эквивалентности.",
  "Timings are for in-JVM calls of main(String[]) inside a Docker container with 1 CPU and 512 MB; absolute numbers do not transfer to other machines.": "Время измерено для вызовов main(String[]) внутри JVM в Docker-контейнере с 1 CPU и 512 МБ; абсолютные числа нельзя переносить на другие машины.",
  "Warmup iterations are discarded; steady state is not proven. JIT, GC and CPU frequency scaling add noise, which is why a claim needs at least 10 iterations, a significant Mann-Whitney test, a bootstrap interval excluding 1 and separated interquartile ranges.": "Прогревочные итерации отбрасываются, установившийся режим не доказан. JIT, GC и частота CPU добавляют шум, поэтому вывод требует не менее 10 итераций, значимого теста Манна-Уитни, интервала bootstrap без единицы и разделённых межквартильных диапазонов.",
  "Container creation, source copy, javac and harness compilation are timed separately and are not part of algorithm runtime.": "Создание контейнера, копирование исходников, javac и компиляция обвязки измеряются отдельно и не входят во время алгоритма.",
  "Equivalence means identical stdout and identical outcome (return, exit code or exception type and message) on the provided workloads; it is not a proof of equivalence. stderr is not compared.": "Эквивалентность здесь — одинаковый stdout и одинаковый исход (возврат, код выхода или тип и сообщение исключения) на заданных нагрузках; это не доказательство. stderr не сравнивается.",
  "Programs that use threads, wall-clock time, randomness without a fixed seed or files may be reported unstable.": "Программы с потоками, текущим временем, случайностью без фиксированного seed или файлами могут быть признаны нестабильными.",
  "A speedup on the supplied sizes says nothing about other input sizes; use several sizes to see how the difference scales.": "Ускорение на заданных размерах ничего не говорит о других размерах входа; чтобы увидеть рост разницы, возьмите несколько размеров.",
  "With fresh class loaders every iteration includes class loading and static initialisation; JIT-compiled JDK code is shared across iterations, the program's own classes are re-loaded. Shared mode reuses classes but static state persists between iterations.": "С новыми загрузчиками классов каждая итерация включает загрузку классов и статическую инициализацию; JIT-код JDK общий, собственные классы программы загружаются заново. В общем режиме классы переиспользуются, но статическое состояние сохраняется между итерациями.",
  "Static estimates from the source of each revision (jdt-static-cost-model/2); they say nothing about measured time. `unknown` means no bound could be justified.": "Статические оценки по исходнику каждой ревизии (jdt-static-cost-model/2); о измеренном времени они ничего не говорят. `unknown` — граница не обоснована.",
};

const PATTERNS: [RegExp, (match: RegExpExecArray) => string][] = [
  [/^stdout bytes differ$/, () => "Байты stdout различаются"],
  [/^stderr bytes differ$/, () => "Байты stderr различаются"],
  [/^process exit codes differ$/, () => "Коды завершения процессов различаются"],
  [/^timeout states differ$/, () => "Одна из версий превысила лимит времени, другая нет"],
  [/^both executions timed out.*$/, () => "Обе версии превысили лимит времени: поведение нельзя наблюдать полностью"],
  [/^the (baseline|candidate) revision produced different observations across (\d+) runs of the same input.*$/, (m) => `Версия «${m[1] === "baseline" ? "до" : "после"}» давала разный результат на одном и том же вводе (${m[2]} запуска): сравнивать нечего. Скорее всего вывод зависит от времени или случайных чисел.`],
  [/^stdout of the baseline run differs from expected_stdout$/, () => "stdout исходной версии не совпал с ожидаемым"],
  [/^Dominant term (.+?): (\d+) nested loops over (.+?); sequential loops are additive, the largest wins\.$/, (m) => `Доминирующий член ${m[1]}: ${m[2]} вложенных цикла по «${m[3]}»; последовательные циклы складываются, побеждает наибольший.`],
  [/^n = number of elements in (.+)$/, (m) => `n — число элементов в «${m[1]}»`],
  [/^m = number of elements in (.+)$/, (m) => `m — число элементов в «${m[1]}»`],
  [/^Constructor of (.+) only stores its arguments into fields, so it is O\(1\)\.$/, (m) => `Конструктор ${m[1]} только сохраняет аргументы в поля, поэтому он O(1).`],
  [/^samples: (\d+) \/ (\d+); stdin size declared: (.+)$/, (m) => `измерений: ${m[1]} / ${m[2]}; заявленный размер ввода: ${m[3] === "none" ? "не указан" : m[3]}`],
  [/^the medians differ but the evidence is not strong enough for a claim.*$/, () => "Медианы различаются, но данных недостаточно для вывода (значимость, размер эффекта, доверительный интервал и разделение квартилей должны сойтись)."],
  [/^Static estimates from the source of each revision.*$/, () => "Статические оценки по исходнику каждой ревизии; о измеренном времени они ничего не говорят. `unknown` — граница не обоснована."],
];

/** Russian text for a known English sentence or pattern, or the original text. */
export function ruText(text: string | null | undefined): string {
  if (!text) return "";
  if (SENTENCES[text]) return SENTENCES[text];
  for (const [pattern, build] of PATTERNS) {
    const match = pattern.exec(text);
    if (match) return build(match);
  }
  return text;
}

export function ruVerdict(verdict: string) {
  const labels: Record<string, string> = {
    faster: "быстрее",
    slower: "медленнее",
    no_significant_difference: "разницы нет",
    insufficient_evidence: "данных недостаточно",
    not_comparable: "несопоставимо",
  };
  return labels[verdict] ?? verdict;
}

export function ruWarning(warning: { code: string; message: string }) {
  const messages: Record<string, string> = {
    empty_scenarios: "Ни в одном сценарии нет ввода: программа получит пустой stdin, и проверка почти ничего не покажет.",
    no_method_changes: "Между ревизиями не изменился ни один метод, проверять нечего.",
    change_not_reached: "Изменённый метод недостижим из main по статическому графу вызовов: сценарии могут его не выполнять.",
    reachability_unknown: "Не удалось определить, достижим ли изменённый код (нет анализа, графа проекта или main).",
  };
  return messages[warning.code] ?? warning.message;
}

export function ruNotCovered(note: string) {
  if (note.startsWith("inputs other than")) return "Другие входные данные: без аргументов командной строки, переменных окружения, файлов и сети.";
  if (note.startsWith("code that none")) return "Код, до которого не доходит ни один сценарий.";
  if (note.startsWith("timing and resource")) return "Время и ресурсы: сравниваются только вывод, код выхода и лимит времени.";
  if (note.startsWith("non-determinism")) return "Недетерминизм: каждый сценарий запущен один раз, вывод, который меняется от запуска к запуску (время, случайные числа), не заметен. Выберите 2 запуска и больше.";
  if (note.startsWith("absolute correctness")) return "Абсолютная правильность: версии сравниваются только друг с другом, ни один сценарий не задаёт ожидаемый результат.";
  return note;
}
