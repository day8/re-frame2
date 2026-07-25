(ns re-frame.routing.url-change
  "URL-driven navigation: the shared full-rewrite path
  (`url-change-fx`) plus the `:rf.route/transitioned` and
  `:rf.route/handle-url-change` events for re-frame2 routing.

  Per Spec 012 §URL changes are events. `:rf.route/transitioned`
  (forward nav, default scroll `:top`) and `:rf.route/handle-url-change`
  (popstate / initial / SSR, default scroll `:restore`) share
  `url-change-fx`. The fragment-only branch (Spec 012 §Fragments rules
  3-4) ALSO lives in `url-change-fx`, so both events honour it —
  popstate / Back-Forward to a same-page anchor must not allocate a new
  nav-token or re-fire `:on-match`.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `events/reg-event` calls so a `:reload`
  re-wires them on a fresh registrar."
  (:require [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.routing.decisions :as decisions]
            [re-frame.routing.egress :as egress]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.plan :as plan]
            [re-frame.routing.resolve :as resolver]
            [re-frame.routing.scroll :as scroll]
            [re-frame.trace :as trace]))

(defn- fragment-only-fx
  "Spec 012 §Fragments rules 1-4: the new URL differs from the current
  route slice ONLY in its `#fragment`. Update `:fragment`, emit the
  `:rf.route/fragment-changed` op trace, and return the effects
  map — WITHOUT allocating a fresh nav-token (rule 3) or re-firing
  `:on-match` (rule 4). The canonical op-name says what fires it (only a
  `#fragment` differed) and disambiguates from the runtime event
  `:rf.route/transitioned`, which fires on every URL transition. The
  full URL transition path never emits this op and never coincides with
  a `:rf.route.nav-token/allocated` on the same drain. Consumers carry
  `:prev-fragment` / `:next-fragment` in `:tags`, plus the `:frame` stamp
  (rf2-n0851k) so the fragment-only trace is frame-attributed exactly
  like every other routing trace inside a known navigation cascade (Spec
  012 §Multi-frame routing / Spec 009 — without `:frame`, epoch/Xray
  capture and frame-level trace suppression can drop or bypass the op).

  Scroll (rf2-p1aipi — the URL-driven counterpart to rf2-k4exp1's
  programmatic `navigate.cljc` fragment-only door): capture the LEAVING
  position, THEN emit the resolved `:rf.nav/scroll`. Both fx are the
  entry-point's already-resolved `plan/scroll-plan` outputs, threaded in
  by the caller (`capture-fx` / `scroll-fx`) — the SAME pair the full-
  commit sibling in `url-change-fx` uses — so the fragment-only door and
  the full-commit door resolve scroll identically. Before this fix the
  URL-driven door computed a scroll plan but DROPPED the scroll-fx: a
  user clicking a `#section` link (`:rf.route/transitioned`, default
  `:top` → scroll to the fragment) or Back-Forward to a fragment
  (`:rf.route/handle-url-change`, default `:restore` → the saved
  position) computed where to scroll and then never scrolled. This
  URL-driven door does NOT drive the browser URL (the address bar already
  changed via link-click pushState / popstate), so — unlike the
  programmatic door — it emits NO `:rf.nav/push-url`; but `pushState` /
  popstate do NOT scroll to a fragment natively, so `:rf.nav/scroll` IS
  required. `:scroll false` on the route meta suppresses it: `scroll-fx`
  arrives nil (`plan/scroll-plan` → `::suppress`) and no scroll fx is
  emitted."
  [rdb prev next-fragment capture-fx scroll-fx frame]
  (trace/emit! :rf.event :rf.route/fragment-changed
               {:route-id      (:route-id prev)
                :prev-fragment (:fragment prev)
                :next-fragment next-fragment
                :frame         frame})
  ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db
  ;; state — read/write the runtime-db partition.
  (let [fx (vec (concat (when capture-fx [capture-fx])
                        (when scroll-fx  [scroll-fx])))]
    (cond-> {:rf.db/runtime (assoc-in rdb [:rf.runtime/routing :current :fragment] next-fragment)}
      (seq fx) (assoc :fx fx))))

