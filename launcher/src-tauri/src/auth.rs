//! Microsoft account sign-in: OAuth 2.0 authorization code + PKCE via a loopback redirect,
//! then Xbox Live -> XSTS -> Minecraft services. Only official Microsoft accounts are supported.
//!
//! Secrets: the long-lived refresh token is stored in the OS credential store (keyring);
//! the Minecraft access token lives only in memory. Nothing secret is written to disk or logs.

use crate::error::{Error, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::{distributions::Alphanumeric, Rng};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const AUTHORIZE: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
const TOKEN: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const XBL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MC_LOGIN: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MC_PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";
const SCOPE: &str = "XboxLive.signin offline_access";
const KEYRING_SERVICE: &str = "dev.cobalmoon.launcher";
const LOGIN_TIMEOUT: Duration = Duration::from_secs(300);

/// Public account data, safe to persist and to send to the UI.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AccountInfo {
    pub uuid: String,
    pub name: String,
    #[serde(default)]
    pub xuid: String,
}

/// In-memory session used to launch the game.
#[derive(Clone)]
pub struct Session {
    pub info: AccountInfo,
    pub access_token: String,
    pub expires_at: u64,
}

impl Session {
    pub fn is_fresh(&self) -> bool {
        now() + 60 < self.expires_at
    }
}

pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

pub fn pkce() -> Pkce {
    let verifier: String = rand::thread_rng().sample_iter(&Alphanumeric).take(64).map(char::from).collect();
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    Pkce { verifier, challenge }
}

