(ns re-frame.routing.address
  "The shared RouteAddress extraction law for re-frame2 routing (EP-0037 R0b).

  Per Spec 012 §The extraction law and Spec-Schemas §`:rf/route-address`:
  every navigation door extracts and validates the caller-authored
  **address** from the flat public map it accepts through ONE shared law
  over CLOSED key classes, so a policy / edit / DOM value can never leak
  into the address and a misspelled control key can never hide.

  The closed key classes (Spec 012 §The extraction law):

      address keys        #{:to :params :query :fragment}
      raw-URL escape      #{:url}
      policy keys         #{:replace? :scroll :bypass-guards?}
      edit keys           #{:query :query-merge :fragment}
      link behaviour keys #{:on-click}

  (Spec R0 names the policy set `#{:replace? :scroll :bypass-leave?}` and the
  link-behaviour set `#{:prefetch}`; both graduate in later EP-0037 slices —
  the set-valued `:bypass-guards?` is retired for the boolean `:bypass-leave?`
  in R4, and `:prefetch` lands in R3. R0b preserves the CURRENT vocabulary, so
  the constants here spell `:bypass-guards?` / `:on-click` — the extraction
  MECHANISM is what R0 ratifies, not those two key names.)

  Two operations make up the law:

    - `classify` — the always-on STRUCTURAL GATE: it validates the WHOLE
      accepted-key roster BEFORE any extraction (so an unknown / misspelled
      key — namespaced included — rejects rather than being silently
      discarded) and selects the request branch by key PRESENCE (a `:to` /
      `:url` DESTINATION vs an in-place EDIT). Returns nil when well-formed,
      else `{:reason <kw> :keys [...]}`. This is the SAME closure the
      `:rf/route-address` schema declares — `:reason :unknown-keys` is exactly
      the closed-map rejection.
    - `extract-address` — selects EXACTLY the address keys off the flat map,
      so ONLY the extracted address (never the convenient flat request /
      props map) reaches `:rf/route-address` validation.

  Consumed by `:rf.route/navigate` (`navigate.cljc`), `route-url`
  (`registry.cljc`), and `rf/route-link` / `v/route-link` (`link.cljc`) so
  the four public surfaces share ONE definition of what an address is and
  which keys are policy / edit / behaviour.

  Internal namespace; the public facade is `re-frame.routing`."
  (:require [clojure.set :as set]
            [re-frame.identity :as identity]))

;; ---- the closed key classes (Spec 012 §The extraction law) ----------------

