use serde::Serialize;
use std::{net::SocketAddr, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
use tauri::AppHandle;
use tokio::{net::TcpStream, time::timeout};

const TCP_TIMEOUT: Duration = Duration::from_millis(1500);
const HTTP_TIMEOUT: Duration = Duration::from_secs(6);
const DC_PARALLELISM: usize = 8;

#[derive(Debug, Clone, Serialize)]
pub struct NetworkCheck {
    pub host: String,
    pub tcp_443: bool,
    pub tls_http: bool,
    pub http_status: Option<u16>,
    pub elapsed_ms: u64,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DcPortCheck {
    pub dc_label: String,
    pub address: String,
    pub address_family: String,
    pub source: String,
    pub port: u16,
    pub reachable: bool,
    pub elapsed_ms: u64,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DiagnosticsSnapshot {
    pub generated_at_ms: u64,
    pub network_checks: Vec<NetworkCheck>,
    pub dc_checks: Vec<DcPortCheck>,
    pub network_successes: usize,
    pub network_total: usize,
    pub dc_successes: usize,
    pub dc_total: usize,
}

#[derive(Clone, Copy)]
struct DcTarget {
    label: &'static str,
    address: &'static str,
    family: &'static str,
    source: &'static str,
}

const TARGETS: &[DcTarget] = &[
    DcTarget { label: "DC1", address: "149.154.175.53", family: "IPv4", source: "Pyrogram reference" },
    DcTarget { label: "DC1", address: "2001:b28:f23d:f001::a", family: "IPv6", source: "Pyrogram reference" },
    DcTarget { label: "DC2", address: "149.154.167.51", family: "IPv4", source: "Pyrogram / OONI reference" },
    DcTarget { label: "DC2", address: "2001:67c:4e8:f002::a", family: "IPv6", source: "Pyrogram reference" },
    DcTarget { label: "DC3", address: "149.154.175.100", family: "IPv4", source: "Pyrogram / OONI reference" },
    DcTarget { label: "DC3", address: "2001:b28:f23d:f003::a", family: "IPv6", source: "Pyrogram reference" },
    DcTarget { label: "DC4", address: "149.154.167.91", family: "IPv4", source: "Pyrogram / OONI reference" },
    DcTarget { label: "DC4", address: "2001:67c:4e8:f004::a", family: "IPv6", source: "Pyrogram reference" },
    DcTarget { label: "DC5", address: "91.108.56.130", family: "IPv4", source: "Pyrogram reference" },
    DcTarget { label: "DC5", address: "2001:b28:f23f:f005::a", family: "IPv6", source: "Pyrogram reference" },
    DcTarget { label: "Additional access point", address: "149.154.175.50", family: "IPv4", source: "OONI Telegram test reference" },
    DcTarget { label: "Additional access point", address: "149.154.171.5", family: "IPv4", source: "OONI Telegram test reference" },
    DcTarget { label: "Additional access point", address: "95.161.76.100", family: "IPv4", source: "OONI Telegram test reference" },
];

const NETWORK_HOSTS: &[&str] = &[
    "web.telegram.org",
    "api.telegram.org",
    "telegram.org",
    "t.me",
    "example.com",
    "www.cloudflare.com",
];

async fn check_network_host(host: &'static str) -> NetworkCheck {
    let started = Instant::now();
    let tcp = timeout(TCP_TIMEOUT, TcpStream::connect((host, 443))).await;
    let tcp_443 = matches!(tcp, Ok(Ok(_)));
    if !tcp_443 {
        let detail = match tcp {
            Err(_) => "TCP/443 timed out".to_string(),
            Ok(Err(error)) => format!("TCP/443 failed: {error}"),
            Ok(Ok(_)) => "TCP/443 connected".to_string(),
        };
        return NetworkCheck {
            host: host.into(),
            tcp_443: false,
            tls_http: false,
            http_status: None,
            elapsed_ms: started.elapsed().as_millis() as u64,
            detail,
        };
    }

    let client = match reqwest::Client::builder()
        .connect_timeout(TCP_TIMEOUT)
        .timeout(HTTP_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .user_agent("TGU-Desktop-Diagnostics/0.4")
        .build()
    {
        Ok(client) => client,
        Err(error) => return NetworkCheck {
            host: host.into(),
            tcp_443: true,
            tls_http: false,
            http_status: None,
            elapsed_ms: started.elapsed().as_millis() as u64,
            detail: format!("HTTP client error: {error}"),
        },
    };

    match client.get(format!("https://{host}/")).send().await {
        Ok(response) => NetworkCheck {
            host: host.into(),
            tcp_443: true,
            tls_http: true,
            http_status: Some(response.status().as_u16()),
            elapsed_ms: started.elapsed().as_millis() as u64,
            detail: format!("TCP + TLS established; HTTP {}", response.status().as_u16()),
        },
        Err(error) => NetworkCheck {
            host: host.into(),
            tcp_443: true,
            tls_http: false,
            http_status: None,
            elapsed_ms: started.elapsed().as_millis() as u64,
            detail: format!("TCP connected; TLS/HTTP failed: {error}"),
        },
    }
}

async fn check_dc_target(target: DcTarget, port: u16) -> DcPortCheck {
    let started = Instant::now();
    let result = timeout(TCP_TIMEOUT, TcpStream::connect((target.address, port))).await;
    let (reachable, detail) = match result {
        Ok(Ok(_)) => (true, "TCP connection established".to_string()),
        Err(_) => (false, "Connection timed out".to_string()),
        Ok(Err(error)) if error.kind() == std::io::ErrorKind::ConnectionRefused =>
            (false, "Connection refused".to_string()),
        Ok(Err(error)) => (false, format!("Network error: {error}")),
    };
    DcPortCheck {
        dc_label: target.label.into(),
        address: target.address.into(),
        address_family: target.family.into(),
        source: target.source.into(),
        port,
        reachable,
        elapsed_ms: started.elapsed().as_millis() as u64,
        detail,
    }
}

#[tauri::command]
pub async fn diagnostics_run(_app: AppHandle) -> Result<DiagnosticsSnapshot, String> {
    let mut network_tasks = futures_util::stream::FuturesUnordered::new();
    for host in NETWORK_HOSTS {
        network_tasks.push(check_network_host(host));
    }
    let mut network_checks = Vec::new();
    while let Some(result) = futures_util::StreamExt::next(&mut network_tasks).await {
        network_checks.push(result);
    }
    network_checks.sort_by(|a, b| a.host.cmp(&b.host));

    let semaphore = std::sync::Arc::new(tokio::sync::Semaphore::new(DC_PARALLELISM));
    let mut dc_tasks = futures_util::stream::FuturesUnordered::new();
    for target in TARGETS.iter().copied() {
        for port in [80u16, 443u16] {
            let semaphore = semaphore.clone();
            dc_tasks.push(async move {
                let permit = semaphore.acquire_owned().await
                    .map_err(|e| format!("Diagnostics semaphore failed: {e}"))?;
                let result = check_dc_target(target, port).await;
                drop(permit);
                Ok::<_, String>(result)
            });
        }
    }

    let mut dc_checks = Vec::new();
    while let Some(result) = futures_util::StreamExt::next(&mut dc_tasks).await {
        dc_checks.push(result?);
    }
    dc_checks.sort_by(|a, b| {
        a.dc_label.cmp(&b.dc_label)
            .then_with(|| a.address_family.cmp(&b.address_family))
            .then_with(|| a.address.cmp(&b.address))
            .then_with(|| a.port.cmp(&b.port))
    });

    let network_successes = network_checks.iter().filter(|check| check.tls_http).count();
    let dc_successes = dc_checks.iter().filter(|check| check.reachable).count();
    Ok(DiagnosticsSnapshot {
        generated_at_ms: SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64,
        network_total: network_checks.len(),
        network_successes,
        dc_total: dc_checks.len(),
        dc_successes,
        network_checks,
        dc_checks,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn includes_reference_ports_for_all_five_telegram_data_centers() {
        let labels: std::collections::HashSet<_> = TARGETS.iter().map(|target| target.label).collect();
        for dc in ["DC1", "DC2", "DC3", "DC4", "DC5"] {
            assert!(labels.contains(dc));
        }
        assert!(TARGETS.iter().any(|target| target.family == "IPv6"));
        assert_eq!(NETWORK_HOSTS.len(), 6);
    }
}
