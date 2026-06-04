(ns re-frame.routing.registry
  "Route registration + URL matching + URL building for re-frame2 routing.

  Per Spec 012 §Bidirectional URL ↔ params and §Route ranking algorithm.
  Owns:
    - the canonical thrown-error shape (`route-error` helper);
    - the route-table cache (pre-sorted by `:rf.route/rank` descending);
    - `reg-route` / `unregister-route!` (registry mutation);
    - `match-url` (URL → slice) and `route-url` (slice → URL);
    - query-coercion / fragment-decode / param-validation helpers;
    - `default-max-decoded-keys` keyword-interning DoS guard;
    - `reset-counters!` test-time helper.

  Internal namespace; the public facade is `re-frame.routing` —
  framework-internal callers depend on this ns directly via the
  `registry/` alias, but the published API surface remains the
  facade's re-exports. Per the rf2-2yabr cohesion split: REGISTRY +
  MATCH/EMIT seam."
  (:require [clojure.string :as str]
            [re-frame.registrar :as registrar]
            [re-frame.late-bind :as late-bind]
            [re-frame.routing.match :as match]
            [re-frame.routing.url :as url]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

;; ---- canonical thrown-error shape ----------------------------------------
;; Per Spec 009 §The thrown-error shape — the :rf.error/id ex-data
;; contract. Every routing throw carries :rf.error/id (the canonical
;; discriminator), :where (the public surface fn symbol the caller
;; wrote — 'rf/route-url, 'rf/match-url, 'rf/reg-route — so a grep
;; lands on the call site), :recovery, and a human-readable :reason.
;; Per-site slots (:route-id / :slot / :value / :error / :param / :url
;; / :limit / :count / :pattern) merge on top.
;;
;; NOTE the :error per-site slot (on :rf.error/route-url-validation)
;; holds a Malli explainer payload — that is a SEPARATE contract from
;; the :rf.error/id discriminator and is preserved verbatim.

(defn route-error
  "Build a routing ex-info with the canonical thrown-error shape (per
  Spec 009). `error-kw` becomes the message AND the `:rf.error/id`
  discriminator slot; `where-sym` names the public surface; `reason` is
  the human-readable diagnostic; `extras` merges per-site slots."
  ([error-kw where-sym reason] (route-error error-kw where-sym reason nil))
  ([error-kw where-sym reason extras]
   (ex-info (str error-kw)
            (merge {:rf.error/id error-kw
                    :where       where-sym
                    :recovery    :no-recovery
                    :reason      reason}
                   extras))))

;; ---- registration counter + table cache ----------------------------------

(defonce ^:private reg-counter (atom 0))

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
                    (sort-by (fn [[_id route-meta]]
                               (or (:rf.route/rank route-meta) [0 0 0 0 0 0]))
                             #(compare %2 %1))
                    vec)]
    (reset! route-table-cache {:source-id source :pairs pairs})
    pairs))

