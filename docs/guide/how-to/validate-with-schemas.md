# Validate with schemas

Picture a handler — a handler is the function that runs in response to an event — writing the wrong shape: `"loading"`, the string, where `:loading`, the keyword, belongs. What you want is for app-db (your app's single state map) to scream the instant that happens — name the handler, print the bad value, and cost production nothing. That's exactly what a schema gives you. This page covers the two places you reach for most — a schema on an app-db path, and a schema on an event (the data you dispatch to trigger a handler) — and then the rest of the surface: the other three things you can schema, the seven Malli shapes that handle nearly everything, how to read the failure trace, how tools and agents query your schemas, and how to tell when a schema is worth writing.

If you know **Zod**, you already have the right instinct: describe the shape once, then let a validator enforce it. Three things differ here, though. First, schemas are [Malli](https://github.com/metosin/malli) — plain data vectors, not builder chains. Second, you never call `parse()` at a use site; instead you *register* a schema against a path or an event id, and the runtime validates at fixed points in the cascade. Third, validation is **dev-only by default** — production compiles it out entirely. So a schema is a tripwire, not a guard. That third point has a sharp edge, and we'll get to it below.

Here's the payoff: register one piece of data and it does three jobs at once. The runtime checks it. Tools and AI agents can query it ("what shape lives at `[:auth]`?") — and you'll do exactly that yourself further down. And it documents the slice — which is the one way documentation stays honest:

> **A schema can't lie, because the runtime would catch the lie.**

## Bind a schema to an app-db path

`reg-app-schema` points a schema at a `get-in`-shaped path. Schemas register per-[frame](../concepts/frames.md) — a frame is one isolated instance of your app's state and machinery — so the call runs inside your frame scope. That's the same `with-frame` your boot dispatches already run in, so there's nothing new to set up:

```clojure
;; adapted from examples/reagent/realworld/schema.cljs
(ns myapp.schema
  (:require [re-frame.core :as rf]
            [re-frame.schemas]))   ;; loads the Malli validator — one require, once per app

(def AuthSlice
  [:map
   [:user  [:maybe [:map [:email :string] [:username :string]]]]
   [:token [:maybe :string]]])

(rf/with-frame :rf/default
  (rf/reg-app-schema [:auth] {:schema AuthSlice}))
```

Now, after every event handler runs, the runtime validates what the new app-db holds at `[:auth]` before installing it. When a write doesn't conform, the runtime emits `:rf.error/schema-validation-failure`, which carries `:where :app-db`, the failing path, the offending value, and a Malli explanation. The key thing is that **the write never lands**: app-db keeps its pre-event value, and the dispatch is treated as failed. So you get to debug a named handler and a printed bad value, not a half-corrupted app-db. Try it — cause one bad write with Xray open, and in the event's row you'll see the violation sit on the handler step, with everything downstream marked rolled back.

> **One require wires the validator.** On the CLJS reference, *requiring* `re-frame.schemas` is what turns a registered schema into a live check — the artefact `:require`s the Malli adapter, which installs the validator under the hood. So **schema implies validation**: a `reg-app-schema` is never a silent no-op. Leave the artefact out and your registrations still *record* (tools can read them) but never *fire*. That's the soft-pass default, and it exists so a first-time reader isn't blocked by a missing optional dep — but in a real app you require `re-frame.schemas` once at boot and forget about it.

Paths nest and overlap freely, which is more useful than it first sounds. A write under `[:auth :login-form]` is checked against that schema *and* the surrounding `[:auth]` one. A feature module usually declares its slices in one call with the plural form:

```clojure
(rf/with-frame :rf/default
  (rf/reg-app-schemas
   {[:auth]             AuthSlice
    [:auth :login-form] FormSlice
    [:articles]         RequestSlice
    [:articles :data]   [:vector Article]}))
```

`reg-app-schemas` takes a `{path → schema}` **map** and registers every entry in one call, last-write-wins on a duplicate path; it returns the vector of paths it registered. It's the right shape for a feature module declaring 5–20 slices under a shared prefix. Reach for the singular `reg-app-schema` instead when a feature spans only a path or two, or when you need deterministic registration order (the plural form follows map-iteration order, which small literals preserve but large hash-maps don't). [The RealWorld example](../../../examples/reagent/realworld/) registers nineteen paths this way — every slice that holds server data, every form draft. The empty path `[]` schemas the whole map. These paths address *your* data only; the framework's [runtime-db next door](../concepts/app-db.md) validates through its own machinery.

> **Coming from re-frame v1?** The `check-spec-interceptor` you hand-rolled from the todomvc example is built in now — and the vocabulary is `:schema` everywhere, not `:spec`. v1's `:spec` metadata key, the `:rf.spec/*` namespace, and the `:spec/at-boundary` interceptor are all gone with no back-compat alias; the framework no longer accepts `:spec` on `reg-*` metadata.

### Two ways to get it wrong (and how the framework tells you)

The path and the schema each have a fail-closed guard, because a malformed one of either could otherwise install a validator that silently never checks anything — the worst outcome, a false sense of safety.

- **A non-sequential path is rejected at registration.** The path must be a `get-in`/`assoc-in`-shaped sequential collection of keys (or `[]` for the root). Pass a bare keyword, a string, or a map, and `reg-app-schema` throws `:rf.error/bad-app-schema-path` *before* anything registers. `reg-app-schemas` validates every key up front and rejects the whole batch atomically (`:rf.error/bad-app-schemas-batch` if you hand it a non-map first argument) — one bad key can't half-register the rest. This check is always-on, not dev-only.
- **A path that reaches into runtime-db is a hard error.** App-db schemas validate the app-db partition only. Register one whose first segment is a reserved `:rf.runtime/*` key (or the retired `:rf/runtime` root) and you get `:rf.error/app-schema-runtime-path` at registration — the runtime-db partition is framework-owned, so there is no public schema surface there; the remedy is to drop the runtime path. (See [app-db's two partitions](../concepts/app-db.md) for why the boundary is structural.)
- **A malformed schema value fails closed at first check.** Malli validates schema *forms* lazily, so a structurally-broken schema (a childless `[:vector]`, an unknown op) registers cleanly and then throws on the first post-commit validation. The runtime isolates that per-entry: it surfaces a distinct `:rf.error/malformed-schema` trace, rolls the commit back (it does *not* install the unvalidated state), and keeps validating the frame's sibling schemas — so one bad schema can't disable validation frame-wide.

## The seven shapes you'll actually use

A Malli schema is a vector. The first element names the kind, optionally followed by a properties map. Seven shapes cover the overwhelming majority of app-db:

```clojure
[:map [:email :string] [:password :string]]   ;; a map with these keys
[:enum :idle :loading :loaded :error]         ;; one of a fixed set
[:int {:min 0}]                               ;; a bounded integer
[:string {:min 1}]                            ;; a non-empty string
[:re #".+@.+"]                                ;; a regex-shaped string
[:maybe :string]                              ;; a string, or nil
[:vector Article]                             ;; a homogeneous vector
```

These compose. A status field is an `:enum`. A form draft is a `[:map …]` of constrained strings. A feed is a `[:vector Article]` where `Article` is itself a `[:map …]`. Two defaults will surprise you once each, so it's worth knowing them now. Keys in a `[:map …]` are **required by default** — you relax one with a per-key properties map, `[:phone {:optional true} :string]`. And maps are **open by default**, meaning unknown extra keys pass. That openness is deliberate, so producers can add keys without breaking consumers; use `{:closed true}` to opt in at system boundaries, where you're checking a payload you don't trust. When these seven run out — and you'll reach for it less often than you'd think — `[:set …]`, `[:map-of …]`, `[:tuple …]`, `[:or …]`, and `[:fn pred]` are there, and the [Malli README](https://github.com/metosin/malli) has the full vocabulary.

## Put a schema on the event too

`reg-event` takes an optional metadata map between the id and the handler. The `:schema` key there describes the **event vector**, positionally, with `[:cat …]`:

```clojure
;; examples/reagent/realworld/auth.cljs
(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in [:auth :login-form :draft field] value)
             (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))
```

The first position is the event id itself, pinned with `[:= …]`. Then a keyword, then a string. So if you dispatch `[:auth.login-form/edit-field "email" 42]`, the check fails *before* the handler runs: you get `:where :event`, the handler never runs, and the rest of the event queue keeps draining. Here's the contrast worth holding onto: app-db schemas check writes after the fact, while event schemas refuse bad input up front.

## The other three things you can schema

The same `:schema` slot works on every registration kind, and that's the whole point — one vocabulary, one failure trace, attached wherever data flows. But the *recovery* differs by surface, because a bad event and a bad effect are not the same kind of problem. Three more surfaces, each with its own behaviour on failure:

```clojure
;; A sub's RETURN value — validated after it computes.
(rf/reg-sub :pending-todos
  {:schema [:vector TodoSchema]}
  (fn [db _] (filter pending? (:items db))))

;; An fx's ARGUMENT map — validated before the effect handler runs.
(rf/reg-fx :http-xhrio
  {:schema [:map [:method :keyword] [:url :string]]}
  http-xhrio-handler)

;; A coeffect's INJECTED value — validated as it folds into the handler context.
(rf/reg-cofx :now-wall
  {:schema inst?}
  (fn [] (js/Date.)))
```

What happens when each fails is worth holding in mind, because they're deliberately not uniform:

- **Sub return** (`:where :sub-return`) — the failure is reported and the sub yields `nil` to its consumer (`:replaced-with-default`). Views see no value rather than a bad one; the cascade isn't aborted.
- **Fx args** (`:where :fx-args`) — the *offending fx is skipped*, and its siblings in the same `:fx` vector still run. A typo in one `:url` shouldn't take down the rest of an event's effects, so the recovery is "skip the one, continue the rest." The trace names the failing fx.
- **Recordable coeffect** — this one is different, and it's the exception to "always the same failure trace." A coeffect your handler `:rf.cofx/requires` (a *recordable* world-input — the deterministic kind that replays, not a raw `:now-wall` clock read) is validated as it's satisfied, and a mismatch is a **production hard error**: it emits `:rf.error/cofx-value-invalid` and **throws**, halting the cascade. The reasoning is that folding an out-of-contract value into the durable causal ledger is corrupt state, so this check fires in production too — it is not the dev-only schema-validation tripwire the others are.

> **Gotcha — cofx is the asymmetric one.** Every other `:schema` is a dev-only advisory that elides in production. The recordable-coeffect check is a real, production, throwing guard on the data your event sourcing replays from. If you see `:rf.error/cofx-value-invalid` in a production trace, that's by design — the framework refused to record a value that would corrupt replay.

## Watch one catch a bug

Here's a counter whose count must never go below zero — live. The rule appears **twice**, on purpose. The handler *guards* (`pos?` — real behaviour, ships to production). The schema *declares* (`[:int {:min 0}]` — the dev tripwire). The cell runs in the playground's frame, so no `with-frame` is needed here. Click in and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then drive it with the buttons.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; The slice's shape: a non-negative count, and a history of the same.
(rf/reg-app-schema [:howto.schema/counter]
  {:schema [:map
            [:count   [:int {:min 0}]]
            [:history [:vector [:int {:min 0}]]]]})

(rf/reg-event :howto.schema/initialise
  {:schema [:cat [:= :howto.schema/initialise]]}
  (fn [{:keys [db]} _] {:db (assoc db :howto.schema/counter {:count 3 :history [3]})}))

(rf/reg-event :howto.schema/inc
  {:schema [:cat [:= :howto.schema/inc]]}
  (fn [{:keys [db]} _]
    (let [n (inc (get-in db [:howto.schema/counter :count]))]
      {:db (-> db
               (assoc-in  [:howto.schema/counter :count] n)
               (update-in [:howto.schema/counter :history] conj n))})))

;; The handler OWNS the never-below-zero rule — this guard ships to production.
(rf/reg-event :howto.schema/dec
  {:schema [:cat [:= :howto.schema/dec]]}
  (fn [{:keys [db]} _]
    (let [n (get-in db [:howto.schema/counter :count])]
      {:db (if (pos? n)
             (-> db
                 (assoc-in  [:howto.schema/counter :count] (dec n))
                 (update-in [:howto.schema/counter :history] conj (dec n)))
             db)})))

(rf/reg-sub :howto.schema/count
  (fn [db _] (get-in db [:howto.schema/counter :count])))

(rf/reg-sub :howto.schema/history
  (fn [db _] (get-in db [:howto.schema/counter :history])))

(defn schema-counter []
  [:div
   [:button {:on-click #(rf/dispatch [:howto.schema/dec])} "-"]
   [:span {:style {:margin "0 1em" :font-size "1.4em"}}
    @(rf/subscribe [:howto.schema/count])]
   [:button {:on-click #(rf/dispatch [:howto.schema/inc])} "+"]
   [:div {:style {:margin-top "0.75em" :color "#666" :font-size "0.85em"}}
    "history: " (str @(rf/subscribe [:howto.schema/history]))]])

(rf/dispatch-sync [:howto.schema/initialise])
[schema-counter]
```

Click `-` down to `0` and keep clicking: nothing happens, because the `pos?` guard stands. Now simulate the bug the schema exists to catch. **Delete the guard** — replace `(if (pos? n) (-> db …) db)` with just the `(-> db …)` threading — re-evaluate, and click `-` past zero. The handler writes `-1`, and `[:int {:min 0}]` rejects it. The browser console shows the `:rf.error/schema-validation-failure`, and the count on screen stays `0`: the write was rolled back, so app-db never held the bad value. Put the guard back when you're done.

> **Gotcha — the rollback is a debugging aid, not app behaviour.** Validation, rollback included, is compiled out of production builds. In production, that unguarded handler happily ships `-1`. So the handler keeps its real guard, *always*; the schema's job is to catch the day the guard gets deleted, refactored wrong, or bypassed by some *other* handler writing the same slice — in dev, the moment it happens, instead of in a bug report six weeks later.

## Read the failure trace

Every violation is a structured `:rf.error/schema-validation-failure` trace, not a stack-trace blob — that's what makes it queryable by Xray and agents rather than only readable by you. The tags you'll actually use:

```clojure
{:operation :rf.error/schema-validation-failure
 :tags {:where      :app-db          ;; :event / :fx-args / :sub-return / :app-db / :machine-data / ...
        :path       [:auth :token]   ;; the FAILING LEAF path (root + the navigation suffix to the bad slot)
        :value      "not-a-string"   ;; the offending value
        :explain    {...}            ;; the validator's explanation (a Malli explain map on CLJS)
        :failing-id :auth/init-bad   ;; the handler / sub / fx that produced it
        :frame      :rf/default       ;; which frame the failure happened in
        :rollback?  true             ;; on :app-db — the :db effect was discarded
        :recovery   :no-recovery}}   ;; what the runtime did next
```

Two tags reward a closer look. `:path` is the **failing leaf** — the registered root concatenated with the navigation suffix into the bad slot — so on an `[:auth]` schema a bad `:token` reports `[:auth :token]`, landing you on the exact slot. When you need the *registration* anchor instead (to jump back to the `reg-app-schema` call), an `:app-db` trace also carries `:registered-path`. And `:explain` is the raw validator output; tools that subscribe to these traces also receive `:explain-humanized` — Malli's natural-language version of the same thing — when the Malli adapter is loaded, falling back to `:explain` otherwise.

In **Xray**, these don't pile up in a footnote: the four runtime boundaries (`:event` / `:app-db` / `:fx-args` / `:sub-return`) attach to the matching DISPATCH / HANDLER / FX / SUBSCRIPTIONS step of the event row, and an `:app-db` rollback mutes every downstream step with a "cascade rolled back" banner so you read the blast radius at a glance. [Debug with Xray](debug-with-xray.md) walks the panel.

## Query your schemas (tools and agents)

That "tools and AI agents can query it" promise is a real, public API — and you'll use it yourself when an agent is maintaining a slice or you're writing a test fixture. The introspection functions live on the **`re-frame.schemas`** namespace, not the `rf/` facade (the *registration* macros `reg-app-schema` / `reg-app-schemas` are on `rf/`; the *readers* are on the artefact). So require it directly:

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

The digest is the quiet workhorse: because it's a deterministic, cross-runtime hash of the registered `{path → schema}` set, SSR hydration ships the server's digest and the client compares its own on hydrate — a mismatch (`:rf.ssr/schema-digest-mismatch`) catches the deploy-drift bug where the server bundle's schemas have moved ahead of the client's. Agents read these surfaces to know what shape to write *before* they dispatch, generators (Malli's `mg/generate`) turn them into test data, and pair-tools warn when the runtime's schema set has shifted under an attached REPL.

## Mark slots sensitive or large

A schema slot can carry per-slot metadata — the same `{...}` properties map you'd use for `{:optional true}` — and two reserved keys there change how a *failure trace* behaves. They matter precisely because a validation failure ships the failing value verbatim: a credential that fails its schema would otherwise leak through the trace to every listener, including off-box error monitors.

- **`:sensitive? true`** redacts the value-bearing slots of the failure trace. When a slot marked sensitive fails, `:value`, `:explain` (it re-leaks the value), and the per-surface value slots are all replaced with the reserved sentinel `:rf/redacted`, and the trace is tagged `:sensitive? true`. The *structural* tags — `:path`, `:failing-id`, the schema id — stay, so you still locate the broken slot; only the data is scrubbed.

```clojure
(rf/reg-app-schema [:auth]
  {:schema [:map
            [:user  [:maybe [:map [:email :string] [:username :string]]]]
            [:token {:sensitive? true} [:maybe :string]]]})  ;; a bad :token fails REDACTED
```

- **`:large? true`** swaps a `:rf.size/large-elided` marker in for the value instead of shipping a megabyte of base64 into the trace bus. A slot flagged both ways redacts on sensitivity (the size marker itself would leak a secret's signature) — sensitive wins.

> **A schema is a *trace* policy, not an *egress* policy.** Marking a slot `:sensitive?` / `:large?` controls only what the **validation-failure trace** carries — it does *not* classify what your app sends across the wire in normal operation. Durable wire classification is declared on the frame (`reg-frame` `:sensitive` / `:large`), a separate mechanism. [Keep secrets out of traces](keep-secrets-out-of-traces.md) covers the whole privacy surface; the schema flags here are its path-level, validation-time corner.

## In production, the checks vanish

Dev builds check every registered schema at every validation point. That's the whole idea, and the cost is fine for dev. Production builds eliminate every validation site **at compile time** — under an `:advanced` build with `goog.DEBUG` set false ([Configure dev and production builds](configure-dev-and-prod.md) shows the flags), the compiler removes the validator calls, the error strings, the redaction code, all of it, from the bundle. Not skipped — *absent*. So write schemas freely, because there's no hot-path bill. They stay *registered*, so tools and agents can still introspect them; they're just never *checked*. (The recordable-coeffect check from earlier is the lone exception — it's a real production guard.)

One place else does want production validation: untrusted data crossing a system boundary, like an HTTP response, a websocket message, or a `postMessage` payload. For those handlers, reference the framework's boundary interceptor in the chain, which forces the handler's own `:schema` check regardless of the build flags. It's a registered interceptor like any other, so the chain carries its id — `:rf.schema/at-boundary` — not an inline value:

```clojure
(rf/reg-event :api/tags-received
  {:schema [:cat [:= :api/tags-received] [:map [:tags [:vector :string]]]]
   :interceptors [:rf.schema/at-boundary]}      ;; reference the boundary interceptor by id
  (fn [{:keys [db]} [_ body]]
    {:db (assoc db :tags (:tags body))}))
```

The interceptor doesn't introduce a second schema — it re-uses the handler's existing `:schema` and only *forces* the check past the elision flag. In dev it adds nothing, since the check already runs; in production it's the one check that survives. Two things to know: putting it on a handler with **no `:schema`** is rejected at registration (`:rf.error/at-boundary-missing-schema`) — it's structurally meaningless without one — and you reference it by the bare keyword id, never by dropping the `validate-at-boundary-interceptor` value Var into the chain (under EP-0022 a public `:interceptors` chain carries refs, not inline values). The result is that payloads you didn't produce get checked everywhere, and the other ninety-nine percent of your handlers stay zero-cost.

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

`nil` validator is the documented opt-out (every site passes, not fails), and one validator is in force per process — last-write-wins. You'll rarely touch any of this; it's here so the schema layer is genuinely pluggable, not so you reach for it on day one.

## When a schema earns its keep

One question decides it: *could this schema catch something no test of yours would?* If yes, write it.

**Reach for a schema** when a slice has more than two or three keys (every key widens the typo surface); when a value is the right type but constrainable — an `:enum` status, a non-negative `:int`, a regex'd string; when the slice is a contract between two features (one writes, another reads — the schema is the handshake); and whenever an AI agent maintains the slice, since agents read registered schemas to know what to write.

**Skip it** when the slice is a single scalar — `{:nav/open? true}` doesn't need `[:map [:open? :boolean]]`. And never register `:any` as a placeholder: it implies a constraint that isn't there, which is worse than silence.

Three conventions are worth adopting from day one. Use `[:enum …]` for fixed value sets, never bare `:keyword` — the enum is where the leverage lives. Keep maps open, closing only at boundaries. And keep each schema in the same namespace as the handlers that write its slice, because the schema is the slice's documentation, and documentation lives next to the thing it describes.

---

**You can now:**

- register a Malli schema at an app-db path — a feature's worth at once with `reg-app-schemas` — and read the failure trace when a write violates it
- put a `[:cat …]` schema on an event: events are refused before the handler, app-db writes rolled back after
- schema a sub's return, an fx's args, and a coeffect — and know each one's distinct recovery (yield `nil` / skip the fx / throw in production)
- write the seven shapes that cover most slices, plus the two defaults — keys required, maps open
- read a `:rf.error/schema-validation-failure` trace (`:where`, `:path`, `:value`, `:explain`, `:rollback?`) and query your frame's schemas with `app-schema-at` / `app-schemas` / `app-schemas-digest`
- mark a slot `:sensitive?` or `:large?` so its failing value never leaks verbatim into a trace
- explain why the handler keeps its real guard even though the schema catches the bad write in dev
- force production validation at a system boundary by referencing the `:rf.schema/at-boundary` interceptor, and nowhere else
- swap Malli for another validator (or disable it) through `set-schema-fns!` on `re-frame.schemas`
- decide which slices deserve a schema, and which genuinely don't
