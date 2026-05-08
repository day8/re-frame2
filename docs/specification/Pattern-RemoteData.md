# Pattern — Remote Data

> **Type:** Pattern
> The standard request-lifecycle convention built on the framework's primitives (registered fx with `:platforms`; events; subs; schemas). Convention, not Spec.

> **Pattern-RemoteData is the specific case of [Pattern-AsyncEffect](Pattern-AsyncEffect.md) for HTTP requests** with the standard 5-key lifecycle slice (`:status` / `:data` / `:error` / `:loaded-at` / `:attempt`). Pattern-AsyncEffect names the generic six-step shape (register fx → return `:fx` → post work → reply → dispatch → commit); this pattern adds the canonical request-lifecycle convention layered on top.

## Role

A **convention**, not a Spec. The exact event names and id-prefixes shown below (`:articles/load`, `:articles/loaded`, etc.) are illustrative — the canonical content of this pattern is the **slice shape**, the **state-enum semantics** (especially `:loading` vs `:fetching`), and the **four-event lifecycle structure**. Project code adapts the names to its own feature ids while preserving that shape.

The pattern is convention so that:

- Code reads consistently across features.
- Tooling and scaffolds produce conformant shapes.
- UI loading-states, error toasters, and request-cancellation handles read predictable paths.

Other-language ports adopt the same convention with host-idiomatic naming.

## The lifecycle slice

Every feature that loads remote data has a slice in its `app-db` for tracking the request's state. The standard shape:

```clojure
{:status     :idle | :loading | :fetching | :loaded | :error
 :data       <result-or-nil>
 :error      <error-or-nil>
 :loaded-at  <timestamp-or-nil>          ;; ms since epoch, or nil
 :attempt    :int                        ;; bumps on every fetch (initial counts as 1)
 :stale-after-ms :int?}                  ;; optional; per-slice freshness window
```

Schema (CLJS reference):

```clojure
(def RequestSlice
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]    ;; bumps on every fetch; 0 means "never fetched"
   [:stale-after-ms {:optional true} [:maybe :int]]])
```

A feature that loads multiple resources has multiple slices, each at a documented path. The `RequestSlice` schema is reusable: any feature loading remote data uses it for each resource.

### `:attempt` semantics

`:attempt` increments on **every** fetch dispatched against this slice — initial load counts as `1`, the first retry as `2`, a revalidate as the next bump, and so on. It is not retry-only. The single rule keeps the counter useful for two purposes at once: distinguishing "have we ever tried?" (`:attempt 0` vs `:attempt > 0`) and driving retry-with-backoff calculations from a single source of truth. Authors who want a retry-only counter derive it (`(dec attempt)` once a successful `:loaded` has been seen, or a separate slot if the host needs it).

### `:error` semantics — what lands here

The slice's `:error` slot carries whatever the host's HTTP fx surfaces on failure. The pattern names the slot but does not normalise its shape; that is the host's job. In practice the value covers any of:

- A server-returned error body (4xx / 5xx with structured payload).
- A network-layer failure (connection refused, DNS failure, no response).
- A timeout from the host's HTTP fx.
- A cancellation surfaced as failure by the host (rare; see Cancellation below).

The view treats `:error` as opaque-but-renderable — show the host's error string, or run it through a feature-specific formatter. The pattern does not require distinguishing network-failure from server-error in the slice itself; if a feature needs that distinction, its `:on-error` handler shapes the value before storing.

### `:loading` vs `:fetching` — initial load vs background revalidation

The `:status` enum splits two situations that look the same to a careless UI but feel very different to a user:

| Status | Meaning | Typical UI | Has `:data`? |
|---|---|---|---|
| `:idle` | Never fetched; or just `:reset`. | Empty state / call-to-action. | No. |
| `:loading` | First fetch in flight. No `:data` yet. | Spinner / skeleton. | No. |
| `:fetching` | Re-fetch in flight while we already have `:data` (revalidation, polling, retry-with-data). | Subtle progress indicator at most; **never blank the page**. | Yes (the previous `:data`). |
| `:loaded` | Fetch completed successfully. `:data` is fresh. | Render `:data`. | Yes. |
| `:error` | The most recent fetch failed. Previous `:data` may still be present. | Error UI; offer retry; still render stale `:data` if appropriate. | Maybe. |

