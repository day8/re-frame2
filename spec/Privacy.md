# Privacy & Data-Classification — cross-artefact reference

> **Type:** Reference
> **Normative status:** Supporting companion. Defers to [009-Instrumentation](009-Instrumentation.md), [010-Schemas](010-Schemas.md), [014-HTTPRequests](014-HTTPRequests.md), [015-Data-Classification](015-Data-Classification.md), [Tool-Pair](Tool-Pair.md), [Conventions](Conventions.md), and [Security](Security.md) for every contract surface named here. This doc is the **discoverability index** — one place to land for "where do privacy primitives live across re-frame2's artefacts, what is the composition order, and what do I declare to keep a value out of off-box egress?"

re-frame2's privacy surface is the **leak-prevention overlay on observability**. Real data flows through events / cofx / handlers / fx / app-db / subs / views unchanged; sentinel substitution happens **only at the observation boundary**. The contract spans five artefacts (`re-frame.core`, `re-frame.http`, `re-frame.schemas`, `re-frame.epoch`, `tools/mcp-base`) and four declaration sources (Spec 015 path-marks, Spec 010 schema metadata, Spec 014 HTTP denylists, Spec 009 handler-meta — removed) — this doc gathers them into one inventory and pins the composition order.

> **Posture (per Spec 015 §Posture).** Privacy here is observability hygiene, not authorisation. Apps still own auth, authorisation, encryption-at-rest, and transport security. The classification machinery exists so that the framework's own dev-time observability surfaces (and their downstream consumers — log sinks, AI agents, dashboards) cannot accidentally exfiltrate user secrets or stuff log lines with multi-megabyte blobs. See [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the pattern-level threat model.

---

## Table of contents

