# Spec 015 — Data Classification (Sensitive + Large)

> Status: Drafting. **v1-required.** Graduates [EP-0025 (Data Classification)](../docs/EP/EP-0025-data-classification.md) — `accepted` — to its **final shipped model**: a lightweight **hygiene helper** that classifies a *path* (durable app-db / runtime-db) or a *payload shape* (transient event / fx / cofx) and redacts the classified value at the framework's mediated-egress boundaries. Builds on the registration grammar in [001-Registration](001-Registration.md), the commit-plane effect map in [002-Frames](002-Frames.md), the schema surface in [010-Schemas](010-Schemas.md), the trace surface in [009-Instrumentation](009-Instrumentation.md), the reserved-namespace policy in [Conventions](Conventions.md), and the privacy posture in [Security](Security.md).
>
> **The minimum claim.** You classify a *path* (or a transient payload shape); the framework redacts the classified value at a mediated-egress boundary. Durable app-db paths are classified by **four commit-plane effects** (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) a handler returns with its `:db` write, recorded in a **per-frame elision registry**; transient payloads are classified by `:sensitive` / `:large` **registration metadata**; each runtime subsystem classifies its own instance data, **projection-relative**, lowered per instance. Real values flow through the application unchanged; redaction happens only where a value crosses a framework-mediated observation boundary.
>
> **Posture — hygiene, NOT security.** This contract is a **leak-prevention overlay on observability**, and a *convenience*, not a security boundary. Its single job is to stop secrets and large payloads from *accidentally* reaching observability sinks (Datadog, off-box logs) and AI tools (MCP, the trace bus an AI pair reads). It is **fail-open**: a path you never classify ships raw — that's fine, hygiene not a guarantee. It does not police ownership, enforce secrecy, or track taint. Apps still own their own auth/authorisation, encryption-at-rest, and transport security. See [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the pattern-level threat model this contract grounds.
>
> **EP-0025 graduation note.** This Spec previously graduated the superseded EP-0015 "frame-owned egress policy" model (a frame `:sensitive {:app-db …}` annotation, a frame `:sensitive {:http …}` HTTP carrier block, schema-prop durable classification, an imperative `add-marks` / `set-marks` API, and derived-output sensitivity *propagation*). EP-0025 **removed** every one of those mechanisms (see [§What is removed](#what-is-removed-and-what-is-kept)); they are not described here except as a removal note. The egress-projection substrate and the `:rf.egress/*` profile enum survive (re-keyed off "marks"); the HTTP carrier capability survives on the `:rf.http/managed` `reg-fx` registration (`:carriers` block — the transient-payload case), and the frame `:observability` sink policy survives on the frame's `validate!` seam.

## Abstract

re-frame2 deliberately makes runtime state highly observable: one runtime story feeds traces, Xray, Story, MCP tools, epoch history, recorders, HTTP diagnostics, schema-validation reports, and SSR/hydration payloads. That observability is a core productivity feature, and it is also the privacy problem — an ordinary application value (a token, a session id, a card number, a partner credential, a private profile field) can cross a framework-mediated observation boundary and land in a record that is shipped off-box, handed to an LLM, saved as an artifact, or delivered to a browser.

Data classification is the **one convenient way** to say "don't ship this." You classify a *path* (durable state) or a *payload shape* (transient), and the framework redacts the classified value at the egress boundary. The sentinels are unchanged: a sensitive value projects to **`:rf/redacted`**; a large value projects to the **`:rf.size/large-elided`** marker (rich-map form carrying size diagnostics). **Sensitive wins over large.** What EP-0025 fixed is **who owns the declaration** and **where it lives**: classification moves onto the owner of the data *at its definition site* — durable app-db via commit-plane effects into a per-frame registry; transient payloads via registration metadata; subsystem instance data via projection-relative declarations the subsystem lowers per instance — and projection becomes an explicit record-level boundary primitive (`project-egress`) layered over the low-level value walker (`elide-wire-value`).

**No runtime cost on the happy path.** Real values flow through events → cofx → handler → fx → app-db → subs → views unchanged. Projection happens only at observation/egress boundaries; the dev trace stream rides the `goog.DEBUG` gate per [009 §Production elision](009-Instrumentation.md#production-elision), and the production observation stream is bounded and projected per [§The three observation streams](#the-three-observation-streams).

## The north star — a helper, not a contract

<a id="the-ownership-split"></a><a id="the-sensitive--sensitive-cross-layer-distinction-ep-0007-rule-3"></a>

This feature is about **hygiene and convenience, not security**, and the design follows four rules:

- **We trust the developer.** No ownership policing, no enforced secrecy.
- **We build minimal infrastructure.** Simple beats airtight. The surface is four flat effects + registration metadata; the mechanism is "record the path, redact at egress."
- **It is fail-open.** A path you never classify ships raw — that's the hygiene bargain, not a leak (see [§Failure posture](#failure-posture)). A re-keyed or rendered secret ships raw unless its app-db *path* is classified (ruled `1hms84`=(a): accept fail-open; classify the path to redact).
- **Exceptions are exceptional.** If an event throws or is rejected, all bets are off — fix the exception. We build no special machinery around it.

**Classify at the fact's *definition site*.** A classification lives where the meaning of the data is authored. App-db has no single definition registration (which is exactly why a *frame* annotation was the wrong home), so its definition site is an **event** — the frame's init event for known paths, or the writing handler for discovered paths. A subsystem instance's classification lives and dies with that instance, declared on the subsystem definition and lowered per instance. A transient payload's definition site is its registration. The same name — `:sensitive` — names the fact on every surface.

### The vocabulary in one place

There is **one axis vocabulary** (`:sensitive` / `:large`) over `:rf/path` vectors, used in three lowering shapes:

- **Handler effects** (durable app-db) — bare `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large` in the open effect map a handler returns; written into the per-frame elision registry **with the `:db` write** (see [§Durable app-db — the four commit-plane effects](#durable-app-db--the-four-commit-plane-effects)).
- **Registration metadata** (transient payloads) — bare `:sensitive` / `:large` in the closed registration map of `reg-event` / `reg-fx` / `reg-cofx` / `reg-sub` (see [§Registration-owned transient classification](#registration-owned-transient-classification)).
- **Subsystem declarations** (durable runtime-subsystem state) — bare `:sensitive` / `:large` on `reg-machine` / `reg-resource` / `reg-mutation` / `reg-route`, **projection-relative** to the instance shape; the subsystem lowers them into the same per-frame registry per instance (see [§Subsystem projection-relative classification](#subsystem-projection-relative-classification)).

The concept *is* the verb — `:sensitive` already means "classify sensitive," so there is no generic `:rf/classify`; clear mirrors set per axis (`:clear-sensitive` / `:clear-large`), ordinary set/unset symmetry like `assoc` / `dissoc`. The two axes are **independent**: clearing one never touches the other.

## Durable app-db — the four commit-plane effects

<a id="frame-owned-durable-classification"></a><a id="layer-1--classification-names-facts"></a><a id="1-event-args--app-db"></a>

You classify a **path you own in app-db** by returning a classification effect from the handler that owns the moment the path's meaning is fixed:

```clojure
;; known app-db secret, classified in the frame's init event
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {})
     :sensitive [[:auth :token]]}))

;; discovered at runtime, classified in the handler that writes it
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))

;; rare: a path reused for non-secret data is un-classified
(rf/reg-event :doc/sanitised
  (fn [{:keys [db]} [_ doc-id clean]]
    {:db (assoc-in db [:docs doc-id :body] clean)
     :clear-sensitive [[:docs doc-id :body]]}))
```

The four effects each take a **vector of `:rf/path` vectors**:

```clojure
:sensitive       [[path] …]   ; classify each path sensitive (redact at egress)
:large           [[path] …]   ; classify each path large    (size marker at egress)
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

Semantics — these are the **commit-plane** half of [002 §Commit-plane data-classification effects](002-Frames.md#commit-plane-data-classification-effects-ep-0025); 002 owns the effect-map contract, this Spec the classification model:

- **Applied with the `:db` write.** The four effects are a frame-state transform at the **commit point** (a runtime-db partition write into the per-frame elision registry, `[:rf.runtime/elision …]`), folded into the **same atomic frame-state transition** as `:db` — **not** routed through the later `:fx` do-fx plane. A path classified in an event is therefore redacted from its *first* egress; a classification made earlier (at init, or any time before the value lands) trivially covers it.
- **Value-independent.** Classify a path *before* any value exists there — the common, safe pattern. A classification over an absent or differently-shaped value is a harmless **no-op**; it redacts whatever later occupies the path. Values flow under a standing classification; you do not re-classify per write.
- **Two independent axes.** Sensitive and large are separate; `:clear-sensitive` never touches the large axis and vice-versa. Clearing removes only the named paths.
- **Read only at egress.** Handlers, subs, and views always see real values while events run; redaction happens only at the mediated-egress projection. Clearing is rarely reached for — a classification over absent data is already a no-op, so when data disappears its redaction effectively disappears with it. `:clear-*` matters mainly when a path is *reused* for non-secret data, or when a subsystem tidies its registry on teardown.

The classification lands in the per-frame **elision registry** in runtime-db at `[:rf.runtime/elision …]` (per [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)): the sensitive axis under `:sensitive-declarations`, the large axis under `:declarations`, each path mapped to a `{:source …}` record. The four effects write `:source :effect`; the registry is read at egress by both the path walker and the record projector. Locating the registry in runtime-db (not app-db) means a classification **walks back atomically with the frame** on a revert, per [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility).

> **There is no frame `:sensitive {:app-db …}` / `:large {:app-db …}` annotation, and no schema-prop route to durable app-db classification.** A `reg-frame` `:sensitive` key carrying an `:app-db` block and a top-level `:large` frame key are both **rejected fail-loud** (declare durable classification from a handler's commit-plane effects); a `reg-app-schema` slot's `:sensitive?` / `:large?` props do **not** classify a durable app-db path (see [§What is removed](#what-is-removed-and-what-is-kept) and [§Schemas describe shape](#schemas-describe-shape-not-durable-app-db-egress-policy)).

## Registration-owned transient classification

<a id="registration-owned-transient-classification"></a>

A **transient payload** (event args, fx args, cofx values, sub output) is owned by the registration that introduces its shape. Its author declares the sensitive / large paths there — the same vocabulary, relative to the payload's shape:

```clojure
(rf/reg-event :auth/login {:sensitive [[1 :password]]}
  (fn [{:keys [db]} [_ {:keys [user password]}]] …))

(rf/reg-fx :rf.http/managed
  {:sensitive [[:request :headers "Authorization"] [:request :body :password]]
   :large     [[:request :body :upload]]}
  managed-handler)

(rf/reg-fx :rf.ws/send {:sensitive [[:auth]]} ws-handler)

(rf/reg-cofx :session {:sensitive [[:token]]}
  (fn [cofx] (assoc cofx :session {:user "alice" :token (read-token)})))

(rf/reg-sub :partner/api-token {:sensitive [[]]}    ;; whole sub output is sensitive
  (fn [db _] (get-in db [:tenant :partner-api-key])))
```

Paths index into that registration's primary data shape: the event vector (`[1 :password]` reaches the arg-map's `:password`), the fx-input map, the cofx-injected value, the sub output. Empty path `[[]]` marks the whole shape. A mark at a missing slot is a silent no-op (tolerate shape evolution); a malformed path *vector* fails at registration (see [§Failure posture](#failure-posture)).

**`:rf.http/managed` is not special, and neither is a websocket effect** — every effect / coeffect / event / sub author declares its own sensitive arg-paths once, at registration. The framework ships sensible defaults for its own effects (e.g. the standard sensitive-header denylist for HTTP — see [§HTTP carriers](#http-carriers)); library authors do it for theirs. There is **no propagation**: a sub's `:sensitive` declaration classifies *that sub's own output paths*, not anything derived from it (see [§No propagation, no taint](#no-propagation-no-taint)).

## Subsystem projection-relative classification

<a id="resource-and-mutation-durable-classification"></a><a id="machine-owned-durable-classification-frame-owned-ep-0025-reversal-of-the-ep-0005-redaction-bridge"></a>

A runtime subsystem owns its runtime storage, so the author never names an absolute runtime path. The subsystem declares `:sensitive` / `:large` **relative to its instance projection**, and **lowers** the declaration into the per-frame elision registry **per instance** — params/scope when the instance is minted, data when it lands — dropping it **on teardown** (by any cause). No per-egress resolver; no storage paths in app code; every generated instance is covered with zero per-instance author code.

```clojure
;; a machine declares its own sensitive / large :data slots, projection-relative
;; to one actor snapshot's :data. The [:schemas :data] schema still VALIDATES
;; :data; it no longer classifies it (EP-0025 reverses the EP-0005
;; schema→classification bridge).
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :payment :token]]
   :large     [[:data :payment :receipt-pdf]]
   :schemas   {:data [:map [:payment [:map [:token :string] [:receipt-pdf :bytes]]]]}
   :initial :collecting
   :states  {:collecting {:on {:submit :charging}}
             :charging   {}
             :done       {}}})

;; a resource declares its own statically-known sensitive / large fields
(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

Each subsystem **owns the arrangement that fits it** — the projection root and any nesting that reads naturally for it (a route isn't a cache entry isn't an actor). The only shared invariant is the **axis vocabulary** over projection-relative paths; the rest is the subsystem's ergonomic call.

| Subsystem | Projection root | Lowered at | Dropped at | Generated key |
|---|---|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | actor spawn / first-boot | actor destroy (any cause) | per spawned actor id |
| `reg-resource` | entry's `:params` / `:data` | params/scope at scoped-key mint; data when fetch lands | entry eviction | opaque cache key |
| `reg-mutation` | one work row's `:params` | work creation | work completion | per work id |
| `reg-route` | the current route's `:query` / `:params` | route activation | route change / deactivation | current route (effectively singleton) |

The runtime re-roots each declared projection-relative path to the instance's absolute runtime path (e.g. a machine's `[:data …]` to `[:rf.runtime/machines :snapshots <actor-id> :data …]`) and writes it into the per-frame registry under a subsystem `:source` (e.g. `:source :machine`). The egress / trace / SSR-hydration boundaries read that lowered declaration through the **same registry** the commit-plane effects write to — so the read **unions every source**, and an app *may* additionally classify a subsystem's absolute path from a handler effect, though the subsystem declaration is the canonical surface. The declaration is value-independent and standing (it redacts whatever later occupies the slot, a harmless no-op over an absent value). A malformed declaration is rejected fail-loud at registration (see [§Failure posture](#failure-posture)).

> The machine `:data-schema` (and a resource's `:data-schema` / `:params-schema`) still **validates** the data; its `:sensitive?` / `:large?` per-slot props additionally drive **validation-failure-trace** redaction (a property of the validator's own egress product — see [§Schemas describe shape](#schemas-describe-shape-not-durable-app-db-egress-policy)). That is a *different* axis from durable-state classification, which travels with the subsystem definition as projection-relative paths.

## The keyword namespacing rule

<a id="the-keyword-namespacing-rule"></a>

This Spec follows the configuration convention in [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned). Four rules cover every key:

1. **Closed local grammar keys stay bare.** The four commit-plane effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`), the registration-metadata classification keys (`:sensitive` / `:large` on a `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` map and on subsystem registrations), the `:rf.http/managed` `reg-fx` `:carriers` block, and the frame `:observability` policy stay unqualified — they are read only inside their owning grammar and do not travel.
2. **Cross-surface framework policy keys are namespaced.** Egress-profile keys live under `:rf.egress/*` (the slot `:rf.egress/profile` + its closed value enum); observation-record kinds under `:rf.observe/*` (e.g. `:rf.observe/handled-event`); the low-level walker flags under `:rf.size/*` (`:rf.size/include-sensitive?`, `:rf.size/include-large?`, `:rf.size/include-digests?`); the size marker under `:rf.size/large-elided`; its re-fetch handle under `:rf.elision/at`.
3. **Framework-owned discriminator values are namespaced too.** Egress profiles are *values* under `:rf.egress/*` (e.g. `:rf.egress/off-box-observability`); observation record kinds are values under `:rf.observe/*`.
4. **User / library-owned ids stay outside the framework namespace.** A Datadog sink id is the app's — `:my-app.sinks/datadog`, never `:datadog` or `:rf.sink/datadog` unless the framework ships that sink. Vendor-specific sink fields live under a local `:opts` map so the framework never appears to own their vocabulary.

Wherever a surface uses paths (the four effects, registration marks, subsystem declarations, SSR allowlists), the path grammar is **[EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md)'s `:rf/path` vocabulary** — no fourth ad-hoc path notation.

> **`:sensitive` (no `?`) vs `:sensitive?` (with `?`) — a named [EP-0007 rule-3 cross-layer distinction](../docs/EP/EP-0007-one-name-per-fact.md#the-rules).** `:sensitive` names a **collection of paths** (the classification vocabulary of this Spec) — a frame `:http` carrier list, a registration mark vector, a subsystem declaration, a commit-plane effect payload. `:sensitive?` (with `?`, the predicate-suffix) is a **boolean Malli prop** on a single schema slot (`[:token {:sensitive? true} :string]`), owned by a schema and surviving **only** for validation-failure-trace redaction and for the schema-owned *transient* product owners (resource / HTTP-body `:decode`). `:large` / `:large?` carry the identical distinction. Because `:sensitive` is a path collection, it is never spelled `:sensitive false`.

## Projection — applying classification at a boundary

<a id="projection"></a>

The framework — never the author, never a sink — projects a value or record under the owning frame's classification before it crosses a boundary. There is **one choke point**: `project-egress`, layered over the leaf-level `elide-wire-value` walker.

### `elide-wire-value` — the low-level value walker

`rf/elide-wire-value` is the single low-level walker for **tree-shaped values**. It takes a value and an opts map, walks the tree, and substitutes sentinels at slots the frame's classification (and the resolved profile) say to redact or elide:

```clojure
(rf/elide-wire-value app-db-slice
  {:frame :app/main
   :path  [:auth]
   :rf.egress/profile :rf.egress/off-box-tool})
```

It reads the per-frame registry by path: every classified leaf under a declared sensitive path becomes `:rf/redacted`; every classified leaf under a declared large path becomes the `:rf.size/large-elided` marker. The advanced override layer is the boolean flags under `:rf.size/*` (`:rf.size/include-sensitive?` / `:rf.size/include-large?` / `:rf.size/include-digests?`). `elide-wire-value` knows nothing about record shapes; it is the primitive `project-egress` delegates to, and sinks / tools should rarely call it directly.

### `project-egress` — the record-level boundary primitive

<a id="project-egress--the-record-level-boundary-primitive"></a>

Real egress surfaces emit **records**, not bare values: handled-event records, error records, derived trees (rendered hiccup, a resolved `:effective-args` map, a snapshot body), epoch records, MCP snapshots, sub-cache reads, HTTP diagnostics, hydration payloads. `rf/project-egress` is the public, record-level boundary primitive (the name names the **boundary**, not a record kind). It is the required step before any off-box sink.

```clojure
(rf/project-egress
  {:kind   :rf.observe/handled-event
   :frame  :app/main
   :event  [:auth/login {:password "secret"}]
   :status :ok
   :effects [:db :rf.http/managed]}
  {:rf.egress/profile :rf.egress/off-box-observability})
```

It dispatches on a record's `:kind` — the closed `:rf.observe/*` set (`:rf.observe/handled-event`, `:rf.observe/error`, `:rf.observe/derived-tree`) — and delegates each tree-shaped slot to `elide-wire-value` under the frame's classification, applying per-record-kind rules for the others (e.g. omitting `:event` args entirely under the off-box default — see [§The handled-event record](#frame-owned-observability-sink-policy)). An `:rf.observe/*` record is **frame-bearing**: it carries its owning frame under a top-level `:frame` slot, and `project-egress` seeds the governing frame from it when opts omit `:frame`. An explicit `:frame` opt wins (deliberate reclassification); a kindless input is treated as a tree-shaped direct-read value and walked whole; a frameless input **fails closed** (see [§Direct reads and fail-closed frame resolution](#direct-reads-and-fail-closed-frame-resolution)). An **event-shaped** slot (the raw `[event-id arg-map …]` vector on a handled-event / error record) is **registration-owned**: it is projected through the dispatched handler's own `:sensitive` / `:large` registration marks (rooted at the arg-map) before any frame-policy walk — event args are transient payloads, classified by their registration, not by app-db policy.

> **`:rf.observe/derived-tree` is PATH-BASED, not value-match (EP-0025).** Earlier the derived-tree projector collected the live values at a frame's classified app-db paths and substituted any *equal* leaf wherever it re-surfaced in a derived tree (a token copied out of `[:auth :token]` into a rendered `[:input {:value <token>}]`). EP-0025 **removed** that value-match engine as "propagation / taint by another name." The derived-tree record is now a **path-based** projection: each tree slot (or each named `:slot-keys` value) is walked through `elide-wire-value` against the frame's registry. A value **re-keyed off its classified app-db path ships RAW** — the intended fail-open (ruled `1hms84`=(a)). To redact a derived secret, classify the app-db **path** it lives at, or the destination path it is written to.

### Projection profiles — the `:rf.egress/*` enum

<a id="projection-profiles--the-rfegress-enum-provisional"></a><a id="the-graduation-gate"></a>

The normal public choice at a boundary is **"which boundary is this?"** — a named egress profile — not a remembered combination of booleans. The boolean `:rf.size/*` flags remain the advanced override layer beneath the profiles. `:rf.egress/profile` takes a value from this **closed six-member enum** (additions require a recorded ruling):

| Profile | Default behaviour |
|---|---|
| `:rf.egress/off-box-observability` | hosted monitoring (Datadog / Sentry / Honeycomb). Redact sensitive; elide large; omit digests; the off-box default omits raw `:event` args. |
| `:rf.egress/off-box-tool` | MCP / AI / tool wire. Redact sensitive; elide large; include structural indicators / digests so the tool can reason about shape without seeing content. |
| `:rf.egress/local-redacted` | local dev-UI default. Suppress sensitive display by default; may show size indicators on-box. |
| `:rf.egress/local-raw` | trusted local operator. Include sensitive and large unless size caps still require handles. |
| `:rf.egress/ssr-hydration` | the projection applied **after** the SSR allowlist (see [§SSR and hydration](#ssr-and-hydration-are-allowlist-first)) — defence-in-depth, never a parallel SSR mechanism. |
| `:rf.egress/public-error` | client-safe server error projection; never includes internal raw values. |

The profile resolves to an `:rf.size/*` opt **floor**; explicit caller `:rf.size/*` opts overlay it (override wins). An unknown profile is rejected fail-closed (`:rf.error/unknown-egress-profile`). Every profile is exercised by a real consumer surface (frame `:observability` sink routing; the pair-MCP / Story-MCP wire; the Xray App-DB local render; the trusted-local `--allow-sensitive-reads` opt-in; the SSR hydration slice; the public error-response projection).

## Frame-owned observability sink policy

<a id="frame-owned-observability-sink-policy"></a><a id="the-three-observation-streams"></a>

Production observability sink policy belongs on the frame, under the `:observability` key, validated for shape at `reg-frame` time through the frame's `validate!` seam (`re-frame.frame-classification`) and routed at runtime (`re-frame.observability`):

```clojure
(rf/reg-frame :app/main
  {:observability
   {:handled-events [{:sink :my-app.sinks/datadog
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]
    :errors         [{:sink :my-app.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability
                      :opts {:service "checkout-spa" :env "prod"}}]}
   :initial-events [[:app/init]]})
```

Two streams are routed: `:handled-events` (one production-safe observation record per re-frame event processed by this frame — **not** the dev trace stream's fine-grained events) and `:errors` (production-survivable error records, per [EP-0008](../docs/EP/EP-0008-production-observability-channels.md)'s always-on error axis). Shape validation **fails loud** on a non-map entry, a non-keyword `:sink`, a `:rf.egress/profile` outside the closed enum, or a non-map `:opts` — a typo'd profile is a registration-time error, never a silent install. The candidate record kinds are `:rf.observe/handled-event` and `:rf.observe/error`; the off-box default record omits the `:event` args slot entirely and carries only summary fields (frame, event id, status, elapsed, effect keys, work / correlation ids).

The sink consumes the **already-projected** record — the framework projects under the owning frame's classification and the entry's `:rf.egress/profile`, the sink does no redaction:

```clojure
(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record] (datadog/send projected-record)))   ;; already projected
```

Each sink invocation is **isolated** (a throwing sink is dropped, never crashes the dispatch). Routing is **fail-closed** and frame-scoped (EP-0002): an unresolved or policy-less frame routes nothing — the runtime never synthesizes `:rf/default`, never borrows another frame's policy, and never ships a record under unknown classification.

**Three observation streams**, of which `:observability` routes only the third: (1) the **dev trace stream** (fine-grained, production-elided per the `goog.DEBUG` gate); (2) the **dev epoch stream** (one assembled record per dequeued event, production-elided); (3) the **production observation stream** (bounded, projected, frame-`:observability`-routed handled-event + error records). The third is a small safe hosted-monitoring surface, not a replacement for dev trace detail.

## HTTP carriers

<a id="http-carriers"></a><a id="schemas-describe-shape-not-durable-app-db-egress-policy"></a>

The HTTP **carrier** capability (redacting secret-bearing header / query-param names) is **not a special surface** — it is the [transient-payload](#registration-owned-transient-classification) case. A managed-HTTP effect declares its sensitive carrier NAMES on its `reg-fx` registration — the `:carriers {:headers […] :query-params […]}` block on `:rf.http/managed` (plus the framework's immutable built-in carrier denylists — `Authorization`, `Cookie`, `X-API-Key`, …, which no app can remove). An app extends the carrier set by re-registering `:rf.http/managed` with a `:carriers` block — a **union** onto the built-in defaults (effective policy `(defaults − except) ∪ include` for the query-param form per `rf2-4wqxq8`; `:headers` has no `:except` form). The carriers are **process-global** (one registration); a malformed `:carriers` block fails loud (`:rf.error/bad-classification`). The earlier frame `:sensitive {:http …}` carrier block is **removed** (EP-0025). See [014-HTTPRequests §HTTP carriers](014-HTTPRequests.md#http-carriers-ep-0025).

**Response bodies** are registration-owned transient payloads classified per-slot via `:sensitive?` / `:large?` props on the request's `:decode` schema (the schema is the owner's natural declaration surface for the *transient* reply shape; an unschematized body is whole-sensitive, fail-closed; off-box traces omit response bodies entirely unless a classified projection is explicitly requested). See [014-HTTPRequests §Response-body classification](014-HTTPRequests.md#response-body-classification-ep-0015-5).

**Schemas describe shape, not durable app-db egress policy.** A `reg-app-schema` (and, post-EP-0025, a machine `:data-schema`) slot's `:sensitive?` / `:large?` props are **not** a route into the durable app-db / runtime-db egress registry — durable state is classified by the four commit-plane effects (app-db) and subsystem projection-relative declarations (runtime), so the framework never teaches two equivalent ways to classify the same durable path. Per-slot schema props remain the *one and only* route where a schema **is** the owner's natural declaration surface for a *transient, non-frame-state* product — a resource `:data-schema` / `:params-schema`, an HTTP `:decode` schema — and for a slot's own **validation-failure-trace** redaction (the schema produces that record, so it owns its egress shape). One owner, one route.

## Direct reads and fail-closed frame resolution

<a id="direct-reads-and-fail-closed-frame-resolution"></a><a id="cross-tool-visibility-grain"></a>

Direct reads bypass trace protection — `rf/app-db-value`, `rf/sub-cache`, an MCP `get-path`. Any direct read that crosses an egress boundary **must project app-side, with the frame known**:

```clojure
(rf/project-egress value
  {:frame :app/main :path [:auth] :rf.egress/profile :rf.egress/off-box-tool})
```

Egress policy is frame-scoped and inherits [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md)'s no-default-frame rule: **if a projection needs frame policy and no frame is known, it fails closed — it must not synthesize `:rf/default`** (the delegated walker redacts the whole value rather than leaking it under an invented ambient scope). This is the same fail-closed posture Spec 016 pins for resource scope resolution.

On-box visibility is **per (tool, frame) pair**: there is no process-global `show-sensitive?` toggle. Local tools default to `:rf.egress/local-redacted`; raw requires an explicit trusted-local opt-in (`:rf.egress/local-raw`). Revealing sensitive data is an **operator act and is itself trace-visible** (auditable).

## SSR and hydration are allowlist-first

<a id="ssr-and-hydration-are-allowlist-first"></a>

SSR / hydration is production egress to the browser, and it is **allowlist-first** — it asks "which state is *allowed* to cross?", not "which leaves are sensitive?":

```clojure
(rf/reg-frame :app/server
  {:ssr {:hydrate {:include-app-db [[:route] [:public-config] [:catalog :visible-items]]}}})
```

Unlisted state does not cross even if unclassified; an absent payload policy **fails closed** (`:rf.error/ssr-missing-payload-policy`). Classification composes as **defence-in-depth**: a sensitive child of an allowlisted slice is redacted unless SSR policy explicitly permits it. The `:rf.egress/ssr-hydration` profile is exactly the projection applied **after** this allowlist — never a parallel mechanism. The per-frame registry is itself kept off the hydration wire: a classified path can embed a sensitive id (`[:by-id "user-secret" :token]`), and the declaration keys *are* the application's sensitive path structure, so shipping the raw registry would leak both off-box. The registry is therefore **omitted** from the `:rf/runtime-db` hydration slice — the strongest realization of "no raw view crosses the wire," and sufficient because the client runs the same app image and rebuilds its own identical registry from its `reg-event` classification effects / `reg-flow` / `reg-route` / `reg-machine` on mount (the egress walk reads the live per-frame registry, never the wire copy; the classified app-db / route / machine slices were already projected against the registry server-side, so their redaction is baked in and needs no declarations to re-derive). Runtime classifications are per-frame (two frames running the same image share only the init-event baseline).

## Epoch projection (no storage-side mutation)

<a id="epoch-projection-no-storage-side-mutation"></a>

Epoch records are **causal replay material** (post-[EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)); mutating them at rest corrupts the replay contract. So:

- raw epoch records remain **in-process local dev state** (replay-faithful by construction);
- off-box epoch export **must use `project-egress`** under an off-box profile;
- **storage-side mutation is removed**, not merely discouraged — there is no `:redact-fn` storage hook; projection at the export boundary is the answer.

Egress redacts; durable storage stays raw — the two surfaces diverge exactly so a redacted-frame epoch still replays the raw value through `restore-epoch`.

## The display contract — sentinels

<a id="the-display-contract--sentinels"></a><a id="rfredacted-bytes-n--sensitive--large-composed"></a>

Three sentinel forms span the two-axis space. The sentinel keywords are **framework-reserved** per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned); apps MUST NOT use them as legitimate payload values.

- **`:rf/redacted` — sensitive only.** An opaque keyword carrying **no information** about the underlying content — not its type, size, hash, or prefix. A sensitive value is not revealable by any observation surface.
- **`:rf.size/large-elided {…}` — large only.** The walker emits a marker map carrying `:path`, `:bytes` (printed-representation byte count, or a close approximation), `:type`, `:reason` (the declaration source), an optional `:hint`, a `:handle` (the EDN re-fetch form `[:rf.elision/at <path>]`), and — under `:rf.size/include-digests?` — a `:digest`. Surfaces that preserve size diagnostics MAY render the rich `:rf/large {:bytes N :head "…"}` display form.
- **Sensitive + large composed.** When a value is marked **both**, **sensitive wins on content visibility** — `:rf/redacted` rides the slot, no `:head`/`:bytes` that could leak the bulk value escape. A size diagnostic MAY ride alongside as `:rf/redacted {:bytes N}`; the CLJS reference currently suppresses it. Both behaviours conform; readers must not depend on `:bytes` being present alongside `:rf/redacted`.

**Consuming-tool rendering contract.** `:rf/redacted` MUST NOT be expandable — a "show original" affordance against it is exactly the leak the contract prevents and is non-conformant. A `:rf.size/large-elided` marker MAY be click-to-expand subject to a per-tool size-confirmation safeguard (re-fetch via the `:handle`). The both-marked form is not expandable for content; its size info may display inline.

## Failure posture

<a id="failure-posture"></a>

Three rules keep the helper humble without weakening egress correctness:

- a **forgotten** classification is **fail-open** — the value ships raw (the hygiene bargain; a re-keyed or rendered secret ships raw unless its app-db *path* is classified, ruled `1hms84`=(a));
- a **malformed** input is **fail-loud** where it is applied: a bad commit-plane effect payload (non-vector value, non-`:rf/path` entry, unknown axis) aborts the transition **pre-commit** with `:rf.error/classification-effect-shape` (no `:db` commit), via the existing pre-commit-transactional path; a malformed `:sensitive` / `:large` **registration-metadata** declaration on `reg-event` / `reg-fx` / `reg-cofx` / `reg-sub` is rejected at registration with `:rf.error/bad-classification` (a flow's output marks with `:rf.error/flow-bad-marks`); a malformed **subsystem** declaration is rejected at registration under its own per-subsystem id — `reg-machine` raises `:rf.error/invalid-machine-classification`, a malformed resource spec folds into `:rf.error/invalid-resource-spec` (the catalogue in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) keys each variant);
- an **unknown frame / profile / projector failure** falls back to the existing egress policy's **fail-closed / fail-loud** behaviour — the projector redacts or errors (`:rf.error/unknown-egress-profile` for a bad profile; whole-value redaction for a frameless walk) rather than leaking.

## No propagation, no taint

<a id="no-propagation-no-taint"></a><a id="derived-sensitivity"></a><a id="declassifying-a-derived-output"></a><a id="propagation-rules"></a>

**Classification does not propagate.** You redact exactly the paths you classify; nothing is inherited. There is:

- **no** input → output inheritance — a sub or flow reading a classified input does **not** auto-classify its output; if you derive a secret to a new path (through a sub, a flow, anything), **classify that path** (a sensitive flow output is just a classified db path);
- **no** `:rf.egress/output-sensitivity` declassification claim (and no `:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public` value set) — there is nothing to declassify because nothing propagates; the key is gone and silently ignored if present;
- **no** universal "same value redacted everywhere" value-match / taint engine (see the derived-tree note in [§project-egress](#project-egress--the-record-level-boundary-primitive)).

This is a deliberate scope decision: propagation is machinery for an unusual case already covered by classifying the output path, and dropping it keeps the helper lightweight. The egress rules over the paths you *do* classify are unchanged: **sensitive wins over large** at the same path; a `:large`-marked subtree containing a `:sensitive` descendant redacts rather than showing a size preview; large auto-elides an oversized value even at an undeclared path (the size backstop).

## Author guidance for the exception-path residual

<a id="author-guidance-for-the-exception-path-residual"></a>

Projection walks **known data shapes** and substitutes sentinels at classified paths. It does **not** walk **exception messages** (once a sensitive value is concatenated into an `ex-message` string, no path resolves to the substring) or **`ex-data` maps** (author-chosen keys with no relationship to classified paths; a value-comparison rule would be the rejected value-match non-goal). The residual surface is the intersection of *the handler read a sensitive value* AND *the handler then threw with that value in the message or `ex-data`*. The author MUSTs at the assembly site:

```clojure
;; ANTI-PATTERN — the email lands in the error record verbatim.
(throw (ex-info (str "User " email " failed login") {:user/email email}))

;; PREFERRED — name the category; omit / sentinel-stamp the value.
(throw (ex-info "Invalid credentials" {:reason :invalid-credentials}))
```

Name the *category* of failure, not the value; if the failing structure is essential, substitute `:rf/redacted` at the assembly site; pick a per-app convention (the framework deliberately ships no `rf/safe-throw` — which `ex-data` keys are sensitive is author knowledge). Worked patterns live in [docs/guide §keep-secrets-out-of-traces](../docs/guide/how-to/keep-secrets-out-of-traces.md).

> **Framework-owned diagnostics carry a SHAPE summary, never the raw value (rf2-uwqale).** The residual above is the *app* author's responsibility because the framework cannot know which `ex-data` keys an app marks sensitive. The reverse holds for the framework's **own** adapter / render diagnostics: a render-tree handed to `render-to-string`, a hiccup head / child vector handed to an adapter's element builder, a Form-3 component spec — these are *framework-shaped* values whose offending shape the framework DOES know. Because such a value can carry app-owned sensitive/large content and a thrown adapter diagnostic is captured off-box (browser console, error boundary, host log, SSR error handler, production observability) **before** the record projector can classify the original paths, every framework adapter/render diagnostic carries a **shape-only summary** of the offending value, never the value itself: `{:type … :count … :keys … :head …}` (collection count, sorted structural map keys, a bounded recognition head for a scalar leaf). The CLJS reference centralises this in `re-frame.error/diag-value-summary`; bundle-isolated adapters (the day8/reagent-slim fork, which MUST NOT `:require` re-frame.*) carry a content-identical mirror in a dependency-free leaf ns so the diagnostic vocabulary is uniform across every surface.

## Scope

<a id="scope"></a>

### In scope — the boundaries projection must guard

<a id="in-scope--the-boundaries-projection-must-guard"></a><a id="in-scope--the-five-observation-points-marks-must-guard"></a>

Projection exists to stop *accidental* leaks at every framework-mediated observation boundary:

1. **Trace-bus emit** — every `:rf/trace-event` payload built inside `emit!` (per [009 §The trace event model](009-Instrumentation.md#the-trace-event-model)); the dev stream is production-elided, the always-on error-emit substrate survives.
2. **Xray / Story panel rendering** — on-box dev tools; default `:rf.egress/local-redacted`.
3. **MCP / tool wire transport** — pair-MCP, Story-MCP, and any future MCP server; `:rf.egress/off-box-tool`.
4. **AI / LLM context lifted by tools** — any path that lifts trace events, app-db snapshots, sub outputs, or machine `:data` into an LLM prompt.
5. **Hosted log sinks** — Datadog, Sentry, custom fan-outs; routed by frame `:observability`, projected under `:rf.egress/off-box-observability`.
6. **Epoch export, SSR / hydration, public error responses, HTTP diagnostics, schema-validation failure records** — each a projection boundary with its profile above.

### Out of scope (explicit non-goals)

<a id="out-of-scope-explicit-non-goals"></a>

- **Security.** Apps own auth, access control, authorisation, encryption-at-rest, transport security. This is a hygiene/leak-prevention overlay, not a secrecy boundary or runtime-security defence.
- **A guarantee.** It is fail-open on omission — no static pass verifies every sensitive datum has a declaration. The author owns the policy.
- **Taint-tracking through user code.** No propagation, no value-match — classification covers exactly the paths and payload shapes you declare; arbitrary handler-body provenance is not tracked (see [§No propagation, no taint](#no-propagation-no-taint)).
- **Mid-handler protection.** Handlers MUST see real values to do their job. Projection is at the observation boundary *after* the handler returns, never before.
- **Secrets before they enter re-frame2-owned shapes**, and **app-authored persistence / third-party SDKs that bypass re-frame2 observation boundaries** — once a secret is flattened into an unrelated key, an app logger, or app-owned storage, path-based projection can no longer prove its provenance, and it ships raw.

## What is removed (and what is kept)

<a id="what-is-removed-and-what-is-kept"></a>

**Removed by EP-0025** (this Spec does not describe these as live mechanisms):

- the **frame `:sensitive` / `:large {:app-db …}` durable annotation** — durable app-db classification is the four commit-plane effects (a `reg-frame` `:sensitive` carrying an `:app-db` block **and** a top-level `:large` frame key are BOTH rejected fail-loud with `:rf.error/bad-frame-classification` — `:large` is no longer a frame key, and a config carrying it does not silently register);
- the **frame `:sensitive {:http …}` HTTP carrier block** — HTTP carrier classification is now the transient-payload case on the `:rf.http/managed` `reg-fx` registration (`:carriers` block); a `reg-frame` carrying ANY `:sensitive` key is rejected fail-loud with `:rf.error/bad-frame-classification` (the whole `:sensitive` frame key is retired — its `:app-db` block moved to the commit-plane effects and its `:http` block moved to `:rf.http/managed`);
- the **durable-state Malli schema-prop classification route** + its schema→registry bridge (schemas validate + drive validation-failure-trace redaction only; durable classification is effects / subsystem declarations);
- the **imperative marks API** — `add-marks` / `set-marks` / `clear-app-db-marks!`, the `marks.cljc` namespace, and the `:source :marks` feed (gone; the load-bearing projection substrate is migrated into the marks-free elision engine);
- **all sensitivity propagation** — input → output inheritance through subs *and* flows, and the `:rf.egress/output-sensitivity` declassification claim + its value set;
- the **value-match / taint engine** — the derived-tree value-equality redaction of re-keyed copies (the derived-tree record survives, but path-based only — see [§project-egress](#project-egress--the-record-level-boundary-primitive)).

**Kept:**

- the **egress-projection substrate** + `project-egress` / `elide-wire-value` (re-keyed off "marks");
- the **`:rf.egress/*` profile enum** and the `:rf/redacted` / `:rf.size/large-elided` sentinels;
- the **HTTP carrier** capability (now the transient `reg-fx` case — the `:carriers` block on the `:rf.http/managed` registration);
- the **frame `:observability` sink policy** and its `validate!` seam + `register-observability-sink!` routing;
- **registration-owned transient** `:sensitive` / `:large`; **schema-validation-failure** redaction; **HTTP / `:decode`-body** redaction.

## Tests

<a id="tests"></a>

Conformance fixtures under [conformance/](conformance/README.md) assert the observable contract; the normative set (the durable-app-db rows install their classification under `:source :effect` — the harness shorthand for the commit-plane effects — exercising the same registry the effects write):

| Fixture | What it asserts |
|---|---|
| `data-classification-frame-sensitive-app-db-redacts.edn` | A path classified `:sensitive` (commit-plane effect), after a handler writes a token there, projects `:rf/redacted` at that path in the t1 `:rf.event/db-pending` trace egress; an unmarked sibling passes through; the committed app-db carries the raw value. |
| `data-classification-frame-large-app-db-elides.edn` | A path classified `:large` projects a `:rf.size/large-elided` marker (carrying `:path` / `:bytes` / `:type` / `:reason :effect` / `:handle`) at the marked path; an unmarked sibling rides verbatim. |
| `data-classification-sensitive-wins-over-large.edn` | A path classified both `:sensitive` and `:large` projects as `:rf/redacted`; no large marker (which would leak path / size / type / handle) is emitted, regardless of classification order. |
| `data-classification-http-carrier-extends-defaults.edn` | An app-declared `:rf.http/managed` `:carriers {:headers […]}` carrier redacts that header **in addition to** the immutable built-in defaults; no app can remove a built-in default; an unclassified header rides verbatim. |
| `data-classification-event-arg-sensitive-path-redacts.edn` | A `reg-event` with `:sensitive [[:password]]` registration metadata, dispatched with `{:password "secret"}`, projects `[:event-id {:password :rf/redacted}]` at trace egress; the handler body sees the raw payload; an unmarked sibling rides verbatim. |
| `data-classification-machine-data-declared-redacts.edn` | A `reg-machine` declaring `:sensitive [[:data … :token]]` (projection-relative, lowered per actor instance, `:source :machine`), after a transition writes a token into `:data`, projects `:rf/redacted` at `[:data … :token]` in the machine trace `:after` snapshot; an unmarked sibling slot rides verbatim; the `:data-schema` carries no classifying prop. |
| `data-classification-route-query-declared-redacts.edn` | A `reg-route` declaring `:sensitive [[:query :token]]` (projection-relative, lowered per-frame at activation, `:source :route`, re-rooted under `[:rf.runtime/routing :current …]`), after navigation, redacts the slice `:query :token` at egress: `project-egress` of a route-slice value under `:rf.egress/off-box-observability` projects `:rf/redacted` at the declared path while an unmarked sibling rides verbatim; the committed runtime-db slice carries the raw token. |
| `data-classification-route-change-drops-leaving-classification.edn` | The route singleton-drop (no machine analogue): navigating from a `:sensitive` route to a route declaring none clears the leaving route's `:source :route` entries; `project-egress` of the now-current (plain) route-slice value shows the token field rides raw — the leaving route's classification was dropped (no leak forward). |
| `data-classification-project-egress-omits-event-args-off-box.edn` | `project-egress` of an `:rf.observe/handled-event` under `:rf.egress/off-box-observability` carries the summary fields and **omits the `:event` args slot entirely**. |
| `data-classification-project-egress-fails-closed-no-frame.edn` | `project-egress` of a tree value with no known frame **fails closed** (whole-value `:rf/redacted`; no `:rf/default` synthesis). |
| `data-classification-observability-sink-receives-projected-record.edn` | A frame's `:observability` sink consumes an **already-projected** record: a handled-event under off-box has its `:event` slot omitted, an error record has its frame-classified `[:auth :token]` redacted; the framework projects, the sink never re-implements redaction. |
| `data-classification-clear-axis-independent.edn` | `:clear-sensitive` removes a path from the sensitive axis without touching its (still-standing) `:large` classification, and vice-versa — the two axes clear independently. |
| `data-classification-ssr-hydration-allowlist-first.edn` | An `:ssr {:hydrate {:include-app-db […]}}` frame hydrates **only** the allowlisted slice; an unlisted path does not cross even unclassified; an absent policy fails closed; a sensitive child of an allowlisted slice is redacted under `:rf.egress/ssr-hydration` as defence-in-depth. |
| `data-classification-epoch-export-projects-no-storage-mutation.edn` | The stored epoch record carries the raw value (replay-faithful — storage-side mutation removed); the same event's trace egress redacts the classified path. Egress redacts; storage stays raw. |

Per-artefact unit tests cover the implementation mechanism; the conformance fixtures cover only the observable contract.

## Cross-references

<a id="cross-references"></a><a id="http-response-bodies"></a>

- [EP-0025 (Data Classification)](../docs/EP/EP-0025-data-classification.md) — the accepted proposal this Spec graduates to its final shipped model; §Scope, §How it works, and §What is removed are the rationale record.
- [EP-0015 (Frame-Owned Egress Policy)](../docs/EP/EP-0015-frame-owned-egress-policy.md) — the predecessor model EP-0025 superseded (frame annotation, schema-prop durable classification, imperative marks, propagation — all removed).
- [EP-0007 (One Name Per Fact)](../docs/EP/EP-0007-one-name-per-fact.md) — rule 3 grounds the `:sensitive` / `:sensitive?` cross-layer pairing.
- [EP-0005 (Machine `:data` Schema)](../docs/EP/EP-0005-machine-data-schema.md) — the machine `:data-schema` (VALIDATION) stands; its schema→classification redaction bridge is REVERSED by EP-0025 (machine `:data` classification is the projection-relative `reg-machine` declaration).
- [001-Registration §Registration grammar](001-Registration.md#registration-grammar) — the metadata-map shape registration-owned `:sensitive` / `:large` extend.
- [002-Frames §Commit-plane data-classification effects](002-Frames.md#commit-plane-data-classification-effects-ep-0025) — the effect-map contract for the four commit-plane effects; the no-default-frame rule projection inherits.
- [005-StateMachines](005-StateMachines.md) — machine-owned, projection-relative machine `:data` classification, lowered per actor instance at spawn / first-boot.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — the sub-cache surface registration-owned sub-output classification rides (no propagation through it).
- [009-Instrumentation §The trace event model](009-Instrumentation.md#the-trace-event-model), [§Production elision](009-Instrumentation.md#production-elision) — the emit site and the dev-stream gate.
- [010-Schemas](010-Schemas.md) — schemas describe shape + validation; per-slot props are the *one* route only for schema-owned *transient* data and validation-failure-trace redaction, not a route for durable app-db / runtime-db classification.
- [011-SSR](011-SSR.md) — the allowlist-first hydration boundary the `:rf.egress/ssr-hydration` profile projects after.
- [014-HTTPRequests](014-HTTPRequests.md) — HTTP carrier names (transient — the `:rf.http/managed` `:carriers` block) and `:decode`-body classification.
- [016-Resources](016-Resources.md) — durable resource / mutation runtime-subsystem classification via projection-relative declarations; fail-closed scope and hydration metadata-only projection preserved.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned), [§Reserved commit-plane classification effects](Conventions.md#reserved-commit-plane-classification-effects) — the four effects, the `:rf.runtime/elision` registry, the `:rf.egress/*` / `:rf.observe/*` / `:rf.size/*` reservations, and the sentinels.
- [Privacy](Privacy.md) — the cross-artefact discoverability index for the whole privacy surface.
- [Security §Privacy / secret handling](Security.md#privacy--secret-handling) — the pattern-level threat model this Spec grounds.
- [docs/guide §keep-secrets-out-of-traces](../docs/guide/how-to/keep-secrets-out-of-traces.md) — author-side worked patterns (the four effects, subsystem declarations, the exception-path residual).