(defn- url-change-fx
  "Pure helper: given ONE argument map — runtime-db + url + default scroll
  strategy (+ the `frame` to carry on the no-such-handler trace + the
  RECORDABLE `nav-allocation`), return the effects map `{:rf.db/runtime :fx}` for
  a URL-driven full slice rewrite. Performs the `match-url` lookup and
  publishes the nav-token from the recordable, replay-stable
  `nav-allocation`; the `:counter` high-water bump rides a
  `:rf.route/commit-nav-counter` fx), computes the scroll fx
  entry, and emits the trace events (:rf.warning/no-not-found-route,
  :rf.warning/malformed-url, :rf.error/no-such-handler,
  :rf.route.nav-token/allocated).

  Per Spec 012 §URL changes are events §Route-not-found §Per-route data
  loading §Scroll restoration §Multi-frame routing. The current slice always
  carries the full seven-key published shape.

  Three fallback shapes feed `:rf.route/not-found`:

   - bare miss (`{:url url}`) — `match-url` returned nil and the URL
     percent-encoding decoded cleanly;
   - validation fail (`{:url url :reason :validation}`) — a route's
     pattern matched but its `:params` / `:query` schema rejected the
     parsed values;
   - malformed URL (`{:url url :reason :malformed-url}`) — any of the
     URL's path captures, query keys/values, or `#fragment` failed to
     %-decode. The `:reason` discriminator lets per-route error UIs
     and SSR projections branch on the cause.

   `app-db` is the navigation handler's causal app-db coeffect, threaded
   unchanged into `commit-navigation` and the
   `:routing/on-route-entry` hook so a cross-feature `{:from-db …}`
   route-resource scope resolves db-derived viewer identity at route
   entry. Routing never reads it.

   `cause` is the R0 navigation cause the door represents (`:link` for the
   forward `:rf.route/transitioned` door; `:popstate` / `:initial` / `:ssr`
   for the three sub-doors `:rf.route/handle-url-change` represents, resolved
   by `url-change-cause`). It is carried on the route plan built at the commit
   branch (EP-0037 R0b) and on any leave-pending / entry-denial value the
   decisions produce.

   EP-0037 R4: the guard decisions (stages 4-5) run HERE, after the
   transition kind is classified (stage 3) and before any commit — so an
   exact no-op evaluates NEITHER guard while a full or fragment-only
   transition evaluates both, in order. `opts` is the door's trailing opts
   map: `:bypass-leave?` is the public one-shot leave escape and
   `:rf.route/decided?` is the runtime-internal rider the link door sets on
   the `:rf.route/transitioned` event it synthesises after deciding, so the
   same target is not decided twice.

   rf2-szp11 — ONE argument map, keys named exactly as the destructuring
   names them, which is the shape every other function in this seam already
   takes (`decisions/decide`, `plan/scroll-plan`, `resolve/route-plan`,
   `plan/fallback-telemetry-intents`, `commit-navigation`'s opts,
   `navigate/fragment-only-nav-fx`). This was the seam's one positional
   outlier, and it had grown to NINE positions — the last two (`cause` at
   R0b, `opts` at R4) one slice at a time, each addition individually
   harmless. Two of those positions were `nav-allocation`
   (`{:token :counter}`) and `pending-nav-allocation` (`{:id :counter}`):
   ADJACENT, SAME-SHAPED two-key maps, so transposing them at a call site
   compiled and ran with no arity or type complaint, minted a nil id into
   either the committed slice's nav-token or the pending-navigation value,
   and surfaced hundreds of lines away in nav-token cofx behaviour with
   nothing pointing back at the call site. What kept it safe was that both
   call sites live 75 lines apart in this namespace, not the design. Named
   keys make each call site say what it passes."
  [{:keys [rdb url default-scroll frame nav-allocation pending-nav-allocation
           app-db cause opts]}]
  (let [rdb (or rdb {})
        ;; EP-0037 R0b: the URL -> ResolvedTarget extraction — including the
        ;; `:rf.route/not-found` fallback normalisation and its `:reason`
        ;; vocabulary — is the ONE shared definition in
        ;; `re-frame.routing.resolve/url-resolution`, the same seam the LINK
        ;; door's stage 3 + guards resolve through (`resolver/target-of-url`).
        ;; Deriving it here as well is what let the two disagree: the link door
        ;; decided against an incomplete target while this hop committed the
        ;; canonical not-found one.
        ;;
        ;; rf2-6t1xb / rf2-4ic0f: the seam is fail-closed. `match-url-fail-closed`
        ;; catches any throw out of `match-url` and yields a NIL match plus a
        ;; `:throw-reason` discriminator (`:match-error`), so a throwing URL
        ;; arriving via `:rf.route/transitioned` / `:rf.route/handle-url-change`
        ;; degrades to `:rf.route/not-found` exactly like a bare miss; a bare
        ;; miss is discriminated from a malformed URL by the `malformed-url?`
        ;; scan, run only when `match-url` already missed.
        {:keys [match throw-reason malformed? fallback?]
         resolved-target :target}
        (resolver/url-resolution url)
        fragment          (:fragment resolved-target)
        ;; Spec 012 §Fragments rules 3-4: when the new URL differs from
        ;; the current slice ONLY in its `#fragment` (same route-id,
        ;; params, query) the runtime updates `:fragment`, emits the
        ;; `:rf.route/fragment-changed` op trace, and short-circuits
        ;; BEFORE allocating a fresh nav-token or re-firing `:on-match`.
        ;; This branch lives in the shared helper so EVERY URL-driven
        ;; event honours it — both forward nav (`:rf.route/transitioned`)
        ;; AND popstate / initial / SSR (`:rf.route/handle-url-change`).
        ;; Back/Forward to a same-page anchor must not re-fetch route
        ;; data (rf2-8oxj6).
        prev              (get-in rdb [:rf.runtime/routing :current])
        ;; rf2-u8qe7y: fragment-only classification (Spec 012 §Fragments
        ;; rules 3-4) is shared pre-commit policy — `plan/fragment-only?`.
        ;; The fragment-only comparison is the PRE-fallback view: Spec 012
        ;; §Fragments rules 3-4 compares against the MATCHED route's id /
        ;; params / query, not against the not-found fallback the target
        ;; normalises to — hence the raw `match` the seam also returns.
        fragment-only?    (and match (plan/fragment-only? prev (:route-id match)
                                                          (:params match) (:query match)
                                                          fragment))
        route-id          (:route-id resolved-target)
        params            (:params resolved-target)
        query             (:query resolved-target)
        ;; Spec 012 §Per-route data loading rule 3: a re-navigation whose
        ;; resolved id/params/query/fragment match the current slice
        ;; exactly is a complete no-op — no new nav-token, no `:on-match`
        ;; re-fire, no scroll. Computed AFTER fallback resolution so two
        ;; identical not-found URLs (or two identical validation misses)
        ;; also skip. Sits alongside `fragment-only?` (the id/params/query
        ;; equal, fragment-changed sibling); the two are mutually
        ;; exclusive (fragment-only requires the fragment to differ).
        identical-nav?    (plan/identical-route-target? prev route-id params query fragment)
        route-meta        (registrar/lookup :route route-id)
        on-match-vec      (vec (or (:on-match route-meta) []))
        ;; EP-0037 R1: :on-match never drives readiness. The base transition
        ;; is :idle; commit-navigation projects :loading / :error from the
        ;; resource plan (readiness/project-at-commit).
        transition        :idle
        ;; nav-token allocation moved into `commit-navigation` (reached
        ;; only in the `:else` commit branch); the `identical-nav?` /
        ;; `fragment-only?` short-circuits never allocated a usable token
        ;; (the prior eager alloc was discarded on both), so the
        ;; observable counter behaviour is unchanged.
        ;; rf2-u8qe7y: the capture-fx + scroll-fx assembly is shared
        ;; pre-commit policy — `plan/scroll-plan` (URL-driven path passes
        ;; no opts; default strategy is the caller-supplied `default-scroll`).
        {:keys [capture-fx scroll-fx]}
        (plan/scroll-plan {:rdb              rdb
                           ;; rf2-1hncp2: saved scroll positions are a
                           ;; host-side transient cache (not runtime-db) —
                           ;; read the active frame's cache and thread it in
                           ;; explicitly so the planner stays pure. `:restore`
                           ;; (the popstate / Back-button default) reads it.
                           :scroll-cache     (scroll/frame-scroll-cache frame)
                           :route-meta       route-meta
                           :opts             nil
                           :default-strategy default-scroll
                           :route-id         route-id
                           :params           params
                           :query            query
                           :fragment         fragment
                           :url              url})
        ;; EP-0037 stages 4-5, evaluated ONCE and only for a non-no-op
        ;; transition (stage 3 short-circuits an exact no-op below before
        ;; this thunk is ever forced). `nil` means "both guards allowed".
        ;; Skipped outright when the LINK door already decided this exact
        ;; target and synthesised this event (`:rf.route/decided?`).
        decision (delay
                   (when-not (:rf.route/decided? opts)
                     (decisions/decide
                       {:rdb                    rdb
                        :frame                  frame
                        ;; rf2-2gna9: the guards decide against the ResolvedTarget
                        ;; the seam produced above — NOT a hand-rebuilt copy of it.
                        ;; Re-assembling the five fields here is what let the two
                        ;; branches of this one door disagree about what the target
                        ;; is: the commit branch below publishes
                        ;; `(:target route-plan)`, which IS `resolved-target`, so a
                        ;; field the seam later adds would reach the guards and the
                        ;; committed slice DIFFERENTLY — the precise class of bug
                        ;; R0b closed between the link door and the commit hop
                        ;; (`resolve.cljc`: "no door reinvents the ResolvedTarget
                        ;; shape"), reappearing three lines from where the door
                        ;; received the value.
                        :target                 resolved-target
                        :requested-url          url
                        :cause                  cause
                        :policy                 {}
                        :bypass-leave?          (:bypass-leave? opts)
                        :url-driven?            true
                        :pending-nav-allocation pending-nav-allocation})))]
    (cond
      ;; Spec 012 §Per-route data loading rule 3 / EP-0037 stage 3: nothing
      ;; relevant changed — skip the dispatch entirely. No guard evaluation,
      ;; no pending state, no nav-token allocation, no `:on-match` drain, no
      ;; scroll fx, no slice rewrite. The previously-allocated token stands.
      ;; This is the "redundant navigation to the already-active URL" no-op
      ;; (clicking the current nav link, popstate to the current URL); an
      ;; exact no-op leaves nothing and enters nothing, so a redundant
      ;; request can neither be blocked nor create pending state.
      identical-nav?
      {:rf.db/runtime rdb}

      ;; EP-0037 stages 4-5: a FULL or FRAGMENT-ONLY transition consults the
      ;; current route's `:can-leave` and then the target route's
      ;; `:can-enter`. A leave rejection writes the resumable leave-only
      ;; pending value; an entry rejection is TERMINAL (no pending value, no
      ;; commit). Both restore the address bar by replace — this is a
      ;; URL-driven door, so the host URL has already moved.
      (some? @decision)
      @decision

      ;; Spec 012 §Fragments rules 3-4 (rf2-8oxj6): short-circuit BEFORE
      ;; the nav-token allocation / on-match drain below. Honoured on
      ;; both `:rf.route/transitioned` and `:rf.route/handle-url-change`
      ;; (popstate) because the branch lives in the shared helper. The
      ;; carried `frame` is threaded through so the emitted
      ;; `:rf.route/fragment-changed` trace is frame-attributed (rf2-n0851k),
      ;; consistent with the commit-path lifecycle traces below.
      ;; rf2-p1aipi: pass the already-resolved scroll pair (`capture-fx` +
      ;; `scroll-fx` from the shared `plan/scroll-plan` above) so the
      ;; fragment-only door EMITS the resolved `:rf.nav/scroll` (capture →
      ;; scroll), matching the programmatic `navigate.cljc` door
      ;; (rf2-k4exp1). No push-fx — the URL-driven door never drives the
      ;; browser URL. `:scroll false` → `scroll-fx` nil → suppressed.
      fragment-only?
      (fragment-only-fx rdb prev fragment capture-fx scroll-fx frame)

      :else
      ;; EP-0037 R0b: the URL-driven door lowers to the SAME resolved-target /
      ;; route-plan seam as the programmatic door. The plan's `:target` is the
      ;; very ResolvedTarget `url-resolution` produced above — the value the link
      ;; door's stage 3 and guards already decided against, now published into
      ;; the slice, so the seam is load-bearing rather than a parallel diagnostic
      ;; copy. Its `:cause` / `:branch` / `:leaf-plan` are the R0 diagnostic
      ;; projection (Spec 012 §Resolved target and the plan diagnostic
      ;; projection). The raw URL IS the source here.
      (let [route-plan (resolver/route-plan {:cause  cause
                                             :source {:url url}
                                             :target resolved-target})]
        ;; rf2-u8qe7y: the fail-closed warning telemetry
        ;; (`:rf.warning/malformed-url` for a `match-url` throw / malformed
        ;; %-encoding — rf2-6t1xb / rf2-4ic0f; `:rf.warning/no-not-found-route`
        ;; when the not-found fallback has no registered route — rf2-0zr2o /
        ;; Spec 012 §Route-not-found §3) is shared pre-commit policy with the
        ;; programmatic path. Both build the SAME intent list from the same
        ;; inputs via `plan/fallback-telemetry-intents`, so the two paths
        ;; cannot drift (the drift navigate.cljc:426-437 documents). The
        ;; `:rf.error/no-such-handler` error is URL-driven-specific (every
        ;; URL-driven fallback is a handler-miss; the programmatic `{:url}`
        ;; miss is the documented not-found escape hatch, not a
        ;; handler-resolution error) so it stays a call-site intent.
        (plan/emit-intents!
          (cond-> (plan/fallback-telemetry-intents
                    {:throw-reason  throw-reason
                     :malformed?    malformed?
                     :no-not-found? (and fallback? (nil? route-meta))
                     :url           url
                     :frame         frame})
            ;; :rf.error/no-such-handler discriminates from event / frame
            ;; handler misses by :kind :route. rf2-4ic0f: carry the
            ;; `:reason` (throw pre-empts the malformed scan, so the two are
            ;; mutually exclusive — throw-reason wins) so the structured
            ;; error is uniform across the trace + the slice. The :frame tag
            ;; (present when the caller threads it in) lets the SSR
            ;; error-projection listener attribute the trace per-frame.
            fallback?
            (conj [:emit-error :rf.error/no-such-handler
                   ;; EP-0015 (rf2-n1f4rh): `:rf.error/no-such-handler` is a
                   ;; production-survivable / off-box-observable error
                   ;; category EP-0015 requires to FAIL CLOSED. The route-miss
                   ;; URL has no matched route → no schema to consult, and is
                   ;; the class most likely to carry secret carriers
                   ;; (`?token=…`, `#access_token=…`). Redact the query/
                   ;; fragment carrier VALUES (keep the structured path +
                   ;; `:reason`) before the error crosses the trace / log /
                   ;; SSR-projection egress boundary.
                   (-> (cond-> {:url url
                                :kind :route
                                :recovery :replaced-with-default}
                         frame        (assoc :frame frame)
                         throw-reason (assoc :reason throw-reason)
                         malformed?   (assoc :reason :malformed-url))
                       egress/redact-url-tag)])))
        ;; EP-0037 R0b: ONE `:rf.route/planned` trace per door commit branch, so
        ;; the R0 diagnostic projection is REACHABLE from an executed navigation
        ;; rather than only from a tool holding a plan value. This door stands for
        ;; FOUR of the five causes (`:link` / `:popstate` / `:initial` / `:ssr`),
        ;; so the `cause` it was handed is what distinguishes them on the stream.
        ;; `resolver/plan-trace-tags` is the ONE projection-to-tags mapping (the
        ;; programmatic door emits through it too) and it is what keeps the trace
        ;; from becoming a carrier: the URL rides the existing `redact-url-tag`
        ;; path and `:params` / `:query` contribute KEY SETS, not values.
        (trace/emit! :rf.event :rf.route/planned
                     (cond-> (resolver/plan-trace-tags route-plan)
                       frame (assoc :frame frame)))
        ;; rf2-g8tzb / commit-navigation: nav-token alloc, the
        ;; allocated/activation traces, the slice publish (targeting
        ;; `:current`, so sibling routing-runtime keys are untouched),
        ;; and the fx assembly are the shared commit shape. The
        ;; URL-driven path passes NO `push-fx` — the browser URL already
        ;; changed (popstate / initial / link-click pushState).
        (routing-events/commit-navigation
          rdb
          (assoc (:target route-plan) :transition transition)
          on-match-vec
          {:prev-id        (get-in rdb [:rf.runtime/routing :current :route-id])
           ;; rf2-vdyrls: the prior route's nav-token — the second half of the
           ;; previous route owner the resources plan releases on route leave
           ;; (Spec 016 §Route integration).
           :prev-nav-token (get-in rdb [:rf.runtime/routing :current :nav-token])
           :capture-fx   capture-fx
           :scroll-fx    scroll-fx
           ;; rf2-vcop6y: the RECORDABLE nav-token allocation threaded through
           ;; so the nav-token is PUBLISHED from `:token` (recorded +
           ;; replay-stable) + the `:counter` bump rides an fx.
           :nav-allocation nav-allocation
           ;; rf2-cqyq2: the plan's already-resolved fail-loud `:parent` walk —
           ;; the SAME value its `:branch` diagnostic projects, so the trace and
           ;; the resource composition cannot disagree, and the commit hop walks
           ;; the chain no second time.
           :branch-contributors (:branch-contributors route-plan)
           :branch-error        (:branch-error route-plan)
           ;; rf2-dbmj6x: the carried frame stamp (validated at the handler
           ;; top, threaded into `url-change-fx`). `commit-navigation` stamps
           ;; it on the nav-token-allocated + activated/deactivated lifecycle
           ;; traces so the URL-driven `:rf.route/transitioned` /
           ;; `:rf.route/handle-url-change` paths frame-attribute them too,
           ;; consistent with the route-miss diagnostics already tagged above.
           :frame        frame
           ;; EP-0016 D3 slice 3: route-entry app-db for `{:from-db …}` scope.
           :app-db       app-db})))))

