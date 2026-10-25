//! Runtime comparison of two revisions: sample statistics, a significance test and the verdict rules.
//!
//! Measurements come from the in-container harness (`apps/runner/Bench.java`). This module never runs
//! anything; it only turns raw samples into a report and decides what may be claimed from them.

use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

/// Fewer measured iterations than this never support a speed claim.
pub const MIN_ITERATIONS_FOR_CLAIM: usize = 10;
/// Median ratio below which a difference is not reported as a speedup even if it is significant.
pub const MIN_EFFECT: f64 = 1.10;
const ALPHA: f64 = 0.01;
const BOOTSTRAP_RESAMPLES: usize = 2000;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct SampleStats {
    pub iterations: usize,
    pub median_ns: u64,
    pub min_ns: u64,
    pub max_ns: u64,
    pub p25_ns: u64,
    pub p75_ns: u64,
    /// Interquartile range divided by the median: the run-to-run spread.
    pub relative_spread: f64,
    pub mean_ns: f64,
    pub stddev_ns: f64,
    /// Median absolute deviation.
    pub mad_ns: u64,
}

pub fn summarize(samples: &[u64]) -> Option<SampleStats> {
    if samples.is_empty() {
        return None;
    }
    let mut sorted = samples.to_vec();
    sorted.sort_unstable();
    let n = sorted.len();
    let median = quantile(&sorted, 0.5);
    let p25 = quantile(&sorted, 0.25);
    let p75 = quantile(&sorted, 0.75);
    let mean = sorted.iter().map(|&v| v as f64).sum::<f64>() / n as f64;
    let variance = if n > 1 {
        sorted
            .iter()
            .map(|&v| (v as f64 - mean).powi(2))
            .sum::<f64>()
            / (n as f64 - 1.0)
    } else {
        0.0
    };
    let mut deviations: Vec<u64> = sorted.iter().map(|&v| v.abs_diff(median)).collect();
    deviations.sort_unstable();
    Some(SampleStats {
        iterations: n,
        median_ns: median,
        min_ns: sorted[0],
        max_ns: sorted[n - 1],
        p25_ns: p25,
        p75_ns: p75,
        relative_spread: if median == 0 {
            0.0
        } else {
            (p75 - p25) as f64 / median as f64
        },
        mean_ns: mean,
        stddev_ns: variance.sqrt(),
        mad_ns: quantile(&deviations, 0.5),
    })
}

/// Linear-interpolated quantile of a sorted, non-empty slice.
fn quantile(sorted: &[u64], q: f64) -> u64 {
    if sorted.len() == 1 {
        return sorted[0];
    }
    let position = q * (sorted.len() - 1) as f64;
    let lower = position.floor() as usize;
    let upper = position.ceil() as usize;
    let fraction = position - lower as f64;
    (sorted[lower] as f64 + (sorted[upper] as f64 - sorted[lower] as f64) * fraction).round() as u64
}

/// Two-sided Mann-Whitney U test p-value (normal approximation with tie and continuity correction).
pub fn mann_whitney_p(a: &[u64], b: &[u64]) -> f64 {
    let (n1, n2) = (a.len() as f64, b.len() as f64);
    if a.is_empty() || b.is_empty() {
        return 1.0;
    }
    let mut all: Vec<(u64, bool)> = a
        .iter()
        .map(|&v| (v, true))
        .chain(b.iter().map(|&v| (v, false)))
        .collect();
    all.sort_by_key(|&(v, _)| v);
    let n = all.len();
    let mut rank_sum_a = 0.0;
    let mut tie_term = 0.0;
    let mut i = 0;
    while i < n {
        let mut j = i;
        while j + 1 < n && all[j + 1].0 == all[i].0 {
            j += 1;
        }
        let average_rank = (i + j) as f64 / 2.0 + 1.0;
        let ties = (j - i + 1) as f64;
        tie_term += ties.powi(3) - ties;
        for item in &all[i..=j] {
            if item.1 {
                rank_sum_a += average_rank;
            }
        }
        i = j + 1;
    }
    let u1 = rank_sum_a - n1 * (n1 + 1.0) / 2.0;
    let mean = n1 * n2 / 2.0;
    let total = n1 + n2;
    let variance = n1 * n2 / 12.0 * ((total + 1.0) - tie_term / (total * (total - 1.0)));
    if variance <= 0.0 {
        return 1.0;
    }
    let z = ((u1 - mean).abs() - 0.5).max(0.0) / variance.sqrt();
    (2.0 * (1.0 - normal_cdf(z))).clamp(0.0, 1.0)
}

