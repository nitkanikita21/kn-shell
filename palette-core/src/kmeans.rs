//! k-means++ clustering in OKLab space, run in parallel via `rayon`.

use crate::error::{cancelled, PaletteError};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, Ordering};

/// A single cluster: OKLab center + fraction of all points it owns.
#[derive(Clone, Debug)]
pub struct Cluster {
    pub center: (f64, f64, f64),
    pub weight: f64,
}

/// Deterministic xorshift64 PRNG so a given seed always yields the same
/// initialization (and therefore the same palette).
struct XorShift64 {
    state: u64,
}

impl XorShift64 {
    fn new(seed: u64) -> Self {
        Self {
            state: seed | 1, // never 0
        }
    }
    fn next_u64(&mut self) -> u64 {
        let mut x = self.state;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.state = x;
        x
    }
    fn next_f64(&mut self) -> f64 {
        // 53-bit mantissa, like `rand`.
        let v = self.next_u64() >> 11;
        (v as f64) / ((1u64 << 53) as f64)
    }
}

/// k-means++ initialization. Picks the first center uniformly, then each
/// subsequent center with probability proportional to the squared distance from
/// the nearest already-chosen center.
fn kmeans_plus_plus(
    points: &[(f64, f64, f64)],
    k: usize,
    rng: &mut XorShift64,
) -> Vec<(f64, f64, f64)> {
    let n = points.len();
    let mut centers = Vec::with_capacity(k);
    let first = (rng.next_u64() as usize) % n;
    centers.push(points[first]);

    let mut d2 = vec![f64::MAX; n];
    for _ in 1..k {
        let mut total = 0.0;
        for (i, p) in points.iter().enumerate() {
            let mut best = f64::MAX;
            for c in &centers {
                let dl = p.0 - c.0;
                let da = p.1 - c.1;
                let db = p.2 - c.2;
                let dist = dl * dl + da * da + db * db;
                if dist < best {
                    best = dist;
                }
            }
            d2[i] = best;
            total += best;
        }
        if total == 0.0 {
            // All remaining points coincide with chosen centers; just repeat one.
            centers.push(centers[(rng.next_u64() as usize) % centers.len()]);
            continue;
        }
        let mut target = rng.next_f64() * total;
        let mut chosen = 0;
        for (i, &val) in d2.iter().enumerate().take(n) {
            target -= val;
            if target <= 0.0 {
                chosen = i;
                break;
            }
        }
        centers.push(points[chosen]);
    }
    centers
}

/// Run k-means in OKLab until convergence or `max_iters`.
///
/// `cancel` (when `Some`) is checked between iterations; if set, clustering
/// stops early and [`crate::error::cancelled`] is returned.
pub fn kmeans_oklab(
    points: &[(f64, f64, f64)],
    k: usize,
    seed: Option<u64>,
    cancel: Option<&AtomicBool>,
) -> Result<Vec<Cluster>, PaletteError> {
    let n = points.len();
    let k = k.max(1).min(n);
    let mut rng = XorShift64::new(seed.unwrap_or_else(|| {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0x1234_5678_9ABC_DEF1)
    }));

    let mut centers = kmeans_plus_plus(points, k, &mut rng);
    let mut assignments = vec![0usize; n];

    const MAX_ITERS: usize = 24;
    const SHIFT_EPS: f64 = 1e-4;

    for _ in 0..MAX_ITERS {
        if let Some(c) = cancel {
            if c.load(Ordering::Relaxed) {
                return Err(cancelled());
            }
        }

        // E-step: assign every point to its nearest center (parallel).
        let changed = assignments
            .par_iter_mut()
            .enumerate()
            .map(|(i, slot)| {
                let p = points[i];
                let mut best = 0usize;
                let mut best_d = f64::MAX;
                for (ci, c) in centers.iter().enumerate() {
                    let dl = p.0 - c.0;
                    let da = p.1 - c.1;
                    let db = p.2 - c.2;
                    let d = dl * dl + da * da + db * db;
                    if d < best_d {
                        best_d = d;
                        best = ci;
                    }
                }
                let prev = *slot;
                *slot = best;
                prev != best
            })
            .reduce(|| false, |a, b| a | b);

        // M-step: recompute centers as the mean of assigned points.
        let mut sums = vec![(0.0f64, 0.0f64, 0.0f64, 0.0f64); k]; // l, a, b, count
        for (i, &ci) in assignments.iter().enumerate() {
            let p = points[i];
            sums[ci].0 += p.0;
            sums[ci].1 += p.1;
            sums[ci].2 += p.2;
            sums[ci].3 += 1.0;
        }

        let mut max_shift = 0.0f64;
        for (ci, s) in sums.iter().enumerate() {
            let new_center = if s.3 > 0.0 {
                (s.0 / s.3, s.1 / s.3, s.2 / s.3)
            } else {
                // Empty cluster: reseed to the point farthest from all centers.
                let mut far = 0usize;
                let mut far_d = -1.0f64;
                for (i, p) in points.iter().enumerate() {
                    let mut best = f64::MAX;
                    for c in &centers {
                        let dl = p.0 - c.0;
                        let da = p.1 - c.1;
                        let db = p.2 - c.2;
                        let d = dl * dl + da * da + db * db;
                        if d < best {
                            best = d;
                        }
                    }
                    if best > far_d {
                        far_d = best;
                        far = i;
                    }
                }
                points[far]
            };
            let old = centers[ci];
            let shift = ((new_center.0 - old.0).powi(2)
                + (new_center.1 - old.1).powi(2)
                + (new_center.2 - old.2).powi(2))
            .sqrt();
            if shift > max_shift {
                max_shift = shift;
            }
            centers[ci] = new_center;
        }

        if !changed && max_shift < SHIFT_EPS {
            break;
        }
    }

    // Final weights.
    let mut counts = vec![0.0f64; k];
    for &ci in &assignments {
        counts[ci] += 1.0;
    }
    let total = n as f64;
    let mut clusters = Vec::with_capacity(k);
    for (ci, c) in centers.into_iter().enumerate() {
        clusters.push(Cluster {
            center: c,
            weight: counts[ci] / total,
        });
    }
    Ok(clusters)
}