(defn route-table
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

;; ---- url encoding / decoding facade --------------------------------------
;; The public predicate `malformed-url?` is re-exported here so the
;; routing facade has a stable entry point; internal callers within
;; this ns use the `url/` alias directly.

(defn malformed-url?
  "Public predicate: true when `url`'s percent-encoding is malformed in
  any of its decode'd portions — any non-empty path segment, any query
  key or value, or the `#fragment`. The scan is purely lexical: it splits
  the URL into pieces and tries to %-decode each one; no route table or
  pattern is consulted. Used by `:rf.route/transitioned` /
  `:rf.route/handle-url-change` to discriminate the bare route-miss case
  (`{:url url}`) from the malformed-URL fail-closed case
  (`{:url url :reason :malformed-url}`) — both end up at
  `:rf.route/not-found` but the structured `:reason` lets per-route
  error UIs and SSR projections branch on the cause.

  Per Spec 012 §Routing failure semantics §Malformed percent-encoding
  (rf2-4ic0f). Thin facade over `re-frame.routing.url/malformed-url?`
  (rf2-icrxv Phase-2 — URL seam)."
  [url]
  (url/malformed-url? url))

(declare compile-query-coercions)

;; ---- authoring-boundary metadata validation ------------------------------
;; Per Spec 012 §Reserved route-metadata keys. `reg-route` has the
;; largest registration shape in the v2 surface (twelve reserved keys);
;; a typo'd key (`:on-matched` for `:on-match`) or an opts-shaped map in
;; the wrong slot would otherwise pass silently at registration and fail
;; later at nav-time, or never. We fail LOUDLY at the authoring boundary
;; (rf2-45b95): bare keys outside the reserved set are rejected; hosts
;; and apps add their own keys under a namespace (`:myapp/*`), which are
;; always allowed (per Spec 012 §Other pattern-level requirements — route
;; metadata is an open map for NAMESPACED keys only).

(def ^:private reserved-route-keys
  "Route-metadata keys `reg-route` accepts as bare (unqualified) keys;
  any other bare key is a likely typo and is rejected at registration.

  The first twelve are the routing-owned reserved keys per Spec 012
  §Reserved route-metadata keys. `:head` is a CROSS-FEATURE reserved
  key owned by SSR (Spec 011 §Head/meta contract — \"routes name which
  head to use via `:head` route metadata\"); Spec 012 itself lists it as
  a valid route-metadata key alongside the routing-owned set (Spec 012
  §Route-not-found). Cross-feature reserved keys are enumerated here so
  the authoring guard does not false-flag a legitimate SSR route."
  #{;; routing-owned (Spec 012 §Reserved route-metadata keys)
    :doc :path :params :query :query-defaults :query-retain
    :tags :parent :on-match :on-error :scroll :can-leave
    ;; cross-feature: SSR head selection (Spec 011 §Head/meta contract)
    :head})

(defn- validate-route-metadata!
  "Authoring-boundary guardrail for `reg-route` (rf2-45b95). Throws
  `:rf.error/invalid-route-metadata` (canonical thrown-error shape, per
  Spec 009) when `metadata` carries a BARE key outside the reserved set —
  the common typo case (`:on-matched` for `:on-match`). Namespaced keys
  (`:myapp/analytics-id`) are host/app extension points and always pass
  (Spec 012 §Reserved route-metadata keys). A non-map `metadata` is also
  rejected here so the failure names the route at the authoring boundary
  rather than NPE-ing downstream.

  The thrown error names every offending key under `:keys` and carries
  the reserved set under `:reserved` so the message is actionable —
  authors see exactly which key is wrong and what the valid vocabulary
  is. Fails in dev AND prod (it is a caller bug, not user input)."
  [id metadata]
  (when-not (map? metadata)
    (throw (route-error
             :rf.error/invalid-route-metadata
             'rf/reg-route
             (str "route " id "'s metadata must be a map, got " (pr-str (type metadata)))
             {:route-id id :value metadata})))
  (let [bad (into []
                  (comp (map key)
                        (remove qualified-keyword?)
                        (remove reserved-route-keys))
                  metadata)]
    (when (seq bad)
      (throw (route-error
               :rf.error/invalid-route-metadata
               'rf/reg-route
               (str "route " id " declares unknown metadata "
                    (if (= 1 (count bad)) "key " "keys ")
                    (str/join ", " (map pr-str bad))
                    " — bare keys outside the reserved set are rejected as likely "
                    "typos (e.g. :on-matched for :on-match). Reserved keys: "
                    (str/join ", " (map pr-str (sort reserved-route-keys)))
                    ". Host/app extension keys must be namespaced (e.g. :myapp/analytics-id).")
               {:route-id id
                :keys     bad
                :reserved reserved-route-keys})))))

;; ---- registration --------------------------------------------------------

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
  ;; Authoring-boundary guardrail (rf2-45b95): reject bare metadata keys
  ;; outside the reserved set BEFORE any computation, so a typo fails
  ;; loudly at registration naming the bad key. Runs on the
  ;; user-supplied map (pre-merge-coords) so it never sees the computed
  ;; `:rf.route/*` / source-coord keys.
  (validate-route-metadata! id metadata)
  (let [pattern      (match/canonical-route-pattern (:path metadata))
        metadata     (assoc metadata :path pattern)
        idx          (swap! reg-counter inc)
        _            (match/validate-route-pattern! id pattern)
        ;; Single-pass parse: rank + regex + capture names +
        ;; per-optional-group lookup all derive from one left-to-right
        ;; walk (rf2-uovh5).
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
    (let [previous (registrar/lookup :route id)]
      (registrar/register! :route id meta')
      ;; Cache invalidation is automatic — the registrar's `:route` map
      ;; gets a new identity on every register!, and `route-table` checks
      ;; identity equality before reusing the cached pairs vector.
      ;;
      ;; Per Spec 012 §Trace events: `:rf.route/registered` fires on
      ;; FIRST-TIME registration so tools subscribing to "all route
      ;; lifecycle events" see one event per fresh route.
      ;; Re-registration rides the cross-kind `:rf.registry/handler-
      ;; replaced` trace (emitted by `registrar/register!` per Spec 001
      ;; §Hot-reload trace surface); not re-emitted here. Mirrors the
      ;; `:rf.flow/registered` symmetry.
      (when (nil? previous)
        (trace/emit! :rf.event :rf.route/registered
                     {:route-id id
                      :path     pattern})))
    id))

(defn unregister-route!
  "Remove a registered route. Emits `:rf.route/cleared` so tools
  subscribing to route lifecycle observe the removal; symmetric with
  `:rf.flow/cleared`. Per Spec 012 §Trace events. No-op
  when the route id was not registered."
  [id]
  (let [previous (registrar/lookup :route id)]
    (when previous
      (registrar/unregister! :route id)
      (trace/emit! :rf.event :rf.route/cleared
                   {:route-id id
                    :path     (:path previous)})))
  nil)

;; ---- match + coerce ------------------------------------------------------

(def ^:const default-max-decoded-keys
  "Default cap on the number of unique query-string keys a single URL
  may carry through `match-url`. Per rf2-3k3o7 — a defensive ceiling
  against the keyword-interning DoS surface on long-running JVMs,
  symmetric with `:rf.http/max-decoded-keys` (rf2-wu1n5). JVM keywords
  intern into a process-global, never-GC'd table; a hostile partner
  URL stream with N-unique query keys per request burns N permanent
  slots. 10000 is generous enough not to false-positive on legitimate
  large URLs, finite enough to bound an attacker-controlled payload.

  This is a single global constant. The cap is enforced inside
  `match-url`'s query-parse, which runs route-agnostically (the raw
  query map is built once, before any route is matched), so there is no
  per-route override hook — Spec 012 §Keyword-interning cap names only
  the global `default-max-decoded-keys`."
  10000)

(defn- compile-query-coercions
  "Flatten a `[:map [k type-or-opts] ...]` Malli vector schema into a
  `{k type-form}` map for O(1) per-key lookup during URL coercion.
  Returns nil when the schema is absent or not a vector. Computed once
  at registration time and cached on the route metadata under
  `:rf.route/query-coerce` (rf2-yjjrv) — O(1) per-key lookup at nav time
  rather than re-scanning the schema per query key.

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
        (fn [acc slot-entry]
          (if (and (vector? slot-entry) (keyword? (first slot-entry)))
            (let [k         (first slot-entry)
                  raw       (cond
                              (= 2 (count slot-entry)) (second slot-entry)
                              (= 3 (count slot-entry)) (last slot-entry)
                              :else                    nil)
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
              (assoc! acc k type-form))
            acc))
        (transient {})
        (rest schema)))))

