(ns realworld.comments
  "Article detail plus comments for the RealWorld (Conduit) example.

   This namespace shows:
   - `:article` and `:comments` in the plain remote-data slice shape.
   - `:comment-form` in the plain form slice shape.
   - Route-driven loads reading the current slug from the runtime-db
     coeffect at `[:rf.runtime/routing :current :params :slug]`.
   - Optimistic post/delete flows that roll back via ordinary events."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld-shared.avatar :as avatar]
            [realworld-shared.markdown :as md]
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

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

;; ============================================================================
;; RECORDABLE COEFFECTS
;; ============================================================================
;;
;; The optimistic temp-id is written into durable app-db (the optimistic
;; card is conj'd into `[:comments :data]`) and used as a join key — it
;; correlates the optimistic card with its eventual save
;; (`:comment-form/submit-success`) or rollback
;; (`:comment-form/submit-error`). A value written durably or used as a
;; join key must come from a recordable coeffect, not a fresh
;; `random-uuid` at the write site — otherwise replay mints a different id
;; and the correlation breaks. So this is a recordable `reg-cofx`: the
;; supplier runs once, the id is recorded onto the causal token, and replay
;; re-presents it verbatim. `:comment-form/submit` declares it via
;; `:rf.cofx/requires` and reads it flat, staying pure and replayable. See
;; the coeffects guide:
;; ../../../docs/guide/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable
(rf/reg-cofx :realworld/temp-comment-id
  {:recordable? true
   :doc "Replayable optimistic temp-id for a newly-posted comment."}
  (fn [] (str "temp-" (random-uuid))))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :article/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :article {:status :idle :data nil :error nil
                        :loaded-at nil :attempt 0})}))

(rf/reg-event :comments/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :comments {:status :idle :data [] :error nil
                         :loaded-at nil :attempt 0})}))

(rf/reg-event :comment-form/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :comment-form (comment-form-defaults))}))

;; ============================================================================
;; ARTICLE
;; ============================================================================

(rf/reg-event :article/load
  {:doc "Load the article matching
         `[:rf.runtime/routing :current :params :slug]` (the route lives in
         runtime-db).

         This handler shows default reply addressing: with no `:on-success`
         / `:on-failure`, the framework re-dispatches the reply back to this
         same event id, merging `:rf/reply` into the original message map.
         The body branches on `(:rf/reply msg)` — one event id, two roles.
         See the HTTP guide on when request and reply belong together:
         ../../../docs/resources/http.md#when-request-and-reply-belong-together"
   :rf.http/decode-schemas [schema/ArticleResponse]
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms] rt :rf.db/runtime} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply branch — handle success or failure.
      (case (:kind reply)
        :success
        {:db (-> db
                 (assoc-in [:article :status] :loaded)
                 (assoc-in [:article :data] (:article (:value reply)))
                 (assoc-in [:article :error] nil)
                 (assoc-in [:article :loaded-at] time-ms))}

        :failure
        {:db (-> db
                 (assoc-in [:article :status] :error)
                 (assoc-in [:article :error] (rh/failure->message (:failure reply))))})

      ;; Initial dispatch — issue the managed request. Default reply
      ;; addressing routes the reply back here.
      (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
        {:db (-> db
                 (assoc-in [:article :status]
                           (if (get-in db [:article :data]) :fetching :loading))
                 (assoc-in [:article :error] nil)
                 (update-in [:article :attempt] (fnil inc 0)))
         :fx [[:rf.http/managed
               (rh/request {:method     :get
                            :path       (article-path slug)
                            :decode     schema/ArticleResponse
                            :retry      rh/data-fetch-retry
                            :request-id [:article/load slug]})]]}))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(rf/reg-event :comments/load
  {:doc "Load comments for the current article. Uses explicit success /
         failure handlers (cf. :article/load above, which uses default
         reply addressing) — both shapes are valid; pick whichever reads
         best for the handler."
   :rf.http/decode-schemas [schema/CommentsResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug (get-in rt [:rf.runtime/routing :current :params :slug])]
      {:db (-> db
               (assoc-in [:comments :status]
                         (if (seq (get-in db [:comments :data])) :fetching :loading))
               (assoc-in [:comments :error] nil)
               (update-in [:comments :attempt] (fnil inc 0)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (comment-path slug)
                          :decode     schema/CommentsResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:comments/load slug]
                          :on-success [:comments/loaded]
                          :on-failure [:comments/load-failed]})]]})))

(rf/reg-event :comments/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:comments :status] :loaded)
             (assoc-in [:comments :data] (vec (:comments value)))
             (assoc-in [:comments :error] nil)
             (assoc-in [:comments :loaded-at] time-ms))}))

(rf/reg-event :comments/load-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
        (assoc-in [:comments :status] :error)
        (assoc-in [:comments :error] (rh/failure->message failure)))}))

