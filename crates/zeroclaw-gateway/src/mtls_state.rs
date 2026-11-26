//! State for the gateway's identity-based mTLS listener: issued-certificate
//! ledger, token binding, dynamic revocation, and active-connection tracking.
//!
//! The CA private key is the root of trust for the listener; it stays in this
//! process (loaded at startup) and never leaves it. Revocation is checked per
//! handshake against an in-memory set and active sessions are cancelled by
//! their peer certificate fingerprint.
//!
//! The ledger (`issued.json`) is the source of truth for which device owns a
//! certificate and which bearer token that device was issued. This state owns
//! every write to it and keeps the parsed copy in memory, so a request-path
//! binding check never reads the file.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Weak};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use parking_lot::{Mutex, RwLock};
use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;

use zeroclaw_config::pairing::{PairingGuard, constant_time_eq};
use zeroclaw_runtime::security::audit::{AuditEvent, AuditEventType, AuditLogger};

use crate::api_pairing::{DeviceInfo, DeviceRegistry};

/// Peer certificate fingerprint attached to requests served on the mTLS listener.
#[derive(Clone, Debug)]
pub struct PeerCertFingerprint(pub String);

/// Persists the pairing-token set through the gateway's live-config authority.
pub type PairingPersister =
    Arc<dyn Fn() -> Pin<Box<dyn Future<Output = Result<()>> + Send>> + Send + Sync>;

/// Wrong pairing codes one enrollment window accepts, from all clients
/// together, before it closes. Per-client lockout alone does not bound a
/// guesser that rotates addresses.
const MAX_WINDOW_FAILURES: u32 = 20;
/// Enrollment requests one address may make per minute.
const ENROLL_ATTEMPTS_PER_MINUTE: usize = 10;
/// Addresses tracked by the per-address limiter before stale ones are pruned.
const MAX_TRACKED_ENROLL_CLIENTS: usize = 1024;

/// One client certificate issued by the gateway mTLS CA.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IssuedCert {
    pub device_id: String,
    pub name: String,
    pub fingerprint: String,
    pub created_at: String,
    pub not_after: i64,
    #[serde(default)]
    pub revoked: bool,
    /// SHA-256 of the bearer token issued to this device at enrollment and
    /// carried through renewal. A bound token is accepted only together with
    /// one of its device's certificates. `None` for entries recorded before
    /// binding existed; the first renewal binds them.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token_hash: Option<String>,
    /// Fingerprint of the certificate this one replaced. The predecessor
    /// stays valid until this certificate completes its first handshake, so a
    /// renewal response lost in transit does not lock the device out.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub supersedes: Option<String>,
}

/// Why the last enrollment window closed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EnrollmentCloseReason {
    Paired,
    Timeout,
    Closed,
    Replaced,
    TooManyFailures,
}

impl EnrollmentCloseReason {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Paired => "paired",
            Self::Timeout => "timeout",
            Self::Closed => "closed",
            Self::Replaced => "replaced",
            Self::TooManyFailures => "too_many_failures",
        }
    }
}

/// State of the enrollment window.
#[derive(Debug, Clone, Copy)]
pub struct EnrollmentInfo {
    pub active: bool,
    pub port: u16,
    pub auto_close: bool,
    pub expires_in_secs: Option<u64>,
    pub last_close: Option<EnrollmentCloseReason>,
}

#[derive(Clone)]
struct EnrollmentRuntime {
    tasks: crate::ServeTasks,
    shutdown: tokio::sync::watch::Receiver<bool>,
}

struct EnrollmentWindow {
    id: u64,
    port: u16,
    token: CancellationToken,
    opened_at: Instant,
    expires_at: Option<Instant>,
    auto_close: bool,
    failures: u32,
}

/// Owns the on-demand enrollment listener. The gateway starts it only when
/// `gateway.mtls.enroll` is true or `zeroclaw pair --pair-timeout` asks for it;
/// a successful enrollment, the deadline, or too many wrong codes cancels the
/// accept loop and every connection it accepted. Closing a timed window also
/// expires the pairing code minted for it.
pub struct EnrollmentControl {
    state: Weak<MtlsState>,
    bind: String,
    port: u16,
    server_cert_path: PathBuf,
    server_key_path: PathBuf,
    runtime: Mutex<Option<EnrollmentRuntime>>,
    window: Mutex<Option<EnrollmentWindow>>,
    last_close: Mutex<Option<EnrollmentCloseReason>>,
    next_id: AtomicU64,
}

impl EnrollmentControl {
    fn new(
        state: Weak<MtlsState>,
        bind: String,
        port: u16,
        server_cert_path: PathBuf,
        server_key_path: PathBuf,
    ) -> Self {
        Self {
            state,
            bind,
            port,
            server_cert_path,
            server_key_path,
            runtime: Mutex::new(None),
            window: Mutex::new(None),
            last_close: Mutex::new(None),
            next_id: AtomicU64::new(0),
        }
    }

    pub(crate) fn attach_runtime(
        &self,
        tasks: crate::ServeTasks,
        shutdown: tokio::sync::watch::Receiver<bool>,
    ) {
        *self.runtime.lock() = Some(EnrollmentRuntime { tasks, shutdown });
    }

    pub async fn open(
        self: &Arc<Self>,
        timeout: Option<Duration>,
        port: Option<u16>,
    ) -> Result<EnrollmentInfo> {
        let port = port.unwrap_or(self.port);
        let runtime = {
            self.runtime
                .lock()
                .clone()
                .context("enrollment listener runtime is not attached")?
        };
        let state = self
            .state
            .upgrade()
            .context("mTLS state is no longer available")?;

        // Replacing a window must release its port first.
        self.close_reason(EnrollmentCloseReason::Replaced);

        let listener = tokio::net::TcpListener::bind((self.bind.as_str(), port))
            .await
            .with_context(|| format!("binding enrollment listener on {}:{port}", self.bind))?;
        let acceptor = crate::mtls_enroll::build_enrollment_acceptor(
            &self.server_cert_path,
            &self.server_key_path,
        )?;
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let token = CancellationToken::new();
        let opened_at = Instant::now();
        crate::mtls_enroll::serve(
            listener,
            acceptor,
            state,
            token.clone(),
            runtime.shutdown.clone(),
            runtime.tasks.clone(),
        );

        *self.window.lock() = Some(EnrollmentWindow {
            id,
            port,
            token: token.clone(),
            opened_at,
            expires_at: timeout.map(|duration| opened_at + duration),
            auto_close: timeout.is_some(),
            failures: 0,
        });
        *self.last_close.lock() = None;

        if let Some(timeout) = timeout {
            let weak = Arc::downgrade(self);
            runtime.tasks.spawn(async move {
                tokio::time::sleep(timeout).await;
                if let Some(control) = weak.upgrade() {
                    control.close_if(id, EnrollmentCloseReason::Timeout);
                }
            });
        }

        Ok(EnrollmentInfo {
            active: true,
            port,
            auto_close: timeout.is_some(),
            expires_in_secs: timeout.map(|duration| duration.as_secs()),
            last_close: None,
        })
    }

