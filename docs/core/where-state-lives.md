# Where should this value live?

You have a value.

A cart total. An article you fetched. The step a checkout is sitting in. And re-frame2 hands it four possible homes: a [**subscription**](glossary.md#subscription) (a derived value computed on demand), a [**flow**](glossary.md#flow) (a derived value written into your state), a [**resource**](../resources/glossary.md#resource) (a cached copy of server data), or a [**machine**](../machines/glossary.md#machine) (a process with named states).

Pick the wrong home and the value fights you. It goes stale. It lies to your handlers. It scatters across booleans nobody keeps in sync. Pick the right home and it just behaves — because each home is shaped for a different job.

This is the page every other concept page links back to instead of answering the question a fifth time. And the whole decision comes down to **four questions, asked in order — the first *yes* is your answer.** We'll grow one value — a shopping cart — until it has lived in all four homes, and you can feel why each one exists.

## The four questions

Ask them top to bottom. Stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** → It's a **subscription**. ([Subscriptions: the derivation graph](subscriptions.md))
2. **Must it live *in* [`app-db`](glossary.md#app-db) — read by [event handlers](glossary.md#event-handler), covered by your schema, riding time-travel?** → It's a **flow**. ([Flows: derived values your handlers can read](flows.md))
3. **Does it come from a server, where it can go stale and needs caching, refetch, and invalidation?** → It's a **resource**. ([Server state: resources](../resources/concepts.md))
4. **Does it have a lifecycle of its own — named states, timers, retries, cancellation?** → It's a **machine**. ([State machines](../machines/concepts.md))

The order sorts by cost, cheapest first. A subscription costs nothing to declare and stores nothing. A flow pays an `app-db` write. A resource brings a whole cache. A machine brings a whole transition table. So you reach for a heavier home only when the value genuinely needs what it buys. **The cheapest home that fits is the right one.**

That rule is the whole page; everything below is it, unpacked four times.

!!! note "Why ask in order?"

    Because the questions are nested, not parallel. Everything a flow can do, a subscription can *almost* do — the flow just adds "…and a handler can read it." Everything a resource is, a flow plus some hand-rolled `:loading?` booleans can *almost* be — the resource just adds "…and the runtime owns the cache." Asking cheapest-first means you stop the instant your value's real obligations are met, instead of grabbing the biggest tool and whittling it down.

??? info "For JavaScript developers"

    You already make this call — you just make it across four separate libraries. A selector (reselect) for a derived value, Redux for store state, TanStack Query for the server cache, XState for process state. re-frame2 keeps those same four territories inside one framework: one mental model instead of four, one trace stream instead of four devtools panels. As each home comes up below, we'll point at the JS tool it stands in for.

??? info "From re-frame v1"

    In v1 you had two homes: subscriptions and `app-db`. That was the whole map — everything else (server caches, loading states, multi-step processes) you hand-rolled *on top of* `app-db` with reagent atoms, ad-hoc `:loading?` booleans, and a fog of effect handlers. re-frame2 promotes the three patterns you kept re-inventing into first-class homes: **flows**, **resources**, and **machines**. Subscriptions and `app-db` are still here and still the cheap default; the new homes exist so you stop paying the same hand-rolled tax on every feature.

## One value, four homes: the cart

The fastest way to feel the four questions is to follow a single value as a feature grows up — because a value moves house the way people do. It starts out owning nothing, recomputed fresh whenever anyone asks. Then it needs a fixed address other code can find it at. Then it's dealing with a landlord on another continent who can change the place while you're out. And eventually it has house rules, a timer on the porch light, and a homeowners' association with opinions about which room may follow which.

Too much? Fine. Watch a shopping cart do it instead.

### Question 1 — can you recompute it? Then it's a subscription

The cart total is the sum of the line items' prices, and the items already live in `app-db`. So you can compute the total every time from state you already have, storing nothing extra — and that is *exactly* what a [subscription](glossary.md#subscription) is.

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map :price items))))
```

The total is never wrong. There's no second copy, so there is nothing to drift. It recomputes from the items whenever they change — and only while something is actually subscribed to it, so when no view is looking it costs nothing. You never write an "update the total" handler. A view reads `@(rf/subscribe [:cart/total])` and stays in lockstep for free. This is the default home, and most derived values in your app land here.

??? info "Coming from Redux?"

    This is a reselect selector — a memoised derivation over store state — and `:<- [:cart/items]` is its input-signal vector, the moral equivalent of the selectors you'd thread into `createSelector`. The difference: in re-frame2 the dependency wiring is explicit *data*, not a closure you assemble by hand, so the framework can draw the [derivation graph](glossary.md#the-derivation-graph) for you.

One rule keeps this home standing: **a subscription must stay pure.** It is a *read* — same inputs, same output, no reaching into the world. The moment it fetches, writes `localStorage`, or reads the clock, it has stopped being a derivation and become a question the wrong home is trying to answer. (That smell — and where the value should go instead — is the first row of [the wrong-home table](#signs-you-picked-the-wrong-home) below.)

### Question 2 — must a handler read it? Then promote it to a flow

Now a new requirement lands. When the total crosses $50 the user gets free shipping, and the *checkout event handler* — the function that runs in response to a dispatched event — needs to know that while building its order payload. Here's the wall you hit, and everyone hits it: **handlers can't read subscriptions.** A subscription lives view-side, not in `app-db`, and a handler reads state as the plain `db` value handed to it — so it has no way to ask for a subscription. You could recompute the total inside the handler, sure. Now the formula lives in two places, and they'll drift the first time pricing changes.

This is the moment the value wants to be *part of the application's state*. That's a [flow](glossary.md#flow): "when these `app-db` paths change, recompute this and write the result to *this* `app-db` path."

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

The formula is identical. What changed is *where the value lives*. Your checkout handler now reads `(:cart/total db)` like any other state, and because the value is part of the [frame](glossary.md#frame)'s state, it rides time-travel and SSR. Dispatch a cart event with [Xray](glossary.md#xray) open and you'll watch the flow's recompute ride the very [pipeline run](glossary.md#run) that changed its inputs — so the total becomes part of the event's *outcome*, not a render-time afterthought.

Those four keys are the whole core of a flow: `:id` names it, `:inputs` is the ordered vector of paths to watch (each value arrives positionally to `:derive`), `:derive` is the pure recompute, and `:output-path` is the `app-db` path written for you.

The rent is real: an `app-db` write on every recompute, plus a piece of registered runtime. You pay it *because a handler needs the value as data*, and not before. Rule of thumb: a typical app has dozens of subscriptions and a *handful* of flows. If no handler reads a flow's output, you're over-paying — go back to a sub.

!!! note "You write the inputs, never the output"

    A flow's `:output-path` belongs to the flow. Your handlers `assoc` into `[:cart :items]` (an input); the flow computes `[:cart/total]` (the output) for you, atomically, on the same event. Writing the output by hand from a handler reintroduces the exact two-copies-drift bug the flow was meant to kill. ([Flows](flows.md) covers the rules a flow's `:output-path` must obey; the paths are ordinary [app-db paths](app-db.md).)

!!! note "Two more knobs worth knowing"

    First, **`:inputs` can read [runtime-db](glossary.md#runtime-db), not just `app-db`.** Alongside your `app-db`, the framework keeps its own slice of frame state — *runtime-db* — where it stashes things like machine snapshots and the current route (you'll meet it again in Question 4). A bare input path like `[:cart :items]` reads `app-db`; a path led by `:rf.db/runtime` reads that runtime partition — so a flow can derive an `app-db` value from a machine's snapshot or the current route, e.g. `[:rf.db/runtime :rf.runtime/machines :snapshots :checkout/flow :state]`. The **write side stays `app-db`-only**, always: a flow's `:output-path` is never a runtime-db path. Second, **flows are frame-scoped.** `reg-flow` registers against the surrounding frame; pass `{:frame …}` as a second argument (or wrap the call in `with-frame`) to target a specific one. A bare `(reg-flow …)` outside any frame scope fails loud with `:rf.error/no-frame-context` rather than guessing a default.

!!! note "Flows fail loud, at registration, before they can bite"

    The flow graph is checked the moment you register, not the first time it runs. Two flows whose `:inputs`/`:output-path` form a cycle (`:a` reads `:b`'s output, `:b` reads `:a`'s) throw `:rf.error/flow-cycle` — the `ex-data` even carries `:cycle`, an ordered vector naming the offending chain (e.g. `[:a :b :a]`). Two flows that would write the same slot throw `:rf.error/flow-path-overlap`. And at *run* time a flow is **atomic with its event**: if a `:derive` throws, the whole event aborts before any `:db` is committed — no half-written `app-db`, no partial total. You never debug a flow that silently produced garbage; it either computes cleanly or fails where you can see it.

!!! warning "Gotcha — a flow's `:schema` is a check, not a guard"

    A flow may carry an optional `:schema` (a [Malli schema](glossary.md#schema)) for its output, validated on every recompute in dev. But unlike a `:derive` *throw*, a `:schema` violation is **observational** — it does **not** abort the event and does **not** unwind the write. By the time the violation is caught, a downstream flow in the same walk may already have read the value as its input, so the runtime writes the value anyway, lets the event commit, and surfaces the mismatch as a diagnostic `:rf.error/schema-validation-failure` error event (`:where :flow-output`) carrying the failing path and value. So a flow `:schema` tells you "your `:derive` produced the wrong shape" — it does not *protect* `app-db` from that value. (Like all schema checks, it's [elided](glossary.md#elide) from production.)

!!! note "What flows replace"

    The free-shipping case above is the kind you might otherwise solve by recomputing the total inside the handler (formula duplicated, drift guaranteed) or by writing a "denormalised" copy into `app-db` from a success handler and praying every writer kept it fresh. A flow is that pattern done right and owned by the framework: declared once, recomputed for you, atomic with the event, and impossible to forget to update.

### Question 3 — does it come from a server and go stale? Then it's a resource

The cart so far is *local*. The user built it, so it's true by construction. But the checkout page must show the article being bought — title, price, stock — and that data isn't yours. It never was. It lives on a server, and what you hold is a *cache* of it, stale the instant you read it. A value like that — remote origin, an identity naming *which* thing you fetched, staleness, refetch, invalidation — is a [resource](../resources/glossary.md#resource).

A resource splits cleanly into two halves: a **read** and a **cause**. The read is an ordinary subscription a view pulls passively — it never reaches out to the network. The fetch happens separately, set off by a **cause**: some named thing in your app that says "go get this now" — a route opening, an event firing, a machine entering a state. So the slogan is *a sub you read and a cause you fire*. Keeping the fetch off the render path is the whole point: a view that fetched while rendering would re-fetch on every re-render, and two views showing the same article would race to fetch it twice. A cause fires once, for a reason you can name and see in the trace.

```clojure
;; Register once: identity (params-schema), leak boundary (scope), then the
;; request fn as the THIRD slot — (reg-resource id metadata request-fn).
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
                                 :cause    [:manual :checkout/opened]}]]

