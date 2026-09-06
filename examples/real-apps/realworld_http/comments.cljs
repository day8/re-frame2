(ns realworld-http.comments
  "Article detail plus comments for the RealWorld (Conduit) example.

   The article-detail page is where re-frame2's optimistic-update story really
   earns its keep: post a comment and it appears instantly, delete one and it
   vanishes instantly, and if the server later disagrees, the change quietly
   rolls back. Worth a read for:

   - `:article` and `:comments` in the plain remote-data slice shape.
   - `:comment-form` in the plain form slice shape.
   - Route-driven loads that read the current slug off the runtime-db
     coeffect at `[:rf.runtime/routing :current :params :slug]`.
   - Everything that writes this page's state staying owned by the route
     identity — the reads, the comment mutations, and the article's own
     social buttons alike: each slice records the slug it is loading, every
     settle carries the slug it was requested for, and one that no longer
     belongs to the screen is refused (see `reply-for-current-slug?` and its
     route-level sibling `article-route-for-slug?` below — the same
     correlation law article_editor.cljs spells for the editor).
   - Optimistic post / delete flows that roll back through nothing fancier
     than ordinary events."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld-shared.avatar :as avatar]
            [realworld-shared.markdown :as md]
            [realworld-shared.schema :as schema]
            [realworld-http.http :as rh])
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
;; THE CORRELATION GATE — route-keyed reads stay owned by the route identity
;; ============================================================================
;;
;; Entering `/article/alpha` and then `/article/beta` puts FOUR requests in
;; flight, and nothing cancels the first pair: the per-slug `:request-id`s
;; (`[:article/load "alpha"]` vs `[:article/load "beta"]`) are deliberately
;; DISTINCT, so managed HTTP's same-id supersede never fires between them and
;; alpha's replies are delivered in full — however late. Supersede is an
;; optimisation for re-issuing the SAME read; correlating a reply with the
;; screen is the app's correctness boundary (Spec 014: navigation staleness
;; for a plain managed request is the app's, not the fx's).
;;
;; So each slice records WHICH slug it is loading (`[:article :slug]` /
;; `[:comments :slug]`, stamped by the load handler in the same write that
;; starts the load), the reply targets BELOW carry the slug they were
;; REQUESTED for, and their handlers ask this one question before writing
;; anything — data, status, error, or timestamp. A late alpha settle while
;; the slice targets beta is dropped on the floor; a beta settle (success OR
;; failure) is beta's own and lands normally. Retained data follows the same
;; law: a SAME-slug refresh keeps the prior data up while `:fetching`
;; (never blank a loaded page on a refresh), but a slug CHANGE resets the
;; slice, so alpha's article is never renderable under `/article/beta`.
;;
;; The rule is now universal in this file: EVERY settle that touches
;; route-owned state carries the slug it was requested for and asks, before
;; acting, whether that slug still owns the thing it is about to change. Ten
;; do — nine of them asking about the slice, one asking about the route (TWO
;; QUESTIONS, below, is why) —
;;
;;   - the three route-driven READS: `:article/load`'s reply hat,
;;     `:comments/loaded`, `:comments/load-failed` (rf2-iy3d6);
;;   - the three comment MUTATION settles: `:comment-form/submit-success`,
;;     `:comment-form/submit-error`, `:comment/delete-rollback`. They land on
;;     the one shared `[:comments :data]` / `[:comment-form]`, so alpha's late
;;     POST failure would otherwise banner beta's form and alpha's failed
;;     DELETE would re-insert alpha's comment into beta's list (rf2-84iek);
;;   - the four article SOCIAL settles further down:
;;     `:article/author-follow-synced`, `:article/author-follow-rollback`,
;;     `:article/delete-failed`, `:article/delete-success`. These fire from a
;;     button press rather than from the route's own load, but what they touch
;;     — `[:article ...]` and the route itself — the active article owns just
;;     the same. Measured, not merely suspected: a late follow FAILURE restored
;;     ALPHA's prior flag onto beta's author, so beta's Follow button read the
;;     opposite of the truth, and a late follow SUCCESS was worse still — it
;;     replaced beta's author map wholesale, so the byline name, the avatar and
;;     the profile link all became alpha's author. A late failed DELETE
;;     bannered alpha's error across beta's page (rf2-amhpk).
;;
;; `:article/delete-success` is the one member that writes NO db, and it is
;; here anyway. It navigates home, and NAVIGATION IS STATE — the most visible
;; state the active article owns. Delete alpha, walk to beta before the server
;; answers, and an ungated success yanks the reader off the article they chose
;; and out to the home page. That is the same ownership violation as the eight
;; above; "writes no `:db`" is a fact about the mechanism, not about who owns
;; the outcome. The strand question gets the same answer as the rest: the
;; server deletion succeeded regardless, beta is a fine place to be, and
;; returning to alpha later fails and reloads like any other missing article.
;;
;; TWO QUESTIONS, because there are two owners. Ask the one that matches the
;; OUTCOME, not the one nearest to hand:
;;
;;   - a WRITE into `[:article …]` / `[:comments …]` is the SLICE's, so
;;     `reply-for-current-slug?` asks which slug the slice is loading. That is
;;     the right owner for the nine db writes: a settle for the slug the slice
;;     still targets is that slice's own business wherever the reader has
;;     wandered off to meanwhile, and coming back to it is a same-slug refresh
;;     that clears the error and re-reads the truth anyway.
;;
;;   - a NAVIGATION is the ROUTE's, so `article-route-for-slug?` asks whether
;;     the committed route is still `/article/<that slug>`. Nothing weaker
;;     will do, because leaving an article for a NON-ARTICLE page — home, a
;;     profile, login, the editor — runs that route's own `:on-match` and
;;     never touches `[:article]`, so the slice goes on naming alpha long
;;     after alpha left the screen. Alpha → beta HIDES that (beta's
;;     `:article/load` happens to overwrite the cached slug on the way in);
;;     alpha → `/profile/eve` exposes it, and a slug-only gate took that
;;     reader home just as an ungated one did.
;;
;; Why a gate ALONE is enough for all ten, where the comment form also needed
;; a reset: everything gated here lives in a slice that `:article/load` or
;; `:comments/load` REBUILDS on a slug change, so the navigation has already
;; released it and refusing a stale settle strands nothing. `[:comment-form]`
;; was the one exception — a boot-time singleton nothing revisited, which rode
;; across the navigation still `:submitting` — which is why rf2-84iek had to
;; pair its gate with a reset in `:comments/load`. The article's author and
;; error are not in that position: `[:article]` is reset wholesale on a new
;; identity, and the Follow button carries no pending or disabled state to get
;; stuck in.
;;
;; One deliberate NON-member, so nobody reads the list as "everything in this
;; file is correlated": `:comment/delete-success` exists only to give
;; `:on-success` a target. It writes nothing and does nothing, so there is
;; nothing to correlate. Note what the test is — not "writes no db", which
;; `:article/delete-success` also satisfies while very much needing the gate,
;; but "produces no outcome the route owns".
;;
;; Both gates correlate ROUTE IDENTITY, not request identity: they ask which
;; article the screen is on, so alpha → beta → alpha readmits an alpha reply
;; issued before the round trip. That is the same strength the reads have
;; had since rf2-iy3d6, and it is deliberate — a per-request epoch or a
;; cancellation scheme would buy a much narrower race at the cost of the
;; machinery this example exists to stay clear of.
;;
;; article_editor.cljs spells this identical law for the editor
;; (`still-editing?`, with the full why-neither-request-id-nor-leafwise-seed-
;; covers-this reasoning); the resources twin spells it against the route.
(defn reply-for-current-slug?
  "Does a reply REQUESTED for `slug` still belong to the slice at
   `slice-key` (`:article` / `:comments`)? True exactly when the slug the
   reply carries equals the slug the slice currently targets."
  [db slice-key slug]
  (= slug (get-in db [slice-key :slug])))

