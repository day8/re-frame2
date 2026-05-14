(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route) keyed by user route-id.
  Navigation is an event (:rf.route/navigate); URL changes are events
  (:rf/url-changed). The :rf/route slice in app-db carries
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
    :rf.test/simulate-http-resolution   test-only nav-token check

  Effects:
    :rf.nav/push-url    :rf.nav/replace-url    :rf.nav/scroll
    :rf.route/with-nav-token            stale-result suppression wrapper"
  (:require [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.router :as router]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs :as subs]
            [re-frame.trace :as trace]
            #?@(:cljs [[re-frame.views :as views]])))

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

(defn- segment-end
  "Scan forward from index `j` in `pattern` (length `n`) until a
  segment-boundary char is hit; return the index of that boundary (or
  `n` if none). The boundary set is always {/, {, }}; the 4-arity
  additionally treats `?` as a boundary when `?-boundary?` is truthy.
  Pure helper shared by every pattern walker in this namespace —
  replaces the four near-identical inline loops. The 3-arity (defaults
  `?-boundary?` to true) suits param / splat scanners and compile-pattern;
  pattern-shape's static-segment :else branch passes false so a `?`
  inside a static segment doesn't truncate the static run."
  ([^String pattern n j] (segment-end pattern n j true))
  ([^String pattern n j ?-boundary?]
   (loop [m j]
     (cond
       (>= m n) m
       (let [c (.charAt pattern m)]
         (or (= c \/) (= c \{) (= c \})
             (and ?-boundary? (= c \?)))) m
       :else (recur (inc m))))))

