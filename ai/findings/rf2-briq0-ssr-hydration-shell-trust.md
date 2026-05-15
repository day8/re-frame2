# rf2-briq0: SSR Hydration Exposure and Shell Trust Model

## Scope

This is a report-only security/spec review for bead `rf2-briq0`. It covers:

- `implementation/ssr-ring`
- SSR hydration policy in `spec/011-SSR.md` and `spec/Spec-Schemas.md`
- SSR Ring tests and conformance fixtures relevant to hydration payloads, shell emission, head/body attributes, and streaming final payloads

No implementation changes were made.

## Contract Answers

### Should whole-app-db hydration ever be the default payload policy?

No.

Whole-`app-db` hydration should remain possible as an explicit development/convenience policy, but it should not be the default for a public SSR host adapter. The default should be "no application state crosses the wire unless selected by a named/public hydration policy."

Current spec language already points in this direction: `spec/011-SSR.md` says the hydration payload is "bounded" and carries "the minimum data the client needs to recompute the server's view." However, the Ring implementation currently falls back to the entire `app-db` when `:payload-keys` is absent.

Defaulting to the whole app-db makes privacy depend on every app author never placing server-only or user-sensitive data in `app-db`. That is the wrong default for a framework-level SSR boundary. The framework should make the safe posture the path of least resistance.

### What trust level should shell options assume?

Shell options should be trusted-code-only by default.

The options `:app-element-id`, `:script-src`, `:head`, and `:body-end` are HTML/DOM assembly inputs, not user-content fields. They should be treated like code-level deployment configuration selected by the application at boot, not as mutable tenant/admin/user data.

Current implementation already documents this explicitly for `:body-end`: it is concatenated as raw HTML, with "trust-the-caller" load-bearing. But the same trust model effectively applies to the other shell options:

- `:app-element-id` is interpolated into an HTML attribute without local escaping in `default-html-shell` / `default-streaming-prefix`.
- `:script-src` is interpolated into a script `src` attribute without local escaping in non-streaming and streaming suffix paths.
- `:head` is injected as already-rendered/raw head HTML when caller-supplied, bypassing route-driven `reg-head` model emission.
- `:body-end` is appended as raw HTML and may contain arbitrary script tags.

Therefore, the public contract should say these are trusted-code-only shell controls. If an application wants admin/tenant-controlled shell customisation, that should be a separate safe-data API with structured fields, escaping, allowlists, and CSP expectations, not the raw shell option surface.

### What elegant pre-alpha default would make this state-of-the-art?

Adopt an explicit hydration exposure contract:

1. Default payload policy: `:none` or `:declared-public`, not `:whole-app-db`.
2. Public hydration state is selected by declarative, inspectable registrations or by an explicit payload selector.
3. Whole-app-db is available only via an explicit opt such as `:payload-policy :whole-app-db` or equivalent.
4. Shell raw HTML options are documented and validated as trusted-code-only; untrusted shell customisation gets a separate structured API.

The state-of-the-art version is not "support `:payload-keys`." It is "make hydration exposure a first-class contract." That means the default has no accidental disclosure path, every exposed slice is visible to tools and review, and tests can assert the public hydration surface without reading arbitrary app code.

## Current Behavior

### Non-streaming Ring payload

`re-frame.ssr.ring.payload/build-payload` accepts `:payload-keys`. If present, it serialises `(select-keys app-db payload-keys)`. If absent, it serialises the entire `app-db`.

The `ssr-handler` docstring describes this directly: `:payload-keys` is optional, and the default is to "ship the whole app-db."

The pipeline obtains `app-db` via `rf/get-frame-db` after render, calls `payload/build-payload`, serialises it with `pr-str`, and injects it into the default shell's `__rf_payload` script.

### Streaming final payload

`re-frame.ssr.streaming/build-final-payload` mirrors the same behavior. If `:payload-keys` is absent, the final streaming payload includes the whole frame `app-db`.

Streaming per-subtree deltas are separate, but the final payload is authoritative. So the streaming adapter has the same default exposure issue.

### Shell emission

The default shell has good protection for the hydration payload script body: it escapes `<` in the EDN payload before inserting it into `<script id="__rf_payload" type="application/edn">`.

The default shell also supports:

- `:head` as raw head HTML
- `:html-attrs` / `:body-attrs` via `attr-string`
- `:app-element-id` interpolated into `<div id="...">`
- `:script-src` interpolated into `<script src="...">`
- `:body-end` appended raw

