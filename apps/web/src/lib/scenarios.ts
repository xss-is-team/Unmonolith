import type { VerifyTestCase } from "@/lib/api";

/**
 * Suggested stdin scenarios for the behaviour check, so that it runs without the user inventing input. The source is
 * only scanned for the words a console program compares its input with (`case "add"`, `"exit".equals(cmd)`,
 * `cmd.equals("list")`); the suggestions prefill editable fields, the verification itself runs the real programs.
 */
const EXIT_WORDS = ["exit", "quit", "q", "выход", "stop", "end", "0"];
const MAX_COMMANDS = 8;

function commandsOf(sources: string[]) {
  const found = new Map<string, number>();
  const patterns = [
    /case\s+"([^"\\\n]{1,24})"\s*(?:->|:|,)/g,
    /"([^"\\\n]{1,24})"\s*\.\s*equals(?:IgnoreCase)?\s*\(/g,
    /\.\s*equals(?:IgnoreCase)?\s*\(\s*"([^"\\\n]{1,24})"\s*\)/g,
  ];
  for (const source of sources) {
    for (const pattern of patterns) {
      for (const match of source.matchAll(pattern)) {
        const word = match[1].trim();
        if (/^[\p{L}\p{N}_-]+$/u.test(word)) found.set(word, (found.get(word) ?? 0) + 1);
      }
    }
  }
  return [...found.keys()];
}

export function suggestScenarios(sources: string[]): VerifyTestCase[] {
  const commands = commandsOf(sources);
  const exit = commands.find((word) => EXIT_WORDS.includes(word.toLowerCase())) ?? null;
  const work = commands.filter((word) => word !== exit).slice(0, MAX_COMMANDS);
  const finish = exit ? `${exit}\n` : "";
  const readsNumbers = sources.some((source) => /\.\s*next(?:Int|Long|Double)\s*\(/.test(source));
  const scenarios: VerifyTestCase[] = [];
  if (work.length) {
    scenarios.push({ name: "Все команды подряд", stdin: work.map((word) => `${word}\n`).join("") + finish });
    for (const word of work.slice(0, 5)) scenarios.push({ name: `Команда «${word}»`, stdin: `${word}\n${finish}` });
    scenarios.push({ name: "Неизвестная команда", stdin: `no_such_command\n${finish}` });
  } else if (readsNumbers) {
    scenarios.push({ name: "Небольшие числа", stdin: "5\n3\n7\n" }, { name: "Крупные числа", stdin: "200\n150\n97\n" });
  }
  scenarios.push({ name: "Пустой ввод", stdin: "" });
  return scenarios;
}

const KEY = (projectId: string) => `blueprints.scenarios.${projectId}`;

export function loadScenarios(projectId: string): VerifyTestCase[] | null {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(KEY(projectId)) ?? "null");
    return Array.isArray(parsed) && parsed.every((item) => item && typeof item.name === "string" && typeof item.stdin === "string") && parsed.length ? parsed : null;
  } catch {
    return null;
  }
}

export function saveScenarios(projectId: string, cases: VerifyTestCase[]) {
  try { window.localStorage.setItem(KEY(projectId), JSON.stringify(cases)); } catch { /* scenarios are kept for this browser only */ }
}
