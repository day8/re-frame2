(ns realworld-http.profile
  "Profile pages for the RealWorld (Conduit) example.

   A profile page is a banner up top and a tabbed list of articles below — two
   moving parts that happen to load independently, which is exactly why a
   two-region machine fits. Worth a read for:

   - One parallel state machine `:ui/profile`, two orthogonal regions
     (`:tab` x `:data`). See the machines guide on parallel regions:
     ../../../docs/machines/concepts.md#when-the-machine-grows.
   - A remote-data lifecycle folded into the `:data` region, whose
     state-keyword is the banner's status. The article items themselves live in
     app-db slices (`:profile.articles`, `:profile.favorites`), within reach of
     favorites.cljs's cross-slice optimistic updates.
   - Tab switching as `:tab` region transitions broadcast from the route's
     `:on-match` — the same move the home page makes with `:home/load`.
   - The profile view's root: a `case` over `:profile/render`, a selector sub
     reading a render-priority table against the machine's tag union.

   Three remote-data slices sit behind the view:
   - `:profile`             — the public banner (username, bio, image, following)
   - `:profile.articles`    — articles they wrote
   - `:profile.favorites`   — articles they liked

   Follow/unfollow is optimistic, and shared between the banner and any article
   cards the profile routes happen to render."
  (:require [re-frame.core :as rf]
            ;; State machines live in their own artefact; we require it to load
            ;; it, which registers the hooks that make `rf/reg-machine` (below)
            ;; and the `:rf/machine` subs resolve. See the machines guide:
            ;; ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld-shared.avatar :as avatar]
            [realworld-shared.schema :as schema]
            [realworld-http.http :as rh]
            [realworld-http.articles :as articles])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn request-slice []
  {:status :idle :data nil :error nil :loaded-at nil :attempt 0})

(defn banner-slice
  "The banner's own slice: the standard shape plus `:follow-pending?`, the
   one-at-a-time latch the follow/unfollow toggle serialises on (see
   SERIALISING THE TOGGLE, below the loads). Only this slice carries it — the
   two list slices have no mutation of their own to serialise."
  []
  (assoc (request-slice) :follow-pending? false))

;; Which profile are we on? It's in the URL. The route lives in runtime-db, and
;; `username-from-db` digs the :username param out of it (handlers receive
;; runtime-db via the `:rf.db/runtime` coeffect).
(defn username-from-db [runtime-db]
  (get-in runtime-db [:rf.runtime/routing :current :params :username]))

