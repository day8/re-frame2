# Part 4: writes — favoriting, posting, invalidation

In [Part 2](02-server-data.md) the app *read* server state through resources — a resource is a cached, declarative read of remote data. In [Part 3](03-auth-and-forms.md) you added login. Now Conduit gets its writes: the favorite heart on every article card, and the editor's **Publish Article** button. By the end of this part:

- clicking the heart fires a **mutation** — a write to the server, registered once with its consequences attached. Its registration declares which cached reads it breaks, so the detail, the lists, and your personal feed all refresh with **no wiring at the call site**.
- publishing an article saves it, then *continues* — navigate to the new article, clear the form — via a **`:reply-to` event**, not a callback.
- a **`:can-leave` route guard** and a confirm dialog block you from navigating away from a half-written draft.

> **Coming from RTK Query or TanStack Query?** A mutation here is RTK Query's mutation with `invalidatesTags`, with three differences: invalidation is declared once on the write's *registration*, not per call site; every invalidation is **scoped** — your feed and another user's feed are different cache entries, and a write names which scopes it touches; and the post-write continuation is a dispatched **event**, not an `onSuccess` callback.

The idea this part lands is worth holding onto as you read:

**A mutation's `:reply-to` is the continuation — on the record, inspectable, replayable.**

## The reads, ready to be broken

In Part 2, each resource declared `:tags` on its cached data. The article detail carries `[:article slug]`. The lists carry `[:article-list]` plus a tag per article they contain. We planted those tags back then for exactly this moment — they're the join key between writes and reads, the thing that lets a write say "I just made these tags stale" without naming a single read by hand.

One read is still missing: the **personal feed** (`GET /articles/feed`). Part 2 left it out on purpose, because what it returns depends on *who is asking*. So its cache has to be keyed per user, which means we need a way to compute that key. That's a **named scope resolver** — a small function that turns app-db (your app's single state map) into a cache scope:

```clojure
;; src/conduit/scope.cljs
;; cf. examples/reagent/realworld_resources/scope.cljs
(rf/reg-resource-scope :conduit/session
  {:doc     "The session's cache scope — nil when logged out (fail-closed)."
   :inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Now register `:conduit/feed` exactly like Part 2's resources — tagged `#{[:feed]}` — but with `:scope {:from-db :conduit/session}` instead of `:rf.scope/global`. Its cache entries are keyed by the signed-in username, so each user gets their own. Here's the payoff of fail-closed: signed out, the scope resolves to `nil` and the read fails closed. It never silently serves the previous user's feed.

## Register the write

A mutation is the write-side counterpart of a resource. Where a resource describes a *read* and how its result is cached, a mutation describes a *write* and what that write makes stale. You register it with `reg-mutation`:

```clojure
;; src/conduit/mutations.cljs
;; cf. examples/reagent/realworld_resources/mutations.cljs
(ns conduit.mutations
  (:require [re-frame.core :as rf]
            [re-frame.resources]      ;; reg-mutation + the :rf.mutation/* surface
            [re-frame.http.managed]   ;; the transport mutations lower through
            ;; Part 3's api base in a helper: (defn full-url [path] (str api path))
            [conduit.http :as rh]
            [conduit.schema :as schema]))

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Seed the cached article detail from the write's own reply — the heart
   ;; flips the moment the server confirms.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
                     result})
   ;; The reads this write breaks: article + lists (global scope), and the
   ;; signed-in user's feed (session scope).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))
```

Three things do the work:

- **The request fn** (the third positional arg) describes the HTTP write the way a resource describes its read. It must *not* supply `:on-success` / `:on-failure` / `:request-id`, because the runtime owns reply addressing — and that ownership is what makes the stale-reply suppression below possible. There's one asymmetry from reads worth flagging: **writes never retry by default.** Re-sending a POST because the reply was slow is the classic double-submit bug, so a mutation retries only if its request map explicitly opts in. This one doesn't.
- **`:invalidates`** declares which tags the write makes stale on success. A single-scope write can use a bare tag set, like `#{[:article slug]}`. But favoriting breaks reads in *two* scopes: the article and lists are global, while your feed is keyed by session. So it returns a vector of descriptors, each naming its own scope. The second resolves through the `:conduit/session` resolver above, at settle time. One write, both scopes, declared once.
- **`:populates`** seeds an exact cache entry from the write's own reply, *before* the invalidation runs. The favorite endpoint replies with the full updated article, so we write it straight into the `:conduit/article` entry. The populated value must be the resource's stored shape — the same `{:article …}` envelope a normal load produces, which is why we pass `result` whole. A populated entry counts as freshly loaded, so this mutation's own invalidation won't turn around and refetch the key it just learned.

