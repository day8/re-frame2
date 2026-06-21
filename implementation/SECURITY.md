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
| Wire-elision walker — the low-level VALUE primitive, single emission site for `:rf/redacted` and `:rf.size/large-elided` | `re-frame.core/elide-wire-value` | `day8/re-frame2` (core) — `re-frame.elision` |
| Record-level egress projection — THE boundary primitive (the normal call before any off-box sink); resolves a `:rf.egress/*` profile + known `:frame`, applies frame-owned classification, delegates tree slots to `elide-wire-value`, fails closed with no frame | `re-frame.core/project-egress` | `day8/re-frame2` (core) — `re-frame.projection` |
| Production-elision gate (CLJS) | `re-frame.interop/debug-enabled?` — alias of `goog.DEBUG` | `day8/re-frame2` (core) — `re-frame.interop` |
| Open-redirect-mitigating fx | `:rf.server/safe-redirect` (registered fx; handler `re-frame.ssr.response/safe-redirect-fx`) | `day8/re-frame2-ssr` (registered in `re-frame.ssr`; handler in `re-frame.ssr.response`) |
| Header CRLF check site | `:rf.server/set-header` / `:rf.server/append-header` fx (handlers `re-frame.ssr.response/set-header-fx` / `append-header-fx`) | `day8/re-frame2-ssr` (registered in `re-frame.ssr`; handlers in `re-frame.ssr.response`) |
| Cookie per-attribute CRLF check | `:rf.server/set-cookie` fx (handler `re-frame.ssr.response/set-cookie-fx`) | `day8/re-frame2-ssr` (registered in `re-frame.ssr`; handler in `re-frame.ssr.response`) |
| Editor-URI scheme reject-list (denylist-only, rf2-ox357n) | `re-frame.source-coords.editor-uri/forbidden-uri-schemes` (`#{"javascript:" "data:" "vbscript:"}`); gate predicate `forbidden-scheme?` (applied at editor-uri build time AND at each tool's `open!` seam) | `day8/re-frame2` (core) — `re-frame.source-coords.editor-uri` |
| Path-policy check (writing tools) | `enforcePolicy` (Node helper; opt-in via the `OPT_IN_VAR` env var) | tooling — `implementation/scripts/_path-policy.cjs` |
| JSON keyword-interning cap (Cheshire path, JVM) | `re-frame.http.json/json-parse` (`{:max-decoded-keys N}`; default `re-frame.http.json/default-max-decoded-keys`) | `day8/re-frame2-http` (`re-frame.http.json`) |
| JSON keyword-interning cap (CLJS reader path) | `re-frame.http.json/json-parse` (same fn; the `:cljs` branch walks the parsed tree counting unique keys before keywordizing) | `day8/re-frame2-http` (`re-frame.http.json`) |
| Schema walker — extracts the per-slot `{path {:sensitive? true}}` map from a registered schema's EDN form. EP-0015 §8 (rf2-d2r3um): this map does NOT feed the app-db egress registry under `[:rf.runtime/elision :sensitive-declarations]` (app-db egress is frame-owned; the `populate-sensitive-from-schemas!` boot bridge is removed). The map is read **owner-locally** by the machine / resource `:data-schema` redaction bridges, the HTTP body-privacy projector, and story-mcp's tool-egress projector | `re-frame.schemas.walker/extract-sensitive-paths-from-schema` (re-exported as `re-frame.schemas/extract-sensitive-paths-from-schema`) | `day8/re-frame2-schemas` (`re-frame.schemas.walker`) |
| Always-on error-emit substrate (production-survivable) | `re-frame.error-emit/dispatch-on-error!`. The normal production binding is the frame's `:observability` config (a sink receives an **already-projected** record under the frame egress policy). `register-error-listener!` / `unregister-error-listener!` are the low-level registry — an advanced/internal integration API, NOT the hosted-monitoring story; a listener that ships off-box carries the same projection obligation (`project-egress` under `:rf.egress/public-error` / `:rf.egress/off-box-observability`) | `day8/re-frame2` (core) — `re-frame.error-emit` |
| Always-on event-emit substrate | `re-frame.event-emit/dispatch-on-event!`. Same shape as the error path: the production binding is frame-owned `:observability`, the sink receives a projected record; `register-event-listener!` / `unregister-event-listener!` are the advanced/internal registry, carrying the same projection-before-egress obligation | `day8/re-frame2` (core) — `re-frame.event-emit` |
| Schema-installed validation-failure redaction | `{:sensitive? true}` on a schema slot redacts that slot in the schema-validation-**failure** trace (`:value` / `:explain` / scrubbed `:path`). EP-0015 §8 (rf2-d2r3um): app-schema slot props are NO LONGER a route into the durable app-db egress registry — durable app-db classification is frame-owned (`re-frame.privacy` / `re-frame.frame-classification`). This row is the surviving, separate egress product: failure-trace redaction only | `day8/re-frame2-schemas` — `re-frame.schemas` (`redact-validation-tags`) |
| Recordable-coeffect value redaction (EP-0017) | A `reg-cofx` recordable value that fails its `:schema` surfaces `:rf.error/cofx-value-invalid`; when the `:schema` marks any slot `{:sensitive? true}` the value-bearing slots (`:value` / `:explain`) of BOTH the emitted trace AND the thrown `ex-info` ex-data scrub to `:rf/redacted` (fails closed off-box) via the shared `redact-validation-tags` seam. The delivered ambient/recordable `:rf.cofx/value` is projected by the marks chokepoint (`project-cofx-run-tags`) before it surfaces in trace, honouring `reg-cofx` `:sensitive` / `:large` path declarations | `day8/re-frame2` (core) — `re-frame.cofx` (`emit-cofx-value-invalid!`); `re-frame.marks` (`project-cofx-run-tags`) |
| Declared-only cofx delivery + generator EDN-always guard (EP-0017) | `:rf.cofx/requires` delivers EXACTLY the declared facts, flat — nothing implicit. A generator-backed recordable value is dev-mode walked for ordinary-EDN-ness at the write-back site (a minted host handle — DOM node, Promise, fn, atom — throws `:rf.error/cofx-value-invalid` reason `:non-edn-recordable-value` at the source, not far away at replay / SSR / Xray). The recordable grade is the durable-write surface — secrets MUST NOT be carried as recordable coeffects (see the Privacy / secret-handling decisions-log row) | `day8/re-frame2` (core) — `re-frame.cofx` (`deliver-declared-cofx` / `run-generator`); `re-frame.recordable` (`explain-non-recordable`) |
| Epoch projected-record helper (off-box egress emission site) | `re-frame.epoch/projected-record` (uses `re-frame.core/project-egress` under a named off-box profile; `project-egress` delegates tree slots to `re-frame.core/elide-wire-value`) | `day8/re-frame2-epoch` — `re-frame.epoch` |
| Record-level egress projection (the boundary primitive — off-box default) | `re-frame.core/project-egress` (over `re-frame.core/elide-wire-value`) — the required step before any off-box sink. A tool / direct read / sink names a `:rf.egress/*` profile and a known `:frame`; the projector applies the frame-owned classification and delegates every tree-shaped slot to `elide-wire-value`. Fails closed with no frame (Spec 015 §Direct reads). | `day8/re-frame2` (core) — `re-frame.projection` |
| MCP-tool wire-egress INDICATOR walkers (downstream of projection) | `re-frame.mcp-base.elision/count-elided-markers` (counts `:rf.size/large-elided` markers for the `:elided-large` envelope slot) and `re-frame.mcp-base.sensitive/strip-sensitive` (drops `:sensitive?`-stamped trace EVENTS, returning `[kept dropped-count]` for the `:dropped-sensitive` slot). These are envelope-indicator counters over an ALREADY-projected payload — NOT the redaction boundary: `re-frame.core/project-egress` (over `elide-wire-value`) runs at the runtime boundary, under the frame's classification + the tool's `:rf.egress/off-box-tool` profile, before the payload reaches these walkers. | `day8/re-frame2-mcp-base` (`re-frame.mcp-base.elision` / `re-frame.mcp-base.sensitive`) |
| Hiccup → HTML attribute-key gate (HTML5-grammar reject, applied at attribute emit) | `re-frame.ssr.html-helpers/validate-attr-name!` (applied via `re-frame.ssr.html-helpers/attr-string`) | `day8/re-frame2-ssr` (`re-frame.ssr.html-helpers`) |
| JSON-LD `<script>` body `<` escape | `re-frame.ssr.html-helpers/escape-script-body-string` | `day8/re-frame2-ssr` (`re-frame.ssr.html-helpers`) |
| Reagent-slim event-handler-prop filter | `reagent2.dom.server/event-handler-prop?` (applied in `emit-attr`; also drops fn-valued props) | `day8/re-frame2-reagent` slim build — `reagent2.dom.server` |
| Reagent-slim reserved-prop-keys gate | `reagent2.impl.template/reserved-prop-key?` over the set `reserved-prop-keys` (`#{"__proto__" "prototype" "constructor"}`) | `day8/re-frame2-reagent` slim build — `reagent2.impl.template` |

The public surface (the names a user types into their code) is consolidated in [`../spec/API.md`](../spec/API.md). This table is the contract for *internal CLJS implementation conformance* — a future port would re-bind every row to host-idiomatic names.

## Numeric defaults

Every numeric default the reference ships, with its config slot, its purpose, and the structured failure category that surfaces on overflow / cap / mismatch.

| Slot | Default | Purpose | Overflow surface |
|---|---|---|---|
| `:rf.http/max-decoded-keys` | `10000` | Per-request keyword-interning cap (DoS + integrity threat on JSON decode) | `:rf.http/decode-failure` with `:reason :too-many-keys` |
| `:timeout-ms` (managed-HTTP) | `30000` (30 seconds) | Per-attempt wall-clock timeout; slow-loris defense. `nil` / `0` opt out (deliberate caller intent) | `:rf.http/timeout` |
| Unschema'd large-value warning floor | `16384` (16 KB) | Dev-only advisory floor for large strings lacking `{:large? true}` schema metadata | n/a (warning, not failure) |
| `:rf.size/include-sensitive?` | `false` | ADVANCED override beneath the `:rf.egress/*` profile API — the profile a tool / sink passes to `project-egress` resolves this (e.g. `:rf.egress/off-box-tool` floors it `false`; `:rf.egress/local-raw` opts it `true`). An explicit flag overlays the profile floor (the override wins). Direct off-box use is the raw-flags path, not the normal boundary. | n/a (filter; `:dropped-sensitive` indicator slot reports filtered count) |
| `:rf.size/include-large?` | `false` | ADVANCED override beneath the `:rf.egress/*` profile API — same composition as `include-sensitive?` (profile floor + explicit overlay). | n/a (filter; `:elided-large` indicator slot reports filtered count) |
| `:rf.egress/profile` | (no default — required at an off-box boundary) | The named egress boundary a tool / direct read / sink selects (the closed six-member EP-0015 §10 enum: `:rf.egress/off-box-observability` / `:rf.egress/off-box-tool` / `:rf.egress/local-redacted` / `:rf.egress/local-raw` / `:rf.egress/ssr-hydration` / `:rf.egress/public-error`). `project-egress` resolves it to the `:rf.size/*` floor; the boolean flags above overlay it. | `:rf.error/unknown-egress-profile` (the enum is closed — a typo throws, never a silent permissive walk) |
| Drain-depth ceiling (`:rf/drain-depth-limit`) | `1024` cascaded dispatches per drain | Cascading-dispatch DoS defense | `:rf.error/drain-depth-exceeded` with `:tags {:depth :queue-size :last-event}` |
| `:trace-events-keep` (`(rf/configure! {:epoch-history {:trace-events-keep N}})`) | `5` (rf2-mrsck) | Per-frame epoch ring: keep raw `:trace-events` on the most-recent N records; older keep only the cheap `:sub-runs` / `:renders` / `:effects` projections. Bounds dev-session heap growth from accumulated raw cascade traces. | n/a (silent elision past the cap; consumers walk the cheap structured projections) |
| `re-frame.debug` system property (JVM) | unset → gate `true` | JVM-side dev-flag override; SSR / long-running JVMs set `false` | n/a (gate) |
| `RE_FRAME_DEBUG` environment variable (JVM) | unset → gate `true` | Equivalent to `re-frame.debug` for env-var-driven deployments | n/a (gate) |
| `goog.DEBUG` (CLJS) | `true` in dev, `false` in `:advanced` | Closure-constant gate for the trace surface; DCE folds gated branches in production | n/a (gate) |
| `--no-eval` MCP launch flag (re-frame2-pair-mcp) | absent → `eval-cljs` ENABLED (rf2-a0z0h; inverts prior rf2-cxx5s default-OFF) | One-time opt-OUT for the arbitrary-code-execution surface. Default ON because eval is the REPL primitive of a pair-debug session and a default-OFF gate did not add a protection separable from `--allow-writes` (eval expresses every write the writes-gate blocks); the load-bearing remote-attack protection is the localhost-bind. | n/a (gate; tool refusal envelope returned when launch-flag set) |
| MCP server bind address | `127.0.0.1` (localhost) | Default-localhost-bind for published MCP servers | n/a (gate; remote access requires explicit launch flag) |

The Conventions doc carries the reserved-config-slot table ([Conventions.md](../spec/Conventions.md)); this table cross-references it with the *concrete numeric* the reference ships.

## JVM-vs-CLJS stub semantics

The reference implementation uses `re-frame.interop` (with separate `.clj` and `.cljs` implementations per [`../spec/000-Vision.md` §C2](../spec/000-Vision.md#c2-cross-platform-jvm-interop-preserved)) to give every host its appropriate realisation of the production-elision gate and the dev-flag posture.

### `re-frame.interop/debug-enabled?` — the dual-host gate

The same name, two implementations:

- **CLJS** (`re-frame/interop.cljs`): aliased to `goog.DEBUG`. In `:advanced` builds, `(when ^boolean re-frame.interop/debug-enabled? ...)` is constant-folded by the Closure compiler — the entire gated branch (allocation, listener iteration, malli call, error-reason string assembly, Performance API bridge) is dead-code-eliminated. The CLJS production-elision conformance gate (`npm run test:elision` → [`scripts/check-elision.cjs`](scripts/check-elision.cjs)) verifies that gated branches do not survive `:advanced` + `goog.DEBUG=false`.
- **JVM** (`re-frame/interop.clj`): a `def` whose value is read once at ns-load from the system property `re-frame.debug` or the env var `RE_FRAME_DEBUG`. Defaults `true` (dev posture). SSR / webhook receivers / long-running JVMs set `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false` explicitly to eliminate the dev-side enrichment surface in production. Unlike CLJS, the JVM gate is a runtime read, not a compile-time constant — the cost is a single boolean check per trace-emit site, which is acceptable for the JVM posture (SSR responses are not the hot loop the CLJS browser path is).

The JVM stub is not a "no-op stub" — it is a real, runtime-honoured gate. The same source code (`(when re-frame.interop/debug-enabled? ...)`) elides via Closure on CLJS and runtime-shortcircuits on JVM.

### Always-on substrates — production-survivable on both hosts

Two substrates survive `:advanced` + `goog.DEBUG=false` on CLJS, and survive `re-frame.debug=false` on JVM:

- **The event-emit substrate** — `re-frame.event-emit/dispatch-on-event!`. The normal production binding is the frame's `:observability` config: each per-event record is **projected** under the frame egress policy *before* the sink (Datadog, Honeycomb, Sentry, custom) receives it — sinks consume already-projected records only, never raw event/db state. `register-event-listener!` / the low-level listener registry remain as an advanced/internal integration API; a listener that ships off-box carries the same projection-before-egress obligation.
- **The error-emit substrate** — `re-frame.error-emit/dispatch-on-error!`. Same shape: per `:rf.error/*` record, the production binding is frame-owned `:observability`, and the record is projected before delivery; per-sink exceptions are isolated. The `register-error-listener!` low-level registry is the advanced/internal path with the same obligation. (The per-frame `:on-error` recovery policy was removed per rf2-hiqtk8 — recovery is framework-owned via the per-category typed defaults.)

Sensitive data marking on these substrates is **owner-declared** per the graduated EP-0015 / EP-0025 / [Spec 015](../spec/015-Data-Classification.md) model. Durable app-db classification rides the **EP-0025 commit-plane classification effects** — a `reg-event` returns `:sensitive` / `:large` alongside `:db`, written into the per-frame elision registry under `:source :effect` (`re-frame.elision/apply-classification-effects`); the overlap-redaction interceptor lives in `re-frame.privacy`. Per-slot `:sensitive?` / `:large?` schema props own owner-local schema'd data only. Projection is centralized at the trust boundary via `project-egress` (over `elide-wire-value`) under a named `:rf.egress/*` profile, so a record reaching an off-box sink is already redacted. The legacy handler-meta `:sensitive?` annotation, the durable `:sensitive` / `:large {:app-db …}` **frame annotation**, and the imperative `add-marks` API have all been removed; app-schema slot metadata is NO LONGER a route into durable app-db egress policy (the `populate-sensitive-from-schemas!` boot bridge is removed).

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
| Recordable cofx value fails its `reg-cofx` `:schema` (supplied / replayed / generated) — EP-0017 | `:rf.error/cofx-value-invalid` | `:rf.cofx/id`, `:value` / `:explain` (**redacted to `:rf/redacted` + `:sensitive? true` when the `:schema` marks any slot `:sensitive?`** — fails closed on the trace AND the thrown ex-data); always-on (production hard error — out-of-contract durable value is corrupt state) |
| Generated recordable cofx value is not ordinary EDN (a minted host handle) — EP-0017, dev-mode | `:rf.error/cofx-value-invalid` | `:rf.cofx/value-error :non-edn-recordable-value`, `:path`, `:bad-type`, `:preview` (only when itself recordable — never the raw host object) |
| Declared cofx with no `reg-cofx` registration (typo) — EP-0017 | `:rf.error/unregistered-cofx` | `:rf.cofx/id`, `:failing-id`; always-on (fires in production) |
| Declared recordable cofx absent + unproducible (`:provided?` not stamped, or `:strict` mint policy) — EP-0017 | `:rf.error/missing-required-cofx` | `:rf.cofx/id`, `:failing-id`; always-on (the strict-replay loud failure) |
| Cofx supplier / generator threw during context assembly — EP-0017 | `:rf.error/coeffect-exception` | `:rf.cofx/id`, `:phase :before`, `:exception`; the handler is SKIPPED (capture-don't-propagate, mirroring retired `inject-cofx`) |
| `reg-cofx` name collision / malformed `:rf.cofx/requires` / malformed grade — EP-0017 (registration-time) | `:rf.error/cofx-name-collision` (collides with `:db` / `:event` or a duplicate id) · `:rf.error/cofx-request-invalid` (malformed `:rf.cofx/requires`) · `:rf.error/cofx-registration-invalid` (`:provided?` without `:recordable?`; missing supplier; provided fact carrying an ignored supplier) | `:rf.cofx/id` / `:failing-id`, `:reason`; registration-time diagnostic |
| `inject-cofx` called (REMOVED in EP-0017, no alias) | `:rf.error/inject-cofx-removed` | `:rf.cofx/id`, `:reason` naming `:rf.cofx/requires` as the replacement; always-on (correctness contract, not a dev diagnostic) |
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
| rf2-hjs2d | Removal of handler-meta `:sensitive?` annotation | Mike 2026-05-17: "We won't be having :sensitive? true on the event handler. That's a bad idea." Sensitive data marking is owner-declared per the graduated EP-0015 / [Spec 015](../spec/015-Data-Classification.md) model — durable app-db classification is **frame-owned**; per-slot schema props own owner-local schema'd data. Sensitivity is a property of the data value at a path, not of the handler that touched it. |
| rf2-kj51z | Schema-validation-failure redacts `:value` / `:received` / `:explain` / `:fx-args` when slot is `:sensitive?` | Malli's standard behaviour carries the failing value verbatim under `:value` / `:errors[].value`. Without redaction the trace event re-leaks the secret to every registered listener. |
| rf2-hdadz | Recorder redacts payload but records the slot | Drop-the-payload semantics, not refuse-to-record — devs lose useful correlation otherwise. Matches the always-on error-emit substrate's posture. |
| rf2-czv3p (egress model updated by EP-0015 §10/§11) | Direct-read tools MUST project before off-box egress; named mutations need no extra gate | Direct reads (`get-app-db`, `get-path`, `snapshot`, `sub-cache`, epoch export, tool readbacks) bypass handler-scoped trace stamping, so they MUST project at the egress boundary. EP-0015 final: the normal call is `re-frame.core/project-egress` with a named `:rf.egress/*` profile (a tool uses `:rf.egress/off-box-tool`) and a KNOWN `:frame` whose classification applies — `project-egress` delegates each tree-shaped slot to the low-level `elide-wire-value` primitive. A direct read that needs frame policy must FAIL CLOSED when no frame is known: with no frame and no `:rf.size/include-sensitive? true` opt-out the delegated walker redacts the whole value to `:rf/redacted` rather than borrow another frame's policy — `project-egress` does NOT synthesise `:rf/default`. Durable app-db redaction comes from the EP-0025 commit-plane classification effects (a `reg-event` returns `:sensitive` / `:large` alongside `:db`), NOT schema-declared live-value redaction. The bare `elide-wire-value` call stays available as the low-level value primitive (advanced override), but the boundary tools name is `project-egress` + profile. Named mutations get no extra gate — invoking the tool is the consent. |
| rf2-b2hip | spec/004-Wire-Pipeline.md aligned to spec/Tool-Pair MUST on direct-read privacy | Spec-vs-spec drift resolution: trace redaction does NOT protect a live-value direct read. Tool-Pair MUST wins. |
| rf2-isdwf | `:sensitive?` hoisted from `:tags` to trace-event top-level | Consumers route on top-level `:sensitive?` rather than `(get-in trace-event [:tags :sensitive?])` — flatter access path, cheaper conformance gate. |
| rf2-j1m7x / rf2-mrsck | `re-frame.epoch/projected-record` (`day8/re-frame2-epoch`) — single normative projection helper that uses `project-egress` under a named off-box profile (`project-egress` delegates tree slots to `elide-wire-value`); `re-frame.epoch/configure!` `:trace-events-keep` finite retention cap; `:rf.epoch/sensitive?` record-level rollup | Listener fan-out delivers raw records (Xray diff / `restore-epoch!` need them); off-box egress (Xray-MCP `watch-epochs`, story / pair recorders, hosted forwarders) routes through `projected-record` at the wire boundary. The `:trace-events-keep` cap bounds dev-session heap growth (the most-recent N records keep raw `:trace-events`; older keep only the cheap structured projections). The `:rf.epoch/sensitive?` rollup mirrors the trace-event `:sensitive?` boolean so consumers branch on one slot per record. |

#### EP-0017 recordable coeffects — durability is the threat model

EP-0017 (final) makes the cofx surface a privacy boundary: `reg-cofx` is value-returning + graded, `:rf.cofx/requires` declares consumption, and the runtime delivers **exactly** the declared facts, flat. A **recordable** coeffect is folded into durable frame-state — written into the `:rf.cofx` envelope, recorded with the causal token, re-presented verbatim by replay, and shipped in the SSR payload / epoch export. The discipline: *durable state folds facts, never reads.*

| Bead / EP | Call | Rationale |
|---|---|---|
| EP-0017 §Security Considerations | **Crypto-grade randomness, tokens, nonces, session ids, and key material MUST NOT be minted or carried as recordable coeffects** | Recording a secret does not make it safe — it makes it **durable** (copied into every recording, fixture, exported trace, and the on-box ring, where redaction does not undo durability). Secrets are generated at the edge and handled off the ledger (EP-0010's exclusion, restated at this surface). Because app-owned `reg-cofx` suppliers mint arbitrary values, this is a **normative review discipline + lint** (the EP-0017 "durable-writing handler declaring an *ambient*-grade id" lint), NOT a structural guarantee. The framework's one built-in recordable fact, `:rf/time-ms`, is classified always-safe. See [Privacy §Recordable coeffects must exclude secrets](../spec/Privacy.md#recordable-coeffects-must-exclude-secrets). |
| EP-0017 / EP-0015 (rf2-hdi6wr) | Every other `:rf.cofx` leaf projects under the SAME EP-0015 rules as event-arg values | Exclusion is the load-bearing rule for *secrets*; **projection** is the safety net for the merely *private*. A recordable value that is legitimately sensitive is classified (`reg-cofx` `:sensitive` / `:large`, or the cofx's `:schema` props) and redacted at the off-box boundary like any transient payload — both the `marks/project-cofx-run-tags` chokepoint on the delivered `:rf.cofx/value` and the `:rf.error/cofx-value-invalid` emit fail closed when the `:schema` marks a slot `:sensitive?`. |
| EP-0017 §6 (rf2-ygpac8 / rf2-uqz2ir) | Mint-policy + generator status: `:live` (router default) / `:explicit-live` generate a declared-absent generator-backed recordable fact; `:strict` (the `:test` preset; hard-wired for replay) does not | Replay is unconditionally strict — an incomplete record fails loudly (`:rf.error/missing-required-cofx`) rather than silently re-reading the host (the no-silent-mint invariant; an unrecognised policy is treated conservatively as non-generating). A generated recordable value is written back into the in-flight `:rf.cofx` record (so the epoch captures the post-generation token), `:schema`-validated (production hard error), and dev-mode walked for ordinary-EDN-ness before the write-back. |

### MCP tool authority

| Bead | Call | Rationale |
|---|---|---|
| rf2-czv3p (part 1) | Named-mutation tools ungated; `eval-cljs` separate authority class | Programmer-friction matters; named mutations are the debugging primitive. `eval-cljs` is qualitatively different — arbitrary code execution. |
| rf2-cxx5s (superseded by rf2-a0z0h) | re-frame2-pair-mcp `eval-cljs` originally shipped DISABLED with `--allow-eval` opt-in | The original published-default-safe stance. Reversed by rf2-a0z0h after the friction was measured against the threat-model gain (zero — `--allow-writes` does not become more secure when `--allow-eval` is off, because eval expresses every write the writes-gate would block). |
| rf2-a0z0h | re-frame2-pair-mcp `eval-cljs` now defaults ENABLED; `--no-eval` is the opt-out | Eval-cljs is the REPL primitive of a pair-debug session; defaulting it off forced every operator to edit `~/.claude.json` and restart Claude Code to access the surface their MCP install was for. The gate did not add a protection separable from `--allow-writes`. The load-bearing remote-attack protection is the localhost-bind (rf2-hpkkx); per-operator trust is the install decision. |
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
| rf2-hqbeh | Always-on error-emit substrate (not gated by `debug-enabled?`) | The handler-exception path is the primary production-monitoring case; gating it on `debug-enabled?` would eliminate the production observability surface. The production binding is frame-owned `:observability` config and the sink receives an already-projected record (EP-0015); dev-side enrichments (`:dispatch-id`, source-coord) elide with the rest of the trace surface. |
| rf2-rirbq | Always-on event-emit substrate | Sibling to the error-emit substrate; per-event records reach hosted observability (Datadog, Honeycomb, Sentry) through the frame's `:observability` config, projected under the frame egress policy before sink delivery. |
| rf2-bacs4 | Always-on error-emit substrate; frame-owned `:observability` sink | EP-0015 final makes production observability sinks frame configuration (`:observability`); the sink receives a record already projected under the frame egress policy / `:rf.egress/*` profile — raw `:rf.error/*` records never reach shippers. The low-level `register-error-listener!` registry remains as an advanced/internal integration API carrying the same projection-before-egress obligation; per-sink exceptions are isolated. (The per-frame `:on-error` recovery policy was removed per rf2-hiqtk8.) |
| rf2-jbcmt | SSR response accumulator moved to side-channel atom (not in `app-db`) | EP-0015 final makes SSR/hydration **allowlist-first** production egress: the payload emits only **allowlisted frame state**, then applies the `:rf.egress/ssr-hydration` projection — unlisted state (including raw owner-local frame data) does not cross the browser boundary. (The pre-EP-0015 whole-app-db-by-default hydration is **obsolete**; this is the safety property the allowlist eliminates.) Keeping SSR response state — auth cookies, internal `X-*` headers — in a side-channel atom rather than `app-db` is the complementary defense in depth: response accumulators are never even a candidate for the hydration allowlist, so the privacy boundary is self-enforcing. |

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
| rf2-c1l4d | (SUPERSEDED by EP-0015 §8, rf2-d2r3um) Schema walker populated `[:rf/runtime :elision :sensitive-declarations]` at boot | *Historical context only — no longer the contract.* EP-0015 final makes durable app-db classification **frame-owned**; the boot bridge that fed app-schema slot metadata into the durable app-db egress registry (`populate-sensitive-from-schemas!`) is **removed**. The schema walker survives, but its `{path {:sensitive? true}}` map is read **owner-locally** by the machine / resource `:data-schema` redaction bridges and the HTTP body-privacy projector — never as a route into durable app-db egress policy. |
| rf2-edfhh | Original top-level `Security.md` catalogue + threat model + decisions log (now split into pattern + impl per rf2-1g6cj / rf2-ao8a2) | Same shape pattern as Conventions.md + Principles.md: top-level coordination doc that points at the detail without duplicating it. |
| rf2-1g6cj | Decision: split Security.md into pattern-level + CLJS-impl (Option A) | Each doc serves one audience cleanly — a TS implementer reads `../spec/Security.md`; a CLJS contributor reads `implementation/SECURITY.md`. Per rf2-0hs5t.3 (a) — external canonical homes are allowed for impl-level concerns. |
| rf2-ao8a2 | This split executed: pattern-level moved to `../spec/Security.md`; CLJS-impl specifics landed here | Keystone bead — unblocks 10-bead rf2-wpo8k Security cross-ref cluster + clears `../spec/Ownership.md` for rf2-exdfg spec-coherence cluster. |

## Cross-references

- [`../spec/Security.md`](../spec/Security.md) — the pattern-level companion: threat model, behavioural MUSTs, pragmatic stance.
- [`../spec/Ownership.md`](../spec/Ownership.md) — the contract-surface → owning-spec table; this doc's row in Ownership pins it as the canonical impl-side security home (per rf2-0hs5t.3 (a)).
- [`../spec/Conventions.md`](../spec/Conventions.md) — reserved namespaces, fx-ids, app-db keys, and meta keys (the cross-cutting vocabulary).
- [`../spec/Privacy.md`](../spec/Privacy.md) — the graduated EP-0015 owner-classification + `project-egress` cross-artefact privacy inventory and composition order; the recordable-coeffect secrets-exclusion rule.
- [`../spec/015-Data-Classification.md`](../spec/015-Data-Classification.md) — the normative EP-0015 contract: frame-owned durable app-db classification, registration-owned transient classification, owner-local schema'd-data props.
- [`../spec/009-Instrumentation.md`](../spec/009-Instrumentation.md) — trace event model, error catalogue, the always-on substrate definitions.
- [`../spec/010-Schemas.md`](../spec/010-Schemas.md) — schema-driven privacy declaration (owner-local, per-slot); boundary-validation seam.
- [`../spec/011-SSR.md`](../spec/011-SSR.md) — response-shape fx (the CRLF-check sites); `:rf.server/safe-redirect`; allowlist-first hydration egress.
- [`../spec/014-HTTPRequests.md`](../spec/014-HTTPRequests.md) — managed-HTTP input-validation and DoS defaults.
- [`../spec/002-Frames.md`](../spec/002-Frames.md) / [`../docs/EP/EP-0017-recordable-coeffects.md`](../docs/EP/EP-0017-recordable-coeffects.md) — the `:rf.cofx` recordable-coeffect envelope, the `:rf.cofx/requires` declaration key, mint policies, and the secrets-exclusion §Security Considerations.
- [`../spec/API.md`](../spec/API.md) — public-surface signatures (the names a user types: the commit-plane `:sensitive` / `:large` effects, schema `:sensitive?` / `:large?` metadata, `elide-wire-value`, `project-egress`). Per the graduated EP-0015 / EP-0025 model, durable app-db classification rides the **commit-plane classification effects** (a `reg-event` returns `:sensitive` / `:large` alongside `:db`); per-slot schema `:sensitive?` / `:large?` props are the surviving declaration for **owner-local schema'd data** (machine / resource `:data-schema`, HTTP `:decode`, schema-validation-failure redaction) — NOT a route into durable app-db egress. The legacy handler-meta `:sensitive?` annotation and the durable `:sensitive` / `:large {:app-db …}` frame annotation were removed (rf2-hjs2d / EP-0025).
- [`../spec/Tool-Pair.md`](../spec/Tool-Pair.md) — MCP-server direct-read wire-egress contract.