    pub fn close(&self) {
        self.close_reason(EnrollmentCloseReason::Closed);
    }

    fn close_reason(&self, reason: EnrollmentCloseReason) {
        let taken = self.window.lock().take();
        self.finish(taken, reason);
        *self.last_close.lock() = Some(reason);
    }

    fn close_if(&self, id: u64, reason: EnrollmentCloseReason) {
        let taken = {
            let mut guard = self.window.lock();
            if guard.as_ref().is_some_and(|window| window.id == id) {
                guard.take()
            } else {
                None
            }
        };
        self.finish(taken, reason);
    }

    /// Stop a window: cancel its accept loop and connections, and expire the
    /// pairing code a timed window was opened for. Runs without the window
    /// lock held.
    fn finish(&self, window: Option<EnrollmentWindow>, reason: EnrollmentCloseReason) {
        let Some(window) = window else {
            return;
        };
        window.token.cancel();
        if window.auto_close
            && let Some(state) = self.state.upgrade()
        {
            state
                .pairing
                .expire_pairing_code_minted_since(window.opened_at);
        }
        *self.last_close.lock() = Some(reason);
    }

    /// Called after a certificate was issued. A permanent startup window stays
    /// open; a timed pairing window closes now that this device succeeded.
    pub fn close_after_pairing(&self) {
        let taken = {
            let mut guard = self.window.lock();
            if guard.as_ref().is_some_and(|window| window.auto_close) {
                guard.take()
            } else {
                None
            }
        };
        self.finish(taken, EnrollmentCloseReason::Paired);
    }

    /// Count one wrong pairing code against the open window, closing a timed
    /// window once the window-wide budget is spent.
    ///
    /// A permanent window (`enroll = true`) is deliberately exempt: it is the
    /// operator's standing enrollment surface, and closing it would silently
    /// disable enrollment until a restart or another `zeroclaw pair
    /// --pair-timeout`. The per-address limiter and the per-client lockout
    /// still bound guessing there; this budget exists to end a short-lived
    /// window a guesser could otherwise keep hitting.
    pub fn record_failed_attempt(&self) {
        let taken = {
            let mut guard = self.window.lock();
            match guard.as_mut() {
                Some(window) => {
                    window.failures += 1;
                    if window.auto_close && window.failures >= MAX_WINDOW_FAILURES {
                        guard.take()
                    } else {
                        None
                    }
                }
                None => None,
            }
        };
        if taken.is_some() {
            ::zeroclaw_log::record!(
                WARN,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                    .with_attrs(serde_json::json!({ "failures": MAX_WINDOW_FAILURES })),
                "mTLS enrollment window closed after too many wrong pairing codes"
            );
        }
        self.finish(taken, EnrollmentCloseReason::TooManyFailures);
    }

    pub fn status(&self) -> EnrollmentInfo {
        let guard = self.window.lock();
        match guard.as_ref() {
            Some(window) => EnrollmentInfo {
                active: true,
                port: window.port,
                auto_close: window.auto_close,
                expires_in_secs: window
                    .expires_at
                    .map(|deadline| deadline.saturating_duration_since(Instant::now()).as_secs()),
                last_close: None,
            },
            None => EnrollmentInfo {
                active: false,
                port: 0,
                auto_close: false,
                expires_in_secs: None,
                last_close: *self.last_close.lock(),
            },
        }
    }
}

/// A device whose last live certificate was revoked, so its bearer token was
/// revoked with it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReleasedDevice {
    pub device_id: String,
    pub token_hash: String,
}

/// What one revocation changed.
#[derive(Debug, Clone, Default)]
pub struct RevokeOutcome {
    /// Revoked certificate fingerprints.
    pub fingerprints: Vec<String>,
    /// Devices left with no live certificate; their tokens were revoked in
    /// the pairing guard and must be persisted by the caller.
    pub released: Vec<ReleasedDevice>,
}

/// Why a renewal was refused.
#[derive(Debug)]
pub enum RenewRefusal {
    /// The presenting certificate is not a live ledger entry.
    NotIssued,
    /// The bearer token is not the one issued to this certificate's device.
    TokenMismatch,
    InvalidCsr(String),
    Internal(String),
}

/// A renewed certificate and the device it belongs to.
#[derive(Debug)]
pub struct Renewed {
    pub leaf: zeroclaw_tls::IssuedLeaf,
    pub device_id: String,
}

/// Why a request was refused by the token-to-certificate binding.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BindingRefusal {
    /// A device-bound token arrived without a client certificate.
    CertificateRequired,
    /// A device-bound token arrived with another device's certificate.
    WrongCertificate,
    /// A bound certificate arrived with a token that is not its device's.
    WrongToken,
}

impl BindingRefusal {
    pub fn message(self) -> &'static str {
        match self {
            Self::CertificateRequired => {
                "this token is bound to an mTLS client certificate; use the gateway mTLS listener"
            }
            Self::WrongCertificate => "this token is bound to another device's client certificate",
            Self::WrongToken => "this client certificate is bound to a different token",
        }
    }
}

pub struct MtlsState {
    pub dir: PathBuf,
    pub gateway_id: String,
    pub ca_cert_pem: String,
    pub ca_key_pem: zeroize::Zeroizing<String>,
    pub pairing: Arc<PairingGuard>,
    pub pinned_client_certs: Vec<String>,
    /// The revocation list `[gateway.mtls].crl_path` names (default
    /// `<dir>/revoked`), read at startup and appended on every revocation.
    pub crl_path: PathBuf,
    pub enrollment: Arc<EnrollmentControl>,
    ledger: RwLock<Vec<IssuedCert>>,
    revoked: Arc<RwLock<HashSet<String>>>,
    acceptor: RwLock<Option<Arc<tokio_rustls::TlsAcceptor>>>,
    active: Mutex<HashMap<String, Vec<CancellationToken>>>,
    enroll_attempts: Mutex<HashMap<IpAddr, Vec<Instant>>>,
    pairing_persister: Mutex<Option<PairingPersister>>,
    device_registry: Mutex<Option<Arc<DeviceRegistry>>>,
    /// The daemon's one certificate audit writer (or the gateway's own in a
    /// standalone run). Enrollment, renewal and revocation append through it,
    /// so the mTLS path keeps the same hash-chained trail as every other
    /// certificate path instead of growing a second one.
    cert_audit: Mutex<Option<Arc<AuditLogger>>>,
    startup_token_revocations: AtomicBool,
}

