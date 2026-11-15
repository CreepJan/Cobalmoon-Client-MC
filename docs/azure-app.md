# Microsoft sign-in: registering the Azure app

Cobalmoon only supports official Microsoft accounts. To sign in, the launcher needs an **Azure
application (client) ID**. The ID is public (it ships inside every launcher and is not a secret),
but **Mojang must approve the app** before it can call the Minecraft services API. Until then,
sign-in fails at the last step with:

> Minecraft services rejected this launcher's Azure app (403)

## 1. Create the app registration

1. Sign in at <https://portal.azure.com> with any Microsoft account → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Name: `Cobalmoon Client`.
3. Supported account types: **Personal Microsoft accounts only**.
4. Redirect URI: platform **Public client/native (mobile & desktop)**, value `http://localhost`.
   The launcher listens on a random free port (`http://localhost:<port>`); Microsoft accepts any port for `localhost`.
5. Create it and copy the **Application (client) ID**.
6. Under **Authentication**, set **Allow public client flows** to **Yes**. Do **not** create a client secret: the launcher uses PKCE and never needs one.

## 2. Request access to the Minecraft API

Fill in Mojang's form for third-party launchers (linked from the Minecraft Help Center and
[minecraft-launcher-lib's guide](https://minecraft-launcher-lib.readthedocs.io/en/latest/tutorial/microsoft_login.html)),
giving the client ID, the project URL and a short description: free, open-source PvP launcher; official
accounts only; tokens stored in the OS credential store; no telemetry. Approval can take days or weeks.

## 3. Configure Cobalmoon

Use either of these:

- **Per user:** launcher → **Ajustes** → *Client ID de Azure*.
- **Built in (releases):** set the repository variable `COBALMOON_MSA_CLIENT_ID`; `release.yml` compiles it in as the default.
  For a local build: `COBALMOON_MSA_CLIENT_ID=<id> npm run tauri build`.

## What the launcher does with your account

| Data | Where it lives |
|---|---|
| Microsoft refresh token | OS credential store (Windows Credential Manager, macOS Keychain, Secret Service on Linux) |
| Minecraft access token | Memory only; masked as `<token>` in every log line |
| Name and UUID | `accounts.json` in the launcher's data folder |
| Password | Never seen: you type it on Microsoft's own page in your browser |

## Developing without an approved app

You don't need sign-in to work on the mod: `./gradlew :client-core:mc-26.3:runClient` starts the game
with Fabric's development profile. The launcher's download and launch pipeline is covered by
`cargo test --test launch_smoke -- --ignored` (see `launcher/src-tauri/tests/launch_smoke.rs`).