;; ============================================================================
;; THE CORRELATION GATE — the profile the page is on owns every settle
;; ============================================================================
;;
;; Walking `/profile/alice` → `/profile/bob` puts up to four requests in flight
;; and cancels none of them. The per-username `:request-id`s
;; (`[:profile/load "alice"]` vs `[:profile/load "bob"]`) are deliberately
;; DISTINCT, so managed HTTP's same-id supersede never fires between them and
;; alice's replies are delivered in full, however late.
;;
;; That is the framework doing exactly what it promises rather than falling
;; short of it. Supersede retires a re-issue of the SAME read — a route
;; re-match, a `?page=` step; both DO reuse the id here, so both are handled
;; for you, and a suppressed reply cannot clobber fresh data no matter how late
;; it arrives. Correlating a reply with the SCREEN is the app's correctness
;; boundary (Spec 014: navigation staleness for a plain managed request is the
;; app's, not the fx's). See ../../../docs/async/http.md#cancellation-supersession-and-abort.
;;
;; And no id scheme closes the gap, which is worth knowing before reaching for
;; one. Collapse the reads onto a page-slot id (`:request-id :profile/load`)
;; and bob's issuance would indeed supersede alice's — but the two FOLLOW
;; settles below are superseded by nothing, because arriving at bob issues no
;; new follow. `:profile/followed` re-seeds the WHOLE banner map from its
;; reply, so a late alice success would put alice's name, bio and avatar under
;; bob's URL. One law wants one mechanism, so every settle in this file carries
;; the username it was issued for and asks the question below before writing.
;;
;; Each slice records WHICH profile it is loading (`[:profile :username]`,
;; `[:profile.articles :username]`, `[:profile.favorites :username]`, stamped
;; by the load handler in the same write that starts the load). The nine
;; settles that follow — three successes, three failures, the two follow
;; settles and the follow rollback — carry that username and gate on it. A late
;; alice settle while the slice targets bob is dropped whole: no data, no
;; status, no error, no timestamp, no machine broadcast.
;;
;; The MACHINE is why "no broadcast" is in that list. `:profile/load-failed`
;; sends `:fetch-failed`, which puts the `:data` region in `:error` — a state
;; with no `fetch-succeeded` edge — so an ungated late alice failure could
;; strand a perfectly good bob banner in an error presentation for as long as
;; the reader stayed on it. Gated, no cross-identity broadcast reaches the
;; machine at all, and every path to `:loaded` for the identity the page IS on
;; passes through `:fetch-started` first. The missing edge is unreachable
;; rather than merely unused, so it stays missing.
;;
;; Retention follows the same law: a SAME-username re-entry is a refresh that
;; keeps the loaded data up while `:fetching` (never blank a page to reload
;; it), while a username CHANGE is a new identity that resets the slice, so
;; alice is never renderable under `/profile/bob`. The two list subs ask once
;; more at READ time — a list belongs to the page only while its username
;; matches the banner's — because the tabs load on separate routes, so
;; `/profile/alice` → `/profile/bob/favorites` reloads the banner and the
;; favorited list and leaves the AUTHORED list still holding alice's.
;;
;; comments.cljs spells the identical law against `:slug`, with the two-owners
;; refinement this page does not need: every settle here writes a slice, none
;; navigates, so the slice's own identity is the only question to ask.
;;
;; The `realworld_resources/` twin needs none of this, and the contrast is the
;; real lesson. Its state is keyed by resource identity — `[scope
;; :realworld/profile {:username …}]`, and its follow/unfollow mutations
;; `:populates` and `:invalidates` that same keyed entry — so alice and bob are
;; simply different cache entries and a late reply has nowhere wrong to land.
;; Here the page owns one shared slice per region, and a shared slice has to
;; carry its identity by hand.
(defn reply-for-current-profile?
  "Does a reply REQUESTED for `username` still belong to the slice at
   `slice-key` (`:profile` / `:profile.articles` / `:profile.favorites`)? True
   exactly when the username the reply carries equals the one the slice
   currently targets."
  [db slice-key username]
  (= username (get-in db [slice-key :username])))

;; ============================================================================
;; THE MACHINE — :ui/profile  (one machine, two regions)
;; ============================================================================
;;
;; Two independent things about a profile page, so two regions:
;;
;;   :tab    — which article list is showing, authored or favorited. Driven by
;;             `:show-articles` / `:show-favorites` broadcasts from the route's
;;             `:on-match` (see `:realworld.profile/show` and
;;             `:realworld.profile/favorites` in routing.cljs). Its
;;             state-keyword tells the view which app-db slice to render.
;;
;;   :data   — where the BANNER fetch is in its life. The slice still sets a
;;             `:status` field for parity with its siblings, but it's this
;;             region's state-keyword that gates the page render. The two
;;             article-list slices keep their own slice shape and load on their
;;             own schedule.
;;
;; Parallel machine, so the usual deal: every event hits every region, the
;; region event names stay distinct to avoid crosstalk, and each region treats
;; `:reset` as a self-target.