impl MtlsState {
    #[allow(clippy::too_many_arguments)]
    pub fn load(
        dir: PathBuf,
        gateway_id: String,
        ca_cert_pem: String,
        ca_key_pem: zeroize::Zeroizing<String>,
        pairing: Arc<PairingGuard>,
        pinned_client_certs: Vec<String>,
        crl_path: PathBuf,
        enroll_bind: String,
        enroll_port: u16,
        server_cert_path: PathBuf,
        server_key_path: PathBuf,
    ) -> Result<Arc<Self>> {
        std::fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
        let mut revoked = HashSet::new();
        if crl_path.exists() {
            for line in std::fs::read_to_string(&crl_path)
                .with_context(|| format!("reading {}", crl_path.display()))?
                .lines()
            {
                let value = normalize_fingerprint(line);
                if !value.is_empty() && !value.starts_with('#') {
                    revoked.insert(value);
                }
            }
        }

        // A ledger that exists but does not parse must stop startup: treating
        // it as empty would let the next write erase every binding.
        let ledger_path = dir.join("issued.json");
        let mut ledger: Vec<IssuedCert> = if ledger_path.exists() {
            let text = std::fs::read_to_string(&ledger_path)
                .with_context(|| format!("reading {}", ledger_path.display()))?;
            serde_json::from_str(&text)
                .with_context(|| format!("parsing {}", ledger_path.display()))?
        } else {
            Vec::new()
        };

        // The CRL may have been written while the gateway was down
        // (`zeroclaw mtls delete` falls back to it). Fold it into the ledger
        // and revoke the tokens of devices that have no live certificate left.
        let mut ledger_changed = false;
        for entry in &mut ledger {
            let fingerprint = entry.fingerprint.to_lowercase();
            if revoked.contains(&fingerprint) && !entry.revoked {
                entry.revoked = true;
                ledger_changed = true;
            }
            if entry.revoked {
                revoked.insert(fingerprint);
            }
        }
        if ledger_changed {
            write_ledger_file(&ledger_path, &ledger)?;
        }
        let mut startup_revocations = false;
        let hashes: HashSet<String> = ledger
            .iter()
            .filter_map(|entry| entry.token_hash.clone())
            .collect();
        for hash in hashes {
            if token_released(&ledger, &hash) && pairing.revoke_token_hash(&hash) {
                startup_revocations = true;
            }
        }

        Ok(Arc::new_cyclic(|weak| Self {
            dir,
            gateway_id,
            ca_cert_pem,
            ca_key_pem,
            pairing,
            pinned_client_certs,
            crl_path,
            enrollment: Arc::new(EnrollmentControl::new(
                weak.clone(),
                enroll_bind,
                enroll_port,
                server_cert_path,
                server_key_path,
            )),
            ledger: RwLock::new(ledger),
            revoked: Arc::new(RwLock::new(revoked)),
            acceptor: RwLock::new(None),
            active: Mutex::new(HashMap::new()),
            enroll_attempts: Mutex::new(HashMap::new()),
            pairing_persister: Mutex::new(None),
            device_registry: Mutex::new(None),
            cert_audit: Mutex::new(None),
            startup_token_revocations: AtomicBool::new(startup_revocations),
        }))
    }

    /// Install the callback that writes the pairing-token set to config.
    /// Enrollment is the only mTLS path that mints a token, and the plain
    /// `/pair` handler is not on this listener, so the token would otherwise
    /// live only in memory until the next gateway restart.
    pub fn set_pairing_persister(&self, persister: PairingPersister) {
        *self.pairing_persister.lock() = Some(persister);
    }

    pub async fn persist_pairing_tokens(&self) -> Result<()> {
        let persister = { self.pairing_persister.lock().clone() };
        match persister {
            Some(persister) => persister().await,
            None => Ok(()),
        }
    }

    /// Whether loading the ledger revoked tokens that still have to be
    /// written to config. Reports true once.
    pub fn take_startup_token_revocations(&self) -> bool {
        self.startup_token_revocations.swap(false, Ordering::SeqCst)
    }

    /// Give enrollment the paired-device registry, so mTLS devices are listed
    /// and revocable with every other paired device.
    pub fn set_device_registry(&self, registry: Arc<DeviceRegistry>) {
        *self.device_registry.lock() = Some(registry);
    }

    /// Install the certificate audit writer this listener appends to.
    pub fn set_cert_audit(&self, audit: Arc<AuditLogger>) {
        *self.cert_audit.lock() = Some(audit);
    }

    /// Append one certificate lifecycle event to the shared audit chain.
    ///
    /// The caller decides whether a failure must refuse the action (enrollment)
    /// or is only reported after a durable change (revocation).
    fn audit_cert(
        &self,
        event_type: AuditEventType,
        device_id: &str,
        fingerprint: &str,
        not_after: i64,
        outcome: Option<(bool, Option<String>)>,
    ) -> Result<()> {
        let audit = { self.cert_audit.lock().clone() };
        let Some(audit) = audit else {
            return Ok(());
        };
        let mut event = AuditEvent::new(event_type)
            .with_actor(
                "cert".to_string(),
                Some(device_id.to_string()),
                Some("gateway-mtls".to_string()),
            )
            .with_action(
                format!("fingerprint={fingerprint} not_after={not_after}"),
                "cert".to_string(),
                true,
                true,
            );
        if let Some((success, error)) = outcome {
            event = event.with_result(success, None, 0, error);
        }
        audit
            .log(&event)
            .with_context(|| format!("write certificate audit event for {fingerprint}"))
    }

    /// The CSR is signed but no certificate has been delivered yet.
    pub fn audit_issuance_attempted(
        &self,
        device_id: &str,
        fingerprint: &str,
        not_after: i64,
    ) -> Result<()> {
        self.audit_cert(
            AuditEventType::CertIssuanceAttempted,
            device_id,
            fingerprint,
            not_after,
            None,
        )
    }

    /// The certificate is committed to the ledger and about to be delivered.
    pub fn audit_issued(
        &self,
        device_id: &str,
        fingerprint: &str,
        not_after: i64,
        renewal: bool,
    ) -> Result<()> {
        self.audit_cert(
            if renewal {
                AuditEventType::CertRenewed
            } else {
                AuditEventType::CertIssued
            },
            device_id,
            fingerprint,
            not_after,
            Some((true, None)),
        )
    }

    fn audit_revoked(&self, entry: &IssuedCert) {
        if let Err(error) = self.audit_cert(
            AuditEventType::CertRevoked,
            &entry.device_id,
            &entry.fingerprint,
            entry.not_after,
            Some((true, None)),
        ) {
            ::zeroclaw_log::record!(
                WARN,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(serde_json::json!({
                        "fingerprint": entry.fingerprint,
                        "device_id": entry.device_id,
                        "error": format!("{error:#}"),
                    })),
                "mTLS certificate revoked but its audit event could not be written"
            );
        }
    }