Register `:conduit/unfavorite` the same way — same shape, `:method :delete`. The full registration surface lives in [Spec 016](../../../spec/016-Resources.md).

> **The scope footgun — and the safe pattern.** The single most common mutation bug is a **scope mismatch**: a write invalidates a tag in the *wrong* scope, so the invalidation matches no cache entry and the stale read is never refreshed. It is silent by construction — invalidating a tag that has no entry in *this* scope is a legitimate "nothing to do," so the runtime can't tell a true no-op from a miss by the match alone.
>
> The footgun looks like this. A mutation with **no `:scope`** defaults its execution scope to `:rf.scope/global` (writes are fail-open — a write leaks nothing, so global is a safe default). A **bare tag set** on `:invalidates` then inherits that resolved scope. So this:
>
> ```clojure
> ;; ✗ WRONG — the feed lives in the SESSION scope, but this invalidates GLOBAL
> (rf/reg-mutation :conduit/post-to-feed
>   {:params-schema [:map …]
>    :invalidates   (fn [_ _result] #{[:feed]})}   ;; resolves to :rf.scope/global
>   (fn [_ _] {:request {:method :post :url (rh/full-url "/articles")}}))
> ```
>
> quietly *misses* `:conduit/feed`, because that resource is `:scope {:from-db :conduit/session}` — its entries live under `[:rf.scope/session {:username …}]`, never under global. The feed stays stale.
>
> The fix is the per-scope descriptor form you already used above: name the scope each tag actually lives in.
>
> ```clojure
> ;; ✓ RIGHT — name the session scope, via the same resolver the resource uses
> :invalidates (fn [_ _result]
>                [{:scope {:from-db :conduit/session} :tags #{[:feed]}}])
> ```
>
> The same rule covers route- and tenant-scoped reads: **a write's invalidation scope must match the scope of the resources it breaks.** When a write touches both a global fact and a session fact, return one descriptor per scope (exactly as `:conduit/favorite` does above) — never a blanket `:cross-scope? true`, which is the audited multi-tenant escape, not the default.
>
> You don't have to spot this by eye. In dev builds the framework emits a loud **`:rf.warning/mutation-scope-mismatch`** at the moment a mutation invalidates in a scope that holds no matching entry *while a different scope does* — the write-side complement of the read-side `:rf.warning/resource-sub-scope-mismatch`. It names the mutation, the scope it invalidated in, the scope that actually held the entry, and the tags, and its `:hint` points at the fix. It's dev-only (elided from production by `goog.DEBUG`), dedupe-keyed so a form firing on every keystroke warns once, and it fires only on a genuine *mismatch* — a tag with no entry anywhere (a true nothing-to-invalidate) and a deliberate `:cross-scope? true` sweep are both quiet. Watch for it in the trace stream or in Xray.

