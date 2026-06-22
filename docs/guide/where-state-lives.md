# Where should this value live?

You have a value — a cart total, an article you fetched, the step a checkout is sitting in — and re-frame2 gives it four possible homes: a **subscription** (a derived value computed on demand), a **flow** (a derived value written into your state), a **resource** (a cached copy of server data), or a **machine** (a process with named states). Pick the wrong one and the value fights you. It goes stale, it lies to your handlers, it scatters across booleans nobody keeps in sync. Pick the right one and it just behaves, because each home is shaped for a different job.

You already make this call if you come from React, just across four libraries: a selector for a derived value, Redux for store state, TanStack Query for the server cache, XState for process state. re-frame2 keeps the same four territories inside one framework — one mental model instead of four. Instead of folklore, you get a router: **four questions, asked in order. The first *yes* is your answer.** Every concepts page links back here instead of answering it again.

## The four questions

Ask them top to bottom, and stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** → It's a **subscription**. ([Subscriptions: the derivation graph](concepts/subscriptions.md))
2. **Must it live *in* `app-db` — read by event handlers, covered by your schema, riding time-travel?** → It's a **flow**. ([Flows: derived values your handlers can read](concepts/flows.md))
3. **Does it come from a server, where it can go stale and needs caching, refetch, and invalidation?** → It's a **resource**. ([Server state: resources](concepts/server-state.md))
4. **Does it have a lifecycle of its own — named states, timers, retries, cancellation?** → It's a **machine**. ([State machines](concepts/machines.md))

The order sorts by cost, cheapest first. A subscription costs nothing to declare and stores nothing. A flow pays an `app-db` (your app's single state map) write. A resource brings a whole cache. A machine brings a whole transition table. So you reach for a heavier home only when the value genuinely needs what it buys. **The cheapest home that fits is the right one.**

> **Why ask in order?** Because the questions are nested, not parallel. Everything a flow can do, a subscription can *almost* do — the flow just adds "and a handler can read it." Everything a resource is, a flow plus some hand-rolled `:loading?` booleans can *almost* be — the resource just adds "and the runtime owns the cache." Asking cheapest-first means you stop the moment your value's actual obligations are met, instead of reaching for the biggest tool and trimming down later.

## One value, four homes: the cart

The easiest way to feel the four questions is to follow one value as a feature grows up. We'll take a shopping cart and let it pick up obligations until it has visited all four homes.

### Question 1 — can you recompute it? Then it's a subscription

The cart total is the sum of the line items' prices, and the items already live in `app-db`. So you can compute the total every time from state you already have, storing nothing extra — and that's exactly what a **subscription** is.

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :price items))))
```

The total is never wrong, because there's no second copy to drift out of sync. It recomputes from the items whenever they change, and for nobody when nothing is looking, so you never write an "update the total" handler. A view reads `@(rf/subscribe [:cart/total])` and stays in lockstep for free. This is the default home, and most derived values in your app will land here.

> **Coming from Redux?** This is a reselect selector — a memoized derivation over store state — and the `:<- [:cart/items]` is its input-signal vector, the moral equivalent of the selectors you'd pass to `createSelector`. The difference: in re-frame2 the dependency wiring is explicit data, not a closure you assemble by hand, so the framework can draw the graph for you.

### Question 2 — must a handler read it? Then promote it to a flow

Now a new requirement lands. When the total crosses $50 the user gets free shipping, and the *checkout event handler* (the function that runs in response to a dispatched event) needs to know that while building its order payload. Here's the wall you hit: **handlers can't read subscriptions.** A subscription lives view-side, not in `app-db`, and a handler reads state as plain `db` data — so it has no way to ask for a subscription. Recomputing the total inside the handler would put the formula in two places, and they'll drift the first time pricing changes.

This is the moment the value wants to be *part of the application's state*. That's a **flow**: "when these `app-db` paths change, recompute this and write the result to *this* `app-db` path."

```clojure
;; BEFORE — the subscription from question 1. View-side; gone after the render.
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _] (reduce + (map :price items))))

;; AFTER — a flow. Same formula, but the result is WRITTEN into app-db,
;; where handlers read it as plain data and your schema can cover it.
(rf/reg-flow
  {:id          :cart/total
   :inputs      [[:cart :items]]                 ;; app-db paths to watch
   :derive      (fn [items] (reduce + (map :price items)))
   :output-path [:cart/total]})                  ;; where the result is written