    /// Record an enrolled device in the paired-device registry, keyed by its
    /// bearer token hash, with the ledger's device id as the registry id.
    pub fn register_enrolled_device(
        &self,
        token_hash: &str,
        device_id: &str,
        name: &str,
        peer: &str,
    ) -> Result<()> {
        let registry = { self.device_registry.lock().clone() };
        let Some(registry) = registry else {
            return Ok(());
        };
        let now = chrono::Utc::now();
        registry
            .register(
                token_hash.to_string(),
                DeviceInfo {
                    id: device_id.to_string(),
                    name: Some(name.to_string()),
                    device_type: Some("mtls".to_string()),
                    paired_at: now,
                    last_seen: now,
                    ip_address: Some(peer.to_string()),
                    capabilities: None,
                },
            )
            .context("recording the device in the paired-device registry")
    }

    pub fn ledger_path(&self) -> PathBuf {
        self.dir.join("issued.json")
    }

    pub fn read_ledger(&self) -> Vec<IssuedCert> {
        self.ledger.read().clone()
    }

    pub fn record_issued(&self, entry: IssuedCert) -> Result<()> {
        let mut ledger = self.ledger.write();
        let mut next = ledger.clone();
        next.retain(|existing| {
            !existing
                .fingerprint
                .eq_ignore_ascii_case(&entry.fingerprint)
        });
        next.push(entry);
        write_ledger_file(&self.ledger_path(), &next)?;
        *ledger = next;
        Ok(())
    }

    pub fn set_acceptor(&self, acceptor: tokio_rustls::TlsAcceptor) {
        *self.acceptor.write() = Some(Arc::new(acceptor));
    }

    pub fn acceptor(&self) -> Option<Arc<tokio_rustls::TlsAcceptor>> {
        self.acceptor.read().clone()
    }

    /// Track a live mTLS connection by peer certificate fingerprint.
    pub fn register_active(&self, fingerprint: String) -> CancellationToken {
        let token = CancellationToken::new();
        self.active
            .lock()
            .entry(fingerprint)
            .or_default()
            .push(token.clone());
        token
    }

    pub fn unregister_active(&self, fingerprint: &str, token: &CancellationToken) {
        let mut active = self.active.lock();
        if let Some(tokens) = active.get_mut(fingerprint) {
            tokens.retain(|existing| !existing.is_cancelled() && existing != token);
            if tokens.is_empty() {
                active.remove(fingerprint);
            }
        }
    }

    pub fn active_fingerprints(&self) -> Vec<(String, usize)> {
        self.active
            .lock()
            .iter()
            .filter(|(_, tokens)| !tokens.is_empty())
            .map(|(fingerprint, tokens)| (fingerprint.clone(), tokens.len()))
            .collect()
    }

    fn cancel_active(&self, fingerprints: &[String]) {
        let mut active = self.active.lock();
        for fingerprint in fingerprints {
            if let Some(tokens) = active.remove(fingerprint) {
                for token in tokens {
                    token.cancel();
                }
            }
        }
    }

