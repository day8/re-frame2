(ns realworld-resources.article-editor
  "Create / edit / delete an article — the RealWorld-on-resources editor.

   This is the form-heavy, flow-bearing page the focused `resources/` demo never
   shows and the variant was previously missing: it is the resources-variant
   port of the `:rf.http/managed` sibling's `realworld/article_editor.cljs`. It
   exercises three things at once:

   1. The WRITE is a MUTATION. `:realworld/save-article` POSTs `/articles`
      (create) or PUTs `/articles/:slug` (edit) and declares per-target scoped
      `:invalidates` descriptors (EP-0016 D2) — the global article tags
      (`[:article slug]` / `[:article-list]`) in `:rf.scope/global`, and the
      session `[:feed]` in `{:from-db :realworld/session}` — so on success the
      detail read, every list showing the article, AND the session feed refetch
      with NO further wiring (the read→write→invalidate→refetch loop, end to
      end; one mutation reaches both scopes). A `:realworld/delete-article`
      mutation invalidates the same tags. The write
      lifecycle is the mutation INSTANCE watched through `[:rf.mutation/state
      {:instance …}]` — there is no `:status` app-db field.

   2. The can-submit gate is a SPEC 013 FLOW. `:editor/can-submit?` materialises
      \"the draft is valid AND differs from the loaded baseline\" into app-db at
      `[:editor :can-submit?]`. The submit handler reads it as plain data (no
      mid-handler subscribe); the submit button reads it through a plain sub over
      the flow's `:output-path` (Spec 013 §Sub integration). The flow is registered
      per-frame from `:editor/initialise` via `:rf.fx/reg-flow` so it binds to
      whatever frame the app booted on.

   3. A navigation `:can-leave` guard. The editor route declares
      `:can-leave [:editor/can-leave?]`; a dirty draft blocks navigation away and
      the app-shell renders a confirm dialog off the `:rf/pending-navigation`
      sub (see core.cljs). Clean (or saved) drafts leave freely.

   The save / delete SUCCESS continuation (navigate to the saved article, or
   home on delete) is the mutation's call-site `:reply-to [:editor/replied]`
   target (EP-0016 D1) — the same idiom settings.cljs uses. When the runtime
   accepts the reply, it dispatches `[:editor/replied reply]` once, AFTER the
   `:invalidates` staled the lists + feed and the instance settled; the
   continuation branches save-vs-delete on the reply value. Save and delete
   share one instance, so they share one continuation. (Only the seed-on-load
   reaction stays a Form-3 reaction — it watches a RESOURCE read settle, which
   has no reply-side continuation.) The render bodies are pure functions of subs
   and never dispatch out of band.

   Contrast the sibling: its editor models the SAME page with Pattern-NineStates
   (a parallel `:mode` × `:lifecycle` machine) + a managed-HTTP write whose
   `:on-success` carries the continuation for free. Here the lifecycle IS the
   mutation instance, the mode is the route, and the continuation is the
   `:reply-to` target — the resources-variant expression of the same shapes."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.resources]
            ;; Flows ship in day8/re-frame2-flows (Spec 013). Loading the ns
            ;; publishes its late-bind hooks so the `:rf.fx/reg-flow` effect
            ;; resolves; without it the effect raises
            ;; :rf.error/flows-artefact-missing.
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
  "The form's app-db slice. The draft + baseline (for dirty-detection) + the
   per-field validation bookkeeping. The submission lifecycle is NOT here — it
   is the `:editor/save` mutation instance."
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
  "The single, stable instance id the editor form watches for the save write."
  :editor/save)

