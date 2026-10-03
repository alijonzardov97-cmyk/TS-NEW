use std::sync::LazyLock;
use std::time::{Duration, Instant};

use axum::extract::{Query, State};
use axum::routing::get;
use axum::{Extension, Json, Router};
use dashmap::DashMap;
use regex::Regex;
use std::sync::Arc;

use ts_common::api_types::{LinkPreviewQuery, LinkPreviewResponse};

use crate::app_state::AppState;
use crate::error::AppError;
use crate::middleware::auth::AccessClaims;

struct CacheEntry {
    data: LinkPreviewResponse,
    created: Instant,
}

static PREVIEW_CACHE: LazyLock<DashMap<String, CacheEntry>> = LazyLock::new(DashMap::new);
const CACHE_TTL: Duration = Duration::from_secs(3600);
const MAX_CACHE_SIZE: usize = 1000;

// Pre-compiled regexes for OG metadata parsing (avoids repeated compilation and unwrap panics)
static META_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"<meta\s+[^>]*?(?:property|name)=["']([^"']+)["'][^>]*?content=["']([^"']*)["']"#)
        .expect("META_RE is a valid regex")
});
static META_RE2: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"<meta\s+[^>]*?content=["']([^"']*)["'][^>]*?(?:property|name)=["']([^"']+)["']"#)
        .expect("META_RE2 is a valid regex")
});
static TITLE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"<title[^>]*>([^<]+)</title>").expect("TITLE_RE is a valid regex")
});

pub fn routes() -> Router<Arc<AppState>> {
    Router::new().route("/link-preview", get(get_link_preview))
}

async fn get_link_preview(
    State(_state): State<Arc<AppState>>,
    Extension(_claims): Extension<AccessClaims>,
    Query(query): Query<LinkPreviewQuery>,
) -> Result<Json<LinkPreviewResponse>, AppError> {
    let url = query.url.trim().to_string();

    // Validate URL length and scheme
    if url.is_empty() || url.len() > 2048 {
        return Err(AppError::Validation(
            "URL must be 1-2048 characters".into(),
        ));
    }
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Err(AppError::Validation(
            "Only http/https URLs are allowed".into(),
        ));
    }

    // Check cache
    if let Some(entry) = PREVIEW_CACHE.get(&url) {
        if entry.created.elapsed() < CACHE_TTL {
            return Ok(Json(entry.data.clone()));
        }
        drop(entry);
        PREVIEW_CACHE.remove(&url);
    }

    // Fetch the URL. Every hop (including redirects) is resolved and validated
    // BEFORE a connection is made, and the connection is pinned to the validated
    // address, so neither DNS rebinding nor redirects can reach internal hosts.
    let mut response = fetch_public(&url).await?;

    // Only process HTML responses
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();

    if !content_type.contains("text/html") {
        let preview = LinkPreviewResponse {
            url: url.clone(),
            title: None,
            description: None,
            image: None,
            site_name: None,
        };
        cache_preview(&url, &preview);
        return Ok(Json(preview));
    }

    // Limit response body to 512KB using chunked reads (prevents OOM from malicious servers)
    const MAX_BODY_SIZE: usize = 512_000;
    let mut body_buf = Vec::with_capacity(MAX_BODY_SIZE.min(65_536));
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| AppError::Validation("Could not read response".into()))?
    {
        let remaining = MAX_BODY_SIZE.saturating_sub(body_buf.len());
        if remaining == 0 {
            break;
        }
        body_buf.extend_from_slice(&chunk[..chunk.len().min(remaining)]);
    }
    let body = String::from_utf8_lossy(&body_buf);

    let preview = parse_og_metadata(&body, &url);
    cache_preview(&url, &preview);

    Ok(Json(preview))
}

const MAX_REDIRECTS: usize = 3;