;; A view READS it passively — it never fetches.
@(rf/subscribe [:rf/resource {:resource :article/by-slug :params {:slug "widget"}}])
;; → {:status :loaded :data {:title "Widget" :price 1200} :has-data? true ...}
```

Three keys make a registration valid, and forgetting any one is an error you hit immediately, not a surprise in production: **`:params-schema`** (every variable that names *which* thing you fetched), **`:scope`** (the leak boundary — see below), and the **`:request`** function in the third slot, returning a [managed-HTTP](../async/http.md) args map (`:request`, `:decode`, plus optional `:accept`/`:retry`). A `reg-resource` missing any of them throws at registration time (`:rf.error/resource-missing-scope-policy` for a missing scope, `:rf.error/resource-bad-spec` for the others) — there is no half-registered resource.

Two ideas make a resource a resource. First, its **identity is the params**: `{:slug "widget"}` says *which* article, so two screens asking for the same one share one cache entry and one request. Second, its **scope is the leak boundary**: scope decides *whose* cache an entry lives in. And the cache itself isn't a store of its own — it's a *runtime-db* subsystem (`:rf.runtime/resources`, sitting beside the machine snapshots from Question 4), so it rides the same frame-state revert, serialise, and hydrate machinery as everything else the framework owns.

??? info "Coming from TanStack Query?"

    A resource is your query — identity-as-params, staleness, tag invalidation — except reads are subscriptions and fetches are caused by routes and events, never by render. No `useQuery` smuggling a side effect into a component's render path: the cause is named data you can see in the trace, and the read is a pure subscription that never touches the network.

!!! note "Scope is what stops a cross-user data leak"

    Get the scope wrong and you get a loud error, never a logged-out user quietly reading the previous user's data. There is no silent default scope — every resource declares its policy at registration, and a global cache is a deliberate, auditable claim (`:scope :rf.scope/global`), not a convenience hideaway. The policies you'll reach for: `:rf.scope/global` (the same params yield the same data for everyone — a public article), a resolver `{:from-db :app/session}` that derives the scope from the current viewer's identity (a per-user or per-tenant cache), or `:rf.scope/from-caller` (every `ensure`/`refetch`/`state` call must supply `:scope` itself). Treat scope as a security boundary, not a tuning knob.

Reads are a small, passive family of subscriptions — you never poke at the raw cache. `[:rf/resource …]` is the aggregate projection shown above; when you want one fact, the scalar subs are `[:rf.resource/data …]`, `[:rf.resource/status …]`, `[:rf.resource/loading?]`, `[:rf.resource/fetching?]`, `[:rf.resource/stale?]`, `[:rf.resource/error]`, `[:rf.resource/refresh-error]`, and `[:rf.resource/has-data?]`. They keep the `:loading` / `:loaded` / `:error` distinction honest for you, so a view never has to infer "stale data plus a refresh warning" out of raw fields:

```clojure
;; First-load failure — no data ever arrived.
{:status :error  :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :refresh-error nil  :has-data? false}