> **Make it optimistic: the heart flips *before* the reply.** `:populates` runs on success — the heart flips the moment the server confirms. For a tiny, reversible change like a favorite, you usually want the flip *immediately on click*, then a revert if the write fails. That's an **optimistic mutation**, and it's a registration key, not a call-site dance.
>
> Add `:optimistic-tags` — the tag-addressed twin of `:invalidates`. Where `:invalidates` says "these tags went *stale*," `:optimistic-tags` says "patch every entry carrying these tags, *now*, before the request":
>
> ```clojure
> (rf/reg-mutation :conduit/favorite
>   {:doc           "Favorite an article (optimistic). POST /articles/:slug/favorite."
>    :params-schema [:map [:slug :string]]
>    :scope         :rf.scope/global
>    ;; FORWARD: flip the heart + bump the count on every entry tagged
>    ;; [:article slug] — the detail, every list, the session feed — at once.
>    :optimistic-tags (fn [{:keys [slug]}]
>                       [{:scope :rf.scope/global
>                         :tags  #{[:article slug]}
>                         :patch (fn [data] (favorite-patch true slug data))}
>                        {:scope {:from-db :conduit/session}
>                         :tags  #{[:feed]}
>                         :patch (fn [data] (favorite-patch true slug data))}])
>    ;; COMMIT on :ok — the reply's authoritative Article overwrites the guess.
>    :populates     (fn [{:keys [slug]} result]
>                     {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
>                      result})
>    :invalidates   (fn [{:keys [slug]} _result]
>                     [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
>                      {:scope {:from-db :conduit/session} :tags #{[:feed]}}])
>    :on-conflict   :invalidate}
>   (fn [{:keys [slug]} _ctx]
>     {:request {:method :post :url (rh/full-url (str "/articles/" slug "/favorite"))}
>      :decode  schema/ArticleResponse}))
> ```
>
> Three things make this safe, and none of them are your job:
>
> - **The runtime records the inverse.** You write only the *forward* `:patch` (`favorite-patch` toggles the flag and steps the count, handling both the detail's `{:article …}` and a list's `{:articles […]}` shape). The runtime snapshots each touched entry's prior value, so a rollback restores exactly what was there — never a reconstruction that can drift from the forward patch.
> - **The reply settles deterministically.** An `:ok` reply **commits** (the `:populates` seed overwrites the optimistic value with the server's exact count, then `:invalidates` reconciles the lists). An `:error` reply **rolls back** — the heart flips back everywhere, verbatim. No `:on-failure` handler, no `app-db` undo flag.
> - **A contested rollback refetches, it doesn't clobber.** If a *concurrent* write moved a touched entry while yours was in flight, `:on-conflict :invalidate` (the default) marks that entry stale and lets the read path fetch the authoritative value, rather than restoring a now-stale snapshot. This is re-frame2's deliberate divergence from TanStack/SWR's unconditional context restore — on a contested rollback, the read path is the recovery authority.
>
> The view changes by *one* thing: drop `:disabled (:pending? fav)` — the user already sees their change, so don't block the button — and optionally read the derived **`:optimistic?`** flag (`(:optimistic? fav)`, true while the optimistic value is showing and unconfirmed) for a subtle in-flight cue. **Coming from TanStack/RTK/SWR?** This is their `onMutate` + `onError` rollback (TanStack), `updateQueryData` + undo patch (RTK), or `optimisticData` + `rollbackOnError` (SWR) — except the inverse is runtime-recorded, not hand-written, and the whole apply/settle is on the trace ([`:rf.mutation/optimistic-applied`](../../../spec/016-Resources.md#optimistic-mutations) → `optimistic-reconciled` / `optimistic-rolled-back`). The full optimistic surface lives in [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations); `examples/reagent/realworld_resources/mutations.cljs` runs exactly this favorite.

## Fire it, watch the instance

A resource is "a sub you read and a cause you fire." A mutation is the mirror image: **a cause you fire and an instance you watch.** The UI never calls the mutation directly. Instead it dispatches `:rf.mutation/execute` — `dispatch` being how every event enters the system:

```clojure
;; src/conduit/views.cljs
;; cf. examples/reagent/realworld_resources/views.cljs
(rf/reg-event :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    (if (nil? (get-in db [:auth :user]))
      ;; Logged out, a favorite click goes to login instead of a 401.
      {:fx [[:dispatch [:rf.route/navigate :conduit.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if favorited? :conduit/unfavorite :conduit/favorite)
                         :params   {:slug slug}
                         :instance [:favorite slug]
                         :cause    [:click :ui/favorite slug]}]]]})))

(reg-view favorite-button [{:keys [article]}]
  (let [{:keys [slug favorited favoritesCount]} article
        fav @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])]
    [:button.btn.btn-outline-primary.btn-sm
     {:type     "button"
      :class    (when favorited "active")
      :disabled (:pending? fav)
      :on-click #(dispatch [:ui/favorite slug favorited])}
     [:i.ion-heart] " " favoritesCount]))
```

Pause on the `:instance` id, because this is where people get tripped up. Mutation state is keyed by **instance**, not by mutation id. `[:favorite slug]` gives every article card its own lifecycle, which means you can click hearts on three cards in quick succession and they can never clobber each other. The view watches its instance through the passive `[:rf.mutation/state {:instance …}]` sub — a subscription being a read of derived state — which returns `{:pending? :success? :error? :settled? :result :error :optimistic?}`. That's where `:disabled (:pending? fav)` comes from. No `app-db` bookkeeping, no `:saving?` flag to maintain. (The `:optimistic?` flag is for the optimistic variant in the note above — true while an unconfirmed optimistic value is showing.)

Notice what the view *doesn't* do: it never invalidates anything. Add this button to the article cards from Part 1 and to the article page, and you're done. Favoriting behaves identically everywhere, because the write's consequences live on the write, not on the call site.

### Watch it happen

Run the app, sign in, and click a heart. The count changes immediately — that's `:populates` landing. A moment later the list and your feed have refetched. Now open Xray, click another heart, and read the causal chain off the trace: the `:ui/favorite` dispatch, then `:rf.mutation/started`, the HTTP request, then `succeeded` carrying the per-descriptor invalidation evidence (which tags, in which scopes), then the refetches of the reads a route still owns. Every step names its cause. When a list refreshes "by itself" six months from now, this trace is how you'll know which write did it.

## Publish from the editor — and continue with `:reply-to`

Watching an instance is the right tool for *rendering*: the button disables itself, and that's all it needs. But a successful save usually has to **drive workflow** too — navigate to the new article, clear the form. Those are causes, not renders. In Promise-land you'd `await` the POST and then navigate. Here the continuation is a declared part of the execute call: `:reply-to`.

First, the write. Create and edit share one mutation that switches POST/PUT on whether a slug exists yet:

```clojure
;; src/conduit/mutations.cljs
;; cf. examples/reagent/realworld_resources/article_editor.cljs
(rf/reg-mutation :conduit/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug)."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   :scope         :rf.scope/global
   ;; Lists always go stale; an edit also stales its own detail entry. A new
   ;; article has no prior slug — its detail loads fresh on navigate.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug] :as draft} _ctx]
    {:request {:method (if slug :put :post)
               :url    (rh/full-url (if slug (str "/articles/" slug) "/articles"))
               :body   {:article (select-keys draft [:title :description :body :tagList])}}
     :decode  schema/ArticleResponse}))
```

The editor's `app-db` slice is an ordinary form in Part 3's mold: a `:draft` the inputs edit, plus a `:baseline` (the article as loaded, or blank) so we can tell whether anything actually changed. Note what's *not* here: there's no `:status` field. The submission lifecycle Part 3 hand-rolled lives on the mutation instance instead — one of the things you get back by moving to mutations.

```clojure
;; src/conduit/editor.cljs
;; cf. examples/reagent/realworld_resources/article_editor.cljs
(ns conduit.editor
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def blank-draft {:title "" :description "" :body "" :tagList ""})

(defn editor-slice
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug slug :draft baseline :baseline baseline
    :errors {} :submit-attempted? false}))

(defn draft-from-article [{:keys [title description body tagList]}]
  {:title title :description description :body body
   :tagList (str/join ", " tagList)})

(defn validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

(defn parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim) (remove str/blank?) vec))

;; The editor route's :on-match (registered below): fresh slice, prior
;; save instance cleared.
(rf/reg-event :editor/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance :editor/save}]]]}))
```

Submit validates, then fires the mutation. The continuation is named right at the call site:

```clojure
(rf/reg-event :editor/submit
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft baseline]} (:editor db)
          errors (validate-draft draft)]
      (cond
        (seq errors)
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] errors))}

        (= draft baseline) {}   ;; valid but unchanged — nothing to save

        :else
        {:fx [[:dispatch [:rf.mutation/execute
                          {:mutation :conduit/save-article
                           :params   (cond-> (-> (select-keys draft [:title :description :body])
                                                 (assoc :tagList (parse-tag-list (:tagList draft))))
                                       slug (assoc :slug slug))
                           :instance :editor/save
                           :reply-to [:editor/replied]
                           :cause    [:submit :editor/save]}]]]}))))
```

When the runtime accepts the write's reply, it dispatches `[:editor/replied reply]` — your event target, with one canonical **reply map** appended as the final argument:

```clojure
(rf/reg-event :editor/replied
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (if (not= :ok status)
      ;; Failure already shows on the form via the instance's :error state.
      {}
      ;; The save replies with the saved article: re-seed the editor so the
      ;; draft is CLEAN (the :can-leave guard below will let us go), clear
      ;; the instance, and navigate.
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance :editor/save}]]
              [:dispatch [:rf.route/navigate :conduit.article/show {:slug (:slug article)}]]]}))))
```

That `{:keys [status value]}` is the reply map's public shape: `:status` tells you how the write settled, and `:value` carries the decoded result on `:ok`. (One spelling worth filing away: the *reply map* always names the decoded result `:value`, while the durable mutation *instance* stores the same thing under `:result` — a queryable status record, a different layer from the transient reply. You read `:value` in a continuation; you read `:result` off the instance sub.)

Three rules make `:reply-to` trustworthy:

- **You only ever see accepted, terminal replies.** The reply's `:status` is `:ok`, `:error`, or `:cancelled` — so you branch on it. A *stale* reply (the user re-submitted under the same instance, or something cleared it) is suppressed by the runtime and never reaches your handler. You simply cannot write the "slow first response overwrites the fast second one" bug here.
- **The continuation observes a settled world.** The phase order is fixed: populate and invalidate run first, the instance settles, *then* `:reply-to` dispatches. By the time `:editor/replied` runs, the lists are already marked stale and refetching.
- **Workflow goes in `:reply-to`; cache consequences go on the registration.** Navigate, toast, update a session — those are continuation. "Which reads did this break" — that's `:invalidates` / `:populates`, declared once. Don't invalidate tags from a continuation.

And here's the point this part exists to land: `[:editor/replied]` is **data**. It's not a closure awaiting a Promise. It's an event vector, sitting in the execute payload where Xray can show it (the mutation's `replied` trace op is that dispatch), where a test can assert it, and where replay can re-run it deterministically. The async workflow "save, then navigate" is on the record, step by step. That's the trade against `await`: slightly more ceremony, in exchange for a workflow you can inspect after the fact — [No await: continuations are data](../explanation/continuations-are-data.md) makes the full argument.

