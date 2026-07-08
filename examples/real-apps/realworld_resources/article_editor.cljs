(ns realworld-resources.article-editor
  "Create / edit / delete an article — the RealWorld-on-resources editor.

   This is the busiest page in the app, and a good one to study because it puts
   three distinct re-frame2 ideas to work side by side:

   1. The write is a mutation. `:realworld/save-article` POSTs `/articles` to
      create or PUTs `/articles/:slug` to edit, and declares per-target scoped
      `:invalidates` descriptors — the global article tags (`[:article slug]` /
      `[:article-list]`) in `:rf.scope/global`, and the session `[:feed]` in
      `{:from-db :realworld/session}`. So on success the detail read, every list
      showing the article, and the session feed all refetch with no further wiring
      — one mutation reaching across both scopes. `:realworld/delete-article`
      invalidates the same tags. The write lifecycle is the mutation INSTANCE,
      watched through `[:rf/mutation {:instance …}]`; there's no `:status`
      field in app-db.

   2. The can-submit gate is a flow. `:editor/can-submit?` materialises one
      derived fact — 'the draft is valid AND differs from the loaded baseline' —
      into app-db at `[:editor :can-submit?]`. The submit handler then reads it as
      plain data (no subscribing mid-handler), and the submit button reads the same
      value through a plain sub over the flow's `:output-path`. The flow is
      registered ONCE at boot from `:editor/register-flow` (dispatched by
      `:app/initialise`) via `:rf.fx/reg-flow`, so it binds to the boot frame and
      isn't re-registered on every editor entry. See the flow glossary:
      ../../../docs/core/glossary.md#flow.

   3. A navigation `:can-leave` guard. The editor route declares
      `:can-leave [:editor/can-leave?]`; a dirty draft blocks a navigate-away and
      the app shell pops a confirm dialog off the `:rf/pending-navigation` sub (see
      core.cljs). Clean (or just-saved) drafts leave freely. See route guard:
      ../../../docs/routing/glossary.md#route-guard.

   Both continuations this page needs are call-site `:reply-to` targets — the
   very idiom settings.cljs uses — so the view has no off-render reactions at all.
   The save / delete success continuation (navigate to the saved article, or home
   on a delete) is the mutation's `:reply-to [:editor/replied]`; the runtime
   dispatches `[:editor/replied reply]` once, AFTER `:invalidates` staled the lists
   and feed and the instance settled, and the continuation branches save-vs-delete
   on the reply value (save and delete share one instance, so they share one
   continuation). The seed-on-load continuation is the `:realworld/article`
   ensure's `:reply-to [:editor/article-loaded]` (rf2-p1yri7 — a resource read now
   gets the same causal completion continuation a mutation does): a cache-hit
   fires it immediately, a fetch fires it on settle, and a stale/superseded reply
   never fires it. Both are declarative, replayable causal events, not Form-3
   `reagent.ratom/run!` reactions watching a settle. The render bodies, as
   everywhere here, are pure functions of subs that never dispatch out of band."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            ;; The flows runtime. Loading it publishes the hooks so the
            ;; `:rf.fx/reg-flow` effect has something to resolve against; without
            ;; it, the effect raises.
            [re-frame.flows]
            [reagent.core :as r]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; DRAFT SHAPE + VALIDATION
;; ============================================================================

(def blank-draft
  {:title "" :description "" :body "" :tagList ""})

(defn draft-from-article [article]
  {:title       (:title article)
   :description (:description article)
   :body        (:body article)
   :tagList     (str/join ", " (:tagList article))})

(defn- parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- editor-slice
  "The form's app-db slice: the draft, the baseline (for dirty-detection), and the
   per-field validation bookkeeping. The submission lifecycle is deliberately not
   here — that's the `:editor/save` mutation instance."
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug              slug
    :draft             baseline
    :baseline          baseline
    :errors            {}
    :touched           #{}
    :submit-attempted? false}))

