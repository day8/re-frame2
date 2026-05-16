(ns re-frame.routing
  "Routing as state. Per Spec 012.

  Routes are registry entries (kind :route). Navigation is an event;
  URL changes are events. The :rf/route slice carries
  {:id :params :query :fragment :transition :error :nav-token}."
  (:require [re-frame.registrar :as registrar]
            [re-frame.error-emit :as error-emit]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.router :as router]
            [re-frame.routing.match :as match]
            [re-frame.routing.url :as url]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs :as subs]
            [re-frame.trace :as trace]
            #?@(:cljs [[re-frame.views :as views]])))

;; ---- url encoding / decoding ---------------------------------------------
;; Moved to `re-frame.routing.url` (rf2-icrxv Phase-2 — URL seam).
;; The public predicate `malformed-url?` is re-exported below as the
;; facade's stable entry point; internal callers within this ns use the
;; `url/` alias.

(defn malformed-url?
  "Public predicate: true when `url`'s percent-encoding is malformed in
  any of its decode'd portions (path captures via the registered
  routes' patterns, query keys, query values, or `#fragment`). Used by
  `:rf/url-changed` / `:rf.route/handle-url-change` to discriminate the
  bare route-miss case (`{:url url}`) from the malformed-URL fail-
  closed case (`{:url url :reason :malformed-url}`) — both end up at
  `:rf.route/not-found` but the structured `:reason` lets per-route
  error UIs and SSR projections branch on the cause.

  Per Spec 012 §Routing failure semantics §Malformed percent-encoding
  (rf2-4ic0f). Thin facade over `re-frame.routing.url/malformed-url?`
  (rf2-icrxv Phase-2 — URL seam)."
  [url]
  (url/malformed-url? url))

;; ---- registration ---------------------------------------------------------
;; Pattern validation, the single-pass pattern parser, and the
;; `segment-end` / `canonical-route-pattern` helpers moved to
;; `re-frame.routing.match` (rf2-icrxv Phase-2 — PATTERN seam).
;; Internal callers within this ns use the `match/` alias.

(defonce ^:private reg-counter (atom 0))

;; ---- pre-sorted route table -----------------------------------------------
;; Vector of `[id meta]` pairs sorted by `:rf.route/rank` descending.
;; `match-url` iterates in pre-sorted order and short-circuits on the
;; first pattern that matches — that IS the highest-rank winner. Cache
;; invalidation is automatic via registrar map-identity (rf2-9ihwx).

(defonce ^:private route-table-cache
  ;; {:source-id <identity of the registrar's :route map at build time>
  ;;  :pairs    <vector of [id meta] pairs sorted by rank descending>}
  ;; nil ⇒ never built. Stale-check compares :source-id against the
  ;; current registrar map identity so clear-all! / clear-kind! / any
  ;; out-of-band mutation invalidates without an explicit hook.
  (atom nil))

(defn- rebuild-route-table-cache!
  "Read the current `:route` kind from the registrar, sort descending by
  rank, and replace the cache. Returns the new pairs vector."
  []
  (let [source (registrar/registrations :route)
        pairs  (->> source
                    (sort-by (fn [[_id meta]]
                               (or (:rf.route/rank meta) [0 0 0 0 0 0]))
                             #(compare %2 %1))
                    vec)]
    (reset! route-table-cache {:source-id source :pairs pairs})
    pairs))

(defn- route-table
  "Return the cached pre-sorted route table, rebuilding when the
  underlying registrar map changes identity (Spec 002 §The public
  registrar query API — `registrations` returns a snapshot map, so identity
  equality is a safe invalidation signal — register! / clear-kind! /
  clear-all! all swap the underlying ref, so the snapshot identity
  changes on every mutation)."
  []
  (let [cache  @route-table-cache
        source (registrar/registrations :route)]
    (if (and cache (identical? source (:source-id cache)))
      (:pairs cache)
      (rebuild-route-table-cache!))))

(declare compile-query-coercions)

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
  (let [pattern      (match/canonical-route-pattern (:path metadata))
        metadata     (assoc metadata :path pattern)
        idx          (swap! reg-counter inc)
        _            (match/validate-route-pattern! id pattern)
        ;; Single-pass parse: rank + regex + capture names +
        ;; per-optional-group lookup all derive from one left-to-right
        ;; walk (rf2-uovh5). Pre-rf2-uovh5 these were three separate
        ;; walkers with hand-replicated segment-end logic.
        parsed       (match/parse-pattern pattern)
        structural   (when parsed (:rank parsed))
        rank         (when structural (conj structural (- idx)))
        compiled     (when parsed (select-keys parsed [:regex :names :pattern :groups]))
        query-coerce (compile-query-coercions (:query metadata))
        meta'        (cond-> (source-coords/merge-coords metadata)
                       rank         (assoc :rf.route/rank rank)
                       compiled     (assoc :rf.route/compiled compiled)
                       query-coerce (assoc :rf.route/query-coerce query-coerce))]
    ;; Spec 012 rule-6 warning: scan existing routes for one whose
    ;; structural rank (rules 1-5) equals ours. The match-time tuple
    ;; (`:rf.route/rank`) carries `(- reg-index)` as its trailing
    ;; element and is structurally one longer; drop that suffix.
    (when structural
      (when-let [shadowed
                 (some (fn [[other-id other-meta]]
                         (when-let [other-rank (:rf.route/rank other-meta)]
                           (when (and (not= other-id id)
                                      (= structural (subvec other-rank 0 5)))
                             other-id)))
                       (registrar/registrations :route))]
        (trace/emit! :warning :rf.warning/route-shadowed-by-equal-score
                     {:route-id id :shadowed shadowed})))
    (registrar/register! :route id meta')
    ;; Cache invalidation is automatic — the registrar's `:route` map
    ;; gets a new identity on every register!, and `route-table` checks
    ;; identity equality before reusing the cached pairs vector.
    id))

;; ---- match + coerce -------------------------------------------------------
;; `match-against` moved to `re-frame.routing.match` (rf2-icrxv Phase-2
;; — PATTERN seam); call sites below use the `match/` alias.

(def ^:const default-max-decoded-keys
  "Default cap on the number of unique query-string keys a single URL
  may carry through `match-url`. Per rf2-3k3o7 — a defensive ceiling
  against the keyword-interning DoS surface on long-running JVMs,
  symmetric with `:rf.http/max-decoded-keys` (rf2-wu1n5). JVM keywords
  intern into a process-global, never-GC'd table; a hostile partner
  URL stream with N-unique query keys per request burns N permanent
  slots. 10000 is generous enough not to false-positive on legitimate
  large URLs, finite enough to bound an attacker-controlled payload.

  Override per route via the `:rf.route/max-decoded-keys` route-meta
  slot; absent → this default."
  10000)