(defn article-route-for-slug?
  "Is the ACTIVE ROUTE still the detail page for `slug`? True exactly when the
   committed route is `:realworld.article/show` and its `:slug` param is this
   one. Reads RUNTIME-db, where the route slice lives — handlers get it as the
   `:rf.db/runtime` coeffect.

   The stronger sibling of `reply-for-current-slug?`, and the question a
   settle whose outcome is a NAVIGATION has to ask: the slice's slug survives
   a walk to any non-article page, the route's does not. See TWO QUESTIONS
   above."
  [rt slug]
  (let [{:keys [route-id params]} (get-in rt [:rf.runtime/routing :current])]
    (and (= :realworld.article/show route-id)
         (= slug (:slug params)))))

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
;; ../../../docs/core/coeffects.md#two-grades-ambient-and-recordable
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
                        :loaded-at nil :attempt 0 :slug nil})}))

(rf/reg-event :comments/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :comments {:status :idle :data [] :error nil
                         :loaded-at nil :attempt 0 :slug nil})}))

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

         This one's a little demo of the unified reply spelling. Point
         `:reply-to` back at this very same event id and the framework delivers
         ONE reply — success or failure — to it, the canonical envelope
         appended as the event's last argument. So the handler runs twice for
         one load — once to send the request, once when the answer comes back —
         and branches on the envelope's `:status` to tell which hat it's
         wearing. One event id, two roles. See the HTTP guide, the one-handler
         way to handle the reply: ../../../docs/async/http.md#one-handler

         The `:reply-to` target CARRIES THE REQUESTED SLUG, so the reply hat
         can ask `reply-for-current-slug?` before writing anything — a slow
         alpha reply landing after the reader moved to `/article/beta` is
         dropped, success and failure alike. And on a slug CHANGE the request
         hat resets the slice (prior data is only kept up for a SAME-slug
         `:fetching` refresh), so the page never renders one slug's article
         under another slug's URL. See THE CORRELATION GATE above."
   :rf.http/decode-schemas [schema/ArticleResponse]
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms] rt :rf.db/runtime} [_ slug reply]]
    (if reply
      ;; Reply hat — the answer's back, carrying the slug it was requested
      ;; for. The correlation gate first: a reply whose slug is no longer the
      ;; slice's is not this screen's to act on — return nil, change nothing.
      (when (reply-for-current-slug? db :article slug)
        (case (:status reply)
          :ok
          {:db (-> db
                   (assoc-in [:article :status] :loaded)
                   (assoc-in [:article :data] (:article (:value reply)))
                   (assoc-in [:article :error] nil)
                   (assoc-in [:article :loaded-at] time-ms))}

          :error
          {:db (-> db
                   (assoc-in [:article :status] :error)
                   (assoc-in [:article :error] (rh/failure->message (:error reply))))}))

      ;; Request hat — first time through, fire the managed request. `:reply-to`
      ;; brings the one reply right back to this event, slug aboard. A
      ;; same-slug re-entry is a refresh (keep the loaded article up,
      ;; `:fetching`); a different slug is a new identity (reset the slice, so
      ;; the old article is not renderable while the new one loads).
      (let [slug     (get-in rt [:rf.runtime/routing :current :params :slug])
            refresh? (reply-for-current-slug? db :article slug)
            slice    (if refresh?
                       (:article db)
                       {:status :idle :data nil :error nil
                        :loaded-at nil :attempt 0})]
        {:db (assoc db :article
                    (-> slice
                        (assoc :slug   slug
                               :status (if (and refresh? (:data slice)) :fetching :loading)
                               :error  nil)
                        (update :attempt (fnil inc 0))))
         :fx [[:rf.http/managed
               (rh/request {:method     :get
                            :path       (article-path slug)
                            :decode     schema/ArticleResponse
                            :retry      rh/data-fetch-retry
                            :request-id [:article/load slug]
                            :reply-to   [:article/load slug]})]]}))))

