# 08 — Schemas

You want your app to scream the instant `app-db` goes wrong — wrong key, wrong type, a `:status` that drifted from `:submitted` the keyword to `"submitted"` the string — and you want that scream to happen in dev, the moment it happens, pointing at the exact bad value. And you want it to cost *exactly nothing* in production. This chapter is that schema boundary and the off-switch that makes it free.

## The bug that ships green and surfaces six weeks later

Here's the re-frame2 story so far, in one sentence: events change `app-db`, views read it, every handler is a pure function over plain Clojure data. That's the whole machine, and it works beautifully. But "plain Clojure data" is a category roomy enough to hide a *lot* of bugs, and the worst ones share a profile. They don't throw. They don't crash. They don't fail a test, because you never thought to write the test that would've caught them — who asserts that `:status` is still a keyword? They just quietly write a slightly-wrong shape into `app-db`, the app keeps running, everything's green, you ship it, and then six weeks later someone files "the avatar disappeared on the profile page" and you spend an afternoon discovering that a handler three features away wrote `:user/id` as a string when everything downstream expected an int.

The maddening part is that ClojureScript can't help you here. There's no type system to catch `:status :submited` (note the typo) before it lands. The compiler is delighted to let you `assoc` any keyword you like. So the bad shape sails straight through compilation, straight through your test suite, and out into a user's browser, where it manifests as a visual glitch with no stack trace and no obvious cause. This is the single most common way a re-frame2 app — or any app over plain data — rots: not a loud failure, a quiet one, displaced in time and space from its cause.

A **schema** is how you close that gap. A schema is just a piece of data that describes *what shape another piece of data must have*. Bind one to an `app-db` path and the runtime checks that slice after every handler runs. Bind one to an event and the runtime checks the event vector before the handler runs. The instant a write produces the wrong shape, you find out — in dev, immediately, with an explanation pointing at the exact key and value that went bad. The bug stops being "the avatar disappeared six weeks later" and becomes "this handler, right now, just tried to write `-1` into a slice that says non-negative."

And — this is the part that makes it *usable* rather than merely virtuous — in production, every single one of those checks vanishes at compile time. Write schemas freely, in volume, on every slice and every event you like. They cost you nothing where it counts.

## Concepts first: it's all just data

Before any syntax, hold onto the shape of the idea, because the syntax is dense and it's easy to lose the forest.

A schema is **data, not code**. It's a Clojure value — a vector of keywords, mostly — that you write down once, next to the thing it describes. It doesn't *do* anything by itself. It just sits there being the canonical answer to "what's this supposed to look like?" The runtime is what gives it teeth: at a few well-defined moments — after a handler writes `app-db`, before a handler reads an event — the runtime takes the relevant schema and the relevant data and asks "does this fit?" If yes, nothing happens, the cascade rolls on. If no, you get a precise, located error.

Because the schema is data, it does triple duty. It's **validation** (the runtime checks it). It's **documentation that can't drift** (comments rot because nothing enforces them; a schema can't lie, because the runtime would catch the lie). And it's an **AI-facing contract** — an agent can query the registered schemas and *know* the shape of every slice and event in your app, then pre-check "what would happen if I dispatched `[:auth/login {...}]`?" against the event's schema before firing a thing. One piece of data, three jobs. That's the leverage.

