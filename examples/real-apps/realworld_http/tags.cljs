(ns realworld-http.tags
  "Popular-tags list, plus the home-page navigation helpers (the `/tag/:tag`
   PATH route and the `?feed=` / `?page=` query).

   This file is the deliberate contrast case. The popular-tags lifecycle here
   lives ENTIRELY inside a single-region state machine, `:realworld/tags`,
   whose state-keyword IS the remote-data status — no app-db slice at all.
   Every other remote-data resource in realworld (`:articles`, `:feed`,
   `:article`, `:comments`, `:profile`, `:profile.articles`,
   `:profile.favorites`) uses the plain 5-key slice instead, so you can hold
   the two shapes up side by side and see the trade. See the machines guide:
   ../../../docs/machines/index.md

   What changes when the machine swallows the lifecycle:

   - The status enum (`:idle :loading :fetching :loaded :error`) becomes the
     machine's states, one for one; the slice's `:status` field just vanishes.
   - The items, error, loaded-at, and attempt all move into the machine's
     `:data` map — there's no slice left to hold them.
   - The slice's `:loading?` / `:fetching?` booleans turn into per-state
     `:tags`, asked with `rf/machine-has-tag?`.

   The routing helpers (`:home/load`, `:home/show-global-feed`, …) live down
   below; they dispatch `:tags/load` so the tags machine fetches whenever the
   home route lights up."
  (:require [re-frame.core :as rf]
            ;; State machines live in their own artefact; we require it to load
            ;; it, which registers the hooks that make `rf/reg-machine` (below)
            ;; and the `:rf/machine` / `:rf/machine-has-tag?` subs resolve. See
            ;; the machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld-http.schema :as schema]
            [realworld-http.http :as rh]))