fn random_state() -> String {
    rand::thread_rng().sample_iter(&Alphanumeric).take(24).map(char::from).collect()
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

pub fn authorize_url(client_id: &str, redirect_uri: &str, challenge: &str, state: &str) -> String {
    let mut u = url::Url::parse(AUTHORIZE).expect("static url");
    u.query_pairs_mut()
        .append_pair("client_id", client_id)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", redirect_uri)
        .append_pair("scope", SCOPE)
        .append_pair("code_challenge", challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("state", state)
        .append_pair("prompt", "select_account");
    u.into()
}

/// Parses the request line of the loopback redirect ("GET /?code=..&state=.. HTTP/1.1").
/// Returns (code, state) or the OAuth error description.
pub fn parse_redirect(request: &str) -> Result<(String, String)> {
    let target = request
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .ok_or_else(|| Error::Auth("invalid redirect request".into()))?;
    let u = url::Url::parse(&format!("http://localhost{target}")).map_err(|_| Error::Auth("invalid redirect URL".into()))?;
    let mut code = None;
    let mut state = None;
    let mut error = None;
    for (k, v) in u.query_pairs() {
        match k.as_ref() {
            "code" => code = Some(v.into_owned()),
            "state" => state = Some(v.into_owned()),
            "error_description" => error = Some(v.into_owned()),
            "error" if error.is_none() => error = Some(v.into_owned()),
            _ => {}
        }
    }
    if let Some(e) = error {
        return Err(Error::Auth(format!("Microsoft sign-in was cancelled or failed: {e}")));
    }
    match (code, state) {
        (Some(c), Some(s)) => Ok((c, s)),
        _ => Err(Error::Auth("the redirect did not include an authorization code".into())),
    }
}

const DONE_PAGE: &str = r#"<!doctype html><html lang="es"><meta charset="utf-8"><title>Cobalmoon Client</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#10182C;color:#E9EDF2;font-family:system-ui,sans-serif">
<div style="text-align:center"><h1 style="color:#52E0D0;font-weight:600">Sesión iniciada</h1>
<p style="color:#7486B8">Ya puedes cerrar esta pestaña y volver a Cobalmoon Client.</p></div></body></html>"#;

/// Waits for the browser redirect on an already-bound loopback listener.
async fn wait_for_redirect(listeners: Vec<TcpListener>, expected_state: &str) -> Result<String> {
    let accept = async {
        loop {
            // Accept on whichever loopback address (IPv4 or IPv6) the browser used for "localhost".
            let (mut stream, _) = futures_util::future::select_all(listeners.iter().map(|l| Box::pin(l.accept())))
                .await
                .0?;
            let mut buf = vec![0u8; 8192];
            let n = stream.read(&mut buf).await?;
            let request = String::from_utf8_lossy(&buf[..n]).to_string();
            // Ignore favicon and other stray requests.
            if !request.starts_with("GET /?") {
                let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n").await;
                continue;
            }
            let result = parse_redirect(&request);
            let body = DONE_PAGE;
            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(resp.as_bytes()).await;
            let (code, state) = result?;
            if state != expected_state {
                return Err(Error::Auth("sign-in state mismatch; please try again".into()));
            }
            return Ok::<String, Error>(code);
        }
    };
    tokio::time::timeout(LOGIN_TIMEOUT, accept)
        .await
        .map_err(|_| Error::Auth("sign-in timed out after 5 minutes".into()))?
}

#[derive(Deserialize)]
struct MsTokens {
    access_token: String,
    refresh_token: String,
}

#[derive(Deserialize)]
struct XboxResponse {
    #[serde(rename = "Token")]
    token: String,
    #[serde(rename = "DisplayClaims")]
    display_claims: DisplayClaims,
}

#[derive(Deserialize)]
struct DisplayClaims {
    xui: Vec<Xui>,
}

#[derive(Deserialize)]
struct Xui {
    uhs: String,
    #[serde(default)]
    xid: Option<String>,
}

#[derive(Deserialize)]
struct McLogin {
    access_token: String,
    expires_in: u64,
}

#[derive(Deserialize)]
struct McProfile {
    id: String,
    name: String,
}

pub struct Authenticator {
    http: reqwest::Client,
    client_id: String,
}

impl Authenticator {
    pub fn new(http: reqwest::Client, client_id: String) -> Result<Self> {
        if client_id.trim().is_empty() {
            return Err(Error::Auth(
                "No Azure client ID configured. Set it in Settings (see docs/azure-app.md).".into(),
            ));
        }
        Ok(Self { http, client_id })
    }

    /// Full interactive sign-in. `open` is called with the Microsoft URL to show in the user's browser.
    pub async fn login_interactive(&self, open: impl FnOnce(&str) -> Result<()>) -> Result<(Session, String)> {
        let v4 = TcpListener::bind("127.0.0.1:0").await?;
        let port = v4.local_addr()?.port();
        let mut listeners = vec![v4];
        if let Ok(v6) = TcpListener::bind(("::1", port)).await {
            listeners.push(v6);
        }
        let redirect_uri = format!("http://localhost:{port}");
        let p = pkce();
        let state = random_state();
        open(&authorize_url(&self.client_id, &redirect_uri, &p.challenge, &state))?;
        let code = wait_for_redirect(listeners, &state).await?;

        let tokens: MsTokens = self
            .token_request(&[
                ("client_id", self.client_id.as_str()),
                ("grant_type", "authorization_code"),
                ("code", code.as_str()),
                ("redirect_uri", redirect_uri.as_str()),
                ("code_verifier", p.verifier.as_str()),
                ("scope", SCOPE),
            ])
            .await?;
        let session = self.minecraft_session(&tokens.access_token).await?;
        Ok((session, tokens.refresh_token))
    }

    /// Silent sign-in from a stored refresh token. Returns the new session and the rotated refresh token.
    pub async fn refresh(&self, refresh_token: &str) -> Result<(Session, String)> {
        let tokens: MsTokens = self
            .token_request(&[
                ("client_id", self.client_id.as_str()),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh_token),
                ("scope", SCOPE),
            ])
            .await?;
        let session = self.minecraft_session(&tokens.access_token).await?;
        Ok((session, tokens.refresh_token))
    }

    async fn token_request(&self, form: &[(&str, &str)]) -> Result<MsTokens> {
        let resp = self.http.post(TOKEN).form(form).send().await?;
        if !resp.status().is_success() {
            let status = resp.status();
            let body: serde_json::Value = resp.json().await.unwrap_or_default();
            let desc = body.get("error_description").and_then(|v| v.as_str()).unwrap_or("unknown error");
            let first_line = desc.lines().next().unwrap_or(desc);
            return Err(Error::Auth(format!("Microsoft token request failed ({status}): {first_line}")));
        }
        Ok(resp.json().await?)
    }

    async fn minecraft_session(&self, ms_access_token: &str) -> Result<Session> {
        // 1. Xbox Live user token
        let xbl: XboxResponse = self
            .http
            .post(XBL)
            .json(&json!({
                "Properties": { "AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access_token}") },
                "RelyingParty": "http://auth.xboxlive.com",
                "TokenType": "JWT"
            }))
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let uhs = xbl.display_claims.xui.first().map(|x| x.uhs.clone()).ok_or_else(|| Error::Auth("Xbox Live returned no user hash".into()))?;

        // 2. XSTS token for Minecraft services
        let resp = self
            .http
            .post(XSTS)
            .json(&json!({
                "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl.token] },
                "RelyingParty": "rp://api.minecraftservices.com/",
                "TokenType": "JWT"
            }))
            .send()
            .await?;
        if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
            let body: serde_json::Value = resp.json().await.unwrap_or_default();
            return Err(Error::Auth(xsts_error(body.get("XErr").and_then(|v| v.as_u64()).unwrap_or(0))));
        }
        let xsts: XboxResponse = resp.error_for_status()?.json().await?;
        let xuid = xsts.display_claims.xui.first().and_then(|x| x.xid.clone()).unwrap_or_default();

        // 3. Minecraft access token
        let resp = self
            .http
            .post(MC_LOGIN)
            .json(&json!({ "identityToken": format!("XBL3.0 x={uhs};{}", xsts.token) }))
            .send()
            .await?;
        if resp.status() == reqwest::StatusCode::FORBIDDEN {
            return Err(Error::Auth(
                "Minecraft services rejected this launcher's Azure app (403). The app must be approved by Mojang — see docs/azure-app.md.".into(),
            ));
        }
        let mc: McLogin = resp.error_for_status()?.json().await?;

        // 4. Profile (also proves the account owns Java Edition)
        let resp = self.http.get(MC_PROFILE).bearer_auth(&mc.access_token).send().await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Err(Error::Auth("This Microsoft account does not own Minecraft: Java Edition.".into()));
        }
        let profile: McProfile = resp.error_for_status()?.json().await?;

        Ok(Session {
            info: AccountInfo { uuid: profile.id, name: profile.name, xuid },
            access_token: mc.access_token,
            expires_at: now() + mc.expires_in,
        })
    }
}

