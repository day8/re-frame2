# Validate with schemas

Picture a handler — a handler is the function that runs in response to an event — writing the wrong shape: `"loading"`, the string, where `:loading`, the keyword, belongs. What you want is for app-db (your app's single state map) to scream the instant that happens — name the handler, print the bad value, and cost production nothing. That's exactly what a schema gives you. This page covers two places you can attach one: a schema on an app-db path, and a schema on an event (the data you dispatch to trigger a handler). It also covers the seven Malli shapes that handle nearly everything, and how to tell when a schema is worth writing.

If you know **Zod**, you already have the right instinct: describe the shape once, then let a validator enforce it. Three things differ here, though. First, schemas are [Malli](https://github.com/metosin/malli) — plain data vectors, not builder chains. Second, you never call `parse()` at a use site; instead you *register* a schema against a path or an event id, and the runtime validates at fixed points in the cascade. Third, validation is **dev-only by default** — production compiles it out entirely. So a schema is a tripwire, not a guard. That third point has a sharp edge, and we'll get to it below.

Here's the payoff: register one piece of data and it does three jobs at once. The runtime checks it. Tools and AI agents can query it ("what shape lives at `[:auth]`?"). And it documents the slice — which is the one way documentation stays honest:

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

Paths nest and overlap freely, which is more useful than it first sounds. A write under `[:auth :login-form]` is checked against that schema *and* the surrounding `[:auth]` one. A feature module usually declares its slices in one call with the plural form:

```clojure
(rf/with-frame :rf/default
  (rf/reg-app-schemas
   {[:auth]             AuthSlice
    [:auth :login-form] FormSlice
    [:articles]         RequestSlice
    [:articles :data]   [:vector Article]}))
```

[The RealWorld example](../../../examples/reagent/realworld/) registers nineteen paths this way — every slice that holds server data, every form draft. The empty path `[]` schemas the whole map. These paths address *your* data only; the framework's [runtime-db next door](../concepts/app-db.md) validates through its own machinery.

> **Coming from re-frame v1?** The `check-spec-interceptor` you hand-rolled from the todomvc example is built in now — and the vocabulary is `:schema` everywhere, not `:spec`.

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

These compose. A status field is an `:enum`. A form draft is a `[:map …]` of constrained strings. A feed is a `[:vector Article]` where `Article` is itself a `[:map …]`. Two defaults will surprise you once each, so it's worth knowing them now. Keys in a `[:map …]` are **required by default** — you relax one with a per-key properties map, `[:phone {:optional true} :string]`. And maps are **open by default**, meaning unknown extra keys pass. That openness is deliberate, so producers can add keys without breaking consumers; use `{:closed true}` to opt in at system boundaries, where you're checking a payload you don't trust. When these seven run out — and you'll reach for it less often than you'd think — `[:set …]`, `[:map-of …]`, and `[:fn pred]` are there, and the [Malli README](https://github.com/metosin/malli) has the full vocabulary.

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

The same `:schema` slot works on every registration kind — a `reg-sub`'s return value (a subscription is a derived, reactive read of app-db), a `reg-fx`'s argument map (an effect is a description of a side effect to perform), a `reg-cofx`'s injected value (a coeffect is data injected into a handler, like the current time). It's always a Malli schema in the metadata map, and always the same failure trace.

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

!!! warning "The rollback is a debugging aid, not app behaviour"

    Validation — rollback included — is compiled out of production builds. In production, that unguarded handler happily ships `-1`. So the handler keeps its real guard, always; the schema's job is to catch the day the guard gets deleted, refactored wrong, or bypassed by some *other* handler writing the same slice — in dev, the moment it happens, instead of in a bug report six weeks later.

## In production, the checks vanish

Dev builds check every registered schema at every validation point. That's the whole idea, and the cost is fine for dev. Production builds eliminate every validation site **at compile time** — under an `:advanced` build with `goog.DEBUG` set false ([Configure dev and production builds](configure-dev-and-prod.md) shows the flags), the compiler removes the validator calls, the error strings, all of it, from the bundle. Not skipped — *absent*. So write schemas freely, because there's no hot-path bill. They stay *registered*, so tools and agents can still introspect them; they're just never *checked*.

One place does want production validation, though: untrusted data crossing a system boundary, like an HTTP response, a websocket message, or a `postMessage` payload. For those handlers, reference the framework's boundary interceptor in the chain, which forces the handler's own `:schema` check regardless of the build flags. It's a registered interceptor like any other, so the chain carries its id — `:rf.schema/at-boundary` — not an inline value:

```clojure
(rf/reg-event :api/tags-received
  {:schema [:cat [:= :api/tags-received] [:map [:tags [:vector :string]]]]
   :interceptors [:rf.schema/at-boundary]}      ;; reference the boundary interceptor by id
  (fn [{:keys [db]} [_ body]]
    {:db (assoc db :tags (:tags body))}))
```

In dev it adds nothing, since the check already runs; in production it's the one check that survives. Registering it on a handler with no `:schema` is rejected outright. The result is that payloads you didn't produce get checked everywhere, and the other ninety-nine percent of your handlers stay zero-cost.

## When a schema earns its keep

One question decides it: *could this schema catch something no test of yours would?* If yes, write it.

**Reach for a schema** when a slice has more than two or three keys (every key widens the typo surface); when a value is the right type but constrainable — an `:enum` status, a non-negative `:int`, a regex'd string; when the slice is a contract between two features (one writes, another reads — the schema is the handshake); and whenever an AI agent maintains the slice, since agents read registered schemas to know what to write.

**Skip it** when the slice is a single scalar — `{:nav/open? true}` doesn't need `[:map [:open? :boolean]]`. And never register `:any` as a placeholder: it implies a constraint that isn't there, which is worse than silence.

Three conventions are worth adopting from day one. Use `[:enum …]` for fixed value sets, never bare `:keyword` — the enum is where the leverage lives. Keep maps open, closing only at boundaries. And keep each schema in the same namespace as the handlers that write its slice, because the schema is the slice's documentation, and documentation lives next to the thing it describes.

---

**You can now:**

- register a Malli schema at an app-db path — a feature's worth at once with `reg-app-schemas` — and read the failure trace when a write violates it
- put a `[:cat …]` schema on an event: events are refused before the handler, app-db writes rolled back after
- write the seven shapes that cover most slices, plus the two defaults — keys required, maps open
- explain why the handler keeps its real guard even though the schema catches the bad write in dev
- force production validation at a system boundary by referencing the `:rf.schema/at-boundary` interceptor, and nowhere else
- decide which slices deserve a schema, and which genuinely don't
