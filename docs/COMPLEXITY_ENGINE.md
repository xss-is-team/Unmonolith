# Asymptotic analysis engine — feasibility and decision (Phase 3)

## Decision

**Keep the in-tree `ComplexityAnalyzer` (JDT, static cost model). Do not integrate AProVE or COSTA.**
The experiments below show neither produces usable complexity bounds for this product; the engine is
instead extended conservatively (below) and its results are exposed in a machine-readable form.

## Feasibility experiment (isolated, nothing added to the repo)

Run on macOS arm64, OpenJDK 26, 2026-10-25. Everything was downloaded into a scratch directory.

### COSTA (UCM, "COSt and Termination Analyzer for Java Bytecode")

* Only reachable as a research-prototype **web interface** (`costa.ls.fi.upm.es`: Home/Analyzer/About
  pages, GPL header, copyright 2010, linked papers 2008–2009). No downloadable binary or source release was found on the site,
  so it cannot be installed locally, run in CI, or called per method.
* Using the web form would mean sending code to a third-party server. Not evaluated; **not viable**.

### AProVE (`aprove.jar`, release `master_2026_02_15`, 29.5 MB, GitHub aprove-releases)

* Installs as one JAR, but: needs **bytecode** (`.class`/`.jar`; the `.java` extension is not a
  supported input — it falls through to the default term-rewriting parser and fails), so every analysis requires a
  `javac --release 8` step; needs external SMT solvers on `PATH` (`z3` and `yices` were requested; with
  only `z3` installed, `yices` failures are printed and the run continues); GitHub reports no license for the
  `aprove-releases` repository (licensing was not otherwise verified) — a redistribution question that would
  need an answer before shipping it.
* The JBC front end of this release answers **termination, not cost**: `-C complexity` on Java
  bytecode prints `termination of the given Bare JBC problem could be proven: YES`. There is no bound
  in the output (no `O(...)`, no `WORST_CASE(...)`), no per-method entry point (it analyses a whole jar
  from its `Main-Class`), and the plain proof is a free-text proof tree, not machine-readable.

Benchmark cases were compiled with `--release 8`, packaged as jars and run with
`java -Xmx2g -jar aprove.jar -m <limit> -u cli -p plain -C complexity t.jar` (z3 on PATH):

| case | AProVE result | wall / peak RSS |
|---|---|---|
| O(1) arithmetic | termination YES, no bound | 1 s / 186 MB |
| O(n) loop | termination YES, no bound | 1 s / 429 MB |
| nested loops O(n·m) | termination YES, no bound | 1 s / 876 MB |
| O(log n) halving loop | termination YES, no bound | 38 s / 2.4 GB |
| binary search | **no verdict after > 4 min CPU**; `-m 60` was not honoured (killed manually) | — |
| recursive Fibonacci | termination YES, no bound | 2 s |
| quick sort | no verdict | 2 s |
| nested `List` scans | termination YES, no bound | < 1 s |
| calls across several files | not applicable: whole-jar input, no method granularity | — |

Result: **no machine-readable complexity, no per-method association, runtime and memory far above the
current engine (milliseconds), heavy external dependencies.** Decision gate not met.

## What was implemented instead

All in `ComplexityAnalyzer.java` (engine id `jdt-static-cost-model/2`). The engine keeps its rule:
if a bound cannot be justified the result is `unknown`.

* **Binary search / midpoint loops** → `O(log n)`: `while (lo <= hi)` over local ints where a midpoint
  of `[lo, hi]` is computed each iteration, every write to `lo`/`hi` is `mid + 1` / `mid - 1`, and
  every path leaves the loop or moves `lo`/`hi` (an `if` without `else`, `lo = mid`, `continue` →
  unknown). `n` is the size `hi` starts from. Confidence medium; the no-overflow assumption is listed.