;; ============================================================================
;; COMMENTS
;; ============================================================================

(rf/reg-event :comments/load
  {:doc "Load the comments for the current article. This one names explicit
         `:on-success` / `:on-failure` handlers — the split sugar, the opposite
         choice from :article/load just above, which uses the unified
         `:reply-to` back to itself. Both styles are perfectly valid; reach for
         whichever reads more clearly in the handler at hand. Here, separate
         handlers keep the load logic tidy.

         Both reply targets CARRY THE REQUESTED SLUG (the same correlation law
         as :article/load — see THE CORRELATION GATE above), and the same
         refresh-vs-new-identity split applies on the way out: a same-slug
         re-entry keeps the loaded comments up while `:fetching`; a different
         slug resets the slice.

         A new identity resets THE COMMENT FORM along with the slice, and
         that pairing is load-bearing rather than tidiness. The form is a
         single shared widget — `:comment-form/initialise` runs once at boot,
         not on the route — so without this it carries alpha's half-finished
         submission into beta: alpha's draft text, alpha's error banner, and
         (because both the textarea and the Post button are `:disabled`
         while `:status` is `:submitting`) a form beta can never type in.
         That last one is what makes it load-bearing: once the mutation
         settles are correlation-gated, a submit issued on alpha and answered
         after the reader reached beta is refused, and refusing it is only
         safe because the navigation has ALREADY released the form. Reset and
         gate are two halves of one fix (rf2-84iek)."
   :rf.http/decode-schemas [schema/CommentsResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [slug     (get-in rt [:rf.runtime/routing :current :params :slug])
          refresh? (reply-for-current-slug? db :comments slug)
          slice    (if refresh?
                     (:comments db)
                     {:status :idle :data [] :error nil
                      :loaded-at nil :attempt 0})]
      {:db (cond-> (assoc db :comments
                          (-> slice
                              (assoc :slug   slug
                                     :status (if (and refresh? (seq (:data slice))) :fetching :loading)
                                     :error  nil)
                              (update :attempt (fnil inc 0))))
             ;; New article identity → the page's form starts over too. A
             ;; SAME-slug refresh leaves it alone, so a background re-load
             ;; never eats what the reader is part-way through typing.
             (not refresh?) (assoc :comment-form (comment-form-defaults)))
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       (comment-path slug)
                          :decode     schema/CommentsResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:comments/load slug]
                          :on-success [:comments/loaded slug]
                          :on-failure [:comments/load-failed slug]})]]})))

