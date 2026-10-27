# Passkeys: planned for later

**Status:** deferred until the game is hosted on the Mac mini behind a Cloudflare Tunnel.

## Why it waits

Browsers only allow passkeys (WebAuthn) on a secure origin: `https://` with a
real domain name, or `localhost`. Plain `http://<pc-ip>:1042` on the LAN is
refused, so passkeys can't work from phones until the site has an HTTPS domain.
A Cloudflare Tunnel provides exactly that.

## Prerequisites (at hosting time)

- Domain served through the Cloudflare Tunnel, e.g. `https://netrunner.<your-domain>`.
- Production config (`resources/prod.edn`, see `docker/docker-prod.edn`) with
  the session cookie `:secure true`. `resources/dev.edn` has it `false` only
  for LAN testing.
- The WebAuthn relying-party ID set to that domain (configurable, not hard-coded).

## Planned scope

- **Sign up with a passkey:** username + email + Face ID / Touch ID / fingerprint /
  Windows Hello, no password needed.
- **Log in with a passkey:** one tap, using browser autofill ("conditional UI").
- **Add a passkey to an existing account** from Account settings, and list/remove
  passkeys there.
- **Passwords stay as a fallback**, and so does password reset by email.

## Implementation notes

- Server: a maintained Java WebAuthn library from Maven Central (Yubico
  `java-webauthn-server` or `webauthn4j`), wrapped in a `web.passkeys` namespace.
  Credentials stored in a new `passkeys` collection (user id, credential id,
  public key, sign count, created/last-used dates).
- Endpoints: registration options + verify, authentication options + verify,
  list/delete for the account page. Challenges are short-lived and single-use.
- On successful verification, issue the same `session` cookie as password login
  (`session-cookie` in `src/clj/web/auth.clj`).
- Client (`src/cljs/nr/auth.cljs`, `src/cljs/nr/account.cljs`):
  `navigator.credentials.create/get`, a "Sign in with a passkey" button, and
  hiding the passkey options where the browser doesn't support them.
