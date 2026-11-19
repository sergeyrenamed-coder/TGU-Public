//! Locally checked warm standby candidates. Cloud RU proof only decides probe order.
//! Keep a small ephemeral 10-minute pool, never swap upstream solely on stale proof.
use std::{
    collections::HashSet,
    sync::{atomic::{AtomicBool, Ordering}, Mutex, OnceLock},
    time::{Duration, Instant},
};
use crate::proxy::{CatalogRecord, is_supported_profile_record, run_proxy_test};

const MAX_STANDBYS: usize = 5;
const STANDBY_TTL: Duration = Duration::from_secs(10 * 60);
const SCAN_PAUSE: Duration = Duration::from_millis(1600);

#[derive(Clone)]
struct Warm {
    id: String,
    latency_ms: u64,
    checked: Instant,
}
static CACHE: OnceLock<Mutex<Vec<Warm>>> = OnceLock::new();
static SCANNING: AtomicBool = AtomicBool::new(false);
static PINNED_ID: OnceLock<Mutex<Option<String>>> = OnceLock::new();

fn pinned() -> &'static Mutex<Option<String>> {
    PINNED_ID.get_or_init(|| Mutex::new(None))
}

/// A Fast Connect route must not be replaced merely because a faster backup
/// appears; only health-check failure should switch the active upstream.
pub(crate) fn is_fast_pinned(active_id: &str) -> bool {
    pinned().lock().ok()
        .and_then(|p| p.clone())
        .as_deref() == Some(active_id)
}


fn cache() -> &'static Mutex<Vec<Warm>> {
    CACHE.get_or_init(|| Mutex::new(Vec::new()))
}
fn cleanup(items: &mut Vec<Warm>) {
    items.retain(|item| item.checked.elapsed() < STANDBY_TTL);
}

pub(crate) fn standby_count() -> usize {
    cache().lock().map(|mut items| {
        cleanup(&mut items);
        items.len()
    }).unwrap_or(0)
}
pub(crate) fn is_scanning() -> bool {
    SCANNING.load(Ordering::Acquire)
}

struct ScanGuard;
impl Drop for ScanGuard {
    fn drop(&mut self) { SCANNING.store(false, Ordering::Release); }
}

pub(crate) fn start_scan(mut records: Vec<CatalogRecord>, active_id: String, ru_ids: HashSet<String>) {
    // Set the active Fast Connect route even when a previous scan is in progress.
    if let Ok(mut p) = pinned().lock() { *p = Some(active_id.clone()); }
    if SCANNING.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_err() {
        return;
    }
    records.retain(|r| r.id != active_id && is_supported_profile_record(r));
    records.sort_by(|a,b| ru_ids.contains(&b.id).cmp(&ru_ids.contains(&a.id))
        .then_with(|| a.rtt_ms.unwrap_or(u64::MAX).cmp(&b.rtt_ms.unwrap_or(u64::MAX))));
    tokio::spawn(async move {
        let _reset = ScanGuard;
        for record in records {
            let enough = cache().lock().map(|mut c| {
                cleanup(&mut c);
                c.len() >= MAX_STANDBYS
            }).unwrap_or(true);
            if enough { break; }
            let already = cache().lock().map(|c| c.iter().any(|r| r.id == record.id)).unwrap_or(true);
            if already { continue; }
            let first = tokio::task::spawn_blocking({
                let record = record.clone();
                move || run_proxy_test(record)
            }).await.ok();
            if first.as_ref().is_some_and(|r| r.success) {
                let second = tokio::task::spawn_blocking({
                    let record = record.clone();
                    move || run_proxy_test(record)
                }).await.ok();
                if let (Some(a), Some(b)) = (first, second) {
                    if a.success && b.success {
                        if let Ok(mut c) = cache().lock() {
                            cleanup(&mut c);
                            c.retain(|p| p.id != record.id);
                            c.push(Warm {
                                id: record.id,
                                latency_ms: a.elapsed_ms.saturating_add(b.elapsed_ms) / 2,
                                checked: Instant::now(),
                            });
                            c.sort_by_key(|p| p.latency_ms);
                            c.truncate(MAX_STANDBYS);
                        }
                    }
                }
            }
            // The scan must not saturate Telegram traffic while user is online.
            tokio::time::sleep(SCAN_PAUSE).await;
        }
    });
}

/// Recheck cached candidates against the *current* catalog and current network.
pub(crate) async fn verified_backup(
    records: &[CatalogRecord],
    excluded: &HashSet<String>,
) -> Option<(CatalogRecord, u64)> {
    let candidates = cache().lock().ok().map(|mut c| {
        cleanup(&mut c);
        c.clone()
    }).unwrap_or_default();
    for warm in candidates {
        if excluded.contains(&warm.id) { continue; }
        let Some(record) = records.iter().find(|r| r.id == warm.id
            && is_supported_profile_record(r)) else { continue };
        let a = tokio::task::spawn_blocking({
            let r = record.clone();
            move || run_proxy_test(r)
        }).await.ok();
        let b = if a.as_ref().is_some_and(|r| r.success) {
            tokio::task::spawn_blocking({
                let r = record.clone();
                move || run_proxy_test(r)
            }).await.ok()
        } else { None };
        if let (Some(a), Some(b)) = (a,b) {
            if a.success && b.success {
                return Some((record.clone(), a.elapsed_ms.saturating_add(b.elapsed_ms)/2));
            }
        }
        if let Ok(mut c) = cache().lock() { c.retain(|p| p.id != warm.id); }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn evicts_stale_probes() {
        let mut c = vec![
            Warm { id:"old".into(), latency_ms:20,
                   checked: Instant::now() - Duration::from_secs(601) },
            Warm { id:"new".into(), latency_ms:21, checked: Instant::now() },
        ];
        cleanup(&mut c);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].id, "new");
    }
}
