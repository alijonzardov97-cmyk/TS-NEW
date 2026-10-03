mod app_state;
mod config;
mod error;
mod middleware;
pub mod permissions;
mod routes;
mod services;
mod ws;

use std::sync::Arc;
use std::time::Instant;

use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use crate::app_state::AppState;
use crate::config::Config;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize tracing
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "ts_server=debug,tower_http=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    // Load configuration
    let config = Config::from_env()?;
    tracing::info!("Starting TS server on {}", config.listen_addr);

    // Create database pool and run migrations
    let db_pool = ts_db::pool::create_pool(&config.database_url).await?;
    tracing::info!("Database connected");

    ts_db::pool::run_migrations(&db_pool).await?;
    tracing::info!("Migrations applied");

    // Build application state
    let start_time = Instant::now();
    let state = Arc::new(AppState::new(config.clone(), db_pool, start_time)?);

    // Seed admin user from env var — only if there are zero admins (first-run bootstrap).
    // This prevents ADMIN_USERNAME from silently re-granting admin on every restart.
    if let Some(ref admin_username) = config.admin_username {
        let has_admins = ts_db::repos::user_repo::has_any_admin(&state.db)
            .await
            .unwrap_or(true); // assume admins exist on error (safe default)
        if !has_admins {
            match ts_db::repos::user_repo::ensure_admin(&state.db, admin_username).await {
                Ok(true) => tracing::info!("Granted admin to user '{admin_username}' (first-run bootstrap)"),
                Ok(false) => {
                    tracing::debug!("User '{admin_username}' does not exist yet, skipping admin seed")
                }
                Err(e) => tracing::warn!("Failed to seed admin user '{admin_username}': {e}"),
            }
        } else {
            tracing::debug!("Admin users already exist, skipping ADMIN_USERNAME seed");
        }
    }

    // Spawn background task: typing indicator timeout (10s)
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
            loop {
                interval.tick().await;
                let expired = state
                    .connections
                    .expire_typing(std::time::Duration::from_secs(10));
                for (channel_id, user_id) in expired {
                    state.connections.broadcast_to_channel(
                        channel_id,
                        ts_common::ws_messages::ServerMessage::UserStoppedTyping {
                            channel_id,
                            user_id,
                        },
                    );
                }
            }
        });
    }

    // Spawn background task: broadcast channel cleanup (every 5 minutes)
    {
        let state = state.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
            loop {
                interval.tick().await;
                let removed = state.connections.cleanup_idle_channels();
                if removed > 0 {
                    tracing::debug!("Cleaned up {removed} idle broadcast channels");
                }
            }
        });
    }

    // Spawn background task: periodic data cleanup (every hour)
    {
        let db = state.db.clone();
        tokio::spawn(async move {
            // Wait 1 minute after startup before first cleanup
            tokio::time::sleep(tokio::time::Duration::from_secs(60)).await;
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(3600));
            loop {
                interval.tick().await;
                // Delete refresh tokens expired more than 7 days ago
                match sqlx::query(
                    "DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '7 days'",
                )
                .execute(&db)
                .await
                {
                    Ok(r) => {
                        if r.rows_affected() > 0 {
                            tracing::info!(
                                "Cleaned up {} expired refresh tokens",
                                r.rows_affected()
                            );
                        }
                    }
                    Err(e) => tracing::warn!("Failed to clean expired tokens: {e}"),
                }
                // Delete used one-time prekeys older than 30 days
                match sqlx::query("DELETE FROM one_time_prekeys WHERE used = true AND created_at < NOW() - INTERVAL '30 days'")
                    .execute(&db)
                    .await
                {
                    Ok(r) => {
                        if r.rows_affected() > 0 {
                            tracing::info!("Cleaned up {} used prekeys", r.rows_affected());
                        }
                    }
                    Err(e) => tracing::warn!("Failed to clean used prekeys: {e}"),
                }
                // Prune audit logs older than 90 days
                match sqlx::query(
                    "DELETE FROM audit_log WHERE created_at < NOW() - INTERVAL '90 days'",
                )
                .execute(&db)
                .await
                {
                    Ok(r) => {
                        if r.rows_affected() > 0 {
                            tracing::info!(
                                "Cleaned up {} audit log entries older than 90 days",
                                r.rows_affected()
                            );
                        }
                    }
                    Err(e) => tracing::warn!("Failed to clean old audit logs: {e}"),
                }
                // End orphaned voice sessions (no participants, still active)
                match sqlx::query(
                    "UPDATE voice_sessions SET ended_at = NOW() WHERE ended_at IS NULL AND NOT EXISTS (SELECT 1 FROM voice_session_participants WHERE session_id = voice_sessions.id AND left_at IS NULL)"
                )
                    .execute(&db)
                    .await
                {
                    Ok(r) => {
                        if r.rows_affected() > 0 {
                            tracing::info!("Cleaned up {} orphaned voice sessions", r.rows_affected());
                        }
                    }
                    Err(e) => tracing::warn!("Failed to clean orphaned voice sessions: {e}"),
                }
                // Clean up failed and stale push subscriptions
                match ts_db::repos::push_subscription_repo::cleanup_failed(&db).await {
                    Ok(n) if n > 0 => tracing::info!("Cleaned up {n} failed push subscriptions"),
                    Err(e) => tracing::warn!("Failed to clean push subscriptions: {e}"),
                    _ => {}
                }
                match ts_db::repos::push_subscription_repo::cleanup_stale(&db).await {
                    Ok(n) if n > 0 => tracing::info!("Cleaned up {n} stale push subscriptions"),
                    Err(e) => tracing::warn!("Failed to clean stale push subscriptions: {e}"),
                    _ => {}
                }
            }
        });
    }

    // Spawn background task: soft-delete message GC (daily, 5min startup delay)
    // Hard-deletes messages that were soft-deleted more than 30 days ago
    {
        let db = state.db.clone();
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_secs(300)).await;
            let mut interval =
                tokio::time::interval(tokio::time::Duration::from_secs(24 * 60 * 60));
            loop {
                interval.tick().await;
                match ts_db::repos::message_repo::gc_soft_deleted(&db, 30).await {
                    Ok(0) => {}
                    Ok(n) => {
                        tracing::info!("GC: hard-deleted {n} messages soft-deleted >30 days ago")
                    }
                    Err(e) => tracing::warn!("GC soft-delete cleanup failed: {e}"),
                }
            }
        });
    }

    // Spawn background task: orphan file cleanup (daily, 2h startup delay)
    // Removes disk files with no DB record and DB records with missing disk files
    {
        let db = state.db.clone();
        let storage_path = state.config.file_storage_path.clone();
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_secs(7200)).await;
            let mut interval =
                tokio::time::interval(tokio::time::Duration::from_secs(24 * 60 * 60));
            loop {
                interval.tick().await;

                // Get all known file paths from DB
                let db_files = match ts_db::repos::file_repo::list_all_file_paths(&db).await {
                    Ok(f) => f,
                    Err(e) => {
                        tracing::warn!("Orphan cleanup: failed to list DB files: {e}");
                        continue;
                    }
                };

                let db_paths: std::collections::HashSet<String> =
                    db_files.iter().map(|(_, p)| p.clone()).collect();

                // Walk storage directory and find disk files with no DB record
                let mut orphan_disk_files = 0u64;
                let storage = std::path::Path::new(&storage_path);
                // Directories that store non-file assets (avatars, community/group
                // images).  These are NOT tracked in the `files` table, so they must
                // be skipped or the cleanup will delete them.
                const SKIP_DIRS: &[&str] = &["avatars", "community_assets", "group_assets"];

                if let Ok(mut shard_dirs) = tokio::fs::read_dir(storage).await {
                    while let Ok(Some(shard_entry)) = shard_dirs.next_entry().await {
                        let shard_path = shard_entry.path();
                        if !shard_path.is_dir() {
                            continue;
                        }
                        // Skip asset directories that aren't managed by the files table
                        if let Some(name) = shard_path.file_name().and_then(|n| n.to_str())
                            && SKIP_DIRS.contains(&name)
                        {
                            continue;
                        }
                        if let Ok(mut files) = tokio::fs::read_dir(&shard_path).await {
                            while let Ok(Some(file_entry)) = files.next_entry().await {
                                let file_path = file_entry.path();
                                let path_str = file_path.to_string_lossy().to_string();
                                if !db_paths.contains(&path_str) {
                                    if let Err(e) = tokio::fs::remove_file(&file_path).await {
                                        tracing::warn!(
                                            "Orphan cleanup: failed to remove {path_str}: {e}"
                                        );
                                    } else {
                                        orphan_disk_files += 1;
                                    }
                                }
                            }
                        }
                    }
                }

                if orphan_disk_files > 0 {
                    tracing::info!(
                        "Orphan cleanup: removed {orphan_disk_files} disk files with no DB record"
                    );
                }
            }
        });
    }

    // Spawn background task: message expiry cleanup (every 5 min)
    {
        let db = state.db.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
            loop {
                interval.tick().await;
                match ts_db::repos::message_repo::delete_expired_messages(&db).await {
                    Ok(0) => {}
                    Ok(n) => tracing::info!("Expired {n} messages past their TTL"),
                    Err(e) => tracing::warn!("Failed to delete expired messages: {e}"),
                }
            }
        });
    }

    // Spawn background task: timeout cleanup (every 5 min)
    {
        let db = state.db.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(300));
            loop {
                interval.tick().await;
                let _ = ts_db::repos::timeout_repo::cleanup_expired(&db).await;
            }
        });
    }

    // Load instance settings from DB into memory
    match ts_db::repos::settings_repo::list_all(&state.db).await {
        Ok(rows) => {
            let mut settings = state.instance_settings.write().await;
            for row in &rows {
                match row.key.as_str() {
                    "max_messages_cache" => {
                        if let Ok(v) = row.value.parse::<u32>() {
                            settings.max_messages_cache = v.clamp(50, 10_000);
                        }
                    }
                    "max_pins_per_channel" => {
                        if let Ok(v) = row.value.parse::<i64>() {
                            settings.max_pins_per_channel = v.clamp(1, 200);
                        }
                    }
                    "e2e_enabled" => {
                        settings.e2e_enabled = row.value == "true";
                    }
                    _ => {}
                }
            }
            tracing::info!(
                "Instance settings: max_messages_cache={}, max_pins_per_channel={}, e2e_enabled={}",
                settings.max_messages_cache,
                settings.max_pins_per_channel,
                settings.e2e_enabled
            );
        }
        Err(e) => tracing::warn!("Failed to load instance settings: {e}"),
    }

    // Populate the in-memory suspended users set
    match ts_db::repos::user_repo::get_suspended_user_ids(&state.db).await {
        Ok(ids) => {
            for id in &ids {
                state.suspended_users.insert(*id);
            }
            if !ids.is_empty() {
                tracing::info!("Loaded {} suspended user(s) into memory", ids.len());
            }
        }
        Err(e) => tracing::warn!("Failed to load suspended users: {e}"),
    }

    // Build the router
    let app = routes::build_router(state.clone());

    // Start the server
    let listener = tokio::net::TcpListener::bind(&config.listen_addr).await?;
    tracing::info!("Listening on {}", config.listen_addr);

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await?;

    tracing::info!("Server shut down gracefully");
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }

    tracing::info!("Shutdown signal received");
}