(rf/reg-event :comments/loaded
  {:doc "The GET's `:on-success`, carrying the slug it was requested for.
         Correlation-gated: a late reply for a slug the slice no longer
         targets writes nothing — not data, not status, not the timestamp."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ slug {:keys [value]}]]
    (when (reply-for-current-slug? db :comments slug)
      {:db (-> db
               (assoc-in [:comments :status] :loaded)
               (assoc-in [:comments :data] (vec (:comments value)))
               (assoc-in [:comments :error] nil)
               (assoc-in [:comments :loaded-at] time-ms))})))

(rf/reg-event :comments/load-failed
  {:doc "The GET's `:on-failure`, correlated exactly as `:comments/loaded` is —
         a late failure for the PREVIOUS article must not mark the current
         one's comments errored."}
  (fn [{:keys [db]} [_ slug {:keys [error]}]]
    (when (reply-for-current-slug? db :comments slug)
      {:db (-> db
          (assoc-in [:comments :status] :error)
          (assoc-in [:comments :error] (rh/failure->message error)))})))

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
         `random-uuid` here — see that block for the why.

         Those same targets carry THE SLUG WE ARE POSTING TO, ahead of the
         temp-id, because the two facts answer different questions: the slug
         says WHICH PAGE this reply belongs to, the temp-id says WHICH CARD
         on it. A POST answered after the reader has moved on has no page
         left to land on, so both settles gate on the slug first — see THE
         CORRELATION GATE above."
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
                            :on-success [:comment-form/submit-success slug temp-id]
                            :on-failure [:comment-form/submit-error slug temp-id]})]]}))))

(rf/reg-event :comment-form/submit-success
  {:doc "The POST's `:on-success`, carrying the slug it was posted to.
         Correlation-gated (THE CORRELATION GATE above): a save that comes
         back for an article the reader has left writes nothing — it neither
         clears the form under the new page nor reaches into its comments.
         Nothing is stranded by that refusal: the optimistic temp card went
         with the slice when `:comments/load` reset it for the new slug, the
         comment really is saved server-side, and coming back to the article
         re-reads it from there."}
  (fn [{:keys [db]} [_ slug temp-id {:keys [value]}]]
    (when (reply-for-current-slug? db :comments slug)
      {:db (let [saved (:comment value)]
             (-> db
                 (assoc-in [:comment-form] (comment-form-defaults))
                 ;; Swap the optimistic temp card for the saved comment IN
                 ;; PLACE, right where it already sits. If we appended the
                 ;; saved one instead, the comment would visibly jump from the
                 ;; bottom (where it landed optimistically) to the top — a tiny
                 ;; teleport the eye absolutely catches. Same move as
                 ;; favorites.cljs/update-article-in-list: map over, replace the
                 ;; match, keep the order.
                 (update-in [:comments :data]
                            (fn [comments]
                              (mapv (fn [comment]
                                      (if (= temp-id (:id comment)) saved comment))
                                    (or comments []))))))})))

(rf/reg-event :comment-form/submit-error
  {:doc "The POST's `:on-failure`, correlated exactly as
         `:comment-form/submit-success` is. A post that fails for an article
         the reader has left must not banner the article now on screen with
         the previous one's error, nor flip its form's lifecycle. There is
         again nothing to strand: the temp card this would have yanked back
         out went with the slice on the slug change, and that same reset put
         the form back to `:idle`, so refusing here cannot leave the new
         page's form stuck mid-submit."}
  (fn [{:keys [db]} [_ slug temp-id {:keys [error]}]]
    (when (reply-for-current-slug? db :comments slug)
      {:db (-> db
               (update-in [:comments :data]
                          (fn [comments]
                            (vec (remove #(= temp-id (:id %)) comments))))
               (assoc-in [:comment-form :status] :idle)
               (assoc-in [:comment-form :submit-error]
                         (rh/failure->message error)))})))

(rf/reg-event :comment/delete
  {:doc "Whisk a comment off the screen first, then send the DELETE. If the
         server says no, the rollback handler slots the comment back in at its
         original index, as though nothing happened.

         The rollback target carries THE SLUG WE ARE DELETING FROM alongside
         the captured `prior`, so a failure answered after the reader moved on
         cannot slot the previous article's comment into the current one's
         list (rf2-84iek). `:on-success` needs neither: it is a no-op."}
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
                          :on-failure [:comment/delete-rollback slug prior]})]]})))

