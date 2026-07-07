# Pattern — Remote Data

The standard request-lifecycle convention. A 5-key slice (or one machine region) tracks **status / data / error / loaded-at / attempt**, and four events drive the lifecycle (**load / loaded / load-failed / reset**). The load-bearing distinction is `:loading` (truly empty, first fetch) vs `:fetching` (revalidate with existing data) — they look identical to a careless UI but feel very different to a user.

> **Mental-model anchor:** this is the **SWR / React-Query "stale-while-revalidate"** shape — the `:loading` vs `:fetching` split IS the SWR distinction (show a spinner on an empty page; keep stale data visible while refreshing). Map that intuition onto the re-frame2 slice below.

RemoteData is the **app-side** lifecycle slice on top of a **managed external effect** — typically `:rf.http/managed`, but the shape composes with any managed surface, framework-shipped (state-machine `:spawn`'d loaders, `:rf.server/*` per-request fxs) or app/library-built (request-reply over a WebSocket connection you build per [Pattern-WebSocket](../../../spec/Pattern-WebSocket.md) — re-frame2 ships **no** `:rf.ws/*`). See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md); this leaf names what the *receiving* state looks like once the reply lands.

## When to load

The prompt mentions: fetching data from a server, an HTTP request lifecycle, a list/article/feed/profile that needs to load, "spinner vs revalidate", optimistic update, polling, or any feature whose `app-db` will hold "the result of a fetch". Also load this leaf when picking between the **slice form** (a key in `app-db`) and the **machine form** (`:data-region` of a `reg-machine`) — see §Common variations.

## The re-frame2 features this pattern uses

The pattern composes:

- **`reg-app-schema`** — schema-binds the slice path so the slice's shape is enforced at boundaries (per cardinal rule 4 — schemas at boundaries, not everywhere).
- **`reg-event` for `:feature/load`** — dispatches the HTTP effect; picks `:loading` vs `:fetching` based on whether prior `:data` exists; bumps `:attempt`.
- **`reg-event` for `:feature/loaded`** — folds the success reply into the slice and stamps a durable `:loaded-at` from the causal clock (declare `:rf.cofx/requires [:rf/time-ms]`, EP-0017), so it declares and reads the recorded time — not a host-clock read. **`reg-event` for `:feature/load-failed`** — folds the failure; **prior `:data` is kept**, only `:status` and `:error` change (a db-only handler that just returns `{:db ...}`).
- **`:rf.http/managed` fx** (or the host's HTTP fx) — issues the request; its `:on-success` and `:on-failure` are pure routing sugar that dispatch the lifecycle events. The reply each delivers **is the canonical EP-0011 reply envelope** verbatim (one dialect, no reshape): `{:status :ok :value v …}` / `{:status :error :error m …}` / `{:status :cancelled :error m …}` (see managed-http.md). Read `:value` on `:ok`, the classified `:rf.http/*` map from `:error` on failure.
- **Layered subs `:feature/status`, `:feature/data`, `:feature/loading?`, `:feature/fetching?`** — convenience subs over the slice. `:loading?` means truly empty + in-flight; `:fetching?` means any in-flight (covers both `:loading` and `:fetching`).
- **(machine variant) `:initial :idle` + states `:idle :loading :fetching :loaded :error` + `:tags`** — the lifecycle as machine states. `:rf/machine-has-tag?` answers the same question `:loading?` / `:fetching?` did.

The single rule for the lifecycle: **`:loading` and `:fetching` are not interchangeable**. The first means "page is empty, show a spinner"; the second means "data is on screen, refresh in the background, never blank the page". Convenience subs hide the distinction from views that don't care.

## Canonical declaration — slice form

The dominant shape; used wherever an explicit `:status` keyword and Pattern-RemoteData's full 5-key slice is wanted. Lifted from `examples/real-apps/realworld_http/articles.cljs` (one of seven slice-form resources in realworld):

```clojure
(def RequestSlice
  [:map
   [:status    [:enum :idle :loading :fetching :loaded :error]]
   [:data      {:default nil} :any]
   [:error     {:default nil} [:maybe :any]]
   [:loaded-at {:default nil} [:maybe :int]]
   [:attempt   {:default 0}   :int]])

(rf/reg-app-schema [:articles] {:schema RequestSlice})

(rf/reg-event :articles/load
  (fn [{:keys [db]} _]
    ;; First load runs against an EMPTY db — reg-app-schema validates app-db, it
    ;; does NOT materialise schema :default values into it, so (:attempt) is nil
    ;; on the first dispatch. Use (fnil inc 0), never bare inc, or it throws.
    (let [has-data? (some? (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in  [:articles :status] (if has-data? :fetching :loading))
               (assoc-in  [:articles :error]  nil)
               (update-in [:articles :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             {:request    {:method :get :url "/api/articles"}
              :on-success [:articles/loaded]
              :on-failure [:articles/load-failed]}]]})))

(rf/reg-event :articles/loaded
  ;; :loaded-at is a DURABLE timestamp, so DECLARE the recorded clock fact
  ;; (EP-0017): :rf/time-ms is delivered flat under its id; reading it here
  ;; (not a fresh host clock) keeps the same reply replaying to the same
  ;; :loaded-at on epoch restore / SSR hydration / time-travel.
  {:rf.cofx/requires [:rf/time-ms]}
  ;; The HTTP `:on-success` reply IS the canonical EP-0011 envelope
  ;; `{:status :ok :value v …}` (no reshape); here we only need
  ;; the decoded body, so destructure `:value`. (The envelope also carries
  ;; `:work/id` / `:completed-at` — read `(:completed-at reply)` when you want
  ;; the causal completion time off the reply itself; below we use the
  ;; declared recordable clock instead.) The recorded clock arrives flat as
  ;; `rf/time-ms` (declared above), the durable causal time.
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:articles :status]    :loaded)
             (assoc-in [:articles :data]      value)
             (assoc-in [:articles :error]     nil)
             (assoc-in [:articles :loaded-at] time-ms))}))

(rf/reg-event :articles/load-failed
  ;; Failure reply: the canonical `{:status :error :error m …}` envelope —
  ;; the classified `:rf.http/*` map rides under `:error`.
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (-> db
             (assoc-in [:articles :status] :error)
             (assoc-in [:articles :error]  error))}))

;; The fourth lifecycle event: seed (or re-seed) the whole slice explicitly.
;; This is the supported way to initialise the slice — :default schema props
;; are validation hints, not an app-db seed, so the slice must be written.
(rf/reg-event :articles/reset
  (fn [{:keys [db]} _]
    {:db (assoc db :articles {:status :idle :data nil :error nil :loaded-at nil :attempt 0})}))

(rf/reg-sub :articles            (fn [db _] (get db :articles)))
;; computation fn is (fn [input query-v] value) — a bare keyword in fn position
;; is rejected at registration (:rf.error/reg-sub-bad-args)
(rf/reg-sub :articles/status     :<- [:articles] (fn [articles _] (:status articles)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [articles _] (:data articles)))
(rf/reg-sub :articles/loading?   :<- [:articles/status] (fn [status _] (= status :loading)))
(rf/reg-sub :articles/fetching?  :<- [:articles/status] (fn [status _] (contains? #{:loading :fetching} status)))
```

## Canonical declaration — `:data-region` machine form

Used when the lifecycle is *part of* a larger page's machine (the page already has a `:type :parallel` machine with `:form` / `:mode` axes — see `patterns/nine-states.md`), or when the lifecycle plus a per-region cancellation/`:spawn` are wanted. Lifted from `examples/real-apps/realworld_http/tags.cljs`:

```clojure
(rf/reg-machine :realworld/tags
  {:initial :idle
   :data    {:tags [] :error nil :loaded-at nil :attempt 0}
   :actions
   {:bump-attempt (fn [{d :data}] {:data (update d :attempt (fnil inc 0))})
    ;; `now` is the causal `:completed-at` carried in on the `:fetch-succeeded`
    ;; event payload (threaded from the reply map / managed-HTTP completion) —
    ;; NOT an ambient `(current-time-ms)` read inside the action. A durable
    ;; `:loaded-at` MUST source from causal context so the snapshot replays
    ;; identically; see EP-0017 §Recordable coeffects (the recorded clock is
    ;; `:rf/time-ms`; here it rides in on the reply payload).
    :set-tags     (fn [{d :data [_ {:keys [tags now]}] :event}]
                    {:data (assoc d :tags (vec tags) :error nil :loaded-at now)})
    :set-error    (fn [{d :data [_ {:keys [failure]}] :event}] {:data (assoc d :error failure)})}
   :states
   {:idle     {:tags #{:tags/idle}
               :on   {:fetch-started {:target :loading :action :bump-attempt}}}
    :loading  {:tags #{:tags/loading :tags/in-flight :tags/transient}
               :on   {:fetch-succeeded {:target :loaded :action :set-tags}
                      :fetch-failed    {:target :error  :action :set-error}}}
    :fetching {:tags #{:tags/fetching :tags/in-flight :tags/loaded :tags/transient}
               :on   {:fetch-succeeded {:target :loaded :action :set-tags}
                      :fetch-failed    {:target :error  :action :set-error}}}
    :loaded   {:tags #{:tags/loaded}
               :on   {:fetch-started {:target :fetching :action :bump-attempt}}}
    :error    {:tags #{:tags/error}
               :on   {:fetch-started {:target :loading  :action :bump-attempt}}}}})
```

The lifecycle's status enum maps **one-to-one** onto state-keywords. The slice's `:status` field disappears — the state-keyword IS the status. The `:loading?` / `:fetching?` view booleans become `(rf/machine-has-tag? :realworld/tags :tags/loading)` / `(rf/machine-has-tag? :realworld/tags :tags/in-flight)`.

## When to choose each form

- **Slice form** — single resource, no concurrent axes, no cancellation cascade needed, view code can be host-agnostic. The vast majority of cases.
- **Machine form** — the lifecycle is one region of a larger parallel machine (composes with `patterns/nine-states.md` and `patterns/forms.md`); OR the request's lifetime should be bound to a parent state (the actor-destroy cancellation cascade fires when the region exits); OR the team wants tag-shaped queries instead of slice-field comparisons.

Realworld ships both shapes side-by-side. `articles`, `feed`, `article`, `comments`, `profile`, `profile.articles`, `profile.favorites` use the slice form; `tags` uses the machine form. The README's "Pattern-RemoteData — two shapes side-by-side" section has the worked comparison.

## Common variations

- **Optimistic updates.** Commit to `:data` *before* the fetch; capture the prior value as part of the rollback event. Pure rollback handler.
- **Polling.** `:dispatch-later` schedules the next `:load`. Pause/resume via a `:poll-active?` flag.
- **Retry with backoff.** Read `:attempt` from the slice; compute backoff as a sub. The framework ships no built-in retry — convention only. (For HTTP-specific retry semantics see `:rf.http/managed`'s `:retry` arg in EP 014.)
- **Stale detection.** Carry an epoch on the dispatched reply event; suppress on mismatch. See `SKILL-REDIRECT.md` → *Pattern — Stale detection*.

## Worked example

- **Slice form**: `examples/real-apps/realworld_http/articles.cljs` — articles list with `?tag=` query, revalidate-on-route, full lifecycle.
- **Machine form**: `examples/real-apps/realworld_http/tags.cljs` — popular-tags list, single-region `reg-machine`, tag-shaped view queries.
- **Compose with NineStates**: `examples/patterns/nine_states/core.cljs` — `:data` region as one axis of a parallel machine, with cardinality cascade (`:empty` / `:one` / `:some` / `:too-many`).

## Why `:loading` vs `:fetching` is non-negotiable

The split exists for one reason: an empty page mid-load shows a spinner; a page that already has data and is refreshing does not. Without it, every revalidation flashes a spinner over loaded content. The `:loading?` / `:fetching?` subs hide the distinction from view code that doesn't care, cheaply expose it to code that does.

`:attempt` increments on **every** fetch (initial `1`, first retry `2`, revalidate `3`). One counter answers two questions: "have we ever tried?" (`> 0`) and "how many retries?" (drives backoff). Don't add a parallel retry-only counter — derive it.

## When a resource fits better

This leaf is the **hand-rolled** slice (or machine region). If the same fetch is **shared across views**, needs **freshness / TTL / fresh-skip**, must be **invalidated after a write** (list ⇄ detail), or wants **tenant/user scoping** as a fail-closed boundary, reach instead for the declarative [`patterns/resources.md`](resources.md) (`reg-resource`, optional `day8/re-frame2-resources`) — the TanStack-Query-shaped layer that owns identity, scope, staleness, dedupe, invalidation, GC, and SSR preload for you. Resources lower onto the same managed-HTTP transport; you stop hand-writing the lifecycle. Use this RemoteData slice when the fetch is a one-off with no sharing or invalidation story.

## Deeper pointers

- Spec: `SKILL-REDIRECT.md` → *Pattern — Remote data* (full slice schema, `:loading` vs `:fetching` table, optimistic-update rollback, SSR considerations).
- HTTP fx: `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* (the `:rf.http/managed` surface, retry semantics, failure categories, cancellation cascade).
- Cached/shared server-state: [`patterns/resources.md`](resources.md) and `SKILL-REDIRECT.md` → *EP — Resources (016)* — the framework-owned cache layer over this slice's territory.
- Compose: `patterns/nine-states.md` (the `:data` region of a parallel machine).
- Stale: `SKILL-REDIRECT.md` → *Pattern — Stale detection* (epoch idiom).

---

*Derived from `examples/real-apps/realworld_http/articles.cljs` (slice form) and `examples/real-apps/realworld_http/tags.cljs` (machine form) @ main `89bd9c3`. Re-verify if RealWorld's slice shape changes.*
