# Where Should This Value Live?

This is the question you'll face every afternoon you work in re-frame2. You've got a value — a cart total, an article you fetched, the step a checkout is sitting in — and the framework hands you four different places it could live: a subscription, a flow, a resource, a machine. Pick wrong and the value fights you: it goes stale, it lies to your handlers, it leaks between users, it scatters across three booleans nobody can keep in sync. Pick right and it just *behaves* — it's fresh when you read it, it's where your code expects it, it survives time-travel and SSR, and the bug you were bracing for never shows up.

The good news is that you don't have to develop a feel for this over six months of mistakes. There are four questions, and you ask them in order, and the first one that says *yes* is your answer. This page is those four questions, threaded through one worked example so you can watch a single value — a shopping cart — find each of the four homes as its obligations grow.

You won't need to know how the four mechanisms work under the hood to use this page. Each question is about what the value *needs*, not about machinery; the chapter links are there for when you want the full story on whichever home you landed in. (There's also one optional box near the end for readers who like the deeper unifying idea. The page makes complete sense if you never open it.)

## The four questions

Ask them top to bottom. Stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** → It's a **subscription**. [(chapter 05)](05-subscriptions.md)
2. **Must it live *in* `app-db` — read by event handlers, covered by your schema, riding SSR and time-travel?** → It's a **flow**. [(chapter 21)](21-dynamic-model.md#flows--derived-state-that-lives-in-app-db)
3. **Does it come from a server, where it can go stale and needs caching, refetch, and invalidation?** → It's a **resource**. [(chapter 27)](27-resources.md)
4. **Does it have a lifecycle of its own — named states, timers, retries, cancellation?** → It's a **machine**. [(chapter 12)](12-machines.md)

The order matters, because the cheapest home that fits is the right one. A subscription costs nothing to declare and is gone after the render; a flow pays an `app-db` write; a resource brings a whole cache; a machine brings a whole transition table. Reach for the heavier homes only when the value genuinely needs what they buy. When two answers seem to fit, the earlier one usually wins — and the worked example below shows exactly the moments where a value *graduates* from one home to the next, and what each graduation costs.

## One value, four homes: the cart

Let's follow a single value as a feature grows up. We'll start with the most modest thing in the app — a cart total — and watch it acquire obligations until it has visited all four homes.

### Question 1 — can you recompute it? Then it's a subscription

The cart total is the sum of the line items' prices. The line items already live in `app-db` (you put them there when the user added them). So the total is *derivable*: given the items, you can compute it, every single time, with no extra stored state. That's the signature of a **subscription** — a named derivation that reads existing state and hands a view the slice it wants.

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :price items))))
```

What this buys you: the total is **never wrong**. There's no second copy of it that can drift from the items it's derived from, because there's no second copy at all — it's recomputed from the items whenever the items change, and it recomputes for nobody when nothing's looking. You never wrote an "update the total" handler, and you never will, because there's nothing to update. A view reads `@(rf/subscribe [:cart/total])` and the framework keeps it in lockstep with the items for free. This is the default, the cheapest home, the one you should *want* the answer to be. Most derived values in your app are this. ([Chapter 05](05-subscriptions.md) is the whole graph these chain into.)

### Question 2 — must a handler read it? Then promote it to a flow

Now a requirement lands: the moment the cart total crosses $50, the user qualifies for free shipping, and the *checkout event handler* needs to know that when it builds its order payload. A handler — not a view. And here's the wall: **handlers can't read subscriptions.** A subscription is a view-side derivation; it isn't *in* `app-db`, so an event handler, which sees only `db`, has no way to ask for it. You could recompute the total inside the handler, but now the formula lives in two places and they'll drift the first time pricing rules change.

This is the exact moment the total wants to *be part of the application's state* rather than just a view-render input — and that's a **flow**: a registered rule that says "when these `app-db` paths change, recompute this and write the result to *this* `app-db` path." Here are the two registrations side by side so you can see precisely what changed:

```clojure
;; BEFORE — a subscription. Lives in the sub-cache; views only; gone after the render.
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :price items))))

;; AFTER — a flow. The same formula, but the result is WRITTEN to app-db at [:cart/total],
;; where event handlers read it as plain data, schema covers it, and it rides SSR + time-travel.
(rf/reg-flow
  {:id     :cart/total
   :inputs [[:cart :items]]
   :output (fn [items] (reduce + (map :price items)))
   :path   [:cart/total]})
