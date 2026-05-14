# Security — posture, threat model, and decisions

> **Type:** Reference
> The framework's consolidated security-posture document. Top-level catalogue + threat model + decisions log, with cross-references to the specs that own the concrete defenses. The owning specs carry the per-defense detail; this doc names the concern, points at the owner, and records the decision.

## How to read this doc

This doc is a **coordination layer**, not a duplicate. Each category below names a class of concern, lists the concrete defenses re-frame2 ships, and cross-references the owning Spec section + the bead where the decision was recorded. The detail lives in the owner; this doc names *what is defended, where the defense lives, and why the call was made*.

Five sections:

1. **[Threat model + scope](#threat-model--scope)** — what the framework defends and what is explicitly out of scope.
2. **[Categories](#categories)** — input validation, XSS at output boundaries, CRLF injection, privacy / secrets, DoS by input, MCP tool authority, editor URI allowlist, file-path boundaries, production gates.
3. **[Catalogue references](#catalogue-references)** — pointers (not duplication) into [Conventions.md](Conventions.md) and Spec 009 for the `:rf.error/*` / `:rf.warning/*` rows, reserved config slots, and `:sensitive?` / `:large?` meta keys.
4. **[Pragmatic stance](#pragmatic-stance)** — the working principle that governs every call below.
5. **[Decisions log](#decisions-log)** — bead IDs + one-line rationales for every concrete call.

The mirror to this doc is [Ownership.md](Ownership.md) — Security here names *what is defended*; Ownership names *where the defense's contract lives*. Where the two overlap (the `:sensitive?` slot, the wire-elision walker, the `:rf.http/max-decoded-keys` cap), Ownership wins on the "who owns the surface" question; Security names the threat the surface defends against.

## Threat model + scope

re-frame2 is a framework. The trust boundaries it owns are the surfaces *the framework* emits, parses, renders, or mediates. The trust boundaries *applications* own — authentication, authorisation, deployment hardening, network egress policy, user-code input validation — are out of scope by design. Framework defenses compose with application defenses; the framework does not pretend to substitute for them.

### What the framework defends

- **Framework-emitted traces, error records, and MCP wire surfaces.** The trace bus ([009](009-Instrumentation.md)), the always-on error-emit substrate ([009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)), the always-on event-emit substrate ([009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces)), and the MCP triplet's wire pipelines (pair2-mcp, story-mcp, causa-mcp per [Tool-Pair.md](Tool-Pair.md)) all carry framework-shaped payloads to consumers that may forward off-box. Sensitive values must not default-leak; oversize values must elide deterministically; the wire vocabulary must be stable across servers.
- **Framework-parsed input.** The managed-HTTP decoder ([014 §Decoding](014-HTTPRequests.md#decoding)) parses HTTP response bodies — JSON, EDN, custom decoders — into Clojure values. The hydration boundary ([011 §Payload scope](011-SSR.md#payload-scope-canonical-boundary)) parses serialised app-db on the client. Schema-driven boundary validation ([010 §Boundary-validation seam](010-Schemas.md#boundary-validation-seam)) gates structured-input surfaces. Each parse site is an attacker-controllable input under hostile-upstream / supply-chain compromise threat models.
- **Framework-rendered output.** Server-side rendering ([011](011-SSR.md)) emits HTML strings — hiccup → DOM, with attribute values, text content, and JSON-LD payloads inlined into a script context. Each emission position has different escaping rules; mixing them is the XSS vector. Response-shape fx (`:rf.server/set-header`, `:rf.server/redirect`, `:rf.server/set-cookie`) produce header strings on the wire boundary.
- **Framework-mediated dispatch.** The router accepts events, drains them through registered handlers, runs the eight effect-resolution dominoes, and commits app-db. Cascading dispatch, depth-exceeded recovery, and the `:on-error` substrate are runtime concerns the framework owns.
- **Framework tooling authority.** The MCP triplet ships tools that read and mutate live runtime state (`get-app-db`, `dispatch`, `restore-epoch`, `reset-frame-db`, and the qualitatively-different `eval-cljs`). The authority model — which tools require explicit gates, which assume "I invoked the tool" as consent — is a framework decision.

### What is explicitly out of scope

- **Application authentication and authorisation.** The framework does not ship a login flow, a session store, an authz policy engine, or a permission model. Apps build these on top, typically as a feature with its own slice of app-db and its own events. The framework's only auth-adjacent surface is `:rf/server-init` cofx-injection for SSR ([011 §Cofx injection at boot](011-SSR.md)), which is a *plumbing* hook, not an auth surface.
- **Deployment configuration.** TLS termination, reverse-proxy hardening, CDN edge rules, web application firewalls, network segmentation. These are operations concerns; the framework does not configure them and cannot enforce them.
- **User-code input validation beyond what the framework offers.** Schemas ([010](010-Schemas.md)) provide a declarative validator the framework runs at registered boundaries; the framework does not pretend that every user surface is automatically validated. Apps that read untrusted input through paths the framework does not gate are responsible for their own validation.
- **Network-level concerns.** Same-origin policy, CORS preflight, certificate pinning, mTLS — these ride the host platform's networking stack (browser fetch, JDK HttpClient). The framework classifies CORS rejections as a failure category ([014 §Classification order](014-HTTPRequests.md#classification-order)) but does not configure CORS.
- **Supply-chain integrity of host dependencies.** npm packages, Maven artefacts, the host JVM. The framework pins Cheshire as a hard dep (rf2-dgsu1) but does not verify host-package signatures.
- **Third-party egress in dev tooling.** Documented; not gated. Story's QR generator hits `api.qrserver.com`; axe-core loads from a public CDN. These are dev-tool conveniences with documented egress (rf2-su313); apps that want to bundle locally do so on the user side.

The split mirrors [000 §Goals](000-Vision.md#goals) — the framework optimises for *AI-implementable from the spec alone*, which means the security surface is the surface the spec normatively pins. Concerns outside that surface are real but live in adjacent domains.

## Categories

Each category names the concern, lists the concrete defenses re-frame2 ships, and cross-references the owning spec section + the bead where the decision was recorded.

### Input validation / boundary parsing

The framework parses attacker-controllable bytes at three boundaries: managed-HTTP response bodies ([014 §Decoding](014-HTTPRequests.md#decoding)), the SSR hydration payload's deserialisation on the client ([011 §Payload scope](011-SSR.md#payload-scope-canonical-boundary)), and the schema-driven boundary-validation seam ([010 §Boundary-validation seam](010-Schemas.md#boundary-validation-seam)). Each is a point where a compromised upstream / hostile partner / corrupted edge could submit shaped bytes intended to crash the parse, mint state, or amplify load.

- **JSON keyword-interning cap** — the managed-HTTP JSON decoder caps the number of unique object keys interned as keywords per request. Default 10000; opt-up via the per-request `:rf.http/max-decoded-keys` slot. Overflow throws `:rf.http/decode-failure` with `:reason :too-many-keys`. Both the Cheshire path and the CLJS reader honour the cap. Per [014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5) (rf2-wu1n5).
- **Pure-Clojure JSON reader bounds-check** — the legacy fallback reader (now removed per rf2-dgsu1, but the bounds-check is the canonical contract for any port that ships a hand-rolled reader) bounds-checks every `\uXXXX` escape and validates the hex digits; truncated / invalid escapes surface `:rf.error/malformed-json` with `:reason :truncated-unicode-escape` / `:invalid-unicode-escape` rather than throwing an opaque NPE. Per rf2-263km.
- **Cheshire is a hard JVM dep** — the JVM HTTP path requires Cheshire; the hand-rolled fallback reader (and the associated `requiring-resolve` indirection) has been deleted. Removing the fallback eliminates a parser the framework owns and would have to keep hardened across versions. Per rf2-dgsu1.
- **Boundary-validation seam** — `:spec/validate-at-boundary` interceptors run schemas at event / sub / fx boundaries and emit `:rf.error/schema-validation-failure` on mismatch. Apps that want shape-correctness on every wire-edge ingress install the interceptor at the boundary they care about. Per [010 §Boundary-validation seam](010-Schemas.md#boundary-validation-seam).

### XSS at output boundaries

The SSR HTML emitter renders hiccup data to a string that crosses the trust boundary into a browser. Three emission positions have different escaping rules — text nodes, attribute values, and raw-script bodies (`<script>` for JSON-LD, `<style>`) — and the emitter must apply the position-appropriate escape at every leaf. The reagent-slim adapter has the parallel surface on the client side: hiccup attrs land on React `createElement` props.

- **JSON-LD `<script>` body escape** — string values inlined into a `<script type="application/ld+json">` body have every `<` re-encoded as `<` so an attacker-supplied substring cannot close the script context. Per rf2-m5u23 and [011 §HTML emission](011-SSR.md).
- **Hiccup attribute-key escape** — attribute *keys* (not just values) are escaped at the SSR emitter so an attacker-controlled key (a registered hiccup form receiving keyed data) cannot break out of the attribute namespace. Per rf2-vl8ir.
- **Reagent-slim event-handler-prop filter + reserved-prop-keys gate** — `render-to-static-markup` strips `on*` event-handler props and function-valued props at attribute-emit time, matching react-dom/server. The `cached-prop-name` path returns reserved keys (`__proto__`, `constructor`, `prototype`) verbatim without caching, and `kv-conv` drops those keys before they `aset` on the props object passed to `React.createElement`. Per rf2-dwds9.

### CRLF injection at HTTP-response boundaries

SSR response-shape fx produce header strings on the wire. A CRLF (`\r\n`) embedded in a header value would split the header into adjacent header lines, allowing a response-splitting attack (injection of a new header or even a second response body). The fail-fast policy is "reject at fx-handler time, no strip-and-warn" — silent normalisation would mask bugs and let through downstream-encoded attacks.

- **`:rf.server/set-header` / `:rf.server/append-header` fail-fast** — header values containing `\r` or `\n` throw the fx with `:rf.error/header-invalid-value` and the rejecting fx-id in `:tags`. No strip-and-warn semantic; the caller's bug surfaces immediately. Per rf2-hbty2 and [011 §Standard fx](011-SSR.md#standard-fx).
- **`:rf.server/redirect` Location-header fail-fast** — the `Location:` header is a header value subject to the same CRLF check, plus a structural URL-shape check; a CRLF in the redirect target surfaces `:rf.error/redirect-invalid-location`. Per rf2-hbty2.
- **`:rf.server/set-cookie` per-attribute CRLF check** — `Set-Cookie`'s attribute fields (`:domain`, `:path`, `:name`, `:value`, `:max-age`, `:same-site`) are individually checked for CRLF before the host adapter serialises the cookie line. Apps build cookies as Clojure maps; the per-attribute check protects against attacker-supplied attribute values flowing into a user-id field, a domain string, or a path that re-enters the header line as CRLF-bearing payload. Per rf2-rpedl and [011 §Cookies](011-SSR.md).

### Privacy / secret handling

Sensitive values — credentials, session tokens, PII, partner secrets — flow through the framework on three paths: the trace surface (`:rf.error/*` events carry failing values verbatim by default), direct reads against `app-db` from MCP tools (`get-app-db`, `get-path`), and the always-on error-emit / event-emit substrates that forward to hosted observability back-ends. Each path must default-redact and offer explicit opt-in. The `:sensitive?` declaration is the single normative privacy marker; the canonical emission site for the `:rf/redacted` sentinel is the `rf/elide-wire-value` walker ([API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)).

- **`:sensitive?` enforcement on the always-on error path** — the always-on error-emit substrate (per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)) survives `:advanced` + `goog.DEBUG=false`. The substrate honours `:sensitive?` from registration-meta and from per-slot Malli props before fanning out — sensitive failing values land at hosted error monitors as `:rf/redacted`, not as verbatim secrets. Per rf2-vnjfg.
- **Schema-validation-failure redaction** — when a `:rf.error/schema-validation-failure` carries a failing slot whose Malli props declare `:sensitive? true`, the emit-site replaces `:value`, `:received`, `:explain`, and `:fx-args` with the `:rf/redacted` sentinel before the trace event ships. Per [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) (rf2-kj51z).
- **Recorder redact-but-record** — story / pair2 recorders honour `:sensitive?` by *dropping the `:event` payload (or marking `:rf/redacted`)* rather than refusing to record. The slot survives so the dev can correlate the cascade; the payload is scrubbed. Per rf2-hdadz.
- **`with-redacted` interceptor + handler-meta + per-slot meta** — the three composition sites for `:sensitive?`: (a) the positional `with-redacted` interceptor scrubs named payload keys *before* the handler body runs (the handler sees the unredacted value via the regular `:event` coeffect; the trace surface sees the redacted version); (b) registration-meta `:sensitive? true` stamps every trace event in the handler's scope; (c) per-slot Malli `:sensitive? true` props redact at the validation emit-site. Per [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) and [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces).
- **Direct-read tools MUST run `rf/elide-wire-value`** — MCP-server direct reads (`get-app-db`, `get-app-db-diff`, `get-machine-state`, `get-path`, `sub-cache`) bypass the trace surface — they read live runtime values directly. The framework requires those tools route the returned value through `rf/elide-wire-value` at the wire-egress boundary, with `:include-sensitive?` defaulting to `false`. With-redacted shapes trace `:db-before` / `:db-after` slots; it does not protect a live-value read. Per [Tool-Pair §Direct-read privacy](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) (rf2-czv3p / rf2-b2hip).
- **`include-sensitive?` vs `show-sensitive?` verb split** — knobs that govern *wire egress out of the process* use the `include-sensitive?` verb; knobs that govern *on-box devtools UI visibility* use `show-sensitive?`. Both default to suppress. Per [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress).
- **`:dropped-sensitive` indicator on MCP returns** — every MCP tool response that walked a tree-typed payload carries an integer `:dropped-sensitive` count when the walker dropped at least one leaf. The agent sees "the payload was filtered" without re-inferring from absence. Per [Conventions §Reserved indicator slots](Conventions.md#reserved-indicator-slots-mcp-shaped-returns).

### DoS by input

Attacker-controllable inputs that the framework will process unconditionally — JSON bodies, HTTP request reads, dispatch cascades — must have bounded resource consumption. "Bounded" means an explicit upper limit (size, time, depth) the framework enforces, with a structured failure category on overflow.

- **JSON keyword-interning cap (DoS variant)** — the same cap that defends against the integrity threat above ([§Input validation](#input-validation--boundary-parsing)) also defends against the DoS threat: a compromised partner submitting N-unique-key JSON-per-response would permanently burn N keyword slots per response on the JVM, exhausting keyword storage. Cap default 10000. Per rf2-wu1n5 and [014 §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5).
- **`:timeout-ms` 30000 default** — managed-HTTP requests apply a 30-second per-attempt wall-clock timeout when `:timeout-ms` is absent. A slow-loris upstream that never finishes the body would otherwise pin a `CompletableFuture` (JVM) / fetch promise (CLJS) indefinitely; in a long-running JVM the in-flight registry fills until the connection pool is exhausted. Two explicit opt-outs (`:timeout-ms nil` / `:timeout-ms 0`) carry deliberate caller intent. Per rf2-it1cd and [014 §`:timeout-ms` security defaults](014-HTTPRequests.md#timeout-ms-security-defaults-rf2-it1cd).
- **Drain-depth-exceeded atomic rollback** — the run-to-completion drain has a depth limit; on overflow the runtime emits `:rf.error/drain-depth-exceeded` with `:tags {:depth :queue-size :last-event}`, halts the cascade atomically (no partial app-db commit), and surfaces the failure to `:on-error`. A handler that recursively dispatches itself cannot DoS the dispatch loop; the failure surfaces as a bounded error rather than a stack overflow. Per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) `:rf.error/drain-depth-exceeded`.

### MCP tool authority and isolation

The MCP triplet (pair2-mcp, story-mcp, causa-mcp) ships tools that read and mutate live runtime state. Two qualitatively-different authority classes: **named mutations** (`dispatch`, `restore-epoch`, `reset-frame-db`, `get-app-db`, `get-path`) — known, addressable, framework-shaped operations the user invoked the tool to perform; and **arbitrary code execution** (`eval-cljs`) — the operator can run anything the host process can. The two classes get different gates.

- **Named-mutation tools — no extra approval gate** — invoking a causa-mcp / pair2-mcp tool is itself the explicit consent. Dispatching an event through `dispatch`, rewinding via `restore-epoch`, resetting via `reset-frame-db` does not require a per-call confirmation prompt. The programmer must be able to use these tools with low friction; an approval gate per mutation would make the tools unusable as a debugging surface. Per rf2-czv3p.
- **`eval-cljs` gated by launch flag, default OFF** — `eval-cljs` is qualitatively different — arbitrary code execution. The published causa-mcp and pair2-mcp ship with `eval-cljs` DISABLED. The operator opts in *at server launch* via an explicit `--allow-eval` (or similar) flag; the default-OFF posture means a stock install cannot execute arbitrary code over MCP. Per rf2-czv3p / rf2-zyoj2 / rf2-cxx5s.
- **MCP servers default to localhost-bind** — the published MCP servers bind to `127.0.0.1` by default; remote access is an explicit launch-flag opt-in. Per rf2-hpkkx.
- **Wire payloads run through `rf/elide-wire-value`** — every tool response that walks tree-typed payloads runs `rf/elide-wire-value` with off-box defaults (`:include-sensitive? false`, `:include-large? false`) before egress. Per [Tool-Pair §Direct-read privacy](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) and [Conventions §Reserved indicator slots](Conventions.md#reserved-indicator-slots-mcp-shaped-returns).

### Editor URI scheme allowlist

Pair-tool source-map surfaces produce clickable links in dev tooling — IDE protocol URIs that open a file at a line. Custom editor templates (`{:custom "vim://%s:%d"}` and similar) let devs route to JetBrains, Sublime, Emacs, org-tooling, etc. An attacker-controllable scheme — `javascript:`, `data:`, `vbscript:` — that landed in the editor template would be clicked, opening an attack surface in the dev's browser. The minimum gate is a scheme-rejection list, not an allowlist; everything other than the three known-bad schemes passes through.

- **Reject `javascript:` / `data:` / `vbscript:` schemes** — the editor-template surface rejects URIs whose scheme matches the three known-bad schemes. Everything else (`vim:`, `idea:`, `subl:`, `org:`, `vscode:`, `cursor:`, …) passes, no dev burden. Per rf2-vwcsq.

### File-path boundaries

Implementation tooling that writes to the filesystem (test fixtures, scaffolding, conformance-corpus emit sites) accepts env-var overrides for path roots. A misconfigured env var pointing at the home directory or `/` would let a tool's "rm temporary scratch" step delete the wrong tree. The defense is a path-policy check that constrains every writing tool to `implementation/` and `examples/`; an escape attempt fails fast with a clear error.

- **Path-policy env var constrained to `implementation/` + `examples/`** — every writing tool that accepts an env-var path override checks the resolved path against the allowed prefix list; an escape (`../`, an absolute path outside the allowed roots, a symlink that resolves out) surfaces a clear error. Documented as a CI-internal knob, not a stable public interface. Per rf2-21rfv.

### Production gates

The trace surface and dev-only instrumentation must elide in production builds — both for performance (no trace allocation in the hot path) and for security (dev-side enrichment slots — `:dispatch-id`, source-coord, retain-N ring buffer — would otherwise leak to production-bound consumers). The framework owns two parallel gates: `goog.DEBUG` on CLJS (Closure constant-folds the gated branch in `:advanced`) and `re-frame.debug` / `RE_FRAME_DEBUG` on the JVM (read once at ns-load).

- **CLJS production-elision contract** — every dev-only emit site sits inside `(when ^boolean re-frame.interop/debug-enabled? ...)`. With `:closure-defines {goog.DEBUG false}`, Closure DCEs the dependent allocation: trace maps, listener iteration, malli calls, error reason strings, the Performance API bridge. Verified by the production-elision conformance gate. Per [009 §Production builds](009-Instrumentation.md#the-mechanism-re-frameinteropdebug-enabled-alias-of-googdebug).
- **JVM production gate (SSR posture)** — the JVM-side `re-frame.interop/debug-enabled?` reads `-Dre-frame.debug=false` / `RE_FRAME_DEBUG=false` once at ns-load. SSR / webhook receivers / long-running JVMs that face untrusted input set the flag explicitly, eliminating the dev-side enrichment surface in production. Per [009 §JVM builds](009-Instrumentation.md#jvm-builds) and rf2-0la4f / rf2-vnjfg.
- **Always-on substrate is the production-survivable surface** — three always-on substrates survive `:advanced` + `goog.DEBUG=false`: the per-frame `:on-error` slot, the event-emit listener surface, the error-emit listener surface (per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)). These are the *only* paths registered listeners may rely on at production runtime; the trace surface is dev-only by construction. Listener registration sites SHOULD use `^boolean re-frame.interop/debug-enabled?` as a belt-and-braces gate alongside the user's explicit config flag.

## Catalogue references

This section pins the cross-references into [Conventions.md](Conventions.md) and Spec 009 — the **catalogue** of security-relevant reserved namespaces, error categories, warning categories, and meta keys lives in those docs; this doc names the security relevance and points there.

### Security-relevant `:rf.error/*` rows (catalogue: [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue))

| Error keyword | Owning spec | Security relevance |
|---|---|---|
| `:rf.error/header-invalid-value` | [011](011-SSR.md) | CRLF injection in `:rf.server/set-header` / `:rf.server/append-header` |
| `:rf.error/redirect-invalid-location` | [011](011-SSR.md) | CRLF / malformed-URL injection in `:rf.server/redirect` |
| `:rf.error/schema-validation-failure` | [010](010-Schemas.md) | Boundary input validation; sensitive-slot redaction at emit-site |
| `:rf.error/drain-depth-exceeded` | [009](009-Instrumentation.md) | Dispatch-depth DoS via runaway recursion |
| `:rf.error/handler-exception` | [009](009-Instrumentation.md) | Production-survivable error path; `:sensitive?` redaction at the always-on substrate |
| `:rf.error/no-such-handler` | [009](009-Instrumentation.md) | Surfaces tampered dispatch / route / frame lookups |
| `:rf.error/malformed-json` | [014](014-HTTPRequests.md) | Hostile JSON input — truncated / invalid unicode escapes, structurally invalid bodies |
| `:rf.http/decode-failure` | [014](014-HTTPRequests.md) | Closes the keyword-interning-cap overflow path (`:reason :too-many-keys`) and the decode failure surface |
| `:rf.http/cors` | [014](014-HTTPRequests.md) | Cross-origin rejection classified (Option a — see decisions log); retry policy decides per category |
| `:rf.http/timeout` | [014](014-HTTPRequests.md) | Slow-loris defense — the per-attempt 30s default surfaces as this category |

### Security-relevant `:rf.warning/*` rows (catalogue: [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue))

| Warning keyword | Owning spec | Security relevance |
|---|---|---|
| `:rf.warning/sensitive-without-redaction` | [009](009-Instrumentation.md) | Registration declares `:sensitive? true` but the interceptor chain has no `with-redacted` — emit-time advisory |
| `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | [009](009-Instrumentation.md) | An async-callback dispatch landed on `:rf/default` because frame-context binding did not survive — surface for tampered async resolution |

### Security-relevant reserved namespaces (catalogue: [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned))

| Namespace | Owning spec | Security relevance |
|---|---|---|
| `:rf.error/*` | [009](009-Instrumentation.md) | Structured error vocabulary; every security-relevant error rides here |
| `:rf.warning/*` | [009](009-Instrumentation.md) | Structured warning vocabulary |
| `:rf.http/*` | [014](014-HTTPRequests.md) | Failure-taxonomy keys, decode-keys cap, classification categories |
| `:rf.server/*` | [011](011-SSR.md) | Response-shape fx (the CRLF-check site) |
| `:rf.size/*` | [009](009-Instrumentation.md) | Size-elision wire markers, policy keys (`:include-sensitive?`, `:include-large?`) |
| `:rf/elision`, `:rf.elision/*` | [009](009-Instrumentation.md) | App-db elision registry (declared-sensitive, declared-large), wire-egress sentinel-handle |
| `:rf/redacted` | [009](009-Instrumentation.md) | The single sentinel keyword for sensitive-value substitution; reserved at the keyword level |

### Security-relevant reserved config slots (catalogue: [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids) and surface tables)

| Slot | Owning spec | Default | Security relevance |
|---|---|---|---|
| `:rf.http/max-decoded-keys` | [014](014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5) | 10000 | Per-request keyword-interning cap (rf2-wu1n5) |
| `:timeout-ms` (managed-HTTP) | [014](014-HTTPRequests.md#timeout-ms-security-defaults-rf2-it1cd) | 30000 ms | Slow-loris defense; `nil` / `0` deliberately opt out |
| `:rf.size/threshold-bytes` | [API §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) | 16384 | Wire-elision size cap |
| `:rf.size/include-sensitive?` | [API §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) | `false` | Off-box wire-egress sensitivity opt-in |
| `:rf.size/include-large?` | [API §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) | `false` | Off-box wire-egress oversize opt-in |
| `re-frame.debug` / `RE_FRAME_DEBUG` | [009 §JVM builds](009-Instrumentation.md#jvm-builds) | unset (gate true) | JVM-side dev-flag override; set `false` in SSR production |
| `goog.DEBUG` | [009 §The mechanism](009-Instrumentation.md#the-mechanism-re-frameinteropdebug-enabled-alias-of-googdebug) | `true` in dev, `false` in `:advanced` | CLJS dead-code-elimination gate for the trace surface |

### Security-relevant meta keys

| Meta key | Position | Owning spec | Security relevance |
|---|---|---|---|
| `:sensitive?` | registration-meta (`reg-event-*`, `reg-sub`, `reg-cofx`) | [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) | Stamps every trace event in the handler's scope; substrate-level enforcement on always-on paths |
| `:sensitive?` | per-slot Malli props | [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) | Schema-driven privacy declaration; populates `[:rf/elision :sensitive-declarations]` at boot |
| `:large?` | registration-meta + per-slot Malli props | [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) | Size-elision nomination; composes with `:sensitive?` (sensitive drop wins) |
| `:rf.trace/no-emit?` | registration-meta | [009 §Trace-emission opt-out](009-Instrumentation.md#trace-emission-opt-out-rftraceno-emit-event-meta) | Handler-scoped trace suppression; honoured by the always-on event-emit substrate |

## Pragmatic stance

The working principle that governs every call in the decisions log below:

> **Trust the explicit invoker; gate accidents not theoretical attacks; programmer ergonomics matter; secure by default but not at the cost of friction.**

This stance is normative for re-frame2 security decisions and applies whenever a security call has a friction cost. Four propositions:

1. **Trust the explicit invoker.** A programmer who launches `causa-mcp` and calls `dispatch` is the principal; the act of invoking the tool is the consent. Inserting an "are you sure?" gate per mutation does not improve security — the attacker model where someone *else* invokes the tool is the wrong threat model for a debugging surface bound to localhost. The right threat model is "the programmer made an accident"; gate accidents not theoretical attacks. (rf2-czv3p)
2. **Gate accidents, not theoretical attacks.** Concrete examples: the editor URI allowlist rejects `javascript:` / `data:` / `vbscript:` because those *are* known XSS vectors; it does not gate `vim:` / `idea:` / `subl:` because gating a long-tail of editor URIs would force a list-maintenance burden onto every dev workflow with no actual attack surface mitigated. The path-policy env-var check constrains writes to `implementation/` + `examples/` because the accident-mode is "env var unset, tool writes to `$HOME`"; the check is a safety net, not a security boundary against a hostile attacker. (rf2-vwcsq, rf2-21rfv)
3. **Programmer ergonomics matter.** Friction is a tax with a real cost — devs route around painful gates, sometimes by disabling them, often by working around them. A gate that ships disabled because it is friction-heavy provides *no* security; a gate that is low-friction and *enabled* by default is worth N theoretically-stricter gates that ship disabled. The `eval-cljs` launch-flag-opt-in is friction (you must add `--allow-eval`), but it is one-time, transparent, and a documented opt-in for a qualitatively-different authority class. The MCP-named-mutation tools shipping ungated is a friction call — frequent use, low harm per call, fast feedback if something goes wrong. (rf2-czv3p, rf2-zyoj2, rf2-cxx5s)
4. **Secure by default but not at the cost of friction.** The defaults the framework ships matter — `:include-sensitive?` defaults `false`, `:timeout-ms` defaults to 30 seconds, `:rf.http/max-decoded-keys` defaults to 10000, `eval-cljs` ships disabled, `goog.DEBUG=false` is the production default. Each default trades a small friction (the conscious opt-out, the bumped cap) for a real-world default-safe posture. Where a default is friction-free *and* default-safe, ship it (the SSR `app-db` side-channel atom for response state — invisible to apps, makes the privacy boundary self-enforcing per rf2-jbcmt). Where a default would impose burden without proportionate risk reduction, document and move on (third-party egress in story tooling per rf2-su313, nested npm install per rf2-o0tpo).

This stance was codified across nine policy-review beads in May 2026 (rf2-cktdt, rf2-80grk, rf2-s6k4i, rf2-hpkkx, rf2-hdadz, rf2-su313, rf2-o0tpo, rf2-vwcsq, rf2-21rfv). Each landed a concrete call against the four propositions; together they established the framework's settled posture, and new security calls are graded against them.

The stance composes with [Principles §Regularity over cleverness](Principles.md#regularity-over-cleverness) and [Principles §Public query surfaces](Principles.md#public-query-surfaces). Regularity says "one obvious way to do a thing" — the canonical wire-elision walker is `rf/elide-wire-value`; the canonical privacy marker is `:sensitive?`; the canonical error vocabulary is `:rf.error/*`. Public query surfaces says "an agent can ask the runtime, without private-API spelunking" — the `:dropped-sensitive` / `:elided-large` indicator slots make filter-events programmatically observable rather than implicit. Security is the *application* of the principles to attack surfaces; it does not invent new disciplines.

## Decisions log

Every concrete security call recorded as a bead, with one-line rationale. Ordered roughly by category. The bead's `bd show <id>` carries the full context; this log is the audit trail of "this is the call we made, here is the why."

### Input validation / DoS

| Bead | Call | Rationale |
|---|---|---|
| rf2-wu1n5 | JSON keyword-interning cap = `:rf.http/max-decoded-keys` (default 10000) | Compromised upstream returning N-unique-key JSON per response would permanently burn N keyword slots; long-running JVMs (SSR, webhooks) are the worst case. Cap is the second line of defence — first line is `:decode :text` for endpoints that don't need keywordization. |
| rf2-263km | Pure-Clojure JSON reader bounds-check on `\uXXXX` escapes | Truncated / invalid hex escapes surface `:rf.error/malformed-json` with structured `:reason` instead of an opaque NPE. Hand-rolled reader's contract; matters for any port that ships one. |
| rf2-dgsu1 | Cheshire mandatory; hand-rolled JSON fallback deleted (~165 LoC) | Removing the fallback eliminates a parser the framework owns and would have to keep hardened. The bounds-check / cap contracts moved to Cheshire-only paths. |
| rf2-it1cd | `:timeout-ms` 30000 default; `nil` / `0` opt out | Slow-loris defense against partner / webhook / agent-controlled fetches. Two opt-out values are explicit caller intent (not idiomatic); the call-site author signals "I genuinely need unbounded." |
| rf2-r40km | CORS classification implemented (Option a) | Spec-vs-impl drift fix: the `:rf.http/cors` category was specced but never emitted. Heuristic emission on CLJS landed (TypeError + cross-origin URL); 3 classifier tests + retry-set membership test added. |

### XSS at output boundaries

| Bead | Call | Rationale |
|---|---|---|
| rf2-m5u23 | JSON-LD `<script>` body: escape `<` as `<` | Standard XSS posture for inline `application/ld+json`. Attacker-supplied substring cannot close the script context. |
| rf2-vl8ir | Hiccup attribute *key* escape (not just value) | Attribute keys are attacker-reachable through registered hiccup forms receiving keyed data; escape prevents breakout from the attribute namespace. |
| rf2-dwds9 | Reagent-slim strips `on*` and fn-valued props at emit-attr; reserved-prop-keys dropped before `aset` | Matches react-dom/server. Closes both the event-handler-injection vector and the `__proto__` / `constructor` / `prototype` prototype-pollution path on the client. |

### CRLF injection

| Bead | Call | Rationale |
|---|---|---|
| rf2-hbty2 | Headers / redirects fail-fast on CRLF | No strip-and-warn — silent normalisation masks bugs and lets through downstream-encoded attacks. `:rf.error/header-invalid-value` / `:rf.error/redirect-invalid-location` surface immediately. |
| rf2-rpedl | `Set-Cookie` per-attribute CRLF check | Attacker-supplied attribute values (user-id flowing into `:name`, partner-supplied `:domain`) get the same fail-fast treatment as the top-level header value. |

### Privacy / secret handling

| Bead | Call | Rationale |
|---|---|---|
| rf2-vnjfg | `:sensitive?` enforcement on always-on error path (not warning-only) | Always-on error-emit substrate survives `:advanced` + `goog.DEBUG=false`; a sensitive failing value would land at hosted error monitors as a verbatim secret without substrate-level enforcement. |
| rf2-kj51z | Schema-validation-failure redacts `:value` / `:received` / `:explain` / `:fx-args` when slot is `:sensitive?` | Malli's standard behaviour carries the failing value verbatim under `:value` / `:errors[].value`. Without redaction the trace event re-leaks the secret to every registered listener. |
| rf2-hdadz | Recorder redacts payload but records the slot | Drop-the-payload semantics, not refuse-to-record — devs lose useful correlation otherwise. Matches the always-on error-emit substrate's posture. |
| rf2-czv3p | Direct-read tools MUST run `rf/elide-wire-value`; named mutations need no extra gate | Direct reads (`get-app-db`, `get-path`) bypass the trace surface where `with-redacted` and `:sensitive?` stamping operate. The MCP egress is the right boundary for live-value redaction. Named mutations get no extra gate — invoking the tool is the consent. |
| rf2-b2hip | spec/004-Wire-Pipeline.md aligned to spec/Tool-Pair MUST on direct-read privacy | Spec-vs-spec drift resolution: `with-redacted` shapes trace `:db-before` / `:db-after`; it does NOT protect a live-value direct read. Tool-Pair MUST wins. |
| rf2-isdwf | `:sensitive?` hoisted from `:tags` to trace-event top-level | Consumers route on top-level `:sensitive?` rather than `(get-in trace-event [:tags :sensitive?])` — flatter access path, cheaper conformance gate. |
| rf2-iwqu9 | `re-frame.privacy/sensitive?-from-meta` for non-trace consumers | Same boolean as the trace surface uses, exposed for non-trace consumers (the always-on substrates). One reader, two surfaces. |

### MCP tool authority

| Bead | Call | Rationale |
|---|---|---|
| rf2-czv3p (part 1) | Named-mutation tools ungated; `eval-cljs` separate authority class | Programmer-friction matters; named mutations are the debugging primitive. `eval-cljs` is qualitatively different — arbitrary code execution. |
| rf2-zyoj2 | causa-mcp `eval-cljs` ships disabled; `--allow-eval` (or similar) launch-flag opt-in | Published servers default-safe. One-time per server launch, transparent, documented. |
| rf2-cxx5s | pair2-mcp `eval-cljs` gets the same treatment | Same posture cascades to the sibling server; same rationale. |
| rf2-hpkkx | MCP servers default localhost-bind | Remote access is explicit opt-in; rules out the casual cross-network reach. |
| rf2-3rt1f | Per-session app-db cache keyed on root hash | Cache invalidation is keyed on the actual app-db identity — cache poisoning by mismatched session is structurally impossible. |

### Editor URI allowlist + file-path boundaries

| Bead | Call | Rationale |
|---|---|---|
| rf2-vwcsq | Reject `javascript:` / `data:` / `vbscript:` schemes; everything else passes | Minimum gate against known XSS vectors; no dev burden for the long-tail of legitimate IDE schemes. |
| rf2-21rfv | Env-var path-policy constrains writes to `implementation/` + `examples/` | Safety net against env-var-unset accidents (`rm -rf $UNSET_VAR/...`). Documented as a CI-internal knob, not a stable public interface. |

### Production gates

| Bead | Call | Rationale |
|---|---|---|
| rf2-0la4f | JVM `re-frame.debug` / `RE_FRAME_DEBUG` env/property gate | SSR / long-running JVM posture: explicit dev-flag opt-out, read once at ns-load. Eliminates the dev-side trace surface in production. |
| rf2-hqbeh | Always-on error-emit substrate (not gated by `debug-enabled?`) | The handler-exception path is the primary production-monitoring case; gating it on `debug-enabled?` would eliminate the production observability surface. Dev-side enrichments (`:dispatch-id`, source-coord) elide with the rest of the trace surface. |
| rf2-rirbq | Always-on event-emit substrate | Sibling to the error-emit substrate; per-event records for hosted observability (Datadog, Honeycomb, Sentry). |
| rf2-bacs4 | Always-on error-emit listener surface | Corpus-wide fan-out path parallel to per-frame `:on-error`. Mutually isolated from the per-frame policy fn. |
| rf2-jbcmt | SSR response accumulator moved to side-channel atom (not in `app-db`) | Hydration payload defaults to shipping the whole app-db. Response state (auth cookies, internal `X-*` headers) in app-db default-leaks; side-channel atom makes the privacy boundary self-enforcing. |

### Pragmatic stance (the nine policy beads)

| Bead | Call | Rationale |
|---|---|---|
| rf2-cktdt | Migration skill warn-before-mass-rewrite gate | Accident protection — mass rewrite is high-risk; the gate is one warning, not a per-file confirmation. |
| rf2-80grk | Retrospective skill: GH-issue routing + shell-safety here-doc pattern | Pattern, not a hard gate. Documents the safe shell idiom so future skill authors copy from a vetted example. |
| rf2-s6k4i | Implementor cross-repo announce gate + GH-issue routing | Per-repo announce on cross-cutting changes; mirrors the migration-skill posture for implementor-side changes. |
| rf2-hpkkx | Published-skill baseline allowed-tools policy + nREPL localhost note | Default-safe published skills; nREPL is documented localhost-only. |
| rf2-hdadz | Recorder redact-but-record on `:sensitive?` | Pragmatic privacy: scrub the payload, keep the correlation slot. |
| rf2-su313 | Keep third-party egress in story tooling (QR via api.qrserver.com, axe-core via CDN); document the egress | Bundling axe-core balloons the story bundle for the a11y minority; QR is explicitly user-triggered. Dev-tool conveniences with documented egress, not a security gate worth its friction. |
| rf2-o0tpo | Nested npm install during test runs is fine; skip the bootstrap-script restructure | Nested-npm install is how nested npm projects work; not a security concern in a dev tool. |
| rf2-vwcsq | Reject only the three known-bad URI schemes in editor templates | Minimum gate, maximum dev compatibility. |
| rf2-21rfv | Env-var path-policy check constrains to `implementation/` + `examples/` | Accident-mode defense, not adversary defense. |

### Tooling and infrastructure

| Bead | Call | Rationale |
|---|---|---|
| rf2-rrnnf | Wire-vocab Malli + grep conformance gate (`tools/mcp-conformance/wire-vocab/`) | Cross-MCP namespace pin: every server emits byte-identical `:rf.mcp/*` / `:rf.size/*` markers. Drift detector. |
| rf2-tygdv | `:rf.mcp/summary` lazy-summary slot | Wire-vocabulary pin so the agent sees the summary boundary the same way across servers. |
| rf2-obpa9 | `:rf.mcp/dedup-table` + `:rf.mcp/ref` structural dedup | Reserved cross-server so the agent pattern-matches the dedup shape uniformly. |
| rf2-c1l4d | Schema walker populates `[:rf/elision :sensitive-declarations]` at boot | Boot-time additive population so the schema-validation emit-site can consult the registry without re-walking. |
| rf2-edfhh | This doc — top-level Security.md catalogue + threat model + decisions log | Same shape pattern as Conventions.md + Principles.md: top-level coordination doc that points at the detail without duplicating it. |

## Cross-references

- [Principles.md](Principles.md) — the discipline principles this doc applies to attack surfaces.
- [Conventions.md](Conventions.md) — reserved namespaces, fx-ids, app-db keys, and meta keys cited throughout.
- [Ownership.md](Ownership.md) — the contract-surface → owning-spec table; this doc cites owners, Ownership pins them.
- [009-Instrumentation.md §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the canonical privacy posture and the `:sensitive?` mechanism.
- [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — the full `:rf.error/*` and `:rf.warning/*` row catalogue.
- [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) — the three always-on substrates that survive `:advanced` + `goog.DEBUG=false`.
- [010-Schemas.md §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) — schema-driven privacy declaration.
- [011-SSR.md §Standard fx](011-SSR.md#standard-fx) — CRLF-check site (`:rf.server/set-header` / `:rf.server/redirect` / `:rf.server/set-cookie`).
- [014-HTTPRequests.md §Keyword-interning cap](014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5) and [§`:timeout-ms` security defaults](014-HTTPRequests.md#timeout-ms-security-defaults-rf2-it1cd) — managed-HTTP input-validation and DoS defaults.
- [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) — the wire-elision walker, single normative emission site for `:rf/redacted` and `:rf.size/large-elided`.
- [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) — public surfaces (`:sensitive?` registration meta, `with-redacted`).
- [Tool-Pair.md §Direct-read privacy](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — the MCP-server direct-read wire-egress contract.