> **Coming from re-frame v1?** `:reply-to` is your `:on-success`/`:on-failure` pair collapsed into one stale-safe target with a uniform reply map — the same envelope every async family replies with ([From re-frame v1](../25-from-re-frame-v1.md)).

## Guard the half-written draft

One gap is left. Write half an article, click the site logo, and the draft silently vanishes — which is exactly the kind of thing users never forgive. Routes close this with a `:can-leave` guard, a subscription the router consults before navigating away:

```clojure
;; src/conduit/editor.cljs
(rf/reg-sub :editor/dirty?
  (fn [db _]
    (let [{:keys [draft baseline]} (:editor db)]
      (not= draft baseline))))

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))

;; src/conduit/routing.cljs — a new route for the editor, with the guard.
(rf/reg-route :conduit.editor/new
  {:tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]}
  "/editor")
```

(The example adds the `/editor/:slug` edit route the same way — same guard; its `:on-match` seeds the draft from the article read.)

The contract is strict, and the strictness is the point. `true` allows the navigation. `false` blocks it. Anything else blocks *and* emits a structured error (`:rf.error/can-leave-non-boolean`), so a buggy guard fails safe rather than waving you through by accident. The guard runs on **every** way out — a link click, a programmatic `:rf.route/navigate`, the browser Back button. There's no unguarded side door. The full pending-nav protocol lives in [Spec 012](../../../spec/012-Routing.md).