(defn- validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

(defn- article-body [draft]
  {:article {:title       (:title draft)
             :description (:description draft)
             :body        (:body draft)
             :tagList     (parse-tag-list (:tagList draft))}})

(def save-instance
  "The one stable instance id the editor form watches for the save write."
  :editor/save)

;; ============================================================================
;; THE FLOW — :editor/can-submit?
;; ============================================================================
;;
;; This materialises one derived boolean — 'the draft passes client-side
;; validation AND differs from the loaded baseline' — into app-db at
;; `[:editor :can-submit?]`. Why bother with a flow instead of a sub? Because the
;; `:editor/submit` handler wants to read this value as plain app-db data to gate
;; the submit, and a sub would force the handler to subscribe mid-handler, which is
;; awkward. The submit button, meanwhile, reads the very same materialised value
;; through a plain sub over the `:output-path`. One derived fact, two readers, no
;; duplication. See the flow glossary: ../../../docs/core/glossary.md#flow.
;;
;; It's registered ONCE at boot via `:rf.fx/reg-flow` from `:editor/register-flow`
;; (dispatched by `:app/initialise`, not at ns-load and not per route entry), so it
;; binds to the boot frame and lives as long as it — the same lifetime as the
;; `[:editor …]` slice it derives over. The flow's first walk fires on the next
;; drain; a fresh editor starts invalid and clean anyway, so that one-event lag
;; never carries a stale value.

;; The 3-slot triple `[flow-id metadata derive-fn]` (rf2-bqstzr) — the exact
;; shape both `reg-flow` and `:rf.fx/reg-flow` take, so `[:rf.fx/reg-flow
;; can-submit-flow]` splats it straight through.
(def can-submit-flow
  [:editor/can-submit?
   {:doc    "True when the editor draft is both valid AND dirty (differs from the
             loaded baseline). Materialised into app-db so the submit handler can
             read it as plain data."
    :inputs [[:editor :draft] [:editor :baseline]]
    :output-path [:editor :can-submit?]}
   (fn [draft baseline]
     (and (empty? (validate-draft draft))
          (not= draft baseline)))])

;; ============================================================================
;; THE WRITE — mutations (POST create / PUT edit / DELETE)
;; ============================================================================
;;
;; Saving an article invalidates the article detail and every list/feed that shows
;; it. The mutation declares those tags once, and the runtime takes care of
;; refetching the mounted readers. Create and edit share a single mutation: the
;; `:request` flips POST `/articles` ↔ PUT `/articles/:slug` on whether a `:slug`
;; is present, and the slug-bearing edit path invalidates its own detail entry too.