There is a debug-gated CSP-host warning for `:body-end` script hosts when `:csp-script-src-allowlist` is supplied. It warns only; it does not block.

### Tests

Existing tests pin:

- `:payload-keys` can slice app-db and omit server-only top-level keys.
- response accumulator data no longer lives in `app-db`, so cookies/headers do not leak through the hydration payload.
- payload script-body `</script>` injection is escaped.
- `:html-attrs` / `:body-attrs` are stamped via the shared attribute helper.
- `:body-end` CSP allowlist warnings emit in debug mode.

The tests do not currently pin a safe default payload policy. They pin the existence of `:payload-keys`, not that absence of a policy is safe.

## Risks

### Whole-app-db default leaks by construction

The response accumulator leak was fixed by moving response state out of `app-db`. That is good, but it addresses only framework-owned server response state. Application-owned secrets and PII can still land in `app-db` during SSR:

- session-derived user profile fields
- auth/permission flags
- CSRF tokens or form tokens
- internal feature flags
- request-derived metadata
- partner/API response payloads
- transient server-only loader data

If whole-app-db is the default, every such value is exposed unless the app author remembers to configure `:payload-keys` correctly.

### `:payload-keys` is too coarse as the only safety valve

Top-level key selection is useful, but not expressive enough for high-quality SSR defaults:

- public and private fields often share a top-level domain key such as `:user`, `:account`, `:checkout`, or `:route/data`
- route-specific payload needs differ by page
- derived hydration data may be safer than raw app-db slices
- tools cannot easily audit intent from an unstructured vector of keys

### Shell option trust is implicit and uneven

`:body-end` is documented as trusted raw HTML. `:head`, `:script-src`, and `:app-element-id` carry similar injection consequences but do not have the same prominent trust-model statement.

This creates a trap for deployments that expose "custom scripts", "custom head tags", "custom app id", or "asset URL" settings through an admin UI or tenant config table.

### Debug-only warning is not a production safety boundary

The CSP-host allowlist warning is useful observability, but it is debug-gated and non-blocking. It should not be represented as protecting production `:body-end`.

## Options

### Option A: Keep whole-app-db default, document caveats

This is the compatibility path. It preserves current behavior and requires users to discover/use `:payload-keys`.

Reject. Pre-alpha posture should prefer the cleaner and safer contract. Documentation-only warnings are not enough for a framework-owned SSR boundary.

### Option B: Default to empty app-db unless `:payload-keys` is supplied

This is simple and safe. It makes accidental disclosure unlikely, but it can produce hydration mismatches unless users configure every SSR route correctly. It also keeps payload selection as a host-adapter option rather than an app/spec-visible model.

Acceptable as an interim hardening step, but not elegant enough as the final shape.

### Option C: Require an explicit payload policy

The handler refuses to construct unless the caller chooses one of a small set:

- `:payload-policy :none`
- `:payload-policy {:keys [...]}` or `:payload-keys [...]`
- `:payload-policy :declared-public`
- `:payload-policy :whole-app-db`

This is safer than current behavior and makes whole-app-db consciously chosen. It is still more host-option-shaped than pattern-shaped.

Good transitional choice.

### Option D: Declarative public hydration registry

Add a first-class SSR hydration exposure model. Apps register public hydration slices/selectors by id, with metadata. Routes or handler options name which hydration policy to apply.

Example shape, not a final API proposal:

```clojure
(rf/reg-hydration-slice :hydration/article
  {:paths [[:articles/current] [:rf/route]]
   :doc "Public article page state"})
```

or:

```clojure
(rf/reg-hydration-policy :hydration/article
  {:select (fn [db route] ...)
   :schema :public/article-hydration
   :doc "Only data needed to rerender article page"})
```

This makes exposure reviewable, route-local, testable, and tool-visible.

Recommended final direction.

## Recommendation

Adopt Option D as the target, with Option C as the implementation bridge if needed.

Recommended public contract:

- Default policy: no whole-app-db. Prefer `:declared-public` if route/hydration declarations exist; otherwise fail closed or emit an empty payload depending on how strict the pre-alpha contract wants to be.
- Whole-app-db: allowed only by explicit opt, named in a way that makes the exposure obvious.
- Public hydration: declared by id, route, or registered policy. The declaration is data-first and visible to tools.
- Replace semantics: keep `:rf/hydrate` replace-app-db as-is. The payload's `:rf/app-db` is the selected public hydration value, not necessarily a raw full app-db snapshot.
- Streaming: final payload and progressive deltas must use the same policy. Deltas should never introduce keys outside the final policy unless the streaming policy explicitly allows a boundary-local public delta.

