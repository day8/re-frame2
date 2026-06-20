(ns re-frame.routing.registry
  "Route registration + URL matching + URL building for re-frame2 routing.

  Per Spec 012 §Bidirectional URL ↔ params and §Route ranking algorithm.
  Owns:
    - the canonical thrown-error shape (`route-error` helper);
    - the route-table cache (pre-sorted by `:rf.route/rank` descending);
    - `reg-route` / `clear-route` (registry mutation);
    - `match-url` (URL → slice) and `route-url` (slice → URL);
    - query-coercion / fragment-decode / param-validation helpers;
    - `reset-counters!` test-time helper.

  Internal namespace; the public facade is `re-frame.routing` —
  framework-internal callers depend on this ns directly via the
  `registry/` alias, but the published API surface remains the
  facade's re-exports. Per the rf2-2yabr cohesion split: REGISTRY +
  MATCH/EMIT seam."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.identity :as identity]
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
  "Build a routing ex-info via the central thrown-error builder
  `re-frame.error/thrown-ex-info` (per Spec 009 §The thrown-error shape).
  `error-kw` is the `:rf.error/id` discriminator slot; `where-sym` names
  the public surface; `reason` is the human-readable diagnostic that
  LEADS the message (the message also trails the `[:rf.error/<id>]`
  greppability token); `extras` merges per-site slots."
  ([error-kw where-sym reason] (route-error error-kw where-sym reason nil))
  ([error-kw where-sym reason extras]
   (error/thrown-ex-info error-kw where-sym reason {:extra extras})))

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

(declare compile-schema-coercions)

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

(defn- accepted-route-keys
  "The full set of accepted bare route-metadata keys: the routing-owned
  `reserved-route-keys` UNIONed with any LATE-BOUND cross-feature
  extension keys published under `:routing/extra-route-keys`.

  Per Spec 012 §Reserved route-metadata keys, routing rejects unknown
  bare route-metadata keys at registration. A cross-feature artefact
  (e.g. the Resources artefact's `:resources` key, Spec 016 §Route
  integration) extends the accepted set via this late-bound framework
  extension — exactly as `:head` is a cross-feature key owned by SSR.
  The hook publishes a SET of extra keys; resources is the first
  publisher (rf2-p10npe). When no extension artefact is loaded the hook
  is absent and the set is exactly the routing-owned reserved keys, so an
  app without resources/SSR sees no behaviour change."
  []
  (if-let [extra (late-bind/get-fn :routing/extra-route-keys)]
    (into reserved-route-keys (extra))
    reserved-route-keys))

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
  (let [accepted (accepted-route-keys)
        bad (into []
                  (comp (map key)
                        (remove qualified-keyword?)
                        (remove accepted))
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
                    (str/join ", " (map pr-str (sort accepted)))
                    ". Host/app extension keys must be namespaced (e.g. :myapp/analytics-id).")
               {:route-id id
                :keys     bad
                :reserved accepted})))))

;; ---- registration --------------------------------------------------------

