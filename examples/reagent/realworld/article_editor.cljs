(ns realworld.article-editor
  "Article editor for the RealWorld (Conduit) example.

   This sketch demonstrates:
   - Pattern-NineStates — one parallel state machine `:ui/article-editor`
     with two orthogonal regions (`:mode` x `:lifecycle`) replacing the
     prior mode-flag + lifecycle-status shape.
   - Pattern-Forms — the draft / errors / touched / submit-error slice
     still lives in app-db (`:editor`); the machine carries only the
     state vocabulary. `:editor/dirty?` is a draft-vs-baseline sub, not
     a state-machine concern.
   - The view's input-busy and Delete-button visibility are tag queries
     (`(rf/machine-has-tag? :ui/article-editor :editor/busy)` and
     `(rf/machine-has-tag? :ui/article-editor :editor/can-delete)`) rather than
     boolean discriminator subs.
   - The view's root is a `case` over `:article-editor/render`, a
     selector sub that consults a render-priority table against the
     machine's tag union (per Pattern-NineStates §4)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine (called
            ;; below at ns-load) and the `:rf/machine` framework subs
            ;; resolve.
            [re-frame.machines]
            ;; Flows (Spec 013) ship in day8/re-frame2-flows. The editor
            ;; registers a `:editor/can-submit?` flow (see :editor/initialise
            ;; below) that materialises form-validity-AND-dirty into app-db.
            ;; Loading the ns here publishes the artefact's late-bind hooks
            ;; (`:flows/reg-flow` etc.) so the `:rf.fx/reg-flow` effect
            ;; resolves; without it the effect raises
            ;; :rf.error/flows-artefact-missing.
            [re-frame.flows]
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def blank-draft
  {:title "" :description "" :body "" :tagList ""})

(defn draft-from-article [article]
  {:title       (:title article)
   :description (:description article)
   :body        (:body article)
   :tagList     (str/join ", " (:tagList article))})

(defn parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn editor-slice
  "The form's app-db slice. Holds Pattern-Forms shape (draft, baseline,
   errors, touched, submit-attempted?, submit-error). The machine
   carries the state vocabulary; this slice carries the data."
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug              slug
    :draft             baseline
    :baseline          baseline
    :submitted         nil
    :errors            {}
    :touched           #{}
    :submit-attempted? false
    :submit-error      nil}))

(defn validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

;; ============================================================================
;; THE FLOW — :editor/can-submit?  (Spec 013 §Flows)
;; ============================================================================
;;
;; This is RealWorld's worked Spec 013 flow — the `-flows` artefact in the
;; example's "composes core + -schemas + -machines + -routing + -flows +
;; -http" claim. `:editor/can-submit?` materialises a derived boolean —
;; "the draft passes client-side validation AND differs from the loaded
;; baseline" — into app-db at `[:editor :can-submit?]`.
;;
;; WHY A FLOW HERE (Spec 013 §When (and when not) to use a flow): the
;; `:editor/submit` handler reads this value as plain app-db data to gate
;; the submit, exactly the "other event handlers read the value" criterion.
;; A sub would force the handler to `subscribe` mid-handler (awkward and
;; non-idiomatic); the flow keeps the gate as ordinary state the handler
;; reads with `get-in`. The submit-button-disabled view still reads it
;; through a plain sub over the flow's `:path` (Spec 013 §Sub integration).
;;
;; The flow is registered per-frame via `:rf.fx/reg-flow` from
;; `:editor/initialise` (below) rather than at ns-load, so it registers
;; against whatever frame the app boots on — the default frame in the
;; browser, and each per-test `make-frame` frame in the headless fixtures
;; (Spec 013 §Dynamic toggle via fx §Frame routing). Registering at ns-load
;; would bind it only to `(current-frame-id)` and miss the test frames.

(def can-submit-flow
  {:id     :editor/can-submit?
   :doc    "True when the editor draft is valid AND dirty (differs from the
            loaded baseline). Materialised into app-db so the submit
            handler can read it as plain data."
   :inputs [[:editor :draft] [:editor :baseline]]
   :output (fn [draft baseline]
             (and (empty? (validate-draft draft))
                  (not= draft baseline)))
   :path   [:editor :can-submit?]})

