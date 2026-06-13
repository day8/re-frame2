# Part 4: writes — favoriting, posting, invalidation

In [Part 2](02-server-data.md) your app *read* server state through resources, and in [Part 3](03-auth-and-forms.md) you added login. Now Conduit gets its writes: the favorite heart on every article card, and the editor's **Publish Article** button. By the end of this part:

- clicking the heart fires a **mutation** whose registration declares which cached reads it breaks — detail, lists, and your personal feed all refresh with **no wiring at the call site**;
- publishing an article saves it and then *continues* — navigate to the new article, clear the form — via a **`:reply-to` event**, not a callback;
- navigating away from a half-written draft is blocked by a **`:can-leave` route guard** and a confirm dialog.

> **Coming from RTK Query or TanStack Query?** A mutation here is RTK Query's mutation with `invalidatesTags`, with three differences: invalidation is declared once on the write's *registration*, not per call site; every invalidation is **scoped** — your feed and another user's feed are different cache entries, and a write names which scopes it touches; and the post-write continuation is a dispatched **event**, not an `onSuccess` callback.

The idea this part lands:

**A mutation's `:reply-to` is the continuation — on the record, inspectable, replayable.**

## The reads, ready to be broken

In Part 2, each resource declared `:tags` on its cached data: the article detail carries `[:article slug]`; the lists carry `[:article-list]` plus a tag per article they contain. Those tags were planted for this moment — they are the join key between writes and reads.

One read is still missing: the **personal feed** (`GET /articles/feed`). Part 2 left it out because what it returns depends on *who is asking*, so its cache must be keyed per user. That key is a **named scope resolver**:

```clojure
;; src/conduit/scope.cljs
;; cf. examples/reagent/realworld_resources/scope.cljs
(rf/reg-resource-scope :conduit/session
  {:doc     "The session's cache scope — nil when logged out (fail-closed)."
   :inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Now register `:conduit/feed` exactly like Part 2's resources — tagged `#{[:feed]}` — but with `:scope {:from-db :conduit/session}` instead of `:rf.scope/global`. Its cache entries are keyed by the signed-in username; signed out, the scope resolves `nil` and the read fails closed — never a silent serving of the previous user's feed.

## Register the write

A mutation is the write-side counterpart of a resource, registered with `reg-mutation`:

```clojure
;; src/conduit/mutations.cljs
;; cf. examples/reagent/realworld_resources/mutations.cljs
(ns conduit.mutations
  (:require [re-frame.core :as rf]
            [re-frame.resources]      ;; reg-mutation + the :rf.mutation/* surface
            [re-frame.http-managed]   ;; the transport mutations lower through
            ;; Part 3's api base in a helper: (defn full-url [path] (str api path))
            [conduit.http :as rh]
            [conduit.schema :as schema]))

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post
                               :url    (rh/full-url (str "/articles/" slug "/favorite"))}
                     :decode  schema/ArticleResponse})
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
                      :tags  #{[:feed]}}])})
```

Three keys do the work:

- **`:request`** describes the HTTP write like a resource's read — and it must *not* supply `:on-success` / `:on-failure` / `:request-id`; the runtime owns reply addressing, and that's what makes stale-reply suppression possible below. One asymmetry from reads: **writes never retry by default**. Re-sending a POST because the reply was slow is the classic double-submit bug, so a mutation retries only if its `:request` explicitly opts in (this one doesn't).
- **`:invalidates`** declares which tags the write makes stale on success. A single-scope write can use a bare tag set — `#{[:article slug]}` — but favoriting breaks reads in *two* scopes: the article and lists are global, your feed is keyed by session. So it returns a vector of descriptors, each naming its own scope, the second through the `:conduit/session` resolver above, resolved at settle time. One write, both scopes, declared once.
- **`:populates`** seeds an exact cache entry from the write's own reply, *before* the invalidation runs. The favorite endpoint replies with the full updated article, so we write it straight into the `:conduit/article` entry — the populated value must be the resource's stored shape (the same `{:article …}` envelope a normal load produces — hence `result` whole). A populated entry counts as freshly loaded, so this mutation's own invalidation won't refetch the key it just learned.

Register `:conduit/unfavorite` the same way — same shape, `:method :delete`. The full registration surface lives in [Spec 016](../../../spec/016-Resources.md).

> **Honest limits.** `:populates` is a *forward-only* seed — optimistic rollback is a deferred feature, not a current one. Here that's harmless: populate runs only on success. But don't reach for populate expecting TanStack-style optimistic updates that revert on failure; that shape isn't available yet.

## Fire it, watch the instance

If a resource is "a sub you read and a cause you fire," a mutation is **a cause you fire and an instance you watch**. The UI never calls the mutation directly — it dispatches `:rf.mutation/execute`:

```clojure
;; src/conduit/views.cljs
;; cf. examples/reagent/realworld_resources/views.cljs
(rf/reg-event-fx :ui/favorite
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

The `:instance` id is the part worth pausing on. Mutation state is keyed by **instance**, not by mutation id — `[:favorite slug]` gives every article card its own lifecycle, so hearts clicked on three cards in quick succession can never clobber each other. The view watches its instance through the passive `[:rf.mutation/state {:instance …}]` sub, which returns `{:pending? :success? :error? :settled? :result :error}` — that's where `:disabled (:pending? fav)` comes from. No `app-db` bookkeeping, no `:saving?` flag to maintain.

Notice what the view *doesn't* do: it never invalidates anything. Add this button to the article cards from Part 1 and to the article page, and you're done — favoriting behaves identically everywhere, because the write's consequences live on the write.

### Watch it happen

Run the app, sign in, and click a heart. The count changes immediately (that's `:populates` landing), and a moment later the list and your feed have refetched. Now open Xray, click another heart, and read the causal chain off the trace: the `:ui/favorite` dispatch, then `:rf.mutation/started`, the HTTP request, then `succeeded` carrying the per-descriptor invalidation evidence (which tags, in which scopes), then the refetches of the reads a route still owns. Every step names its cause. When a list refreshes "by itself" six months from now, this trace is how you'll know which write did it.

## Publish from the editor — and continue with `:reply-to`

Watching an instance is right for *rendering* — the button disables itself. But a successful save usually has to **drive workflow**: navigate to the new article, clear the form. Those are causes, not renders. In Promise-land you'd `await` the POST and then navigate; here the continuation is a declared part of the execute call: `:reply-to`.

First, the write — create and edit share one mutation that switches POST/PUT on whether a slug exists yet:

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
   :request       (fn [{:keys [slug] :as draft} _ctx]
                    {:request {:method (if slug :put :post)
                               :url    (rh/full-url (if slug (str "/articles/" slug) "/articles"))
                               :body   {:article (select-keys draft [:title :description :body :tagList])}}
                     :decode  schema/ArticleResponse})
   ;; Lists always go stale; an edit also stales its own detail entry. A new
   ;; article has no prior slug — its detail loads fresh on navigate.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])})
```

The editor's `app-db` slice is an ordinary form in Part 3's mold — a `:draft` the inputs edit — plus a `:baseline` (the article as loaded, or blank) so we can tell whether anything actually changed. Note what's *not* here: no `:status` field — the submission lifecycle Part 3 hand-rolled lives on the mutation instance instead.

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
(rf/reg-event-fx :editor/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance :editor/save}]]]}))
```

Submit validates, then fires the mutation — with the continuation named at the call site:

```clojure
(rf/reg-event-fx :editor/submit
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

When the runtime accepts the write's reply, it dispatches `[:editor/replied reply]` — your event target with one canonical **reply map** appended as the final argument:

```clojure
(rf/reg-event-fx :editor/replied
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

Three rules make `:reply-to` trustworthy:

- **You only ever see accepted, terminal replies.** The reply's `:status` is `:ok`, `:error`, or `:cancelled` — branch on it. A *stale* reply (the user re-submitted under the same instance, or something cleared it) is suppressed by the runtime and never reaches your handler. You cannot write the "slow first response overwrites the fast second one" bug here.
- **The continuation observes a settled world.** Phase order is fixed: populate and invalidate run first, the instance settles, *then* `:reply-to` dispatches. By the time `:editor/replied` runs, the lists are already marked stale and refetching.
- **Workflow goes in `:reply-to`; cache consequences go on the registration.** Navigate, toast, update a session — continuation. "Which reads did this break" — `:invalidates` / `:populates`, declared once. Don't invalidate tags from a continuation.

And the point this part exists to land: `[:editor/replied]` is **data**. It's not a closure awaiting a Promise — it's an event vector, sitting in the execute payload where Xray can show it (the mutation's `replied` trace op is that dispatch), where a test can assert it, and where replay can re-run it deterministically. The async workflow "save, then navigate" is on the record, step by step. That's the trade against `await`: slightly more ceremony, for a workflow you can inspect after the fact — [No await: continuations are data](../explanation/continuations-are-data.md) makes the full argument.

> **Coming from re-frame v1?** `:reply-to` is your `:on-success`/`:on-failure` pair collapsed into one stale-safe target with a uniform reply map — the same envelope every async family replies with ([From re-frame v1](../25-from-re-frame-v1.md)).

## Guard the half-written draft

One gap left: write half an article, click the site logo, and the draft silently vanishes. Routes close this with a `:can-leave` guard — a subscription, consulted by the router before navigating away:

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
  {:path      "/editor"
   :tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]})
```

(The example adds the `/editor/:slug` edit route the same way — same guard; its `:on-match` seeds the draft from the article read.)

The contract is strict: `true` allows the navigation, `false` blocks, and anything else blocks *and* emits a structured error (`:rf.error/can-leave-non-boolean`) — a buggy guard fails safe. The guard runs on **every** way out — a link click, a programmatic `:rf.route/navigate`, the browser Back button — there's no unguarded side door. The full pending-nav protocol lives in [Spec 012](../../../spec/012-Routing.md).

When the guard blocks, the runtime parks the blocked navigation in a **pending-navigation slot** and leaves the decision to your UI, which reads it from the `:rf/pending-navigation` sub:

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

`:rf.route/continue` re-issues the original navigation (skipping the guard this one time); `:rf.route/cancel` clears the slot and stays put. The blocked navigation is, once again, *data*: a map you can subscribe to, assert on in a test, and see in Xray — not a `window.confirm` buried in router internals.

Now re-read `:editor/replied` above and notice the choreography: on a successful save it re-seeds the editor from the saved article *before* navigating, so `:editor/dirty?` is `false` and the guard waves the navigation through. Type into the editor, hit Back — dialog. Publish — clean navigation to your new article, lists already refreshing behind you. (The example's submit gate materialises "valid and dirty" as a [flow](../concepts/flows.md) shared by the button and the handler.)

Everything in this part is running code: [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/) is the full app, including the pieces we trimmed for space (edit mode's load-and-seed, article delete, comments, follow/unfollow, the editor's field markup).

---

**You can now:**

- register a mutation whose `:invalidates` (per-scope descriptors) and `:populates` (authoritative seed) declare the write's cache consequences once, at the registration — across scopes via a named scope resolver;
- fire writes with `:rf.mutation/execute` and render their lifecycle from the instance-keyed `[:rf.mutation/state …]` sub — concurrency-safe, with no app-db flags;
- continue a workflow after a write with `:reply-to` — an event target that receives the uniform reply map, only ever for accepted replies, after the cache has settled;
- block navigation away from unsaved work with a `:can-leave` guard and a dialog over `:rf/pending-navigation`.

**Next:** [Part 5: test it, ship it](05-test-and-ship.md) — or, for the full argument behind `:reply-to`, [No await: continuations are data](../explanation/continuations-are-data.md).
