# EP-0015: Frame-Owned Egress Policy

Status: final
Type: standards-track

> This EP defines one public model for sensitive/large classification,
> trust-boundary projection, and observability sink policy.
>
> **Ruling recorded 2026-06-11 (Mike, in-session; bead `rf2-9ghh7u`).**
> Accepted. All twelve open issues are dispositioned in
> [§Open Issues](#open-issues) — nine as recommended (with sharpenings from the
> three-analysis convergence), and three previously-open calls resolved:
> issue 3 adopts the renamed six-profile closed set provisionally
> (`local-redacted`/`local-raw` replacing the on-box/trusted-local spellings),
> issue 5 rules response-body classification onto per-slot `:sensitive?` /
> `:large?` props on the request's `:decode` schema (the EP-0005 mechanism;
> whole-body fail-closed when unschematized; off-box captures omit bodies
> unless classified), and issue 12 rules **no supersession of EP-0005** — the
> schema-first machine surface stands, and §5 is rewritten as composition, not
> replacement, in the action wave. The resulting one-line model: **durable
> frame-wide facts → frame config path maps; owner-local schema'd data
> (machine `:data`, resource data/params, HTTP bodies) → per-slot schema
> props; event args → registration metadata** — a named EP-0007 rule-3
> cross-layer distinction, not an exception.
>
> Normative home after acceptance: `spec/015-Data-Classification.md`,
> `spec/009-Instrumentation.md`, `spec/014-HTTPRequests.md`,
> `spec/011-SSR.md`, `spec/016-Resources.md`, `spec/Tool-Pair.md`,
> `spec/Security.md`, `spec/Privacy.md`, `spec/API.md`, and guide material
> for privacy and observability.
>
> **Graduated `accepted → final` 2026-06-12 (Mike, in-session).** The decisions
> are settled and the normative homes above govern (where this EP and the spec
> differ, the spec governs). The wave-end completeness+correctness review
> (`rf2-cuc6es`) returned graduation-ready — prior review tails clean, no new
> non-test bug. Remaining work is build-not-decision and is tracked in the
> implementation-errata ledger: the `:rf.egress/local-redacted` Xray-local-render
> adoption (`rf2-t55hxg.12`) and the schema-prop `:large?` / `:params-schema`
> classification coverage (`rf2-t55hxg.5`).

## Abstract

re-frame2 currently has the raw ingredients for privacy and wire-size safety:
path marks, schema-attached `:sensitive?` / `:large?` metadata, HTTP sensitive
header/query denylists, event/error listeners, epoch projection helpers,
`rf/elide-wire-value`, and per-call `include-sensitive?` style opts.

The problem is not absence of mechanism. The problem is that the mechanisms
look like several competing public models:

- durable app-db paths can be classified through schemas or `add-marks`;
- HTTP carrier names are classified through process-global mutation;
- production observability is configured through process-global listeners;
- epoch privacy has a process-global `:redact-fn`;
- sinks and tools must know when to call a value walker or projection helper;
- `show-sensitive?`, `include-sensitive?`, `:sensitive?`, `:sensitive`, and
  `:rf/redacted` all appear at different layers with unclear ownership.

This EP proposes the positive model:

> Classification is declarative and local to the owner.
> Projection is centralized at trust boundaries.
> Sinks receive already-projected records.

Frame configuration owns durable frame policy: app-db sensitive paths, app-db
large paths, frame-local HTTP carrier names, and frame observability sinks.
Machine definitions own durable machine `:data` policy: sensitive paths and
large paths in machine-local process state. Resource and mutation definitions
own durable resource-runtime policy: cache data, params, scopes, mutation
params, patch/populate payloads, and work-ledger summaries. Registration
metadata owns transient payload shapes: event args, fx/cofx args, sub outputs,
flow outputs, machine transition payloads, and other registration-owned values.
Egress calls own trust-boundary profiles and opts. Everything else should be
internal, advanced, or removed.

Keyword names follow the existing convention: local grammar keys stay bare,
cross-surface framework policy keys live under `:rf.<area>/*`, and
user/library-owned ids stay outside the framework namespace.

## Problem Statement

Large SPAs leak information through framework-shaped records, not just through
application log statements. A checkout token can appear in app-db, an event
vector, an HTTP diagnostic, a schema-validation failure, an epoch record, a
resource cache entry or scoped key, a sub-cache value, an MCP response, an
Xray/Story artifact, a hosted monitoring payload, or an SSR/hydration payload.

re-frame2 deliberately makes runtime state highly observable. That is a core
productivity feature: one runtime story can feed traces, Xray, Story, MCP
tools, epoch history, recorders, HTTP diagnostics, schema validation reports,
and SSR or hydration surfaces. The same property creates the privacy problem.
Ordinary application values can cross framework-mediated observation
boundaries, and some of those values are credentials, tokens, private user
data, internal service facts, or other sensitive material.

For this EP, **egress** means a value leaves ordinary in-process application
execution and becomes part of a framework-created observation product: a trace
event, an error record, an epoch projection, an MCP response, a recorder/export
artifact, an SSR/hydration payload, an HTTP diagnostic, a schema-validation
report, or a tool/UI readback. Not all egress is equal. A trusted local
developer inspecting their own process may deliberately opt into raw values. A
third-party/off-box consumer, hosted log sink, AI/model provider, saved
artifact, or browser-delivered payload should fail closed by default.

re-frame2 should protect those boundaries by default while preserving the
trusted-local developer model. Handlers, subscriptions, effects, flows,
machines, and views must continue to see real values so the application can
work. Redaction belongs at observation and egress boundaries: when the
framework emits, records, projects, serializes, renders for a tool, or places
data on an official wire.

