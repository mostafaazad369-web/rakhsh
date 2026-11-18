#[cfg(feature = "acme")]
mod acme;
mod carrier;
pub mod cli;
mod config;
#[cfg_attr(not(any(target_os = "windows", target_os = "macos")), allow(dead_code))]
mod craft;
mod dgram;
mod dns;
mod doctor;
mod engine;
mod entry;
mod exit;
mod fleet;
mod l3;
mod limits;
mod links;
#[cfg(target_os = "macos")]
mod mac_raw;
mod noise;
mod openvpn;
mod proxy;
mod ramaquic;
#[cfg(target_os = "linux")]
mod raw;
#[cfg(not(target_os = "linux"))]
#[path = "raw_stub.rs"]
mod raw;
mod resume;
mod route;
mod session;
mod stats;
mod status;
#[cfg(target_os = "windows")]
mod win_raw;
mod wire;
mod xui;
pub use shabdiz;

/// Run the tunnel from an Android host without writing credentials to disk.
/// The VPN descriptor is transferred to the native engine and closed by
/// tun-rs when the engine releases it.
#[cfg(target_os = "android")]
pub async fn run_android(
    config_toml: &str,
    token: &str,
    static_secret: &str,
    tun_fd: i32,
) -> anyhow::Result<()> {
    let mut cfg = config::Config::from_toml_str(config_toml)?;
    cfg.token = token.to_owned();
    cfg.static_secret = static_secret.to_owned();
    entry::run_android(cfg, tun_fd).await
}
