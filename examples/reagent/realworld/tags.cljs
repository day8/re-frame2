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
     collapse into per-state `:tags` queried with `rf/has-tag?`.

   Routing pieces (`:home/load`, `:home/show-global-feed`, etc.) sit
   below — they predate the refactor and are unaffected by it,
   though they dispatch `:tags/load` so the new machine fetches when
   the home route activates."
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
;;     :loading?   = @(rf/has-tag? :realworld/tags :tags/loading)
;;     :fetching?  = @(rf/has-tag? :realworld/tags :tags/in-flight)
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

   :actions
   {:bump-attempt
    (fn action-bump-attempt [data _event]
      {:data (-> data
                 (update :attempt (fnil inc 0))
                 (assoc  :error nil))})

    :set-tags
    ;; :fetch-succeeded carries the resolved tags vector under :tags.
    (fn action-set-tags [data [_ {:keys [tags now]}]]
      {:data (-> data
                 (assoc :tags (vec tags))
                 (assoc :error nil)
                 (assoc :loaded-at now))})

    :set-error
    (fn action-set-error [data [_ {:keys [failure]}]]
      {:data (assoc data :error failure)})

    :reset-data
    (fn action-reset-data [_data _event]
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

(rf/reg-event-fx :tags/initialise
  {:doc "Reset the popular-tags machine to its initial state. Dispatched
         from `:app/initialise` (see core.cljs)."}
  (fn handler-tags-initialise [_ _]
    {:fx [[:dispatch [:realworld/tags [:reset]]]]}))

;; ============================================================================
;; LOAD / LOADED / LOAD-FAILED — the three lifecycle events from
;; Pattern-RemoteData, translated into machine broadcasts.
;; ============================================================================

(rf/reg-event-fx :tags/load
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
                        :auth?      false
                        :decode     schema/TagsResponse
                        :retry      rh/data-fetch-retry
                        :request-id :tags/load
                        :on-success [:tags/loaded]
                        :on-failure [:tags/load-failed]})]]}))

(rf/reg-event-fx :tags/loaded
  {:doc "Successful tags fetch. Folds the list + a load timestamp into
         the machine's `:data` via the `:set-tags` action; the region
         lands in `:loaded`."}
  [(rf/inject-cofx :realworld/now)]
  (fn handler-tags-loaded [{:keys [realworld/now]} [_ {:keys [value]}]]
    {:fx [[:dispatch [:realworld/tags
                      [:fetch-succeeded {:tags (vec (:tags value))
                                         :now  now}]]]]}))

(rf/reg-event-fx :tags/load-failed
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
;; for the items, `:tags/error` for the error map. The `:loading?` /
;; `:fetching?` booleans are gone — views ask the tag instead:
;;
;;     @(rf/has-tag? :realworld/tags :tags/loading)     ;; truly empty + in-flight
;;     @(rf/has-tag? :realworld/tags :tags/in-flight)   ;; any in-flight (loading OR fetching)

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
;; HOME-PAGE QUERY HELPERS  (predate the machine refactor; unaffected by it)
;; ============================================================================
;;
;; The route-query driven part of the home page:
;; - `?tag=<name>` filters the global articles list
;; - `?feed=your` switches the home page to the authenticated feed
;; - navigation is always expressed as `:rf.route/navigate` events
;;
;; `:home/load` is dispatched by the `:route/home` `:on-match`; it
;; broadcasts the per-axis transitions into the home machine
;; (`:realworld/articles-home`) before kicking the per-feed fetch.

(defn home-query [db]
  (get-in db [:rf/route :query] {}))

(rf/reg-event-fx :home/load
  {:doc "Route :on-match handler for `:route/home`. Reads the route's
         query params and:
           - broadcasts the `:feed` region into `:user-feed` / `:tag-feed`
             / `:global` per `?feed=` and `?tag=`,
           - broadcasts the `:filter` region into `:tagged` / `:none`
             per `?tag=`,
           - kicks the per-feed fetch (`:articles/load` or `:feed/load`).
         Each fetch handler in turn broadcasts `:fetch-started` into the
         home machine's `:data` region (per articles.cljs and
         favorites.cljs)."}
  (fn [{:keys [db]} _]
    (let [{:keys [feed tag] :as _query} (home-query db)
          your-feed? (= "your" feed)
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

(rf/reg-event-fx :home/show-global-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {}}]]]}))

(rf/reg-event-fx :home/show-your-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {:feed "your"}}]]]}))

(rf/reg-event-fx :tags/apply-filter
  (fn [_ [_ tag]]
    {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query {:tag tag}}]]]}))

(rf/reg-event-fx :tags/clear-filter
  (fn [{:keys [db]} _]
    (let [query (dissoc (home-query db) :tag)]
      {:fx [[:dispatch [:rf.route/navigate :route/home {} {:query query}]]]})))

(rf/reg-sub :home/query
  (fn [db _] (home-query db)))

(rf/reg-sub :home/selected-tag
  :<- [:home/query]
  (fn [query _] (:tag query)))

(rf/reg-sub :home/feed-kind
  :<- [:home/query]
  (fn [query _] (if (= "your" (:feed query)) :your :global)))