- [The five observation boundaries](#the-five-observation-boundaries)
- [Inventory by artefact](#inventory-by-artefact) — every imperative + declarative entry point, grouped by owning namespace
- [Inventory by declaration source](#inventory-by-declaration-source) — same surfaces, grouped by where the author declares the mark
- [The composition order (data-flow)](#the-composition-order-data-flow) — what runs when, from handler exit to off-box wire
- [Display sentinels](#display-sentinels) — what observation surfaces render
- [Config knobs](#config-knobs) — the two verb families and the configure-keys
- [Indicator slots](#indicator-slots) — what observers expose so callers know the payload was filtered
- [Worked example](#worked-example--password-in-app-db--token-header-on-http) — the canonical case Finding #8 names
- [Author guidance — the exception-path residual](#author-guidance--the-exception-path-residual)
- [Removed surfaces](#removed-surfaces)
- [Cross-references](#cross-references)

---

## The five observation boundaries

Privacy declarations exist to stop leaks at every observation surface the framework owns or participates in. Per [015 §Scope](015-Data-Classification.md#in-scope--the-five-observation-points-marks-must-guard) the complete set:

| # | Boundary | What sees it | Production-elided? |
|---|---|---|---|
| 1 | **Trace-bus emit** — every `:rf/trace-event` built by `emit!` / `emit-error!` | Trace listeners, Causa panel, error monitors, log sinks | Yes — gated on `re-frame.interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`) for the dev-only stream; the always-on **error-emit substrate** ([009 §Error-emit substrate](009-Instrumentation.md)) survives production |
| 2 | **Causa panels** — Event Detail, App-DB Diff, Subscriptions, Trace, Causality Graph, Machine Inspector, Flow Panel | The on-box dev tool; CLJS-only | Yes — Causa is dev-only |
| 3 | **MCP wire transport** — `tools/re-frame2-pair-mcp`, `tools/story-mcp`, any future MCP server | Off-box LLM consumers | N/A (tooling, not shipped in the production bundle) |
| 4 | **AI / LLM context lifted by tools** — any code path that lifts trace events / app-db / sub outputs / machine `:data` into an LLM prompt | The hosted LLM endpoint | N/A |
| 5 | **Third-party log sinks** — Datadog, Sentry, LogRocket, Honeybadger, custom log fan-outs | Off-box ops/monitoring | The always-on error-emit substrate is **the live path** here — it survives `goog.DEBUG=false`, so sensitive-stamping MUST work in production builds. Per Spec 015 §Hot-path cost the trace bus is dev-only; the error substrate is not. |

The contract for boundaries 3 / 4 / 5 is **default-drop the stamped event**: when a trace event carries `:sensitive? true` at the top level, the forwarder MUST drop the whole event before egress. Apps explicitly opt back in by passing `{:include-sensitive? true}` (off-box wire) or `{:show-sensitive? true}` (on-box panel). See [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress).

---

## Inventory by artefact

The complete imperative + declarative surface, grouped by owning namespace. Every entry's normative owner lives in the cited Spec section; this table is the index, not the contract.

### `re-frame.core` (production-survivable subset re-exported from artefacts below)

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `:sensitive` | reg-meta key | Vector of paths into the registration's primary data shape (event arg-map, fx-input map, cofx-injection, machine snapshot, sub output, flow output) | [015 §Seven first-class marking sites](015-Data-Classification.md#the-seven-first-class-marking-sites) |
| `:large` | reg-meta key | Symmetric to `:sensitive` — paths to slots elided with `:rf.size/large-elided` | [015 §3](015-Data-Classification.md#3-subscriptions--reg-sub) |
| `:sensitive?` / `:large?` | reg-meta key (`reg-sub`, `reg-flow`) | Whole-output override (`true` = force-mark, `false` = opt out of propagation) | [015 §3](015-Data-Classification.md#3-subscriptions--reg-sub), [015 §7](015-Data-Classification.md#7-flows--reg-flow) |
| `add-marks` / `set-marks` | registration kinds | Frame-scoped declarations of path-marks against `app-db`. `(rf/add-marks frame-id {path mark, ...})` merges additively; `(rf/set-marks frame-id {path mark, ...})` replaces wholesale. | [015 §2](015-Data-Classification.md#2-app-db-marks-per-frame--add-marks--set-marks) |
| `redact-interceptor` | interceptor factory | `(rf/redact-interceptor paths)` → positional interceptor. Overwrites named event-payload keys with `:rf/redacted` on the **trace surface** before the handler runs; handler body itself sees the unredacted value via `:event` coeffect. | [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) |
| `sensitive?` | predicate | `(rf/sensitive? trace-event)` → bool. True iff the event carries `:sensitive? true` at the top level. The framework-published predicate every forwarder composes against. | [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) |
| `elide-wire-value` | walker | `(rf/elide-wire-value v opts)` → walked `v`. The **single normative emission site** for `:rf/redacted` + `:rf.size/large-elided`. Consumed by every off-box egress. | [API.md §wire-elision walker](API.md#elide-wire-value-the-wire-boundary-walker), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |
| `elision-declarations` | reader | `(rf/elision-declarations frame-id)` → schema-derived `:large?` declarations for the frame. Pair-tool / introspection. | [API.md](API.md), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |
| `populate-elision-from-schemas!` | boot hydrator | Walks the frame's app-schemas and writes `{:large? true :source :schema}` declarations into `[:rf/elision :declarations]`. Idempotent. No-op when schemas artefact absent. | [API.md](API.md), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |
| `populate-sensitive-from-schemas!` | boot hydrator | Symmetric — writes `:sensitive?` slot meta into `[:rf/elision :sensitive-declarations]`. | [010 §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) |
| `(configure :elision ...)` | runtime config | `{:rf.size/threshold-bytes N}` — wire-elision size cap. Default `16384`. | [API.md §Configure keys](API.md) |

### `re-frame.http`

HTTP-specific extensions for header / query-param denylists. These are HTTP-namespace-specific because the HTTP fx maps headers + query-strings into `:rf.http/*` trace events — declaring them here keeps the artefact's default denylists colocated with the consumer. Re-exported from `re-frame.core` through `re-frame.http-managed` only for the headers pair; the query-param pair lives only in `re-frame.http`.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `declare-sensitive-header!` | imperative | Add header name to denylist. Keys lower-cased; case-insensitive lookup. Stored across the process. | [014 §HTTP privacy headers](014-HTTPRequests.md), rf2-bma05 |
| `clear-sensitive-headers!` | imperative | Drop app-declared header names from the denylist (built-in defaults survive). Test fixture. | rf2-bma05 |
| `declare-sensitive-query-param!` | imperative | Add query-param name to denylist. URLs carrying the param value are redacted **inline** in every `:rf.http/*` trace event that carries a `:url` slot, regardless of the request `:sensitive?` flag — the **name is the signal**. | [014 §URL privacy](014-HTTPRequests.md), rf2-2p8wr |
| `clear-sensitive-query-params!` | imperative | Test fixture. | rf2-2p8wr |
| `:sensitive?` (per-call) | request arg | `{:rf.http/managed {:sensitive? true}}` — opts a specific request in. When true, the request **body** is redacted to the sentinel and **all** query params are scrubbed (broader than the denylist). Sugar form: `{:request {:sensitive? true}}`. | [014 §Privacy](014-HTTPRequests.md) |

Built-in denylists ship populated with the obvious cross-app names (`authorization`, `cookie`, `x-api-key`, `set-cookie`, ...; `api_key`, `access_token`, `auth`, `token`, ...) — `(rf/declare-sensitive-header! ...)` extends them for app-specific tokens (`X-MyApp-Auth`).

### `re-frame.schemas` (declarative — no imperative surface)

Schema-attached marks. Apps that already register rich app-schemas via `rf/reg-app-schema` get these for free — the boot hydrators read them.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `:sensitive? true` | schema slot prop | Per-slot Malli property `{:sensitive? true}` on an app-schema slot. Boot-time `populate-sensitive-from-schemas!` walks every registered schema and writes the slot's path into `[:rf/elision :sensitive-declarations]`. Schema-validation error traces also consult the prop (`:value` / `:received` / `:explain` / `:fx-args` / `:query-v` redaction per rf2-kj51z / rf2-adtp2). | [010 §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) |
| `:large? true` | schema slot prop | Symmetric — boot-time `populate-elision-from-schemas!` writes the slot's path into `[:rf/elision :declarations]`. The wire-elision walker substitutes `:rf.size/large-elided` for matching slots at off-box egress. | [010 §`:large?`](010-Schemas.md#large--schema-driven-size-elision-nomination-rf2-nwv63) |

### `re-frame.epoch`

Per-frame epoch snapshots get one privacy hook — the `:redact-fn`. Runs once per assembled record between `build-record` and ring-append / listener fan-out; the ring + every listener see the same redacted shape. The on-box dev consumer sees raw records (`epoch-history`); off-box egress routes through `projected-record` / `projected-history`.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `(configure :epoch-history {:redact-fn fn})` | runtime config | Install a record-in / record-out fn that mutates the assembled `:rf/epoch-record` before ring-append. Failures emit `:rf.warning/epoch-redact-fn-exception` and fall back to the raw record for that drain only — does not break the drain. Production-elided (the whole epoch surface rides `debug-enabled?`). | [Tool-Pair §Redaction hook](Tool-Pair.md), [API.md §Configure keys](API.md) |
| `:rf.epoch/sensitive?` | record-level rollup | Top-level boolean on the assembled `:rf/epoch-record` — true iff any captured trace event in the record had `:sensitive? true`. Computed BEFORE `:redact-fn` runs (so the rollup is preserved even when redact-fn erases the leaves it keyed on). | [Tool-Pair §Time-travel](Tool-Pair.md) |
| `projected-record` | projection fn | `(rf/projected-record record)` — off-box-safe projection of a `:rf/epoch-record`. Strips raw `:db-before` / `:db-after`, runs `elide-wire-value` on captured trace events, keeps the structured fields (`:trigger-event`, `:fx`, `:halt-reason`, `:schema-digest`, `:rf.epoch/sensitive?`, `:rf.epoch/redacted-modified-paths-count`). The **single emission site** for `:rf/redacted` + `:rf.size/large-elided` markers when shipping epoch data off-box. Idempotent. | [Tool-Pair §Direct-read privacy](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) |
| `projected-history` | projection fn | `(rf/projected-history frame-id)` — `(mapv projected-record (epoch-history frame-id))`. Off-box-safe equivalent of `epoch-history`. | [Tool-Pair §Time-travel](Tool-Pair.md) |

### `tools/mcp-base` (cross-MCP wire egress)

The framework-published privacy filter every MCP forwarder composes. Apps don't author against this directly — MCP server implementations do, conforming to the cross-server vocabulary.

| Surface | Kind | Purpose | Owner |
|---|---|---|---|
| `sensitive-event?` | predicate | Conservative predicate over a trace-event map — `true` iff `(:sensitive? ev)` is literal `true`. Mirror of `re-frame.privacy/sensitive?`. | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `strip-sensitive` | walker | `(strip-sensitive coll)` → `[kept dropped-count]`. The `dropped-count` becomes the `:dropped-sensitive` envelope counter on the MCP response. | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `scrub-snapshot` | walker | Snapshot-tree walker — descends into nested registration handles and removes `:sensitive?`-stamped sub-trees (stricter than top-level filtering). | [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) |
| `:include-sensitive?` | cross-MCP wire arg | Per-call opt-in on every MCP tool surfacing trace-like data. Defaults to `false`. Two literal spellings in-flight (story-mcp ships `:include-sensitive` without `?` to satisfy the Anthropic input-schema regex; pair-mcp ships `:include-sensitive?` pending rf2-ihq4d) — treat as policy pin, not literal-spelling pin. | [`tools/mcp-base/spec/sensitive.md` §Cross-server arg-vocabulary](../tools/mcp-base/spec/sensitive.md#cross-server-arg-vocabulary-convention), [Conventions §Privacy config-knob](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress) |
| `:rf.size/large-elided` (elision marker) + `:include-large?` (wire arg) | cross-MCP wire vocabulary | Size-elision peer of `:sensitive?`. The walker substitutes `:rf.size/large-elided {:bytes N :head "..." :handle ...}` at over-threshold or `:large?`-declared slots; off-box callers opt in with `{:include-large? true}`. | [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md), [009 §Size elision](009-Instrumentation.md#size-elision-in-traces) |

---

## Inventory by declaration source

Same surfaces, regrouped by **where the author declares the mark**. This is the angle Finding #8 names — Eight imperative entry points + two declarative across three artefacts.

### Schema-attached (boot-time hydration)

- `{:sensitive? true}` on an app-schema slot → `populate-sensitive-from-schemas!` → `[:rf/elision :sensitive-declarations]` at boot
- `{:large? true}` on an app-schema slot → `populate-elision-from-schemas!` → `[:rf/elision :declarations]` at boot

The two hydrators are idempotent and no-op when the schemas artefact is absent. Schema-derived entries carry `:source :schema` so they survive an `add-marks` / `set-marks` re-call (per [015 §Relationship with schema-attached marks](015-Data-Classification.md#relationship-with-schema-attached-marks)).

### Per-registration declarative (Spec 015 path-marks)

Every `reg-*` accepts `:sensitive` / `:large` (vectors of paths) plus, for subs and flows, `:sensitive?` / `:large?` (whole-output boolean override). Paths root at the kind's primary data shape:

| Reg kind | Path root | Owner |
|---|---|---|
| `reg-event-db` / `reg-event-fx` / `reg-event-ctx` | the event arg-map (second element of `[:event-id {arg-map}]`) | [015 §1](015-Data-Classification.md#1-event-handlers--reg-event-dbfxctx) |
| `reg-sub` | the sub's output value; `:sensitive?` is the whole-output override | [015 §3](015-Data-Classification.md#3-subscriptions--reg-sub) |
| `reg-fx` | the fx-input map | [015 §4](015-Data-Classification.md#4-effects--reg-fx) |
| `reg-cofx` | the injected value (`[[]]` = the whole injection) | [015 §5](015-Data-Classification.md#5-coeffects--reg-cofx) |
| `reg-machine` | the machine snapshot (`[:data :jwt]`, `[:data :user :ssn]`, ...) | [015 §6](015-Data-Classification.md#6-state-machines--reg-machine) |
| `reg-flow` | the flow's `:output` value; `:sensitive?` is the whole-output override | [015 §7](015-Data-Classification.md#7-flows--reg-flow) |

### App-db declarative (dedicated registration kind)

- `(rf/add-marks frame-id {path mark, ...})` — frame-scoped, additively merges into the frame's existing mark-set.
- `(rf/set-marks frame-id {path mark, ...})` — frame-scoped, wholesale replaces the frame's mark-set.

Both write through `[:rf/elision :sensitive-declarations]` + `[:rf/elision :declarations]` keyed by absolute path with `:source :marks` (so they survive schema re-hydration; schema-sourced entries survive an `add-marks` / `set-marks` re-call). Per [015 §2](015-Data-Classification.md#2-app-db-marks-per-frame--add-marks--set-marks).

### Imperative — HTTP denylists

- `(rf/declare-sensitive-header! "X-MyApp-Auth")` / `(rf/clear-sensitive-headers!)` — extends the built-in header denylist
- `(rf/declare-sensitive-query-param! "my_token")` / `(rf/clear-sensitive-query-params!)` — extends the built-in query-param denylist
- `{:rf.http/managed {:sensitive? true ...}}` — per-call opt-in (body redaction + ALL params scrubbed)

### Imperative — interceptor-based scrub

- `(rf/redact-interceptor paths)` — positional interceptor that scrubs named event-payload keys with `:rf/redacted` before the handler runs. The handler body sees the unredacted value via `:event` coeffect; the trace surface sees the scrubbed version via `:rf/redacted-event`. Composes additively with the router's internal schema-redaction interceptor (when both are present, the union of paths is scrubbed).

### Runtime config — epoch redact hook

- `(rf/configure :epoch-history {:redact-fn (fn [record] ...)})` — single-pass record-in / record-out hook at the epoch boundary.

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
│  2. SCHEMA / INTERCEPTOR SCRUB during trace-event build                     │
│     - The router's internal :rf/schema-redaction interceptor stashes a      │
│       scrubbed copy at :rf/redacted-event for every handler whose path-     │
│       scoped slice overlaps a schema-declared sensitive app-db path.        │
│     - User-installed `redact-interceptor` interceptors extend (union, not        │
│       replace) the stashed copy with their declared payload paths.          │
│     - Trace assembly reads :rf/redacted-event (not :event) when building    │
│       :event/* and :event/db-changed tag shapes.                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  3. SPEC 015 PATH-MARK PROJECTION (re-frame.marks/project-trace-event)      │
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
│  4. HTTP-SPECIFIC REDACTION (re-frame.http-privacy/prepare-emit-tags)       │
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
│     - Dev-only listeners (Causa, story recorder, dev panels): consult       │
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
│     - sensitive-rollup computes :rf.epoch/sensitive? from the raw trace     │
│       events captured in this drain (BEFORE redact-fn runs).                │
│     - Installed :redact-fn (from `(rf/configure :epoch-history {...})`)     │
│       runs once on the assembled record. Failures emit a warning and fall   │
│       back to the raw record for that drain only.                           │
│     - Ring-append + listener fan-out see the SAME redacted shape — no later │
│       projection re-derives slots the fn erased.                            │
└─────────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  7. OFF-BOX PROJECTION (rf/projected-record + rf/elide-wire-value)          │
│     - The single emission site for :rf/redacted + :rf.size/large-elided     │
│       at the framework wire boundary.                                       │
│     - `projected-record` strips raw :db-before / :db-after from epoch       │
│       records (which the on-box `epoch-history` reader still surfaces).     │
│     - `elide-wire-value` walks tree-typed payloads; consults the per-frame  │
│       [:rf/elision :declarations] + [:rf/elision :sensitive-declarations]   │
│       (which carries BOTH schema-sourced and app-db-marks-sourced entries — │
│       union at lookup time).                                                │
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

- **Composition is additive at every site.** A path declared via `add-marks` / `set-marks` AND `:sensitive?` on the schema both redact at the same observation surface — they union.
- **Sensitive wins over large at the same path.** [015 §`:rf/redacted {:bytes N}`](015-Data-Classification.md#rfredacted-bytes-n--sensitive--large-composed) and [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). The sensitive drop suppresses the size marker because the marker carries `:path` / `:bytes` / `:digest` which would themselves leak.
- **HTTP denylists are upstream of the trace stream.** They run inside `prepare-emit-tags` / `prepare-emit-failure` *before* `trace/emit!` fires — they shape the trace event itself, not its downstream consumers. Per [Spec 014 §Privacy](014-HTTPRequests.md), rf2-02vzz.
- **Real values are never redacted mid-handler.** The router stashes a scrubbed *copy* at `:rf/redacted-event`; the handler body continues to read the unredacted `:event` coeffect.
- **Production has one live path: the always-on error-emit substrate.** Everything else (dev trace bus, epoch ring, schema-validation traces, Causa) elides via `goog.DEBUG`. The error substrate honours `:sensitive?` *in production* — that's the load-bearing case for substrate-level enforcement (rf2-vnjfg).

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
| `:rf.privacy/show-sensitive?` | On-box devtools panels (Causa, Story trace panel) — set via each tool's `configure!`, e.g. `(causa-config/configure! {:rf.privacy/show-sensitive? true})`. Reads back via `(re-frame.privacy/get-show-sensitive)`. Per rf2-xea9u — the `:rf.privacy/*` namespace is the cross-tool reservation (every re-frame2 tool that consumes the trace bus reads the same atom; one config flip covers every tool). | `false` (suppress) | The panel is for the operator running this process; toggle controls UI visibility, not egress. |
| `:include-sensitive?` / `:rf.size/include-sensitive?` | Off-box wire egress (MCP servers, hosted-LLM preload, error monitors, Datadog/Sentry forwarders) | `false` (suppress) | The toggle controls whether sensitive values cross the process trust boundary. |

Both default to suppress per Spec 009's default-private posture. A sixth consumer adding a knob picks the verb by trust-boundary class — on-box panel → `show-sensitive?`; off-box wire → `include-sensitive?`.

### Configure-keys that touch privacy

Per [API.md §Configure keys](API.md) and [015](015-Data-Classification.md):

| `(rf/configure <key> {...})` | Privacy-relevant opt | Default | Purpose |
|---|---|---|---|
| `:elision` | `:rf.size/threshold-bytes N` | `16384` | Wire-elision size cap. Non-negative integer; 0 disables runtime auto-detect (only declared / schema-marked entries elide). |
| `:epoch-history` | `:redact-fn fn` | `nil` | Per-record redaction hook at the epoch boundary. See [Tool-Pair §Redaction hook](Tool-Pair.md). |
| `:epoch-history` | `:depth N` / `:trace-events-keep N` | depth `50`, trace-events-keep `nil` | Bounds the ring (doesn't redact; bounds the surface). |

---

## Indicator slots

Counters that ride alongside MCP tool responses so the calling agent knows the payload was filtered, without re-inferring from absence. Per [Conventions §Reserved indicator slots](Conventions.md#reserved-indicator-slots-mcp-shaped-returns):

| Slot | Meaning | Where | Owner |
|---|---|---|---|
| `:dropped-sensitive` | Integer count of leaves the walker dropped because they matched `:sensitive? true`. Omit when zero. | MCP response envelope (unqualified key) | Cross-MCP convention |
| `:elided-large` | Integer count of leaves replaced with the `:rf.size/large-elided` marker. Omit when zero. | MCP response envelope (unqualified key) | Cross-MCP convention |
| `[● REDACTED N]` / `[● ELIDED N]` | Panel-chrome mirror of the MCP slots for on-box surfaces (Causa, story panel) | Panel chrome (not JSON) | [Conventions §Reserved panel-chrome surface](Conventions.md) |

The walker also emits a top-level `:rf.epoch/redacted-modified-paths-count` on `:rf/epoch-record` values when the `:redact-fn` substituted at non-schema-declared paths — apps can detect "the redact-fn touched these many slots" without re-walking.

---

## Worked example — password in app-db + token header on HTTP

Finding #8's canonical question: *"I have a `:password` field in `app-db` and a `:token` header on an HTTP request — what do I declare where to keep both out of off-box egress?"*

```clojure
;; 1. Declare the app-db path-mark — either via add-marks / set-marks OR via schema.
;;
;;    Option A (path-mark, declarative, no schema required):
(rf/set-marks :rf/default
  {[:auth :password] :sensitive
   [:auth :token]    :sensitive
   [:user :ssn]      :sensitive})

;;    Option B (schema-attached, when the app already runs schemas):
(rf/reg-app-schema [:auth]
  [:map
   [:password {:sensitive? true} :string]
   [:token    {:sensitive? true} :string]])

;;    The two sources UNION at lookup time. Apps without schemas use A;
;;    apps already running schemas can use B alone, or A + B together for
;;    the cross-cutting paths that no schema covers (e.g. HTTP response
;;    headers landing in :on-success event payloads).

;; 2. Declare the event-arg-side mark on the login handler — the password
;;    arrives in the event arg-map before it lands in app-db.
(rf/reg-event-fx :auth/log-in
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

;; 3. Declare the header denylist for the app-specific auth token name.
;;    The built-in defaults already cover `authorization` / `x-api-key` /
;;    `cookie` / `set-cookie`; the call below adds an app-specific name.
(rf/declare-sensitive-header! "X-MyApp-Session")

;; 4. The on-success event receives the JWT in the response payload. Mark
;;    its event arg so the trace surface sees :rf/redacted there too.
(rf/reg-event-fx :auth/log-in-success
  {:sensitive [[:jwt] [:refresh-token]]}
  (fn [{:keys [db]} [_ {:keys [jwt refresh-token user]}]]
    ;; Writing the JWT into app-db [:auth :token] — the set-marks
    ;; declaration on step 1 means downstream Causa renders the path
    ;; as :rf/redacted. The propagation rule in Spec 015 also marks
    ;; the destination path even without the explicit set-marks call.
    {:db (-> db
             (assoc-in [:auth :token] jwt)
             (assoc-in [:auth :refresh-token] refresh-token)
             (assoc-in [:user :id] (:id user)))}))

;; 5. (Optional) — a subscription reading from a sensitive path inherits
;;    sensitivity by default. Override only if you've sanitised:
(rf/reg-sub :auth/token-prefix
  {:sensitive? false}   ;; the author has asserted the derivation is safe
  :<- [:db/auth]
  (fn [auth _] (str (subs (:token auth) 0 8) "...")))

;; 6. (Optional) — install an epoch redact-fn for any defence-in-depth
;;    redaction of slots no path-mark covered (raw exception messages,
;;    custom slots in :trace-events captured during the drain).
(rf/configure :epoch-history
  {:redact-fn (fn [record]
                ;; Scrub :exception-message on any captured trace event.
                (update record :trace-events
                        #(mapv (fn [ev]
                                 (cond-> ev
                                   (= :error (:op-type ev))
                                   (update :tags dissoc :exception-message)))
                               %)))})
```

**What every observation surface sees after the cascade settles:**

| Surface | Observation |
|---|---|
| Handler body (`:auth/log-in`) | Real password value in `:event` coeffect (via the regular handler arg) |
| Trace bus `:event/dispatched` | `[:auth/log-in {:email "..." :password :rf/redacted :totp-code :rf/redacted}]`, top-level `:sensitive? true` |
| Trace bus `:rf.fx/handled` for `:rf.http/managed` | `:fx-args` body and params scrubbed (per-call `:sensitive? true`); `:headers` `X-MyApp-Session` value `:rf/redacted` (denylist hit) |
| Trace bus `:event/db-changed` | `[:auth :token]` slot renders `:rf/redacted` (set-marks + schema path-mark, plus event-arg propagation from `:auth/log-in-success`) |
| Causa App-DB Diff panel | Same as above (Causa consults the same registry) |
| MCP `get-app-db` tool response | `:rf/redacted` at the marked slots; `:dropped-sensitive N` envelope counter set to the count of dropped leaves |
| Off-box log shipper (Datadog/Sentry) | Drops the whole `:event/dispatched` and `:rf.fx/handled` events (top-level `:sensitive? true`); ships the structural skeleton only |
| Always-on error-emit substrate (production survives) | The error record carries `:sensitive? true` and the listener-side scrub honours it before egress to Sentry |
| Epoch `projected-record` | All of the above redactions plus the `:redact-fn`'s extra scrub; ring-append + listener fan-out see the same shape |

**What's NOT covered by this declaration set:**

- An `ex-info` message that interpolates the password into the string (`(throw (ex-info (str "User " email " failed login") {...}))`) — the path walker can't resolve into a string substring. See [§Author guidance — the exception-path residual](#author-guidance--the-exception-path-residual) below.
- An `ex-data` map whose author-chosen key name (`{:user/email "..."}`) has no relationship to the path-marked declarations. Substitute `:rf/redacted` at the assembly site, or omit the key.

---

## Author guidance — the exception-path residual

The path-marked declarations redact at the **five observation boundaries** named above. They walk known data shapes; they do NOT walk exception messages or `ex-data` map keys. The residual surface — *the handler read a sensitive value AND threw with that value in `ex-message` or `ex-data`* — is author responsibility. Per [015 §Author guidance for the exception-path residual](015-Data-Classification.md#author-guidance-for-the-exception-path-residual) and [Security §Author guidance for exceptions under path-level `:sensitive?`](Security.md#author-guidance-for-exceptions-under-path-level-sensitive):

| Anti-pattern | Preferred |
|---|---|
| `(throw (ex-info (str "User " email " failed login") {:user/email email :reason :invalid-credentials}))` — leaks email into `:exception-message` and `:exception-data` | `(throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))` — name the category in the message; correlate via `:dispatch-id` against the (correctly redacted) `:app-db-before` snapshot |
| Author-named `ex-data` keys carrying the sensitive value | Substitute `:rf/redacted` at the assembly site, or omit the key entirely |

The framework deliberately does NOT ship a `safe-throw` helper — the call-site knowledge of *which ex-data keys correspond to sensitive paths in this specific app* is author knowledge, not framework knowledge. A twelve-line per-app `safe-throw` helper is the recommended shape; worked example at [docs/guide §24.08 — Exceptions under `:sensitive?`](../docs/guide/24-configuration-and-safety/08-exceptions-under-sensitive.md).

---

## Removed surfaces

Surfaces that previously lived in this matrix and have been removed. Listed here so readers don't search for them in v1.

| Surface | Removed by | Why |
|---|---|---|
| Handler-meta `:sensitive?` registration flag | rf2-hjs2d | Coarse (whole-handler scope) when the data was always path-shaped. Replaced by Spec 015 per-path declarations. Handlers that were the unit of sensitivity (the rare "this whole cascade is sensitive" case) re-express by declaring the path-marks that the handler reads / writes. |
| `:rf.fx/sensitive-mode` configure key (audit name) | never landed | Replaced by per-call `{:sensitive? true}` on `:rf.http/managed` args; the audit-era name `set-trace-redaction-policy` was a working-document placeholder that never landed in `re-frame.core`. |
| `rf/safe-throw` framework helper (proposed) | declined | Author-level concern; per-app helpers conform better to the local convention than a framework default. Worked-example shape lives in the docs/guide. |

---

## Cross-references

### Primary contract owners

- [015-Data-Classification](015-Data-Classification.md) — the design spec for path-marked `:sensitive` / `:large` declarations and `add-marks` / `set-marks`. The Spec; this doc is the cross-artefact index.
- [009-Instrumentation §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the canonical trace-surface privacy posture: `:sensitive?` top-level stamp, consumer-side default-drop, the always-on error-emit substrate's posture.
- [009-Instrumentation §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) — the size-elision peer of sensitive marking.
- [010-Schemas §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) and [010-Schemas §`:large?`](010-Schemas.md#large--schema-driven-size-elision-nomination-rf2-nwv63) — schema-attached marks that boot-hydrate into the elision registry.
- [014-HTTPRequests §Privacy](014-HTTPRequests.md) — HTTP-specific denylists and the per-call `:sensitive?` request arg.
- [Tool-Pair §Time-travel — Redaction hook](Tool-Pair.md) — the `:redact-fn` config key on `(rf/configure :epoch-history ...)`; the `projected-record` / `projected-history` off-box egress pair.
- [Tool-Pair §Direct-read privacy posture](Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path) — the MCP wire-egress contract for direct-read tools.

### Cross-cutting conventions

- [Conventions §Reserved namespaces (framework-owned)](Conventions.md#reserved-namespaces-framework-owned) — the `:rf/`, `:rf.size/`, `:rf.elision/` namespaces this surface reserves.
- [Conventions §Reserved indicator slots (MCP-shaped returns)](Conventions.md#reserved-indicator-slots-mcp-shaped-returns) — `:dropped-sensitive`, `:elided-large` envelope counters.
- [Conventions §Privacy config-knob naming](Conventions.md#privacy-config-knob-naming-on-box-ui-vs-off-box-wire-egress) — `show-sensitive?` (on-box) vs `include-sensitive?` (off-box) verb split.
- [Security §Privacy / secret handling](Security.md#privacy--secret-handling) — the pattern-level threat model and the behavioural MUSTs.

### Implementation cross-references

- [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) — cross-MCP `sensitive-event?` / `strip-sensitive` / `scrub-snapshot` walkers and the `:include-sensitive?` arg vocabulary.
- [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md) — cross-MCP elision walker + the `:include-large?` arg vocabulary.

### API.md projection

- [API.md §wire-elision walker](API.md#elide-wire-value-the-wire-boundary-walker) — `elide-wire-value`, `elision-declarations`, `populate-elision-from-schemas!`.
- [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces) — `sensitive?`, `redact-interceptor`.
- [API.md §Configure keys](API.md) — the four `(rf/configure ...)` keys, including `:elision` and `:epoch-history`.

### Author-side guide

- [docs/guide §23a — Privacy: keeping secrets out of traces](../docs/guide/23a-privacy-secrets.md) — guide-side worked-example tour for declaring `:sensitive?` on schema slots.
- [docs/guide §23b — Large blobs](../docs/guide/23b-large-blobs.md) — guide-side companion for `:large?` declarations.
- [docs/guide §24.07 — Privacy and elision in practice](../docs/guide/24-configuration-and-safety/07-privacy-and-elision.md) — operational config walkthrough.
- [docs/guide §24.08 — Exceptions under `:sensitive?`](../docs/guide/24-configuration-and-safety/08-exceptions-under-sensitive.md) — the per-app `safe-throw` convention and the three patterns for the exception-path residual.
