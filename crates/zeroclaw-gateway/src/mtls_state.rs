//! State for the gateway's identity-based mTLS listener: issued-certificate
//! ledger, dynamic revocation, and active-connection tracking.
//!
//! The CA private key is the root of trust for the listener; it stays in this
//! process (loaded at startup) and never leaves it. Revocation is checked per
//! handshake against an in-memory set and active sessions are cancelled by
//! their peer certificate fingerprint.

use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use parking_lot::{Mutex, RwLock};
use serde::{Deserialize, Serialize};
use tokio_util::sync::CancellationToken;

use zeroclaw_config::pairing::PairingGuard;

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
}

pub struct MtlsState {
    pub dir: PathBuf,
    pub gateway_id: String,
    pub ca_cert_pem: String,
    pub ca_key_pem: zeroize::Zeroizing<String>,
    pub pairing: Arc<PairingGuard>,
    revoked: Arc<RwLock<HashSet<String>>>,
    active: Mutex<HashMap<String, Vec<CancellationToken>>>,
    enroll_attempts: Mutex<HashMap<IpAddr, Vec<Instant>>>,
}

impl MtlsState {
    pub fn load(
        dir: PathBuf,
        gateway_id: String,
        ca_cert_pem: String,
        ca_key_pem: zeroize::Zeroizing<String>,
        pairing: Arc<PairingGuard>,
    ) -> Result<Arc<Self>> {
        std::fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
        let mut revoked = HashSet::new();
        let revoked_path = dir.join("revoked");
        if revoked_path.exists() {
            for line in std::fs::read_to_string(&revoked_path)
                .with_context(|| format!("reading {}", revoked_path.display()))?
                .lines()
            {
                let value = line.trim().replace(':', "").to_lowercase();
                if !value.is_empty() && !value.starts_with('#') {
                    revoked.insert(value);
                }
            }
        }
        Ok(Arc::new(Self {
            dir,
            gateway_id,
            ca_cert_pem,
            ca_key_pem,
            pairing,
            revoked: Arc::new(RwLock::new(revoked)),
            active: Mutex::new(HashMap::new()),
            enroll_attempts: Mutex::new(HashMap::new()),
        }))
    }

    pub fn ledger_path(&self) -> PathBuf {
        self.dir.join("issued.json")
    }

    pub fn read_ledger(&self) -> Vec<IssuedCert> {
        let path = self.ledger_path();
        if !path.exists() {
            return Vec::new();
        }
        std::fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<Vec<IssuedCert>>(&text).ok())
            .unwrap_or_default()
    }

    fn write_ledger(&self, entries: &[IssuedCert]) -> Result<()> {
        let text = serde_json::to_string_pretty(entries).context("serializing the cert ledger")?;
        std::fs::write(self.ledger_path(), text)
            .with_context(|| format!("writing {}", self.ledger_path().display()))
    }

    pub fn record_issued(&self, entry: IssuedCert) -> Result<()> {
        let mut entries = self.read_ledger();
        entries.retain(|existing| existing.fingerprint != entry.fingerprint);
        entries.push(entry);
        self.write_ledger(&entries)
    }

    /// Whether a peer certificate fingerprint is revoked.
    pub fn is_revoked(&self, fingerprint: &str) -> bool {
        self.revoked.read().contains(&fingerprint.to_lowercase())
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

    /// Revoke one fingerprint or every certificate for a device id.
    ///
    /// Returns the revoked fingerprints. Active sessions for those
    /// certificates are cancelled immediately, and the fingerprints are
    /// appended to the on-disk CRL so a restart keeps the revocation.
    pub fn revoke(&self, selector: &str) -> Result<Vec<String>> {
        let selector = selector.trim().replace(':', "").to_lowercase();
        let mut entries = self.read_ledger();
        let mut targets: Vec<String> = entries
            .iter()
            .filter(|entry| {
                entry.device_id.eq_ignore_ascii_case(&selector)
                    || entry.fingerprint.eq_ignore_ascii_case(&selector)
            })
            .map(|entry| entry.fingerprint.to_lowercase())
            .collect();
        if targets.is_empty() {
            // Allow revoking a fingerprint that was issued out of band.
            targets.push(selector);
        }
        {
            let mut revoked = self.revoked.write();
            let mut crl = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(self.dir.join("revoked"))
                .with_context(|| format!("opening {}", self.dir.join("revoked").display()))?;
            use std::io::Write as _;
            for fingerprint in &targets {
                if revoked.insert(fingerprint.clone()) {
                    writeln!(crl, "{fingerprint}")?;
                }
            }
        }
        for entry in &mut entries {
            if targets.contains(&entry.fingerprint.to_lowercase()) {
                entry.revoked = true;
            }
        }
        self.write_ledger(&entries)?;

        let mut active = self.active.lock();
        for fingerprint in &targets {
            if let Some(tokens) = active.remove(fingerprint) {
                for token in tokens {
                    token.cancel();
                }
            }
        }
        Ok(targets)
    }

    /// Bounded per-IP enrollment attempt counter (10 per minute).
    pub fn allow_enroll(&self, ip: IpAddr) -> bool {
        let now = Instant::now();
        let mut attempts = self.enroll_attempts.lock();
        let window = attempts.entry(ip).or_default();
        window.retain(|at| now.duration_since(*at) < Duration::from_secs(60));
        if window.len() >= 10 {
            return false;
        }
        window.push(now);
        true
    }

    /// Ledger plus active connection counts, for `mtls list`.
    pub fn list(&self) -> (Vec<IssuedCert>, Vec<(String, usize)>) {
        (self.read_ledger(), self.active_fingerprints())
    }
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

/// Write the SAS the operator compares with the app.
pub fn enrollment_sas_for_code(code: &str, ca_cert_pem: &str) -> Result<String> {
    let fingerprint = zeroclaw_tls::single_cert_pem_sha256_fingerprint(ca_cert_pem)?;
    Ok(zeroclaw_tls::enrollment_sas(code, &fingerprint))
}