```

The formula is identical. What changed is *where the value lives*. Your checkout handler now reads `(:cart/total db)` like any other state, and because the value is part of the frame's state, it rides time-travel and SSR. Dispatch a cart event with Xray open and you'll see the flow's recompute ride the same event row that changed its inputs — so the total becomes part of the event's outcome, not a render-time afterthought.

The cost is an `app-db` write on every recompute, plus a piece of registered runtime. You pay it *because a handler needs the value as data*, and not before. As a rule of thumb, a typical app has dozens of subscriptions and a *handful* of flows. If no handler reads a flow's output, you've over-paid — go back to a sub.

> **You write the inputs, never the output.** A flow's `:output-path` belongs to the flow. Your handlers `assoc` into `[:cart :items]` (an input); the flow computes `[:cart/total]` (the output) for you, atomically, on the same event. Writing the output by hand from a handler reintroduces exactly the two-copies-drift problem the flow was meant to kill. ([Flows](concepts/flows.md) covers the rules a flow's `:output-path` must obey; the paths are ordinary [app-db paths](concepts/app-db.md).)

### Question 3 — does it come from a server and go stale? Then it's a resource

The cart so far is *local*. The user built it, so it's true by construction. But the checkout page must show the article being bought — title, price, stock — and that data isn't yours. It lives on a server, and you hold a *cache* of it that's stale the instant you read it. A value like that — remote origin, an identity naming *which* thing you fetched, staleness, refetch, invalidation — is a **resource**: a sub you read and a cause you fire. You register it once, let a *cause* (a route opening, an event, a machine entering a state) make it fetch, and read it passively from views. Fetching is caused rather than triggered by render so the same fetch can't fire twice from two components racing each other.

> **Coming from TanStack Query?** A resource is your query — identity-as-params, staleness, tag invalidation — except reads are subscriptions and fetches are caused by routes and events, never by render. No `useQuery` smuggling a side effect into a component's render path: the cause is named data you can see in the trace, and the read is a pure subscription that never reaches out to the network.

```clojure
;; Register once: identity (params), leak boundary (scope), request.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; A CAUSE fires the fetch — here from an event handler's :fx vector.
;; (Declaring the resource on the route is the most common cause of all.)
[:dispatch [:rf.resource/ensure {:resource :article/by-slug
                                 :params   {:slug "widget"}
                                 :cause    [:event :checkout/opened]}]]

;; A view READS it passively — it never fetches.
@(rf/subscribe [:rf.resource/state {:resource :article/by-slug :params {:slug "widget"}}])
;; → {:status :loaded :data {:title "Widget" :price 1200} :has-data? true ...}
```

Two ideas make a resource a resource. First, its **identity is the params** — `{:slug "widget"}` says *which* article, so two screens asking for the same one share one cache entry and one request. Second, its **scope is the leak boundary**: scope decides *whose* cache an entry lives in.

> **Scope is what stops a cross-user data leak.** Get the scope wrong and you get a loud error, never a logged-out user quietly reading the previous user's data. There is no silent default scope — every resource declares its policy at registration, and a global cache is a deliberate, auditable claim (`:scope :rf.scope/global`), not a convenience hideaway. Treat the scope as a security boundary, not a tuning knob.

Staleness and invalidation come built in, too: ensuring a stale entry refetches in the background while old data stays on screen, and a write elsewhere invalidates by tag. ([Server state: resources](concepts/server-state.md) is the full story; the transport underneath is [managed HTTP](concepts/http.md).)

### Question 4 — does it have its own lifecycle? Then it's a machine

The user clicks **Checkout**. Now you're not modelling a value anymore — you're modelling a *process*: idle, then validating, then awaiting payment, then done or failed-and-retrying. There are rules about which state may follow which, a timeout, and cancellation. The load-bearing question has become "**what state are we in, and what moves us to the next one?**" That's a **machine**. You can usually spot the smell that precedes one:

```clojure
;; THE SMELL — three booleans pretending to be one state.
{:checkout/validating?      false
 :checkout/awaiting-payment? true
 :checkout/error?            false}