    /// Add fingerprints to the live revocation set and append the new ones to
    /// the on-disk CRL.
    fn append_crl(&self, fingerprints: &[String]) -> Result<()> {
        let mut revoked = self.revoked.write();
        let fresh: Vec<&String> = fingerprints
            .iter()
            .filter(|fingerprint| !revoked.contains(*fingerprint))
            .collect();
        if fresh.is_empty() {
            return Ok(());
        }
        let mut crl = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.crl_path)
            .with_context(|| format!("opening {}", self.crl_path.display()))?;
        use std::io::Write as _;
        for fingerprint in &fresh {
            writeln!(crl, "{fingerprint}")?;
        }
        crl.sync_all()?;
        for fingerprint in fresh {
            revoked.insert(fingerprint.clone());
        }
        Ok(())
    }

    /// Revoke one fingerprint or every certificate for a device id.
    ///
    /// Active sessions for those certificates are cancelled immediately, the
    /// fingerprints are appended to the on-disk CRL so a restart keeps the
    /// revocation, and a device left without a live certificate also loses
    /// its bearer token and paired-device row. The caller persists the token
    /// set when [`RevokeOutcome::released`] is not empty.
    pub fn revoke(&self, selector: &str) -> Result<RevokeOutcome> {
        let selector = normalize_fingerprint(selector);
        if selector.is_empty() {
            anyhow::bail!("pass a device id or a certificate fingerprint");
        }
        // An unknown value is revoked only if it is a fingerprint (a leaf
        // issued out of band); a mistyped device id must not become a CRL line.
        let fallback = is_sha256_hex(&selector).then(|| selector.clone());
        self.revoke_matching(
            |entry| {
                entry.device_id.eq_ignore_ascii_case(&selector)
                    || entry.fingerprint.eq_ignore_ascii_case(&selector)
            },
            fallback,
        )
    }

    /// Revoke every certificate bound to a bearer token. Used when the
    /// token's paired device is removed from the device registry.
    pub fn revoke_token_hash(&self, token_hash: &str) -> Result<RevokeOutcome> {
        self.revoke_matching(
            |entry| entry.token_hash.as_deref() == Some(token_hash) && !entry.revoked,
            None,
        )
    }

    fn revoke_matching(
        &self,
        select: impl Fn(&IssuedCert) -> bool,
        fallback: Option<String>,
    ) -> Result<RevokeOutcome> {
        let mut ledger = self.ledger.write();
        let mut next = ledger.clone();
        let mut targets: Vec<String> = next
            .iter()
            .filter(|entry| select(entry))
            .map(|entry| entry.fingerprint.to_lowercase())
            .collect();
        if targets.is_empty()
            && let Some(fingerprint) = fallback
        {
            targets.push(fingerprint);
        }
        if targets.is_empty() {
            return Ok(RevokeOutcome::default());
        }
        // Only these transitions are real revocations; a selector that names
        // an already-revoked certificate must not add a duplicate audit event.
        let newly_revoked: Vec<IssuedCert> = ledger
            .iter()
            .filter(|entry| targets.contains(&entry.fingerprint.to_lowercase()) && !entry.revoked)
            .cloned()
            .collect();
        for entry in &mut next {
            if targets.contains(&entry.fingerprint.to_lowercase()) {
                entry.revoked = true;
            }
        }
        let mut released = Vec::new();
        let mut seen = HashSet::new();
        for entry in next
            .iter()
            .filter(|entry| targets.contains(&entry.fingerprint.to_lowercase()))
        {
            if let Some(hash) = &entry.token_hash
                && seen.insert(hash.clone())
                && token_released(&next, hash)
            {
                released.push(ReleasedDevice {
                    device_id: entry.device_id.clone(),
                    token_hash: hash.clone(),
                });
            }
        }
        // The CRL first: once it is written the handshake refuses the
        // certificate even if the ledger write below fails.
        self.append_crl(&targets)?;
        write_ledger_file(&self.ledger_path(), &next)?;
        *ledger = next;
        drop(ledger);

        for entry in &newly_revoked {
            self.audit_revoked(entry);
        }

        let registry = { self.device_registry.lock().clone() };
        for device in &released {
            self.pairing.revoke_token_hash(&device.token_hash);
            if let Some(registry) = registry.as_ref()
                && let Err(error) = registry.revoke(&device.device_id)
            {
                ::zeroclaw_log::record!(
                    WARN,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(serde_json::json!({
                            "device_id": device.device_id,
                            "error": format!("{error}"),
                        })),
                    "mTLS device token revoked but its registry row could not be removed"
                );
            }
        }
        self.cancel_active(&targets);
        ::zeroclaw_log::record!(
            INFO,
            ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note).with_attrs(
                serde_json::json!({
                    "fingerprints": targets,
                    "released_devices": released.iter().map(|d| d.device_id.clone()).collect::<Vec<_>>(),
                })
            ),
            "mTLS client certificates revoked"
        );
        Ok(RevokeOutcome {
            fingerprints: targets,
            released,
        })
    }

    /// Issue a replacement for the presenting certificate.
    ///
    /// The bearer token must be the one bound to the certificate's device
    /// (a legacy entry without a binding is bound to it now). The new entry
    /// records the old fingerprint as its predecessor; the predecessor stays
    /// valid until the new certificate completes a handshake (see
    /// [`Self::note_handshake`]), so a lost response can be retried with the
    /// old certificate. A retry revokes the earlier, never-used replacement.
    pub fn renew(
        &self,
        peer_fingerprint: &str,
        token_hash: &str,
        csr_pem: &str,
    ) -> std::result::Result<Renewed, RenewRefusal> {
        let peer = normalize_fingerprint(peer_fingerprint);
        let mut ledger = self.ledger.write();
        let mut next = ledger.clone();
        let Some(index) = next
            .iter()
            .position(|entry| entry.fingerprint.eq_ignore_ascii_case(&peer) && !entry.revoked)
        else {
            return Err(RenewRefusal::NotIssued);
        };
        match next[index].token_hash.as_deref() {
            Some(bound) if !constant_time_eq(bound, token_hash) => {
                return Err(RenewRefusal::TokenMismatch);
            }
            Some(_) => {}
            None => next[index].token_hash = Some(token_hash.to_string()),
        }
        let device_id = next[index].device_id.clone();
        let name = next[index].name.clone();
        let leaf = zeroclaw_tls::sign_csr(&self.ca_cert_pem, &self.ca_key_pem, &device_id, csr_pem)
            .map_err(|error| RenewRefusal::InvalidCsr(format!("{error}")))?;
        self.audit_issuance_attempted(&device_id, &leaf.fingerprint, leaf.not_after)
            .map_err(|error| RenewRefusal::Internal(format!("audit: {error:#}")))?;

        let stale: Vec<String> = next
            .iter()
            .filter(|entry| {
                !entry.revoked
                    && entry
                        .supersedes
                        .as_deref()
                        .is_some_and(|previous| previous.eq_ignore_ascii_case(&peer))
            })
            .map(|entry| entry.fingerprint.to_lowercase())
            .collect();
        for entry in &mut next {
            if stale.contains(&entry.fingerprint.to_lowercase()) {
                entry.revoked = true;
            }
        }
        next.push(IssuedCert {
            device_id: device_id.clone(),
            name,
            fingerprint: leaf.fingerprint.clone(),
            created_at: chrono::Utc::now().to_rfc3339(),
            not_after: leaf.not_after,
            revoked: false,
            token_hash: Some(token_hash.to_string()),
            supersedes: Some(peer),
        });
        self.append_crl(&stale)
            .map_err(|error| RenewRefusal::Internal(format!("{error}")))?;
        write_ledger_file(&self.ledger_path(), &next)
            .map_err(|error| RenewRefusal::Internal(format!("{error}")))?;
        *ledger = next;
        drop(ledger);
        // The ledger row is durable; the completion event must be too before
        // the certificate reaches the device. If it cannot be written, revoke
        // what was just issued so no undelivered credential stays live.
        if let Err(error) = self.audit_issued(&device_id, &leaf.fingerprint, leaf.not_after, true) {
            let rollback = self
                .revoke(&leaf.fingerprint)
                .err()
                .map(|revoke_error| format!(" (rollback failed: {revoke_error:#})"))
                .unwrap_or_default();
            return Err(RenewRefusal::Internal(format!(
                "audit: {error:#}{rollback}"
            )));
        }
        Ok(Renewed { leaf, device_id })
    }

    /// Called after every successful handshake. The first handshake of a
    /// renewed certificate proves the device holds it, so its predecessor is
    /// revoked and the predecessor's connections are closed.
    pub fn note_handshake(&self, fingerprint: &str) {
        let pending = self.ledger.read().iter().any(|entry| {
            entry.fingerprint.eq_ignore_ascii_case(fingerprint) && entry.supersedes.is_some()
        });
        if !pending {
            return;
        }
        let retired = (|| -> Result<Option<String>> {
            let mut ledger = self.ledger.write();
            let mut next = ledger.clone();
            let Some(index) = next
                .iter()
                .position(|entry| entry.fingerprint.eq_ignore_ascii_case(fingerprint))
            else {
                return Ok(None);
            };
            let Some(previous) = next[index].supersedes.take() else {
                return Ok(None);
            };
            let previous = normalize_fingerprint(&previous);
            for entry in &mut next {
                if entry.fingerprint.eq_ignore_ascii_case(&previous) {
                    entry.revoked = true;
                }
            }
            self.append_crl(std::slice::from_ref(&previous))?;
            write_ledger_file(&self.ledger_path(), &next)?;
            *ledger = next;
            Ok(Some(previous))
        })();
        match retired {
            Ok(Some(previous)) => self.cancel_active(&[previous]),
            Ok(None) => {}
            Err(error) => ::zeroclaw_log::record!(
                WARN,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(serde_json::json!({ "error": format!("{error}") })),
                "could not retire the certificate a renewal replaced; it stays valid until expiry"
            ),
        }
    }

    /// Enforce the token-to-certificate binding for one request.
    ///
    /// `presented` holds the SHA-256 of every bearer candidate the request
    /// carries; `peer` is the client certificate fingerprint on the mTLS
    /// listener and `None` everywhere else. A token issued by enrollment is
    /// accepted only with a certificate of its own device, and a certificate
    /// bound to a token is accepted only with that token. Unbound tokens and
    /// certificates keep their existing behavior.
    pub fn check_binding(
        &self,
        presented: &[String],
        peer: Option<&str>,
    ) -> std::result::Result<(), BindingRefusal> {
        if presented.is_empty() {
            return Ok(());
        }
        let ledger = self.ledger.read();
        let peer_bound = peer
            .and_then(|fingerprint| {
                ledger
                    .iter()
                    .find(|entry| entry.fingerprint.eq_ignore_ascii_case(fingerprint))
            })
            .and_then(|entry| entry.token_hash.clone());
        for hash in presented {
            let bound = ledger
                .iter()
                .any(|entry| entry.token_hash.as_deref() == Some(hash.as_str()));
            match (bound, peer, peer_bound.as_deref()) {
                (true, None, _) => return Err(BindingRefusal::CertificateRequired),
                (true, Some(_), Some(own)) if own == hash => {}
                (true, Some(_), _) => return Err(BindingRefusal::WrongCertificate),
                (false, Some(_), Some(_)) => return Err(BindingRefusal::WrongToken),
                (false, _, _) => {}
            }
        }
        Ok(())
    }

    /// Bounded per-IP enrollment attempt counter.
    pub fn allow_enroll(&self, ip: IpAddr) -> bool {
        let now = Instant::now();
        let window = Duration::from_secs(60);
        let mut attempts = self.enroll_attempts.lock();
        if attempts.len() >= MAX_TRACKED_ENROLL_CLIENTS && !attempts.contains_key(&ip) {
            attempts.retain(|_, times| times.iter().any(|at| now.duration_since(*at) < window));
            // Still full of recent addresses: refuse new ones rather than
            // grow without bound. The window-wide failure budget closes the
            // window under such a spray anyway.
            if attempts.len() >= MAX_TRACKED_ENROLL_CLIENTS {
                return false;
            }
        }
        let times = attempts.entry(ip).or_default();
        times.retain(|at| now.duration_since(*at) < window);
        if times.len() >= ENROLL_ATTEMPTS_PER_MINUTE {
            return false;
        }
        times.push(now);
        true
    }

    /// Ledger plus active connection counts, for `mtls list`.
    pub fn list(&self) -> (Vec<IssuedCert>, Vec<(String, usize)>) {
        (self.read_ledger(), self.active_fingerprints())
    }
}

