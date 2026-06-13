# Validate with schemas

You want app-db to scream the instant a handler writes the wrong shape — `"loading"` the string where `:loading` the keyword belongs — naming the handler, printing the bad value, costing production nothing. That's what schemas are for. This page covers the two attachment points you'll use daily — a schema on an app-db path, a schema on an event — the seven Malli shapes that cover nearly everything, and when a schema earns its keep.

If you know **Zod**, you have the right instinct: describe the shape once, let a validator enforce it. Three deliberate differences. Schemas here are [Malli](https://github.com/metosin/malli) — plain data vectors, not builder chains. You never call `parse()` at a use site — you *register* a schema, against a path or an event id, and the runtime validates at fixed points in the cascade. And validation is **dev-only by default**: production compiles it out entirely, which changes what a schema is *for* — a tripwire, not a guard. That last difference has a sharp edge this page gets to below.

One registered piece of data then does three jobs: the runtime checks it, tools and AI agents query it ("what shape lives at `[:auth]`?"), and it documents the slice the one way documentation stays true:

> **A schema can't lie, because the runtime would catch the lie.**

## Bind a schema to an app-db path

`reg-app-schema` points a schema at a `get-in`-shaped path. Schemas register per-[frame](../concepts/frames.md), so the call runs inside your frame scope — the same `with-frame` your boot dispatches already run in:

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
  (rf/reg-app-schema [:auth] AuthSlice))
```

From now on, after every event handler runs, the runtime validates what the new app-db holds at `[:auth]` before installing it. When a write doesn't conform, it emits `:rf.error/schema-validation-failure` — carrying `:where :app-db`, the failing path, the offending value, and a Malli explanation — **and the write never lands**: app-db keeps its pre-event value, and the dispatch is treated as failed. You debug a named handler and a printed bad value, not a half-corrupted app-db. Cause one bad write with Xray open: in the event's row, the violation sits on the handler step, with everything downstream marked rolled back.

Paths nest and overlap freely — a write under `[:auth :login-form]` is checked against that schema *and* the surrounding `[:auth]` one. A feature module usually declares its slices in one call with the plural form:

```clojure
(rf/with-frame :rf/default
  (rf/reg-app-schemas
   {[:auth]             AuthSlice
    [:auth :login-form] FormSlice
    [:articles]         RequestSlice
    [:articles :data]   [:vector Article]}))