;; Background-refresh failure — prior data kept on screen, refresh warning surfaced.
{:status :loaded  :data {:title "Welcome"}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true}
```

Staleness and invalidation come built in too: ensuring a stale entry refetches in the background while old data stays on screen, and a write elsewhere invalidates by tag. ([Server state: resources](../resources/concepts.md) is the full story; the most common cause of all is a route declaring its `:resources`, covered in [Routing](../routing/concepts.md); the transport underneath is [managed HTTP](../async/http.md).)

!!! note "The read-side scope footgun"

    A subscription can't run a `(route, ctx)` resolver — it's pure — so a `:rf.scope/from-caller` resource that a route ensured under one scope, then a view subscribes to *without* passing the same `:scope`, fails closed. If the scope is unresolvable you get a loud `:rf.error/resource-sub-unresolved-scope`; if it resolves to a *different* key the view reads `:idle` forever (a permanent skeleton), and the framework emits a dev warning naming the active scope you probably meant. The fix is always the same: subscribe with the same `:scope` the owning route or event ensured under.

### Question 4 — does it have its own lifecycle? Then it's a machine

The user clicks **Checkout**. Now you're not modelling a value anymore — you're modelling a *process*: idle, then validating, then awaiting payment, then done, or failed-and-retrying. There are rules about which state may follow which, a timeout, and cancellation. The question itself has changed shape: "**what state are we in, and what moves us to the next one?**" That's a [machine](../machines/glossary.md#machine). You can usually spot the smell that precedes one:

```clojure
;; THE SMELL — three booleans pretending to be one state.
{:checkout/validating?       false
 :checkout/awaiting-payment? true
 :checkout/error?            false}