(def ^:private int-literal-re
  "rf2-oyw04: a strict integer-literal guard for `:int` query-value
  coercion, applied **identically on JVM and CLJS**. A value is coerced to
  a number only when the WHOLE string is an optionally-signed run of ASCII
  digits; otherwise it passes through as a string on both hosts.

  The host-divergent predecessor (`Long/parseLong` on JVM vs `js/parseInt`
  on CLJS) disagreed on non-strict input — `?page=12abc` yielded the string
  `\"12abc\"` server-side but the number `12` client-side, a Spec 011
  hydration-mismatch hazard that violated Spec 012's \"same handler runs
  server- and client-side\" contract and the Spec 000 Goal 2 cross-host
  conformance bar. A shared regex makes the parse decision a pure function
  of the string, independent of host.

  Leading zeros (`\"007\"`) and surrounding whitespace are NOT special-cased
  here beyond what the regex permits: `^-?\\d+$` rejects whitespace and
  radix prefixes, so `\" 12\"`, `\"0x10\"`, `\"12abc\"` all stay strings on
  both hosts. The downstream `:query` Malli schema (rf2-ug2m1 layered
  validation) then surfaces `:validation-failed?` for a `:int`-typed slot
  carrying a non-coerced string — the coercion contract is honoured on both
  hosts, not silently passed through on one."
  #"^-?\d+$")

(defn- parse-int-strict
  "Coerce `v` to an integer iff it is a whole integer literal per
  `int-literal-re`; otherwise return `v` unchanged. Identical on JVM and
  CLJS (rf2-oyw04). On CLJS the digit-string is parsed via `parseInt`
  base-10 — safe because the regex has already proven the whole string is
  `^-?\\d+$`, so no NaN / radix-sniffing / trailing-junk path is reachable."
  [v]
  (if (and (string? v) (re-matches int-literal-re v))
    #?(:clj  (Long/parseLong v)
       :cljs (js/parseInt v 10))
    v))

