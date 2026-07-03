(ns re-frame.routing.navigate
  "`:rf.route/navigate` event for re-frame2 routing.

  Per Spec 012 §Navigation is an event. Programmatic navigation entry
  point: accepts a route-id (`[:rf.route/navigate :route/cart]`), a
  target-map (`{:url ...}` form), or the URL-string form. Honours
  :can-leave, :params/:query validation, scroll-strategy resolution,
  :query-retain merge, and the rule-3 no-op short-circuit.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event :rf.route/navigate` call so a
  `:reload` re-wires it on a fresh registrar. Per the rf2-2yabr cohesion
  split: NAVIGATE-EVENT seam."
  (:require [clojure.string :as str]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.routing.can-leave :as can-leave]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.plan :as plan]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.routing.url :as url]
            [re-frame.trace :as trace]))

;; Per Spec 012 §Navigation is an event — the arity contract:
;;   [:rf.route/navigate target]              ;; no path-params, no opts
;;   [:rf.route/navigate target params]       ;; params 2nd, opts absent
;;   [:rf.route/navigate target params opts]  ;; params 2nd, OPTS THIRD
;; The two trailing maps are positionally ambiguous to a reader
;; (rf2-1os1c): the likely mistake is dropping an OPTS-shaped map into
;; the PARAMS slot — `[:rf.route/navigate :route/x {:replace? true}]`
;; reads as "navigate with these path-params" but the author meant opts.
;; `opts-only-keys` names the keys that ONLY ever belong in the opts map.
(def ^:private opts-only-keys
  "Keys the trailing `opts` map recognises (Spec 012 §Navigation is an
  event) that are NOT path-param names. An occurrence of one of these in
  the PARAMS slot — and not as a declared path-param of the target route
  — is the classic params/opts swap and is rejected (rf2-1os1c).
  `:bypass-guards?` (rf2-p69yaz point 8) is the SET-valued rename of the
  former single `:bypass-leave-guard?` opt — it skips `:leave` / `:enter`
  / both guards for one navigation."
  #{:replace? :scroll :fragment :bypass-guards?})

(defn- misplaced-opts-keys
  "Disambiguate the params/opts positional swap (rf2-1os1c). Returns the
  seq of opts-only keys present in the PARAMS slot that the target route
  does NOT declare as path-params — i.e. keys that can only sensibly be
  opts. Empty (falsy via `seq`) when `params` is clean. Route-id form
  only; the `{:url ...}` form has no positional params slot.

  Declared path-param names are read from the compiled pattern's
  `:names` (string capture names), so a route that legitimately captures
  a segment named `:scroll`/`:fragment`/… is never false-flagged."
  [route-meta params]
  (when (and (map? params) (seq params))
    (let [declared (into #{}
                         (map keyword)
                         (:names (:rf.route/compiled route-meta)))]
      (seq (filter (fn [k] (and (contains? opts-only-keys k)
                                (not (contains? declared k))))
                   (keys params))))))

(defn- route-schema-sensitive?
  "True iff the route's `:params` OR `:query` Malli schema declares ANY
  `:sensitive?` slot (rf2-zsm03). The sensitivity decision is made by the
  SAME shared schema-aware seam the rf2-o69h5 class sweep routes every
  off-schemas validation-failure emit site through
  (`:schemas/redact-validation-tags`): routing the route's schema + an
  empty tag map through it stamps `:sensitive? true` exactly when the
  schema is sensitive (the seam's `redact-tags` always stamps when it
  fires). Reaching it through the late-bind hook keeps routing's optional-
  schemas posture — when the schemas artefact is absent the hook is unbound
  and this returns false (no schema to walk → nothing to redact), the same
  fall-through every other off-namespace seam consumer uses."
  [route-meta]
  (boolean
    (when-let [redact (late-bind/get-fn-cached :schemas/redact-validation-tags)]
      (some (fn [slot]
              (when-let [schema (get route-meta slot)]
                (true? (:sensitive? (redact schema {})))))
            [:params :query]))))

(defn- redact-route-error-tags
  "Elide the `:error` slot of a `:rf.route/navigate` schema-validation-failure
  trace when the route's `:params` / `:query` schema declares any
  `:sensitive?` slot (rf2-zsm03; AI/MCP egress + logs threat model).

  The `:error` slot carries `(ex-data ex)` of a `route-url` construction
  throw. For the `:rf.error/route-url-validation` case that ex-data embeds
  `:value` (the raw path-params / query-params the caller supplied) AND
  `:error` (the Malli explainer, which itself reproduces the failing value
  verbatim); for `:rf.error/missing-route-param` it carries `:value` (the
  offending param value). Route params can be document-ids / tokens, so on a
  `:sensitive?`-marked route this slot leaks the same secret material the
  route's `:params` / `:query` schema gates — the SAME class the rf2-o69h5
  sweep closed for the schema-validation hot path, except route-param
  validation is STRUCTURAL (the throw is from `route-url`, not from a
  per-slot Malli walk at this emit point), so the shared
  `redact-validation-tags` seam cannot path-target it. We elide the WHOLE
  `:error` slot to `:rf/redacted` and stamp `:sensitive? true`.

  The structural slots stay intact — `:where` / `:route-id` / `:recovery`
  carry no user value, and the SSR error-projector keys only off the
  `:operation` category (it never echoes `:error` into the public 400), so
  eliding `:error` is invisible to the public-error projection and scoped to
  the diagnostic (Xray / dev-detail / log) egress the threat model targets.
  A route with no `:sensitive?` slot rides `:error` verbatim — the seam is
  precise, not a blanket scrub."
  [tags route-meta]
  (if (and (contains? tags :error)
           (route-schema-sensitive? route-meta))
    (assoc tags :error privacy/redacted-sentinel :sensitive? true)
    tags))

(defn navigate-handler
  "`:rf.route/navigate` event handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
  state, so the handler reads it from the `:rf.db/runtime` coeffect (`rdb`)
  and `commit-navigation` returns a `:rf.db/runtime` effect. The handler
  never touches user app-db. rf2-vcop6y: the nav-token / pending-nav-id are
  minted by RECORDABLE generator-backed allocation cofx — `:rf.route/
  nav-allocation` (commit) and `:rf.route/pending-nav-allocation` (block) —
  so the minted ids are recorded on the causal token and replay re-presents
  them verbatim. The handler stays pure: it publishes the supplied id and
  emits the host high-water bump via fx. Both allocations generate eagerly
  at processing-start; the block-vs-commit branch uses only one (the other
  is recorded-but-unused — harmless, the counters are monotone never-recycle
  allocators). Scroll positions are likewise a host-side cache (rf2-1hncp2)."
  [{frame          :rf.frame/id
    rdb-raw        :rf.db/runtime
    nav-allocation :rf.route/nav-allocation
    pending-nav-allocation :rf.route/pending-nav-allocation
    app-db         :db}
   [_ target params opts :as event-vec]]
    ;; Per Spec 012 §Navigation is an event and §Fragments §Programmatic
    ;; navigation with fragments. Fragment may be supplied in opts
    ;; (`{:fragment "x"}`), on the target-map form (`{:url "/x"
    ;; :fragment "y"}`), or — for URL-string targets — embedded in the
    ;; URL itself; match-url surfaces the latter. Opts/target-map win
    ;; over a URL-embedded fragment.
    ;;
    ;; Per Spec 012 §Navigation tokens — stale-result suppression and
    ;; §The route slice: the slice ALWAYS carries :fragment and a
    ;; freshly-allocated :nav-token, and the runtime emits
    ;; :rf.route.nav-token/allocated as the cascade begins (rf2-d60go) —
    ;; the programmatic path matches the URL-driven path so async loaders
    ;; have a token to thread through stale-suppression.
    (let [;; EP-0002 carried invariant — `:rf.route/navigate` is a cascade
          ;; event, so the cofx carries the frame stamp under `:rf.frame/id`;
          ;; a nil stamp is an invariant failure
          ;; (`:rf.error/no-frame-context`), never a
          ;; synthesised `:rf/default`. Validated once; the trace stamps and
          ;; the leave-guard call below all read this carried value.
          frame (frame/require-frame-stamp!
                  frame :rf.route/navigate
                  {:where 'rf.route/navigate-handler})
          opts (or opts {})
          rdb  (or rdb-raw {})
          ;; The current route slice (durable framework runtime-db fact).
          ;; Read once here so the `:rf.route/self` identity-on-route target
          ;; and the `:query-merge` opt both resolve against it (rf2-ue2d4t).
          current (get-in rdb [:rf.runtime/routing :current])
          {:keys [route-id path-params query-params matched-fragment unmatched-url
                  throw-reason requested-url external-url-target?]}
          (cond
            ;; rf2-ue2d4t — Spec 012 §Navigation is an event §Reserved
            ;; targets §:rf.route/self. `:rf.route/self` is a reserved
            ;; navigate target that means "stay on the CURRENT route,
            ;; change only these query params" — the single most common URL
            ;; operation (search, pagination, tabs). It is a documented,
            ;; semantically-honest exception to enumerable route-id targets:
            ;; identity-on-route. It resolves `:route-id` + `:path-params`
            ;; from the current slice (the path is held fixed — self-nav
            ;; never re-threads path params; the 2nd `params` arg is ignored
            ;; for a self target, so the classic `{}` placeholder is
            ;; correct). Query starts from `(:query opts)` exactly like a
            ;; route-id target — the `:query-merge` opt below then folds the
            ;; caller's deltas into the CURRENT query. Before the first
            ;; navigation there is no current route; `:route-id` is nil and
            ;; the `route-url` build fails closed to a rejected navigation
            ;; (`:rf.error/no-such-route`), the same as any unresolvable
            ;; target.
            (= :rf.route/self target)
            {:route-id     (:route-id current)
             :path-params  (or (:params current) {})
             :query-params (:query opts {})}

            (keyword? target)
            {:route-id     target
             :path-params  (or params {})
             :query-params (:query opts {})}

            ;; rf2-cylse.4 (SECURITY — open-redirect): the `{:url ...}`
            ;; escape hatch is the untrusted-input sink (Spec 012 §Target
            ;; form — URL-string: deep-link handlers, server-redirect
            ;; targets, programmatic redirects from a string). It MUST gate
            ;; through the SAME fail-closed open-redirect classifier the
            ;; `:rf/url-requested` link-click path uses — otherwise every
            ;; rf2-3bv8o bypass vector (`//evil`, `/\evil`, `javascript:`,
            ;; leading-space, `user@host`) is pushed VERBATIM to
            ;; `:rf.nav/push-url`. The classifier is hoisted into
            ;; `re-frame.routing.url` so both sinks share one gate. When the
            ;; URL classes EXTERNAL we short-circuit BEFORE `match-url` (no
            ;; match work, no slice rewrite, no push) — the commit body's
            ;; `external-url-target?` cond arm fails closed identically to
            ;; `url-requested-handler` (`:rf.route/external-url-requested`
            ;; trace + `{}`).
            (and (map? target) (:url target) (url/external-url? (:url target)))
            {:route-id             :rf.route/not-found
             :path-params          {}
             :query-params         {}
             :requested-url        (:url target)
             :external-url-target? true}

            (and (map? target) (:url target))
            ;; rf2-6t1xb: any unexpected throw out of `match-url`, left
            ;; unhandled here, escapes the `:rf.route/navigate` handler and
            ;; CRASHES the event drain. `match-url-fail-closed` catches it
            ;; → NIL match + `:throw-reason` (`:match-error`), so a throwing
            ;; `{:url ...}` target fails closed to `:rf.route/not-found` —
            ;; the same fail-closed path as a bare miss, mirroring the
            ;; URL-driven entry point.
            (let [{:keys [match throw-reason]}
                  (registry/match-url-fail-closed (:url target))]
              ;; Spec 012 §Target form — URL-string (escape hatch): an
              ;; unmatched URL-string resolves to `:rf.route/not-found`
              ;; with the URL in `:params`. `:unmatched-url` flags this
              ;; no-match fallback so the commit below pushes the
              ;; REQUESTED url VERBATIM rather than `route-url` of the
              ;; not-found route — keeping the address bar on the URL the
              ;; caller aimed at, consistent with the URL-driven not-found
              ;; path (`url-change-fx`), which never pushes a fabricated
              ;; `/404` (rf2-0zr2o). nil ⇒ the URL matched a route and the
              ;; canonical `route-url` round-trip drives the push.
              ;;
              ;; rf2-6t1xb: on a throw (or any miss) `:params` carries the
              ;; requested url; a throw also stamps the `:reason`
              ;; discriminator (`:match-error`), uniform with the URL-driven
              ;; not-found slice.
              {:route-id         (or (:route-id match) :rf.route/not-found)
               ;; rf2-u8qe7y: the not-found fallback `:params` shape +
               ;; `:reason` vocabulary is shared with the URL-driven path
               ;; (`plan/not-found-params`). On a throw it carries the cause
               ;; reason; on a bare miss it carries `{:url ...}` (no reason);
               ;; a match supplies its own `:params`.
               :path-params      (if throw-reason
                                   (plan/not-found-params (:url target) throw-reason)
                                   (:params match (plan/not-found-params (:url target) nil)))
               :query-params     (:query match {})
               :matched-fragment (:fragment match)
               :unmatched-url    (when-not (:route-id match) (:url target))
               ;; rf2-2zyvj: thread the match-url throw cause + the
               ;; requested URL out of this `{:url ...}` branch so the
               ;; commit body below can emit `:rf.warning/malformed-url`,
               ;; symmetric with the URL-driven path (url_change.cljc:190).
               ;; Without this the match-error navigate target fails closed
               ;; SILENTLY — invisible to the security dashboards / SSR
               ;; projections.
               :throw-reason     throw-reason
               :requested-url    (:url target)}))
          ;; rf2-zmcq6 / rf2-u8qe7y: normalize an explicit empty-string
          ;; fragment to nil at the navigate boundary via the shared
          ;; `plan/normalize-fragment`. `route-url` (the URL builder) treats
          ;; `:fragment ""` as NO fragment (emits no trailing `#`), but
          ;; `""` is truthy, so without this normalization
          ;; `[:rf.route/navigate :route/docs {} {:fragment ""}]` would
          ;; push `/docs` (no `#`) while writing `:fragment ""` into the
          ;; route slice — a slice/URL divergence vs URL-driven nav to the
          ;; same URL (which yields `:fragment nil`). Collapsing `""` → nil
          ;; here keeps the programmatic and URL-driven paths in agreement:
          ;; the pushed URL and the slice's `:fragment` match regardless of
          ;; how the route was reached.
          fragment    (plan/normalize-fragment
                        (or (:fragment opts)
                            (and (map? target) (:fragment target))
                            matched-fragment))
          route-meta  (registrar/lookup :route route-id)
          ;; rf2-1os1c: catch the params/opts positional swap at the
          ;; event boundary. Route-id form only — the `{:url ...}` form
          ;; has no positional params slot.
          misplaced   (when (keyword? target)
                        (misplaced-opts-keys route-meta params))
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
          ;; Cross-route coercion-class carry (rf2-b3rzz): retained
          ;; values are pulled from the CURRENT route's `:query` slice
          ;; VERBATIM — already coerced to that route's class (a keyword
          ;; via `[:enum ...]`, an int via `:int`, …) — and carried into
          ;; the target unchanged. They are NOT re-coerced against the
          ;; target route's `:query` schema. The target's `:query`
          ;; validator still runs at the call site (below + in
          ;; `route-url`), so a class mismatch (current route typed the
          ;; key `[:enum :a :b]`, target types it `:string`) surfaces as
          ;; a validation failure rather than silently desyncing. The
          ;; contract: authors keep a `:query-retain` key's type
          ;; CONSISTENT across every route that retains it (same trust
          ;; class as the `:query` schema being author-named intent).
          ;; Re-coercion was considered + rejected: retained values are
          ;; runtime values, not URL strings, so re-running
          ;; string→class coercion is ill-typed; verbatim-carry keeps the
          ;; merge a pure `select-keys`. See Spec 012 §Query strings and
          ;; fragments §:query-retain cross-route coercion class.
          retain-keys  (:query-retain route-meta)
          retained     (when (seq retain-keys)
                         (select-keys (get-in rdb [:rf.runtime/routing :current :query])
                                      retain-keys))
          query-params (if (seq retained)
                         (merge retained query-params)
                         query-params)
          ;; rf2-ue2d4t — Spec 012 §Navigation is an event §The :query-merge
          ;; opt. `:query-merge` folds the caller's deltas into the CURRENT
          ;; route's `:query` slice — the "stay here, change these query
          ;; params" primitive (search, pagination, tabs). It REUSES the
          ;; same read-and-merge machinery `:query-retain` uses above:
          ;; the current query is read from the durable slice
          ;; (`[:rf.runtime/routing :current :query]`, i.e. `current`),
          ;; not built afresh. Precedence, low→high: current query → any
          ;; already-resolved `query-params` (`:query` opt + retained
          ;; keys) → the `:query-merge` deltas — so an explicit delta wins.
          ;; A `nil` value REMOVES a key from the result (both the pushed
          ;; URL and the written slice), matching `route-url`'s query
          ;; nil-elision policy (rf2-w3qgc): eliding here keeps the slice
          ;; clean rather than carrying a `{:page nil}` the URL omits. A
          ;; present-but-FALSY value (`false`, `0`, `""`) is a legitimate
          ;; value and survives, same as `route-url`. `:query-merge` is
          ;; target-agnostic — it works with any target — but its natural
          ;; pairing is `:rf.route/self`, where the current route IS the
          ;; target. When `:query-merge` is absent the query resolves
          ;; exactly as before (no current-query base is folded in).
          query-params (if-let [merge-in (:query-merge opts)]
                         (into {}
                               (remove (comp nil? val))
                               (merge (:query current) query-params merge-in))
                         query-params)
          ;; Per Spec 012 §Param validation at the call site: the
          ;; event-boundary path `[:rf.route/navigate ...]` runs the
          ;; route's `:params` / `:query` schema BEFORE transitioning;
          ;; on failure the navigation is REJECTED — the route slice
          ;; at [:rf.runtime/routing :current] does not change, no URL
          ;; is pushed — and the runtime
          ;; emits `:rf.error/schema-validation-failure` (`:where
          ;; :event`). `route-url` raises the structured error on a
          ;; caller bug (`:rf.error/route-url-validation` /
          ;; `:rf.error/missing-route-param` / `:rf.error/no-such-route`);
          ;; we catch it, surface the canonical event-boundary error id,
          ;; and reject (the `::reject` sentinel short-circuits the cond
          ;; below). The reject is total — no slice write, no fallback URL
          ;; push — so a caller bug never desyncs the browser URL or
          ;; strands the slice in an invalid state.
          ;;
          ;; Unmatched URL-string target (rf2-0zr2o): when the `{:url ...}`
          ;; form missed every route, `route-id` fell back to
          ;; `:rf.route/not-found`. We do NOT call `route-url` for that id —
          ;; that would build the not-found route's literal `:path` (`/404`)
          ;; and push it, rewriting the address bar away from the URL the
          ;; caller aimed at (and throwing `:no-such-route` when no
          ;; not-found route is registered). Instead the REQUESTED url is
          ;; pushed verbatim, so the address bar keeps it and the not-found
          ;; view renders — matching the URL-driven not-found path
          ;; (`url-change-fx`), which already keeps the requested URL (it
          ;; changed via link/popstate, so that path emits no push). Per
          ;; Spec 012 §Target form — URL-string.
          url (cond
                ;; rf2-cylse.4: an external-classed `{:url ...}` target
                ;; fails closed below (the `external-url-target?` cond arm)
                ;; — never built into a push URL, so skip `route-url`
                ;; entirely (calling it for `:rf.route/not-found` would
                ;; throw `:no-such-route` when none is registered).
                external-url-target? nil
                unmatched-url        unmatched-url
                :else
                (try (registry/route-url route-id path-params query-params fragment)
                     (catch #?(:clj Throwable :cljs :default) ex
                       ;; rf2-7d30s — stamp the in-flight cascade's `:frame`
                       ;; (destructured from the cofx at the handler top) so
                       ;; this navigate-reject lands in the emitting frame's
                       ;; epoch (epoch capture buffers only frame-tagged
                       ;; traces) AND so the SSR error-projection listener
                       ;; can map it to a 4xx for the correct frame under
                       ;; concurrent server frames — not the single-frame
                       ;; fallback's guess.
                       ;; rf2-zsm03 — the `:error` slot carries the
                       ;; `route-url` throw's ex-data, which on a
                       ;; `:rf.error/route-url-validation` /
                       ;; `:rf.error/missing-route-param` throw embeds the
                       ;; raw route-param value (document-ids / tokens). Elide
                       ;; it when the route's `:params` / `:query` schema is
                       ;; `:sensitive?`, BEFORE the trace crosses the bus /
                       ;; epoch-capture / AI-MCP egress boundary or a log
                       ;; sink — the route-param analogue of the rf2-o69h5
                       ;; class sweep (structural param validation has no
                       ;; per-slot Malli walk here, so the slot is elided
                       ;; whole rather than path-targeted).
                       (trace/emit-error! :rf.error/schema-validation-failure
                                          (-> (cond-> {:where    :event
                                                       :route-id route-id
                                                       :error    (or (ex-data ex)
                                                                     {:message (ex-message ex)})
                                                       :recovery :no-recovery}
                                                frame (assoc :frame frame))
                                              (redact-route-error-tags route-meta)))
                       ::reject)))
          on-match-vec (vec (or (:on-match route-meta) []))
          ;; Spec 012 §Per-route data loading rule 3: a programmatic
          ;; navigation whose target id/params/query/fragment match the
          ;; current slice exactly is a no-op re-navigation — skip the
          ;; `:on-match` re-fire and the nav-token allocation. Mirrors the
          ;; URL-driven path's `identical-nav?` short-circuit so a
          ;; duplicate `[:rf.route/navigate :route/cart]` doesn't re-fetch
          ;; unchanged data.
          identical-nav? (plan/identical-route-target?
                           (get-in rdb [:rf.runtime/routing :current])
                           route-id path-params query-params fragment)]
      (cond
        ;; rf2-1os1c: params/opts positional swap. An opts-only key
        ;; (`:replace?` / `:scroll` / `:fragment` / `:bypass-guards?`)
        ;; sits in the PARAMS slot and is not a declared path-param of
        ;; the target route — the author almost certainly meant to pass
        ;; it as the THIRD `opts` arg. Reject loudly (caller bug; slice
        ;; unchanged, no push) so the swap fails at the event boundary
        ;; rather than navigating with a wrong URL or silently dropping
        ;; the intended opts. Per Spec 012 §Navigation is an event.
        (seq misplaced)
        (do
          (trace/emit-error! :rf.error/navigate-arity-misuse
                             (cond-> {:where    :event
                                      :route-id route-id
                                      :reason   (str "opts-shaped key(s) "
                                                     (str/join ", " (map pr-str misplaced))
                                                     " appeared in the PARAMS slot (2nd arg) of "
                                                     "[:rf.route/navigate " route-id " params opts]. "
                                                     "These belong in the OPTS map (3rd arg): "
                                                     "[:rf.route/navigate " route-id " {} {...opts}]. "
                                                     "Navigation rejected.")
                                      :keys     (vec misplaced)
                                      :recovery :no-recovery}
                               ;; rf2-7d30s — frame-attribute the reject so it
                               ;; lands in the emitting frame's epoch.
                               frame (assoc :frame frame)))
          {})

        ;; Caller-bug schema failure: reject (slice unchanged, no push).
        (= ::reject url)
        {}

        ;; rf2-cylse.4 (SECURITY — open-redirect): the `{:url ...}` target
        ;; classed EXTERNAL by the shared `url/external-url?` gate. Fail
        ;; closed IDENTICALLY to `url-requested-handler`: emit
        ;; `:rf.route/external-url-requested` and return `{}` — no
        ;; `:rf.nav/push-url`, no slice rewrite, no `:rf.route/transitioned`.
        ;; The verbatim-push open-redirect sink is closed across all three
        ;; URL-string nav sinks (the gated `:rf/url-requested`, this
        ;; `:rf.route/navigate {:url}`, and `:rf.route/continue` re-issuing
        ;; either).
        external-url-target?
        (do
          (trace/emit! :rf.event :rf.route/external-url-requested
                       (cond-> {:url requested-url}
                         frame (assoc :frame frame)))
          {})

        ;; The navigation gate runs first (mirrors the URL-driven path,
        ;; where `maybe-block-navigation` precedes `url-change-fx`): it
        ;; evaluates the current route's `:can-leave` THEN the target's
        ;; `:can-enter` (rf2-p69yaz Option A). A blocked guard wins even
        ;; over a rule-3 no-op so the pending-nav protocol stays uniform
        ;; across both entry points.
        :else
        (if-let [blocked (can-leave/maybe-block-navigation
                           rdb frame
                           event-vec url
                           (:bypass-guards? opts)
                           pending-nav-allocation)]
          blocked
          (if identical-nav?
            ;; Spec 012 §Per-route data loading rule 3: nothing relevant
            ;; changed — leave the slice and the standing nav-token as-is;
            ;; emit no allocation, fire no loaders, push no URL.
            {}
            (let [push-fx    (if (:replace? opts)
                               [:rf.nav/replace-url url]
                               [:rf.nav/push-url    url])
                  ;; rf2-u8qe7y: the capture-fx + scroll-fx assembly is
                  ;; shared pre-commit policy — `plan/scroll-plan`. Forward
                  ;; navigation defaults to `:top` (Spec 012 §Scroll
                  ;; restoration); the per-call `:scroll` override rides in
                  ;; `opts`. `:url` keys the (forward → never-hit) `:restore`
                  ;; saved-position lookup, kept for parity with the
                  ;; URL-driven path.
                  {:keys [capture-fx scroll-fx]}
                  (plan/scroll-plan {:rdb              rdb
                                     ;; rf2-1hncp2: saved scroll positions
                                     ;; are a host-side transient cache (not
                                     ;; runtime-db) — read the active frame's
                                     ;; cache and thread it in explicitly so
                                     ;; the planner stays pure.
                                     :scroll-cache     (scroll/frame-scroll-cache frame)
                                     :route-meta       route-meta
                                     :opts             opts
                                     :default-strategy :top
                                     :route-id         route-id
                                     :params           path-params
                                     :query            query-params
                                     :fragment         fragment
                                     :url              url})]
              ;; rf2-2zyvj / rf2-u8qe7y: the fail-closed warning telemetry is
              ;; shared pre-commit policy with the URL-driven path
              ;; (`plan/fallback-telemetry-intents`). An unexpected `match-url`
              ;; THROW on the `{:url ...}` target form (`:match-error`)
              ;; surfaces `:rf.warning/malformed-url`; an unmatched URL-string
              ;; target that resolved to `:rf.route/not-found` with no such
              ;; route registered surfaces `:rf.warning/no-not-found-route`
              ;; (rf2-0zr2o). Both entry points build the SAME intent list,
              ;; so a throwing URL is visible regardless of WHICH of the three
              ;; nav events it arrived on — before the seam the programmatic
              ;; path once failed closed SILENTLY. The navigate path has no
              ;; `:malformed?` branch (`match-url-fail-closed` only THROWS for
              ;; the `{:url}` form — no %-decode scan), so it passes
              ;; `:malformed? false`. `requested-url` equals `unmatched-url`
              ;; when both are present (both derive from `(:url target)`).
              (plan/emit-intents!
                (plan/fallback-telemetry-intents
                  {:throw-reason  throw-reason
                   :malformed?    false
                   :no-not-found? (boolean (and unmatched-url (nil? route-meta)))
                   :url           requested-url
                   :frame         frame}))
              ;; rf2-g8tzb / commit-navigation: nav-token alloc, the
              ;; allocated/activation traces, the slice publish, and the
              ;; fx assembly are the shared commit shape. The programmatic
              ;; path is the only one that drives the browser URL, so it
              ;; passes `push-fx`.
              (routing-events/commit-navigation
                rdb
                {:route-id   route-id
                 :params     path-params
                 :query      query-params
                 :fragment   fragment
                 :transition (if (seq on-match-vec) :loading :idle)}
                on-match-vec
                {:prev-id        (get-in rdb [:rf.runtime/routing :current :route-id])
                 ;; rf2-vdyrls: the prior route's nav-token — the second half
                 ;; of the previous route owner `[:route prev-id prev-nav-token]`
                 ;; the resources plan releases on route leave (Spec 016 §Route
                 ;; integration).
                 :prev-nav-token (get-in rdb [:rf.runtime/routing :current :nav-token])
                 :capture-fx   capture-fx
                 :scroll-fx    scroll-fx
                 :push-fx      push-fx
                 ;; rf2-vcop6y: the RECORDABLE nav-token allocation delivered
                 ;; by the `:rf.route/nav-allocation` cofx — `commit-navigation`
                 ;; publishes its `:token` (recorded + replay-stable) + rides
                 ;; `:counter` on the bump fx.
                 :nav-allocation nav-allocation
                 ;; rf2-dbmj6x: the in-flight cascade's carried frame stamp
                 ;; (validated at the handler top). `commit-navigation` stamps
                 ;; it on the nav-token-allocated + activated/deactivated
                 ;; lifecycle traces so they enter the emitting frame's epoch
                 ;; and obey the frame trace-disable gate, consistent with the
                 ;; route-miss diagnostics this path already frame-tags above.
                 :frame        frame
                 ;; EP-0016 D3 slice 3: the route-entry app-db, threaded into
                 ;; the `:routing/on-route-entry` hook so a `{:from-db …}`
                 ;; route-resource scope resolves db-derived viewer identity.
                 :app-db       app-db})))))))