(defn article-body [draft]
  {:article {:title       (:title draft)
             :description (:description draft)
             :body        (:body draft)
             :tagList     (parse-tag-list (:tagList draft))}})

;; ============================================================================
;; THE MACHINE — :ui/article-editor  (one machine, two regions)
;; ============================================================================
;;
;; The article editor has two orthogonal axes:
;;
;;   :mode      — :create (the /editor route, POST to /articles) vs
;;                :edit (the /editor/:slug route, PUT to /articles/:slug
;;                and a Delete button). The :edit state also emits the
;;                :editor/can-delete tag so the view can ask a tag-shaped
;;                question without inspecting the region directly.
;;
;;   :lifecycle — Pattern-Forms lifecycle: :idle | :loading | :submitting
;;                | :saved | :error. The :loading and :submitting states
;;                emit the :editor/busy tag so the view can disable
;;                inputs without a separate `:submitting?` sub.
;;
;; Per Spec 005 §Transition broadcast: every event delivered to the
;; machine is broadcast to every region. Region-distinct event names
;; below avoid collisions; `:reset` is handled by every region as a
;; self-target.

(def editor-machine
  {:type :parallel

   :regions
   {;; ---- :mode region — create vs edit ----
    :mode
    {:initial :create
     :states
     {:create
      ;; The /editor route. POST on submit. No Delete button.
      {:tags #{:mode/create}
       :on   {:use-edit   :edit
              :use-create :create
              :reset      :create}}

      :edit
      ;; The /editor/:slug route. PUT on submit. Delete button visible.
      {:tags #{:mode/edit :editor/can-delete}
       :on   {:use-create :create
              :use-edit   :edit
              :reset      :create}}}}

    ;; ---- :lifecycle region — Pattern-Forms lifecycle ----
    :lifecycle
    {:initial :idle
     :states
     {:idle
      {:tags #{:lifecycle/idle}
       :on   {:fetch-started  :loading
              :submit-started :submitting
              :reset          :idle}}

      :loading
      ;; Edit mode's initial article fetch in flight. Inputs disable
      ;; via the :editor/busy tag.
      {:tags #{:lifecycle/loading :editor/busy}
       :on   {:fetch-succeeded :idle
              :fetch-failed    :error
              :reset           :idle}}

      :submitting
      ;; Save in flight (POST or PUT, decided by :mode) or destructive
      ;; delete in flight. Inputs disable via the :editor/busy tag.
      {:tags #{:lifecycle/submitting :editor/busy}
       :on   {:submit-succeeded :saved
              :submit-failed    :idle
              :reset            :idle}}

      :saved
      ;; Transient post-submit-success state. The :editor/submit-success
      ;; handler navigates to the article detail page immediately, so
      ;; this state is short-lived; included for completeness.
      {:tags #{:lifecycle/saved}
       :on   {:reset :idle}}

      :error
      ;; Load-failed state. Submit-failed returns to :idle so the user
      ;; can retry; load-failed lands here so the view can show the
      ;; error banner. The submit-error message lives in the editor
      ;; slice; this state is the page-level render gate.
      {:tags #{:lifecycle/error}
       :on   {:fetch-started  :loading
              :submit-started :submitting
              :reset          :idle}}}}}})

(rf/reg-machine :ui/article-editor editor-machine)

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :editor/initialise
  {:doc "Reset the editor slice + machine, and register the
         `:editor/can-submit?` flow against the dispatching frame (Spec
         013 §Dynamic toggle via fx). The flow's first walk fires on the
         NEXT drain (Spec 013 §Sequencing); a fresh editor starts invalid
         + clean anyway, so the one-event lag carries no stale value."}
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]
          [:rf.fx/reg-flow can-submit-flow]]}))

