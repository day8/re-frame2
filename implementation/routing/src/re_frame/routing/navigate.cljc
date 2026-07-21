(ns re-frame.routing.navigate
  "`:rf.route/navigate` event for re-frame2 routing.

  Per Spec 012 §Navigation is an event. Programmatic navigation entry
  point: ONE flat request map — `[:rf.route/navigate {request}]`. Address
  keys `:to` / `:url` / `:params` / `:query` / `:fragment`; policy keys
  `:replace?` / `:scroll` / `:bypass-guards?`; the in-place edit key
  `:query-merge`. A destination request (`:to` / `:url`) builds a FRESH
  address; an in-place request (neither) PATCHES the current location.
  Honours :can-leave and :can-enter, :params/:query validation, scroll
  resolution, :query-retain merge, and the rule-3 no-op short-circuit.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event :rf.route/navigate` call so a
  `:reload` re-wires it on a fresh registrar."
  (:require [clojure.set :as set]
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

(def ^:private request-roster
  "The closed set of keys a `:rf.route/navigate` request map may carry:
  address (`:to` `:url` `:params` `:query` `:fragment`), policy
  (`:replace?` `:scroll` `:bypass-guards?`), and the in-place edit key
  `:query-merge`. Any other key -- namespaced or not -- is rejected by the
  structural gate (`:reason :unknown-keys`). The runtime's own resume rider
  `:rf.route/enter-attempts` is stripped BEFORE the gate (it is
  continuation bookkeeping, absent from the published grammar)."
  #{:to :url :params :query :fragment :replace? :scroll :bypass-guards? :query-merge})

(def ^:private in-place-change-keys
  "The request keys whose PRESENCE marks an in-place change (a patch of the
  current location) when no destination (`:to` / `:url`) is supplied. Key
  PRESENCE -- not truthiness -- discriminates: `:query {}` clears the query,
  `:fragment nil` clears the fragment; both are valid lone in-place
  requests."
  #{:query :query-merge :fragment})

(defn- validate-request
  "The always-on structural gate for a `:rf.route/navigate` request map
  (Spec 012 Enforcement). Returns nil when the request is well-formed,
  else `{:reason <kw> :keys [<offending keys>]}` naming the first
  violation. Plain set logic, total over `request-roster`; rejects BEFORE
  any guard evaluation so a malformed request never consumes a `:can-leave`
  run. `current` is the current route slice (nil before the first
  navigation).

  The rules (Spec 012 Validity rules):
  1. `:to` xor `:url`.
  2. `:url` excludes `:params` / `:query` / `:query-merge` -- a raw URL IS
     the address. `:url` + `:fragment` is legal.
  3. `:query` xor `:query-merge`.
  4. `:query-merge` requires an in-place request (no `:to` / `:url`).
  5. A destination (`:to` / `:url`) OR an in-place change is required;
     PRESENCE discriminates. Empty maps and pure-policy maps reject loud.
  6. An in-place request before any current route exists rejects loud.
  7. Unknown keys reject (namespaced included)."
  [request current]
  (let [ks           (set (keys request))
        unknown      (set/difference ks request-roster)
        destination? (or (contains? request :to) (contains? request :url))
        in-place?    (boolean (seq (set/intersection ks in-place-change-keys)))
        present      (fn [& kws] (vec (filter #(contains? request %) kws)))]
    (cond
      (seq unknown)
      {:reason :unknown-keys :keys (vec (sort unknown))}

      (and (contains? request :to) (contains? request :url))
      {:reason :to-url-exclusive :keys [:to :url]}

      (and (contains? request :url)
           (some #(contains? request %) [:params :query :query-merge]))
      {:reason :url-excludes-address :keys (present :params :query :query-merge)}

      (and (contains? request :query) (contains? request :query-merge))
      {:reason :query-exclusive :keys [:query :query-merge]}

      (and (contains? request :query-merge) destination?)
      {:reason :query-merge-in-place-only :keys [:query-merge]}

      (not (or destination? in-place?))
      {:reason :no-destination-or-change :keys (vec (sort ks))}

      (and (not destination?) in-place? (nil? current))
      {:reason :no-current-route :keys (vec (sort ks))}

      :else nil)))

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

(defn- fragment-only-nav-fx
  "Spec 012 §Fragments rules 3-4 / §Programmatic navigation with fragments
  (rf2-k4exp1): a resolved `:rf.route/navigate` whose target differs from the
  CURRENT slice ONLY in its `#fragment` (same `:route-id`/`:params`/`:query`)
  is an in-page anchor change, not a route (re)activation. Mirrors the
  URL-driven `url_change.cljc` fragment-only short-circuit so the SAME logical
  operation behaves identically whichever door — programmatic
  `:rf.route/navigate`, forward-nav `:rf.route/transitioned`, or popstate
  `:rf.route/handle-url-change` — it enters.

  Classified only AFTER target resolution / fragment-normalisation / query
  shaping / URL build / validation AND after the `:can-leave`/`:can-enter`
  gate passes (both upstream in `navigate-handler`). On this branch the
  handler:

    1. assocs ONLY `[:rf.runtime/routing :current :fragment]` — `:route-id`,
       `:params`, `:query`, `:transition`, `:error`, `:nav-token`, and every
       routing sibling (pending-nav, resource-blocking bookkeeping) are
       preserved BYTE-FOR-BYTE. It does NOT call `commit-navigation`: no
       `:on-match` re-fire, no settle event, no resource ensure/release plan,
       and no nav-token counter bump — the standing token stays current, so an
       already-running loader for the unchanged route remains eligible to
       complete.
    2. emits exactly one `:rf.route/fragment-changed` op carrying
       `{:route-id :prev-fragment :next-fragment :frame}` — and NO
       `:rf.route.nav-token/allocated`, NO route activated/deactivated pair,
       NO resource route-plan trace.
    3. drives programmatic history + scroll via REGISTERED effects, in order:
       capture the LEAVING url's scroll position, then push (default) /
       replace (`{:replace? true}`) the new URL via `:rf.nav/push-url` /
       `:rf.nav/replace-url` (never `window.location.hash`), then the resolved
       `:rf.nav/scroll` unless `:scroll false` suppresses it (via
       `plan/scroll-plan`). Default `:top` scrolls to the new fragment (or top
       when the fragment is cleared/missing); `:restore`/`:preserve`/map-form
       retain today's meanings. `pushState`/`replaceState` do NOT scroll to a
       fragment natively, so `:rf.nav/scroll` IS required — this makes NO focus
       / `:target` pseudo-class parity claim (a separate a11y decision).

  State-first: the runtime-db `:fragment` write commits before the ordered
  history/scroll effects, exactly like every other route effect; a rejected
  history projection surfaces the existing `:rf.fx/push-url-failed` /
  `:rf.fx/replace-url-failed` diagnostic and leaves the slice + trace
  committed. NB this must NOT copy `url_change.cljc`'s `fragment-only-fx`
  verbatim — that helper carries scroll CAPTURE only; the programmatic path
  also drives the URL and the resolved scroll."
  [{:keys [rdb current fragment url opts route-meta route-id params query frame]}]
  (trace/emit! :rf.event :rf.route/fragment-changed
               {:route-id      (:route-id current)
                :prev-fragment (:fragment current)
                :next-fragment fragment
                :frame         frame})
  (let [push-fx (if (:replace? opts)
                  [:rf.nav/replace-url url]
                  [:rf.nav/push-url    url])
        {:keys [capture-fx scroll-fx]}
        (plan/scroll-plan {:rdb              rdb
                           ;; rf2-1hncp2: saved scroll positions are a
                           ;; host-side transient cache — thread the active
                           ;; frame's cache in explicitly so the planner stays
                           ;; pure (same shape the full commit branch passes).
                           :scroll-cache     (scroll/frame-scroll-cache frame)
                           :route-meta       route-meta
                           :opts             opts
                           :default-strategy :top
                           :route-id         route-id
                           :params           params
                           :query            query
                           :fragment         fragment
                           :url              url})]
    ;; EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
    ;; state — write ONLY the `:fragment` field of `:current`; every other
    ;; slice field and routing sibling is preserved.
    {:rf.db/runtime (assoc-in rdb [:rf.runtime/routing :current :fragment] fragment)
     :fx (vec (concat (when capture-fx [capture-fx])
                      [push-fx]
                      (when scroll-fx [scroll-fx])))}))

