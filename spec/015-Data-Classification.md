# Spec 015 — Data Classification (Sensitive + Large)

> Status: Drafting. **v1-required.** Builds on the registration grammar in [001-Registration](001-Registration.md), the dispatch envelope in [002-Frames](002-Frames.md), the trace surface in [009-Instrumentation](009-Instrumentation.md), the reserved-namespace policy in [Conventions](Conventions.md), and the privacy posture in [Security](Security.md).
>
> **The minimum claim:** application developers declaratively mark which *paths* inside well-known data shapes (event arg-maps, app-db, sub outputs, fx inputs, cofx injections, machine `:data`, flow outputs) carry **sensitive content** or **large blobs**. The framework auto-propagates those marks across the dataflow and observation surfaces (trace bus, Causa, MCP, third-party log sinks) substitute display sentinels (`:rf/redacted`, `:rf/large { …}`) at the marked paths *at emission time*. Real values flow through the application unchanged; only what leaves the trust boundary is filtered.
>
> **Posture.** This contract is **leak-prevention overlay on observability**, not a security boundary. Apps still own their own auth/authorisation, encryption-at-rest, and transport security. The classification machinery exists so that the framework's own dev-time observability surfaces (and their downstream consumers — log sinks, AI agents, dashboards) cannot accidentally exfiltrate user secrets or stuff log lines with multi-megabyte blobs. See [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the pattern-level threat model this contract grounds.

## Abstract

re-frame2 ships **opt-in, path-marked data classification**: every registration kind that participates in the dataflow accepts a `{:sensitive [paths] :large [paths]}` declaration on its registration map, plus dedicated `add-marks` / `set-marks` APIs for marking paths inside `app-db` (additive merge and wholesale replace, respectively). Paths are vectors of keywords/indices that index into the relevant data shape; the framework consults them at *emission time* (when a trace event is built, when Causa renders a panel, when MCP returns a tool response, when a third-party log sink invokes its trace listener) and substitutes a display sentinel for the marked value.

Two sentinels carry the contract:

- **`:rf/redacted`** — opaque keyword. The content was sensitive; observation surfaces MUST NOT make the underlying value revealable. No click-to-expand, no off-box hop, no LLM context-window leak.
- **`:rf/large {:bytes N :head "..."}`** — rich map. The content was large but not sensitive; observation surfaces MAY surface a size-confirmed click-to-expand affordance.

The two axes compose: a value marked **both** sensitive and large renders as `:rf/redacted {:bytes N}` (size visible for diagnostic purposes; content not).

**Marks propagate across the dataflow.** A sub reading an `app-db` path marked sensitive yields sensitive output by default. An event handler that writes a sensitive event-arg into `app-db` widens the destination path's sensitivity transitively. Authors can override propagation per registration when they have explicitly sanitised the derived value. Propagation is **footgun prevention**, not a security-grade taint system — the framework trusts the author's overrides.