Today the design asks the author to understand too many overlapping mechanisms:

```clojure
;; app-db path via schema metadata
(rf/reg-app-schema [:auth]
  [:map
   [:token {:sensitive? true} :string]])

;; app-db path via imperative marks
(rf/add-marks :rf/default
  {[:auth :token] :sensitive})

;; event payload via registration metadata
(rf/reg-event-fx :auth/login
  {:sensitive [[:password]]}
  handler)

;; event payload via interceptor
(rf/reg-event-fx :auth/login
  {:interceptors [(rf/redact-interceptor [[:password]])]}
  handler)

;; HTTP carrier names via process-global mutation
(rf/declare-sensitive-header! "X-Honeycomb-Team")
(rf/declare-sensitive-query-param! "shop_token")

;; production observability via process-global listener mutation
(rf/register-error-listener! :app/sentry sentry-sink)

;; epoch record privacy via process-global config
(rf/configure! :epoch-history {:redact-fn redact-record})

;; direct wire boundary via low-level value walker
(rf/elide-wire-value value
  {:frame :rf/default
   :rf.size/include-sensitive? false
   :rf.size/include-large? false})
```

Each surface can be defended locally. Together they are too much. They make
privacy feel like a set of escape hatches rather than a small law.

There are four specific design smells.

1. **App policy leaks into process globals.** App-specific HTTP carrier names
   and production monitoring policy are mutable process-wide facts, even though
   re-frame2 is explicitly multi-frame.
2. **Classification is attached to the wrong owners.** Durable app-db policy is
   split between schema metadata and post-frame imperative mutation. Schemas
   should describe shape and validation; frames should own egress policy for
   their durable state.
3. **Projection is too value-shaped.** `elide-wire-value` is the right primitive,
   but real egress surfaces emit records: handled-event records, error records,
   epoch records, MCP snapshots, sub-cache reads, HTTP diagnostics, and
   hydration payloads. Record projection should be named and centralized.
4. **Human-facing APIs expose low-level booleans.** `include-sensitive?`,
   `include-large?`, and `include-digests?` are useful advanced controls, but
   the normal choice is a trust-boundary profile: off-box, on-box hidden,
   trusted local raw, SSR hydration, hosted monitoring.

The non-goals are as important as the goal. This is not a malicious-developer
defense, not deployment security, not application authorization, and not full
taint tracking. Once a secret has been flattened into an exception message,
stored under an unrelated `ex-data` key, copied into app-owned localStorage, or
sent through an app-owned logger outside re-frame2's record shapes, the
framework cannot generally recover its provenance. The privacy mechanism can be
strong where the framework still has a path, declared mark, owned record shape,
or owned egress boundary; outside that, the best it can do is document the
residual risk and make the safe convention obvious.

### Scope

In scope for this EP:

- framework-emitted trace events, handled-event records, error records, and
  warning records;
- production event/error listener substrates that downstream observability code
  may forward elsewhere;
- MCP and tool wire surfaces: pair-MCP, Story-MCP, Xray-MCP, direct reads,
  snapshots, subscription reads, epoch reads, recorder outputs, eval envelopes,
  and tool-frame state;
- Xray and Story runtime readbacks where they consume re-frame2 runtime state,
  traces, epochs, subscriptions, view tags, or recorder data;
- epoch history and time-travel records at any projection or process boundary;
- schema-validation failure records produced by re-frame2 validators;
- managed HTTP request/response data when re-frame2 captures it into traces, fx
  records, failures, or diagnostics;
- SSR and hydration payloads emitted or parsed by re-frame2;
- framework-generated diagnostics around malformed privacy markers, failed
  projection, or privacy-policy decisions.

Out of scope for this EP:

- application authentication, authorization, session policy, and permission
  checks;
- deployment and network controls such as TLS, reverse proxies, CDN rules,
  CORS policy, certificate policy, and network egress allowlists;
- secrets before they enter re-frame2-owned data shapes, including environment
  variables, credential managers, build secrets, operator consoles, shell
  history, and source-control hygiene;
- app-authored persistence that bypasses re-frame2 observation boundaries, such
  as localStorage, sessionStorage, IndexedDB, cookies, files, and direct browser
  or host APIs;
- third-party SDKs or loggers that do not consume re-frame2 trace/event/error
  records or tool output;
- screenshots, screen sharing, browser devtools copies, issue attachments, and
  manual copy/paste, except where a re-frame2 feature itself creates the
  copied/exported artifact;
- AI vendor retention policy or model-side handling after data has already left
  re-frame2.

### Where Secrets Live

From re-frame2's point of view, sensitive values can live in:

- **App-db values.** Auth tokens, session ids, passwords, card numbers, medical
  identifiers, private profile data, uploaded documents, partner credentials,
  and cached API payloads matter when app-db is traced, validated, snapshotted,
  read by a tool, sent through SSR/hydration, or projected into an epoch.
- **Runtime-db values.** Framework-owned state can carry app-sensitive material:
  machine snapshots, actor `:data`, resource cache entries, work-ledger rows,
  mutation instances, route state, SSR/hydration state, epoch metadata, and
  tool/runtime frame state.
- **Event/coeffect/effect payloads.** Secrets can enter through dispatched event
  vectors, injected cofx, returned fx arguments, dispatch child events, HTTP
  request maps, and failure payloads.
- **HTTP data.** Headers, cookies, URL query params, request bodies, decoded
  responses, and managed-failure details are concentrated secret carriers when
  the HTTP artifact captures or reports them.
- **Derived values.** Subscriptions, flows, machine transitions, schema explain
  data, SSR props, and view props can copy, aggregate, summarize, or reshape
  sensitive inputs into new sensitive outputs.