```

[The RealWorld example](../../../examples/reagent/realworld/) registers nineteen paths this way — every slice that holds server data, every form draft. The empty path `[]` schemas the whole map. These paths address *your* data only: the framework's [runtime-db next door](../concepts/app-db.md) validates through its own machinery.

> **Coming from re-frame v1?** The `check-spec-interceptor` you hand-rolled from the todomvc example is built in now — and the vocabulary is `:schema` everywhere, not `:spec`.

## The seven shapes you'll actually use

A Malli schema is a vector whose first element names the kind, optionally followed by a properties map. Seven shapes cover the overwhelming majority of app-db:

```clojure
[:map [:email :string] [:password :string]]   ;; a map with these keys
[:enum :idle :loading :loaded :error]         ;; one of a fixed set
[:int {:min 0}]                               ;; a bounded integer
[:string {:min 1}]                            ;; a non-empty string
[:re #".+@.+"]                                ;; a regex-shaped string
[:maybe :string]                              ;; a string, or nil
[:vector Article]                             ;; a homogeneous vector
```

They compose: a status field is an `:enum`, a form draft is a `[:map …]` of constrained strings, a feed is a `[:vector Article]` where `Article` is itself a `[:map …]`. Two defaults will surprise you exactly once. Keys in a `[:map …]` are **required by default** — relax one with a per-key properties map, `[:phone {:optional true} :string]`. And maps are **open by default** — unknown extra keys pass, deliberately, so producers can add keys without breaking consumers; `{:closed true}` is an opt-in for system boundaries where you're checking a payload you don't trust. When these seven run out — `[:set …]`, `[:map-of …]`, `[:fn pred]` — the [Malli README](https://github.com/metosin/malli) has the full vocabulary; you'll reach for it less often than you'd think.

## Put a schema on the event too

Every `reg-event-*` takes an optional metadata map between the id and the handler; `:schema` there describes the **event vector**, positionally, with `[:cat …]`:

```clojure
;; examples/reagent/realworld/auth.cljs
(rf/reg-event-db :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))))
```

First position is the event id itself, pinned with `[:= …]`; then a keyword; then a string. Dispatch `[:auth.login-form/edit-field "email" 42]` and the check fails *before* the handler runs — `:where :event`, handler never invoked, the rest of the event queue keeps draining. App-db schemas check writes after the fact; event schemas refuse bad input up front.

The same `:schema` slot works on every registration kind — a `reg-sub`'s return value, a `reg-fx`'s argument map, a `reg-cofx`'s injected value — always a Malli schema in the metadata map, always the same failure trace.

## Watch one catch a bug

Here's a counter whose count must never go below zero — live. The rule appears **twice**, on purpose: the handler *guards* (`pos?` — real behaviour, ships to production) and the schema *declares* (`[:int {:min 0}]` — the dev tripwire). The cell runs in the playground's frame, so no `with-frame` is needed here. Click in and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then drive it with the buttons.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; The slice's shape: a non-negative count, and a history of the same.
(rf/reg-app-schema [:howto.schema/counter]
  [:map
   [:count   [:int {:min 0}]]
   [:history [:vector [:int {:min 0}]]]])

(rf/reg-event-db :howto.schema/initialise
  {:schema [:cat [:= :howto.schema/initialise]]}
  (fn [db _] (assoc db :howto.schema/counter {:count 3 :history [3]})))

(rf/reg-event-db :howto.schema/inc
  {:schema [:cat [:= :howto.schema/inc]]}
  (fn [db _]
    (let [n (inc (get-in db [:howto.schema/counter :count]))]
      (-> db
          (assoc-in  [:howto.schema/counter :count] n)
          (update-in [:howto.schema/counter :history] conj n)))))

;; The handler OWNS the never-below-zero rule — this guard ships to production.
(rf/reg-event-db :howto.schema/dec
  {:schema [:cat [:= :howto.schema/dec]]}
  (fn [db _]
    (let [n (get-in db [:howto.schema/counter :count])]
      (if (pos? n)
        (-> db
            (assoc-in  [:howto.schema/counter :count] (dec n))
            (update-in [:howto.schema/counter :history] conj (dec n)))
        db))))

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

Click `-` down to `0` and keep clicking: nothing happens — the `pos?` guard stands. Now simulate the bug the schema exists to catch. **Delete the guard** — replace `(if (pos? n) (-> db …) db)` with just the `(-> db …)` threading — re-evaluate, and click `-` past zero. The handler writes `-1`; `[:int {:min 0}]` rejects it; the browser console shows the `:rf.error/schema-validation-failure`, and the count on screen stays `0` — the write was rolled back, app-db never held the bad value. Put the guard back.

> **The rollback is a debugging aid, not app behaviour.** Validation — rollback included — is compiled out of production builds. In production, that unguarded handler happily ships `-1`. So the handler keeps its real guard, always; the schema's job is to catch the day the guard gets deleted, refactored wrong, or bypassed by some *other* handler writing the same slice — in dev, the moment it happens, instead of in a bug report six weeks later.

## In production, the checks vanish

Dev builds check every registered schema at every validation point — that's the whole idea, and the cost is fine for dev. Production builds eliminate every validation site **at compile time**: under an `:advanced` build with `goog.DEBUG` set false ([Configure dev and production builds](configure-dev-and-prod.md) shows the flags), the compiler removes the validator calls, the error strings, all of it, from the bundle. Not skipped — *absent*. So write schemas freely; there is no hot-path bill. They stay *registered* — tools and agents can still introspect them — they're just never *checked*.

The one place you do want production validation is untrusted data crossing a system boundary: an HTTP response, a websocket message, a `postMessage` payload. For those handlers, add the boundary interceptor — it forces the handler's own `:schema` check regardless of the build flags:

```clojure
(rf/reg-event-fx :api/tags-received
  {:schema [:cat [:= :api/tags-received] [:map [:tags [:vector :string]]]]}
  [rf/validate-at-boundary-interceptor]
  (fn [{:keys [db]} [_ body]]
    {:db (assoc db :tags (:tags body))}))
```

In dev it adds nothing (the check already runs); in production it's the one check that survives. Registering it on a handler with no `:schema` is rejected outright. The result: payloads you didn't produce get checked everywhere; the other ninety-nine percent of your handlers stay zero-cost.

## When a schema earns its keep

One question decides it: *could this schema catch something no test of yours would?* If yes, write it.

**Reach for a schema** when a slice has more than two or three keys (every key widens the typo surface); when a value is the right type but constrainable — an `:enum` status, a non-negative `:int`, a regex'd string; when the slice is a contract between two features (one writes, another reads — the schema is the handshake); and whenever an AI agent maintains the slice, since agents read registered schemas to know what to write.

**Skip it** when the slice is a single scalar — `{:nav/open? true}` doesn't need `[:map [:open? :boolean]]`. And never register `:any` as a placeholder: it implies a constraint that isn't there, which is worse than silence.

Three conventions from day one: use `[:enum …]` for fixed value sets, never bare `:keyword` — the enum is where the leverage lives; keep maps open, closing only at boundaries; and keep each schema in the same namespace as the handlers that write its slice — the schema is the slice's documentation, and documentation lives next to the thing it describes.

---

**You can now:**

- register a Malli schema at an app-db path — a feature's worth at once with `reg-app-schemas` — and read the failure trace when a write violates it
- put a `[:cat …]` schema on an event: events are refused before the handler, app-db writes rolled back after
- write the seven shapes that cover most slices, plus the two defaults — keys required, maps open
- explain why the handler keeps its real guard even though the schema catches the bad write in dev
- force production validation at a system boundary with `validate-at-boundary-interceptor`, and nowhere else
- decide which slices deserve a schema, and which genuinely don't

**Next:** [Build a form](build-a-form.md) — schemas doing daily work on a draft slice · [Configure dev and production builds](configure-dev-and-prod.md) — the flags that make elision real