(defn- compile-query-coercions
  "Flatten a `[:map [k type-or-opts] ...]` Malli vector schema into a
  `{k type-form}` map for O(1) per-key lookup during URL coercion.
  Returns nil when the schema is absent or not a vector. Computed once
  at registration time and cached on the route metadata under
  `:rf.route/query-coerce` (rf2-yjjrv); pre-rf2-yjjrv this re-scanned
  `(rest schema)` per query key per nav.

  Per rf2-3k3o7: when the slot's type-form is a bare `[:enum ...]` with
  all-keyword choices, the type-form is rewritten as `[:rf.route/enum-keyword #{choice-names...}]`
  — an allowlist of permitted string-→keyword conversions. A bare
  `:keyword` type-form (no enum allowlist) is rewritten as
  `:rf.route/keyword-unbounded` so the coercer can flag it as a
  string-passthrough rather than an unbounded intern site."
  [schema]
  (when (and schema (vector? schema))
    (persistent!
      (reduce
        (fn [m e]
          (if (and (vector? e) (keyword? (first e)))
            (let [k         (first e)
                  raw       (cond
                              (= 2 (count e)) (second e)
                              (= 3 (count e)) (last e)
                              :else           nil)
                  ;; rf2-3k3o7: detect `[:enum kw kw ...]` as a bounded
                  ;; keyword allowlist. Skip the optional opts-map at
                  ;; position 1 when present (Malli convention:
                  ;; `[:enum {...opts} :a :b]`).
                  enum-set  (when (and (vector? raw) (= :enum (first raw)))
                              (let [tail (rest raw)
                                    ;; Strip leading opts-map if present.
                                    items (if (and (seq tail) (map? (first tail)))
                                            (rest tail)
                                            tail)]
                                (when (and (seq items) (every? keyword? items))
                                  (into #{} (map name) items))))
                  type-form (cond
                              enum-set        [:rf.route/enum-keyword enum-set]
                              (= :keyword raw) :rf.route/keyword-unbounded
                              :else           raw)]
              (assoc! m k type-form))
            m))
        (transient {})
        (rest schema)))))

(defn- coerce-by-type-form
  "Apply a single Malli type-form coercion to a raw URL string. First-pass
  vocabulary: `:int` / `:boolean` plus the rf2-3k3o7 keyword variants:

  - `:rf.route/keyword-unbounded` — declared as `:keyword` with no enum
    constraint. **Stays as string** (no intern; the unbounded keyword-
    interning DoS surface is precisely what rf2-3k3o7 guards against).
  - `[:rf.route/enum-keyword #{names}]` — declared as `[:enum :a :b ...]`.
    Intern is gated by the allowlist; values matching a declared enum
    choice are keyword'd, others stay string. Bounded by construction.

  Any other type-form (including nil) is a pass-through. Per Spec 012
  §Query-string coercion and rf2-3k3o7."
  [type-form v]
  (cond
    (= :int type-form)
    (try
      #?(:clj  (Long/parseLong v)
         :cljs (let [n (js/parseInt v 10)]
                 (if (js/isNaN n) v n)))
      (catch #?(:clj Throwable :cljs :default) _ v))

    (= :boolean type-form)
    (case v "true" true "false" false v)

    (= :rf.route/keyword-unbounded type-form)
    ;; rf2-3k3o7: `:keyword` without an enum allowlist stays as string —
    ;; permitting `(keyword v)` here is the unbounded keyword-interning
    ;; DoS surface this fix closes. Authors who want keyword values
    ;; must declare an `[:enum ...]` allowlist.
    v

    (and (vector? type-form) (= :rf.route/enum-keyword (first type-form)))
    ;; rf2-3k3o7: enum allowlist gate — intern only when the URL value
    ;; matches one of the declared keyword choices' names.
    (if (contains? (second type-form) v)
      (keyword v)
      v)

    :else v))

(defn- coerce-query
  "Coerce a raw `{string-key string-value}` map against a precompiled
  `query-coerce` table (`{:keyword-key type-form}`). Returns an
  array-map to preserve URL key order.

  Per rf2-3k3o7 + rf2-5ifai: only query keys named by the route's
  `:query` schema (encoded as `query-coerce`), `:query-defaults`, or
  `:query-retain` are promoted to keyword keys; unknown keys retain
  their **string** form. The route's declared vocabulary defines the
  keyword universe; the framework refuses to extend the process-global
  keyword table on behalf of URL keys the route did not name. The cap
  on `default-max-decoded-keys` is a second-line defence that bounds
  the raw-query map size before this fn even sees it.

  Pre-rf2-5ifai a route declaring NO vocabulary at all received the
  legacy `keyword-all` shortcut — every URL key was promoted to a
  keyword. That was the symmetrical version of the rf2-3k3o7 enum gap
  on the value side and was closed for the same reason: hostile URLs
  composed of N-unique keys burn N permanent JVM keyword slots, and a
  bare `(reg-route :route/x {:path \"/x\"})` is precisely the high-
  cardinality public-surface case where this hits hardest. Authors who
  want keyword keys declare them via `:query` / `:query-defaults` /
  `:query-retain` — author-named intent is the trust boundary.

  `:query-defaults` and `:query-retain` slots widen the declared
  universe (they are author-named intent, identical trust class to
  the `:query` schema itself)."
  [query-coerce defaults retain raw-query]
  (let [declared-names (cond-> #{}
                         query-coerce   (into (map name) (keys query-coerce))
                         (seq defaults) (into (map name) (keys defaults))
                         (seq retain)   (into (map name) retain))]
    (reduce-kv
      (fn [m k v]
        (if (contains? declared-names k)
          ;; Declared key: promote to keyword + apply type coercion.
          (let [kk (keyword k)]
            (assoc m kk (coerce-by-type-form (get query-coerce kk) v)))
          ;; Undeclared key: pass through with the **string** key, no
          ;; type coercion. The framework does not burn a keyword slot
          ;; per unique URL key the route did not declare (rf2-5ifai).
          (assoc m k v)))
      (array-map)
      raw-query)))

(defn- split-fragment
  "Split a URL into [url-without-fragment fragment]. Returns
  [path-and-query nil] when no '#' is present, else
  [before-hash decoded-fragment]. The fragment is returned as nil when
  absent, as `\"\"` when bare (URL ends with bare '#'), as the
  %-decoded substring when well-formed, and as `::malformed-fragment`
  when its %-encoding is malformed (rf2-4ic0f — malformed fragment
  fails closed at `match-url`).

  Pre-rf2-4ic0f the fragment was returned as the raw (un-decoded)
  substring; that left malformed fragments to be silently surfaced into
  the slice. Per Spec 012 §Routing failure semantics §Malformed
  percent-encoding the entire URL is treated as a route-miss when the
  fragment cannot be decoded."
  [^String url]
  (let [hash-idx (.indexOf url "#")]
    (cond
      (neg? hash-idx)
      [url nil]

      ;; Bare '#' — empty fragment, no decoding needed.
      (= (inc hash-idx) (count url))
      [(subs url 0 hash-idx) ""]

      :else
      (let [raw     (subs url (inc hash-idx))
            decoded (url/safe-url-decode raw)]
        [(subs url 0 hash-idx) (or decoded ::malformed-fragment)]))))

(defn- validate-route-shape
  "Run the registered schema validator against `value` for the route's
  `:params` or `:query` schema (`slot` ∈ #{:params :query}). Returns
  `[validation-failed? validation-error]`:
    - `[false nil]` when no schema is declared, no validator is
      registered, or the value conforms;
    - `[true explain-data]` when validation fails.

  Per Spec 010 the validator is pluggable via
  `:schemas/validate-with-registered-fn` and
  `:schemas/explain-with-registered-fn`; the routing artefact never
  requires re-frame.schemas statically (rf2-k682) — late-bind keeps
  the apps that opt out of schemas/Malli runnable."
  [route-meta slot value]
  (let [schema (get route-meta slot)]
    (if-not schema
      [false nil]
      (let [validate (late-bind/get-fn :schemas/validate-with-registered-fn)]
        (if-not validate
          [false nil]
          (if (validate schema value)
            [false nil]
            (let [explain  (late-bind/get-fn :schemas/explain-with-registered-fn)
                  details  (when explain (explain schema value))]
              [true details])))))))

(defn- normalize-match-path
  "Spec 012 trailing-slash normalisation for incoming URLs. `/cart` and
  `/cart/` are equivalent; root remains `/`, and the historical
  no-leading-slash leniency remains (`cart/` → `cart`)."
  [path]
  (loop [p (or path "")]
    (if (and (< 1 (count p))
             (clojure.string/ends-with? p "/"))
      (recur (subs p 0 (dec (count p))))
      p)))

(defn match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Try each registered route's
  pattern against url; return
  {:route-id :params :query :fragment :validation-failed? :validation-error}
  for the first match, or nil if no route matches.

  Query string coercion: if the route declares a :query Malli schema,
  string values are coerced per key type. :query-defaults populate
  absent keys. The URL's '#fragment' portion (per Spec 012 §Fragments)
  is parsed off the front and surfaced as :fragment (string or nil);
  fragments do not participate in route matching.

  Per Spec 012 §Bidirectional URL ↔ params §match-url, when a route
  declares :params or :query schemas, the parsed values are validated
  against them; failure surfaces as :validation-failed? true and a
  :validation-error explanation (rf2-ug2m1).

  Performance (rf2-9ihwx): walks the pre-sorted route-table cache
  (rebuilt on reg-route / registrar replacement-hook) and short-circuits
  on the first matching pattern — that is the highest-rank winner by
  construction. Avoids the per-call `keep + sort-by + first` allocation
  pattern."
  [url]
  ;; Split off the fragment first (per Spec 012 §Fragments — fragments
  ;; do not participate in route matching); then strip query string for
  ;; pattern matching and parse query separately. Uses array-map to
  ;; preserve the URL's left-to-right key order so round-trip URLs come
  ;; back byte-identical.
  ;;
  ;; Performance (rf2-r1in4): query parsing is deferred behind a `delay`
  ;; — the URL's query string is only walked once a path-pattern match
  ;; succeeds. Pre-rf2-r1in4 the parse fired for every URL even when no
  ;; route matched, and the cost (split + url-decode per pair) hit every
  ;; URL-driven navigation. The closure captures `query-str`; the delay
  ;; forces at most once and is held for the lifetime of this call.
  (let [[url-no-frag fragment] (split-fragment url)]
    ;; rf2-4ic0f fast-path: malformed fragment fails closed at the URL
    ;; level, before we touch the route table.
    (when-not (= ::malformed-fragment fragment)
      (let [[path0 query-str] (clojure.string/split url-no-frag #"\?" 2)
            path              (normalize-match-path path0)
            ;; rf2-3k3o7: parse query as a **string-keyed** raw map and
            ;; enforce a per-URL cap on the number of unique keys. The cap
            ;; defends against the same accident-class as rf2-wu1n5 (unbounded
            ;; JVM keyword-table growth on long-running SSR processes
            ;; consuming attacker-influenced URL streams). Overflow throws
            ;; `:rf.error/route-too-many-keys` with `:limit` ex-data so the
            ;; caller can route the failure (currently propagates through
            ;; navigate / url-change-fx — error projection surfaces it).
            ;;
            ;; Note: the cap counts unique decoded query keys, not raw pair
            ;; count. Repeated keys keep last-wins semantics and do not trip
            ;; the DoS guard unless the unique-key set itself exceeds the
            ;; configured ceiling.
            raw-query-delayed
            (delay
              (when query-str
                (let [pairs (clojure.string/split query-str #"&")]
                  (reduce
                    (fn [m pair]
                      (let [[k v] (clojure.string/split pair #"=" 2)
                            ;; Per Spec 012 §Routing failure semantics
                            ;; (rf2-wbvme + rf2-4ic0f): malformed %-encoding
                            ;; in a query key or value FAILS CLOSED — the
                            ;; whole URL is treated as a route-miss. Pre-
                            ;; rf2-4ic0f the offending pair was silently
                            ;; dropped, which let hostile URLs into the
                            ;; routing slice when the host route had no
                            ;; required keys. The empty-value branch (`v`
                            ;; is nil → "") is distinct from a malformed
                            ;; value and must not be conflated.
                            kstr  (url/safe-url-decode k)
                            vstr  (if v (url/safe-url-decode v) "")]
                        (if (or (nil? kstr) (nil? vstr))
                          (reduced ::malformed-query)
                          (let [m' (assoc m kstr vstr)]
                            (when (> (count m') default-max-decoded-keys)
                              (throw (ex-info ":rf.error/route-too-many-keys"
                                              {:kind   :rf.error/route-too-many-keys
                                               :url    url
                                               :limit  default-max-decoded-keys
                                               :count  (count m')})))
                            m'))))
                    (array-map)
                    pairs))))]
        ;; Iterate the pre-sorted table; the first pattern that matches is
        ;; the highest-rank winner (Spec 012 §Route ranking algorithm).
        ;; `reduce` with `reduced` short-circuits on the first hit. nil ⇒
        ;; no route matched OR malformed query fails closed (rf2-4ic0f).
        (reduce
          (fn [_ [id meta]]
            (when-let [compiled (or (:rf.route/compiled meta)
                                    (some-> (:path meta) match/parse-pattern))]
              (when-let [params (match/match-against compiled path)]
                (let [query-coerce  (:rf.route/query-coerce meta)
                      defaults      (:query-defaults meta)
                      retain        (:query-retain meta)
                      ;; Force the query parse on the first successful path
                      ;; match — unmatched URLs and pre-match iterations skip
                      ;; the work entirely (rf2-r1in4).
                      raw-query     @raw-query-delayed]
                  (if (= ::malformed-query raw-query)
                    ;; rf2-4ic0f: short-circuit the entire match; the URL
                    ;; carries malformed %-encoding in its query string and
                    ;; the framework refuses to surface a partial slice.
                    (reduced nil)
                    (let [;; Coercion: O(M) lookups against the precompiled
                          ;; `query-coerce` map (rf2-yjjrv). Per rf2-3k3o7
                          ;; only keys declared by the route (in `query-coerce`
                          ;; or `:query-defaults`) are promoted to keyword keys;
                          ;; unknown keys retain their string form so the
                          ;; framework does not extend the JVM keyword-table on
                          ;; behalf of attacker-controlled URLs.
                          coerced       (when raw-query
                                          (coerce-query query-coerce defaults retain raw-query))
                          ;; Defaults: short-circuit when the route declares no
                          ;; defaults (the common case). When both raw-query and
                          ;; defaults are empty, fall back to an empty array-map
                          ;; so the slice's `:query` shape stays consistent and
                          ;; `validate-route-shape` below runs against a map.
                          with-defaults (cond
                                          (and (nil? coerced) (empty? defaults)) (array-map)
                                          (empty? defaults)                      coerced
                                          :else
                                          (reduce-kv
                                            (fn [m k v]
                                              (if (contains? m k) m (assoc m k v)))
                                            (or coerced (array-map))
                                            defaults))
                          ;; Per Spec 012 §Param validation at the call site: when
                          ;; the route declares :params or :query schemas, validate
                          ;; the parsed values. Either schema failing flips the
                          ;; flag; the explanation surfaces under :validation-error
                          ;; so callers ((`:rf.route/handle-url-change`)) can route
                          ;; to `:rf.route/not-found` with `:reason :validation`.
                          [params-failed? params-error] (validate-route-shape meta :params params)
                          [query-failed?  query-error]  (validate-route-shape meta :query  with-defaults)
                          validation-failed? (or params-failed? query-failed?)
                          validation-error   (cond
                                               (and params-failed? query-failed?)
                                               {:params params-error :query query-error}
                                               params-failed? params-error
                                               query-failed?  query-error
                                               :else          nil)
                          result        (cond-> {:route-id           id
                                                 :params             params
                                                 :query              with-defaults
                                                 :fragment           fragment
                                                 :validation-failed? validation-failed?}
                                          validation-error
                                          (assoc :validation-error validation-error))]
                      (reduced result)))))))
          nil
          (route-table))))))

(defn route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Build a URL string from a
  route-id + path-params (+ optional query-params + optional fragment).
  Inverse of match-url.

  Optional groups ({...}?) are emitted only when ALL their inner params
  are supplied in path-params; otherwise the group is silently elided.

  4-arity: when `fragment` is non-nil and non-empty, appends `#fragment`
  to the URL (per Spec 012 §Fragments §Programmatic navigation with
  fragments). nil or empty-string fragments are not appended.

  Per Spec 012 §Bidirectional URL ↔ params: throws
  `:rf.error/route-url-validation` when path-params doesn't conform to
  the route's `:params` schema, or query-params doesn't conform to the
  route's `:query` schema (caller bug — not user input). The exception
  carries `{:route-id :slot :error}` ex-data (rf2-ug2m1).

  Performance (rf2-r1in4): this fn sits on the render path through
  `route-link-render` / `route-link-render-ssr` — large link lists
  re-render at navigation rate, and each link calls `route-url`. The
  pattern body and `:groups` lookup are read from `:rf.route/compiled`
  (precomputed at registration time by `parse-pattern`), so the inner
  loop runs over a fixed-cost lookup table rather than re-walking the
  pattern source. If a future profile shows `route-url` dominating the
  render budget, the next step is to precompute URL-emission metadata
  at `reg-route` time (analogous to `:rf.route/query-coerce`)."
  ([route-id path-params] (route-url route-id path-params {} nil))
  ([route-id path-params query-params] (route-url route-id path-params query-params nil))
  ([route-id path-params query-params fragment]
   (let [query-params (or query-params {})
         meta    (registrar/lookup :route route-id)
         pattern (:path meta)]
     (when (nil? pattern)
       (throw (ex-info ":rf.error/no-such-route" {:route-id route-id})))
     ;; Per Spec 012 §Bidirectional URL ↔ params: validate the caller's
     ;; inputs against the route's :params / :query schemas BEFORE
     ;; emitting the URL. A schema mismatch is a caller bug; raise with
     ;; the structured id so callers (`:rf.route/navigate`) and tests
     ;; can react. When no schema is declared OR no validator is
     ;; registered, this is a no-op.
     (let [[p-failed? p-error] (validate-route-shape meta :params path-params)]
       (when p-failed?
         (throw (ex-info ":rf.error/route-url-validation"
                         {:route-id route-id
                          :slot     :params
                          :value    path-params
                          :error    p-error}))))
     (let [[q-failed? q-error] (validate-route-shape meta :query query-params)]
       (when q-failed?
         (throw (ex-info ":rf.error/route-url-validation"
                         {:route-id route-id
                          :slot     :query
                          :value    query-params
                          :error    q-error}))))
     (let [n      (count pattern)
           ;; Per Spec 012 §Bidirectional URL ↔ params: optional groups
           ;; are emitted only when every inner param is supplied. The
           ;; `:groups` map produced by `parse-pattern` (rf2-uovh5) maps
           ;; each opening '{' index to `{:inner-names [...] :close-end
           ;; <pos-after-}?>}` — `route-url` consults it instead of
           ;; re-walking the pattern body.
           groups (or (:groups (:rf.route/compiled meta))
                      (:groups (match/parse-pattern pattern)))
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
                         end   (match/segment-end pattern n start)
                         k     (keyword (subs pattern start end))]
                     (recur end (conj parts (url/url-encode (get path-params k)))))

                   (= c2 \*)
                   (let [start (inc i)
                         end   (match/segment-end pattern n start)
                         k     (keyword (subs pattern start end))]
                     (recur end (conj parts (url/url-encode-splat (get path-params k)))))

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
                   (let [{:keys [inner-names close-end]} (get groups i)
                         all-present? (every? #(some? (get path-params (keyword %))) inner-names)]
                     (if all-present?
                       (let [[i' parts'] (emit-group (inc i) parts)]
                         (recur i' parts'))
                       (recur close-end parts)))

                   (= ch \:)
                   (let [start (inc i)
                         end   (match/segment-end pattern n start)
                         k     (keyword (subs pattern start end))
                         ;; Per Spec 012 §Bidirectional URL ↔ params: an
                         ;; absent or `nil` value raises; a present-but-falsy
                         ;; value (`false`, `0`, `""`) is a legitimate
                         ;; segment and round-trips through url-encode.
                         ;; `(or v throw)` mis-classifies falsy as absent;
                         ;; `if-some` discriminates correctly.
                         v     (if-some [v (get path-params k)]
                                 v
                                 (throw (ex-info ":rf.error/missing-route-param"
                                                 {:param k :route-id route-id})))]
                     (recur end (conj parts (url/url-encode v))))

                   (= ch \*)
                   (let [start (inc i)
                         end   (match/segment-end pattern n start)
                         k     (keyword (subs pattern start end))
                         v     (if-some [v (get path-params k)]
                                 v
                                 (throw (ex-info ":rf.error/missing-route-param"
                                                 {:param k :route-id route-id})))]
                     (recur end (conj parts (url/url-encode-splat v))))

                   :else
                   (recur (inc i) (conj parts (str ch)))))))
           path-out (apply str parts)
           qs (when (seq query-params)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]]
                              (str (url/url-encode (name k)) "="
                                   (url/url-encode v)))
                            query-params))))
           ;; Per Spec 012 §Fragments §Programmatic navigation with
           ;; fragments: the 4-arity emits `#fragment` when non-nil and
           ;; non-empty. Empty-string fragments collapse to no fragment.
           frag (when (and fragment (not= "" fragment))
                  (str "#" fragment))]
       (str path-out qs frag)))))

;; ---- scroll-restoration helpers -------------------------------------------
;; Per Spec 012 §Scroll restoration §Multi-frame routing. Per-frame
;; saved-position map at [:rf.route/scroll-positions], LRU-capped by
;; scroll-positions-cap. Recency anchor lives under
;; [:rf.route/scroll-positions-order] as an internal vector.

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

(defn- current-route-url
  "Best-effort URL reconstruction for the active route slice. Used only
  to key scroll-position capture; route deletion or invalid historical
  slices skip capture rather than failing navigation."
  [route-slice]
  (when-let [id (:id route-slice)]
    (try
      (route-url id
                 (or (:params route-slice) {})
                 (or (:query route-slice) {})
                 (:fragment route-slice))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- capture-scroll-fx-entry [db]
  (when-let [url (current-route-url (:rf/route db))]
    [:rf.nav/capture-scroll {:url url}]))

;; Per Spec 012 §Multi-frame routing: nav-token and pending-nav id
;; counters are per-frame. Pure allocators: take db, return [db' id-str].

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

;; Per Spec 012 §Per-route data loading §2. FIFO drain queues
;; :rf.route/settle-transition after the :on-match events so :transition
;; lands at :idle once the synchronous portion completes. The settle is
;; nav-token-aware: a newer navigation mid-drain bumps :nav-token, and
;; the stale settle becomes a no-op so the new :loading isn't clobbered.
;;
;; Per Spec 012 §Per-route error handling: if any :on-match event errors
;; the runtime flips :transition :error, populates :rf.route/error, and
;; dispatches :on-error (when declared). The settle handler additionally
;; guards on `(= :loading current-transition)` so a settle queued AFTER
;; an :on-match throw does NOT clobber :error back to :idle — the throw's
;; trap (`:rf.route/on-match-error` below) ran first and the slice now
;; carries :error.
(events/reg-event-db :rf.route/settle-transition
  (fn [db [_ token]]
    (let [current (get-in db [:rf/route :nav-token])]
      (if (and (= current token)
               (= :loading (get-in db [:rf/route :transition])))
        (assoc-in db [:rf/route :transition] :idle)
        db))))

;; ---- :on-match error trap -------------------------------------------------
;; Per Spec 012 §Per-route error handling: if any :on-match event errors
;; (a handler throws, a registered fx errors, or a downstream handler
;; errors during the drain — per Spec 009's structured error contract),
;; the runtime:
;;   1. Sets :rf.route/transition to :error.
;;   2. Populates :rf.route/error with the structured error map
;;      (schema :rf/error per Spec 009 §error-contract).
;;   3. If the route declares :on-error, dispatches it. The handler reads
;;      (:error (:rf/route db)) for the error context.
;;
;; Mechanism: a corpus-wide listener on the always-on error-emit
;; substrate (per rf2-bacs4 / Spec 009 §What IS available in production)
;; receives every :rf.error/handler-exception record. The listener
;; discriminates "is this exception from an :on-match dispatch?" by:
;;   - reading the failing record's :frame
;;   - reading that frame's :rf/route slice
;;   - checking :transition is :loading (the slice is mid-drain)
;;   - checking the failing event-id is in the active route's :on-match
;;
;; All four together identify the error as originating from an :on-match
;; cascade for the currently-loading route. The listener then dispatches
;; :rf.route/on-match-error with the structured error map; that event
;; flips :transition, populates :rf.route/error, and chains :on-error.
;;
;; The listener is always-on (survives `:advanced` + `goog.DEBUG=false`)
;; so production builds with the trace surface elided still observe
;; :on-match errors and route them to :on-error policies.

(defn- on-match-event-ids
  "Return a set of event-ids declared in `route-meta`'s `:on-match`.
  Empty when the route declares no `:on-match`. Used by the error-emit
  listener to discriminate which handler-exception records originated
  from an `:on-match` dispatch."
  [route-meta]
  (into #{}
        (comp (filter vector?)
              (map first))
        (or (:on-match route-meta) [])))

(events/reg-event-fx :rf.route/on-match-error
  (fn [{:keys [db]} [_ {:keys [error nav-token]}]]
    ;; Per Spec 012 §Per-route error handling. Nav-token-guarded: if a
    ;; newer navigation has already bumped :nav-token, this error
    ;; belongs to a superseded drain and is dropped (matches
    ;; :rf.route/settle-transition's epoch check).
    (let [current-token (get-in db [:rf/route :nav-token])
          current-id    (get-in db [:rf/route :id])
          route-meta    (when current-id (registrar/lookup :route current-id))
          on-error-ev   (:on-error route-meta)]
      (if (not= nav-token current-token)
        ;; Stale — the trap fired for an :on-match throw from a previous
        ;; navigation that has since been superseded. Drop silently
        ;; (the corpus-wide error-emit substrate already surfaced the
        ;; underlying :rf.error/handler-exception for observability).
        {}
        (cond->
          {:db (-> db
                   (assoc-in [:rf/route :transition] :error)
                   (assoc-in [:rf/route :error]      error))}
          ;; Spec 012 §Per-route error handling: a declared :on-error
          ;; receives no payload — the handler reads (:error (:rf/route
          ;; db)) for the error context. Vector form `[:ev-id ...]`
          ;; dispatches as-is; bare keyword wraps as `[:ev-id]`.
          on-error-ev
          (assoc :fx [[:dispatch (if (vector? on-error-ev)
                                   on-error-ev
                                   [on-error-ev])]]))))))

(defn- on-match-error-listener
  "Corpus-wide `register-error-emit-listener!` fn. Inspects every
  `:rf.error/handler-exception` record; when the failing event-id was
  dispatched as part of the active route's `:on-match` (per the
  discrimination logic in this ns's `:on-match error trap` block),
  dispatches `:rf.route/on-match-error` to the offending frame so the
  slice flips to `:error` and `:on-error` chains.

  Per Spec 012 §Per-route error handling and rf2-ye7sh."
  [{:keys [error event-id frame exception] :as _record}]
  (when (= :rf.error/handler-exception error)
    (let [db            (frame/frame-app-db-value frame)
          route-slice   (when db (:rf/route db))
          route-id      (:id route-slice)
          transition    (:transition route-slice)
          nav-token     (:nav-token route-slice)
          route-meta    (when route-id (registrar/lookup :route route-id))
          on-match-ids  (on-match-event-ids route-meta)]
      ;; Three discriminators all must hold:
      ;;   1. The slice is mid-drain (`:loading`).
      ;;   2. The failing event-id is in the active route's `:on-match`.
      ;;   3. A nav-token is present (otherwise routing is uninitialised).
      ;; All three together mean: the failing handler was an :on-match
      ;; dispatch for the currently-loading route.
      (when (and (= :loading transition)
                 nav-token
                 (contains? on-match-ids event-id))
        ;; Build the structured error map (Spec 009 §error-contract).
        ;; The exception itself carries the diagnostic detail; we surface
        ;; the canonical :rf.error/ id + tags so apps can switch on it
        ;; the same way they do for any other Spec 009 error.
        (let [error-map {:operation         :rf.error/handler-exception
                         :failing-id        event-id
                         :event-id          event-id
                         :frame             frame
                         :exception         exception
                         :exception-message #?(:clj (when exception
                                                      (.getMessage ^Throwable exception))
                                               :cljs (some-> exception .-message))
                         :reason            "An :on-match event threw."}]
          (router/dispatch! [:rf.route/on-match-error
                             {:error     error-map
                              :nav-token nav-token}]
                            {:frame frame}))))))

;; Register the listener at ns-load. The listener id is namespaced under
;; :rf.route/* so accidental re-registration by another artefact is
;; rejected by the corpus-wide substrate's id check. The substrate's
;; `defonce` over the listener atom guards against re-load wiping a
;; production-registered listener.
(error-emit/register-error-emit-listener!
  :rf.route/on-match-error-trap
  on-match-error-listener)

(declare maybe-block-navigation)

(events/reg-event-fx :rf.route/navigate
  (fn [{:keys [db frame]} [_ target params opts :as event-vec]]
    ;; Per Spec 012 §Navigation is an event and §Fragments §Programmatic
    ;; navigation with fragments. Fragment may be supplied in opts
    ;; (`{:fragment "x"}`), on the target-map form (`{:url "/x"
    ;; :fragment "y"}`), or — for URL-string targets — embedded in the
    ;; URL itself; match-url surfaces the latter. Opts/target-map win
    ;; over a URL-embedded fragment.
    ;;
    ;; Per Spec 012 §Navigation tokens — stale-result suppression and
    ;; §The :rf/route slice the slice ALWAYS carries :fragment and a
    ;; freshly-allocated :nav-token, and the runtime emits
    ;; :rf.route.nav-token/allocated as the cascade begins. Pre-fix
    ;; this handler omitted :fragment + :nav-token from the slice write
    ;; and never emitted the allocation trace, so the programmatic
    ;; navigation path diverged from the URL-driven path (rf2-d60go's
    ;; mirror finding) and async loaders had no token to thread through
    ;; stale-suppression.
    (let [opts (or opts {})
          {:keys [route-id path-params query-params matched-fragment]}
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
          ;; Per Spec 012 §Query strings and fragments: `:query-retain`
          ;; on the TARGET route names the keys that should be carried
          ;; through from the current `:rf.route/query` slice when the
          ;; caller did not supply them. The merge runs here (rather
          ;; than inside `route-url`, which is documented pure and
          ;; cannot read app-db) so apps that navigate by
          ;; `[:rf.route/navigate :route/cart]` from a search page
          ;; automatically preserve `?theme=dark` / `?locale=en`
          ;; without explicitly threading those keys through every call
          ;; site (rf2-u8t3s). Caller-supplied values always win.
          retain-keys  (:query-retain route-meta)
          retained     (when (seq retain-keys)
                         (select-keys (get-in db [:rf/route :query])
                                      retain-keys))
          query-params (if (seq retained)
                         (merge retained query-params)
                         query-params)
          ;; Per Spec 012 §Bidirectional URL ↔ params and rf2-ug2m1:
          ;; route-url raises :rf.error/route-url-validation /
          ;; :rf.error/missing-route-param / :rf.error/no-such-route on
          ;; caller bugs. Surface these as a structured trace + recover
          ;; to "/" rather than swallowing silently.
          url (try (route-url route-id path-params query-params fragment)
                   (catch #?(:clj Throwable :cljs :default) ex
                     (trace/emit-error! :rf.error/route-url-validation
                                        {:route-id route-id
                                         :error    (or (ex-data ex)
                                                       {:message (ex-message ex)})
                                         :recovery :replaced-with-default})
                     "/"))
          push-fx (if (:replace? opts)
                    [:rf.nav/replace-url url]
                    [:rf.nav/push-url    url])
          on-match-vec (vec (or (:on-match route-meta) []))
          ;; Per Spec 012 §Multi-frame routing nav-token allocation bumps
          ;; the per-frame counter; thread the new db through the slice
          ;; write below.
          [db' token] (alloc-nav-token db)
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
                         :fragment  fragment})
          capture-fx  (capture-scroll-fx-entry db)]
      (if-let [blocked (maybe-block-navigation db (or frame :rf/default)
                                               event-vec url
                                               (:bypass-leave-guard? opts))]
        blocked
        (do
          (trace/emit! :event :rf.route.nav-token/allocated
                       {:route-id  route-id
                        :nav-token token})
          {:db (assoc db' :rf/route
                      {:id         route-id
                       :params     path-params
                       :query      query-params
                       :fragment   fragment
                       :transition (if (seq on-match-vec) :loading :idle)
                       :error      nil
                       :nav-token  token})
           :fx (vec (concat (when capture-fx [capture-fx])
                            [push-fx]
                            (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                            ;; Per Spec 012 §Per-route data loading §2:
                            ;; transition :loading → :idle when the
                            ;; on-match drain completes. FIFO order means
                            ;; the settle dispatch runs after every
                            ;; on-match event already queued above.
                            (when (seq on-match-vec)
                              [[:dispatch [:rf.route/settle-transition token]]])
                            (when scroll-fx [scroll-fx])))})))))

(defn reset-counters!
  "Reset the route-registration counter to zero. Test-time helper so
  reg-index is deterministic across fixture runs."
  []
  (reset! reg-counter 0))

;; ---- :rf/url-requested + can-leave gating + pending-nav protocol ----------
;; Per Spec 012 §Navigation blocking — pending-nav protocol.

(defn- can-leave-query [route-meta]
  (let [declared (:can-leave route-meta)]
    (cond
      (vector? declared) declared
      (keyword? declared) [declared]
      :else nil)))

(defn- can-leave-guard-id [route-meta]
  (let [declared (:can-leave route-meta)]
    (cond
      (vector? declared) (first declared)
      (keyword? declared) declared
      :else nil)))

(defn- can-leave?
  "Resolve and call the route's `:can-leave` sub against the live frame.
  Per Spec 012 §Navigation blocking §Default flow: only an explicit
  `false` from the guard sub blocks the navigation; `true`, `nil`, or
  any other value proceeds. The sub's name is documented to describe
  the positive case (`:can-leave`), so missing / broken evaluation
  cannot safely default to 'blocked' — that would silently strand the
  user on a route they had asked to leave.

  Returns `false` only when a `:can-leave` sub IS declared AND the sub
  returns a value `(= % false)`. Returns `true` (proceed) in three
  diagnostic-but-recoverable cases — each emits a warning so tooling
  and the dev console can surface the misconfiguration:

    - `:subs/subscribe-once` late-bind is unset (consumer opted out of
      the subs artefact — the runtime has no way to evaluate the sub);
    - the sub returns `nil` (registration likely typo'd the sub-id, or
      the sub fn forgot to return a value);
    - the sub returns a non-boolean truthy value (route author got the
      polarity wrong; we err on letting the nav through and warn)."
  [frame route-meta]
  (if-let [query (can-leave-query route-meta)]
    (if-let [subscribe-once (late-bind/get-fn :subs/subscribe-once)]
      (let [v (subscribe-once frame query)]
        (cond
          (false? v) false
          (true?  v) true
          :else
          (do (trace/emit! :warning :rf.warning/can-leave-guard-non-boolean
                           {:route-id (some-> route-meta :path)
                            :query    query
                            :value    v})
              true)))
      (do (trace/emit! :warning :rf.warning/can-leave-subs-artefact-missing
                       {:query query})
          true))
    true))

(defn- maybe-block-navigation
  [db frame-id event-vec requested-url bypass-leave-guard?]
  (let [current-route (:rf/route db)
        current-meta  (registrar/lookup :route (:id current-route))
        ok?           (or bypass-leave-guard?
                          (can-leave? frame-id current-meta))]
    (when-not ok?
      (let [[db' pn-id] (alloc-pending-nav-id db)
            guard-id    (can-leave-guard-id current-meta)]
        (trace/emit! :event :rf.route/navigation-blocked
                     {:requested-url   requested-url
                      :rejecting-route (:id current-route)
                      :rejecting-guard guard-id})
        {:db (assoc db' :rf/pending-navigation
                    (cond-> {:id                 pn-id
                             :requested-by-event (vec event-vec)
                             :requested-url      requested-url
                             :reason             :can-leave
                             :rejecting-route    (:id current-route)}
                      guard-id (assoc :rejecting-guard guard-id)))}))))

(defn- absolute-url-like? [url]
  (boolean
    (and (string? url)
         (or (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" url)
             (clojure.string/starts-with? url "//")))))

(defn- external-url? [url]
  #?(:cljs
     (try
       (if (and (exists? js/window) (.-location js/window))
         (let [loc      (.-location js/window)
               parsed   (js/URL. url (.-href loc))
               protocol (.-protocol parsed)]
           (or (not (#{"http:" "https:"} protocol))
               (not= (.-origin parsed) (.-origin loc))))
         (absolute-url-like? url))
       (catch :default _
         (absolute-url-like? url)))
     :clj
     (absolute-url-like? url)))

(defn- request-url->app-url [url]
  #?(:cljs
     (try
       (if (and (exists? js/window) (.-location js/window)
                (not (external-url? url)))
         (let [parsed (js/URL. url (.-href (.-location js/window)))]
           (str (.-pathname parsed) (.-search parsed) (.-hash parsed)))
         url)
       (catch :default _ url))
     :clj
     url))

(defn- inject-bypass-leave-guard [event-vec fallback-url]
  (let [event-id (first event-vec)]
    (case event-id
      :rf/url-requested
      (let [request (if (map? (second event-vec)) (second event-vec) {})]
        [:rf/url-requested (assoc request :bypass-leave-guard? true)])

      :rf.route/navigate
      (let [[_ target params opts] event-vec]
        [:rf.route/navigate target params (assoc (or opts {}) :bypass-leave-guard? true)])

      :rf/url-changed
      (let [[_ url opts] event-vec]
        [:rf/url-changed url (assoc (or opts {}) :bypass-leave-guard? true)])

      :rf.route/handle-url-change
      (let [[_ url opts] event-vec]
        [:rf.route/handle-url-change url (assoc (or opts {}) :bypass-leave-guard? true)])

      [:rf/url-requested {:url fallback-url :bypass-leave-guard? true}])))

(events/reg-event-fx :rf/url-requested
  (fn [{:keys [db frame]}
       [_ {:keys [url bypass-leave-guard?] :as _request} :as event-vec]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol the
    ;; runtime fires :can-leave for the active route on every
    ;; :rf/url-requested; rejection writes :rf/pending-navigation with
    ;; the full slot shape `{:id :requested-by-event :requested-url
    ;; :reason :rejecting-route :rejecting-guard}` per Spec-Schemas.md
    ;; §:rf/pending-navigation (rf2-b8ugt).
    ;;
    ;; The :bypass-leave-guard? request flag is the rf2-yursn one-shot
    ;; escape hatch :rf.route/continue uses to re-issue the original
    ;; navigation request without re-running the leave guard.
    (let [external? (external-url? url)
          app-url   (request-url->app-url url)
          blocked   (when-not external?
                      (maybe-block-navigation db (or frame :rf/default)
                                              event-vec app-url bypass-leave-guard?))]
      (cond
        external?
        (do
          (trace/emit! :event :rf.route/external-url-requested
                       {:url url})
          {})

        blocked
        blocked

        :else
        ;; can leave — push the URL and dispatch :rf/url-changed.
        ;; Per Spec 012 §URL changes are events route-link clicks call
        ;; `.preventDefault` and dispatch :rf/url-requested; the browser's
        ;; URL has NOT updated. The handler is responsible for pushing
        ;; the new URL (history pushState) and then synthesising the
        ;; :rf/url-changed event the slice + on-match write keys off.
        {:fx [[:rf.nav/push-url app-url]
              [:dispatch [:rf/url-changed app-url {:bypass-leave-guard? true}]]]}))))

(events/reg-event-fx :rf.route/continue
  (fn [{:keys [db]} [_ pn-id]]
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol continue
    ;; re-issues the original navigation request, *bypassing* the leave
    ;; guard for this one shot. Pre-rf2-yursn this dispatched
    ;; :rf/url-changed + :rf.nav/push-url directly, skipping
    ;; :rf/url-requested's policy interceptors and racing the slice
    ;; write with the URL push. Right model: re-emit
    ;; :rf/url-requested with :bypass-leave-guard? true so the same
    ;; policy chain runs.
    (let [pending  (:rf/pending-navigation db)
          original (:requested-by-event pending)
          url      (:requested-url pending)]
      (if (and pending (= pn-id (:id pending)))
        (cond-> {:db (dissoc db :rf/pending-navigation)}
          (or (vector? original) url)
          (assoc :fx [[:dispatch (if (vector? original)
                                   (inject-bypass-leave-guard original url)
                                   [:rf/url-requested {:url url
                                                       :bypass-leave-guard? true}])]]))
        {}))))

(events/reg-event-fx :rf.route/cancel
  (fn [{:keys [db]} [_ pn-id]]
    (if (= pn-id (get-in db [:rf/pending-navigation :id]))
      {:db (dissoc db :rf/pending-navigation)}
      {})))

;; ---- nav-token stale suppression ------------------------------------------
;; Per Spec 012 §Navigation tokens — stale-result suppression.
;; `:rf.test/simulate-http-resolution` is the test-only fixture analogue
;; of the production-grade `:rf.route/with-nav-token` fx below.

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

;; ---- URL-driven navigation: shared full-rewrite path ---------------------
;; `:rf/url-changed` (forward nav, default scroll `:top`) and
;; `:rf.route/handle-url-change` (popstate / initial / SSR, default
;; scroll `:restore`) share `url-change-fx`. The fragment-only branch is
;; exclusive to `:rf/url-changed`.

(defn- url-change-fx
  "Pure helper: given db + url + default scroll strategy (+ optional
  `:frame` to carry on the no-such-handler trace), return the cofx map
  `{:db :fx}` for a URL-driven full slice rewrite. Performs the match-url
  lookup, allocates a fresh nav-token, computes the scroll fx entry, and
  emits the trace events (:rf.warning/no-not-found-route,
  :rf.warning/malformed-url, :rf.error/no-such-handler,
  :rf.route.nav-token/allocated).

  Per Spec 012 §URL changes are events §Route-not-found §Per-route data
  loading §Scroll restoration §Multi-frame routing. The slice always
  carries the full seven-key shape (rf2-d60go).

  Three fallback shapes feed `:rf.route/not-found` (rf2-4ic0f):

   - bare miss (`{:url url}`) — `match-url` returned nil and the URL
     percent-encoding decoded cleanly;
   - validation fail (`{:url url :reason :validation}`) — a route's
     pattern matched but its `:params` / `:query` schema rejected the
     parsed values (rf2-ug2m1);
   - malformed URL (`{:url url :reason :malformed-url}`) — any of the
     URL's path captures, query keys/values, or `#fragment` failed to
     %-decode. The `:reason` discriminator lets per-route error UIs
     and SSR projections branch on the cause."
  [db url default-scroll frame]
  (let [m                 (match-url url)
        ;; rf2-4ic0f: when match-url returns nil, discriminate the
        ;; bare-miss case from the malformed-URL case via the public
        ;; `malformed-url?` predicate. The predicate scans the URL
        ;; once; we run it only when match-url already missed (the
        ;; happy path pays nothing).
        malformed?        (and (nil? m) (malformed-url? url))
        ;; Malformed URLs surface no fragment in the slice — the
        ;; fragment was the (or potentially the) decode-fail site.
        fragment          (when-not malformed? (:fragment m))
        matched?          (some? m)
        validation-fail?  (:validation-failed? m)
        fallback?         (or (not matched?) validation-fail?)
        route-id          (if fallback? :rf.route/not-found (:route-id m))
        params            (cond
                            malformed?       {:url url :reason :malformed-url}
                            validation-fail? {:url url :reason :validation}
                            (not matched?)   {:url url}
                            :else            (:params m))
        query             (if fallback? {} (:query m))
        route-meta        (registrar/lookup :route route-id)
        on-match-vec      (vec (or (:on-match route-meta) []))
        transition        (if (seq on-match-vec) :loading :idle)
        [db' token]       (alloc-nav-token db)
        to-route          (cond-> {:id route-id}
                            (seq params) (assoc :params params)
                            (seq query)  (assoc :query  query))
        strategy          (resolve-scroll-strategy route-meta nil default-scroll)
        capture-fx        (capture-scroll-fx-entry db)
        scroll-fx         (scroll-fx-entry
                            {:strategy  strategy
                             :from      (route-descriptor (:rf/route db))
                             :to        to-route
                             :saved-pos (when (= :restore strategy)
                                          (lookup-scroll-position db url))
                             :fragment  fragment})]
    ;; rf2-4ic0f: structured telemetry for the malformed-URL case so
    ;; SSR error projections, security dashboards, and pair-tools can
    ;; surface the failure independently of the generic miss trace.
    ;; Emitted alongside the regular `:rf.error/no-such-handler` event
    ;; below — the discriminator is the `:reason :malformed-url` slot
    ;; on the slice's `:params`.
    (when malformed?
      (trace/emit! :warning :rf.warning/malformed-url
                   (cond-> {:url url}
                     frame (assoc :frame frame))))
    ;; Spec 012 §Route-not-found §3: emit :rf.warning/no-not-found-route
    ;; when the unmatched-URL path resolves to :rf.route/not-found AND
    ;; no such route is registered. Tools / AI scaffolds key off this.
    (when (and fallback? (nil? route-meta))
      (trace/emit! :warning :rf.warning/no-not-found-route
                   {:url url}))
    ;; :rf.error/no-such-handler discriminates from event / frame
    ;; handler misses by :kind :route. The :frame tag (present when the
    ;; caller threads it in — `:rf.route/handle-url-change`) lets the
    ;; SSR error-projection listener attribute the trace per-frame.
    ;; rf2-4ic0f: include `:reason :malformed-url` when applicable so
    ;; the structured error is uniform across the trace + the slice.
    (when fallback?
      (trace/emit-error! :rf.error/no-such-handler
                         (cond-> {:url url
                                  :kind :route
                                  :recovery :replaced-with-default}
                           frame      (assoc :frame frame)
                           malformed? (assoc :reason :malformed-url))))
    (trace/emit! :event :rf.route.nav-token/allocated
                 {:route-id  route-id
                  :nav-token token})
    {:db (assoc db' :rf/route
                {:id         route-id
                 :params     params
                 :query      query
                 :fragment   fragment
                 :transition transition
                 :error      nil
                 :nav-token  token})
     :fx (vec (concat (when capture-fx [capture-fx])
                      (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                      ;; Per Spec 012 §Per-route data loading §2:
                      ;; settle :loading → :idle after the on-match
                      ;; drain. FIFO order: settle runs after every
                      ;; on-match event already queued above.
                      (when (seq on-match-vec)
                        [[:dispatch [:rf.route/settle-transition token]]])
                      (when scroll-fx [scroll-fx])))}))

(events/reg-event-fx :rf/url-changed
  (fn [{:keys [db frame]} [_ url opts :as event-vec]]
    ;; Per Spec 012 §URL changes are events / §Fragments. match-url
    ;; surfaces the URL's `#fragment` directly on its result; if only
    ;; the fragment differs from the current slice, update :fragment but
    ;; DO NOT re-fire :on-match — emit :rf.route/url-changed instead.
    ;; Otherwise full nav: delegate to `url-change-fx`.
    ;;
    ;; Default scroll strategy for forward nav (click / programmatic
    ;; push) is `:top` per Spec 012 §Scroll restoration; popstate /
    ;; initial / SSR routes through `:rf.route/handle-url-change` which
    ;; defaults to `:restore`.
    (let [opts           (or opts {})
          blocked        (maybe-block-navigation db (or frame :rf/default)
                                                event-vec url
                                                (:bypass-leave-guard? opts))
          m              (when-not blocked (match-url url))
          fragment       (:fragment m)
          prev           (:rf/route db)
          fragment-only? (and prev m
                              (= (:id prev)     (:route-id m))
                              (= (:params prev) (:params m))
                              (= (:query prev)  (:query m))
                              (not= (:fragment prev) fragment))]
      (cond
        blocked
        blocked

        fragment-only?
        ;; Per Spec 009 §:op-type vocabulary and Spec 012 §Fragments:
        ;; :rf.route/url-changed is the canonical op-name for
        ;; fragment-only navigation; consumers discriminate full vs
        ;; fragment-only by :tags (the fragment-only emission carries
        ;; :prev-fragment / :next-fragment and never coincides with a
        ;; :rf.route.nav-token/allocated on the same drain).
        (do (trace/emit! :event :rf.route/url-changed
                         {:route-id      (:id prev)
                          :prev-fragment (:fragment prev)
                          :next-fragment fragment})
            (let [capture-fx (capture-scroll-fx-entry db)]
              (cond-> {:db (assoc-in db [:rf/route :fragment] fragment)}
                capture-fx (assoc :fx [capture-fx]))))

        :else
        (url-change-fx db url :top nil)))))

(events/reg-event-fx :rf.route/handle-url-change
  (fn [{:keys [db frame]} [_ url opts :as event-vec]]
    ;; Per Spec 012 §URL changes are events — popstate, initial load,
    ;; SSR. Always a full slice rewrite (the fragment-only branch is
    ;; exclusive to `:rf/url-changed`); default scroll strategy is
    ;; `:restore` so the saved position trumps. `:frame` is threaded
    ;; through to `url-change-fx` so the SSR error-projection listener
    ;; can attribute the :no-such-handler trace per-frame.
    (let [opts    (or opts {})
          blocked (maybe-block-navigation db (or frame :rf/default)
                                          event-vec url
                                          (:bypass-leave-guard? opts))]
      (or blocked
          (url-change-fx db url :restore frame)))))

;; ---- standard navigation fx ----------------------------------------------
;;
;; Per Spec 012 §Multi-frame routing and rf2-w50qm: `:rf.nav/push-url` /
;; `:rf.nav/replace-url` MUST consult the calling frame's `:url-bound?`
;; metadata before touching the browser history. The default frame
;; (`:rf/default`) is URL-bound; non-default frames are not, unless they
;; opt in via `(reg-frame :my-frame {:url-bound? true})`. Non-URL-bound
;; frames no-op the fx (history.pushState would race with the
;; URL-owning frame). The check honours the framework default:
;; `:rf/default` is URL-bound when no explicit `:url-bound?` slot is
;; declared.

(defn- url-bound?-from-config
  "Read `:url-bound?` from a frame's stored config map. `nil` when
  unset. Default-on for `:rf/default` is applied at the call site, not
  here, so the hook can discriminate explicit-`true` from default-`true`."
  [config]
  (when (map? config)
    (:url-bound? config)))

(defn- url-owner-frame-id
  "Return the single frame allowed to mutate browser history. The default
  frame owns the URL unless it explicitly opts out; otherwise the first
  explicit non-default `:url-bound? true` frame wins deterministically.
  Duplicate registrations still emit `:rf.error/duplicate-url-binding`,
  but this predicate enforces the one-owner rule at fx time."
  []
  (let [frames       (registrar/registrations :frame)
        default-meta (get frames :rf/default)]
    (if-not (false? (url-bound?-from-config default-meta))
      :rf/default
      (->> frames
           (filter (fn [[id meta]]
                     (and (not= :rf/default id)
                          (true? (url-bound?-from-config meta)))))
           (sort-by (fn [[id _]] (str id)))
           ffirst))))

(defn- url-bound-frame?
  "Return true when the frame named `frame-id` is the one active URL
  owner. Per Spec 012 §Multi-frame routing, duplicate `:url-bound? true`
  declarations are reported AND non-owners are prevented from pushing."
  [frame-id]
  (= (or frame-id :rf/default) (url-owner-frame-id)))

(fx/reg-fx :rf.nav/push-url
  {:platforms #{:client}
   :doc       "Push the URL to the browser history (HTML5 pushState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."}
  (fn [{:keys [frame]} url]
    (if (url-bound-frame? frame)
      #?(:cljs (.pushState js/window.history nil "" url)
         :clj  (trace/emit! :fx :rf.fx/skipped-on-platform
                            {:fx-id :rf.nav/push-url :url url}))
      ;; Non-URL-bound frame: skip the history mutation. Frame's
      ;; `:rf/route` slice still updates — only the browser-URL sync is
      ;; suppressed. Per Spec 012 §Multi-frame routing this is the right
      ;; default for story-variant / devcard / per-test fixtures.
      (trace/emit! :fx :rf.fx/skipped-on-platform
                   {:fx-id :rf.nav/push-url
                    :url   url
                    :frame frame
                    :reason :frame-not-url-bound}))))

(fx/reg-fx :rf.nav/replace-url
  {:platforms #{:client}
   :doc       "Replace the URL in the browser history (HTML5 replaceState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."}
  (fn [{:keys [frame]} url]
    (if (url-bound-frame? frame)
      #?(:cljs (.replaceState js/window.history nil "" url)
         :clj  (trace/emit! :fx :rf.fx/skipped-on-platform
                            {:fx-id :rf.nav/replace-url :url url}))
      (trace/emit! :fx :rf.fx/skipped-on-platform
                   {:fx-id :rf.nav/replace-url
                    :url   url
                    :frame frame
                    :reason :frame-not-url-bound}))))

(fx/reg-fx :rf.nav/capture-scroll
  {:platforms #{:client}
   :doc       "Capture the current browser scroll position under the
per-frame [:rf.route/scroll-positions <url>] map before leaving a route."}
  (fn [{:keys [frame]} {:keys [url position]}]
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
       (trace/emit! :fx :rf.fx/skipped-on-platform
                    {:fx-id :rf.nav/capture-scroll :url url}))))

;; ---- :url-bound? exclusivity check ----------------------------------------
;; Per Spec 012 §Multi-frame routing — "Only one frame can own the URL at
;; a time": registering a second `:url-bound? true` frame emits
;; `:rf.error/duplicate-url-binding` per Spec 009 §error event catalogue.
;; The check runs from a registrar registration-hook (rf2-w50qm) so it
;; fires on BOTH first-time and re-registration paths.
;;
;; The recovery is `:no-recovery` per Spec 009. The registry remains
;; inspectable as-written, but `url-owner-frame-id` enforces one active
;; owner at fx time: non-owner `:rf.nav/push-url` / `:rf.nav/replace-url`
;; calls no-op. The error surfaces the conflict; resolving it is the
;; app's concern.

(defn- frame-id-of-existing-url-binding
  "Scan the registrar's `:frame` map for any frame OTHER than `exclude-id`
  that currently carries an explicit `:url-bound? true`. Returns the
  offending frame-id or nil. The `:rf/default` frame's implicit
  `:url-bound? true` IS counted — the existing URL owner is unchanged."
  [exclude-id]
  (some (fn [[other-id other-meta]]
          (when (and (not= other-id exclude-id)
                     (or (true? (url-bound?-from-config other-meta))
                         (and (= :rf/default other-id)
                              (not (false? (url-bound?-from-config other-meta))))))
            other-id))
        (registrar/registrations :frame)))

(defn- check-url-bound-exclusivity!
  "Registration-hook fn. When a `:frame` registration carries
  `:url-bound? true` AND another frame already owns the URL, emit
  `:rf.error/duplicate-url-binding`. Per Spec 012 §Multi-frame routing
  and Spec 009 §error event catalogue.

  Recovery per Spec 009 is `:no-recovery` — the offending registration's
  storage has already been written by `registrar/register!`, but the
  navigation fx (`:rf.nav/push-url` / `:rf.nav/replace-url`) consults
  `url-owner-frame-id`, so only the single active owner can mutate
  browser history. The app resolves the conflict by removing one of the
  bindings."
  [{:keys [kind id now]}]
  (when (= :frame kind)
    (when (true? (url-bound?-from-config now))
      (when-let [other (frame-id-of-existing-url-binding id)]
        (trace/emit-error! :rf.error/duplicate-url-binding
                           {:existing-frame  other
                            :offending-frame id
                            :reason          "Two frames carry :url-bound? true; only one frame may own the URL at a time."
                            :recovery        :no-recovery})))))

(registrar/add-registration-hook! check-url-bound-exclusivity!)

;; ---- :rf.route/with-nav-token --------------------------------------------
;; Per Spec 012 §Navigation tokens §Threading. Wraps an async-completion
;; fx entry (`:do`) with a stale-result check: match → run; mismatch →
;; suppress and emit `:rf.route.nav-token/stale-suppressed`. Spec-Schemas
;; carries the `:rf.fx/with-nav-token-args` shape.

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
;; Per Spec 012. Subs live in this artefact (not re-frame.core) so apps
;; that don't pull day8/re-frame2-routing carry no `:rf.route/*` strings
;; on their production-elision bundle (rf2-k682).

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
(subs/reg-sub :rf.route/fragment
  {:doc "Subscribe to the current route's URL `#fragment` (string or nil). Per Spec 012 §Fragments."}
  :<- [:rf/route] (fn [route _] (:fragment route)))

(defn- chain-from-meta
  "Walk the `:parent` chain from `id` to the root, returning a vector
  [parent-most ... id]. Routes without a `:parent` produce a single-
  element chain. Cycles are guarded by a `seen` set; a route id whose
  `:parent` ultimately points back to itself terminates at the cycle's
  entry point (defensive — Spec 012 §Nested layouts does not address
  cycle handling explicitly, but a bad-faith registration must not
  hang the runtime)."
  [id]
  (when id
    (loop [cur  id
           acc  (list)
           seen #{}]
      (cond
        (nil? cur)      (vec acc)
        (seen cur)      (vec acc)
        :else           (recur (:parent (registrar/lookup :route cur))
                               (conj acc cur)
                               (conj seen cur))))))

(subs/reg-sub :rf.route/chain
  {:doc "Subscribe to the `:parent`-chain of the active route, returned
  as a vector `[parent-most ... current]`. Per Spec 012 §Nested layouts."}
  :<- [:rf.route/id] (fn [id _] (chain-from-meta id)))

(subs/reg-sub :rf/pending-navigation
  {:doc "Subscribe to the `:rf/pending-navigation` slot (nil when no
  navigation is pending). Per Spec 012 §Navigation blocking — pending-nav
  protocol."}
  (fn [db _] (:rf/pending-navigation db)))

;; ---- route-link registered view ------------------------------------------
;; Per Spec 012 §Linking from views. Plain left-click → preventDefault
;; + dispatch `:rf/url-requested`; modifier-key / middle-click defers to
;; the browser. CLJS-only render; JVM gets an SSR shell (no DOM events
;; to intercept).

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
     `:rf/url-requested`; modifier-key or middle-click → no interception.

     Performance (rf2-r1in4): this is render-path code — every
     `[rf/route-link ...]` re-render walks `route-url` for the href.
     Large nav menus re-rendering frequently amortise the cost over many
     calls; see `route-url`'s perf note for the precompute follow-on
     should it become a bottleneck."
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
;; Per rf2-k682. `re-frame.core` MUST NOT `:require [re-frame.routing]` —
;; the artefact is optional. Public-API re-exports are published through
;; the late-bind table; consumers without the artefact see the hooks
;; unregistered and the active surfaces throw cleanly.

(late-bind/set-fn! :routing/reg-route        reg-route)
(late-bind/set-fn! :routing/match-url        match-url)
(late-bind/set-fn! :routing/route-url        route-url)
(late-bind/set-fn! :routing/reset-counters!  reset-counters!)
(late-bind/set-fn! :routing/route-sub-fn     route-sub-fn)

;; route-link is exposed on both platforms so .cljc render trees
;; resolve identically server- and client-side.
#?(:cljs (late-bind/set-fn! :routing/route-link route-link)
   :clj  (late-bind/set-fn! :routing/route-link route-link-render-ssr))
