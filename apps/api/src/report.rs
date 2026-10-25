//! Markdown report of a project: complexity before → after, findings, behaviour check and measured runtime.
//!
//! The report only restates stored results (analysis artifacts, the latest verify and benchmark report of the
//! revision pair); it computes nothing new and never states an effect that no stored result supports.

use serde_json::Value;
use std::collections::BTreeMap;

pub struct ReportInput<'a> {
    pub project_name: &'a str,
    pub baseline_revision: u64,
    pub candidate_revision: u64,
    pub baseline_analysis: Option<&'a Value>,
    pub candidate_analysis: &'a Value,
    pub verification: Option<&'a Value>,
    pub benchmark: Option<&'a Value>,
}

#[derive(Clone)]
struct MethodRow {
    id: String,
    time: String,
    confidence: String,
}

fn methods(artifact: &Value) -> Vec<MethodRow> {
    let mut rows = Vec::new();
    collect(artifact, &mut rows);
    rows
}

fn collect(value: &Value, rows: &mut Vec<MethodRow>) {
    match value {
        Value::Object(object) => {
            if matches!(
                object.get("kind").and_then(Value::as_str),
                Some("method" | "constructor")
            ) {
                if let (Some(id), Some(time)) = (
                    object.get("id").and_then(Value::as_str),
                    value
                        .pointer("/analysis/complexity/time")
                        .and_then(Value::as_str),
                ) {
                    rows.push(MethodRow {
                        id: id.to_owned(),
                        time: time.to_owned(),
                        confidence: value
                            .pointer("/analysis/complexity/confidence")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .to_owned(),
                    });
                }
            }
            for (key, child) in object {
                if key != "cfg" && key != "analysis" {
                    collect(child, rows);
                }
            }
        }
        Value::Array(items) => items.iter().for_each(|item| collect(item, rows)),
        _ => {}
    }
}

/// Orders estimates: unknown < O(1) < O(log n) < O(n) < O(n log n) < O(n^2) < ...
fn rank(time: &str) -> i32 {
    let text: String = time
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_lowercase();
    if !text.starts_with("o(") || text.contains("unknown") {
        return -1;
    }
    if text == "o(1)" {
        return 0;
    }
    if text.contains("log") && !text.contains("n*") && !text.contains('^') {
        return if text.contains("nlog") { 3 } else { 1 };
    }
    if let Some(position) = text.find('^') {
        let digits: String = text[position + 1..]
            .chars()
            .take_while(char::is_ascii_digit)
            .collect();
        if let Ok(power) = digits.parse::<i32>() {
            return 2 + power;
        }
    }
    let widest = text[2..text.len().saturating_sub(1)]
        .split('+')
        .map(|term| term.split('*').count() as i32)
        .max()
        .unwrap_or(1);
    if widest == 1 {
        2
    } else {
        2 + widest
    }
}

fn cell(text: &str) -> String {
    text.replace('|', "\\|").replace('\n', " ")
}