```

The formula is identical. What changed is *where the value lives*: the flow writes it into `app-db` at `[:cart/total]`, so your checkout handler reads `(:cart/total db)` like any other piece of state, your registered schema can cover it, and it survives SSR hydration and a time-travel revert because it's part of the frame's state value now. A flow's `:inputs` and `:path` are ordinary [app-db paths](02-app-db.md#paths-are-ordinary-data); the output `:path` is a concrete app-db leaf — never the root `[]`, never with a `nil` segment, and never overlapping a sibling flow's output by prefix (each flow owns its own slice outright). [Chapter 21](21-dynamic-model.md#the-rules-a-flows-path-must-obey) states those rules in full.

What it cost: a flow pays an `app-db` write on every recompute and adds a small piece of registered runtime — so this isn't a free upgrade, it's a deliberate trade you make *because a handler needs the value as data*. If no handler ever needed to read it, you'd leave it a subscription. The rule of thumb, straight from the framework's own design: a typical app has dozens of subscriptions and only a *handful* of flows. If you're reaching for a flow and no handler reads the output, you've over-paid — go back to a sub. ([Chapter 21](21-dynamic-model.md#flows--derived-state-that-lives-in-app-db) covers flows in full, including why you write the *inputs* and never the output.)

### Question 3 — does it come from a server and go stale? Then it's a resource

The cart so far is all *local* state — the user built it, it lives in `app-db`, it's true by construction. But now the checkout page needs to show the article the user is buying: its title, its price, its stock level. That data isn't yours. It lives on a server, you hold a *cache* of it, and that cache is stale the instant you read it. The moment a value has those properties — remote origin, an identity that says *which* thing you fetched, staleness, the need to refetch and invalidate — it's a **resource**, and trying to cram it into `app-db` by hand is the classic mistake the next section catalogues.

A resource is "a sub you read and a cause you fire": you register it once, a *cause* (a route opening, an event, a machine entering a state) makes it fetch, and your view reads it passively through a subscription. The runtime owns everything painful in between — identity, caching, staleness, dedupe, invalidation, and the leak boundary that stops one user seeing another's data.

```clojure
;; Register the article resource once: its identity (params), its leak boundary (scope), its request.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :get :url (str "/api/articles/" slug)}
               :decode  :json})})

;; A CAUSE fires the fetch (here, an event; a route opening is the most common cause).
(rf/dispatch [:rf.resource/ensure {:resource :article/by-slug :params {:slug "widget"}}])

;; A view READS it passively — it never fetches.
@(rf/subscribe [:rf.resource/state {:resource :article/by-slug :params {:slug "widget"}}])
;; → {:status :loaded :data {:title "Widget" :price 1200} :has-data? true ...}
```

The two ideas that make a resource a resource, in two sentences: its **identity** is the params — `{:slug "widget"}` says *which* article, so two screens asking for the same one share a single cache entry and a single request — plus a **scope** that decides *whose* cache it is and fails closed so a logged-out user can never see the previous user's data. And **staleness and invalidation** are first-class: the entry knows when it was loaded, a stale read can trigger a background refetch while the old data stays on screen, and a write elsewhere can invalidate it by tag so it refetches with fresh data. You stop hand-rolling that bookkeeping per feature. ([Chapter 27](27-resources.md) is the full server-state story; the underlying transport is managed HTTP, [chapter 10](10-http.md).)

### Question 4 — does it have its own lifecycle? Then it's a machine

The cart is built, the article is loaded, the user clicks **Checkout**. Now what you're modelling isn't a *value* at all — it's a *process*. Checkout is idle, then validating the cart, then awaiting payment, then confirming, then either done or failed-and-retrying. There are rules about which state can follow which (you can't confirm before payment), there's a timer (cancel if payment doesn't come back in 30s), and there's cancellation (the user backs out). The load-bearing question stopped being "what's the value?" and became "**what state are we in, and what moves us to the next one?**" That's a **machine**.

You can always tell you've grown into a machine by the smell that precedes it. You'll have started with booleans:

```clojure
;; THE SMELL — three booleans pretending to be one state.
{:checkout/validating? false
 :checkout/awaiting-payment? true
 :checkout/error? false}
