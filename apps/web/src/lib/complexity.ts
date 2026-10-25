import type { ComplexityEstimate, ComplexityFragment } from "@/lib/api";

/**
 * Plain-language reading of a Big O estimate. The analyzer names every letter of its formula in the assumptions
 * ("n = number of elements in items"); the letters of a loop fragment are its own, so they are mapped back to the
 * letters of the method by what they stand for.
 */
export type Variable = { letter: string; what: string; source: string };

const VARIABLE = /^([a-z]) = (.+)$/;

function describe(raw: string): { what: string; source: string } {
  let match = /^number of elements in (.+)$/.exec(raw);
  if (match) return { what: `число элементов в «${match[1]}»`, source: match[1] };
  match = /^number of input tokens or lines read from standard input$/.exec(raw);
  if (match) return { what: "число строк или токенов, прочитанных со стандартного ввода", source: "stdin" };
  match = /^initial value of (.+)$/.exec(raw);
  if (match) return { what: `начальное значение «${match[1]}»`, source: match[1] };
  match = /^value of (\S+)(?: \(assumed non-negative\))?$/.exec(raw);
  if (match) return { what: `значение «${match[1]}» (считается неотрицательным)`, source: match[1] };
  match = /^length of (.+)$/.exec(raw);
  if (match) return { what: `длина «${match[1]}»`, source: match[1] };
  return { what: raw, source: raw };
}

export function variablesOf(estimate: { assumptions: string[] }): Variable[] {
  const list: Variable[] = [];
  for (const line of estimate.assumptions) {
    const match = VARIABLE.exec(line);
    if (match) list.push({ letter: match[1], ...describe(match[2]) });
  }
  return list;
}

/** Other assumptions (cost model), without the variable legend. */
export function modelAssumptions(estimate: { assumptions: string[] }) {
  return estimate.assumptions.filter((line) => !VARIABLE.test(line));
}

/** Rewrites the letters of `time` from `from` to `to` (matched by what they stand for); unknown letters stay. */
export function relabel(time: string, from: Variable[], to: Variable[]) {
  const map = new Map<string, string>();
  const spare = "pqrstuvwxyz".split("").filter((letter) => !to.some((item) => item.letter === letter));
  for (const variable of from) {
    const target = to.find((item) => item.source === variable.source);
    map.set(variable.letter, target ? target.letter : (spare.shift() ?? variable.letter));
  }
  return pretty(time.replace(/\b([a-z])\b/g, (letter) => map.get(letter) ?? letter));
}

export function pretty(time: string) {
  return time.replace(/\*/g, "·").replace(/\^2\b/g, "²").replace(/\^3\b/g, "³");
}

function terms(time: string) {
  const inner = /^O\((.*)\)$/.exec(time.trim())?.[1] ?? time;
  return inner.split(/\s\+\s/).map((term) => term.trim());
}

/** One sentence per term of the formula: what grows with what. */
export function readFormula(estimate: ComplexityEstimate): string[] {
  const time = estimate.time;
  if (time === "unknown") return ["Оценку вывести не удалось: в методе есть вызов или цикл, для которого нет модели стоимости (см. «Почему»)."];
  if (time === "O(1)") return ["Время не зависит от размера входных данных: в методе нет циклов по коллекциям или вводу."];
  const variables = variablesOf(estimate);
  const name = (letter: string) => variables.find((item) => item.letter === letter)?.what ?? letter;
  const sentences: string[] = [];
  const parts = terms(time);
  for (const term of parts) {
    const log = /log/.test(term);
    const letters = [...new Set([...term.replace(/log/g, "").matchAll(/\b([a-z])\b/g)].map((m) => m[1]))];
    const square = /\^2/.test(term);
    const cube = /\^3/.test(term);
    if (!letters.length) continue;
    if (cube) sentences.push(`${pretty(term)}: три вложенных цикла по одним данным (${name(letters[0])}); вдвое больше данных — в 8 раз дольше.`);
    else if (square) sentences.push(`${pretty(term)}: вложенные циклы по одним и тем же данным (${name(letters[0])}); вдвое больше данных — в 4 раза дольше.`);
    else if (log && letters.length === 1 && /\*|·/.test(term)) sentences.push(`${pretty(term)}: на каждый элемент (${name(letters[0])}) выполняется логарифмическая работа, как при сортировке.`);
    else if (log) sentences.push(`${pretty(term)}: на каждом шаге область поиска делится пополам (${name(letters[0])}); вдвое больше данных — всего на один шаг дольше.`);
    else if (letters.length > 1) {
      const sources = letters.map((letter) => variables.find((item) => item.letter === letter)?.source ?? letter);
      const chain = sources.slice(1).map((source) => `перебираются все элементы «${source}»`).join(", и для каждого из них ");
      sentences.push(`${pretty(term)}: вложенные циклы — на каждый элемент «${sources[0]}» ${chain}; время растёт как произведение этих размеров (по 10 элементов — уже ${10 ** letters.length} шагов).`);
    }
    else sentences.push(`${pretty(term)}: один проход по данным (${name(letters[0])}); вдвое больше данных — вдвое дольше.`);
  }
  if (parts.length > 1) sentences.push("Слагаемые — это циклы, которые идут друг за другом: их время складывается, а при росте данных главным становится самое большое слагаемое.");
  return sentences;
}

export type Origin = { line: number | null; label: string; time: string; range: ComplexityFragment["source_range"] };

/** The loops of the method that produce the formula, with letters of the method. */
export function originsOf(estimate: ComplexityEstimate, fragments: ComplexityFragment[]): Origin[] {
  const method = variablesOf(estimate);
  const kinds: Record<string, string> = { foreach: "цикл for-each", for: "цикл for", while: "цикл while", do_while: "цикл do-while", recursion: "рекурсия" };
  return fragments
    .filter((fragment) => fragment.time !== "O(1)")
    .map((fragment) => {
      const own = variablesOf(fragment);
      const over = own.map((item) => item.source).filter((source) => source !== "stdin");
      return {
        line: fragment.source_range.line ?? null,
        label: `${kinds[fragment.statement_kind] ?? fragment.kind}${over.length ? ` по «${over.at(-1)}»` : own.some((item) => item.source === "stdin") ? " по строкам ввода" : ""}`,
        time: fragment.time === "unknown" ? "unknown" : relabel(fragment.time, own, method),
        range: fragment.source_range,
      };
    })
    .sort((a, b) => (a.line ?? 0) - (b.line ?? 0));
}