(rf/reg-mutation :realworld/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug) an
                   article. On success, invalidates the detail + lists + feed."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   :scope         :rf.scope/global
   ;; The lists (global scope) go stale so they re-read with the new article, and
   ;; an edit's slug also stales its own detail entry. A create has no prior slug,
   ;; so it stales only the lists — the new detail is read fresh on navigate
   ;; anyway. The session feed lives in the session scope, so it gets its own
   ;; per-target descriptor naming `{:from-db :realworld/session}`: one mutation,
   ;; reaching across both scopes.
   ;;
   ;; A third descriptor stales the author's OWN `:realworld/author-articles`
   ;; (My Articles) cache, keyed off the reply's `:article :author :username` —
   ;; the fact the decoded result carries, not `params` (a create has no `:slug`
   ;; to key against, and `[:article-list]` above only reaches the global feed's
   ;; list-identity tag, which `:realworld/author-articles` never carries — see
   ;; its `:tags` fn in resources.cljs). Without this, a freshly-created article
   ;; is invisible on My Articles until its cached page naturally goes stale.
   :invalidates   (fn [{:keys [slug]} result]
                    [{:scope :rf.scope/global
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}
                     {:scope :rf.scope/global
                      :tags  #{[:author-articles (get-in result [:article :author :username])]}}])}
  (fn [{:keys [slug] :as draft} _ctx]
    {:request {:method (if slug :put :post)
               :url    (rh/full-url (if slug
                                      (str "/articles/" slug)
                                      "/articles"))
               :body   (article-body (select-keys draft [:title :description :body :tagList]))}
     :decode  schema/ArticleResponse}))

(rf/reg-mutation :realworld/delete-article
  {:doc           "Delete an article. DELETE /articles/:slug. Invalidates the
                   detail + lists + feed."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Global article tags plus the session feed, each in its own scope (same shape
   ;; as :realworld/save-article above).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete
               :url    (rh/full-url (str "/articles/" slug))}
     ;; The delete endpoint returns no body, and `:auto` takes a 204/empty in its
     ;; stride.
     :decode  :auto}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :editor/register-flow
  {:doc "Boot-time one-shot: register the `:editor/can-submit?` flow ONCE against
         the boot frame. Dispatched from `:app/initialise` at boot — NOT per route
         entry. `:rf.fx/reg-flow` replaces by id, so re-registering on every
         `/editor` entry wouldn't stack copies — but it WOULD leave the flow
         registered after you leave the editor, quietly recomputing over the
         editor slice for the rest of the session. Registering once at boot (the
         flow lives as long as the frame, like the `[:editor …]` slice it derives
         over) is the honest singleton shape; each route entry then only resets
         the slice. (The http sibling reuses its same-named `:editor/initialise`
         as this boot one-shot; here `:editor/initialise` is the create-route
         `:on-match`, so the boot registration gets its own event.)"}
  (fn [_ _]
    {:fx [[:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/initialise
  {:doc "Create-mode entry (`:realworld.editor/new` `:on-match`). Resets the editor
         slice to a blank draft and clears any leftover save instance. Because
         edit and create share the one `editor-page` component (core.cljs), an
         edit -> new navigation never unmounts it, so this also releases the
         outgoing edit lease (mirroring the slug-change branch of
         `:editor/load-article`) so its `:realworld/article` cache entry can go
         inactive and GC. The `:editor/can-submit?` flow is registered ONCE at
         boot by `:editor/register-flow`, not per entry."}
  (fn [{:keys [db]} _]
    (let [prev-slug (get-in db [:editor :slug])]
      {:db (assoc db :editor (editor-slice))
       :fx (cond-> [[:dispatch [:rf.mutation/clear {:instance save-instance}]]]
             ;; edit -> new re-uses the same `editor-page` component, so no
             ;; unmount fires to release the edit lease — and blanking the slice
             ;; to a nil slug here would strand it. Release the outgoing slug's
             ;; lease before the slice is cleared, or its `:realworld/article`
             ;; cache entry stays pinned active for the rest of the session
             ;; (N edits -> N leaked entries).
             prev-slug
             (conj [:dispatch [:editor/release-article prev-slug]]))})))

(rf/reg-event :editor/load-article
  {:doc "Edit-mode entry (`:realworld.editor/edit` `:on-match`). Seeds the draft
         from the existing article via a one-shot resource ensure under a
         releaseable lease, and asks to be told when that read settles with the
         ensure's call-site `:reply-to [:editor/article-loaded]` — no off-render
         reaction. Clears any leftover save instance, and (on a same-component
         `/editor/A` -> `/editor/B` re-match) releases the outgoing slug's lease
         before taking the new one. The `:editor/can-submit?` flow is registered
         ONCE at boot by `:editor/register-flow`, not per entry. The seeded
         baseline is what gives the dirty-check something to compare against."}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug      (get-in rt [:rf.runtime/routing :current :params :slug])
          prev-slug (get-in db [:editor :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx (cond-> [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
                    ;; Ensure the article read so the editor can seed its
                    ;; baseline. A fresh entry is a cache-hit (the `:reply-to`
                    ;; continuation fires immediately); otherwise it fetches and
                    ;; the continuation fires on settle. The lease is released on
                    ;; leave by `:editor/release-article`.
                    [:dispatch [:rf.resource/ensure
                                {:resource :realworld/article
                                 :params   {:slug slug}
                                 :owner    [:lease :editor/article slug]
                                 :cause    [:route-entry :realworld.editor/edit]
                                 :reply-to [:editor/article-loaded]}]]]
             ;; `/editor/A` -> `/editor/B` re-matches the SAME route under a new
             ;; slug, so this component never unmounts — release the OUTGOING
             ;; slug's lease here. The event is the natural home for the
             ;; slug-change release (navigation is causal); the component's
             ;; unmount handles leaving the editor entirely.
             (and prev-slug (not= prev-slug slug))
             (conj [:dispatch [:editor/release-article prev-slug]]))})))

(rf/reg-event :editor/article-loaded
  {:doc "The article-read completion continuation (the ensure's `:reply-to`
         target). It receives the canonical reply map as its final arg the moment
         the `:realworld/article` read the editor caused settles — a cache-hit
         fires it immediately, a fetch fires it on settle, and a stale/superseded
         reply never fires it. On `:ok`, seed the editor draft + baseline from the
         loaded article so the dirty-check has a baseline to compare against; a
         load error surfaces through the read's own state, so there's nothing to
         do here. This is the read counterpart of the mutation `:reply-to` idiom
         settings.cljs uses — a declarative, replayable causal event, not an
         off-render Form-3 reaction watching the read settle."}
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (when (= :ok status)
      (when-let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))}))))

(rf/reg-event :editor/release-article
  {:doc "Release an edit-mode article lease. Called with an explicit slug (the
         slug-change release from `:editor/load-article`) or with none (the
         component's unmount release), in which case it releases the lease for the
         editor slice's current slug. Dropping the editor's owner lets the article
         read go inactive and eventually GC — an app-minted lease, like any
         event-created owner, MUST have a matching release."}
  (fn [{:keys [db]} [_ slug]]
    (when-let [slug (or slug (get-in db [:editor :slug]))]
      {:fx [[:dispatch [:rf.resource/release-owner
                        {:owner [:lease :editor/article slug]
                         :cause [:route-leave :realworld.editor/edit]}]]]})))

(rf/reg-event :editor/edit-field
  {:schema [:cat [:= :editor/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field)
        (update-in [:editor :errors] dissoc field))}))

(rf/reg-event :editor/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:editor :touched] (fnil conj #{}) field)}))

(rf/reg-event :editor/submit
  {:doc "Save the article. Reads the `:editor/can-submit?` FLOW output straight off
         app-db — a materialised derived value, consumed as plain data, no
         subscribe ceremony required. Valid-and-dirty fires the save mutation; an
         invalid draft re-runs validation to fill in the per-field error map; and
         valid-but-unchanged is a no-op."}
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft]} (:editor db)
          can-submit? (get-in db [:editor :can-submit?])
          errors      (validate-draft draft)]
      (cond
        ;; Valid but unchanged → nothing to save. The button is already disabled in
        ;; this state; this is just the belt-and-braces no-op for a programmatic
        ;; dispatch.
        (and (not can-submit?) (empty? errors))
        {}

        (seq errors)
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] errors))}

        :else
        {:db (assoc-in db [:editor :submit-attempted?] true)
         :fx [[:dispatch [:rf.mutation/execute
                          {:mutation :realworld/save-article
                           :params   (cond-> (select-keys draft [:title :description :body])
                                       true (assoc :tagList (parse-tag-list (:tagList draft)))
                                       slug (assoc :slug slug))
                           :instance save-instance
                           ;; The save-success continuation is the call-site
                           ;; `:reply-to`, not an off-render reaction. Save and
                           ;; delete share one instance, so they share one
                           ;; continuation that branches on the reply — `:value`
                           ;; carries the saved Article for a save; a delete comes
                           ;; back with no body.
                           :reply-to [:editor/replied]
                           :cause    [:submit :editor/save]}]]]}))))