re-frame2 ships [Malli](https://github.com/metosin/malli) as the default validator. (It's pluggable — Spec 010 is the extension point — but every example in this guide is Malli, and unless you've got a specific reason, you should be too.)

## The Malli vocabulary you'll actually use

A Malli schema is a *vector* whose first element is a keyword naming the schema kind, optionally followed by a properties map, then the body. There's a lot of vocabulary, but seven shapes carry about ninety percent of the schemas you'll ever write:

```clojure
;; A map with two required keys.
[:map
 [:email    :string]
 [:password :string]]

;; A keyword from a fixed set — an enum.
[:enum :idle :submitting :submitted :error]

;; An integer with a lower bound.
[:int {:min 0}]

;; A non-empty string.
[:string {:min 1}]

;; A regex predicate.
[:re #".+@.+"]

;; The leaf scalars.
:keyword            ;; any keyword
:uuid               ;; a UUID
:boolean            ;; true or false
:any                ;; anything; useful for "I haven't figured out the shape yet"
[:maybe :string]    ;; either a string, or nil
```

These compose, which is the whole point. A login form's draft is `[:map [:email [:re #".+@.+"]] [:password [:string {:min 8}]]]`. A status field is `[:enum :idle :submitting :submitted :error]`. A nested slice is a `[:map ...]` containing other `[:map ...]` entries, all the way down. There's more vocabulary when you need it — `[:vector ...]`, `[:set ...]`, `[:map-of ...]`, `[:fn pred]`, custom registries — but reach for those when the seven above can't say what you mean, which is rarer than you'd think.

Two defaults worth knowing because they'll surprise you otherwise. A key in a `[:map ...]` is **required by default** — to make one optional, add `{:optional true}` to its properties map. And a `[:map ...]` is **open by default** — extra keys not named in the schema are tolerated, deliberately, because it matches re-frame2's "consumers tolerate unknown keys" convention. To reject extras, opt into `{:closed true}`, which you'll typically only want at a system boundary where you're validating an incoming payload from somewhere you don't trust.

```clojure
[:map
 [:email                    :string]
 [:phone   {:optional true} :string]]    ;; present or absent, both fine
```

## Binding a schema to an `app-db` path

Now the everyday API. `reg-app-schema` points a schema at an `app-db` path:

```clojure
(def AuthSlice
  [:map
   [:user    [:maybe [:map [:id :uuid] [:email :string]]]]
   [:status  [:enum :anonymous :authenticated]]])

(rf/reg-app-schema [:auth] AuthSlice)
```

From now on, after every event handler returns, the runtime reaches into the new `app-db` at `[:auth]`, pulls out whatever's there, and validates it against `AuthSlice`. Here's the part that makes it more than a warning light. Suppose a handler wrote `:status :loggedin` — a typo, not in the enum. The runtime emits `:rf.error/schema-validation-failure` with `:where :app-db`, `:path [:auth]`, the offending value, and a Malli `:explain` map — *and it rolls back the `:db` effect entirely.* The pre-handler `app-db` is restored. The dispatch is treated as failed.

That rollback is the load-bearing bit. The handler that produced the bad shape is named in the error. The value that failed is in the error. And the *app itself* is still sitting in the last known-good state — you are not debugging a corrupted `app-db` at 2am, picking through a half-mutated map trying to figure out which of forty handlers got there first. The bad write never took.

The path is `get-in`-shaped, so nested slices compose without any wiring on your part:

```clojure
(rf/reg-app-schema [:auth :login]        FormSlice)   ;; the form's lifecycle shape
(rf/reg-app-schema [:auth :login :draft] LoginForm)   ;; the value the user is typing
```

A write through `[:auth :login :draft]` gets checked against `LoginForm`; the surrounding `[:auth :login]` slice gets checked against `FormSlice`; both fire, and you wired nothing to make them cooperate. For a feature declaring several at once, the plural form takes a `{path -> schema}` map:

```clojure
(rf/reg-app-schemas {[:auth]               AuthSlice
                     [:auth :login]        FormSlice
                     [:auth :login :draft] LoginForm})
```

## Binding a schema to an event

The other everyday surface. Every `reg-event-*` accepts an optional metadata map between the id and the handler, and `:schema` in that map is the schema for the event vector:

```clojure
(rf/reg-event-db :form.login/edit-field
  {:doc    "User changed a single field."
   :schema [:cat [:= :form.login/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login :draft field] value)
        (update-in [:auth :login :touched] conj field))))
```

`[:cat ...]` means "a sequence of exactly these, in order." First element is the event id itself, pinned by `[:= :form.login/edit-field]`; second is the field keyword; third is the string value. Dispatch `[:form.login/edit-field :email "user@host"]` and it passes. Dispatch `[:form.login/edit-field "email" 42]` and it fails fast with `:where :event`, the handler is *not invoked*, and the cascade halts at this event (other events already in the queue keep draining).

`:schema` works across the whole `reg-*` family — `reg-sub` validates the return value, `reg-fx` the fx args, `reg-cofx` the injected value — always the same shape, a Malli schema in the metadata map. The failure-handling rule per surface is worth pinning, because the responses differ sensibly: **event-vector and cofx failures skip the handler; `app-db` failures roll back; fx-args failures skip the one offending fx; sub-return failures default the sub to `nil`.** Each does the least-destructive thing that still refuses to propagate bad data.

## The off-switch: dev vs production

This is the half of the chapter the hook promised, and it's the half that makes schemas a free lunch instead of a tax.

In **dev builds**, every registered schema is checked at its validation point. The cost is real — each event runs one event-vector check, possibly several cofx checks, and one `app-db` check per registered path — but it's tolerable, and it's *exactly when you want to be paying it.* Dev is where bugs are born; dev is where you want the alarm wired live.

In **production builds**, every validation site is **elided at compile time**. Here's the mechanism, because it's worth trusting rather than taking on faith. Every validation call is wrapped in `(when re-frame.interop/debug-enabled? ...)`, which on ClojureScript is `goog.DEBUG` — `true` in dev, and `false` under an `:advanced` production build that sets `{:closure-defines {goog.DEBUG false}}`. The Closure compiler constant-folds that `false`, sees the whole `when` body is now dead, and DCEs it: the validator call, the trace envelope, the explanation string, gone. Not "skipped at runtime" — *removed from the bundle.* Production cost: zero. Not "small." Zero.

The schemas stay **registered** in production — tooling can still introspect the registry — they're just never *checked*. Which gives you exactly the licence you want: write schemas freely, in volume, on everything, without ever once thinking about hot-path cost, because there is no hot-path cost.

There's one deliberate exception, for the case where you *do* want production validation: untrusted data crossing a system boundary — an incoming HTTP response, a websocket message, a `postMessage` payload. For those, re-frame2 ships a `:rf.schema/at-boundary` interceptor:

```clojure
(rf/reg-event-fx :api/response-received
  {:schema ApiResponseSchema}
  [rf/validate-at-boundary-interceptor]
  (fn [{:keys [db]} [_ response]] ...))
```

It forces a check against the handler's `:schema` *regardless* of the global elision flag — every payload from outside gets validated even in production. The other ninety-nine percent of your handlers stay zero-cost. You pay for validation precisely where the data is untrustworthy, and nowhere else.

## See the boundary catch a bug

Enough prose. Here's a counter that remembers its history, with both the `app-db` slice and the events schema-bound — running live in your browser. Click into the cell and hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate, then click the buttons. (First run wakes the engine; after that it's instant.)

