(ns re-frame-2.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route) keyed by user route-id.
  Navigation is an event (`:rf.route/navigate`); URL changes are events
  (`:rf.route/url-changed`); the :route slice in app-db carries
  {:id :params :query :fragment :transition :error :nav-token}.

  This first pass implements the core: reg-route, match-url, route-url,
  the :rf.route/navigate event, the :route slice. Stale-nav-token
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
;;
;; Per Spec 012 §Route ranking algorithm: each registration computes a
;; structural rank tuple that match-url consults to pick the winner among
;; overlapping matches.

(defn- pattern-shape
  "Walk a path pattern and tally segment shapes used by the rank tuple.
  Counts non-optional segments only (rule 2 vs rule 5); the per-pattern
  optional-group count is tracked separately. Catch-all detection: the
  whole pattern is a single splat (/*name)."
  [pattern]
  (let [n           (count pattern)
        i           (atom 0)
        depth       (atom 0)        ;; inside-optional-group depth
        seen        (atom {:static 0 :named 0 :splat 0 :optional 0 :total 0})
        bump        (fn [k] (swap! seen update k inc))]
    ;; Walk char-by-char like compile-pattern. At each segment-start we
    ;; classify the segment kind.
    (loop []
      (when (< @i n)
        (let [ch (.charAt pattern @i)]
          (cond
            (= ch \{)
            (do (bump :optional) (swap! depth inc) (swap! i inc))

            (= ch \})
            (do (swap! depth dec) (swap! i inc)
                (when (and (< @i n) (= \? (.charAt pattern @i)))
                  (swap! i inc)))

            (= ch \:)
            (do (when (zero? @depth)
                  (bump :named)
                  (bump :total))
                (swap! i (fn [j]
                           (let [k (loop [m (inc j)]
                                     (cond
                                       (>= m n) m
                                       (or (= \/ (.charAt pattern m))
                                           (= \{ (.charAt pattern m))
                                           (= \} (.charAt pattern m))
                                           (= \? (.charAt pattern m))) m
                                       :else (recur (inc m))))]
                             k))))

            (= ch \*)
            (do (when (zero? @depth)
                  (bump :splat)
                  (bump :total))
                (swap! i (fn [j]
                           (loop [m (inc j)]
                             (cond
                               (>= m n) m
                               (or (= \/ (.charAt pattern m))
                                   (= \{ (.charAt pattern m))
                                   (= \} (.charAt pattern m))
                                   (= \? (.charAt pattern m))) m
                               :else (recur (inc m)))))))

            (= ch \/)
            (swap! i inc)

            :else
            (do (when (zero? @depth)
                  (bump :static)
                  (bump :total))
                (swap! i (fn [j]
                           (loop [m (inc j)]
                             (cond
                               (>= m n) m
                               (or (= \/ (.charAt pattern m))
                                   (= \{ (.charAt pattern m))
                                   (= \} (.charAt pattern m))) m
                               :else (recur (inc m)))))))))
        (recur)))
    (let [s @seen
          catch-all? (and (= 1 (:total s))
                          (= 1 (:splat s))
                          (zero? (:static s))
                          (zero? (:named s))
                          (zero? (:optional s)))]
      (assoc s :catch-all? catch-all?))))

(defn- compute-rank
  "Per Spec 012 §Route ranking algorithm. Returns a tuple sorted descending —
  the first element that distinguishes two patterns wins.

  Tuple positions, in priority order:
    [static-count
     non-optional-total-length
     (- splat-count)              ;; fewer splats win (rule 3)
     (if catch-all? 0 1)          ;; non-catch-all wins (rule 4)
     (- optional-count)           ;; fewer optional groups win (rule 5)
     (- reg-index)]               ;; earlier registration wins (rule 6)
  reg-index is added at registration time."
  [pattern]
  (let [{:keys [static total splat catch-all? optional]} (pattern-shape pattern)]
    [static
     total
     (- splat)
     (if catch-all? 0 1)
     (- optional)]))

(defonce ^:private reg-counter (atom 0))

(defn reg-route
  "Register a route. metadata carries the route's :path pattern and any
  :on-match / :params / :scroll / :can-leave keys (see Spec 012).

  Computes :rf.route/rank at registration time so match-url can sort
  candidates by rank without re-parsing on each call. If a previously-
  registered route has an equal structural rank, emits
  :rf.warning/route-shadowed-by-equal-score (per Spec 012 §Route ranking
  algorithm — rule 6) so tooling can flag the conflict."
  [id metadata]
  (let [pattern (:path metadata)
        rank    (when pattern (compute-rank pattern))
        idx     (swap! reg-counter inc)
        meta'   (cond-> metadata
                  rank (assoc :rf.route/rank (conj rank (- idx))))]
    ;; Spec 012 rule-6 warning: scan existing routes for one whose structural
    ;; rank (i.e. the rank tuple SANS the reg-index final element) equals ours.
    (when rank
      (when-let [shadowed
                 (some (fn [[other-id other-meta]]
                         (when-let [other-rank (:rf.route/rank other-meta)]
                           (when (and (not= other-id id)
                                      (= rank (vec (drop-last other-rank))))
                             other-id)))
                       (registrar/handlers :route))]
        (trace/emit! :warning :rf.warning/route-shadowed-by-equal-score
                     {:route-id id :shadowed shadowed})))
    (registrar/register! :route id meta')
    id))

;; ---- path-pattern compilation ---------------------------------------------

(defn- compile-pattern
  "Compile a Spec 012 path-pattern into a regex with capture groups.
  Recognises:
    /literal          -> literal
    /:name            -> ([^/]+)        named param
    /*name            -> (.+)           splat — greedy across /
    /{...}?           -> (?: ... )?     optional group; inside the
                                        group, /:name is treated like
                                        a normal named param.

  The capture-group ordering matches the order that param names appear
  left-to-right in the pattern. :names is the vector of those param
  keywords; absent params (optional group not matched) yield nil."
  [pattern]
  (let [;; Walk character-by-character, emitting regex fragments and
        ;; collecting param names in order. We build two pieces in
        ;; parallel: the regex source and the names vector.
        ;;
        ;; Token boundaries: "/", "{", "}", ":", "*", "?".
        n         (count pattern)
        sb        (StringBuilder.)
        names     (atom [])
        i         (atom 0)]
    (.append sb "^/?")
    ;; Skip leading '/'
    (when (and (pos? n) (= \/ (.charAt pattern 0)))
      (swap! i inc))
    (loop []
      (when (< @i n)
        (let [ch (.charAt pattern @i)]
          (cond
            ;; literal '/'
            (= ch \/)
            (do (.append sb "/") (swap! i inc))

            ;; named param ':name'
            (= ch \:)
            (let [start (inc @i)
                  end   (loop [j start]
                          (cond
                            (>= j n) j
                            (or (= \/ (.charAt pattern j))
                                (= \{ (.charAt pattern j))
                                (= \} (.charAt pattern j))
                                (= \? (.charAt pattern j))) j
                            :else (recur (inc j))))
                  name  (subs pattern start end)]
              (.append sb "([^/]+)")
              (swap! names conj name)
              (reset! i end))

            ;; splat '*name'
            (= ch \*)
            (let [start (inc @i)
                  end   (loop [j start]
                          (cond
                            (>= j n) j
                            (or (= \/ (.charAt pattern j))
                                (= \{ (.charAt pattern j))
                                (= \} (.charAt pattern j))
                                (= \? (.charAt pattern j))) j
                            :else (recur (inc j))))
                  name  (subs pattern start end)]
              (.append sb "(.+)")
              (swap! names conj name)
              (reset! i end))

            ;; optional group '{...}?'
            (= ch \{)
            (do (.append sb "(?:")
                (swap! i inc))

            (= ch \})
            (do (.append sb ")")
                (swap! i inc)
                ;; consume trailing '?' marker — required by the grammar.
                (when (and (< @i n) (= \? (.charAt pattern @i)))
                  (.append sb "?")
                  (swap! i inc)))

            ;; literal char
            :else
            (do (.append sb (java.util.regex.Pattern/quote (str ch)))
                (swap! i inc))))
        (recur)))
    (.append sb "$")
    (let [regex-str (.toString sb)]
      {:regex    #?(:clj  (java.util.regex.Pattern/compile regex-str)
                    :cljs (re-pattern regex-str))
       :names    @names
       :pattern  pattern})))

(defn- match-against
  "Try to match url against the route's compiled pattern. Returns the
  params map on success, nil on miss."
  [compiled url]
  (let [{:keys [regex names]} compiled
        m (re-matches regex url)]
    (when m
      (let [groups (if (sequential? m) (rest m) [])]
        (zipmap (map keyword names) groups)))))

(defn- coerce-query-value
  "Per Spec 012 §Query-string coercion: when a route declares :query as
  a Malli vector schema, look up the per-key type and coerce. First-pass
  recognises :int / :keyword / :boolean — strings pass through.

  schema is the Malli :map vector or nil; k is the keyword key whose
  value we're coercing; v is the raw string from the URL."
  [schema k v]
  (if-not (and schema (vector? schema))
    v
    (let [;; Walk top-level [:map [k type-or-opts] ...] entries to find k.
          entry (some (fn [e]
                        (cond
                          (and (vector? e) (= k (first e))) e
                          :else nil))
                      (rest schema))
          type-form (cond
                      (and entry (= 2 (count entry))) (second entry)
                      (and entry (= 3 (count entry))) (last entry)
                      :else                            nil)]
      (case type-form
        :int     (try (Long/parseLong v) (catch Throwable _ v))
        :keyword (keyword v)
        :boolean (case v "true" true "false" false v)
        v))))

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Try each registered route's
  pattern against url; return {:route-id :params :query :validation-failed?}
  for the first match, or nil if no route matches.

  Query string coercion: if the route declares a :query Malli schema,
  string values are coerced per key type. :query-defaults populate
  absent keys."
  [url]
  ;; Strip query string for pattern matching; parse query separately.
  ;; Uses array-map to preserve the URL's left-to-right key order so
  ;; round-trip URLs come back byte-identical.
  (let [[path query-str] (clojure.string/split url #"\?" 2)
        raw-query        (when query-str
                           (reduce
                             (fn [m pair]
                               (let [[k v] (clojure.string/split pair #"=" 2)]
                                 (assoc m (keyword k) (or v ""))))
                             (array-map)
                             (clojure.string/split query-str #"&")))
        ;; Find every route whose pattern matches; sort by rank descending
        ;; (Spec 012 §Route ranking algorithm); the highest-ranked wins.
        candidates
        (keep
          (fn [[id meta]]
            (when-let [compiled (some-> (:path meta) compile-pattern)]
              (when-let [params (match-against compiled path)]
                (let [schema     (:query meta)
                      defaults   (:query-defaults meta {})
                      coerced    (when raw-query
                                   (reduce-kv
                                     (fn [m k v]
                                       (assoc m k (coerce-query-value schema k v)))
                                     (array-map)
                                     raw-query))
                      with-defaults (reduce-kv
                                      (fn [m k v]
                                        (if (contains? m k) m (assoc m k v)))
                                      (or coerced (array-map))
                                      defaults)]
                  {:route-id           id
                   :rank               (or (:rf.route/rank meta)
                                           [0 0 0 0 0 0])
                   :params             params
                   :query              with-defaults
                   :validation-failed? false}))))
          (registrar/handlers :route))
        winner (->> candidates (sort-by :rank #(compare %2 %1)) first)]
    (when winner (dissoc winner :rank))))

(defn- collect-param-names-in-group
  "Walk a pattern starting at `start` (just past the opening '{'), return
  [end-after-closing-?, param-names-vec] for the group's contents."
  [pattern start]
  (let [n (count pattern)]
    (loop [j     start
           names []]
      (cond
        (>= j n)
        [j names]   ;; unterminated; let later parsing catch it.

        (= \} (.charAt pattern j))
        ;; closing brace; consume the trailing '?' if present.
        (let [k (inc j)]
          [(if (and (< k n) (= \? (.charAt pattern k))) (inc k) k) names])

        (or (= \: (.charAt pattern j)) (= \* (.charAt pattern j)))
        (let [start (inc j)
              end   (loop [m start]
                      (cond
                        (>= m n) m
                        (or (= \/ (.charAt pattern m))
                            (= \{ (.charAt pattern m))
                            (= \} (.charAt pattern m))
                            (= \? (.charAt pattern m))) m
                        :else (recur (inc m))))]
          (recur end (conj names (subs pattern start end))))

        :else
        (recur (inc j) names)))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Build a URL string from a
  route-id + path-params. Inverse of match-url.

  Optional groups ({...}?) are emitted only when ALL their inner params
  are supplied in path-params; otherwise the group is silently elided."
  ([route-id path-params] (route-url route-id path-params {}))
  ([route-id path-params query-params]
   (let [meta    (registrar/lookup :route route-id)
         pattern (:path meta)
         _ (when (nil? pattern)
             (throw (ex-info ":rf.error/no-such-route" {:route-id route-id})))
         n  (count pattern)
         sb (StringBuilder.)
         i  (atom 0)]
     (loop []
       (when (< @i n)
         (let [ch (.charAt pattern @i)]
           (cond
             (= ch \{)
             (let [[after-end inner-names] (collect-param-names-in-group pattern (inc @i))
                   all-present? (every? #(some? (get path-params (keyword %))) inner-names)]
               (if all-present?
                 ;; Emit the group's contents (without the braces / '?').
                 (do (reset! i (inc @i))
                     (loop []
                       (let [c2 (.charAt pattern @i)]
                         (cond
                           (= c2 \})
                           (let [k (inc @i)]
                             (reset! i (if (and (< k n) (= \? (.charAt pattern k))) (inc k) k)))
                           :else
                           (do
                             (cond
                               (= c2 \:)
                               (let [start (inc @i)
                                     end   (loop [m start]
                                             (cond
                                               (>= m n) m
                                               (or (= \/ (.charAt pattern m))
                                                   (= \{ (.charAt pattern m))
                                                   (= \} (.charAt pattern m))
                                                   (= \? (.charAt pattern m))) m
                                               :else (recur (inc m))))
                                     k     (keyword (subs pattern start end))]
                                 (.append sb (str (get path-params k)))
                                 (reset! i end))

                               (= c2 \*)
                               (let [start (inc @i)
                                     end   (loop [m start]
                                             (cond
                                               (>= m n) m
                                               (or (= \/ (.charAt pattern m))
                                                   (= \{ (.charAt pattern m))
                                                   (= \} (.charAt pattern m))
                                                   (= \? (.charAt pattern m))) m
                                               :else (recur (inc m))))
                                     k     (keyword (subs pattern start end))]
                                 (.append sb (str (get path-params k)))
                                 (reset! i end))

                               :else
                               (do (.append sb c2) (swap! i inc)))
                             (recur))))))
                 ;; group elided
                 (reset! i after-end)))

             (= ch \:)
             (let [start (inc @i)
                   end   (loop [m start]
                           (cond
                             (>= m n) m
                             (or (= \/ (.charAt pattern m))
                                 (= \{ (.charAt pattern m))
                                 (= \} (.charAt pattern m))
                                 (= \? (.charAt pattern m))) m
                             :else (recur (inc m))))
                   k     (keyword (subs pattern start end))]
               (.append sb (str (or (get path-params k)
                                    (throw (ex-info ":rf.error/missing-route-param"
                                                    {:param k :route-id route-id})))))
               (reset! i end))

             (= ch \*)
             (let [start (inc @i)
                   end   (loop [m start]
                           (cond
                             (>= m n) m
                             (or (= \/ (.charAt pattern m))
                                 (= \{ (.charAt pattern m))
                                 (= \} (.charAt pattern m))
                                 (= \? (.charAt pattern m))) m
                             :else (recur (inc m))))
                   k     (keyword (subs pattern start end))]
               (.append sb (str (or (get path-params k)
                                    (throw (ex-info ":rf.error/missing-route-param"
                                                    {:param k :route-id route-id})))))
               (reset! i end))

             :else
             (do (.append sb ch) (swap! i inc))))
         (recur)))
     (let [path-out (.toString sb)
           qs (when (seq query-params)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]] (str (name k) "=" v)) query-params))))]
       (str path-out qs)))))

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
      {:db (assoc db :route
                  {:id         route-id
                   :params     path-params
                   :query      query-params
                   :transition (if (seq on-match-vec) :loading :idle)
                   :error      nil})
       :fx (vec (concat [push-fx]
                        (mapv (fn [ev] [:dispatch ev]) on-match-vec)))})))

(defn- split-fragment
  "Split a URL into [url-without-fragment fragment]. Returns
  [path-and-query nil] when no '#' is present, else
  [before-hash after-hash]."
  [url]
  (let [hash-idx (.indexOf url "#")]
    (if (neg? hash-idx)
      [url nil]
      [(subs url 0 hash-idx) (subs url (inc hash-idx))])))

(defonce ^:private nav-token-counter (atom 0))

(defn- alloc-nav-token []
  (str "nav-" (swap! nav-token-counter inc)))

;; ---- :rf/url-requested + can-leave gating + pending-nav protocol ----------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol: a route may
;; declare :can-leave (a sub-id whose value is true when leaving is OK).
;; A user-initiated :rf/url-requested checks the active route's can-leave.
;; If it rejects, the navigation is held in :rf/pending-navigation, a
;; :rf.route/navigation-blocked trace fires, and no URL push happens.
;; The user's app then dispatches :rf.route/continue (resume) or
;; :rf.route/cancel (drop).

(defonce ^:private pending-nav-counter (atom 0))

(defn- alloc-pending-nav-id []
  (str "pn-" (swap! pending-nav-counter inc)))

(defn- can-leave?
  "Resolve and call the route's :can-leave sub against the live frame."
  [frame route-meta]
  (if-let [sub-id (:can-leave route-meta)]
    (when-let [subscribe-value (resolve 're-frame-2.subs/subscribe-value)]
      (boolean ((deref subscribe-value) frame [sub-id])))
    true))

(events/reg-event-fx :rf/url-requested
  (fn [{:keys [db frame]} [_ {:keys [url] :as request}]]
    (let [m              (match-url url)
          current-route  (:route db)
          current-meta   (registrar/lookup :route (:id current-route))
          ok?            (can-leave? (or frame :rf/default) current-meta)]
      (cond
        (not ok?)
        (let [pn-id (alloc-pending-nav-id)]
          (trace/emit! :event :rf.route/navigation-blocked
                       {:requested-url   url
                        :rejecting-route (:id current-route)})
          {:db (assoc db :rf/pending-navigation
                      {:id pn-id :request request})})

        :else
        ;; can leave — just dispatch :rf/url-changed for the new URL.
        {:fx [[:dispatch [:rf/url-changed url]]]}))))

(events/reg-event-fx :rf.route/continue
  (fn [{:keys [db]} [_ _pn-id]]
    (let [pending (:rf/pending-navigation db)
          url     (get-in pending [:request :url])]
      (cond-> {:db (dissoc db :rf/pending-navigation)}
        url (assoc :fx [[:dispatch [:rf/url-changed url]]
                        [:rf.nav/push-url url]])))))

(events/reg-event-fx :rf.route/cancel
  (fn [{:keys [db]} [_ _pn-id]]
    {:db (dissoc db :rf/pending-navigation)}))

(events/reg-event-fx :rf/url-changed
  (fn [{:keys [db]} [_ url]]
    ;; Per Spec 012 §URL changes are events / §Fragments. Splits URL into
    ;; path-with-query and fragment. If only the fragment differs from the
    ;; current slice, update :fragment but DO NOT re-fire :on-match — emit
    ;; :rf.route/url-changed instead. Otherwise full nav: allocate a
    ;; nav-token, write new slice, fire :on-match.
    (let [[path-q fragment] (split-fragment url)
          m                 (match-url path-q)
          prev              (:route db)
          fragment-only?    (and prev m
                                 (= (:id prev)     (:route-id m))
                                 (= (:params prev) (:params m))
                                 (= (:query prev)  (:query m))
                                 (not= (:fragment prev) fragment))]
      (cond
        fragment-only?
        (do (trace/emit! :event :rf.route/url-changed
                         {:route-id      (:id prev)
                          :prev-fragment (:fragment prev)
                          :next-fragment fragment})
            {:db (assoc-in db [:route :fragment] fragment)})

        (some? m)
        (let [route-meta   (registrar/lookup :route (:route-id m))
              on-match-vec (vec (or (:on-match route-meta) []))
              token        (alloc-nav-token)]
          (trace/emit! :event :route.nav-token/allocated
                       {:route-id  (:route-id m)
                        :nav-token token})
          {:db (assoc db :route
                      {:id         (:route-id m)
                       :params     (:params m)
                       :query      (:query m)
                       :fragment   fragment
                       :transition :idle
                       :error      nil
                       :nav-token  token})
           :fx (vec (mapv (fn [ev] [:dispatch ev]) on-match-vec))})

        :else
        (do (trace/emit-error! :rf.error/no-such-handler
                               {:url url :recovery :replaced-with-default})
            {})))))

(events/reg-event-fx :rf.route/handle-url-change
  (fn [{:keys [db]} [_ url]]
    (let [m (match-url url)]
      (when (nil? m)
        ;; Unmatched URL — fixture corpus calls this :rf.error/no-such-handler
        ;; in the routing context. The default error projector maps it to a
        ;; public-facing 404. Per Spec 011 §Default projector.
        (trace/emit-error! :rf.error/no-such-handler
                           {:url url
                            :recovery :replaced-with-default}))
      (let [route-id   (or (:route-id m) :rf.route/not-found)
            params     (or (:params m) {:url url})
            query      (or (:query m) {})
            route-meta (registrar/lookup :route route-id)
            on-match-vec (vec (or (:on-match route-meta) []))]
        {:db (assoc db :route
                    {:id         route-id
                     :params     params
                     :query      query
                     :transition (if (seq on-match-vec) :loading :idle)
                     :error      nil})
         :fx (vec (mapv (fn [ev] [:dispatch ev]) on-match-vec))}))))

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
  "Layer-1 sub fn for :route — reads the slice from app-db."
  [db _query]
  (:route db))