When the guard blocks, the runtime parks the blocked navigation in a **pending-navigation slot** and leaves the decision to your UI. The UI reads it from the `:rf/pending-navigation` sub:

```clojure
;; src/conduit/core.cljs — rendered once in the app shell.
;; cf. examples/reagent/realworld_resources/core.cljs
(reg-view pending-nav-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay
     [:div.pending-nav-dialog
      [:p "You have unsaved changes. Leave anyway?"]
      [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard changes"]
      [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]]]))
```

`:rf.route/continue` re-issues the original navigation, skipping the guard this one time. `:rf.route/cancel` clears the slot and stays put. The blocked navigation is, once again, *data*: a map you can subscribe to, assert on in a test, and see in Xray — not a `window.confirm` buried in router internals.

Now re-read `:editor/replied` above and notice the choreography. On a successful save it re-seeds the editor from the saved article *before* navigating, so `:editor/dirty?` is `false` and the guard waves the navigation through. Type into the editor, hit Back — dialog. Publish — clean navigation to your new article, lists already refreshing behind you. (The example's submit gate materialises "valid and dirty" as a [flow](../concepts/flows.md) shared by the button and the handler.)

Everything in this part is running code: [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/) is the full app, including the pieces we trimmed for space (edit mode's load-and-seed, article delete, comments, follow/unfollow, the editor's field markup).

---

**You can now:**

- register a mutation whose `:invalidates` (per-scope descriptors) and `:populates` (authoritative seed) declare the write's cache consequences once, at the registration — across scopes via a named scope resolver;
- fire writes with `:rf.mutation/execute` and render their lifecycle from the instance-keyed `[:rf.mutation/state …]` sub — concurrency-safe, with no app-db flags;
- continue a workflow after a write with `:reply-to` — an event target that receives the uniform reply map, only ever for accepted replies, after the cache has settled;
- block navigation away from unsaved work with a `:can-leave` guard and a dialog over `:rf/pending-navigation`.