```

Three booleans encode eight combinations, but checkout has only five *legal* states. The other three are nonsense your code has to keep defending against. Every handler grows a `cond` re-deriving "which state are we *really* in," and the transition rules live as lore in your head — which is exactly where bugs go to breed.

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

Now checkout can only ever be in a state it can legally reach. The timeout belongs to the state that owns it and is cancelled automatically on exit, so a stale timer can't fire after you've moved on. "What happens on payment?" has *one* answer, not a `cond` smeared across five handlers. The machine's current state — its [snapshot](../machines/glossary.md#snapshot) — lives in [runtime-db](glossary.md#runtime-db): a framework-owned slice of the frame's state, kept apart from your `app-db` so your handlers can't accidentally clobber it. You'll find this snapshot at `[:rf.db/runtime :rf.runtime/machines :snapshots :checkout/flow]`, where time-travel and Xray see it like any other state — but a view reads it with `@(rf/subscribe [:rf/machine :checkout/flow])`, never by digging into runtime-db by hand.

??? info "Coming from XState?"

    This is a statechart, and if you know XState you already know most of the grammar — `:initial`, `:states`, `:on`, `:after` map almost one-for-one onto `initial`, `states`, `on`, and `after`. The parity reference is **XState v6**: re-frame2 tracks v6's transition-selection order, its internal-by-default self-transitions, and its `schemas` direction for typed context (a machine's data-context schema lives at `[:schemas :data]` — re-frame2's rename of XState's `context`). The spelling is EDN instead of JS objects, and transitions plug into the event/effect system you already use, but the model — finite states, declared transitions, entry/exit, delayed transitions — is the one you carried over from XState. ([State machines](../machines/concepts.md) is the full grammar, including transient `:type :choice` states, public/private `:internal-events`, and the `:timeout` / `:on-timeout` pair.)

!!! note "An event a state doesn't handle is a no-op, not a crash"

    Dispatch `:checkout/paid` while the machine is `:idle` and nothing happens — the snapshot is unchanged and the runtime emits one benign `:rf.machine.event/unhandled-no-op` trace (XState-parity: an unhandled event is a no-op). So you never have to guard every dispatch with "are we even in the right state?" — the machine simply ignores what it has no transition for, and you can still see the ignored event in the trace if you're hunting a missed wire. Two contrasts worth knowing: a transition is also *not selected* if its `:guard` predicate returns false (lower-priority transitions for the same event stay eligible), and a state with `{}` for a body (like `:complete`) is a legal terminal — it simply has no outgoing transitions.

??? note "Going deeper"

    All four homes are nodes in **one [derivation graph](glossary.md#the-derivation-graph) rooted at your state**. They differ on only two policies: *storage* (where the value is kept) and *evaluation* (when it's recomputed). A subscription is *no storage, recompute on demand*. A flow is *stored in `app-db`, recomputed after each event*. A resource is *stored in the resource cache — a runtime-db subsystem at `:rf.runtime/resources`, not a store of its own — recomputed on cause and staleness*. A machine is *stored as a snapshot in that same runtime-db partition, recomputed on transition*. One more axis: storage always names the **local** home. "Remote" is never a storage class. A resource's data lives in *your* cache; the server is its *authority*, a separate fact. That's why this page's questions never answer "on a server." These axes are the framework's derivation algebra — [One graph: derivations and algebra views](derivations-and-algebra-views.md) is the full tour.

## Signs you picked the wrong home

The four questions get you there the first time. This table is for the *second* time — when something misbehaves because a value lives where it shouldn't.

| The smell | What it really means | Move it to |
|---|---|---|
| A **subscription that does IO** — fetches, writes `localStorage`, reads the clock. | A subscription is a *pure read*. If it reaches into the world, it isn't a derivation. | A **resource** if it's remote data; otherwise the [event boundary](coeffects.md) — an effect for the write, a declared coeffect for the read. |
| A **flow whose output no handler reads.** | An `app-db` write paid to materialise a value only views consume — a subscription wearing a flow's costume. | A **subscription** — drop the flow, recompute on demand. |
| A **handler writing a flow's `:output-path` by hand.** | The two-copies-drift bug the flow exists to kill, reintroduced — and now flow and handler fight over the same slot. | Nothing — *remove the hand-write*. Let the handler `assoc` an **input**; the flow owns the output. |
| A **machine wrapping a single fetch** — `:loading`, `:loaded`, nothing else. | No real branching, timers, or cancellation isn't a process; it's a remote read with a status. | A **resource** — its status model already *is* the loading/loaded/error lifecycle. |
| **Remote data hand-rolled into `app-db`** with `:loading?` / `:error?` booleans set in success/failure handlers. | The resource cache — identity, staleness, dedupe, the leak boundary — re-implemented per feature, races included. | A **resource** — register once, let the runtime own the bookkeeping. |
| A **boolean trio** (`:validating?` / `:awaiting?` / `:error?`) handlers keep re-deriving "which one are we really in" from. | A finite-state process flattened into independent flags, most of whose combinations are illegal. | A **machine** — name the states, make the illegal combinations unrepresentable. |

Each wrong home is a value asked to do a job its home isn't shaped for. Move it, and the code defending against impossible states simply evaporates.

## The rule, stated once

!!! note "Recompute it from existing state?"

    → subscription. **Must a handler read it as `app-db` data?** → flow. **Remote, cached, can go stale?** → resource. **A process with states and timers?** → machine.

A value graduates to a heavier home only when it *earns* the upgrade — a handler that needs it, a server it answers to, a lifecycle of its own. Ask the four questions, top to bottom. Stop at the first *yes*. Move in.
