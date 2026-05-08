(ns realworld.comments
  "Article detail plus comments for the RealWorld (Conduit) example.

   This sketch keeps the current re-frame2 API surface explicit:
   - `:article` and `:comments` use the standard Pattern-RemoteData shape.
   - `:comment-form` uses the standard Pattern-Forms slice shape.
   - Route-driven loads read the current slug from `[:route :params :slug]`.
   - Post/delete flows are optimistic and roll back via ordinary events."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.schema]
            [realworld.http]
            [realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn current-time-ms [] (.getTime (js/Date.)))

(defn comment-form-defaults []
  {:draft        {:body ""}
   :submitted    nil
   :status       :idle
   :errors       {}
   :touched      #{}
   :submit-error nil})

(defn article-path [slug]
  (str "/articles/" slug))

(defn comment-path [slug]
  (str (article-path slug) "/comments"))

(defn temp-comment-id []
  (str "temp-" (random-uuid)))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-db :article/initialise
  (fn [db _]
    (assoc db :article {:status :idle :data nil :error nil
                        :loaded-at nil :attempt 0})))

(rf/reg-event-db :comments/initialise
  (fn [db _]
    (assoc db :comments {:status :idle :data [] :error nil
                         :loaded-at nil :attempt 0})))

(rf/reg-event-db :comment-form/initialise
  (fn [db _]
    (assoc db :comment-form (comment-form-defaults))))

;; ============================================================================
;; ARTICLE
;; ============================================================================

(rf/reg-event-fx :article/load
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:route :params :slug])]
      {:db (-> db
               (assoc-in [:article :status]
                         (if (get-in db [:article :data]) :fetching :loading))
               (assoc-in [:article :error] nil)
               (update-in [:article :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        (article-path slug)
                    :auth?      false
                    :on-success [:article/loaded]
                    :on-error   [:article/load-failed]}]]})))

(rf/reg-event-db :article/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:article :status] :loaded)
        (assoc-in [:article :data] (:article resp))
        (assoc-in [:article :error] nil)
        (assoc-in [:article :loaded-at] (current-time-ms)))))

(rf/reg-event-db :article/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:article :status] :error)
        (assoc-in [:article :error] err))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(rf/reg-event-fx :comments/load
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:route :params :slug])]
      {:db (-> db
               (assoc-in [:comments :status]
                         (if (seq (get-in db [:comments :data])) :fetching :loading))
               (assoc-in [:comments :error] nil)
               (update-in [:comments :attempt] (fnil inc 0)))
       :fx [[:http {:method     :get
                    :url        (comment-path slug)
                    :auth?      false
                    :on-success [:comments/loaded]
                    :on-error   [:comments/load-failed]}]]})))

(rf/reg-event-db :comments/loaded
  (fn [db [_ resp]]
    (-> db
        (assoc-in [:comments :status] :loaded)
        (assoc-in [:comments :data] (vec (:comments resp)))
        (assoc-in [:comments :error] nil)
        (assoc-in [:comments :loaded-at] (current-time-ms)))))

(rf/reg-event-db :comments/load-failed
  (fn [db [_ err]]
    (-> db
        (assoc-in [:comments :status] :error)
        (assoc-in [:comments :error] err))))

;; ============================================================================
;; COMMENT FORM
;; ============================================================================

