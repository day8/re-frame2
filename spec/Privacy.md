# Privacy & Data-Classification — cross-artefact reference

> **Type:** Reference
> **Normative status:** Supporting companion. Defers to [009-Instrumentation](009-Instrumentation.md), [010-Schemas](010-Schemas.md), [014-HTTPRequests](014-HTTPRequests.md), [015-Data-Classification](015-Data-Classification.md), [Tool-Pair](Tool-Pair.md), [Conventions](Conventions.md), and [Security](Security.md) for every contract surface named here. This doc is the **discoverability index** — one place to land for "where do privacy primitives live across re-frame2's artefacts, what is the composition order, and what do I declare to keep a value out of off-box egress?"

re-frame2's privacy surface is the **leak-prevention overlay on observability**. Real data flows through events / cofx / handlers / fx / app-db / subs / views unchanged; sentinel substitution happens **only at the observation/egress boundary**. The contract spans five artefacts (`re-frame.core`, `re-frame.http`, `re-frame.schemas`, `re-frame.epoch`, `tools/mcp-base`) and the **owner-classification** declaration sources the graduated [EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) model fixes — frame config (durable frame-wide facts), per-slot schema props (owner-local schema'd data), and registration metadata (transient payloads) — this doc gathers them into one inventory and pins the composition order.

