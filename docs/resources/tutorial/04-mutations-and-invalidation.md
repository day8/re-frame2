# Part 4: writes — favoriting, posting, invalidation

Parts 2 and 3 read server state. Now the app writes it — and a write makes other reads wrong. Favorite an article and three cached reads go stale at once: the article detail, every list it appears in, and your personal feed.

Wiring each write to "now refetch these reads" at the call site works until one call site forgets. re-frame2's answer is a [**mutation**](../glossary.md#mutation): a write registered once, with the reads it breaks declared on the registration. This part adds three things:

- a favorite heart whose **mutation** declares which cached reads it [invalidates](../glossary.md#invalidate), so the detail, the lists and your feed refresh with no wiring at the call site;
- a publish button that saves an article and then navigates to it, via a **`:reply-to` event** rather than a callback;
- a **`:can-leave` route guard** that stops you navigating away from a half-written draft.

??? info "Coming from RTK Query or TanStack Query?"

    A mutation here is RTK Query's mutation with `invalidatesTags`, with three differences: invalidation is declared once on the registration, not per call site; every invalidation is **scoped**, so a write names which users' caches it touches; and the post-write continuation is a dispatched [event](../../core/glossary.md#event), not an `onSuccess` callback.

## The reads, ready to be broken

Part 2 already labelled the reads. Each resource declares [`:tags`](../glossary.md#cache-tag) on its data: the article detail carries `[:article slug]`, and the lists carry `[:article-list]` plus one `[:article slug]` per article they contain. A write can then say "I made `[:article slug]` stale", and the runtime finds every read carrying that tag without the write naming any of them.

One read is still missing: the **personal feed** (`GET /articles/feed`). Part 3 scoped the article reads by *viewer*; the feed goes further — it exists only for a signed-in user, and it's a different list for each. That's a **session** [scope](../glossary.md#scope): one per signed-in user, and none when nobody is.

Add a second scope resolver beside Part 3's `:conduit/viewer`:

```clojure
;; add to src/conduit/scope.cljc
(rf/reg-resource-scope :conduit/session
  {:doc    "The signed-in user's own reads — nil when logged out (fail-closed)."
   :inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/session {:username username}])))
```

Then register `:conduit/feed` like Part 2's list, but with `:scope {:from-db :conduit/session}`, so each signed-in user gets their own entries:

```clojure
;; add to src/conduit/resources.cljc
(rf/reg-resource :conduit/feed
  {:params-schema  [:map]
   :scope          {:from-db :conduit/session}
   :stale-after-ms 60000
   :gc-after-ms    300000
   :tags           (fn [_params data]
                     (into #{[:feed]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [_params _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles/feed")}
     :decode  :json}))
```

Sign-out now has a second scope to clear, so extend Part 3's `:auth/logout` to resolve and clear both:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} _]
    (let [old-viewer  (rf/resolve-resource-scope db :conduit/viewer)
          old-session (rf/resolve-resource-scope db :conduit/session)]
      {:db (assoc db :auth {:user nil :token nil})
       :fx (cond-> [[:auth.session/persist {:token nil}]]
             old-viewer  (conj [:dispatch [:rf.resource/clear-scope {:scope old-viewer :cause :logout}]])
             old-session (conj [:dispatch [:rf.resource/clear-scope {:scope old-session :cause :logout}]])
             true        (conj [:dispatch [:rf.route/navigate {:to :conduit/home}]]
                               [:dispatch [:rf.route/replan-resources {:cause [:logout]}]]))})))
```

As with the viewer resolver, returning `nil` when logged out fails closed: the feed read fails rather than serving the previous user's feed.

## Register the write

A mutation is the write-side counterpart of a resource: it describes a write and what that write makes stale. Here is the favorite write — two consequence keys plus a request fn:

```clojure
;; src/conduit/mutations.cljc
;; cf. examples/real-apps/realworld_resources/mutations.cljs
(ns conduit.mutations
  (:require [re-frame.core :as rf]
            [re-frame.resources]      ;; reg-mutation + the :rf.mutation/* surface
            [re-frame.http.managed]   ;; the transport mutations lower through
            [conduit.api :as api]
            [conduit.scope]))         ;; the :conduit/viewer and :conduit/session resolvers

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   ;; Seed the signed-in viewer's cached article detail from the write's own
   ;; reply — the heart flips the moment the server confirms.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope {:from-db :conduit/viewer}}
                     result})
   ;; The reads this write breaks: the article + lists (viewer scope), and the
   ;; signed-in user's feed (session scope).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope {:from-db :conduit/viewer}
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (str api/api-base "/articles/" slug "/favorite")}
     :decode  :json}))
```

Three parts do the work:

- **The request fn** (the third argument) describes the HTTP write the way a resource describes its read. As with resources, the runtime decides where the reply goes, so it must not supply `:on-success`, `:on-failure` or `:request-id`.
- **`:invalidates`** declares which tags the write makes stale on success. Favoriting breaks reads in *two* scopes — the article and lists in the reader's viewer scope, the feed in their session scope — so it returns a vector of *descriptors*, one per scope, each naming a scope and the tags to stale there. Each scope is resolved when the write's reply arrives.
- **`:populates`** writes the reply straight into an exact cache entry, before the invalidation runs. The favorite endpoint replies with the full updated article, so it seeds this reader's `:conduit/article` entry and skips a refetch. The value must be in the shape the resource stores — the whole `{:article …}` map the server sent (`result`), not the article inside it. A populated entry counts as freshly loaded, so this mutation's own invalidation doesn't refetch it.

Register `:conduit/unfavorite` the same way, with `:method :delete`.

!!! warning "Gotcha — name the scope each tag lives in"

    A bare tag set — `(fn [_ _] #{[:feed]})` — invalidates only in the mutation's own scope, which defaults to `:rf.scope/global`. The feed lives in the session scope, so the invalidation would silently match nothing and the feed would stay stale. Use one descriptor per scope, as above. Dev builds warn with `:rf.warning/mutation-scope-mismatch` when this happens ([The scope footgun](../how-to/invalidate-after-a-mutation.md#the-scope-footgun-and-how-to-disarm-it)).

Retries are opt-in for every managed request, and for a write that matters: re-sending a POST because the reply was slow is the double-submit bug. The favorite declares no `:retry`, so a slow favorite waits and never fires twice.

??? info "Coming from RTK Query?"

    `:invalidates` is `invalidatesTags`, and `:populates` is `updateQueryData` / `upsertQueryData` — except both live once on the registration, not in an `onQueryStarted` per call.

## Fire it, watch the instance

A resource is a subscription you read and a cause you fire. A mutation is the mirror image: **an event you fire and an instance you watch.** The UI dispatches `:rf.mutation/execute`:

```clojure
;; src/conduit/views.cljc — .cljc: nothing here touches the browser
;; cf. examples/real-apps/realworld_resources/views.cljs
(ns conduit.views
  (:require [re-frame.core :as rf]
            [conduit.mutations]))

(rf/reg-event :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    (if (nil? (get-in db [:auth :user]))
      ;; Logged out, a favorite click goes to login instead of a 401.
      {:fx [[:dispatch [:rf.route/navigate {:to :conduit.auth/login}]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if favorited? :conduit/unfavorite :conduit/favorite)
                         :params   {:slug slug}
                         :instance [:favorite slug]
                         :cause    [:click :ui/favorite slug]}]]]})))

(rf/reg-view favorite-button [{:keys [article]}]
  (let [{:keys [slug favorited favoritesCount]} article
        fav @(subscribe [:rf/mutation {:instance [:favorite slug]}])]
    [:button.btn.btn-outline-primary.btn-sm
     {:type     "button"
      :class    (when favorited "active")
      :disabled (:pending? fav)
      :on-click #(dispatch [:ui/favorite slug favorited])}
     [:i.ion-heart] " " favoritesCount]))
```

Mutation state is keyed by **instance**, not by mutation id. `[:favorite slug]` gives every article card its own lifecycle, so clicking hearts on three cards in quick succession can't mix them up.

The view watches its instance through the `[:rf/mutation {:instance …}]` subscription, which returns the instance's facts plus derived booleans:

```clojure
{:status :idle      ;; :idle | :pending | :success | :error
 :result …          ;; the decoded reply value, on success
 :error  …          ;; the structured error envelope, on failure
 :affected-keys […] ;; the cache keys this write touched
 :pending? … :success? … :error? … :settled? … :optimistic?}
```

That's where `:disabled (:pending? fav)` comes from — no `:saving?` flag in app-db. (`:optimistic?` belongs to the optimistic variant under [Advanced](#advanced).)

When a write fails, the instance settles `{:status :error, :error <failure-map>}`, and a view shows it by reading the same instance: `(when (:error? fav) [error-banner (:error fav)])`. The failure map has the same closed `:rf.http/*` shape as a resource's `:error` ([Part 2](02-server-data.md)). A heart can ignore a failure — the count just doesn't move — but a form shouldn't.

The view never invalidates anything. Put the button on the article page — require `[conduit.views :as views]` in `articles.cljs` and add it to the banner — and on the article cards too if you like:

```clojure
;; in article-page (src/conduit/articles.cljs)
       [:div.banner [:div.container
                     [:h1 (:title article)]
                     [views/favorite-button {:article article}]]]
```

Favoriting behaves the same everywhere, because the consequences live on the write.

??? info "Coming from RTK Query?"

    `[:rf/mutation {:instance …}]` is what `useMutation` hands back — `isLoading`, `isSuccess`, `error`, `data` — but keyed by an `:instance` id you choose rather than bound to one component. Two views can watch the same write, and a write survives the unmount of the component that fired it.

### Watch it happen

Sign in and click a heart. The count changes when the server replies — that's `:populates` — and a moment later the lists and your feed refetch. In [Xray](../../core/glossary.md#xray), click another heart and follow the chain: the `:ui/favorite` dispatch, `:rf.mutation/started`, the HTTP request, `succeeded` carrying the invalidation evidence (which tags went stale, in which scopes), and the refetches of stale reads still on screen. When a list refreshes "by itself" months from now, this trace tells you which write did it.

### Resetting an instance

`:rf.mutation/execute` takes a few more keys than the three used here (`:scope`, `:reply-to`, `:optimistic?`), and there are narrower subs such as `[:rf.mutation/pending? {:instance …}]`; [Invalidate after a mutation](../how-to/invalidate-after-a-mutation.md#the-rfmutationexecute-payload-and-the-focused-rfmutation-subs) lists them. One more command matters for the editor below: **`:rf.mutation/clear`** — `[:rf.mutation/clear {:instance [:favorite slug]}]` — drops an instance back to `:idle` and best-effort aborts any in-flight work for it. That abort makes it a *cancellation*: a write still in flight loses its request (with no proof the server didn't perform it), and its late reply is suppressed, so its `:invalidates` never run. So clear an instance after its write has settled, and when a form needs a fresh start while an earlier write may still be out, give the new session its own instance id instead. (`:rf.mutation/clear` is an event; `(rf/clear :mutation id)` is a different thing — it removes the registration.)

!!! warning "Gotcha — `:result` on the instance, `:value` in a reply"

    The instance sub stores the decoded result under `:result`. The [reply map](../glossary.md#reply-map) a `:reply-to` continuation receives (next section) spells the same value `:value`.

## Publish from the editor — and continue with `:reply-to`

Watching an instance is right for *rendering*. But a successful save also has to **drive workflow** — navigate to the new article, reset the form. With promises you'd `await` the POST and then navigate; here the next step is named on the execute call, as `:reply-to`.

First, the write. Create and edit share one mutation that switches between POST and PUT on whether a slug exists yet:

```clojure
;; src/conduit/mutations.cljc
;; cf. examples/real-apps/realworld_resources/article_editor.cljs
(rf/reg-mutation :conduit/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug)."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   ;; Lists always go stale; an edit also stales its own detail entry. A new
   ;; article has no prior slug — its detail loads fresh on navigate.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope {:from-db :conduit/viewer}
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug] :as draft} _ctx]
    {:request {:method (if slug :put :post)
               :url    (str api/api-base (if slug (str "/articles/" slug) "/articles"))
               :body   {:article (select-keys draft [:title :description :body :tagList])}}
     :decode  :json}))
```

The editor's app-db slice is a form in Part 3's style: a `:draft` the inputs edit, plus a `:baseline` (the article as loaded, or blank) to tell whether anything changed. There's no `:status` field: the submission lifecycle Part 3 hand-rolled lives on the mutation instance instead.

```clojure
;; src/conduit/editor.cljs
;; cf. examples/real-apps/realworld_resources/article_editor.cljs
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

;; Each visit to the editor is its own form session, named by the
;; navigation's token — so each session watches a fresh instance.
(defn save-instance [nav-token] [:editor/save nav-token])

;; The editor route's :on-match (registered below): a fresh slice. There's
;; no instance to clear — this visit's token names a new one — so a save
;; still in flight from an earlier visit runs to completion.
(rf/reg-event :editor/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))}))

;; The form watches this visit's instance:
;; [:rf/mutation {:instance @(subscribe [:editor/save-instance])}]
(rf/reg-sub :editor/save-instance {:inputs [[:rf/route]]}
  (fn [[route] _] (save-instance (:nav-token route))))
```

The token comes from the route slice: `:rf/route` carries a fresh [nav-token](../../routing/glossary.md#nav-token) for every navigation. A handler asks for the same token with `:rf.cofx/requires`, as Part 3's boot handler asked for the saved session token.

Submit validates, then fires the mutation, naming the continuation — which carries the token too:

```clojure
(rf/reg-event :editor/submit
  {:rf.cofx/requires [:rf.route/nav-token]}
  (fn [{:keys [db] :rf.route/keys [nav-token]} _]
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
                           :instance (save-instance nav-token)
                           :reply-to [:editor/replied nav-token]
                           :cause    [:submit :editor/save]}]]]}))))
```

When the runtime accepts the write's reply, it dispatches `[:editor/replied nav-token reply]` — your event target, with one canonical **reply map** appended as the final argument:

```clojure
(rf/reg-event :editor/replied
  {:rf.cofx/requires [:rf.route/nav-token]}
  (fn [{:keys [db] :rf.route/keys [nav-token]} [_ issued-under {:keys [status value instance]}]]
    (cond
      ;; Issued under a visit the reader has since left: not this page's to
      ;; act on. The instance is this write's own, so retire it and stop.
      (not= issued-under nav-token)
      {:fx [[:dispatch [:rf.mutation/clear {:instance instance}]]]}

      ;; Failure already shows on the form via the instance's :error state.
      (not= :ok status)
      {}

      ;; The save replies with the saved article: re-seed the editor so the
      ;; draft is CLEAN (the :can-leave guard below will let us go), clear
      ;; the instance, and navigate.
      :else
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance instance}]]
              [:dispatch [:rf.route/navigate {:to :conduit.article/show :params {:slug (:slug article)}}]]]}))))
```

`{:keys [status value instance]}` is the reply map's shape: `:status` says how the write settled, `:value` carries the decoded result on `:ok`, and `:instance` names the instance — the same [uniform reply](../../core/glossary.md#the-uniform-reply) every managed async operation produces. The token check comes first because a write runs to completion wherever the reader goes, and a save that answers after they've left the editor mustn't pull them back. Either way, retiring the instance is the last step.

Three rules make `:reply-to` dependable:

- **You only see accepted, terminal replies.** `:status` is `:ok`, `:error` or `:cancelled`. A stale reply — from an attempt superseded under the same instance, or cleared — is suppressed and never reaches your handler, so a slow first response can't overwrite a faster second one.
- **The continuation runs after the cache is updated.** Populate and invalidate run first, the instance settles, then `:reply-to` dispatches. By the time `:editor/replied` runs, the lists are already stale and refetching.
- **Workflow goes in `:reply-to`; cache consequences go on the registration.** Navigating and showing a toast are continuation; which reads the write broke is `:invalidates` / `:populates`. Don't invalidate tags from a continuation.

Because `[:editor/replied nav-token]` is an event vector rather than a closure, Xray can show it (the mutation's `replied` trace op is that dispatch), a test can assert it, and replay can re-run it. [Why no await: continuations are data](../../async/continuations-are-data.md) makes the full argument.

??? info "From re-frame v1"

    `:reply-to` is your `:on-success`/`:on-failure` pair collapsed into one stale-safe target with a uniform reply map ([From re-frame v1](../../core/25-from-re-frame-v1.md)).

## Guard the half-written draft

Write half an article, click the site logo, and the draft vanishes. A [`:can-leave` guard](../../routing/glossary.md#route-guard) closes that gap: a subscription the router consults before navigating away. It mirrors Part 3's `:can-enter`:

```clojure
;; src/conduit/editor.cljs
(rf/reg-sub :editor/dirty?
  (fn [db _]
    (let [{:keys [draft baseline]} (:editor db)]
      (not= draft baseline))))

(rf/reg-sub :editor/can-leave? {:inputs [[:editor/dirty?]]}
  (fn [[dirty?] _] (not dirty?)))

;; also src/conduit/editor.cljs — the editor's route: signed-in to enter, clean to leave.
;; Add [conduit.editor] to core.cljs's requires so these registrations load.
(rf/reg-route :conduit.editor/new
  {:tags      #{:requires-auth}
   :can-enter [:conduit/signed-in?]
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]}
  "/editor")
```

(The example adds the `/editor/:slug` edit route the same way — same guard; its `:on-match` seeds the draft from the article read.)

As with `:can-enter`, `true` allows and `false` blocks; anything else blocks *and* raises `:rf.error/can-leave-non-boolean`. The guard runs on every way out — a link click, a programmatic `:rf.route/navigate`, the Back button.

The two guards differ in what a refusal does. "Is this visitor signed in?" is a question for application state, so a `:can-enter` refusal is final. "Discard your draft?" is a question for the *user*, so a `:can-leave` block parks the navigation in a **pending-navigation slot** and waits. Your UI reads it from the `:rf/pending-navigation` sub:

```clojure
;; src/conduit/core.cljs — rendered once in the app shell.
;; cf. examples/real-apps/realworld_resources/core.cljs
(reg-view pending-nav-dialog []
  (when-let [pending @(rf/subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay
     [:div.pending-nav-dialog
      [:p "You have unsaved changes. Leave anyway?"]
      [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard changes"]
      [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]]]))
```

Both buttons pass the pending navigation's `:id`. `:rf.route/continue` re-issues the original navigation, skipping the guard once; `:rf.route/cancel` clears the slot and stays put. (A stale id is a safe no-op.) [Guard against unsaved changes](../../routing/how-to/guard-unsaved-changes.md) has the full recipe, including "save and close".

Now re-read `:editor/replied`: on a successful save it re-seeds the editor from the saved article *before* navigating, so `:editor/dirty?` is `false` and the guard lets the navigation through. Type into the editor and press Back — dialog. Publish — a clean navigation to your new article, with the lists already refreshing.

??? info "For JavaScript developers"

    A `:can-leave` guard plays the role of React Router's `useBlocker`. Instead of calling `blocker.proceed()` / `blocker.reset()` from a component, the parked navigation is a value under `:rf/pending-navigation`, and `:rf.route/continue` / `:rf.route/cancel` are ordinary events — so the dialog is a view that tests like any other.

[`examples/real-apps/realworld_resources/`](../../../examples/real-apps/realworld_resources) is the full app, including the pieces trimmed here: edit mode, article delete, comments, follow/unfollow, and the editor's field markup.

## Advanced

### Make the heart flip before the reply

`:populates` runs on success, so the heart flips when the server confirms. For a small, reversible change like a favorite you usually want it to flip *on click*, and flip back if the write fails. That's an [**optimistic mutation**](../glossary.md#optimistic-update--rollback), and it's one more registration key.

`:optimistic-tags` is the tag-addressed twin of `:invalidates`: where `:invalidates` says "these tags went stale", `:optimistic-tags` says "patch every entry carrying these tags now, before the request". The patch has to cope with both stored shapes — the detail's `{:article …}` and a list's `{:articles […]}`:

```clojure
;; cf. examples/real-apps/realworld_resources/mutations.cljs
(defn- toggle-fav [favorited? article]
  (some-> article
          (assoc :favorited favorited?)
          (update :favoritesCount (fn [n] (max 0 (+ (or n 0) (if favorited? 1 -1)))))))

(defn favorite-patch
  "Flip one article's heart inside any cached entry that shows it."
  [favorited? slug data]
  (cond-> data
    (contains? data :article)  (update :article #(toggle-fav favorited? %))
    (contains? data :articles) (update :articles
                                       (fn [as] (mapv #(if (= slug (:slug %)) (toggle-fav favorited? %) %) as)))))

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article (optimistic). POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   ;; FORWARD: flip the heart on every entry tagged [:article slug] — the
   ;; detail and every list — and in the feed, before the request goes out.
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope {:from-db :conduit/viewer}
                        :tags  #{[:article slug]}
                        :patch (fn [data] (favorite-patch true slug data))}
                       {:scope {:from-db :conduit/session}
                        :tags  #{[:feed]}
                        :patch (fn [data] (favorite-patch true slug data))}])
   ;; COMMIT on :ok — the reply's authoritative article overwrites the guess.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope {:from-db :conduit/viewer}}
                     result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope {:from-db :conduit/viewer} :tags #{[:article slug] [:article-list]}}
                     {:scope {:from-db :conduit/session} :tags #{[:feed]}}])
   :on-conflict   :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str api/api-base "/articles/" slug "/favorite")}
     :decode  :json}))
```

You write only the forward patch; the runtime does the rest:

- **It records the inverse.** Before patching, it snapshots each touched entry, so a rollback restores exactly what was there.
- **The reply settles it.** An `:ok` reply commits: `:populates` overwrites the optimistic value with the server's article, then `:invalidates` refetches the lists. An `:error` reply rolls back, and the heart flips back everywhere.
- **A contested rollback refetches instead of clobbering.** If another write changed a touched entry in the meantime, restoring your snapshot would overwrite newer data, so `:on-conflict :invalidate` (the default) marks that entry stale and refetches it. (TanStack and SWR restore the snapshot unconditionally.)

In the view, drop `:disabled (:pending? fav)` — the user already sees their change — and optionally read `(:optimistic? fav)`, true while the unconfirmed value is showing. When a write touches exactly one known entry, the exact-target sibling key is `:optimistic`.

??? info "Coming from TanStack / RTK / SWR?"

    This is TanStack's `onMutate` + `onError` rollback, RTK's `updateQueryData` + undo patch, or SWR's `optimisticData` + `rollbackOnError` — except the inverse is recorded by the runtime, and the apply and settle appear on the trace (`:rf.mutation/optimistic-applied` → `optimistic-reconciled` / `optimistic-rolled-back`).

### More cache consequences

[Invalidate after a mutation](../how-to/invalidate-after-a-mutation.md) covers what a write can do beyond `:populates` and `:invalidates`:

- [`:patches` and `:removes`](../how-to/invalidate-after-a-mutation.md#4-optional-the-other-cache-consequences) — transform an existing entry in place, or evict one after a delete.
- [`:invalidate-timing`](../how-to/invalidate-after-a-mutation.md#when-the-invalidation-fires-invalidate-timing) — invalidate before the request, on failure, or on either outcome.
- [`:refetch-populated?`](../how-to/invalidate-after-a-mutation.md#5-optional-seed-the-cache-from-the-reply) — refetch a populated entry when the reply is only part of the record.
- [`:cross-scope? true`](../how-to/invalidate-after-a-mutation.md#when-you-cant-name-the-scopes-cross-scope-true) — the audited sweep for scopes you can't name.
- [Optimistic writes](../how-to/invalidate-after-a-mutation.md#advanced-optimistic-writes) — the full settle contract, including `:optimistic` and `:on-conflict :force`.
