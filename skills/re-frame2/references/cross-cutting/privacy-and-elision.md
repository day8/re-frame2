# Privacy and size elision — the egress-policy model

re-frame2's killer feature is also its leak: one runtime story feeds every tool and every off-box shipper, so an auth token in an event vector is reachable by every trace listener, every Datadog dashboard, every MCP server an agent attached to. A multi-megabyte `app-db` slice is the same shape of problem — a denial-of-service against your own observability. Both have one answer.

> **Mental model: classify a path, redact at the boundary — fail-open.** If you have used Rails' filtered parameters, a logging redactor, or a DLP/data-classification scheme, the move is familiar — *but inverted from the usual mistake.* The wrong answer is to filter at each consumer ("tell every tool to drop the password"); the one that doesn't, leaks everything. re-frame2 classifies a *path* once **at the owner of that data's shape**, projects at the **trust boundary**, and every **sink receives an already-safe record**. You declare; the framework projects; sinks consume. Critically: it is **fail-open** — a path you never classify ships raw, and there is **no propagation or taint**. This is hygiene, not a guarantee.

This leaf is the recipe and when-to-reach-for-it. The depth — the failure posture, the exception gap, the three sentinels, the SSR allowlist — lives in [Keep secrets out of traces](../../../../docs/guide/how-to/keep-secrets-out-of-traces.md). The normative home is [`spec/015-Data-Classification.md`](../../../../spec/015-Data-Classification.md).

## The one law

> **Classification** names facts — *this path is sensitive, this slot is large.*
> **Projection** applies them at a boundary — the framework op that returns a record safe to cross *this* trust boundary.
> **Sink policy** routes the projected records — Datadog, Sentry, Xray, MCP, the SSR response.
>
> **App authors declare classification (the paths) and sink policy. The framework performs projection. Sinks consume already-projected records only.**

Everything you write is a *declaration of paths*; everything the framework runs is *projection*; a sink never hand-rolls redaction and neither do you.

Two different defaults sit at two different surfaces, and conflating them is the common error:

- **Classification is fail-OPEN.** A path you forget to classify ships raw — that's the hygiene bargain, not a leak the framework guards against. Classify exactly the paths you care about; a re-keyed or rendered secret ships raw until *its* path is classified too.
- **Projection is fail-CLOSED on the unknowns it *can* see.** An unknown frame, an unschematized HTTP body, an unknown egress profile — the projector redacts the whole value or errors rather than leak under invented scope.

This is a **leak-prevention overlay on observability, not a security boundary.** Your app still owns auth, authorisation, encryption-at-rest, and transport. What this buys you is that the framework's *own* observation surfaces cannot accidentally exfiltrate a *classified* secret or stuff a record with megabytes.

## Where you declare it: the three-owner table

Classification attaches to **whoever owns the data's shape**, and you always classify a *path* (or, for transient payloads, a path into the payload shape). That single rule has three answers:

| The data is… | Owner | Declare it on… | Shape |
|---|---|---|---|
| Durable `app-db` state (auth tokens, partner keys, big uploads) | the **event** that writes / initialises it | `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` **classification effects** returned alongside `:db` | a vector of paths: `:sensitive [[:auth :token]]` |
| Subsystem instance data — machine `:data`, resource data/params | the **subsystem definition** | projection-relative `:sensitive` / `:large` on `reg-machine` / `reg-resource`, lowered per instance | a vector of paths relative to the instance: `:sensitive [[:data :token]]` |
| Transient payloads — event args, fx/cofx args, sub outputs, flow outputs, HTTP bodies | the **registration** (or its `:decode` schema) that introduces the shape | `reg-event` / `reg-sub` / `reg-fx` / `reg-flow` `:sensitive` / `:large` metadata; `:sensitive?` props on a `:decode` schema | a vector of paths: `[[:password]]` |

Hold this one line in your head: *durable app-db → classification effects from the writing event; subsystem instance data → projection-relative declaration on the subsystem; transient payloads → registration metadata.* Three owners, three surfaces, no overlap, and **each classifies a path it owns** — the same secret crossing two surfaces (an HTTP reply *and* its durable app-db copy) is two declarations, because there is no propagation between them.