(rf/reg-event :editor/replied
  {:doc "The save / delete completion continuation (the `:reply-to` target). It
         receives the canonical reply map as its final arg, observed AFTER the
         mutation's `:invalidates` staled the lists and feed and the instance
         settled. On a successful SAVE (`:value` carries the saved `{:article …}`),
         re-seed the editor from the saved article so the draft reads as clean —
         that way the `:can-leave` guard won't block — clear the instance, and
         navigate to the article detail. On a successful DELETE (no `:article` in
         the reply value), clear the slice and instance and head home. On `:error`
         there's nothing to do here; the form already shows it off the instance
         state."}
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (cond
      (not= :ok status) {}

      (:article value)
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
              [:dispatch [:rf.route/navigate :realworld.article/show {:slug (:slug article)}]]]})

      :else
      (let [prev-slug (get-in db [:editor :slug])]
        {:db (assoc db :editor (editor-slice))
         :fx (cond-> [[:dispatch [:rf.mutation/clear {:instance save-instance}]]]
               ;; Release the deleted article's lease BEFORE the slice is cleared
               ;; out from under it. The navigate-home below unmounts the editor,
               ;; whose `:editor/release-article` (no slug) reads the editor
               ;; slice's slug — but that's now nil, so it would release nothing
               ;; and leak the lease (and the delete's `[:article slug]`
               ;; invalidation could even refetch the just-deleted slug while the
               ;; orphaned lease keeps it active). Releasing the captured
               ;; outgoing slug here closes that gap.
               prev-slug (conj [:dispatch [:editor/release-article prev-slug]])
               true      (conj [:dispatch [:rf.route/navigate :realworld/home]]))}))))