- **Trace and epoch records.** Observation products carry before/after state,
  event vectors, fx args, sub runs, renders, errors, and timing context. These
  records are often more sensitive than a single value because they correlate
  many facts in one place.
- **Tool readbacks.** Xray, Story, pair-MCP, Story-MCP, direct `get-path`,
  `snapshot`, `sub-cache`, `read-sub`, `read-ui`, DOM reads, recorders, and eval
  result envelopes can all materialize live runtime values outside normal app
  code.
- **Exception values in framework records.** Exception messages and `ex-data`
  are author-built data, but re-frame2 error records can carry or render them.
  Once a secret has been interpolated into a string or put under an unrelated
  key, path-based projection can no longer prove where it came from.

## Motivation

The pre-alpha window is the right time to make this elegant. Backwards
compatibility with earlier re-frame2 drafts is not a constraint. Mechanical
upgrade from re-frame v1 remains valuable where it does not compromise the
model, but v1 did not have re-frame2's multi-frame, tool-rich, AI-assisted,
SSR-aware privacy surface.

The target is a framework that makes large SPAs easier to reason about:

- A human can inspect a frame declaration and see what durable data is sensitive
  or large.
- A human can inspect an event/sub/fx registration and see what transient
  payloads are sensitive or large.
- A sink author does not hand-roll redaction.
- A tool author does not need to remember ten privacy rules.
- A hosted monitoring sink never receives raw sensitive values by default.
- A multi-frame app can route different frames to different sinks.
- An app can opt into raw local reads deliberately, without weakening off-box
  defaults.

The positive model is close to the rest of re-frame2's ethos: effects are data,
frames own state, paths are explicit, and boundaries are named.

## Goals

- Define one public declaration model for sensitive and large data.
- Make durable app-db classification a frame creation concern.
- Make durable machine `:data` classification an explicit machine definition
  concern.
- Make resource and mutation classification an explicit definition concern,
  preserving Spec 016's fail-closed scope and hydration projection rules.
- Make frame-local HTTP carrier-name extensions part of frame policy, not
  process-global mutation.
- Make production observability sink policy part of frame configuration.
- Keep registration-owned transient payload classification on registrations.
- Introduce named egress projection profiles over the low-level elision flags.
- State the keyword namespacing rule for the proposed API.
- Define `elide-wire-value` as the low-level value walker, not the whole public
  projection story.
- Define record-level projection as the required step before any off-box sink.
- Clarify the three observation streams: dev trace, dev epoch, production
  handled-event/error observations.
- Preserve fail-closed behavior when a frame is unknown.
- Remove or demote duplicate public APIs where they express the same fact.
- Identify the remaining hard questions explicitly so the EP can carry the
  design conversation.

## Non-Goals

- This EP does not finalize every sink integration API.
- This EP does not define a Datadog, Sentry, Honeycomb, or OpenTelemetry
  client.
- This EP does not solve arbitrary taint tracking through user code.
- This EP does not guarantee that every derived value can be classified
  automatically.
- This EP does not require a full optics library; it consumes EP-0012's path
  vocabulary.
- This EP does not redesign resource cache scope or SSR allowlists, though both
  consume the resulting projection policy.
- This EP does not make local developers unable to see their own secrets.
  Trusted-local tools can opt in; off-box egress defaults closed.

## Relationships

- **EP-0002 (Explicit Frame Target Resolution).** Egress policy is frame-scoped
  and therefore inherits the no-default-frame rule. If a projection needs frame
  policy and no frame is known, it fails closed rather than borrowing
  `:rf/default`.
- **EP-0001 (Frame App/Runtime Partitions).** Egress policy projects a coherent
  frame-state value: app-db, runtime-db, epoch records, and runtime-subsystem
  children are distinguished at the frame boundary instead of through
  process-global privacy state.
- **EP-0003 (Resource Queries) / Spec 016.** Resource entries, work-ledger rows,
  scopes, params, owner/cause summaries, and SSR/hydration payloads are egress
  records under this EP's projection model. This EP must preserve Spec 016's
  fail-closed scope and resource redaction rules. Resource and mutation
  definitions are durable runtime-subsystem owners, not merely transient
  registration payloads.
- **EP-0005 (Machine `:data` Schema).** EP-0005 is final and currently governs
  machine `:data` sensitivity: v1 expresses machine data sensitivity only via
  per-slot `:data-schema` `:sensitive?` / `:large?` properties. This proposal
  deliberately reopens that public-model question by moving durable machine
  `:data` egress policy to explicit machine metadata. Until EP-0015 is accepted
  and the named specs are changed, EP-0005 and Spec 005 govern.
- **EP-0006 (Runtime Subsystem Contract).** Runtime subsystems need projection
  policies. This EP supplies the data-classification and egress-profile model
  those policies should cite.
- **EP-0007 (One Name Per Fact).** This EP removes duplicate names for the same
  privacy fact. One durable app-db path classification should not be expressible
  through schema metadata, frame marks, and a post-creation mutation API as
  equally first-class choices.
- **EP-0008 (Production Observability Channels).** EP-0008 names the
  production-survivable error axis and separates it from causal and diagnostic
  channels. This EP governs how those error records, and sibling production
  observability records, are projected and which frame-owned sinks may receive
  them.
- **EP-0012 (Path Optics And Canonical Forms).** Sensitive and large
  declarations consume the common `:rf/path` vocabulary and overlap laws.
- **EP-0013 (App Values And Runtime Realms).** Long term, frame policy and
  registration policy can become part of app values installed into realms. This
  EP does not require that architecture, but it is aligned with it.
- **EP-0014 (Derivation And Process Algebra).** Derived sensitivity is a graph
  question. This EP opens that issue and should eventually cite EP-0014's
  dependency graph for conservative derived-value propagation.