(rf/defmachine profile-machine
  {:type :parallel

   ;; The banner's latest error map. The profile/article items live in
   ;; app-db slices.
   :data {:error nil}

   :actions
   {:set-error
    (fn action-set-error [{data :data [_ {:keys [failure]}] :event}]
      {:data (assoc data :error failure)})

    :clear-error
    (fn action-clear-error [{data :data}]
      {:data (assoc data :error nil)})}

   :regions
   {;; ---- :data region — the data lifecycle for the banner ----
    :data
    {:initial :nothing
     :states
     {:nothing
      {:tags #{:data/nothing}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :loading
      ;; First fetch in flight; banner not yet rendered.
      {:tags #{:data/loading :data/transient}
       :on   {:fetch-succeeded {:target :loaded :action :clear-error}
              :fetch-failed    {:target :error  :action :set-error}
              :reset           :nothing}}

      :refreshing
      ;; A reload while the banner's still on screen. Tagged :data/loaded so
      ;; render-priority keeps the `:loaded` view up (no flicker); the
      ;; :data/refreshing tag is there if a view wants to show a subtle
      ;; "updating" hint.
      {:tags #{:data/loaded :data/refreshing :data/transient}
       :on   {:fetch-succeeded {:target :loaded :action :clear-error}
              :fetch-failed    {:target :error  :action :set-error}
              :reset           :nothing}}

      :loaded
      {:tags #{:data/loaded}
       :on   {:fetch-started :refreshing
              :reset         :nothing}}

      :error
      {:tags #{:data/error}
       :on   {:fetch-started :loading
              :reset         :nothing}}}}

    ;; ---- :tab region — which article list the view renders ----
    :tab
    {:initial :articles
     :states
     {:articles
      ;; `/profile/:username` — reads the :profile.articles slice.
      {:tags #{:tab/articles}
       :on   {:show-favorites :favorites
              :show-articles  :articles
              :reset          :articles}}

      :favorites
      ;; `/profile/:username/favorites` — reads :profile.favorites.
      {:tags #{:tab/favorites}
       :on   {:show-articles  :articles
              :show-favorites :favorites
              :reset          :articles}}}}}})

(rf/reg-machine :ui/profile profile-machine)

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :profile/initialise
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc :profile (banner-slice))
             (assoc :profile.articles (assoc (request-slice) :data []))
             (assoc :profile.favorites (assoc (request-slice) :data [])))
     :fx [[:dispatch [:ui/profile [:reset]]]]}))

;; ============================================================================
;; LOADS
;; ============================================================================

(rf/reg-event :profile/load
  {:doc "Fetch the public profile banner — username, bio, image, following.
         Public endpoint, so the house data-fetch retry applies. Broadcasts
         `:fetch-started` into the `:ui/profile` machine, nudging the `:data`
         region to `:loading` (or `:refreshing`, if a banner's already up).

         Stamps the username the slice is loading and CARRIES it on both reply
         targets, so a settle for a profile the reader has left writes nothing
         — see THE CORRELATION GATE above. A same-username re-entry is a
         refresh (keep the banner up, `:fetching`); a different username is a
         new identity, so the slice resets and alice's banner is never
         renderable under `/profile/bob`."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [username (username-from-db rt)
          refresh? (reply-for-current-profile? db :profile username)
          ;; A refresh keeps the slice — including a follow still in flight,
          ;; whose settle is still coming and still correlated. A new identity
          ;; builds a fresh banner slice, which is also how the toggle's latch
          ;; is released on navigation: it goes with the slice it belonged to.
          slice    (if refresh? (:profile db) (banner-slice))]
      {:db (assoc db :profile
                  (-> slice
                      (assoc :username username
                             :status   (if (and refresh? (:data slice)) :fetching :loading)
                             :error    nil)
                      (update :attempt (fnil inc 0))))
       :fx [[:dispatch [:ui/profile [:fetch-started]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       (str "/profiles/" username)
                          :decode     schema/ProfileResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile/load username]
                          :on-success [:profile/loaded username]
                          :on-failure [:profile/load-failed username]})]]})))

(rf/reg-event :profile/loaded
  {:doc "The banner GET's `:on-success`, carrying the username it was requested
         for. Correlation-gated: a late reply for a profile the slice no longer
         targets writes nothing — not data, not status, not the timestamp — and
         broadcasts nothing into the machine."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ username {:keys [value]}]]
    (when (reply-for-current-profile? db :profile username)
      {:db (-> db
               (assoc-in [:profile :status] :loaded)
               (assoc-in [:profile :data] (:profile value))
               (assoc-in [:profile :error] nil)
               (assoc-in [:profile :loaded-at] time-ms))
       :fx [[:dispatch [:ui/profile [:fetch-succeeded]]]]})))