/// Fetch a URL while refusing any connection to a non-public address.
async fn fetch_public(start: &str) -> Result<reqwest::Response, AppError> {
    let mut current = url::Url::parse(start)
        .map_err(|_| AppError::Validation("Invalid URL".into()))?;

    for _ in 0..=MAX_REDIRECTS {
        if !matches!(current.scheme(), "http" | "https") {
            return Err(AppError::Validation("Only http/https URLs are allowed".into()));
        }
        // Reject credentials embedded in URLs.
        if !current.username().is_empty() || current.password().is_some() {
            return Err(AppError::Validation("URLs with credentials are not allowed".into()));
        }
        let host = current
            .host_str()
            .ok_or_else(|| AppError::Validation("Invalid URL".into()))?
            .to_string();
        if is_private_host(&host) {
            return Err(AppError::Validation("Cannot preview internal URLs".into()));
        }
        let port = current.port_or_known_default().unwrap_or(443);

        let addrs: Vec<std::net::SocketAddr> = tokio::net::lookup_host((host.as_str(), port))
            .await
            .map_err(|_| AppError::Validation("Could not fetch URL".into()))?
            .collect();
        if addrs.is_empty() || addrs.iter().any(|a| is_private_ip(a.ip())) {
            return Err(AppError::Validation("Cannot preview internal URLs".into()));
        }

        // One-shot client pinned to the validated address; redirects handled manually.
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(10))
            .resolve(&host, addrs[0])
            .build()
            .map_err(|_| AppError::Validation("Could not fetch URL".into()))?;

        let response = client
            .get(current.clone())
            .header(reqwest::header::USER_AGENT, "TSBot/1.0 (link preview)")
            .send()
            .await
            .map_err(|e| {
                tracing::debug!("Link preview fetch failed for {current}: {e}");
                AppError::Validation("Could not fetch URL".into())
            })?;

        if response.status().is_redirection() {
            let location = response
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .ok_or_else(|| AppError::Validation("Could not fetch URL".into()))?;
            current = current
                .join(location)
                .map_err(|_| AppError::Validation("Could not fetch URL".into()))?;
            continue;
        }
        return Ok(response);
    }
    Err(AppError::Validation("Too many redirects".into()))
}

fn cache_preview(url: &str, preview: &LinkPreviewResponse) {
    if PREVIEW_CACHE.len() >= MAX_CACHE_SIZE {
        // Evict expired entries
        let to_remove: Vec<String> = PREVIEW_CACHE
            .iter()
            .filter(|e| e.created.elapsed() > CACHE_TTL)
            .map(|e| e.key().clone())
            .take(MAX_CACHE_SIZE / 10)
            .collect();
        for key in to_remove {
            PREVIEW_CACHE.remove(&key);
        }
    }
    PREVIEW_CACHE.insert(
        url.to_string(),
        CacheEntry {
            data: preview.clone(),
            created: Instant::now(),
        },
    );
}

fn parse_og_metadata(html: &str, url: &str) -> LinkPreviewResponse {
    let mut title = None;
    let mut description = None;
    let mut image = None;
    let mut site_name = None;

    for cap in META_RE.captures_iter(html) {
        let prop = cap[1].to_lowercase();
        let content = html_escape::decode_html_entities(&cap[2]).to_string();
        match prop.as_str() {
            "og:title" => title = Some(content),
            "og:description" => description = Some(content),
            "description" if description.is_none() => description = Some(content),
            "og:image" => image = Some(content),
            "og:site_name" => site_name = Some(content),
            _ => {}
        }
    }

    for cap in META_RE2.captures_iter(html) {
        let content = html_escape::decode_html_entities(&cap[1]).to_string();
        let prop = cap[2].to_lowercase();
        match prop.as_str() {
            "og:title" if title.is_none() => title = Some(content),
            "og:description" if description.is_none() => description = Some(content),
            "description" if description.is_none() => description = Some(content),
            "og:image" if image.is_none() => image = Some(content),
            "og:site_name" if site_name.is_none() => site_name = Some(content),
            _ => {}
        }
    }

    // Fallback: extract <title> tag
    if title.is_none()
        && let Some(cap) = TITLE_RE.captures(html)
    {
        title = Some(html_escape::decode_html_entities(&cap[1]).to_string());
    }

    // Make relative image URLs absolute
    if let Some(ref img) = image {
        if img.starts_with("//") {
            image = Some(format!("https:{img}"));
        } else if img.starts_with('/')
            && let Ok(parsed) = url::Url::parse(url)
        {
            image = Some(format!(
                "{}://{}{}",
                parsed.scheme(),
                parsed.host_str().unwrap_or(""),
                img
            ));
        }
    }

    // Only allow http/https image URLs; truncate to prevent oversized data URIs
    let image = image.filter(|i| {
        i.starts_with("http://") || i.starts_with("https://") || i.starts_with("//")
    });

    LinkPreviewResponse {
        url: url.to_string(),
        title: title.map(|t| truncate_str(t, 200)),
        description: description.map(|d| truncate_str(d, 500)),
        image: image.map(|i| truncate_str(i, 2048)),
        site_name: site_name.map(|s| truncate_str(s, 100)),
    }
}