## Specification

### 1. The Three-Layer Model

re-frame2 privacy and size policy has three layers.

**Classification** names facts:

- this app-db path is sensitive;
- this app-db path is large;
- this event payload path is sensitive;
- this HTTP header name carries secret material;
- this sub output is large.

**Projection** applies classification at a boundary:

- dev panel rendering;
- MCP response;
- hosted monitoring emit;
- epoch export;
- direct read;
- SSR/hydration;
- error response projection.

**Sink policy** decides where projected records go:

- Datadog;
- Sentry;
- Honeycomb;
- Story recorder;
- Xray;
- MCP;
- local file recorder;
- browser console;
- SSR response.

The public rule:

> App authors declare classification and sink policy.
> The framework performs projection.
> Sinks consume projected records only.

### 2. Keyword Namespacing Rule

This EP follows the existing configuration convention:

- **Closed local grammar keys stay bare.** A `reg-frame` metadata map is already
  a framework-owned grammar, so ergonomic frame-local keys such as
  `:sensitive`, `:large`, `:observability`, `:app-db`, `:http`, `:headers`,
  `:query-params`, `:handled-events`, and `:errors` stay unqualified. The same
  applies to registration-local metadata keys such as `:sensitive` and
  `:large`.
- **Cross-surface framework policy keys are namespaced.** A key that means the
  same thing across `project-egress`, sink policy, MCP, SSR, and tool options
  uses the reserved `:rf.egress/*` namespace. The first proposed member is
  `:rf.egress/profile`.
- **Framework-owned discriminator values are namespaced.** Egress profiles are
  values under `:rf.egress/*`, e.g.
  `:rf.egress/off-box-observability`. Observation record kinds live under
  `:rf.observe/*`, e.g. `:rf.observe/handled-event`.
- **User/library-owned ids are not claimed by the framework.** A Datadog sink
  id is not `:datadog` and not `:rf.sink/datadog` unless the framework itself
  ships that sink. The app or integration library owns the id, e.g.
  `:my-app.sinks/datadog` or `:acme.datadog/main`.
- **Sink-specific options are isolated.** Vendor-specific fields such as
  `:service`, `:env`, endpoint URLs, or API-key references live under a local
  `:opts` map so the framework does not appear to own their vocabulary.

This keeps the common case readable while preserving EP-0007's one-name rule
for reusable framework facts.

### 3. Frame-Owned Durable Classification

Durable app-db classification belongs on the frame:

```clojure
(rf/reg-frame :app/main
  {:sensitive
   {:app-db [[:auth :token]
             [:auth :refresh-token]
             [:tenant :partner-api-key]]
    :http    {:headers      ["X-Honeycomb-Team"]
              :query-params ["shop_token"]}}

   :large
   {:app-db [[:documents :csv-upload]
             [:reports :raw-export]]}

   :on-create [:app/init]})
```

Semantics:

- Frame classification is installed atomically as part of frame creation,
  before `:on-create` runs.
- Re-registering a frame replaces frame-owned classification using ordinary
  frame metadata replacement semantics.
- `:sensitive :app-db` and `:large :app-db` entries are `:rf/path` values.
- `:sensitive :http :headers` and `:sensitive :http :query-params` are
  frame-local extensions to immutable framework defaults.
- Built-in HTTP names remain immutable framework defaults.
- Sensitive wins over large. A path that is both sensitive and large redacts as
  sensitive and does not emit a large marker that could leak path, size, digest,
  or fetch-handle information.
- Malformed paths, unknown classification keys, and non-string HTTP carrier
  names fail loudly at frame registration.

This replaces the public need for:

```clojure
(rf/add-marks frame-id path->mark)
(rf/set-marks frame-id path->mark)
(rf/declare-sensitive-header! name)
(rf/declare-sensitive-query-param! name)
```

Those functions may remain as internal/test/generated-code helpers if the
implementation benefits from them, but they should not be the normal guide
surface.

### 4. Registration-Owned Transient Classification

Transient payloads are owned by the registration that introduces the shape.
Examples:

```clojure
(rf/reg-event-fx
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db db
     :rf.http/managed
     {:request {:method :post
                :url "/api/login"
                :body {:email email :password password}}}}))
```

```clojure
(rf/reg-sub
  :partner/api-token
  {:sensitive [[]]}       ;; whole sub output
  (fn [db _]
    (get-in db [:tenant :partner-api-key])))
```

```clojure
(rf/reg-flow
  :auth/session-summary
  {:inputs {:token [:auth :token]
            :user  [:auth :user]}
   :output [:auth :session-summary]
   :sensitive [[:token-hash]]
   :derive (fn [{:keys [token user]}]
             {:user-id (:id user)
              :token-hash (sha256 token)})})
```

Registration metadata classifies values whose shape is local to the
registration: event args, cofx values, fx args, sub outputs, flow outputs,
machine transition payloads, and similar records. Durable runtime-subsystem
state introduced by resources and mutations follows its own owner rule below;
it is not classified as a transient registration payload merely because it is
declared by `reg-resource` or `reg-mutation`.

This keeps the ownership rule compact:

> Frame config classifies frame-owned durable state and frame-owned egress.
> Machine definitions classify machine-owned durable process state.
> Resource and mutation definitions classify their durable runtime-subsystem
> state.
> Registration metadata classifies registration-owned transient payloads.

### 5. Machine-Owned Durable Classification

> **Ruled 2026-06-11 — this section's replacement proposal was REJECTED
> (Open Issues, disposition 12).** EP-0005's schema-first surface stands:
> machine `:data` sensitivity is expressed through per-slot `:data-schema`
> props, with no top-level machine `:sensitive` / `:large` keys. This section
> is retained as the record of the considered-and-rejected alternative; the
> action wave rewrites it as *composition* (how the schema-first machine
> surface composes with frame-owned and registration-owned policy), not
> replacement.

