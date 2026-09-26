# Where should this value live?

A cart total, an article you fetched, the step a checkout is in: each value in a re-frame2 app has four possible homes. A [**subscription**](glossary.md#subscription) is a derived value computed on demand; a [**flow**](glossary.md#flow) is a derived value written into your state; a [**resource**](../resources/glossary.md#resource) is a cached copy of server data; a [**machine**](../machines/glossary.md#machine) is a process with named states.

A value in the wrong home goes stale, hides from your handlers, or spreads across booleans that drift out of sync. Four questions, asked in order, pick the home; the first *yes* is the answer. The sections below follow one shopping cart through all four.

## The four questions

Ask them top to bottom. Stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** → It's a **subscription**. ([Subscriptions: the derivation graph](subscriptions.md))
2. **Must it live *in* [`app-db`](glossary.md#app-db) — read by [event handlers](glossary.md#event-handler), covered by your schema, riding time-travel?** → It's a **flow**. ([Flows: derived values your handlers can read](flows.md))
3. **Does it come from a server, where it can go stale and needs caching, refetch, and invalidation?** → It's a **resource**. ([Server state: resources](../resources/concepts.md))
4. **Does it have a lifecycle of its own — named states, timers, retries, cancellation?** → It's a **machine**. ([State machines](../machines/concepts.md))

The questions are ordered by cost. A subscription stores nothing. A flow adds an `app-db` write. A resource adds a cache. A machine adds a transition table. Use a heavier home only when the value needs what it provides; the cheapest home that fits is the right one.

!!! note "Why ask in order?"

    Each home adds to the one before. A flow is a subscription whose result a handler can also read. A resource is roughly a flow plus hand-rolled `:loading?` booleans, with the runtime managing the cache. Asking cheapest-first stops as soon as the value's needs are met.

??? info "For JavaScript developers"

    You already make this choice across four libraries: a selector (reselect) for a derived value, Redux for store state, TanStack Query for the server cache, XState for process state. re-frame2 has all four in one framework, with one trace stream. Each section below names the JS tool it corresponds to.

??? info "From re-frame v1"

    v1 had two homes, subscriptions and `app-db`. Server caches, loading states and multi-step processes were built on top of `app-db` with Reagent atoms, `:loading?` booleans and effect handlers. re-frame2 turns those three patterns into built-in homes: **flows**, **resources** and **machines**. Subscriptions and `app-db` remain the default.

## One value, four homes: the cart

Follow one value as a feature grows: it starts as a pure recompute, then a handler needs it as data, then data comes from a server, then the feature becomes a process.

### Question 1 — can you recompute it? Then it's a subscription

The cart total is the sum of the line items' prices, and the items already live in `app-db`, so the total can be computed from existing state with nothing stored. That is a [subscription](glossary.md#subscription).

```clojure
(rf/reg-sub :cart/total {:inputs [[:cart/items]]}
  (fn [[items] _]
    (reduce + (map :price items))))
```

There is no second copy of the total to drift out of date. It recomputes when the items change, and only while something is subscribed to it, so it costs nothing when no view reads it. There is no "update the total" handler; a view reads `@(rf/subscribe [:cart/total])`. Most derived values belong here.

??? info "Coming from Redux?"

    This is a reselect selector, a memoised derivation over store state, and `{:inputs [[:cart/items]]}` plays the role of the input selectors you pass to `createSelector`. The inputs are data rather than closures, so tools can draw the [derivation graph](glossary.md#the-derivation-graph).

A subscription must stay pure: same inputs, same output, no access to the outside world. One that fetches, writes `localStorage` or reads the clock is in the wrong home; the first row of [the wrong-home table](#signs-you-picked-the-wrong-home) says where that value should go.

### Question 2 — must a handler read it? Then promote it to a flow

A new requirement: orders over $50 ship free, and the checkout [event handler](glossary.md#event-handler) needs the total while building its order payload. **Handlers can't read subscriptions.** A handler receives app-db as a plain `db` value, and a subscription's value is not in it. Recomputing the total inside the handler puts the formula in two places, which drift apart the first time pricing changes.

The value now needs to be part of the application's state. That is a [flow](glossary.md#flow): when these `app-db` paths change, recompute this and write the result to that `app-db` path.

```clojure
;; BEFORE — the subscription from question 1. Handlers can't read it.
(rf/reg-sub :cart/total {:inputs [[:cart/items]]}
  (fn [[items] _] (reduce + (map :price items))))

;; AFTER — a flow. Same formula, but the result is WRITTEN into app-db,
;; where handlers read it as plain data and your schema can cover it.
(rf/reg-flow :cart/total                         ;; the flow's id
  {:inputs      [[:cart :items]]                 ;; app-db paths to watch
   :output-path [:cart/total]}                   ;; where the result is written
  (fn [items] (reduce + (map :price items))))    ;; the pure recompute
```

The formula is the same; the value's home changed. The checkout handler now reads `(:cart/total db)` like any other state, and because the value is part of the [frame](glossary.md#frame)'s state it is included in time-travel and SSR. With [Xray](glossary.md#xray) open, a cart event shows the flow recomputing in the same [pipeline run](glossary.md#run) that changed its inputs, so the total is part of that event's result.

A flow has four parts: the id; `:inputs`, the ordered paths to watch, whose values are passed to the function positionally; the pure function in the third position; and `:output-path`, where the result is written.

The cost is an `app-db` write on every recompute, plus a registered flow. Pay it only when a handler needs the value. A typical app has dozens of subscriptions and a handful of flows; if no handler reads a flow's output, use a subscription instead.

!!! note "You write the inputs, never the output"

    A flow's `:output-path` belongs to the flow. Handlers write the inputs (`[:cart :items]`), and the flow writes the output (`[:cart/total]`) in the same event. A handler that writes the output by hand brings back the two-copies problem the flow removed. ([Flows](flows.md) covers the rules for `:output-path`; the paths are ordinary [app-db paths](app-db.md).)

!!! note "Two more knobs worth knowing"

    **`:inputs` can read [runtime-db](glossary.md#runtime-db).** Beside `app-db`, the framework keeps its own part of frame state, runtime-db, holding things like machine snapshots and the current route (see Question 4). An input path like `[:cart :items]` reads `app-db`; a path starting with `:rf.db/runtime` reads runtime-db, so a flow can derive an `app-db` value from a machine's snapshot or the current route, for example `[:rf.db/runtime :rf.runtime/machines :snapshots :checkout/flow :state]`. The output is always an `app-db` path; an `:output-path` starting with `:rf.db/runtime` raises `:rf.error/flow-reserved-output-path`.

    **Flows are frame-scoped.** `reg-flow` registers against the current frame; add a `:frame` key to the metadata map, or wrap the call in `with-frame`, to target a specific one. A `reg-flow` with no frame in scope raises `:rf.error/no-frame-context`.

!!! note "Flows fail loud, at registration, before they can bite"

    The flow graph is checked when you register. Two flows whose `:inputs` and `:output-path` form a cycle (`:a` reads `:b`'s output and `:b` reads `:a`'s) throw `:rf.error/flow-cycle`, whose `ex-data` carries `:cycle`, the chain in order (for example `[:a :b :a]`). Two flows writing overlapping paths throw `:rf.error/flow-path-overlap`. At run time a flow is atomic with its event: if its function throws, the whole event aborts before any `:db` is committed.

!!! warning "Gotcha — a flow's `:schema` is a check, not a guard"

    A flow may carry an optional `:schema` (a [Malli schema](glossary.md#schema)) for its output, checked on every recompute in dev. Unlike a throw, a violation does not abort the event or undo the write: a downstream flow may already have read the value, so the runtime writes it, commits the event, and emits `:rf.error/schema-validation-failure` (`:where :flow-output`) with the failing path and value. The schema reports a wrong shape; it does not keep that value out of `app-db`. Flow schemas are [elided](glossary.md#elide) from production builds.

!!! note "What flows replace"

    Without flows, the free-shipping case is solved by recomputing the total inside the handler (duplicated formula) or by having every writer also update a stored copy (easy to miss one). A flow is declared once, recomputed by the framework, and updated in the same event as its inputs.

### Question 3 — does it come from a server and go stale? Then it's a resource

The cart so far is local: the user built it. The checkout page also shows the article being bought — title, price, stock — and that data lives on a server. What you hold is a cache of it, which can go stale. A value with a remote origin, an identity naming which item you fetched, staleness, refetching and invalidation is a [resource](../resources/glossary.md#resource).

A resource has two parts: a **read** and a **cause**. The read is an ordinary subscription a view reads; it never touches the network. The fetch is started separately by a cause: a route opening, an event, a machine entering a state. Keeping the fetch out of rendering matters: a view that fetched while rendering would fetch again on every re-render, and two views showing the same article would both fetch it. A cause fires once, for a reason you can see in the trace.

```clojure
;; Register once: identity (params-schema), leak boundary (scope), then the
;; request fn as the THIRD slot — (reg-resource id metadata request-fn).
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; A CAUSE fires the fetch — here an entry in an event handler's :fx vector.
;; (Declaring the resource on a route is the most common cause.)
[:dispatch [:rf.resource/ensure {:resource :article/by-slug
                                 :params   {:slug "widget"}
                                 :cause    [:event :checkout/opened]}]]

;; A view READS it passively — it never fetches.
@(rf/subscribe [:rf/resource {:resource :article/by-slug :params {:slug "widget"}}])
;; → {:status :loaded :data {:title "Widget" :price 1200} :has-data? true ...}
```

A registration needs three things: **`:params-schema`** (the parameters that identify which item you fetch), **`:scope`** (whose cache the entry belongs to; see below), and the request function in the third position, returning a [managed-HTTP](../async/http.md) args map (`:request`, `:decode`, and optionally `:accept` and `:retry`). A `reg-resource` missing one throws at registration: `:rf.error/resource-missing-scope-policy` for a missing scope, `:rf.error/resource-bad-spec` for the others.

A resource's identity is its params: `{:slug "widget"}` names the article, so two screens asking for the same one share one cache entry and one request. Its scope decides whose cache the entry lives in. The cache is part of runtime-db (`:rf.runtime/resources`, beside the machine snapshots from Question 4), so it is reverted, serialised and hydrated along with the rest of the frame's state.

??? info "Coming from TanStack Query?"

    A resource is your query — identity from params, staleness, tag invalidation — except that reads are subscriptions and fetches are started by routes and events, never by rendering. There is no `useQuery` running a side effect during render: the cause is named data you can see in the trace, and the read never touches the network.

!!! note "Scope is what stops a cross-user data leak"

    There is no default scope: every resource declares its policy at registration, so a shared cache is an explicit `:scope :rf.scope/global`. There are two policies. `:rf.scope/global` means the same params give the same data for everyone (a public article). `{:from-db :app/session}` names a resolver that derives the scope from the current viewer's identity (a per-user or per-tenant cache). A scope known only at the call site is either written to a slot the resolver reads, or passed as an explicit `:scope` override at each use. Treat scope as a security boundary: getting it wrong raises an error rather than showing one user another user's data.

You read a resource through subscriptions, never the raw cache. `[:rf/resource …]` returns the whole entry, as shown above; for one fact, the single-value subs are `[:rf.resource/data …]`, `[:rf.resource/status …]`, `[:rf.resource/loading?]`, `[:rf.resource/fetching?]`, `[:rf.resource/stale?]`, `[:rf.resource/error]`, `[:rf.resource/refresh-error]`, and `[:rf.resource/has-data?]`. They keep the `:loading` / `:loaded` / `:error` distinction straight, so a view doesn't have to work out "stale data plus a refresh warning" from raw fields:

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

Ensuring a stale entry refetches it in the background while the old data stays on screen, and a write elsewhere can invalidate entries by tag. ([Server state: resources](../resources/concepts.md) covers resources in full; a route declaring its `:resources`, the most common cause, is covered in [Routing](../routing/concepts.md); the transport is [managed HTTP](../async/http.md).)

!!! note "The read-side scope footgun"

    A view that passes no `:scope` uses the registration's policy, the same scope the route or event used to ensure the entry. If the resolver produces nothing (nobody is logged in), the read raises `:rf.error/resource-sub-unresolved-scope` instead of reading a shared entry. A `:scope` on the query is an override for reading under a different principal; passing one by mistake makes the view read a different, empty entry that stays `:idle`, so pass one only when you mean it.

### Question 4 — does it have its own lifecycle? Then it's a machine

The user clicks **Checkout**, and you are now modelling a process: idle, validating, awaiting payment, then complete, or failed and retrying. There are rules about which state may follow which, a timeout and cancellation. The question is now "what state are we in, and what moves us to the next one?", and that is a [machine](../machines/glossary.md#machine). The usual sign that you need one:

```clojure
;; THE SMELL — three booleans pretending to be one state.
{:checkout/validating?       false
 :checkout/awaiting-payment? true
 :checkout/error?            false}
```

Three booleans encode eight combinations, but checkout has only five legal states, and the code has to guard against the rest. Every handler grows a `cond` working out which state it is really in, and the transition rules exist only in the developers' heads.

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

Checkout can now only be in a state it can reach. The timeout belongs to the state that declares it and is cancelled when the machine leaves that state, so a stale timer can't fire later. "What happens on payment?" has one answer in one place. The machine's current state, its [snapshot](../machines/glossary.md#snapshot), lives in [runtime-db](glossary.md#runtime-db), the framework's part of the frame's state, kept apart from `app-db` so handlers can't overwrite it. It sits at `[:rf.db/runtime :rf.runtime/machines :snapshots :checkout/flow]`, where time-travel and Xray see it; a view reads it with `@(rf/subscribe [:rf/machine :checkout/flow])`.

??? info "Coming from XState?"

    This is a statechart, and `:initial`, `:states`, `:on` and `:after` map almost directly onto XState's `initial`, `states`, `on` and `after`. A machine's working memory is `:data` rather than `context`, with its schema at `[:schemas :data]`. The syntax is EDN rather than JS objects and transitions use the event and effect system you already use, but the model — finite states, declared transitions, entry and exit actions, delayed transitions — is the same. [Coming from XState](../machines/coming-from-xstate.md) maps the rest, and [State machines](../machines/concepts.md) covers the full grammar.

!!! note "An event a state doesn't handle is a no-op, not a crash"

    Dispatch `:checkout/paid` while the machine is `:idle` and nothing happens: the snapshot is unchanged and the runtime emits a `:rf.machine.event/unhandled-no-op` trace, as XState does. You don't need to check the state before dispatching, and the trace shows ignored events if you are looking for one. A transition whose `:guard` returns false is also not taken, and lower-priority transitions for the same event remain eligible. A state with an empty body, like `:complete`, has no outgoing transitions.

??? note "Going deeper"

    All four homes are nodes in one [derivation graph](glossary.md#the-derivation-graph) rooted at your state. They differ in *storage* (where the value is kept) and *evaluation* (when it is recomputed). A subscription stores nothing and recomputes on demand. A flow is stored in `app-db` and recomputed after each event. A resource is stored in the resource cache in runtime-db and recomputed on a cause or when stale. A machine is stored as a snapshot in runtime-db and advanced by transitions. Storage always names the local home: a resource's data lives in your cache, and the server is its *authority*, a separate property. [One graph: derivations and their algebra views](derivations-and-algebra-views.md) explains the model.

## Signs you picked the wrong home

Use this table when something misbehaves because a value lives in the wrong home.

| The smell | What it really means | Move it to |
|---|---|---|
| A **subscription that does IO** — fetches, writes `localStorage`, reads the clock. | A subscription must be a pure read. | A **resource** if it's remote data; otherwise the [event boundary](coeffects.md) — an effect for the write, a declared coeffect for the read. |
| A **flow whose output no handler reads.** | An `app-db` write for a value only views use. | A **subscription** — drop the flow, recompute on demand. |
| A **handler writing a flow's `:output-path` by hand.** | Two writers for one value; the flow and the handler overwrite each other. | Remove the handler's write. Let the handler write an **input**; the flow writes the output. |
| A **machine wrapping a single fetch** — `:loading`, `:loaded`, nothing else. | Without branching, timers or cancellation, this is a remote read with a status, not a process. | A **resource** — its status model already *is* the loading/loaded/error lifecycle. |
| **Remote data hand-rolled into `app-db`** with `:loading?` / `:error?` booleans set in success/failure handlers. | The resource cache — identity, staleness, deduplication, scope — rebuilt per feature, with its race conditions. | A **resource** — register once and let the runtime manage the cache. |
| A **boolean trio** (`:validating?` / `:awaiting?` / `:error?`) that handlers keep decoding. | A finite-state process split into independent flags, most of whose combinations are illegal. | A **machine** — name the states so illegal combinations can't be represented. |
| A **view that causes** — dispatches, fetches, ensures a resource, or navigates *while rendering*. | Rendering should only read state; a cause in a view runs again on every re-render. | The **cause's real home** — a [route's `:resources`](../routing/concepts.md), an event's `:fx`, or the `:on-*` handler the interaction fires. |

!!! note "Views read; they don't cause"

    Rendering may read props, pure values and subscriptions. It must not fetch, ensure a resource, navigate or dispatch because it rendered. A click handler that dispatches is correct, because the cause is the click. A fetch during render runs again on every re-render and races any other view showing the same data. re-frame2 has no API for loading from a view and won't add one; when a view seems to need it, a cause is missing from a route or an event.