(rf/reg-event :profile/load-failed
  {:doc "The banner GET's `:on-failure`, correlated exactly as `:profile/loaded`
         is — and this is the gate that keeps the MACHINE honest. An ungated
         late failure sends the `:data` region to `:error`, which has no
         `fetch-succeeded` edge, so it would strand the current profile's page
         in an error presentation even after that profile's own load succeeded."}
  (fn [{:keys [db]} [_ username {:keys [error]}]]
    (when (reply-for-current-profile? db :profile username)
      (let [message (rh/failure->message error)]
        {:db (-> db
                 (assoc-in [:profile :status] :error)
                 (assoc-in [:profile :error] message))
         :fx [[:dispatch [:ui/profile [:fetch-failed {:failure message}]]]]}))))

(rf/reg-event :profile.articles/load
  {:doc "Fetch the articles this user wrote. Public; house retry. Also
         broadcasts `:show-articles` so the `:ui/profile` :tab region knows
         which tab is live. `?page=` paginates via `rh/paginate-path` (the
         official RealWorld limit/offset shape), which also URL-encodes the
         `:author` filter.

         Same correlation law as the banner: the slice records the username it
         is loading, both reply targets carry it, and a username change resets
         the list. The `?page=` step does NOT — it reuses `:request-id`, so
         managed HTTP supersedes the previous page's reply for you, and the
         rows stay up while `:fetching`."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [username (username-from-db rt)
          page     (or (get-in rt [:rf.runtime/routing :current :query :page]) 1)
          path     (rh/paginate-path "/articles" {:author username} page)
          refresh? (reply-for-current-profile? db :profile.articles username)
          slice    (if refresh? (:profile.articles db) (assoc (request-slice) :data []))]
      {:db (assoc db :profile.articles
                  (-> slice
                      (assoc :username username
                             :status   (if (and refresh? (seq (:data slice))) :fetching :loading)
                             :error    nil)
                      (update :attempt (fnil inc 0))))
       :fx [[:dispatch [:ui/profile [:show-articles]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile.articles/load username]
                          :on-success [:profile.articles/loaded username]
                          :on-failure [:profile.articles/load-failed username]})]]})))

(rf/reg-event :profile.articles/loaded
  {:doc "The authored-list GET's `:on-success`, carrying the username it was
         requested for and gated on it — see THE CORRELATION GATE above."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ username {:keys [value]}]]
    (when (reply-for-current-profile? db :profile.articles username)
      {:db (-> db
               (assoc-in [:profile.articles :status] :loaded)
               (assoc-in [:profile.articles :data] (vec (:articles value)))
               (assoc-in [:profile.articles :articles-count]
                         (or (:articlesCount value) (count (:articles value))))
               (assoc-in [:profile.articles :loaded-at] time-ms))})))