The split exists for one reason: **the UI for an empty page mid-load is a spinner; the UI for a page that already has data and is refreshing is not.** Without the split, every revalidation flashes a spinner over loaded content. With it, the convenience subs `:loading?` (truly empty) and `:fetching?` (in-flight, regardless of prior data) drive the right UI without per-feature gymnastics.

Standard transitions:

```text
:idle      --:load-->     :loading
:loading   --:loaded-->   :loaded
:loading   --:failed-->   :error
:loaded    --:load-->     :fetching     ;; revalidate; keep :data visible
:fetching  --:loaded-->   :loaded       ;; replace :data; clear :error
:fetching  --:failed-->   :error        ;; keep prior :data; populate :error
:error     --:load-->     :fetching     ;; retry; if prior :data exists
:error     --:load-->     :loading      ;; retry; if no prior :data
:any       --:reset-->    :idle
```

The `:feature.resource/load` event chooses `:loading` vs `:fetching` based on whether `:data` is currently `nil`. Convenience subs:

```clojure
(rf/reg-sub :articles/loading?    :<- [:articles/status] #(= % :loading))
(rf/reg-sub :articles/fetching?   :<- [:articles/status] #(or (= % :loading) (= % :fetching)))
(rf/reg-sub :articles/has-data?   :<- [:articles/data]   some?)
```

### `:loaded-at` and `:stale-after-ms` — declarative freshness

`:loaded-at` is the timestamp (ms since epoch) of the last successful fetch. The slice carries an optional `:stale-after-ms` that names the freshness window for *this* slice; a feature that wants 30-second freshness sets `:stale-after-ms 30000` once at initialise time. A derived sub answers "is this stale?":

```clojure
(rf/reg-sub :articles/stale?
  :<- [:articles]
  (fn [{:keys [loaded-at stale-after-ms]} _]
    (cond
      (nil? loaded-at)        true
      (nil? stale-after-ms)   false           ;; never auto-stale; explicit refresh only
      :else                   (> (- (current-time-ms) loaded-at) stale-after-ms))))
```

The pattern provides the timestamps and the sub; the **revalidation policy** (when to act on staleness — focus regain, interval timer, route re-entry, `:on-match` re-fire) lives in feature code or in a per-feature interceptor. The framework does not ship an automatic revalidator.

The decision lives in user code, so the policy is an enumerable, debuggable event flow — `[:articles/maybe-revalidate]` reads the staleness sub and emits `[:articles/load]` or no-op. The policy is visible to the same trace surface that sees every other event.

## The four standard events

Every request lifecycle uses the same four events (per resource), namespaced by feature:

| Event | What it does |
|---|---|
| `:feature.resource/load` | User wants the data. Sets `:status` to `:loading` (no prior `:data`) or `:fetching` (revalidate over existing `:data`); bumps `:attempt`; dispatches the fetch. |
| `:feature.resource/loaded` | The fetch returned successfully. Sets `:status :loaded`, `:data`, `:loaded-at`; clears `:error`. |
| `:feature.resource/load-failed` | The fetch failed. Sets `:status :error`, `:error`. Existing `:data` from a prior `:loaded` is **kept**. |
| `:feature.resource/reset` | **Optional convenience event.** Resets the slice to `:idle` (clears `:data`, `:error`, `:loaded-at`). Useful on navigation away or "clear results" affordances. Conformance does not require it; features that never need to clear the slice can omit it, and applications free to implement their own `clear-state` event with different semantics. |

Worked example — articles list:

```clojure
;; Schema
(rf/reg-app-schema [:articles] RequestSlice)

;; :on-create init for the feature
(rf/reg-event-db :articles/initialise
  (fn [db _]
    (assoc db :articles {:status :idle :data nil :error nil :loaded-at nil :attempt 0})))

;; Trigger the load — picks :loading vs :fetching based on whether :data exists
(rf/reg-event-fx :articles/load
  {:doc "Fetch the articles list. Sets the slice to :loading (no prior data)
         or :fetching (revalidate over existing data); the fetch dispatches
         :articles/loaded or :articles/load-failed on completion."}
  (fn handler-articles-load [{:keys [db]} _]
    (let [has-data? (some? (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in [:articles :status]  (if has-data? :fetching :loading))
               (assoc-in [:articles :error]   nil)
               (update-in [:articles :attempt] inc))
       :fx [[:http {:method :get
                    :url    "/api/articles"
                    :on-success [:articles/loaded]
                    :on-error   [:articles/load-failed]}]]})))

;; Success
(rf/reg-event-db :articles/loaded
  (fn handler-articles-loaded [db [_ articles]]
    (-> db
        (assoc-in [:articles :status]    :loaded)
        (assoc-in [:articles :data]      articles)
        (assoc-in [:articles :error]     nil)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

;; Failure — keep prior :data; populate :error
(rf/reg-event-db :articles/load-failed
  (fn handler-articles-load-failed [db [_ err]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error]  err))))
```

Convenience subs per slice (the `:loading?` / `:fetching?` split is the load-bearing one):

```clojure
(rf/reg-sub :articles            (fn [db _] (get db :articles)))
(rf/reg-sub :articles/status     :<- [:articles] (fn [s _] (:status s)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles] (fn [s _] (:error s)))
(rf/reg-sub :articles/loading?   :<- [:articles/status] #(= % :loading))                ;; truly empty + in-flight
(rf/reg-sub :articles/fetching?  :<- [:articles/status] #(or (= % :loading) (= % :fetching)))  ;; any in-flight
```

Views read the convenience subs:

```clojure
(rf/reg-view :pages/articles
  (fn []
    (cond
      @(subscribe [:articles/loading?])
      [:p "Loading…"]

      @(subscribe [:articles/error])
      [:p.error (str "Couldn't load: " @(subscribe [:articles/error]))]

      :else
      [:ul (for [a @(subscribe [:articles/data])]
             ^{:key (:id a)} [:li (:title a)])])))
```

## Variations

### Optimistic updates

For mutations where the user expects immediate feedback (toggle a like, update a profile), commit the change to `app-db` *before* the fetch resolves. If the fetch fails, roll back.

```clojure
(rf/reg-event-fx :article/toggle-like
  (fn [{:keys [db]} [_ id]]
    (let [prior (get-in db [:articles :data])]
      {:db (update-in db [:articles :data]
                      (fn [arts] (mapv #(if (= id (:id %)) (update % :liked? not) %) arts)))
       :fx [[:http {:method :post
                    :url (str "/api/articles/" id "/toggle-like")
                    :on-error [:article/toggle-like-failed prior]}]]})))

(rf/reg-event-db :article/toggle-like-failed
  (fn [db [_ prior _err]]
    (assoc-in db [:articles :data] prior)))
```

The pattern: capture the prior value as part of the *event*, not in mutable state. The rollback handler is pure.

### Cancellation

The remote-data slice itself does **not** track cancellation status — there is no `:cancelled` enum value, and `:status :error` is not reused as a cancellation signal. The canonical answer for "the user navigated away / a newer request superseded this one" is [Pattern-StaleDetection](Pattern-StaleDetection.md): carry an epoch on the dispatched reply event and suppress on mismatch. The work may still complete; the result simply gets discarded at the commit-validation stage rather than committed into the slice.

