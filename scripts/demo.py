#!/usr/bin/env python3
"""Scripted end-to-end demo run: upload -> analyze -> optimize -> verify -> benchmark.

Starts its own API on a free port with a temporary data directory, drives the public HTTP API with the stdin-driven
demo monolith (apps/api/tests/fixtures/demo/Main.java), and stores every real response under docs/evidence/demo/.
Nothing is simulated: if the parser JAR, Docker or the runner image is missing the script stops with a message and
writes no evidence. Python 3 standard library only.

Usage (repository root):   ./dev demo        or        python3 scripts/demo.py [--out DIR] [--keep-data]
Needs: ./dev parser-build, a running Docker daemon, ./dev runner-build (or BLUEPRINTS_RUNNER_IMAGE=<image>).
"""
import argparse
import datetime
import hashlib
import http.cookiejar
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "apps/parser/target/ASTPARSER-1.0-SNAPSHOT.jar"
BINARY = ROOT / "apps/api/target/debug/java-blueprints"
DEMO = ROOT / "apps/api/tests/fixtures/demo/Main.java"
DEFAULT_IMAGE = "java-blueprints-runner:java21"
METHOD = "Main.DataOptimizer.mergeDuplicateItemsQuadratic(List<Item>)"
LEVEL = "21"

# Scenarios for verify: some reach the optimized method (merge_inventory), some do not.
CASES = [
    ("help", "help\nexit\n"),
    ("inventory", "inventory\nexit\n"),
    ("merge-seed-duplicates", "merge_inventory\ninventory\nexit\n"),
    (
        "merge-after-add",
        "add_item ITM-101;Ноутбук Pro 15;Электроника;90000;2\n"
        "add_item ITM-105;Кабель;Аксессуары;300;40\nmerge_inventory\ninventory\nexit\n",
    ),
    ("unknown-command", "foo\nexit\n"),
    ("eof-without-exit", "orders\n"),
]


def fail(message):
    print(f"demo: {message}", file=sys.stderr)
    sys.exit(1)