Machine `:data` is durable process state owned by the machine definition. It
should follow the same explicit-policy posture as frames: schemas describe
shape; machine metadata declares egress policy.

```clojure
(rf/reg-machine
  :checkout/payment
  {:data-schema
   [:map
    [:payment [:map
               [:token :string]
               [:receipt-pdf :bytes]]]]

   :sensitive
   {:data [[:payment :token]]}

   :large
   {:data [[:payment :receipt-pdf]]}}
  ...)
```

Semantics:

- `:sensitive :data` and `:large :data` entries are paths rooted at the
  machine's `:data` value.
- Machine transition payloads remain transient payloads and are classified by
  the transition/registration metadata that introduces them.
- Machine `:data-schema` does not carry public `:sensitive?` / `:large?`
  metadata. If migration tooling imports old schema-attached marks, it should
  lower them into explicit machine metadata.
- Sensitive wins over large using the same rule as frame-owned app-db policy.
- Malformed paths and unknown classification keys fail at machine registration.

This keeps machines from becoming a special schema-based exception to the
owner-declares-policy rule.

### 6. Resource And Mutation Durable Classification

This EP must not accidentally treat Spec 016 resources as ordinary transient
registration payloads. A resource definition creates durable runtime-db state:
entries under `:rf.runtime/resources`, scoped keys, params, data, errors,
refresh errors, and associated work-ledger summaries. A mutation definition
creates durable mutation-instance and work-ledger state, and may patch or
populate resource entries. Those shapes are owned by the resource or mutation
definition.

Current Spec 016 and the implementation express that policy with coarse
resource metadata such as `:sensitive?` and `:large?`, plus schema-based marks.
They also pin load-bearing rules that this EP must preserve:

- scopes and params can be sensitive, not just resource data;
- no resource read falls through to an implicit global scope;
- SSR/hydration projects only an allowlisted resource runtime slice;
- sensitive resource entries hydrate as metadata-only redacted entries;
- large resource entries hydrate as metadata-only omitted-data entries;
- resource keys that carry sensitive scopes or params do not ride raw merely
  because the entry's `:data` was redacted.

If EP-0015 is accepted, the exact spelling can be reworked in the same spirit
as frames and machines: either keep coarse `:sensitive?` / `:large?` claims
where the whole resource is the classification unit, or add explicit
`:sensitive` / `:large` path maps rooted at `:data`, `:params`, `:scope`, and
mutation payload shapes. The ownership rule should not change: resource and
mutation definitions own the durable runtime-subsystem policy they introduce,
and projection applies it at SSR, tool, trace, epoch, and observability
boundaries.

### 7. `redact-interceptor` Is Removed From The Public API

`redact-interceptor` now looks like an older escape hatch. If registration
metadata can classify event payload paths, and projection happens centrally at
egress boundaries, then a positional interceptor that "redacts for trace but not
for the handler" is conceptually odd. It makes privacy depend on interceptor
placement rather than on the owner of the payload shape.

This EP removes `redact-interceptor` from the public API. Migration tooling
should generate registration metadata, not interceptor stacks. The guide-level
model teaches:

```clojure
(rf/reg-event-fx
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  handler)
```

not:

```clojure
(rf/reg-event-fx
  :auth/login
  [(rf/redact-interceptor [[:password]])]
  handler)
```

### 8. Schemas Describe Shape, Not Public Egress Policy

Schema metadata is currently used as a public classification path:

```clojure
(rf/reg-app-schema [:auth]
  [:map
   [:token {:sensitive? true} :string]
   [:profile [:map
              [:email {:sensitive? true} :string]]]])
```

This EP proposes to remove schema-attached `:sensitive?` / `:large?` from the
guide-level API for app-db policy. Schemas should primarily describe shape,
validation, explainability, and digestable contracts. Egress policy belongs to
the frame.

Implementation options:

- remove schema extraction entirely;
- keep it temporarily as an importer for generated migration data;
- keep it as an internal compatibility bridge that lowers into the same
  frame-owned classification registry.

The public model should not teach schema metadata and frame policy as two
equivalent ways to classify the same app-db path.

Machine `:data-schema` follows the same rule. It describes machine data shape
and validation. It does not carry public egress classification; explicit
machine `:sensitive` and `:large` metadata owns that policy.

### 9. Frame-Owned Observability Sink Policy

Production observability sink policy belongs on the frame:

```clojure
(rf/reg-frame :app/main
  {:observability
   {:handled-events [{:sink :my-app.sinks/datadog
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa"
                             :env "prod"}}]
    :errors         [{:sink :my-app.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa"
                             :env "prod"}}]}

   :sensitive
   {:app-db [[:auth :token]]
    :http {:headers ["X-Honeycomb-Team"]}}

   :on-create [:app/init]})
```

In this proposal, `:handled-events` means one production-safe observation
record per re-frame event processed by this frame. It is not browser DOM
events, and it is not the development trace stream's many fine-grained trace
events.

Candidate record shape:

```clojure
{:kind       :rf.observe/handled-event
 :frame      :app/main
 :event-id   :checkout/submit
 :event      [:checkout/submit {:cart-id "c-123"}]
 :status     :ok
 :elapsed-ms 12
 :effects    [:db :rf.http/managed]}
```

The sink does not receive this raw record. The runtime first projects it under
the frame's egress policy and the sink's egress profile.

```clojure
(defn datadog-sink [projected-record]
  ;; Already projected. No sink-local redaction.
  (datadog/send projected-record))
```

Low-level listener registries may still exist internally:

```clojure
(rf/register-event-listener! :advanced/custom f)
(rf/register-error-listener! :advanced/custom f)
```

But they should be advanced integration APIs, not the normal production
Datadog/Sentry story.

### 10. Projection Profiles

The low-level walker already needs boolean opts:

```clojure
(rf/elide-wire-value value
  {:frame :app/main
   :rf.size/include-sensitive? false
   :rf.size/include-large? false
   :rf.size/include-digests? false})
```

Those flags should remain the advanced override layer. Human-facing APIs should
prefer named egress profiles:

```clojure
(rf/project-egress record
  {:frame :app/main
   :rf.egress/profile :rf.egress/off-box-observability})
```

Candidate profile vocabulary:

| Profile | Default behavior |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring; omit raw app-db/runtime-db; redact sensitive; elide large; omit digests unless explicitly enabled |
| `:rf.egress/off-box-tool` | MCP/AI/tool wire; redact sensitive; elide large; include structural indicators/counters |
| `:rf.egress/on-box-hidden-sensitive` | local dev UI; suppress sensitive display by default; may show indicators |
| `:rf.egress/trusted-local-raw` | trusted local operator; include sensitive and large unless size caps still require handles |
| `:rf.egress/ssr-hydration` | allowlist first; redaction is defense-in-depth, not the primary boundary |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values |

The exact names are open. The important design point is that the public
question is "which boundary is this?" rather than "which combination of
booleans did I remember?"

### 11. `elide-wire-value` Remains The Value Primitive