(rf/reg-event :profile.articles/load-failed
  {:doc "The authored-list GET's `:on-failure`, correlated exactly as
         `:profile.articles/loaded` is — a late failure for the previous
         profile must not mark the current one's list errored."}
  (fn [{:keys [db]} [_ username {:keys [error]}]]
    (when (reply-for-current-profile? db :profile.articles username)
      {:db (-> db
          (assoc-in [:profile.articles :status] :error)
          (assoc-in [:profile.articles :error] (rh/failure->message error)))})))

(rf/reg-event :profile.favorites/load
  {:doc "Fetch the articles this user favorited. Public; house retry. Also
         broadcasts `:show-favorites` so the `:ui/profile` :tab region knows
         which tab is live. `?page=` paginates via `rh/paginate-path` (the
         official RealWorld limit/offset shape), which also URL-encodes the
         `:favorited` filter.

         Correlated exactly as the authored list is — the slice records the
         username it is loading, both reply targets carry it, a username change
         resets the list, and a `?page=` step is left to managed HTTP's same-id
         supersede."
   :rf.http/decode-schemas [schema/ArticlesResponse]}
  (fn [{:keys [db] rt :rf.db/runtime} _]
    (let [username (username-from-db rt)
          page     (or (get-in rt [:rf.runtime/routing :current :query :page]) 1)
          path     (rh/paginate-path "/articles" {:favorited username} page)
          refresh? (reply-for-current-profile? db :profile.favorites username)
          slice    (if refresh? (:profile.favorites db) (assoc (request-slice) :data []))]
      {:db (assoc db :profile.favorites
                  (-> slice
                      (assoc :username username
                             :status   (if (and refresh? (seq (:data slice))) :fetching :loading)
                             :error    nil)
                      (update :attempt (fnil inc 0))))
       :fx [[:dispatch [:ui/profile [:show-favorites]]]
            [:rf.http/managed
             (rh/request {:method     :get
                          :path       path
                          :decode     schema/ArticlesResponse
                          :retry      rh/data-fetch-retry
                          :request-id [:profile.favorites/load username]
                          :on-success [:profile.favorites/loaded username]
                          :on-failure [:profile.favorites/load-failed username]})]]})))

(rf/reg-event :profile.favorites/loaded
  {:doc "The favorited-list GET's `:on-success`, carrying the username it was
         requested for and gated on it — see THE CORRELATION GATE above."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ username {:keys [value]}]]
    (when (reply-for-current-profile? db :profile.favorites username)
      {:db (-> db
               (assoc-in [:profile.favorites :status] :loaded)
               (assoc-in [:profile.favorites :data] (vec (:articles value)))
               (assoc-in [:profile.favorites :articles-count]
                         (or (:articlesCount value) (count (:articles value))))
               (assoc-in [:profile.favorites :loaded-at] time-ms))})))

(rf/reg-event :profile.favorites/load-failed
  {:doc "The favorited-list GET's `:on-failure`, correlated exactly as
         `:profile.favorites/loaded` is."}
  (fn [{:keys [db]} [_ username {:keys [error]}]]
    (when (reply-for-current-profile? db :profile.favorites username)
      {:db (-> db
          (assoc-in [:profile.favorites :status] :error)
          (assoc-in [:profile.favorites :error] (rh/failure->message error)))})))

;; ============================================================================
;; FOLLOW / UNFOLLOW
;; ============================================================================
;;
;; Button-driven rather than route-driven, but they write the ROUTE-OWNED
;; `[:profile ...]` slice, so their settles carry the username they were issued
;; on and gate on it exactly as the loads do. This pair is why the gate is the
;; mechanism and a request-id scheme is not: arriving at bob issues no new
;; follow, so nothing supersedes an alice follow still in flight, and
;; `:profile/followed` re-seeds the WHOLE banner map from its reply. See THE
;; CORRELATION GATE above.
;;
;; The issuing username comes from `[:profile :username]` — the identity the
;; slice recorded and the settle will be gated against — rather than from the
;; route, so the URL written, the flag flipped and the question asked on the
;; way back are all one fact. It is the same move comments.cljs's
;; `:article/toggle-follow-author` makes with `[:article :slug]`.
;;
;; No `:request-id` here, deliberately, and the same for every other mutation
;; in this app. It WOULD make a rapid Follow→Unfollow supersede its
;; predecessor, but supersede aborts the request as well as suppressing the
;; reply, and abort-mid-write is a decision a POST deserves to have made about
;; it on purpose: an aborted request is no proof the server declined to perform
;; the write.
;;
;; SERIALISING THE TOGGLE
;;
;; Declining supersession leaves a second race, and the correlation gate above
;; does NOT cover it — both replies are for the SAME profile, so both pass.
;; Starting unfollowed: Follow flips `:following` true and issues the POST; the
;; button now reads "Unfollow", so a second click issues the DELETE. Let the
;; DELETE settle first and the older POST settle last, and `:profile/followed`
;; re-seeds the whole banner from the STALE reply — the page ends up showing
;; followed when the reader's last intent was to unfollow. Rollback ordering
;; inverts the same way. Arrival order is not intent order, and nothing in a
;; username-keyed gate can tell these two apart.
;;
;; So the toggle is SERIALISED rather than superseded. `:follow-pending?` on
;; the banner slice latches while a mutation is in flight; both handlers refuse
;; a second intent while it is set; the button disables itself on it; and every
;; correlated settle — success or rollback — clears it. One mutation per
;; profile at a time means there is never a pair to reorder. That is the
;; smallest policy honest about both facts above: abort is not proof, and
;; arrival order is not intent order.
;;
;; The alternative — keep rapid replacement, carry a generation, and reconcile
;; against server truth — buys a responsiveness this page has no need for, and
;; costs a reconciliation it would then have to get right. Not worth it here.
;; Navigating away needs no handling of its own: a new username rebuilds the
;; banner slice and the latch goes with it.

(rf/reg-event :profile/follow
  {:doc "Mark the profile followed right away, then reconcile when the reply
         lands. Auth-gated (same reasoning as
         favorites.cljs/:article/toggle-favorite): following needs a session,
         so a logged-out click heads to login instead of flipping `:following`
         optimistically only to walk it back after the backend 401s.

         Refuses outright while a follow/unfollow is already in flight — see
         SERIALISING THE TOGGLE above."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (cond
      (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate {:to :realworld.auth/login}]]]}

      ;; One mutation per profile at a time. The button is disabled while this
      ;; is set, so this is the belt to the view's braces — a refused click,
      ;; not a queued one.
      (get-in db [:profile :follow-pending?])
      {}

      :else
      (let [username (get-in db [:profile :username])]
        {:db (-> db
                 (assoc-in [:profile :data :following] true)
                 (assoc-in [:profile :follow-pending?] true))
         :fx [[:rf.http/managed
               (rh/request {:method     :post
                            :path       (str "/profiles/" username "/follow")
                            :decode     schema/ProfileResponse
                            :on-success [:profile/followed username]
                            :on-failure [:profile/follow-rollback username false]})]]}))))

