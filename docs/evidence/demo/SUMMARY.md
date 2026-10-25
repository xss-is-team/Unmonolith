# Demo run — evidence

Generated 2026-10-25T06:37:52+00:00 by `scripts/demo.py` in 39.9 s against a real API,
the real parser JAR and the real Docker runner. All numbers below are copied from the stored responses;
nothing is estimated by this script.

| | |
|---|---|
| Monolith | `apps/api/tests/fixtures/demo/Main.java` (819 lines, sha256 `06019caa46cf`) |
| Optimized method | `Main.DataOptimizer.mergeDuplicateItemsQuadratic(List<Item>)` |
| Git commit | `4e599a1e7e55` (working tree had uncommitted changes) |
| Parser JAR sha256 | `43ddc2eef93d` |
| Runner image | `java-blueprints-runner:java21` (`sha256:73c6a2aa2fce`), Docker 29.1.3 |

## 1. Diagnosis (static)
- Complexity before: **O(n^2)** (confidence medium).
- Proposal: Replace the nested scan of 'items' with a HashMap index — `O(n*m)` → `O(n + m + k)`; assumptions acknowledged: null_and_exception_timing, outer_key_non_null, single_threaded.
- Applied as revision 2; static re-analysis of the rewritten method: **O(n*m)**.
- Then `contains_to_hashset` on `Main.DataOptimizer.filterUnavailableInventory(List<Item>,List<String>)` (revision 3; assumptions acknowledged: list_not_changed_through_alias, list_not_null_before_loop).
- Then `string_concat_to_builder` on `Main.OutputFormatter.buildLabels(List<Item>)` (revision 4; assumptions acknowledged: none).
  The proposal itself predicts `O(n + m + k)` for the replaced scan. The analyzer's bound for the whole method is an upper bound and can stay pessimistic (it cannot prove that the loops over matched groups sum to the number of matching pairs), so the measured runtime in section 3 is the evidence for the effect.

## 2. Behaviour check (`POST /verify`, baseline revision 1 vs candidate)
- Status: **passed**, `behaviorally_verified`: True, 8/8 scenarios identical (stdout, stderr, exit code).
- Scenarios: `help`, `inventory`, `merge-seed-duplicates`, `merge-after-add`, `available`, `labels`, `unknown-command`, `eof-without-exit`.
- Warnings: none. This result applies only to these scenarios; it is not a proof of equivalence.

## 3. Measured runtime (`POST /benchmark`)
- Status: completed; functional equivalence on all workloads: True.
- Overall verdict: **no_significant_difference** — mixed: 2 faster, 0 slower, 1 without a supported claim
  - `merge-150` (size 150): verdict insufficient_evidence
  - `merge-600` (size 600): verdict faster, speedup 1.94x (95% CI 1.70–7.45, p=0.0024)
  - `merge-1200` (size 1200): verdict faster, speedup 2.66x (95% CI 2.48–7.48, p=0.0003)

Limitations reported by the API:
- Timings are for in-JVM calls of main(String[]) inside a Docker container with 1 CPU and 512 MB; absolute numbers do not transfer to other machines.
- With fresh class loaders every iteration includes class loading and static initialisation; JIT-compiled JDK code is shared across iterations, the program's own classes are re-loaded. Shared mode reuses classes but static state persists between iterations.
- Warmup iterations are discarded; steady state is not proven. JIT, GC and CPU frequency scaling add noise, which is why a claim needs at least 10 iterations, a significant Mann-Whitney test, a bootstrap interval excluding 1 and separated interquartile ranges.
- Container creation, source copy, javac and harness compilation are timed separately and are not part of algorithm runtime.
- Equivalence means identical stdout and identical outcome (return, exit code or exception type and message) on the provided workloads; it is not a proof of equivalence. stderr is not compared.
- Programs that use threads, wall-clock time, randomness without a fixed seed or files may be reported unstable.
- A speedup on the supplied sizes says nothing about other input sizes; use several sizes to see how the difference scales.

## 4. Decomposition (`POST /decomposition/apply`)
- The optimized monolith (revision 2) was split into **17 files** as revision 5: `Main.java` 827 → 235 lines, 16 member types moved into files of their own (`Identifiable.java`, `Formattable.java`, `OrderFilter.java`, `AppException.java`, `ValidationException.java`, `EntityNotFoundException.java`, …).
- Verify of the **original** (revision 1) against the split project: **passed**, 8/8 scenarios identical; warnings: none.
- The project is now a multi-file (`archive`) project; revision 1 stays readable and comparable.

Raw responses: `01`–`11` JSON files in this directory; `run.json` has the environment.