`rf/elide-wire-value` remains the single low-level value walker for tree-shaped
values:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main
   :path [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

But sinks and tools should usually call record projection, not manually walk
fragments. For example:

```clojure
(rf/project-egress
  {:kind :rf.observe/handled-event
   :frame :app/main
   :event [:auth/login {:password "secret"}]
   :effects [:db :rf.http/managed]}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

The record projector knows which slots are app-db-shaped, event-shaped,
exception-shaped, HTTP-shaped, or public-summary-only. It delegates to
`elide-wire-value` where a slot is tree-shaped and frame-policy applies.

### 12. Observation Streams

This EP distinguishes three streams:

1. **Dev trace stream.** Fine-grained diagnostic events. Production-elided in
   CLJS release builds. Consumed by Xray, Story, pair tools, local recorders,
   and custom dev tools.
2. **Dev epoch stream.** One assembled record per dequeued event, useful for
   time travel and "what just happened?" tools. Production-elided with the
   epoch feature.
3. **Production observation stream.** Production-survivable handled-event and
   error records. Bounded, projected, and routed by frame `:observability`
   policy.

The production stream is not a replacement for dev trace detail. It is a
small, safe, hosted-monitoring surface.

### 13. Direct Reads And Fail-Closed Frame Resolution

Direct reads bypass trace protection:

```clojure
(rf/app-db-value :app/main)
(rf/sub-cache :app/main)
;; MCP get-path
```

Any direct read that crosses an egress boundary must project app-side, with the
frame known:

```clojure
(rf/project-egress value
  {:frame :app/main
   :path [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

If a projection needs frame policy and no frame is known, it must fail closed.
It must not synthesize `:rf/default`.

### 14. SSR And Hydration Are Allowlist-First

SSR/hydration is production egress to the browser. It should not primarily ask
"which leaves are sensitive?" It should ask "which state is allowed to cross
this boundary?"

This EP keeps SSR/hydration as an allowlist-first boundary:

```clojure
(rf/reg-frame :app/server
  {:ssr
   {:hydrate
    {:include-app-db [[:route]
                      [:public-config]
                      [:catalog :visible-items]]}}})
```

Frame classification still composes as defense-in-depth. If an allowlisted
slice contains a sensitive child, projection redacts it unless the SSR policy
explicitly permits it. But the main safety property is that unlisted state does
not cross.

### 15. Epoch Redaction

The current epoch hook is powerful:

```clojure
(rf/configure! :epoch-history
  {:redact-fn redact-record})
```

It also smells. It mutates the record that tools may later restore from, so a
custom redaction function can damage restore fidelity.

Proposed posture:

- raw epoch records may remain in-process local dev state;
- off-box epoch export must use egress projection;
- frame-level epoch projection policy should replace process-global
  `:redact-fn` for ordinary use;
- a custom record transform, if retained, should be advanced and explicitly
  warn that it may affect restore fidelity.

### 16. Derived Sensitivity

Derived values are the hardest unresolved case. A subscription, flow, resource
normalizer, or view prop can copy, summarize, hash, concatenate, or otherwise
reshape sensitive input into a new value.

Candidate conservative rule:

> If a framework-known derivation depends on sensitive input, its output is
> treated as sensitive unless the registration explicitly declares the output
> safe.

Example:

```clojure
(rf/reg-sub
  :auth/token-prefix
  {:inputs [[:auth :token]]
   ;; Candidate spelling only: this EP rejects overloading :sensitive false.
   :rf.egress/output-sensitivity :rf.egress.sensitivity/public}
  (fn [token _]
    (subs token 0 4)))
```

This needs more design. It depends on EP-0014's declared dependency graph, and
it must avoid making ordinary useful derived data invisible in tools. The
important constraint is that an explicit safe-output declaration should not
reuse `:sensitive false`, because `:sensitive` already means a collection of
sensitive paths in registration metadata.

## Backwards Compatibility

re-frame2 is pre-alpha. Compatibility with current draft APIs is not required.
The right design should win.

Mechanical upgrade from re-frame v1 remains a secondary goal:

- v1-style event/sub/fx registrations remain source forms;
- single-frame apps can put policy on their one frame and recover the old
  "global enough" mental model;
- schema metadata import can exist as a migration tool if useful;
- old guide-level global APIs should not be preserved solely for draft
  compatibility.

## Open Issues

**All twelve issues were ruled 2026-06-11 (Mike, in-session; bead
`rf2-9ghh7u`).** Original recommendations are kept verbatim as the record of
what was ruled; dispositions and riders are inline.

1. **Exact frame config shape.** Should the keys be `:sensitive` / `:large`, or
   a nested `:egress` / `:classification` map? Recommendation: use
   `:sensitive` / `:large`; they are the words authors think in.
   **Disposition: as recommended.** `:observability` stays a separate sibling
   key. Rider: the `:sensitive` (frame path map) vs `:sensitive?` (per-slot
   schema prop) pair is recorded as a named EP-0007 rule-3 cross-layer
   vocabulary distinction, not left as accident.
2. **Projection API name.** `project-egress`, `project-record`, and
   `project-observation` are candidate names. Recommendation: use a general
   `project-egress` for the public boundary primitive and narrower internal
   helpers per record kind. **Disposition: as recommended.** The name names the
   boundary, not a record kind; per-record-kind projectors stay private. The
   facade-export classification rule applies when `rf/project-egress` lands.
3. **Profile names.** The exact `:rf.egress/*` vocabulary should be thrashed
   out with examples from MCP, Xray, SSR, and hosted monitoring.
   **Disposition (ruled; no prior recommendation):** adopt the six-profile set
   **provisionally**, renamed for axis consistency:
   `:rf.egress/off-box-observability`, `:rf.egress/off-box-tool`,
   `:rf.egress/local-redacted` (was `on-box-hidden-sensitive`),
   `:rf.egress/local-raw` (was `trusted-local-raw`),
   `:rf.egress/ssr-hydration`, `:rf.egress/public-error`. The set is a
   **closed enum** (additions need a recorded ruling). Graduation gate: each
   profile must be exercised by at least one real consumer surface (pair-MCP,
   Xray, SSR payload, hosted-monitoring sink) before final naming locks.
   `:rf.egress/ssr-hydration` is defined as the projection applied **after**
   §14's allowlist — never a parallel SSR mechanism.
4. **Handled-event record shape.** What is safe and useful by default? Should
   event args be omitted unless registration metadata declares them safe?
   Recommendation: hosted monitoring defaults to summary-only; tools can opt
   into richer projected payloads. **Disposition: as recommended, sharpened:**
   the off-box default record carries frame, event id, status, elapsed,
   effect keys, and work/correlation ids; the `:event` args slot is **omitted
   entirely** by default (the §9 candidate shape showing full args is
   corrected in the action wave). Tools opt into **projected** payloads, never
   raw. This is one rule with EP-0008's "structured data only — never raw
   values" always-on record rule; the two specs cross-cite a single statement.
5. **HTTP response bodies.** Header/query policy is not enough. Managed HTTP
   needs a response-body classification story for login, refresh, partner API,
   upload URL, and opaque token responses.
   **Disposition (ruled; no prior recommendation):** response bodies are
   **registration-owned transient payloads**, classified per-slot via
   `:sensitive?` / `:large?` props on the request's `:decode` schema — the
   EP-0005 mechanism, reused (the decode schema lives on the owning
   call/resource/mutation declaration). Whole-body sensitivity is a root-level
   prop; an **unschematized body is whole-sensitive (fail-closed)**; off-box
   production traces and captures **omit response bodies entirely** unless a
   classified projection is explicitly requested. This does not conflict with
   §7: that section bars schemas from being a *second* route to classify
   durable app-db paths; transient payloads have one owner and one route.
6. **Epoch storage versus projection.** Should `:redact-fn` be removed,
   demoted, or reframed as a projection hook? Recommendation: demote;
   projection should be the normal answer. **Disposition: as recommended,
   hardened:** the surviving hook is **projection-side only** (export/egress);
   storage-side mutation is **removed**, not discouraged — post-EP-0010, epoch
   records are causal replay material, and mutating them at rest corrupts the
   replay contract, not merely restore fidelity.
7. **Cross-tool `show-sensitive?`.** Should on-box visibility be per tool, per
   session, per frame, or all three? Recommendation: no single process-global
   user toggle. **Disposition: as recommended, with the grain named:**
   visibility is **per (tool, frame) pair**; session pinning composes on top
   as tool UX, not framework state. Local tools default `local-redacted`; raw
   requires explicit trusted-local opt-in; revealing sensitive data is an
   operator act and is itself trace-visible (auditable).
8. **Derived sensitivity.** Should derived outputs inherit sensitivity from
   sensitive inputs by default? Recommendation: yes for framework-known
   dependency graphs, with explicit safe-output declarations.
   **Disposition: as recommended, scoped:** framework-known graphs means
   subscription topology (EP-0004's fixed-topology invariant, including
   `:realized-inputs` for parametric subs), flows, machine selectors, and
   EP-0016's named scope resolvers (declared inputs). Handler internals are
   honestly out of scope — no pretend taint-tracking. **Not gated on EP-0014**
   (a proposal); EP-0004's topology suffices for v1, EP-0014 generalizes later.
9. **Derived declassification spelling.** What is the exact metadata key and
    value for declaring a derived output safe? Recommendation: do not overload
    `:sensitive false`; reserve `:sensitive` for path declarations and use a
    namespaced egress/sensitivity key for output-level claims.
    **Disposition: as recommended, with the spelling fixed:**
    `:rf.egress/output-sensitivity` with the closed value set
    `:rf.egress/inherit` (default) | `:rf.egress/sensitive` |
    `:rf.egress/public` — flat in `:rf.egress/*`, no sub-namespaces (the §15
    `:rf.egress.sensitivity/*` candidate is rejected as an issue-10 boundary
    violation). A `:public` claim is the declassification analogue of the
    `:rf.scope/global` claim: **Xray enumerates every `:public` claim as a
    standing audit surface**, mirroring the global-scope list. No reason-note
    requirement in v1 (demand-driven).
10. **Reserved namespaces.** Should the initial reserved policy namespaces be
    limited to `:rf.egress/*`, `:rf.observe/*`, and existing `:rf.size/*`
    low-level flags? Recommendation: yes; keep frame-local grammar keys bare
    and keep user/integration sink ids outside framework namespaces.
    **Disposition: as recommended.** Rider: wherever this EP's surfaces use
    path maps (frame config, SSR allowlists), the path grammar is EP-0012's
    `:rf/path` vocabulary — no fourth ad-hoc path notation.
11. **Resource and mutation spelling.** Should Spec 016 keep its coarse
    `:sensitive?` / `:large?` registration claims for whole resource entries,
    or should EP-0015 introduce explicit resource/mutation `:sensitive` /
    `:large` path maps rooted at `:scope`, `:params`, `:data`, errors, and
    mutation payloads? Recommendation: preserve the current fail-closed scope
    and hydration semantics either way; decide the spelling with the Spec 016
    follow-up. **Disposition: semantics pinned as recommended; spelling ruled
    now:** per-slot `:sensitive?` / `:large?` props on the existing
    `:data-schema` / `:params-schema` are the canonical fine-grained surface;
    the coarse whole-entry claims remain as the degenerate root-prop case.
    **No new resource path-map vocabulary** — that would be the fourth
    spelling EP-0007 exists to prevent. Scope values stay covered by the
    shared elision walker; error envelopes are covered by issue 5's
    failure-shape classification at the HTTP layer.
12. **EP-0005 supersession.** Should EP-0015 replace EP-0005's final machine
    `:data-schema` sensitivity surface with explicit machine `:sensitive` /
    `:large` metadata, or should machines keep EP-0005's schema-first v1 rule
    while frames move to owner-owned policy? Recommendation: decide this at
    EP-0015 acceptance, because the proposal intentionally crosses a final EP.
    **Disposition: NO supersession — EP-0005's schema-first machine surface
    stands.** Grounds: (a) the surface is final, implementation-complete, and
    was ruled twice (the five 2026-06-08 calls; `rf2-0k5ubx` on 2026-06-09
    explicitly rejecting top-level machine `:sensitive`/`:large` keys) with no
    defect identified since; (b) the "schema-based exception" premise
    dissolves — per-slot props on a declared schema *are* owner-declares-policy
    for owners whose natural declaration surface is a schema, and co-located
    props are structurally immune to the schema-rename drift hazard that
    sibling path maps carry; (c) with issues 5 and 11 ruled schema-props,
    machines/resources/HTTP bodies now share one mechanism — superseding
    EP-0005 would make machines the odd one out in the opposite direction.
    Consequence: §5 and the §7 machine sentence are **rewritten as composition,
    not replacement** in the action wave (machines keep their surface; frame
    policy and registration policy cover the other owners); Bead Plan item 4
    is void as written; this EP no longer crosses any final EP.

## Bead Plan

1. Spec design bead: update `spec/015-Data-Classification.md` with the
   owner-classification rule and frame/registration split.
2. API bead: remove or demote public `add-marks` / `set-marks` and
   app-specific `declare-sensitive-*` globals from the guide-level API, and
   remove `redact-interceptor` from the public API.
3. Frame bead: add frame metadata schema for `:sensitive`, `:large`, and
   `:observability`.
4. Machine bead: add machine metadata schema for explicit `:sensitive` and
   `:large` paths rooted at machine `:data`, independent of `:data-schema`, if
   the EP-0005 supersession ruling accepts that move.
5. Resource/mutation bead: align Spec 016 resource and mutation classification
   with the owner-owned model while preserving fail-closed scope resolution,
   scoped-key privacy, and SSR/hydration metadata-only projection.
6. Projection bead: introduce record-level `project-egress` and profiles over
   `elide-wire-value`.
7. Observability bead: route production handled-event/error records through
   frame `:observability` policy.
8. HTTP bead: move app-specific HTTP carrier declarations to frame policy and
   design response-body classification.
9. Schema bead: remove guide-level schema-attached sensitive/large markers or
   lower them into explicit owner metadata as migration/import only.
10. Epoch bead: replace ordinary `:redact-fn` use with frame/profile projection
   and document any retained advanced hook.
11. Direct-read/tool bead: audit MCP, Xray, Story, SSR/hydration, epoch export,
   Datadog/Sentry, schema failures, HTTP diagnostics, and direct-read surfaces
   against `project-egress`.
12. Guide bead: rewrite the privacy/large-things guide around classification,
    projection, and sink policy.

## Guide Impact

This EP should produce a human-facing guide chapter or rewrite with this
teaching order:

1. Declare what is sensitive/large on the frame.
2. Declare transient payload sensitivity on registrations.
3. Choose where production observations go.
4. Understand projection profiles at trust boundaries.
5. Use raw/trusted-local views only deliberately.

The guide should avoid teaching internals first. It should not start with
`elide-wire-value`, listener registries, schema walkers, or mark tables.

## Recommendation

**Adopted and graduated to `final`** (ruling 2026-06-11; see the header
blockquote and [§Open Issues](#open-issues) for the twelve dispositions). The
central move proved sound: **frame-owned durable policy, registration-owned
transient policy, centralized projection, projected sinks**. The open issues
were thrashed out here — inline in §Open Issues — rather than spread across
findings notes, API tables, and piecemeal bead descriptions, and the normative
contract now lives in its spec homes (which govern).