(def address-keys
  "The closed RouteAddress key class — `{:to :params :query :fragment}`. Only
  these keys ever reach `:rf/route-address` validation (Spec-Schemas
  §`:rf/route-address`). `:to` names the destination route; facts spell it
  `:route-id`, intent spells it `:to`."
  #{:to :params :query :fragment})

(def raw-url-keys
  "The raw-URL escape destination form — `{:url ...}`. Not a RouteAddress: a
  raw URL IS the address (Spec 012 §The raw-URL escape). Accepted by
  navigation doors that must handle an already-authored URL; NOT by
  `route-url` / prefetch."
  #{:url})

(def policy-keys
  "Navigation POLICY key class — `:replace?` / `:scroll` (and the leave-only
  `:bypass-guards?`). Policy travels beside the address in the same flat map
  but keeps its own shape and is extracted / validated SEPARATELY, so no
  policy key is ever serialisable as a destination (Spec 012 §The extraction
  law). `:bypass-guards?` becomes the boolean `:bypass-leave?` in EP-0037 R4."
  #{:replace? :scroll :bypass-guards?})

(def edit-keys
  "In-place EDIT key class — `:query` / `:query-merge` / `:fragment`. When no
  destination (`:to` / `:url`) is present these PATCH the current location and
  are NEVER validated or stored as a RouteAddress (Spec 012 §The extraction
  law rule 3). Key PRESENCE — not truthiness — discriminates: `:query {}`
  clears the query, `:fragment nil` clears the fragment."
  #{:query :query-merge :fragment})

(def link-behavior-keys
  "`route-link` BEHAVIOUR key class — `:on-click` and `:prefetch` (EP-0037 R3).
  Stripped from the props map before DOM emission alongside the address keys
  (Spec 012 §The extraction law — route-link's open-props exception), so a
  behaviour key never reaches the `<a>` as an unknown attribute. `:on-click` is
  the imperative pre-navigation seam (replaced by the framework's click closure
  on the interactive host, dropped on SSR); `:prefetch :intent` is the warm-mode
  trigger (Spec 012 §Route-plan prefetch)."
  #{:on-click :prefetch})

(def navigate-request-roster
  "The closed set of keys a `:rf.route/navigate` request map may carry — the
  WHOLE accepted-key roster the structural gate validates before extraction:
  address ∪ raw-URL escape ∪ policy ∪ edit. Any other key (namespaced or not)
  is rejected `:reason :unknown-keys`. The runtime's own resume rider
  `:rf.route/enter-attempts` is stripped BEFORE the gate (continuation
  bookkeeping, absent from the published grammar)."
  (set/union address-keys raw-url-keys policy-keys edit-keys))

;; ---- the closed `:rf/route-address` value ---------------------------------

(def RouteAddress
  "The closed `:rf/route-address` Malli schema DATA (Spec-Schemas
  §`:rf/route-address`) — the caller-authored address of one registered named
  destination. Held as data so the routing artefact carries the contract's
  schema shape without a hot-path Malli-artefact dependency; `valid-address?`
  is the pure structural check the extractor applies to the EXTRACTED
  address."
  [:map {:closed true}
   [:to       :keyword]
   [:params   {:optional true} :map]
   [:query    {:optional true} :map]
   [:fragment {:optional true} [:maybe :string]]])

(defn valid-address?
  "Pure structural predicate for the closed `:rf/route-address` shape
  (Spec-Schemas §`:rf/route-address`): a map whose keys are a subset of
  `address-keys`, with a keyword `:to`, map `:params` / `:query` when
  present, and a string-or-nil `:fragment` when present. `:to` is required;
  omitted `:params` / `:query` / `:fragment` are legal (they normalise
  downstream). Applied to the EXTRACTED address (post `extract-address`), so
  a policy / edit / DOM key can never be the reason it fails — the structural
  gate has already rejected those on the whole request."
  [address]
  (boolean
    (and (map? address)
         (empty? (set/difference (set (keys address)) address-keys))
         (keyword? (:to address))
         (or (not (contains? address :params))   (map? (:params address)))
         (or (not (contains? address :query))    (map? (:query address)))
         (or (not (contains? address :fragment)) (nil? (:fragment address))
             (string? (:fragment address))))))

;; ---- extraction -----------------------------------------------------------

(defn extract-address
  "Select EXACTLY the address keys off a flat public map, discarding every
  policy / edit / behaviour / DOM key. This is the guarantee that ONLY the
  extracted address (never the convenient flat request / props map a door
  accepts) reaches `:rf/route-address` validation (Spec 012 §The extraction
  law). Pure `select-keys` over the closed `address-keys` class."
  [flat-map]
  (select-keys flat-map address-keys))

(defn destination?
  "True when `request` names a DESTINATION — a `:to` route id or the `:url`
  raw-URL escape. Presence (not truthiness) discriminates."
  [request]
  (or (contains? request :to) (contains? request :url)))

(defn in-place?
  "True when `request` carries an in-place EDIT key (`:query` / `:query-merge`
  / `:fragment`). Presence (not truthiness) discriminates — `:query {}` and
  `:fragment nil` are valid lone in-place requests."
  [request]
  (boolean (seq (set/intersection (set (keys request)) edit-keys))))

(defn prefetch-address-error
  "The always-on STRUCTURAL GATE for a `[:rf.route/prefetch {address}]` request
  (Spec 012 §Route-plan prefetch). Prefetch accepts ONLY the closed named
  `:rf/route-address` form (`#{:to :params :query :fragment}`) — never a raw
  `:url` escape, a policy / edit key, or an unknown key. Returns nil when the
  request is a well-formed address, else `{:reason <kw> :keys [<offending>]}`
  naming the first violation, which the handler surfaces as
  `:rf.error/prefetch-bad-address` BEFORE any planning runs. Total over the
  address roster; rejects before any resource ensure is dispatched. Distinct
  from a resource *planning* failure (a well-formed address whose plan cannot be
  built — the ordinary `:rf.error/resource-route-plan` with `:plan-cause
  :prefetch`).

  The offending unknown keys are reported in the same total canonical order
  (`identity/canonical-bytes`) the navigate gate uses, so heterogeneous EDN keys
  never trip a `compare`-based `sort`."
  [request]
  (let [sorted-keys (fn [kws] (vec (sort-by identity/canonical-bytes kws)))]
    (cond
      (not (map? request))
      {:reason :request-not-a-map :keys []}

      (seq (set/difference (set (keys request)) address-keys))
      {:reason :unknown-keys
       :keys   (sorted-keys (set/difference (set (keys request)) address-keys))}

      (not (keyword? (:to request)))
      {:reason :missing-to :keys [:to]}

      (not (valid-address? request))
      {:reason :bad-address :keys []}

      :else nil)))

(defn classify
  "The always-on STRUCTURAL GATE for a `:rf.route/navigate` request map
  (Spec 012 §The extraction law, §Validity rules — the always-on structural
  gate). Validates the WHOLE `navigate-request-roster` BEFORE extraction so a
  misspelled / foreign key can never be silently discarded, then selects the
  request branch by key PRESENCE. Returns nil when well-formed, else
  `{:reason <kw> :keys [<offending keys>]}` naming the first violation. Plain
  set logic, total over the roster; rejects BEFORE any guard evaluation so a
  malformed request never consumes a `:can-leave` run. `current` is the
  current route slice (nil before the first navigation).

  The rules (Spec 012 §Validity rules):
  1. `:to` xor `:url`.
  2. `:url` excludes `:params` / `:query` / `:query-merge` — a raw URL IS the
     address. `:url` + `:fragment` is legal.
  3. `:params` requires a destination (`:to` / `:url`) — it names path params
     for a FRESH address, so it is NEVER accepted in-place (changing params is
     a destination): `:reason :params-requires-destination`.
  4. `:query` xor `:query-merge`.
  5. `:query-merge` requires an in-place request (no `:to` / `:url`).
  6. A destination (`:to` / `:url`) OR an in-place change is required;
     PRESENCE discriminates. Empty maps and pure-policy maps reject loud.
  7. An in-place request before any current route exists rejects loud.
  8. Unknown keys reject (namespaced included), reported in a total canonical
     order (`identity/canonical-bytes`) so heterogeneous EDN keys never trip a
     `compare`-based `sort`.

  This is the byte-for-byte gate `:rf.route/navigate` ran inline before R0b;
  moving it here makes it the ONE definition every door's whole-roster
  closure is measured against."
  [request current]
  (let [ks           (set (keys request))
        unknown      (set/difference ks navigate-request-roster)
        dest?        (destination? request)
        edit?        (in-place? request)
        present      (fn [& kws] (vec (filter #(contains? request %) kws)))
        ;; Total, host-symmetric key order over heterogeneous EDN keys — a
        ;; plain `sort` throws when a request carries mixed-kind keys (a
        ;; keyword beside a string / number), so the offending-key report is
        ;; ordered by the shared CEDN-1 identity the whole prism sorts by.
        sorted-keys  (fn [kws] (vec (sort-by identity/canonical-bytes kws)))]
    (cond
      (seq unknown)
      {:reason :unknown-keys :keys (sorted-keys unknown)}

      (and (contains? request :to) (contains? request :url))
      {:reason :to-url-exclusive :keys [:to :url]}

      (and (contains? request :url)
           (some #(contains? request %) [:params :query :query-merge]))
      {:reason :url-excludes-address :keys (present :params :query :query-merge)}

      (and (contains? request :params) (not dest?))
      {:reason :params-requires-destination :keys [:params]}

      (and (contains? request :query) (contains? request :query-merge))
      {:reason :query-exclusive :keys [:query :query-merge]}

      (and (contains? request :query-merge) dest?)
      {:reason :query-merge-in-place-only :keys [:query-merge]}

      (not (or dest? edit?))
      {:reason :no-destination-or-change :keys (sorted-keys ks)}

      (and (not dest?) edit? (nil? current))
      {:reason :no-current-route :keys (sorted-keys ks)}

      :else nil)))