(defn navigate-handler
  "`:rf.route/navigate` event handler. Registered by the facade so a
  `:reload` re-wires it on a fresh registrar.

  Per Spec 012 the event carries ONE flat request map. The handler:
    1. strips the internal resume rider (`:rf.route/enter-attempts`) BEFORE
       the always-on structural gate (`validate-request`);
    2. rejects a malformed request with `:rf.error/navigate-bad-request`
       BEFORE any guard runs (slice unchanged, no push);
    3. resolves a DESTINATION request (`:to` / `:url`) into a FRESH address,
       or an IN-PLACE request into a PATCH of the current location;
    4. runs the `:can-leave` / `:can-enter` gate, then the rule-3 no-op /
       fragment-only short-circuits, then commits.

  EP-0001 (rf2-vzld77): the route slice is durable framework runtime-db
  state, so the handler reads it from the `:rf.db/runtime` coeffect (`rdb`)
  and `commit-navigation` returns a `:rf.db/runtime` effect. The handler
  never touches user app-db. rf2-vcop6y: the nav-token / pending-nav-id are
  minted by RECORDABLE generator-backed allocation cofx -- `:rf.route/
  nav-allocation` (commit) and `:rf.route/pending-nav-allocation` (block) --
  so the minted ids are recorded on the causal token and replay re-presents
  them verbatim. Scroll positions are likewise a host-side cache (rf2-1hncp2)."
  [{frame          :rf.frame/id
    rdb-raw        :rf.db/runtime
    nav-allocation :rf.route/nav-allocation
    pending-nav-allocation :rf.route/pending-nav-allocation
    app-db         :db}
   [_ request0 :as event-vec]]
    (let [;; EP-0002 carried invariant -- `:rf.route/navigate` is a cascade
          ;; event, so the cofx carries the frame stamp under `:rf.frame/id`;
          ;; a nil stamp is an invariant failure (`:rf.error/no-frame-context`),
          ;; never a synthesised `:rf/default`.
          frame (frame/require-frame-stamp!
                  frame :rf.route/navigate
                  {:where 'rf.route/navigate-handler})
          rdb  (or rdb-raw {})
          ;; The current route slice (durable framework runtime-db fact). Read
          ;; once so the in-place patch, `:query-retain`, and `:query-merge`
          ;; all resolve against it.
          current (get-in rdb [:rf.runtime/routing :current])
          ;; Strip the internal resume rider BEFORE the gate: `:rf.route/
          ;; enter-attempts` is threaded by `:rf.route/continue`
          ;; (can_leave.cljc) and is continuation bookkeeping, absent from the
          ;; published grammar. It still rides `event-vec` so the guard gate's
          ;; `loop-count` can read it on a resume.
          request (dissoc (or request0 {}) :rf.route/enter-attempts)
          bad     (validate-request request current)]
      (if bad
        ;; Spec 012 §Error surfaces #1: the always-on structural gate rejected
        ;; the request. `:rf.error/navigate-bad-request` is a DISTINCT channel
        ;; from `:rf.error/schema-validation-failure` (that category is the
        ;; dev-only, schemas-artefact-gated validation channel; this gate is
        ;; always-on and production-surviving). Slice unchanged, no push.
        ;; rf2-7d30s: frame-attribute the reject so it lands in the emitting
        ;; frame's epoch.
        (do
          (trace/emit-error! :rf.error/navigate-bad-request
                             (cond-> {:where    :event
                                      :reason   (:reason bad)
                                      :keys     (:keys bad)
                                      :recovery :no-recovery}
                               frame (assoc :frame frame)))
          {})
        (let [destination? (or (contains? request :to) (contains? request :url))
              url-target   (:url request)
              {:keys [route-id path-params query-params matched-fragment unmatched-url
                      throw-reason requested-url external-url-target?]}
              (cond
                ;; rf2-cylse.4 (SECURITY -- open-redirect): an external-classed
                ;; `:url` target fails closed BEFORE match-url, identical to
                ;; `url-requested-handler` (the shared `url/external-url?` gate).
                (and (some? url-target) (url/external-url? url-target))
                {:route-id             :rf.route/not-found
                 :path-params          {}
                 :query-params         {}
                 :requested-url        url-target
                 :external-url-target? true}

                ;; URL-string target (escape hatch). `match-url-fail-closed`
                ;; catches any throw -> nil match + `:throw-reason`, so a
                ;; throwing `:url` fails closed to `:rf.route/not-found` (the
                ;; same fail-closed path as a bare miss). An unmatched URL keeps
                ;; the REQUESTED url on the address bar (rf2-0zr2o).
                (some? url-target)
                (let [{:keys [match throw-reason]}
                      (registry/match-url-fail-closed url-target)]
                  {:route-id         (or (:route-id match) :rf.route/not-found)
                   :path-params      (if throw-reason
                                       (plan/not-found-params url-target throw-reason)
                                       (:params match (plan/not-found-params url-target nil)))
                   :query-params     (:query match {})
                   :matched-fragment (:fragment match)
                   :unmatched-url    (when-not (:route-id match) url-target)
                   :throw-reason     throw-reason
                   :requested-url    url-target})

                ;; Route-id destination -- build a FRESH address (omitted
                ;; :query/:fragment empty, exactly as today).
                (contains? request :to)
                {:route-id     (:to request)
                 :path-params  (:params request {})
                 :query-params (:query request {})}

                ;; In-place request -- PATCH the current location. Route + path
                ;; params are carried from the current slice and never accepted
                ;; in-place (changing params is a destination). Query base:
                ;; `:query` replaces wholesale ({} clears); `:query-merge` folds
                ;; over the current query (below); otherwise the current query is
                ;; carried unchanged.
                :else
                {:route-id     (:route-id current)
                 :path-params  (or (:params current) {})
                 :query-params (cond
                                 (contains? request :query)       (:query request)
                                 (contains? request :query-merge) (or (:query current) {})
                                 :else                             (or (:query current) {}))})
              ;; Fragment: an explicit `:fragment` (present, even nil) wins --
              ;; the request's fragment overrides a URL-embedded one, and
              ;; `:fragment nil` clears. Otherwise a DESTINATION uses the
              ;; URL-embedded fragment (nil for a route-id -> fresh), and an
              ;; IN-PLACE request carries the current fragment. Normalised so an
              ;; empty-string fragment collapses to nil (slice/URL agreement).
              fragment (plan/normalize-fragment
                         (cond
                           (contains? request :fragment) (:fragment request)
                           destination?                  matched-fragment
                           :else                         (:fragment current)))
              ;; rf2-0zsvw: an explicit `:fragment` on an UNMATCHED raw-URL
              ;; navigate overrides (or, when nil/empty, clears) the fragment
              ;; embedded in the raw URL. The raw `unmatched-url` is otherwise
              ;; pushed VERBATIM, so the address bar kept `#old` while the slice
              ;; carried `#new` -- address bar, slice, and guard/pending target
              ;; disagreed. Rebuild ONE effective requested URL (the raw
              ;; path/query with its `#fragment` replaced by the resolved
              ;; `fragment`, percent-encoded exactly as `route-url` emits) and
              ;; thread it through the not-found `:params`, the guards, the
              ;; history push, and the fallback telemetry. Absent an explicit
              ;; `:fragment` the raw URL rides verbatim -- existing not-found
              ;; behaviour (embedded fragment kept) is unchanged.
              override-unmatched-fragment? (boolean (and unmatched-url
                                                          (contains? request :fragment)))
              unmatched-url (if override-unmatched-fragment?
                              (let [hash-idx (.indexOf #?(:clj  ^String unmatched-url
                                                          :cljs ^string unmatched-url)
                                                       "#")
                                    base     (if (neg? hash-idx)
                                               unmatched-url
                                               (subs unmatched-url 0 hash-idx))]
                                (if (some? fragment)
                                  (str base "#" (url/url-encode fragment))
                                  base))
                              unmatched-url)
              requested-url (if override-unmatched-fragment? unmatched-url requested-url)
              path-params   (if override-unmatched-fragment?
                              (assoc path-params :url unmatched-url)
                              path-params)
              route-meta (registrar/lookup :route route-id)
              ;; `:query-retain` on the resolved route carries retain keys from
              ;; the current query into a FRESH DESTINATION address (rf2-u8t3s);
              ;; caller values win. It does NOT apply to an in-place request --
              ;; the current query is already the base there, and a wholesale
              ;; `:query {}` must be able to clear it.
              retain-keys (:query-retain route-meta)
              retained    (when (and destination? (seq retain-keys))
                            (select-keys (get-in rdb [:rf.runtime/routing :current :query])
                                         retain-keys))
              query-params (if (seq retained) (merge retained query-params) query-params)
              ;; `:query-merge` (in-place only, gated above) folds the caller's
              ;; deltas over the current query; a nil value removes a key. Strip
              ;; nil-valued query keys on EVERY branch so the written slice
              ;; matches the pushed URL (route-url elides nils, rf2-gxq7z1).
              query-params (let [merged (if-let [merge-in (:query-merge request)]
                                          (merge (:query current) query-params merge-in)
                                          query-params)]
                             (into {} (remove (comp nil? val)) merged))
              ;; Build the push URL. An external / unmatched target skips
              ;; `route-url`; a `route-url` throw is a caller bug -> emit
              ;; `:rf.error/schema-validation-failure` (`:where :event`) and
              ;; reject via the `::reject` sentinel (slice unchanged, no push).
              url (cond
                    external-url-target? nil
                    unmatched-url        unmatched-url
                    :else
                    (try (registry/route-url {:to       route-id
                                              :params   path-params
                                              :query    query-params
                                              :fragment fragment})
                         (catch #?(:clj Throwable :cljs :default) ex
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
              ;; Rule-3 no-op: an exactly-identical target skips the on-match
              ;; re-fire + nav-token allocation. The fragment-only sibling
              ;; (same route/params/query, different #fragment) is an anchor
              ;; change routed through the shared `plan/fragment-only?`.
              identical-nav? (plan/identical-route-target?
                               current route-id path-params query-params fragment)
              fragment-only? (and (not unmatched-url)
                                  (plan/fragment-only?
                                    current route-id path-params query-params fragment))]
          (cond
            ;; Caller-bug schema failure on `route-url`: reject (slice
            ;; unchanged, no push).
            (= ::reject url)
            {}

            ;; External `:url` target: fail closed identical to
            ;; `url-requested-handler` -- emit `:rf.route/external-url-requested`
            ;; and return `{}` (no push, no slice rewrite).
            external-url-target?
            (do
              (trace/emit! :rf.event :rf.route/external-url-requested
                           (cond-> {:url requested-url}
                             frame (assoc :frame frame)))
              {})

            ;; The navigation gate runs first (mirrors the URL-driven path):
            ;; current route's `:can-leave` THEN target's `:can-enter`. A
            ;; blocked guard wins even over a rule-3 no-op.
            :else
            (if-let [blocked (can-leave/maybe-block-navigation
                               rdb frame
                               event-vec url
                               (:bypass-guards? request)
                               pending-nav-allocation)]
              blocked
              (cond
                identical-nav?
                {}

                ;; Same route-id/params/query, only the #fragment differs --
                ;; update `:fragment`, emit `:rf.route/fragment-changed`, and
                ;; drive history + scroll via effects. No `commit-navigation`:
                ;; no nav-token bump, no `:on-match` re-fire, no resource re-plan.
                fragment-only?
                (fragment-only-nav-fx {:rdb        rdb
                                       :current    current
                                       :fragment   fragment
                                       :url        url
                                       :opts       request
                                       :route-meta route-meta
                                       :route-id   route-id
                                       :params     path-params
                                       :query      query-params
                                       :frame      frame})

                :else
                (let [push-fx    (if (:replace? request)
                                   [:rf.nav/replace-url url]
                                   [:rf.nav/push-url    url])
                      {:keys [capture-fx scroll-fx]}
                      (plan/scroll-plan {:rdb              rdb
                                         :scroll-cache     (scroll/frame-scroll-cache frame)
                                         :route-meta       route-meta
                                         :opts             request
                                         :default-strategy :top
                                         :route-id         route-id
                                         :params           path-params
                                         :query            query-params
                                         :fragment         fragment
                                         :url              url})]
                  ;; Shared fail-closed telemetry (rf2-2zyvj / rf2-u8qe7y): a
                  ;; match-url throw on a `:url` target surfaces
                  ;; `:rf.warning/malformed-url`; an unmatched URL that resolved
                  ;; to `:rf.route/not-found` with no such route registered
                  ;; surfaces `:rf.warning/no-not-found-route`.
                  (plan/emit-intents!
                    (plan/fallback-telemetry-intents
                      {:throw-reason  throw-reason
                       :malformed?    false
                       :no-not-found? (boolean (and unmatched-url (nil? route-meta)))
                       :url           requested-url
                       :frame         frame}))
                  ;; commit-navigation: nav-token alloc, allocated/activation
                  ;; traces, slice publish, fx assembly -- the shared commit
                  ;; shape. The programmatic path is the only one that drives the
                  ;; browser URL, so it passes `push-fx`.
                  (routing-events/commit-navigation
                    rdb
                    {:route-id   route-id
                     :params     path-params
                     :query      query-params
                     :fragment   fragment
                     :transition (if (seq on-match-vec) :loading :idle)}
                    on-match-vec
                    {:prev-id        (get-in rdb [:rf.runtime/routing :current :route-id])
                     :prev-nav-token (get-in rdb [:rf.runtime/routing :current :nav-token])
                     :capture-fx     capture-fx
                     :scroll-fx      scroll-fx
                     :push-fx        push-fx
                     :nav-allocation nav-allocation
                     :frame          frame
                     :app-db         app-db})))))))))