(defn- coerce-by-type-form
  "Apply a single Malli type-form coercion to a raw URL string. First-pass
  vocabulary: `:int` / `:boolean` plus the rf2-3k3o7 keyword variants:

  - `:int` — coerced to a number **only when the whole string is an
    integer literal** (`^-?\\d+$`), identically on JVM and CLJS (rf2-oyw04).
    Non-integer-literal input (`\"12abc\"`, `\"0x10\"`, `\" 12\"`, `\"abc\"`)
    stays a string on BOTH hosts; the route's `:query` schema then flags the
    type mismatch via the layered validator.
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
    (parse-int-strict v)

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

  A route declaring NO vocabulary keeps EVERY URL key as a string
  (rf2-5ifai) — the value-side rf2-3k3o7 enum gate's key-side mirror:
  hostile URLs composed of N-unique keys would otherwise burn N
  permanent JVM keyword slots, and a bare
  `(reg-route :route/x {:path \"/x\"})` is the high-cardinality
  public-surface case where this hits hardest. Authors who want keyword
  keys declare them via `:query` / `:query-defaults` / `:query-retain` —
  author-named intent is the trust boundary.

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

  Per Spec 012 §Routing failure semantics §Malformed percent-encoding
  the entire URL is treated as a route-miss when the fragment cannot be
  decoded — the fragment is %-decoded here (not surfaced raw) so a
  malformed `#fragment` is caught before it reaches the slice."
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

(defn validate-route-shape
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
      (let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (if-not validate
          [false nil]
          (if (validate schema value)
            [false nil]
            (let [explain  (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
                  details  (when explain (explain schema value))]
              [true details])))))))

