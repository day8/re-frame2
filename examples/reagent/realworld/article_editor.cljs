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
     (`(rf/has-tag? :ui/article-editor :editor/busy)` and
     `(rf/has-tag? :ui/article-editor :editor/can-delete)`) rather than
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
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

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
   errors, touched, submit-error). The machine carries the state
   vocabulary; this slice carries the data."
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug         slug
    :draft        baseline
    :baseline     baseline
    :submitted    nil
    :errors       {}
    :touched      #{}
    :submit-error nil}))

(defn validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

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

(rf/reg-event-fx :editor/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]]}))

(rf/reg-event-fx :editor/load-article
  {:doc "Load an existing article into the editor in :edit mode.
         data-fetch retry policy applies (Spec 014). Broadcasts
         `:use-edit` so the :mode region tracks the edit-load and
         `:fetch-started` so the :lifecycle region advances to :loading."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:rf/route :params :slug])]
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

(rf/reg-event-fx :editor/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [article (:article value)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (editor-slice (:slug article) draft))
       :fx [[:dispatch [:ui/article-editor [:fetch-succeeded]]]]})))

(rf/reg-event-fx :editor/load-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:fetch-failed]]]]}))

(rf/reg-event-db :editor/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field))))

(rf/reg-event-db :editor/blur-field
  (fn [db [_ field]]
    (update-in db [:editor :touched] (fnil conj #{}) field)))

(rf/reg-event-fx :editor/submit
  {:doc "Save the article (POST for create, PUT for edit). NO retry — the
         user's intent is one submission per click; surface errors so the
         user can decide whether to retry (Spec 014).

         Reads the current `:mode` region's state to decide POST vs PUT.
         Broadcasts `:submit-started` into the lifecycle region; the
         `:on-success` / `:on-failure` replies broadcast
         `:submit-succeeded` / `:submit-failed`."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft]} (:editor db)
          mode   (get-in db [:rf/machines :ui/article-editor :state :mode])
          errors (validate-draft draft)]
      (if (seq errors)
        {:db (-> db
                 (assoc-in [:editor :errors] errors)
                 (assoc-in [:editor :submit-error] "Please fix the highlighted fields."))}
        {:db (-> db
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

(rf/reg-event-fx :editor/submit-success
  (fn [{:keys [db]} [_ {:keys [value]}]]
    (let [article (:article value)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (editor-slice (:slug article) draft))
       :fx [[:dispatch [:ui/article-editor [:use-edit]]]
            [:dispatch [:ui/article-editor [:submit-succeeded]]]
            [:dispatch [:rf.route/navigate :route/article {:slug (:slug article)}]]]})))

(rf/reg-event-fx :editor/submit-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

(rf/reg-event-fx :editor/delete
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

(rf/reg-event-fx :editor/delete-success
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:ui/article-editor [:reset]]]
          [:dispatch [:rf.route/navigate :route/home]]]}))

(rf/reg-event-fx :editor/delete-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:editor :submit-error] (rh/failure->message failure))
     :fx [[:dispatch [:ui/article-editor [:submit-failed]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor
  (fn [db _] (:editor db)))

(rf/reg-sub :editor/draft :<- [:editor] (fn [editor _] (:draft editor)))
(rf/reg-sub :editor/errors :<- [:editor] (fn [editor _] (:errors editor)))
(rf/reg-sub :editor/submit-error :<- [:editor]
  (fn [editor _] (:submit-error editor)))
(rf/reg-sub :editor/dirty?
  :<- [:editor]
  (fn [editor _]
    (not= (:draft editor) (:baseline editor))))

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

;; Legacy sub names kept as thin adapters over the machine so existing
;; tests and any external callers still resolve. Prefer the tag queries
;; and the render selector in new code.
(rf/reg-sub :editor/status
  :<- [:rf/machine :ui/article-editor]
  (fn [snap _] (get-in snap [:state :lifecycle])))

(rf/reg-sub :editor/mode
  :<- [:rf/machine :ui/article-editor]
  (fn [snap _] (get-in snap [:state :mode])))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The shared editor form. Rendered by every render-mode
                   today; `:editor/busy` disables inputs, `:editor/can-delete`
                   shows the Delete button."}
          editor-form []
  (let [draft        @(subscribe [:editor/draft])
        errors       @(subscribe [:editor/errors])
        submit-error @(subscribe [:editor/submit-error])
        busy?        @(rf/has-tag? :ui/article-editor :editor/busy)
        can-delete?  @(rf/has-tag? :ui/article-editor :editor/can-delete)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
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
             :placeholder "Article Title"
             :value       (:title draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :title])
             :on-change   #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when-let [err (:title errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :placeholder "What's this article about?"
             :value       (:description draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :description])
             :on-change   #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when-let [err (:description errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows        8
             :placeholder "Write your article (in markdown)"
             :value       (:body draft)
             :disabled    busy?
             :on-blur     #(dispatch [:editor/blur-field :body])
             :on-change   #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when-let [err (:body errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :placeholder "Enter tags"
             :value       (:tagList draft)
             :disabled    busy?
             :on-change   #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           {:type "submit" :disabled busy?}
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