(rf/reg-event :comment/delete-success
  {:doc "Success means: do nothing, gracefully. The optimistic delete already
         took the comment off the screen, so there's literally nothing left to
         do — this handler exists only to give `:on-success` a target to land
         on. All the real work lives in `:comment/delete-rollback`, for the day
         the server says no."}
  (fn [{:keys [db]} _] {:db db}))

(rf/reg-event :comment/delete-rollback
  {:doc "The DELETE's `:on-failure`, carrying the slug it was deleting from.
         Correlation-gated: restoring a comment into a list that now belongs
         to a DIFFERENT article would be pure fabrication — the comment is not
         one of that article's. Refusing loses nothing, because the article
         this comment does belong to had its list reset on the way out, and
         the server still holds the comment (the DELETE failed), so coming
         back re-reads it."}
  (fn [{:keys [db]} [_ slug {:keys [index comment]} _failure-payload]]
    (when (reply-for-current-slug? db :comments slug)
      {:db (if (and (some? index) comment)
             (update-in db [:comments :data]
                        (fn [xs]
                          ;; Clamp the re-insert point to the list's CURRENT
                          ;; length. The index we saved was true at
                          ;; optimistic-delete time, but the list may have
                          ;; shrunk since — a `:comments/loaded` re-fetch, or a
                          ;; second delete racing this one. Skip the clamp and
                          ;; `subvec` throws IndexOutOfBounds the moment
                          ;; index > (count xs), taking the whole event drain
                          ;; down with it. A little defensive arithmetic is
                          ;; cheaper than that.
                          (let [xs (vec xs)
                                i  (min (max 0 index) (count xs))]
                            (vec (concat (subvec xs 0 i)
                                         [comment]
                                         (subvec xs i))))))
             db)})))

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
;;
;; These are button-driven rather than route-driven, but they write the
;; ROUTE-OWNED `[:article ...]` slice, so their settles carry the slug they
;; were issued on and gate on it exactly as the loads do — see THE CORRELATION
;; GATE above for what went wrong before they did (rf2-amhpk).

(rf/reg-event :article/toggle-follow-author
  {:doc "Flip following-the-author on or off optimistically, then send the
         POST/DELETE to /profiles/:username/follow; restore the old flag if it
         fails. Auth-gated (same reasoning as :article/toggle-favorite): a
         logged-out click goes to login instead of firing a tokenless write the
         real Conduit backend would just 401 anyway.

         Both reply targets CARRY THE SLUG THE FLIP WAS ISSUED ON — the slice's
         own `[:article :slug]`, the same identity the reads correlate against
         — because they write `[:article :data :author]` and the route owns it."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (if (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate {:to :realworld.auth/login}]]]}
      (let [slug       (get-in db [:article :slug])
            author     (get-in db [:article :data :author])
            username   (:username author)
            following? (:following author)]
        (if (nil? username)
          {}
          {:db (assoc-in db [:article :data :author :following] (not following?))
           :fx [[:rf.http/managed
                 (rh/request {:method     (if following? :delete :post)
                              :path       (str "/profiles/" username "/follow")
                              :decode     schema/ProfileResponse
                              :on-success [:article/author-follow-synced slug]
                              :on-failure [:article/author-follow-rollback slug following?]})]]})))))

(rf/reg-event :article/author-follow-synced
  {:doc "The follow POST/DELETE's `:on-success`, carrying the slug the flip was
         issued on. Correlation-gated, and this is the write that most needs
         it: it re-seeds the WHOLE author map, so a late reply for the article
         the reader has left would put alpha's author — name, avatar and
         profile link included — on beta's byline."}
  (fn [{:keys [db]} [_ slug {:keys [value]}]]
    (when (reply-for-current-slug? db :article slug)
      {:db (if-let [profile (:profile value)]
             (assoc-in db [:article :data :author] profile)
             db)})))

(rf/reg-event :article/author-follow-rollback
  {:doc "The follow POST/DELETE's `:on-failure`, correlated exactly as
         `:article/author-follow-synced` is. Refusing a stale rollback strands
         nothing: the optimistic flip it would undo went with the slice when
         `:article/load` reset it for the new slug, and coming back to the
         article re-reads the true flag from the server."}
  (fn [{:keys [db]} [_ slug previous-following _failure-payload]]
    (when (reply-for-current-slug? db :article slug)
      {:db (assoc-in db [:article :data :author :following] previous-following)})))

(rf/reg-event :article/delete
  {:doc "Delete the current article, straight from the DETAIL page (authors
         only). No retry — it's destructive and it's one click. On success we
         head home. (The editor has its own Delete path too, in
         article_editor.cljs; both lead to the same place.)

         BOTH reply targets carry the slug the delete was issued on — the
         failure because it banners an error, the success because it NAVIGATES.
         See THE CORRELATION GATE above."}
  (fn [{:keys [db]} _]
    (let [slug (get-in db [:article :data :slug])]
      (if (nil? slug)
        {}
        {:fx [[:rf.http/managed
               (rh/request {:method     :delete
                            :path       (article-path slug)
                            :decode     :auto
                            :on-success [:article/delete-success slug]
                            :on-failure [:article/delete-failed slug]})]]}))))

(rf/reg-event :article/delete-success
  {:doc "The DELETE's `:on-success`, carrying the slug it was issued for.
         Correlation-gated like the rest — but against the ROUTE rather than
         the slice, because what it produces is a navigation and the route is
         what owns that. Delete alpha, walk away while the server thinks about
         it, and an ungated success takes the reader off whatever they chose
         instead. Asking the slice alone would catch only the walk to another
         ARTICLE — every non-article page leaves `[:article :slug]` saying
         alpha, so the slice would answer yes and the reader would be sent
         home anyway. See TWO QUESTIONS above.

         Refusing strands nothing: the server deletion succeeded either way,
         wherever the reader has got to is a perfectly good place to be, and
         coming back to alpha later just fails and reloads like any other
         missing article."}
  (fn [{rt :rf.db/runtime} [_ slug _reply]]
    (when (article-route-for-slug? rt slug)
      {:fx [[:dispatch [:rf.route/navigate {:to :realworld/home}]]]})))

(rf/reg-event :article/delete-failed
  {:doc "The DELETE's `:on-failure`, carrying the slug it was issued for.
         Correlation-gated: `[:article :error]` sits on screen until the next
         load, so an ungated late failure banners alpha's error across whatever
         article the reader has moved on to."}
  (fn [{:keys [db]} [_ slug {:keys [error]}]]
    (when (reply-for-current-slug? db :article slug)
      {:db (assoc-in db [:article :error] (rh/failure->message error))})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :article/slice (fn [db _] (:article db)))
(rf/reg-sub :article/data {:inputs [[:article/slice]]} (fn [[slice] _] (:data slice)))
(rf/reg-sub :article/status {:inputs [[:article/slice]]} (fn [[slice] _] (:status slice)))
(rf/reg-sub :article/error {:inputs [[:article/slice]]} (fn [[slice] _] (:error slice)))

(rf/reg-sub :article/author
  {:doc "The current article's author profile (username, image, following)."
   :inputs [[:article/data]]}
  (fn [[article] _] (:author article)))

(rf/reg-sub :article/own?
  {:doc "Is this the reader's own article? True when the signed-in user is the
         author — which is what reveals the Edit / Delete controls on the
         detail page."}
  (fn [db _]
    (let [me (get-in db [:auth :user :username])]
      (and me (= me (get-in db [:article :data :author :username]))))))

(rf/reg-sub :comments/slice (fn [db _] (:comments db)))
(rf/reg-sub :comments/data {:inputs [[:comments/slice]]} (fn [[slice] _] (:data slice)))
(rf/reg-sub :comments/status {:inputs [[:comments/slice]]} (fn [[slice] _] (:status slice)))
(rf/reg-sub :comments/error {:inputs [[:comments/slice]]} (fn [[slice] _] (:error slice)))

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
         how-to: ../../../docs/core/how-to/build-a-form.md"
   :inputs [[:comment-form/slice]]}
  (fn [[form] [_ field]]
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

