(ns re-frame.routing.plan
  "Pure navigation-planning seam shared by the programmatic
  (`:rf.route/navigate`) and URL-driven (`:rf.route/transitioned` /
  `:rf.route/handle-url-change`) entry points.

  Per the rf2-u8qe7y best-practice review: before this seam each entry
  point re-encoded the SAME pre-commit policy — fragment normalisation,
  the `:rf.route/not-found` fallback shape + `:reason` vocabulary, the
  identical-/fragment-only/no-op classification, the telemetry the
  hostile-URL / missing-not-found-route streams must surface, and the
  scroll-fx args — and only `commit-navigation` (the final commit
  assembler in `re-frame.routing.events`) was actually shared. The
  invariant is \"one navigation policy with entry-point-specific
  inputs\", so the shared pre-commit policy lives here, in ONE place,
  rather than as two parallel policy bodies whose parity depends on
  maintainers remembering to patch both and adding a regression test
  after drift is discovered.

  Everything here is PURE (or returns telemetry as DATA). The two entry
  points resolve their target differently — the programmatic path has
  three target forms (`:route/id`, `{:url ...}`, URL-string), open-redirect
  fail-closed gating, params/opts swap detection, `:query-retain` merge,
  and `route-url` validation/reject; the URL-driven path just matches a
  URL string and carries a default scroll strategy (`:top` forward,
  `:restore` for popstate/SSR). Those input-resolution differences stay
  in `navigate.cljc` / `url_change.cljc`. Once the slice fields and the
  classification flags are resolved, BOTH feed the shared helpers below
  and then `commit-navigation`.

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split convention: NAVIGATION-PLAN seam."
  (:require [re-frame.routing.egress :as egress]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.scroll :as scroll]
            [re-frame.trace :as trace]))

;; ---- fragment normalisation ----------------------------------------------

(defn normalize-fragment
  "Collapse an explicit empty-string fragment to nil (rf2-zmcq6).

  `route-url` (the URL builder) treats `:fragment \"\"` as NO fragment
  (emits no trailing `#`), but `\"\"` is truthy, so an un-normalised
  empty-string fragment would push `/docs` (no `#`) while writing
  `:fragment \"\"` into the route slice — a slice/URL divergence vs
  URL-driven nav to the same URL (which yields `:fragment nil`).
  Collapsing `\"\"` → nil keeps the programmatic and URL-driven paths in
  agreement: the pushed URL and the slice's `:fragment` match regardless
  of how the route was reached. A nil / non-empty fragment passes through
  unchanged."
  [fragment]
  (when-not (= "" fragment) fragment))

;; ---- not-found fallback shape --------------------------------------------

;; Per Spec 012 §Route-not-found + §Routing failure semantics: every
;; not-found fallback writes `{:url url}` into the slice's `:params`,
;; discriminated by an optional `:reason` for per-route error UIs and SSR
;; projections. The reason vocabulary is SHARED across both entry points
;; (rf2-4ic0f / rf2-6t1xb): an unexpected `match-url` throw stamps
;; `:match-error`; a malformed percent-encoding stamps `:malformed-url`; a
;; schema-validation miss stamps `:validation`; a bare route-miss carries
;; no reason. Encoding the shape once keeps the two paths' fallback params
;; byte-for-byte identical.

(defn not-found-params
  "Build the `:rf.route/not-found` slice `:params` map for `url` under
  the optional `reason` discriminator. `reason` is one of the throw-cause
  keyword `:match-error`, `:malformed-url`, `:validation`, or nil (bare
  miss). Returns `{:url url}` (bare miss) or `{:url url :reason reason}`."
  [url reason]
  (cond-> {:url url}
    reason (assoc :reason reason)))

;; ---- classification ------------------------------------------------------

;; `identical-route-target?` is the complete-no-op predicate (Spec 012
;; §Per-route data loading rule 3) — re-exported here so the planner
;; surface carries the full classification vocabulary both entry points
;; key on. The implementation lives in `re-frame.routing.events` (it is
;; also consumed by `commit-navigation`'s neighbours); the alias avoids a
;; second definition while letting planner callers + planner tests reach
;; it through the cohesive plan seam.
(def identical-route-target? routing-events/identical-route-target?)

(defn fragment-only?
  "Spec 012 §Fragments rules 3-4: true when the prospective target shares
  `:route-id`/`:params`/`:query` with `prev` (the current slice) but its
  `fragment` differs — a same-page anchor change that must update
  `:fragment` WITHOUT allocating a fresh nav-token or re-firing
  `:on-match`. Mutually exclusive with `identical-route-target?` (which
  requires the fragment to be equal too). `prev` is the current slice (or
  nil before the first nav, in which case there is nothing to be
  fragment-only against)."
  [prev id params query fragment]
  (boolean
    (and prev
         (= (:route-id prev) id)
         (= (:params prev)   params)
         (= (:query prev)    query)
         (not= (:fragment prev) fragment))))

;; ---- scroll plan ---------------------------------------------------------

;; Both entry points assemble the SAME scroll fx pair before a commit:
;;   - `capture-fx` — `[:rf.nav/capture-scroll {:url <leaving-url>}]`,
;;     keyed off the CURRENT slice so a later `:restore` finds the
;;     position the user is leaving;
;;   - `scroll-fx`  — `[:rf.nav/scroll {:strategy :from :to :saved-pos
;;     :fragment}]`, resolved from the route's `:scroll` meta + opts +
;;     the entry-point default strategy.
;; The only per-entry-point variation is the default strategy (`:top`
;; forward, `:restore` for popstate/SSR) and whether opts carries a
;; per-call `:scroll` override (programmatic only). Holding the assembly
;; here keeps the `:from`/`:to`/`:saved-pos`/`:fragment` shape and the
;; `:restore`-only saved-pos lookup from drifting between the two callers.

(defn scroll-plan
  "Pure: build the `{:capture-fx :scroll-fx}` pair for a navigation.
  `rdb` is the routing runtime-db value (carries the current slice — the
  durable `:current` route fact). `scroll-cache` is the frame's host-side
  transient scroll-position cache map (`{:positions :order}` from
  `re-frame.routing.scroll/frame-scroll-cache`, threaded in EXPLICITLY by
  the caller so this helper stays pure and never reaches the host atom —
  rf2-1hncp2). `route-meta` is the TARGET route's metadata (for its
  `:scroll` strategy). `opts` is the navigate opts map (or nil on the
  URL-driven path — no per-call `:scroll` override). `default-strategy` is
  `:top` (forward) or `:restore` (popstate/SSR). `url` keys the `:restore`
  saved-position lookup. Returns a map; either fx may be nil (no current
  route to capture from; `:scroll false` suppression)."
  [{:keys [rdb scroll-cache route-meta opts default-strategy route-id params query fragment url]}]
  (let [strategy (scroll/resolve-scroll-strategy route-meta opts default-strategy)]
    {:capture-fx (scroll/capture-scroll-fx-entry rdb)
     :scroll-fx  (scroll/scroll-fx-entry
                   {:strategy  strategy
                    :from      (scroll/route-descriptor
                                 (get-in rdb [:rf.runtime/routing :current]))
                    :to        (scroll/route-descriptor* route-id params query)
                    :saved-pos (when (= :restore strategy)
                                 (scroll/lookup-scroll-position scroll-cache url))
                    :fragment  fragment})}))