(defn- normalize-match-path
  "Spec 012 trailing-slash normalisation for incoming URLs. `/cart` and
  `/cart/` are equivalent; root remains `/`, and the no-leading-slash
  leniency remains (`cart/` → `cart`). Coerces nil to `\"\"` then
  delegates to the shared `url/strip-trailing-slashes` — the same loop
  `match/canonical-route-pattern` runs on author patterns, so the
  incoming-URL and route-pattern surfaces normalise identically."
  [path]
  (url/strip-trailing-slashes (or path "")))

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
  ;; — the URL's query string (split + url-decode per pair) is only
  ;; walked once a path-pattern match succeeds, so unmatched URLs pay
  ;; nothing. The closure captures `query-str`; the delay forces at most
  ;; once and is held for the lifetime of this call.
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
            ;; caller can route the failure. rf2-6t1xb: the nav entry points
            ;; (`url-change-fx`, `navigate`) wrap match-url in
            ;; `match-url-fail-closed`, which catches this throw and treats
            ;; the URL as a route-miss → `:rf.route/not-found` with
            ;; `:reason :too-many-keys` (the fail-closed contract). Direct
            ;; `match-url` callers still see the throw.
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
                    (fn [acc pair]
                      ;; Skip empty pairs. A trailing `?` (`/x?`), a leading
                      ;; `&` (`/x?&a=1`), or a doubled `&&` (`/x?a=1&&b=2`)
                      ;; splits to an empty `""` token; decoding it would
                      ;; inject a spurious `{"" ""}` key into the slice's
                      ;; :query — junk that breaks identical-route-target?
                      ;; no-op detection, counts toward the keyword-interning
                      ;; cap, and never round-trips through route-url. Per
                      ;; Spec 012 §Query strings and fragments §`+` is a
                      ;; literal (rf2-9a9ix finding 2).
                      (if (clojure.string/blank? pair)
                        acc
                        (let [[k v] (clojure.string/split pair #"=" 2)
                              ;; Per Spec 012 §Routing failure semantics
                              ;; (rf2-wbvme + rf2-4ic0f): malformed %-encoding
                              ;; in a query key or value FAILS CLOSED — the
                              ;; whole URL is treated as a route-miss (rather
                              ;; than dropping the offending pair, which would
                              ;; let hostile URLs into the slice when the host
                              ;; route had no required keys). The empty-value
                              ;; branch (`v` is nil → "") is distinct from a
                              ;; malformed value and must not be conflated.
                              kstr  (url/safe-url-decode k)
                              vstr  (if v (url/safe-url-decode v) "")]
                          (if (or (nil? kstr) (nil? vstr))
                            (reduced ::malformed-query)
                            (let [acc' (assoc acc kstr vstr)]
                              (when (> (count acc') default-max-decoded-keys)
                                (throw (route-error
                                         :rf.error/route-too-many-keys
                                         'rf/match-url
                                         (str "the query string exceeded the per-call unique-key cap (" default-max-decoded-keys ") — a keyword-interning DoS guard; the URL is treated as a route-miss")
                                         {:url   url
                                          :limit default-max-decoded-keys
                                          :count (count acc')})))
                              acc')))))
                    (array-map)
                    pairs))))]
        ;; Iterate the pre-sorted table; the first pattern that matches is
        ;; the highest-rank winner (Spec 012 §Route ranking algorithm).
        ;; `reduce` with `reduced` short-circuits on the first hit. nil ⇒
        ;; no route matched OR malformed query fails closed (rf2-4ic0f).
        (reduce
          (fn [_ [id route-meta]]
            (when-let [compiled (or (:rf.route/compiled route-meta)
                                    (some-> (:path route-meta) match/parse-pattern))]
              (when-let [params (match/match-against compiled path)]
                (let [query-coerce  (:rf.route/query-coerce route-meta)
                      defaults      (:query-defaults route-meta)
                      retain        (:query-retain route-meta)
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
                          [params-failed? params-error] (validate-route-shape route-meta :params params)
                          [query-failed?  query-error]  (validate-route-shape route-meta :query  with-defaults)
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

(defn match-url-fail-closed
  "Fail-closed wrapper over `match-url` for the URL-driven and
  programmatic nav entry points (`url-change-fx`, `navigate`). Returns
  `{:match <match-or-nil> :throw-reason <keyword-or-nil>}`.

  `match-url` itself THROWS on the `:rf.error/route-too-many-keys`
  keyword-interning DoS guard (rf2-3k3o7): a URL whose unique decoded
  query-key count exceeds `default-max-decoded-keys`. The guard's intent
  (registry.cljc §default-max-decoded-keys, the inline comment at the
  throw site) is that such a URL `is treated as a route-miss` — a clean
  fail-closed to `:rf.route/not-found`, NOT a crash. But the throw, left
  unhandled at the nav entry points, escapes the event handler and
  CRASHES the event drain (rf2-6t1xb) — converting a memory-pressure DoS
  into a worse drain-crash DoS.

  This wrapper catches the throw (and, defensively, ANY throw out of
  `match-url`) and turns it into a NIL match plus a `:throw-reason`
  discriminator, so the caller routes to `:rf.route/not-found` exactly
  as it does for a bare miss — the fail-closed path the guard promises.
  `:throw-reason` becomes the `:reason` slot on the not-found slice's
  `:params` (alongside the existing `:malformed-url` / `:validation`
  discriminators), so per-route error UIs and SSR projections can branch
  on the cause:

   - `:too-many-keys`  — the `:rf.error/route-too-many-keys` cap throw;
   - `:match-error`    — any other (unexpected) throw out of match-url.

  Note: malformed %-encoding does NOT throw — `match-url` already fails
  it closed to nil (`malformed-url?` then discriminates `:reason
  :malformed-url`). This wrapper is strictly for the THROW path."
  [url]
  (try
    {:match (match-url url) :throw-reason nil}
    (catch #?(:clj Throwable :cljs :default) ex
      {:match        nil
       :throw-reason (if (= :rf.error/route-too-many-keys
                            (:rf.error/id (ex-data ex)))
                       :too-many-keys
                       :match-error)})))

;; ---- route-url param-segment emission ------------------------------------
;; `route-url`'s pattern walk hits a `:name` / `*name` segment in two
;; places — the top-level loop (params may be absent → throw) and
;; `emit-group` (an optional group is only entered when all its inner
;; params are present → read directly). Both share the identical
;; cursor-advance (`segment-end` to the segment boundary, `keyword` the
;; captured name) and the same `:` → url-encode / `*` → url-encode-splat
;; encoder dispatch. The two helpers below carry that shared shape so
;; the four branch bodies collapse to a bounds-read + an encode call,
;; and the encoder-vs-segment-kind mapping cannot drift between the two
;; walk sites.

(defn- param-seg-bounds
  "Given `pattern` (length `n`) with the cursor `i` sitting on a `:` or
  `*` sigil, return `[end k]`: `end` is the index just past the param
  name (the next segment boundary) and `k` is the captured name as a
  keyword."
  [^String pattern n i]
  (let [start (inc i)
        end   (match/segment-end pattern n start)]
    [end (keyword (subs pattern start end))]))

(defn- encode-param
  "Percent-encode a path-param `v` for emission: splat values
  (`splat?`) preserve literal '/' between captured segments
  (`url-encode-splat`); a plain named param is a single component
  (`url-encode`)."
  [splat? v]
  (if splat?
    (url/url-encode-splat v)
    (url/url-encode v)))

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

  NIL-POLICY ASYMMETRY between PATH params and QUERY params (rf2-b3rzz —
  deliberate, documented here so the split never costs a debugging
  session):

  - PATH params: a `nil` (or absent) value for a required `:name` /
    `*name` segment is a HARD ERROR — throws
    `:rf.error/missing-route-param`. The URL cannot be built without it.
    A present-but-FALSY value (`false`, `0`, `\"\"`) is legitimate and
    round-trips (the `if-some` discipline below discriminates falsy from
    absent).
  - QUERY params: a `nil`-valued key is SILENTLY ELIDED — `{:page nil}`
    omits the key entirely rather than emitting a bare `?page=` or
    throwing. This is the useful default for absent OPTIONAL query keys
    (a search form that conditionally adds `?sort=` only when a sort is
    chosen). A present-but-FALSY query value (`false`, `0`, `\"\"`) is a
    legitimate value and round-trips, same as the path side.

  So `nil` means \"hard error\" on the path side and \"omit this key\" on
  the query side — same function, two nil-policies. Authors relying on a
  query key being present must supply a non-nil value.

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
         route-meta   (registrar/lookup :route route-id)
         pattern      (:path route-meta)]
     (when (nil? pattern)
       (throw (route-error
                :rf.error/no-such-route
                'rf/route-url
                (str "no route is registered under id " route-id)
                {:route-id route-id})))
     ;; Per Spec 012 §Bidirectional URL ↔ params: validate the caller's
     ;; inputs against the route's :params / :query schemas BEFORE
     ;; emitting the URL. A schema mismatch is a caller bug; raise with
     ;; the structured id so callers (`:rf.route/navigate`) and tests
     ;; can react. When no schema is declared OR no validator is
     ;; registered, this is a no-op.
     (let [[p-failed? p-error] (validate-route-shape route-meta :params path-params)]
       (when p-failed?
         (throw (route-error
                  :rf.error/route-url-validation
                  'rf/route-url
                  (str "the supplied :params did not validate against route " route-id "'s :params schema")
                  {:route-id route-id
                   :slot     :params
                   :value    path-params
                   :error    p-error}))))
     (let [[q-failed? q-error] (validate-route-shape route-meta :query query-params)]
       (when q-failed?
         (throw (route-error
                  :rf.error/route-url-validation
                  'rf/route-url
                  (str "the supplied :query did not validate against route " route-id "'s :query schema")
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
           groups (or (:groups (:rf.route/compiled route-meta))
                      (:groups (match/parse-pattern pattern)))
           ;; Resolve a REQUIRED path-param value or throw. Per Spec 012
           ;; §Bidirectional URL ↔ params: an absent or `nil` value
           ;; raises; a present-but-falsy value (`false`, `0`, `""`) is a
           ;; legitimate segment and round-trips. `if-some` discriminates
           ;; falsy-but-present from absent (a plain `(or v throw)` would
           ;; mis-classify falsy as absent). `kind` ("path"/"splat") only
           ;; flavours the diagnostic message.
           require-param
           (fn [k kind]
             (if-some [v (get path-params k)]
               v
               (throw (route-error
                        :rf.error/missing-route-param
                        'rf/route-url
                        (str "route " route-id " requires " kind " param " k " but it was absent (or nil)")
                        {:param k :route-id route-id}))))
           ;; Inner loop emits the body of an optional group whose params
           ;; are all present. State threads as (loop [i parts]); returns
           ;; [next-i parts'] when the group's '}' (and optional '?') is
           ;; consumed.
           emit-group
           (fn emit-group [i parts]
             (loop [i     i
                    parts parts]
               (let [ch (.charAt ^String pattern i)]
                 (cond
                   (= ch \})
                   (let [after-close (inc i)]
                     [(if (and (< after-close n)
                               (= \? (.charAt ^String pattern after-close)))
                        (inc after-close)
                        after-close)
                      parts])

                   ;; `:name` / `*name` inside an optional group — the
                   ;; group is only entered when all its inner params are
                   ;; present, so read directly (no require check).
                   (or (= ch \:) (= ch \*))
                   (let [[end k] (param-seg-bounds pattern n i)]
                     (recur end (conj parts (encode-param (= ch \*)
                                                          (get path-params k)))))

                   :else
                   (recur (inc i) (conj parts (str ch)))))))
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

                   ;; `:name` / `*name` in the top-level pattern — the
                   ;; value is REQUIRED; `require-param` throws on absent.
                   (or (= ch \:) (= ch \*))
                   (let [splat?  (= ch \*)
                         [end k] (param-seg-bounds pattern n i)
                         v       (require-param k (if splat? "splat" "path"))]
                     (recur end (conj parts (encode-param splat? v))))

                   :else
                   (recur (inc i) (conj parts (str ch)))))))
           path-out (apply str parts)
           ;; Drop nil-valued query keys from emission — `{:page nil}`
           ;; omits the key rather than emitting a bare `?page=`. Mirrors
           ;; the path-param `if-some` discipline above: a present-but-
           ;; falsy value (`false`, `0`, `""`) is a legitimate query value
           ;; and round-trips, but `nil` means "absent" and is elided.
           emitted-query (into (array-map)
                               (remove (fn [[_ v]] (nil? v)))
                               query-params)
           qs (when (seq emitted-query)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]]
                              (str (url/url-encode (name k)) "="
                                   (url/url-encode v)))
                            emitted-query))))
           ;; Per Spec 012 §Fragments §Programmatic navigation with
           ;; fragments: the 4-arity emits `#fragment` when non-nil and
           ;; non-empty. Empty-string fragments collapse to no fragment.
           frag (when (and fragment (not= "" fragment))
                  (str "#" fragment))]
       (str path-out qs frag)))))

(defn reset-counters!
  "Reset the route-registration counter to zero. Test-time helper so
  reg-index is deterministic across fixture runs."
  []
  (reset! reg-counter 0))