;; ============================================================================
;; THE MACHINE — :realworld/tags  (one region; the remote-data lifecycle)
;; ============================================================================
;;
;; Here's the same lifecycle, two ways. The status enum becomes machine states,
;; one for one. Put the slice form (used by `:articles`, `:feed`, `:article`,
;; …) next to the machine form (used here):
;;
;;     ;; SLICE FORM (the other seven remote-data resources)
;;     ;; A plain map with an explicit :status keyword.
;;     {:status :loading :data [] :error nil :loaded-at nil :attempt 0}
;;
;;     ;; MACHINE FORM (this file)
;;     ;; The state-keyword IS the status; everything else moves into :data.
;;     {:state :loading :data {:tags [] :error nil :loaded-at nil :attempt 0} :tags #{...}}
;;
;; And the two booleans the view actually cares about:
;;
;;     :loading?   = (= status :loading)                        ;; empty AND in-flight
;;     :fetching?  = (#{:loading :fetching} status)             ;; in-flight, full or not
;;
;; turn into tag questions about the current state:
;;
;;     :loading?   = @(rf/machine-has-tag? :realworld/tags :tags/loading)
;;     :fetching?  = @(rf/machine-has-tag? :realworld/tags :tags/in-flight)
;;
;; The payoff: the view never has to remember WHICH state means \"in-flight\".
;; It asks for the tag, and the machine keeps that bookkeeping to itself.

(def tags-machine
  {:initial :idle
   :data    {:tags     []
             :error    nil
             :loaded-at nil
             :attempt  0}
   ;; Validates the snapshot's :data at every macrostep boundary. The snapshot
   ;; lives in runtime-db, not app-db, so THIS is its validation surface — an
   ;; app-schema would never see it.
   :schemas {:data schema/TagsData}

   :actions
   {:bump-attempt
    (fn action-bump-attempt [{data :data}]
      {:data (-> data
                 (update :attempt (fnil inc 0))
                 (assoc  :error nil))})

    :set-tags
    ;; :fetch-succeeded brings the resolved tags vector along under :tags.
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
    ;; Never fetched, or freshly :reset. The slice-form equivalent of
    ;; `:status :idle`.
    {:tags #{:tags/idle}
     :on   {:fetch-started {:target :loading :action :bump-attempt}
            :reset         {:target :idle    :action :reset-data}}}

    :loading
    ;; First fetch in flight, nothing to show yet. The :tags/in-flight tag
    ;; rides on both :loading and :fetching, so a view asking "is something
    ;; loading?" doesn't have to OR two state-keywords together.
    {:tags #{:tags/loading :tags/in-flight :tags/transient}
     :on   {:fetch-succeeded {:target :loaded :action :set-tags}
            :fetch-failed    {:target :error  :action :set-error}
            :reset           {:target :idle   :action :reset-data}}}

    :fetching
    ;; A re-fetch while the old :tags are still on screen. We don't blank the
    ;; sidebar — a subtle progress hint at most. The :tags/loaded tag stays
    ;; lit, so whatever renders the tags carries on undisturbed.
    {:tags #{:tags/fetching :tags/in-flight :tags/loaded :tags/transient}
     :on   {:fetch-succeeded {:target :loaded :action :set-tags}
            :fetch-failed    {:target :error  :action :set-error}
            :reset           {:target :idle   :action :reset-data}}}

    :loaded
    ;; Settled, with tags in hand. The next :fetch-started goes to :fetching,
    ;; not :loading — revalidating without yanking the sidebar out from under
    ;; the reader.
    {:tags #{:tags/loaded}
     :on   {:fetch-started {:target :fetching :action :bump-attempt}
            :reset         {:target :idle     :action :reset-data}}}

    :error
    ;; The last fetch failed. Any earlier :tags are still sitting in :data, so
    ;; the view gets to choose: keep showing them, or surface the :error.
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
;; LOAD / LOADED / LOAD-FAILED — the three lifecycle events, each one just a
;; broadcast into the machine.
;; ============================================================================

(rf/reg-event :tags/load
  {:doc "Fetch the popular-tags list. Broadcasts `:fetch-started` into the
         `:realworld/tags` machine and lets the machine decide where to land:
         from `:loaded` it goes to `:fetching` (tags already showing),
         everywhere else to `:loading`. Public endpoint; house retry."
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
  {:doc "Tags fetch came back happy. Folds the list and a load timestamp into
         the machine's `:data` via the `:set-tags` action; the region settles
         in `:loaded`."
   :rf.cofx/requires [:rf/time-ms]}
  (fn handler-tags-loaded [{:keys [rf/time-ms]} [_ {:keys [value]}]]
    {:fx [[:dispatch [:realworld/tags
                      [:fetch-succeeded {:tags (vec (:tags value))
                                         :now  time-ms}]]]]}))

(rf/reg-event :tags/load-failed
  {:doc "Tags fetch fell over. Folds a readable error message into the
         machine's `:data` via the `:set-error` action; the region lands in
         `:error`. Any earlier tags stay in `:data`, so the view can keep
         showing them if it likes."}
  (fn handler-tags-load-failed [_ [_ {:keys [error]}]]
    {:fx [[:dispatch [:realworld/tags
                      [:fetch-failed {:failure (rh/failure->message error)}]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS — plain readers projected off the machine snapshot
;; ============================================================================
;;
;; To the view this looks like any other slice: `:tags/data` for the items,
;; `:tags/error` for the error. What's missing is the `:loading?` /
;; `:fetching?` booleans — for those, views ask the machine a tag question:
;;
;;     @(rf/machine-has-tag? :realworld/tags :tags/loading)     ;; empty AND in-flight
;;     @(rf/machine-has-tag? :realworld/tags :tags/in-flight)   ;; in-flight, loading OR fetching

(rf/reg-sub :tags/data
  {:doc "The popular-tags items, read out of the machine's :data."}
  :<- [:rf/machine :realworld/tags]
  (fn sub-tags-data [snap _]
    (get-in snap [:data :tags])))

(rf/reg-sub :tags/error
  {:doc "The latest tags-fetch error, read out of the machine's :data."}
  :<- [:rf/machine :realworld/tags]
  (fn sub-tags-error [snap _]
    (get-in snap [:data :error])))

;; ============================================================================
;; HOME-PAGE QUERY HELPERS
;; ============================================================================
;;
;; The route-driven half of the home page, all following the official URL
;; contract:
;; - the tag filter is the `/tag/:tag` PATH route (`:realworld/home-tag`), so
;;   the active tag is a route PARAM, not a `?tag=` query.
;; - `?feed=following` flips the home page to the authenticated feed — the
;;   contract's value, NOT `?feed=your`.
;; - every navigation is a `:rf.route/navigate` event; nothing pokes history
;;   directly.
;;
;; `:home/load` is the `:on-match` for BOTH `:realworld/home` and
;; `:realworld/home-tag`. It broadcasts the per-axis transitions into the home
;; machine (`:realworld/articles-home`), then kicks off the per-feed fetch.

;; The contract's token for the authenticated "Your Feed".
(def following-feed-token "following")

;; The route lives in runtime-db, and `home-context` reads it off a runtime-db
;; value (handlers get it via the `:rf.db/runtime` coeffect; the subs below
;; instead compose off the public `[:rf.route/params]` / `[:rf.route/query]`
;; subs). Its job: flatten the two home routes into one tidy
;; `{:tag :feed :page}` — the tag from the `/tag/:tag` route's params, the feed
;; and page from the query.
(defn home-context [runtime-db]
  (let [cur   (get-in runtime-db [:rf.runtime/routing :current] {})
        query (:query cur {})]
    {:tag  (get-in cur [:params :tag])
     :feed (:feed query)
     :page (:page query)}))

(rf/reg-event :home/load
  {:doc "The `:on-match` for BOTH `:realworld/home` and `:realworld/home-tag`.
         It reads the flattened home context (the active tag off the
         `/tag/:tag` route's path params, the feed + page off the query) and
         then conducts three things:
           - steers the `:feed` region to `:user-feed` / `:tag-feed` / `:global`
             based on `?feed=` and the tag,
           - steers the `:filter` region to `:tagged` / `:none` based on the
             tag,
           - kicks off the matching fetch (`:articles/load` or `:feed/load`).
         Each fetch then broadcasts its own `:fetch-started` into the home
         machine's `:data` region (see articles.cljs and favorites.cljs), so
         the loading state takes care of itself."}
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

;; Switching feed, or applying/clearing a tag, is a fresh navigation — and so
;; it drops any lingering `?page=` and lands you back on page 1. That's the
;; official Conduit behaviour: change the feed or the tag and the page-number
;; control resets. Only `:home/show-page` carries the current feed/tag forward,
;; because paging shouldn't change what you're paging through.
;;
;; (URL shapes again: the tag filter navigates to the `/tag/:tag` PATH route
;; `:realworld/home-tag`, the following feed uses `?feed=following`.
;; `:home/show-page` re-aims at whichever home route is active — the tag route
;; keeps its `:tag` param — so paging stays inside the tag.)

(rf/reg-event :home/show-global-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {}}]]]}))

(rf/reg-event :home/show-your-feed
  (fn [_ _]
    {:fx [[:dispatch [:rf.route/navigate :realworld/home {} {:query {:feed following-feed-token}}]]]}))

(rf/reg-event :home/show-page
  {:doc "Jump to a 1-indexed page of the active home feed, carrying the current
         feed / tag along so you keep paging the same list. A tag-filtered list
         re-aims at the `/tag/:tag` route, tag param intact and `?page=` set;
         otherwise the home route carries `?feed=` + `?page=`. Either way,
         changing `?page=` re-fires the route's `:on-match` (same route, new
         query), which re-runs `:home/load` and the fetch with the new
         limit/offset window. The URL leads; the data follows."}
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
    ;; Dropping the tag means leaving the `/tag/:tag` route altogether — back
    ;; to the plain global feed at `/`, page 1.
    {:fx [[:dispatch [:rf.route/navigate :realworld/home]]]}))

(rf/reg-sub :home/selected-tag
  {:doc "The active tag — read from the `/tag/:tag` route's PATH params, since
         that's where it lives (a path param, not a `?tag=` query)."}
  :<- [:rf.route/params]
  (fn [params _] (:tag params)))

(rf/reg-sub :home/page
  {:doc "The 1-indexed home/tag page, straight off the route query (`?page=`)."}
  :<- [:rf.route/query]
  (fn [query _] (or (:page query) 1)))