> **Graduated to the EP-0015 model.** This doc was rewritten to the **[EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md)** (final) owner-classification + `project-egress` + `:rf.egress/*`-profile model that [Spec 015](015-Data-Classification.md) graduates, and reflects the **[EP-0017](../docs/EP/EP-0017-recordable-coeffects.md)** (final) recordable-coeffect secrets-exclusion rule (see [§Recordable coeffects must exclude secrets](#recordable-coeffects-must-exclude-secrets)). The pre-EP-0015 framing — the "seven first-class marking sites", the public `add-marks` / `set-marks` app-db path-mark API, schema-attached *app-db* classification, and the positional `redact-interceptor` — is **superseded**; those surfaces are removed (EP-0025 also removed the `re-frame.marks` namespace itself — the marks projection substrate migrated into the marks-free `re-frame.classification` / `re-frame.elision` engine), and where this index links to a sibling spec that still carries the old framing, the link is kept but its description reconciled here. The normative contract lives in [Spec 015](015-Data-Classification.md); this doc is the cross-artefact index.

> **Posture (per Spec 015 §Posture).** Privacy here is observability hygiene, not authorisation. Apps still own auth, authorisation, encryption-at-rest, and transport security. The classification machinery exists so that the framework's own dev-time observability surfaces (and their downstream consumers — log sinks, AI agents, dashboards) cannot accidentally exfiltrate user secrets or stuff log lines with multi-megabyte blobs. See [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the pattern-level threat model.

---

## Table of contents

- [The six observation boundaries](#the-six-observation-boundaries)
- [Inventory by artefact](#inventory-by-artefact) — every imperative + declarative entry point, grouped by owning namespace
- [Inventory by declaration source](#inventory-by-declaration-source) — same surfaces, grouped by where the author declares the classification
- [The composition order (data-flow)](#the-composition-order-data-flow) — what runs when, from handler exit to off-box wire
- [Recordable coeffects must exclude secrets](#recordable-coeffects-must-exclude-secrets) — the EP-0017 secrets-exclusion rule
- [Display sentinels](#display-sentinels) — what observation surfaces render
- [Config knobs](#config-knobs) — the two verb families and the configure-keys
- [Indicator slots](#indicator-slots) — what observers expose so callers know the payload was filtered
- [Worked example](#worked-example--password-in-app-db--token-header-on-http) — the canonical case Finding #8 names
- [Author guidance — the exception-path residual](#author-guidance--the-exception-path-residual)
- [Removed surfaces](#removed-surfaces)
- [Cross-references](#cross-references)

---

## The six observation boundaries

Privacy declarations exist to stop leaks at every framework-mediated observation/egress boundary. Per [015 §Scope](015-Data-Classification.md#in-scope--the-boundaries-projection-must-guard) the complete set, each with the egress profile [Spec 015](015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional) projects it under:

| # | Boundary | What sees it | Profile / production posture |
|---|---|---|---|
| 1 | **Trace-bus emit** — every `:rf/trace-event` built by `emit!` / `emit-error!` | Trace listeners, Xray panel, error monitors, log sinks | Dev stream gated on `re-frame.interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); the always-on **error-emit substrate** ([009 §Error-emit substrate](009-Instrumentation.md)) survives production |
| 2 | **Xray / Story panels** — Event Detail, App-DB Diff, Subscriptions, Trace, Causality Graph, Machine Inspector, Flow Panel, Story scenarios | The on-box dev tool; CLJS-only | `:rf.egress/local-redacted` by default; dev-only (production-elided) |
| 3 | **MCP / tool wire transport** — `tools/re-frame2-pair-mcp`, `tools/story-mcp`, any future MCP server | Off-box LLM / tool consumers | `:rf.egress/off-box-tool`; N/A (tooling, not in the production bundle) |
| 4 | **AI / LLM context lifted by tools** — any code path that lifts trace events / app-db / sub outputs / machine `:data` into an LLM prompt | The hosted LLM endpoint | `:rf.egress/off-box-tool`; N/A |
| 5 | **Hosted log sinks** — Datadog, Sentry, LogRocket, Honeybadger, custom fan-outs; routed by frame `:observability` | Off-box ops/monitoring | `:rf.egress/off-box-observability`; the always-on error-emit substrate is **the live path** here — it survives `goog.DEBUG=false`, so sensitive-projection MUST work in production builds |
| 6 | **Epoch export, SSR / hydration, public error responses, HTTP diagnostics, schema-validation failure records** | Off-box recorders, the browser, hosted dashboards | each its own profile — `:rf.egress/ssr-hydration` (after the §SSR allowlist), `:rf.egress/public-error`, `:rf.egress/off-box-observability` |

The contract for the off-box boundaries (3 / 4 / 5 / 6) is **project before egress**: the runtime projects every record under the owning frame's classification and the boundary's `:rf.egress/*` profile via [`project-egress`](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive) before any sink, tool, or wire sees it; a sink **never** receives a raw record. On the always-on trace/error substrate, a record carrying `:sensitive? true` at the top level is dropped by the off-box forwarder. Apps opt back in by passing the off-box-wire opt — the MCP tool argument is the unqualified `:include-sensitive` (the Anthropic tool-input-schema regex rejects a trailing `?`; see [`tools/mcp-base/spec/sensitive.md` §Cross-server arg-vocabulary](../tools/mcp-base/spec/sensitive.md#cross-server-arg-vocabulary-convention)), which resolves to the `:rf.egress/local-raw` profile — or, for an on-box panel, the `{:show-sensitive? true}` UI toggle. The off-box-wire *verb family* is named `include-sensitive?` (the `?` rides the config-knob verb, not the MCP wire key). See [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress).

---

## Inventory by artefact

The complete imperative + declarative surface, grouped by owning namespace. Every entry's normative owner lives in the cited Spec section; this table is the index, not the contract.

### `re-frame.core` (production-survivable subset re-exported from artefacts below)

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `:sensitive` | reg-meta key | Vector of paths into the registration's primary data shape (event arg-map, fx-input map, cofx value, sub output, flow output, machine transition payload) | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `:large` | reg-meta key | Symmetric to `:sensitive` — paths to slots elided with `:rf.size/large-elided` | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` | commit-plane effects (`reg-event` return) | The owner of durable app-db classification (EP-0025) — each a vector of `:rf/path` vectors, applied **with the `:db` write** at the commit point into the per-frame elision registry. A handler classifies a durable app-db path by returning these alongside `:db`. **Replaces** the removed `reg-frame` `:sensitive {:app-db …}` annotation and the `add-marks` / `set-marks` / `declare-sensitive-*!` surfaces. | [015 §Durable app-db — the four commit-plane effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects) |
| subsystem `:sensitive` / `:large` | subsystem registration (`reg-machine`, `reg-resource`, `reg-mutation`, `reg-route`) | Projection-relative durable classification declared on a subsystem definition (e.g. a machine's `:data`-rooted `:rf/path` vectors), re-rooted to the instance's absolute runtime path and unioned into the same per-frame registry the commit-plane effects write. | [015 §Subsystem projection-relative classification](015-Data-Classification.md#subsystem-projection-relative-classification) |
| `:rf.http/managed` `:carriers` | `reg-fx :rf.http/managed` meta | App-specific HTTP carrier names (a union onto the immutable built-in defaults) — the EP-0025 transient-payload case. `(rf/reg-fx :rf.http/managed {:carriers {:headers […] :query-params […]}} h)`. | [015 §HTTP carriers](015-Data-Classification.md#http-carriers) |
| frame `:observability` | `reg-frame` meta | Observability sink policy. The frame `:sensitive` key is **gone** entirely — its `:app-db` durable block moved to the four commit-plane effects above, and its `:http` carrier block moved to `:rf.http/managed`; a `reg-frame` `:sensitive` is rejected fail-loud. | [015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy) |
| `project-egress` | record-level boundary primitive | `(rf/project-egress record opts)` → projected record. The public record-level projection primitive (knows app-db-/event-/exception-/HTTP-/summary-shaped slots); the **required step before any off-box sink**. Delegates per tree-shaped slot to `elide-wire-value`. New façade export (subject to the facade-export classification rule). | [015 §`project-egress`](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive) |
| `register-observability-sink!` / `unregister-observability-sink!` | façade fns | Register the concrete sink fn against the id a frame's `:observability` policy names; the sink consumes the already-projected record. New façade exports (subject to the facade-export classification rule). | [015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy) |
| `sensitive?` | predicate | `(rf/sensitive? trace-event)` → bool. True iff the event carries `:sensitive? true` at the top level. The framework-published predicate every forwarder composes against. | [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) |
| `elide-wire-value` | walker | `(rf/elide-wire-value v opts)` → walked `v`. The **low-level value walker** for tree-shaped values; `project-egress` delegates to it per tree-shaped slot. Sinks and tools should rarely call it directly. | [API.md §wire-elision walker](API.md#elide-wire-value-the-wire-boundary-walker), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |
| `(configure! {:elision ...})` | runtime config | `{:rf.size/threshold-bytes N}` — wire-elision size cap. Default `16384`. | [API.md §Configure keys](API.md) |

**Removed surfaces** (not live surfaces; listed so a reader following an older cross-reference lands on the removal):

- `:rf.egress/output-sensitivity` reg-meta key — the derived-output declassification claim + its `:rf.egress/inherit` / `:sensitive` / `:public` value set; classification does **not** propagate, so there is nothing to declassify (per [015 §No propagation, no taint](015-Data-Classification.md#no-propagation-no-taint)).
- the `reg-frame` `:sensitive` / `:large {:app-db …}` durable annotation — durable app-db classification is the four commit-plane effects above; a `reg-frame` `:sensitive {:app-db …}` is rejected fail-loud.
- `redact-derived-slots` — the value-match / taint dual of `elide-wire-value`; removed from the façade **and** from `re-frame.elision` (value-match is propagation by another name). The only derived-tree egress boundary is now `project-egress` over a `:rf.observe/derived-tree` record, path-walked against the frame's classification (per [015 §project-egress](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive)).
- `populate-elision-from-schemas!` / `populate-sensitive-from-schemas!` — the schema→registry migration bridge; removed (schemas describe shape, not durable app-db egress policy — per [015 §Schemas describe shape](015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy)).

### `re-frame.http`

HTTP carrier policy. The HTTP fx maps headers + query-strings + the decoded response body into `:rf.http/*` trace events; their classification surfaces:

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| Built-in header / query-param denylists | framework default (immutable) | Closed sets of always-sensitive header / query-param names redacted in every `:rf.http/*` trace event regardless of the request `:sensitive?` flag — the **name is the signal**. No frame can remove a built-in name. | [014 §1–2](014-HTTPRequests.md) |
| Managed-HTTP carriers | declarative (`reg-fx :rf.http/managed` meta) | `(rf/reg-fx :rf.http/managed {:carriers {:headers [..] :query-params [..]}} h)` — app-specific carrier names that **union** onto the immutable built-in defaults (the transient-payload case). There is no frame `:sensitive {:http …}` block and no process-global `declare-sensitive-*!` mutator. | [014 §HTTP carriers](014-HTTPRequests.md#http-carriers-ep-0025) |
| Response-body classification | declarative (`:decode` schema) | Per-slot `:sensitive?` / `:large?` props on the request's `:decode` Malli schema classify the decoded body. Whole-body root prop redacts everything; an unschematized body fails closed off-box. | [014 §Response-body classification](014-HTTPRequests.md#response-body-classification-ep-0015-5) |
| `:sensitive?` (per-call) | request arg | `{:rf.http/managed {:sensitive? true}}` — opts a specific request in. When true, the request **body** is redacted to the sentinel and **all** query params are scrubbed (broader than the denylist). Sugar form: `{:request {:sensitive? true}}`. | [014 §Privacy](014-HTTPRequests.md) |

Built-in denylists ship populated with the obvious cross-app names (`authorization`, `cookie`, `x-api-key`, `set-cookie`, ...; `api_key`, `access_token`, `auth`, `token`, ...). App-specific carrier names (`X-MyApp-Auth`, `shop_token`) are declared on the **`:rf.http/managed` `reg-fx` registration** via the `:carriers` block — there is no frame `:sensitive {:http …}` block and no process-global `declare-sensitive-header!` / `declare-sensitive-query-param!` mutator.

### `re-frame.schemas` (declarative — no imperative surface)

Schema-attached slot props. These are the **one and only** classification route for *owner-local schema'd data* — machine `:data-schema`, resource `:data-schema` / `:params-schema`, an HTTP request's `:decode` schema (one owner, one route) — and they drive **schema-validation error-trace** redaction. They are **not** a route for durable *app-db* classification (that rides the four commit-plane effects per [015 §Schemas describe shape](015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy)); the schema→registry hydrators (`populate-elision-from-schemas!` / `populate-sensitive-from-schemas!`) are removed (see the removal note above).

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `:sensitive? true` | schema slot prop | Per-slot Malli property `{:sensitive? true}` on a `:data-schema` / `:params-schema` / `:decode` schema slot (or, for migration import only, an app-schema slot). The canonical fine-grained surface for schema-owned data; schema-validation error traces consult the prop (`:value` / `:received` / `:explain` / `:rf.fx/args` / `:rf.sub/query-v` redaction). | [010 §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces), [015 §Machine-owned](015-Data-Classification.md#machine-owned-durable-classification-frame-owned-ep-0025-reversal-of-the-ep-0005-redaction-bridge) |
| `:large? true` | schema slot prop | Symmetric to `:sensitive?` for the size axis — per-slot Malli property on a `:data-schema` / `:params-schema` / `:decode` schema slot. Schema-validation error traces consult the prop and substitute `:rf.size/large-elided` for the matching slot value. (Not a durable *app-db* route — that rides the four commit-plane effects.) | [010 §`:large?`](010-Schemas.md#large--schema-driven-size-elision-nomination) |

### `re-frame.epoch`

Per EP-0015 issue 6 (graduated), epoch records are **causal replay material** (post-[EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)) and **storage-side mutation is removed** — the raw record stays in the ring and every `register-epoch-listener!` listener receives it unmutated; **off-box egress MUST project** through `projected-record` / `projected-history` (which run `project-egress` under an off-box profile). The surviving `:redact-fn` hook is **projection-side only** (export/egress), not a storage-side record transform; it is an advanced escape for slots the frame's classification cannot prove.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `(configure! {:epoch-history {:redact-fn fn}})` | runtime config | **Projection-side advanced override.** Invoked **once per record at the off-box egress boundary** — inside the projected-record helper, **after** the frame/profile `project-egress` projection — and MUST NOT mutate the record at storage time. The in-process ring + every listener therefore deliver the **raw** record (mutating replay material at rest corrupts the EP-0010 replay contract). Failures emit `:rf.warning/epoch-redact-fn-exception` and fall back to the projected record for that egress only. Production-elided (the whole epoch surface rides `debug-enabled?`). | [015 §Epoch projection](015-Data-Classification.md#epoch-projection-no-storage-side-mutation), [Tool-Pair §Redaction hook](Tool-Pair.md), [API.md §Configure keys](API.md) |
| `:rf.epoch/sensitive?` | record-level rollup | Top-level boolean on the assembled `:rf/epoch-record` — true iff any captured trace event / declared-sensitive leaf in the record was sensitive. Computed at build-time from the raw record's schema-declared sensitive leaves, so it stays an accurate off-box-branch signal on the raw ring record. | [Tool-Pair §Time-travel](Tool-Pair.md) |
| `projected-record` | projection fn | `(rf/projected-record record)` — off-box-safe projection of a `:rf/epoch-record`. Routes each tree slot through `project-egress` (over `elide-wire-value`), strips raw `:db-before` / `:db-after`, keeps the structured fields (`:trigger-event`, `:fx`, `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`, `:rf.epoch/redacted-modified-paths-count`). The single projection site when shipping epoch data off-box; then applies the `:redact-fn` advanced override. Idempotent. | [Tool-Pair §Direct-read privacy](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) |
| `projected-history` | projection fn | `(rf/projected-history frame-id)` — `(mapv projected-record (epoch-history frame-id))`. Off-box-safe equivalent of `epoch-history`. | [Tool-Pair §Time-travel](Tool-Pair.md) |

### `tools/mcp-base` (cross-MCP wire egress)

The framework-published privacy filter every MCP forwarder composes. Apps don't author against this directly — MCP server implementations do, conforming to the cross-server vocabulary.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `sensitive-event?` | predicate | Conservative predicate over a trace-event map — `true` iff `(:sensitive? ev)` is literal `true`. Mirror of `re-frame.privacy/sensitive?`. | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `strip-sensitive` | walker | `(strip-sensitive coll)` → `[kept dropped-count]`. The `dropped-count` becomes the `:dropped-sensitive` envelope counter on the MCP response. | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `scrub-snapshot` | walker | Snapshot-tree walker — descends into nested registration handles and removes `:sensitive?`-stamped sub-trees (stricter than top-level filtering). | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `:include-sensitive` | cross-MCP wire arg | Per-call opt-in on every MCP tool surfacing trace-like data. Defaults to `false`. The wire-key spelling is now **uniform** across every server — story-mcp and re-frame2-pair-mcp both ship the unqualified `:include-sensitive` (no trailing `?` — the Anthropic tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$` rejects `?`). The `?` is retained only on the internal walker option (`:rf.size/include-sensitive?`) and the config-knob verb (`include-sensitive?`), never the MCP wire key. | [`tools/mcp-base/spec/sensitive.md` §Cross-server arg-vocabulary](../tools/mcp-base/spec/sensitive.md#cross-server-arg-vocabulary-convention), [Conventions §Privacy config-knob](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress) |
| `:rf.size/large-elided` (elision marker) + `:include-large?` (wire arg) | cross-MCP wire vocabulary | Size-elision peer of `:sensitive?`. The walker substitutes `:rf.size/large-elided {:bytes N :head "..." :handle ...}` at over-threshold or `:large?`-declared slots; off-box callers opt in with `{:include-large? true}`. | [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |

---

## Inventory by declaration source

Same surfaces, regrouped by **the owner that declares the classification** ([015 §The ownership split](015-Data-Classification.md#the-ownership-split)): handler commit-plane effects classify durable app-db state; machine / resource / mutation definitions classify owner-local schema'd data (projection-relative); registration metadata classifies transient payloads (including the `:rf.http/managed` `:carriers` block — HTTP carrier names); frame config carries frame-local egress facts (observability sink policy, SSR allowlist).

### Handler commit-plane effects (durable app-db, EP-0025)

Durable app-db classification is declared by the four commit-plane effects a handler returns alongside `:db`; the classification commits **with the db write** into the per-frame elision registry, value-independently:

- `{:db … :sensitive [[:auth :token] …]}` — classify durable app-db sensitive paths (`:rf/path` values)
- `{:db … :large [[:docs :csv-upload] …]}` — classify durable app-db large paths
- `{:db … :clear-sensitive [[:auth :token]]}` / `{:db … :clear-large [[:docs :csv-upload]]}` — un-classify

This **replaces** the removed `reg-frame` `:sensitive` / `:large {:app-db …}` annotation and the `add-marks` / `set-marks` app-db path-mark API (the marks namespace is gone; its underlying projection substrate migrated into the marks-free elision engine — see [§Removed surfaces](#removed-surfaces)). Per [015 §Durable app-db — the four commit-plane effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects).

### Frame config (frame-local egress facts)

The frame carries observability sink policy and the SSR hydration allowlist (the `:sensitive` key is **gone** entirely — its `:app-db` durable block moved to the commit-plane effects and its `:http` carrier block moved to `:rf.http/managed`; a `reg-frame` `:sensitive` is rejected fail-loud):

- `(rf/reg-frame :app {:observability {:handled-events […] :errors […]}})` — production sink policy ([§Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy))
- `(rf/reg-frame :app {:ssr {:hydrate {:include-app-db […]}}})` — allowlist-first SSR/hydration boundary

Frame config installs atomically at frame creation (before the `:initial-events` setup runs); re-registering replaces it wholesale. HTTP carrier names are NO LONGER frame config — they ride the `:rf.http/managed` `reg-fx` registration's `:carriers` block (see the next section); there is no frame `:sensitive {:http …}` block and no process-global `declare-sensitive-header!` / `declare-sensitive-query-param!` mutator (their underlying fns survive as internal/test helpers only — see [§Removed surfaces](#removed-surfaces)).

### Per-slot schema props (owner-local schema'd data)

`{:sensitive? true}` / `{:large? true}` Malli props on the **owner's own schema** are the one-and-only route for owner-local schema'd data — machine `:data-schema`, resource `:data-schema` / `:params-schema`, an HTTP request's `:decode` schema (the one shared mechanism; no sibling path-map vocabulary). Whole-shape claims are the degenerate root-prop case; an unschematized HTTP body is whole-sensitive (fail-closed). Per [015 §Machine-owned](015-Data-Classification.md#machine-owned-durable-classification-frame-owned-ep-0025-reversal-of-the-ep-0005-redaction-bridge), [§Resource and mutation](015-Data-Classification.md#resource-and-mutation-durable-classification), [§HTTP response bodies](015-Data-Classification.md#http-response-bodies). (Schema props on an *app-db* schema are **not** a route to durable app-db classification — that rides the four commit-plane effects; the schema→registry hydrators are removed, per [015 §Schemas describe shape](015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy).)

### Registration metadata (transient payloads)

`reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow` accept `:sensitive` / `:large` (vectors of paths) into the registration's primary data shape. Empty path `[[]]` marks the whole shape. (There is no `:rf.egress/output-sensitivity` declassification key — classification does not propagate, so there is nothing to declassify; to expose a derived secret safely, classify only the paths you mean to redact.)

| Reg kind | Path root | Owner |
|---|---|---|
| `reg-event` | the event arg-map (second element of `[:event-id {arg-map}]`) | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `reg-sub` | the sub's output value (classify the output paths directly — no propagation, no declassification claim) | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `reg-fx` | the fx-input map | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `reg-cofx` | the coeffect value (`[[]]` = the whole value) — see also [§Recordable coeffects must exclude secrets](#recordable-coeffects-must-exclude-secrets) | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |
| `reg-flow` | the flow's `:output` value (classify the output paths directly — no propagation, no declassification claim) | [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) |

Machine **transition payloads** are transient payloads classified by the transition/registration metadata that introduces them — not by the machine's durable `:data` policy. Durable runtime-subsystem state (resource/mutation) is owned by its definition (per-slot schema props), not classified as a transient payload merely because it is declared by `reg-resource` / `reg-mutation`.

### HTTP carriers (`:rf.http/managed` registration) — quick reference

- `(rf/reg-fx :rf.http/managed {:carriers {:headers ["X-MyApp-Auth"]}} h)` — header carrier; unions onto the immutable built-in header denylist
- `(rf/reg-fx :rf.http/managed {:carriers {:query-params ["my_token"]}} h)` — query-param carrier; unions onto the built-in query-param denylist
- `{:rf.http/managed {:decode <malli-schema-with-:sensitive?-props>}}` — per-slot response-body classification
- `{:rf.http/managed {:sensitive? true ...}}` — per-call opt-in (body redaction + ALL params scrubbed)

### Runtime config — epoch redact hook

- `(rf/configure! {:epoch-history {:redact-fn (fn [record] ...)}})` — single-pass record-in / record-out hook at the epoch boundary.

---

## The composition order (data-flow)

The single most-asked question this doc answers: **what runs when, in what order, between handler exit and off-box wire?** The order is fixed and documented in pieces across 009 / 014 / 015 / Tool-Pair — this section pins it in one place.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  1. HANDLER BODY runs with REAL VALUES                                      │
│     - Event handler sees the raw event arg-map (via :event coeffect)        │
│     - Cofx values, app-db reads, fx args — all unredacted                   │
│     - This is by design — the handler MUST see real values to do its job   │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  2. EVENT-PAYLOAD SCRUB during trace-event build                            │
│     - The router's internal redaction interceptor stashes a scrubbed copy   │
│       at :rf/redacted-event for every handler whose registration-owned      │
│       :sensitive paths (or a frame-sensitive app-db path the slice          │
│       overlaps) match the event payload.                                    │
│     - (The public positional `redact-interceptor` is REMOVED per EP-0015    │
│       §7; the underlying re-frame.privacy/redact-interceptor survives as    │
│       internal router plumbing only — registration-owned :sensitive is the  │
│       public route.)                                                        │
│     - Trace assembly reads :rf/redacted-event (not :event) when building    │
│       :rf.event/* and :rf.event/db-changed tag shapes.                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  3. SPEC 015 CLASSIFICATION PROJECTION (classification/project-trace-event) │
│     - The trace bus chokepoint walks :tags for per-registration marks       │
│       declared at reg-time (`:sensitive [paths]` on the registration meta). │
│     - Substitutes :rf/redacted at sensitive paths, :rf.size/large-elided    │
│       at large paths inside the per-tag shape (events under :event, fxs    │
│       under :fx-args, cofx under :coeffects, subs under :value, machines   │
│       under :before / :after / :snapshot).                                  │
│     - Sub-output propagation table consulted: a sub reading any sensitive   │
│       app-db path yields a sensitive output (footgun prevention).           │
│     - Stamps :sensitive? true at the top level of the trace event.          │
│     - Gated on `re-frame.interop/debug-enabled?` — production CLJS bundles  │
│       DCE this away.                                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  4. HTTP-SPECIFIC REDACTION (re-frame.http.privacy/prepare-emit-tags)       │
│     - For :rf.http/* trace events only.                                     │
│     - `redact-headers` walks the :headers map, replaces values whose name   │
│       is in the header denylist with :rf/redacted (unconditional — denyy-  │
│       listed names are the signal).                                         │
│     - `redact-url-query-string` walks the :url string, replaces query-      │
│       param values whose name is in the query-param denylist (unconditional).│
│     - When `:sensitive? true` is the per-call flag: also scrubs :body and   │
│       ALL params (broader than the denylist).                               │
│     - `:sensitive? true` stamped on the trace event when ANY scrub fired    │
│       (denylist hit OR per-call opt-in OR upstream from path-mark).         │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  5. TRACE-BUS EMIT — every listener receives the redacted, stamped event    │
│     - Dev-only listeners (Xray, story recorder, dev panels): consult       │
│       :sensitive? at top level; on-box dev panels render an opaque indicator│
│       and require `:trace/show-sensitive? true` to reveal.                  │
│     - Always-on error-emit substrate listeners (production-survivable):     │
│       consult :sensitive? and drop the whole event by default at off-box    │
│       egress (Sentry/Honeybadger/Datadog forwarders).                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  6. EPOCH ASSEMBLY (re-frame.epoch/build-record)                            │
│     - Per-frame, on drain-settle.                                           │
│     - sensitive-rollup computes :rf.epoch/sensitive? from the raw record's  │
│       schema-declared sensitive leaves at build-time.                       │
│     - The RAW record is appended to the ring and delivered to every         │
│       register-epoch-listener! listener UNMUTATED — storage-side mutation   │
│       is REMOVED (EP-0015 issue 6): epoch records are EP-0010 causal replay │
│       material, mutating them at rest corrupts the replay contract.         │
│     - The :redact-fn runs at step 7 (off-box egress), not here.             │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  7. OFF-BOX PROJECTION (rf/project-egress → rf/projected-record →           │
│     rf/elide-wire-value)                                                    │
│     - `project-egress` is the record-level boundary primitive; it           │
│       delegates per tree-shaped slot to `elide-wire-value` (the value       │
│       walker, single emission site for :rf/redacted + :rf.size/large-       │
│       elided) under the frame's classification + the boundary's             │
│       :rf.egress/* profile.                                                 │
│     - `projected-record` (epoch) strips raw :db-before / :db-after; THEN    │
│       applies the projection-side :redact-fn advanced override (after the   │
│       frame/profile projection, never at storage time).                     │
│     - The structured :effects rows' :args (raw fx-handler payload, not      │
│       app-db-rooted so the walker cannot prove it safe) FAIL CLOSED to      │
│       :rf/redacted off-box, lifted only by :include-fx-args? true.          │
│     - `elide-wire-value` walks tree-typed payloads; consults the per-frame  │
│       [:rf.runtime/elision :declarations] +                                 │
│       [:rf.runtime/elision :sensitive-declarations] (frame-sourced, plus    │
│       schema-sourced migration-import entries — union at lookup time).      │
│     - Composition rule: sensitive drop WINS over large elision when both    │
│       apply at the same path (the size marker would otherwise leak :path /  │
│       :bytes / :digest).                                                    │
│     - Default `{:include-sensitive? false :include-large? false}` —         │
│       maximum elision unless the caller explicitly opts in.                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  8. MCP TOOL EGRESS (tools/mcp-base/sensitive + elision)                    │
│     - Cross-MCP filter that runs on every tool response payload.            │
│     - `strip-sensitive` returns [kept dropped-count]; populates the         │
│       `:dropped-sensitive` envelope counter (omitted when zero).            │
│     - `:elided-large` envelope counter sums the `:rf.size/large-elided`     │
│       substitutions.                                                        │
│     - The counters ride alongside the payload as unqualified keys so the    │
│       calling agent recognises filtering without re-inferring from absence. │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Rule summary

- **Composition is additive at every site.** A commit-plane `:sensitive` classification effect (durable app-db) and an owner-local schema `:sensitive?` prop that resolve to the same path both redact at the same observation surface — they union.
- **Sensitive wins over large at the same path.** [015 §`:rf/redacted {:bytes N}`](015-Data-Classification.md#rfredacted-bytes-n--sensitive--large-composed) and [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). The sensitive drop suppresses the size marker because the marker carries `:path` / `:bytes` / `:digest` which would themselves leak.
- **HTTP denylists are upstream of the trace stream.** They run inside `prepare-emit-tags` / `prepare-emit-failure` *before* `trace/emit!` fires — they shape the trace event itself, not its downstream consumers. Per [Spec 014 §Privacy](014-HTTPRequests.md).
- **Real values are never redacted mid-handler.** The router stashes a scrubbed *copy* at `:rf/redacted-event`; the handler body continues to read the unredacted `:event` coeffect. Projection happens at the observation/egress boundary *after* the handler returns, never before.
- **Production has one live path: the always-on error-emit substrate.** Everything else (dev trace bus, epoch ring, schema-validation traces, Xray) elides via `goog.DEBUG`. The error substrate honours `:sensitive?` *in production* — that's the load-bearing case for substrate-level enforcement.
- **runtime-db is redacted/omitted off-box by default (EP-0001, Mike ruling #14).** Off-box egress of frame-state — the epoch `projected-record` / `projected-history` pair, Xray-MCP, and pair recorders — redacts or omits the **runtime-db** side of frame-state by default; the off-box default fails closed. Only the app-db partition (subject to its own `:sensitive?` / `:large?` elision) and explicitly allowlisted serializable runtime-db facts cross the wire. The SSR hydration payload likewise ships only the serializable runtime-db facts the client needs to reconstitute (machine snapshots, route slice, elision declarations, SSR metadata — per [011 §The `:rf/hydrate` event](011-SSR.md#the-rfhydrate-event)), never transient runtime side-channel state. A **trusted-local** tool may request richer runtime-db diagnostics explicitly (the same opt-in shape as `:include-sensitive?` for app-db); **off-box / AI / log** egress fails closed. This is the runtime-db peer of the app-db `:sensitive?` default: app-db redacts at marked paths, runtime-db redacts/omits wholesale unless explicitly opted in. The normative statement (including the elision-declarations-live-in-runtime-db corollary) is [009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces).

---

## Recordable coeffects must exclude secrets

Recordable coeffects fold host facts into durable frame-state — values written into the `:rf.cofx` envelope map and re-presented verbatim by replay (the discipline: *durable state folds facts, never reads*; see [002 §The recordable-coeffect rule](002-Frames.md#the-recordable-coeffect-rule-the-durable-write-rule), [EP-0017](../docs/EP/EP-0017-recordable-coeffects.md)). A recordable coeffect is durable — it lands in every epoch record, replay fixture, and exported trace. That durability **is the threat model for credentials**:

> **Crypto-grade randomness, tokens, nonces, session ids, and key material MUST NOT be minted or carried as recordable coeffects.** Recording a secret does not make it safe; it makes it durable — copied into every recording, fixture, and exported trace. Secrets are generated at the edge and handled by guarded runtime mechanisms, **off the ledger**.

The rule is a normative review discipline, not a structural guarantee: app-owned `reg-cofx` suppliers mint whatever value they return, so the framework cannot prove a value is not a secret. The enforcement surface is this guide's secrets material plus a recommended lint (a handler that writes durable state declaring an *ambient*-grade id — "durable state folds facts, never reads", mechanically checkable). See [EP-0017 §Security Considerations](../docs/EP/EP-0017-recordable-coeffects.md#security-considerations).

**Projection composes the same way for cofx values as for event payloads.** Each `:rf.cofx` leaf follows the **same projection / redaction rules as event-arg values** — classify the cofx's value shape per-slot (`reg-cofx` `:sensitive` / `:large`, or the cofx's registered `:schema` props), and the off-box projection redacts it like any other transient payload. The framework's one built-in recordable fact, **`:rf/time-ms`**, is classified **always-safe** and never redacted. So the cofx surface has two complementary obligations:

1. **Off-box egress redaction** — a recordable coeffect that *is* legitimately sensitive (e.g. a non-secret-but-private fact a fold needs) is classified and projected like any transient payload; it is never shipped raw off-box.
2. **The secrets exclusion** (above) — a *secret* must not be a recordable coeffect at all, because redaction does not undo durability: the raw value still lives in the on-box ring, the local epoch, and any trusted-local raw read. The two rules are not substitutes — exclusion is the load-bearing rule for credentials; projection is the safety net for the merely-private.

The analogous obligation already holds for [resource scopes / params](#inventory-by-declaration-source) (classified, projected — but not a place to put a secret) and for [HTTP response bodies](#http-carriers-rfhttpmanaged-registration--quick-reference) (fail-closed when unschematized). Recordable coeffects extend that posture to the input-fold side.

---

## Display sentinels

Per [015 §Display contract](015-Data-Classification.md#the-display-contract--sentinels) and [API.md §wire-elision walker](API.md#elide-wire-value-the-wire-boundary-walker):

| Sentinel | When | Drillable? |
|---|---|---|
| `:rf/redacted` (opaque keyword) | Sensitive content. Carries no information about the underlying value — not its type, not its size, not a hash, not a prefix. | **No.** A tool that offers a "show original" affordance against `:rf/redacted` is non-conformant. |
| `:rf.size/large-elided {:path [...] :bytes N :type :map :hint "..." :handle [:rf.elision/at path] :digest "sha256:..." (when `:include-digests?` true)}` | Large content; size diagnostic without leaking content. The `:hint` rides from the schema's `:hint` prop. | **Yes** for on-box panels with size-confirmation modal; **no** for off-box egress by default. |
| `:rf/redacted` at a path *also* marked large | Sensitive + large composed — sensitive wins. The size marker is suppressed entirely (the marker payload would leak `:path` / `:bytes`). | **No.** (Per [015 §`:rf/redacted {:bytes N}`](015-Data-Classification.md#rfredacted-bytes-n--sensitive--large-composed) — the Spec contemplates a `:rf/redacted {:bytes N}` composed form preserving the size diagnostic; the CLJS reference currently suppresses the marker entirely. Both are conformant — readers should not depend on `:bytes` being present alongside `:rf/redacted`.) |

All three sentinel keywords are framework-reserved per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — apps MUST NOT use them as legitimate payload values.

---

## Config knobs

The two verb families that decide whether a sensitive value passes through a consumer. The verb encodes the trust boundary. Per [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress):

| Verb | Where | Default | Trust boundary |
|---|---|---|---|
| `:rf.privacy/show-sensitive?` | On-box devtools panels (Xray, Story trace panel) — set via each tool's `configure!`, e.g. `(xray-config/configure! {:rf.privacy/show-sensitive? true})`. Reads back via `(re-frame.privacy/get-show-sensitive)`. Per — the `:rf.privacy/*` namespace is the cross-tool reservation (every re-frame2 tool that consumes the trace bus reads the same atom; one config flip covers every tool). | `false` (suppress) | The panel is for the operator running this process; toggle controls UI visibility, not egress. |
| `:include-sensitive?` / `:rf.size/include-sensitive?` | Off-box wire egress (MCP servers, hosted-LLM preload, error monitors, Datadog/Sentry forwarders) | `false` (suppress) | The toggle controls whether sensitive values cross the process trust boundary. |

Both default to suppress per Spec 009's default-private posture. A sixth consumer adding a knob picks the verb by trust-boundary class — on-box panel → `show-sensitive?`; off-box wire → `include-sensitive?`.

### Configure-keys that touch privacy

Per [API.md §Configure keys](API.md) and [015](015-Data-Classification.md):

| `(rf/configure! {<key> {...}})` | Privacy-relevant opt | Default | Purpose |
|---|---|---|---|
| `:elision` | `:rf.size/threshold-bytes N` | `16384` | Wire-elision size cap. Non-negative integer; 0 disables runtime auto-detect (only declared / schema-marked entries elide). |
| `:epoch-history` | `:redact-fn fn` | `nil` | **Projection-side** advanced override — runs at off-box egress (inside `projected-record`, after the frame/profile projection), never at storage (EP-0015 issue 6). See [Tool-Pair §Redaction hook](Tool-Pair.md). |
| `:epoch-history` | `:depth N` / `:trace-events-keep N` | depth `50`, trace-events-keep `nil` | Bounds the ring (doesn't redact; bounds the surface). |

---

## Indicator slots

Counters that ride alongside MCP tool responses so the calling agent knows the payload was filtered, without re-inferring from absence. Per [Conventions §Reserved indicator slots](Conventions.md#reserved-indicator-slots-mcp-shaped-returns):

| Slot | Meaning | Where | Owner |
|---|---|---|---|
| `:dropped-sensitive` | Integer count of leaves the walker dropped because they matched `:sensitive? true`. Omit when zero. | MCP response envelope (unqualified key) | Cross-MCP convention |
| `:elided-large` | Integer count of leaves replaced with the `:rf.size/large-elided` marker. Omit when zero. | MCP response envelope (unqualified key) | Cross-MCP convention |
| `[● REDACTED N]` / `[● ELIDED N]` | Panel-chrome mirror of the MCP slots for on-box surfaces (Xray, story panel) | Panel chrome (not JSON) | [Conventions §Reserved panel-chrome surface](Conventions.md) |

The walker also emits a top-level `:rf.epoch/redacted-modified-paths-count` on `:rf/epoch-record` values when the `:redact-fn` substituted at non-schema-declared paths — apps can detect "the redact-fn touched these many slots" without re-walking.

---

## Worked example — password in app-db + token header on HTTP

Finding #8's canonical question: *"I have a `:password` field in `app-db` and a `:token` header on an HTTP request — what do I declare where to keep both out of off-box egress?"*

```clojure
;; 1. Declare the durable app-db classification via COMMIT-PLANE EFFECTS
;;    (EP-0025) — a handler returns `:sensitive` (a vector of `:rf/path`
;;    vectors) alongside `:db`; the classification commits WITH the db write
;;    into the per-frame elision registry. Classify a path before any value
;;    lands there (value-independent); an init event is the natural home.
;;    This replaces the removed frame `:sensitive {:app-db …}` annotation and
;;    the add-marks / set-marks API. (App-specific HTTP carriers ride the
;;    :rf.http/managed registration — see step 3; the observability sink
;;    policy still lives on the frame map.)
(rf/reg-event :app/init-classification
  (fn [{:keys [db]} _]
    {:db db
     :sensitive [[:auth :password]
                 [:auth :token]
                 [:auth :refresh-token]
                 [:user :ssn]]}))

;; App-specific HTTP carrier names ride the :rf.http/managed registration's
;; :carriers block (EP-0025 §HTTP carriers). The frame map carries only the
;; concerns it still owns (e.g. observability sink policy).
(rf/reg-fx :rf.http/managed
  {:carriers {:headers ["X-MyApp-Session"]}}
  re-frame.http.managed/managed-handler)

;; 2. Declare the event-arg-side mark on the login handler — the password
;;    arrives in the event arg-map before it lands in app-db.
(rf/reg-event :auth/log-in
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password totp-code]}]]
    ;; The handler sees real password / totp-code values.
    ;; The trace event sees [:auth/log-in {:email "..."
    ;;                                     :password :rf/redacted
    ;;                                     :totp-code :rf/redacted}].
    {:fx [[:rf.http/managed
           {:method     :post
            :url        "/api/login"
            :body       {:email email :password password}
            :sensitive? true     ;; per-call opt-in — body + ALL params scrubbed
            :on-success [:auth/log-in-success]
            :on-failure [:auth/log-in-failure]}]]}))

;; 3. The app-specific auth-token header carrier `X-MyApp-Session` was
;;    declared on the :rf.http/managed registration's :carriers block above.
;;    The built-in defaults already cover `authorization` / `x-api-key` /
;;    `cookie` / `set-cookie`; the carrier unions onto those immutable
;;    defaults (EP-0025 §HTTP carriers).

;; 4. The on-success event receives the JWT in the response payload. Mark
;;    its event arg so the trace surface sees :rf/redacted there too.
(rf/reg-event :auth/log-in-success
  {:sensitive [[:jwt] [:refresh-token]]}
  (fn [{:keys [db]} [_ {:keys [jwt refresh-token user]}]]
    ;; Writing the JWT into app-db [:auth :token] — the commit-plane
    ;; `:sensitive` classification from step 1 (standing in the per-frame
    ;; registry) means downstream Xray renders the path as :rf/redacted.
    ;; Classification does NOT propagate: a sub/flow reading this path does
    ;; not auto-inherit — classify the derived output path too (see step 5).
    {:db (-> db
             (assoc-in [:auth :token] jwt)
             (assoc-in [:auth :refresh-token] refresh-token)
             (assoc-in [:user :id] (:id user)))}))

;; 5. (Optional) — a subscription reading from a sensitive path does NOT
;;    inherit its input's classification (EP-0025: no propagation, no taint).
;;    A derived prefix is a NEW value at a new output path — it ships raw
;;    unless YOU classify the output. There is nothing to "declassify": if a
;;    sanitised derived value is safe, simply do not classify it; if a derived
;;    value is itself sensitive, declare the sub's own output path `:sensitive`:
(rf/reg-sub :auth/token-prefix
  :<- [:db/auth]
  (fn [auth _] (str (subs (:token auth) 0 8) "...")))   ;; sanitised — ships raw

;; 6. (Optional) — install a PROJECTION-SIDE epoch redact-fn for
;;    defence-in-depth redaction of slots no classification covered (raw
;;    exception messages, custom :trace-events slots). EP-0015 issue 6: the
;;    hook runs at off-box EGRESS (inside projected-record, after the
;;    frame/profile projection), NEVER at storage — the in-process ring
;;    stays raw (causal replay material).
(rf/configure! {:epoch-history
  {:redact-fn (fn [record]
                ;; Scrub :exception-message on any captured trace event.
                (update record :trace-events
                        #(mapv (fn [ev]
                                 (cond-> ev
                                   (= :error (:op-type ev))
                                   (update :tags dissoc :exception-message)))
                               %)))}})
```

**What every observation surface sees after the drain settles:**

| Surface | Observation |
|---|---|
| Handler body (`:auth/log-in`) | Real password value in `:event` coeffect (via the regular handler arg) |
| Trace bus `:rf.event/dispatched` | `[:auth/log-in {:email "..." :password :rf/redacted :totp-code :rf/redacted}]`, top-level `:sensitive? true` |
| Trace bus `:rf.fx/handled` for `:rf.http/managed` | `:rf.fx/args` body and params scrubbed (per-call `:sensitive? true`); `:headers` `X-MyApp-Session` value `:rf/redacted` (denylist hit) |
| Trace bus `:rf.event/db-changed` | `[:auth :token]` slot renders `:rf/redacted` (the commit-plane `:sensitive` classification effect that classified `[:auth :token]`) |
| Xray App-DB Diff panel | Same as above (Xray projects via `project-egress` under `:rf.egress/local-redacted`, consulting the same frame classification) |
| MCP `get-app-db` tool response | `:rf/redacted` at the marked slots (projected under `:rf.egress/off-box-tool`); `:dropped-sensitive N` envelope counter set to the count of dropped leaves |
| Off-box log shipper (Datadog/Sentry) | Routed by frame `:observability` under `:rf.egress/off-box-observability`; drops the whole `:rf.event/dispatched` and `:rf.fx/handled` events (top-level `:sensitive? true`); ships the structural skeleton only |
| Always-on error-emit substrate (production survives) | The error record carries `:sensitive? true` and the listener-side projection honours it before egress to Sentry |
| Epoch `projected-record` | All of the above redactions plus the projection-side `:redact-fn`'s extra scrub (applied at egress, never at storage); the structured `:effects` rows' `:args` fail closed to `:rf/redacted` off-box (lifted only by `:include-fx-args? true`); the in-process ring + listener fan-out see the RAW record |

**What's NOT covered by this declaration set:**

- An `ex-info` message that interpolates the password into the string (`(throw (ex-info (str "User " email " failed login") {...}))`) — the path walker can't resolve into a string substring. See [§Author guidance — the exception-path residual](#author-guidance--the-exception-path-residual) below.
- An `ex-data` map whose author-chosen key name (`{:user/email "..."}`) has no relationship to the path-marked declarations. Substitute `:rf/redacted` at the assembly site, or omit the key.

---

## Author guidance — the exception-path residual

Classification declarations are projected at the **six observation boundaries** named above. Projection walks known data shapes; it does NOT walk exception messages or `ex-data` map keys. The residual surface — *the handler read a sensitive value AND threw with that value in `ex-message` or `ex-data`* — is author responsibility. Per [015 §Author guidance for the exception-path residual](015-Data-Classification.md#author-guidance-for-the-exception-path-residual) and [Security §Author guidance for exceptions under path-level `:sensitive?`](Security.md#author-guidance-for-exceptions-under-path-level-sensitive):

| Anti-pattern | Preferred |
|---|---|
| `(throw (ex-info (str "User " email " failed login") {:user/email email :reason :invalid-credentials}))` — leaks email into `:exception-message` and `:exception-data` | `(throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))` — name the category in the message; correlate via `:dispatch-id` against the (correctly redacted) `:db-before` snapshot |
| Author-named `ex-data` keys carrying the sensitive value | Substitute `:rf/redacted` at the assembly site, or omit the key entirely |

The framework does NOT ship a `safe-throw` helper — *which ex-data keys correspond to sensitive paths in this specific app* is app-level knowledge. A twelve-line per-app `safe-throw` helper is the recommended shape; worked example at [docs/core §24.08 — Exceptions under `:sensitive?`](../docs/core/how-to/configure-dev-and-prod.md).

---

## Removed surfaces

Surfaces removed from this matrix. Listed here so readers don't search for them in v1.

| Surface | Replacement |
|---|---|
| `add-marks` / `set-marks` (public app-db path-mark API) | Durable app-db egress policy is declared by the **four commit-plane effects** (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`, returned by a handler alongside `:db`), not a post-creation imperative mutation. The marks API and `marks.cljc` namespace are gone; the projection substrate lives in the marks-free elision engine. |
| `declare-sensitive-header!` / `declare-sensitive-query-param!` (and `clear-*!`) | App-specific HTTP carrier names belong on the **`:rf.http/managed` `reg-fx` registration** (`:carriers {:headers […] :query-params […]}`), union onto the immutable built-in defaults — not process-global mutation and not a frame annotation. |
| frame `:sensitive {:http …}` carrier block | A `reg-frame` carries no `:sensitive {:http …}` carrier block (the whole `:sensitive` frame key is gone). App-specific HTTP carrier names ride the `:rf.http/managed` `reg-fx` registration's `:carriers` block (the transient-payload case). A `reg-frame` `:sensitive` is rejected fail-loud. |
| `redact-interceptor` (public positional interceptor) | Registration-owned `:sensitive` classifies event payload paths; centralized `project-egress` projects at egress. `re-frame.privacy/redact-interceptor` survives as internal router plumbing only (not façade-published). |
| Schema-attached `:sensitive?` / `:large?` as the public **app-db** classification route | Schemas describe shape; durable app-db egress policy rides the four commit-plane effects. Per-slot props remain the *one* route for owner-local schema'd data (machine / resource / HTTP-body), not a second route for durable app-db. The schema→registry hydrators (`populate-elision-from-schemas!` / `populate-sensitive-from-schemas!`) are removed. |
| `inject-cofx` (public cofx-injection interceptor) | Coeffect dependencies are declared with `:rf.cofx/requires` registration metadata; `reg-cofx` is value-returning + graded. `inject-cofx` is removed (calling it is the hard error `:rf.error/inject-cofx-removed`). Named here because cofx values are a classification surface. |
| Handler-meta `:sensitive?` registration flag | Use Spec 015 per-path declarations. A handler that is the unit of sensitivity (the rare "this whole run is sensitive" case) re-expresses by declaring the path-marks that the handler reads / writes. |
| `:rf.fx/sensitive-mode` configure key | Use per-call `{:sensitive? true}` on `:rf.http/managed` args. The name `set-trace-redaction-policy` is not in `re-frame.core`. |
| `rf/safe-throw` framework helper | Author-level concern; per-app helpers fit the local convention better than a framework default. Worked-example shape lives in the docs/core. |

---

## Cross-references

### Primary contract owners

- [015-Data-Classification](015-Data-Classification.md) — the normative spec for the EP-0015 owner-classification + `project-egress` + `:rf.egress/*` model. The Spec; this doc is the cross-artefact index.
- [EP-0015 (Frame-Owned Egress Policy)](../docs/EP/EP-0015-frame-owned-egress-policy.md) — the final proposal Spec 015 graduates (the twelve dispositioned open issues are the rationale record).
- [EP-0017 (Recordable Coeffects)](../docs/EP/EP-0017-recordable-coeffects.md) — the recordable-coeffect surface and its secrets-exclusion §Security Considerations (see [§Recordable coeffects must exclude secrets](#recordable-coeffects-must-exclude-secrets)).
- [009-Instrumentation §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the canonical trace-surface privacy posture: `:sensitive?` top-level stamp, consumer-side default-drop, the always-on error-emit substrate's posture.
- [009-Instrumentation §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) — the size-elision peer of sensitive marking.
- [010-Schemas §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces) and [010-Schemas §`:large?`](010-Schemas.md#large--schema-driven-size-elision-nomination) — per-slot schema props for owner-local schema'd data and schema-validation error-trace redaction.
- [014-HTTPRequests §Privacy](014-HTTPRequests.md) — HTTP-specific denylists, the `:rf.http/managed` `:carriers` block, and the per-call `:sensitive?` request arg.
- [Tool-Pair §Time-travel — Redaction hook](Tool-Pair.md) — the projection-side `:redact-fn` config key on `(rf/configure! {:epoch-history ...})`; the `projected-record` / `projected-history` off-box egress pair.
- [Tool-Pair §Direct-read privacy posture](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — the MCP wire-egress contract for direct-read tools.

### Cross-cutting conventions

- [Conventions §Reserved namespaces (framework-owned)](Conventions.md#reserved-namespaces-framework-owned) — the `:rf/`, `:rf.size/`, `:rf.elision/` namespaces this surface reserves.
- [Conventions §Reserved indicator slots (MCP-shaped returns)](Conventions.md#reserved-indicator-slots-mcp-shaped-returns) — `:dropped-sensitive`, `:elided-large` envelope counters.
- [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress) — `show-sensitive?` (on-box) vs `include-sensitive?` (off-box) verb split.
- [Security §Privacy / secret handling](Security.md#privacy--secret-handling) — the pattern-level threat model and the behavioural MUSTs.

### Implementation cross-references

- [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) — cross-MCP `sensitive-event?` / `strip-sensitive` / `scrub-snapshot` walkers and the `:include-sensitive` arg vocabulary (the unqualified MCP wire key; the `?` is retained only on the internal `:rf.size/include-sensitive?` walker option and the config-knob verb).
- [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md) — cross-MCP elision walker + the `:include-large?` arg vocabulary.

### API.md projection

- [API.md §wire-elision walker](API.md#elide-wire-value-the-wire-boundary-walker) — `elide-wire-value`, `project-egress`.
- [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) — `sensitive?`, `redact-interceptor`.
- [API.md §Configure keys](API.md) — the four `(rf/configure! ...)` keys, including `:elision` and `:epoch-history`.

### Author-side guide

- [docs/core §23a — Privacy: keeping secrets out of traces](../docs/core/how-to/keep-secrets-out-of-traces.md) — guide-side worked-example tour for declaring `:sensitive?` on schema slots.
- [docs/core §23b — Large blobs](../docs/core/how-to/keep-secrets-out-of-traces.md) — guide-side companion for `:large?` declarations.
- [docs/core §24.07 — Privacy and elision in practice](../docs/core/how-to/configure-dev-and-prod.md) — operational config walkthrough.
- [docs/core §24.08 — Exceptions under `:sensitive?`](../docs/core/how-to/configure-dev-and-prod.md) — the per-app `safe-throw` convention and the three patterns for the exception-path residual.