;; ============================================================================
;; COMMENT FORM
;; ============================================================================

(rf/reg-event :comment-form/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:comment-form :draft field] value)
        (update-in [:comment-form :touched] (fnil conj #{}) field))}))

(rf/reg-event :comment-form/submit
  {:doc "Optimistically post a new comment. No retry — the user clicked
         once. The temp-id correlates the optimistic UI card with the
         eventual save / rollback: the partial event vectors in
         `:on-success` / `:on-failure` pre-populate the correlation arg. The
         temp-id comes from a recordable coeffect (the
         `:realworld/temp-comment-id` reg-cofx above), never a fresh
         `random-uuid` at the write site."
   :rf.http/decode-schemas [schema/CommentResponse]
   :rf.cofx/requires [:realworld/temp-comment-id]}
  (fn [{:keys [db] rt :rf.db/runtime temp-id :realworld/temp-comment-id} _]
    (let [slug      (get-in rt [:rf.runtime/routing :current :params :slug])
          draft     (get-in db [:comment-form :draft])
          body      (str/trim (or (:body draft) ""))
          user      (get-in db [:auth :user])
          temp-card {:id        temp-id
                     :createdAt "pending"
                     :updatedAt "pending"
                     :body      body
                     :author    {:username  (:username user)
                                 :bio       (:bio user)
                                 :image     (:image user)
                                 :following false}}]
      (if (str/blank? body)
        ;; Client-side validation failure. Validation messages live in
        ;; `:errors` (`:_form` for whole-form, otherwise per-field);
        ;; `:submit-error` is reserved for transport / non-field HTTP
        ;; failures. Flip :submit-attempted? so the view's per-field-error
        ;; sub reveals the :body error even on a fresh, never-:touched
        ;; textarea.
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

(rf/reg-event :comment-form/submit-success
  (fn [{:keys [db]} [_ temp-id {:keys [value]}]]
    {:db (let [saved (:comment value)]
      (-> db
          (assoc-in [:comment-form] (comment-form-defaults))
          ;; Replace the optimistic temp card IN PLACE with the saved
          ;; comment — preserve its position so a confirmed comment does
          ;; not visibly teleport from where it was appended (the bottom)
          ;; to the top. Mirrors favorites.cljs/update-article-in-list:
          ;; map, swap the matching entry, keep order.
          (update-in [:comments :data]
                     (fn [comments]
                       (mapv (fn [comment]
                               (if (= temp-id (:id comment)) saved comment))
                             (or comments []))))))}))

(rf/reg-event :comment-form/submit-error
  (fn [{:keys [db]} [_ temp-id {:keys [failure]}]]
    {:db (-> db
        (update-in [:comments :data]
                   (fn [comments]
                     (vec (remove #(= temp-id (:id %)) comments))))
        (assoc-in [:comment-form :status] :idle)
        (assoc-in [:comment-form :submit-error]
                  (rh/failure->message failure)))}))

(rf/reg-event :comment/delete
  {:doc "Optimistically remove a comment, then DELETE. On failure, the
         rollback handler re-inserts the comment at its original index."}
  (fn [{:keys [db] rt :rf.db/runtime} [_ id]]
    (let [slug     (get-in rt [:rf.runtime/routing :current :params :slug])
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

(rf/reg-event :comment/delete-success
  {:doc "Nothing to do on success: the optimistic delete already removed the
         comment, so confirming it is a no-op. The handler exists only as the
         `:on-success` reply target — work happens in `:comment/delete-rollback`
         when the server says no."}
  (fn [{:keys [db]} _] {:db db}))

(rf/reg-event :comment/delete-rollback
  (fn [{:keys [db]} [_ {:keys [index comment]} _failure-payload]]
    {:db (if (and (some? index) comment)
      (update-in db [:comments :data]
                 (fn [xs]
                   ;; Clamp to the CURRENT length: the captured index is from
                   ;; optimistic-delete time and the list may have shrunk since
                   ;; (a `:comments/loaded` re-fetch or a concurrent delete).
                   ;; Without the clamp, `subvec` throws IndexOutOfBounds when
                   ;; index > (count xs) and the whole event drain dies.
                   (let [xs (vec xs)
                         i  (min (max 0 index) (count xs))]
                     (vec (concat (subvec xs 0 i)
                                  [comment]
                                  (subvec xs i))))))
      db)}))

;; ============================================================================
;; ARTICLE-DETAIL SOCIAL CONTROLS
;; ============================================================================
;;
;; The official Conduit article page puts contextual controls ON THE DETAIL
;; PAGE: a non-author viewer can follow/unfollow the AUTHOR; the author sees
;; Edit Article (→ /editor/:slug) and Delete Article. Logged-out viewers see
;; neither (the controls are auth-gated like the favorite toggle). The follow
;; here targets the article's OWN author profile (`[:article :data :author]`),
;; distinct from profile.cljs's `:profile/follow` which targets the profile-page
;; banner slice.

(rf/reg-event :article/toggle-follow-author
  {:doc "Optimistically toggle following the article's author, then POST/DELETE
         /profiles/:username/follow. On failure the prior flag is restored.
         Auth-gated (same rationale as :article/toggle-favorite): a logged-out
         click navigates to login rather than issuing a tokenless write the real
         Conduit backend would 401."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate :realworld.auth/login]]]}
      (let [author    (get-in db [:article :data :author])
            username  (:username author)
            following? (:following author)]
        (if (nil? username)
          {}
          {:db (assoc-in db [:article :data :author :following] (not following?))
           :fx [[:rf.http/managed
                 (rh/request {:method     (if following? :delete :post)
                              :path       (str "/profiles/" username "/follow")
                              :decode     schema/ProfileResponse
                              :on-success [:article/author-follow-synced]
                              :on-failure [:article/author-follow-rollback following?]})]]})))))

(rf/reg-event :article/author-follow-synced
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (if-let [profile (:profile value)]
      (assoc-in db [:article :data :author] profile)
      db)}))

(rf/reg-event :article/author-follow-rollback
  (fn [{:keys [db]} [_ previous-following _failure-payload]]
    {:db (assoc-in db [:article :data :author :following] previous-following)}))

(rf/reg-event :article/delete
  {:doc "Delete the current article from the DETAIL page (author only). No
         retry — destructive, one click. On success navigate home; the editor's
         own Delete path (article_editor.cljs) remains reachable too."}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:article :data :slug])]
      (if (nil? slug)
        {}
        {:fx [[:rf.http/managed
               (rh/request {:method     :delete
                            :path       (article-path slug)
                            :decode     :auto
                            :on-success [:article/delete-success]
                            :on-failure [:article/delete-failed]})]]}))))

(rf/reg-event :article/delete-success
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home]]]}))

(rf/reg-event :article/delete-failed
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:article :error] (rh/failure->message failure))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :article/slice (fn [db _] (:article db)))
(rf/reg-sub :article/data :<- [:article/slice] (fn [slice _] (:data slice)))
(rf/reg-sub :article/status :<- [:article/slice] (fn [slice _] (:status slice)))
(rf/reg-sub :article/error :<- [:article/slice] (fn [slice _] (:error slice)))

(rf/reg-sub :article/author
  {:doc "The current article's author profile (username, image, following)."}
  :<- [:article/data]
  (fn [article _] (:author article)))

(rf/reg-sub :article/own?
  {:doc "True when the signed-in viewer is the article's author — gates the
         Edit / Delete controls on the detail page."}
  (fn [db _]
    (let [me (get-in db [:auth :user :username])]
      (and me (= me (get-in db [:article :data :author :username]))))))

(rf/reg-sub :comments/slice (fn [db _] (:comments db)))
(rf/reg-sub :comments/data :<- [:comments/slice] (fn [slice _] (:data slice)))
(rf/reg-sub :comments/status :<- [:comments/slice] (fn [slice _] (:status slice)))
(rf/reg-sub :comments/error :<- [:comments/slice] (fn [slice _] (:error slice)))

(rf/reg-sub :comment-form/draft
  (fn [db _] (get-in db [:comment-form :draft])))

(rf/reg-sub :comment-form/submitting?
  (fn [db _] (= :submitting (get-in db [:comment-form :status]))))

(rf/reg-sub :comment-form/submit-error
  (fn [db _] (get-in db [:comment-form :submit-error])))

(rf/reg-sub :comment-form/slice
  (fn [db _] (:comment-form db)))

(rf/reg-sub :comment-form/field-error
  {:doc "Per-field validation error for the comment form. Reveal every
         error after the first submit click, or once the field is :touched.
         See the forms how-to: ../../../docs/guide/how-to/build-a-form.md"}
  :<- [:comment-form/slice]
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
    [:div.card {:data-testid (str "comment-card-" (:id comment))}
     [:div.card-block [:p.card-text {:data-testid "comment-body"} (:body comment)]]
     [:div.card-footer
      [rf/route-link {:to     :realworld.profile/show
                           :params {:username (get-in comment [:author :username])}
                           :class  "comment-author"}
       [:img.comment-author-img {:src (avatar/avatar-src (get-in comment [:author :image]))}]
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

(reg-view ^{:doc "The article-detail contextual controls: the
                  author byline plus, per the official Conduit template, the
                  author's Follow/Unfollow for a non-author viewer OR Edit /
                  Delete for the author. Logged-out viewers see the byline only.
                  Rendered twice on the page (banner + footer) like the official
                  template."}
          article-meta []
  (let [article @(subscribe [:article/data])
        author  @(subscribe [:article/author])
        own?    @(subscribe [:article/own?])
        authed? @(subscribe [:auth/authenticated?])]
    [:div.article-meta
     [rf/route-link {:to :realworld.profile/show :params {:username (:username author)}}
      [:img.user-pic {:src (avatar/avatar-src (:image author))}]]
     [:div.info
      [rf/route-link {:to :realworld.profile/show :params {:username (:username author)} :class "author"}
       (:username author)]
      [:span.date (:createdAt article)]]
     (cond
       own?
       [:span
        [rf/route-link {:to :realworld.editor/edit :params {:slug (:slug article)}
                        :class "btn btn-sm btn-outline-secondary"
                        :data-testid "article-edit"}
         [:i.ion-edit] " Edit Article"]
        " "
        [:button.btn.btn-sm.btn-outline-danger
         {:type "button" :data-testid "article-delete"
          :on-click #(dispatch [:article/delete])}
         [:i.ion-trash-a] " Delete Article"]]

       authed?
       [:button.btn.btn-sm.btn-outline-secondary
        {:type "button" :data-testid "article-follow-author"
         :on-click #(dispatch [:article/toggle-follow-author])}
        [:i.ion-plus-round] " "
        (if (:following author) "Unfollow " "Follow ") (:username author)])]))

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

       ;; Only surface the error view when there is no article to show. A
       ;; failed re-fetch (`:status :error`) of an already-loaded article
       ;; leaves the prior `:data` in place — render it (the `article`
       ;; branch below) rather than blanking a page the user is reading.
       ;; Never blank loaded data on a refresh failure. (tags.cljs keeps
       ;; prior `:tags` visible across a `:fetch-failed` the same way.)
       (and article-error (nil? article))
       [:div.article-preview.error
        (str "Couldn't load article: " (pr-str article-error))]

       article
       [:<>
        [:div.banner
         [:div.container
          [:h1 {:data-testid "article-title"} (:title article)]
          [:p {:data-testid "article-description"} (:description article)]
          [:span.article-controls
           ;; The official RealWorld article-detail favorite
           ;; control shows visible "Favorite"/"Unfavorite" text and toggles
           ;; `.btn-outline-primary` (not favorited) ↔ `.btn-primary`
           ;; (favorited) — the E2E contract asserts on both. The compact
           ;; heart-only button on article cards stays `.btn-outline-primary`
           ;; (that one is correct per the official client).
           (let [favorited? (:favorited article)]
             [:button.btn.btn-sm
              {:type        "button"
               :data-testid "article-favorite"
               :class       (if favorited? "btn-primary" "btn-outline-primary")
               :on-click    #(dispatch [:article/toggle-favorite (:slug article)])}
              [:i.ion-heart] " "
              (if favorited? "Unfavorite" "Favorite") " Article "
              [:span.counter {:data-testid "article-favorites-count"}
               "(" (:favoritesCount article) ")"]])
           " "
           [article-meta]]]]
        [:div.container.page
         [:div.row.article-content
          [:div.col-md-12
           [:div {:data-testid "article-body"} (md/render (:body article))]
           [:ul.tag-list
            (for [tag (:tagList article)]
              ^{:key tag}
              [:li.tag-default.tag-pill.tag-outline tag])]]]
         [:hr]
         [:div.article-actions
          [article-meta]
          [rf/route-link {:to :realworld/home} "Back to feed"]]
         [:div.row
          [:div.col-xs-12.col-md-8.offset-md-2
           (if current-user
             [:form.card.comment-form
              {:data-testid "comment-form"
               :on-submit (fn [e]
                            (.preventDefault e)
                            (dispatch [:comment-form/submit]))}
              [:div.card-block
               [:textarea.form-control
                {:data-testid "comment-body-input"
                 :rows 3
                 :placeholder "Write a comment..."
                 :value (:body comment-draft)
                 :disabled submitting?
                 :on-change #(dispatch [:comment-form/edit-field :body (.. % -target -value)])}]]
              [:div.card-footer
               [:img.comment-author-img {:src (avatar/avatar-src (:image current-user))}]
               [:button.btn.btn-sm.btn-primary
                {:type "submit"
                 :data-testid "comment-submit"
                 :disabled submitting?}
                (if submitting? "Posting…" "Post Comment")]]
              (when body-error
                [:div.error-messages body-error])
              (when submit-error
                [:div.error-messages submit-error])]
             [:p
              [rf/route-link {:to :realworld.auth/login} "Sign in"]
              " or "
              [rf/route-link {:to :realworld.auth/register} "sign up"]
              " to add comments."])
           (when comments-error
             [:div.article-preview.error
              (str "Couldn't load comments: " (pr-str comments-error))])
           (for [comment comments]
             ^{:key (:id comment)}
             [comment-card {:comment comment :current-user current-user}])]]]]

       :else
       [:div.article-preview "No article loaded."])]))