(rf/reg-event :editor/delete
  {:doc "Delete the article (edit mode only). Fires the delete mutation under the
         same instance the save uses, with the same `:reply-to [:editor/replied]`
         continuation — which branches save-vs-delete on the reply value."}
  (fn [{:keys [db]} _]
    (when-let [slug (get-in db [:editor :slug])]
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation :realworld/delete-article
                         :params   {:slug slug}
                         :instance save-instance
                         :reply-to [:editor/replied]
                         :cause    [:click :editor/delete slug]}]]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor/slice (fn [db _] (:editor db)))
(rf/reg-sub :editor/draft :<- [:editor/slice] (fn [e _] (:draft e)))
(rf/reg-sub :editor/slug  :<- [:editor/slice] (fn [e _] (:slug e)))

(rf/reg-sub :editor/field-error
  {:doc "Per-field validation error — held back until either the first submit
         attempt or the moment the field is touched, whichever comes first."}
  :<- [:editor/slice]
  (fn [e [_ field]]
    (when (or (:submit-attempted? e) (contains? (:touched e) field))
      (get-in e [:errors field]))))

(rf/reg-sub :editor/can-submit?
  {:doc "Reads the `:editor/can-submit?` FLOW output at its app-db `:output-path` —
         a flow's output is just ordinary app-db state, read through a plain sub.
         Drives the submit button. It's nil until the flow's first walk lands,
         which `(boolean …)` tidily normalises to false."}
  (fn [db _]
    (boolean (get-in db [:editor :can-submit?]))))

(rf/reg-sub :editor/dirty?
  :<- [:editor/slice]
  (fn [e _] (not= (:draft e) (:baseline e))))

(rf/reg-sub :editor/can-leave?
  {:doc "The route `:can-leave` guard query. A clean (or just-saved) draft leaves
         freely; a dirty one blocks, and the app shell shows the confirm dialog off
         `:rf/pending-navigation`. See route guard:
         ../../../docs/routing/glossary.md#route-guard."}
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))

;; ============================================================================
;; VIEW  (pure Form-1 render + a Form-3 wrapper holding the editor reactions)
;; ============================================================================
;;
;; Same shape as settings.cljs. The render is a pure registered `reg-view` that
;; never dispatches; the one off-render reaction it needs (seed-on-load in edit
;; mode) lives in a Form-3 wrapper's lifecycle hooks. The frame is captured at
;; render via `rf/capture-frame`, so the reaction's out-of-render dispatch still
;; carries the right frame.

(reg-view ^{:doc "The pure article-editor form — a function of subs, and it never
                   dispatches. The save/delete continuations live elsewhere, in
                   the `editor-page` Form-3 wrapper."}
          editor-form-view []
  (let [draft       @(subscribe [:editor/draft])
        slug        @(subscribe [:editor/slug])
        can-submit? @(subscribe [:editor/can-submit?])
        title-err   @(subscribe [:editor/field-error :title])
        desc-err    @(subscribe [:editor/field-error :description])
        body-err    @(subscribe [:editor/field-error :body])
        save        @(subscribe [:rf/mutation {:instance save-instance}])
        editing?    (some? slug)
        busy?       (:pending? save)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when (:error? save)
          [:ul.error-messages {:data-testid "editor-error"} [:li (rh/failure->message (:error save))]])
        [:form
         {:data-testid "editor-form"
          :on-submit (fn [e] (.preventDefault e) (dispatch [:editor/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text" :name "title" :placeholder "Article Title" :data-testid "editor-title"
             :value (:title draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :title])
             :on-change #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when title-err [:div.error-messages title-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "description" :placeholder "What's this article about?" :data-testid "editor-description"
             :value (:description draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :description])
             :on-change #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when desc-err [:div.error-messages desc-err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows 8 :name "body" :placeholder "Write your article (in markdown)" :data-testid "editor-body"
             :value (:body draft) :disabled busy?
             :on-blur #(dispatch [:editor/blur-field :body])
             :on-change #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when body-err [:div.error-messages body-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "tags" :placeholder "Enter tags (comma-separated)" :data-testid "editor-tags"
             :value (:tagList draft) :disabled busy?
             :on-change #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           {:type "submit" :data-testid "editor-submit"
            ;; Disabled while busy, or while the can-submit? flow is false (draft
            ;; invalid or unchanged) — the flow's materialised output drives this
            ;; directly.
            :disabled (or busy? (not can-submit?))}
           (if editing? "Update Article" "Publish Article")]
          (when editing?
            [:button.btn.btn-outline-danger.pull-xs-left
             {:type "button" :data-testid "editor-delete" :disabled busy?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))

(defn editor-page
  "The article-editor page. The render is the pure `editor-form-view`, and the
   ONE lifecycle concern left is releasing the article read's lease when the
   editor leaves the screen. There is no off-render reaction: the seed-on-load is
   the `:realworld/article` ensure's `:reply-to [:editor/article-loaded]`
   continuation (a declarative causal event — a resource read now gets the same
   reply-side continuation a mutation does, rf2-p1yri7), and the same-component
   `/editor/A` -> `/editor/B` slug-change lease release rides
   `:editor/load-article` (navigation is causal). So the only thing that
   genuinely needs a component hook is leaving the editor entirely — the unmount.

   `rf/capture-frame` is captured here during render, under the frame-provider,
   so the unmount dispatch carries the right frame; `:editor/release-article`
   with no slug releases whatever lease the editor slice still names."
  []
  (let [{:keys [dispatch]} (rf/capture-frame)]
    (r/create-class
     {:display-name "realworld-resources.article-editor/editor-page"
      :component-will-unmount
      (fn [_this]
        ;; Release whatever article lease this editor still holds on the way out
        ;; (the slug is read from the editor slice by the release event).
        (dispatch [:editor/release-article]))
      :reagent-render
      (fn [] [editor-form-view])})))