```

Three booleans encode eight combinations, but checkout has only five *legal* states. The other three are nonsense your code has to keep defending against. Every handler grows a `cond` re-deriving "which state are we really in", and the transition rules live as lore in your head — which is exactly where bugs hide.

```clojure
;; THE FIX — one named state, transitions as data. Illegal combinations are unrepresentable.
(rf/reg-machine :checkout/flow
  {:initial :idle
   :states
   {:idle             {:on {:checkout/start   {:target :validating}}}
    :validating       {:on {:checkout/valid   {:target :awaiting-payment}
                            :checkout/invalid {:target :idle}}}
    :awaiting-payment {:after {30000 {:target :failed}}
                       :on {:checkout/paid    {:target :complete}
                            :checkout/cancel  {:target :idle}}}
    :complete         {}
    :failed           {:on {:checkout/retry   {:target :validating}}}}})
```

Now checkout can only be in a state it can legally reach. The timeout belongs to the state that owns it and is cancelled automatically on exit, so a stale timer can't fire after you've moved on. "What happens on payment?" has *one* answer, not a `cond` smeared across five handlers. The snapshot lives in the frame's runtime-db partition, where time-travel and Xray see it like any other state.

> **Coming from XState?** This is a statechart, and if you know XState v5 you already know most of the grammar — `:initial`, `:states`, `:on`, `:after` map almost one-for-one onto `initial`, `states`, `on`, and `after`. The re-frame2 spelling is EDN instead of JS objects, and transitions integrate with the event/effect system you already use, but the model — finite states, declared transitions, entry/exit, delayed transitions — is the one you carried over from XState. ([State machines](concepts/machines.md) is the full grammar.)

> **For the categorically curious.** All four homes are nodes in **one dependency graph rooted at your state**. They differ on only two policies: *storage* (where the value is kept) and *evaluation* (when it's recomputed). A subscription is *no storage, recompute on demand*. A flow is *stored in app-db, recomputed after each event*. A resource is *stored in a runtime cache, recomputed on cause and staleness*. A machine is *stored as a snapshot, recomputed on transition*. One more axis: storage always names the **local** home. "Remote" is never a storage class. A resource's data lives in your cache; the server is its *authority*, a separate fact. That's why this page's question never answers "on a server". These axes are the framework's derivation algebra, specified in [`spec/Derivations.md`](../../spec/Derivations.md).

## Signs you picked the wrong home

The four questions get you there the first time. This table is for the second time — when something misbehaves because a value lives where it shouldn't.

| The smell | What it really means | Move it to |
|---|---|---|
| A **subscription that does IO** — fetches, writes `localStorage`, reads the clock. | A subscription is a *pure read*. If it reaches into the world, it isn't a derivation. | A **resource** if it's remote data; otherwise the [event boundary](concepts/effects-and-coeffects.md) — an effect for the write, a declared coeffect for the read. |
| A **flow whose output no handler reads.** | An `app-db` write paid to materialize a value only views consume — a subscription in a flow's costume. | A **subscription** — drop the flow, recompute on demand. |
| A **machine wrapping a single fetch** — `:loading`, `:loaded`, nothing else. | No real branching, timers, or cancellation isn't a process; it's a remote read with a status. | A **resource** — its status model already *is* the loading/loaded/error lifecycle. |
| **Remote data hand-rolled into `app-db`** with `:loading?` / `:error?` booleans set in success/failure handlers. | The resource cache — identity, staleness, dedupe, the leak boundary — re-implemented per feature, races included. | A **resource** — register once, let the runtime own the bookkeeping. |

Each wrong home is a value asked to do a job its home isn't shaped for. Move it, and the code defending against impossible states evaporates.

## The rule, stated once

> **Recompute it from existing state?** → subscription. **Must a handler read it as `app-db` data?** → flow. **Remote, cached, can go stale?** → resource. **A process with states and timers?** → machine.

A value graduates to a heavier home only when it earns the upgrade — a handler that needs it, a server it answers to, a lifecycle of its own. Pick the right home and the value just behaves.

---

**You can now:**

- route any new value to a subscription, a flow, a resource, or a machine by asking the four questions in order
- say what each heavier home costs, and what a value must need before it earns the promotion
- recognise a value living in the wrong home and name where it should move