;; ---- pre-commit telemetry intents ----------------------------------------

;; Per Spec 009 + Spec 012: a fail-closed navigation must surface the same
;; structured telemetry regardless of WHICH entry point the hostile /
;; unmatched URL arrived on. Before this seam each path emitted the
;; `:rf.warning/malformed-url` + `:rf.warning/no-not-found-route` pair
;; inline with subtly parallel `cond->` bodies, and the programmatic path
;; once SHIPPED missing the URL-driven path's hostile-URL warning
;; (navigate.cljc:426-437 documents the drift). Returning the intents as
;; DATA — a vector of `[kind level-or-op op-or-tags tags?]` triples — and
;; running them through one `emit-intents!` driver makes the parity
;; structural: both callers build the same intent list from the same
;; inputs, so they cannot drift without changing this one fn.
;;
;; `:rf.error/no-such-handler` is NOT included here: the URL-driven path
;; emits it (every URL-driven fallback is a handler-miss), the
;; programmatic path does not (a `{:url ...}` miss is the documented
;; not-found escape hatch, not a handler-resolution error). It stays at
;; the URL-driven call site as an entry-point-specific intent.

(defn fallback-telemetry-intents
  "Pure: the telemetry both entry points emit on a fail-closed / not-found
  navigation, returned as a vector of intents for `emit-intents!`. Inputs:

    :throw-reason — non-nil when `match-url` threw an unexpected error
                    (`:match-error`); emits `:rf.warning/malformed-url`
                    carrying `{:url :reason}`.
    :malformed?   — the URL's percent-encoding failed to decode; emits
                    `:rf.warning/malformed-url` carrying `{:url}` (rf2-4ic0f).
                    Mutually exclusive with `:throw-reason` (a throw
                    pre-empts the malformed scan).
    :no-not-found? — the fallback resolved to `:rf.route/not-found` AND no
                    such route is registered; emits
                    `:rf.warning/no-not-found-route` carrying `{:url}`
                    (rf2-0zr2o / Spec 012 §Route-not-found §3).
    :url          — the requested URL.
    :frame        — the active frame; threaded onto every tag map when
                    present so the trace lands in the emitting frame's epoch
                    / Xray and the SSR error-projector can attribute it
                    per-frame (rf2-7d30s / rf2-w3qgc).

  Each intent is `[:emit level op tags]`."
  [{:keys [throw-reason malformed? no-not-found? url frame]}]
  (let [;; EP-0015 (rf2-n1f4rh): these are route-MISS / malformed-URL
        ;; diagnostics — there is NO matched route and therefore NO
        ;; `:params` / `:query` schema to consult, yet the requested URL is
        ;; the class most likely to carry secret material (`?token=…`,
        ;; `?code=…`, `#access_token=…`). Redact the query/fragment carrier
        ;; VALUES (keeping the structured path) BEFORE the warning crosses
        ;; the trace bus / Xray / MCP / log / SSR-projection egress boundary.
        ;; Default-on scrub (no schema to target) — the no-schema analogue of
        ;; `navigate/redact-route-error-tags`.
        tag (fn [base] (-> base
                           (cond-> frame (assoc :frame frame))
                           egress/redact-url-tag))]
    (cond-> []
      throw-reason
      (conj [:emit :warning :rf.warning/malformed-url
             (tag {:url url :reason throw-reason})])

      malformed?
      (conj [:emit :warning :rf.warning/malformed-url
             (tag {:url url})])

      no-not-found?
      (conj [:emit :warning :rf.warning/no-not-found-route
             (tag {:url url})]))))

(defn emit-intents!
  "Impure driver: run a vector of telemetry intents (from
  `fallback-telemetry-intents` or assembled at a call site) through the
  trace bus. `[:emit level op tags]` → `trace/emit!`;
  `[:emit-error op tags]` → `trace/emit-error!`. Returns nil."
  [intents]
  (doseq [intent intents]
    (case (first intent)
      :emit       (let [[_ level op tags] intent] (trace/emit! level op tags))
      :emit-error (let [[_ op tags] intent]       (trace/emit-error! op tags))))
  nil)
