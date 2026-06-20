# Spec 015 — Data Classification (Sensitive + Large)

> Status: Drafting. **v1-required.** Graduates the owner-classification + projection + sink model ruled in [EP-0015 (Frame-Owned Egress Policy)](../docs/EP/EP-0015-frame-owned-egress-policy.md) — **final** (graduated accepted → final 2026-06-12; all twelve open issues dispositioned 2026-06-11). Builds on the registration grammar in [001-Registration](001-Registration.md), the dispatch envelope in [002-Frames](002-Frames.md), the schema surface in [010-Schemas](010-Schemas.md), the trace surface in [009-Instrumentation](009-Instrumentation.md), the reserved-namespace policy in [Conventions](Conventions.md), and the privacy posture in [Security](Security.md).
>
> **The minimum claim:** classification, projection, and sink policy are three distinct layers. **Authors declare classification and sink policy; the framework projects; sinks consume already-projected records only.** Durable frame-wide facts are owned by frame config; durable owner-local schema'd data (machine `:data`, resource data/params, HTTP bodies) is owned by per-slot schema props; transient payloads (event args, fx/cofx args, sub/flow outputs) are owned by registration metadata. Real values flow through the application unchanged; only what crosses a framework-mediated observation boundary is projected.
>
> **Posture.** This contract is a **leak-prevention overlay on observability**, not a security boundary. Apps still own their own auth/authorisation, encryption-at-rest, and transport security. The classification machinery exists so that the framework's own observability surfaces (trace bus, Xray, Story, MCP, epoch export, SSR/hydration, hosted log sinks) cannot accidentally exfiltrate user secrets or stuff records with multi-megabyte blobs. See [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the pattern-level threat model this contract grounds.
>
> **Provisional surfaces.** Per EP-0015 issue 3, the six-member `:rf.egress/*` profile enum is adopted **provisionally**: it is a closed set, but the exact names do not lock until each profile is exercised by a real consumer surface (see [§The graduation gate](#the-graduation-gate)). The EP is `final`; the only provisional surface is the profile *naming* — the decisions are settled and the normative homes (this Spec and the cross-cited specs) govern.

## Abstract

re-frame2 deliberately makes runtime state highly observable: one runtime story feeds traces, Xray, Story, MCP tools, epoch history, recorders, HTTP diagnostics, schema-validation reports, and SSR/hydration payloads. That observability is a core productivity feature, and it is also the privacy problem — an ordinary application value (a token, a session id, a card number, a partner credential, a private profile field) can cross a framework-mediated observation boundary and land in a record that is shipped off-box, handed to an LLM, saved as an artifact, or delivered to a browser.

The mechanisms to defend those boundaries already exist in scattered form: path marks, schema-attached `:sensitive?` / `:large?` props, HTTP header/query denylists, event/error listeners, epoch projection helpers, `elide-wire-value`, and per-call `include-sensitive?` flags. The problem was never absence of mechanism; it was that the mechanisms read as several competing public models. This Spec graduates EP-0015's one model:

> **Classification** names facts. **Projection** applies those facts at a boundary. **Sink policy** routes projected records.
>
> App authors declare classification and sink policy. The framework performs projection. Sinks consume projected records only.

The sentinels are unchanged: a sensitive value projects to **`:rf/redacted`**; a large value projects to **`:rf.size/large-elided`** (rich-map form `:rf/large {:bytes N :head "..."}` where the surface preserves size diagnostics). Sensitive wins over large. What changes is **who owns the declaration** and **where the projection runs**: classification moves onto the owner of the data (frame config for durable frame-wide facts, per-slot schema props for owner-local schema'd data, registration metadata for transient payloads), and projection becomes an explicit record-level boundary primitive (`project-egress`) layered over the low-level value walker (`elide-wire-value`).

**No runtime cost on the happy path.** Real values flow through events → cofx → handler → fx → app-db → subs → views unchanged. Projection happens only at observation/egress boundaries; the dev trace stream rides the `goog.DEBUG` gate per [009 §Production elision](009-Instrumentation.md#production-elision), and the production observation stream is bounded and projected per [§The three observation streams](#the-three-observation-streams).

## The three-layer model

re-frame2 privacy and size policy has three layers, and keeping them distinct is the whole design.

### Layer 1 — Classification names facts

Classification is a **declaration that a named slot carries sensitive or large content**. It is local to whoever owns the data shape, and it says nothing about where the data goes — only what it *is*:

- this app-db path is sensitive;
- this app-db path is large;
- this event-arg path is sensitive;
- this HTTP header name carries secret material;
- this machine `:data` slot is large;
- this sub output is large.

Classification is the only layer the **app author** writes for "what is this." It is declarative, it is colocated with the owner (see [§The ownership split](#the-ownership-split)), and it is inert until a projection consults it.

### Layer 2 — Projection applies classification at a boundary

Projection is the framework operation that **takes a record and a frame's classification, and returns a record safe to cross a specific trust boundary**. The boundaries are concrete:

- dev panel rendering (Xray, Story);
- MCP / tool response;
- hosted monitoring emit (Datadog, Sentry, Honeycomb);
- epoch export;
- direct read (`get-path`, `sub-cache`, `app-db-value`);
- SSR / hydration payload;
- public error response.

Projection is **centralized in the framework**. The author never hand-rolls it; a sink never hand-rolls it. The public projection primitive is [`project-egress`](#project-egress--the-record-level-boundary-primitive); it delegates to the low-level [`elide-wire-value`](#elide-wire-value--the-low-level-value-walker) walker where a slot is tree-shaped and frame-policy applies.

### Layer 3 — Sink policy routes projected records

Sink policy decides **where projected records go**: Datadog, Sentry, Honeycomb, a Story recorder, Xray, an MCP server, a local file recorder, the browser console, an SSR response. Sink policy is the second thing the **app author** declares (alongside classification), and it lives on the frame (see [§Frame-owned observability sink policy](#frame-owned-observability-sink-policy)).

A sink **never** receives a raw record. The runtime projects every record under the owning frame's classification and the sink's egress profile **before** the sink sees it.

### The public rule

> **App authors declare classification and sink policy.**
> **The framework performs projection.**
> **Sinks consume projected records only.**

This is the one law the rest of the Spec elaborates. Everything an author writes is a declaration (Layer 1 + Layer 3); everything the framework runs is projection (Layer 2); everything a sink does is consume an already-projected record.

## The keyword namespacing rule

This Spec follows the existing configuration convention recorded in [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned). Four rules cover every key on every surface this Spec introduces.

1. **Closed local grammar keys stay bare.** A `reg-frame` metadata map is already a framework-owned grammar, so ergonomic frame-local keys stay unqualified: `:sensitive`, `:large`, `:observability`, `:app-db`, `:http`, `:headers`, `:query-params`, `:handled-events`, `:errors`, `:ssr`, `:hydrate`. The same applies to registration-local metadata keys (`:sensitive`, `:large` on a `reg-event`/`reg-sub`/`reg-fx`/`reg-cofx`/`reg-flow` map). These keys are read only inside their owning grammar; they do not travel.

2. **Cross-surface framework policy keys are namespaced under `:rf.egress/*` and `:rf.observe/*`.** A key that means the same thing across `project-egress`, sink policy, MCP, SSR, and tool options is reserved framework vocabulary. Egress-policy keys live under `:rf.egress/*` (the first member is `:rf.egress/profile`); observation-record kinds live under `:rf.observe/*` (e.g. `:rf.observe/handled-event`). The existing low-level walker flags stay under `:rf.size/*` (`:rf.size/include-sensitive?`, `:rf.size/include-large?`, `:rf.size/include-digests?`).

3. **Framework-owned discriminator values are namespaced too.** Egress profiles are *values* under `:rf.egress/*` — e.g. `:rf.egress/off-box-observability` — not bare keywords. Observation record kinds are values under `:rf.observe/*`.

4. **User / library-owned ids stay outside the framework namespace.** A Datadog sink id is **not** `:datadog` and **not** `:rf.sink/datadog` unless the framework itself ships that sink. The app or integration library owns the id: `:my-app.sinks/datadog`, `:acme.datadog/main`. Vendor-specific sink fields (`:service`, `:env`, endpoint URLs, API-key references) are **isolated under a local `:opts` map** so the framework never appears to own their vocabulary.

This keeps the common case readable while preserving EP-0007's one-name rule for reusable framework facts. Wherever a surface in this Spec uses path maps (frame config, SSR allowlists, machine/resource schema slots), the path grammar is **EP-0012's `:rf/path` vocabulary** — no fourth ad-hoc path notation (EP-0015 issue 10 rider).

### The `:sensitive` / `:sensitive?` cross-layer distinction (EP-0007 rule 3)

Two spellings of the same English word live deliberately at different layers, and this pairing is recorded as a named **[EP-0007 rule-3 cross-layer vocabulary distinction](../docs/EP/EP-0007-one-name-per-fact.md#the-rules)**, not an accident:

| Spelling | Layer | Shape | Owner |
|---|---|---|---|
| `:sensitive` (no `?`) | durable frame-wide / transient-payload classification | a **path map** (`{:app-db [[:auth :token]]}`) or a vector of paths (`[[:password]]`) | frame config (`reg-frame`) / registration metadata |
| `:sensitive?` (with `?`) | owner-local schema'd-slot classification of a *transient* product | a **boolean Malli prop** on a single schema slot (`[:token {:sensitive? true} :string]`) | a resource `:data-schema` / `:params-schema` or an HTTP-body `:decode` schema (EP-0025: NOT a machine `:data-schema` — durable machine `:data` is frame-classified) |

The `?` follows the predicate-suffix convention: `:sensitive?` answers a yes/no question about **one slot**, so it is a boolean; `:sensitive` names a **set of paths**, so it is a collection. Reusing `:sensitive false` to declassify a derived output is therefore forbidden (`:sensitive` is already a path collection at that layer) — declassification has its own key, [`:rf.egress/output-sensitivity`](#derived-sensitivity). `:large` / `:large?` carry the identical distinction.

## The ownership split

Classification is attached to **whoever owns the data shape**. There are four owners; the rule is compact:

> **Frame config** classifies frame-owned durable state and frame-owned egress.
> **Machine definitions** classify machine-owned durable process state.
> **Resource and mutation definitions** classify their durable runtime-subsystem state.
> **Registration metadata** classifies registration-owned transient payloads.

The one-line model, stated by data shape: *durable frame-wide facts → frame config path maps; owner-local schema'd data (machine `:data`, resource data/params, HTTP bodies) → per-slot schema props; event args and other transient payloads → registration metadata.*

> **Supersession note.** This Spec graduates the EP-0015 owner-classification model and **supersedes** the earlier "seven first-class marking sites" framing, the public `add-marks` / `set-marks` app-db surface, and schema-attached app-db classification. The earlier per-site grammar (event handlers, subscriptions, effects, coeffects, flows) is now the single [§Registration-owned transient classification](#registration-owned-transient-classification) rule; app-db marks are [§Frame-owned durable classification](#frame-owned-durable-classification); machine `:data` is [§Machine-owned durable classification](#machine-owned-durable-classification-frame-owned-ep-0025-reversal-of-the-ep-0005-redaction-bridge); the schema relationship is [§Schemas describe shape](#schemas-describe-shape-not-durable-app-db-egress-policy). The companion cross-artefact docs (`Privacy.md`, `API.md`, `005-StateMachines.md`) reconcile their inbound links in later EP-0015 action-wave slices; the anchors below preserve those links to the surviving concepts in the interim.
>
> <a id="the-seven-first-class-marking-sites"></a><a id="1-event-handlers--reg-event-dbfxctx"></a><a id="3-subscriptions--reg-sub"></a><a id="4-effects--reg-fx"></a><a id="5-coeffects--reg-cofx"></a><a id="7-flows--reg-flow"></a><a id="2-app-db-marks-per-frame--add-marks--set-marks"></a><a id="6-state-machines--reg-machine"></a><a id="relationship-with-schema-attached-marks"></a><a id="in-scope--the-five-observation-points-marks-must-guard"></a><a id="propagation-rules"></a><a id="1-event-args--app-db"></a>

### Frame-owned durable classification

Durable app-db classification, frame-local HTTP carrier names, and frame observability sink policy belong on the frame:

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

   :observability
   {:handled-events [{:sink :my-app.sinks/datadog
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]
    :errors         [{:sink :my-app.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]}

   :on-create [:app/init]})
```

Semantics:

- Frame classification is installed **atomically as part of frame creation**, before `:on-create` runs.
- Re-registering a frame **replaces** frame-owned classification using ordinary frame-metadata replacement semantics (no additive merge — the declaration *is* the frame's policy).
- `:sensitive :app-db` and `:large :app-db` entries are `:rf/path` values ([EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md)).
- `:sensitive :http :headers` and `:sensitive :http :query-params` are **frame-local extensions** to the immutable built-in framework defaults; built-in HTTP carrier names remain immutable framework defaults that no frame can remove. `:query-params` additionally accepts a `{:include [..] :except [..]}` policy map (rf2-4wqxq8) whose `:except` set **subtracts** built-in defaults for that frame's own (dev-only, debug-gated) trace — effective policy `(defaults − except) ∪ include`, `:include` winning a tie; `:headers` has no `:except` form (a default-off header would be a real leak). See [014-HTTPRequests §Frame-local carriers](014-HTTPRequests.md#frame-local-carriers-ep-0015-3).
- **Sensitive wins over large.** A path that is both sensitive and large redacts as sensitive and does **not** emit a large marker that could leak path, size, digest, or fetch-handle information.
- Malformed paths, unknown classification keys, and non-string HTTP carrier names **fail loudly at frame registration** (fail-fast, not silent-ignore).

Frame-owned classification is the durable, cross-frame-distinct, one-place declaration of "what in this frame's app-db is sensitive or large." It replaces the public need for post-creation imperative mark mutation (`add-marks` / `set-marks`) and for app-specific process-global HTTP carrier declarations. The HTTP carrier removal has landed: `declare-sensitive-header!` / `declare-sensitive-query-param!` (and `clear-*!`) are **removed** from the public API; the immutable built-in HTTP denylists remain framework defaults that a frame's `:sensitive {:http {...}}` carriers union onto ([014-HTTPRequests §Frame-local carriers](014-HTTPRequests.md#frame-local-carriers-ep-0015-3)).

### Registration-owned transient classification

Transient payloads are owned by the registration that introduces the shape. Registration metadata classifies values whose shape is local to the registration: event args, cofx values, fx args, sub outputs, flow outputs, machine transition payloads, and similar short-lived records.

```clojure
(rf/reg-event
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db db
     :rf.http/managed
     {:request {:method :post
                :url    "/api/login"
                :body   {:email email :password password}}}}))

(rf/reg-sub
  :partner/api-token
  {:sensitive [[]]}                ;; whole sub output is sensitive
  (fn [db _]
    (get-in db [:tenant :partner-api-key])))

(rf/reg-flow
  :auth/session-summary
  {:inputs {:token [:auth :token]
            :user  [:auth :user]}
   :output [:auth :session-summary]
   :sensitive [[:token-hash]]
   :derive (fn [{:keys [token user]}]
             {:user-id    (:id user)
              :token-hash (sha256 token)})})
```

Paths in a registration's `:sensitive` / `:large` index into that registration's primary data shape: the event arg-map (second element of `[:event-id {arg-map}]`), the fx-input map, the cofx-injected value, the sub output, the flow output. Empty path `[[]]` marks the whole shape. Marks at a missing slot are a silent no-op (tolerate shape evolution); malformed path *vectors* fail at registration.

Durable runtime-subsystem state introduced by resources and mutations follows its own owner rule (below); it is **not** classified as a transient registration payload merely because it is declared by `reg-resource` or `reg-mutation`.

### Machine-owned durable classification (frame-owned; EP-0025 reversal of the EP-0005 redaction bridge)

> **Ruled 2026-06-20 (EP-0025, rf2-398kql): frame-declared paths are the SOLE app-db / runtime-db data-classification mechanism.** This REVERSES the EP-0005 schema→marks redaction bridge for machine `:data` and the earlier 2026-06-11 "no supersession of EP-0005" ruling (EP-0015 issue 12). A machine's `:data-schema` `:sensitive?` / `:large?` per-slot props NO LONGER classify the machine's durable `:data` for trace / SSR egress. The `:data-schema` still **validates** `:data` (EP-0005's rename + validation stand); only the classification bridge is removed.

Machine `:data` is durable process state owned by the machine definition (analogous to XState's `context`; guards and actions read it, and the instance's lifetime makes it a long-lived sensitive surface). The machine snapshot — `{:state :data :tags …}` — lives in the frame's **runtime-db** partition at `[:rf.runtime/machines :snapshots <actor-id>]` ([005-StateMachines §Where snapshots live](005-StateMachines.md)). Like every other durable path in a frame's state, a sensitive or large `:data` slot is classified **by the frame** via `reg-frame` `:sensitive` / `:large {:app-db …}` (EP-0015 §Frame-owned durable classification, the sole app-db mechanism):

```clojure
(rf/reg-frame :checkout
  {:sensitive {:app-db [[:rf.runtime/machines :snapshots :checkout/payment :data :payment :token]]}
   :large     {:app-db [[:rf.runtime/machines :snapshots :checkout/payment :data :payment :receipt-pdf]]}})

;; The machine's :data-schema still VALIDATES :data; it no longer classifies it.
(rf/reg-machine :checkout/payment
  {:data-schema
   [:map
    [:payment [:map
               [:token       :string]
               [:receipt-pdf :bytes]]]]
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging    {:spawn {:src :checkout/charge :on-done :done}}
             :done        {}}})
```

Semantics:

- Machine `:data` egress classification is **frame-owned**, resolved through the frame's elision registry like any other durable path — the projection / trace-egress / SSR-hydration boundaries consult the frame's declarations, not a schema-derived per-machine marks table.
- Machine **transition payloads** remain *transient* payloads and are classified by the transition / registration metadata that introduces them, not by the machine's durable `:data` policy.
- Sensitive wins over large, using the same rule as frame-owned app-db policy.
- The machine `:data-schema`'s per-slot `:sensitive?` props **still** drive validation-FAILURE-trace redaction (the `:where :machine-data` validation path, [010-Schemas §`:sensitive?`](010-Schemas.md)) — that is a property of the validator's own egress product, a DIFFERENT axis from durable `:data` classification. Only the durable-`:data`-classification bridge (snapshot / SSR egress) is reversed.

**Why frame-owned (EP-0025 grounds).** One mechanism, one place: secrets in durable frame state are recorded as `:rf/path` vectors on the frame. The machine snapshot is durable runtime-db state inside a frame, so it classifies the same way every other durable path does — there is no second schema-attached route. This removes the EP-0005 schema→marks bridge (registration-time extraction, the per-machine / per-instance schema-marks side-table, the spawn/destroy marks lifecycle) in favour of the single frame-owned registry. The `:sensitive` (frame path map) vs `:sensitive?` (per-slot schema prop) distinction recorded above still applies to the surviving schema-prop owners (resource `:data-schema` / `:params-schema`, HTTP-body `:decode`) and to validation-failure-trace redaction.

### Resource and mutation durable classification

A resource definition creates durable runtime-db state (entries under `:rf.runtime/resources`, scoped keys, params, data, errors, refresh errors, and associated work-ledger summaries); a mutation definition creates durable mutation-instance and work-ledger state and may patch or populate resource entries. Those shapes are owned by the resource or mutation definition — they are durable runtime-subsystem state, not transient registration payloads.

Per EP-0015 issue 11, the canonical fine-grained surface is **per-slot `:sensitive?` / `:large?` props on the existing `:data-schema` / `:params-schema`** (the same EP-0005 mechanism the machine surface uses) — **no new resource path-map vocabulary** (that would be the fourth spelling EP-0007 exists to prevent). The coarse whole-entry `:sensitive?` / `:large?` claims remain as the degenerate **root-prop** case (the whole resource is the classification unit). Scope values stay covered by the shared elision walker; error envelopes are covered by HTTP-layer failure-shape classification (see [§HTTP response bodies](#http-response-bodies)).

This Spec must preserve [Spec 016](016-Resources.md)'s load-bearing rules, which projection applies at every boundary:

- scopes and params can be sensitive, not just resource `:data`;
- no resource read falls through to an implicit global scope (fail-closed scope resolution);
- SSR / hydration projects only an allowlisted resource runtime slice;
- sensitive resource entries hydrate as **metadata-only redacted** entries;
- large resource entries hydrate as **metadata-only omitted-data** entries;
- resource keys carrying sensitive scopes or params do not ride raw merely because the entry's `:data` was redacted.

The ownership rule does not change: resource and mutation definitions own the durable runtime-subsystem policy they introduce; projection applies it at SSR, tool, trace, epoch, and observability boundaries. (The exact Spec 016 reconciliation is sequenced as a later EP-0015 action-wave slice.)

### Schemas describe shape, not durable app-db egress policy

A schema's job is shape, validation, explainability, and digestable contracts. Egress policy for **durable app-db paths** belongs to the **frame**, not to a `reg-app-schema` value. EP-0015 removes schema-attached `:sensitive?` / `:large?` from the *guide-level* app-db classification path so the public model does not teach two equivalent ways (frame policy vs schema metadata) to classify the same app-db path. Implementations may keep schema extraction as an internal migration importer that lowers into the frame-owned registry, but it is not a co-equal public route.

This is **not** in tension with the schema-prop owners above. The rule is precise: **schemas must not be a *second* route to classify a durable app-db / runtime-db path that the frame already owns.** Where a schema *is* the owner's natural declaration surface for a *transient, non-frame-state* product — a resource `:data-schema` / `:params-schema` (covering the request/response shape on the wire), an HTTP request's `:decode` schema (the reply body) — per-slot props are the *one and only* route for that owner's data, and there is no competing frame-config route for those shapes. Durable frame state, by contrast — app-db paths AND the machine snapshot's `:data` (runtime-db state inside a frame) — is classified by the frame (EP-0025: the machine `:data-schema`→marks bridge is reversed; machine `:data` joins the frame-owned model). One owner, one route.

## Projection

### `elide-wire-value` — the low-level value walker

`rf/elide-wire-value` is the single low-level walker for **tree-shaped values**. It takes a value and an opts map, walks the tree, and substitutes sentinels at slots the frame's classification (and the resolved profile) say to redact or elide:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main
   :path  [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

The advanced override layer is the boolean flags under `:rf.size/*`:

```clojure
(rf/elide-wire-value value
  {:frame :app/main
   :rf.size/include-sensitive? false
   :rf.size/include-large?     false
   :rf.size/include-digests?   false})
```

`elide-wire-value` knows nothing about record shapes. It is the leaf-level primitive that `project-egress` delegates to; sinks and tools should rarely call it directly.

### `project-egress` — the record-level boundary primitive

Real egress surfaces emit **records**, not bare values: handled-event records, error records, epoch records, MCP snapshots, sub-cache reads, HTTP diagnostics, hydration payloads. `rf/project-egress` is the public, record-level boundary primitive (EP-0015 issue 2: the name names the **boundary**, not a record kind). It is the required step before any off-box sink.

```clojure
(rf/project-egress
  {:kind   :rf.observe/handled-event
   :frame  :app/main
   :event  [:auth/login {:password "secret"}]
   :status :ok
   :effects [:db :rf.http/managed]}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

An `:rf.observe/*` record is **frame-bearing**: it carries its **owning frame** under a top-level `:frame` slot (`:app/main` above), and that owning frame is the frame whose classification governs the record's tree-shaped slots. The example therefore supplies only `:rf.egress/profile` in opts — when opts omit `:frame`, `project-egress` **seeds it from the record's own `:frame`**. An explicit `:frame` opt still **wins** (override semantics — the caller deliberately reclassifying under a different frame); a kindless / frameless input (or a `:frame nil` record) seeds nothing and so still **fails closed** (see [§Direct reads and fail-closed frame resolution](#direct-reads-and-fail-closed-frame-resolution) below). Without this seed the documented record-owned-frame call shape would silently drop the record's frame and walk every tree slot under the ambient (or no) frame — over-redacting off-box slots, and, under a sensitive opt-in profile, shipping values raw that the record's frame would have governed.

The record projector knows which slots are app-db-shaped, event-shaped, exception-shaped, HTTP-shaped, or public-summary-only. It delegates to `elide-wire-value` for each tree-shaped slot where frame policy applies, and it applies per-record-kind rules for the others (e.g. omitting `:event` args entirely under the off-box default — see [§The handled-event record](#the-handled-event-record)). An **event-shaped** slot (the `:event` slot on a handled-event / error record, the raw `[event-id arg-map …]` vector) is **registration-owned**, not app-db-owned: it is projected through the dispatched event handler's own `:sensitive` / `:large` registration marks (rooted at the arg-map) **before** the frame-policy walk, so a handler registered `reg-event {:sensitive [[:password]]}` has its declared arg redacted on an off-box `:observability :errors` sink even when the frame declares no matching `:sensitive {:app-db …}` classification — event args are registration-owned transient payloads ([§Registration-owned transient classification](#registration-owned-transient-classification)), and routing the event-shaped slot through frame app-db policy alone would attribute it to the wrong owner. Per-record-kind projectors are **private** helpers; `project-egress` is the one public name. (`rf/project-egress` is a new `re-frame.core` facade export and is subject to the facade-export classification rule when it lands.)

### Projection profiles — the `:rf.egress/*` enum (provisional)

The normal public choice at a boundary is **"which boundary is this?"** — a named egress profile — not **"which combination of booleans did I remember?"** The boolean `:rf.size/*` flags remain the advanced override layer beneath the profiles.

`:rf.egress/profile` takes a value from this **closed six-member enum** (EP-0015 issue 3, ruled provisionally; additions require a recorded ruling):

| Profile | Default behaviour |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb). Omit raw app-db / runtime-db; redact sensitive; elide large; omit digests unless explicitly enabled. |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire. Redact sensitive; elide large; include structural indicators / counters so the tool can reason about shape without seeing content. |
| `:rf.egress/local-redacted` | local dev UI default. Suppress sensitive display by default; may show indicators. (Renamed from the EP's earlier `on-box-hidden-sensitive`.) |
| `:rf.egress/local-raw` | trusted local operator. Include sensitive and large unless size caps still require handles. (Renamed from the EP's earlier `trusted-local-raw`.) |
| `:rf.egress/ssr-hydration` | the projection applied **after** [§SSR and hydration are allowlist-first](#ssr-and-hydration-are-allowlist-first)'s allowlist — defence-in-depth, never a parallel SSR mechanism. |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values. |

The names are **renamed for axis consistency** (`local-redacted` / `local-raw` replacing the on-box / trusted-local spellings) and are **provisional**: see [§The graduation gate](#the-graduation-gate).

### The graduation gate

The six-profile enum is a **closed set** but the **exact names do not lock** until each profile is exercised by at least one real consumer surface. The per-profile consumer-exercise evidence (the issue-3 graduation requirement) is enumerated in the third column — produced by the `/tools` propagation pass:

| Profile | Graduating consumer (must exercise before naming locks) | Exercised by (evidence) |
|---|---|---|
| `:rf.egress/off-box-observability` | a hosted-monitoring sink (Datadog / Sentry shape) | ✅ frame `:observability` sink routing — `re-frame.observability` routes handled-event / error records through `project-egress` under this profile to declared sinks (`re-frame.frame-classification` default) |
| `:rf.egress/off-box-tool` | pair-MCP / Story-MCP wire | ✅ re-frame2-pair-mcp + story-mcp default boundary — both servers resolve their direct-read / live-state egress to this profile via `re-frame.mcp-base.egress` |
| `:rf.egress/local-redacted` | an on-box dev tool (Xray panel) | ✅ Xray's App-DB panel local render — the `:rf.xray/app-db-state` section-model sub projects every value-bearing partition through `project-egress` under this profile, keyed on the observed frame (`day8.re-frame2-xray.panels.local-render`; suppress sensitive display, keep large on-box, fail-closed) |
| `:rf.egress/local-raw` | a dev tool under explicit trusted-local opt-in | ✅ the pair-MCP / story-mcp `--allow-sensitive-reads` + per-call `:include-sensitive` two-key opt-in resolves to this profile |
| `:rf.egress/ssr-hydration` | a real SSR / hydration payload | ✅ `re-frame.resources.ssr` projects the allowlisted resource hydration slice under this profile (`re-frame.resources.classification`) |
| `:rf.egress/public-error` | a public error-response projection | ✅ `re-frame.error-emit` / `re-frame.observability` route the `:rf.observe/error` record under this profile (drops the host `:exception`, never internal raw values) |

**All six profiles now have a real consumer** (the final gap closed was the on-box Xray App-DB local-render adoption of `:rf.egress/local-redacted`). With every row exercised, the names are eligible to lock and the EP can move `accepted` → `final` (the EP-0015 graduation assessment owns that flip). Additions to the set after lock require a recorded ruling.

## Frame-owned observability sink policy

Production observability sink policy belongs on the frame, under the `:observability` sibling key (shown in [§Frame-owned durable classification](#frame-owned-durable-classification) above). Two streams are routed:

- `:handled-events` — **one production-safe observation record per re-frame event processed by this frame.** This is *not* browser DOM events, and it is *not* the development trace stream's many fine-grained trace events.
- `:errors` — production-survivable error records, per [EP-0008](../docs/EP/EP-0008-production-observability-channels.md)'s always-on error axis.

### The handled-event record

The candidate record kind is `:rf.observe/handled-event`. Per **EP-0015 issue 4**, the **off-box default record omits the `:event` args slot entirely**; it carries only summary fields:

```clojure
;; off-box default projection (off-box-observability): no :event slot at all
{:kind         :rf.observe/handled-event
 :frame        :app/main
 :event-id     :checkout/submit
 :status       :ok
 :elapsed-ms   12
 :effects      [:db :rf.http/managed]
 :correlation  {:work-id "w-77" :dispatch-id "d-91"}}
```

The off-box default carries **frame, event id, status, elapsed, effect keys, and work / correlation ids** — and the `:event` args slot is **omitted entirely** by default. Tools may opt into a richer payload, but they receive a **projected** payload, never raw values. This is **one rule** with [EP-0008](../docs/EP/EP-0008-production-observability-channels.md)'s "structured data only — never raw values" always-on record rule; the two specs cross-cite a single statement rather than each carrying a parallel one.

The sink consumes the already-projected record:

```clojure
;; The app registers the concrete sink fn against the id the frame
;; policy names. The framework ships no Datadog / Sentry client; the sink
;; fn is an app / integration-library concern.
(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

### How routing runs

The frame `:observability` config is **validated for shape** at `reg-frame` time (`re-frame.frame-classification`) and **routed at runtime** (`re-frame.observability`). Shape validation fails loudly on a non-map entry, a non-keyword `:sink`, a `:rf.egress/profile` outside the [closed six-member enum](#projection-profiles--the-rfegress-enum-provisional), or a non-map `:opts` — so a typo'd profile or malformed option bag is a registration-time error, never a silent install that only surfaces when the sink first fires. The runtime does the projection; the sink consumes the projected record. The two streams route at distinct sites:

- **`:handled-events`** — the router fires the route **once per processed event**, after the cascade settles, alongside the always-on event-emit fan-out (the `:events` stream of `register-listener!`). It builds the `:rf.observe/handled-event` record, projects it through [`project-egress`](#project-egress--the-record-level-boundary-primitive) under the owning frame's classification and the entry's `:rf.egress/profile`, and delivers the projected record to the entry's registered `:sink`.
- **`:errors`** — every production-reachable `:rf.error/*` site fires the route, alongside the always-on error-emit fan-out (the `:errors` stream of `register-listener!`). It builds the `:rf.observe/error` record (the `:event` is a tree slot the projector redacts under frame policy; the host `:exception` is dropped under `:rf.egress/public-error`, walked otherwise) and delivers the projected record to the entry's `:sink`.

An entry that omits `:rf.egress/profile` defaults to **`:rf.egress/off-box-observability`** — the hosted-monitoring boundary §9 names. Each sink invocation is **isolated**: a throwing sink is dropped (sibling isolation — a buggy sink cannot block its siblings) and never crashes the dispatch.

Routing is **fail-closed** and frame-scoped (EP-0002): an unresolved frame (destroyed / never-registered) or a frame with no `:observability` policy routes **nothing** — the runtime never synthesizes `:rf/default`, never borrows another frame's sink policy, and never ships a record under unknown classification. (`project-egress` is *also* per-slot fail-closed, so a record reaching the projector frameless redacts rather than leaks — belt-and-braces.)

Low-level listener registries (the `:events` and `:errors` streams of `register-listener!`) may still exist as **advanced integration APIs**, but they are not the normal production Datadog / Sentry story — the normal story is declaring a sink under frame `:observability` and registering its fn with `rf/register-observability-sink!`. (`rf/register-observability-sink!` / `rf/unregister-observability-sink!` are new `re-frame.core` façade exports, subject to the facade-export classification rule when they land.)

## The three observation streams

This Spec distinguishes three streams, and frame `:observability` routes only the third:

1. **Dev trace stream.** Fine-grained diagnostic events, consumed by Xray, Story, pair tools, local recorders, and custom dev tools. **Production-elided** in CLJS release builds (the `goog.DEBUG` gate per [009 §Production elision](009-Instrumentation.md#production-elision)).
2. **Dev epoch stream.** One assembled record per dequeued event, useful for time travel and "what just happened?" tools. Production-elided with the epoch feature.
3. **Production observation stream.** Production-survivable handled-event and error records — bounded, projected, and routed by frame `:observability` policy. This is **not** a replacement for dev trace detail; it is a small, safe, hosted-monitoring surface.

## Direct reads and fail-closed frame resolution

Direct reads bypass trace protection — `rf/app-db-value`, `rf/sub-cache`, an MCP `get-path`. Any direct read that crosses an egress boundary **must project app-side, with the frame known**:

```clojure
(rf/project-egress value
  {:frame :app/main
   :path  [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

Egress policy is frame-scoped and therefore inherits [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md)'s no-default-frame rule. **If a projection needs frame policy and no frame is known, it must fail closed — it must not synthesize `:rf/default`.** This is the same fail-closed posture Spec 016 pins for resource scope resolution.

### Cross-tool visibility grain

On-box visibility is **per (tool, frame) pair** (EP-0015 issue 7): there is no single process-global `show-sensitive?` user toggle. Local tools default to `:rf.egress/local-redacted`; raw requires an explicit trusted-local opt-in (`:rf.egress/local-raw`). Session pinning composes on top as tool UX, not framework state. Revealing sensitive data is an **operator act and is itself trace-visible** (auditable).

## SSR and hydration are allowlist-first

SSR / hydration is production egress to the browser. It does **not** primarily ask "which leaves are sensitive?" — it asks **"which state is allowed to cross this boundary?"** SSR / hydration is an **allowlist-first** boundary:

```clojure
(rf/reg-frame :app/server
  {:ssr
   {:hydrate
    {:include-app-db [[:route]
                      [:public-config]
                      [:catalog :visible-items]]}}})
```

Frame classification still composes as **defence-in-depth**: if an allowlisted slice contains a sensitive child, projection redacts it unless the SSR policy explicitly permits it. But the primary safety property is that **unlisted state does not cross**. The `:rf.egress/ssr-hydration` profile is exactly the projection applied **after** this allowlist — never a parallel mechanism. (The Spec 011 reconciliation is sequenced as a later EP-0015 action-wave slice.)

## Epoch projection (no storage-side mutation)

Per **EP-0015 issue 6**, epoch records are **causal replay material** (post-[EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)), and mutating them at rest corrupts the replay contract — not merely restore fidelity. The posture:

- raw epoch records may remain **in-process local dev state**;
- off-box epoch export **must use egress projection** (`project-egress` with an off-box profile);
- frame-level epoch projection policy replaces the process-global `:redact-fn` for ordinary use;
- **storage-side mutation is removed**, not merely discouraged. A surviving custom-transform hook, if retained, is **projection-side only** (export / egress) and explicitly advanced.

The old `(rf/configure! {:epoch-history {:redact-fn …}})` storage-side hook is gone; projection at the export boundary is the normal answer.

## Derived sensitivity

Derived values are the hardest case: a subscription, flow, machine selector, or named scope resolver can copy, summarize, hash, concatenate, or otherwise reshape sensitive input into a new value. The conservative rule (EP-0015 issue 8):

> **If a framework-known derivation depends on sensitive input, its output is treated as sensitive unless the registration explicitly declares the output safe.**

"Framework-known" means the surfaces re-frame2 actually owns the dependency graph for: **subscription topology** ([EP-0004](../docs/EP/EP-0004-subscription-inputs.md)'s fixed-topology invariant, including `:realized-inputs` for parametric subs), **flows**, **machine selectors** (recognised as subscription variants, so the subscription propagation path covers them), and **[EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md) named scope resolvers** (the fourth graph — see below). Handler internals are honestly **out of scope** — there is no pretend taint-tracking through arbitrary handler bodies. This is **not gated on [EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md)** (a proposal); EP-0004's topology suffices for v1, and EP-0014 generalizes the propagation later.

[EP-0016](../docs/EP/EP-0016-resource-mutation-completion.md)'s **named scope resolvers** are the fourth framework-known graph EP-0015 issue 8 names, and their automatic-inheritance propagation arm **ships in the EP-0016 action wave**: a resource whose `:scope` policy is a `{:from-db <id>}` reference derives its cache scope from the resolver's declared `:db` inputs, so **when any declared `:db` input reads a frame-sensitive app-db path the derived scope — and thus the resolved resource's egress — inherits `:sensitive`, even when the owning resource was not declared `:sensitive?`.** The resolver honours the closed `:rf.egress/output-sensitivity` claim (the **same** claim subs/flows honour — `:rf.egress/inherit` is the propagating default, `:rf.egress/sensitive` force-marks, `:rf.egress/public` declassifies). The propagation reads the resolver's stored `:inputs` dependency graph against the frame's sensitive declarations (the shared `:rf/path` overlap relation — an input reading a sensitive slot, a parent, or a child all count, conservative + fail-closed, the same footgun-prevention posture as subs' layer-1 check); the consumption side reads it at scoped-key / durable-entry classification, redacting the resource's data **and** its scope/params in the wire key. This arm is **defence-in-depth, not the load-bearing scope boundary**: the primary scope-value boundary holds independently — resource scopes and params are classified by the resource owner's `:sensitive?` claim (issue 11) and redacted off-box (the scoped-key projection in Spec 016 §SSR and hydration), and a resolved scope egresses through the `:rf.resource/scope-resolved` trace op's normal `project-egress` path. The propagation arm only **adds** the automatic inheritance for the case where the owning resource was not itself declared `:sensitive?` — useful precision (in practice such a resolver's resource is normally declared sensitive too).

### Declassifying a derived output

An author declares a derived output safe with **`:rf.egress/output-sensitivity`** (EP-0015 issue 9), whose closed value set is:

```
:rf.egress/inherit    ;; default — inherit sensitivity from inputs
:rf.egress/sensitive  ;; force-mark sensitive even from public inputs
:rf.egress/public     ;; declassify — safe to surface despite sensitive inputs
```

```clojure
(rf/reg-sub
  :auth/token-prefix
  {:inputs [[:auth :token]]
   :rf.egress/output-sensitivity :rf.egress/public}
  (fn [token _]
    (subs token 0 4)))                ;; only the non-secret prefix
```

The keys are **flat under `:rf.egress/*`** — no sub-namespaces (the EP's earlier `:rf.egress.sensitivity/*` candidate is rejected as a namespacing-boundary violation). `:rf.egress/output-sensitivity :rf.egress/sensitive` **must not** be spelled `:sensitive false` — `:sensitive` already means "a collection of sensitive paths" at the registration layer, so overloading it to a boolean would break the cross-layer distinction recorded above.

A `:rf.egress/public` claim is the **declassification analogue of the `:rf.scope/global` claim**: **Xray enumerates every `:public` claim as a standing audit surface**, mirroring the global-scope list, so a reviewer can see every place an author asserted "this derived-from-sensitive value is safe." No reason-note is required in v1 (demand-driven).

## The display contract — sentinels

Three sentinel forms span the two-axis space. The sentinel keywords are **framework-reserved per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)**; apps MUST NOT use them as legitimate payload values.

### `:rf/redacted` — sensitive only

```clojure
{:auth/token :rf/redacted}
```

An opaque keyword. The substituted value carries **no information** about the underlying content — not its type, not its size, not a hash, not a prefix. A sensitive value is not revealable by any observation surface.

### `:rf/large {:bytes N :head "..."}` — large only

```clojure
{:docs/csv-upload :rf/large {:bytes 4523198 :head "ID,Name,Email\n42,Alice,…"}}
```

A two-element clause: the sentinel keyword `:rf/large` followed by a metadata map carrying `:bytes` (integer byte size, or a close `count`/string-length approximation) and an optional `:head` (first N chars of a printable rendering; N implementation-defined, CLJS reference uses 128). The low-level walker emits this as `:rf.size/large-elided` at the marked slot; surfaces that preserve size diagnostics render the rich `:rf/large` form.

### `:rf/redacted {:bytes N}` — sensitive + large composed

```clojure
{:internal/diff-blob :rf/redacted {:bytes 4523198}}
```

When a value is marked **both** sensitive and large, **sensitive wins on content visibility** — `:rf/redacted` rides the slot, no `:head` is permitted — but a size diagnostic MAY ride alongside. The CLJS reference currently suppresses the size marker entirely; both behaviours are conformant, so readers must not depend on `:bytes` being present alongside `:rf/redacted`.

### Consuming-tool rendering contract

| Mark axis | Projected form | Drillable? |
|---|---|---|
| sensitive only | `:rf/redacted` | **NO** — never revealable; no expand affordance offered, ever. |
| large only | `:rf/large {:bytes N :head "…"}` | **YES** — click-to-expand subject to a per-tool size-confirmation safeguard. |
| both | `:rf/redacted {:bytes N}` | **NO** — content not revealable; size info displayed inline. |

The rule is uniform for any consuming tool: **`:rf/redacted` MUST NOT be expandable.** A "show original" affordance against `:rf/redacted` is non-conformant — that affordance is the exact leak the contract exists to prevent.

## Author guidance for the exception-path residual

Projection walks **known data shapes** and substitutes sentinels at classified paths. It does **not** walk:

- **Exception messages.** Once a sensitive value has been concatenated into an `ex-message` string, no path resolves to the substring.
- **`ex-data` maps.** The keys are author-chosen (`{:user/email "..."}`); they have no relationship to the frame's classified paths, and a value-comparison rule would be the rejected taint-tracking non-goal.

The residual surface is the intersection of *the handler read a sensitive value* AND *the handler then threw with that value in the message or `ex-data`*. The author MUSTs at the assembly site:

```clojure
;; ANTI-PATTERN — the email lands in the error record verbatim.
(rf/reg-event :auth/login
  (fn [{:keys [db]} _]
    (let [email (get-in db [:user :email])]    ;; [:user :email] is frame-sensitive
      (throw (ex-info (str "User " email " failed login")
                      {:user/email email :reason :invalid-credentials})))))

;; PREFERRED — name the category; omit / sentinel-stamp the value.
(rf/reg-event :auth/login
  (fn [_ _]
    (throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))))
```

- **Name the *category* of failure in the message, not the value.** A category-only message plus a `:dispatch-id` correlation against the (correctly projected) pending-app-db snapshot recovers the failing identity for the dev without leaking it.
- **If the failing structure is essential, substitute `:rf/redacted` at the assembly site** so the dev's mental model stays uniform.
- **Pick a per-app convention.** The framework deliberately does **not** ship a `rf/safe-throw` helper — which `ex-data` keys are sensitive is author knowledge, not framework knowledge. Worked patterns live in [docs/guide §24.08 — Exceptions under `:sensitive?`](../docs/guide/how-to/configure-dev-and-prod.md).

> **Framework-owned diagnostics carry a SHAPE summary, never the raw value (rf2-uwqale).** The residual above is the *app* author's responsibility because the framework cannot know which `ex-data` keys an app marks sensitive. The reverse holds for the framework's **own** adapter / render diagnostics: a render-tree handed to `render-to-string`, a hiccup head / child vector handed to an adapter's element builder, a Form-3 component spec — these are *framework-shaped* values whose offending shape the framework DOES know. Because such a value can carry app-owned sensitive/large content and a thrown adapter diagnostic is captured off-box (browser console, error boundary, host log, SSR / static-export error handler, production observability) **before** the record projector can classify the original paths — and a value flattened into a message or `ex-data` slot no longer sits at any path projection can reach — every framework adapter/render diagnostic carries an **EP-0015-safe summary** of the offending value, never the value itself. The summary is the shape only: `{:type … :count … :keys … :head …}` (collection count, sorted structural map keys, a bounded recognition head for a scalar leaf); the whole value is never reproduced and ex-data slots name the summary explicitly (`:render-tree/summary`, `:tag/summary`, `:argv/summary`, `:got/summary`, `:hiccup/summary`, `:spec/summary`). The CLJS reference centralises this in `re-frame.error/diag-value-summary`; bundle-isolated adapters (the day8/reagent-slim fork, which MUST NOT `:require` re-frame.*) carry a content-identical mirror in a dependency-free leaf ns so the diagnostic vocabulary is uniform across every surface.

## Scope

### In scope — the boundaries projection must guard

Projection exists to stop leaks at every framework-mediated observation boundary:

1. **Trace-bus emit** — every `:rf/trace-event` payload built inside `emit!` (per [009 §The trace event model](009-Instrumentation.md#the-trace-event-model)); the dev stream is production-elided, the always-on error-emit substrate survives production.
2. **Xray / Story panel rendering** — Event Detail, App-DB Diff, Subscriptions, Trace, Causality Graph, Machine Inspector, Flow Panel, Story scenarios. On-box dev tools; default `:rf.egress/local-redacted`.
3. **MCP / tool wire transport** — pair-MCP, Story-MCP, Xray-MCP, and any future MCP server; `:rf.egress/off-box-tool`.
4. **AI / LLM context lifted by tools** — any path that lifts trace events, app-db snapshots, sub outputs, or machine `:data` into an LLM prompt.
5. **Hosted log sinks** — Datadog, Sentry, LogRocket, Honeybadger, custom fan-outs; routed by frame `:observability`, projected under `:rf.egress/off-box-observability`.
6. **Epoch export, SSR / hydration, public error responses, HTTP diagnostics, schema-validation failure records** — each a projection boundary with its profile above.

### Out of scope (explicit non-goals)

- **Runtime security.** Apps own auth, access control, authorisation, encryption-at-rest, transport security. This contract is a leak-prevention overlay; it does not defend against runtime code execution, XSS, or a malicious privileged-frame user.
- **Compile-time exhaustiveness.** No static pass verifies every sensitive datum has a declaration. The author owns the policy; the framework enforces it where declared.
- **Encryption at rest.** Persistence, storage, sync, IndexedDB, localStorage — app concerns.
- **Mid-handler protection.** Handlers MUST see real values to do their job. Projection is at the observation boundary *after* the handler returns, never before.
- **Full taint-tracking through user code.** Derived-sensitivity propagation (above) covers only the framework-known dependency graph; arbitrary handler-body provenance is not tracked, and the explicit `:rf.egress/public` opt-out is trusted by design.
- **Secrets before they enter re-frame2-owned shapes**, and **app-authored persistence / third-party SDKs that bypass re-frame2 observation boundaries** — once a secret has been flattened into an unrelated key, an app logger, or app-owned storage, path-based projection can no longer prove its provenance.

## Tests

Conformance fixtures under [conformance/](conformance/README.md) assert the observable contract; the normative set:

| Fixture | What it asserts |
|---|---|
| `data-classification/frame-sensitive-app-db-redacts.edn` | A frame with `:sensitive {:app-db [[:auth :token]]}`, after an event writes a token to `[:auth :token]`, projects `:rf/redacted` at that path in any off-box record; an unmarked sibling passes through. |
| `data-classification/frame-large-app-db-elides.edn` | A frame with `:large {:app-db [[:docs :csv-upload]]}` projects `:rf/large {:bytes N …}` (or `:rf.size/large-elided`) at the marked path. |
| `data-classification/sensitive-wins-over-large.edn` | A path declared in both `:sensitive` and `:large` projects as `:rf/redacted` (optionally `{:bytes N}`); no large marker that could leak path / size / digest is emitted. |
| `data-classification/frame-http-carrier-extends-defaults.edn` | A frame-local `:sensitive {:http {:headers ["X-Honeycomb-Team"]}}` redacts that header **in addition to** the immutable built-in defaults; no frame can remove a built-in default. |
| `data-classification/event-arg-sensitive-path-redacts.edn` | A `reg-event` with `:sensitive [[:password]]`, dispatched with `{:password "secret"}`, projects `[:event-id {:password :rf/redacted}]`. |
| `data-classification/machine-data-frame-declared-redacts.edn` | A frame declaring `:sensitive {:app-db [[:rf.runtime/machines :snapshots <id> :data :payment :token]]}`, after a transition writes a token into the machine's `:data`, projects `:rf/redacted` at `[:data :payment :token]` in `:rf.machine/snapshot-updated` (EP-0025 — frame-owned; the machine `:data-schema` `:sensitive?` slot does NOT classify durable `:data`). |
| `data-classification/project-egress-omits-event-args-off-box.edn` | `project-egress` of an `:rf.observe/handled-event` under `:rf.egress/off-box-observability` carries `:frame` / `:event-id` / `:status` / `:elapsed-ms` / `:effects` / correlation ids and **omits the `:event` args slot entirely**. |
| `data-classification/project-egress-fails-closed-no-frame.edn` | `project-egress` of a value with no known frame **fails closed** (does not synthesize `:rf/default`). |
| `data-classification/observability-sink-receives-projected-record.edn` | A frame declaring an `:observability {:handled-events [{:sink … :rf.egress/profile :rf.egress/off-box-observability}]}` sink, with the sink fn registered via `register-observability-sink!`, delivers an **already-projected** `:rf.observe/handled-event` record to the sink on each processed event (the off-box default omits the `:event` args slot); a declared `:errors` sink likewise receives a projected `:rf.observe/error` record on a handler exception, with a frame-sensitive path inside the error's `:event` redacted. An unresolved frame or absent `:observability` policy routes **nothing** (no `:rf/default` synthesis). |
| `data-classification/derived-output-inherits-sensitivity.edn` | A `reg-sub` reading a frame-sensitive input with no `:rf.egress/output-sensitivity` projects its output as sensitive. |
| `data-classification/derived-output-declassified-public.edn` | A `reg-sub` reading a sensitive input with `:rf.egress/output-sensitivity :rf.egress/public` projects its output **unredacted**, and the claim is enumerable as a standing audit surface. |
| `data-classification/ssr-hydration-allowlist-first.edn` | An `:ssr {:hydrate {:include-app-db […]}}` frame hydrates **only** the allowlisted slice; an unlisted path does not cross even if unclassified, and a sensitive child of an allowlisted slice is redacted as defence-in-depth. |
| `data-classification/epoch-export-projects-no-storage-mutation.edn` | Off-box epoch export projects via `project-egress`; the stored epoch record is **unmutated** (replay-faithful). |

Per-artefact unit tests cover implementation-specific propagation mechanism; the conformance fixtures cover only the observable contract.

## Cross-references

- [EP-0015 (Frame-Owned Egress Policy)](../docs/EP/EP-0015-frame-owned-egress-policy.md) — the accepted proposal this Spec graduates; §Specification (1–16) and the twelve dispositioned open issues are the rationale record.
- [EP-0007 (One Name Per Fact)](../docs/EP/EP-0007-one-name-per-fact.md) — rule 3 (cross-layer distinctions are named rules) grounds the `:sensitive` / `:sensitive?` pairing.
- [EP-0005 (Machine `:data` Schema)](../docs/EP/EP-0005-machine-data-schema.md) — the machine `:data-schema` (VALIDATION) stands; its schema→marks redaction bridge is REVERSED by [EP-0025](../docs/EP/EP-0025-frame-declared-sole-data-classification.md).
- [EP-0025 (Frame-Declared Paths As The Sole Data Classification)](../docs/EP/EP-0025-frame-declared-sole-data-classification.md) — kills schema-attached `:sensitive?` / `:large?` field classification for app-db / runtime-db (incl. machine `:data`); frame-declared paths are the one mechanism.
- [001-Registration §Registration grammar](001-Registration.md#registration-grammar) — the metadata-map shape registration-owned `:sensitive` / `:large` extend.
- [002-Frames §Routing — the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope) — the event arg-map shape event marks index into; the no-default-frame rule projection inherits.
- [005-StateMachines §Privacy — redacting machine `:data` at trace egress](005-StateMachines.md#privacy--redacting-machine-data-at-trace-egress) — frame-owned machine `:data` classification (EP-0025); `reg-machine` carries no `:sensitive` / `:large` key.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — the sub-cache dependency graph derived-sensitivity propagation traverses.
- [009-Instrumentation §The trace event model](009-Instrumentation.md#the-trace-event-model), [§Production elision](009-Instrumentation.md#production-elision) — the emit site and the dev-stream gate.
- [010-Schemas](010-Schemas.md) — schemas describe shape and validation; per-slot props are the *one* route only for schema-owned *transient* data (resource / HTTP-body), not a second route for frame-owned app-db / runtime-db paths (incl. machine `:data`, EP-0025).
- [011-SSR](011-SSR.md) — the allowlist-first hydration boundary the `:rf.egress/ssr-hydration` profile projects after.
- [014-HTTPRequests](014-HTTPRequests.md) — HTTP carrier names (frame-local extensions) and response-body classification via the request's `:decode` schema (issue 5).
- [016-Resources](016-Resources.md) — durable resource / mutation runtime-subsystem classification via `:data-schema` / `:params-schema` props; fail-closed scope and hydration metadata-only projection preserved.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.egress/*`, `:rf.observe/*`, `:rf.size/*` reservations and the namespacing rule.
- [Privacy](Privacy.md) — the cross-artefact discoverability index for the whole privacy surface.
- [Security §Privacy / secret handling](Security.md#privacy--secret-handling) — the pattern-level threat model this Spec grounds.
- [docs/guide §24.08 — Exceptions under `:sensitive?`](../docs/guide/how-to/configure-dev-and-prod.md) — author-side exception-path worked patterns.

## HTTP response bodies

> Cross-referenced from [§Resource and mutation durable classification](#resource-and-mutation-durable-classification) and the [§Cross-references](#cross-references) HTTP entry. The Spec 014 HTTP-layer reconciliation has landed: see [014-HTTPRequests §Response-body classification](014-HTTPRequests.md#response-body-classification-ep-0015-5).

Header / query carrier policy is not enough — managed HTTP needs a response-**body** classification story (login, refresh, partner API, upload-URL, opaque-token responses). Per EP-0015 issue 5, response bodies are **registration-owned transient payloads classified per-slot via `:sensitive?` / `:large?` props on the request's `:decode` schema** — the EP-0005 mechanism reused (the `:decode` schema lives on the owning call / resource / mutation declaration). Whole-body sensitivity is a root-level prop; an **unschematized body is whole-sensitive (fail-closed)**; off-box production traces and captures **omit response bodies entirely** unless a classified projection is explicitly requested.

This does not conflict with [§Schemas describe shape](#schemas-describe-shape-not-durable-app-db-egress-policy): that rule bars schemas from being a *second* route to classify durable app-db paths; a transient HTTP body has one owner (its request's `:decode` schema) and one route.
