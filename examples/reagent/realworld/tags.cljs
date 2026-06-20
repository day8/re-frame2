(ns realworld.tags
  "Popular-tags list plus home-page query helpers (`?tag=` / `?feed=`).

   This namespace demonstrates the **`:data-region` machine variant** of
   Pattern-RemoteData: the popular-tags lifecycle is modelled
   as a single-region state machine — `:realworld/tags` — whose
   state-keyword IS the Pattern-RemoteData status. Every other
   remote-data resource in realworld (`:articles`, `:feed`, `:article`,
   `:comments`, `:profile`, `:profile.articles`, `:profile.favorites`)
   stays in the original 5-key slice form, so the two shapes sit
   side-by-side and a reader can compare. The README's
   §'Pattern-RemoteData — two shapes side-by-side' has the worked
   comparison plus a 'when to choose each' note.

   The shape:

   - The Pattern-RemoteData status enum (`:idle :loading :fetching
     :loaded :error`) maps **one-to-one** onto machine states; the
     slice's `:status` field disappears.
   - The items, error, loaded-at, and attempt fields live in the
     machine's shared `:data` map (no separate app-db slice).
   - The slice's `:loading?` / `:fetching?` derived boolean subs
     collapse into per-state `:tags` queried with `rf/machine-has-tag?`.

   Routing pieces (`:home/load`, `:home/show-global-feed`, etc.) sit
   below — they dispatch `:tags/load` so the popular-tags machine fetches
   when the home route activates."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine (called
            ;; below at ns-load) and the `:rf/machine` / `:rf/machine-has-tag?`
            ;; framework subs resolve.
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh]))

