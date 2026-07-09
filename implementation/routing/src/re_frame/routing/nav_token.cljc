(ns re-frame.routing.nav-token
  "Navigation-token stale-result suppression for re-frame2 routing.

  Per Spec 012 §Navigation tokens — stale-result suppression. Owns:
    - `:rf.route/nav-token` cofx — injects the current navigation epoch
      token (`[:rf.runtime/routing :current :nav-token]`) FLAT under the
      `:rf.route/nav-token` key in an `:on-match` handler's coeffects map
      (EP-0017 §5), so the handler can capture it and thread it into an async
      continuation;
    - `:rf.route/with-nav-token` fx — names an async-completion continuation
      via the canonical `:rf/reply-to` reply target and guards it with a
      stale-result check: match → complete the reply; mismatch →
      suppress and emit `:rf.route.nav-token/stale-suppressed`.

  ## Lowered onto the uniform reply envelope (EP-0011, rf2-zqefg3.5)

  The receipt-side stale check is NOT a bespoke per-family token
  comparison: it is an ORDINARY reply-envelope `:suppress` gate. The
  carried `:nav-token` is the value of the ONE data-only suppression gate
  `{:route/nav-token <token>}`, validated against the live
  `[:rf.runtime/routing :current :nav-token]` via the shared
  `re-frame.reply/stale?` (through `re-frame.routing.reply`). The route
  work-id is `[:rf.work/route route-id nav-token loader-id]`; the
  suppression trace is joined to `:rf.reply/work-id`. The PUBLIC API (the cofx and
  the `:rf.route/with-nav-token` fx) is PRESERVED — internal lowering
  only. See `spec/Managed-Effects.md` §The uniform reply envelope and
  Spec 012 §Lowering onto the uniform reply envelope.

  The test-only `:rf.test/simulate-http-resolution` fixture analogue of
  this fx lives in `re-frame.routing.test-support` (rf2-dbiv8) — behind
  an explicit test-support require, so it never reaches a production
  registry. This namespace carries only production surface.

  Spec-Schemas carries the `:rf.fx/with-nav-token-args` shape.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade registers the fx so a `:reload` of the façade re-wires it on a
  fresh registrar. Per the rf2-2yabr cohesion split: NAV-TOKEN seam."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.reply :as reply]
            [re-frame.routing.reply :as route-reply]
            [re-frame.trace :as trace]))