Recommended shell contract:

- `:app-element-id`, `:script-src`, `:head`, `:body-end`, `:html-shell`, `default-streaming-prefix`, and `default-streaming-suffix` are trusted-code-only deployment hooks.
- They must not be fed from mutable tenant/admin/user config without an application-owned validation/sanitisation layer.
- If re-frame2 wants first-class tenant-safe shell customisation, it should provide a separate structured API: e.g. `:asset-script {:src ... :integrity ... :nonce ...}`, `:head-model`, `:json-ld`, and allowlisted script descriptors. Raw HTML remains an escape hatch, not the safe path.

## Implementation Implications

Hydration payload:

- Change non-streaming `payload/build-payload` so absent policy does not mean whole-app-db.
- Change streaming `build-final-payload` the same way.
- Consider centralising policy resolution so streaming and non-streaming cannot drift.
- Preserve current `:payload-keys` as a migration/bridge, but make its use explicit and documented as a selected policy.
- Add a deliberately loud opt for full state, e.g. `:payload-policy :whole-app-db`, with docstring naming the exposure.
- Decide whether missing policy is fail-fast at handler construction or request time. Handler construction is better for operator feedback, but route-specific policies may need request-time resolution.

Shell:

- Escape `:app-element-id` and `:script-src` if they remain simple string opts, or validate them as safe tokens/URLs before emission.
- Keep `:head` and `:body-end` raw only under a trusted-code-only contract.
- Consider renaming raw hooks to make trust explicit, e.g. `:trusted-head-html`, `:trusted-body-end-html`, `:trusted-script-src`.
- Keep route-driven `reg-head` / head model as the preferred safe surface because it goes through structured emission/escaping.

Docs/spec:

- Update `spec/011-SSR.md` payload scope to distinguish "payload contains `:rf/app-db`" from "payload contains the whole app-db."
- Update `Spec-Schemas.md` comment for `:rf/app-db` from "serialised app-db" to "serialised public hydration state / selected app-db slice."
- Add a shell trust-model section to Spec 011 or Security.md.
- Cross-reference the raw shell trust model from Ring adapter docstrings and setup guide examples.

## Test Implications

Add tests that pin the new contract:

- Missing payload policy does not expose arbitrary app-db keys.
- Explicit `:payload-policy :whole-app-db` exposes all app-db keys and is the only way to get current behavior.
- `:payload-keys` / equivalent policy exposes only selected keys.
- Route-specific or registered hydration policy can expose a nested public subset while excluding sibling private data under the same top-level key.
- Streaming final payload obeys the same policy as non-streaming.
- Streaming deltas cannot leak keys excluded from the final policy.
- Raw shell options are documented/validated as trusted-only; if validation is added, malicious `:app-element-id` / `:script-src` values fail or escape as expected.
- Admin/tenant-safe shell customisation, if added, round-trips through structured escaping and CSP allowlisting.

## Beads To Create Later

Do not create these in this report-only task unless the operator asks. Suggested follow-up beads:

1. `security(spec): define SSR hydration payload policy contract`
   Decide the named policy shape, default behavior, and whole-app-db opt-in spelling.

2. `impl(ssr-ring): remove whole-app-db default payload`
   Apply the decided policy to non-streaming Ring payload construction.

3. `impl(ssr-streaming): share hydration payload policy with non-streaming`
   Centralise policy resolution and ensure final payload/deltas cannot drift.

4. `spec(security): document SSR shell trusted-code-only model`
   Add explicit trust-boundary language for raw shell hooks and safe structured alternatives.

5. `test(ssr): pin hydration exposure defaults and streaming parity`
   Add regression tests for fail-closed/default-safe payload policy and explicit full-app-db opt-in.

6. `impl(ssr-ring): validate or rename raw shell options`
   Make `:app-element-id`, `:script-src`, `:head`, and `:body-end` trust obvious in API and/or validation.

## Bottom Line

The current SSR Ring implementation is materially safer than earlier drafts because response/request accumulators no longer ride `app-db` and the payload script body is escaped. But the remaining default is still too broad: absent `:payload-keys`, the adapter serialises the whole app-db.

For pre-alpha, re-frame2 should take the stronger path: make hydration exposure explicit, tool-visible, and fail-closed by default. Whole-app-db should be a named opt-in, not ambient behavior. Raw shell hooks should be trusted-code-only, with any untrusted tenant/admin customisation routed through a separate structured safe API.
