(ns realworld.article-editor
  "Article editor for the RealWorld (Conduit) example.

   This sketch shows the current re-frame2 shape for:
   - a Pattern-Forms slice with draft / touched / submit status
   - route-driven create-vs-edit branching
   - unsaved-change blocking via the route's `:can-leave` subscription
   - ordinary HTTP effects for create / update / delete"
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.schema]
            [realworld.http]
            [realworld.routing :as routing])
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
  ([] (editor-slice :create nil blank-draft))
  ([mode slug baseline]
   {:mode         mode
    :slug         slug
    :draft        baseline
    :baseline     baseline
    :submitted    nil
    :status       :idle
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
;; EVENTS
;; ============================================================================

(rf/reg-event-db :editor/initialise
  (fn [db _]
    (assoc db :editor (editor-slice))))

(rf/reg-event-fx :editor/load-article
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:route :params :slug])]
      {:db (-> db
               (assoc :editor (assoc (editor-slice :edit slug blank-draft)
                                     :status :loading)))
       :fx [[:http {:method     :get
                    :url        (str "/articles/" slug)
                    :on-success [:editor/loaded]
                    :on-error   [:editor/load-failed]}]]})))

(rf/reg-event-db :editor/loaded
  (fn [db [_ resp]]
    (let [article  (:article resp)
          draft    (draft-from-article article)]
      (assoc db :editor (editor-slice :edit (:slug article) draft)))))

(rf/reg-event-db :editor/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:editor :status] :error)
        (assoc-in [:editor :submit-error]
                  (or (some-> err :errors :body first)
                      (:message err)
                      "Couldn't load article.")))))

(rf/reg-event-db :editor/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:editor :draft field] value)
        (update-in [:editor :touched] (fnil conj #{}) field))))

(rf/reg-event-db :editor/blur-field
  (fn [db [_ field]]
    (update-in db [:editor :touched] (fnil conj #{}) field)))

(rf/reg-event-fx :editor/submit
  (fn [{:keys [db]} _]
    (let [{:keys [mode slug draft]} (:editor db)
          errors (validate-draft draft)]
      (if (seq errors)
        {:db (-> db
                 (assoc-in [:editor :errors] errors)
                 (assoc-in [:editor :submit-error] "Please fix the highlighted fields."))}
        {:db (-> db
                 (assoc-in [:editor :status] :submitting)
                 (assoc-in [:editor :submitted] draft)
                 (assoc-in [:editor :errors] {})
                 (assoc-in [:editor :submit-error] nil))
         :fx [[:http {:method     (if (= mode :edit) :put :post)
                      :url        (if (= mode :edit)
                                    (str "/articles/" slug)
                                    "/articles")
                      :body       (article-body draft)
                      :on-success [:editor/submit-success]
                      :on-error   [:editor/submit-error]}]]}))))

(rf/reg-event-fx :editor/submit-success
  (fn [{:keys [db]} [_ resp]]
    (let [article (:article resp)
          draft   (draft-from-article article)]
      {:db (assoc db :editor (assoc (editor-slice :edit (:slug article) draft)
                                    :status :saved))
       :fx [[:dispatch [:rf.route/navigate :route/article {:slug (:slug article)}]]]})))

(rf/reg-event-db :editor/submit-error
  (fn [db [_ err]]
    (-> db
        (assoc-in [:editor :status] :idle)
        (assoc-in [:editor :submit-error]
                  (or (some-> err :errors :body first)
                      (:message err)
                      "Couldn't save article.")))))

(rf/reg-event-fx :editor/delete
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:editor :slug])]
      {:db (assoc-in db [:editor :status] :submitting)
       :fx [[:http {:method     :delete
                    :url        (str "/articles/" slug)
                    :on-success [:editor/delete-success]
                    :on-error   [:editor/delete-error]}]]})))

(rf/reg-event-fx :editor/delete-success
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.route/navigate :route/home]]]}))

(rf/reg-event-db :editor/delete-error
  (fn [db [_ err]]
    (-> db
        (assoc-in [:editor :status] :idle)
        (assoc-in [:editor :submit-error]
                  (or (some-> err :errors :body first)
                      (:message err)
                      "Couldn't delete article.")))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :editor
  (fn [db _] (:editor db)))

(rf/reg-sub :editor/draft :<- [:editor] (fn [editor _] (:draft editor)))
(rf/reg-sub :editor/errors :<- [:editor] (fn [editor _] (:errors editor)))
(rf/reg-sub :editor/status :<- [:editor] (fn [editor _] (:status editor)))
(rf/reg-sub :editor/mode :<- [:editor] (fn [editor _] (:mode editor)))
(rf/reg-sub :editor/submitting? :<- [:editor/status]
  (fn [status _] (or (= status :submitting) (= status :loading))))
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

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view editor-page []
  (let [draft        @(subscribe [:editor/draft])
        errors       @(subscribe [:editor/errors])
        submitting?  @(subscribe [:editor/submitting?])
        submit-error @(subscribe [:editor/submit-error])
        mode         @(subscribe [:editor/mode])]
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
             :disabled    submitting?
             :on-blur     #(dispatch [:editor/blur-field :title])
             :on-change   #(dispatch [:editor/edit-field :title (.. % -target -value)])}]
           (when-let [err (:title errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :placeholder "What's this article about?"
             :value       (:description draft)
             :disabled    submitting?
             :on-blur     #(dispatch [:editor/blur-field :description])
             :on-change   #(dispatch [:editor/edit-field :description (.. % -target -value)])}]
           (when-let [err (:description errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:textarea.form-control
            {:rows        8
             :placeholder "Write your article (in markdown)"
             :value       (:body draft)
             :disabled    submitting?
             :on-blur     #(dispatch [:editor/blur-field :body])
             :on-change   #(dispatch [:editor/edit-field :body (.. % -target -value)])}]
           (when-let [err (:body errors)]
             [:div.error-messages err])]
          [:fieldset.form-group
           [:input.form-control
            {:type        "text"
             :placeholder "Enter tags"
             :value       (:tagList draft)
             :disabled    submitting?
             :on-change   #(dispatch [:editor/edit-field :tagList (.. % -target -value)])}]]
          [:button.btn.btn-lg.pull-xs-right.btn-primary
           {:type "submit" :disabled submitting?}
           (if (= mode :edit) "Update Article" "Publish Article")]
          (when (= mode :edit)
            [:button.btn.btn-outline-danger
             {:type "button"
              :disabled submitting?
              :on-click #(dispatch [:editor/delete])}
             "Delete Article"])]]]]]]))

    
