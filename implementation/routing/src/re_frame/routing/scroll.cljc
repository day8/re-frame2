(ns re-frame.routing.scroll
  "Scroll-restoration helpers + `:rf.nav/scroll` / `:rf.nav/capture-scroll`
  fxs for re-frame2 routing.

  Per Spec 012 §Scroll restoration §Multi-frame routing. Per-frame
  saved-position map at `[:rf/runtime :routing :scroll-positions]`,
  LRU-capped by `scroll-positions-cap`. Recency anchor lives at
  `[:rf/runtime :routing :scroll-positions-order]` as an internal
  vector. Both sit under the framework-owned `:rf/runtime` root
  (spec/Conventions §Reserved app-db keys, Spec-Schemas §`:rf/runtime`)
  so all routing runtime state in app-db sits beneath the single
  framework root.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar. Per the rf2-2yabr cohesion split: SCROLL seam."
  (:require [re-frame.frame :as frame]
            [re-frame.routing.registry :as registry]
            [re-frame.trace :as trace]))

(def scroll-positions-cap
  "Soft upper bound on tracked URLs in the per-frame scroll-positions map.
  Sized for typical SPA navigation depth — large enough that real
  Back-button restoration hits saved positions, small enough that the
  per-frame app-db slice stays bounded over long sessions."
  50)

(defn lookup-scroll-position
  "Return the saved [x y] for url in this frame's app-db, or nil if none."
  [db url]
  (get-in db [:rf/runtime :routing :scroll-positions url]))

