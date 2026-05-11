# Pattern — RemoteData

The standard request-lifecycle convention. A 5-key slice (or one machine region) tracks **status / data / error / loaded-at / attempt**, and four events drive the lifecycle (**load / loaded / load-failed / reset**). The load-bearing distinction is `:loading` (truly empty, first fetch) vs `:fetching` (revalidate with existing data) — they look identical to a careless UI but feel very different to a user.

## When to load this leaf

The prompt mentions: fetching data from a server, an HTTP request lifecycle, a list/article/feed/profile that needs to load, "spinner vs revalidate", optimistic update, polling, or any feature whose `app-db` will hold "the result of a fetch". Also load this leaf when picking between the **slice form** (a key in `app-db`) and the **machine form** (`:data-region` of a `reg-machine`) — see §Common variations.

## The re-frame2 features that implement it

The pattern composes:

- **`reg-app-schema`** — schema-binds the slice path so the slice's shape is enforced at boundaries (per Pillar — schemas at boundaries, not everywhere).
- **`reg-event-fx` for `:feature/load`** — dispatches the HTTP effect; picks `:loading` vs `:fetching` based on whether prior `:data` exists; bumps `:attempt`.
- **`reg-event-db` for `:feature/loaded` / `:feature/load-failed`** — folds the reply into the slice. On failure, **prior `:data` is kept**; only `:status` and `:error` change.
- **`:rf.http/managed` fx** (or the host's HTTP fx) — issues the request; its `:on-success` and `:on-failure` dispatch the lifecycle events.
- **Layered subs `:feature/status`, `:feature/data`, `:feature/loading?`, `:feature/fetching?`** — convenience subs over the slice. `:loading?` means truly empty + in-flight; `:fetching?` means any in-flight (covers both `:loading` and `:fetching`).
- **(machine variant) `:initial :idle` + states `:idle :loading :fetching :loaded :error` + `:tags`** — the lifecycle as machine states. `:rf/machine-has-tag?` answers the same question `:loading?` / `:fetching?` did.

The single rule for the lifecycle: **`:loading` and `:fetching` are not interchangeable**. The first means "page is empty, show a spinner"; the second means "data is on screen, refresh in the background, never blank the page". Convenience subs hide the distinction from views that don't care.

## Canonical declaration — slice form

The dominant shape; used wherever an explicit `:status` keyword and Pattern-RemoteData's full 5-key slice is wanted. Lifted from `examples/reagent/realworld/articles.cljs` (one of seven slice-form resources in realworld):

```clojure
(def RequestSlice
  [:map
   [:status    [:enum :idle :loading :fetching :loaded :error]]
   [:data      {:default nil} :any]
   [:error     {:default nil} [:maybe :any]]
   [:loaded-at {:default nil} [:maybe :int]]
   [:attempt   {:default 0}   :int]])

(rf/reg-app-schema [:articles] RequestSlice)

(rf/reg-event-fx :articles/load
  (fn [{:keys [db]} _]
    (let [has-data? (some? (get-in db [:articles :data]))]
      {:db (-> db
               (assoc-in  [:articles :status] (if has-data? :fetching :loading))
               (assoc-in  [:articles :error]  nil)
               (update-in [:articles :attempt] inc))
       :fx [[:rf.http/managed
             {:request    {:method :get :url "/api/articles"}
              :on-success [:articles/loaded]
              :on-failure [:articles/load-failed]}]]})))

(rf/reg-event-db :articles/loaded
  (fn [db [_ {:keys [value]}]]
    (-> db
        (assoc-in [:articles :status]    :loaded)
        (assoc-in [:articles :data]      value)
        (assoc-in [:articles :error]     nil)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

(rf/reg-event-db :articles/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error]  failure))))

(rf/reg-sub :articles            (fn [db _] (get db :articles)))
(rf/reg-sub :articles/status     :<- [:articles] :status)
(rf/reg-sub :articles/data       :<- [:articles] :data)
(rf/reg-sub :articles/loading?   :<- [:articles/status] #(= % :loading))
(rf/reg-sub :articles/fetching?  :<- [:articles/status] #(or (= % :loading) (= % :fetching)))
```

## Canonical declaration — `:data-region` machine form

Used when the lifecycle is *part of* a larger page's machine (the page already has a `:type :parallel` machine with `:form` / `:mode` axes — see `patterns/nine-states.md`), or when the lifecycle plus a per-region cancellation/`:invoke` are wanted. Lifted from `examples/reagent/realworld/tags.cljs`:

```clojure
(rf/reg-machine :realworld/tags
  {:initial :idle
   :data    {:tags [] :error nil :loaded-at nil :attempt 0}
   :actions
   {:bump-attempt (fn [d _] {:data (update d :attempt (fnil inc 0))})
    :set-tags     (fn [d [_ {:keys [tags now]}]]
                    {:data (assoc d :tags (vec tags) :error nil :loaded-at now)})
    :set-error    (fn [d [_ {:keys [failure]}]] {:data (assoc d :error failure)})}
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

The lifecycle's status enum maps **one-to-one** onto state-keywords. The slice's `:status` field disappears — the state-keyword IS the status. The `:loading?` / `:fetching?` view booleans become `(rf/has-tag? :realworld/tags :tags/loading)` / `(rf/has-tag? :realworld/tags :tags/in-flight)`.

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

- **Slice form**: `examples/reagent/realworld/articles.cljs` — articles list with `?tag=` query, revalidate-on-route, full lifecycle.
- **Machine form**: `examples/reagent/realworld/tags.cljs` — popular-tags list, single-region `reg-machine`, tag-shaped view queries.
- **Compose with NineStates**: `examples/reagent/nine_states/core.cljs` — `:data` region as one axis of a parallel machine, with cardinality cascade (`:empty` / `:one` / `:some` / `:too-many`).

## Pillar 5 — why `:loading` vs `:fetching` is non-negotiable

The split exists for one reason: the UI for an empty page mid-load is a spinner; the UI for a page that already has data and is refreshing is not. Without the split, every revalidation flashes a spinner over loaded content. The pattern's `:loading?` and `:fetching?` subs hide the distinction from view code that doesn't care, while making it cheap for view code that does.

`:attempt` increments on **every** fetch (initial load is `1`, first retry `2`, revalidate `3`). One counter answers two questions: "have we ever tried?" (`> 0`) and "how many times have we retried?" (drives backoff). Don't add a parallel retry-only counter — derive it.

## Deeper pointers

- Spec: `SKILL-REDIRECT.md` → *Pattern — Remote data* (full slice schema, `:loading` vs `:fetching` table, optimistic-update rollback, SSR considerations).
- HTTP fx: `SKILL-REDIRECT.md` → *EP — HTTP requests (014)* (the `:rf.http/managed` surface, retry semantics, failure categories, cancellation cascade).
- Compose: `patterns/nine-states.md` (the `:data` region of a parallel machine).
- Stale: `SKILL-REDIRECT.md` → *Pattern — Stale detection* (epoch idiom).