(rf/reg-event :editor/load-article
  {:doc "Load an existing article into the editor in :edit mode.
         data-fetch retry policy applies (Spec 014). Broadcasts
         `:use-edit` so the :mode region tracks the edit-load and
         `:fetch-started` so the :lifecycle region advances to :loading."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  ;; EP-0001: the route slice is durable routing runtime-db state.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (assoc db :editor (editor-slice slug blank-draft))
       :fx [[:dispatch [:ui/article-editor [:use-edit]]]
            [:dispatch [:ui/article-editor [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/articles/" slug)
                          :decode     schema/ArticleResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:editor/load-article slug]
                          :on-success [:editor/loaded]
                          :on-failure [:editor/load-failed]})]]})))

(rf/reg-event :editor/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [article (:article value)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (editor-slice (:slug article) draft))
       :fx [[:dispatch [:ui/article-editor [:fetch-succeeded]]]]})))

(rf/reg-event :editor/load-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:fetch-failed]]]]}))

(rf/reg-event :editor/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field))}))

(rf/reg-event :editor/blur-field
  (fn [{:keys [db]} [_ field]]
    {:db (update-in db [:editor :touched] (fnil conj #{}) field)}))

(rf/reg-event :editor/submit
  {:doc "Save the article (POST for create, PUT for edit). NO retry — the
         user's intent is one submission per click; surface errors so the
         user can decide whether to retry (Spec 014).

         Reads the current `:mode` region's state to decide POST vs PUT.
         Broadcasts `:submit-started` into the lifecycle region; the
         `:on-success` / `:on-failure` replies broadcast
         `:submit-succeeded` / `:submit-failed`.

         The guard reads the `:editor/can-submit?` FLOW output straight off
         app-db (Spec 013 §Sub integration (a)) — a materialised derived
         value the handler consumes as plain data with no subscribe
         ceremony. When the form is unchanged (valid but not dirty) the
         flow is false and the submit is a no-op; an invalid draft re-runs
         validation to populate the per-field error map for display."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  ;; EP-0001: the machine snapshot is durable runtime-db state.
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [{:keys [slug draft]} (:editor db)
          mode        (get-in rt [:rf.runtime/machines :snapshots :ui/article-editor :state :mode])
          ;; Materialised flow output — read as plain app-db data.
          can-submit? (get-in db [:editor :can-submit?])
          errors      (validate-draft draft)]
      (cond
        ;; Valid but unchanged → nothing to save (the submit button is
        ;; disabled in this state via the :editor/can-submit? sub, so this
        ;; is the belt-and-braces no-op for programmatic dispatch).
        (and (not can-submit?) (empty? errors))
        {}

        (seq errors)
        ;; Client-side validation failure. Flip :submit-attempted? so
        ;; per-field error subs (Pattern-Forms §Error visibility)
        ;; reveal every error even on untouched fields, without the
        ;; prior workaround of re-touching every error field.
        ;; Whole-form prompt lives in `:errors :_form`; transport-shape
        ;; failures still land in `:submit-error` (the HTTP path).
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] (assoc errors :_form "Please fix the highlighted fields."))
                 (assoc-in [:editor :submit-error] nil))}

        :else
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :submitted] draft)
                 (assoc-in [:editor :errors] {})
                 (assoc-in [:editor :submit-error] nil))
         :fx [[:dispatch [:ui/article-editor [:submit-started]]]
              [:rf.http/managed
               (rh/request {:method     (if (= mode :edit) :put :post)
                            :path       (if (= mode :edit)
                                          (str "/articles/" slug)
                                          "/articles")
                            :body       (article-body draft)
                            :decode     schema/ArticleResponse
                            :on-success [:editor/submit-success]
                            :on-failure [:editor/submit-error]})]]}))))