/// Merge clusters whose OKLab centers are within `eps` of each other, summing
/// their weights. This collapses degenerate cases (e.g. a solid-color image
/// produces many identical centers) and near-duplicate clusters.
pub fn merge_clusters(clusters: Vec<Cluster>, eps: f64) -> Vec<Cluster> {
    let mut merged: Vec<Cluster> = Vec::new();
    for c in clusters {
        match merged
            .iter_mut()
            .find(|m| crate::color::oklab_distance(m.center, c.center) < eps)
        {
            Some(m) => m.weight += c.weight,
            None => merged.push(c),
        }
    }
    merged
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::srgb_u8_to_oklab;

    fn build_points(colors: &[(u8, u8, u8)], per: usize) -> Vec<(f64, f64, f64)> {
        let mut v = Vec::new();
        for &(r, g, b) in colors {
            let (l, a, bb) = srgb_u8_to_oklab(r, g, b);
            for _ in 0..per {
                v.push((l, a, bb));
            }
        }
        v
    }

    #[test]
    fn deterministic_with_seed() {
        let pts = build_points(&[(255, 0, 0), (0, 0, 255)], 50);
        let a = kmeans_oklab(&pts, 2, Some(42), None).unwrap();
        let b = kmeans_oklab(&pts, 2, Some(42), None).unwrap();
        let mut wa: Vec<_> = a.iter().map(|c| c.weight).collect();
        let mut wb: Vec<_> = b.iter().map(|c| c.weight).collect();
        wa.sort_by(|x, y| x.partial_cmp(y).unwrap());
        wb.sort_by(|x, y| x.partial_cmp(y).unwrap());
        for (x, y) in wa.iter().zip(wb.iter()) {
            assert!((x - y).abs() < 1e-9, "weights differ");
        }
    }

    #[test]
    fn two_tone_returns_two_clusters() {
        let pts = build_points(&[(255, 0, 0), (0, 200, 255)], 100);
        let clusters = kmeans_oklab(&pts, 6, Some(1), None).unwrap();
        let merged = merge_clusters(clusters, 1e-3);
        assert_eq!(merged.len(), 2, "expected 2 clusters, got {}", merged.len());
        for c in &merged {
            assert!((c.weight - 0.5).abs() < 0.05, "weight {}", c.weight);
        }
    }

    #[test]
    fn solid_color_merges_to_one() {
        let pts = build_points(&[(255, 0, 0)], 50);
        let clusters = kmeans_oklab(&pts, 6, Some(7), None).unwrap();
        let merged = merge_clusters(clusters, 1e-3);
        assert_eq!(merged.len(), 1);
        assert!((merged[0].weight - 1.0).abs() < 1e-6);
    }
}
