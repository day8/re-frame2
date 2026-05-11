(ns realworld.comments
  "Article detail plus comments for the RealWorld (Conduit) example.

   This sketch keeps the current re-frame2 API surface explicit:
   - `:article` and `:comments` use the standard Pattern-RemoteData shape.
   - `:comment-form` uses the standard Pattern-Forms slice shape.
   - Route-driven loads read the current slug from `[:rf/route :params :slug]`.
   - Post/delete flows are optimistic and roll back via ordinary events."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.schema :as schema]
            [realworld.http :as rh]
            [realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn comment-form-defaults []
  {:draft             {:body ""}
   :submitted         nil
   :status            :idle
   :errors            {}
   :touched           #{}
   :submit-attempted? false
   :submit-error      nil})

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
  {:doc "Load the article matching `[:rf/route :params :slug]`.

         This handler demonstrates Spec 014's *default reply addressing*:
         no `:on-success` / `:on-failure` is supplied, so the framework
         re-dispatches the reply back to this same event id with
         `:rf/reply` merged into the original message map. The handler
         body branches on `(:rf/reply msg)` — one event id, two roles."
   :rf.http/decode-schemas [schema/ArticleResponse]}
  [(rf/inject-cofx :realworld/now)]
  (fn [{:keys [db realworld/now]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply branch — handle success or failure.
      (case (:kind reply)
        :success
        {:db (-> db
                 (assoc-in [:article :status] :loaded)
                 (assoc-in [:article :data] (:article (:value reply)))
                 (assoc-in [:article :error] nil)
                 (assoc-in [:article :loaded-at] now))}

        :failure
        {:db (-> db
                 (assoc-in [:article :status] :error)
                 (assoc-in [:article :error] (rh/failure->message (:failure reply))))})

      ;; Initial dispatch — issue the managed request. Default reply
      ;; addressing routes the reply back here.
      (let [slug (get-in db [:rf/route :params :slug])]
        {:db (-> db
                 (assoc-in [:article :status]
                           (if (get-in db [:article :data]) :fetching :loading))
                 (assoc-in [:article :error] nil)
                 (update-in [:article :attempt] (fnil inc 0)))
         :fx [[:rf.http/managed
               (rh/request {:method     :get
                            :path       (article-path slug)
                            :auth?      false
                            :decode     schema/ArticleResponse
                            :retry      rh/data-fetch-retry
                            :request-id [:article/load slug]})]]}))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(rf/reg-event-fx :comments/load
  {:doc "Load comments for the current article. Uses explicit success /
         failure handlers (cf. :article/load above which uses default
         reply addressing) — both shapes are valid Spec 014; pick whichever
         reads best for the handler."
   :rf.http/decode-schemas [schema/CommentsResponse]}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:rf/route :params :slug])]
      {:db (-> db
               (assoc-in [:comments :status]
                         (if (seq (get-in db [:comments :data])) :fetching :loading))
               (assoc-in [:comments :error] nil)
               (update-in [:comments :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (comment-path slug)
                          :auth?      false
                          :decode     schema/CommentsResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:comments/load slug]
                          :on-success [:comments/loaded]
                          :on-failure [:comments/load-failed]})]]})))

(rf/reg-event-fx :comments/loaded
  [(rf/inject-cofx :realworld/now)]
  (fn [{:keys [db realworld/now]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:comments :status] :loaded)
             (assoc-in [:comments :data] (vec (:comments value)))
             (assoc-in [:comments :error] nil)
             (assoc-in [:comments :loaded-at] now))}))

(rf/reg-event-db :comments/load-failed
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:comments :status] :error)
        (assoc-in [:comments :error] (rh/failure->message failure)))))

;; ============================================================================
;; COMMENT FORM
;; ============================================================================