(rf/reg-event :editor/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [article (:article value)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (editor-slice (:slug article) draft))
       :fx [[:dispatch [:ui/article-editor [:use-edit]]]
            [:dispatch [:ui/article-editor [:submit-succeeded]]]
            [:dispatch [:rf.route/navigate :realworld.article/show {:slug (:slug article)}]]]})))

(rf/reg-event :editor/submit-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

(rf/reg-event :editor/delete
  {:doc "Delete the article. No retry — destructive action, one click.
         Broadcasts `:submit-started` so the lifecycle region advances
         to :submitting (which carries the :editor/busy tag)."}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:editor :slug])]
      {:fx [[:dispatch [:ui/article-editor [:submit-started]]]
            [:rf.http/managed
             (rh/request {:method     :delete
                          :path       (str "/articles/" slug)
                          ;; The delete endpoint returns no body; :auto
                          ;; handles 204/empty gracefully.
                          :decode     :auto
                          :on-success [:editor/delete-success]
                          :on-failure [:editor/delete-error]})]]})))

(rf/reg-event :editor/delete-success
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]
          [:dispatch [:rf.route/navigate :realworld/home]]]}))

(rf/reg-event :editor/delete-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor/slice
  (fn [db _] (:editor db)))

(rf/reg-sub :editor/draft :<- [:editor/slice] (fn [editor _] (:draft editor)))
(rf/reg-sub :editor/errors :<- [:editor/slice] (fn [editor _] (:errors editor)))
(rf/reg-sub :editor/submit-error :<- [:editor/slice]
  (fn [editor _] (:submit-error editor)))

(rf/reg-sub :editor/field-error
  {:doc "Per-field validation error. Per Pattern-Forms §Error
         visibility: reveal every error after the first submit click,
         OR once the field is :touched."}
  :<- [:editor/slice]
  (fn [editor [_ field]]
    (when (or (:submit-attempted? editor)
              (contains? (:touched editor) field))
      (get-in editor [:errors field]))))

(rf/reg-sub :editor/form-error
  {:doc "Whole-form prompt (the :_form key under :errors) — populated
         when a submit attempt failed client-side validation."}
  :<- [:editor/slice]
  (fn [editor _]
    (when (:submit-attempted? editor)
      (get-in editor [:errors :_form]))))
(rf/reg-sub :editor/dirty?
  :<- [:editor/slice]
  (fn [editor _]
    (not= (:draft editor) (:baseline editor))))

(rf/reg-sub :editor/can-submit?
  {:doc "Reads the `:editor/can-submit?` FLOW output at its app-db :path
         (Spec 013 §Sub integration (b)). No special flow sub-id — a flow's
         output is ordinary app-db state and consumers read it through a
         plain sub over the path. Drives the submit button's disabled
         attribute. nil until the flow's first walk lands — the walk runs
         right after the handler, before the db install (Spec 013
         §Sequencing) — which `(boolean ...)` normalises to false."}
  (fn [db _]
    (boolean (get-in db [:editor :can-submit?]))))

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _]
    (not dirty?)))

;; ---- render-priority + :article-editor/render selector ----
;;
;; The render-priority table is plain data: a vector of {:tag :render}
;; pairs consulted in order. The `:article-editor/render` sub reads the
;; machine's tag union and returns the first :render whose :tag is
;; present. The editor view's `case` over the resolved keyword is the
;; only branch site.
;;
;; Priority rationale: the lifecycle region drives the gate. `:error`
;; (load-failed) shows the form with the error banner. `:loading`
;; (edit-mode initial fetch in flight) shows the form with busy inputs.
;; `:saved` is a transient state immediately followed by navigation;
;; included so the table is complete. Default is `:editing` — the form
;; in its normal interactive state.

(def render-priority
  [{:tag :lifecycle/error      :render :error}
   {:tag :lifecycle/loading    :render :loading}
   {:tag :lifecycle/saved      :render :saved}
   {:tag :lifecycle/submitting :render :editing}
   {:tag :lifecycle/idle       :render :editing}])