fn truncate_str(s: String, max: usize) -> String {
    if s.len() <= max {
        s
    } else {
        match s.char_indices().nth(max) {
            Some((idx, _)) => format!("{}...", &s[..idx]),
            None => s,
        }
    }
}

/// Evict expired entries from the link preview cache.
pub fn cleanup_preview_cache() -> usize {
    let to_remove: Vec<String> = PREVIEW_CACHE
        .iter()
        .filter(|e| e.created.elapsed() > CACHE_TTL)
        .map(|e| e.key().clone())
        .collect();
    let count = to_remove.len();
    for k in to_remove {
        PREVIEW_CACHE.remove(&k);
    }
    count
}

fn is_private_v4(v4: std::net::Ipv4Addr) -> bool {
    let o = v4.octets();
    v4.is_private()
        || v4.is_loopback()
        || v4.is_link_local()
        || v4.is_unspecified()
        || v4.is_broadcast()
        || v4.is_multicast()
        || o[0] == 0
        || (o[0] == 100 && (o[1] & 0xc0) == 64) // 100.64.0.0/10 CGNAT
        || (o[0] == 192 && o[1] == 0 && o[2] == 0) // 192.0.0.0/24
        || (o[0] == 192 && o[1] == 0 && o[2] == 2) // TEST-NET-1
        || (o[0] == 198 && (o[1] & 0xfe) == 18) // 198.18.0.0/15 benchmarking
        || (o[0] == 198 && o[1] == 51 && o[2] == 100) // TEST-NET-2
        || (o[0] == 203 && o[1] == 0 && o[2] == 113) // TEST-NET-3
        || o[0] >= 240 // reserved
}

fn is_private_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => is_private_v4(v4),
        std::net::IpAddr::V6(v6) => {
            if v6.is_loopback() || v6.is_unspecified() || v6.is_multicast() {
                return true;
            }
            let seg = v6.segments();
            if seg[0] & 0xffc0 == 0xfe80 {
                return true; // link-local fe80::/10
            }
            if seg[0] & 0xfe00 == 0xfc00 {
                return true; // unique local fc00::/7
            }
            if seg[0] == 0x2001 && seg[1] == 0x0db8 {
                return true; // documentation 2001:db8::/32
            }
            if seg[0] == 0x2002 {
                return true; // 6to4 (embeds an IPv4 address)
            }
            if seg[0] == 0x0064 && seg[1] == 0xff9b {
                return true; // NAT64 64:ff9b::/96
            }
            if let Some(v4) = v6.to_ipv4_mapped() {
                return is_private_v4(v4);
            }
            // IPv4-compatible ::a.b.c.d
            if seg[..6].iter().all(|&x| x == 0) {
                return true;
            }
            false
        }
    }
}

fn is_private_host(host: &str) -> bool {
    if host == "localhost" || host == "0.0.0.0" {
        return true;
    }
    // Strip IPv6 brackets as returned by Url::host_str().
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return is_private_ip(ip);
    }
    let h = host.trim_end_matches('.').to_ascii_lowercase();
    if h.ends_with(".local")
        || h.ends_with(".internal")
        || h.ends_with(".localhost")
        || h.ends_with(".lan")
        || h.ends_with(".home.arpa")
        || !h.contains('.') && h.parse::<std::net::IpAddr>().is_err()
    {
        return true;
    }
    false
}