**`:sensitive` (no `?`) vs `:sensitive?` (with `?`) is deliberate, not an inconsistency.** `:sensitive` names a *collection of paths* (a set → plural, bare) — the classification effects, the registration marks, the subsystem declaration. `:sensitive?` answers a *yes/no question about one schema slot* (a boolean → predicate `?`) and survives **only** for a schema's own validation-failure-trace redaction and for the schema-owned *transient* products (HTTP `:decode` body, resource params). Same for `:large` / `:large?`. Because `:sensitive` already means "a collection of paths," there is no `:sensitive false` — and no declassification key, because nothing propagates (see [no propagation](#no-propagation-no-taint)).

## 1 — Event-owned: durable app-db state

Durable `app-db` classification rides **four commit-plane effects** a handler returns alongside `:db`, in the same event:

```clojure
:sensitive       [[path] …]   ; classify each path sensitive (redact at egress)
:large           [[path] …]   ; classify each path large    (size marker at egress)
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

Where you return them tracks *when* the path's meaning is fixed — known paths in the frame's init event, discovered ones in the handler that writes them:

```clojure
;; known at authoring — classify in the frame's init event
(rf/reg-event :app/init
  (fn [{:keys [db]} _]
    {:db        (assoc db :auth {})
     :sensitive [[:auth :token] [:auth :refresh-token] [:tenant :partner-api-key]]
     :large     [[:documents :csv-upload] [:reports :raw-export]]}))

;; discovered at runtime — classify in the handler that writes it
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))
```

Wire the init event with `:initial-events [[:app/init]]` so the classification is in force before any off-box egress. Worth knowing:

- The effects are applied **with the `:db` write** (a frame-state transform at the commit point, recorded in the per-frame elision registry), *not* a later `:fx`. A path classified in an event is redacted from its *first* egress.
- **Classification is value-independent.** Classify a path before a value lands there — the classification redacts whatever later occupies it; over an absent path it's a harmless no-op.
- **The two axes are independent.** `:clear-sensitive` never touches the large axis, and vice-versa. Clearing is rarely reached for — when data disappears, its classification is a no-op anyway.
- **Sensitive wins over large.** A path that's both redacts as sensitive and emits *no* size marker (the marker's `:path` / `:bytes` would themselves leak structure).
- Malformed effect payloads (a non-vector value, a non-path entry, an unknown axis) **fail loud pre-commit** with `:rf.error/classification-effect-shape` — the transition aborts, no `:db` commits.

There is **no frame `:sensitive {:app-db …}` annotation** and **no schema-prop route** to classify a durable app-db path — the event is app-db's definition site, full stop. Frame-local HTTP carrier names (`:sensitive {:http …}`) and observability sink policy *do* still live on the frame (see [§Choosing where observations go](#choosing-where-observations-go-frame-observability)); they are a different surface from durable app-db classification.

## 2 — Subsystem-owned: machine and resource instance data

A runtime subsystem owns its storage, so you never name an absolute runtime path. You declare `:sensitive` / `:large` **projection-relative to the instance's shape** on the subsystem definition, and the framework lowers it into the registry per instance (at spawn / fetch) and drops it on teardown:

```clojure
;; the :data-schema still VALIDATES :data; the :sensitive declaration below is
;; what classifies :data for SNAPSHOT egress (EP-0025 reverses the old
;; schema-prop → snapshot-redaction bridge).
(rf/reg-machine :checkout/payment
  {:sensitive   [[:data :payment :token]]      ;; projection-relative to one actor's :data
   :large       [[:data :payment :receipt-pdf]]
   :data-schema [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]
   :initial :collecting
   :states  {,,,}})

