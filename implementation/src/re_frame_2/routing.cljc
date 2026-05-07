(ns re-frame-2.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route) keyed by user route-id.
  Navigation is an event (`:rf.route/navigate`); URL changes are events
  (`:rf.route/url-changed`); the :rf/route slice in app-db carries
  {:id :params :query :fragment :transition :error :nav-token}.

  This first pass implements the core: reg-route, match-url, route-url,
  the :rf.route/navigate event, the :rf/route slice. Stale-nav-token
  suppression, can-leave guards, scroll restoration, fragments are
  TODO (filed as beads).

  Path-pattern grammar (per Spec 012): segments delimited by /, with
  named params `:foo` matching one segment. Catch-all `*rest` and
  optional segments are TODO."
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.events :as events]
            [re-frame-2.fx :as fx]
            [re-frame-2.trace :as trace]))

;; ---- registration ---------------------------------------------------------

(defn reg-route
  "Register a route. metadata carries the route's :path pattern and any
  :on-match / :params / :scroll / :can-leave keys (see Spec 012)."
  [id metadata]
  (registrar/register! :route id metadata)
  id)

;; ---- path-pattern compilation ---------------------------------------------

(defn- segment->regex [seg]
  (cond
    (and (string? seg) (.startsWith seg ":"))
    [(subs seg 1) "([^/]+)"]      ;; named param

    :else
    [nil (java.util.regex.Pattern/quote seg)]))

(defn- compile-pattern [pattern]
  (let [segs (rest (clojure.string/split pattern #"/"))
        compiled (mapv segment->regex segs)
        names    (vec (keep first compiled))
        regex-parts (map second compiled)
        regex-str (str "^/?" (clojure.string/join "/" regex-parts) "$")]
    {:regex   #?(:clj  (java.util.regex.Pattern/compile regex-str)
                 :cljs (re-pattern regex-str))
     :names   names
     :pattern pattern}))

(defn- match-against
  "Try to match url against the route's compiled pattern. Returns the
  params map on success, nil on miss."
  [compiled url]
  (let [{:keys [regex names]} compiled
        m (re-matches regex url)]
    (when m
      (let [groups (if (sequential? m) (rest m) [])]
        (zipmap (map keyword names) groups)))))

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Try each registered route's
  pattern against url; return {:route-id :params :query :validation-failed?}
  for the first match, or nil if no route matches."
  [url]
  ;; Strip query string for pattern matching; parse query separately.
  (let [[path query-str] (clojure.string/split url #"\?" 2)
        query-params (when query-str
                       (into {}
                             (map (fn [pair]
                                    (let [[k v] (clojure.string/split pair #"=" 2)]
                                      [(keyword k) (or v "")])))
                             (clojure.string/split query-str #"&")))]
    (some
      (fn [[id meta]]
        (when-let [compiled (some-> (:path meta) compile-pattern)]
          (when-let [params (match-against compiled path)]
            {:route-id id
             :params   params
             :query    (or query-params {})
             :validation-failed? false})))
      (registrar/handlers :route))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Build a URL string from a
  route-id + path-params. Inverse of match-url."
  ([route-id path-params] (route-url route-id path-params {}))
  ([route-id path-params query-params]
   (let [meta    (registrar/lookup :route route-id)
         pattern (:path meta)
         _ (when (nil? pattern)
             (throw (ex-info ":rf.error/no-such-route" {:route-id route-id})))
         segs    (rest (clojure.string/split pattern #"/"))
         resolved (mapv (fn [seg]
                          (if (and (string? seg) (.startsWith seg ":"))
                            (let [k (keyword (subs seg 1))]
                              (str (or (get path-params k)
                                       (throw (ex-info ":rf.error/missing-route-param"
                                                       {:param k :route-id route-id})))))
                            seg))
                        segs)
         path-out (str "/" (clojure.string/join "/" resolved))
         qs (when (seq query-params)
              (str "?"
                   (clojure.string/join "&"
                     (map (fn [[k v]] (str (name k) "=" v)) query-params))))]
     (str path-out qs))))

;; ---- standard handlers ----------------------------------------------------

(events/reg-event-fx :rf.route/navigate
  (fn [{:keys [db]} [_ target params opts]]
    (let [{:keys [route-id path-params query-params]}
          (cond
            (keyword? target)
            {:route-id target
             :path-params (or params {})
             :query-params (:query opts {})}

            (and (map? target) (:url target))
            (let [m (match-url (:url target))]
              {:route-id     (or (:route-id m) :rf.route/not-found)
               :path-params  (:params m {:url (:url target)})
               :query-params (:query m {})}))
          route-meta (registrar/lookup :route route-id)
          url (try (route-url route-id path-params query-params)
                   (catch #?(:clj Throwable :cljs :default) _ "/"))
          push-fx (if (:replace? opts)
                    [:rf.nav/replace-url url]
                    [:rf.nav/push-url    url])
          on-match-vec (vec (or (:on-match route-meta) []))]
      {:db (assoc db :rf/route
                  {:id         route-id
                   :params     path-params
                   :query      query-params
                   :transition (if (seq on-match-vec) :loading :idle)
                   :error      nil})
       :fx (vec (concat [push-fx]
                        (mapv (fn [ev] [:dispatch ev]) on-match-vec)))})))

(events/reg-event-fx :rf.route/handle-url-change
  (fn [{:keys [db]} [_ url]]
    (let [m (match-url url)
          route-id   (or (:route-id m) :rf.route/not-found)
          params     (or (:params m) {:url url})
          query      (or (:query m) {})
          route-meta (registrar/lookup :route route-id)
          on-match-vec (vec (or (:on-match route-meta) []))]
      {:db (assoc db :rf/route
                  {:id         route-id
                   :params     params
                   :query      query
                   :transition (if (seq on-match-vec) :loading :idle)
                   :error      nil})
       :fx (vec (mapv (fn [ev] [:dispatch ev]) on-match-vec))})))

;; ---- standard navigation fx ----------------------------------------------

(fx/reg-fx :rf.nav/push-url
  {:platforms #{:client}
   :doc       "Push the URL to the browser history (HTML5 pushState)."}
  (fn [_ url]
    #?(:cljs (.pushState js/window.history nil "" url)
       :clj  (trace/emit! :fx :rf.fx/skipped-on-platform
                          {:fx-id :rf.nav/push-url :url url}))))

(fx/reg-fx :rf.nav/replace-url
  {:platforms #{:client}
   :doc       "Replace the URL in the browser history (HTML5 replaceState)."}
  (fn [_ url]
    #?(:cljs (.replaceState js/window.history nil "" url)
       :clj  (trace/emit! :fx :rf.fx/skipped-on-platform
                          {:fx-id :rf.nav/replace-url :url url}))))

;; ---- subs over the slice --------------------------------------------------
;; Will be picked up by re-frame-2.subs/reg-sub when this ns loads.

;; (We can't call reg-sub here directly because of dep direction; the
;; user's app calls re-frame-2.core/init which forwards into this.
;; For now, expose helpers and let the public API wire them.)

(defn route-sub-fn
  "Layer-1 sub fn for :rf/route — reads the slice from app-db."
  [db _query]
  (:rf/route db))