;; ============================================================================
;; THE FLOW — :editor/can-submit?  (Spec 013 §Flows)
;; ============================================================================
;;
;; Materialises a derived boolean — \"the draft passes client-side validation
;; AND differs from the loaded baseline\" — into app-db at `[:editor
;; :can-submit?]`. WHY A FLOW (Spec 013 §When to use a flow): the
;; `:editor/submit` handler reads this value as plain app-db data to gate the
;; submit (the \"other event handlers read the value\" criterion); a sub would
;; force the handler to subscribe mid-handler. The submit button reads the same
;; materialised value through a plain sub over the `:output-path`.
;;
;; Registered per-frame via `:rf.fx/reg-flow` from `:editor/initialise` (not at
;; ns-load) so it binds to whatever frame the app booted on (Spec 013 §Dynamic
;; toggle via fx). The flow's first walk fires on the NEXT drain; a fresh editor
;; starts invalid + clean anyway, so the one-event lag carries no stale value.

(def can-submit-flow
  {:id     :editor/can-submit?
   :doc    "True when the editor draft is valid AND dirty (differs from the
            loaded baseline). Materialised into app-db so the submit handler
            reads it as plain data."
   :inputs [[:editor :draft] [:editor :baseline]]
   :derive (fn [draft baseline]
             (and (empty? (validate-draft draft))
                  (not= draft baseline)))
   :output-path [:editor :can-submit?]})

;; ============================================================================
;; THE WRITE — mutations (POST create / PUT edit / DELETE)
;; ============================================================================
;;
;; Saving an article invalidates the article detail AND every list/feed that
;; shows it; the mutation declares those tags once and the runtime refetches the
;; mounted readers. Create and edit share one mutation: the `:request` switches
;; POST `/articles` ↔ PUT `/articles/:slug` on whether a `:slug` is present, so
;; the slug-bearing edit path invalidates its own detail entry too.

(rf/reg-mutation :realworld/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug) an
                   article. Invalidates the detail + lists + feed on success."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   :scope         :rf.scope/global
   ;; The lists go stale (global scope) so they re-read with the new article;
   ;; an edit's slug also stales its own detail entry. The create path has no
   ;; prior slug, so it stales only the lists (the new detail is read fresh on
   ;; navigate). The session feed lives in the session scope, so it gets its
   ;; own per-target descriptor naming `{:from-db :realworld/session}`
   ;; (EP-0016 D2) — one mutation reaches both scopes.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
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
   ;; Global article tags + the session feed, each in its own scope (EP-0016
   ;; D2 — see :realworld/save-article above).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :realworld/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete
               :url    (rh/full-url (str "/articles/" slug))}
     ;; The delete endpoint returns no body; `:auto` handles
     ;; 204/empty gracefully.
     :decode  :auto}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :editor/initialise
  {:doc "Create-mode entry (`:realworld.editor/new` `:on-match`). Reset the
         editor slice to a blank draft, clear any prior save instance, and
         register the `:editor/can-submit?` flow against the dispatching frame
         (Spec 013 §Dynamic toggle via fx)."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
          [:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/load-article
  {:doc "Edit-mode entry (`:realworld.editor/edit` `:on-match`). Seed the draft
         from the existing article via a one-shot resource ensure under a
         releaseable lease, register the flow, and clear any prior save
         instance. The seeded baseline lets the dirty-check fire."}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx [[:dispatch [:rf.mutation/clear {:instance save-instance}]]
            [:rf.fx/reg-flow can-submit-flow]
            ;; Ensure the article read so the editor can seed its baseline from
            ;; cache (a fresh entry is a cache-hit; otherwise it fetches). The
            ;; lease is released on leave by `:editor/release-article`.
            [:dispatch [:rf.resource/ensure
                        {:resource :realworld/article
                         :params   {:slug slug}
                         :owner    [:lease :editor/article slug]
                         :cause    [:route-entry :realworld.editor/edit]}]]]})))

(rf/reg-event :editor/seed-from-article
  {:doc "Seed the editor draft + baseline from a loaded article. Dispatched by
         the editor page's Form-3 load reaction when the article read first
         settles with data (off the render path)."}
  (fn [{:keys [db]} [_ article]]
    {:db (let [draft (draft-from-article article)]
      (assoc db :editor (editor-slice (:slug article) draft)))}))

(rf/reg-event :editor/release-article
  {:doc "Release the edit-mode article lease (dispatched on unmount). Drops the
         editor's owner on the article read so it can go inactive / GC. The
         release path is mandatory for an app-minted lease (Spec 016 §Active
         owners — event-created owners MUST have a matching release path)."}
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
  {:doc "Save the article. Reads the `:editor/can-submit?` FLOW output straight
         off app-db (Spec 013 §Sub integration (a)) — a materialised derived
         value consumed as plain data, no subscribe ceremony. Valid-and-dirty
         fires the save mutation; an invalid draft re-runs validation to
         populate the per-field error map; valid-but-unchanged is a no-op."}
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft]} (:editor db)
          can-submit? (get-in db [:editor :can-submit?])
          errors      (validate-draft draft)]
      (cond
        ;; Valid but unchanged → nothing to save (the button is disabled in this
        ;; state; this is the belt-and-braces no-op for programmatic dispatch).
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
                           ;; `:reply-to` (EP-0016 D1) — not an off-render
                           ;; reaction. The save and delete writes share one
                           ;; instance, so they share one continuation that
                           ;; branches on the reply (`:value` carries the saved
                           ;; Article for a save; a delete returns no body).
                           :reply-to [:editor/replied]
                           :cause    [:submit :editor/save]}]]]}))))

(rf/reg-event :editor/replied
  {:doc "The save / delete mutation completion continuation (the `:reply-to`
         target, EP-0016 D1). Receives the canonical reply map appended as the
         final arg, observed AFTER the mutation's `:invalidates` staled the
         lists + feed and the instance settled (phase 6). On a successful SAVE
         (`:value` carries the saved `{:article …}`), re-seed the editor from
         the saved article so the draft is clean — the `:can-leave` guard won't
         block — clear the instance, and navigate to the article detail. On a
         successful DELETE (no `:article` in the reply value), clear the slice
         + instance and go home. On `:error` the form already shows the error
         off the instance state; nothing more to do."}
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
  {:doc "Delete the article (edit mode only). Fires the delete mutation under
         the same instance the save uses, with the same `:reply-to
         [:editor/replied]` continuation (which branches save vs delete on the
         reply value)."}
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
  {:doc "Per-field validation error — revealed after the first submit attempt
         OR once the field is touched (Pattern-Forms §Error visibility)."}
  :<- [:editor/slice]
  (fn [e [_ field]]
    (when (or (:submit-attempted? e) (contains? (:touched e) field))
      (get-in e [:errors field]))))

(rf/reg-sub :editor/can-submit?
  {:doc "Reads the `:editor/can-submit?` FLOW output at its app-db `:output-path`
         (Spec 013 §Sub integration (b)) — a flow's output is ordinary app-db
         state read through a plain sub. Drives the submit button. nil until the
         flow's first walk lands, which `(boolean …)` normalises to false."}
  (fn [db _]
    (boolean (get-in db [:editor :can-submit?]))))

(rf/reg-sub :editor/dirty?
  :<- [:editor/slice]
  (fn [e _] (not= (:draft e) (:baseline e))))

(rf/reg-sub :editor/can-leave?
  {:doc "The route `:can-leave` guard query (Spec 012 §Redirects and guards): a
         clean (or just-saved) draft leaves freely; a dirty draft blocks and the
         app shell shows the confirm dialog off `:rf/pending-navigation`."}
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))

;; ============================================================================
;; VIEW  (pure Form-1 render + a Form-3 wrapper holding the editor reactions)
;; ============================================================================
;;
;; Same shape as settings.cljs: the render is a pure registered `reg-view` that
;; never dispatches; the off-render reactions (seed-on-load in edit mode, and
;; the save/delete-success continuation) live in a Form-3 wrapper's lifecycle
;; hooks. The frame is captured at render via `rf/frame-handle`, so the
;; reactions' out-of-render dispatches carry the frame.

(reg-view ^{:doc "Pure article-editor form — a function of subs, never
                   dispatches. The save/delete continuations live in the
                   `editor-page` Form-3 wrapper."}
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
            ;; Disabled while busy OR while the can-submit? flow is false (draft
            ;; invalid or unchanged) — the flow's materialised output drives it.
            :disabled (or busy? (not can-submit?))}
           (if editing? "Update Article" "Publish Article")]
          (when editing?
            [:button.btn.btn-outline-danger.pull-xs-left
             {:type "button" :data-testid "editor-delete" :disabled busy?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))

(defn editor-page
  "The article-editor page. A Reagent Form-3 component: the inner render is the
   pure `editor-form-view`; the mount hook installs ONE off-render reaction that
   seeds the draft when an edit-mode article read settles with data. (The
   save/delete SUCCESS continuation is no longer an off-render reaction — it is
   the mutation's `:reply-to [:editor/replied]` target, EP-0016 D1. A reply-side
   continuation only exists for the mutation write; the seed reaction watches a
   RESOURCE read settle, which has no reply-to, so it stays a Form-3 reaction.)
   `rf/frame-handle` is captured here (during render, under the frame-provider),
   so the reaction's dispatch carries the frame; unmount disposes the reaction
   and releases the edit-mode article lease."
  []
  (let [{:keys [dispatch subscribe]} (rf/frame-handle)
        seeded?   (atom false)
        watcher   (atom nil)]
    (r/create-class
     {:display-name "realworld-resources.article-editor/editor-page"
      :component-did-mount
      (fn [_this]
        ;; Edit-mode seed: when the article read first settles with data, seed
        ;; the draft + baseline (so dirty-detection has a baseline). The
        ;; load-article event ensured the read under a lease; this only seeds.
        (reset!
         watcher
         (ratom/run!
          (let [slug  @(subscribe [:editor/slug])
                state (when slug
                        @(subscribe [:rf.resource/state {:resource :realworld/article
                                                         :params {:slug slug}}]))]
            (when (and slug (:has-data? state) (not @seeded?))
              (when-let [article (:article (:data state))]
                (reset! seeded? true)
                (dispatch [:editor/seed-from-article article])))))))
      :component-will-unmount
      (fn [_this]
        (when @watcher (ratom/dispose! @watcher))
        (reset! watcher nil)
        ;; Release the edit-mode article lease if we held one.
        (when-let [slug @(subscribe [:editor/slug])]
          (dispatch [:editor/release-article slug])))
      :reagent-render
      (fn [] [editor-form-view])})))
