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
      watched through `[:rf.mutation/state {:instance …}]`; there's no `:status`
      field in app-db.

   2. The can-submit gate is a flow. `:editor/can-submit?` materialises one
      derived fact — 'the draft is valid AND differs from the loaded baseline' —
      into app-db at `[:editor :can-submit?]`. The submit handler then reads it as
      plain data (no subscribing mid-handler), and the submit button reads the same
      value through a plain sub over the flow's `:output-path`. The flow is
      registered per-frame from `:editor/initialise` via `:rf.fx/reg-flow`, so it
      binds to whatever frame the app booted on. See the flow glossary:
      ../../../docs/core/glossary.md#flow.

   3. A navigation `:can-leave` guard. The editor route declares
      `:can-leave [:editor/can-leave?]`; a dirty draft blocks a navigate-away and
      the app shell pops a confirm dialog off the `:rf/pending-navigation` sub (see
      core.cljs). Clean (or just-saved) drafts leave freely. See route guard:
      ../../../docs/routing/glossary.md#route-guard.

   The save / delete success continuation — navigate to the saved article, or home
   on a delete — is the mutation's call-site `:reply-to [:editor/replied]` target,
   the very idiom settings.cljs uses. When the runtime accepts the reply it
   dispatches `[:editor/replied reply]` once, AFTER `:invalidates` staled the lists
   and feed and the instance settled; the continuation then branches save-vs-delete
   on the reply value. Save and delete share one instance, so they share one
   continuation. The one exception is the seed-on-load reaction, which stays a
   Form-3 reaction because it watches a RESOURCE read settle, and a read has no
   reply-side continuation to hang off. The render bodies, as everywhere here, are
   pure functions of subs that never dispatch out of band."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            ;; The flows runtime. Loading it publishes the hooks so the
            ;; `:rf.fx/reg-flow` effect has something to resolve against; without
            ;; it, the effect raises.
            [re-frame.flows]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
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
;; It's registered per-frame via `:rf.fx/reg-flow` from `:editor/initialise` (not
;; at ns-load), so it binds to whatever frame the app booted on. The flow's first
;; walk fires on the next drain; a fresh editor starts invalid and clean anyway,
;; so that one-event lag never carries a stale value.

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

(rf/reg-event :editor/initialise
  {:doc "Create-mode entry (`:realworld.editor/new` `:on-match`). Resets the editor
         slice to a blank draft, clears any leftover save instance, and registers
         the `:editor/can-submit?` flow against the dispatching frame."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
          [:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/load-article
  {:doc "Edit-mode entry (`:realworld.editor/edit` `:on-match`). Seeds the draft
         from the existing article via a one-shot resource ensure under a
         releaseable lease, registers the flow, and clears any leftover save
         instance. The seeded baseline is what gives the dirty-check something to
         compare against."}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
            [:rf.fx/reg-flow can-submit-flow]
            ;; Ensure the article read so the editor can seed its baseline from
            ;; cache — a fresh entry is a cache-hit, otherwise it fetches. The
            ;; lease is released on leave by `:editor/release-article`.
            [:dispatch [:rf.resource/ensure
                        {:resource :realworld/article
                         :params   {:slug slug}
                         :owner    [:lease :editor/article slug]
                         :cause    [:route-entry :realworld.editor/edit]}]]]})))

(rf/reg-event :editor/seed-from-article
  {:doc "Seed the editor draft + baseline from a loaded article. Dispatched by the
         editor page's Form-3 load reaction the moment the article read first
         settles with data, safely off the render path."}
  (fn [{:keys [db]} [_ article]]
    {:db (let [draft (draft-from-article article)]
      (assoc db :editor (editor-slice (:slug article) draft)))}))

(rf/reg-event :editor/release-article
  {:doc "Release the edit-mode article lease (dispatched on unmount). Drops the
         editor's owner on the article read so it can go inactive and eventually
         GC. This release path isn't optional — an app-minted lease, like any
         event-created owner, MUST have a matching release."}
  (fn [_ [_ slug]]
    (when slug
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
      {:db (assoc db :editor (editor-slice))
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
            [:dispatch [:rf.route/navigate :realworld/home]]]})))

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
        save        @(subscribe [:rf.mutation/state {:instance save-instance}])
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
  "The article-editor page, a Reagent Form-3 component. The inner render is the
   pure `editor-form-view`; the mount hook installs exactly one off-render
   reaction, which seeds the draft when an edit-mode article read settles with
   data. (Why only one? The save/delete success continuation is the mutation's
   `:reply-to [:editor/replied]` target, not a reaction — a reply-side
   continuation only exists for a write. The seed watches a RESOURCE read settle,
   and a read has no reply-to, so it has to stay a Form-3 reaction.)

   `/editor/A` -> `/editor/B` re-matches the SAME `:realworld.editor/edit` route
   under a new slug, so `root-view`'s `case` never unmounts this component — the
   same reaction has to also notice the slug changing under it, or the draft
   keeps showing A forever and A's lease never releases. So the reaction tracks
   the SLUG it last acted on rather than a plain seeded? boolean: whenever that
   slug moves, it releases the outgoing lease and clears the seed marker so the
   new slug reseeds from its own read the moment it settles, exactly as a fresh
   mount would.

   `rf/capture-frame` is captured here during render, under the frame-provider,
   so the reaction's dispatch carries the frame; unmount disposes the reaction
   and releases whatever lease is still held."
  []
  (let [{:keys [dispatch subscribe]} (rf/capture-frame)
        ;; The slug this component instance currently holds the article lease
        ;; for, and has (or hasn't yet) seeded the draft from. `nil` means "no
        ;; lease held" — true both before the first reaction run and in
        ;; create-mode (no slug at all), so a transition away from `nil` never
        ;; triggers a spurious release.
        owned-slug  (atom nil)
        seeded-slug (atom nil)
        watcher     (atom nil)]
    (r/create-class
     {:display-name "realworld-resources.article-editor/editor-page"
      :component-did-mount
      (fn [_this]
        (reset!
         watcher
         (ratom/run!
          (let [slug @(subscribe [:editor/slug])]
            ;; The slug moved since this reaction last ran (mount, or a
            ;; same-component nav to a different edit target). Release the
            ;; outgoing lease, if any, and clear the seed marker so the new
            ;; slug's own read gets to seed the draft.
            (when (not= slug @owned-slug)
              (let [prev-slug @owned-slug]
                (reset! owned-slug slug)
                (reset! seeded-slug nil)
                (when prev-slug
                  (dispatch [:editor/release-article prev-slug]))))
            (let [state (when slug
                          @(subscribe [:rf.resource/state {:resource :realworld/article
                                                           :params {:slug slug}}]))]
              (when (and slug (:has-data? state) (not= @seeded-slug slug))
                (when-let [article (:article (:data state))]
                  (reset! seeded-slug slug)
                  (dispatch [:editor/seed-from-article article]))))))))
      :component-will-unmount
      (fn [_this]
        (when @watcher (ratom/dispose! @watcher))
        (reset! watcher nil)
        ;; Release whatever lease this instance still holds.
        (when @owned-slug
          (dispatch [:editor/release-article @owned-slug])))
      :reagent-render
      (fn [] [editor-form-view])})))