```

Three booleans encode eight combinations, but checkout has maybe five *legal* states — which means three of those eight are nonsense the code has to keep defending against (`validating?` and `awaiting-payment?` both true at once should be impossible, but nothing stops it). Every handler that touches checkout grows a `cond` that re-derives "which state are we really in" from the boolean soup, and the rules about legal transitions live as unwritten lore in your head. The moment you notice you're maintaining three booleans and a mental rulebook about their combinations, the value wants to be a machine: one named state, an explicit transition table, and per-state timers and actions.

```clojure
;; THE FIX — one named state, transitions as data. The illegal combinations are unrepresentable.
(rf/reg-machine :checkout/flow
  {:initial :idle
   :states
   {:idle             {:on {:checkout/start    {:target :validating}}}
    :validating       {:on {:checkout/valid    {:target :awaiting-payment}
                            :checkout/invalid  {:target :idle}}}
    :awaiting-payment {:after {30000 {:target :failed}}
                       :on {:checkout/paid     {:target :confirming}
                            :checkout/cancel   {:target :idle}}}
    :confirming       {:on {:checkout/done     {:target :complete}
                            :checkout/error    {:target :failed}}}
    :complete         {}
    :failed           {:on {:checkout/retry    {:target :validating}}}}})
```

What it buys: the checkout can now only be in a state it can legally reach, the timer is part of the state that owns it (and is cancelled automatically when you leave that state), and "what happens on payment?" has *one* answer that depends on the current state, not a `cond` smeared across five handlers. The snapshot lives in the framework's runtime-db partition, so undo, time-travel, and SSR extend to your checkout for free. ([Chapter 12](12-machines.md) is machines in full; if you know xstate, you already know 80% of the grammar.)

<details markdown="1">
<summary>For the categorically curious</summary>

All four homes are the same thing seen four ways: a node in **one dependency graph rooted at your state**, distinguished only by its *storage policy* (where the value is kept) and its *evaluation policy* (when it's recomputed). A **subscription** is *no storage, recompute on demand*. A **flow** is *stored in app-db, recompute after each event*. A **resource** is *stored in a runtime cache, recompute on cause and staleness*. A **machine** is *stored as a snapshot, recompute on transition*. Same graph, four policies — which is why "where should this value live?" is really "which storage-and-evaluation policy does this value need?"
</details>

## Signs you picked the wrong home

The four questions get you to the right home the first time. This table is for the second time — when something's misbehaving and the cause is a value living somewhere it shouldn't. Each row is a smell, what it means, and where the value actually wants to be.

| The smell | What it really means | Move it to |
|---|---|---|
| A **subscription that does IO** — fetches, writes `localStorage`, reads the clock. | A subscription is a *pure read* of existing state. If it reaches into the world, it isn't a derivation — it's a fetch (a resource) or an effect (a handler). | A **resource** if it's remote data; an **event handler + effect** if it's a side effect. |
| A **flow whose output no handler ever reads.** | You paid for an `app-db` write to materialize a value only views consume. That's a subscription wearing a flow's costume. | A **subscription** — drop the flow, recompute on demand. |
| A **machine that wraps a single fetch** — two states, `:loading` and `:loaded`, and nothing else. | A lifecycle with no real branching, timers, or cancellation isn't a process; it's a remote read with a status. | A **resource** — its five-status model already *is* the loading/loaded/error lifecycle, for free. |
| **Remote data hand-rolled into `app-db`** with `:loading?` / `:error?` booleans you set in `:on-success` / `:on-failure`. | You're re-implementing the resource cache — identity, staleness, dedupe, the leak boundary — by hand, per feature, and getting the races wrong. | A **resource** — register it once and let the runtime own the bookkeeping. |

The common thread: each wrong home is a value being asked to do a job its home isn't shaped for. The subscription is asked to touch the world; the flow is asked to store something nobody reads from storage; the machine is asked to be a process when it's just a value with a status; `app-db` is asked to be a server-state cache. Move the value to the home shaped for its job and the defending-against-impossible-states code you'd been writing simply evaporates.

## The rule, stated once

When a value appears, walk the four questions in order and stop at the first *yes*:

> **Recompute it from existing state?** → subscription. **Must a handler read it as `app-db` data?** → flow. **Remote, cached, can go stale?** → resource. **A process with states and timers?** → machine.

The cheapest home that fits is the right one, and a value graduates to a heavier home only when it earns the upgrade — a handler that needs to read it, a server it comes from, a lifecycle of its own. Get the home right and the value behaves; get it wrong and you'll find it again in the misuse table above.