**No runtime cost on the happy path.** Real values flow through events → cofx → handler → fx → app-db → subs → views unchanged; mark lookups happen at observation/emission time and the trace-bus emit path is compile-time elided in production via `goog.DEBUG` per [009 §Production elision](009-Instrumentation.md#production-elision).

## Scope

### In scope — the five observation points marks MUST guard

The classification machinery exists to stop leaks at every observation surface the framework owns or participates in. The complete set:

1. **Trace bus emit** — every `:rf/trace-event` payload built inside `emit!` (per [009 §The trace event model](009-Instrumentation.md#the-trace-event-model)). The runtime substitutes sentinels at marked paths before the event reaches any listener.
2. **Causa panel rendering** — Event Detail, App-DB Diff, Subscriptions, Trace, Causality Graph, Machine Inspector, Flow Panel. Causa is one of the in-tree consumers; its renderer consumes the same sentinel vocabulary every other consumer sees.
3. **MCP wire transport** — every tool response from `tools/re-frame2-pair-mcp/`, `tools/story-mcp/`, and any future MCP server. The cross-MCP wire elision walker (`rf/elide-wire-value`, per [Cross-MCP shared primitives in the Ownership matrix](Ownership.md) → [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md) and [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md)) reads the same marks the trace bus consults.
4. **AI / LLM context handed off by tools** — any code path that lifts trace events, app-db snapshots, sub outputs, or machine `:data` into an LLM prompt. The Causa AI co-pilot rail (where one exists) is one such consumer; future LLM consumers of the trace surface MUST honour the same contract.
5. **Third-party log sinks consuming the trace bus** — Datadog, Sentry, LogRocket, Honeybadger, custom log fan-outs, in-house observability pipelines. The framework's [§Sample wiring](009-Instrumentation.md#wiring-an-external-error-monitor-sentry-rollbar-honeybadger-etc) snippets default to consulting marks before egress; user-supplied listeners are normatively expected to do the same.

### Out of scope (explicit non-goals)

- **Runtime security.** Apps own their own auth, access control, authorisation, encryption-at-rest, and transport security. This contract is a leak-prevention overlay on observability; it does NOT defend against an attacker who has runtime code execution, against XSS, or against a malicious user who controls a privileged frame. See [Security.md](Security.md) for the framework's security threat model.
- **Compile-time exhaustiveness checking.** No static analysis pass verifies that every sensitive datum has a corresponding `:sensitive` declaration. The author owns the policy; the framework enforces it where declared.
- **Encryption at rest.** Persistence, storage, sync, IndexedDB, localStorage — all app concerns. The framework does not encrypt `app-db` or any other data structure.
- **Mid-handler protection.** Handlers MUST see real values to do their job. The framework does NOT redact data before the handler runs — only at the observation boundary *after* the handler returns.
- **Full taint-tracking system.** Auto-propagation (sub output marked when an input was sensitive) is a *footgun-prevention* affordance, not a security-grade taint engine. Authors can override (`{:sensitive? false}`) and that is by design — the framework trusts the author. A determined contributor can leak a sensitive value through a deliberately-overridden sub; this is acceptable because the contract is observability hygiene, not authorisation.

### Implications of the scope choice

- **Real values flow normally** through the entire runtime. Event handlers see real event-args; sub computation fns see real `app-db` slots; fx handlers receive real outbound payloads. No runtime impact on app behaviour, dispatch latency, or memory footprint.
- **Marks are consulted at emission time.** An app could in principle ship with the trace bus disabled and the marks would be dormant — they cost zero. The CLJS reference's `goog.DEBUG` gate already excludes the entire trace surface from production builds; the mark-lookup machinery rides the same gate.
- **Propagation is footgun prevention.** A sub that returns `(get-in users [uid :ssn])` propagates the upstream `:sensitive` mark to its output. A sub that returns *only the last 4 digits* of an SSN can opt out (`{:sensitive? false}`) — the author has asserted that the derived value is safe to surface. The framework cannot distinguish between these two cases automatically; the override is the author's contract with downstream consumers.
- **No production-runtime cost** on the happy path. Mark-lookup happens at emission time, gated behind `goog.DEBUG` for trace-bus emission (matches the existing privacy posture for app-db schemas per [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z)).

## The classification model

Three properties define the model:

1. **Opt-in.** Nothing is auto-detected. No size threshold automatically classifies a blob as large; no regex auto-detects credit-card numbers. The framework substitutes a sentinel **only where the author declared a path**. Auto-detection was explicitly considered and rejected — false positives would corrode trust in the indicator, and false negatives would create exactly the leak the contract exists to prevent.
2. **Path-marked.** The unit of declaration is a *path into a known data shape*, not a value, not a type, not a function. Paths are vectors of keywords or indices (the same path vocabulary [`get-in`](https://clojuredocs.org/clojure.core/get-in) accepts). A registration declares a *set* of paths; the framework walks each path at emission time.
3. **Two parallel axes — `:sensitive` and `:large`.** Both axes use the same path vocabulary, both compose at the same registration site, and both substitute a display sentinel — but they are independent. A datum may be sensitive without being large (a JWT), large without being sensitive (a CSV upload), or both (a redacted PII dump). The two sentinels compose into the combined form `:rf/redacted {:bytes N}`.

### Why paths and not schemas

[010-Schemas](010-Schemas.md) already supports schema-attached `:sensitive?` and `:large?` per-slot metadata (per [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z) and [010 §`:large?` — schema-driven size-elision nomination](010-Schemas.md#large--schema-driven-size-elision-nomination-rf2-nwv63)). That mechanism remains valid and continues to populate the framework's elision registry where a schema is registered.

**But schemas are optional in re-frame2.** Per [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) and [010 §Abstract](010-Schemas.md#abstract), schema attachment is pattern-level *required* but the *content* of the schema can be sparse — apps may register the shape of one slice and leave others undeclared. Privacy MUST NOT depend on whether the author got around to writing a schema. The path-marking surface defined in this Spec is the **primary** declaration site; schema-attached marks remain a convenience for apps already running rich schemas (the elision registry merges marks from both sources — see [§Relationship with schema-attached marks](#relationship-with-schema-attached-marks)).

### What gets a sentinel

The framework substitutes a sentinel at exactly the slot the path resolves to. Slot semantics match `get-in` / `assoc-in`:

```clojure
;; Mark declares the path [:auth :token] sensitive
{:auth {:token  "eyJhbGc..."
        :method :jwt}
 :user {:name "Alice"}}

;; Observation surface sees:
{:auth {:token  :rf/redacted
        :method :jwt}
 :user {:name "Alice"}}
```

Marks at empty path `[[]]` substitute the entire root value:

```clojure
(rf/reg-cofx :auth/jwt
  {:sensitive [[]]}            ;; the cofx-injected value as a whole is sensitive
  (fn [cofx] (assoc cofx :auth/jwt (read-jwt))))

;; Trace-bus emit of the cofx-map entry: {:auth/jwt :rf/redacted}
```

Paths that resolve to a missing slot are silently ignored — declaring `[:user :ssn]` against an app-db that contains no `:user` key is a no-op, not an error. This matches the spirit of [Principles §Open maps with schemas](Principles.md): tolerate the shape evolving.

## The seven first-class marking sites

Every registration kind that participates in the dataflow accepts the same declaration shape:

```clojure
{:sensitive [<path> <path> ...]      ;; vector of get-in-shaped paths
 :large     [<path> <path> ...]}     ;; vector of get-in-shaped paths
```

Both keys are optional and independent. A registration that declares neither defaults to "no path-marks at this site" (which does NOT mean "no marks reach this site" — propagation from upstream sites can still inject marks at evaluation time; see [§Propagation rules](#propagation-rules)).

### 1. Event handlers — `reg-event-{db,fx,ctx}`

The canonical event shape (per [002 §Routing — the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)) is `[:event-id {arg-map}]`. Paths in `:sensitive` and `:large` index into the **arg-map** (the second element of the event vector). Single-arg or trivial events (id-only, scalar second slot) accept marks but their path vocabulary is the singleton `[[]]` (whole arg).

```clojure
(rf/reg-event-fx :auth/log-in
  {:doc       "Initiate user log-in."
   :sensitive [[:password] [:totp-code]]
   :large     []}
  (fn [cofx [_ args]]
    {:fx [[:rf.http/managed {:method :post
                             :url    "/api/login"
                             :body   args}]]}))

;; Trace-bus / Causa display of the dispatched event:
;;   [:auth/log-in {:email     "alice@example.com"
;;                  :password  :rf/redacted
;;                  :totp-code :rf/redacted}]
```

The `:sensitive` declaration also seeds the propagation graph (per [§Propagation rules](#propagation-rules)): paths the handler writes into `app-db` from a sensitive event-arg-path inherit the mark on the destination side.

**Cross-reference:** [002 §Events](002-Frames.md#routing-the-dispatch-envelope) is the canonical home for the event-registration surface and the `[:event-id {arg-map}]` shape. This Spec's contract slots *into* the existing registration metadata map per [001 §Registration grammar](001-Registration.md#registration-grammar); it does not redefine the registration surface.

### 2. App-db marks (per frame) — `add-marks` / `set-marks`

Two dedicated registration kinds declare path-marks against `app-db`, symmetric in shape:

- **`add-marks`** — additive merge. Paths supplied MERGE into the frame's existing mark-set; unmentioned paths keep their prior state. Repeat calls accumulate.
- **`set-marks`** — wholesale replace. Paths supplied REPLACE the frame's prior mark-set; unmentioned paths are CLEARED.

Both take the same `{path mark, ...}` shape — a path-keyed map from `get-in`-shaped path vectors to mark keywords (`:sensitive` or `:large`).

```clojure
(rf/add-marks :rf/default
  {[:user :ssn]            :sensitive
   [:auth :token]          :sensitive
   [:auth :refresh-token]  :sensitive
   [:docs :csv-upload]     :large
   [:logs :history-buffer] :large})

;; App-db inspection in Causa renders:
;;   {:user {:ssn :rf/redacted :name "Alice"}
;;    :auth {:token :rf/redacted}
;;    :docs {:csv-upload :rf/large {:bytes 4523198 :head "ID,Name,Email\n..."}}}
```

Signatures:

```clojure
(add-marks frame-id {path mark, ...})
(set-marks frame-id {path mark, ...})
```

`frame-id` is the first positional arg (matching the asymmetry of `reg-app-schema` per [010 §`app-db` schemas — path-based](010-Schemas.md#app-db-schemas--path-based)). The whole declaration is **frame-scoped** — `:rf/default` and a wizard's `:wizard-frame` carry independent mark sets.

**Last-write-wins between `add-marks` and `set-marks`** when called against the same frame. `set-marks` is the declarative form (whatever the caller passes IS the frame's mark-set); `add-marks` is the incremental form (use when feature-modular code registers its own marks across multiple modules).

Both fns return the `frame-id` they registered against, per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

**Marks are pure declarations.** Neither `add-marks` nor `set-marks` mutates `app-db`, installs an interceptor, or changes any handler's view of the data. The declaration only feeds the mark-lookup table the observation surfaces consult.

### 3. Subscriptions — `reg-sub`

Subscriptions support **two override granularities** in their registration map, both supported simultaneously:

```clojure
;; Default propagation: a sub reading any sensitive app-db path
;; emits sensitive output. No declaration needed.
(rf/reg-sub :user-profile
  :<- [:db/users]
  (fn [users [_ uid]] (get users uid)))
;; Output marked :rf/redacted in Causa's sub panel because the upstream
;; [:user :ssn] path was declared sensitive via add-marks / set-marks.

;; Per-path declaration: explicit mark on slots of the sub's output.
(rf/reg-sub :computed-credentials
  {:sensitive [[:hashed]]
   :large     [[:audit-log]]}
  (fn [_] {:hashed (hash-fn ...) :audit-log (...)}))

;; Whole-output opt-out: author asserts they sanitised before returning.
(rf/reg-sub :user-display-name
  {:sensitive? false}
  :<- [:db/users]
  (fn [users [_ uid]]
    (-> users (get uid) :display-name)))  ;; only the safe field

;; Whole-output opt-in: force-mark even when no upstream input is sensitive.
(rf/reg-sub :synthetic-secret-derivation
  {:sensitive? true}
  (fn [_] (compute-secret-from-public-inputs)))
```

The grammar:

| Key | Effect | Combines with |
|---|---|---|
| `:sensitive [paths]` | Mark the listed paths inside the sub's output as sensitive. Additive on top of propagation. | `:sensitive?` (whole-output) wins on conflict; per-path adds to whatever the whole-output rule decided. |
| `:large [paths]` | Mark the listed paths inside the sub's output as large. Additive on top of propagation. | `:large?` (whole-output) wins on conflict. |
| `:sensitive? true` | Force the sub's entire output sensitive regardless of inputs. | Overrides propagation. The whole output renders as `:rf/redacted`. |
| `:sensitive? false` | Explicit opt-out — the sub's output is safe to surface even if upstream inputs were sensitive. | Overrides propagation. Per-path `:sensitive [paths]` declarations still apply. |
| `:large? true` / `:large? false` | Symmetric to `:sensitive?`. | Symmetric. |

**Default behaviour (no declaration):** propagate. A sub reading any `app-db` path (or any input sub) carrying a `:sensitive` mark yields a sensitive output. This is the right default — most subs that read sensitive data forward it; the few that explicitly sanitise are the exception and opt out.

### 4. Effects — `reg-fx`

Paths in an effect's `:sensitive` / `:large` declaration index into the **fx-input map** the framework hands to the fx handler. For the `[fx-id <args>]` shape (per [Spec-Schemas §`:rf/effect-map`](Spec-Schemas.md#rfeffect-map)), paths root at the args.

```clojure
(rf/reg-fx :rf.http/managed
  {:doc       "Issue a managed HTTP request."
   :sensitive [[:body :password]
               [:body :ssn]
               [:headers :authorization]
               [:headers :cookie]]
   :large     [[:body :upload]
               [:body :csv-blob]]}
  (fn [_ {:keys [body headers] :as req}]
    ;; The fx handler receives REAL values to put on the wire.
    ;; Trace-bus emits the fx invocation with the declared paths sentinel-substituted.
    (issue-http-request req)))

;; Trace-bus / Causa display of the fx invocation:
;;   [:rf.http/managed {:method :post
;;                      :url "/api/login"
;;                      :body    {:email "alice@…" :password :rf/redacted}
;;                      :headers {:authorization :rf/redacted}}]
```

The fx handler ALWAYS sees real values — the framework does not redact before invoking the handler. Only the trace-bus emission of the fx invocation (and any downstream observer of that trace event) sees the sentinels.

### 5. Coeffects — `reg-cofx`

Paths in a cofx's `:sensitive` / `:large` declaration index into the **value the cofx injects** into the coeffects map. Empty path `[[]]` marks the entire injected value.

```clojure
(rf/reg-cofx :auth/jwt
  {:doc       "Inject current JWT from session storage."
   :sensitive [[]]}                       ;; whole injected value is sensitive
  (fn [cofx]
    (assoc cofx :auth/jwt (read-jwt-from-storage))))

(rf/reg-cofx :user/profile
  {:doc       "Inject the current user's profile."
   :sensitive [[:ssn] [:dob]]
   :large     [[:avatar-bytes]]}
  (fn [cofx]
    (assoc cofx :user/profile (load-profile))))

;; Trace-bus emit of the assembled cofx-map (under :event/do-fx tags):
;;   {:auth/jwt     :rf/redacted
;;    :user/profile {:ssn :rf/redacted
;;                   :dob :rf/redacted
;;                   :name "Alice"
;;                   :avatar-bytes :rf/large {:bytes 84219 :head "..."}}}
```

The handler that consumes the cofx sees the real values; the mark propagates through the handler into any `app-db` write or `:fx` emission that threads the value (per [§Propagation rules](#propagation-rules) below).

### 6. State machines — `reg-machine`

Each machine instance has a `:data` slot (analogous to XState's `context`) — guards and actions read from `:data`, and the instance's lifetime means `:data` is a long-lived sensitive surface. Paths in `:sensitive` / `:large` on `reg-machine` index into the machine snapshot, rooted at `:data`.

```clojure
(rf/reg-machine :auth/session
  {:doc       "User session state machine."
   :sensitive [[:data :jwt]
               [:data :refresh-token]
               [:data :user :ssn]]
   :large     [[:data :audit-trail]]}
  {:initial :idle
   :states  {:idle           {:on {:log-in :authenticating}}
            :authenticating {:spawn {:src :auth/fetch-jwt
                                       :on-done :authenticated}}
            :authenticated  {:on {:log-out :idle}}}})

;; Causa's Machine Inspector renders the :data slot with marks resolved:
;;   :data {:jwt :rf/redacted
;;          :refresh-token :rf/redacted
;;          :user {:ssn :rf/redacted :name "Alice"}
;;          :audit-trail :rf/large {:bytes 12382 :head "..."}}
```

The machine's transition table, guards, and actions all see real `:data` values when they fire. Trace events under `:rf.machine/*` (per [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary)) consult the declaration when they emit `:rf.machine/transition`, `:rf.machine/snapshot-updated`, and any other emit site that lifts `:data` onto a trace event.

**Path convention.** Marking paths in `reg-machine` are rooted at the machine snapshot (NOT at `:data` directly), because the snapshot also carries `:state` and other reserved keys (per [005 §Reserved snapshot-internal keys](005-StateMachines.md#reserved-snapshot-internal-keys-rf2-33y0y)). Authors who want to mark every `:data` slot wholesale write `[[:data]]`.

### 7. Flows — `reg-flow`

Flows are derived state from `app-db` paths (per [013](013-Flows.md)). They share subscriptions' propagation/override semantics: paths in the flow's `:inputs` carry their marks through to the flow's `:output`, the author can override per registration, and per-path declarations may also mark slots of the output value.

```clojure
;; Default propagation: a flow whose inputs include sensitive app-db paths
;; emits a sensitive output (the resulting :path write inherits the mark).
(rf/reg-flow
  {:id     :computed/full-name
   :inputs [[:user :first-name] [:user :last-name]]
   :output (fn [first last] (str first " " last))
   :path   [:computed :full-name]})

;; Per-path declaration on the flow's output.
(rf/reg-flow
  {:id        :computed/auth-summary
   :sensitive [[:token-hash]]
   :large     []
   :inputs    [[:auth :token] [:auth :user]]
   :output    (fn [token user]
                {:token-hash (hash token) :user-display (:name user)})
   :path      [:computed :auth-summary]})

;; Whole-output opt-out: author hashed the token, so the result is safe.
(rf/reg-flow
  {:id         :computed/hashed-token
   :sensitive? false
   :inputs     [[:auth :token]]
   :output     (fn [token] (hash token))
   :path       [:computed :token-hash]})
```

The grammar matches subscriptions row-for-row:

| Key | Effect |
|---|---|
| `:sensitive [paths]` | Per-path marks on slots of the flow's `:output` value. |
| `:large [paths]` | Per-path marks on slots of the flow's `:output` value. |
| `:sensitive? true / false` | Whole-output force-mark or opt-out. Overrides propagation. |
| `:large? true / false` | Symmetric to `:sensitive?`. |

The flow's `:path` write into `app-db` carries the resolved sensitivity — Causa's App-DB-Diff panel sees `:rf/redacted` at the destination slot just as if `add-marks` / `set-marks` had declared the path directly.

## The display contract — sentinels

Three sentinel forms span the two-axis space. **The sentinel keywords are framework-reserved per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)**; apps MUST NOT use `:rf/redacted` or `:rf/large` as legitimate payload values.

### `:rf/redacted` — sensitive only

```clojure
{:user/ssn :rf/redacted}
```

An opaque keyword. The substituted value carries **no information** about the underlying content — not its type, not its size, not a hash, not a prefix. The framework's contract with the user is unambiguous: a sensitive value is not revealable by any observation surface.

### `:rf/large {:bytes N :head "..."}` — large only

```clojure
{:docs/csv-upload :rf/large {:bytes 4523198 :head "ID,Name,Email\n42,Alice,…"}}
```

A two-element clause: the sentinel keyword `:rf/large` followed by a metadata map carrying:

| Key | Meaning |
|---|---|
| `:bytes` | Integer. Byte size of the original value (or a close approximation — implementations MAY use string-length or `count` over a serialised form). |
| `:head` | String. First N characters of a printable rendering of the value. N is implementation-defined (CLJS reference uses 128 chars). May be absent for non-string values that have no printable head. |

Causa's Event Detail and App-DB-Diff panels MAY surface a click-to-expand affordance for `:rf/large` entries, conditional on a per-row size-confirmation modal so the user does not accidentally inflate the panel with a multi-megabyte expansion.

### `:rf/redacted {:bytes N}` — sensitive + large composed

```clojure
{:internal/diff-blob :rf/redacted {:bytes 4523198}}
```

When a value is marked **both** sensitive and large, the sensitive sentinel wins on content visibility — `:rf/redacted` rides the head slot — but the size metadata MAY ride alongside (no `:head` is permitted; the content is sensitive). This preserves the size diagnostic without leaking content.

### Causa rendering contract (the consumer)

| Mark axis | Causa renders | Drillable? |
|---|---|---|
| `:sensitive` only | `:rf/redacted` | **NO** — never revealable; no expand affordance offered. |
| `:large` only | `:rf/large {:bytes N :head "…"}` | **YES** — click-to-expand with size-confirmation modal. |
| Both | `:rf/redacted {:bytes N}` | **NO** — content not revealable; size info displayed inline. |

The rule for any consuming tool is uniform: **`:rf/redacted` MUST NOT be expandable, ever.** A tool that offers a "show original" affordance against `:rf/redacted` is non-conformant; that affordance is the exact leak the contract exists to prevent. `:rf/large` MAY be expanded by tools that surface the affordance, subject to whatever per-tool UX safeguard (size confirmation, off-by-default switch) the tool's spec requires.

## Propagation rules

The framework auto-propagates marks across the dataflow as **footgun prevention** — not as a security-grade taint system. The seven boundaries marks cross:

### 1. Event-args → app-db

When an event handler writes a value sourced from a sensitive event-arg path into an `app-db` path that was not previously marked, the **destination app-db path inherits the mark** transitively. The framework tracks this either by (a) instrumenting the writes during the handler's run (taint propagation) or by (b) computing a path-graph union at trace-bus emit time. Both are conforming implementations; see [§Implementation notes](#implementation-notes).

```clojure
(rf/reg-event-db :auth/log-in-success
  {:sensitive [[:jwt]]}
  (fn [db [_ {:keys [jwt user-id]}]]
    (-> db
        (assoc-in [:auth :token] jwt)        ;; ← destination path inherits :sensitive
        (assoc-in [:user :id] user-id))))

;; After this handler runs, Causa's App-DB-Diff panel renders:
;;   {:auth {:token :rf/redacted}     <- mark propagated from event-arg [:jwt]
;;    :user {:id 42}}                  <- not marked
```

### 2. App-db → subs

A sub whose computation reads any sensitive `app-db` path yields a sensitive output by default. The mark propagates through the sub-cache; consumers of the sub's value (views, downstream subs, cofx, fx that thread the value) see the propagated mark.

Override per registration (per [§3. Subscriptions](#3-subscriptions--reg-sub) above):

- `{:sensitive? false}` opts out — the author has sanitised internally.
- `{:sensitive? true}` opts in even with no sensitive input — the sub derives a secret from public inputs.
- `{:sensitive [paths]}` adds per-path marks on the output slots.

### 3. App-db → flows

Symmetric to subs. A flow whose `:inputs` include any sensitive `app-db` path yields a sensitive `:output` by default; the `:path` write in `app-db` carries the mark. Same override grammar.

### 4. Subs → fx

If a handler reads from a sensitive sub (via cofx-wrapping per [Guide ch.05 §Reading a sub from a handler](../docs/guide/06-coeffects.md#reading-a-sub-from-a-handler)) and threads the value into an `:fx` entry, the fx invocation's trace-bus emission inherits the mark on the slot the value lands in. Per-fx `:sensitive` declarations (per [§4. Effects](#4-effects--reg-fx) above) add to whatever the upstream propagation produced.

### 5. Cofx → handler → fx

Cofx-injected values carry their marks through the handler into any `app-db` write or `:fx` emission. A handler that pulls `:auth/jwt` (marked `:sensitive [[]]`) from cofx and writes it into `[:auth :token]` causes the app-db path to inherit; one that threads it into `[:rf.http/managed {:headers {:authorization jwt}}]` causes the fx-input path to inherit.

### 6. Interceptors

Interceptors thread context; they do not have their own marking site. Marks flow through the interceptor chain from event-args, cofx, and `app-db` reads; the runtime's emit machinery resolves marks against whatever the interceptor's context holds at observation time.

### 7. HTTP response → `:on-success` event

HTTP response data lands in the user-space `:on-success` event payload (per [014 §Reply-payload shape](014-HTTPRequests.md#reply-payload-shape)). The mark for that data is declared on the **event handler** that receives the reply, NOT on `:rf.http/managed` (which doesn't know which paths in the response will be sensitive). The standard `reg-event-fx :on-success-id {:sensitive [...]}` pattern applies.

### Override at any boundary

Every boundary respects an explicit override. The most common form is `{:sensitive? false}` on a sub or flow — the author has asserted that their derivation sanitised. Less commonly, `{:sensitive? true}` force-marks a derived value that started from non-marked inputs (a hash, an encrypted blob, a derived authentication artefact).

The framework trusts the override. A determined contributor who writes `{:sensitive? false}` on a sub that returns the JWT *will* leak the JWT through the trace bus; the contract is observability hygiene, not authorisation.

## Relationship with schema-attached marks

[010-Schemas](010-Schemas.md) supports `:sensitive?` and `:large?` per-slot metadata on the schema value passed to `reg-app-schema`. That mechanism remains valid and continues to populate the framework's elision registry slot at `[:rf/elision :sensitive-declarations]` (per [010 §`:sensitive?` — privacy in schema-validation error traces](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z)).

The two declaration sources **merge** at lookup time. The mark-lookup table the observation surfaces consult is the union of:

- Schema-attached `:sensitive?` / `:large?` per-slot metadata (per 010).
- `add-marks` / `set-marks` and per-registration declarations (per this Spec).
- Propagated marks (per [§Propagation rules](#propagation-rules)).

Conflict between the two sources is resolved by union — if a path is declared sensitive by *either* source, the path is sensitive. There is no way for one source to *unmark* a path the other source marked; the only way to opt out of a propagated mark on a derived value is the `{:sensitive? false}` whole-output override on the deriving registration (sub or flow).

**The recommendation.** Apps already running rich schemas may continue to use the per-slot `:sensitive?` / `:large?` metadata for `app-db` paths; the schema-attached form colocates the mark with the shape declaration. Apps without schemas, or apps whose schemas don't cover the marked surface, use `add-marks` / `set-marks`. Per-registration declarations (`reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-machine`, `reg-flow`) live at their respective registration sites regardless of schema coverage — schemas don't cover event-arg, fx-input, cofx-injection, or machine-data shapes anyway.

## Reserved keys and namespaces

Per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned), the following identifiers are framework-reserved by this Spec:

| Reserved | What | Site |
|---|---|---|
| `:rf/redacted` | Sentinel keyword for sensitive content. Substituted at observation surfaces. | [§Display contract](#the-display-contract--sentinels) |
| `:rf/large` | Sentinel keyword for large content; appears as the head of a `[:rf/large {:bytes N :head "..."}]` clause. | [§Display contract](#the-display-contract--sentinels) |
| `:sensitive` | Optional key on every `reg-*` registration metadata map (per [001-Registration §Registration grammar](001-Registration.md#registration-grammar)). Value: vector of paths. | [§Seven first-class marking sites](#the-seven-first-class-marking-sites) |
| `:large` | Symmetric to `:sensitive`. Optional key on every `reg-*` registration metadata map. Value: vector of paths. | [§Seven first-class marking sites](#the-seven-first-class-marking-sites) |
| `:sensitive?` | Optional boolean key on `reg-sub` and `reg-flow` for whole-output override (per `{:sensitive? true/false}`). | [§3. Subscriptions](#3-subscriptions--reg-sub), [§7. Flows](#7-flows--reg-flow) |
| `:large?` | Symmetric to `:sensitive?`. Optional boolean key on `reg-sub` and `reg-flow`. | [§3. Subscriptions](#3-subscriptions--reg-sub), [§7. Flows](#7-flows--reg-flow) |
| `add-marks` / `set-marks` | Two new registration kinds. Declare path-marks against an `app-db`, scoped to one frame. `add-marks` merges additively; `set-marks` replaces the frame mark-set wholesale. | [§2. App-db marks (per frame) — `add-marks` / `set-marks`](#2-app-db-marks-per-frame--add-marks--set-marks) |

Apps MUST NOT use the sentinel keywords as legitimate payload values; doing so collides with the framework's substitution semantics and the observation surfaces will render the legitimate value as if it were a substituted sentinel.

## Implementation notes

Implementations have latitude on **how** marks propagate, subject to the observable contract above. Two approaches both conform:

**(A) Taint-tracking on writes.** Instrument every `assoc` / `assoc-in` / `update-in` inside event-handler execution and at sub-output assembly time; when the source value's path carries a mark, record the mark against the destination path in the elision registry. Higher implementation cost (the runtime instruments writes); produces stable, persistent marks visible to every subsequent observation.

**(B) Path-graph union at emit time.** At trace-bus emit time, the runtime walks the in-scope handler's `:sensitive` declaration and the propagation graph (event-arg paths → app-db write paths, app-db paths → sub paths via the sub-cache's dependency graph, etc.) and computes the union for the specific emit site. Lower implementation cost (no write instrumentation; mark resolution happens lazily); produces marks only at the boundaries where they are needed for the contract.

**The recommendation for the CLJS reference: approach B.** The trace bus is already the natural emit-time chokepoint; computing the union there localises the mark-lookup cost to the path that pays for observability (and that compiles out in production via `goog.DEBUG`). The cost is one path-walk per trace event under the dev-only gate; in production, the entire mechanism elides per [009 §Production elision](009-Instrumentation.md#production-elision).

The contract is **observable behaviour**, not the implementation approach. A port that chooses approach A is conforming as long as the sentinels appear at the same paths in the same observation surfaces. Conformance fixtures under [conformance/](conformance/README.md) assert the observable contract; they do not assert the propagation mechanism.

### Hot-path cost

- **Production builds:** zero. The entire mark-lookup machinery rides the `re-frame.interop/debug-enabled?` gate that elides the trace bus per [009 §Production elision](009-Instrumentation.md#production-elision). `add-marks` / `set-marks` and per-registration declarations do still register at boot (they go through the same registrar slot as every other `reg-*`); the declarations sit in the registry consuming a constant amount of memory but are never consulted in production builds because no emit site fires.
- **Dev builds:** one path-walk per trace event per declared mark, gated by mark-set size. For the typical app (10–30 marked paths across 5–8 registrations), this is sub-millisecond per emit on the CLJS reference and dominated by the existing trace-event-assembly cost.

### Mark-lookup table shape

The CLJS reference materialises the merged mark set at `[:rf/elision :sensitive-declarations]` and `[:rf/elision :large-declarations]` per-frame, keyed by absolute path:

```clojure
;; Reference shape (CLJS, not pattern-required)
{:rf/elision
 {:sensitive-declarations
  {[:user :ssn]      {:source :marks}
   [:auth :token]    {:source :marks}
   [:auth :jwt]      {:source :propagated :from {:event :auth/log-in-success :arg-path [:jwt]}}}
  :large-declarations
  {[:docs :csv-upload] {:source :marks}}}}
```

The `:source` slot is for tooling (Causa's "why is this redacted?" affordance reads it); the lookup contract only requires the path → presence mapping. Per-source attribution is a CLJS-reference convenience.

## Author guidance for the exception-path residual

The path-marked declarations in this Spec redact at the five observation surfaces named in [§Scope](#in-scope--the-five-observation-points-marks-must-guard). They walk **known data shapes** — the trace event's `:tags :event` slot, `:tags :app-db-after`, `:tags :sub-output`, and friends — and substitute sentinels at marked paths. They do NOT walk:

- **Exception messages.** Once a sensitive value has been concatenated into an `ex-message` string, no path resolves to the substring; the walker has no rule that says "this substring of this string is a marked leaf."
- **`ex-data` maps.** The map's keys are author-chosen (`{:user/email "..."}`); they have no relationship to the path-marked declarations in `[:rf/elision :sensitive-declarations]`. A walker rule that scrubbed `:user/email` would either need a separate ex-data-key registration (which would duplicate the path declaration and drift) or auto-detect by value comparison (the [§Out of scope §Full taint-tracking system](#out-of-scope-explicit-non-goals) non-goal).

The residual surface is the intersection of *the handler read a sensitive-path value* AND *the handler then threw with that value in the message or the ex-data map*. The `:rf.error/handler-exception` trace event ([Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) the cascade emits carries the raw value in `:exception-message` / `:exception-data`. The top-level `:sensitive?` rollup fires (because some leaf in the record overlapped a marked path) and off-box shippers drop the whole event — but the on-box dev surfaces (Causa Event Detail, the re-frame2-pair-mcp surface under `:show-sensitive? true`, story scenarios saved for replay) render the exception fields verbatim.

```clojure
;; ANTI-PATTERN — the email lands in the trace event verbatim.
(rf/reg-event-fx :auth/log-in
  (fn [{:keys [db]} [_ {:keys [submitted-password]}]]
    (let [email (get-in db [:user :email])]    ;; [:user :email] is marked sensitive
      (throw (ex-info (str "User " email " failed login")
                      {:user/email email :reason :invalid-credentials})))))

;; PREFERRED — name the category; omit / sentinel-stamp the value.
(rf/reg-event-fx :auth/log-in
  (fn [{:keys [db]} [_ {:keys [submitted-password]}]]
    (let [email (get-in db [:user :email])]
      (throw (ex-info "Invalid credentials"
                      {:reason :invalid-credentials})))))
```

The author MUSTs at the assembly site:

- **Name the *category* of failure in the exception message, not the value.** A category-only message ("Invalid credentials") plus `:dispatch-id` correlation against the (correctly redacted) `:app-db-before` snapshot recovers the failing user identity for the dev without leaking it into the trace.
- **If the structure of the failing context is essential, substitute `:rf/redacted` at the assembly site.** `(throw (ex-info "User :rf/redacted failed login" {:user/email :rf/redacted}))` matches the sentinel form the walker emits everywhere else; the dev's mental model is uniform.
- **Pick a per-app convention.** A twelve-line `safe-throw` helper that takes a category keyword, an optional context map, and an optional scrub-key set is the recommended shape. Worked example and three patterns lives in [docs/guide §24.08 — Exceptions under :sensitive?](../docs/guide/26-config.md#exceptions-under-sensitive).

The framework deliberately does NOT ship a `rf/safe-throw` helper. The call-site knowledge of *which ex-data keys correspond to sensitive paths in this specific app* is author knowledge, not framework knowledge — a framework helper would either demand the author name the scrub keys at every call (no value over an in-app helper) or auto-detect (the rejected taint-tracking non-goal). The right shape is a per-app convention; the framework's job is the five path-walked observation surfaces.

Per rf2-dv79m (docs-side complement to rf2-4ku9l / Spec 015's path-marked redaction).

## Tests

Conformance fixtures under [conformance/](conformance/README.md) assert the observable contract; the normative set:

| Fixture | What it asserts |
|---|---|
| `data-classification/event-arg-sensitive-path-redacts-in-trace.edn` | A `reg-event-fx` with `:sensitive [[:password]]`, when dispatched with `{:password "secret"}`, produces an `:event/dispatched` trace event whose `:tags :event` slot renders `[:event-id {:password :rf/redacted}]`. |
| `data-classification/app-db-sensitive-path-redacts-in-diff.edn` | A frame with `set-marks {[:user :ssn] :sensitive}`, after dispatching an event that writes `"123-45-6789"` to `[:user :ssn]`, produces an `:event/db-changed` trace event whose `:tags :app-db-after` slot renders `:rf/redacted` at that path. |
| `data-classification/sub-auto-propagates-sensitivity.edn` | A `reg-sub` reading `[:user :ssn]` (marked sensitive at `set-marks`) with no `:sensitive?` override produces a `:sub/run` trace event whose output value is marked sensitive (the sub's value, when re-emitted into a downstream trace event, renders as `:rf/redacted`). |
| `data-classification/sub-explicit-opt-out-honoured.edn` | A `reg-sub` reading sensitive app-db data with `{:sensitive? false}` produces a `:sub/run` trace event whose output is NOT marked (the value renders unredacted). |
| `data-classification/combined-sensitive-and-large.edn` | A path declared in both `:sensitive` and `:large` (or marked by one source and inheriting from another) renders as `:rf/redacted {:bytes N}` — the combined sentinel form. |
| `data-classification/cofx-empty-path-redacts-whole.edn` | A `reg-cofx` with `{:sensitive [[]]}` produces a trace event whose corresponding cofx-map slot renders `:rf/redacted` for the whole injected value. |
| `data-classification/machine-data-sensitive-path-redacts-in-snapshot.edn` | A `reg-machine` with `:sensitive [[:data :jwt]]`, after a transition that writes a JWT into `:data`, produces an `:rf.machine/snapshot-updated` trace event whose `:tags :snapshot :data :jwt` slot renders `:rf/redacted`. |
| `data-classification/flow-output-inherits-from-input.edn` | A `reg-flow` whose `:inputs` include a sensitive app-db path produces a flow `:path` write whose value is marked sensitive in the downstream `:event/db-changed` trace event. |
| `data-classification/set-marks-replaces-not-merges.edn` | A second `set-marks` call against the same frame *replaces* the previous declaration set (the previous set's paths no longer redact). |
| `data-classification/add-marks-merges-not-replaces.edn` | A second `add-marks` call against the same frame MERGES into the previous declaration set (the previous set's paths still redact). |
| `data-classification/schema-and-app-db-marks-union.edn` | A path declared sensitive by schema (per [010 §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z)) AND a different path declared sensitive by `add-marks` / `set-marks` both redact in the same observation; the two sources union. |

Per-artefact unit tests cover the implementation-specific propagation mechanism (approach A vs B); the conformance fixtures cover only the observable contract.

## Cross-references

- [001-Registration §Registration grammar](001-Registration.md#registration-grammar) — the metadata-map shape this Spec extends with `:sensitive` / `:large` keys.
- [002-Frames §Routing — the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope) — canonical home for the event-registration surface and the `[:event-id {arg-map}]` shape the event-handler path-marks index into.
- [005-StateMachines §Snapshot shape](005-StateMachines.md#snapshot-shape) — the snapshot the `reg-machine` path-marks root at; the `:data` slot is the long-lived sensitive surface.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — the sub-cache dependency graph the propagation rule traverses to derive sub-output marks from app-db marks.
- [009-Instrumentation §The trace event model](009-Instrumentation.md#the-trace-event-model) — the emit site every observation surface lifts from.
- [009-Instrumentation §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the existing trace-bus privacy posture this Spec generalises from whole-handler scope to per-path scope.
- [010-Schemas §`:sensitive?`](010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces-rf2-kj51z), [010-Schemas §`:large?`](010-Schemas.md#large--schema-driven-size-elision-nomination-rf2-nwv63) — the schema-attached per-slot marks that union with this Spec's declarations.
- [013-Flows](013-Flows.md) — `reg-flow`'s registration shape that the flow-side path-marks extend.
- [014-HTTPRequests §Reply-payload shape](014-HTTPRequests.md#reply-payload-shape) — HTTP response data lands in the `:on-success` event payload; marking happens on the receiving event handler.
- [Conventions §Reserved indicator slots](Conventions.md#reserved-indicator-slots-mcp-shaped-returns) — the cross-MCP wire-vocabulary slots (`:dropped-sensitive`, `:elided-large`) that surface counters of sentinel substitutions on MCP tool responses.
- [Security §Privacy / secret handling](Security.md#privacy--secret-handling) — pattern-level security posture; this Spec is the per-path declarative mechanism that grounds the pattern-level MUSTs documented there.
- [Security §Author guidance for exceptions under path-level `:sensitive?`](Security.md#author-guidance-for-exceptions-under-path-level-sensitive) — pattern-level MUSTs for the exception-path residual surface this Spec leaves to the author.
- [docs/guide §24.08 — Exceptions under `:sensitive?`](../docs/guide/26-config.md#exceptions-under-sensitive) — author-side worked example, three patterns, and a copyable `safe-throw` helper convention.
- [`tools/mcp-base/spec/sensitive.md`](../tools/mcp-base/spec/sensitive.md), [`tools/mcp-base/spec/elision.md`](../tools/mcp-base/spec/elision.md) — the cross-MCP wire-elision walker that consumes the same marks at the MCP wire boundary.
