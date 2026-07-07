# Validate with schemas

Picture an [event handler](../glossary.md#event-handler) — the pure function a `reg-event` runs when its event fires — writing the wrong shape into your state: `"loading"`, the string, where `:loading`, the keyword, belongs. The bug is silent. It lands in [app-db](../glossary.md#app-db), your app's single state map, rides downstream into a [subscription](../glossary.md#subscription), and surfaces three screens later as a blank panel with no clue who wrote the bad value.

A [schema](../glossary.md#schema) turns that silent corruption into a loud, named, instant failure. You describe the shape of a slice once, register it, and from then on the runtime checks every write against it. When a write doesn't conform you get the handler's name, the offending value, and the exact path it landed on — at the moment it happens, not six weeks later in a bug report. And it costs production nothing: validation is **dev-only by default** and is elided entirely from a release build.

This page builds up one idea at a time. The simplest useful thing first — a schema on one app-db path — then schemas on events, the handful of shapes you'll actually write, the other surfaces you can validate, how to read a failure, and how to decide whether a schema is worth writing at all.

??? info "Coming from Zod?"

    You already have the right instinct: describe the shape once, then let a validator enforce it. Two things differ here. You never call `parse()` at a use site — you *register* a schema against a path or an event id, and the runtime validates at fixed points on its own. And validation is dev-only by default: a schema here is a **tripwire**, not a guard. Hold that distinction; it has a sharp edge we'll come back to.

## Your first schema: a slice of app-db

Start with the most common case — pin the shape of one slice of app-db. `reg-app-schema` points a schema at a path, where a path is just the vector of keys you'd hand to `get-in` to reach that slice:

```clojure
(ns myapp.schema
  (:require [re-frame.core :as rf]
            [re-frame.schemas]))   ;; loads the validator — one require, once per app

(def AuthSlice
  [:map
   [:user  [:maybe [:map [:email :string] [:username :string]]]]
   [:token [:maybe :string]]])

(rf/with-frame :rf/default
  (rf/reg-app-schema [:auth] {:schema AuthSlice}))
```

Two pieces of that snippet are new. Both recur on every example below, so let's name them now.

The schema itself — `AuthSlice` — is just a **vector of plain data**: `[:map [:user ...] [:token ...]]`. No builder chain, no class to instantiate. That's [Malli](https://github.com/metosin/malli), the default schema language; we'll learn its handful of shapes in a moment.

And the registration runs inside `with-frame`. A [frame](../glossary.md#frame) is one isolated running instance of your app, and schemas register *per frame*, so the registration has to name which one. `:rf/default` is the frame your app boots into, and `with-frame` is the same wrapper your boot dispatches already run in — so this isn't new ceremony, it's just where registration code lives.

Now, what the registration buys you. From this point on, after every event handler runs, the runtime validates whatever the new app-db holds at `[:auth]` *before* installing it. If a write doesn't conform, three things happen at once: the runtime emits a structured error (`:rf.error/schema-validation-failure`), the bad write **never lands** — app-db keeps its pre-event value — and the dispatch is treated as failed. So you debug a named handler and a printed value, not a half-corrupted app-db three screens away.

!!! note "One require wires the validator"

    Why did the snippet `require` `re-frame.schemas` with no alias and never call it? Because the *require itself* is the wiring: pulling that artefact in installs the Malli validator under the hood, which is what turns a registered schema into a live check. So a registration is never a silent no-op — once the artefact is loaded, **a schema you register is a schema that fires.** And there's no inert half-state to fall into: leave the artefact off the classpath and `reg-app-schema` itself [fails loud](../glossary.md#fail-loud-not-silent) with `:rf.error/schemas-artefact-missing`, rather than recording a schema that silently validates nothing. In a real app you require it once at boot and never think about it again.

??? info "From re-frame v1"

    The `check-spec-interceptor` you hand-rolled from the todomvc example is built in now — and the vocabulary is `:schema` everywhere, not `:spec`. v1's `:spec` metadata key, the `:rf.spec/*` namespace, and the `:spec/at-boundary` interceptor are all gone with no back-compat alias; the framework no longer accepts `:spec` on `reg-*` metadata.

## Watch one catch a bug

Theory's cheap. Here's a schema doing its job — live. In Conduit, an article's favorite count must never go below zero (you can't un-favorite past nobody). The rule appears **twice**, on purpose. The handler *guards* (`pos?` — real behaviour that ships to production). The schema *declares* (`[:int {:min 0}]` — the dev tripwire). The cell runs in the playground's frame, so no `with-frame` is needed. Click in and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then drive it with the buttons.

The piece to watch is the `[:int {:min 0}]` on the `[:howto.schema/article]` slice — that's the app-db schema from the last section. (Each `reg-event` *also* carries a small `{:schema [:cat ...]}` map describing its event vector; ignore those for now — they're event schemas, a few sections down.)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; The slice's shape: a non-negative favorite count, and a favorited? flag.
(rf/reg-app-schema [:howto.schema/article]
  {:schema [:map
            [:favorites-count [:int {:min 0}]]
            [:favorited?      :boolean]]})

(rf/reg-event :howto.schema/initialise
  {:schema [:cat [:= :howto.schema/initialise]]}
  (fn [{:keys [db]} _] {:db (assoc db :howto.schema/article {:favorites-count 3 :favorited? false})}))

(rf/reg-event :howto.schema/favorite
  {:schema [:cat [:= :howto.schema/favorite]]}
  (fn [{:keys [db]} _]
    (let [n (inc (get-in db [:howto.schema/article :favorites-count]))]
      {:db (-> db
               (assoc-in [:howto.schema/article :favorites-count] n)
               (assoc-in [:howto.schema/article :favorited?] true))})))

;; The handler OWNS the never-below-zero rule — this guard ships to production.
(rf/reg-event :howto.schema/unfavorite
  {:schema [:cat [:= :howto.schema/unfavorite]]}
  (fn [{:keys [db]} _]
    (let [n (get-in db [:howto.schema/article :favorites-count])]
      {:db (if (pos? n)
             (-> db
                 (assoc-in [:howto.schema/article :favorites-count] (dec n))
                 (assoc-in [:howto.schema/article :favorited?] false))
             db)})))

(rf/reg-sub :howto.schema/favorites-count
  (fn [db _] (get-in db [:howto.schema/article :favorites-count])))

(rf/reg-sub :howto.schema/favorited?
  (fn [db _] (get-in db [:howto.schema/article :favorited?])))

(defn favorite-button []
  [:div
   [:button {:on-click #(rf/dispatch [:howto.schema/unfavorite])} "♥ unfavorite"]
   [:span {:style {:margin "0 1em" :font-size "1.4em"}}
    @(rf/subscribe [:howto.schema/favorites-count])]
   [:button {:on-click #(rf/dispatch [:howto.schema/favorite])} "♥ favorite"]
   [:div {:style {:margin-top "0.75em" :color "#666" :font-size "0.85em"}}
    "favorited?: " (str @(rf/subscribe [:howto.schema/favorited?]))]])

(rf/dispatch-sync [:howto.schema/initialise])
[favorite-button]
```

Click `unfavorite` down to `0` and keep clicking: nothing happens, because the `pos?` guard stands. Now simulate the bug the schema exists to catch. **Delete the guard** — replace `(if (pos? n) (-> db …) db)` with just the `(-> db …)` threading — re-evaluate, and click `unfavorite` past zero. The handler writes `-1`, and `[:int {:min 0}]` rejects it. The browser console shows the `:rf.error/schema-validation-failure`, and the count on screen stays `0`: the write was rolled back, so app-db never held the bad value. Put the guard back when you're done.

One thing to hear plainly before you move on: **the rollback is a debugging aid, not app behaviour.** Validation, rollback included, is elided from production builds — in production that unguarded handler happily ships `-1`. So the handler keeps its real guard, *always*. The schema is the tripwire that catches the day the guard gets deleted, refactored wrong, or bypassed by some *other* handler writing the same slice — in dev, the moment it happens.

## The shapes you'll actually write

A Malli schema is a vector, and it always reads in the same order: first a keyword naming the *kind* of shape (`:map`, `:int`, `:enum`, …), then an optional properties map that tunes it (`{:min 0}`), then the nested schemas it's built from — Malli calls those its *children*. Seven shapes cover the overwhelming majority of app-db:

```clojure
[:map [:email :string] [:password :string]]   ;; a map with these keys
[:enum :idle :loading :loaded :error]         ;; one of a fixed set
[:int {:min 0}]                               ;; a bounded integer
[:string {:min 1}]                            ;; a non-empty string
[:re #".+@.+"]                                ;; a regex-shaped string
[:maybe :string]                              ;; a string, or nil
[:vector Article]                             ;; a homogeneous vector
```

These compose, and that's the whole trick. A status field is an `:enum`. A form draft is a `[:map …]` of constrained strings. A feed is a `[:vector Article]` where `Article` is itself a `[:map …]`. You build big shapes by nesting small ones.

Two defaults will surprise you exactly once each, so learn them now. Keys in a `[:map …]` are **required by default** — relax one with a per-key properties map, `[:phone {:optional true} :string]`. And maps are **open by default**: unknown extra keys pass. That openness is deliberate — producers can add keys without breaking consumers — but reach for `{:closed true}` at system boundaries, where you're checking a payload you don't trust.

When these seven run out — less often than you'd think — `[:set …]`, `[:map-of …]`, `[:tuple …]`, `[:or …]`, and `[:fn pred]` are there, and the [Malli README](https://github.com/metosin/malli) has the full vocabulary.

??? note "Going deeper"

    A schema is a *predicate with structure*. `[:int {:min 0}]` is the set of non-negative integers; `[:map …]` is a product type (a record); `[:enum …]` and `[:or …]` are sum types (a tagged union); `[:maybe X]` is `X ⊕ nil`. Composing schemas composes the underlying predicates — `[:vector [:map …]]` is "for every element, this product holds." This is why open-by-default maps matter algebraically: a consumer that tolerates unknown keys depends only on the *projection* it reads, so a producer can extend its product without breaking any consumer's contract. Width subtyping, by another name.

## Register a feature's slices at once

A single feature usually owns several slices. `reg-app-schemas` (plural) takes a `{path → schema}` map and registers every entry in one call:

```clojure
(rf/with-frame :rf/default
  (rf/reg-app-schemas
   {[:auth]             AuthSlice
    [:auth :login-form] FormSlice
    [:articles]         RequestSlice
    [:articles :data]   [:vector Article]}))
```

Paths nest and overlap freely, which is more useful than it first sounds: a write under `[:auth :login-form]` is checked against *that* schema **and** the surrounding `[:auth]` one. The empty path `[]` schemas the whole map. [The Conduit example](../../../examples/real-apps/realworld_http) registers nineteen paths this way — every slice that holds server data, every form draft.

`reg-app-schemas` is last-write-wins on a duplicate path and returns the vector of paths it registered. It's the right shape for a feature module declaring 5–20 slices under a shared prefix. Reach for the singular `reg-app-schema` instead when a feature spans only a path or two, or when you need a *guaranteed* registration order. (The plural form registers in the order it iterates the map you hand it. A small map literal like the one above iterates in source order, but Clojure promotes large maps to hash-maps, whose iteration order isn't insertion order — so for a big batch where order matters, register the ordered paths one at a time.)

These paths address *your* data only. The framework's [runtime-db partition next door](../app-db.md) validates through its own machinery, and reaching into it is an error (see the callout below).

!!! warning "Gotcha — three ways to get a registration wrong"

    All three fail closed, because a malformed path or schema could otherwise install a validator that silently never checks anything — the worst outcome, a false sense of safety.

    - **A non-sequential path is rejected at registration.** The path must be a `get-in`-shaped sequential collection of keys (or `[]` for the root). Pass a bare keyword, string, or map and `reg-app-schema` throws `:rf.error/app-schema-bad-path` *before* anything registers. `reg-app-schemas` validates every key up front and rejects the whole batch atomically (`:rf.error/app-schemas-bad-batch` for a non-map argument). This check is always-on, not dev-only.
    - **A path that reaches into runtime-db is a hard error.** App-db schemas validate the [app-db](../glossary.md#app-db) partition only. Register one whose first segment is a reserved `:rf.runtime/*` key (or the retired `:rf/runtime` root) and you get `:rf.error/app-schema-runtime-path` at registration — [runtime-db](../glossary.md#runtime-db) is framework-owned, with no public schema surface; the remedy is to drop the runtime path. (See [app-db's two partitions](../app-db.md) for why the boundary is structural.)
    - **A malformed schema value fails closed at first check.** Malli validates schema *forms* lazily, so a structurally-broken schema (a childless `[:vector]`, an unknown op) registers cleanly and then throws on the first post-commit validation. The runtime isolates that per-entry: a distinct `:rf.error/malformed-schema` trace, the commit rolled back (it does *not* install unvalidated state), and the frame's sibling schemas keep validating — so one bad schema can't disable validation frame-wide.

## Put a schema on the event too

App-db schemas check writes *after* the fact. The complementary move is to refuse bad input *before* it ever reaches a handler — and that's what an event schema does. `reg-event` takes an optional metadata map between the id and the handler; the `:schema` key there describes the **event vector**, positionally, with `[:cat …]`:

```clojure
(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))
```

Read the `[:cat …]` positionally: the first slot is the event id itself, pinned with `[:= …]`; then a keyword; then a string. So if you dispatch `[:auth.login-form/edit-field "email" 42]`, the check fails *before* the handler runs — you get `:where :event`, the handler never runs, and the rest of the [event](../glossary.md#event) queue keeps draining.

Here's the contrast worth holding onto: **app-db schemas check writes after the fact; event schemas refuse bad input up front.** Same vocabulary, two complementary moments.

## The other three things you can schema

The `:schema` slot works on every registration kind — that's the whole point: one vocabulary, one failure trace, attached wherever data flows. What differs is the *recovery*, because a bad sub return and a bad effect argument are not the same kind of problem:

```clojure
;; A sub's RETURN value — validated after it computes.
(rf/reg-sub :favorited-articles
  {:schema [:vector Article]}
  (fn [db _] (filter :favorited? (:articles db))))

;; An fx's ARGUMENT map — validated before the effect handler runs.
(rf/reg-fx :http-xhrio
  {:schema [:map [:method :keyword] [:url :string]]}
  http-xhrio-handler)

;; A RECORDABLE coeffect's value — validated as it folds into the handler context.
(rf/reg-cofx :order/delta
  {:recordable? true                  ;; the grade that makes the schema a hard guard
   :schema [:int {:min 1 :max 6}]}
  (fn [] (inc (rand-int 6))))
```

Each one fails differently, on purpose:

- **Sub return** (`:where :sub-return`) — the failure is reported and the [subscription](../glossary.md#subscription) yields `nil` to its consumer (`:replaced-with-default`). [Views](../glossary.md#view) see no value rather than a bad one; the [pipeline run](../glossary.md#run) isn't aborted.
- **Fx args** (`:where :fx-args`) — the *offending [effect](../glossary.md#effect) is skipped*, and its siblings in the same `:fx` vector still run. A typo in one `:url` shouldn't take down the rest of an event's effects, so the recovery is "skip the one, continue the rest." The trace names the failing fx.
- **Recordable coeffect** — the exception to "always the same trace," and the one place the grade of the input decides what its schema does. A [coeffect](../glossary.md#coeffect) injected `{:recordable? true}` is saved verbatim so the run [replays](../glossary.md#time-travel) identically from it; an *ambient* one is read live and never recorded — the [two grades live in Effects & Coeffects](../coeffects.md). Because that saved value is data the replay machinery depends on, a recordable coeffect's schema is validated as the value folds in — and there a mismatch is a **production hard error** — it emits `:rf.error/cofx-value-invalid` and **throws**, halting the run. (That's why the example above is `{:recordable? true}`: only the recordable grade arms the throwing guard; an ambient supplier's `:schema` is a dev-only advisory like every other check.)

!!! warning "Gotcha — cofx is the asymmetric one"

    Every other `:schema` on this page is a dev-only advisory that vanishes in production. The recordable-coeffect check is the lone real, production, throwing guard — because it protects the recorded values your app would later replay from. Folding an out-of-contract value into that saved record means a future replay reconstructs corrupt state, so the framework refuses it on the spot, in production too. If you see `:rf.error/cofx-value-invalid` in a production trace, that's working as designed: the framework declined to record a value that would have poisoned a replay.

And one more surface, if you use [machines](../../machines/concepts.md). A machine's working memory — its `:data` slot — takes a schema too, declared at `[:schemas :data]` on the machine spec rather than via `reg-app-schema` (the snapshot lives in [runtime-db](../glossary.md#runtime-db), not app-db, so it's the machine that owns the shape). The runtime checks it after every transition and at boot, and a mismatch rolls the whole macrostep back exactly like an `app-db` failure — reported as `:where :machine-data`. You'll see that surface named in the trace's `:where` tag below; the full treatment lives in the machines guide.

```clojure
(rf/reg-machine :article/editor
  {:initial     :idle
   :data        {:tags []}
   :schemas     {:data [:map [:tags [:vector :string]]]}   ;; validates :data
   :states      {...}})
```

## Read the failure trace

Every violation is a structured `:rf.error/schema-validation-failure` [trace event](../glossary.md#trace-event), not a stack-trace blob — that's what makes it queryable by Xray and agents rather than only readable by you. The tags you'll actually use:

```clojure
{:operation :rf.error/schema-validation-failure
 :tags {:where      :app-db          ;; :event / :fx-args / :sub-return / :app-db / :machine-data / ...
        :path       [:auth :token]   ;; the FAILING LEAF path (root + navigation suffix to the bad slot)
        :value      "not-a-string"   ;; the offending value
        :explain    {...}            ;; the validator's explanation (a Malli explain map on CLJS)
        :failing-id :auth/init-bad   ;; the handler / sub / fx that produced it
        :frame      :rf/default       ;; which frame the failure happened in
        :rollback?  true             ;; on :app-db — the :db effect was discarded
        :recovery   :no-recovery}}   ;; what the runtime did next
```

Two tags reward a closer look. `:path` is the **failing leaf** — the registered root concatenated with the navigation suffix into the bad slot — so on an `[:auth]` schema a bad `:token` reports `[:auth :token]`, landing you on the exact slot. When you need the *registration* anchor instead (to jump back to the `reg-app-schema` call), an `:app-db` trace also carries `:registered-path`. And `:explain` is the raw validator output; tools that subscribe to these traces also receive `:explain-humanized` — Malli's natural-language version of the same thing — when the Malli adapter is loaded, falling back to `:explain` otherwise.

In **Xray**, these don't pile up in a footnote: the four runtime boundaries (`:event` / `:app-db` / `:fx-args` / `:sub-return`) attach to the matching DISPATCH / HANDLER / FX / SUBSCRIPTIONS step of the event row, and an `:app-db` rollback mutes every downstream step with a "run rolled back" banner so you read the blast radius at a glance. [Debug with Xray](../../xray/index.md) walks the panel.

One quiet consequence is worth saying plainly: the schema *is* the slice's description, and the runtime would catch any lie. A schema can't drift from reality, because the moment it does, a dispatch fails.

!!! warning "Gotcha — editing a schema mid-session can flag a value no handler wrote"

    Re-registering a path's schema is last-write-wins; a file save that re-evaluates a `reg-app-schema` with a *tighter* shape is the normal hot-reload path. But the value already sitting at that path was written under the *old* schema, so it may not satisfy the new one — through no handler's fault. The runtime doesn't fail a dispatch for this (nothing was dispatched); it emits a softer `:rf.schema/violation` warning trace (`:op-type :warning`) carrying `:path`, `:pre-reload-schema`, `:post-reload-schema`, and `:mismatching-value`, so a dev panel can highlight the now-stale slice. app-db is **not** auto-cleared or rewound — dispatch the event that rewrites the slice (or reset the frame) to clear it. This is the one schema signal that isn't tied to a dispatch.

## Query your schemas (tools and agents)

Register one schema and it does three jobs at once: the runtime checks it, it documents the slice, and tools and AI agents can *query* it — "what shape lives at `[:auth]`?" — which is a real, public API you'll use yourself when an agent maintains a slice or you write a test fixture.

There's one wrinkle in where these live. The *registration* macros you've used so far — `reg-app-schema` / `reg-app-schemas` — sit on the `rf/` namespace (`re-frame.core`, the main entry point you alias as `rf`). The *reader* functions sit one layer down, on the `re-frame.schemas` namespace — the same artefact whose require wired up validation at the top of the page. So to query schemas you require that namespace directly:

```clojure
(require '[re-frame.schemas :as schemas])

(schemas/app-schema-at [:auth])
;; → AuthSlice — the registered schema value at that path

(schemas/app-schema-meta-at [:auth])
;; → {:path [:auth] :schema AuthSlice :frame :rf/default :ns ... :line ... :file ...}
;;    the full registration metadata — what a "click back to code" jump uses

(schemas/app-schemas)
;; → {[:auth] AuthSlice, [:articles] RequestSlice, ...} for the active frame

(schemas/app-schemas {:frame :production})
;; → the {path → schema} map for a named frame

(schemas/app-schemas-digest)
;; → "sha256:abc1234567890def" — a stable hash of this frame's whole schema set
```

Each reader takes an optional `{:frame frame-id}` (or a bare frame-id), defaulting to the active frame; outside any frame scope it raises `:rf.error/no-frame-context`. Event/sub/fx schemas come back through the registrar query API instead — `(rf/handler-meta :event :auth/login)` returns `{:schema [:cat ...] :doc ... :ns ...}`.

The digest is the quiet workhorse. It's a single deterministic hash of a frame's whole `{path → schema}` set — same input, same hash, on any runtime — which makes it a cheap fingerprint for "are these two builds describing the same shapes?" The headline use is catching a deploy mismatch: when a server-rendered page [hydrates](../../ssr/glossary.md#hydration) in the browser, it carries the server's digest, and the client compares it against its own. If they differ (`:rf.ssr/schema-digest-mismatch`), the server bundle and the client bundle have drifted out of sync — exactly the deploy bug that's otherwise invisible until something renders wrong. The same readers serve other consumers too: AI agents read these surfaces to learn what shape to write *before* they dispatch, generators (Malli's `mg/generate`) turn a schema into test data, and pair-tools warn you when the running app's schema set has shifted under a REPL you're attached to.

## Keep a failing value out of the trace

A validation failure ships the failing value verbatim — that's what makes it debuggable. But a credential that fails its schema would otherwise leak through the trace to every listener, including off-box error monitors. So a schema slot can carry per-slot metadata — the same `{...}` properties map you'd use for `{:optional true}` — and two reserved keys there change how a *failure trace* behaves:

```clojure
(rf/reg-app-schema [:auth]
  {:schema [:map
            [:user  [:maybe [:map [:email :string] [:username :string]]]]
            [:token {:sensitive? true} [:maybe :string]]]})  ;; a bad :token fails REDACTED
```

- **`:sensitive? true`** redacts the value-bearing slots of the failure trace. When a slot marked sensitive fails, `:value`, `:explain` (it re-leaks the value), and the per-surface value slots are all replaced with the reserved sentinel `:rf/redacted`, and the trace is tagged `:sensitive? true`. The *structural* tags — `:path`, `:failing-id`, the schema id — stay, so you still locate the broken slot; only the data is scrubbed.
- **`:large? true`** swaps a `:rf.size/large-elided` marker in for the value instead of shipping a megabyte of base64 into the trace bus. A slot flagged both ways redacts on sensitivity (the size marker itself would leak a secret's signature) — sensitive wins.

!!! warning "Gotcha — a schema flag is a *trace* policy, not an *egress* policy"

    Marking a slot `:sensitive?` / `:large?` controls only what the **validation-failure trace** carries — it does *not* classify what your app sends across the wire in normal operation. Durable wire classification is a separate mechanism: the commit-plane effects a handler returns alongside `:db` (`{:db … :sensitive [[:auth :token]]}`). [Keep secrets out of traces](keep-secrets-out-of-traces.md) covers the whole privacy surface; the schema flags here are its path-level, validation-time corner.

## In production, the checks vanish

Dev builds check every registered schema at every validation point. That's the whole idea, and the cost is fine for dev. Production builds [elide](../glossary.md#elide) every validation site at compile time — under an `:advanced` build with `goog.DEBUG` set false ([Configure dev and production builds](configure-dev-and-prod.md) shows the flags), the compiler removes the validator calls, the error strings, the redaction code, all of it, from the bundle. Not skipped — *absent*. So write schemas freely; there's no hot-path bill. They stay *registered*, so tools and agents can still introspect them; they're just never *checked*. (The recordable-coeffect check from earlier is the lone exception — it's a real production guard.)

One place else does want production validation: untrusted data crossing a system boundary — an HTTP response, a websocket message, a `postMessage` payload. For those handlers, add the framework's boundary [interceptor](../glossary.md#interceptor) to the event's chain — an interceptor is a named step that wraps a handler — and it forces the handler's own `:schema` check to run regardless of the build flags. You add it by its id, `:rf.schema/at-boundary`, the same way you'd add any other interceptor:

```clojure
(rf/reg-event :api/tags-received
  {:schema [:cat [:= :api/tags-received] [:map [:tags [:vector :string]]]]
   :interceptors [:rf.schema/at-boundary]}      ;; reference the boundary interceptor by id
  (fn [{:keys [db]} [_ body]]
    {:db (assoc db :tags (:tags body))}))
```

The interceptor doesn't introduce a second schema — it re-uses the handler's existing `:schema` and only *forces* that check to survive past the production elision flag. In dev it adds nothing, since the check already runs; in production it's the one check that survives. Two things to know: putting it on a handler with **no `:schema`** is rejected at registration (`:rf.error/at-boundary-missing-schema`) — it has no check to force, so it's meaningless there — and you always reference it by the bare keyword id `:rf.schema/at-boundary`, never by reaching for the underlying interceptor value directly (a public `:interceptors` chain carries id references, not inline interceptor values). The result: payloads you didn't produce get checked even in production, while the other ninety-nine percent of your handlers stay zero-cost.

??? info "Coming from TanStack Query?"

    You probably validate API responses with a parser at the fetch boundary — `schema.parse(await res.json())`. `:rf.schema/at-boundary` is that idea, framework-native: the validation lives on the *handler* that receives the payload, survives production elision, and emits the same structured failure trace as every other check — so an untrusted-response shape error reads identically to an internal one in your tooling.

## Swap the validator (Malli isn't load-bearing)

Malli is the *default*, not a hard dependency — the runtime never inspects a `:schema` directly, it routes every check through a registered **validator fn**. That's the seam a port crosses to use Zod or Pydantic, and it's also how an app drops Malli for `clojure.spec` or disables validation wholesale. The setters live on `re-frame.schemas`, and the preferred one installs the whole bundle atomically at boot so the three fns never drift:

```clojure
(require '[re-frame.schemas :as schemas])

;; PREFERRED — install validator + explainer + printer in one atomic call.
(schemas/set-schema-fns! {:validate my-validate-fn   ;; (fn [schema value] truthy?)
                          :explain  my-explain-fn     ;; (fn [schema value] explanation)
                          :print    my-print-fn})     ;; (fn [schema-value] canonical-string) — feeds the digest

;; Lower-level single-fn setters — reach for these only to adjust one fn:
(schemas/set-schema-validator! my-validate-fn)
(schemas/set-schema-explainer! my-explain-fn)
(schemas/set-schema-printer!   my-print-fn)

;; Disable validation everywhere — every check short-circuits to "pass".
(schemas/set-schema-validator! nil)
```

A `nil` validator is the documented opt-out (every site passes, not fails), and one validator is in force per process — last-write-wins. You'll rarely touch any of this; it's here so the schema layer is genuinely pluggable, not so you reach for it on day one.

## When a schema earns its keep

One question decides it: *could this schema catch something no test of yours would?* If yes, write it.

**Reach for a schema** when a slice has more than two or three keys (every key widens the typo surface); when a value is the right type but constrainable — an `:enum` status, a non-negative `:int`, a regex'd string; when the slice is a contract between two features (one writes, another reads — the schema is the handshake); and whenever an AI agent maintains the slice, since agents read registered schemas to know what to write.

**Skip it** when the slice is a single scalar — `{:nav/open? true}` doesn't need `[:map [:open? :boolean]]`. And never register `:any` as a placeholder: it implies a constraint that isn't there, which is worse than silence.

Three conventions are worth adopting from day one. Use `[:enum …]` for fixed value sets, never bare `:keyword` — the enum is where the leverage lives. Keep maps open, closing only at boundaries. And keep each schema in the same namespace as the handlers that write its slice, because the schema is the slice's documentation, and documentation lives next to the thing it describes.

And when a slice clears the bar, promise me you'll write the schema. Promise me. Okay, good.