(def nav-token-cofx-meta
  "Metadata for the `:rf.route/nav-token` cofx registration. Per Spec 012
  §Navigation tokens — stale-result suppression step 2: the cofx
  injects the current navigation epoch token so an `:on-match`-reached
  handler can capture the token live at scheduling time and thread it
  into an async continuation (via `:rf.route/with-nav-token` or its own
  follow-up event payload).

  Universal platform: the route slice exists on both client and server,
  so the cofx resolves under SSR and browser alike."
  {:doc "The current navigation epoch token, read from
`[:rf.runtime/routing :current :nav-token]` and delivered FLAT under the
`:rf.route/nav-token` key in the handler coeffects map (EP-0017 §5). Declare with
`:rf.cofx/requires [:rf.route/nav-token]` on an `:on-match`-reached
handler; capture the value and thread it into an async continuation so a
superseding navigation suppresses the stale result. Per Spec 012
§Navigation tokens — stale-result suppression."})

(defn nav-token-cofx
  "Value-returning AMBIENT supplier for the `:rf.route/nav-token` cofx (EP-0017 §2).
  Reads the current navigation epoch token from the active frame's runtime-db
  route slice (`[:rf.runtime/routing :current :nav-token]`, read via
  `frame/frame-runtime-db-value` of `frame/*current-frame*` — bound by the
  router during processing). EP-0001 (rf2-vzld77): the route slice is durable
  routing runtime-db state. Never recorded; replay re-runs it (the token is
  re-presented because the route slice itself is recorded durable state).

  A handler declares `:rf.cofx/requires [:rf.route/nav-token]` and reads the value flat.
  Meaningful only inside a handler reached via an `:on-match` drain (or a
  follow-up of one), where the route slice holds the epoch the navigation
  cascade allocated. Read from any other handler it reflects whatever
  navigation is currently active — the correct \"is this still the live
  navigation?\" reading the stale-suppression pattern wants. Tests /
  conformance harnesses re-register the supplier to assert the threading
  shape without standing up a route slice."
  []
  (let [rdb (frame/frame-runtime-db-value frame/*current-frame*)]
    (get-in rdb [:rf.runtime/routing :current :nav-token])))

(def route-id-cofx-meta
  "Metadata for the `:rf.route/route-id` cofx registration (rf2-ph1grf).

  The route-loader work-id is `[:rf.work/route route-id nav-token loader-id]`
  (Managed-Effects §Work-id correlation; Spec 012 §Lowering onto the uniform
  reply envelope). The `route-id` is a CARRIED fact of one attempt — captured
  at scheduling time alongside the nav-token, NOT read from the live route
  slice at stale-arrival (a cross-route stale completion's live slice id is the
  superseding route's, which would mint a corrupt work-id). This cofx is the
  framework capture helper that delivers the live route-id flat under
  `:rf.route/route-id`, so a loader that declares BOTH
  `:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]` captures the two
  facts the work-id tuple needs together — the documented path can no longer
  emit a nil-route route work-id (rf2-azcmd3 / rf2-ph1grf).

  Universal platform: the route slice exists on both client and server, so the
  cofx resolves under SSR and browser alike."
  {:doc "The current route id, read from
`[:rf.runtime/routing :current :route-id]` and delivered FLAT under the
`:rf.route/route-id` key in the handler coeffects map (EP-0017 §5). Declare
ALONGSIDE `:rf.route/nav-token` on an `:on-match`-reached handler
(`:rf.cofx/requires [:rf.route/nav-token :rf.route/route-id]`) and thread BOTH
into an async continuation, so the route-loader work-id
`[:rf.work/route route-id nav-token loader-id]` carries its complete attempt
identity. Per Spec 012 §Lowering onto the uniform reply envelope."})

(defn route-id-cofx
  "Value-returning AMBIENT supplier for the `:rf.route/route-id` cofx (EP-0017
  §2; rf2-ph1grf). Reads the current route id from the active frame's
  runtime-db route slice (`[:rf.runtime/routing :current :route-id]`). The
  capture-side companion to `nav-token-cofx`: a loader captures the route id at
  scheduling time so a superseded completion's work-id is attributed to the
  route-loader ATTEMPT (the captured route), never the route live at
  stale-arrival.

  A handler declares `:rf.cofx/requires [:rf.route/route-id]` (usually together
  with `:rf.route/nav-token`) and reads the value flat. Meaningful inside a
  handler reached via an `:on-match` drain (or a follow-up of one). Never
  recorded; replay re-runs it (the route slice itself is recorded durable
  state)."
  []
  (let [rdb (frame/frame-runtime-db-value frame/*current-frame*)]
    (get-in rdb [:rf.runtime/routing :current :route-id])))

(defn emit-stale-suppressed!
  "Emit the `:rf.route.nav-token/stale-suppressed` trace for a superseded
  route-loader completion. The facts ride on top of the shared
  `re-frame.reply/suppress` outcome (EP-0011 §Route Loader Completion):
  the suppression trace is joined to `:rf.reply/work-id`, alongside the existing
  carried-token / current-token / event-id tags. Shared by the
  production `:rf.route/with-nav-token` handler and the test-only
  `:rf.test/simulate-http-resolution` fixture so one conformance
  assertion covers both paths.

  `event-id` is the suppressed continuation's event-id (per
  `target-event-id`); `frame-id` (when non-nil) frame-attributes the
  suppression so it lands in the emitting frame's epoch / Xray
  (rf2-7d30s). The work-id is built from the route context
  (`{:route-id … :nav-token <carried> :loader-id …}`) — `route-reply/
  suppress` carries it on the reply + trace.

  rf2-6mfkp3 — the canonical EP-0011 reply-envelope facts ride ADDITIVELY
  (`:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`,
  `:rf.reply/stale-reason`) — the SAME shape the resource
  (`:rf.resource/stale-suppressed`) and machine (`:rf.machine/done` /
  `:rf.machine.timer/stale-after`) families stamp — so a superseded route
  loader (an EP-0011 managed async family) is classifiable on the
  production trace via the identical canonical facts as every other family,
  not only the route-specific `:carried-token` / `:current-token` tokens.
  The facts are read off the `route-reply/suppress` `:reply` (the
  `:status :stale` / `:rf.reply/work-status :suppressed` / `:rf.reply/stale-reason` the
  shared substrate produced).

  rf2-ux8sgg — `:completed-at` is the recordable `:rf/time-ms` completion
  fact on the reply token (EP-0017): reply completions are causal tokens,
  so a superseded route loader's stale reply/trace MUST carry the actual
  replayed completion time, not drop it. The caller sources it from the
  declared `:rf.cofx/requires [:rf/time-ms]` reply fact (NOT an ambient
  clock read) and threads it here; it rides verbatim through
  `route-reply/suppress` onto the stale reply and is stamped on the trace
  when present, so route completion time tracks the HTTP / resource /
  mutation families that already carry `:completed-at`. Absent ⇒ omitted
  (a route loader that did not source a completion time).

  rf2-2avo53 — `:target` is the (optional) normalized `:rf/reply-to` reply
  target. It is threaded into `route-reply/suppress` so the EP-0011
  stale-delivery AUTHORITY rule applies at the PRODUCTION routing surface:
  the default is non-delivery (the app target MUST NOT run on a stale
  completion), but a FRAMEWORK/TOOL target that carries the
  `::stale-authority` capability and sets `:dispatch-stale? true` is honoured
  — and an app target that sets `:dispatch-stale? true` without authority
  FAILS LOUD (the shared substrate throws). Returns the full
  `re-frame.reply/suppress` outcome (`{:deliver? :reply :rf.reply/work-status :trace}`)
  so the caller can complete + dispatch the stale reply when (and only when)
  `:deliver?` is true — the same shape the live branch's `complete-live`
  produces."
  [{:keys [carried-token current-token event-id frame-id route-id loader-id completed-at target]}]
  (let [{:keys [reply trace] :as outcome}
        (route-reply/suppress
                          {;; rf2-azcmd3 — `route-id` is the route id CAPTURED
                           ;; at scheduling time (carried with the nav-token),
                           ;; NOT the live route slice id read at stale-arrival.
                           ;; If route A's stale completion arrives after a
                           ;; navigation to route B, the live slice id is B's —
                           ;; using it would mint a corrupt work-id
                           ;; `[:rf.work/route :route/B nav-A loader]` mixing
                           ;; B's route id with A's carried nav-token. The
                           ;; carried id keeps the work-id attributed to the
                           ;; route-loader ATTEMPT (Spec 012 §742 / EP-0011
                           ;; §Work-id correlation). nil when the caller did
                           ;; not capture one — preferred over a false
                           ;; live-route attribution.
                           :route-id  route-id
                           :nav-token carried-token
                           :loader-id loader-id
                           :frame     frame-id
                           ;; rf2-ux8sgg — the recordable reply completion
                           ;; time. `route-reply/suppress` only carries it onto
                           ;; the reply when non-nil, so a route loader without
                           ;; a sourced completion time is unaffected.
                           :completed-at completed-at}
                          current-token
                          ;; rf2-2avo53 — the reply target, consulted only for
                          ;; its `:dispatch-stale?` opt-in + `::stale-authority`.
                          target)]
    (trace/emit-error! :rf.route.nav-token/stale-suppressed
                       (cond-> {:carried-token     carried-token
                                :current-token     current-token
                                :rf.trace/event-id event-id
                                ;; The suppression trace is joined to
                                ;; `:rf.reply/work-id` per EP-0011 §Route Loader
                                ;; Completion — the route-loader work-id
                                ;; `[:rf.work/route route-id nav-token
                                ;; loader-id]` the shared substrate built.
                                :rf.reply/work-id           (:rf.reply/work-id trace)
                                ;; rf2-waawic — the shared carried/current
                                ;; correlation facts `re-frame.reply/suppress`
                                ;; computes (Managed-Effects §Tracing), so the
                                ;; uniform reply-envelope view reads the route
                                ;; stale gate without route-family-specific
                                ;; parsing. The bespoke `:carried-token` /
                                ;; `:current-token` above are preserved.
                                :rf.reply/carried  (:rf.reply/carried trace)
                                :rf.reply/current  (:rf.reply/current trace)
                                ;; rf2-6mfkp3 — the canonical EP-0011 status /
                                ;; work-status / stale-reason vocabulary
                                ;; (Managed-Effects §9), read off the shared
                                ;; substrate `:reply`. Route loaders are a
                                ;; managed async family: a superseded route
                                ;; completion is classifiable on the production
                                ;; trace via the SAME canonical facts as the
                                ;; resource / machine / HTTP families.
                                :rf.reply/status      (:status reply)
                                :rf.reply/work-status (:rf.reply/work-status reply)
                                :rf.reply/stale-reason (:rf.reply/stale-reason reply)
                                :recovery          :replaced-with-default}
                         frame-id (assoc :frame frame-id)
                         ;; rf2-ux8sgg — the recordable reply completion
                         ;; time rides on the stale trace when the loader
                         ;; sourced it (read off the suppressed `:reply`,
                         ;; where `route-reply/suppress` placed it). The
                         ;; uniform reply-envelope view ties the stale route
                         ;; reply to the actual replayed completion token —
                         ;; the same `:completed-at` the HTTP / resource /
                         ;; mutation families carry. Absent ⇒ omitted.
                         (some? (:completed-at reply))
                         (assoc :completed-at (:completed-at reply))))
    ;; rf2-2avo53 — return the full suppression outcome so the caller can
    ;; complete + dispatch the stale reply when (and only when) `:deliver?` is
    ;; true (a framework/tool-authorised `:dispatch-stale?` target). The
    ;; default app path leaves `:deliver?` false and the caller dispatches
    ;; nothing — the trace above is the only effect of a suppressed completion.
    outcome))

(def with-nav-token-meta
  "Metadata for the `:rf.route/with-nav-token` fx registration: the
  docstring + the inline Malli schema per Spec-Schemas.md
  §`:rf.fx/with-nav-token-args`. Inline rather than a registered
  schema-id so validation works in consumers that don't pre-register the
  keyword in their Malli registry; the registered-id form remains
  available to apps that want to centralise schemas (per Spec 010
  §Schema registration)."
  {:doc  "Per Spec 012 §Navigation tokens — the receipt-side stale check,
lowered onto the uniform reply envelope (EP-0011). Threads the carried
`:nav-token` against the current `[:rf.runtime/routing :current :nav-token]`.
Match → complete the continuation; mismatch → suppress and emit
`:rf.route.nav-token/stale-suppressed`.

The continuation is named by the canonical `:rf/reply-to` reply target (an
event-vector prefix or descriptor — the EP-0011 lowering: on match the route
loader's `:status :ok` reply map is appended to the target via the shared
`re-frame.reply/complete`, and on a framework/tool-authorised `:dispatch-stale?`
target the stale reply is delivered the same way). `:rf/reply-to` is the single,
required continuation surface."
   :schema [:map
            ;; rf2-2avo53 — the CANONICAL `:rf/reply-to` reply target: the
            ;; single, required EP-0011 property-9 target key. The route
            ;; loader's reply lowers through it on every match.
            [:rf/reply-to :any]
            [:nav-token :any]
            ;; rf2-azcmd3 — OPTIONAL captured route id. When the loader
            ;; captured the route id at scheduling time and threads it here,
            ;; a cross-route stale completion attributes its work-id to the
            ;; route-loader ATTEMPT rather than the route live at arrival.
            [:route-id {:optional true} :any]
            ;; rf2-2avo53 — OPTIONAL live reply `:value` (the loader's decoded
            ;; result). Rides the `:status :ok` reply map the matched
            ;; `:rf/reply-to` target is completed with.
            [:value {:optional true} :any]
            ;; rf2-ux8sgg — OPTIONAL reply completion time. The documented
            ;; lane for the recordable `:rf/time-ms` completion fact on the
            ;; reply token (EP-0017). When the route completion handler
            ;; sources it from its declared `:rf.cofx/requires [:rf/time-ms]`
            ;; and threads it here, a superseded route loader's stale reply /
            ;; trace carries the actual replayed completion time, tying the
            ;; route stale reply to the completion token like every other
            ;; managed-async family. Sourced from the reply fact, NOT an
            ;; ambient clock read.
            [:completed-at {:optional true} :any]]})

(defn- target-event-id
  "Best-effort extraction of the loader/event-id from a `:rf/reply-to` reply
  target (rf2-2avo53) — the head of the target's event-vector prefix (short
  form `[:event-id …]` or descriptor `{:event [:event-id …]}`). nil for a
  malformed/absent target, so the suppressed attempt's `:event-id` /
  `:loader-id` tags identify the continuation."
  [target]
  (let [event (cond
                (vector? target) target
                (map? target)    (:event target)
                :else            nil)]
    (when (and (vector? event) (seq event) (keyword? (first event)))
      (first event))))

(defn with-nav-token-handler
  "`:rf.route/with-nav-token` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  The stale check is an ordinary reply-envelope `:suppress` gate
  (EP-0011, rf2-zqefg3.5): the carried `:nav-token` and the live route
  slice token are compared through the shared `re-frame.reply/stale?`
  (via `re-frame.routing.reply/suppress?`).

  rf2-2avo53 — the continuation lowers onto the uniform reply envelope. The
  wrapper names the continuation by the canonical `:rf/reply-to` reply target:

   - on match the route loader's `:status :ok` reply map is built and APPENDED
     to the target via the shared `re-frame.reply/complete` (the production
     lowering: the route surface normalizes + completes a reply target through
     the shared substrate, exercising the EP-0011 mapping/completion law at the
     actual navigation wrapper), then dispatched.

   - on mismatch the target is threaded into `re-frame.reply/suppress`, so a
     framework/tool-authorised `:dispatch-stale? true` target receives the
     stale reply (and an app target asking for it without authority FAILS
     LOUD); the default app path suppresses (no dispatch).

  Match completes the continuation; mismatch emits
  `:rf.route.nav-token/stale-suppressed` joined to the route work-id."
  [{:keys [frame] :as _ctx} args]
  ;; rf2-2avo53 — the canonical `:rf/reply-to` reply target (the EP-0011
  ;; lowering surface): the single, required continuation surface.
  (let [reply-target    (get args :rf/reply-to)
        nav-token       (get args :nav-token)
        ;; rf2-azcmd3 — the CAPTURED route id (optional). Captured at
        ;; scheduling time alongside the nav-token and threaded into the
        ;; async continuation, so a cross-route stale completion attributes
        ;; its work-id to the route-loader ATTEMPT, not whatever route is live
        ;; when the stale completion arrives. Absent ⇒ nil (preferred over a
        ;; false live-route attribution).
        carried-route-id (get args :route-id)
        ;; rf2-2avo53 — the OPTIONAL live reply `:value` (the loader's decoded
        ;; result), appended via the `:status :ok` reply map on the matched
        ;; `:rf/reply-to` target.
        value           (get args :value)
        ;; rf2-ux8sgg — the OPTIONAL reply completion time. Sourced by the
        ;; route completion handler from its declared
        ;; `:rf.cofx/requires [:rf/time-ms]` reply fact and threaded here, so
        ;; a stale (superseded) route loader's reply / trace carries the
        ;; actual replayed completion token time rather than dropping it.
        completed-at    (get args :completed-at)
        ;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame as `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id        (frame/require-frame-stamp!
                          frame :rf.route/with-nav-token
                          {:where 'rf.route/with-nav-token-handler})
        frame-record    (frame/frame frame-id)
        ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
        rdb             (frame/frame-runtime-db-value frame-id)
        slice           (get-in rdb [:rf.runtime/routing :current])
        current         (:nav-token slice)
        ;; The continuation's loader/event-id, derived from the `:rf/reply-to`
        ;; target. Shared by the live-dispatch and the suppression trace.
        loader-id       (target-event-id reply-target)
        active-platform (or (get-in frame-record [:config :platform])
                            (interop/active-platform))
        ;; The identity-fact context the reply substrate keys on (rf2-2avo53 /
        ;; rf2-azcmd3 / rf2-ux8sgg). Shared by both branches so a LIVE and a
        ;; STALE completion of the same attempt correlate by the identical
        ;; `:rf.reply/work-id`.
        reply-ctx       {:route-id     carried-route-id
                         :nav-token    nav-token
                         :loader-id    loader-id
                         :frame        frame-id
                         :completed-at completed-at}
        ;; Route an event vector through `fx/handle-one-fx` via `:dispatch`.
        ;; `handle-one-fx` rather than `do-fx` so the cascade's single
        ;; `:event/do-fx` boundary marker stays on the outer walk (the inner
        ;; re-entry must not double-emit it — the epoch projection's
        ;; six-domino bucketing keys off that marker). The active-platform
        ;; resolution mirrors `router/run-fx-effects!`.
        dispatch!       (fn [fx-entry]
                          (fx/handle-one-fx frame-id fx-entry active-platform {} nil))]
    (if-not (route-reply/suppress? nav-token current)
      ;; Gate matches (token current) — complete the continuation.
      ;; rf2-2avo53 — CANONICAL `:rf/reply-to`: build the `:status :ok` reply
      ;; map and APPEND it to the target through the shared
      ;; `re-frame.reply/complete`, then dispatch the completed event. The
      ;; route surface normalizes + completes the reply target through the
      ;; shared substrate at the actual navigation wrapper.
      (dispatch! [:dispatch (route-reply/complete-live reply-ctx reply-target value)])

      ;; Stale — suppress through the shared reply-envelope correctness
      ;; boundary. rf2-7d30s — `frame-id` frame-attributes the suppression so
      ;; it lands in the emitting frame's epoch / Xray. rf2-2avo53 — the reply
      ;; target is threaded in so a framework/tool-authorised `:dispatch-stale?`
      ;; target receives the stale reply; the default app path does not.
      (let [{:keys [deliver? reply]}
            (emit-stale-suppressed!
              (assoc reply-ctx
                     :carried-token nav-token
                     :current-token current
                     :event-id      loader-id
                     :frame-id      frame-id
                     :target        reply-target))]
        ;; rf2-2avo53 — authorised stale delivery: complete the target with the
        ;; STALE reply and dispatch it. `:deliver?` is true ONLY for a
        ;; framework/tool target that opted in via an authorised
        ;; `:dispatch-stale? true` (the substrate already FAILED LOUD on an
        ;; unauthorised app target). The default app path leaves `:deliver?`
        ;; false → no dispatch, the suppression trace is the only effect.
        (when (and deliver? (some? reply-target))
          (dispatch! [:dispatch (reply/complete reply-target reply)]))))))