;; ============================================================================
;; THE MACHINE — :realworld/tags  (one region; Pattern-RemoteData lifecycle)
;; ============================================================================
;;
;; The Pattern-RemoteData status enum maps one-to-one onto machine
;; states. Compare with the slice form used by `:articles`,
;; `:feed`, `:article`, ...:
;;
;;     ;; SLICE FORM (used by the other seven remote-data resources)
;;     ;; The slice carries an explicit :status keyword.
;;     {:status :loading :data [] :error nil :loaded-at nil :attempt 0}
;;
;;     ;; MACHINE FORM (used here)
;;     ;; The state-keyword IS the status; the rest lives in :data.
;;     {:state :loading :data {:tags [] :error nil :loaded-at nil :attempt 0} :tags #{...}}
;;
;; Pattern-RemoteData's two view-load-bearing booleans:
;;
;;     :loading?   = (= status :loading)                        ;; truly empty + in-flight
;;     :fetching?  = (#{:loading :fetching} status)             ;; any in-flight
;;
;; become tag-shaped queries against the active state:
;;
;;     :loading?   = @(rf/machine-has-tag? :realworld/tags :tags/loading)
;;     :fetching?  = @(rf/machine-has-tag? :realworld/tags :tags/in-flight)
;;
;; The view doesn't need to know which state-keyword carries the
;; "in-flight" intent; the tag does. That is the load-bearing
;; pedagogical move.

(def tags-machine
  {:initial :idle
   :data    {:tags     []
             :error    nil
             :loaded-at nil
             :attempt  0}
   ;; Validates the snapshot's :data slot at every macrostep boundary.
   ;; The snapshot lives in runtime-db, so this — not an app-schema — is
   ;; the validation surface (EP-0001, Mike ruling #11).
   :data-schema schema/TagsData

   :actions
   {:bump-attempt
    (fn action-bump-attempt [{data :data}]
      {:data (-> data
                 (update :attempt (fnil inc 0))
                 (assoc  :error nil))})

    :set-tags
    ;; :fetch-succeeded carries the resolved tags vector under :tags.
    (fn action-set-tags [{data :data [_ {:keys [tags now]}] :event}]
      {:data (-> data
                 (assoc :tags (vec tags))
                 (assoc :error nil)
                 (assoc :loaded-at now))})

    :set-error
    (fn action-set-error [{data :data [_ {:keys [failure]}] :event}]
      {:data (assoc data :error failure)})

    :reset-data
    (fn action-reset-data [_]
      {:data {:tags [] :error nil :loaded-at nil :attempt 0}})}

   :states
   {:idle
    ;; Never fetched; or just :reset. The slice's `:status :idle`.
    {:tags #{:tags/idle}
     :on   {:fetch-started {:target :loading :action :bump-attempt}
            :reset         {:target :idle    :action :reset-data}}}

    :loading
    ;; First fetch in flight; no prior :tags. The :tags/in-flight tag
    ;; lights up on both :loading and :fetching so the view doesn't have
    ;; to disjoin two state-keywords.
    {:tags #{:tags/loading :tags/in-flight :tags/transient}
     :on   {:fetch-succeeded {:target :loaded :action :set-tags}
            :fetch-failed    {:target :error  :action :set-error}
            :reset           {:target :idle   :action :reset-data}}}

    :fetching
    ;; Re-fetch in flight while prior :tags are still rendered. Per
    ;; Pattern-RemoteData: never blank the page; a subtle progress
    ;; indicator at most. The :tags/loaded tag stays present so the
    ;; render path that consumes :tags is unaffected.
    {:tags #{:tags/fetching :tags/in-flight :tags/loaded :tags/transient}
     :on   {:fetch-succeeded {:target :loaded :action :set-tags}
            :fetch-failed    {:target :error  :action :set-error}
            :reset           {:target :idle   :action :reset-data}}}

    :loaded
    ;; Successful fetch settled. A subsequent :fetch-started lands in
    ;; :fetching (keeps :tags visible during revalidation) rather than
    ;; :loading (which would blank the sidebar).
    {:tags #{:tags/loaded}
     :on   {:fetch-started {:target :fetching :action :bump-attempt}
            :reset         {:target :idle     :action :reset-data}}}

    :error
    ;; Most recent fetch failed. Prior :tags (if any) are still in
    ;; :data; the view can choose to render them or surface :error.
    {:tags #{:tags/error}
     :on   {:fetch-started {:target :loading :action :bump-attempt}
            :reset         {:target :idle    :action :reset-data}}}}})

(rf/reg-machine :realworld/tags tags-machine)

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :tags/initialise
  {:doc "Reset the popular-tags machine to its initial state. Dispatched
         from `:app/initialise` (see core.cljs)."}
  (fn handler-tags-initialise [_ _]
    {:fx [[:dispatch [:realworld/tags [:reset]]]]}))

;; ============================================================================
;; LOAD / LOADED / LOAD-FAILED — the three lifecycle events from
;; Pattern-RemoteData, translated into machine broadcasts.
;; ============================================================================

(rf/reg-event :tags/load
  {:doc "Fetch the popular-tags list. Broadcasts `:fetch-started` into
         the `:realworld/tags` machine; the region picks `:loading` or
         `:fetching` based on whether prior tags are present (the
         `:loaded` state has `:fetch-started → :fetching`; everywhere
         else it goes to `:loading`). Public endpoint; data-fetch retry."
   :rf.http/decode-schemas [schema/TagsResponse]}
  (fn handler-tags-load [_ _]
    {:fx [[:dispatch [:realworld/tags [:fetch-started]]]
          [:rf.http/managed
           (rh/request {:method     :get
                        :path       "/tags"
                        :decode     schema/TagsResponse
                        :retry      rh/data-fetch-retry
                        :request-id :tags/load
                        :on-success [:tags/loaded]
                        :on-failure [:tags/load-failed]})]]}))

(rf/reg-event :tags/loaded
  {:doc "Successful tags fetch. Folds the list + a load timestamp into
         the machine's `:data` via the `:set-tags` action; the region
         lands in `:loaded`."
   :rf.cofx/requires [:rf/time-ms]}
  (fn handler-tags-loaded [{:keys [rf/time-ms]} [_ {:keys [value]}]]
    {:fx [[:dispatch [:realworld/tags
                      [:fetch-succeeded {:tags (vec (:tags value))
                                         :now  time-ms}]]]]}))

(rf/reg-event :tags/load-failed
  {:doc "Failed tags fetch. Folds a human-readable error message into
         the machine's `:data` via the `:set-error` action; the region
         lands in `:error`. Any prior tags remain in `:data` so the
         view may still render them."}
  (fn handler-tags-load-failed [_ [_ {:keys [failure]}]]
    {:fx [[:dispatch [:realworld/tags
                      [:fetch-failed {:failure (rh/failure->message failure)}]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS — slice-shape readers projected off the machine snapshot
;; ============================================================================
;;
;; The view consumes the same names a slice-form reader would: `:tags/data`
;; for the items, `:tags/error` for the error map. There are no `:loading?` /
;; `:fetching?` booleans — views ask the tag instead:
;;
;;     @(rf/machine-has-tag? :realworld/tags :tags/loading)     ;; truly empty + in-flight
;;     @(rf/machine-has-tag? :realworld/tags :tags/in-flight)   ;; any in-flight (loading OR fetching)

(rf/reg-sub :tags/data
  {:doc "The popular-tags items, projected off the machine's :data."}
  :<- [:rf/machine :realworld/tags]
  (fn sub-tags-data [snap _]
    (get-in snap [:data :tags])))

(rf/reg-sub :tags/error
  {:doc "The most recent tags-fetch error, projected off the machine's :data."}
  :<- [:rf/machine :realworld/tags]
  (fn sub-tags-error [snap _]
    (get-in snap [:data :error])))

;; ============================================================================
;; HOME-PAGE QUERY HELPERS
;; ============================================================================
;;
;; The route-driven part of the home page (route-shape conformance):
;; - the tag filter is the `/tag/:tag` PATH route (`:realworld/home-tag`); the
;;   active tag is a route PARAM, not a `?tag=` query.
;; - `?feed=following` switches the home page to the authenticated feed (the
;;   official contract value — NOT `?feed=your`).
;; - navigation is always expressed as `:rf.route/navigate` events.
;;
;; `:home/load` is dispatched by BOTH the `:realworld/home` and
;; `:realworld/home-tag` `:on-match`; it broadcasts the per-axis transitions
;; into the home machine (`:realworld/articles-home`) before kicking the
;; per-feed fetch.

;; The official-contract feed token for the authenticated "Your Feed".
(def following-feed-token "following")

;; EP-0001: the route slice is durable routing runtime-db state —
;; `home-context` reads it off a runtime-db value (event handlers pass the
;; `:rf.db/runtime` coeffect; the subs below compose off the public
;; `[:rf.route/params]` / `[:rf.route/query]` framework subs). It normalises
;; the two home routes into one `{:tag :feed :page}` context: the tag comes
;; from the `/tag/:tag` route's params, the feed + page from the query.
(defn home-context [runtime-db]
  (let [cur   (get-in runtime-db [:rf.runtime/routing :current] {})
        query (:query cur {})]
    {:tag  (get-in cur [:params :tag])
     :feed (:feed query)
     :page (:page query)}))

(rf/reg-event :home/load
  {:doc "Route :on-match handler for `:realworld/home`. Reads the route's
         query params and:
           - broadcasts the `:feed` region into `:user-feed` / `:tag-feed`
             / `:global` per `?feed=` and `?tag=`,
           - broadcasts the `:filter` region into `:tagged` / `:none`
             per `?tag=`,
           - kicks the per-feed fetch (`:articles/load` or `:feed/load`).
         Each fetch handler in turn broadcasts `:fetch-started` into the
         home machine's `:data` region (per articles.cljs and
         favorites.cljs)."}
  (fn [{rt :rf.db/runtime} _]
    (let [{:keys [feed tag]} (home-context rt)
          your-feed? (= following-feed-token feed)
          tag-feed?  (and (not your-feed?) (some? tag))
          feed-event (cond
                       your-feed? [:show-user-feed]
                       tag-feed?  [:show-tag-feed]
                       :else      [:show-global])
          filter-event (if tag [:apply-filter] [:clear-filter])]
      {:fx (cond-> [[:dispatch [:realworld/articles-home feed-event]]
                    [:dispatch [:realworld/articles-home filter-event]]
                    [:dispatch [:tags/load]]]
             your-feed?       (conj [:dispatch [:feed/load]])
             (not your-feed?) (conj [:dispatch [:articles/load]]))})))

;; Switching feed or applying/clearing a tag is a fresh navigation and so
;; drops any in-flight `?page=` — landing back on page 1, which is the
;; official Conduit behaviour (the page-number control resets when the feed or
;; tag changes). Only `:home/show-page` carries the active feed/tag forward.
;;
;; ROUTE-SHAPE CONFORMANCE: the tag filter navigates to the
;; `/tag/:tag` PATH route (`:realworld/home-tag`), the following feed uses
;; `?feed=following`. `:home/show-page` re-targets whichever home route is
;; active (tag route keeps its `:tag` param) so paging stays within the tag.

(rf/reg-event :home/show-global-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-event :home/show-your-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:feed following-feed-token}}]]]}))

(rf/reg-event :home/show-page
  {:doc "Navigate to a 1-indexed pagination page for the active home feed,
         carrying the current feed / tag forward so paging stays within it. A
         tag-filtered list re-targets the `/tag/:tag` PATH route with the tag
         param preserved and `?page=` set; otherwise the home route carries
         `?feed=` + `?page=`. Changing `?page=` re-fires the active route's
         `:on-match` (Spec 012 — same route, changed query), re-running
         `:home/load` and the per-feed fetch with the new limit/offset window."}
  (fn [{rt :rf.db/runtime} [_ page]]
    (let [{:keys [tag feed]} (home-context rt)]
      (if tag
        {:fx [[:dispatch [:rf.route/navigate :realworld/home-tag {:tag tag} {:query {:page page}}]]]}
        (let [query (cond-> {:page page} feed (assoc :feed feed))]
          {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query query}]]]})))))

(rf/reg-event :tags/apply-filter
  (fn [_ [_ tag]]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home-tag {:tag tag}]]]}))

(rf/reg-event :tags/clear-filter
  (fn [_ _]
    ;; Clearing the tag leaves the `/tag/:tag` route entirely, back to the
    ;; global feed at `/` (page 1).
    {:fx [[:dispatch [:rf.route/navigate :realworld/home]]]}))

(rf/reg-sub :home/selected-tag
  {:doc "The active tag, read from the `/tag/:tag` route's PATH params
         (a route path param, not a `?tag=` query)."}
  :<- [:rf.route/params]
  (fn [params _] (:tag params)))

(rf/reg-sub :home/page
  {:doc "The 1-indexed home/tag page, off the route query (`?page=`)."}
  :<- [:rf.route/query]
  (fn [query _] (or (:page query) 1)))