(rf/reg-event-db :comment-form/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:comment-form :draft field] value)
        (update-in [:comment-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :comment-form/submit
  {:doc "Optimistically post a new comment. NO retry — the user clicked
         once. The temp-id correlates the optimistic UI card with the
         eventual save / rollback (Spec 014 - explicit on-success/on-failure
         where the partial event vector pre-populates correlation args)."
   :rf.http/decode-schemas [schema/CommentResponse]}
  (fn [{:keys [db]} _]
    (let [slug      (get-in db [:rf/route :params :slug])
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
        ;; Client-side validation failure. Per Pattern-Forms
        ;; §:submit-error vs :errors: validation messages live in
        ;; `:errors` (`:_form` for whole-form, otherwise per-field);
        ;; `:submit-error` is reserved for transport / non-field
        ;; HTTP failures. Flip :submit-attempted? so the view's
        ;; per-field-error sub reveals the :body error even on a
        ;; fresh, never-:touched textarea.
        {:db (-> db
                 (assoc-in [:comment-form :submit-attempted?] true)
                 (assoc-in [:comment-form :errors] {:body "Comment body is required."})
                 (assoc-in [:comment-form :submit-error] nil))}
        {:db (-> db
                 (assoc-in [:comment-form :submit-attempted?] true)
                 (assoc-in [:comment-form :status] :submitting)
                 (assoc-in [:comment-form :submitted] {:body body})
                 (assoc-in [:comment-form :errors] {})
                 (assoc-in [:comment-form :submit-error] nil)
                 (update-in [:comments :data] (fnil conj []) temp-card))
         :fx [[:rf.http/managed
               (rh/request {:method     :post
                            :path       (comment-path slug)
                            :body       {:comment {:body body}}
                            :decode     schema/CommentResponse
                            :on-success [:comment-form/submit-success temp-id]
                            :on-failure [:comment-form/submit-error temp-id]})]]}))))

(rf/reg-event-db :comment-form/submit-success
  (fn [db [_ temp-id {:keys [value]}]]
    (let [saved (:comment value)]
      (-> db
          (assoc-in [:comment-form] (comment-form-defaults))
          (update-in [:comments :data]
                     (fn [comments]
                       (->> (or comments [])
                            (remove #(= temp-id (:id %)))
                            (concat [saved])
                            vec)))))))

(rf/reg-event-db :comment-form/submit-error
  (fn [db [_ temp-id {:keys [failure]}]]
    (-> db
        (update-in [:comments :data]
                   (fn [comments]
                     (vec (remove #(= temp-id (:id %)) comments))))
        (assoc-in [:comment-form :status] :idle)
        (assoc-in [:comment-form :submit-error]
                  (rh/failure->message failure)))))

(rf/reg-event-fx :comment/delete
  {:doc "Optimistically remove a comment, then DELETE. On failure, the
         rollback handler re-inserts the comment at its original index."}
  (fn [{:keys [db]} [_ id]]
    (let [slug     (get-in db [:rf/route :params :slug])
          comments (vec (get-in db [:comments :data]))
          index    (first (keep-indexed (fn [idx comment]
                                          (when (= id (:id comment)) idx))
                                        comments))
          prior    (when (some? index) {:index index :comment (nth comments index)})]
      {:db (update-in db [:comments :data]
                      (fn [xs] (vec (remove #(= id (:id %)) xs))))
       :fx [[:rf.http/managed
             (rh/request {:method     :delete
                          :path       (str (comment-path slug) "/" id)
                          :decode     :auto
                          :on-success [:comment/delete-success id]
                          :on-failure [:comment/delete-rollback prior]})]]})))

(rf/reg-event-db :comment/delete-success
  (fn [db _] db))

(rf/reg-event-db :comment/delete-rollback
  (fn [db [_ {:keys [index comment]} _failure-payload]]
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

(rf/reg-sub :comment-form
  (fn [db _] (:comment-form db)))

(rf/reg-sub :comment-form/field-error
  {:doc "Per-field validation error for the comment form. Per
         Pattern-Forms §Error visibility: reveal every error after the
         first submit click, OR once the field is :touched."}
  :<- [:comment-form]
  (fn [form [_ field]]
    (when (or (:submit-attempted? form)
              (contains? (:touched form) field))
      (get-in form [:errors field]))))

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
        body-error     @(subscribe [:comment-form/field-error :body])
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
              (when body-error
                [:div.error-messages body-error])
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