(defn save-scroll-position
  "Pure: return db with the scroll position for url recorded at
  [:rf/runtime :routing :scroll-positions url]. Used inside :db effect
  maps so scroll positions live under the frame boundary (Spec 012
  §Multi-frame routing). The map is LRU-capped at `scroll-positions-cap`
  entries — re-saving an existing url promotes it to most-recent; new
  saves past the cap evict the least-recently-used entry."
  [db url xy]
  (let [order   (or (get-in db [:rf/runtime :routing :scroll-positions-order]) [])
        order'  (-> (filterv #(not= url %) order)
                    (conj url))
        over    (- (count order') scroll-positions-cap)
        dropped (when (pos? over) (subvec order' 0 over))
        order'' (if (pos? over) (subvec order' over) order')
        positions  (as-> (or (get-in db [:rf/runtime :routing :scroll-positions]) {}) m
                     (if dropped (apply dissoc m dropped) m)
                     (assoc m url xy))]
    (update-in db [:rf/runtime :routing] assoc
               :scroll-positions       positions
               :scroll-positions-order order'')))

(defn route-descriptor*
  "Build the canonical `{:id :params :query}` descriptor — the shape
  :rf.nav/scroll's :from / :to args carry. `:params` / `:query` are
  included only when non-empty. Single builder shared by
  `route-descriptor` (slice-driven :from) and the navigate /
  url-change :to sites (explicit args)."
  [id params query]
  (cond-> {:id id}
    (seq params) (assoc :params params)
    (seq query)  (assoc :query  query)))

(defn route-descriptor
  "Build the {:id :params :query} descriptor used by :rf.nav/scroll's
  :from / :to args from a route slice (or nil if no slice yet). The
  slice lives at [:rf/runtime :routing :current]."
  [route-slice]
  (when (and route-slice (:id route-slice))
    (route-descriptor* (:id route-slice)
                       (:params route-slice)
                       (:query route-slice))))

(defn resolve-scroll-strategy
  "Per Spec 012 §Scroll restoration, resolution order:
    1. opts' :scroll (per-call override)
    2. route metadata's :scroll
    3. implicit default (caller-supplied — :top for forward, :restore
       for popstate / initial)
  Returns the resolved strategy, or ::suppress when the resolved value
  is `false` (which means: do not emit the fx)."
  [route-meta opts default]
  (let [from-opts (when (and (map? opts) (contains? opts :scroll))
                    (:scroll opts))
        from-meta (:scroll route-meta)]
    (cond
      ;; per-call override wins; explicit `false` suppresses
      (some? from-opts) (if (false? from-opts) ::suppress from-opts)
      (false? from-meta) ::suppress
      (some? from-meta) from-meta
      :else             default)))

(defn scroll-fx-entry
  "Build the [:rf.nav/scroll args] fx entry for a navigation, or nil
  when the resolved strategy is ::suppress (no fx emission).

  Per Spec 012 §Scroll restoration §`:rf.nav/scroll` integration the args
  shape is {:strategy :from :to :saved-pos :fragment}."
  [{:keys [strategy from to saved-pos fragment]}]
  (when (not= ::suppress strategy)
    [:rf.nav/scroll
     (cond-> {:strategy strategy}
       from      (assoc :from      from)
       to        (assoc :to        to)
       saved-pos (assoc :saved-pos saved-pos)
       fragment  (assoc :fragment  fragment))]))

(defn- current-route-url
  "Best-effort URL reconstruction for the active route slice. Used only
  to key scroll-position capture; route deletion or invalid historical
  slices skip capture rather than failing navigation."
  [route-slice]
  (when-let [id (:id route-slice)]
    (try
      (registry/route-url id
                          (or (:params route-slice) {})
                          (or (:query route-slice) {})
                          (:fragment route-slice))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn capture-scroll-fx-entry
  "Build the `[:rf.nav/capture-scroll {:url ...}]` fx entry that saves
  the scroll position of the route the user is LEAVING, keyed by that
  route's reconstructed URL. Returns nil when the current slice has no
  reconstructable URL (no active route, or `route-url` throws — e.g. the
  route was unregistered mid-session), so navigation never fails on a
  capture miss. Emitted by both nav entry points before the transition
  commits so a later `:restore` to this URL finds the saved position."
  [db]
  (when-let [url (current-route-url (get-in db [:rf/runtime :routing :current]))]
    [:rf.nav/capture-scroll {:url url}]))

(def capture-scroll-meta
  "Metadata for the `:rf.nav/capture-scroll` fx registration."
  {:platforms #{:client}
   :doc       "Capture the current browser scroll position under the
per-frame [:rf/runtime :routing :scroll-positions <url>] map before
leaving a route."})

(defn capture-scroll-handler
  "`:rf.nav/capture-scroll` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [frame]} {:keys [url position]}]
  #?(:cljs
     (when url
       (let [pos (or position
                     [(or (.-scrollX js/window) (.-pageXOffset js/window) 0)
                      (or (.-scrollY js/window) (.-pageYOffset js/window) 0)])]
         (frame/swap-frame-db! (or frame :rf/default)
                               save-scroll-position
                               url
                               pos)))
     :clj
     (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:fx-id :rf.nav/capture-scroll :url url})))

(def scroll-fx-meta
  "Metadata for the `:rf.nav/scroll` fx registration."
  {:platforms #{:client}
   :doc       "Per Spec 012 §Scroll restoration. Args: {:strategy :from
:to :saved-pos :fragment}. Standard strategies are :top, :restore,
:preserve. Map-form strategies are host-extensible; the runtime treats
unknown strategies as :preserve (no-op)."})

(defn scroll-fx-handler
  "`:rf.nav/scroll` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar."
  [_ {:keys [strategy saved-pos fragment]}]
  #?(:cljs
     (case strategy
       :top      (if-let [el (and fragment
                                  (.getElementById js/document fragment))]
                   (.scrollIntoView el)
                   (.scrollTo js/window 0 0))
       :restore  (when (and saved-pos (sequential? saved-pos))
                   (.scrollTo js/window
                              (first saved-pos)
                              (second saved-pos)))
       :preserve nil
       ;; map-form / unknown → host-extensible; default no-op so the
       ;; runtime doesn't blow up on a strategy it doesn't recognise.
       nil)
     :clj
     (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                  {:fx-id :rf.nav/scroll :strategy strategy})))