(rf/reg-sub :article-editor/render
  {:doc "Resolve the editor's render-model keyword by consulting the
         render-priority table against the `:ui/article-editor` machine's
         tag union. The root view's `case` is the only branch site."}
  :<- [:rf/machine :ui/article-editor]
  (fn sub-editor-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The shared editor form. Rendered by every render-mode
                   today; `:editor/busy` disables inputs, `:editor/can-delete`
                   shows the Delete button."}
          editor-form []
  (let [draft        @(subscribe [:editor/draft])
        title-err    @(subscribe [:editor/field-error :title])
        desc-err     @(subscribe [:editor/field-error :description])
        body-err     @(subscribe [:editor/field-error :body])
        form-err     @(subscribe [:editor/form-error])
        submit-error @(subscribe [:editor/submit-error])
        can-submit?  @(subscribe [:editor/can-submit?])
        busy?        @(rf/machine-has-tag? :ui/article-editor :editor/busy)
        can-delete?  @(rf/machine-has-tag? :ui/article-editor :editor/can-delete)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when form-err
          [:ul.error-messages [:li form-err]])
        (when submit-error
          [:ul.error-messages [:li submit-error]])
        [:form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (dispatch [:editor/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type        "text"
             :name        "title"
             :placeholder "Article Title"
             :value       (:title draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :title])
             :on-change   #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when title-err
             [:div.error-messages title-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :name        "description"
             :placeholder "What's this article about?"
             :value       (:description draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :description])
             :on-change   #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when desc-err
             [:div.error-messages desc-err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows        8
             :name        "body"
             :placeholder "Write your article (in markdown)"
             :value       (:body draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :body])
             :on-change   #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when body-err
             [:div.error-messages body-err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :name        "tags"
             :placeholder "Enter tags"
             :value       (:tagList draft)
             :disabled    busy?
             :on-change   #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           ;; Disabled while busy OR while the :editor/can-submit? flow is
           ;; false (draft invalid or unchanged) — the flow's materialised
           ;; output drives the button state.
           {:type "submit" :disabled (or busy? (not can-submit?))}
           (if can-delete? "Update Article" "Publish Article")]
          (when can-delete?
            [:button.btn.btn-outline-danger
             {:type "button"
              :disabled busy?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))

;; ---- per-render-state subviews ----
;;
;; All render-modes delegate to `editor-form` today; the form's own
;; tag queries (`:editor/busy`, `:editor/can-delete`) handle the in-form
;; differences. Splitting them out as separate reg-views keeps the
;; pattern shape consistent and gives a single
;; cheap site to introduce render-mode-specific scaffolding (a
;; full-page spinner, a dedicated load-error layout) without rewriting
;; the case branch.

(reg-view ^{:doc "Lifecycle :idle / :submitting — the form in its normal
                   interactive (or busy) state."}
          editor-editing []
  [editor-form])

(reg-view ^{:doc "Lifecycle :loading — edit-mode initial fetch in
                   flight. Today renders the form with disabled inputs
                   (via the :editor/busy tag); the form-only render is
                   pixel-equivalent to the prior behaviour."}
          editor-loading []
  [editor-form])

(reg-view ^{:doc "Lifecycle :error — load failed. Renders the form
                   with the submit-error banner; pixel-equivalent to
                   the prior behaviour."}
          editor-error []
  [editor-form])

(reg-view ^{:doc "Lifecycle :saved — transient post-submit-success
                   state. The :editor/submit-success handler navigates
                   immediately, so this view is rarely visible."}
          editor-saved []
  [editor-form])

(reg-view editor-page []
  (let [render-mode @(subscribe [:article-editor/render])]
    (case render-mode
      :error   [editor-error]
      :loading [editor-loading]
      :saved   [editor-saved]
      :editing [editor-editing]
      [editor-editing])))