* **Direct recursion** from a recurrence, only for constant work per call, one integer parameter that
  every recursive call decreases by a constant (`n - c`) or divides (`n / d`, `n >> k`), no recursive
  call inside a loop/lambda, and a base-case guard (`n <= c`, `n < c`, or `n == c` for unit steps /
  halving onto 0 or 1) before the first recursive call. Results: `O(n)`, `O(k^n)` (stated as an upper
  bound; Fibonacci is Θ(1.618ⁿ)), `O(log n)`, `O(n)` for `k ≤ d` halving calls. Anything else (e.g.
  quick sort, which recurses on sub-ranges) is `unknown` with the reason.
* **Callees across files.** A callee found anywhere in the analysed project is analysed on its own: O(1)
  callees (including getters in other files) keep the caller's bound; a callee whose cost depends only
  on its parameters is rewritten into the argument sizes (`find(sorted, k)` inside a loop over `keys`
  gives `O(n log m)`), medium confidence. Callees that depend on fields/locals, recurse, mutate their
  arguments, may be overridden in the analysed files, or contain unknown calls stay `unknown`.
* **Standard library models** (already type-aware: ArrayList vs LinkedList vs HashSet/TreeSet/ArrayDeque/
  PriorityQueue, `String`, `Arrays`, `Collections`): added `Arrays.binarySearch` /
  `Collections.binarySearch` (log), `Collections.swap` (O(1)).
* Existing `AnalysisTest`/`AcceptanceTest` expectations were revisited: `Organizer.binarySearch` is now
  `O(log n)` (was `unknown`), and a loop calling a parameter-only-dependent helper is `O(n*m)` with
  medium confidence (was `unknown`); the "unknown call" test now uses a genuinely unmodelled callee.

### Documented benchmark set (`ComplexityBenchmarkTest`, 11 tests)

| case | result | note |
|---|---|---|
| basic arithmetic | `O(1)` | |
| loop over array | `O(n)` | |
| two nested loops over one array / over two arrays | `O(n^2)` / `O(n*m)` | |
| halving loop | `O(log n)` | |
| binary search (while/mid) | `O(log n)` | medium; overflow assumption listed |
| midpoint loop without guaranteed progress, `lo = mid` | `unknown` | would not terminate / not halve |
| recursive Fibonacci | `O(2^n)` | upper bound, stated |
| factorial, `n/2` recursion, `n == 0` base case | `O(n)`, `O(log n)`, `O(n)` | |
| unguarded, growing, or loop-recursive methods | `unknown` | |
| quick sort | `unknown` (low) | not claimed |
| nested `List` scans; `List.contains` in a loop; `HashSet.contains` in a loop | `O(n*m)`, `O(n*m)`, `O(n)` | |
| `Collections.sort`, `Arrays.binarySearch` | `O(n log n)`, `O(log n)` | |
| getter in another file; binary search / linear scan callee in another file; native callee | `O(n)`; `O(n log m)`; `O(n*m)`; `unknown` | multi-file project |

## Output contract

`GET /api/v1/projects/{id}/complexity` (works for single-file and archive projects):

```json
{
  "revision": 1,
  "methods": [{
    "method_id": "com.acme.service.Repo.find(int)",
    "file": "src/main/java/com/acme/service/Repo.java",
    "time_complexity": "O(n)",
    "space_complexity": null,
    "confidence": "high",
    "analysis_engine": "jdt-static-cost-model/2",
    "reason": "Dominant term n: a loop over items; …",
    "assumptions": ["n = number of elements in items"],
    "limitations": ["Static upper-bound estimate from the source; no execution, no profiling.", "…"],
    "source_range": {"file": "…", "start": 312, "length": 190, "line": 8, "end_line": 11},
    "cfg_node_ids": ["f1m1.n4"]
  }]
}
```

* `time_complexity` is `"unknown"` (confidence `low`) when no bound is justified.
* `space_complexity` is always `null`: space is **not** estimated; it is listed under `limitations`.
* `cfg_node_ids` are the loop nodes that determine the estimate (the method's entry node if none).
* Concerns are separate endpoints: estimates here, code smells/optimization findings under
  `GET /projects/{id}/findings`, source transformations under the transform/optimization routes.
  The legacy `GET /methods/{selector}/analysis` still returns the combined per-method object.
