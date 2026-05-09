(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route) keyed by user route-id.
  Navigation is an event (:rf.route/navigate); URL changes are events
  (:rf/url-changed). The :route slice in app-db carries
  {:id :params :query :fragment :transition :error :nav-token}.

  Path-pattern grammar (per Spec 012):
    /literal      literal segment
    /:name        named param (one segment)
    /*rest        splat — greedy across /
    /{...}?       optional group; inner /:name is treated as a normal
                  named param and is elided in route-url output when
                  the param is absent from path-params.

  Match resolution: structural rank tuple computed at registration time
  per Spec 012 §Route ranking algorithm (6-rule cascade: static-count,
  length, splat-count, catch-all, optional-count, registration order).
  When a new route's structural rank equals an existing one,
  :rf.warning/route-shadowed-by-equal-score fires at registration.

  Query strings: per-key coercion via the route's :query Malli schema
  (:int / :keyword / :boolean); :query-defaults populate absent keys;
  URL key order is preserved for round-trip identity.

  Events:
    :rf/url-changed                     full / fragment-only nav
    :rf/url-requested                   user-initiated; can-leave guard
    :rf.route/navigate                  programmatic
    :rf.route/handle-url-change         pop-state / initial / SSR
    :rf.route/continue / cancel         pending-nav protocol
    :rf.test/simulate-http-resolution   test-only nav-token check"
  (:require [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- url encoding / decoding ---------------------------------------------
;;
;; Per Spec 012 §Bidirectional URL ↔ params: param values are
;; %-encoded in URLs and decoded into clojure values. We use the
;; encodeURIComponent-equivalent behaviour (spaces → %20, slashes
;; encoded) for named path params and query values. Splat values
;; preserve literal '/' between captured segments — so the splat
;; encoder runs per-segment.

(defn- url-encode
  "Encode a single component (named param or query value). Uses
  encodeURIComponent semantics on CLJS; emulates it on JVM (URLEncoder
  + + → %20 swap)."
  [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
               (.replace "+" "%20"))
     :cljs (js/encodeURIComponent (str s))))

(defn- url-encode-splat
  "Encode a splat value — multi-segment, preserves literal '/'.
  Each segment is encoded individually."
  [s]
  (clojure.string/join "/" (map url-encode (clojure.string/split (str s) #"/"))))

(defn- url-decode
  "Decode a percent-encoded string back to its raw form. Round-trip
  inverse of url-encode."
  [s]
  #?(:clj  (java.net.URLDecoder/decode (str s) "UTF-8")
     :cljs (js/decodeURIComponent (str s))))

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

(declare compile-pattern)

(defn reg-route
  "Register a route. metadata carries the route's :path pattern and any
  :on-match / :params / :scroll / :can-leave keys (see Spec 012).

  Computes :rf.route/rank AND a :rf.route/compiled regex at registration
  time so match-url can sort candidates by rank and match without
  re-parsing on each call. If a previously-registered route has an
  equal structural rank, emits :rf.warning/route-shadowed-by-equal-score
  (per Spec 012 §Route ranking algorithm — rule 6) so tooling can flag
  the conflict."
  [id metadata]
  (let [pattern  (:path metadata)
        rank     (when pattern (compute-rank pattern))
        compiled (when pattern (compile-pattern pattern))
        idx      (swap! reg-counter inc)
        meta'    (cond-> (source-coords/merge-coords metadata)
                   rank     (assoc :rf.route/rank (conj rank (- idx)))
                   compiled (assoc :rf.route/compiled compiled))]
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

(defn- regex-escape
  "Quote a single character for use as a regex literal. Portable across
  JVM (java.util.regex.Pattern/quote) and CLJS (manual escape table)."
  [s]
  #?(:clj  (java.util.regex.Pattern/quote s)
     :cljs (clojure.string/replace s
                                   #"[\\^$.|?*+()\[\]{}]"
                                   #(str "\\" %))))

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
  (let [n      (count pattern)
        ;; Build the regex source as a vector of fragments; (apply str)
        ;; at the end. Vector accumulator works on JVM and CLJS without
        ;; the StringBuilder portability tax.
        parts  (atom ["^/?"])
        names  (atom [])
        i      (atom 0)
        emit   (fn [s] (swap! parts conj s))]
    ;; Skip leading '/'.
    (when (and (pos? n) (= \/ (.charAt pattern 0)))
      (swap! i inc))
    (loop []
      (when (< @i n)
        (let [ch (.charAt pattern @i)]
          (cond
            (= ch \/)
            (do (emit "/") (swap! i inc))

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
                  nm    (subs pattern start end)]
              (emit "([^/]+)")
              (swap! names conj nm)
              (reset! i end))

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
                  nm    (subs pattern start end)]
              (emit "(.+)")
              (swap! names conj nm)
              (reset! i end))

            (= ch \{)
            (do (emit "(?:") (swap! i inc))

            (= ch \})
            (do (emit ")")
                (swap! i inc)
                (when (and (< @i n) (= \? (.charAt pattern @i)))
                  (emit "?")
                  (swap! i inc)))

            :else
            (do (emit (regex-escape (str ch)))
                (swap! i inc))))
        (recur)))
    (emit "$")
    (let [regex-str (apply str @parts)]
      {:regex   (re-pattern regex-str)
       :names   @names
       :pattern pattern})))

(defn- match-against
  "Try to match url against the route's compiled pattern. Returns the
  params map (with %-decoded values) on success, nil on miss."
  [compiled url]
  (let [{:keys [regex names]} compiled
        m (re-matches regex url)]
    (when m
      (let [groups (if (sequential? m) (rest m) [])]
        (zipmap (map keyword names)
                (map (fn [g] (when g (url-decode g))) groups))))))

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
        :int     (try
                   #?(:clj  (Long/parseLong v)
                      :cljs (let [n (js/parseInt v 10)]
                              (if (js/isNaN n) v n)))
                   (catch #?(:clj Throwable :cljs :default) _ v))
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
                                 (assoc m
                                        (keyword (url-decode k))
                                        (if v (url-decode v) ""))))
                             (array-map)
                             (clojure.string/split query-str #"&")))
        ;; Find every route whose pattern matches; sort by rank descending
        ;; (Spec 012 §Route ranking algorithm); the highest-ranked wins.
        candidates
        (keep
          (fn [[id meta]]
            ;; Use the pre-compiled pattern from registration; fall back
            ;; to ad-hoc compile only if metadata didn't carry one
            ;; (defensive — shouldn't happen).
            (when-let [compiled (or (:rf.route/compiled meta)
                                    (some-> (:path meta) compile-pattern))]
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
         n     (count pattern)
         parts (atom [])
         i     (atom 0)
         emit  (fn [x] (swap! parts conj x))]
     (loop []
       (when (< @i n)
         (let [ch (.charAt pattern @i)]
           (cond
             (= ch \{)
             (let [[after-end inner-names] (collect-param-names-in-group pattern (inc @i))
                   all-present? (every? #(some? (get path-params (keyword %))) inner-names)]
               (if all-present?
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
                                 (emit (url-encode (get path-params k)))
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
                                 (emit (url-encode-splat (get path-params k)))
                                 (reset! i end))

                               :else
                               (do (emit (str c2)) (swap! i inc)))
                             (recur))))))
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
                   k     (keyword (subs pattern start end))
                   v     (or (get path-params k)
                             (throw (ex-info ":rf.error/missing-route-param"
                                             {:param k :route-id route-id})))]
               (emit (url-encode v))
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
                   k     (keyword (subs pattern start end))
                   v     (or (get path-params k)
                             (throw (ex-info ":rf.error/missing-route-param"
                                             {:param k :route-id route-id})))]
               (emit (url-encode-splat v))
               (reset! i end))

             :else
             (do (emit (str ch)) (swap! i inc))))
         (recur)))
     (let [path-out (apply str @parts)
           qs (when (seq query-params)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]]
                              (str (url-encode (name k)) "="
                                   (url-encode v)))
                            query-params))))]
       (str path-out qs)))))

;; ---- standard handlers ----------------------------------------------------

;; ---- scroll-restoration helpers -------------------------------------------
;;
;; Per Spec 012 §Scroll restoration: the runtime captures scroll positions
;; per URL on every navigation so a later :restore strategy can re-apply
;; them. Per Spec 012 §Multi-frame routing the saved-position map is
;; per-frame (each frame's :route slice is independent and the URL it
;; remembers is its own). The map lives in app-db at
;; [:rf.route/scroll-positions]; helpers below work against a db value.

(defn lookup-scroll-position
  "Return the saved [x y] for url in this frame's app-db, or nil if none."
  [db url]
  (get-in db [:rf.route/scroll-positions url]))

(defn save-scroll-position
  "Pure: return db with the scroll position for url recorded under
  [:rf.route/scroll-positions url]. Used inside :db effect maps so
  scroll positions live under the frame boundary (Spec 012 §Multi-frame
  routing)."
  [db url xy]
  (assoc-in db [:rf.route/scroll-positions url] xy))

(defn- route-descriptor
  "Build the {:id :params :query} descriptor used by :rf.nav/scroll's
  :from / :to args from a :route slice (or nil if no slice yet)."
  [route-slice]
  (when (and route-slice (:id route-slice))
    (cond-> {:id (:id route-slice)}
      (seq (:params route-slice)) (assoc :params (:params route-slice))
      (seq (:query route-slice))  (assoc :query  (:query route-slice)))))

(defn- resolve-scroll-strategy
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

(defn- scroll-fx-entry
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
          on-match-vec (vec (or (:on-match route-meta) []))
          ;; Per Spec 012 §Scroll restoration: forward navigation defaults
          ;; to :top. Resolve the strategy from opts → route-meta → default.
          fragment    (:fragment opts)
          to-route    (cond-> {:id route-id}
                        (seq path-params)  (assoc :params path-params)
                        (seq query-params) (assoc :query  query-params))
          strategy    (resolve-scroll-strategy route-meta opts :top)
          ;; Per Spec 012 §Multi-frame routing: scroll-position lookup
          ;; reads the per-frame map under [:rf.route/scroll-positions].
          scroll-fx   (scroll-fx-entry
                        {:strategy  strategy
                         :from      (route-descriptor (:route db))
                         :to        to-route
                         :saved-pos (when (= :restore strategy)
                                      (lookup-scroll-position db url))
                         :fragment  fragment})]
      {:db (assoc db :route
                  {:id         route-id
                   :params     path-params
                   :query      query-params
                   :transition (if (seq on-match-vec) :loading :idle)
                   :error      nil})
       :fx (vec (concat [push-fx]
                        (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                        (when scroll-fx [scroll-fx])))})))

(defn- split-fragment
  "Split a URL into [url-without-fragment fragment]. Returns
  [path-and-query nil] when no '#' is present, else
  [before-hash after-hash]."
  [url]
  (let [hash-idx (.indexOf url "#")]
    (if (neg? hash-idx)
      [url nil]
      [(subs url 0 hash-idx) (subs url (inc hash-idx))])))

;; Per Spec 012 §Multi-frame routing: nav-token and pending-nav id
;; counters live under the frame boundary — each frame has its own
;; epoch space at [:rf.route/nav-token-counter] and
;; [:rf.route/pending-nav-counter]. Allocators are pure: they take a
;; db value, return [db' allocated-id-string]. Callers thread the new
;; db through the :db effect map alongside their other writes.

(defn- alloc-nav-token
  "Pure allocator: returns [db' \"nav-N\"]. Increments the per-frame
  counter at [:rf.route/nav-token-counter]."
  [db]
  (let [n (inc (or (:rf.route/nav-token-counter db) 0))]
    [(assoc db :rf.route/nav-token-counter n)
     (str "nav-" n)]))

(defn- alloc-pending-nav-id
  "Pure allocator: returns [db' \"pn-N\"]. Increments the per-frame
  counter at [:rf.route/pending-nav-counter]."
  [db]
  (let [n (inc (or (:rf.route/pending-nav-counter db) 0))]
    [(assoc db :rf.route/pending-nav-counter n)
     (str "pn-" n)]))

(defn reset-counters!
  "Reset the route-registration counter to zero. Test-time helper so
  reg-index is deterministic across fixture runs. Per Spec 012
  §Multi-frame routing the nav-token and pending-nav id counters and
  the saved-scroll-positions map all live in app-db, so they reset
  naturally when a frame's app-db is reset; nothing to clear here for
  those."
  []
  (reset! reg-counter 0))

;; ---- :rf/url-requested + can-leave gating + pending-nav protocol ----------
;;
;; Per Spec 012 §Navigation blocking — pending-nav protocol: a route may
;; declare :can-leave (a sub-id whose value is true when leaving is OK).
;; A user-initiated :rf/url-requested checks the active route's can-leave.
;; If it rejects, the navigation is held in :rf/pending-navigation, a
;; :rf.route/navigation-blocked trace fires, and no URL push happens.
;; The user's app then dispatches :rf.route/continue (resume) or
;; :rf.route/cancel (drop).

(defn- can-leave?
  "Resolve and call the route's :can-leave sub against the live frame."
  [frame route-meta]
  (if-let [sub-id (:can-leave route-meta)]
    (when-let [subscribe-value (late-bind/get-fn :subs/subscribe-value)]
      (boolean (subscribe-value frame [sub-id])))
    true))

(events/reg-event-fx :rf/url-requested
  (fn [{:keys [db frame]} [_ {:keys [url] :as request}]]
    (let [m              (match-url url)
          current-route  (:route db)
          current-meta   (registrar/lookup :route (:id current-route))
          ok?            (can-leave? (or frame :rf/default) current-meta)]
      (cond
        (not ok?)
        (let [[db' pn-id] (alloc-pending-nav-id db)]
          (trace/emit! :event :rf.route/navigation-blocked
                       {:requested-url   url
                        :rejecting-route (:id current-route)})
          {:db (assoc db' :rf/pending-navigation
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

;; ---- nav-token stale suppression ------------------------------------------
;;
;; Per Spec 012 §Navigation tokens — stale-result suppression. The runtime
;; allocates a fresh nav-token on every full navigation. Async handlers
;; (typically :http :on-success) capture the token at request time and
;; thread it back when their response arrives. The runtime checks the
;; carried token against the current :route.:nav-token; mismatch means
;; the navigation has moved on and the response is stale — suppress.
;;
;; :rf.test/simulate-http-resolution is a test-only event the conformance
;; fixtures use to simulate an http :on-success arriving with a captured
;; nav-token. Real client code uses :rf.route/with-nav-token at the fx
;; layer to wrap the actual response dispatch.

(events/reg-event-fx :rf.test/simulate-http-resolution
  (fn [{:keys [db]} [_ {:keys [on-success-event carried-nav-token]}]]
    (let [current (get-in db [:route :nav-token])]
      (cond
        (= carried-nav-token current)
        ;; Token matches — dispatch the continuation.
        {:fx [[:dispatch on-success-event]]}

        :else
        ;; Stale — suppress.
        (do (trace/emit-error! :route.nav-token/stale-suppressed
                               {:carried-token carried-nav-token
                                :current-token current
                                :event-id      (when (vector? on-success-event)
                                                 (first on-success-event))
                                :recovery      :replaced-with-default})
            {})))))

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
            ;; Per Spec 009 §:op-type vocabulary: :route.url/fragment-changed
            ;; is the canonical op-type for fragment-only navigation. Spec 012
            ;; §Fragments references this name directly. Emitted alongside the
            ;; legacy :rf.route/url-changed pending its eventual removal.
            (trace/emit! :event :route.url/fragment-changed
                         {:route-id      (:id prev)
                          :prev-fragment (:fragment prev)
                          :next-fragment fragment})
            {:db (assoc-in db [:route :fragment] fragment)})

        (some? m)
        (let [route-meta    (registrar/lookup :route (:route-id m))
              on-match-vec  (vec (or (:on-match route-meta) []))
              ;; Per Spec 012 §Multi-frame routing: nav-token allocation
              ;; bumps the per-frame counter — the [db' token] tuple is
              ;; threaded through the :db write below.
              [db' token]   (alloc-nav-token db)
              ;; Per Spec 012 §Scroll restoration: a URL-driven navigation
              ;; emits :rf.nav/scroll. URL changes from clicked links /
              ;; programmatic pushes are forward-style (default :top); the
              ;; route metadata's :scroll wins when declared.
              to-route      (cond-> {:id (:route-id m)}
                              (seq (:params m)) (assoc :params (:params m))
                              (seq (:query m))  (assoc :query  (:query m)))
              strategy      (resolve-scroll-strategy route-meta nil :top)
              scroll-fx     (scroll-fx-entry
                              {:strategy  strategy
                               :from      (route-descriptor prev)
                               :to        to-route
                               :saved-pos (when (= :restore strategy)
                                            (lookup-scroll-position db url))
                               :fragment  fragment})]
          (trace/emit! :event :route.nav-token/allocated
                       {:route-id  (:route-id m)
                        :nav-token token})
          {:db (assoc db' :route
                      {:id         (:route-id m)
                       :params     (:params m)
                       :query      (:query m)
                       :fragment   fragment
                       :transition :idle
                       :error      nil
                       :nav-token  token})
           :fx (vec (concat (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                            (when scroll-fx [scroll-fx])))})

        :else
        (do (trace/emit-error! :rf.error/no-such-handler
                               {:url url :recovery :replaced-with-default})
            {})))))

(events/reg-event-fx :rf.route/handle-url-change
  (fn [{:keys [db frame]} [_ url]]
    (let [[path-q fragment] (split-fragment url)
          m                 (match-url path-q)]
      (when (nil? m)
        ;; Unmatched URL — fixture corpus calls this :rf.error/no-such-handler
        ;; in the routing context. The default error projector maps it to a
        ;; public-facing 404. Per Spec 011 §Default projector. We carry
        ;; :frame so the SSR error-projection listener can attribute the
        ;; trace to the right server frame.
        (trace/emit-error! :rf.error/no-such-handler
                           {:url url
                            :frame frame
                            :recovery :replaced-with-default}))
      (let [route-id     (or (:route-id m) :rf.route/not-found)
            params       (or (:params m) {:url url})
            query        (or (:query m) {})
            route-meta   (registrar/lookup :route route-id)
            on-match-vec (vec (or (:on-match route-meta) []))
            ;; Per Spec 012 §Scroll restoration: popstate / initial / SSR
            ;; navigations default to :restore — the saved position trumps.
            to-route     (cond-> {:id route-id}
                           (seq params) (assoc :params params)
                           (seq query)  (assoc :query  query))
            strategy     (resolve-scroll-strategy route-meta nil :restore)
            scroll-fx    (scroll-fx-entry
                           {:strategy  strategy
                            :from      (route-descriptor (:route db))
                            :to        to-route
                            :saved-pos (when (= :restore strategy)
                                         (lookup-scroll-position db url))
                            :fragment  fragment})]
        {:db (assoc db :route
                    {:id         route-id
                     :params     params
                     :query      query
                     :transition (if (seq on-match-vec) :loading :idle)
                     :error      nil})
         :fx (vec (concat (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                          (when scroll-fx [scroll-fx])))}))))

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

(fx/reg-fx :rf.nav/scroll
  {:platforms #{:client}
   :doc       "Per Spec 012 §Scroll restoration. Args: {:strategy :from
:to :saved-pos :fragment}. Standard strategies are :top, :restore,
:preserve. Map-form strategies are host-extensible; the runtime treats
unknown strategies as :preserve (no-op)."}
  (fn [_ {:keys [strategy saved-pos fragment]}]
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
       (trace/emit! :fx :rf.fx/skipped-on-platform
                    {:fx-id :rf.nav/scroll :strategy strategy}))))

;; ---- subs over the slice --------------------------------------------------
;; Will be picked up by re-frame.subs/reg-sub when this ns loads.

;; (We can't call reg-sub here directly because of dep direction; the
;; user's app calls re-frame.core/init which forwards into this.
;; For now, expose helpers and let the public API wire them.)

(defn route-sub-fn
  "Layer-1 sub fn for :route — reads the slice from app-db."
  [db _query]
  (:route db))