(defn- pattern-shape
  "Walk a path pattern and tally segment shapes used by the rank tuple.
  Counts non-optional segments only (rule 2 vs rule 5); the per-pattern
  optional-group count is tracked separately. Catch-all detection: the
  whole pattern is a single splat (/*name)."
  [pattern]
  (let [n          (count pattern)
        ;; Walk char-by-char. State threads as (loop [i depth seen]):
        ;; i — cursor; depth — optional-group nesting; seen — counts.
        seen       (loop [i     0
                          depth 0
                          seen  {:static 0 :named 0 :splat 0 :optional 0 :total 0}]
                     (if-not (< i n)
                       seen
                       (let [ch (.charAt ^String pattern i)]
                         (cond
                           (= ch \{)
                           (recur (inc i) (inc depth) (update seen :optional inc))

                           (= ch \})
                           (let [i' (inc i)]
                             (recur (if (and (< i' n) (= \? (.charAt ^String pattern i')))
                                      (inc i')
                                      i')
                                    (dec depth)
                                    seen))

                           (= ch \:)
                           (recur (segment-end pattern n (inc i))
                                  depth
                                  (cond-> seen
                                    (zero? depth) (-> (update :named inc)
                                                      (update :total inc))))

                           (= ch \*)
                           (recur (segment-end pattern n (inc i))
                                  depth
                                  (cond-> seen
                                    (zero? depth) (-> (update :splat inc)
                                                      (update :total inc))))

                           (= ch \/)
                           (recur (inc i) depth seen)

                           :else
                           (recur (segment-end pattern n (inc i) false)
                                  depth
                                  (cond-> seen
                                    (zero? depth) (-> (update :static inc)
                                                      (update :total inc))))))))
        catch-all? (and (= 1 (:total seen))
                        (= 1 (:splat seen))
                        (zero? (:static seen))
                        (zero? (:named seen))
                        (zero? (:optional seen)))]
    (assoc seen :catch-all? catch-all?)))

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
  (let [n  (count pattern)
        i0 (if (and (pos? n) (= \/ (.charAt ^String pattern 0))) 1 0)
        ;; Walk char-by-char. State threads as (loop [i parts names]):
        ;; i — cursor; parts — regex fragments (vector); names — captured
        ;; param names (vector). Output is (apply str parts).
        [parts names]
        (loop [i     i0
               parts ["^/?"]
               names []]
          (if-not (< i n)
            [(conj parts "$") names]
            (let [ch (.charAt ^String pattern i)]
              (cond
                (= ch \/)
                (recur (inc i) (conj parts "/") names)

                (= ch \:)
                (let [start (inc i)
                      end   (segment-end pattern n start)
                      nm    (subs pattern start end)]
                  (recur end (conj parts "([^/]+)") (conj names nm)))

                (= ch \*)
                (let [start (inc i)
                      end   (segment-end pattern n start)
                      nm    (subs pattern start end)]
                  (recur end (conj parts "(.+)") (conj names nm)))

                (= ch \{)
                (recur (inc i) (conj parts "(?:") names)

                (= ch \})
                (let [i'        (inc i)
                      ?-suffix? (and (< i' n) (= \? (.charAt ^String pattern i')))]
                  (recur (if ?-suffix? (inc i') i')
                         (cond-> (conj parts ")") ?-suffix? (conj "?"))
                         names))

                :else
                (recur (inc i) (conj parts (regex-escape (str ch))) names)))))]
    {:regex   (re-pattern (apply str parts))
     :names   names
     :pattern pattern}))

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

(defn- split-fragment
  "Split a URL into [url-without-fragment fragment]. Returns
  [path-and-query nil] when no '#' is present, else
  [before-hash after-hash]. The fragment is returned as nil when absent
  and as the raw substring (sans leading '#') when present; an empty
  fragment (URL ends with bare '#') decodes to \"\"."
  [^String url]
  (let [hash-idx (.indexOf url "#")]
    (if (neg? hash-idx)
      [url nil]
      [(subs url 0 hash-idx) (subs url (inc hash-idx))])))

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Try each registered route's
  pattern against url; return
  {:route-id :params :query :fragment :validation-failed?} for the first
  match, or nil if no route matches.

  Query string coercion: if the route declares a :query Malli schema,
  string values are coerced per key type. :query-defaults populate
  absent keys. The URL's '#fragment' portion (per Spec 012 §Fragments)
  is parsed off the front and surfaced as :fragment (string or nil);
  fragments do not participate in route matching."
  [url]
  ;; Split off the fragment first (per Spec 012 §Fragments — fragments
  ;; do not participate in route matching); then strip query string for
  ;; pattern matching and parse query separately. Uses array-map to
  ;; preserve the URL's left-to-right key order so round-trip URLs come
  ;; back byte-identical.
  (let [[url-no-frag fragment] (split-fragment url)
        [path query-str]       (clojure.string/split url-no-frag #"\?" 2)
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
                   :fragment           fragment
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
              end   (segment-end pattern n start)]
          (recur end (conj names (subs pattern start end))))

        :else
        (recur (inc j) names)))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Build a URL string from a
  route-id + path-params (+ optional query-params + optional fragment).
  Inverse of match-url.

  Optional groups ({...}?) are emitted only when ALL their inner params
  are supplied in path-params; otherwise the group is silently elided.

  4-arity: when `fragment` is non-nil and non-empty, appends `#fragment`
  to the URL (per Spec 012 §Fragments §Programmatic navigation with
  fragments). nil or empty-string fragments are not appended."
  ([route-id path-params] (route-url route-id path-params {} nil))
  ([route-id path-params query-params] (route-url route-id path-params query-params nil))
  ([route-id path-params query-params fragment]
   (let [meta    (registrar/lookup :route route-id)
         pattern (:path meta)]
     (when (nil? pattern)
       (throw (ex-info ":rf.error/no-such-route" {:route-id route-id})))
     (let [n     (count pattern)
           ;; Inner loop emits the body of an optional group whose params
           ;; are all present. State threads as (loop [i parts]); returns
           ;; [next-i parts'] when the group's '}' (and optional '?') is
           ;; consumed.
           emit-group
           (fn emit-group [i parts]
             (loop [i     i
                    parts parts]
               (let [c2 (.charAt ^String pattern i)]
                 (cond
                   (= c2 \})
                   (let [k (inc i)]
                     [(if (and (< k n) (= \? (.charAt ^String pattern k))) (inc k) k)
                      parts])

                   (= c2 \:)
                   (let [start (inc i)
                         end   (segment-end pattern n start)
                         k     (keyword (subs pattern start end))]
                     (recur end (conj parts (url-encode (get path-params k)))))

                   (= c2 \*)
                   (let [start (inc i)
                         end   (segment-end pattern n start)
                         k     (keyword (subs pattern start end))]
                     (recur end (conj parts (url-encode-splat (get path-params k)))))

                   :else
                   (recur (inc i) (conj parts (str c2)))))))
           parts
           (loop [i     0
                  parts []]
             (if-not (< i n)
               parts
               (let [ch (.charAt ^String pattern i)]
                 (cond
                   (= ch \{)
                   (let [[after-end inner-names] (collect-param-names-in-group pattern (inc i))
                         all-present? (every? #(some? (get path-params (keyword %))) inner-names)]
                     (if all-present?
                       (let [[i' parts'] (emit-group (inc i) parts)]
                         (recur i' parts'))
                       (recur after-end parts)))

                   (= ch \:)
                   (let [start (inc i)
                         end   (segment-end pattern n start)
                         k     (keyword (subs pattern start end))
                         v     (or (get path-params k)
                                   (throw (ex-info ":rf.error/missing-route-param"
                                                   {:param k :route-id route-id})))]
                     (recur end (conj parts (url-encode v))))

                   (= ch \*)
                   (let [start (inc i)
                         end   (segment-end pattern n start)
                         k     (keyword (subs pattern start end))
                         v     (or (get path-params k)
                                   (throw (ex-info ":rf.error/missing-route-param"
                                                   {:param k :route-id route-id})))]
                     (recur end (conj parts (url-encode-splat v))))

                   :else
                   (recur (inc i) (conj parts (str ch)))))))
           path-out (apply str parts)
           qs (when (seq query-params)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]]
                              (str (url-encode (name k)) "="
                                   (url-encode v)))
                            query-params))))
           ;; Per Spec 012 §Fragments §Programmatic navigation with
           ;; fragments: the 4-arity emits `#fragment` when non-nil and
           ;; non-empty. Empty-string fragments collapse to no fragment.
           frag (when (and fragment (not= "" fragment))
                  (str "#" fragment))]
       (str path-out qs frag)))))

;; ---- standard handlers ----------------------------------------------------

;; ---- scroll-restoration helpers -------------------------------------------
;;
;; Per Spec 012 §Scroll restoration: the runtime captures scroll positions
;; per URL on every navigation so a later :restore strategy can re-apply
;; them. Per Spec 012 §Multi-frame routing the saved-position map is
;; per-frame (each frame's :rf/route slice is independent and the URL it
;; remembers is its own). The map lives in app-db at
;; [:rf.route/scroll-positions]; helpers below work against a db value.
;;
;; LRU cap: the map is bounded so a long session over many URLs (SPAs that
;; deep-link through ids — `/articles/:id`, `/users/:id`) can't grow it
;; unboundedly. Recency is tracked under [:rf.route/scroll-positions-order];
;; on each save the url is appended (or re-promoted) to the tail and any
;; head entries beyond `scroll-positions-cap` are evicted from both the
;; order vector and the position map. Tools and migrations that inspect
;; [:rf.route/scroll-positions <url>] still see the raw [x y]; the order
;; key is an internal LRU anchor.

(def ^:private scroll-positions-cap
  "Soft upper bound on tracked URLs in the per-frame scroll-positions map.
  Sized for typical SPA navigation depth — large enough that real
  Back-button restoration hits saved positions, small enough that the
  per-frame app-db slice stays bounded over long sessions."
  50)

(defn lookup-scroll-position
  "Return the saved [x y] for url in this frame's app-db, or nil if none."
  [db url]
  (get-in db [:rf.route/scroll-positions url]))

(defn save-scroll-position
  "Pure: return db with the scroll position for url recorded under
  [:rf.route/scroll-positions url]. Used inside :db effect maps so
  scroll positions live under the frame boundary (Spec 012 §Multi-frame
  routing). The map is LRU-capped at `scroll-positions-cap` entries —
  re-saving an existing url promotes it to most-recent; new saves past
  the cap evict the least-recently-used entry."
  [db url xy]
  (let [order   (or (:rf.route/scroll-positions-order db) [])
        order'  (-> (filterv #(not= url %) order)
                    (conj url))
        over    (- (count order') scroll-positions-cap)
        dropped (when (pos? over) (subvec order' 0 over))
        order'' (if (pos? over) (subvec order' over) order')
        positions  (as-> (or (:rf.route/scroll-positions db) {}) m
                     (if dropped (apply dissoc m dropped) m)
                     (assoc m url xy))]
    (assoc db
           :rf.route/scroll-positions       positions
           :rf.route/scroll-positions-order order'')))

(defn- route-descriptor
  "Build the {:id :params :query} descriptor used by :rf.nav/scroll's
  :from / :to args from a :rf/route slice (or nil if no slice yet)."
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
    ;; Per Spec 012 §Navigation is an event and §Fragments §Programmatic
    ;; navigation with fragments. Fragment may be supplied in opts
    ;; (`{:fragment "x"}`), on the target-map form (`{:url "/x"
    ;; :fragment "y"}`), or — for URL-string targets — embedded in the
    ;; URL itself; match-url surfaces the latter. Opts/target-map win
    ;; over a URL-embedded fragment.
    (let [{:keys [route-id path-params query-params matched-fragment]}
          (cond
            (keyword? target)
            {:route-id     target
             :path-params  (or params {})
             :query-params (:query opts {})}

            (and (map? target) (:url target))
            (let [m (match-url (:url target))]
              {:route-id         (or (:route-id m) :rf.route/not-found)
               :path-params      (:params m {:url (:url target)})
               :query-params     (:query m {})
               :matched-fragment (:fragment m)}))
          fragment    (or (:fragment opts)
                          (and (map? target) (:fragment target))
                          matched-fragment)
          route-meta  (registrar/lookup :route route-id)
          url (try (route-url route-id path-params query-params fragment)
                   (catch #?(:clj Throwable :cljs :default) _ "/"))
          push-fx (if (:replace? opts)
                    [:rf.nav/replace-url url]
                    [:rf.nav/push-url    url])
          on-match-vec (vec (or (:on-match route-meta) []))
          ;; Per Spec 012 §Scroll restoration: forward navigation defaults
          ;; to :top. Resolve the strategy from opts → route-meta → default.
          to-route    (cond-> {:id route-id}
                        (seq path-params)  (assoc :params path-params)
                        (seq query-params) (assoc :query  query-params))
          strategy    (resolve-scroll-strategy route-meta opts :top)
          ;; Per Spec 012 §Multi-frame routing: scroll-position lookup
          ;; reads the per-frame map under [:rf.route/scroll-positions].
          scroll-fx   (scroll-fx-entry
                        {:strategy  strategy
                         :from      (route-descriptor (:rf/route db))
                         :to        to-route
                         :saved-pos (when (= :restore strategy)
                                      (lookup-scroll-position db url))
                         :fragment  fragment})]
      {:db (assoc db :rf/route
                  {:id         route-id
                   :params     path-params
                   :query      query-params
                   :transition (if (seq on-match-vec) :loading :idle)
                   :error      nil})
       :fx (vec (concat [push-fx]
                        (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                        (when scroll-fx [scroll-fx])))})))

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
          current-route  (:rf/route db)
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
;; carried token against the current :rf/route :nav-token; mismatch means
;; the navigation has moved on and the response is stale — suppress.
;;
;; :rf.test/simulate-http-resolution is a test-only event the conformance
;; fixtures use to simulate an http :on-success arriving with a captured
;; nav-token. Real client code uses :rf.route/with-nav-token at the fx
;; layer to wrap the actual response dispatch.

(events/reg-event-fx :rf.test/simulate-http-resolution
  (fn [{:keys [db]} [_ {:keys [on-success-event carried-nav-token]}]]
    (let [current (get-in db [:rf/route :nav-token])]
      (cond
        (= carried-nav-token current)
        ;; Token matches — dispatch the continuation.
        {:fx [[:dispatch on-success-event]]}

        :else
        ;; Stale — suppress.
        (do (trace/emit-error! :rf.route.nav-token/stale-suppressed
                               {:carried-token carried-nav-token
                                :current-token current
                                :event-id      (when (vector? on-success-event)
                                                 (first on-success-event))
                                :recovery      :replaced-with-default})
            {})))))

(events/reg-event-fx :rf/url-changed
  (fn [{:keys [db]} [_ url]]
    ;; Per Spec 012 §URL changes are events / §Fragments. match-url
    ;; surfaces the URL's `#fragment` directly on its result; if only
    ;; the fragment differs from the current slice, update :fragment but
    ;; DO NOT re-fire :on-match — emit :rf.route/url-changed instead.
    ;; Otherwise full nav: allocate a nav-token, write new slice, fire
    ;; :on-match.
    (let [m                 (match-url url)
          fragment          (:fragment m)
          prev              (:rf/route db)
          fragment-only?    (and prev m
                                 (= (:id prev)     (:route-id m))
                                 (= (:params prev) (:params m))
                                 (= (:query prev)  (:query m))
                                 (not= (:fragment prev) fragment))]
      (cond
        fragment-only?
        ;; Per Spec 009 §:op-type vocabulary and Spec 012 §Fragments:
        ;; :rf.route/url-changed is the canonical op-name for fragment-only
        ;; navigation; consumers discriminate full vs fragment-only by
        ;; :tags (the fragment-only emission carries :prev-fragment /
        ;; :next-fragment and never coincides with a
        ;; :rf.route.nav-token/allocated on the same drain).
        (do (trace/emit! :event :rf.route/url-changed
                         {:route-id      (:id prev)
                          :prev-fragment (:fragment prev)
                          :next-fragment fragment})
            {:db (assoc-in db [:rf/route :fragment] fragment)})

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
          (trace/emit! :event :rf.route.nav-token/allocated
                       {:route-id  (:route-id m)
                        :nav-token token})
          {:db (assoc db' :rf/route
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
        ;; Unmatched URL — same operation as event/frame handler-lookup
        ;; misses, discriminated by `:kind :route`. See [Spec 009 §Error
        ;; categories — :rf.error/no-such-handler] for the three-way `:kind`
        ;; vocabulary (`:event`, `:frame`, `:route`).
        (do (trace/emit-error! :rf.error/no-such-handler
                               {:url url
                                :kind :route
                                :recovery :replaced-with-default})
            {})))))

(events/reg-event-fx :rf.route/handle-url-change
  (fn [{:keys [db frame]} [_ url]]
    (let [m        (match-url url)
          fragment (:fragment m)]
      (when (nil? m)
        ;; Unmatched URL — fixture corpus calls this :rf.error/no-such-handler
        ;; in the routing context. The default error projector maps it to a
        ;; public-facing 404. Per Spec 011 §Default projector. We carry
        ;; :frame so the SSR error-projection listener can attribute the
        ;; trace to the right server frame. The `:kind :route` tag
        ;; discriminates from `:kind :event` (router.cljc handler-lookup miss)
        ;; and `:kind :frame` (epoch.cljc frame-lookup miss) — see
        ;; [Spec 009 §Error categories — :rf.error/no-such-handler].
        (trace/emit-error! :rf.error/no-such-handler
                           {:url url
                            :frame frame
                            :kind :route
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
                            :from      (route-descriptor (:rf/route db))
                            :to        to-route
                            :saved-pos (when (= :restore strategy)
                                         (lookup-scroll-position db url))
                            :fragment  fragment})]
        {:db (assoc db :rf/route
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

;; ---- :rf.route/with-nav-token --------------------------------------------
;;
;; Per Spec 012 §Navigation tokens §Threading and §Trace events, and the
;; `:rf.fx/with-nav-token-args` schema in Spec-Schemas.md. The fx wraps an
;; async-completion fx entry (`:do`) with a stale-result check: at fire
;; time, the carried `:nav-token` is compared against the current
;; `:rf/route :nav-token` in app-db; on match, the inner fx entry is
;; dispatched through the regular fx machinery (so `:dispatch`,
;; `:dispatch-later`, `:rf.http/managed`, etc all flow through); on
;; mismatch, the inner fx is suppressed and the runtime emits
;; `:rf.route.nav-token/stale-suppressed` with the canonical tags
;; (`:carried-token`, `:current-token`, `:event-id`) — the handler does
;; NOT run (no `:db` write, no `:fx`, no transition).
;;
;; This is the production-grade staleness-suppression path; the
;; `:rf.test/simulate-http-resolution` event above is its test-only
;; conformance-fixture stand-in (used where the fixture DSL can't
;; emit an effect-map of its own).

(defn- inner-fx-event-id
  "Best-effort extraction of an `event-id` from an `:do` fx entry. For
  the canonical `[:dispatch [<event-id> args...]]` shape the event-id is
  the head of the inner vector; for any other fx entry we fall back to
  the outer fx-id (e.g. `:rf.http/managed`) so the `:event-id` tag still
  identifies what was suppressed."
  [do-entry]
  (when (vector? do-entry)
    (let [[fx-id args] do-entry]
      (cond
        (and (= :dispatch fx-id) (vector? args) (seq args))
        (first args)

        :else
        fx-id))))

(fx/reg-fx :rf.route/with-nav-token
  {:doc  "Per Spec 012 §Navigation tokens. Threads the carried
`:nav-token` against the current `:rf/route :nav-token`. Match → run
`:do` (any fx entry); mismatch → suppress and emit
`:rf.route.nav-token/stale-suppressed`."
   ;; Inline Malli schema per Spec-Schemas.md §`:rf.fx/with-nav-token-args`.
   ;; Inline rather than a registered schema-id so validation works in
   ;; consumers that don't pre-register the keyword in their Malli
   ;; registry; the registered-id form remains available to apps that
   ;; want to centralise schemas (per Spec 010 §Schema registration).
   :spec [:map
          [:do        [:vector :any]]
          [:nav-token :any]]}
  (fn [{:keys [frame] :as _ctx} args]
    ;; Destructure `:do` via `get` rather than `:keys` so the binding name
    ;; doesn't shadow `clojure.core/do` inside the body. Per Spec 012
    ;; §Threading the `:do` slot is the wrapped fx entry to perform.
    (let [do-entry        (get args :do)
          nav-token       (get args :nav-token)
          frame-id        (or frame :rf/default)
          frame-record    (frame/frame frame-id)
          db              (frame/frame-app-db-value frame-id)
          current         (get-in db [:rf/route :nav-token])]
      (cond
        (= nav-token current)
        ;; Token matches — route the inner fx entry through
        ;; `fx/handle-one-fx`. Routing it through the same machinery means
        ;; `:dispatch`, `:dispatch-later`, `:rf.http/managed`, et al. all
        ;; work uniformly. `handle-one-fx` rather than `do-fx` so the
        ;; cascade's single `:event/do-fx` boundary marker stays on the
        ;; outer walk (the inner re-entry must not double-emit it — the
        ;; epoch projection's six-domino bucketing keys off that marker
        ;; per `trace/projection.cljc`). The active-platform resolution
        ;; mirrors `router/run-fx-effects!` so a server-only or
        ;; client-only inner fx skips with the standard
        ;; `:rf.fx/skipped-on-platform` trace.
        (let [active-platform (or (get-in frame-record [:config :platform])
                                  interop/platform)]
          (fx/handle-one-fx frame-id do-entry active-platform {} nil))

        :else
        ;; Stale — suppress. Same trace shape as
        ;; `:rf.test/simulate-http-resolution` so a single conformance
        ;; assertion covers both production and test paths.
        (trace/emit-error! :rf.route.nav-token/stale-suppressed
                           {:carried-token nav-token
                            :current-token current
                            :event-id      (inner-fx-event-id do-entry)
                            :recovery      :replaced-with-default})))))

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

;; ---- framework-shipped subs over the slice -------------------------------
;;
;; Per Spec 012 the framework ships `:rf/route` (the layer-1 read of the
;; :rf/route slice) and the layer-2 derivations `:rf.route/{id,params,query,
;; transition,error}`. Per rf2-k682 these subs ship in this artefact
;; (rather than `re-frame.core`) so apps that don't pull
;; `day8/re-frame2-routing` carry neither the registration metadata nor
;; the `:rf.route/*` keyword strings on their production-elision bundle.
;;
;; Lives in this namespace (rather than core.cljc) so the smoke-test
;; fixture's `require :reload` re-installs the registrations after
;; `registrar/clear-all!` — exactly the same ergonomic the machines
;; namespace's `:rf/machine` reg-sub uses.

(defn route-sub-fn
  "Layer-1 sub fn for :rf/route — reads the slice from app-db. Exposed
  publicly so external callers (smoke tests, tooling) can recover the
  same projection without re-deriving it."
  [db _query]
  (:rf/route db))

(subs/reg-sub :rf/route
  {:doc "Subscribe to the current route map `{:id :params :query :transition :error}`. Layer-1 read of the `:rf/route` slice. Per Spec 012."}
  route-sub-fn)
(subs/reg-sub :rf.route/id
  {:doc "Subscribe to the current route's `:id` keyword. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:id route)))
(subs/reg-sub :rf.route/params
  {:doc "Subscribe to the current route's path params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:params route)))
(subs/reg-sub :rf.route/query
  {:doc "Subscribe to the current route's query params map. Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:query route)))
(subs/reg-sub :rf.route/transition
  {:doc "Subscribe to the current route's `:transition` state. Per Spec 012 §Route transitions."}
  :<- [:rf/route] (fn [route _] (:transition route)))
(subs/reg-sub :rf.route/error
  {:doc "Subscribe to the current route's `:error` (nil when no error). Per Spec 012."}
  :<- [:rf/route] (fn [route _] (:error route)))

;; ---- route-link registered view ------------------------------------------
;;
;; Per Spec 012 §Linking from views and §Standard runtime events, and the
;; API.md `route-link` row: a registered view at `:route/link` renders an
;; `<a href="...">` for a known route-id and intercepts plain primary-button
;; clicks. Plain left-click (no modifier keys) dispatches
;; `:rf/url-requested`; modifier-key clicks (cmd / ctrl / shift / alt) and
;; auxiliary-button clicks (middle-click) defer to the browser so users get
;; the native open-in-new-tab affordance.
;;
;; The view is CLJS-only — anchor rendering is DOM-bound and there is no
;; meaningful JVM counterpart. The pure helpers `route-url` (URL synthesis)
;; and the `:rf/url-requested` event handler are both JVM-callable, which
;; is what the JVM-side tests reach for.
;;
;; Frame-handling: the view dispatches via `re-frame.router/dispatch!`,
;; which captures the frame at call time through the same resolution chain
;; (dynamic var → React-context → `:rf/default`) every registered view's
;; auto-injected `dispatch` already uses. Routes themselves are a single
;; global registry (the `:route` registrar kind), so per-frame routing is
;; a non-issue at the link layer — the URL string is derived from the
;; route id alone, and the resulting `:rf/url-requested` lands in the
;; frame that owned the click.

#?(:cljs
   (defn- plain-left-click?
     "Return true when the click event is a plain primary-button click with
     no modifier keys. Modifier-key or auxiliary-button clicks defer to
     the browser so users keep open-in-new-tab / open-in-new-window
     affordances."
     [e]
     (and (zero? (.-button e))
          (not (.-metaKey e))
          (not (.-ctrlKey e))
          (not (.-shiftKey e))
          (not (.-altKey e)))))

#?(:cljs
   (defn route-link-render
     "Render fn for the `:route/link` registered view. Exposed (without
     the registry wrap) so tests can call it directly without going
     through Reagent's component pipeline.

     Shape:
       [rf/route-link {:to :route-id
                       :params {...}
                       :query {...}
                       :fragment \"...\"
                       :on-click <opt user fn>
                       & passthrough-html-attrs}
        & children]

     `:to` is the only required key. `:params`, `:query`, and `:fragment`
     are forwarded to `route-url` for href synthesis. Any other key on the
     props map is passed through to the underlying `<a>` element (e.g.
     `:class`, `:title`, `:id`, `:aria-label`).

     If the caller supplies an `:on-click` fn, it is invoked first; when
     it calls `.preventDefault` (or otherwise the event's
     `defaultPrevented` is true after it returns) the framework's
     plain-left-click interception is skipped — the caller has taken
     responsibility for the navigation. Otherwise the standard rules
     apply: plain left-click → `preventDefault` + dispatch
     `:rf/url-requested`; modifier-key or middle-click → no interception."
     [{:keys [to params query fragment on-click] :as props} & children]
     (let [url   (route-url to (or params {}) (or query {}) fragment)
           attrs (-> props
                     (dissoc :to :params :query :fragment :on-click)
                     (assoc :href url
                            :on-click
                            (fn [e]
                              (when on-click (on-click e))
                              (when (and (not (.-defaultPrevented e))
                                         (plain-left-click? e))
                                (.preventDefault e)
                                (router/dispatch!
                                  [:rf/url-requested
                                   (cond-> {:url url :to to}
                                     (seq params)   (assoc :params params)
                                     (seq query)    (assoc :query  query)
                                     fragment       (assoc :fragment fragment))])))))]
       (into [:a attrs] children))))

(defn route-link-render-ssr
  "JVM render fn for `:route/link`. Renders the `<a href=...>` shell
  without the click-interception logic — server-side rendering has no
  DOM events to intercept, so the anchor is emitted as-is and clicks
  on the hydrated page run the CLJS render fn's on-click path. Per
  Spec 011 the render tree is the contract; this is the JVM half of
  that contract for the `:route/link` view."
  [{:keys [to params query fragment] :as props} & children]
  (let [url   (route-url to (or params {}) (or query {}) fragment)
        attrs (-> props
                  (dissoc :to :params :query :fragment :on-click)
                  (assoc :href url))]
    (into [:a attrs] children)))

#?(:cljs
   (def route-link
     "Registered view at `:route/link`. Intercepts plain left-clicks and
     dispatches `:rf/url-requested`; modifier-key clicks defer to the
     browser. Per Spec 012 §Linking from views and API.md `route-link`
     row. The underlying render fn is `route-link-render`."
     (views/reg-view* :route/link
                      (source-coords/merge-coords {})
                      route-link-render))
   :clj
   ;; JVM: register the SSR-side render fn under :route/link so the
   ;; registrar carries the slot on both platforms (route-link views in
   ;; .cljc render trees resolve identically server- and client-side).
   (registrar/register! :view :route/link
                        (assoc (source-coords/merge-coords {})
                               :handler-fn route-link-render-ssr)))

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per rf2-k682 the routing surface ships in `day8/re-frame2-routing`.
;; `re-frame.core` MUST NOT `:require [re-frame.routing]` — the artefact
;; is optional, and a static require would force every consumer of the
;; core artefact to drag the namespace, the route-rank / pattern-compile
;; / nav-token machinery, the `:rf/route` reg-sub family, and every
;; `:rf.route/*` / `:rf.nav/*` trace/event keyword onto the classpath.
;; The public-API re-exports (`reg-route`, `match-url`, `route-url`) are
;; published through the late-bind table; consumers without the routing
;; artefact see the hooks unregistered and the active surfaces throw
;; cleanly while the read-only surfaces return safe defaults.

(late-bind/set-fn! :routing/reg-route        reg-route)
(late-bind/set-fn! :routing/match-url        match-url)
(late-bind/set-fn! :routing/route-url        route-url)
(late-bind/set-fn! :routing/reset-counters!  reset-counters!)
(late-bind/set-fn! :routing/route-sub-fn     route-sub-fn)

;; route-link is exposed on both platforms so .cljc render trees that
;; embed `[rf/route-link ...]` resolve identically server- and client-side.
;; CLJS publishes the Reagent-wrapped render fn (the one `reg-view*`
;; returned); JVM publishes the SSR render fn. Late-bind keeps
;; `re-frame.core` decoupled from this artefact.
#?(:cljs (late-bind/set-fn! :routing/route-link route-link)
   :clj  (late-bind/set-fn! :routing/route-link route-link-render-ssr))