def workload(size):
    """`size` add_item lines with half as many distinct ids, then the defective merge (stdin stays < 64 KiB)."""
    distinct = max(size // 2, 1)
    lines = [f"add_item ITM-{i % distinct:05d};Товар;Склад;{100 + i % 7};{1 + i % 5}" for i in range(size)]
    return {"name": f"merge-{size}", "size": size, "stdin": "\n".join(lines) + "\nmerge_inventory\nexit\n"}


class Api:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, data=None, headers=None, timeout=900):
        headers = dict(headers or {})
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            raw, status = error.read(), error.code
        text = raw.decode("utf-8", "replace")
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = text
        if status >= 400:
            fail(f"{method} {path} -> {status}: {text[:600]}")
        return parsed

    def upload(self, filename, content):
        boundary = "----demo" + hashlib.md5(content).hexdigest()[:12]
        body = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n"
        ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
        return self.call("POST", "/projects", data=body, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run_text(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def preflight():
    if not JAR.exists():
        fail(f"parser JAR not built: {JAR.relative_to(ROOT)} (run ./dev parser-build)")
    if not DEMO.exists():
        fail(f"demo monolith missing: {DEMO.relative_to(ROOT)}")
    if shutil.which("docker") is None or subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
        fail("Docker is not available or its daemon is not running; verification and benchmark need it")
    image = os.environ.get("BLUEPRINTS_RUNNER_IMAGE", DEFAULT_IMAGE)
    if subprocess.run(["docker", "image", "inspect", image], capture_output=True).returncode != 0:
        fail(f"runner image {image} not found (run ./dev runner-build, or set BLUEPRINTS_RUNNER_IMAGE)")
    if not BINARY.exists():
        print("demo: building the API (cargo build)...")
        if subprocess.run(["cargo", "build"], cwd=ROOT / "apps/api").returncode != 0:
            fail("cargo build failed")
    return image


def start_server(image, data_dir):
    port = free_port()
    env = dict(
        os.environ,
        BLUEPRINTS_BIND=f"127.0.0.1:{port}",
        BLUEPRINTS_DATA_DIR=str(data_dir),
        BLUEPRINTS_ANALYZER_JAR=str(JAR),
        BLUEPRINTS_RUNNER_IMAGE=image,
        BLUEPRINTS_RUNNER_BENCHMARK_TIMEOUT_MS=os.environ.get("BLUEPRINTS_RUNNER_BENCHMARK_TIMEOUT_MS", "600000"),
    )
    log = open(data_dir / "server.log", "w")
    process = subprocess.Popen([str(BINARY)], env=env, stdout=log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}/api/v1"
    for _ in range(100):
        if process.poll() is not None:
            fail(f"API exited early, see {data_dir / 'server.log'}")
        try:
            urllib.request.urlopen(base + "/healthz", timeout=1).read()
            return process, base
        except OSError:
            time.sleep(0.1)
    process.kill()
    fail("API did not become healthy in 10 s")


def save(out, name, value):
    path = out / name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def find(items, key, value):
    return next((item for item in items if item.get(key) == value), None)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", default=str(ROOT / "docs/evidence/demo"), help="evidence directory (replaced)")
    parser.add_argument("--keep-data", action="store_true", help="keep the temporary API data directory")
    parser.add_argument("--iterations", type=int, default=12, help="measured benchmark iterations (>= 10 to allow a verdict)")
    parser.add_argument("--sizes", default="150,600,1200", help="benchmark workload sizes (add_item lines)")
    args = parser.parse_args()

    image = preflight()
    data_dir = pathlib.Path(tempfile.mkdtemp(prefix="blueprints-demo-"))
    process, base = start_server(image, data_dir)
    out = pathlib.Path(args.out)
    staging = pathlib.Path(tempfile.mkdtemp(prefix="blueprints-evidence-"))
    started = time.time()
    try:
        api = Api(base)
        api.call("POST", "/sessions/guest")
        source = DEMO.read_bytes()
        project = api.upload("Main.java", source)
        pid = project["id"]
        p = f"/projects/{pid}"
        line_count = source.count(b"\n")
        print(f"demo: uploaded Main.java ({len(source)} bytes, {line_count} lines) as project {pid}")

        analysis = api.call("POST", p + "/analyze")
        assert analysis["status"] == "completed", analysis
        before = find(api.call("GET", p + "/complexity")["methods"], "method_id", METHOD)
        findings = api.call("GET", p + "/findings")
        print(f"demo: {len(analysis['methods'])} methods analyzed; {METHOD} is {before['time_complexity']}")

        proposals = api.call("GET", p + f"/optimizations?source_level={LEVEL}")["proposals"]
        proposal = find(proposals, "method_id", METHOD)
        if proposal is None or proposal["applicability"] != "applicable":
            fail(f"no applicable optimization for {METHOD}: {json.dumps(proposal)[:500]}")
        preview = api.call("POST", p + f"/optimizations/{proposal['id']}/preview", {"base_revision": 1, "source_level": LEVEL})
        applied = api.call("POST", p + f"/optimizations/{proposal['id']}/apply", {
            "base_revision": 1, "source_level": LEVEL, "preview_sha256": preview["preview_sha256"],
            "acknowledged_assumptions": [a["id"] for a in proposal["assumptions"]]})
        candidate = applied["revision"]
        after = find(api.call("GET", p + "/complexity")["methods"], "method_id", METHOD)
        print(f"demo: optimization applied -> revision {candidate}; method is now {after['time_complexity']}")

        verify = api.call("POST", p + "/verify", {
            "baseline_revision": 1, "candidate_revision": candidate, "repeats": 2,
            "test_cases": [{"name": n, "stdin": s} for n, s in CASES]})
        passed = sum(1 for c in verify["test_cases"] if c["passed"])
        print(f"demo: verify {verify['status']} ({passed}/{len(verify['test_cases'])} scenarios), "
              f"warnings: {[w['code'] for w in verify['warnings']] or 'none'}")

        sizes = [int(s) for s in args.sizes.split(",") if s.strip()]
        bench = api.call("POST", p + "/benchmark", {
            "baseline_revision": 1, "candidate_revision": candidate,
            "workloads": [workload(n) for n in sizes],
            "warmup_iterations": 3, "measured_iterations": args.iterations})
        overall = bench["runtime"].get("overall", {})
        print(f"demo: benchmark {bench['status']}; verdict {overall.get('verdict')}: {overall.get('summary')}")

        # split the (optimized) monolith into one file per member type and check it against the original
        split_preview = api.call("POST", p + "/decomposition/preview", {"base_revision": candidate})
        split = api.call("POST", p + "/decomposition/apply", {
            "base_revision": candidate, "preview_sha256": split_preview["preview_sha256"]})
        split_verify = api.call("POST", p + "/verify", {
            "baseline_revision": 1, "candidate_revision": split["revision"], "main_class": "Main", "repeats": 2,
            "test_cases": [{"name": n, "stdin": s} for n, s in CASES]})
        print(f"demo: decomposition -> revision {split['revision']} ({split['file_count']} files, Main.java "
              f"{split_preview['host_lines']['Main.java']['before']} -> {split_preview['host_lines']['Main.java']['after']} lines); "
              f"verify vs original: {split_verify['status']}, warnings: {[w['code'] for w in split_verify['warnings']] or 'none'}")

        diff = api.call("GET", p + f"/revisions/1/diff/{candidate}")
        meta = {
            "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "duration_seconds": round(time.time() - started, 1),
            "git_commit": run_text("git", "-C", str(ROOT), "rev-parse", "HEAD"),
            "git_dirty": bool(run_text("git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no")),
            "parser_jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
            "runner_image": image,
            "runner_image_id": run_text("docker", "image", "inspect", "--format", "{{.Id}}", image),
            "docker_server_version": run_text("docker", "version", "--format", "{{.Server.Version}}"),
            "host_java": run_text("java", "-version") or "see `java -version`",
            "monolith": {"path": str(DEMO.relative_to(ROOT)), "lines": line_count, "sha256": hashlib.sha256(source).hexdigest()},
            "method": METHOD,
            "benchmark_workload_sizes": sizes,
        }
        save(staging, "run.json", meta)
        save(staging, "01-analysis-before.json", {"complexity": before, "project_findings": findings})
        save(staging, "02-optimization-proposal.json", proposal)
        save(staging, "03-optimization-preview.json", preview)
        save(staging, "04-optimization-apply.json", applied)
        save(staging, "05-analysis-after.json", after)
        save(staging, "06-revision-diff.json", diff)
        save(staging, "07-verify-report.json", verify)
        save(staging, "08-benchmark-report.json", bench)
        save(staging, "09-decomposition-preview.json", split_preview)
        save(staging, "10-decomposition-apply.json", {k: v for k, v in split.items() if k != "diff"}
             | {"diff_files": [{"path": d["path"], "status": d["status"], "additions": d["additions"], "deletions": d["deletions"]} for d in split["diff"]]})
        save(staging, "11-decomposition-verify.json", split_verify)
        (staging / "SUMMARY.md").write_text(
            summary(meta, before, after, proposal, applied, verify, bench, split_preview, split, split_verify), encoding="utf-8")
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
        if args.keep_data:
            print(f"demo: data kept in {data_dir}")
        else:
            shutil.rmtree(data_dir, ignore_errors=True)

    if out.exists():
        shutil.rmtree(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(staging, out)
    shutil.rmtree(staging, ignore_errors=True)
    print(f"demo: evidence written to {out}")


def summary(meta, before, after, proposal, applied, verify, bench, split_preview, split, split_verify):
    runtime = bench["runtime"]
    lines = [
        "# Demo run — evidence",
        "",
        f"Generated {meta['generated_at']} by `scripts/demo.py` in {meta['duration_seconds']} s against a real API,",
        "the real parser JAR and the real Docker runner. All numbers below are copied from the stored responses;",
        "nothing is estimated by this script.",
        "",
        "| | |",
        "|---|---|",
        f"| Monolith | `{meta['monolith']['path']}` ({meta['monolith']['lines']} lines, sha256 `{meta['monolith']['sha256'][:12]}`) |",
        f"| Optimized method | `{meta['method']}` |",
        f"| Git commit | `{meta['git_commit'][:12]}`{' (working tree had uncommitted changes)' if meta['git_dirty'] else ''} |",
        f"| Parser JAR sha256 | `{meta['parser_jar_sha256'][:12]}` |",
        f"| Runner image | `{meta['runner_image']}` (`{meta['runner_image_id'][:19]}`), Docker {meta['docker_server_version']} |",
        "",
        "## 1. Diagnosis (static)",
        f"- Complexity before: **{before['time_complexity']}** (confidence {before.get('confidence')}).",
        f"- Proposal: {proposal['title']} — `{proposal['complexity']['scan_before']}` → `{proposal['complexity']['scan_after']}`; "
        f"assumptions acknowledged: {', '.join(a['id'] for a in proposal['assumptions'])}.",
        f"- Applied as revision {applied['revision']}; static re-analysis of the rewritten method: **{after['time_complexity']}**.",
        *([f"  The proposal itself predicts `{proposal['complexity']['scan_after']}` for the replaced scan. The analyzer's bound for the whole "
           "method is an upper bound and can stay pessimistic (it cannot prove that the loops over matched groups sum to the number of "
           "matching pairs), so the measured runtime in section 3 is the evidence for the effect."]
          if after["time_complexity"] != proposal["complexity"]["scan_after"] else []),
        "",
        "## 2. Behaviour check (`POST /verify`, baseline revision 1 vs candidate)",
        f"- Status: **{verify['status']}**, `behaviorally_verified`: {verify['behaviorally_verified']}, "
        f"{sum(1 for c in verify['test_cases'] if c['passed'])}/{len(verify['test_cases'])} scenarios identical "
        "(stdout, stderr, exit code).",
        "- Scenarios: " + ", ".join(f"`{c['name']}`" for c in verify["test_cases"]) + ".",
        "- Warnings: " + (", ".join(f"`{w['code']}`" for w in verify["warnings"]) if verify["warnings"] else "none")
        + ". This result applies only to these scenarios; it is not a proof of equivalence.",
        "",
        "## 3. Measured runtime (`POST /benchmark`)",
        f"- Status: {bench['status']}; functional equivalence on all workloads: "
        f"{bench['functional'].get('equivalent_on_all_workloads')}.",
        f"- Overall verdict: **{runtime.get('overall', {}).get('verdict')}** — {runtime.get('overall', {}).get('summary')}",
    ]
    for w in runtime.get("workloads", []):
        speedup = w.get("speedup")
        ci = w.get("speedup_ci95")
        detail = ""
        if isinstance(speedup, (int, float)):
            detail = f", speedup {speedup:.2f}x"
            if isinstance(ci, list) and len(ci) == 2:
                detail += f" (95% CI {ci[0]:.2f}–{ci[1]:.2f}"
                detail += f", p={w['p_value']:.4f})" if isinstance(w.get("p_value"), (int, float)) else ")"
        lines.append(f"  - `{w.get('name')}` (size {w.get('declared_size')}): verdict {w.get('verdict')}{detail}")
    lines += [
        "",
        "Limitations reported by the API:",
        *[f"- {text}" for text in bench.get("limitations", [])],
        "",
        "## 4. Decomposition (`POST /decomposition/apply`)",
        f"- The optimized monolith (revision {applied['revision']}) was split into **{split['file_count']} files** as revision {split['revision']}: "
        f"`Main.java` {split_preview['host_lines']['Main.java']['before']} → {split_preview['host_lines']['Main.java']['after']} lines, "
        f"{len(split_preview['added_files'])} member types moved into files of their own ("
        + ", ".join(f"`{e['new_file']}`" for e in split["extracted"][:6]) + ", …).",
        f"- Verify of the **original** (revision 1) against the split project: **{split_verify['status']}**, "
        f"{sum(1 for c in split_verify['test_cases'] if c['passed'])}/{len(split_verify['test_cases'])} scenarios identical; "
        "warnings: " + (", ".join(f"`{w['code']}`" for w in split_verify["warnings"]) if split_verify["warnings"] else "none") + ".",
        "- The project is now a multi-file (`archive`) project; revision 1 stays readable and comparable.",
        "",
        "Raw responses: `01`–`11` JSON files in this directory; `run.json` has the environment.",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    main()