pub fn xsts_error(code: u64) -> String {
    match code {
        2148916233 => "This Microsoft account has no Xbox profile. Sign in once at minecraft.net to create one.".into(),
        2148916235 => "Xbox Live is not available in your country/region.".into(),
        2148916236 | 2148916237 => "This account needs adult verification on xbox.com (South Korea).".into(),
        2148916238 => "This is a child account: an adult must add it to a Microsoft family first.".into(),
        other => format!("Xbox Live refused the sign-in (XErr {other})."),
    }
}

// ---- Credential storage (OS keyring) ----

fn entry(uuid: &str) -> Result<keyring::Entry> {
    Ok(keyring::Entry::new(KEYRING_SERVICE, uuid)?)
}

pub fn store_refresh_token(uuid: &str, token: &str) -> Result<()> {
    entry(uuid)?.set_password(token)?;
    Ok(())
}

pub fn load_refresh_token(uuid: &str) -> Result<Option<String>> {
    match entry(uuid)?.get_password() {
        Ok(t) => Ok(Some(t)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.into()),
    }
}

pub fn delete_refresh_token(uuid: &str) -> Result<()> {
    match entry(uuid)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_challenge_is_s256_of_verifier() {
        let p = pkce();
        assert_eq!(p.verifier.len(), 64);
        assert_eq!(p.challenge, URL_SAFE_NO_PAD.encode(Sha256::digest(p.verifier.as_bytes())));
        assert!(!p.challenge.contains('='));
    }

    #[test]
    fn rfc7636_example() {
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assert_eq!(URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes())), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    #[test]
    fn authorize_url_has_pkce_and_scope() {
        let u = authorize_url("cid", "http://localhost:1234", "chal", "st");
        assert!(u.starts_with(AUTHORIZE));
        assert!(u.contains("code_challenge=chal"));
        assert!(u.contains("code_challenge_method=S256"));
        assert!(u.contains("scope=XboxLive.signin+offline_access"));
        assert!(u.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1234"));
    }

    #[test]
    fn parses_successful_redirect() {
        let (code, state) = parse_redirect("GET /?code=M.C1_abc&state=xyz HTTP/1.1\r\nHost: localhost\r\n\r\n").unwrap();
        assert_eq!(code, "M.C1_abc");
        assert_eq!(state, "xyz");
    }

    #[test]
    fn parses_error_redirect() {
        let err = parse_redirect("GET /?error=access_denied&error_description=The+user+cancelled HTTP/1.1").unwrap_err();
        assert!(err.to_string().contains("The user cancelled"));
    }

    #[test]
    fn missing_client_id_is_explained() {
        let err = Authenticator::new(reqwest::Client::new(), " ".into()).err().unwrap();
        assert!(err.to_string().contains("azure-app.md"));
    }

    #[test]
    fn xsts_messages() {
        assert!(xsts_error(2148916238).contains("child"));
        assert!(xsts_error(1).contains("XErr 1"));
    }
}
