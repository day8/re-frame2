# Where should this value live?

You have a value — a cart total, an article you fetched, the step a checkout is sitting in — and re-frame2 offers four homes for it: a **subscription**, a **flow**, a **resource**, or a **machine**. Pick wrong and the value fights you: it goes stale, it lies to your handlers, it scatters across booleans nobody keeps in sync. Pick right and the value just *behaves*.

If you come from the React ecosystem you already make this call — just across four libraries: derived value (a selector), store state (Redux), server cache (TanStack Query), process state (XState). re-frame2 keeps the same four territories inside one framework and replaces the folklore with a router: **four questions, asked in order; the first *yes* is your answer.** Every concepts page links back here instead of re-answering it.

## The four questions

Ask them top to bottom. Stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** → It's a **subscription**. ([Subscriptions: the derivation graph](concepts/subscriptions.md))
2. **Must it live *in* `app-db` — read by event handlers, covered by your schema, riding time-travel?** → It's a **flow**. ([Flows: derived values your handlers can read](concepts/flows.md))
3. **Does it come from a server, where it can go stale and needs caching, refetch, and invalidation?** → It's a **resource**. ([Server state: resources](concepts/server-state.md))
4. **Does it have a lifecycle of its own — named states, timers, retries, cancellation?** → It's a **machine**. ([State machines](concepts/machines.md))

The order matters, because it sorts by cost: a subscription costs nothing to declare and stores nothing; a flow pays an `app-db` write; a resource brings a whole cache; a machine brings a whole transition table. **The cheapest home that fits is the right one.** Reach for a heavier home only when the value genuinely needs what it buys.

## One value, four homes: the cart

Follow one value as a feature grows up — a shopping cart, acquiring obligations until it has visited all four homes.

### Question 1 — can you recompute it? Then it's a subscription

The cart total is the sum of the line items' prices, and the items already live in `app-db`. So the total is *derivable* — computable every time from state you already have, with nothing extra stored. That's a **subscription**.

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :price items))))
```

What this buys: the total is **never wrong**, because there is no second copy to drift — it recomputes from the items whenever they change, and for nobody when nothing is looking. You never write an "update the total" handler. A view reads `@(rf/subscribe [:cart/total])` and stays in lockstep for free. This is the default home; most derived values in your app are this.

### Question 2 — must a handler read it? Then promote it to a flow

A requirement lands: when the total crosses $50 the user gets free shipping, and the *checkout event handler* needs to know that while building its order payload. Here's the wall: **handlers can't read subscriptions.** A subscription lives view-side, not in `app-db`, so a handler — which reads state as plain `db` data — can't ask for it. Recomputing the total inside the handler puts the formula in two places, and they drift the first time pricing changes. This is the moment the value wants to be *part of the application's state*, and that's a **flow**: "when these `app-db` paths change, recompute this and write the result to *this* `app-db` path."

```clojure
;; BEFORE — the subscription from question 1. View-side; gone after the render.
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _] (reduce + (map :price items))))

;; AFTER — a flow. Same formula, but the result is WRITTEN into app-db,
;; where handlers read it as plain data and your schema can cover it.
(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :items]]
   :output (fn [items] (reduce + (map :price items)))
   :path   [:cart/total]})
```

The formula is identical; what changed is *where the value lives*. Your checkout handler now reads `(:cart/total db)` like any other state, and because the value is part of the frame's state it rides time-travel and SSR. Dispatch a cart event with Xray open and you'll see the flow's recompute ride the same event row that changed its inputs — the total is part of the event's outcome, not a render-time afterthought.

What it cost: an `app-db` write on every recompute, plus a piece of registered runtime — a trade you make *because a handler needs the value as data*. The rule of thumb from the framework's own design: a typical app has dozens of subscriptions and a *handful* of flows. If no handler reads a flow's output, you've over-paid — go back to a sub. ([Flows](concepts/flows.md) covers the rules a flow's `:path` must obey and why you write the *inputs*, never the output; paths are ordinary [app-db paths](concepts/app-db.md).)

### Question 3 — does it come from a server and go stale? Then it's a resource

The cart so far is *local* — the user built it, it's true by construction. But the checkout page must show the article being bought: title, price, stock. That data isn't yours. It lives on a server; you hold a *cache* of it, stale the instant you read it. A value with those properties — remote origin, an identity naming *which* thing you fetched, staleness, refetch, invalidation — is a **resource**: a sub you read and a cause you fire. Register it once, let a *cause* (a route opening, an event, a machine entering a state) make it fetch, and read it passively from views.

> **Coming from TanStack Query?** A resource is your query — identity-as-params, staleness, tag invalidation — except reads are subscriptions and fetches are caused by routes and events, never by render.

```clojure
;; Register once: identity (params), leak boundary (scope), request.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :get :url (str "/api/articles/" slug)}
               :decode  :json})})

;; A CAUSE fires the fetch — an event handler's :fx here (declaring it
;; on the route is the most common cause of all).
[:dispatch [:rf.resource/ensure {:resource :article/by-slug
                                 :params   {:slug "widget"}
                                 :cause    [:event :checkout/opened]}]]