(Live cells are functions-only — the view is a plain `defn` with explicit `rf/dispatch` / `rf/subscribe`; `reg-view` is sugar over exactly this. See [chapter 06](06-views.md#defn-views-and-the-reg-view-equivalence).)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; ---- The schema: a piece of data describing the slice's shape ----
;;   :count must be a non-negative int; :history a vector of non-negative ints.
(rf/reg-app-schema [:demo.schema/counter]
  [:map
   [:count   [:int {:min 0}]]
   [:history [:vector [:int {:min 0}]]]])

;; ---- Events, each with a :schema on the event vector itself ----
(rf/reg-event-db :demo.schema/initialise
  {:schema [:cat [:= :demo.schema/initialise]]}
  (fn [db _] (assoc db :demo.schema/counter {:count 3 :history [3]})))

(rf/reg-event-db :demo.schema/inc
  {:schema [:cat [:= :demo.schema/inc]]}
  (fn [db _]
    (let [next (inc (get-in db [:demo.schema/counter :count]))]
      (-> db
          (assoc-in  [:demo.schema/counter :count] next)
          (update-in [:demo.schema/counter :history] conj next)))))

;; This one INTENTIONALLY drives :count below zero — watch the schema stop it.
(rf/reg-event-db :demo.schema/dec
  {:schema [:cat [:= :demo.schema/dec]]}
  (fn [db _]
    (let [next (dec (get-in db [:demo.schema/counter :count]))]
      (-> db
          (assoc-in  [:demo.schema/counter :count] next)
          (update-in [:demo.schema/counter :history] conj next)))))

;; ---- Subscriptions ----
(rf/reg-sub :demo.schema/count
  (fn [db _] (get-in db [:demo.schema/counter :count])))

(rf/reg-sub :demo.schema/history
  (fn [db _] (get-in db [:demo.schema/counter :history])))

;; ---- View ----
(defn schema-counter []
  [:div
   [:button {:on-click #(rf/dispatch [:demo.schema/dec])} "-"]
   [:span {:style {:margin "0 1em" :font-size "1.4em"}}
    @(rf/subscribe [:demo.schema/count])]
   [:button {:on-click #(rf/dispatch [:demo.schema/inc])} "+"]
   [:div {:style {:margin-top "0.75em" :color "#666" :font-size "0.85em"}}
    "history: " (str @(rf/subscribe [:demo.schema/history]))]])

;; ---- Seed, then render ----
(rf/dispatch-sync [:demo.schema/initialise])
[schema-counter]
```

Click `+` and the count climbs and the history grows, exactly as you'd expect — every write fits `[:int {:min 0}]`, so the slice schema is happy and silent. Now click `-` down to `0`, and click it *once more.* The `:demo.schema/dec` handler tries to write `-1` into `:count`, but `[:int {:min 0}]` rejects it: the runtime emits a `:rf.error/schema-validation-failure` (look in your browser console) and **rolls the `:db` effect back.** The count stays `0`. The bad write never lands. You didn't write an `if (count > 0)` guard in the handler — the schema *is* the guard, declared once as data, enforced by the runtime on every single write for free.

> **Try it.** Loosen the floor: change `[:int {:min 0}]` to plain `:int`, re-evaluate, and now click `-` past zero. It sails into the negatives — the schema no longer objects, so the runtime no longer rolls back. That one-keyword edit is the whole feedback loop: the schema is the single source of truth for "what's allowed," and tightening or loosening it instantly changes what the runtime catches. Then try breaking an *event*: change `:demo.schema/inc`'s schema to demand an extra arg — `[:cat [:= :demo.schema/inc] :int]` — re-evaluate, and click `+`. Now the bare `[:demo.schema/inc]` dispatch fails the event-vector check and the handler never runs; the counter freezes. You just watched both halves of the boundary, the `app-db` side and the event side, in about thirty seconds of editing.

## When to reach for a schema, and when not to

Schemas are cheap, but "cheap" isn't "free of judgement." The discriminator is one question: *can this schema catch something a normal test wouldn't?* If yes, write it. If it's just shape-checking the handler obviously does correctly anyway, skip it.

**Reach for a schema** when a slice has more than two or three keys (the typo surface widens with every key); when a field has a *constrained* shape (an enum status, a positive int, a regex'd string, a uuid — anywhere a value can be the right type but the wrong value); when the slice is the contract between two features (one writes, another reads, and the schema is the handshake); when an event's payload is more than "the id and one keyword"; or when the slice is being authored or maintained by an AI agent, since agents read schemas to know what to write.

**Don't reach for a schema** when the slice is a single scalar — `{:counter/show? true}` does not need `[:map [:show? :boolean]]`; a typo there surfaces immediately on its own. And don't write a schema that's just `:any`. A schema's job is to say what shape data *must* have; `:any` is a placeholder that says nothing, and a placeholder schema is worse than none because it implies a constraint that isn't there.

A few conventions, once you're writing them in earnest. Use `[:enum ...]` for fixed value sets, not `:keyword` — "oh, it'll be one of these four" loses all its leverage the moment you write `:keyword` instead. Keep maps open (the default); reach for `{:closed true}` only at system boundaries. And — this is the one people get wrong — keep the schema *next to* the handler and the `reg-app-schema` call that use it, not exiled to some distant `schemas` namespace. The schema is the documentation of the slice, and documentation belongs with the thing it describes, not in a building across town.

## Where this connects

Schemas are deliberately a warmup chapter — you meet the vocabulary here, alone, so it's not being introduced mid-paragraph when you're already busy with something harder. The places it gets used in volume:

- [Chapter 11 — Forms](11-forms.md) is the first heavy user: a `FormSlice` for the lifecycle shape, a draft schema for the value the user's typing, both bound with `reg-app-schema`.
- [Chapter 10 — HTTP](10-http.md) uses schemas as the canonical `:decode` for response bodies — the same shape-description, doing double duty as a parser.
- [Chapter 14 — Errors](14-errors.md) is where `:rf.error/schema-validation-failure` shows up in the trace stream as a first-class, inspectable event rather than a console line you might miss.
- The `:large?` and `:sensitive?` flags you can hang on a schema slot have nothing to do with validation — they control trace-stream elision (redact the auth token, drop the 40MB blob) — and that whole story lives in [chapter 23](23-privacy-and-large-things.md). The schema is just the one place you declare both.
- [Malli's README](https://github.com/metosin/malli) is the full vocabulary — registries, custom schemas, generators — for when the seven shapes run out.

The thing to carry forward isn't the syntax; it's the posture. A schema is a small piece of data you write down once, next to the slice it describes, that turns a class of silent, time-displaced, afternoon-eating bugs into loud, located, immediate ones in dev — and disappears completely in production. The whole architecture is "everything is data"; schemas are what happens when you point that idea back at the data itself and ask it to describe its own shape.