(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

The `[:data :payment :token]` declaration redacts that slot everywhere the machine snapshot egresses (every transition's before/after, the Xray Machine Inspector, the pair-MCP wire, the epoch record), for **every spawned actor instance**, with no per-instance author code. Rename the slot in the declaration and the classification moves with it. A malformed declaration is rejected fail-loud at registration.

> **The schema prop is a *different* axis.** A `:sensitive?` / `:large?` prop on a `:data-schema` slot (or a resource `:params-schema`) redacts that slot **only in the schema's own validation-failure trace** — the validator's egress product, which the schema owns. It does **not** classify the durable `:data` for snapshot egress; the projection-relative `:sensitive` / `:large` declaration above does that. On an **HTTP request's `:decode` schema** the `:sensitive?` prop *is* the right and only route — the response body is a *transient* payload, and the schema is its owner (the registration-owned row). Schemas own *transient* and *validation-failure* products; durable state is the effect (§1) or the subsystem declaration (here).

## 3 — Registration-owned: transient payloads

Values that flow *through* the cascade rather than *living* in durable state are owned by the registration that introduces their shape:

```clojure
(rf/reg-event
  :auth/login
  {:sensitive [[:password] [:totp-code]]}
  (fn [{:keys [db]} [_ {:keys [email password]}]]
    {:db db
     :rf.http/managed
     {:request {:method :post :url "/api/login"
                :body {:email email :password password}}}}))

(rf/reg-sub
  :partner/api-token
  {:sensitive [[]]}                ;; empty path → the whole sub output
  (fn [db _] (get-in db [:tenant :partner-api-key])))
```

The paths index into the registration's primary data shape — the event arg-map (the second element of `[:event-id {arg-map}]`), the fx-input map, the sub/flow output. The empty path `[[]]` marks the whole shape. A mark at a missing slot is a silent no-op (payload shapes evolve); a malformed path *vector* fails at registration.

A secret carried positionally (`[:auth/login "alice" "hunter2"]`) has **no stable named path** to classify — prefer the map payload form (`[:auth/login {:password "…"}]`) so the registration mark can name the key. The handler body sees `password` verbatim — handlers need real values to work. Only the *observable shadow* on a framework surface (the `:event/dispatched` trace event, the always-on event-emit record, the HTTP body) ships with `:password` → `:rf/redacted`. The registration declares the path; the framework projects it.

## Choosing where observations go: frame `:observability`

Sink policy is the other thing you declare. Production observability — the always-on handled-event / error records that survive a release build — is routed by frame `:observability`:

```clojure
(rf/reg-frame :app/main
  {:observability
   {:handled-events [{:sink :my-app.sinks/datadog
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]
    :errors         [{:sink :my-app.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]}
   :initial-events [[:app/init]]})        ;; :app/init classifies [:auth :token] via the :sensitive effect

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; Already projected. No sink-local redaction.
    (datadog/send projected-record)))
```

The framework ships no Datadog / Sentry client — the sink id lives outside the framework namespace (`:my-app.sinks/datadog`, not `:rf.sink/datadog`) and vendor options ride a local `:opts` map. The sink fn receives an **already-projected** record. Routing is **fail-closed and frame-scoped**: an unresolved frame or a frame with no `:observability` policy routes *nothing* (never synthesizes `:rf/default`); a throwing sink is isolated. Under the default `:rf.egress/off-box-observability` profile a handled-event record carries frame, event id, status, elapsed, effect keys, and correlation ids — and **omits the `:event` args slot entirely**. The low-level `register-event-listener!` / `register-error-listener!` registries remain as advanced integration APIs, not the normal production story.

## Projection profiles: which boundary is this?

The framework projects, but needs to know *which trust boundary* a record is about to cross. You answer with a named **egress profile** — a value from a **closed six-member enum** under `:rf.egress/profile`, not a remembered combination of booleans:

```clojure
(rf/project-egress record
  {:frame :app/main
   :rf.egress/profile :rf.egress/off-box-observability})
```

| Profile | Default behaviour |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb). Omit raw app-db / runtime-db; redact sensitive; elide large; omit digests unless enabled. |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire. Redact sensitive; elide large; include structural indicators so a tool reasons about *shape* not *content*. |
| `:rf.egress/local-redacted` | local dev UI **default**. Suppress sensitive display; may show indicators. |
| `:rf.egress/local-raw` | trusted local operator. Include sensitive + large (size caps may still apply). |
| `:rf.egress/ssr-hydration` | the projection applied **after** the SSR allowlist — defence-in-depth, never a parallel SSR mechanism. |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values. |

The boolean `:rf.size/*` flags (`:include-sensitive?`, `:include-large?`, `:include-digests?`) remain beneath the profiles as an **advanced override layer** — reach for them only to override one axis of a profile, not as the everyday choice. (Names are *provisional*: the set is closed but the spellings don't lock until each profile is exercised by a real consumer surface.)

### The two projection primitives

Real egress surfaces emit **records**, not bare values. The public, record-level boundary primitive is **`rf/project-egress`** — the required step before any off-box sink. It dispatches on a record's `:kind` (`:rf.observe/*`) to a private per-kind projector, applies per-kind rules (like omitting `:event` args off-box), and delegates each tree-shaped slot to the low-level walker:

```clojure
(rf/project-egress
  {:kind :rf.observe/handled-event :frame :app/main
   :event [:auth/login {:password "secret"}] :status :ok}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

Beneath it, **`rf/elide-wire-value`** is the single low-level walker for *tree-shaped values* — it substitutes sentinels at the slots the frame's classification says to redact or elide. Use it for custom forwarders, loggers, and direct-value egress; do not reimplement the walk. Sinks and tools should reach for `project-egress` and rarely call the walker directly:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main :path [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

## Direct reads must project, with the frame known

`rf/app-db-value`, `rf/sub-cache`, and an MCP `get-path` bypass the trace surface. Any direct read crossing an egress boundary must **project app-side with the frame known**. Egress policy is frame-scoped and inherits the no-default-frame rule: **if a projection needs frame policy and no frame is known, it fails closed — it does not synthesize `:rf/default`.** On-box visibility is **per (tool, frame) pair** — no single process-global "show sensitive" toggle; local tools default `:rf.egress/local-redacted`, raw requires explicit `:rf.egress/local-raw` opt-in. The payoff of routing everything through one projection: **revealing sensitive data is an operator act and is itself trace-visible** — auditable, not silent.

## No propagation, no taint

<a id="no-propagation-no-taint"></a>

**Classification does not propagate.** You redact exactly the paths you classify; nothing is inherited. A subscription, flow, or machine selector that *reads* a sensitive input does **not** auto-classify its output. There is:

- **no** input → output inheritance — if you derive a secret to a new path (through a sub, a flow, anything), **classify that path**. A sensitive flow output is just a classified db path;
- **no** declassification key — there is nothing to declassify because nothing propagates. The old `:rf.egress/output-sensitivity` claim (and its `:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public` value set) is **gone**, silently ignored if present;
- **no** universal "same value redacted everywhere" value-match / taint engine — a secret copied or re-keyed into an unclassified path **ships raw** (the fail-open bargain).

```clojure
;; A token prefix derived in a sub is NOT auto-sensitive (no propagation),
;; and it is NOT auto-redacted either. If the prefix must be redacted at
;; egress, classify the path it lands at; if it's safe, do nothing.
(rf/reg-sub :auth/token-prefix
  (fn [db _] (subs (get-in db [:auth :token]) 0 4)))

;; If a sub's OWN output is a whole secret, classify it with registration
;; metadata (the transient-payload route) — that classifies this sub's output,
;; not anything derived from it.
(rf/reg-sub :partner/api-token
  {:sensitive [[]]}
  (fn [db _] (get-in db [:tenant :partner-api-key])))
```

This is a deliberate scope decision: propagation is machinery for an unusual case already covered by classifying the output path. The egress rules over the paths you *do* classify are unchanged — **sensitive wins over large** at the same path; a `:large`-marked subtree containing a `:sensitive` descendant redacts rather than showing a size preview; large auto-elides an oversized value even at an undeclared path (the size backstop). When reviewing a consumer app, the audit question is simply *"is every path a secret reaches classified?"* — there is no inheritance to reason about.

## The display contract: three sentinels

Projection substitutes one of three framework-reserved sentinel forms (your app must never use them as legitimate payload values):

- **`:rf/redacted` — sensitive only.** Carries *no* information about content — not type, size, hash, or prefix.
- **`:rf.size/large-elided` / `:rf/large {:bytes N :head "…"}` — large only.** Size plus an optional `:head` (the first N chars).
- **`:rf/redacted {:bytes N}` — sensitive + large composed.** Sensitive wins on content visibility (no `:head` ever); a size diagnostic may ride alongside.

The rendering rule is uniform and load-bearing: a **large marker is drillable** (click-to-expand, subject to a size confirmation), but **`:rf/redacted` MUST NOT be expandable, ever** — a "show original" affordance against `:rf/redacted` is the exact leak the contract exists to prevent.

## Retired surfaces → flip to the model

Several older escape hatches have been removed. If you see these in older code or older guidance, they teach a broken or no-op call:

| Retired surface | Replacement | Why |
|---|---|---|
| `add-marks` / `set-marks` / `clear-app-db-marks!` — imperative post-creation app-db marks | the `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` **classification effects** a handler returns | durable app-db classification is declared from the writing event (its definition site); there is no imperative mark API |
| frame `:sensitive` / `:large {:app-db …}` durable annotation | the four classification effects (above) | the *frame* is not app-db's definition site; a `reg-frame` `:sensitive` carrying an `:app-db` block is rejected fail-loud. (The frame still owns `:sensitive {:http …}` carrier names and `:observability` sink policy — different surfaces.) |
| schema-attached `{:sensitive? true}` on an **app-db** `reg-app-schema` slot | the `:sensitive` classification effect | schemas describe shape/validation, not durable app-db egress policy. (The same `:sensitive?` prop on an **HTTP `:decode`-body** schema is *correct and live* — the transient-payload route; on a **machine / resource `:data-schema`** it drives only validation-failure-trace redaction, not snapshot egress — see [the subsystem row](#2--subsystem-owned-machine-and-resource-instance-data).) |
| schema-prop → machine `:data` snapshot redaction | projection-relative `:sensitive` / `:large` on the `reg-machine` definition | machine `:data` durable classification travels with the machine definition, lowered per actor instance — not its `:data-schema` |
| **all sensitivity propagation** — input → output inheritance through subs / flows, and the `:rf.egress/output-sensitivity` declassification claim | classify the output path directly | nothing propagates; a derived secret is just a classified db path (see [no propagation](#no-propagation-no-taint)) |
| `redact-interceptor` — positional payload scrubber on the handler | registration `:sensitive [[:path]]` metadata | privacy is a property of the *value at a path*, owned by the registration — not of an interceptor's stack position |
| `declare-sensitive-header!` / `declare-sensitive-query-param!` — process-global carrier mutation | a managed-HTTP effect's `reg-fx` `:sensitive` declaration, plus the frame-local `:sensitive {:http {:headers [...] :query-params [...]}}` extension | carrier policy rides the effect's registration (the transient-payload case) and a frame-local union, not a process-wide mutable fact |
| the epoch-history `:redact-fn` storage hook | frame/profile projection at *export* (`project-egress`) | epoch records are causal-replay material; mutating them at rest corrupts the replay contract. Project at export, never at rest. |
| handler-meta `{:sensitive? true}` as a whole-handler switch | registration `:sensitive` paths | **removed from the runtime — does nothing.** Marking a handler `{:sensitive? true}` ships the payload unredacted. |

Calling any of these retired names fails the skills projection gate (it no longer resolves to a `re-frame.core` manifest row) — that loud error is the point.

## Cross-references

- [Guide — Keep secrets out of traces](../../../../docs/guide/how-to/keep-secrets-out-of-traces.md): the failure posture, the exception gap, the SSR allowlist, the exception-path residual.
- [`spec/015-Data-Classification.md`](../../../../spec/015-Data-Classification.md): normative home.
- `production-observability.md`: the listener/event/error substrate behind frame `:observability`.
- Spec 009: privacy at the trace/emission boundary. Spec 010: per-slot Malli metadata and the schema walker.
