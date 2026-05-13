# Pattern — SSR Loaders (parallel data fetch during drain)

> **Type:** Pattern
> The standard fan-out-then-render shape for server-side rendering that needs N parallel HTTP fetches before HTML emission. Built on `:invoke-all` ([005-StateMachines.md §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all)) and `:rf.http/managed` ([014-HTTPRequests.md](014-HTTPRequests.md)) — primitives the spec already locks. Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **convention**, not a Spec. The runtime gives you everything: `:on-create` event firing, run-to-completion drain, `:invoke-all` spawn-and-join, `:rf.http/managed` HTTP fx, the `[:rf/response]` accumulator. What this doc names is **the canonical way to compose them when an SSR render needs to load N pieces of data in parallel before producing HTML.**

The pattern exists because the obvious shape — "dispatch three loader events in series from `:on-create`" — serialises the wall-clock cost of every HTTP fetch. The drain runs to fixed point but it runs in a single thread; back-to-back blocking transport calls (JVM `java.net.http.HttpClient` on the server side of `:rf.http/managed`) add up. The fan-out idiom moves the fetches off the drain thread (each into its own spawned actor) and joins on a join-all-complete condition before the drain settles. Total wall-clock cost falls to `max(fetch-i) + overhead`, not `sum(fetch-i)`.

This is the SSR-side answer to "how do I write the Next.js `Promise.all([getArticle, getComments, getRelated])` shape in re-frame2." The runtime primitives are the same ones client-side loaders already use; the wiring is what this pattern catalogues.

## The shape

A boot-like state machine spawned at `:on-create` time. The machine's first state spawns N HTTP-fetching children via `:invoke-all`; the join-all-complete transition advances to a terminal `:ready` state. The drain settles at terminal; the SSR adapter calls `render-to-string` against the post-drain `app-db`.

The five-step shape:

1. **`:on-create` dispatches `[:page/load]`** — the request-scoped per-page loader event.
2. **`[:page/load]` spawns the loader state machine** — typically via `:invoke` from a singleton boot machine, or via direct `reg-frame` `:on-create` for a one-shot SSR-only loader.
3. **The loader's `:loading` state declares `:invoke-all`** — N children, each a thin machine wrapping `:rf.http/managed` for one fetch. Children dispatch `[<parent> [:loaded :child-id <result>]]` on success or `[<parent> [:failed :child-id <reason>]]` on failure.
4. **The runtime joins** — when every child has reported `:done`, the runtime fires `:on-all-complete` into the parent. The parent transitions to `:ready` and writes the fetched data into `app-db` slices.
5. **The drain settles, `render-to-string` runs** — the registered views read the slices via `subscribe` and emit HTML. Hydration payload carries the same slices to the client.

The shape is the same whether the page fetches two pieces of data or twenty. The same machine spec drives navigation-fetch on the client; only the platform changes.

## Worked example — `/products/:id` page

A product-detail page needs three independent fetches before render: the product itself, the related items, and the most-recent reviews. None depends on another's result; all three can run in parallel.

### The loader machine

```clojure
(rf/reg-machine :pdp/load
  {:doc      "Parallel loader for /products/:id. Fans out three HTTP fetches;
              joins on all-complete; writes results into app-db."
   :initial  :loading
   :data     (fn [_ [_ {:keys [product-id]}]]
               {:product-id product-id
                :product    nil
                :related    nil
                :reviews    nil})
   :states
   {:loading
    {:invoke-all
     {:children
      [{:id         :product
        :machine-id :http/get-one
        :data       (fn [snap _]
                      {:url (str "/api/products/" (-> snap :data :product-id))
                       :decode ProductSchema})}
       {:id         :related
        :machine-id :http/get-one
        :data       (fn [snap _]
                      {:url (str "/api/products/" (-> snap :data :product-id) "/related")
                       :decode RelatedListSchema})}
       {:id         :reviews
        :machine-id :http/get-one
        :data       (fn [snap _]
                      {:url    (str "/api/products/" (-> snap :data :product-id) "/reviews")
                       :params {:limit 10}
                       :decode ReviewListSchema})}]
      :join             :all
      :on-child-done    :loaded
      :on-child-error   :failed
      :on-all-complete  [:pdp/joined]
      :on-any-failed    [:pdp/load-failed]}
     :after {30000 :pdp/timed-out}    ;; phase-level wall-clock guard
     :on    {:pdp/joined       :ready
             :pdp/load-failed  :error
             :pdp/timed-out    :error}}

    :ready {:final? true
            :entry  (fn [data event]
                      (let [[_ _ {:keys [product related reviews]}] event]
                        {:db-fx [[:assoc-in [:pdp :product] product]
                                 [:assoc-in [:pdp :related] related]
                                 [:assoc-in [:pdp :reviews] reviews]]}))}

    :error {:final? true
            :entry  (fn [_ [_ _ reason]]
                      {:fx [[:rf.server/set-status 502]
                            [:assoc-in [:pdp :error] reason]]})}}})
```