/// Whether every ledger entry bound to `token_hash` is revoked.
fn token_released(entries: &[IssuedCert], token_hash: &str) -> bool {
    entries
        .iter()
        .filter(|entry| entry.token_hash.as_deref() == Some(token_hash))
        .all(|entry| entry.revoked)
}

fn normalize_fingerprint(value: &str) -> String {
    value.trim().replace(':', "").to_lowercase()
}

fn is_sha256_hex(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

/// Replace the ledger file atomically so a crash never leaves it truncated.
fn write_ledger_file(path: &Path, entries: &[IssuedCert]) -> Result<()> {
    let text = serde_json::to_string_pretty(entries).context("serializing the cert ledger")?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, text).with_context(|| format!("writing {}", tmp.display()))?;
    std::fs::rename(&tmp, path).with_context(|| format!("replacing {}", path.display()))
}

/// Build the mTLS acceptor with live revocation.
///
/// The inner verifier owns chain, EKU, pin and static-CRL checks; this wrapper
/// adds the in-memory revocation set so `mtls delete` takes effect on the next
/// handshake without a restart.
pub fn build_acceptor(
    cert_path: &Path,
    key_path: &Path,
    ca_path: &Path,
    pinned: &[String],
    state: &Arc<MtlsState>,
) -> Result<tokio_rustls::TlsAcceptor> {
    use rustls::client::danger::HandshakeSignatureValid;
    use rustls::pki_types::{CertificateDer, UnixTime};
    use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
    use rustls::{DigitallySignedStruct, DistinguishedName, SignatureScheme};

    #[derive(Debug)]
    struct RevocationVerifier {
        inner: Arc<dyn ClientCertVerifier>,
        revoked: Arc<RwLock<HashSet<String>>>,
    }

    impl ClientCertVerifier for RevocationVerifier {
        fn offer_client_auth(&self) -> bool {
            self.inner.offer_client_auth()
        }

        fn client_auth_mandatory(&self) -> bool {
            self.inner.client_auth_mandatory()
        }

        fn root_hint_subjects(&self) -> &[DistinguishedName] {
            self.inner.root_hint_subjects()
        }

        fn verify_client_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            intermediates: &[CertificateDer<'_>],
            now: UnixTime,
        ) -> std::result::Result<ClientCertVerified, rustls::Error> {
            let fingerprint = zeroclaw_tls::cert_sha256_fingerprint(end_entity.as_ref());
            if self.revoked.read().contains(&fingerprint) {
                return Err(rustls::Error::General(
                    "client certificate revoked".to_string(),
                ));
            }
            self.inner
                .verify_client_cert(end_entity, intermediates, now)
        }

        fn verify_tls12_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
            self.inner.verify_tls12_signature(message, cert, dss)
        }

        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> std::result::Result<HandshakeSignatureValid, rustls::Error> {
            self.inner.verify_tls13_signature(message, cert, dss)
        }

        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.inner.supported_verify_schemes()
        }
    }

    let inner = zeroclaw_tls::build_client_verifier(&zeroclaw_tls::ClientAuthParams {
        ca_cert_path: ca_path.to_string_lossy().to_string(),
        require_client_cert: true,
        pinned_certs: pinned.to_vec(),
        crl_path: String::new(),
    })?;
    let verifier: Arc<dyn ClientCertVerifier> = Arc::new(RevocationVerifier {
        inner,
        revoked: Arc::clone(&state.revoked),
    });
    let certs = zeroclaw_tls::load_certs(&cert_path.to_string_lossy())?;
    let key = zeroclaw_tls::load_private_key(&key_path.to_string_lossy())?;
    let config = rustls::ServerConfig::builder_with_protocol_versions(&[&rustls::version::TLS13])
        .with_client_cert_verifier(verifier)
        .with_single_cert(certs, key)
        .context("building the gateway mTLS server config")?;
    Ok(tokio_rustls::TlsAcceptor::from(Arc::new(config)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Fixture {
        dir: tempfile::TempDir,
        state: Arc<MtlsState>,
        pairing: Arc<PairingGuard>,
    }

    fn fixture_in(dir: tempfile::TempDir, pairing: Arc<PairingGuard>) -> Fixture {
        let materials = zeroclaw_tls::ensure_gateway_materials(
            dir.path(),
            &zeroclaw_tls::CaKeyProtection::None,
        )
        .expect("materials");
        let ca_cert_pem = std::fs::read_to_string(&materials.ca_cert_path).expect("ca");
        let ca_key_pem = zeroclaw_tls::load_ca_key_pem(
            &materials.ca_key_path,
            &zeroclaw_tls::CaKeyProtection::None,
        )
        .expect("ca key");
        let state = MtlsState::load(
            dir.path().to_path_buf(),
            materials.gateway_id,
            ca_cert_pem,
            ca_key_pem,
            Arc::clone(&pairing),
            Vec::new(),
            dir.path().join("revoked"),
            "127.0.0.1".to_string(),
            0,
            materials.server_cert_path,
            materials.server_key_path,
        )
        .expect("state");
        Fixture {
            dir,
            state,
            pairing,
        }
    }

    fn fixture() -> Fixture {
        let pairing = Arc::new(PairingGuard::new(
            true,
            &[],
            zeroclaw_config::pairing::PairingCodePolicy::default(),
        ));
        fixture_in(tempfile::tempdir().expect("temp dir"), pairing)
    }

    /// Enroll one device the way the enrollment handler does and return its
    /// fingerprint and plaintext token.
    async fn enroll(fixture: &Fixture, device_id: &str) -> (String, String) {
        let code = fixture
            .pairing
            .generate_new_pairing_code(zeroclaw_config::pairing::PairingCodePolicy::default())
            .expect("code");
        let reservation = fixture
            .pairing
            .reserve_pair(&code, "198.51.100.1")
            .await
            .expect("not locked out")
            .expect("code reserves");
        let (csr, _key) = zeroclaw_tls::generate_client_csr("phone").expect("csr");
        let leaf = zeroclaw_tls::sign_csr(
            &fixture.state.ca_cert_pem,
            &fixture.state.ca_key_pem,
            device_id,
            &csr,
        )
        .expect("signed");
        fixture
            .state
            .record_issued(IssuedCert {
                device_id: device_id.to_string(),
                name: "phone".to_string(),
                fingerprint: leaf.fingerprint.clone(),
                created_at: chrono::Utc::now().to_rfc3339(),
                not_after: leaf.not_after,
                revoked: false,
                token_hash: Some(reservation.token_hash()),
                supersedes: None,
            })
            .expect("recorded");
        (leaf.fingerprint, reservation.commit())
    }

    fn csr() -> String {
        zeroclaw_tls::generate_client_csr("phone").expect("csr").0
    }

    #[tokio::test]
    async fn pairing_persister_runs_for_enrollment_tokens() {
        let fixture = fixture();
        let calls = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&calls);
        fixture.state.set_pairing_persister(Arc::new(move || {
            let counter = Arc::clone(&counter);
            Box::pin(async move {
                counter.fetch_add(1, Ordering::SeqCst);
                Ok(())
            })
        }));

        fixture
            .state
            .persist_pairing_tokens()
            .await
            .expect("persist");
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn certificate_lifecycle_appends_to_the_shared_audit_chain() {
        let fixture = fixture();
        let audit = Arc::new(
            zeroclaw_runtime::security::audit::AuditLogger::new(
                zeroclaw_config::schema::AuditConfig {
                    enabled: true,
                    ..Default::default()
                },
                fixture.dir.path().to_path_buf(),
            )
            .expect("audit logger"),
        );
        fixture.state.set_cert_audit(audit);

        let (fingerprint, token) = enroll(&fixture, "dev-phone").await;
        fixture
            .state
            .audit_issuance_attempted("dev-phone", &fingerprint, 0)
            .expect("attempt event");
        fixture
            .state
            .audit_issued("dev-phone", &fingerprint, 0, false)
            .expect("issued event");
        fixture
            .state
            .renew(&fingerprint, &PairingGuard::token_hash(&token), &csr())
            .expect("renewed");
        fixture.state.revoke("dev-phone").expect("revoked");

        let log = std::fs::read_to_string(fixture.dir.path().join("audit.log"))
            .expect("certificate audit log");
        let types: Vec<String> = log
            .lines()
            .map(|line| {
                serde_json::from_str::<serde_json::Value>(line)
                    .expect("audit line")
                    .get("event_type")
                    .and_then(serde_json::Value::as_str)
                    .expect("event type")
                    .to_string()
            })
            .collect();
        assert_eq!(
            types,
            vec![
                "cert_issuance_attempted",
                "cert_issued",
                "cert_issuance_attempted",
                "cert_renewed",
                "cert_revoked",
                "cert_revoked"
            ],
            "the mTLS path must append to the shared certificate audit chain"
        );
    }

    #[tokio::test]
    async fn revoking_a_device_removes_its_registry_row() {
        let fixture = fixture();
        fixture.state.set_device_registry(Arc::new(
            DeviceRegistry::new(fixture.dir.path()).expect("device registry"),
        ));
        let (_, token) = enroll(&fixture, "dev-phone").await;
        fixture
            .state
            .register_enrolled_device(
                &PairingGuard::token_hash(&token),
                "dev-phone",
                "phone",
                "127.0.0.1",
            )
            .expect("registered");
        let registry = fixture
            .state
            .device_registry
            .lock()
            .clone()
            .expect("registry installed");
        assert_eq!(registry.list().expect("rows").len(), 1);

        fixture.state.revoke("dev-phone").expect("revoked");
        assert!(
            registry.list().expect("rows").is_empty(),
            "the paired-device row must go with the certificate and token"
        );
    }

    #[tokio::test]
    async fn a_bound_token_needs_its_own_certificate() {
        let fixture = fixture();
        let (phone_fp, phone_token) = enroll(&fixture, "dev-phone").await;
        let (tablet_fp, _) = enroll(&fixture, "dev-tablet").await;
        let phone_hash = vec![PairingGuard::token_hash(&phone_token)];

        assert_eq!(
            fixture.state.check_binding(&phone_hash, None),
            Err(BindingRefusal::CertificateRequired),
            "the plain listener must refuse an enrollment token"
        );
        assert_eq!(
            fixture.state.check_binding(&phone_hash, Some(&tablet_fp)),
            Err(BindingRefusal::WrongCertificate)
        );
        assert_eq!(
            fixture.state.check_binding(&phone_hash, Some(&phone_fp)),
            Ok(())
        );

        let operator = vec![PairingGuard::token_hash("zc_operator")];
        assert_eq!(fixture.state.check_binding(&operator, None), Ok(()));
        assert_eq!(
            fixture.state.check_binding(&operator, Some(&phone_fp)),
            Err(BindingRefusal::WrongToken)
        );
        assert_eq!(fixture.state.check_binding(&[], Some(&phone_fp)), Ok(()));
    }

    #[tokio::test]
    async fn revoking_a_device_revokes_its_token() {
        let fixture = fixture();
        let (fingerprint, token) = enroll(&fixture, "dev-phone").await;
        assert!(fixture.pairing.token_is_paired(&token));
        let connection = fixture.state.register_active(fingerprint.clone());

        let outcome = fixture.state.revoke("dev-phone").expect("revoked");
        assert_eq!(outcome.fingerprints, vec![fingerprint.clone()]);
        assert_eq!(outcome.released.len(), 1);
        assert!(!fixture.pairing.token_is_paired(&token));
        assert!(connection.is_cancelled());
        assert!(fixture.state.revoked.read().contains(&fingerprint));
        let crl = std::fs::read_to_string(&fixture.state.crl_path).expect("crl");
        assert!(crl.contains(&fingerprint));
    }

    #[tokio::test]
    async fn a_mistyped_device_id_is_not_written_to_the_crl() {
        let fixture = fixture();
        let outcome = fixture.state.revoke("dev-typo").expect("no error");
        assert!(outcome.fingerprints.is_empty());
        assert!(!fixture.state.crl_path.exists());
    }

    #[tokio::test]
    async fn renewal_keeps_the_old_certificate_until_the_new_one_is_used() {
        let fixture = fixture();
        let (old_fp, token) = enroll(&fixture, "dev-phone").await;
        let hash = PairingGuard::token_hash(&token);

        let first = fixture
            .state
            .renew(&old_fp, &hash, &csr())
            .expect("renewed");
        assert_eq!(first.device_id, "dev-phone");
        assert!(
            !fixture.state.revoked.read().contains(&old_fp),
            "a lost response must leave the old certificate usable"
        );

        // The response was lost: the device retries with the old certificate.
        let second = fixture.state.renew(&old_fp, &hash, &csr()).expect("retry");
        assert!(
            fixture
                .state
                .revoked
                .read()
                .contains(&first.leaf.fingerprint),
            "the never-used replacement is revoked by the retry"
        );
        assert!(!fixture.state.revoked.read().contains(&old_fp));

        let old_connection = fixture.state.register_active(old_fp.clone());
        fixture.state.note_handshake(&second.leaf.fingerprint);
        assert!(fixture.state.revoked.read().contains(&old_fp));
        assert!(old_connection.is_cancelled());
        assert!(
            fixture.pairing.token_is_paired(&token),
            "renewal keeps the device's token"
        );
        assert_eq!(
            fixture
                .state
                .check_binding(std::slice::from_ref(&hash), Some(&second.leaf.fingerprint)),
            Ok(())
        );
    }

    #[tokio::test]
    async fn renewal_refuses_another_devices_token_and_unknown_certificates() {
        let fixture = fixture();
        let (phone_fp, _) = enroll(&fixture, "dev-phone").await;
        let (_, tablet_token) = enroll(&fixture, "dev-tablet").await;
        assert!(matches!(
            fixture
                .state
                .renew(&phone_fp, &PairingGuard::token_hash(&tablet_token), &csr()),
            Err(RenewRefusal::TokenMismatch)
        ));
        assert!(matches!(
            fixture.state.renew(&"ab".repeat(32), "hash", &csr()),
            Err(RenewRefusal::NotIssued)
        ));
    }

    #[tokio::test]
    async fn an_offline_revocation_takes_the_token_at_the_next_start() {
        let fixture = fixture();
        let (fingerprint, token) = enroll(&fixture, "dev-phone").await;
        // `zeroclaw mtls delete` with the gateway down only appends the CRL.
        std::fs::write(&fixture.state.crl_path, format!("{fingerprint}\n")).expect("crl");
        let Fixture { dir, pairing, .. } = fixture;
        let restarted = fixture_in(dir, Arc::clone(&pairing));
        assert!(!pairing.token_is_paired(&token));
        assert!(restarted.state.take_startup_token_revocations());
        assert!(!restarted.state.take_startup_token_revocations());
        assert!(restarted.state.read_ledger()[0].revoked);
    }

    #[tokio::test]
    async fn a_corrupt_ledger_stops_startup() {
        let dir = tempfile::tempdir().expect("temp dir");
        std::fs::write(dir.path().join("issued.json"), "{not json").expect("write");
        let pairing = Arc::new(PairingGuard::new(
            true,
            &[],
            zeroclaw_config::pairing::PairingCodePolicy::default(),
        ));
        let materials = zeroclaw_tls::ensure_gateway_materials(
            dir.path(),
            &zeroclaw_tls::CaKeyProtection::None,
        )
        .expect("materials");
        let result = MtlsState::load(
            dir.path().to_path_buf(),
            materials.gateway_id,
            String::new(),
            zeroize::Zeroizing::new(String::new()),
            pairing,
            Vec::new(),
            dir.path().join("revoked"),
            "127.0.0.1".to_string(),
            0,
            materials.server_cert_path,
            materials.server_key_path,
        );
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn a_timed_window_closes_on_failures_and_expires_its_code() {
        let fixture = fixture();
        let (_stop, stopping) = tokio::sync::watch::channel(false);
        fixture
            .state
            .enrollment
            .attach_runtime(crate::ServeTasks::default(), stopping);
        Arc::clone(&fixture.state.enrollment)
            .open(Some(Duration::from_secs(60)), Some(0))
            .await
            .expect("open");
        fixture
            .pairing
            .generate_new_pairing_code(zeroclaw_config::pairing::PairingCodePolicy::default())
            .expect("code");
        for _ in 1..MAX_WINDOW_FAILURES {
            fixture.state.enrollment.record_failed_attempt();
        }
        assert!(fixture.state.enrollment.status().active);
        fixture.state.enrollment.record_failed_attempt();
        let status = fixture.state.enrollment.status();
        assert!(!status.active);
        assert_eq!(
            status.last_close,
            Some(EnrollmentCloseReason::TooManyFailures)
        );
        assert!(
            fixture.pairing.pairing_code().is_none(),
            "the window's code must not outlive it"
        );
    }

    #[tokio::test]
    async fn a_permanent_window_survives_the_failure_budget() {
        let fixture = fixture();
        let (_stop, stopping) = tokio::sync::watch::channel(false);
        fixture
            .state
            .enrollment
            .attach_runtime(crate::ServeTasks::default(), stopping);
        Arc::clone(&fixture.state.enrollment)
            .open(None, Some(0))
            .await
            .expect("open");
        for _ in 0..MAX_WINDOW_FAILURES * 2 {
            fixture.state.enrollment.record_failed_attempt();
        }
        assert!(
            fixture.state.enrollment.status().active,
            "a standing enrollment surface must not be closed by a guesser"
        );
    }

    #[tokio::test]
    async fn closing_a_permanent_window_leaves_the_pairing_code() {
        let fixture = fixture();
        let (_stop, stopping) = tokio::sync::watch::channel(false);
        fixture
            .state
            .enrollment
            .attach_runtime(crate::ServeTasks::default(), stopping);
        Arc::clone(&fixture.state.enrollment)
            .open(None, Some(0))
            .await
            .expect("open");
        let code = fixture
            .pairing
            .generate_new_pairing_code(zeroclaw_config::pairing::PairingCodePolicy::default())
            .expect("code");
        fixture.state.enrollment.close();
        assert_eq!(
            fixture.pairing.pairing_code().as_deref(),
            Some(code.as_str())
        );
    }

    #[test]
    fn the_per_address_limiter_is_bounded() {
        let fixture = fixture();
        for i in 0..MAX_TRACKED_ENROLL_CLIENTS as u32 {
            let ip = IpAddr::from(std::net::Ipv4Addr::from(i));
            assert!(fixture.state.allow_enroll(ip));
        }
        let newcomer = IpAddr::from(std::net::Ipv4Addr::BROADCAST);
        assert!(
            !fixture.state.allow_enroll(newcomer),
            "a full table of recent addresses refuses new ones"
        );
        assert_eq!(
            fixture.state.enroll_attempts.lock().len(),
            MAX_TRACKED_ENROLL_CLIENTS
        );
        let known = IpAddr::from(std::net::Ipv4Addr::from(0));
        for _ in 1..ENROLL_ATTEMPTS_PER_MINUTE {
            assert!(fixture.state.allow_enroll(known));
        }
        assert!(!fixture.state.allow_enroll(known));
    }
}