(defn reg-route
  "Register a route. Per the canonical Spec 001 3-slot grammar (rf2-wvh95f F1)
  the route's defining VALUE — its `:path` pattern — is the THIRD slot, and the
  middle slot is the pure reflection-metadata map:

      (rf/reg-route :route/cart {:doc \"The cart page.\"} \"/cart\")
      (rf/reg-route :route/article
        {:doc \"Article detail.\" :params [:map [:id :uuid]]
         :on-match [[:article/load]]}
        \"/articles/:id\")

  A route has no handler FUNCTION — it is a declarative URL↔params binding, so
  its third slot is the path-pattern VALUE (the legitimate \"handler-or-value\"
  reading of Spec 001 §Registration grammar, exactly as `reg-app-schema`'s
  third facet is its schema value). Moving `:path` out of the middle slot
  restores clean doc-DCE (the middle slot is now a pure metadata map).

  `metadata` carries the route's reflection / lifecycle / shape keys (`:doc`,
  `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`,
  `:on-match`, `:on-error`, `:scroll`, `:can-leave`, plus the cross-feature
  `:head` / `:resources`); see Spec 012. The `:path` is merged onto the stored
  route-meta internally, so every downstream reader (`route-meta`, `match-url`,
  ranking) keeps reading `:path` off the stored map unchanged.

  Computes :rf.route/rank AND a :rf.route/compiled regex at registration
  time so match-url can sort candidates by rank and match without
  re-parsing on each call. If a previously-registered route has an
  equal structural rank, emits :rf.warning/route-shadowed-by-equal-score
  (per Spec 012 §Route ranking algorithm — rule 6) so tooling can flag
  the conflict."
  [id metadata path]
  ;; Reject non-map metadata FIRST (rf2-45b95 authoring-boundary guard) with
  ;; the canonical `:rf.error/invalid-route-metadata`. Under the 3-slot
  ;; grammar (rf2-wvh95f F1) the metadata slot may be any value, so this must
  ;; run before the `contains?`/`assoc` below — both of which throw a raw
  ;; ClassCastException/IllegalArgumentException on a non-associative value.
  (when-not (map? metadata)
    (throw (route-error
             :rf.error/invalid-route-metadata
             'rf/reg-route
             (str "route " id "'s metadata must be a map, got " (pr-str (type metadata)))
             {:route-id id :value metadata})))
  ;; rf2-wvh95f F1 — the path pattern is the 3-slot VALUE. A `:path` left
  ;; INSIDE the metadata map is a mislocated key (the third slot is its one
  ;; home); reject it loudly so the grammar change cannot be half-applied.
  (when (contains? metadata :path)
    (throw (route-error
             :rf.error/invalid-route-metadata
             'rf/reg-route
             (str "route " id " declares :path inside its metadata map — per "
                  "rf2-wvh95f F1 the path pattern is the THIRD slot: "
                  "(reg-route " id " {…} " (pr-str (:path metadata)) "). Move "
                  "the pattern out of the metadata map into the value slot.")
             {:route-id id :keys [:path] :value (:path metadata)})))
  (let [metadata (assoc metadata :path path)
        _ ;; Authoring-boundary guardrail (rf2-45b95): reject bare metadata keys
          ;; outside the reserved set BEFORE any computation, so a typo fails
          ;; loudly at registration naming the bad key. Runs on the
          ;; user-supplied map (pre-merge-coords) so it never sees the computed
          ;; `:rf.route/*` / source-coord keys. `:path` is now present (merged
          ;; from the value slot) and is a reserved key, so it passes.
          (validate-route-metadata! id metadata)
        pattern      (match/canonical-route-pattern (:path metadata))
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
        query-coerce (compile-schema-coercions (:query metadata))
        ;; rf2-cylse.5: compile the `:params` schema into a path-coerce
        ;; table the SAME way as the query side, so PATH captures coerce
        ;; against their declared type (`:int`/`:uuid`/`:double`/enum)
        ;; before validation — without it a non-`:string` path-param type
        ;; makes every valid URL fail :params validation → 404.
        params-coerce (compile-schema-coercions (:params metadata))
        meta'        (cond-> (source-coords/merge-coords metadata)
                       rank          (assoc :rf.route/rank rank)
                       compiled      (assoc :rf.route/compiled compiled)
                       query-coerce  (assoc :rf.route/query-coerce query-coerce)
                       params-coerce (assoc :rf.route/params-coerce params-coerce))]
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

(defn clear-route
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

;; ---- registry-side introspection -----------------------------------------
;; The `<thing>-ids` + `<thing>-meta` enumerate pair every sibling registry
;; carries (`resource-ids`/`resource-meta`, `machines`/`machine-meta`) — the
;; static-registry read a tool or AI inspector walks to answer "which routes
;; are registered, and what is route X's spec?" without re-stating the
;; `registrar/registrations :route` walk at each call site.

(defn route-ids
  "Return a vector of every registered route id. The static-registry
  enumerate half of the routing introspection pair (the live per-frame route
  slice is read through the `:rf.route/*` subs). Mirrors the sibling
  `resource-ids` / machines `machines` introspection accessors."
  []
  (vec (registrar/ids :route)))

(defn route-meta
  "Return the registered route's metadata map (`:path` pattern, `:on-match`,
  `:params`, `:query`, `:scroll`, `:can-leave`, the computed `:rf.route/rank`
  / `:rf.route/compiled` / coercion tables, source coords) for `route-id`, or
  nil if no route is registered under that id. Mirrors the sibling
  `resource-meta` / machines `machine-meta` introspection accessors. Per Spec
  012 §Reserved route-metadata keys."
  [route-id]
  (registrar/lookup :route route-id))

;; ---- match + coerce ------------------------------------------------------

(def ^:private coercible-scalar-type-forms
  "The bare Malli scalar type keywords `coerce-by-type-form` knows how
  to coerce a URL string into. `:keyword` is handled separately (it
  rewrites to `:rf.route/keyword-unbounded`); `:string` is a deliberate
  passthrough. Used to recognise the *optioned* form `[:int {…}]` as the
  same coercion as the bare `:int` (rf2-fwz29i)."
  #{:int :double :uuid :boolean})

(defn- normalize-type-form
  "Reduce a per-slot Malli type-form to the canonical coercion token
  `coerce-by-type-form` understands. Pure; no interning. rf2-fwz29i.

  Handled shapes:
  - bare scalar `:int` / `:double` / `:uuid` / `:boolean` → itself.
  - bare `:keyword` → `:rf.route/keyword-unbounded` (no enum allowlist;
    stays a string at coerce time — the rf2-3k3o7 unbounded-intern guard).
  - `[:enum :a :b …]` / `[:enum {…opts} :a :b …]` with all-keyword
    choices → `[:rf.route/enum-keyword #{choice-names…}]` (rf2-3k3o7
    bounded allowlist).
  - **optioned scalar** `[:int {…}]` / `[:double {…}]` / `[:uuid {…}]` /
    `[:boolean {…}]` → the bare scalar token; **optioned** `[:keyword {…}]`
    → `:rf.route/keyword-unbounded`. Ordinary Malli properties on an
    otherwise-supported scalar no longer silently disable URL-string
    coercion (the rf2-fwz29i bug: `[:int {:min 1}]` validated `\"2\"`
    against `[:int …]` and 404'd every valid deep link).
  - **wrapper** `[:maybe inner]` → recurse on `inner` (optional-with-nil:
    the present URL string still coerces to the inner type; an absent key
    is simply absent). `[:maybe :int]`, `[:maybe [:int {…}]]` supported.

  Any other form (a composite/unsupported schema, or a scalar this
  vocabulary does not coerce — `:string`, a `[:and …]`, a ref) returns
  the `raw` form **unchanged**: it stays in the coercion table (so a
  declared query key is still promoted to a keyword key — the
  `coerce-query` `declared-names` contract, rf2-5ifai) but
  `coerce-by-type-form` passes its value through verbatim, and the route's
  Malli `:params`/`:query` validation has the final say on the type."
  [raw]
  (cond
    (= :keyword raw)
    :rf.route/keyword-unbounded

    (contains? coercible-scalar-type-forms raw)
    raw

    (vector? raw)
    (let [head (first raw)]
      (cond
        ;; rf2-3k3o7: `[:enum kw kw …]` bounded keyword allowlist. Skip
        ;; the optional opts-map at position 1 (Malli `[:enum {…} :a :b]`).
        (= :enum head)
        (let [tail  (rest raw)
              items (if (and (seq tail) (map? (first tail)))
                      (rest tail)
                      tail)]
          (if (and (seq items) (every? keyword? items))
            [:rf.route/enum-keyword (into #{} (map name) items)]
            ;; A non-keyword `[:enum …]` (string/number choices) is not a
            ;; keyword allowlist — leave it as a value passthrough.
            raw))

        ;; rf2-fwz29i: optioned scalar `[:int {…}]` etc. The Malli
        ;; properties map (or its absence) does not change the coercion —
        ;; the head type drives it. `[:keyword {…}]` stays unbounded.
        (= :keyword head)
        :rf.route/keyword-unbounded

        (contains? coercible-scalar-type-forms head)
        head

        ;; rf2-fwz29i: `[:maybe inner]` — coerce the present value against
        ;; the inner type; nil/absent needs no coercion. Unwrap, then keep
        ;; the slot in the table either way (an unsupported inner falls to
        ;; the raw-passthrough below).
        (= :maybe head)
        (let [inner (normalize-type-form (second raw))]
          (if (some? inner) inner raw))

        :else raw))

    :else raw))

(defn- compile-schema-coercions
  "Flatten a `[:map [k type-or-opts] ...]` Malli vector schema into a
  `{k type-form}` map for O(1) per-key lookup during URL coercion.
  Returns nil when the schema is absent or not a vector. Computed once
  at registration time and cached on the route metadata under
  `:rf.route/query-coerce` (from the `:query` schema, rf2-yjjrv) and
  `:rf.route/params-coerce` (from the `:params` schema, rf2-cylse.5) —
  O(1) per-key lookup at nav time rather than re-scanning the schema per
  key. Schema-agnostic: the same `[:map ...]` shape drives both the query
  side and the path side.

  Each slot's type-form is reduced to a canonical coercion token by
  `normalize-type-form` (rf2-fwz29i): bare scalars, **optioned** scalars
  (`[:int {…}]`), `[:enum …]` keyword allowlists (rf2-3k3o7), bare/optioned
  `:keyword` (→ `:rf.route/keyword-unbounded`), and `[:maybe inner]`
  wrappers all map to the right coercion; an unsupported form stays in the
  table verbatim so its slot remains a declared key (string-passthrough at
  coerce time, with the Malli validator having the final say)."
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
                  type-form (normalize-type-form raw)]
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

(def ^:private max-safe-integer
  "The largest integer magnitude both hosts represent EXACTLY: `2^53 - 1`,
  the IEEE-754 double safe-integer ceiling (`js/Number.MAX_SAFE_INTEGER`).
  Above this, a CLJS number loses precision (it is a double) while a JVM
  `Long` stays exact — the same digit string would coerce to DIFFERENT
  numeric values server- vs client-side (a Spec 011 hydration mismatch),
  or coerce on one host and throw/round on the other. rf2-cylse.1."
  9007199254740991)

(defn- parse-int-strict
  "Coerce `v` to an integer iff it is a whole integer literal per
  `int-literal-re` AND fits within the cross-host safe-integer range;
  otherwise return `v` unchanged. HOST-SYMMETRIC AND TOTAL — identical
  result on JVM and CLJS (rf2-oyw04 + rf2-cylse.1).

  rf2-cylse.1: `int-literal-re` (`^-?\\d+$`) makes the parse DECISION a
  pure function of the string, but NOT the parse RESULT — `^-?\\d+$`
  matches arbitrarily long digit runs, and the two hosts then disagree on
  the numeric value for an oversized literal:
    - in (2^53, 2^63):  JVM `parse-long` → an EXACT `Long`; CLJS
      `parse-long` → a LOSSY double (e.g. 9007199254740993 → …92);
    - above 2^63:       JVM → `nil` (out of `Long` range); CLJS → a
      lossy double — divergent OUTCOME (route-miss vs commit), not just
      value.
  Both are the exact cross-host-parity / hydration-mismatch class
  rf2-oyw04 set out to close. The fix bounds the literal at the shared
  `max-safe-integer` ceiling (`2^53 - 1`) and PASSES THROUGH AS A STRING
  on BOTH hosts above it — mirroring the `\"12abc\"` passthrough
  discipline (the route's `:int` `:query`/`:params` Malli schema then
  flags the un-coerced string). `parse-long` is host-symmetric and total
  (returns `nil`, never throws, on overflow), so no
  `NumberFormatException` can escape `match-url` to a direct facade
  caller either (the rf2-cylse.1 case-3 undocumented throw)."
  [v]
  (if (and (string? v) (re-matches int-literal-re v))
    (let [n (parse-long v)]
      (if (and n (<= (- max-safe-integer) n max-safe-integer))
        n
        ;; nil (>2^63 on JVM) or out of the safe-integer range on either
        ;; host → pass through as a string, identically on both hosts.
        v))
    v))

(defn- coerce-by-type-form
  "Apply a single Malli type-form coercion to a raw URL string. First-pass
  vocabulary: `:int` / `:boolean` plus the rf2-3k3o7 keyword variants:

  - `:int` — coerced to a number **only when the whole string is an
    integer literal** (`^-?\\d+$`) within the cross-host safe-integer
    range, identically on JVM and CLJS (rf2-oyw04 + rf2-cylse.1).
    Non-integer-literal or oversized input (`\"12abc\"`, `\"0x10\"`,
    `\" 12\"`, `\"abc\"`, a >2^53 literal) stays a string on BOTH hosts;
    the route's `:query`/`:params` schema then flags the type mismatch via
    the layered validator.
  - `:double` — coerced to a number via the host-symmetric, total
    `parse-double` (returns nil → string passthrough on bad input; never
    throws). rf2-cylse.5.
  - `:uuid` — coerced to a UUID object via the host-symmetric, total
    `parse-uuid` (returns nil → string passthrough on a non-UUID; never
    throws). This is what makes the canonical Spec 012 `:uuid` PATH route
    (`{:path \"/articles/:id\" :params [:map [:id :uuid]]}`) round-trip a
    real UUID URL to `{:id #uuid \"...\"}` rather than 404. rf2-cylse.5.
  - `:rf.route/keyword-unbounded` — declared as `:keyword` with no enum
    constraint. **Stays as string** (no intern; the unbounded keyword-
    interning DoS surface is precisely what rf2-3k3o7 guards against).
  - `[:rf.route/enum-keyword #{names}]` — declared as `[:enum :a :b ...]`.
    Intern is gated by the allowlist; values matching a declared enum
    choice are keyword'd, others stay string. Bounded by construction.

  Any other type-form (including nil) is a pass-through. Per Spec 012
  §Query-string coercion and rf2-3k3o7. Shared by the query side
  (`coerce-query`) and the path side (`coerce-path`, rf2-cylse.5)."
  [type-form v]
  (cond
    (= :int type-form)
    (parse-int-strict v)

    (= :double type-form)
    ;; rf2-cylse.5: host-symmetric + total. `parse-double` returns nil on
    ;; a non-double string (both hosts) → leave the raw string so the
    ;; layered :params/:query validator flags it.
    (if (string? v) (or (parse-double v) v) v)

    (= :uuid type-form)
    ;; rf2-cylse.5: host-symmetric + total. `parse-uuid` returns nil on a
    ;; non-UUID string (both hosts) → leave the raw string for the
    ;; validator. Makes the canonical Spec 012 :uuid path route match.
    (if (string? v) (or (parse-uuid v) v) v)

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

(defn- enum-keyword-token
  "The INVERSE of the `[:rf.route/enum-keyword #{names}]` decode in
  `coerce-by-type-form` (rf2-dcmkke). Given a slot's normalized
  coercion `type-form` (from `:rf.route/query-coerce` /
  `:rf.route/params-coerce`) and a route value `v`, return the URL-token
  representation `route-url` should emit.

  For a declared keyword-enum slot whose value is a keyword in the
  allowlist, this is the keyword's NAME (`:asc` -> `\"asc\"`) — the exact
  token `match-url` decodes back to the canonical enum keyword. Without
  this, `url/url-encode` would host-`(str :asc)` to `\":asc\"` and emit
  `%3Aasc`, which `match-url`'s enum decoder does not recognise (it reads
  only the declared names), so the prism would not round-trip
  (EP-0012 §Route Prism Laws; Spec 012 §924-936 — `:enum :asc :desc` is
  represented on the wire as `desc`, decoded to `:desc`).

  Every OTHER case is a passthrough: a non-enum slot, a non-keyword value,
  or a keyword whose name is not in the allowlist (which `route-url`'s
  schema validation has already rejected before emission — so the
  passthrough is only ever reached for the admitted values). `url-encode`
  then stringifies the admitted scalar as before."
  [type-form v]
  (if (and (keyword? v)
           (vector? type-form)
           (= :rf.route/enum-keyword (first type-form))
           (contains? (second type-form) (name v)))
    (name v)
    v))

(defn query-key->url-token
  "The REVERSIBLE URL-string token for a declared query keyword `k`
  (rf2-jlufhn). A namespaced keyword keeps its namespace in the token so
  the prism leg is bijective:

    :page     -> \"page\"
    :user/id  -> \"user/id\"

  `(name :user/id)` returns just `\"id\"` — it DROPS the namespace, so two
  declared keys `:user/id` and `:account/id` would both emit `id=` and a
  route declaring `:query [:map [:user/id :string]]` could never round-trip
  `{:user/id \"u\"}` through `route-url`/`match-url`. EP-0012 §Route Prism
  Laws require `match-url(route-url(...))` to recover the canonical route
  data, and namespaced query keys are distinct canonical EDN facts
  (Conventions §Canonical EDN identity), so the namespace must survive the
  URL round-trip. The token is the keyword's full qualified name —
  `(subs (str k) 1)` strips the leading `:` from `(str :user/id)` =>
  `\":user/id\"`, yielding `\"user/id\"` — which `(keyword \"user/id\")`
  reads back to the identical `:user/id`. The `/` is percent-encoded by
  `url/url-encode` on emission and decoded by the match-side parse, so the
  token survives the wire (`user%2Fid=u`) and decodes back to `\"user/id\"`
  before the declared-key lookup."
  [k]
  (subs (str k) 1))

(defn- declared-query-tokens
  "Build the `{url-token -> declared-keyword}` map for a route's declared
  query vocabulary (rf2-jlufhn). The token is the REVERSIBLE
  `query-key->url-token` of each declared keyword (namespace-preserving),
  so the match-side parse can recover the EXACT declared keyword — namespace
  included — rather than collapsing `:user/id` to `:id` via a lossy
  `(keyword (name k))`. `:query-coerce`, `:query-defaults`, and
  `:query-retain` all contribute their keys; a later slot does not clobber
  an earlier mapping for the same token (the token -> keyword relation is
  unique by construction, since each maps from one declared keyword)."
  [query-coerce defaults retain]
  (cond-> {}
    query-coerce   (into (map (fn [k] [(query-key->url-token k) k])) (keys query-coerce))
    (seq defaults) (into (map (fn [k] [(query-key->url-token k) k])) (keys defaults))
    (seq retain)   (into (map (fn [k] [(query-key->url-token k) k])) retain)))

(defn- coerce-query
  "Coerce a raw `{string-key string-value}` map against a precompiled
  `query-coerce` table (`{:keyword-key type-form}`). Returns an
  array-map to preserve URL key order.

  Per rf2-3k3o7 + rf2-5ifai: only query keys named by the route's
  `:query` schema (encoded as `query-coerce`), `:query-defaults`, or
  `:query-retain` are promoted to keyword keys; unknown keys retain
  their **string** form. The route's declared vocabulary defines the
  keyword universe; the framework refuses to extend the process-global
  keyword table on behalf of URL keys the route did not name. This
  selective keywording IS the keyword-interning DoS closure — a hostile
  URL of N-unique undeclared keys interns ZERO keywords, so no
  raw-query-size cap is needed (rf2-x0ngkv).

  A route declaring NO vocabulary keeps EVERY URL key as a string
  (rf2-5ifai) — the value-side rf2-3k3o7 enum gate's key-side mirror:
  hostile URLs composed of N-unique keys would otherwise burn N
  permanent JVM keyword slots, and a bare
  `(reg-route :route/x {} \"/x\")` is the high-cardinality
  public-surface case where this hits hardest. Authors who want keyword
  keys declare them via `:query` / `:query-defaults` / `:query-retain` —
  author-named intent is the trust boundary.

  `:query-defaults` and `:query-retain` slots widen the declared
  universe (they are author-named intent, identical trust class to
  the `:query` schema itself).

  rf2-jlufhn: the declared-key match is by the REVERSIBLE
  `query-key->url-token` (namespace-preserving), not a bare `(keyword k)`.
  A declared `:user/id` round-trips through the URL token `\"user/id\"`
  back to `:user/id` — the prior `(keyword k)` collapsed it to `:id`,
  losing the namespace and breaking the EP-0012 route-prism law for any
  namespaced query key (and silently merging `:user/id` + `:account/id`
  into one `:id`)."
  [query-coerce defaults retain raw-query]
  (let [token->declared (declared-query-tokens query-coerce defaults retain)]
    (reduce-kv
      (fn [m k v]
        (if-let [kk (get token->declared k)]
          ;; Declared key: recover the EXACT declared keyword (namespace
          ;; included) from the reversible token + apply type coercion.
          (assoc m kk (coerce-by-type-form (get query-coerce kk) v))
          ;; Undeclared key: pass through with the **string** key, no
          ;; type coercion. The framework does not burn a keyword slot
          ;; per unique URL key the route did not declare (rf2-5ifai).
          (assoc m k v)))
      (array-map)
      raw-query)))

(defn- canonical-query-order
  "Reorder a `match-url` `:query` map's entries into deterministic CEDN-1
  canonical KEY order (`re-frame.identity/canonical-bytes`), preserving every
  key/value pair, and return an array-map so the order is stable downstream.

  rf2-t3cfil (EP-0012 tier-2 routing consumer sweep): the inbound URL's
  query string carries keys in whatever left-to-right order the author of
  THAT URL chose, but the route slice's `:query` is route DATA — an identity
  fact the `:rf.route/query` sub, no-op detection (`identical-route-target?`),
  and SSR-hydration parity key off. Two inbound URLs spelling the same query
  in different key orders (`?b=2&a=1` vs `?a=1&b=2`) MUST therefore yield the
  SAME `:query` identity, not one that varies with the link author's spelling.
  This is the inbound mirror of `route-url`'s already-canonical query emission
  (rf2-wgutc2): per Conventions §Routes are prisms (deferred to Spec 012),
  `match-url(route-url(...))` returns canonical route data and \"query keys are
  emitted in deterministic canonical order\" — both prism legs share ONE order.

  The sort is by the key's shared CEDN-1 byte identity (the same order the
  CEDN-1 map encoding uses), total over the mixed-kind keys a `:query` may
  carry (declared keys are promoted to keywords, undeclared keys stay strings
  — `canonical-bytes` tags each kind, so a keyword `:page` and a string
  `\"page\"` never collide and order deterministically). Applied AFTER the raw
  parse, so the parser's last-wins repeated-key collapse and the malformed-
  %-encoding fail-closed are unchanged — this only fixes the surviving map's
  key ORDER, never its membership or values."
  [query]
  (into (array-map)
        (sort-by (comp identity/canonical-bytes key) query)))

(defn- coerce-path
  "Coerce a `{keyword-key string-value}` PATH-capture map against the
  precompiled `params-coerce` table (`{:keyword-key type-form}`, from the
  route's `:params` schema). Each captured key whose type-form is a known
  coercion vocabulary entry (`:int` / `:uuid` / `:double` / `:boolean` /
  `[:enum ...]` keyword allowlist) is coerced; every other key (incl.
  `:string` and any undeclared capture) passes through unchanged.
  rf2-cylse.5.

  Unlike `coerce-query`, the keyword-interning DoS concern (rf2-3k3o7 /
  rf2-5ifai) does NOT apply here: path-capture keys are already keywords
  produced by `match-against` from the route pattern's FIXED capture
  names — their cardinality is bounded by the author's pattern, not by
  attacker-supplied URL keys. So this coerces values in place without a
  key-allowlist gate.

  Returns `params` unchanged when no `params-coerce` table is present
  (the common case — a route with no `:params` schema, or an all-string
  schema)."
  [params-coerce params]
  (if (and params-coerce (seq params))
    (reduce-kv
      (fn [m k v]
        (assoc m k (if-let [tf (get params-coerce k)]
                     (coerce-by-type-form tf v)
                     v)))
      params
      params)
    params))

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
            ;; rf2-3k3o7: parse query as a **string-keyed** raw map. The
            ;; keyword-interning DoS is closed downstream by `coerce-query`,
            ;; which promotes ONLY the route's declared query vocabulary to
            ;; keyword keys and passes undeclared keys through as strings —
            ;; a hostile URL of N-unique undeclared keys interns ZERO
            ;; keywords. No raw-query-size cap is needed (rf2-x0ngkv).
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
                      ;; no-op detection and never round-trips through
                      ;; route-url. Per Spec 012 §Query strings and fragments
                      ;; §`+` is a literal (rf2-9a9ix finding 2).
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
                            (assoc acc kstr vstr)))))
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
              (when-let [raw-params (match/match-against compiled path)]
                (let [;; rf2-cylse.5: coerce PATH captures against the
                      ;; route's `:params` schema (precompiled to
                      ;; `:rf.route/params-coerce`) BEFORE validation —
                      ;; mirrors the query side. Without this a typed path
                      ;; param (`:int`/`:uuid`/`:double`/enum) is a raw
                      ;; string fed to a typed Malli schema → validation
                      ;; FAILS → the canonical Spec 012 `:uuid` route 404s
                      ;; for every valid URL. `params` (coerced) is what
                      ;; both validation AND the `:params` result key use,
                      ;; so the slice carries `{:id #uuid \"...\"}` per
                      ;; Spec 012:269/498.
                      params        (coerce-path (:rf.route/params-coerce route-meta) raw-params)
                      query-coerce  (:rf.route/query-coerce route-meta)
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
                          merged        (cond
                                          (and (nil? coerced) (empty? defaults)) (array-map)
                                          (empty? defaults)                      coerced
                                          :else
                                          (reduce-kv
                                            (fn [m k v]
                                              (if (contains? m k) m (assoc m k v)))
                                            (or coerced (array-map))
                                            defaults))
                          ;; rf2-t3cfil (EP-0012 tier-2 routing consumer sweep):
                          ;; reorder the surviving query entries into CEDN-1
                          ;; canonical KEY order — the inbound mirror of
                          ;; `route-url`'s canonical query emission (rf2-wgutc2),
                          ;; so the same query spelled in different inbound-URL
                          ;; key orders yields the SAME `:query` identity (a
                          ;; stable `:rf.route/query` sub value / no-op-detection
                          ;; key / SSR-hydration parity). Per Conventions §Routes
                          ;; are prisms: both prism legs share ONE canonical
                          ;; order. Membership + values are untouched (defaults
                          ;; merge already ran; last-wins + malformed-fail-closed
                          ;; happened in the raw parse) — only KEY order changes.
                          with-defaults (canonical-query-order merged)
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

  A generic navigation-resilience guard: any unexpected throw out of
  `match-url` must not escape the nav event handler and crash the event
  drain (rf2-6t1xb). This wrapper catches ANY throw, turns it into a NIL
  match plus a `:throw-reason` discriminator, and lets the caller route
  to `:rf.route/not-found` exactly as it does for a bare miss.
  `:throw-reason` becomes the `:reason` slot on the not-found slice's
  `:params` (alongside the existing `:malformed-url` / `:validation`
  discriminators), so per-route error UIs and SSR projections can branch
  on the cause:

   - `:match-error`    — any unexpected throw out of match-url.

  Note: malformed %-encoding does NOT throw — `match-url` already fails
  it closed to nil (`malformed-url?` then discriminates `:reason
  :malformed-url`). This wrapper is strictly for the THROW path."
  [url]
  (try
    {:match (match-url url) :throw-reason nil}
    (catch #?(:clj Throwable :cljs :default) _ex
      {:match        nil
       :throw-reason :match-error})))

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

;; ---- fail-closed URL-scalar guard (rf2-94o54l.1, EP-0012) ----------------
;; A route path-param value and a (non-nil) query value reach the URL string
;; only through `url/url-encode` / `url/url-encode-splat`, both of which call
;; host `(str v)`. For a URL scalar (string / keyword / symbol / boolean /
;; portable integer / UUID) host `(str v)` produces a stable, decodable,
;; HOST-IDENTICAL segment that round-trips through `match-url`. But two
;; classes of value would silently `(str v)` into a fabricated identity:
;;
;;   (a) a HOST value with no EDN identity — a function, an atom / promise, a
;;       raw JS object, a DOM node, a non-portable number (float / ratio /
;;       out-of-safe-range integer) — `(str v)` yields `#object[...]`,
;;       `[object Object]`, `cljs$core...`, or a host-specific numeral, an
;;       identity invented from a host reference;
;;   (b) an INSTANT / host `Date` — which IS a portable EDN identity for a
;;       resource cache key (`re-frame.identity` canonicalizes it to UTC text),
;;       but whose host `(str v)` is HOST-DIVERGENT for a URL segment
;;       (`#inst "..."` on CLJ vs an ISO string on CLJS vs `Thu Jun 12 ...`
;;       for a `java.util.Date`) and which `match-url` has no instant
;;       coercion vocabulary to read back (it coerces only
;;       `:int :double :uuid :boolean :keyword`), so it cannot round-trip.
;;
;; EP-0012 forbids exactly this: "If a route param value cannot be represented
;; as canonical EDN after schema coercion, route matching or URL printing MUST
;; fail closed at the relevant boundary. It MUST NOT use host `str`, JS object
;; stringification, or object identity to invent a cache or route identity"
;; (docs/EP/EP-0012 §893-896; Conventions §Canonical EDN identity §584-592).
;;
;; The query KEY side is already guarded: every surviving key is run through
;; `identity/canonical-bytes` by the canonical-order sort above, which throws
;; `:rf.error/non-edn-identity` on a host key. The path-param and query-VALUE
;; sides were NOT — they went straight to `(str v)`. This helper closes that
;; gap with a DOCUMENTED NARROWER URL-SCALAR predicate (the second option the
;; bead's smallest-fix offers): it routes class (a) through
;; `identity/canonical-bytes` (the same CEDN-1 boundary, the same fail-closed
;; posture the resources cache key uses via `state/reject-non-edn!`) and
;; additionally rejects class (b) at the URL boundary — because a URL segment
;; has no round-trippable instant form and no host-stable instant `(str v)`.
;; A rejected value raises a structured `:rf.error/route-url-non-edn-value`
;; carrying route context (the underlying `:rf.error/non-edn-identity` rides
;; `:rf.error/cause` for class (a)) BEFORE any URL string is built — never a
;; host-stringified URL.
;;
;; This is the URL-EMISSION boundary, NARROWER than the general CEDN-1 identity
;; domain (which admits instants and composites): `url-encode` stays the
;; encoder for the admitted URL scalars, but it is never the boundary that
;; invents a host-reference or host-divergent identity. The route's declared
;; `:params` / `:query` schema is the surface that further constrains a param
;; to a specific scalar shape.

(defn- host-instant?
  "True when `v` is a host instant / `Date` — a portable EDN identity for a
  cache key, but NOT a round-trippable URL segment (host-divergent `(str v)`,
  no `match-url` instant coercion). Rejected at the URL boundary (rf2-94o54l.1)."
  [v]
  #?(:clj  (or (instance? java.time.Instant v)
               (instance? java.util.Date v))
     :cljs (instance? js/Date v)))

(defn- assert-url-value!
  "Fail closed when `v` (a used path-param value or a non-nil query value) is
  not an admitted URL scalar — i.e. it is a host value outside the CEDN-1
  identity domain (function / atom / promise / raw JS object / DOM node /
  non-portable number) OR an instant / host `Date` (a portable identity, but
  not a round-trippable URL segment). Either would otherwise be
  host-stringified by `url/url-encode` into a fabricated or host-divergent
  route identity (EP-0012 §Canonical EDN identity). `slot` is `:params` or
  `:query`; `k` is the offending param / query key. Raises
  `:rf.error/route-url-non-edn-value` (for the host-value class, the underlying
  `:rf.error/non-edn-identity` rides `:rf.error/cause`); returns `v` unchanged
  when it is admitted."
  [route-id slot k v]
  (when (host-instant? v)
    (throw (route-error
             :rf.error/route-url-non-edn-value
             'rf/route-url
             (str "route " route-id " " (name slot) " value for " k
                  " is an instant / host Date — re-frame2 will not "
                  "host-stringify it into a URL (its host string is "
                  "host-divergent and has no round-trippable URL segment; "
                  "EP-0012 §Canonical EDN identity). Encode it as a portable "
                  "string (e.g. an ISO-8601 token) at the boundary first")
             {:route-id route-id
              :slot     slot
              :param    k
              :value    v})))
  (try
    (identity/canonical-bytes v)
    v
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) ex
      (if (= :rf.error/non-edn-identity (:rf.error/id (ex-data ex)))
        (throw (route-error
                 :rf.error/route-url-non-edn-value
                 'rf/route-url
                 (str "route " route-id " " (name slot) " value for " k
                      " is not a portable EDN identity (" (:bad-type (ex-data ex))
                      ") — re-frame2 will not host-stringify it into a URL "
                      "(EP-0012 §Canonical EDN identity); encode it as portable "
                      "EDN at the boundary first")
                 {:route-id        route-id
                  :slot            slot
                  :param           k
                  :value           v
                  :rf.error/cause  (ex-data ex)}))
        (throw ex)))))

(defn- assert-fragment!
  "Fail closed when `fragment` is not an admitted `route-url` fragment value
  (rf2-jlufhn). Per Spec 012 §Fragments a fragment is `<string-or-nil>`: only
  `nil`, the empty string, or a string is accepted. Returns `fragment`
  unchanged when admitted; raises `:rf.error/route-url-non-edn-value` for
  EVERY other value (a function, atom, host object, number, keyword, boolean,
  …) BEFORE `url/url-encode` host-stringifies it into a fabricated URL
  identity.

  The fragment is part of EP-0012 canonical route data and requires
  encode/decode symmetry (EP-0012 §Route Prism Laws). The path/query value
  boundary (`assert-url-value!`) already fails closed on host values, but
  it ADMITS portable scalars like numbers / keywords / booleans (they are
  canonical EDN identities). A fragment is NARROWER: `match-url` always
  returns a `<string-or-nil>` fragment, so a non-string fragment can never
  round-trip — `route-url(42)` would emit `#42` which `match-url` reads back
  as the STRING `\"42\"`, and `route-url(false)` / `route-url(:x)` would be
  host-stringified or truthiness-elided into a bogus identity. Narrowing the
  fragment to string-only on emission keeps the same fail-closed route-data
  boundary EP-0012 added for params/query (the `false` fragment is the
  motivating trap: the prior `(and fragment (not= \"\" fragment))` gate
  silently ELIDED a `false` fragment as if it were nil)."
  [route-id fragment]
  (if (or (nil? fragment) (string? fragment))
    fragment
    (throw (route-error
             :rf.error/route-url-non-edn-value
             'rf/route-url
             (str "route " route-id " fragment must be a string or nil "
                  "(Spec 012 §Fragments: `match-url` returns a "
                  "<string-or-nil> fragment, so a non-string fragment has "
                  "no round-trippable URL form; EP-0012 §Route Prism Laws). "
                  "re-frame2 will not host-stringify it into a URL — encode "
                  "it as a string at the boundary first")
             {:route-id route-id
              :slot     :fragment
              :value    fragment}))))

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
         ;; rf2-w3qgc: elide nil-valued query keys BEFORE :query-schema
         ;; validation (and reuse the elided map for emission below). Per
         ;; Spec 012 §Bidirectional URL ↔ params a nil-valued query key is
         ;; SILENTLY OMITTED — `{:page nil}` emits no key and never throws —
         ;; so a route declaring e.g. `:query [:map [:sort {:optional true}
         ;; :string]]` must accept `{:sort nil}` to mean "omit :sort" and
         ;; return `/search`, NOT raise `:rf.error/route-url-validation`.
         ;; Validating the raw map first contradicted that policy (nil
         ;; failed the `:string` branch). A present-but-falsy value
         ;; (`false`, `0`, `""`) is a legitimate value and is preserved —
         ;; only `nil` is elided — so non-nil invalid values STILL fail
         ;; validation against the elided map.
         ;;
         ;; rf2-wgutc2 (EP-0012 correctness review item 2): after nil
         ;; elision the surviving entries are sorted into DETERMINISTIC
         ;; CANONICAL KEY ORDER by their keys' shared CEDN-1 bytes
         ;; (`re-frame.identity/canonical-bytes`), NOT the caller's
         ;; insertion order. Per Conventions §The `:rf/path` algebra
         ;; (route-url/match-url prism): "query keys are emitted in
         ;; deterministic canonical order". Two callers that pass the same
         ;; query map spelled in different key orders now build the
         ;; BYTE-IDENTICAL URL — a stable href for caching / dedupe /
         ;; identical-route-target? no-op detection / SSR-hydration parity,
         ;; rather than a URL that varies with map literal order. The sort
         ;; is by the key's canonical EDN identity (the same order the
         ;; CEDN-1 map encoding uses), so it is total over the mixed-kind
         ;; query keys a route may carry. An array-map preserves this sorted
         ;; order downstream through validation and emission.
         emitted-query (into (array-map)
                             (sort-by (comp identity/canonical-bytes key)
                                      (remove (fn [[_ v]] (nil? v))
                                              query-params)))
         route-meta   (registrar/lookup :route route-id)
         pattern      (:path route-meta)
         ;; rf2-dcmkke: the precompiled coercion tables (the SAME tables
         ;; `match-url` decodes against) let the emission side run the
         ;; INVERSE of the enum-keyword decode — a declared keyword-enum
         ;; value emits its token name (`:asc` -> `asc`) so the prism
         ;; round-trips. Nil-safe: an undeclared route has empty tables and
         ;; `enum-keyword-token` is a passthrough for every key.
         params-coerce (:rf.route/params-coerce route-meta)
         query-coerce  (:rf.route/query-coerce route-meta)]
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
     ;; rf2-w3qgc: validate the NIL-ELIDED query map (`emitted-query`), not
     ;; the raw `query-params` — a nil-valued optional key is omitted per
     ;; Spec 012 and must not be presented to the schema (where it would
     ;; fail an optional non-nil branch). `:value` reports the elided map
     ;; actually validated.
     (let [[q-failed? q-error] (validate-route-shape route-meta :query emitted-query)]
       (when q-failed?
         (throw (route-error
                  :rf.error/route-url-validation
                  'rf/route-url
                  (str "the supplied :query did not validate against route " route-id "'s :query schema")
                  {:route-id route-id
                   :slot     :query
                   :value    emitted-query
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
           ;; raises; a present-but-falsy value (`false`, `0`) is a
           ;; legitimate segment and round-trips. `if-some` discriminates
           ;; falsy-but-present from absent (a plain `(or v throw)` would
           ;; mis-classify falsy as absent). `kind` ("path"/"splat") only
           ;; flavours the diagnostic message.
           ;;
           ;; rf2-ede1h.2: an EMPTY-STRING value is rejected the same way
           ;; as absent/nil. `false`/`0` stringify to non-empty segments
           ;; (`/page/false`, `/items/0`) that round-trip cleanly, but `""`
           ;; emits a ZERO-LENGTH segment (`/articles/` for `{:slug ""}`)
           ;; which `match-url`'s trailing-slash normalisation erases
           ;; (`/articles/` → `/articles`) before matching — so the URL
           ;; cannot round-trip back to the same route/params. A path
           ;; segment has no representation for the empty string; rejecting
           ;; it on EMISSION (rather than silently emitting an
           ;; un-round-trippable URL) keeps the bidirectional contract
           ;; honest. This narrows the spec's "present-but-falsy round-trips"
           ;; to the values that actually CAN (`false`, `0`); `""` is the
           ;; one falsy value with no legitimate segment form.
           ;; A PRESENT value for a path segment must be non-empty. Shared
           ;; by `require-param` (top-level segments) and `emit-group`
           ;; (optional-group inner segments): the empty-string-segment
           ;; rule is a property of the SEGMENT, not of where it sits in
           ;; the pattern. Spec 012 §`route-url` nil-policy: `""` on a path
           ;; param is a hard error on EITHER side because a zero-length
           ;; segment (`/articles/`) round-trips back as the param ABSENT.
           reject-empty-segment
           (fn [k kind v]
             (if (= "" (str v))
               (throw (route-error
                        :rf.error/missing-route-param
                        'rf/route-url
                        (str "route " route-id " requires a non-empty " kind " param " k
                             " but it was an empty string (a zero-length path segment "
                             "cannot round-trip through match-url)")
                        {:param k :route-id route-id :value v}))
               v))
           require-param
           (fn [k kind]
             (if-some [v (get path-params k)]
               (reject-empty-segment k kind v)
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
                   ;; `some?` (absent → the whole group elides), so the
                   ;; ABSENT case is already handled by the enclosing gate.
                   ;; A PRESENT `""` is NOT elision: `(some? "")` is true,
                   ;; so the group is entered and would emit a zero-length
                   ;; segment (`/articles/` for `{:id ""}`) that round-trips
                   ;; back as the param absent — the same un-round-trippable
                   ;; URL the top-level path rejects. Spec 012's nil-policy
                   ;; table makes `""` a hard error for ANY path segment, so
                   ;; reject it here too (`false`/`0` still round-trip).
                   ;; rf2-94o54l.1: same fail-closed identity gate as the
                   ;; top-level path — an optional-group inner segment must
                   ;; not host-stringify a host value into the URL either.
                   (or (= ch \:) (= ch \*))
                   (let [splat?  (= ch \*)
                         [end k] (param-seg-bounds pattern n i)
                         ;; rf2-dcmkke: map a declared keyword-enum value to
                         ;; its token name (`:asc` -> `asc`) before encoding
                         ;; so the path-side enum round-trips through match-url.
                         v       (->> (get path-params k)
                                      (reject-empty-segment k (if splat? "splat" "path"))
                                      (assert-url-value! route-id :params k)
                                      (enum-keyword-token (get params-coerce k)))]
                     (recur end (conj parts (encode-param splat? v))))

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
                       ;; rf2-8zvajk: ELIDE the group — and own the
                       ;; separator-slash elision so a slash-OWNED inline
                       ;; group (`{:base}?`, body without a leading `/`) does
                       ;; not orphan a literal `/`. The grammar permits two
                       ;; optional-group shapes (Spec 012 §Path-pattern grammar
                       ;; rule 2 + `validate-optional-group!`): the group either
                       ;; OWNS its leading slash (`{/:id}?` — the `/` is inside
                       ;; the body, so eliding the body drops it cleanly) or is
                       ;; SLASH-OWNED INLINE (`{:base}?` — bracketed by LITERAL
                       ;; `/`s in the surrounding pattern, e.g. `/{:base}?/about`
                       ;; or `/docs/{:section}?/about`). Spec 012's own
                       ;; ranking-rule-5 example uses the inline shape
                       ;; (`/about` beats `/{:base}?/about`), so it is
                       ;; spec-endorsed and cannot be rejected at registration.
                       ;; For the inline shape the present form is
                       ;; `/before/<v>/after`; the absent form must collapse to
                       ;; `/before/after` (one separator, not two). Without this,
                       ;; the prior emitter left BOTH literal slashes, producing
                       ;; `//about` — a PROTOCOL-RELATIVE URL (`route-link` would
                       ;; render `href="//about"`, a modifier-click escapes the
                       ;; app; programmatic `pushState` skips/rejects it, so the
                       ;; route slice and the address bar diverge).
                       ;;
                       ;; Rule: the slash BEFORE the elided group is redundant
                       ;; when the group is followed by a separator `/` (the
                       ;; trailing slash supplies the join) or when nothing
                       ;; follows (a dangling trailing `/`). Pop that leading
                       ;; separator unless it is the lone ROOT slash (so
                       ;; `/{:base}?` absent stays `/`, never `""`). A
                       ;; slash-OWNING group (`{/:id}?`) has a NON-slash char
                       ;; before `{` (`...articles{` → last part `"articles"`),
                       ;; so the guard is a no-op for it — its existing clean
                       ;; elision is unchanged. A global `//`→`/` collapse is
                       ;; deliberately NOT used: a splat value legitimately
                       ;; carries embedded `//` (`{:rest "a//b"}` → `/files/a//b`)
                       ;; and must survive.
                       (let [prev-slash? (= "/" (peek parts))
                             next-slash? (and (< close-end n)
                                              (= \/ (.charAt ^String pattern close-end)))
                             at-end?     (>= close-end n)
                             ;; the lone root slash has no non-slash part before
                             ;; it; popping it would emit `""` instead of `/`.
                             root-only?  (every? #(= "/" %) parts)
                             parts'      (if (and prev-slash?
                                                  (or next-slash?
                                                      (and at-end? (not root-only?))))
                                           (pop parts)
                                           parts)]
                         (recur close-end parts'))))

                   ;; `:name` / `*name` in the top-level pattern — the
                   ;; value is REQUIRED; `require-param` throws on absent.
                   ;; rf2-94o54l.1: a host value fails closed via
                   ;; `assert-url-value!` BEFORE `encode-param` host-stringifies
                   ;; it into a fabricated route identity (EP-0012).
                   (or (= ch \:) (= ch \*))
                   (let [splat?  (= ch \*)
                         [end k] (param-seg-bounds pattern n i)
                         ;; rf2-dcmkke: a declared keyword-enum path param
                         ;; emits its token name (`:desc` -> `desc`), the
                         ;; inverse of match-url's enum decode, so it
                         ;; round-trips rather than emitting `%3Adesc`.
                         v       (->> (require-param k (if splat? "splat" "path"))
                                      (assert-url-value! route-id :params k)
                                      (enum-keyword-token (get params-coerce k)))]
                     (recur end (conj parts (encode-param splat? v))))

                   :else
                   (recur (inc i) (conj parts (str ch)))))))
           path-out (apply str parts)
           ;; rf2-w3qgc: `emitted-query` (the nil-elided query map) is
           ;; computed once at the top of the fn so the SAME elided map
           ;; both feeds `:query`-schema validation AND drives URL emission.
           ;; `{:page nil}` omits the key rather than emitting a bare
           ;; `?page=`; a present-but-falsy value (`false`, `0`, `""`) is a
           ;; legitimate query value and round-trips, but `nil` means
           ;; "absent" and is elided.
           ;; rf2-94o54l.1: query KEYS are already CEDN-guarded by the
           ;; canonical-order sort that built `emitted-query` (it runs each
           ;; surviving key through `identity/canonical-bytes`, which throws
           ;; on a host key). The VALUES went straight to `url/url-encode`'s
           ;; `(str v)` — a host value would have been host-stringified into
           ;; the URL. Guard each non-nil value through `assert-url-value!`
           ;; (nil values are already elided out of `emitted-query`) so the
           ;; value side fails closed the same way the key side does.
           ;; rf2-jlufhn: emit a keyword key via the REVERSIBLE
           ;; `query-key->url-token` (namespace-preserving) so `:user/id`
           ;; emits `user/id` (percent-encoded `user%2Fid`) and round-trips
           ;; back to `:user/id` on the match side — `(name :user/id)` =>
           ;; `"id"` dropped the namespace, collapsing `:user/id` and
           ;; `:account/id` into one `id=` URL key and breaking the EP-0012
           ;; route-prism law. A string key (an undeclared caller-supplied
           ;; query key the route did not name) is emitted verbatim.
           ;; rf2-dcmkke: a declared keyword-enum query VALUE emits its token
           ;; name (`:asc` -> `asc`), the inverse of match-url's enum decode,
           ;; so the value round-trips back to the canonical keyword instead
           ;; of `(str :asc)` -> `%3Aasc` (which match-url reads as the string
           ;; `":asc"`). Applies after the key/value CEDN guard; a non-enum
           ;; value (or a string key the route never declared) is a passthrough.
           qs (when (seq emitted-query)
                (str "?"
                     (clojure.string/join "&"
                       (map (fn [[k v]]
                              (assert-url-value! route-id :query k v)
                              (str (url/url-encode (if (keyword? k)
                                                     (query-key->url-token k)
                                                     k))
                                   "="
                                   (url/url-encode (enum-keyword-token
                                                     (get query-coerce k) v))))
                            emitted-query))))
           ;; Per Spec 012 §Fragments §Programmatic navigation with
           ;; fragments: the 4-arity emits `#fragment` when non-nil and
           ;; non-empty. Empty-string fragments collapse to no fragment.
           ;;
           ;; The fragment is PERCENT-ENCODED on emission (rf2-ede1h.1) —
           ;; symmetric with `match-url`/`split-fragment`, which decode the
           ;; `#fragment` portion through `url/safe-url-decode`
           ;; (decodeURIComponent semantics). `url/url-encode` is the exact
           ;; inverse, so the round-trip is byte-exact: a fragment value
           ;; carrying a literal `%` (e.g. `"50% done"`) emits as
           ;; `#50%25%20done` and decodes back to `"50% done"`. Appending
           ;; the raw value instead produced `#50% done`, which `match-url`
           ;; then read as malformed (`safe-url-decode` throws on the bare
           ;; `%`) → nil — breaking the bidirectional URL contract for any
           ;; fragment with a `%` or other %-significant character.
           ;; rf2-jlufhn: validate the fragment is a string-or-nil BEFORE
           ;; the truthiness/empty gate, so a non-string fragment (a number,
           ;; keyword, boolean, function, host object) FAILS CLOSED rather
           ;; than being host-stringified into a bogus URL identity or
           ;; silently elided by truthiness (the `false` trap). nil and the
           ;; empty string remain elided (no `#`); a non-empty string is
           ;; percent-encoded and appended.
           fragment (assert-fragment! route-id fragment)
           frag (when (and fragment (not= "" fragment))
                  (str "#" (url/url-encode fragment)))]
       (str path-out qs frag)))))

(defn reset-counters!
  "Reset the route-registration counter to zero. Test-time helper so
  reg-index is deterministic across fixture runs."
  []
  (reset! reg-counter 0))