(rf/reg-event :profile/followed
  {:doc "The follow POST's `:on-success`, carrying the username the flip was
         issued on. This is the write that most needs the gate: it re-seeds the
         whole banner map, so a late reply for a profile the reader has left
         would put that profile's name, bio and avatar under the current URL.
         Releases the toggle's latch, so the button comes back."}
  (fn [{:keys [db]} [_ username {:keys [value]}]]
    (when (reply-for-current-profile? db :profile username)
      {:db (-> db
               (assoc-in [:profile :data] (:profile value))
               (assoc-in [:profile :follow-pending?] false))})))

(rf/reg-event :profile/unfollow
  {:doc "Clear the followed flag right away, then reconcile on the reply.
         Auth-gated and serialised, same as `:profile/follow` above."
   :rf.http/decode-schemas [schema/ProfileResponse]}
  (fn [{:keys [db]} _]
    (cond
      (nil? (get-in db [:auth :user]))
      {:fx [[:dispatch [:rf.route/navigate {:to :realworld.auth/login}]]]}

      (get-in db [:profile :follow-pending?])
      {}

      :else
      (let [username (get-in db [:profile :username])]
        {:db (-> db
                 (assoc-in [:profile :data :following] false)
                 (assoc-in [:profile :follow-pending?] true))
         :fx [[:rf.http/managed
               (rh/request {:method     :delete
                            :path       (str "/profiles/" username "/follow")
                            :decode     schema/ProfileResponse
                            :on-success [:profile/unfollowed username]
                            :on-failure [:profile/follow-rollback username true]})]]}))))

(rf/reg-event :profile/unfollowed
  {:doc "The unfollow DELETE's `:on-success`, correlated and latch-releasing
         exactly as `:profile/followed` is."}
  (fn [{:keys [db]} [_ username {:keys [value]}]]
    (when (reply-for-current-profile? db :profile username)
      {:db (-> db
               (assoc-in [:profile :data] (:profile value))
               (assoc-in [:profile :follow-pending?] false))})))

(rf/reg-event :profile/follow-rollback
  {:doc "The shared `:on-failure` for both, correlated the same way. Refusing a
         stale rollback strands nothing: the optimistic flip it would undo went
         with the slice when `:profile/load` reset it for the new username, and
         returning to that profile re-reads the true flag from the server. The
         latch went with that slice too, so there is nothing left latched."}
  (fn [{:keys [db]} [_ username previous-value _failure-payload]]
    (when (reply-for-current-profile? db :profile username)
      {:db (-> db
               (assoc-in [:profile :data :following] previous-value)
               (assoc-in [:profile :follow-pending?] false))})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :profile/slice   (fn [db _] (:profile db)))
(rf/reg-sub :profile/data    :<- [:profile/slice] (fn [s _] (:data s)))
(rf/reg-sub :profile/error   :<- [:profile/slice] (fn [s _] (:error s)))

(rf/reg-sub :profile/follow-pending?
  {:doc "Is a follow/unfollow mutation in flight for the profile on screen? The
         toggle is serialised on this (see SERIALISING THE TOGGLE), so the
         button reads it to disable itself while a reply is outstanding —
         ask, don't tell."}
  :<- [:profile/slice]
  (fn sub-profile-follow-pending? [s _] (boolean (:follow-pending? s))))

;; The read-time half of THE CORRELATION GATE. The gate on the settles keeps a
;; stale reply from LANDING in a slice; this keeps an already-landed list from
;; being RENDERED under the wrong profile — a different question, because the
;; two tabs load on separate routes. `/profile/alice` → `/profile/bob/favorites`
;; reloads the banner and the favorited list, and never touches the authored
;; one, which goes on holding alice's articles quite legitimately. What it must
;; not do is show them, or count them, under bob's URL.
;;
;; Asking here rather than leaning on the `:tab` region is deliberate: the tab
;; broadcast (`:show-articles` / `:show-favorites`) rides in the load handler's
;; `:fx`, so it lands a beat after the route does, and an invariant this plain
;; should not depend on which of two dispatches wins.
(defn list-for-current-profile
  "The list slice at `slice-key`, but only while it belongs to the profile the
   BANNER is on. `nil` when the identities disagree, which every caller reads
   as empty."
  [db slice-key]
  (when (= (get-in db [slice-key :username])
           (get-in db [:profile :username]))
    (get db slice-key)))

(rf/reg-sub :profile.articles/data
  (fn [db _] (:data (list-for-current-profile db :profile.articles))))

(rf/reg-sub :profile.favorites/data
  (fn [db _] (:data (list-for-current-profile db :profile.favorites))))

(rf/reg-sub :profile/own-profile?
  (fn [db _]
    (= (get-in db [:auth :user :username])
       (get-in db [:profile :data :username]))))

;; ---- render-priority + :profile/render selector ----
;;
;; Same data-driven pattern as everywhere else: a plain vector of {:tag :render}
;; pairs, read in order. `:profile/render` looks at the machine's active tags
;; and returns the first matching :render, and the view's `case` on that
;; keyword is the only branch site.
;;
;; The order is the policy: the data lifecycle wins — `:loading` (first-load
;; spinner) over `:error` over `:loaded`. `:refreshing` resolves to `:loaded`,
;; so the existing banner stays put during a reload.

(def render-priority
  [{:tag :data/loading :render :loading}
   {:tag :data/error   :render :error}
   {:tag :data/loaded  :render :loaded}
   {:tag :data/nothing :render :nothing}])

(rf/reg-sub :profile/render
  {:doc "Reduce the `:ui/profile` machine's active tags to one render keyword,
         via the render-priority table. The root view's `case` on it is the
         only branch site."}
  :<- [:rf/machine :ui/profile]
  (fn sub-profile-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

(rf/reg-sub :profile/current-articles
  {:doc "Whichever article list the active tab calls for: the `:tab` region's
         state picks the app-db slice — favorited vs authored."}
  :<- [:rf/machine :ui/profile]
  :<- [:profile.articles/data]
  :<- [:profile.favorites/data]
  (fn sub-current-articles [[snap authored favorited] _]
    (case (get-in snap [:state :tab])
      :favorites (or favorited [])
      (or authored []))))

;; ---- pagination (official RealWorld limit/offset) ----

(rf/reg-sub :profile.articles/count
  (fn [db _] (:articles-count (list-for-current-profile db :profile.articles) 0)))

(rf/reg-sub :profile.favorites/count
  (fn [db _] (:articles-count (list-for-current-profile db :profile.favorites) 0)))

(rf/reg-sub :profile/current-count
  {:doc "Grand article count for the active profile tab — drives the page
         count for the tab's `?page=` control."}
  :<- [:rf/machine :ui/profile]
  :<- [:profile.articles/count]
  :<- [:profile.favorites/count]
  (fn sub-current-count [[snap authored-count favorited-count] _]
    (case (get-in snap [:state :tab])
      :favorites (or favorited-count 0)
      (or authored-count 0))))

(rf/reg-sub :profile/current-page
  {:doc "The 1-indexed current page for the active profile tab, read off the
         route query (`?page=`; defaulted to 1 by `:query-defaults`)."}
  :<- [:rf.route/query]
  (fn sub-profile-page [query _]
    (or (:page query) 1)))

(rf/reg-sub :profile/page-count
  {:doc "Total pages for the active profile tab — `(ceil count / page-size)`,
         never below 1."}
  :<- [:profile/current-count]
  (fn sub-profile-page-count [total _]
    (rh/page-count total)))

(rf/reg-event :profile/show-page
  {:doc "Jump to a 1-indexed page within the active profile tab. It stays on
         the same route (authored vs favorited) and the same username — only
         `?page=` changes. That's enough to re-fire the route `:on-match`,
         which re-runs the tab's load with the new limit/offset window. The URL
         is the input; the data follows."}
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [{:keys [route-id params]} (get-in rt [:rf.runtime/routing :current])]
      {:fx [[:dispatch [:rf.route/navigate {:to route-id :params params :query {:page page}}]]]})))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "The :loading view — first banner fetch in flight."}
          profile-loading []
  [:div.article-preview "Loading profile…"])

(reg-view ^{:doc "The :error view — the banner fetch fell over."}
          profile-error []
  (let [err @(subscribe [:profile/error])]
    [:div.article-preview.error
     (str "Couldn't load profile: " (pr-str err))]))

(reg-view ^{:doc "The :nothing view — the brief placeholder before any fetch."}
          profile-nothing []
  [:div.article-preview "No profile loaded."])

(reg-view ^{:doc "The :loaded view — the real thing: banner, tabs, and the
                  active tab's article list."}
          profile-loaded []
  (let [profile      @(subscribe [:profile/data])
        own?         @(subscribe [:profile/own-profile?])
        follow-busy? @(subscribe [:profile/follow-pending?])
        on-favs?     @(rf/subscribe [:rf.machine/has-tag? :ui/profile :tab/favorites])
        articles*    @(subscribe [:profile/current-articles])
        current-page @(subscribe [:profile/current-page])
        page-count   @(subscribe [:profile/page-count])]
    [:<>
     [:div.user-info
      [:div.container
       [:div.row
        [:div.col-xs-12.col-md-10.offset-md-1
         [:img.user-img {:src (avatar/avatar-src (:image profile))}]
         [:h4 (:username profile)]
         [:p (:bio profile)]
         (if own?
           [rf/route-link {:to :realworld.user/settings
                                :class "btn btn-sm btn-outline-secondary action-btn"}
            [:i.ion-gear-a] " Edit Profile Settings"]
           [:button.btn.btn-sm.btn-outline-secondary.action-btn
            {:type "button"
             ;; Disabled while a follow/unfollow is in flight — the toggle is
             ;; serialised, and the button says so rather than accepting a
             ;; click the handler would only refuse. The handler refuses it
             ;; anyway; a view is an affordance, not a guarantee.
             :disabled follow-busy?
             :on-click #(dispatch [(if (:following profile)
                                     :profile/unfollow
                                     :profile/follow)])}
            (if (:following profile) "Unfollow " "Follow ")
            (:username profile)])]]]]
     [:div.container
      [:div.row
       [:div.col-xs-12.col-md-10.offset-md-1
        [:div.articles-toggle
         [:ul.nav.nav-pills.outline-active
          [:li.nav-item
           [rf/route-link {:to     :realworld.profile/show
                                :params {:username (:username profile)}
                                :class  (str "nav-link" (when-not on-favs? " active"))}
            "My Articles"]]
          [:li.nav-item
           [rf/route-link {:to     :realworld.profile/favorites
                                :params {:username (:username profile)}
                                :class  (str "nav-link" (when on-favs? " active"))}
            "Favorited Articles"]]]]
        (if (seq articles*)
          (for [article articles*]
            ^{:key (:slug article)}
            [articles/article-preview {:article article}])
          [:div.article-preview.empty-feed-message "No articles here yet."])
        [articles/pagination {:current-page current-page
                              :page-count   page-count
                              :on-select    #(dispatch [:profile/show-page %])}]]]]]))

(reg-view profile-page []
  (let [render-mode @(subscribe [:profile/render])]
    [:div.profile-page
     (case render-mode
       :loading [profile-loading]
       :error   [profile-error]
       :loaded  [profile-loaded]
       :nothing [profile-nothing]
       [profile-nothing])]))
