# Security — CLJS reference implementation specifics

> **Type:** Reference (implementation-side)
> The CLJS reference's concrete realisations of the pattern-level security posture in [`../spec/Security.md`](../spec/Security.md). Named functions, numeric defaults, JVM-vs-CLJS stub semantics, specific fail-fast event ids, and the full bead audit trail. Other-language ports use [`../spec/Security.md`](../spec/Security.md) as the contract; this doc is the CLJS reference's binding of that contract to Clojure names and values.

## How to read this doc

[`../spec/Security.md`](../spec/Security.md) is the language-agnostic security pattern — threat model, behavioural MUSTs, the pragmatic stance. **This doc names *what the CLJS reference actually ships*** for each pattern obligation: the function `re-frame.core/elide-wire-value`, the integer literal `10000`, the namespace `re-frame.interop`, the exact wire keyword `:rf.error/header-invalid-value`.

A TypeScript / Fable / Squint port reads [`../spec/Security.md`](../spec/Security.md) and re-binds these names to its host (`elideWireValue`, `RF_HTTP_MAX_DECODED_KEYS`, …). This doc tells a CLJS reader (or a v1 conformance gate) the concrete names and numbers that gate "did the reference actually ship the obligation?"

Four sections:

1. **[Named functions and namespaces](#named-functions-and-namespaces)** — the CLJS-side names for every behavioural primitive Security.md names abstractly.
2. **[Numeric defaults](#numeric-defaults)** — every default the reference ships, with its config slot and its overflow surface.
3. **[JVM-vs-CLJS stub semantics](#jvm-vs-cljs-stub-semantics)** — how the per-platform `re-frame.interop` seam realises the production-elision and dev-flag contracts.
4. **[Fail-fast event ids](#fail-fast-event-ids)** — the exact `:rf.error/*` keywords each safety check surfaces (the pattern-level "structured error category"; the CLJS reference's concrete keyword).
5. **[Decisions log — the 38-bead audit trail](#decisions-log--the-38-bead-audit-trail)** — every concrete CLJS-reference call recorded as a bead with one-line rationale.

## Named functions and namespaces

| Pattern obligation (`../spec/Security.md`) | CLJS reference name | Owning artefact / namespace |
|---|---|---|
| Wire-elision walker — single emission site for `:rf/redacted` and `:rf.size/large-elided` | `re-frame.core/elide-wire-value` | `day8/re-frame2` (core) — `re-frame.elision` |
| Production-elision gate (CLJS) | `re-frame.interop/debug-enabled?` — alias of `goog.DEBUG` | `day8/re-frame2` (core) — `re-frame.interop` |
| Open-redirect-mitigating fx | `:rf.server/safe-redirect` (registered fx) | `day8/re-frame2-ssr` (`re-frame.ssr.fx.safe-redirect`) |
| Header CRLF check site | `:rf.server/set-header` / `:rf.server/append-header` fx handlers | `day8/re-frame2-ssr` (`re-frame.ssr.fx.headers`) |
| Cookie per-attribute CRLF check | `:rf.server/set-cookie` fx handler | `day8/re-frame2-ssr` (`re-frame.ssr.fx.cookies`) |
| Editor-URI scheme reject-list | `re-frame.source-coords.editor-uri/reject-schemes` (`#{"javascript:" "data:" "vbscript:"}`) | `day8/re-frame2` (core) — `re-frame.source-coords.editor-uri` |
| Path-policy check (writing tools) | `re-frame.path-policy/check-write-path` (or per-tool equivalent) | tooling — `tools/*/scripts/` and shared helper |
| JSON keyword-interning cap (Cheshire path, JVM) | `re-frame.http.decode.json/with-keyword-cap` | `day8/re-frame2-http` (`re-frame.http.decode.json`) |
| JSON keyword-interning cap (CLJS reader path) | `re-frame.http.decode.json/decode-with-cap` | `day8/re-frame2-http` (`re-frame.http.decode.json`) |
| Schema walker — populates `[:rf/elision :sensitive-declarations]` at boot | `re-frame.schemas.walker/collect-sensitive-declarations` | `day8/re-frame2-schemas` (core split pending [rf2-p7va](#)) |
| Always-on error-emit substrate (production-survivable) | `re-frame.error-emit/emit-error!` | `day8/re-frame2` (core) — `re-frame.error-emit` |
| Always-on event-emit substrate | `re-frame.event-emit/emit-event!` | `day8/re-frame2` (core) — `re-frame.event-emit` |
| Schema-installed redaction | `{:sensitive? true}` on app-schema slots | `day8/re-frame2` (core) — `re-frame.privacy` |
| Epoch projected-record helper (off-box egress emission site) | `re-frame.epoch/projected-record` (wraps `re-frame.core/elide-wire-value`) | `day8/re-frame2-epoch` — `re-frame.epoch` |
| MCP-tool wire-egress walker (off-box defaults) | `re-frame.mcp-base.elision/walk-for-wire` (wraps `elide-wire-value`) | `day8/re-frame2-mcp-base` ([rf2-vw4sq](#)) |
| Hiccup → HTML attribute-key escape | `re-frame.ssr.hiccup/escape-attr-key` | `day8/re-frame2-ssr` (`re-frame.ssr.hiccup`) |
| JSON-LD `<script>` body `<` escape | `re-frame.ssr.hiccup/escape-script-body` | `day8/re-frame2-ssr` (`re-frame.ssr.hiccup`) |
| Reagent-slim event-handler-prop filter | `re-frame.adapter.reagent-slim/strip-event-props` | `day8/re-frame2-reagent` (slim build path) |
| Reagent-slim reserved-prop-keys gate | `re-frame.adapter.reagent-slim/reserved-prop-keys?` (`#{"__proto__" "constructor" "prototype"}`) | `day8/re-frame2-reagent` (slim build path) |

The public surface (the names a user types into their code) is consolidated in [`../spec/API.md`](../spec/API.md). This table is the contract for *internal CLJS implementation conformance* — a future port would re-bind every row to host-idiomatic names.

## Numeric defaults

Every numeric default the reference ships, with its config slot, its purpose, and the structured failure category that surfaces on overflow / cap / mismatch.

| Slot | Default | Purpose | Overflow surface |
|---|---|---|---|
| `:rf.http/max-decoded-keys` | `10000` | Per-request keyword-interning cap (DoS + integrity threat on JSON decode) | `:rf.http/decode-failure` with `:reason :too-many-keys` |
| `:timeout-ms` (managed-HTTP) | `30000` (30 seconds) | Per-attempt wall-clock timeout; slow-loris defense. `nil` / `0` opt out (deliberate caller intent) | `:rf.http/timeout` |
| Unschema'd large-value warning floor | `16384` (16 KB) | Dev-only advisory floor for large strings lacking `{:large? true}` schema metadata | n/a (warning, not failure) |
| `:rf.size/include-sensitive?` | `false` | Off-box wire-egress sensitivity opt-in (MCP tools, log forwarders) | n/a (filter; `:dropped-sensitive` indicator slot reports filtered count) |
| `:rf.size/include-large?` | `false` | Off-box wire-egress oversize opt-in | n/a (filter; `:elided-large` indicator slot reports filtered count) |
| Drain-depth ceiling (`:rf/drain-depth-limit`) | `1024` cascaded dispatches per drain | Cascading-dispatch DoS defense | `:rf.error/drain-depth-exceeded` with `:tags {:depth :queue-size :last-event}` |
| `:trace-events-keep` (`(rf/configure :epoch-history {:trace-events-keep N})`) | `5` (rf2-mrsck) | Per-frame epoch ring: keep raw `:trace-events` on the most-recent N records; older keep only the cheap `:sub-runs` / `:renders` / `:effects` projections. Bounds dev-session heap growth from accumulated raw cascade traces. | n/a (silent elision past the cap; consumers walk the cheap structured projections) |
| `re-frame.debug` system property (JVM) | unset → gate `true` | JVM-side dev-flag override; SSR / long-running JVMs set `false` | n/a (gate) |
| `RE_FRAME_DEBUG` environment variable (JVM) | unset → gate `true` | Equivalent to `re-frame.debug` for env-var-driven deployments | n/a (gate) |
| `goog.DEBUG` (CLJS) | `true` in dev, `false` in `:advanced` | Closure-constant gate for the trace surface; DCE folds gated branches in production | n/a (gate) |
| `--allow-eval` MCP launch flag (pair2-mcp) | absent → `eval-cljs` DISABLED | One-time opt-in for the arbitrary-code-execution authority class | n/a (gate; tool unavailable until launch-flag set) |
| MCP server bind address | `127.0.0.1` (localhost) | Default-localhost-bind for published MCP servers | n/a (gate; remote access requires explicit launch flag) |

The Conventions doc carries the reserved-config-slot table ([Conventions.md](../spec/Conventions.md)); this table cross-references it with the *concrete numeric* the reference ships.

## JVM-vs-CLJS stub semantics

The reference implementation uses `re-frame.interop` (with separate `.clj` and `.cljs` implementations per [`../spec/000-Vision.md` §C2](../spec/000-Vision.md#c2-cross-platform-jvm-interop-preserved)) to give every host its appropriate realisation of the production-elision gate and the dev-flag posture.

### `re-frame.interop/debug-enabled?` — the dual-host gate

The same name, two implementations:

- **CLJS** (`re-frame/interop.cljs`): aliased to `goog.DEBUG`. In `:advanced` builds, `(when ^boolean re-frame.interop/debug-enabled? ...)` is constant-folded by the Closure compiler — the entire gated branch (allocation, listener iteration, malli call, error-reason string assembly, Performance API bridge) is dead-code-eliminated. The CLJS production-elision conformance gate ([rf2-?? per `npm run test:elision`](../implementation/scripts/test-elision/)) verifies that gated branches do not survive `:advanced` + `goog.DEBUG=false`.
- **JVM** (`re-frame/interop.clj`): a `def` whose value is read once at ns-load from the system property `re-frame.debug` or the env var `RE_FRAME_DEBUG`. Defaults `true` (dev posture). SSR / webhook receivers / long-running JVMs set `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false` explicitly to eliminate the dev-side enrichment surface in production. Unlike CLJS, the JVM gate is a runtime read, not a compile-time constant — the cost is a single boolean check per trace-emit site, which is acceptable for the JVM posture (SSR responses are not the hot loop the CLJS browser path is).

The JVM stub is not a "no-op stub" — it is a real, runtime-honoured gate. The same source code (`(when re-frame.interop/debug-enabled? ...)`) elides via Closure on CLJS and runtime-shortcircuits on JVM.

### Always-on substrates — production-survivable on both hosts

Three substrates survive `:advanced` + `goog.DEBUG=false` on CLJS, and survive `re-frame.debug=false` on JVM:

- **The per-frame `:on-error` policy fn** (per [`../spec/009-Instrumentation.md`](../spec/009-Instrumentation.md)). Always runs; production-monitoring case.
- **The event-emit listener surface** — `re-frame.event-emit/emit-event!` plus the listener registry. Always fans out per-event records to registered observability listeners (Datadog, Honeycomb, Sentry, custom).
- **The error-emit listener surface** — `re-frame.error-emit/emit-error!` plus the listener registry. Corpus-wide fan-out path parallel to per-frame `:on-error`. Mutually isolated from the per-frame policy fn.

Sensitive data marking on these substrates is path-based per the upcoming data-classification mechanism (separate spec doc; in progress). The legacy handler-meta `:sensitive?` annotation has been removed; per-path elision (the per-frame `:rf/elision` registry, populated from app-schema `:sensitive?` slot meta) is the load-bearing privacy surface on these substrates.

### CLJS-only optimisations (not JVM-mirrored)

- **`re-frame-10x` epoch buffer integration** — CLJS-only (the panel itself is a CLJS UI).
- **Chrome Performance Timeline bridge** — CLJS-only (no equivalent on JVM).
- **DOM source annotations** — CLJS-only (no DOM on JVM).
- **Function-valued overrides via direct fn capture** — CLJS-only ergonomic; the portable form is id-valued overrides.

These rows in [`../spec/000-Vision.md` §Host-profile matrix](../spec/000-Vision.md#host-profile-matrix) marked CLJS-only do not need a JVM stub.

## Fail-fast event ids

Every safety check the reference performs surfaces a structured `:rf.error/*` keyword (per [`../spec/Conventions.md`](../spec/Conventions.md) — reserved namespaces). The pattern-level doc names the *behavioural category* ("CRLF in header value must fail fast"); this section names the *exact keyword* the CLJS reference emits.

| Check site | Event id | Carrier slots |
|---|---|---|
| `:rf.server/set-header` / `:rf.server/append-header` — CRLF in value | `:rf.error/header-invalid-value` | `:tags {:fx-id <rejecting-fx-id> :name <header-name>}` |
| `:rf.server/redirect` — CRLF in `:location` (legacy fail-fast) | `:rf.error/redirect-invalid-location` | `:tags {:fx-id :rf.server/redirect}` |
| `:rf.server/safe-redirect` — URL parse failure | `:rf.error/safe-redirect-invalid-url` | `:tags {:fx-id :rf.server/safe-redirect}` |
| `:rf.server/safe-redirect` — bad scheme (`javascript:` / `data:` / `vbscript:`) | `:rf.error/safe-redirect-scheme-rejected` | `:tags {:scheme <rejected-scheme>}` |
| `:rf.server/safe-redirect` — `:relative-only?` violation | `:rf.error/safe-redirect-host-disallowed` | `:tags {:reason :relative-only-violation}` |
| `:rf.server/safe-redirect` — `:allow` allowlist mismatch | `:rf.error/safe-redirect-host-disallowed` | `:tags {:reason :not-in-allowlist :host <host>}` |
| `:rf.server/set-cookie` — CRLF in attribute value | `:rf.error/header-invalid-value` | `:tags {:fx-id :rf.server/set-cookie :attribute <key>}` |
| Schema-validation failure at registered boundary | `:rf.error/schema-validation-failure` | `:value` (redacted to `:rf/redacted` when slot `:sensitive?`), `:received`, `:explain`, `:fx-args` |
| JSON decode — keyword-interning cap exceeded | `:rf.http/decode-failure` | `:reason :too-many-keys`, `:tags {:cap N}` |
| JSON decode — truncated `\uXXXX` escape | `:rf.error/malformed-json` | `:reason :truncated-unicode-escape` |
| JSON decode — invalid `\uXXXX` hex digit | `:rf.error/malformed-json` | `:reason :invalid-unicode-escape` |
| Managed-HTTP per-attempt timeout | `:rf.http/timeout` | (failure-taxonomy category; retry policy decides) |
| Managed-HTTP CORS rejection (CLJS heuristic) | `:rf.http/cors` | (heuristic emission on TypeError + cross-origin URL; per rf2-r40km) |
| Drain depth ceiling exceeded | `:rf.error/drain-depth-exceeded` | `:tags {:depth :queue-size :last-event}`; atomic rollback (no partial app-db commit) |
| Editor-URI scheme rejected (build-time on the source-coord template) | — (helper returns `nil` / falls through to default scheme) | scheme reject is a *predicate*, not a runtime error; surfacing as a fail-loud category would burden every dev's editor config |
| Path-policy escape attempt (writing tool) | tool-specific surfaced error (clear "path outside `implementation/` + `examples/`") | n/a (CI-internal knob; not a stable public interface) |

### Warning surfaces (advisory, not fail-fast)

| Surface | Event id | Emission rule |
|---|---|---|
| Large string reaches wire elision without schema metadata | `:rf.warning/large-value-unschema'd` | One-shot at wire-elision time per (frame-id, path); idempotent |
| Async-callback dispatch landed on `:rf/default` because frame-context binding did not survive | `:rf.warning/dispatch-from-async-callback-fell-through-to-default` | Per dispatch site |

## Decisions log — the 38-bead audit trail

Every concrete CLJS-reference security call recorded as a bead, with one-line rationale. The pattern-level doc carries the *abstract* obligations; this log is the *implementation's* binding of those obligations to specific dates, choices, and bead IDs. Ordered roughly by category.

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
| rf2-m5u23 | JSON-LD `<script>` body: escape `<` as `&lt;` | Standard XSS posture for inline `application/ld+json`. Attacker-supplied substring cannot close the script context. |
| rf2-vl8ir | Hiccup attribute *key* escape (not just value) | Attribute keys are attacker-reachable through registered hiccup forms receiving keyed data; escape prevents breakout from the attribute namespace. |
| rf2-dwds9 | Reagent-slim strips `on*` and fn-valued props at emit-attr; reserved-prop-keys dropped before `aset` | Matches react-dom/server. Closes both the event-handler-injection vector and the `__proto__` / `constructor` / `prototype` prototype-pollution path on the client. |

### CRLF injection

| Bead | Call | Rationale |
|---|---|---|
| rf2-hbty2 | Headers / redirects fail-fast on CRLF | No strip-and-warn — silent normalisation masks bugs and lets through downstream-encoded attacks. `:rf.error/header-invalid-value` / `:rf.error/redirect-invalid-location` surface immediately. |
| rf2-rpedl | `Set-Cookie` per-attribute CRLF check | Attacker-supplied attribute values (user-id flowing into `:name`, partner-supplied `:domain`) get the same fail-fast treatment as the top-level header value. |

### Open-redirect mitigation

| Bead | Call | Rationale |
|---|---|---|
| rf2-zfm8v | `:rf.server/safe-redirect` ships alongside caller-trusted `:rf.server/redirect` | Caller-untrusted strings (`?next=…` query param) get URL parse + scheme reject + `:relative-only?` / `:allow [...]` allowlist gating. Five `:rf.error/safe-redirect-*` categories surface the rejection path. `:rf.server/redirect` keeps the caller-trusted contract for internal use. |

### Privacy / secret handling

| Bead | Call | Rationale |
|---|---|---|
| rf2-hjs2d | Removal of handler-meta `:sensitive?` annotation | Mike 2026-05-17: "We won't be having :sensitive? true on the event handler. That's a bad idea." Sensitive data marking is path-based per the upcoming data-classification mechanism (separate spec doc; in progress) — sensitivity is a property of the data value at a path, not of the handler that touched it. |
| rf2-kj51z | Schema-validation-failure redacts `:value` / `:received` / `:explain` / `:fx-args` when slot is `:sensitive?` | Malli's standard behaviour carries the failing value verbatim under `:value` / `:errors[].value`. Without redaction the trace event re-leaks the secret to every registered listener. |
| rf2-hdadz | Recorder redacts payload but records the slot | Drop-the-payload semantics, not refuse-to-record — devs lose useful correlation otherwise. Matches the always-on error-emit substrate's posture. |
| rf2-czv3p | Direct-read tools MUST run `re-frame.core/elide-wire-value`; named mutations need no extra gate | Direct reads (`get-app-db`, `get-path`) bypass handler-scoped trace stamping. The MCP egress is the right boundary for schema-declared live-value redaction. Named mutations get no extra gate — invoking the tool is the consent. |
| rf2-b2hip | spec/004-Wire-Pipeline.md aligned to spec/Tool-Pair MUST on direct-read privacy | Spec-vs-spec drift resolution: trace redaction does NOT protect a live-value direct read. Tool-Pair MUST wins. |
| rf2-isdwf | `:sensitive?` hoisted from `:tags` to trace-event top-level | Consumers route on top-level `:sensitive?` rather than `(get-in trace-event [:tags :sensitive?])` — flatter access path, cheaper conformance gate. |
| rf2-j1m7x / rf2-mrsck | `re-frame.epoch/projected-record` (`day8/re-frame2-epoch`) — single normative projection helper wrapping `elide-wire-value`; `re-frame.epoch/configure!` `:trace-events-keep` finite retention cap; `:rf.epoch/sensitive?` record-level rollup | Listener fan-out delivers raw records (Causa diff / `restore-epoch` need them); off-box egress (Causa-MCP `watch-epochs`, story / pair recorders, hosted forwarders) routes through `projected-record` at the wire boundary. The `:trace-events-keep` cap bounds dev-session heap growth (the most-recent N records keep raw `:trace-events`; older keep only the cheap structured projections). The `:rf.epoch/sensitive?` rollup mirrors the trace-event `:sensitive?` boolean so consumers branch on one slot per record. |

### MCP tool authority

| Bead | Call | Rationale |
|---|---|---|
| rf2-czv3p (part 1) | Named-mutation tools ungated; `eval-cljs` separate authority class | Programmer-friction matters; named mutations are the debugging primitive. `eval-cljs` is qualitatively different — arbitrary code execution. |
| rf2-cxx5s | pair2-mcp `eval-cljs` ships disabled; `--allow-eval` (or similar) launch-flag opt-in | Published servers default-safe. One-time per server launch, transparent, documented. |
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
| rf2-edfhh | Original top-level `Security.md` catalogue + threat model + decisions log (now split into pattern + impl per rf2-1g6cj / rf2-ao8a2) | Same shape pattern as Conventions.md + Principles.md: top-level coordination doc that points at the detail without duplicating it. |
| rf2-1g6cj | Decision: split Security.md into pattern-level + CLJS-impl (Option A) | Each doc serves one audience cleanly — a TS implementer reads `../spec/Security.md`; a CLJS contributor reads `implementation/SECURITY.md`. Per rf2-0hs5t.3 (a) — external canonical homes are allowed for impl-level concerns. |
| rf2-ao8a2 | This split executed: pattern-level moved to `../spec/Security.md`; CLJS-impl specifics landed here | Keystone bead — unblocks 10-bead rf2-wpo8k Security cross-ref cluster + clears `../spec/Ownership.md` for rf2-exdfg spec-coherence cluster. |

## Cross-references

- [`../spec/Security.md`](../spec/Security.md) — the pattern-level companion: threat model, behavioural MUSTs, pragmatic stance.
- [`../spec/Ownership.md`](../spec/Ownership.md) — the contract-surface → owning-spec table; this doc's row in Ownership pins it as the canonical impl-side security home (per rf2-0hs5t.3 (a)).
- [`../spec/Conventions.md`](../spec/Conventions.md) — reserved namespaces, fx-ids, app-db keys, and meta keys (the cross-cutting vocabulary).
- [`../spec/009-Instrumentation.md`](../spec/009-Instrumentation.md) — trace event model, error catalogue, the always-on substrate definitions.
- [`../spec/010-Schemas.md`](../spec/010-Schemas.md) — schema-driven privacy declaration; boundary-validation seam.
- [`../spec/011-SSR.md`](../spec/011-SSR.md) — response-shape fx (the CRLF-check sites); `:rf.server/safe-redirect`.
- [`../spec/014-HTTPRequests.md`](../spec/014-HTTPRequests.md) — managed-HTTP input-validation and DoS defaults.
- [`../spec/API.md`](../spec/API.md) — public-surface signatures (the names a user types: schema `:sensitive?` / `:large?` metadata, handler `:sensitive?` meta, `elide-wire-value`).
- [`../spec/Tool-Pair.md`](../spec/Tool-Pair.md) — MCP-server direct-read wire-egress contract.