;; A view READS it passively — it never fetches.
@(rf/subscribe [:rf.resource/state {:resource :article/by-slug :params {:slug "widget"}}])
;; → {:status :loaded :data {:title "Widget" :price 1200} :has-data? true ...}
```

Two ideas make a resource a resource. Its **identity is the params** — `{:slug "widget"}` says *which* article, so two screens asking for the same one share one cache entry and one request. And its **scope is the leak boundary** — it decides *whose* cache an entry lives in and fails closed, so a logged-out user can never read the previous user's data. Staleness and invalidation come built in: ensuring a stale entry refetches in the background while old data stays on screen; a write elsewhere invalidates by tag. ([Server state: resources](concepts/server-state.md) is the full story; the transport underneath is [managed HTTP](concepts/http.md).)

### Question 4 — does it have its own lifecycle? Then it's a machine

The user clicks **Checkout**, and what you're modelling stops being a value and becomes a *process*: idle, then validating, then awaiting payment, then done or failed-and-retrying — with rules about which state may follow which, a timeout, and cancellation. The load-bearing question became "**what state are we in, and what moves us to the next one?**" That's a **machine**. You can tell you've grown into one by the smell that precedes it:

```clojure
;; THE SMELL — three booleans pretending to be one state.
{:checkout/validating? false
 :checkout/awaiting-payment? true
 :checkout/error? false}
```

Three booleans encode eight combinations; checkout has five *legal* states. The rest are nonsense your code must defend against, every handler grows a `cond` re-deriving "which state are we really in", and the transition rules live as lore in your head.

```clojure
;; THE FIX — one named state, transitions as data. Illegal combinations are unrepresentable.
(rf/reg-machine :checkout/flow
  {:initial :idle
   :states
   {:idle             {:on {:checkout/start  {:target :validating}}}
    :validating       {:on {:checkout/valid   {:target :awaiting-payment}
                            :checkout/invalid {:target :idle}}}
    :awaiting-payment {:after {30000 {:target :failed}}
                       :on {:checkout/paid   {:target :complete}
                            :checkout/cancel {:target :idle}}}
    :complete         {}
    :failed           {:on {:checkout/retry {:target :validating}}}}})
```

Now checkout can only be in a state it can legally reach; the timeout belongs to the state that owns it and is cancelled automatically on exit; and "what happens on payment?" has *one* answer, not a `cond` smeared across five handlers. The snapshot lives in the frame's runtime-db partition, where time-travel and Xray see it like any other state. ([State machines](concepts/machines.md) is the full grammar — if you know XState v5 you already know most of it.)

<details markdown="1">
<summary>For the categorically curious</summary>

All four homes are nodes in **one dependency graph rooted at your state**, distinguished only by *storage policy* (where the value is kept) and *evaluation policy* (when it's recomputed): a subscription is *no storage, recompute on demand*; a flow is *stored in app-db, recomputed after each event*; a resource is *stored in a runtime cache, recomputed on cause and staleness*; a machine is *stored as a snapshot, recomputed on transition*. One more axis: storage always names the **local** home — "remote" is never a storage class. A resource's data lives in your cache; the server is its *authority*, a separate fact — which is why this page's question never answers "on a server". These axes are the framework's derivation algebra, specified in [`spec/Derivations.md`](../../spec/Derivations.md).
</details>

## Signs you picked the wrong home

The four questions get you there the first time. This table is for the second time — when something misbehaves because a value lives where it shouldn't.

| The smell | What it really means | Move it to |
|---|---|---|
| A **subscription that does IO** — fetches, writes `localStorage`, reads the clock. | A subscription is a *pure read*. If it reaches into the world, it isn't a derivation. | A **resource** if it's remote data; otherwise the [event boundary](concepts/effects-and-coeffects.md) — an effect for the write, a declared coeffect for the read. |
| A **flow whose output no handler reads.** | An `app-db` write paid to materialize a value only views consume — a subscription in a flow's costume. | A **subscription** — drop the flow, recompute on demand. |
| A **machine wrapping a single fetch** — `:loading`, `:loaded`, nothing else. | No real branching, timers, or cancellation isn't a process; it's a remote read with a status. | A **resource** — its status model already *is* the loading/loaded/error lifecycle. |
| **Remote data hand-rolled into `app-db`** with `:loading?` / `:error?` booleans set in success/failure handlers. | The resource cache — identity, staleness, dedupe, the leak boundary — re-implemented per feature, races included. | A **resource** — register once, let the runtime own the bookkeeping. |

The common thread: each wrong home is a value asked to do a job its home isn't shaped for. Move it, and the defending-against-impossible-states code evaporates.

## The rule, stated once

> **Recompute it from existing state?** → subscription. **Must a handler read it as `app-db` data?** → flow. **Remote, cached, can go stale?** → resource. **A process with states and timers?** → machine.

A value graduates to a heavier home only when it earns the upgrade — a handler that needs it, a server it answers to, a lifecycle of its own. **Pick the right home and the value just behaves.**

---

**You can now…**

- route any new value to a subscription, a flow, a resource, or a machine by asking the four questions in order
- say what each heavier home costs, and what a value must need before it earns the promotion
- recognise a value living in the wrong home and name where it should move

**Next:** [One graph: derivations and their algebra views](derivations-and-algebra-views.md) opens up the unifying idea behind the four homes · [The model: six dominoes, one loop](concepts/index.md) is the loop they all plug into.