(rf/reg-event-db :comment-form/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:comment-form :draft field] value)
        (update-in [:comment-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :comment-form/submit
  (fn [{:keys [db]} _]
    (let [slug      (get-in db [:route :params :slug])
          draft     (get-in db [:comment-form :draft])
          body      (str/trim (or (:body draft) ""))
          user      (get-in db [:auth :user])
          temp-id   (temp-comment-id)
          temp-card {:id        temp-id
                     :createdAt "pending"
                     :updatedAt "pending"
                     :body      body
                     :author    {:username  (:username user)
                                 :bio       (:bio user)
                                 :image     (:image user)
                                 :following false}}]
      (if (str/blank? body)
        {:db (-> db
                 (assoc-in [:comment-form :errors] {:body "Comment body is required."})
                 (assoc-in [:comment-form :submit-error] "Comment body is required."))}
        {:db (-> db
                 (assoc-in [:comment-form :status] :submitting)
                 (assoc-in [:comment-form :submitted] {:body body})
                 (assoc-in [:comment-form :errors] {})
                 (assoc-in [:comment-form :submit-error] nil)
                 (update-in [:comments :data] (fnil conj []) temp-card))
         :fx [[:http {:method     :post
                      :url        (comment-path slug)
                      :body       {:comment {:body body}}
                      :on-success [:comment-form/submit-success temp-id]
                      :on-error   [:comment-form/submit-error temp-id]}]]}))))

(rf/reg-event-db :comment-form/submit-success
  (fn [db [_ temp-id resp]]
    (let [saved (:comment resp)]
      (-> db
          (assoc-in [:comment-form] (comment-form-defaults))
          (update-in [:comments :data]
                     (fn [comments]
                       (->> (or comments [])
                            (remove #(= temp-id (:id %)))
                            (concat [saved])
                            vec)))))))

(rf/reg-event-db :comment-form/submit-error
  (fn [db [_ temp-id err]]
    (-> db
        (update-in [:comments :data]
                   (fn [comments]
                     (vec (remove #(= temp-id (:id %)) comments))))
        (assoc-in [:comment-form :status] :idle)
        (assoc-in [:comment-form :submit-error]
                  (or (some-> err :errors :body first)
                      (:message err)
                      "Couldn't post comment.")))))

(rf/reg-event-fx :comment/delete
  (fn [{:keys [db]} [_ id]]
    (let [slug     (get-in db [:route :params :slug])
          comments (vec (get-in db [:comments :data]))
          index    (first (keep-indexed (fn [idx comment]
                                          (when (= id (:id comment)) idx))
                                        comments))
          prior    (when (some? index) {:index index :comment (nth comments index)})]
      {:db (update-in db [:comments :data]
                      (fn [xs] (vec (remove #(= id (:id %)) xs))))
       :fx [[:http {:method     :delete
                    :url        (str (comment-path slug) "/" id)
                    :on-success [:comment/delete-success id]
                    :on-error   [:comment/delete-rollback prior]}]]})))

(rf/reg-event-db :comment/delete-success
  (fn [db _] db))

(rf/reg-event-db :comment/delete-rollback
  (fn [db [_ {:keys [index comment]}]]
    (if (and (some? index) comment)
      (update-in db [:comments :data]
                 (fn [xs]
                   (let [xs (vec xs)]
                     (vec (concat (subvec xs 0 index)
                                  [comment]
                                  (subvec xs index))))))
      db)))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :article (fn [db _] (:article db)))
(rf/reg-sub :article/data :<- [:article] (fn [slice _] (:data slice)))
(rf/reg-sub :article/status :<- [:article] (fn [slice _] (:status slice)))
(rf/reg-sub :article/error :<- [:article] (fn [slice _] (:error slice)))

(rf/reg-sub :comments (fn [db _] (:comments db)))
(rf/reg-sub :comments/data :<- [:comments] (fn [slice _] (:data slice)))
(rf/reg-sub :comments/status :<- [:comments] (fn [slice _] (:status slice)))
(rf/reg-sub :comments/error :<- [:comments] (fn [slice _] (:error slice)))

(rf/reg-sub :comment-form/draft
  (fn [db _] (get-in db [:comment-form :draft])))

(rf/reg-sub :comment-form/submitting?
  (fn [db _] (= :submitting (get-in db [:comment-form :status]))))

(rf/reg-sub :comment-form/submit-error
  (fn [db _] (get-in db [:comment-form :submit-error])))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view comment-card [{:keys [comment current-user]}]
  (let [mine?   (= (:username current-user)
                   (get-in comment [:author :username]))
        temp?   (str/starts-with? (str (:id comment)) "temp-")]
    [:div.card
     [:div.card-block [:p.card-text (:body comment)]]
     [:div.card-footer
      [routing/route-link {:to     :route/profile
                           :params {:username (get-in comment [:author :username])}
                           :class  "comment-author"}
       [:img.comment-author-img {:src (get-in comment [:author :image])}]
       " "
       (get-in comment [:author :username])]
      [:span.date-posted (:createdAt comment)]
      (when temp?
        [:span.mod-options " Sending…"])
      (when (and mine? (not temp?))
        [:button.mod-options
         {:type "button"
          :on-click #(dispatch [:comment/delete (:id comment)])}
         [:i.ion-trash-a]])]]))

(reg-view article-page []
  (let [article        @(subscribe [:article/data])
        article-status @(subscribe [:article/status])
        article-error  @(subscribe [:article/error])
        comments       @(subscribe [:comments/data])
        comments-error @(subscribe [:comments/error])
        comment-draft  @(subscribe [:comment-form/draft])
        submit-error   @(subscribe [:comment-form/submit-error])
        submitting?    @(subscribe [:comment-form/submitting?])
        current-user   @(subscribe [:auth/user])]
    [:div.article-page
     (cond
       (= article-status :loading)
       [:div.article-preview "Loading article…"]

       article-error
       [:div.article-preview.error
        (str "Couldn't load article: " (pr-str article-error))]

       article
       [:<>
        [:div.banner
         [:div.container
          [:h1 (:title article)]
          [:p (:description article)]
          [:button.btn.btn-sm.btn-outline-primary
           {:type "button"
            :on-click #(dispatch [:article/toggle-favorite (:slug article)])}
           [:i.ion-heart] " " (:favoritesCount article)]]]
        [:div.container.page
         [:div.row.article-content
          [:div.col-md-12
           [:p (:body article)]
           [:ul.tag-list
            (for [tag (:tagList article)]
              ^{:key tag}
              [:li.tag-default.tag-pill.tag-outline tag])]]]
         [:hr]
         [:div.article-actions
          [routing/route-link {:to :route/home} "Back to feed"]]
         [:div.row
          [:div.col-xs-12.col-md-8.offset-md-2
           (if current-user
             [:form.card.comment-form
              {:on-submit (fn [e]
                            (.preventDefault e)
                            (dispatch [:comment-form/submit]))}
              [:div.card-block
               [:textarea.form-control
                {:rows 3
                 :placeholder "Write a comment..."
                 :value (:body comment-draft)
                 :disabled submitting?
                 :on-change #(dispatch [:comment-form/edit-field :body (.. % -target -value)])}]]
              [:div.card-footer
               [:img.comment-author-img {:src (:image current-user)}]
               [:button.btn.btn-sm.btn-primary
                {:type "submit"
                 :disabled submitting?}
                (if submitting? "Posting…" "Post Comment")]]
              (when submit-error
                [:div.error-messages submit-error])]
             [:p
              [routing/route-link {:to :route/login} "Sign in"]
              " or "
              [routing/route-link {:to :route/register} "sign up"]
              " to add comments."])
           (when comments-error
             [:div.article-preview.error
              (str "Couldn't load comments: " (pr-str comments-error))])
           (for [comment comments]
             ^{:key (:id comment)}
             [comment-card {:comment comment :current-user current-user}])]]]]

       :else
       [:div.article-preview "No article loaded."])]))

