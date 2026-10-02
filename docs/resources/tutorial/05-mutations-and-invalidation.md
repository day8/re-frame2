# Part 5: writes — favoriting, posting, invalidation

Parts 2 to 4 read server state. Now the app writes it — and a write makes other reads wrong. Favorite an article and three cached reads go stale at once: the article detail, every list it appears in, and your personal feed.

Wiring each write to "now refetch these reads" at the call site works until one call site forgets. re-frame2's answer is a [**mutation**](../glossary.md#mutation): a write registered once, with the reads it breaks declared on the registration. This part adds three things:

- a favorite heart whose **mutation** declares which cached reads it [invalidates](../glossary.md#invalidate), so the detail, the lists and your feed refresh with no wiring at the call site;
- a publish button that saves an article and then navigates to it, via a **`:reply-to` event** rather than a callback;
- a **`:can-leave` route guard** that stops you navigating away from a half-written draft.

## Tag the reads

A write can only say which reads it broke if the reads say what they hold. That is the job of a [`:tags`](../glossary.md#cache-tag) key: a function of the read's params and its decoded data, returning the *facts* the data is about. In `src/conduit/resources.cljc`, add it to `:conduit/articles`'s metadata:

```clojure
   :tags           (fn [_params data]
                     (into #{[:article-list]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))
```

and to `:conduit/article`'s:

```clojure
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})
```

The article detail carries `[:article slug]`, and the list carries `[:article-list]` plus one `[:article slug]` per article it holds. A write can then say "I made `[:article slug]` stale", and the runtime finds every read carrying that tag — the detail, and any list showing that article — without the write naming any of them.

One read is still missing: the **personal feed** (`GET /articles/feed`). Part 4 scoped the article reads by *viewer*; the feed goes further — it exists only for a signed-in user, and it's a different list for each. That's a **session** [scope](../glossary.md#scope): one per signed-in user, and none when nobody is.

Add a second scope resolver beside Part 4's `:conduit/viewer`:

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
   :tags           (fn [_params data]
                     (into #{[:feed]}
                           (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [_params _ctx]
    {:request {:method :get
               :url    (str api/api-base "/articles/feed")}
     :decode  :json}))
```

Sign-out now has a second scope to clear, so extend Part 4's `:auth/logout` to resolve and clear both:

```clojure
(rf/reg-event :auth/logout
  (fn [{:keys [db]} [_ {:keys [generation]}]]
    (if (and generation (not= generation (:auth-generation db)))
      {}
      (let [old-viewer  (rf/resolve-resource-scope db :conduit/viewer)
            old-session (rf/resolve-resource-scope db :conduit/session)]
        {:db (-> db (update :auth-generation (fnil inc 0))
                  (assoc :auth {:user nil :token nil}))
         :fx (cond-> [[:auth.session/persist {:token nil}]]
             old-viewer  (conj [:dispatch [:rf.resource/clear-scope {:scope old-viewer :cause :logout}]])
             old-session (conj [:dispatch [:rf.resource/clear-scope {:scope old-session :cause :logout}]])
             true        (conj [:dispatch [:rf.route/navigate {:to :conduit/home}]]
                               [:dispatch [:rf.route/replan-resources {:cause [:logout]}]]))}))))
```

As with the viewer resolver, returning `nil` when logged out fails closed: the feed read fails rather than serving the previous user's feed.

## Register the write

A mutation is the write-side counterpart of a resource: it describes a write and what that write makes stale. Here is the favorite write — a request fn and one consequence key, `:invalidates`:

```clojure
;; src/conduit/mutations.cljc
;; cf. examples/real-apps/realworld_resources/mutations.cljs
(ns conduit.mutations
  (:require [re-frame.core :as rf]
            [re-frame.resources]      ;; reg-mutation + the :rf.mutation/* surface
            [re-frame.http.managed]   ;; the transport mutations use
            [conduit.api :as api]
            [conduit.scope]))         ;; the :conduit/viewer and :conduit/session resolvers

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
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

- **The request fn** (the third argument) describes the HTTP write the way a resource describes its read. As with resources, the runtime decides where the reply goes, so it must not supply `:on-success`, `:on-failure` or `:request-id`.
- **`:invalidates`** declares which tags the write makes stale on success. Favoriting breaks reads in *two* scopes — the article and lists in the reader's viewer scope, the feed in their session scope — so it returns a vector of *descriptors*, one per scope, each naming a scope and the tags to stale there. Each scope is resolved when the write's reply arrives.

!!! warning "Gotcha — name the scope each tag lives in"

    A bare tag set — `(fn [_ _] #{[:feed]})` — invalidates only in the mutation's own scope, which defaults to `:rf.scope/global`. The feed lives in the session scope, so the invalidation would silently match nothing and the feed would stay stale. Use one descriptor per scope, as above. Dev builds warn with `:rf.warning/mutation-scope-mismatch` when this happens ([The scope footgun](../how-to/invalidate-after-a-mutation.md#the-scope-footgun-and-how-to-disarm-it)).

Retries are opt-in for every managed request, and for a write that matters: re-sending a POST because the reply was slow is the double-submit bug. The favorite declares no `:retry`, so a slow favorite waits and never fires twice.

### Seed the cache from the reply

Invalidation refetches the article detail you're looking at, but the favorite endpoint already replies with the full updated article. `:populates` writes that reply straight into an exact cache entry and skips the refetch. Add it to `:conduit/favorite`'s metadata:

```clojure
   ;; Seed the viewer's cached article detail from the write's own reply.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope {:from-db :conduit/viewer}}
                     result})
```

The map's key names one exact entry — resource, params and scope — and its value is what to store there. The value must be in the shape the resource stores: the whole `{:article …}` map the server sent (`result`), not the article inside it. `:populates` runs before the invalidation, and a populated entry counts as freshly loaded, so this mutation's own invalidation doesn't refetch it.

Register `:conduit/unfavorite` the same way, with `:method :delete`.

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
    [:span
     [:button.btn.btn-outline-primary.btn-sm
     {:type     "button"
      :class    (when favorited "active")
      :disabled (:pending? fav)
      :on-click #(dispatch [:ui/favorite slug favorited])}
     [:i.ion-heart] " " favoritesCount]
     (when (:error? fav)
       [:span.error-messages " Could not save this favorite. Try again."])]))
```

Mutation state is keyed by **instance**, not by mutation id. `[:favorite slug]` gives every article card its own lifecycle, so clicking hearts on three cards in quick succession can't mix them up.

The view watches `[:rf/mutation {:instance [:favorite slug]}]`. It uses
`:pending?` to disable the button and `:error?` to show a failed attempt. The
[mutation subscription reference](../../api/re-frame.resources.md#mutation-subscriptions-passive)
records the full map and focused subscriptions; the
[execute reference](../../api/re-frame.resources.md#rfmutationexecute-)
lists the command's other options.

When a write fails, the instance settles `{:status :error, :error <failure-map>}`. The button above reads `:error?` to display a retry message. Read `:error` when different failures need different messages; it has the same closed `:rf.http/*` shape as a resource's error ([Part 2](02-server-data.md)).

The view never invalidates anything. Put the button on the article page — require `[conduit.views :as views]` in `articles.cljs` and add it to the banner — and on the article cards too if you like:

```clojure
;; in article-page (src/conduit/articles.cljs)
       [:div.banner [:div.container
                     [:h1 (:title article)]
                     [views/favorite-button {:article article}]]]
```

Favoriting behaves the same everywhere, because the consequences live on the write.

### Watch it happen

Sign in and click a heart. The count changes when the server replies — that's `:populates` — and a moment later the lists and your feed refetch. In [Xray](../../core/glossary.md#xray), click another heart and follow the chain: the `:ui/favorite` dispatch, `:rf.mutation/started`, the HTTP request, `succeeded` carrying the invalidation evidence (which tags went stale, in which scopes), and the refetches of stale reads still on screen. When a list refreshes "by itself" months from now, this trace tells you which write did it.

### Resetting an instance

The editor below needs one more command: `[:rf.mutation/clear {:instance [:favorite slug]}]` drops an instance back to `:idle`.

Clearing a write that is still in flight also cancels it: the runtime makes a best-effort abort of the request (the server may have performed the write anyway) and suppresses its late reply, so its `:invalidates` never run. So clear an instance after its write has settled. When a form needs a fresh start while an earlier write may still be out, give the new session its own instance id instead, as the editor does.

(`:rf.mutation/clear` is an event. `(rf/clear :mutation id)` is a different thing: it removes the registration.)

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

The editor's app-db slice is a form in Part 3's style: a `:draft` the inputs edit, plus `:submitted`, the version the server last accepted (the article as loaded, or blank for a new one), to tell whether anything changed. There's no `:status` field: the submission lifecycle Part 3 hand-rolled lives on the mutation instance instead. The namespace starts with plain helpers — build a slice, turn an article into a draft and back, validate:

```clojure
;; src/conduit/editor.cljs
;; cf. examples/real-apps/realworld_resources/article_editor.cljs
(ns conduit.editor
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.routing]
            [conduit.mutations]))

(def blank-draft {:title "" :description "" :body "" :tagList ""})

(defn editor-slice
  ([] (editor-slice nil blank-draft))
  ([slug submitted]
   {:slug slug :draft submitted :submitted submitted
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
```

The new idea is the instance id. A save can answer after the reader has left the editor, and it mustn't then act on a later visit's form. So each visit to the editor is its own form session, with its own instance, named by the navigation's token:

```clojure
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

The token comes from the route slice: `:rf/route` carries a fresh [nav-token](../../routing/glossary.md#nav-token) for every navigation. A handler asks for the same token with `:rf.cofx/requires`, which declares the non-secret navigation identity it needs. The secret JWT from Part 3 never travels through a recordable coeffect.

Submit validates, then fires the mutation, naming the continuation — which carries the token too:

```clojure
(rf/reg-event :editor/submit
  {:rf.cofx/requires [:rf.route/nav-token]}
  (fn [{:keys [db] :rf.route/keys [nav-token]} _]
    (let [{:keys [slug draft submitted]} (:editor db)
          errors (validate-draft draft)]
      (cond
        (seq errors)
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] errors))}

        (= draft submitted) {}  ;; valid but unchanged — nothing to save

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
      ;; draft is clean (the :can-leave guard below will let us go), clear
      ;; the instance, and navigate.
      :else
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance instance}]]
              [:dispatch [:rf.route/navigate {:to :conduit.article/show :params {:slug (:slug article)}}]]]}))))
```

`{:keys [status value instance]}` is the reply map's shape: `:status` says how the write settled, `:value` carries the decoded result on `:ok`, and `:instance` names the instance — the same [uniform reply](../../core/glossary.md#the-uniform-reply) every managed async operation produces. The token check comes first: a write runs to completion wherever the reader goes, and a save that answers after they've left the editor mustn't pull them back. Successful or obsolete visits retire their instance; a failure on the current visit keeps its error available for rendering and retry.

Three rules make `:reply-to` dependable:

- **You only see accepted, terminal replies.** `:status` is `:ok`, `:error` or `:cancelled`. A stale reply — from an attempt superseded under the same instance, or cleared — is suppressed and never reaches your handler, so a slow first response can't overwrite a faster second one.
- **The continuation runs after the cache is updated.** Populate and invalidate run first, the instance settles, then `:reply-to` dispatches. By the time `:editor/replied` runs, the lists are already stale and refetching.
- **Workflow goes in `:reply-to`; cache consequences go on the registration.** Navigating and showing a toast are continuation; which reads the write broke is `:invalidates` / `:populates`. Don't invalidate tags from a continuation.

Because `[:editor/replied nav-token]` is an event vector rather than a closure, Xray can show it (the mutation's `replied` trace op is that dispatch), a test can assert it, and replay can re-run it. [Why no await: continuations are data](../../async/continuations-are-data.md) makes the full argument.

### Put the editor on screen

The events above own submission. Add the field event, subscription and view in
`editor.cljs` so there is a form to submit:

```clojure
(rf/reg-event :editor/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in [:editor :draft field] value)
             (update-in [:editor :errors] dissoc field))}))

(rf/reg-sub :editor/slice (fn [db _] (:editor db)))

(rf/reg-view editor-page []
  (let [{:keys [draft errors]} @(subscribe [:editor/slice])
        instance @(subscribe [:editor/save-instance])
        save @(subscribe [:rf/mutation {:instance instance}])]
    [:div.editor-page.container.page
     [:form {:no-validate true
             :on-submit #(do (.preventDefault %) (dispatch [:editor/submit]))}
      [:fieldset {:disabled (:pending? save)}
       (for [[field label] [[:title "Title"] [:description "Description"]
                            [:body "Article"] [:tagList "Tags, separated by commas"]]]
         ^{:key field}
         [:div.form-group
          [:label {:for (name field)} label]
          [(if (= field :body) :textarea.form-control :input.form-control)
           {:id (name field) :value (get draft field "")
            :on-change #(dispatch [:editor/edit-field field (.. % -target -value)])}]
          (when-let [error (get errors field)] [:p.error-messages error])])
       (when (:error? save)
         [:p.error-messages "Could not save. Your draft is still here; try again."])
       [:button.btn.btn-primary {:type "submit"}
        (if (:pending? save) "Publishing…" "Publish article")]]]]))
```

The fieldset prevents duplicate submissions while a save is pending. Field
errors clear as the reader edits; another submit validates the whole draft.

## Guard the half-written draft

Write half an article, click the site logo, and the draft vanishes. A [`:can-leave` guard](../../routing/glossary.md#route-guard) closes that gap: a subscription the router consults before navigating away. It mirrors Part 4's `:can-enter`:

```clojure
;; src/conduit/editor.cljs
(rf/reg-sub :editor/dirty?
  (fn [db _]
    (let [{:keys [draft submitted]} (:editor db)]
      (not= draft submitted))))

(rf/reg-sub :editor/can-leave? {:inputs [[:editor/dirty?]]}
  (fn [[dirty?] _] (not dirty?)))

;; also src/conduit/editor.cljs — the editor's route: signed-in to enter, clean to leave.
;; core.cljs will require this namespace and render editor-page below.
(rf/reg-route :conduit.editor/new
  {:tags      #{:requires-auth}
   :can-enter [:conduit/signed-in?]
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]}
  "/editor")
```

The tutorial adds the new-article route. The full example also has an edit route; it loads the article and seeds `:submitted` before rendering the same form.

As with `:can-enter`, `true` allows and `false` blocks; anything else blocks *and* raises `:rf.error/can-leave-non-boolean`. The guard runs on every way out — a link click, a programmatic `:rf.route/navigate`, the Back button.

The two guards differ in what a refusal does. "Is this visitor signed in?" is a question for application state, so a `:can-enter` refusal is final. "Discard your draft?" is a question for the *user*, so a `:can-leave` block parks the navigation in a **pending-navigation slot** and waits. Your UI reads it from the `:rf/pending-navigation` sub:

```clojure
;; src/conduit/core.cljs — rendered once in the app shell.
;; cf. examples/real-apps/realworld_resources/core.cljs
(reg-view pending-nav-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay
     [:div.pending-nav-dialog
      [:p "You have unsaved changes. Leave anyway?"]
      [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard changes"]
      [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]]]))
```

Finish the shell wiring in `core.cljs`: require `[conduit.editor :as editor]`,
add `:conduit.editor/new [editor/editor-page]` to `root-view`'s `case`, and render
`[pending-nav-dialog]` beside `[header]` (define the dialog before `root-view`).
Add `[rf/route-link {:to :conduit.editor/new} "New article"]` in the header's
signed-in branch. The editor now has an entry point, a page, and a visible way to
resolve a blocked navigation.

Both buttons pass the pending navigation's `:id`. `:rf.route/continue` re-issues the original navigation, skipping the guard once; `:rf.route/cancel` clears the slot and stays put. (A stale id is a safe no-op.) [Guard against unsaved changes](../../routing/how-to/guard-unsaved-changes.md) has the full recipe, including "save and close".

Now re-read `:editor/replied`: on a successful save it re-seeds the editor from the saved article *before* navigating, so `:editor/dirty?` is `false` and the guard lets the navigation through. Type into the editor and press Back — dialog. Publish — a clean navigation to your new article, with the lists already refreshing.

??? info "For JavaScript developers"

    A `:can-leave` guard plays the role of React Router's `useBlocker`. Instead of calling `blocker.proceed()` / `blocker.reset()` from a component, the parked navigation is a value under `:rf/pending-navigation`, and `:rf.route/continue` / `:rf.route/cancel` are ordinary events — so the dialog is a view that tests like any other.

[`examples/real-apps/realworld_resources/`](../../../examples/real-apps/realworld_resources) is the full app, including the pieces trimmed here: edit mode, article delete, comments, and follow/unfollow.

## Advanced

### Make the heart flip before the reply

`:populates` confirms the change when the server replies.
[Optimistic updates](../how-to/optimistic-updates.md) patches a loaded article
before sending the write and rolls it back on failure. Its
[tagged-list variant](../how-to/optimistic-updates.md#update-every-read-showing-the-article)
updates every read showing the same article.

Keep `:disabled (:pending? fav)` on the button so one control does not issue
overlapping writes. The optimistic change gives immediate feedback while the
server finishes the request.

### More cache consequences

`:patches` transforms an existing entry and `:removes` evicts one after a delete.
The [mutation reference](../../api/re-frame.resources.md#the-mutation-spec)
records those exact forms, invalidation timing and partial-reply policy.
[Scoped invalidation](../how-to/invalidate-after-a-mutation.md#the-scope-footgun-and-how-to-disarm-it)
matches a write's consequences to the reads it changes.