fn normal_cdf(x: f64) -> f64 {
    0.5 * (1.0 + erf(x / std::f64::consts::SQRT_2))
}

/// Abramowitz-Stegun 7.1.26 (|error| < 1.5e-7).
fn erf(x: f64) -> f64 {
    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs();
    let t = 1.0 / (1.0 + 0.327_591_1 * x);
    let poly = (((((1.061_405_429 * t - 1.453_152_027) * t) + 1.421_413_741) * t - 0.284_496_736)
        * t
        + 0.254_829_592)
        * t;
    sign * (1.0 - poly * (-x * x).exp())
}

/// 95% bootstrap interval of `median(baseline) / median(candidate)`; deterministic (fixed seed).
pub fn bootstrap_ratio_ci(baseline: &[u64], candidate: &[u64]) -> (f64, f64) {
    let mut state: u64 = 0x9E37_79B9_7F4A_7C15;
    let mut next = move || {
        // xorshift64*
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        state.wrapping_mul(0x2545_F491_4F6C_DD1D)
    };
    let median_of = |data: &[u64], next: &mut dyn FnMut() -> u64| -> f64 {
        let mut resample: Vec<u64> = (0..data.len())
            .map(|_| data[(next() % data.len() as u64) as usize])
            .collect();
        resample.sort_unstable();
        quantile(&resample, 0.5) as f64
    };
    let mut ratios = Vec::with_capacity(BOOTSTRAP_RESAMPLES);
    for _ in 0..BOOTSTRAP_RESAMPLES {
        let b = median_of(baseline, &mut next);
        let c = median_of(candidate, &mut next);
        if c > 0.0 {
            ratios.push(b / c);
        }
    }
    if ratios.is_empty() {
        return (0.0, f64::INFINITY);
    }
    ratios.sort_by(|a, b| a.partial_cmp(b).expect("ratios are finite"));
    let pick = |q: f64| ratios[((ratios.len() - 1) as f64 * q).round() as usize];
    (pick(0.025), pick(0.975))
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum Verdict {
    /// Outputs differ: the candidate is not an optimization of the baseline.
    NotEquivalent,
    Faster,
    Slower,
    NoSignificantDifference,
    /// Too few iterations, unstable output across iterations or high noise: no claim is made.
    InsufficientEvidence,
    /// A revision failed to compile or a workload did not finish.
    Incomplete,
}

/// Raw per-workload observation from the harness.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
pub struct WorkloadObservation {
    pub status: String,
    #[serde(default)]
    pub samples_ns: Vec<u64>,
    #[serde(default)]
    pub warmup_ns: Vec<u64>,
    #[serde(default)]
    pub stdout_sha256: Option<String>,
    #[serde(default)]
    pub stdout_bytes: Option<u64>,
    /// `returned`, `exit:N` or `exception:Class: message`.
    #[serde(default)]
    pub outcome: Option<String>,
    #[serde(default = "default_true")]
    pub stable_across_iterations: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct WorkloadComparison {
    pub name: String,
    pub declared_size: Option<u64>,
    pub stdin_sha256: String,
    pub stdin_bytes: usize,
    pub baseline: Option<WorkloadObservation>,
    pub candidate: Option<WorkloadObservation>,
    pub baseline_stats: Option<SampleStats>,
    pub candidate_stats: Option<SampleStats>,
    /// Same stdout and same outcome (return / exit code / exception) in baseline and candidate.
    pub equivalent: Option<bool>,
    pub verdict: Verdict,
    /// Baseline median / candidate median. Present only when the verdict is `faster` or `slower`.
    pub speedup: Option<f64>,
    pub speedup_ci95: Option<[f64; 2]>,
    /// Mann-Whitney U p-value (two-sided); informational for every verdict that had enough samples.
    pub p_value: Option<f64>,
    pub notes: Vec<String>,
}

pub fn compare_workload(
    name: &str,
    declared_size: Option<u64>,
    stdin_sha256: String,
    stdin_bytes: usize,
    baseline: Option<WorkloadObservation>,
    candidate: Option<WorkloadObservation>,
) -> WorkloadComparison {
    let mut notes = Vec::new();
    let baseline_stats = baseline.as_ref().and_then(|o| summarize(&o.samples_ns));
    let candidate_stats = candidate.as_ref().and_then(|o| summarize(&o.samples_ns));
    let mut result = WorkloadComparison {
        name: name.to_owned(),
        declared_size,
        stdin_sha256,
        stdin_bytes,
        baseline: baseline.clone(),
        candidate: candidate.clone(),
        baseline_stats: baseline_stats.clone(),
        candidate_stats: candidate_stats.clone(),
        equivalent: None,
        verdict: Verdict::Incomplete,
        speedup: None,
        speedup_ci95: None,
        p_value: None,
        notes: Vec::new(),
    };
    let (Some(b), Some(c)) = (baseline, candidate) else {
        notes.push(
            "a revision produced no result for this workload (compilation failure or timeout)"
                .to_owned(),
        );
        result.notes = notes;
        return result;
    };
    if b.status != "completed" || c.status != "completed" {
        notes.push("a workload iteration exceeded the per-iteration time limit".to_owned());
        result.notes = notes;
        return result;
    }
    let equivalent = b.stdout_sha256 == c.stdout_sha256 && b.outcome == c.outcome;
    result.equivalent = Some(equivalent);
    if !equivalent {
        notes.push("stdout or outcome differs between baseline and candidate: this is a behaviour change, not an optimization; timings are not compared".to_owned());
        result.verdict = Verdict::NotEquivalent;
        result.notes = notes;
        return result;
    }
    if !b.stable_across_iterations || !c.stable_across_iterations {
        notes.push("output changed between iterations of the same revision (static state or nondeterminism); no claim is made".to_owned());
        result.verdict = Verdict::InsufficientEvidence;
        result.notes = notes;
        return result;
    }
    let (Some(bs), Some(cs)) = (baseline_stats, candidate_stats) else {
        result.notes = notes;
        return result;
    };
    result.p_value = Some(mann_whitney_p(&b.samples_ns, &c.samples_ns));
    if b.samples_ns.len() < MIN_ITERATIONS_FOR_CLAIM
        || c.samples_ns.len() < MIN_ITERATIONS_FOR_CLAIM
    {
        notes.push(format!(
            "fewer than {MIN_ITERATIONS_FOR_CLAIM} measured iterations per revision: a speedup is not claimed"
        ));
        result.verdict = Verdict::InsufficientEvidence;
        result.notes = notes;
        return result;
    }
    let (lo, hi) = bootstrap_ratio_ci(&b.samples_ns, &c.samples_ns);
    result.speedup_ci95 = Some([lo, hi]);
    let ratio = bs.median_ns as f64 / cs.median_ns.max(1) as f64;
    let significant = result.p_value.is_some_and(|p| p < ALPHA);
    let separated_faster = cs.p75_ns < bs.p25_ns;
    let separated_slower = bs.p75_ns < cs.p25_ns;
    if significant && ratio >= MIN_EFFECT && lo > 1.0 && separated_faster {
        result.verdict = Verdict::Faster;
        result.speedup = Some(ratio);
    } else if significant && ratio <= 1.0 / MIN_EFFECT && hi < 1.0 && separated_slower {
        result.verdict = Verdict::Slower;
        result.speedup = Some(ratio);
    } else if significant || (ratio - 1.0).abs() >= MIN_EFFECT - 1.0 {
        notes.push("the medians differ but the evidence is not strong enough for a claim (significance, effect size, confidence interval and interquartile separation must all agree)".to_owned());
        result.verdict = Verdict::InsufficientEvidence;
    } else {
        result.verdict = Verdict::NoSignificantDifference;
    }
    if bs.relative_spread > 0.5 || cs.relative_spread > 0.5 {
        notes.push(
            "high run-to-run spread (interquartile range above 50% of the median)".to_owned(),
        );
    }
    result.notes = notes;
    result
}

#[derive(Clone, Debug, Serialize, ToSchema)]
pub struct OverallVerdict {
    /// Every workload produced identical observable behaviour in both revisions.
    pub equivalent_on_all_workloads: bool,
    pub verdict: Verdict,
    pub summary: String,
}

pub fn overall(workloads: &[WorkloadComparison]) -> OverallVerdict {
    let count = |v: Verdict| workloads.iter().filter(|w| w.verdict == v).count();
    let total = workloads.len();
    let equivalent = workloads.iter().all(|w| w.equivalent == Some(true));
    let (verdict, summary) = if count(Verdict::NotEquivalent) > 0 {
        (
            Verdict::NotEquivalent,
            format!(
                "{} of {total} workloads behave differently in the candidate: this is not an optimization",
                count(Verdict::NotEquivalent)
            ),
        )
    } else if count(Verdict::Incomplete) > 0 {
        (
            Verdict::Incomplete,
            "at least one revision or workload did not complete".to_owned(),
        )
    } else if count(Verdict::Faster) == total {
        (
            Verdict::Faster,
            format!("measurably faster on all {total} workloads"),
        )
    } else if count(Verdict::Slower) == total {
        (
            Verdict::Slower,
            format!("measurably slower on all {total} workloads"),
        )
    } else if count(Verdict::Faster) > 0 || count(Verdict::Slower) > 0 {
        (
            Verdict::NoSignificantDifference,
            format!(
                "mixed: {} faster, {} slower, {} without a supported claim",
                count(Verdict::Faster),
                count(Verdict::Slower),
                total - count(Verdict::Faster) - count(Verdict::Slower)
            ),
        )
    } else if count(Verdict::InsufficientEvidence) > 0 {
        (
            Verdict::InsufficientEvidence,
            "no workload has enough evidence for a speed claim".to_owned(),
        )
    } else {
        (
            Verdict::NoSignificantDifference,
            "no measurable difference on any workload".to_owned(),
        )
    };
    OverallVerdict {
        equivalent_on_all_workloads: equivalent,
        verdict,
        summary,
    }
}

pub const LIMITATIONS: [&str; 7] = [
    "Timings are for in-JVM calls of main(String[]) inside a Docker container with 1 CPU and 512 MB; absolute numbers do not transfer to other machines.",
    "With fresh class loaders every iteration includes class loading and static initialisation; JIT-compiled JDK code is shared across iterations, the program's own classes are re-loaded. Shared mode reuses classes but static state persists between iterations.",
    "Warmup iterations are discarded; steady state is not proven. JIT, GC and CPU frequency scaling add noise, which is why a claim needs at least 10 iterations, a significant Mann-Whitney test, a bootstrap interval excluding 1 and separated interquartile ranges.",
    "Container creation, source copy, javac and harness compilation are timed separately and are not part of algorithm runtime.",
    "Equivalence means identical stdout and identical outcome (return, exit code or exception type and message) on the provided workloads; it is not a proof of equivalence. stderr is not compared.",
    "Programs that use threads, wall-clock time, randomness without a fixed seed or files may be reported unstable.",
    "A speedup on the supplied sizes says nothing about other input sizes; use several sizes to see how the difference scales.",
];

#[cfg(test)]
mod tests {
    use super::*;

    fn observation(samples: Vec<u64>, hash: &str) -> WorkloadObservation {
        WorkloadObservation {
            status: "completed".into(),
            samples_ns: samples,
            warmup_ns: vec![],
            stdout_sha256: Some(hash.into()),
            stdout_bytes: Some(4),
            outcome: Some("returned".into()),
            stable_across_iterations: true,
        }
    }

    fn series(center: u64, jitter: u64, n: usize) -> Vec<u64> {
        (0..n as u64)
            .map(|i| center + (i * 7919) % (jitter + 1))
            .collect()
    }

    #[test]
    fn summary_statistics() {
        let s = summarize(&[10, 20, 30, 40, 50]).unwrap();
        assert_eq!(
            (s.median_ns, s.min_ns, s.max_ns, s.p25_ns, s.p75_ns),
            (30, 10, 50, 20, 40)
        );
        assert_eq!(s.mad_ns, 10);
        assert!((s.relative_spread - 20.0 / 30.0).abs() < 1e-9);
        assert!((s.stddev_ns - 15.811).abs() < 0.01);
        assert!(summarize(&[]).is_none());
        assert_eq!(summarize(&[7]).unwrap().median_ns, 7);
    }

    #[test]
    fn mann_whitney_detects_separation_and_not_identity() {
        let slow = series(1_000_000, 50_000, 15);
        let fast = series(200_000, 30_000, 15);
        assert!(mann_whitney_p(&slow, &fast) < 1e-4);
        assert!(mann_whitney_p(&slow, &slow) > 0.9);
        let a = series(500_000, 100_000, 15);
        let b: Vec<u64> = series(500_000, 100_000, 15).iter().rev().cloned().collect();
        assert!(mann_whitney_p(&a, &b) > 0.5);
    }

    #[test]
    fn bootstrap_interval_is_deterministic_and_brackets_the_ratio() {
        let slow = series(1_000_000, 50_000, 15);
        let fast = series(200_000, 30_000, 15);
        let first = bootstrap_ratio_ci(&slow, &fast);
        assert_eq!(first, bootstrap_ratio_ci(&slow, &fast));
        assert!(first.0 > 3.0 && first.1 < 8.0, "{first:?}");
    }

    fn compare(
        base: Vec<u64>,
        cand: Vec<u64>,
        base_hash: &str,
        cand_hash: &str,
    ) -> WorkloadComparison {
        compare_workload(
            "w",
            Some(1000),
            "sha".into(),
            4,
            Some(observation(base, base_hash)),
            Some(observation(cand, cand_hash)),
        )
    }

    #[test]
    fn clear_speedup_with_enough_samples_is_reported() {
        let c = compare(
            series(5_000_000, 200_000, 15),
            series(800_000, 100_000, 15),
            "h",
            "h",
        );
        assert_eq!(c.verdict, Verdict::Faster);
        let speedup = c.speedup.unwrap();
        assert!(speedup > 5.0 && speedup < 7.0, "{speedup}");
        assert!(c.speedup_ci95.unwrap()[0] > 1.0);
        assert_eq!(c.equivalent, Some(true));
    }

    #[test]
    fn a_slowdown_is_reported_as_slower_not_as_speedup_of_the_candidate() {
        let c = compare(
            series(800_000, 100_000, 15),
            series(5_000_000, 200_000, 15),
            "h",
            "h",
        );
        assert_eq!(c.verdict, Verdict::Slower);
        assert!(c.speedup.unwrap() < 0.2);
    }

    #[test]
    fn few_iterations_never_claim_a_speedup() {
        let c = compare(series(5_000_000, 100, 5), series(800_000, 100, 5), "h", "h");
        assert_eq!(c.verdict, Verdict::InsufficientEvidence);
        assert!(c.speedup.is_none());
        assert!(c.notes[0].contains("fewer than 10"));
    }

    #[test]
    fn identical_timings_show_no_difference() {
        let c = compare(
            series(1_000_000, 80_000, 20),
            series(1_000_000, 80_000, 20),
            "h",
            "h",
        );
        assert_eq!(c.verdict, Verdict::NoSignificantDifference);
        assert!(c.speedup.is_none());
    }

    #[test]
    fn different_output_is_not_equivalent_and_timings_are_not_compared() {
        let c = compare(
            series(5_000_000, 100, 15),
            series(800_000, 100, 15),
            "a",
            "b",
        );
        assert_eq!(c.verdict, Verdict::NotEquivalent);
        assert_eq!(c.equivalent, Some(false));
        assert!(c.speedup.is_none() && c.p_value.is_none());
        let overall = overall(&[c]);
        assert_eq!(overall.verdict, Verdict::NotEquivalent);
        assert!(!overall.equivalent_on_all_workloads);
        assert!(overall.summary.contains("not an optimization"));
    }

    #[test]
    fn unstable_output_and_missing_results_make_no_claim() {
        let mut unstable = observation(series(5_000_000, 100, 15), "h");
        unstable.stable_across_iterations = false;
        let c = compare_workload(
            "w",
            None,
            "s".into(),
            1,
            Some(unstable),
            Some(observation(series(1, 1, 15), "h")),
        );
        assert_eq!(c.verdict, Verdict::InsufficientEvidence);
        let c = compare_workload(
            "w",
            None,
            "s".into(),
            1,
            None,
            Some(observation(series(1, 1, 15), "h")),
        );
        assert_eq!(c.verdict, Verdict::Incomplete);
        let mut timed_out = observation(vec![], "h");
        timed_out.status = "timed_out".into();
        let c = compare_workload(
            "w",
            None,
            "s".into(),
            1,
            Some(timed_out),
            Some(observation(series(1, 1, 15), "h")),
        );
        assert_eq!(c.verdict, Verdict::Incomplete);
    }

    #[test]
    fn overall_verdict_combines_workloads() {
        let fast = compare(
            series(5_000_000, 200_000, 15),
            series(800_000, 100_000, 15),
            "h",
            "h",
        );
        let same = compare(
            series(1_000_000, 80_000, 20),
            series(1_000_000, 80_000, 20),
            "h",
            "h",
        );
        assert_eq!(
            overall(&[fast.clone(), fast.clone()]).verdict,
            Verdict::Faster
        );
        let mixed = overall(&[fast, same]);
        assert_eq!(mixed.verdict, Verdict::NoSignificantDifference);
        assert!(mixed.summary.starts_with("mixed"));
        assert!(mixed.equivalent_on_all_workloads);
    }
}