### The per-fetch child machine

A thin wrapper around `:rf.http/managed` — one state spawns the request; success/failure transitions are terminal so the child reports back to its parent. The child is shared across all three siblings; only the spawn-spec's `:data` fn differs per call.

```clojure
(rf/reg-machine :http/get-one
  {:doc     "Single HTTP GET that reports :done / :failed to its parent on terminal."
   :initial :requesting
   :states
   {:requesting
    {:invoke {:machine-id :rf.http/managed
              :data       (fn [snap _] (assoc (:data snap) :method :get))}
     :on     {:succeeded  :done
              :failed     :failed-state}}

    :done   {:final? true
             :entry (fn [data [_ _ result]]
                      {:fx [[:dispatch [(:rf/parent-id (-> data :env))
                                        [:loaded (:invoke-id data) result]]]]})}
    :failed-state {:final? true
                   :entry (fn [data [_ _ reason]]
                            {:fx [[:dispatch [(:rf/parent-id (-> data :env))
                                              [:failed (:invoke-id data) reason]]]]})}}})
```

(`:rf/parent-id` is stamped at spawn time per [005 §Spawn-id tracking](005-StateMachines.md#spawn-id-tracking); the child reads it from its `:data :env` slot and uses it for the dispatch-back target.)

### Wiring into the SSR request

The per-request frame is created by the host adapter; `:on-create` reads the URL (via `:rf.server/request` cofx — see [§Composition with `:rf.server/request`](#composition-with-rfserverrequest-cofx) below) and spawns the loader:

```clojure
(rf/reg-event-fx :rf/server-init
  {:doc       "Per-request boot for SSR. Reads the request URL, spawns the page loader."
   :platforms #{:server}}
  [(rf/inject-cofx :rf.server/request)]
  (fn handler-server-init [{:keys [rf.server/request]} _]
    (let [{:keys [product-id]} (route/match (:url request))]
      {:fx [[:rf.machine/spawn {:machine-id :pdp/load
                                :id-prefix  :pdp/load
                                :data       {:product-id product-id}}]]})))
```

When `:rf/server-init` returns, the drain begins processing the spawn — the loader machine enters `:loading`, the three children spawn, each issues its `:rf.http/managed` request. The drain keeps running until the join resolves: the runtime dispatches `[:pdp/joined ...]` into the parent, the parent transitions to `:ready`, `:ready`'s `:entry` writes the three results into `[:pdp :product]` / `:related` / `:reviews`. With no more events to process, the drain reaches fixed point. The SSR adapter calls `render-to-string`; views subscribe to the slices; HTML reflects all three fetches.

### What the deadline does

`:after {30000 :pdp/timed-out}` is the phase-level wall-clock guard (per [005 §Composition with hierarchy and `:after`](005-StateMachines.md#composition-with-hierarchy-and-after)). If one fetch hangs past 30 s, the `:after` fires; the `:on :pdp/timed-out :error` transition exits `:loading`; the exit cascade tears down every surviving child (their `:rf.http/managed` invocations abort via the standard destroy fx per [014 §Aborts](014-HTTPRequests.md#aborts)). The `:error` state stamps a 502 status and the request returns a degraded response without a partial render of three half-loaded slices.

The deadline is the SSR-specific knob — clients can tolerate "spinner appears for 60 seconds then resolves," but a server has a single render moment and a request-timeout budget. Pick a deadline shorter than the host adapter's request timeout; the host has nothing to render if the deadline fires past it.

## Composition with `:rf.server/request` cofx

The fan-out's children typically need values from the active request — the URL parameters parsed in the route handler, an auth token from a session cookie, a locale from `Accept-Language`. These come from the `:rf.server/request` cofx (per [011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context)).

Read the cofx **once**, at `:rf/server-init` — bind the values into the loader machine's `:data` via the spawn-spec, then thread them into each child's `:data` fn (mechanism 2 in [Pattern-AsyncEffect §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary)). Children read from the parent's snapshot at spawn time; nothing in the child reaches for the request cofx again. This keeps the child machines pure of HTTP-request-context concerns and reusable on the client side (where there is no request cofx) without modification.

```clojure
(rf/reg-event-fx :rf/server-init
  {:platforms #{:server}}
  [(rf/inject-cofx :rf.server/request)]
  (fn [{:keys [rf.server/request]} _]
    (let [{:keys [product-id]} (route/match (:url request))
          auth-token            (-> request :session :token)
          locale                (-> request :headers (get "accept-language") parse-locale)]
      {:fx [[:rf.machine/spawn
             {:machine-id :pdp/load
              :data       {:product-id product-id
                           :auth-token auth-token
                           :locale     locale}}]]})))
```

The loader's `:children` `:data` fns then close over the parent's `:data`:

```clojure
{:id         :product
 :machine-id :http/get-one
 :data       (fn [snap _]
               {:url    (str "/api/products/" (-> snap :data :product-id))
                :headers {"authorization" (str "Bearer " (-> snap :data :auth-token))
                          "accept-language" (-> snap :data :locale)}
                :decode ProductSchema})}
```

## Composition with Managed HTTP (Spec 014)

Each spawned child uses `:rf.http/managed` per [014-HTTPRequests.md](014-HTTPRequests.md) — the full surface is available: retry-with-backoff per `:retry`, abort via `:request-id`, schema-driven decode via `:decode`, and the canonical eight-category failure taxonomy. The fact that the parent runs on the server is transparent to the fx: the JVM transport (`java.net.http.HttpClient`) handles the wire call and dispatches the reply event the same way the browser's Fetch transport does on the client.

Two interaction points matter for the loader:

- **Retry partitioning** (per [014 §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry) and [005 §Retry-ownership boundary with `:rf.http/managed`](005-StateMachines.md#retry-ownership-boundary-with-rfhttpmanaged)) — transport retry lives in the child's `:rf.http/managed` invocation (3 attempts on `:rf.http/transport` failures, fast). Semantic retry (e.g. "if the product is missing, redirect to /404") lives in the parent's transition table on `:on-any-failed`. The phase-level deadline (`:after`) is the outer envelope; transport retries happen inside it.
- **Abort cascade** (per [005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true) and [014 §Aborts](014-HTTPRequests.md#aborts)) — when `:on-any-failed` fires (or the `:after` deadline lapses), the runtime's destroy cascade tears down each surviving child; each child's `:rf.http/managed` invocation receives a `:rf.machine/destroy` which abort the in-flight HTTP request via the host transport's abort surface. No partial-fetch results land in `app-db`; the `:error` state's `:entry` stamps the response cleanly.

## Server vs client semantics

The same loader machine works on both platforms; only the deadline policy and the rendering moment differ.

| Concern | Server (SSR) | Client (navigation) |
|---|---|---|
| Spawn site | `:on-create` of the per-request frame | route's `:on-match` |
| Drain settles before | `render-to-string` | the next React tick |
| Deadline | mandatory (request budget) | optional (spinner tolerated) |
| Partial render | impossible (single render moment) | possible (subscribe to in-flight state, render skeleton) |
| Abort source | server timeout, frame destroy | route change, user navigation away |

On the client, the same machine spec drives navigation-fetch: when the user clicks a link to `/products/123`, the route's `:on-match` spawns `:pdp/load` with the new product-id; the spawned machine fans out three fetches; the view subscribes to the `[:pdp]` slice and shows a skeleton until `:ready` lands. The client can show progressive UI (each `:loaded` event from a child updates a slice; the view re-renders as each lands) by giving the parent an `:on :loaded` handler that writes results incrementally — but the SSR variant cannot, because the server has no progressive render channel.

The mental-model claim from [011-SSR.md](011-SSR.md) — "SSR is the same shape as client" — holds at this layer: same `:invoke-all` primitive, same handler tree, same trace events. The only thing that changes between platforms is *when* the render runs relative to the join. The author writes one machine; the platform decides whether to render mid-fetch or only after the join.

## What this pattern doesn't say

- **How nested-route loaders compose.** Remix's nested-route parallel-loader pattern (parent loader runs in parallel with child loader; child gets parent's data via a context-style read) is not specced in re-frame2 today. The pattern here covers flat per-page fan-out; route nesting is a separate concern tracked elsewhere.
- **How to stream partial HTML.** The server has a single render moment; this pattern fans out fetches that complete before render. Streaming SSR (render a shell, stream subtrees as they resolve) is open per [011 §Streaming SSR](011-SSR.md#streaming-ssr) and would compose with this pattern at the parent level — each `:loaded` event flushes a chunk — but the chunk-flushing primitive is not in v1.
- **What HTTP library to use.** The child uses `:rf.http/managed` if the host ships Spec 014 (the CLJS reference does); other hosts may register their own per Pattern-AsyncEffect.
- **Caching / revalidation policy.** [Pattern-RemoteData §`:loaded-at` and `:stale-after-ms`](Pattern-RemoteData.md#loaded-at-and-stale-after-ms--declarative-freshness) covers freshness; this pattern is silent on whether the loader re-fans-out on every request or reads from a cache slice. App concern.

## Anti-patterns

- **Dispatching three loaders in series from `:on-create`.** Three back-to-back `{:fx [[:dispatch [:load-product]] [:dispatch [:load-related]] [:dispatch [:load-reviews]]]}` events serialise the wall-clock cost; even though the drain processes them in order, each `:rf.http/managed` blocks the drain thread on the server-side JVM transport. Use `:invoke-all` to spawn N children that own their own transport calls.
- **Hand-rolling the join.** A counter in `app-db` (`(when (= 3 @counter) (dispatch [:render-ready]))`) reinvents `:invoke-all`'s join-state without the destroy cascade, the deadline composition, or the trace events. Use the primitive.
- **Reading `:rf.server/request` from child machines.** The cofx is server-only; reading it from a child means the child is server-only too, breaking the "same machine for client navigation" property. Thread request-derived values from the parent's `:data` via the spawn-spec `:data` fn.
- **Omitting the deadline.** A loader without an `:after` on the `:invoke-all` state can hang the request until the host adapter's outer timeout fires — at which point the error path is host-specific and unobservable to the trace stream. The phase-level `:after` makes the timeout deterministic and traceable.
- **Writing results into `app-db` from the child.** The child should dispatch back to the parent; the parent's `:ready :entry` writes. This keeps the join atomic — partial results never land if the join short-circuits on failure or deadline.

## Conformance checklist

A parallel-loader implementation conforms to this convention when:

- The loader is a state machine, not a tree of `:dispatch`-chained events.
- The fan-out uses `:invoke-all` (not a counter, not parallel `:dispatch`es with no join).
- Each child is its own machine (one fetch per child); children are reusable across loader instances via spawn-spec `:data`.
- A phase-level deadline is declared via `:after` on the `:invoke-all`-bearing state.
- Failure is handled via `:on-any-failed`; the `:error` state stamps a non-200 status via `:rf.server/set-status`.
- Request-scoped values come from `:rf.server/request` cofx at the loader's spawn site (typically `:rf/server-init`), not from inside the child machines.
- The same loader machine is used on the client for navigation-fetch (only the spawn site changes — `:on-create` server-side, `:on-match` client-side).

## Cross-references

- [005-StateMachines.md §Spawn-and-join via `:invoke-all`](005-StateMachines.md#spawn-and-join-via-invoke-all) — the join-all-complete primitive this pattern composes.
- [005-StateMachines.md §Composition with hierarchy and `:after`](005-StateMachines.md#composition-with-hierarchy-and-after) — phase-level wall-clock deadlines on `:invoke-all`-bearing states.
- [005-StateMachines.md §Retry-ownership boundary with `:rf.http/managed`](005-StateMachines.md#retry-ownership-boundary-with-rfhttpmanaged) — transport vs semantic retry split.
- [011-SSR.md §Server flow](011-SSR.md#server-flow-per-request) — the drain-then-render lifecycle this pattern fans out across.
- [011-SSR.md §Server-only `reg-cofx` for request context](011-SSR.md#server-only-reg-cofx-for-request-context) — the `:rf.server/request` cofx the loader's spawn site reads.
- [011-SSR.md §Streaming SSR](011-SSR.md#streaming-ssr) — the future composition path for progressive flushing.
- [014-HTTPRequests.md](014-HTTPRequests.md) — the managed-HTTP fx each child wraps; retry, abort, decode, failure taxonomy.
- [014-HTTPRequests.md §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry) — the retry-ownership split between child and parent.
- [Pattern-AsyncEffect.md §Parameter passing across the boundary](Pattern-AsyncEffect.md#parameter-passing-across-the-boundary) — mechanism 2 (spawn-spec `:data` fn) is how request-derived values flow from parent to child.
- [Pattern-Boot.md §SSR composition](Pattern-Boot.md#ssr-composition) — the sibling pattern for app-wide boot; this pattern is the per-page analogue.
- [Pattern-RemoteData.md](Pattern-RemoteData.md) — the per-slice lifecycle convention each child's result lands in.