Hosts that *do* support a cancellation primitive (`AbortController` in browsers, `cancel!` in Promesa, etc.) MAY layer it on top of the `:http` fx as a bandwidth/CPU optimisation, but correctness lives in stale-detection. See [011-SSR.md](011-SSR.md) for SSR-side implications (server-side requests don't need cancellation; the request lifecycle bounds them).

### Polling

Repeating fetches at a fixed interval. Use `:dispatch-later` to schedule the next load:

```clojure
(rf/reg-event-fx :articles/poll
  (fn [_ _]
    {:fx [[:dispatch [:articles/load]]
          [:dispatch-later {:ms 30000 :dispatch [:articles/poll]}]]}))
```

A pause/resume pair (`:articles/poll-pause`, `:articles/poll-resume`) reads from a `:articles/poll-active?` sub if the user wants UI control over polling.

### Retry with backoff

A `:feature.resource/retry` event reads `:attempt` from the slice and decides whether to schedule another `:load` or surface an error. Backoff multipliers are sub computations from `:attempt`. The framework offers no built-in retry logic — convention only.

## SSR considerations

Per [011-SSR.md](011-SSR.md):

- The `:http` fx is `:platforms #{:server :client}` — fires identically on both. The server-side implementation reads via the JVM HTTP client; the client-side via `fetch`.
- Server-side: `:on-create` dispatches `:articles/load` (or equivalent); the drain settles before render-to-string runs; the rendered HTML reflects the loaded data.
- Client-side hydration: `:rf/hydrate` seeds the slice with `:status :loaded` and the server-loaded data. The client doesn't re-fetch on first render; subsequent navigations trigger fresh `:load` events as normal.

## What this pattern doesn't say

- **Which HTTP library to use.** The framework's `:http` fx is whatever the host registers. CLJS reference might use `cljs-http` or `js/fetch`; Python a `requests` wrapper; TS native `fetch`.
- **Authentication header injection.** Application concern; typically a cofx that injects the current session token, plus an interceptor that adds the header to outgoing `:http` calls.
- **Caching policy.** A feature decides how stale `:loaded-at` may be before re-fetching. The pattern provides the timestamp; the policy is application code.
- **Pagination / infinite scroll.** A feature with paginated data uses multiple slices (one per page) or a single slice with a list-of-pages structure. Convention silent here — implementation choice.

## Cross-references

- [004-Views.md §Loading state is explicit, not implicit](004-Views.md#loading-state-is-explicit-not-implicit) — why re-frame2 surfaces loading state as data rather than via Suspense-style implicit suspension; this pattern is the canonical exemplar.
- [011-SSR.md](011-SSR.md) — SSR-side fetch handling and `:platforms` metadata
- [009-Instrumentation.md §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/fx-handler-exception` for failed fetches
- [examples/login/core.cljs](../../examples/login/core.cljs) — the login feature uses a simplified version of this lifecycle
- [Pattern-NineStates.md](Pattern-NineStates.md) — the page-level convention that composes this lifecycle into `Nothing` / `Loading` / `Empty` / `One` / `Some` / `Too Many`.
- [examples/nine_states/](../../examples/nine_states/) — worked example exercising all five status states + data-shape variations (Empty / One / Some / Too Many).
- [examples/realworld/articles.cljs](../../examples/realworld/articles.cljs) — the RealWorld (Conduit) article-list page is the canonical full-shape exercise: standard 5-key slice, four lifecycle events, convenience subs, route `:on-match` integration, headless tests covering load + revalidate + failure.

## Conformance checklist

A remote-data implementation conforms to this convention when:

- Each remote resource has a slice with the standard `:status`/`:data`/`:error`/`:loaded-at`/`:attempt` shape.
- Slice is `reg-app-schema`-bound (dynamic hosts) or typed (static hosts).
- The three required lifecycle events (load / loaded / load-failed) are registered, each with `:doc`. The fourth, `reset`, is optional — register it where the feature needs to clear the slice.
- At least three subs (`:status`, `:data`, `:loading?`) per resource.
- The `:http` fx (or equivalent) has `:platforms` set so SSR works.
- Views render explicitly on `:loading?` / `:error` / loaded-data.
- Optimistic-update events capture the prior value in the rollback dispatch.
