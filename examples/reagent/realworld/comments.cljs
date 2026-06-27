(ns realworld.comments
  "Article detail plus comments for the RealWorld (Conduit) example.

   The article-detail page is where re-frame2's optimistic-update story really
   earns its keep: post a comment and it appears instantly, delete one and it
   vanishes instantly, and if the server later disagrees, the change quietly
   rolls back. Worth a read for:

   - `:article` and `:comments` in the plain remote-data slice shape.
   - `:comment-form` in the plain form slice shape.
   - Route-driven loads that read the current slug off the runtime-db
     coeffect at `[:rf.runtime/routing :current :params :slug]`.
   - Optimistic post / delete flows that roll back through nothing fancier
     than ordinary events."
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
;; Here's a sneaky one. When you post a comment optimistically, we conj a
;; temp card into durable app-db at `[:comments :data]` with a made-up id, and
;; later use that id as a join key to find the card again — to swap in the
;; saved comment (`:comment-form/submit-success`) or yank it back out
;; (`:comment-form/submit-error`). The catch: anything written durably or used
;; as a join key has to be reproducible on replay. A fresh `(random-uuid)`
;; minted in the handler is the opposite of reproducible — replay would mint a
;; DIFFERENT id, the join key wouldn't match, and the correlation would quietly
;; fall apart. So the id comes from a recordable coeffect instead: the supplier
;; runs once, the id is recorded onto the causal token, and replay hands back
;; the very same string. `:comment-form/submit` asks for it via
;; `:rf.cofx/requires` and reads it flat, staying pure and replayable. See the
;; coeffects guide:
;; ../../../docs/guide/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable
(rf/reg-cofx :realworld/temp-comment-id
  {:recordable? true
   :doc "A replay-safe temp-id for an optimistically-posted comment."}
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
  {:doc "Load the article named by
         `[:rf.runtime/routing :current :params :slug]` (the route lives in
         runtime-db).

         This one's a little demo of default reply addressing. Leave off
         `:on-success` / `:on-failure` and the framework loops the reply back
         to this very same event id, with `:rf/reply` merged into the message.
         So the handler runs twice for one load — once to send the request,
         once when the answer comes back — and branches on `(:rf/reply msg)`
         to tell which hat it's wearing. One event id, two roles. See the HTTP
         guide on when request and reply belong together:
         ../../../docs/resources/http.md#when-request-and-reply-belong-together"
   :rf.http/decode-schemas [schema/ArticleResponse]
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms] rt :rf.db/runtime} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply hat — the answer's back. Success or failure?
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

      ;; Request hat — first time through, fire the managed request. With no
      ;; explicit handlers, default reply addressing brings the answer right
      ;; back to this event.
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
  {:doc "Load the comments for the current article. This one names explicit
         success / failure handlers — the opposite choice from :article/load
         just above, which lets the reply default back to itself. Both styles
         are perfectly valid; reach for whichever reads more clearly in the
         handler at hand. Here, separate handlers keep the load logic tidy."
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
  {:doc "Post a new comment, optimistically — the card shows up before the
         server has weighed in. No retry; the user clicked once and means it.
         The temp-id is the thread that ties the optimistic card to its
         eventual save or rollback: it's baked into the partial event vectors
         in `:on-success` / `:on-failure` so the reply knows which card it's
         talking about. And it comes from the recordable
         `:realworld/temp-comment-id` coeffect above, never a fresh
         `random-uuid` here — see that block for the why."
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
        ;; Empty comment — fail it on the client, no round trip needed.
        ;; Validation messages live in `:errors` (`:_form` for the whole form,
        ;; otherwise keyed per field); `:submit-error` is kept for transport /
        ;; non-field HTTP failures, a separate concern. We flip
        ;; :submit-attempted? so the per-field-error sub will surface the
        ;; :body error even on a textarea the user never touched.
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
          ;; Swap the optimistic temp card for the saved comment IN PLACE,
          ;; right where it already sits. If we appended the saved one instead,
          ;; the comment would visibly jump from the bottom (where it landed
          ;; optimistically) to the top — a tiny teleport the eye absolutely
          ;; catches. Same move as favorites.cljs/update-article-in-list: map
          ;; over, replace the match, keep the order.
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
  {:doc "Whisk a comment off the screen first, then send the DELETE. If the
         server says no, the rollback handler slots the comment back in at its
         original index, as though nothing happened."}
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
  {:doc "Success means: do nothing, gracefully. The optimistic delete already
         took the comment off the screen, so there's literally nothing left to
         do — this handler exists only to give `:on-success` a target to land
         on. All the real work lives in `:comment/delete-rollback`, for the day
         the server says no."}
  (fn [{:keys [db]} _] {:db db}))

(rf/reg-event :comment/delete-rollback
  (fn [{:keys [db]} [_ {:keys [index comment]} _failure-payload]]
    {:db (if (and (some? index) comment)
      (update-in db [:comments :data]
                 (fn [xs]
                   ;; Clamp the re-insert point to the list's CURRENT length.
                   ;; The index we saved was true at optimistic-delete time, but
                   ;; the list may have shrunk since — a `:comments/loaded`
                   ;; re-fetch, or a second delete racing this one. Skip the
                   ;; clamp and `subvec` throws IndexOutOfBounds the moment
                   ;; index > (count xs), taking the whole event drain down with
                   ;; it. A little defensive arithmetic is cheaper than that.
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
;; Conduit puts a few context-aware buttons right on the article page. If
;; you're reading someone else's article, you can follow or unfollow the
;; author; if it's your own, you get Edit Article (→ /editor/:slug) and Delete
;; Article instead. Logged out, you get neither — these are auth-gated, same as
;; the favorite toggle. Note the follow here acts on the article's OWN author
;; (`[:article :data :author]`), which is a different slice from
;; profile.cljs's `:profile/follow` (that one drives the profile-page banner).
;; Same gesture, two homes.

(rf/reg-event :article/toggle-follow-author
  {:doc "Flip following-the-author on or off optimistically, then send the
         POST/DELETE to /profiles/:username/follow; restore the old flag if it
         fails. Auth-gated (same reasoning as :article/toggle-favorite): a
         logged-out click goes to login instead of firing a tokenless write the
         real Conduit backend would just 401 anyway."
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
  {:doc "Delete the current article, straight from the DETAIL page (authors
         only). No retry — it's destructive and it's one click. On success we
         head home. (The editor has its own Delete path too, in
         article_editor.cljs; both lead to the same place.)"}
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
  {:doc "Is this the reader's own article? True when the signed-in user is the
         author — which is what reveals the Edit / Delete controls on the
         detail page."}
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
  {:doc "The validation error for one comment-form field, or nil while we stay
         quiet. Same courtesy as the other forms: no error shown until the
         field is touched or the user has tried to submit. See the forms
         how-to: ../../../docs/guide/how-to/build-a-form.md"}
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

(reg-view ^{:doc "The byline-with-buttons strip. Always shows the author; then,
                  following the official Conduit template, adds Follow/Unfollow
                  if you're a visitor, or Edit / Delete if the article is yours.
                  Logged out, it's just the byline. The page renders it twice
                  (once up top in the banner, once down in the footer), exactly
                  as the official template does."}
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

       ;; Show the error view only when there's no article to fall back on. If
       ;; a re-fetch of an already-loaded article fails (`:status :error`), the
       ;; prior `:data` is still sitting there — so render it (via the `article`
       ;; branch below) instead of yanking the page out from under someone
       ;; mid-read. The rule: never blank loaded data on a refresh failure.
       ;; (tags.cljs keeps its prior `:tags` up across a `:fetch-failed` for the
       ;; same reason.)
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
           ;; The big favorite button on the detail page spells it out —
           ;; "Favorite" / "Unfavorite" — and swaps `.btn-outline-primary`
           ;; (not favorited) for `.btn-primary` (favorited); the E2E suite
           ;; checks both the text and the class. (The compact heart-only
           ;; button on the article cards stays `.btn-outline-primary`
           ;; throughout — that's the official client's behaviour there, not
           ;; an oversight here.)
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