(defn transitioned-handler
  "`:rf.route/transitioned` event handler. Registered by the façade
  so a `:reload` re-wires it on a fresh registrar. Per Spec 012 §URL
  changes are events / §Fragments. Forward nav (link click /
  programmatic push). After the leave-guard check, delegate to the
  shared `url-change-fx`, which distinguishes a fragment-only change
  (update :fragment, emit :rf.route/fragment-changed, no nav-token /
  no :on-match — rf2-cj9fn) from a full slice rewrite. Default scroll
  strategy for forward nav is `:top` per Spec 012 §Scroll restoration;
  popstate / initial / SSR routes through `:rf.route/handle-url-change`
  (default `:restore`)."
  [{frame :rf.frame/id rdb :rf.db/runtime
    nav-allocation :rf.route/nav-allocation
    pending-nav-allocation :rf.route/pending-nav-allocation
    app-db :db}
   [_ url opts]]
  (let [;; EP-0002 carried invariant — `:rf.route/transitioned` is a
        ;; cascade event, so the cofx carries the frame stamp under
        ;; `:rf.frame/id`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame   (frame/require-frame-stamp!
                  frame :rf.route/transitioned
                  {:where 'rf.route/transitioned-handler})
        opts    (or opts {})
        rdb     (or rdb {})]
    ;; rf2-w3qgc: thread the active `frame` into `url-change-fx` so the
    ;; forward-nav route-miss / malformed-url trace sites
    ;; carry `:frame`, consistent with the popstate / SSR sibling
    ;; (`handle-url-change-handler`, :restore below) and the programmatic
    ;; `:rf.route/navigate {:url ...}` path. Spec 009 requires `:frame` on
    ;; `:rf.error/no-such-handler {:kind :route}` and
    ;; `:rf.warning/no-not-found-route`. The carried `:frame` (the
    ;; cascade cofx supplies it) tags those traces. EP-0001 (rf2-vzld77):
    ;; the route slice is durable routing runtime-db state.
    ;; EP-0037 R0b: the forward `:rf.route/transitioned` door's plan cause
    ;; is `:link`. EP-0037 R4: the guard decisions run INSIDE
    ;; `url-change-fx`, after the transition kind is classified.
    (url-change-fx {:rdb                    rdb
                    :url                    url
                    :default-scroll         :top
                    :frame                  frame
                    :nav-allocation         nav-allocation
                    :pending-nav-allocation pending-nav-allocation
                    :app-db                 app-db
                    :cause                  :link
                    :opts                   opts})))

(defn url-change-cause
  "The true R0 navigation cause for one `:rf.route/handle-url-change`
  dispatch. The event is ONE door standing for THREE (Spec 012 §URL changes
  are events — popstate, initial page load, and the SSR request URL), and the
  R0 causes `:popstate` / `:initial` / `:ssr` are cause-specific diagnostics,
  so the door must report which of the three it actually was rather than
  labelling all three `:popstate`.

  Resolution, in order:

  1. the runtime-internal `:rf.route/cause` rider on the door's trailing opts
     map. The framework's own strategy-aware history listener sets it — the
     browser-driven `popstate` / `hashchange` callback stamps `:popstate`, and
     the same listener's initial URL -> slice sync stamps `:initial` (Spec 012
     §popstate drives the URL-owner frame). Only a member of
     `resolver/causes` is honoured; anything else falls through, so a stray
     value cannot invent a sixth cause. Like `:rf.route/decided?` this is a
     runtime-internal rider on the trailing opts map, NOT a member of the
     closed `:rf.route/navigate` request roster (Spec 012 §The request
     grammar);
  2. `:ssr` when the dispatching frame is a server frame — the SSR feed is
     dispatched by the application's own `:initial-events`
     (`[:rf.route/handle-url-change (:uri request)]`, Spec 012 §Server-side
     rendering integration step 2), so it carries no framework rider and is
     recognised by the frame's `:platform :server`, the same one read that
     gates the SSR 403 floor;
  3. `:initial` otherwise — a URL-change dispatch with no framework rider on a
     client frame is an initial / direct-URL feed. `popstate` never reaches
     here without the rider: the frame lifecycle owns the listener that fires
     it."
  [frame opts]
  (let [riden (:rf.route/cause opts)]
    (cond
      (contains? resolver/causes riden)  riden
      (decisions/server-frame? frame)    :ssr
      :else                              :initial)))

(defn handle-url-change-handler
  "`:rf.route/handle-url-change` event handler. Registered by the
  façade so a `:reload` re-wires it on a fresh registrar. Per Spec 012
  §URL changes are events — popstate, initial load, SSR. Delegates to
  the shared `url-change-fx`, which honours the fragment-only
  short-circuit (Spec 012 §Fragments rules 3-4): a Back/Forward to a
  same-page `#fragment` updates :fragment WITHOUT allocating a new
  nav-token or re-firing :on-match (rf2-8oxj6). The default scroll
  strategy is `:restore` so the saved position trumps. `:frame` is
  threaded through so the SSR error-projection listener can attribute
  the :no-such-handler trace per-frame.

  This one event stands for THREE doors, so the plan cause is resolved
  per-dispatch by `url-change-cause` rather than hardcoded — otherwise the
  declared `:initial` and `:ssr` causes are dead and cause-specific
  diagnostics misreport two of the five doors."
  [{frame :rf.frame/id rdb :rf.db/runtime
    nav-allocation :rf.route/nav-allocation
    pending-nav-allocation :rf.route/pending-nav-allocation
    app-db :db}
   [_ url opts]]
  (let [;; EP-0002 carried invariant — `:rf.route/handle-url-change` is a
        ;; cascade event (popstate / initial / SSR), so the cofx carries
        ;; the frame stamp under `:rf.frame/id`; a nil stamp is an invariant
        ;; failure (`:rf.error/no-frame-context`), never a synthesised
        ;; `:rf/default`.
        frame   (frame/require-frame-stamp!
                  frame :rf.route/handle-url-change
                  {:where 'rf.route/handle-url-change-handler})
        opts    (or opts {})
        rdb     (or rdb {})]
    ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
    ;; EP-0037 R0b: the URL-driven `:rf.route/handle-url-change` door stands for
    ;; THREE sub-doors, so it carries the cause `url-change-cause` resolves for
    ;; THIS dispatch — `:popstate`, `:initial`, or `:ssr`.
    (url-change-fx {:rdb                    rdb
                    :url                    url
                    :default-scroll         :restore
                    :frame                  frame
                    :nav-allocation         nav-allocation
                    :pending-nav-allocation pending-nav-allocation
                    :app-db                 app-db
                    :cause                  (url-change-cause frame opts)
                    :opts                   opts})))
