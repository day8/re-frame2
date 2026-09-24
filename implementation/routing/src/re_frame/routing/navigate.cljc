(ns re-frame.routing.navigate
  "`:rf.route/navigate` event for re-frame2 routing.

  Per Spec 012 §Navigation is an event. Programmatic navigation entry
  point: ONE flat request map — `[:rf.route/navigate {request}]`. Address
  keys `:to` / `:url` / `:params` / `:query` / `:fragment`; policy keys
  `:replace?` / `:scroll` / `:bypass-leave?`; the in-place edit key
  `:query-merge`. A destination request (`:to` / `:url`) builds a FRESH
  address; an in-place request (neither) PATCHES the current location.
  Honours :can-leave and :can-enter, :params/:query validation, scroll
  resolution, the in-place :query-merge fold, and the rule-3 no-op
  short-circuit. A destination address is taken LITERALLY: no ambient
  current-route query is folded into it (EP-0037 R5).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event :rf.route/navigate` call so a
  `:reload` re-wires it on a fresh registrar."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.decisions :as rf.routing.decisions]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.url :as rf.routing.url]
            [re-frame.trace :as rf.trace]))

(defn- validate-event-shape
  "The event-VECTOR shape gate for `:rf.route/navigate`, run BEFORE the
  request map is examined (Spec 012 §Validity rules). The event is exactly
  `[:rf.route/navigate {request}]`: a TWO-element vector whose payload is a
  MAP. Returns nil when the shape is well-formed, else `{:reason <kw> :keys
  []}`.

  Without it, two malformed shapes would escape the request gate: a THIRD
  event element (a positional opts map, as in a
  `[:rf.route/navigate target opts]` call) would be silently DROPPED
  while the navigation proceeded, and a NON-MAP payload would reach the request
  gate's `dissoc` and throw a RAW host exception. Both reject LOUD
  through `:rf.error/navigate-bad-request` (`:reason :bad-event-arity` /
  `:request-not-a-map`), slice unchanged, no push."
  [event-vec]
  (cond
    (not= 2 (count event-vec))
    {:reason :bad-event-arity :keys []}

    (not (map? (second event-vec)))
    {:reason :request-not-a-map :keys []}

    :else nil))

(defn- route-schema-sensitive?
  "True iff the route's `:params` OR `:query` Malli schema declares ANY
  `:sensitive?` slot. The sensitivity decision is made by the
  SAME shared schema-aware seam every
  off-schemas validation-failure emit site routes through
  (`:schemas/redact-validation-tags`): routing the route's schema + an
  empty tag map through it stamps `:sensitive? true` exactly when the
  schema is sensitive (the seam's `redact-tags` always stamps when it
  fires). Reaching it through the late-bind hook keeps routing's optional-
  schemas posture — when the schemas artefact is absent the hook is unbound
  and this returns false (no schema to walk → nothing to redact), the same
  fall-through every other off-namespace seam consumer uses."
  [route-meta]
  (boolean
    (when-let [redact (rf.late-bind/get-fn-cached :schemas/redact-validation-tags)]
      (some (fn [slot]
              (when-let [schema (get route-meta slot)]
                (true? (:sensitive? (redact schema {})))))
            [:params :query]))))

(defn- redact-route-error-tags
  "Elide the `:error` slot of a `:rf.route/navigate` schema-validation-failure
  trace when the route's `:params` / `:query` schema declares any
  `:sensitive?` slot (AI/MCP egress + logs threat model).

  The `:error` slot carries `(ex-data ex)` of a `route-url` construction
  throw. For the `:rf.error/route-url-validation` case that ex-data embeds
  `:value` (the raw path-params / query-params the caller supplied) AND
  `:error` (the Malli explainer, which itself reproduces the failing value
  verbatim); for `:rf.error/missing-route-param` it carries `:value` (the
  offending param value). Route params can be document-ids / tokens, so on a
  `:sensitive?`-marked route this slot leaks the same secret material the
  route's `:params` / `:query` schema gates — the SAME class the shared seam
  covers on the schema-validation hot path, except route-param
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
    (assoc tags :error rf.privacy/redacted-sentinel :sensitive? true)
    tags))

(defn- fragment-only-nav-fx
  "Spec 012 §Fragments rules 3-4 / §Programmatic navigation with fragments:
  a resolved `:rf.route/navigate` whose target differs from the
  CURRENT slice ONLY in its `#fragment` (same `:route-id`/`:params`/`:query`)
  is an in-page anchor change, not a route (re)activation. Mirrors the
  URL-driven `url_change.cljc` fragment-only short-circuit so the SAME logical
  operation behaves identically whichever door — programmatic
  `:rf.route/navigate`, or the URL-driven `:rf.route/handle-url-change`
  (link click / popstate / initial / SSR) — it enters.

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
       `rf.routing.plan/scroll-plan`). Default `:top` scrolls to the new fragment (or top
       when the fragment is cleared/missing); `:restore`/`:preserve` mean what
       they mean on a full navigation. `pushState`/`replaceState` do NOT scroll to a
       fragment natively, so `:rf.nav/scroll` IS required — this makes NO focus
       / `:target` pseudo-class parity claim (a separate a11y decision).

  State-first: the runtime-db `:fragment` write commits before the ordered
  history/scroll effects, exactly like every other route effect; a rejected
  history projection surfaces the `:rf.fx/push-url-failed` /
  `:rf.fx/replace-url-failed` diagnostic and leaves the slice + trace
  committed. NB this must NOT copy `url_change.cljc`'s `fragment-only-fx`
  verbatim — that helper carries scroll CAPTURE only; the programmatic path
  also drives the URL and the resolved scroll."
  [{:keys [rdb current fragment url opts route-meta route-id params query frame]}]
  (rf.trace/emit! :rf.event :rf.route/fragment-changed
               {:route-id      (:route-id current)
                :prev-fragment (:fragment current)
                :next-fragment fragment
                :frame         frame})
  (let [push-fx (if (:replace? opts)
                  [:rf.nav/replace-url url]
                  [:rf.nav/push-url    url])
        {:keys [capture-fx scroll-fx]}
        (rf.routing.plan/scroll-plan {:rdb              rdb
                           ;; Saved scroll positions are a
                           ;; host-side transient cache — thread the active
                           ;; frame's cache in explicitly so the planner stays
                           ;; pure (same shape the full commit branch passes).
                           :scroll-cache     (rf.routing.scroll/frame-scroll-cache frame)
                           :route-meta       route-meta
                           :opts             opts
                           :default-strategy :top
                           :route-id         route-id
                           :params           params
                           :query            query
                           :fragment         fragment
                           :url              url})]
    ;; EP-0001: the route slice is durable framework runtime-db
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
    1. rejects a malformed request with `:rf.error/navigate-bad-request`
       BEFORE any guard runs, through the shared always-on structural gate
       (`re-frame.routing.address/classify`) — slice unchanged, no push;
    2. resolves a DESTINATION request (`:to` / `:url`) into a FRESH address,
       or an IN-PLACE request into a PATCH of the current location;
    3. classifies the transition (EP-0037 stage 3) — an exact no-op
       terminates HERE, evaluating neither guard;
    4. runs the `:can-leave` then `:can-enter` decisions (stages 4-5) for a
       full or fragment-only transition, then commits.

  EP-0001: the route slice is durable framework runtime-db
  state, so the handler reads it from the `:rf.db/runtime` coeffect (`rdb`)
  and `commit-navigation` returns a `:rf.db/runtime` effect. The handler
  never touches user app-db. The nav-token / pending-nav-id are
  minted by RECORDABLE generator-backed allocation cofx -- `:rf.route/
  nav-allocation` (commit) and `:rf.route/pending-nav-allocation` (block) --
  so the minted ids are recorded on the causal token and replay re-presents
  them verbatim. Scroll positions are likewise a host-side cache."
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
          frame (rf.frame/require-frame-stamp!
                  frame :rf.route/navigate
                  {:where 'rf.route/navigate-handler})
          rdb  (or rdb-raw {})
          ;; The current route slice (durable framework runtime-db fact). Read
          ;; once so the in-place patch and `:query-merge` both resolve
          ;; against it.
          current (get-in rdb [:rf.runtime/routing :current])
          ;; Guarded on `map?` so a non-map payload never reaches the request
          ;; gate (a raw host throw) -- the event-shape gate rejects it first
          ;; through the same `:rf.error/navigate-bad-request` channel.
          request (when (map? request0) request0)
          ;; The event-VECTOR shape gate runs first (arity + map payload),
          ;; then the request-MAP structural gate. Both surface the same
          ;; `:rf.error/navigate-bad-request` channel.
          bad     (or (validate-event-shape event-vec)
                      (rf.routing.address/classify request current))]
      (if bad
        ;; Spec 012 §Error surfaces #1: the always-on structural gate rejected
        ;; the request. `:rf.error/navigate-bad-request` is a DISTINCT channel
        ;; from `:rf.error/schema-validation-failure` (that category is the
        ;; dev-only, schemas-artefact-gated validation channel; this gate is
        ;; always-on and production-surviving). Slice unchanged, no push.
        ;; Frame-attribute the reject so it lands in the emitting
        ;; frame's epoch.
        (do
          (rf.trace/emit-error! :rf.error/navigate-bad-request
                             (cond-> {:where    :event
                                      :reason   (:reason bad)
                                      :keys     (:keys bad)
                                      :recovery :no-recovery}
                               frame (assoc :frame frame)))
          {})
        (let [destination? (rf.routing.address/destination? request)
              url-target   (:url request)
              {:keys [route-id path-params query-params matched-fragment unmatched-url
                      throw-reason malformed? requested-url external-url-target?]}
              (cond
                ;; SECURITY (open-redirect): an external-classed
                ;; `:url` target fails closed BEFORE match-url, identical to
                ;; `url-requested-handler` (the shared `rf.routing.url/external-url?` gate).
                (and (some? url-target) (rf.routing.url/external-url? url-target))
                {:route-id             :rf.route/not-found
                 :path-params          {}
                 :query-params         {}
                 :requested-url        url-target
                 :external-url-target? true}

                ;; URL-string target (escape hatch). This door lowers
                ;; to the SAME `rf.routing.resolve/url-resolution` extraction every other
                ;; URL-bearing door reaches, rather than reading the URL a second
                ;; time, so the `:reason` vocabulary `plan.cljc`'s not-found
                ;; section documents is shared byte-for-byte across both entry
                ;; points. A second, inline reading would skip the
                ;; `malformed-url?` scan, so a malformed percent-encoding
                ;; arriving HERE (the door Spec 012 documents as taking dynamic /
                ;; user-supplied URLs, and `egress.cljc` names as the class most
                ;; likely to carry `?token=` / `#access_token=`) would stamp a bare
                ;; `{:url …}` and emit no EP-0015 diagnostic, while the same
                ;; URL through `:rf.route/handle-url-change` stamps
                ;; `:reason :malformed-url` and warns.
                ;;
                ;; The extraction supplies the shared FACTS; the
                ;; programmatic policy layers on top of them, exactly as
                ;; `url_change.cljc` layers `fragment-only?` on the seam's raw
                ;; `:match` rather than on the normalised target:
                ;;
                ;;   - `(:route-id match)` — NOT the normalised `:rf.route/not-found`.
                ;;     A schema-validation miss is a MATCH, and the programmatic
                ;;     door's specified behaviour on one (Spec 012 §resolve-target
                ;;     table / §Validation-error surfacing) is to reject the
                ;;     caller's bug through `route-url` below, NOT to route to
                ;;     not-found the way the URL-driven door does. Taking the
                ;;     matched id, params, and query preserves that asymmetry
                ;;     byte-for-byte.
                ;;   - `(:params target)` on a MISS — the seam's normalised
                ;;     not-found params, which is where the `:reason`
                ;;     discriminators (`:malformed-url`, `:match-error`, or none)
                ;;     come from rather than being re-derived here.
                ;;   - `match-url` always carries `:route-id` when it matches, so
                ;;     `(when-not matched? …)` is the same unmatched-URL test as
                ;;     `(when-not (:route-id match) …)`. An unmatched URL
                ;;     keeps the REQUESTED url on the address bar.
                ;;   - `(:fragment target)` is nil on a malformed URL (the
                ;;     fragment may itself be the decode-fail site) and the
                ;;     matched fragment otherwise.
                ;;
                ;; The ACCEPTED reference is first reduced to the app
                ;; URL it names — the same `request-app-url` the link door runs.
                ;; Matched raw, a same-origin absolute URL, a protocol-relative
                ;; one, a dot-segment path or a pure `?query` / `#fragment`
                ;; would miss and commit not-found while the pushed URL displayed
                ;; a valid route. The reduction is APP-RELATIVE —
                ;; a reference without an origin resolves against THIS frame's
                ;; current app URL, one with an origin is a browser address
                ;; decoded by the URL owner's strategy — so a base path or a hash
                ;; strategy neither doubles the base nor lands on the wrong
                ;; route. That ONE effective URL feeds the match, the unmatched
                ;; fallback params, the guards and history; the plan's `:source`
                ;; keeps the caller's string as provenance. On the JVM / with no
                ;; window the helper is identity (the fail-closed policy).
                (some? url-target)
                (let [app-url (rf.routing.decisions/request-app-url rdb url-target)
                      {:keys [match matched? malformed? throw-reason target]}
                      (rf.routing.resolve/url-resolution app-url)]
                  {:route-id         (or (:route-id match) :rf.route/not-found)
                   :path-params      (if matched? (:params match) (:params target))
                   :query-params     (:query match {})
                   :matched-fragment (:fragment target)
                   :unmatched-url    (when-not matched? app-url)
                   :throw-reason     throw-reason
                   :malformed?       malformed?
                   :requested-url    app-url})

                ;; Route-id destination -- build a FRESH address (omitted
                ;; :query/:fragment empty).
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
              ;; The ResolvedTarget seam applies the SAME rule for every door;
              ;; this door also normalises here because the
              ;; unmatched raw-URL rebuild immediately below consumes the
              ;; fragment before the seam runs, and `normalize-fragment` is
              ;; idempotent so lowering through it twice changes nothing.
              fragment (rf.routing.plan/normalize-fragment
                         (cond
                           (contains? request :fragment) (:fragment request)
                           destination?                  matched-fragment
                           :else                         (:fragment current)))
              ;; An explicit `:fragment` on an UNMATCHED raw-URL
              ;; navigate overrides (or, when nil/empty, clears) the fragment
              ;; embedded in the raw URL. Pushing the raw `unmatched-url`
              ;; VERBATIM would keep `#old` on the address bar while the slice
              ;; carried `#new` -- address bar, slice, and guard/pending target
              ;; would disagree. Rebuild ONE effective requested URL (the raw
              ;; path/query with its `#fragment` replaced by the resolved
              ;; `fragment`, percent-encoded exactly as `route-url` emits) and
              ;; thread it through the not-found `:params`, the guards, the
              ;; history push, and the fallback telemetry. Absent an explicit
              ;; `:fragment` the raw URL rides verbatim, embedded fragment
              ;; included.
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
                                  (str base "#" (rf.routing.url/url-encode fragment))
                                  base))
                              unmatched-url)
              requested-url (if override-unmatched-fragment? unmatched-url requested-url)
              path-params   (if override-unmatched-fragment?
                              (assoc path-params :url unmatched-url)
                              path-params)
              route-meta (rf.registrar/lookup :route route-id)
              ;; EP-0037 R5: a DESTINATION address is taken literally. The
              ;; router folds no ambient current-route query state into a
              ;; fresh destination -- doing so would let an authored
              ;; `{:to :route/cart}` silently grow keys from whichever route
              ;; happened to be current, and there is no `:query-retain`
              ;; route metadata that asks for it. An
              ;; application that deliberately carries global URL state spells
              ;; it as an ordinary pure function over the address before
              ;; dispatch (Spec 012 §Carrying query state across routes). The
              ;; in-place `:query` / `:query-merge` edits below are the causal
              ;; primitive for editing the CURRENT query.
              ;;
              ;; `:query-merge` (in-place only, gated above) folds the caller's
              ;; deltas over the current query; a nil value removes a key. The
              ;; nil-valued keys the fold leaves behind are dropped by the
              ;; ResolvedTarget seam below (a rule it applies for EVERY
              ;; door), so the
              ;; written slice matches the pushed URL.
              ;;
              ;; The trailing `or {}` is load-bearing: a present-but-nil
              ;; `:query` (`{:to :x :query nil}`) means
              ;; the same "clear the query" as `:query {}` — presence, not
              ;; truthiness, discriminates (`rf.routing.address/edit-keys`) — and the
              ;; branches above hand it through as nil. The seam must NOT do
              ;; this one: there, an ABSENT `:query` stays absent rather than
              ;; being conjured into `{}`, which is a different fact.
              ;;
              ;; The deltas are SPELLED the way the current
              ;; route's URL spells them before they fold, so a delta overwrites
              ;; or removes the entry it names instead of landing beside it: on a
              ;; route that does not declare `:page`, merging an unspelled
              ;; `{:page 2}` into the URL-seeded `{"page" "1"}` would keep BOTH
              ;; keys and push `page=2&page=1`, and `{:page nil}` would remove
              ;; nothing. Nil deltas stay nil through `canonical-query`, so the
              ;; seam below reads them as removals.
              query-params (or (if-let [merge-in (:query-merge request)]
                                 (merge (:query current) query-params
                                        (rf.routing.registry/canonical-query route-meta merge-in))
                                 query-params)
                               {})
              ;; EP-0037 R0: shape the ResolvedTarget ONCE, HERE — before the
              ;; URL, before stage 3's no-op classification, before the guards
              ;; and before the commit — so every one of them sees the same
              ;; facts. `rf.routing.resolve/resolved-target` is the seam that fills the
              ;; route's declared `:query-defaults` and drops
              ;; nil-valued query keys, as `match-url` / `route-url` do for
              ;; the URL-bearing doors: without the fill
              ;; `{:to :d/page :params {:slug "x"}}`
              ;; would commit `:query {}` where `/p/x` commits
              ;; `{:tab :overview}` — a different slice, a different derived
              ;; URL and a different resource identity for one destination. It
              ;; matters that the fill lands BEFORE `identical-nav?` too: a
              ;; repeat navigate to a place reached by URL would otherwise compare
              ;; unequal against the current slice and re-fire `:on-match` on a
              ;; fresh nav-token.
              ;;
              ;; The `:url` arrives below (it is derived FROM these facts), so
              ;; the two consumers assoc it on. One target value feeds the
              ;; guards, the plan and the commit — no door spells the
              ;; ResolvedTarget shape twice.
              resolved     (rf.routing.resolve/resolved-target {:route-id route-id
                                                      :params   path-params
                                                      :query    query-params
                                                      :fragment fragment})
              query-params (:query resolved)
              ;; Build the push URL. An external / unmatched target skips
              ;; `route-url`; a `route-url` throw is a caller bug -> emit
              ;; `:rf.error/schema-validation-failure` (`:where :event`) and
              ;; reject via the `::reject` sentinel (slice unchanged, no push).
              url (cond
                    external-url-target? nil
                    unmatched-url        unmatched-url
                    :else
                    (try (rf.routing.registry/route-url {:to       route-id
                                              :params   path-params
                                              :query    query-params
                                              :fragment fragment})
                         (catch #?(:clj Throwable :cljs :default) ex
                           (rf.trace/emit-error! :rf.error/schema-validation-failure
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
              ;; change routed through the shared `rf.routing.plan/fragment-only?`.
              identical-nav? (rf.routing.plan/identical-route-target?
                               current route-id path-params query-params fragment)
              fragment-only? (and (not unmatched-url)
                                  (rf.routing.plan/fragment-only?
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
              (rf.trace/emit! :rf.event :rf.route/external-url-requested
                           (cond-> {:url requested-url}
                             frame (assoc :frame frame)))
              {})

            ;; EP-0037 stage 3 runs BEFORE the guards: an exact no-op
            ;; terminates here, evaluating NEITHER guard and creating no
            ;; pending state (nothing is being left or entered, and a
            ;; redundant request must not be blockable).
            identical-nav?
            {}

            ;; EP-0037 stages 4-5 (mirrors the URL-driven path): the current
            ;; route's `:can-leave` THEN the target's `:can-enter`. A full
            ;; transition -- INCLUDING a changed in-place `:query` /
            ;; `:query-merge`, which is data-bearing -- and a fragment-only
            ;; transition both evaluate the pair.
            :else
            (if-let [decided (rf.routing.decisions/decide
                               {:rdb                    rdb
                                :frame                  frame
                                ;; the ONE resolved target, plus the URL derived
                                ;; from it — not a second spelling of the facts.
                                :target                 (assoc resolved :url url)
                                :requested-url          url
                                :cause                  :navigate
                                :policy                 (rf.routing.decisions/normalize-policy request)
                                :bypass-leave?          (:bypass-leave? request)
                                :url-driven?            false
                                :pending-nav-allocation pending-nav-allocation})]
              decided
              (cond
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
                      (rf.routing.plan/scroll-plan {:rdb              rdb
                                         :scroll-cache     (rf.routing.scroll/frame-scroll-cache frame)
                                         :route-meta       route-meta
                                         :opts             request
                                         :default-strategy :top
                                         :route-id         route-id
                                         :params           path-params
                                         :query            query-params
                                         :fragment         fragment
                                         :url              url})
                      ;; EP-0037 R0: the programmatic door lowers to the ONE
                      ;; resolved-target / route-plan seam. `:cause :navigate`;
                      ;; the source is the extracted address (a `:to` request),
                      ;; the raw-URL escape (`{:url ...}`), or the in-place edit.
                      ;; The plan's `:target` is the SAME `resolved` value the
                      ;; guards decided against and the commit publishes — so the
                      ;; seam is load-bearing, not a parallel diagnostic copy.
                      ;; Its `:branch` / `:leaf-plan` are the plan diagnostic
                      ;; projection (Spec 012 §Resolved target and the plan
                      ;; diagnostic projection).
                      route-plan (rf.routing.resolve/route-plan
                                   {:cause  :navigate
                                    :source (cond
                                              url-target              {:url url-target}
                                              (contains? request :to) (rf.routing.address/extract-address request)
                                              :else                   (select-keys request rf.routing.address/edit-keys))
                                    :target (assoc resolved :url url)})]
                  ;; Shared fail-closed telemetry: a
                  ;; match-url throw on a `:url` target surfaces
                  ;; `:rf.warning/malformed-url`; an unmatched URL that resolved
                  ;; to `:rf.route/not-found` with no such route registered
                  ;; surfaces `:rf.warning/no-not-found-route`.
                  ;; `:malformed?` is the shared extraction's answer,
                  ;; not a hardcoded `false`. Passing `false` into the SHARED
                  ;; intent builder would make the parity structural in form and
                  ;; absent in fact — the two callers would build the same intent list
                  ;; from DIFFERENT inputs, so the one input that matters would never
                  ;; reach it. Mutually exclusive with `:throw-reason` by
                  ;; construction (a throw pre-empts the malformed scan).
                  (rf.routing.plan/emit-intents!
                    (rf.routing.plan/fallback-telemetry-intents
                      {:throw-reason  throw-reason
                       :malformed?    malformed?
                       :no-not-found? (boolean (and unmatched-url (nil? route-meta)))
                       :url           requested-url
                       :frame         frame}))
                  ;; EP-0037 R0: ONE `:rf.route/planned` trace per door commit
                  ;; branch, so the plan diagnostic projection is REACHABLE from an
                  ;; executed navigation rather than only from a tool holding a
                  ;; plan value. `rf.routing.resolve/plan-trace-tags` is the ONE
                  ;; projection-to-tags mapping (the URL-driven door emits through
                  ;; it too) and it is what keeps the trace from becoming a
                  ;; carrier: the URL rides the `redact-url-tag` path and
                  ;; `:params` / `:query` contribute KEY SETS, not values.
                  ;; Emitted before the commit so the stream reads
                  ;; planned -> nav-token allocated -> deactivated/activated.
                  (rf.trace/emit! :rf.event :rf.route/planned
                               (cond-> (rf.routing.resolve/plan-trace-tags route-plan)
                                 frame (assoc :frame frame)))
                  ;; commit-navigation: nav-token alloc, allocated/activation
                  ;; traces, slice publish, fx assembly -- the shared commit
                  ;; shape. The programmatic path is the only one that drives the
                  ;; browser URL, so it passes `push-fx`. The slice is the plan's
                  ;; resolved `:target` (facts) plus the resolved transition.
                  (rf.routing.events/commit-navigation
                    rdb
                    ;; EP-0037 R1: :on-match never drives readiness. The base
                    ;; transition is :idle; commit-navigation projects
                    ;; :loading / :error from the resource plan (readiness/
                    ;; project-at-commit).
                    (assoc (:target route-plan) :transition :idle)
                    on-match-vec
                    {:prev-id        (get-in rdb [:rf.runtime/routing :current :route-id])
                     :prev-nav-token (get-in rdb [:rf.runtime/routing :current :nav-token])
                     :capture-fx     capture-fx
                     :scroll-fx      scroll-fx
                     :push-fx        push-fx
                     :nav-allocation nav-allocation
                     :frame          frame
                     :app-db         app-db
                     ;; The plan's already-resolved fail-loud
                     ;; `:parent` walk — the SAME value its `:branch` diagnostic
                     ;; projects, so the trace and the resource composition
                     ;; cannot disagree, and the commit hop walks the chain no
                     ;; second time.
                     :branch-contributors (:branch-contributors route-plan)
                     :branch-error        (:branch-error route-plan)})))))))))