fn findings(artifact: &Value) -> Vec<(String, Value)> {
    fn walk(value: &Value, out: &mut Vec<(String, Value)>) {
        match value {
            Value::Object(object) => {
                if matches!(
                    object.get("kind").and_then(Value::as_str),
                    Some("method" | "constructor")
                ) {
                    let id = object
                        .get("id")
                        .and_then(Value::as_str)
                        .unwrap_or("?")
                        .to_owned();
                    for finding in value
                        .pointer("/analysis/findings")
                        .and_then(Value::as_array)
                        .into_iter()
                        .flatten()
                    {
                        out.push((id.clone(), finding.clone()));
                    }
                }
                for (key, child) in object {
                    if key != "cfg" && key != "analysis" {
                        walk(child, out);
                    }
                }
            }
            Value::Array(items) => items.iter().for_each(|item| walk(item, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk(artifact, &mut out);
    out
}

pub fn build(input: &ReportInput) -> String {
    let mut out = String::new();
    out.push_str(&format!("# Отчёт по проекту {}\n\n", input.project_name));
    out.push_str(&format!(
        "Ревизии: **REV {}** (исходная) → **REV {}** (текущая). Отчёт собран из сохранённых результатов анализа, проверки и измерений; ничего в нём не оценено заново.\n\n",
        input.baseline_revision, input.candidate_revision
    ));

    // complexity
    let after = methods(input.candidate_analysis);
    let before: BTreeMap<String, MethodRow> = input
        .baseline_analysis
        .map(|artifact| {
            methods(artifact)
                .into_iter()
                .map(|row| (row.id.clone(), row))
                .collect()
        })
        .unwrap_or_default();
    out.push_str("## 1. Сложность по Big O\n\n");
    let buckets: [(&str, fn(i32) -> bool); 5] = [
        ("O(1)–O(n)", |r| (0..=2).contains(&r)),
        ("O(n log n)", |r| r == 3),
        ("O(n²)", |r| r == 4),
        ("O(n³) и выше", |r| r >= 5),
        ("не определена", |r| r < 0),
    ];
    out.push_str(&format!(
        "Методов оценено: {}.\n\n| Класс | Методов |\n|---|---|\n",
        after.len()
    ));
    for (label, test) in buckets {
        out.push_str(&format!(
            "| {} | {} |\n",
            label,
            after.iter().filter(|row| test(rank(&row.time))).count()
        ));
    }
    let mut changed: Vec<(&MethodRow, &MethodRow)> = after
        .iter()
        .filter_map(|row| before.get(&row.id).map(|old| (old, row)))
        .filter(|(old, new)| old.time != new.time)
        .collect();
    changed.sort_by_key(|(old, new)| -(rank(&old.time) - rank(&new.time)).abs());
    out.push_str("\n### Изменения сложности между ревизиями\n\n");
    if input.baseline_analysis.is_none() {
        out.push_str("Для исходной ревизии нет сохранённого анализа, сравнение недоступно.\n\n");
    } else if changed.is_empty() {
        out.push_str("Статическая оценка ни одного метода не изменилась. Оценка метода — верхняя граница и может оставаться осторожной после правки, поэтому эффект подтверждается измерением (раздел 4).\n\n");
    } else {
        out.push_str("| Метод | Было | Стало |\n|---|---|---|\n");
        for (old, new) in changed.iter().take(20) {
            out.push_str(&format!(
                "| `{}` | {} | {} |\n",
                cell(&new.id),
                cell(&old.time),
                cell(&new.time)
            ));
        }
        out.push('\n');
    }
    let mut heavy: Vec<&MethodRow> = after.iter().filter(|row| rank(&row.time) >= 3).collect();
    heavy.sort_by_key(|row| -rank(&row.time));
    out.push_str("### Самые тяжёлые методы текущей ревизии\n\n");
    if heavy.is_empty() {
        out.push_str("Методов хуже O(n) среди оценённых нет.\n\n");
    } else {
        out.push_str("| Метод | Оценка | Уверенность |\n|---|---|---|\n");
        for row in heavy.iter().take(10) {
            out.push_str(&format!(
                "| `{}` | {} | {} |\n",
                cell(&row.id),
                cell(&row.time),
                cell(&row.confidence)
            ));
        }
        out.push('\n');
    }
    out.push_str("Оценка статическая, по тексту кода; метод, вызывающий неописанный код, получает «не определена».\n\n");

    // findings
    out.push_str("## 2. Найденные проблемы\n\n");
    let found = findings(input.candidate_analysis);
    if found.is_empty() {
        out.push_str("Проблем, найденных анализатором, нет.\n\n");
    } else {
        for (method, finding) in found.iter().take(30) {
            let text = |key: &str| finding.get(key).and_then(Value::as_str).unwrap_or("");
            let line = finding
                .pointer("/source_range/line")
                .and_then(Value::as_u64);
            out.push_str(&format!(
                "- **{}** (`{}`{}): {} {}\n",
                text("message"),
                method,
                line.map(|l| format!(", строка {l}")).unwrap_or_default(),
                text("explanation"),
                if text("recommendation").is_empty() {
                    String::new()
                } else {
                    format!("Рекомендация: {}", text("recommendation"))
                }
            ));
        }
        out.push('\n');
    }

    // verification
    out.push_str("## 3. Сохранение поведения\n\n");
    match input.verification {
        None => out.push_str("Проверка для этой пары ревизий не запускалась.\n\n"),
        Some(report) => {
            let status = report.get("status").and_then(Value::as_str).unwrap_or("?");
            let verified = report
                .get("behaviorally_verified")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            let cases = report
                .get("test_cases")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let passed = cases
                .iter()
                .filter(|c| c.get("passed").and_then(Value::as_bool) == Some(true))
                .count();
            out.push_str(&format!(
                "Статус: **{}**{}. Сценариев совпало: {} из {}. Запусков на сценарий: {}.\n\n",
                status,
                if verified {
                    " (поведение совпало на всех сценариях)"
                } else {
                    ""
                },
                passed,
                cases.len(),
                report.get("repeats").and_then(Value::as_u64).unwrap_or(1)
            ));
            if let Some(warnings) = report
                .get("warnings")
                .and_then(Value::as_array)
                .filter(|w| !w.is_empty())
            {
                out.push_str("Предупреждения:\n\n");
                for warning in warnings {
                    out.push_str(&format!(
                        "- `{}`: {}\n",
                        warning.get("code").and_then(Value::as_str).unwrap_or(""),
                        warning.get("message").and_then(Value::as_str).unwrap_or("")
                    ));
                }
                out.push('\n');
            }
            out.push_str("| Сценарий | Результат |\n|---|---|\n");
            for case in &cases {
                out.push_str(&format!(
                    "| {} | {} |\n",
                    cell(case.get("name").and_then(Value::as_str).unwrap_or("")),
                    if case.get("passed").and_then(Value::as_bool) == Some(true) {
                        "совпало"
                    } else {
                        "расхождение или нестабильный вывод"
                    }
                ));
            }
            out.push('\n');
            if let Some(notes) = report
                .get("not_covered")
                .and_then(Value::as_array)
                .filter(|n| !n.is_empty())
            {
                out.push_str("Чего эта проверка не показывает:\n\n");
                for note in notes {
                    out.push_str(&format!("- {}\n", note.as_str().unwrap_or("")));
                }
                out.push('\n');
            }
        }
    }

    // benchmark
    out.push_str("## 4. Измеренное время выполнения\n\n");
    match input.benchmark {
        None => out.push_str("Измерение для этой пары ревизий не запускалось.\n\n"),
        Some(report) => {
            let overall = report.pointer("/runtime/overall");
            if let Some(summary) = overall
                .and_then(|o| o.get("summary"))
                .and_then(Value::as_str)
            {
                out.push_str(&format!("Итог: {}\n\n", summary));
            }
            out.push_str(
                "| Нагрузка | Размер | Вердикт | Ускорение (95% ДИ) |\n|---|---|---|---|\n",
            );
            for workload in report
                .pointer("/runtime/workloads")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                let speedup = workload.get("speedup").and_then(Value::as_f64);
                let interval = workload.get("speedup_ci95").and_then(Value::as_array);
                out.push_str(&format!(
                    "| {} | {} | {} | {} |\n",
                    cell(workload.get("name").and_then(Value::as_str).unwrap_or("")),
                    workload
                        .get("declared_size")
                        .and_then(Value::as_u64)
                        .map(|s| s.to_string())
                        .unwrap_or_else(|| "—".into()),
                    cell(
                        workload
                            .get("verdict")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                    ),
                    match (speedup, interval) {
                        (Some(value), Some(ci)) if ci.len() == 2 => format!(
                            "{:.2}× ({:.2}–{:.2})",
                            value,
                            ci[0].as_f64().unwrap_or(0.0),
                            ci[1].as_f64().unwrap_or(0.0)
                        ),
                        (Some(value), _) => format!("{value:.2}×"),
                        _ => "не подтверждено".into(),
                    }
                ));
            }
            out.push('\n');
            for limitation in report
                .get("limitations")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .take(4)
            {
                out.push_str(&format!("- {}\n", limitation.as_str().unwrap_or("")));
            }
            out.push('\n');
        }
    }
    out.push_str("---\nОтчёт описывает конкретные ревизии, сценарии и среду измерения; он не доказывает эквивалентность программ на всех входах и не обещает такого же ускорения на других данных.\n");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn artifact(time: &str) -> Value {
        json!({"types": [{"members": [
            {"kind": "method", "id": "A.slow()", "analysis": {"complexity": {"time": time, "confidence": "medium"},
              "findings": [{"message": "Repeated scan", "explanation": "quadratic", "recommendation": "use a map", "source_range": {"line": 7}}]}},
            {"kind": "method", "id": "A.fast()", "analysis": {"complexity": {"time": "O(1)", "confidence": "high"}, "findings": []}},
            {"kind": "method", "id": "A.opaque()", "analysis": {"complexity": {"time": "unknown", "confidence": "low"}, "findings": []}},
        ]}]})
    }

    #[test]
    fn ranks_estimates() {
        assert!(rank("O(1)") < rank("O(n)") && rank("O(n)") < rank("O(n log n)"));
        assert!(rank("O(n log n)") < rank("O(n^2)") && rank("O(n^2)") == rank("O(n*m)"));
        assert_eq!(rank("unknown"), -1);
        assert!(rank("O(n^3)") > rank("O(n^2)"));
    }

    #[test]
    fn report_states_only_what_is_stored() {
        let before = artifact("O(n^2)");
        let after = artifact("O(n)");
        let verification = json!({"status": "passed", "behaviorally_verified": true, "repeats": 2,
            "test_cases": [{"name": "a|b", "passed": true}], "warnings": [{"code": "empty_scenarios", "message": "no stdin"}],
            "not_covered": ["other inputs"]});
        let benchmark = json!({"runtime": {"overall": {"summary": "1 faster"}, "workloads": [
            {"name": "big", "declared_size": 600, "verdict": "faster", "speedup": 2.0, "speedup_ci95": [1.9, 2.2]},
            {"name": "small", "verdict": "no_significant_difference", "speedup": null}]}, "limitations": ["JIT noise"]});
        let text = build(&ReportInput {
            project_name: "Main.java",
            baseline_revision: 1,
            candidate_revision: 2,
            baseline_analysis: Some(&before),
            candidate_analysis: &after,
            verification: Some(&verification),
            benchmark: Some(&benchmark),
        });
        assert!(text.contains("| `A.slow()` | O(n^2) | O(n) |"), "{text}");
        assert!(
            text.contains("Repeated scan") && text.contains("строка 7"),
            "{text}"
        );
        assert!(
            text.contains("Статус: **passed**") && text.contains("Запусков на сценарий: 2"),
            "{text}"
        );
        assert!(text.contains("a\\|b"), "table cells are escaped: {text}");
        assert!(
            text.contains("2.00× (1.90–2.20)") && text.contains("не подтверждено"),
            "{text}"
        );
        assert!(text.contains("не определена | 1"), "{text}");

        let bare = build(&ReportInput {
            project_name: "Main.java",
            baseline_revision: 1,
            candidate_revision: 1,
            baseline_analysis: None,
            candidate_analysis: &after,
            verification: None,
            benchmark: None,
        });
        assert!(bare.contains("Проверка для этой пары ревизий не запускалась"));
        assert!(bare.contains("Измерение для этой пары ревизий не запускалось"));
        assert!(bare.contains("сравнение недоступно"));
    }
}
