(ns re-frame.http.transport
  "Shared (platform-neutral) attempt-and-retry lifecycle for
  `:rf.http/managed`.

  The platform transports live in per-platform sibling adapter
  namespaces (rf2-hp772l): `re-frame.http.transport-cljs` owns the Fetch
  path (`cljs-fetch` + `classify-cljs-error`); `re-frame.http.transport-jvm`
  owns the `java.net.http.HttpClient` path (`jvm-fetch` +
  `classify-jvm-error`) and the per-row CLJS-only-key degradation tracing
  (`check-cljs-only-keys!`). This namespace owns everything else —
  `dispatch-reply!`, `finalise-success!`, `finalise-failure!`,
  `maybe-retry!`, the 4xx/5xx/2xx/else response cascade, the in-flight
  registry interaction, the retry-trace emission, the rf2-bma05 privacy
  redaction, and the rf2-lxd3 supersede suppression — as platform-neutral
  Clojure. `run-attempt!` is the one site that selects the platform
  adapter under a reader conditional; the rest of the lifecycle carries
  no platform interop.

  Per-row CLJS-only request keys (`:abort-signal`, `:mode`, `:cache`,
  `:referrer`, `:integrity`) are no-ops on JVM with a one-line trace
  per occurrence via `transport-jvm/check-cljs-only-keys!`.

  Per Spec 014 §Failure categories the attempt loop classifies status
  codes BEFORE decode (4xx/5xx route to `:rf.http/http-4xx` /
  `:rf.http/http-5xx` with the raw body-text). Per Spec 014 §Retry and
  backoff `maybe-retry!` decides between retry, immediate-final-failure,
  and successful completion based on the failing attempt's failure
  category and the request's `:retry` config."
  (:require [re-frame.http.decode       :as decode]
            [re-frame.http.encoding     :as encoding]
            [re-frame.http.middleware   :as middleware]
            [re-frame.http.privacy      :as privacy]
            [re-frame.http.privacy-body :as privacy-body]
            [re-frame.http.registry     :as registry]
            [re-frame.http.reply        :as http-reply]
            [re-frame.http.transport-cljs :as transport-cljs]
            [re-frame.http.transport-jvm  :as transport-jvm]
            [re-frame.interop           :as interop]
            [re-frame.trace             :as trace])
  #?(:clj (:import [java.util.concurrent CompletableFuture])))

;; ---- shared attempt-and-retry loop ----------------------------------------

(declare run-attempt!)
(declare finalise-failure!)
(declare schedule-backoff-handle!)

(def ^:private reply-suppressing-abort-reasons
  "Abort reasons whose `:rf.http/aborted` failure is NOT dispatched to the app
  `:on-failure` reply target — the cancellation replaces the original outcome,
  so delivering the old reply would race / corrupt the post-cancel state.

  `:request-id-superseded` (rf2-lxd3) — a fresh request with the same
  `:request-id` replaced this one. `:epoch-restored` (rf2-u5kmf8) — epoch
  restore unwound the timeline this request belonged to; per EP-0011 /
  Managed-Effects §restore (\"epoch restore MUST NOT revive host work\") a
  pre-restore completion MUST NOT deliver to its original `:rf/reply-to` target.
  Both still emit their trace facts (the suppressed-attempt's stale envelope);
  only the app dispatch is suppressed.

  Cross-ref: this set gates only the REPLY. For `:request-id-superseded`
  the canonical EP-0011 stale-trace is NOT emitted here — it is emitted
  separately by `managed-handler` → `emit-superseded-stale-trace!` at
  supersede time (the `:epoch-restored` sibling is emitted by
  `http-registry/abort-in-flight-for-frame!`). `dispatch-aborted!` here
  fires only the `:rf.http/aborted` error trace and then suppresses the
  reply for these reasons."
  #{:request-id-superseded :epoch-restored})

(defn reply-suppressing-abort-reason?
  "True when an `:rf.http/aborted` failure carrying `reason` must NOT dispatch
  its `:on-failure` reply (`reply-suppressing-abort-reasons`)."
  [reason]
  (contains? reply-suppressing-abort-reasons reason))

(defn- dispatch-reply!
  "Threads the reply-payload through the per-frame `:after` interceptor
  chain (REVERSE registration order) BEFORE handing off to the
  late-bind router for `:on-success` / `:on-failure` dispatch.

  Per rf2-uheqq + Spec 014 §Middleware: each `:after` sees `(ctx,
  response)` — `ctx` is the SAME middleware-ctx the `:before` chain
  produced for this request (carried forward via the normalised ctx's
  `:middleware-ctx` slot, populated by `managed-handler`). The
  `:after` chain may transform the response shape; its return value is
  what `build-reply-event` appends to the user's `:on-success` /
  `:on-failure` event vector.

  When no middleware-ctx is in scope (synthetic / test-path callers that
  build a ctx directly without going through `managed-handler`), the
  `:after` chain is skipped and the reply-payload passes through
  unchanged — the chain's contract is to see the `:before`'s ctx, not a
  synthesised one."
  [{:keys [origin-event explicit-on-success explicit-on-failure
           kind reply-payload frame middleware-ctx completed-at]}]
  (let [explicit (case kind
                   :success explicit-on-success
                   :failure explicit-on-failure)]
    ;; rf2-uheqq — `:after` chain + late-bind dispatch. Shared with the
    ;; canned-stub reply path (`http-test-support/dispatch-canned-
    ;; reply!`) via `middleware/run-after-then-dispatch!` (rf2-k67u3): the
    ;; `:after` chain is skipped when no middleware-ctx is present
    ;; (synthetic / test-path callers).
    (middleware/run-after-then-dispatch!
      {:frame          frame
       :middleware-ctx middleware-ctx
       :origin-event   origin-event
       :explicit-on    explicit
       :reply-payload  reply-payload
       :kind           kind
       ;; EP-0010 / EP-0017 (rf2-n1rh0f / rf2-alc1lf): the host completion time
       ;; rides the reply dispatch's `:rf.cofx` `:rf/time-ms` so a reply reducer
       ;; reads it as causal data, never a fresh clock.
       :completed-at   completed-at})))

;; rf2-zqefg3.2 / rf2-ibksxg — the canonical-reply lowering seam (EP-0011).
;; Every completion (success / failure / abort) flows through ONE canonical
;; reply map built in `re-frame.http.reply` — `:status` (`:ok` / `:error` /
;; `:cancelled`), `:value` on `:ok`, the classified `:rf.http/*` failure map
;; verbatim under `:error`, `:rf.reply/work-id` `[:rf.work/http logical-id issuance
;; attempt]` (rf2-azcmd3), `:rf.reply/work-kind :http`, `:rf.reply/work-status`, `:attempt`,
;; `:rf.frame/id`, `:completed-at`, and `:correlation {:request-id …}` (the
;; `:request-id` is correlation metadata, NOT a second stale-suppression key).
;; rf2-ibksxg — that canonical reply is the SINGLE public artefact too: it is
;; delivered to the app reply target verbatim. `:on-success` / `:on-failure`
;; are pure ROUTING sugar (both receive the canonical map) and the co-located
;; `(:rf/reply msg)` merge carries the canonical map under `:rf/reply`. The old
;; `{:kind :success/:failure}` reshape (`reply->public-payload`) is DELETED —
;; there is no compat dialect.

(defn- reply-ctx
  "Project the transport ctx onto the data-only correlation/identity facts
  `re-frame.http.reply` consumes to build a canonical reply map. Reads the
  host completion clock ONCE here, at finalisation, and threads it as
  `:completed-at` (the reply handler MUST NOT re-read the clock —
  Managed-Effects §Causal completion metadata).

  EP-0010 §Time (rf2-2elcw3): `:completed-at` is DURABLE causal time — it
  flows through `:rf.cofx` `:rf/time-ms` into resource `:loaded-at` /
  `:stale-at` and mutation `:settled-at`, which freshness readers compare
  against `js/Date.now`. It MUST therefore be wall-clock epoch ms
  (`epoch-now-ms`), NOT `now-ms` — on CLJS `now-ms` is `performance.now()`
  (origin-relative), so a `now-ms`-stamped `:stale-at` reads stale
  immediately against `js/Date`. This is the transport boundary the
  router's fresh-token fix (427f260d3 / rf2-n1rh0f) deliberately preserves
  as the host-clock read; converting it here completes that fix."
  [ctx]
  {:request-id   (:request-id ctx)
   :origin-event (:origin-event ctx)
   ;; rf2-azcmd3 — the per-request-id issuance discriminator rides into the
   ;; work-id so a superseded attempt and its superseder carry distinct ids.
   :issuance     (:issuance ctx)
   :attempt      (:attempt ctx)
   :frame        (:frame ctx)
   :completed-at (interop/epoch-now-ms)})

(defn- self-identify
  "rf2-1u9dja — stamp the four self-identifying fields (`:request
  {:method :url}`, `:request-id`, `:attempt` / `:max-attempts`, `:work/id`)
  onto a classified `:rf.http/*` failure map from the request ctx, so the
  PUBLIC failure reply (and the trace that inherits it) names WHICH request
  failed, not just what kind of failure it was (debugging-dx finding 3).

  Delegates the field construction to `http-reply/self-identify-failure`;
  this fn projects the transport `ctx` onto the identity map that helper
  consumes (`:method` off the `:request` envelope, `:max-attempts` off the
  `:retry` policy, plus `:url` / `:request-id` / `:origin-event` / `:issuance`
  / `:attempt`). Applies uniformly to ALL eight failure categories."
  [failure ctx]
  (http-reply/self-identify-failure
    failure
    {:method       (:method (:request ctx))
     :url          (:url ctx)
     :request-id   (:request-id ctx)
     :origin-event (:origin-event ctx)
     :issuance     (:issuance ctx)
     :attempt      (:attempt ctx)
     :max-attempts (:max-attempts (:retry ctx))}))

;; rf2-ee38b.7 — the failure-reply and success-reply dispatch shapes were
;; spelled out inline at four / two sites across finalise-success!,
;; finalise-failure! and the abort path's dispatch-aborted!. These two
;; helpers collapse each to one line and make the abort/natural symmetry
;; the surrounding comments describe visible in code. The load-bearing
;; concurrency comments stay at the call sites.

(defonce ^:private failure-swallowed-warned?
  ;; rf2-rl5tt — one-shot latch so the "real failure swallowed by
  ;; `:on-failure nil`" warning fires once per runtime, not once per
  ;; swallowed request. Fire-and-forget telemetry beacons (`:on-failure
  ;; nil`) are a legitimate steady-state pattern, so a per-request trace
  ;; would be noise; the single warning makes the FIRST silently-dropped
  ;; non-aborted failure visible (the no-silent-swallow principle) without
  ;; flooding the trace surface for callers who knowingly opted out.
  (atom false))

(defn- warn-failure-swallowed!
  "rf2-rl5tt — surface a swallowed REAL failure once per runtime.

  When a request fails and the caller passed an explicit `:on-failure
  nil`, `build-reply-event` legitimately silences the reply (fire-and-
  forget). But a NON-aborted failure (transport / 5xx / decode / accept /
  timeout) routed into that silence is a real error the app never sees —
  the anti-pattern the committed no-silent-swallow principle calls out.
  Emit a one-shot `:rf.warning/failure-swallowed` so the dropped failure
  is observable in dev / tooling.

  Aborts (`:rf.http/aborted`, any reason) are EXCLUDED: a cancelled
  request that no longer wants its reply is correct-by-design silence,
  not a swallowed error."
  [failure url sensitive? frame]
  (when (and interop/debug-enabled?
             (not= :rf.http/aborted (:kind failure))
             (compare-and-set! failure-swallowed-warned? false true))
    (trace/emit! :warning :rf.warning/failure-swallowed
                 (privacy/prepare-emit-tags
                   {:url     url
                    :failure failure
                    :reason  (str "an HTTP request failed with `:kind "
                                  (pr-str (:kind failure))
                                  "` but `:on-failure nil` silenced the "
                                  "reply — the failure was dropped with no "
                                  "handler. If the silence is intentional "
                                  "(fire-and-forget telemetry), ignore this; "
                                  "otherwise supply an `:on-failure` target.")}
                   (true? sensitive?)
                   {:frame frame}))))

(defn- on-failure-silenced?
  "True when the ctx carries an explicit `:on-failure nil` — the exact
  condition under which `build-reply-event` silences a `:failure` reply
  (`:supplied?` true with a `nil` `:value`). Mirrors the encoding-side
  silence branch so the swallow-warning fires for precisely the replies
  that get dropped."
  [ctx]
  (let [explicit (:explicit-on-failure ctx)]
    (and (:supplied? explicit) (nil? (:value explicit)))))

(defn- emit-reply-trace!
  "rf2-zqefg3.2 — emit a managed-async completion trace row built from the
  CANONICAL reply-envelope facts (Managed-Effects §Tracing), not the
  private callback payload. The wire-bearing slots (`:value` / `:error` /
  `:correlation`) route through the shared `http-reply/trace-reply` →
  `re-frame.reply/trace-summary` → `re-frame.elision/elide-wire-value`
  walker (never a family-private elider); the identity facts (`:status`,
  `:rf.reply/work-id`, `:rf.reply/work-kind`, `:rf.reply/work-status`, `:attempt`, `:rf.frame/id`,
  `:completed-at`) ride verbatim. Gated on `debug-enabled?` like the other
  `:rf.http/*` trace rows."
  [ctx reply]
  (when interop/debug-enabled?
    ;; rf2-ppkh3v — RESPONSE-BODY classification (EP-0015 §8, issue 5). The
    ;; success reply's `:value` IS the decoded response body — a registration-
    ;; owned transient payload classified per-slot via `:sensitive?` props on
    ;; the request's `:decode` SCHEMA (the EP-0005 mechanism). Apply those
    ;; per-slot marks to the value BEFORE it rides the trace, so a body slot
    ;; the owner's `:decode` schema marks sensitive (`[:token]`) redacts even
    ;; when the request was NOT declared per-call `:sensitive?`. A non-schema
    ;; `:decode` (`:auto` / `:json` / `:text` / binary / custom fn) is a
    ;; no-op here — its body is governed by the per-call `:sensitive?` flag on
    ;; the dev trace (and by the off-box fail-closed disposition for captures).
    ;; Only the success status carries a decoded body; failure / cancel carry
    ;; `:error`, untouched here. The schema's per-slot marks now cover BOTH
    ;; `:sensitive?` (→ `:rf/redacted`) and `:large?` (→ `:rf.size/large-elided`)
    ;; (rf2-jhyccs — `classify-decoded` routes through the shared marks walker).
    (let [reply' (if (and (= :ok (:status reply))
                          (contains? reply :value)
                          (privacy-body/schema-decode? (:decode ctx)))
                   (update reply :value privacy-body/classify-decoded (:decode ctx))
                   reply)]
      ;; Thread the CARRIED frame into the elider opts (EP-0002 — wire-egress
      ;; frame resolves from the carried stamp; HTTP completions fire from the
      ;; transport callback, OUTSIDE any `with-frame` scope, so without an
      ;; explicit `:frame` the elider fails closed and redacts every wire slot).
      ;; The per-call `:sensitive?` flag (Spec 014 §Privacy) is forwarded so a
      ;; sensitive request redacts its payload slots wholesale, matching the
      ;; existing `:rf.http/*` trace posture.
      ;;
      ;; rf2-t55hxg.6 — OFF-BOX FAIL-CLOSED (EP-0015 disposition 5). The
      ;; on-box `:value` above is the dev-operator view (an unschematized body
      ;; rides raw — the local operator sees their own process). For OFF-BOX
      ;; egress an unschematized body is whole-sensitive and MUST be omitted.
      ;; The request's `:decode` is request-private — it never rides the trace
      ;; event — so we STAMP the off-box disposition forward under
      ;; `:rf.http/off-box-body`; the off-box trace-events projector
      ;; (`re-frame.epoch.tool-pair`) consults it and omits / classifies the
      ;; body slot. Only the success status carries a body slot to gate.
      (trace/emit! :info :rf.http/replied
                   (cond-> (http-reply/trace-reply reply' (cond-> {:sensitive? (true? (:sensitive? ctx))}
                                                            (:frame ctx) (assoc :frame (:frame ctx))))
                     (= :ok (:status reply))
                     (assoc :rf.http/off-box-body
                            (privacy-body/off-box-body-disposition (:decode ctx))))))))

(defn emit-superseded-stale-trace!
  "rf2-azcmd3 — emit the canonical EP-0011 `:status :stale` /
  `:rf.reply/work-status :suppressed` reply-envelope trace for a SUPERSEDED HTTP
  attempt, WITHOUT dispatching any app target (Managed-Effects §Stale
  suppression — clauses 2/3/4: the superseded attempt's reply outcome becomes
  `:stale`, its ledger row reaches `:suppressed`, and the trace stream records
  the carried + current correlation).

  Called from `managed-handler` when a fresh request supersedes a prior
  in-flight one with the same `:request-id`. `superseded-handle` is the OLD
  attempt's handle (carrying its `:request-id` / `:origin-event` / `:issuance`
  / `:attempt` / `:frame` identity facts, stamped at `record-in-flight!`);
  `current-work-id` is the SUPERSEDING attempt's work-id (the new live
  `:work/id`). The carried (superseded) and current (superseding) work ids are
  `=`-distinct because the per-request-id issuance counter bumped — so tooling
  and conformance can tell the suppressed attempt from its replacement by
  `:work/id` (the EP-0011 single-attempt-identity rule this restores).

  The wire-bearing slots route through the shared
  `http-reply/trace-reply` → `re-frame.reply/trace-summary` →
  `re-frame.elision/elide-wire-value` walker; the carried/current correlation
  rides as `:rf.reply/carried` / `:rf.reply/current` (the shared
  `re-frame.reply/suppress` trace facts) so Xray's reply-envelope view joins
  it as a uniform stale-suppression row. Gated on `debug-enabled?` like the
  other `:rf.http/*` trace rows."
  [superseded-handle current-work-id]
  (when interop/debug-enabled?
    (let [stale-ctx {:request-id   (:request-id superseded-handle)
                     :origin-event (:origin-event superseded-handle)
                     :issuance     (:issuance superseded-handle)
                     :attempt      (:attempt superseded-handle)
                     :frame        (:frame superseded-handle)}
          {:keys [reply trace]} (http-reply/suppress stale-ctx current-work-id)
          summary (http-reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? superseded-handle))}
                      (:frame superseded-handle) (assoc :frame (:frame superseded-handle))))]
      (trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status   (:rf.reply/work-status summary)
                            :rf.reply/stale-reason  (:rf.reply/stale-reason summary)
                            :rf.reply/work-id       (:rf.reply/work-id summary)
                            :rf.reply/work-kind              :http
                            ;; the shared carried/current correlation facts
                            ;; the `re-frame.reply/suppress` trace computes —
                            ;; carried = superseded work-id, current =
                            ;; superseding work-id (Managed-Effects §Tracing).
                            :rf.reply/carried       (:rf.reply/carried trace)
                            :rf.reply/current       (:rf.reply/current trace)
                            :recovery               :superseded-by-fresh-request}
                     (:frame superseded-handle) (assoc :frame (:frame superseded-handle)))))))

(defn- dispatch-failure!
  "Dispatch a `:failure` reply carrying `failure` as its `:failure` slot.

  rf2-zqefg3.2 / rf2-ibksxg — the failure lowers through the canonical
  reply envelope: the `:rf.http/*` failure map becomes a `:status :error`
  (or `:status :cancelled` for an abort, `:rf.reply/work-status :timed-out` for a
  timeout) canonical reply (`http-reply/failure-reply`), a completion trace
  row is emitted from those canonical facts, and the SAME canonical reply is
  delivered to the app target — it threads through the `:after` chain +
  late-bind dispatch verbatim (no `{:kind :failure}` reshape; the compat
  layer was deleted).

  rf2-rl5tt — when the reply is silenced by an explicit `:on-failure nil`
  AND the failure is not an abort, surface it once via
  `warn-failure-swallowed!` before the (no-op) dispatch."
  [ctx failure]
  (when (on-failure-silenced? ctx)
    (warn-failure-swallowed! failure (:url ctx) (:sensitive? ctx) (:frame ctx)))
  (let [reply (http-reply/failure-reply (reply-ctx ctx) failure)]
    (emit-reply-trace! ctx reply)
    (dispatch-reply! (assoc ctx
                            :kind          :failure
                            :reply-payload reply
                            :completed-at  (:completed-at reply)))))

(defn- dispatch-success!
  "Dispatch a `:success` reply carrying `value` as its `:value` slot.

  rf2-zqefg3.2 / rf2-ibksxg — the success lowers through the canonical
  reply envelope: `value` becomes a `:status :ok` / `:rf.reply/work-status
  :completed` canonical reply (`http-reply/success-reply`), a completion
  trace row is emitted from those canonical facts, and the SAME canonical
  reply is delivered to the app target verbatim (no `{:kind :success}`
  reshape; the compat layer was deleted)."
  [ctx value]
  (let [reply (http-reply/success-reply (reply-ctx ctx) value)]
    (emit-reply-trace! ctx reply)
    (dispatch-reply! (assoc ctx
                            :kind          :success
                            :reply-payload reply
                            :completed-at  (:completed-at reply)))))

(defn emit-actor-destroy-stale-trace!
  "rf2-yrrpe2 — emit the canonical EP-0011 `:status :stale` /
  `:rf.reply/work-status :suppressed` reply-envelope trace for an actor-destroy abort
  whose reply target is OBSOLETE (it addressed the destroyed actor itself),
  WITHOUT dispatching the app target (Managed-Effects §Cancellation /
  §Stale suppression — clauses 1/2/3/4: the obsolete actor-bound target's
  reply outcome becomes `:stale`, its ledger row reaches `:suppressed`, and
  the trace stream records the carried correlation).

  Mirrors `emit-superseded-stale-trace!` but for the actor-teardown
  obsolescence case: the carried correlation is the aborted attempt's own
  work-id; there is no live successor (`:current` is nil — the actor that
  owned the target is gone). The wire-bearing slots route through the shared
  `http-reply/trace-reply` → `re-frame.reply/trace-summary` →
  `re-frame.elision/elide-wire-value` walker. Gated on `debug-enabled?` like
  the other `:rf.http/*` trace rows."
  [ctx]
  (when interop/debug-enabled?
    (let [{:keys [reply trace]} (http-reply/actor-destroy-suppress ctx)
          summary (http-reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? ctx))}
                      (:frame ctx) (assoc :frame (:frame ctx))))]
      (trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status   (:rf.reply/work-status summary)
                            :rf.reply/stale-reason  (:rf.reply/stale-reason summary)
                            :rf.reply/work-id       (:rf.reply/work-id summary)
                            :rf.reply/work-kind              :http
                            :rf.reply/carried       (:rf.reply/carried trace)
                            :rf.reply/current       (:rf.reply/current trace)
                            :recovery               :actor-destroyed-target-obsolete}
                     (:frame ctx) (assoc :frame (:frame ctx)))))))

(defn- reply-target-id
  "The head (event-id) of the reply event an abort would dispatch: the
  explicit `:on-failure` vector's head when supplied, else the originating
  event-id (`resolve-origin-event`'s head). Used to decide whether an
  actor-destroy abort's reply target is OBSOLETE (rf2-yrrpe2)."
  [ctx]
  (let [explicit (:explicit-on-failure ctx)
        value    (:value explicit)]
    (if (and (:supplied? explicit) (vector? value))
      (first value)
      (first (:origin-event ctx)))))

(defn- dispatch-aborted!
  "Emit the `:rf.http/aborted` trace + dispatch the abort reply for a
  cancelled request, honouring rf2-lxd3 supersede suppression and the
  rf2-yrrpe2 actor-destroy obsolete-target stale suppression.

  Shared by BOTH cancellation states (rf2-wj8vv):
   - the in-flight-fetch abort-fn in `run-attempt!` (a fetch / future is
     live), and
   - the backoff-sleeping abort-fn in `maybe-retry!` (no fetch is live;
     a retry timer is pending).

  Both flip their once-only `:finalised?` cell, perform the state-
  specific teardown (CLJS `.abort` / JVM `.cancel` vs `clear-timeout!`),
  clear the registry, then land here so an aborted request looks
  identical to consumers regardless of which lifecycle phase it was in.
  `reason` is `:user` / `:actor-destroyed` / `:request-id-superseded` /
  `:epoch-restored` — the genuine cancellation reasons that flip the
  handle's `:aborted?` cell and reach this path. A `:timeout` is NOT an
  abort: it classifies to `:rf.http/timeout` (a failure kind) and routes
  through `maybe-retry!` → `finalise-failure!`, never here. `ctx` must
  carry `:request-id`, `:actor-id`, `:url`, `:sensitive?`.

  Per Managed-Effects §Cancellation (rf2-yrrpe2): an `:actor-destroyed`
  abort whose reply target is OBSOLETE — its event-id names the destroyed
  actor itself (the machine-shape wrapper's `[self-id [:rf.http/failed]]`
  default, or any request whose default reply addresses its own actor) — does
  NOT deliver a live `:cancelled`/failure reply; it lowers to the canonical
  `:status :stale` / `:rf.reply/work-status :suppressed` outcome (the `:rf.http/aborted`
  trace still fires; only the app reply delivery is suppressed). An
  `:actor-destroyed` abort whose target is an ORDINARY (still-meaningful)
  event keeps the live failure delivery, as do explicit `:user` aborts."
  [ctx reason]
  ;; rf2-1u9dja — the aborted failure is self-identifying too (`:request` /
  ;; `:request-id` / `:attempt` / `:max-attempts` / `:work/id`), so both the
  ;; `:rf.http/aborted` trace and the delivered `:status :cancelled` reply name
  ;; which request was cancelled. `:rf.http/aborted` is the eighth category.
  (let [failure (self-identify {:kind     :rf.http/aborted
                                :reason   reason
                                :actor-id (:actor-id ctx)}
                               ctx)]
    (when interop/debug-enabled?
      (let [sensitive? (true? (:sensitive? ctx))
            redacted   (privacy/prepare-emit-failure
                         (assoc failure
                                :url      (:url ctx)
                                :recovery :no-recovery)
                         sensitive?
                         {:frame (:frame ctx)})]
        (trace/emit-error! :rf.http/aborted redacted)))
    ;; rf2-k47b3d — an abort is TERMINAL for this request-id (this is the
    ;; direct abort-fn choke: user / actor-destroy / supersede / epoch-restore
    ;; all land here). Evict the issuance counter so an unbounded distinct-id
    ;; space does not accumulate a permanent entry. Conditional-atomic: a
    ;; supersede has ALREADY bumped the counter past this (superseded) attempt's
    ;; issuance, so the evict skips and the live successor keeps the id; only a
    ;; genuinely-quiescent id is dropped.
    (registry/evict-issuance-on-completion! (:request-id ctx) (:issuance ctx))
    (cond
      ;; Per rf2-lxd3 (supersede) / rf2-u5kmf8 (epoch-restore) — these reasons
      ;; suppress the reply (the cancellation replaces the original outcome; the
      ;; superseded attempt's canonical stale trace is emitted by
      ;; `emit-superseded-stale-trace!` at supersede time, the restore-suppressed
      ;; attempt's by `http-registry/abort-in-flight-for-frame!`).
      (reply-suppressing-abort-reason? reason)
      nil

      ;; Per rf2-yrrpe2 — an actor-destroy abort whose reply target is
      ;; OBSOLETE (it addresses the destroyed actor itself) suppresses the app
      ;; delivery as a canonical `:status :stale` / `:rf.reply/work-status :suppressed`
      ;; outcome (Managed-Effects §Cancellation). The app target MUST NOT run.
      (and (= :actor-destroyed reason)
           (http-reply/actor-destroy-target-obsolete?
             (reply-target-id ctx) (:actor-id ctx)))
      (emit-actor-destroy-stale-trace! (reply-ctx ctx))

      ;; Other abort reasons (`:user`) and actor-destroy aborts whose
      ;; target is still meaningful (an ordinary event) deliver the
      ;; failure reply normally as a live `:status :cancelled`.
      :else
      (dispatch-failure! ctx failure))))

(defn- already-replied?
  "rf2-on7sj — the once-only reply guard. The handle carries a
  `:finalised?` atom stamped at `record-in-flight!` time; the abort
  path AND the natural-completion paths both reach `finalise-*` and
  must NOT both dispatch a reply for the same request. CAS the flag
  from false→true on first arrival; subsequent calls see `true` and
  bail. Returns truthy when the caller MUST NOT proceed (already
  replied OR no handle present at all — defensive, see below).

  Synthetic / test-path callers may pass a ctx with no `:handle`
  (e.g. some failure-shape unit tests build ctx maps directly). In
  that case the guard is a no-op — the flag's nil and the call
  proceeds. The real run-attempt! path always stamps a handle."
  [ctx]
  (when-let [flag (:finalised? (:handle ctx))]
    (not (compare-and-set! flag false true))))

(defn- aborted-snapshot
  "rf2-wez75 — abort-always-wins precedence (Mike decision a, aligned with
  Fetch AbortController / Node HTTP / JVM HttpClient / gRPC universal
  convention). Returns the abort-state map `{:reason :actor-id}` if the
  handle's `:aborted?` atom has been flipped (by either `:rf.http/managed-
  abort` user-aborts OR `abort-on-actor-destroy` per Spec 014 §Abort on
  actor destroy), else nil. Read once at finalise-* entry so the late-
  arriving decode / status / transport classification gets reclassified
  to `:rf.http/aborted` rather than racing the abort-fn's CAS — see Spec
  014 §Abort precedence (abort always wins)."
  [ctx]
  (when-let [abort-cell (:aborted? (:handle ctx))]
    @abort-cell))

(defn- aborted-failure
  "Build the `:rf.http/aborted` failure shape from an abort snapshot."
  [ctx abort-state]
  {:kind       :rf.http/aborted
   :request-id (:request-id ctx)
   :reason     (:reason abort-state)
   :actor-id   (or (:actor-id abort-state) (:actor-id ctx))})

(defn- emit-and-dispatch-failure!
  "rf2-sixs3 — the failure-finalise TAIL: rf2-bma05 redaction →
  `trace/emit-error!` → supersede-suppressed `dispatch-failure!`.

  PRECONDITION: the caller already holds the once-only `:finalised?`
  token (won the CAS / no handle) AND has already cleared the in-flight
  registry. This helper deliberately does NEITHER — it is the shared
  source of truth for the rf2-bma05 redaction shape + the supersede-
  suppression guard, called from BOTH:
    - `finalise-failure!` (the canonical site), and
    - `finalise-success!`'s sample-(2) abort path (which has already won
      the CAS and cleared the registry, so it must NOT re-enter
      `finalise-failure!` — that would double-clear + re-check
      `already-replied?`).

  Before this extraction the prepare-emit-failure / emit-error! /
  suppress sequence was duplicated inline at both sites and could drift
  (e.g. a future privacy slot added to one but not the other). Now the
  redaction contract exists once.

  Per rf2-lxd3: a supersede (`:rf.http/aborted` with
  `:reason :request-id-superseded`) emits to the trace bus but does NOT
  dispatch the `:on-failure` reply — the new request replaces the old.

  rf2-1u9dja — the failure map is made SELF-IDENTIFYING (`:request
  {:method :url}` / `:request-id` / `:attempt` / `:max-attempts` /
  `:work/id`) here, ONCE, so both the emitted trace AND the dispatched
  reply carry the identity — the trace inherits it for free and the app
  reply names which request failed."
  [ctx failure]
  (let [failure (self-identify failure ctx)]
  (when interop/debug-enabled?
    ;; rf2-bma05 — redact response-side payload slots (body, body-text,
    ;; decoded, detail) and the headers denylist before the trace surface
    ;; sees them; stamp :sensitive? when applicable. The CLJS and JVM
    ;; transports share the same contract.
    (let [sensitive? (true? (:sensitive? ctx))
          redacted   (privacy/prepare-emit-failure
                       (assoc failure
                              :request-id (:request-id ctx)
                              :url        (:url ctx)
                              :recovery   :no-recovery)
                       sensitive?
                       {:frame (:frame ctx)})
          ;; rf2-t55hxg.6 — OFF-BOX FAIL-CLOSED (EP-0015 disposition 5). An
          ;; `:rf.http/accept-failure` carries the pre-`:accept` decoded body
          ;; at `:decoded` — same off-box rule as the success `:value`: an
          ;; unschematized body is whole-sensitive and omitted off-box. Stamp
          ;; the off-box disposition forward (`:decode` is request-private and
          ;; never on the trace event) so the off-box trace-events projector
          ;; omits / classifies the `:decoded` slot. Only failures carrying a
          ;; body slot are gated.
          ;;
          ;; rf2-t55hxg.10 — the SAME disposition-5 fail-closed rule for the
          ;; RAW error-response body: an `:rf.http/http-4xx` / `:rf.http/http-5xx`
          ;; carries the raw `:body` (response body-text), and an
          ;; `:rf.http/decode-failure` carries the raw `:body-text`. By
          ;; construction these bodies are UNSCHEMATIZED — status classification
          ;; (4xx/5xx) runs BEFORE decode, and a `:rf.http/decode-failure` is the
          ;; decode step itself failing — so the request's `:decode` schema was
          ;; never (and could never be) applied to them. The off-box rule is
          ;; therefore UNCONDITIONALLY `:omit` for a raw error body, irrespective
          ;; of the per-call `:sensitive?` flag (error bodies frequently echo
          ;; request context / tokens). On-box ring stays raw; the off-box
          ;; trace-events projector omits the slot, lifted only by the
          ;; trusted-local `:include-sensitive?` opt-in.
          redacted   (cond-> redacted
                       (contains? failure :decoded)
                       (assoc :rf.http/off-box-body
                              (privacy-body/off-box-body-disposition (:decode ctx)))

                       (or (contains? failure :body)
                           (contains? failure :body-text))
                       (assoc :rf.http/off-box-body :omit))]
      (trace/emit-error! (:kind failure) redacted)))
  (cond
    ;; rf2-lxd3 / rf2-u5kmf8 — supersede / epoch-restore reasons suppress the
    ;; reply outright; the canonical stale trace for those is emitted at
    ;; supersede / restore time, not here.
    (and (= :rf.http/aborted (:kind failure))
         (reply-suppressing-abort-reason? (:reason failure)))
    nil

    ;; rf2-4teurt — the rf2-yrrpe2 actor-destroy obsolete-target suppression
    ;; must ALSO gate this abort-precedence reclassification path (the
    ;; finalise-failure! + finalise-success! sample-2 routes into this shared
    ;; tail), not only the direct `dispatch-aborted!` path. An `:actor-destroyed`
    ;; reclassification whose reply target addresses the destroyed actor ITSELF
    ;; must NOT deliver a live `:status :cancelled` reply to the dead actor — a
    ;; JVM completion-wins-the-CAS race reaches here (the abort-fn flips
    ;; `:aborted?` then loses the once-only CAS to the whenComplete thread, which
    ;; reclassifies via `aborted-snapshot`). It lowers to the canonical
    ;; `:status :stale` / `:rf.reply/work-status :suppressed` outcome, matching
    ;; `dispatch-aborted!`. The `:rf.http/aborted` error trace already fired
    ;; above; only the app delivery is replaced by the stale-suppressed trace.
    ;; `(:actor-id failure)` is the destroyed actor's id carried from the abort
    ;; snapshot (`aborted-failure`), falling back to the ctx's actor-id.
    (and (= :rf.http/aborted (:kind failure))
         (= :actor-destroyed (:reason failure))
         (http-reply/actor-destroy-target-obsolete?
           (reply-target-id ctx) (:actor-id failure)))
    (emit-actor-destroy-stale-trace! (reply-ctx ctx))

    :else
    (dispatch-failure! ctx failure))))

(defn- finalise-success! [ctx accepted]
  ;; rf2-wez75 — abort-precedence check. Two sampling points:
  ;;   (1) BEFORE the once-only CAS — covers the case where abort-fn
  ;;       already flipped `:aborted?` and lost the CAS to a
  ;;       synchronously-completing decode.
  ;;   (2) AFTER winning the CAS — covers the narrower window where
  ;;       abort-fn flips `:aborted?` between our sample-(1) read and
  ;;       our CAS. Sampling after the CAS pins the contract:
  ;;       any abort observed by a flag we hold ownership of has
  ;;       precedence over the success-classification reply.
  ;; Together these close every interleaving consistent with the
  ;; abort-always-wins rule (Spec 014 §Abort precedence). The CAS-loser
  ;; case (sample (1)) routes through finalise-failure! so the trace
  ;; emit + supersede-suppression path stays in one place.
  (if-let [abort-state (aborted-snapshot ctx)]
    (finalise-failure! ctx (aborted-failure ctx abort-state))
    (when-not (already-replied? ctx)
      (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
      ;; rf2-k47b3d — terminal completion: evict this id's issuance counter
      ;; (conditional-atomic; skips when a live re-issue has bumped past it).
      (registry/evict-issuance-on-completion! (:request-id ctx) (:issuance ctx))
      (if-let [post-cas-abort (aborted-snapshot ctx)]
        ;; Sample (2): abort flipped between our pre-CAS sample and
        ;; our CAS-win. We hold the once-only token AND have cleared the
        ;; registry above; dispatch the aborted reply directly rather
        ;; than re-entering finalise-failure! (which would double-clear
        ;; the registry and re-check `already-replied?`). The redact +
        ;; emit + supersede-suppress tail is shared with finalise-failure!
        ;; via `emit-and-dispatch-failure!` (rf2-sixs3) — single source of
        ;; truth for the rf2-bma05 redaction shape.
        (emit-and-dispatch-failure! ctx (aborted-failure ctx post-cas-abort))
        (cond
          (contains? accepted :ok)
          (dispatch-success! ctx (:ok accepted))

          (contains? accepted :failure)
          ;; rf2-ltaihw — a domain `:accept` failure (`{:failure user-map}` on a
          ;; successful 2xx decode) is an `:rf.http/accept-failure`, exactly like
          ;; the throw / malformed-return accept-failure branches in
          ;; `handle-response!`. It MUST route through `emit-and-dispatch-failure!`
          ;; (NOT `dispatch-failure!` directly): that is the shared tail which
          ;;   - emits the `:rf.http/accept-failure` failure-CATEGORY trace via
          ;;     `trace/emit-error!` (the rf2-bma05 redaction + Managed-Effects
          ;;     §Tracing structured-failure-coverage contract), and
          ;;   - stamps `:rf.http/off-box-body` for the `:decoded` slot
          ;;     (rf2-t55hxg.6 fail-closed disposition).
          ;; The earlier `dispatch-failure!` shortcut emitted ONLY the canonical
          ;; `:rf.http/replied` envelope, so the failure-category event was missed
          ;; and the decoded body rode unclassified off-box. The `:decoded` slot
          ;; carries the schema-classified body (`privacy-body/classify-decoded`)
          ;; so a `:decode`-schema sensitive slot redacts on the on-box trace —
          ;; the same on-box projection the `handle-response!` accept-failure
          ;; branches apply. We already hold the once-only `:finalised?` token and
          ;; cleared the registry above, satisfying `emit-and-dispatch-failure!`'s
          ;; precondition (the abort/natural-completion sample-(2) path next door
          ;; relies on the same).
          (emit-and-dispatch-failure!
            ctx {:kind       :rf.http/accept-failure
                 :detail     (:failure accepted)
                 :decoded    (privacy-body/classify-decoded (:decoded ctx) (:decode ctx))
                 :request-id (:request-id ctx)}))))))

(defn- finalise-failure!
  "Final-failure dispatch (after retries exhausted or non-retriable).

  Per rf2-lxd3: when a fresh request supersedes a prior one with the
  same `:request-id`, the prior request's `:on-failure` reply is NOT
  dispatched (the supersede semantic = the new request replaces the
  old one — the original `:on-failure` would race the new request's
  outcome and corrupt debounce-search patterns). The supersede event
  still emits to the trace bus (`:rf.http/aborted` with
  `:reason :request-id-superseded`); consumers wanting abort telemetry
  subscribe via `register-listener!`.

  Per rf2-on7sj: guarded by the once-only `:finalised?` CAS so the
  abort path and a later natural-completion path can't both dispatch
  a reply for the same request. The trace emit + registry clear ALSO
  live inside the guard — a doubled trace would be just as observable
  as a doubled reply on the dev surface.

  Per rf2-wez75 (Mike decision a, abort-always-wins — aligned with
  Fetch AbortController / Node HTTP / JVM HttpClient / gRPC universal
  convention): the abort-precedence check fires BEFORE the CAS. If the
  handle's `:aborted?` cell has been flipped (by user abort OR
  actor-destroy), the incoming `failure` is replaced by the canonical
  `:rf.http/aborted` shape before trace-emit and reply-dispatch. This
  closes the window where a decode-failure / transport / 5xx
  classification could synchronously win the once-only `:finalised?`
  CAS in the same scheduler tick the abort-fn was running — the
  user-visible outcome is now deterministic by classification, not by
  CAS race ordering. See Spec 014 §Abort precedence (abort always wins)."
  [ctx failure]
  (when-not (already-replied? ctx)
    (let [;; rf2-wez75 — abort-precedence reclassification. Sampled
          ;; AFTER winning the once-only CAS so any abort-fn that
          ;; flipped `:aborted?` between our caller's classification
          ;; and this point is still observed. The abort-fn itself
          ;; flips the cell BEFORE racing the CAS, so the cell is
          ;; monotonic-set under contention — once flipped, every
          ;; subsequent sample reads non-nil.
          abort-state (aborted-snapshot ctx)
          effective   (if (and abort-state
                               (not= :rf.http/aborted (:kind failure)))
                        (aborted-failure ctx abort-state)
                        failure)]
      (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
      ;; rf2-k47b3d — terminal completion: evict this id's issuance counter
      ;; (conditional-atomic; skips when a live re-issue has bumped past it, so
      ;; a superseded-then-reclassified attempt does not drop the successor's
      ;; live counter).
      (registry/evict-issuance-on-completion! (:request-id ctx) (:issuance ctx))
      ;; rf2-sixs3 — the redact + emit-error! + supersede-suppressed
      ;; dispatch tail is shared with finalise-success!'s sample-(2) abort
      ;; path via `emit-and-dispatch-failure!`. We have already won the
      ;; once-only CAS (the `already-replied?` guard above) and cleared
      ;; the registry, satisfying the helper's precondition.
      (emit-and-dispatch-failure! ctx effective))))

(defn- schedule-backoff-handle!
  "rf2-wj8vv — arm the retry backoff timer AND keep the request
  registered (and therefore cancellable) for the whole backoff window.

  Two cells coordinate the timer-fires-vs-cancel race:
   - `fired?` is a once-only CAS owned jointly by the timer callback and
     the abort-fn. Whoever wins it owns the transition out of the
     backoff state; the loser is a no-op. This is the source of truth —
     even if `clear-timeout!` races and misses (e.g. a JVM cancel that
     arrives after the timer thread has begun but before our cell flips),
     a timer callback that LOST the CAS bails before issuing the next
     attempt, so no retry ever fires after a cancel.
   - `timer-cell` forwards the scheduler handle to the abort-fn closure,
     which is constructed before the handle exists. The abort-fn reads
     it lazily and calls `interop/clear-timeout!` (a best-effort fast
     cancel layered over the authoritative `fired?` CAS).

  The backoff handle carries the same `:request-id` / `:actor-id` /
  `:url` / `:sensitive?` shape every cancellation path expects, so
  `:rf.http/managed-abort`, `abort-on-actor-destroy`, and `supersede!`
  all cancel a sleeping request through their existing `:abort-fn`
  dispatch with no path-specific code. It ALSO carries the sleeping
  attempt's `:origin-event` / `:issuance` / `:attempt` identity facts
  (rf2-hbus90), mirroring the live-fetch handle (rf2-azcmd3), so a
  `supersede!` during the backoff window can build the SUPERSEDED
  attempt's canonical `:status :stale` reply-envelope trace with the
  correct carried work-id (issuance/attempt preserved) rather than a
  default first-attempt id.

  `interop/set-timeout!` / `interop/clear-timeout!` are defined on both
  platforms (CLJS: `js/setTimeout` / `js/clearTimeout`; JVM:
  `ScheduledExecutorService` + `ScheduledFuture.cancel`), so the backoff
  scheduling and its cancellation are uniform across hosts."
  [ctx delay-ms]
  (let [{:keys [request-id actor-id]} ctx
        fired?     (atom false)
        timer-cell (atom nil)
        ;; rf2-meq28 — forward-reference cell for the stamped handle, the
        ;; same idiom as `timer-cell` here and `handle-holder` in
        ;; `run-attempt!` (rf2-lz7se). The abort-fn is constructed before
        ;; `record-in-flight!` returns the handle, so it cannot close over
        ;; `handle` lexically; it reads it lazily through `@handle-cell` at
        ;; fire time (always after registration completes).
        handle-cell (atom nil)
        abort-fn   (fn [reason]
                     ;; Win the once-only transition; a concurrent timer
                     ;; fire that loses here bails without retrying.
                     (when (compare-and-set! fired? false true)
                       (when-let [t @timer-cell]
                         (interop/clear-timeout! t))
                       ;; rf2-meq28 — drop the backoff handle from both
                       ;; indexes via the 2-arg `clear-in-flight!`, passing
                       ;; the stamped handle (through `@handle-cell`) so the
                       ;; actor-in-flight slot is cleared BY IDENTITY,
                       ;; independent of whether `request-id` is non-nil.
                       ;; This mirrors the rf2-lz7se fix at the live-fetch
                       ;; abort-fn in `run-attempt!` and the by-identity
                       ;; clear the timer-fires path below already uses.
                       ;; The earlier 1-arg form resolved by request-id and
                       ;; no-op'd on a nil id — correct only under the
                       ;; implicit, load-bearing invariant that an anonymous
                       ;; (request-id-less, issued-from-actor) request can be
                       ;; aborted SOLELY via actor-destroy (which eager-
                       ;; dissocs the actor slot first). An anonymous request
                       ;; can carry a `:retry` config and sit in this backoff
                       ;; window with `actor-id` set / `request-id` nil; any
                       ;; future non-pre-clearing trigger (frame-level
                       ;; abort-all, timeout-driven abort of a sleeping retry)
                       ;; would strand the handle. Passing the handle drops
                       ;; that fragility: the 2-arg `remove-from-actor-index!`
                       ;; is an identity-based vector remove that no-ops
                       ;; against an already-cleared (or absent) slot, so it
                       ;; is a safe idempotent no-op on the actor-destroy path
                       ;; while closing the gap for any future trigger.
                       (registry/clear-in-flight! request-id @handle-cell)
                       (dispatch-aborted! ctx reason)))
        handle     (registry/record-in-flight!
                     request-id actor-id
                     {:abort-fn   abort-fn
                      :url        (:url ctx)
                      :sensitive? (true? (:sensitive? ctx))
                      ;; Carry the originating frame for the actor-destroy abort
                      ;; trace's frame stamp. HTTP carrier redaction is
                      ;; process-global now (the :rf.http/managed `:carriers`
                      ;; registration, EP-0025) — no longer frame-resolved.
                      :frame      (:frame ctx)
                      ;; rf2-hbus90 — carry the SLEEPING attempt's reply-ctx
                      ;; identity facts on the backoff handle, exactly as the
                      ;; live-fetch handle in `run-attempt!` does (rf2-azcmd3).
                      ;; `supersede!` returns this handle when a same-id
                      ;; request supersedes the request DURING its backoff
                      ;; window, and `emit-superseded-stale-trace!` builds the
                      ;; carried (superseded) work-id from the handle's
                      ;; `:origin-event` / `:issuance` / `:attempt`. Without
                      ;; these the carried work-id defaulted to issuance 1 /
                      ;; attempt 1 — a phantom first-attempt id that breaks
                      ;; reply-envelope/trace correlation whenever the sleeping
                      ;; retry is at attempt > 1 (or issuance > 1). The handle
                      ;; represents the just-failed attempt (`ctx`'s `:attempt`
                      ;; / `:issuance`); the timer's `(update :attempt inc)`
                      ;; advances to the NEXT attempt only when it fires.
                      :origin-event (:origin-event ctx)
                      :issuance     (:issuance ctx)
                      :attempt      (:attempt ctx)})
        ;; rf2-meq28 — publish the stamped handle so the abort-fn closure
        ;; (defined above, before `handle` was bound) can pass it to the
        ;; 2-arg `clear-in-flight!` via `@handle-cell`. The reset! happens
        ;; synchronously here, before the timer is armed, so the cell is
        ;; always populated by the time any abort can fire.
        _          (reset! handle-cell handle)
        ;; Schedule AFTER registering so the request is cancellable the
        ;; instant the timer is armed. The callback wins/loses the same
        ;; `fired?` CAS: on a win it clears its own handle and proceeds;
        ;; on a loss (a cancel beat it) it does nothing.
        timer      (interop/set-timeout!
                     (fn []
                       (when (compare-and-set! fired? false true)
                         (registry/clear-in-flight! request-id handle)
                         (run-attempt! (-> ctx
                                           (dissoc :handle)
                                           (update :attempt inc)))))
                     delay-ms)]
    (reset! timer-cell timer)
    ;; rf2-wj8vv — a cancel that arrived between `record-in-flight!` and
    ;; this `reset!` already won `fired?` (so the timer callback will
    ;; no-op) but may have read `timer-cell` as nil and skipped
    ;; `clear-timeout!`. Re-check and cancel the now-known timer so the
    ;; scheduler doesn't carry a doomed task for the full backoff.
    (when @fired?
      (interop/clear-timeout! timer))
    nil))

(defn- maybe-retry!
  "Decide between retry, immediate-final-failure, and successful-completion.
  `failure` is the failure map for the just-finished attempt.

  Per rf2-wez75 (abort always wins): a request whose handle's
  `:aborted?` cell has been flipped MUST NOT be retried, regardless of
  the just-classified failure category or the caller's `:retry :on`
  set. A user/actor-destroy abort that arrives mid-decode-failure
  retry-eligible classification would otherwise schedule a fresh
  attempt against a request the caller has already cancelled — a
  contract violation under Spec 014 §Abort precedence. Routing the
  aborted request through `finalise-failure!` lets the in-flight
  reclassification (built into finalise-failure!'s abort-snapshot
  read) replace the would-be retry-eligible failure with the canonical
  `:rf.http/aborted` shape.

  Per rf2-wj8vv (backoff window is cancellable): when a retry is
  scheduled, the request stays REGISTERED for the whole backoff window
  under a `schedule-backoff-handle!` handle whose `:abort-fn` cancels
  the pending retry timer and clears the registry, rather than firing
  a network abort (there is no live fetch between attempts). All three
  cancellation paths — `:rf.http/managed-abort`, `abort-on-actor-
  destroy`, `supersede!` — resolve a handle and fire its `:abort-fn`,
  so registering the backoff handle is sufficient to make the sleeping
  request cancellable through every path with no path-specific code.
  Previously `maybe-retry!` cleared the handle from both indexes BEFORE
  arming the timer, leaving the request invisible to every cancellation
  path for the whole backoff — the timer fired regardless."
  [ctx failure]
  (let [{:keys [retry attempt request-id]} ctx
        {:keys [on max-attempts backoff]} retry
        on-set      (or on #{})
        kind        (:kind failure)
        aborted?    (some? (aborted-snapshot ctx))
        can-retry?  (and (some? max-attempts)
                         (> max-attempts attempt)
                         (contains? on-set kind)
                         (not= :rf.http/aborted kind)
                         (not aborted?))]
    (if can-retry?
      (let [delay-ms (encoding/compute-backoff-ms (or backoff {}) attempt)]
        (when interop/debug-enabled?
          (trace/emit! :info :rf.http/retry-attempt
                       ;; rf2-t55hxg.10 — the retry-attempt trace nests the
                       ;; intermediate `failure` under `:failure`; a retryable
                       ;; `:rf.http/http-4xx` / `:rf.http/http-5xx` carries the
                       ;; raw `:body` there. Stamp the off-box `:omit`
                       ;; disposition forward (the raw error body is
                       ;; unschematized by construction — fail-closed) so the
                       ;; off-box trace-events projector omits the nested
                       ;; `[:failure :body]` slot off-box.
                       (cond-> (privacy/prepare-emit-tags
                                 {:request-id      request-id
                                  :url             (:url ctx)
                                  :attempt         attempt
                                  :max-attempts    max-attempts
                                  :failure         failure
                                  :next-backoff-ms delay-ms}
                                 (true? (:sensitive? ctx))
                                 {:frame (:frame ctx)})
                         (or (contains? failure :body)
                             (contains? failure :body-text))
                         (assoc :rf.http/off-box-body :omit))))
        ;; Clear the prior attempt's live-fetch handle from both indexes;
        ;; `schedule-backoff-handle!` immediately re-registers a fresh
        ;; backoff handle so the request is never invisible to a
        ;; cancellation path. Without the clear the actor-in-flight index
        ;; would accumulate stale handles across retries (rf2-wvkn).
        (registry/clear-in-flight! request-id (:handle ctx))
        (schedule-backoff-handle! ctx delay-ms))
      (do
        ;; rf2-upexd.3 — terminal failure: emit the final `:rf.http/retry-
        ;; attempt` trace (with `:next-backoff-ms nil`) ONLY when retries
        ;; were actually part of this request's lifecycle. The spec (§Retry
        ;; × `:on-failure` semantics) ties the trace to "each intermediate
        ;; attempt" — the final exhaustion event is meaningful only when a
        ;; retry sequence happened. The previous guard `(> max-attempts 1)`
        ;; fired for ANY terminal failure under a >1-attempt policy, even a
        ;; NON-retry-eligible failure on attempt 1 (e.g. a
        ;; `:rf.http/decode-failure` under `:retry {:on #{:rf.http/http-5xx}
        ;; :max-attempts 3}`) — no retry ever happened, yet pair tools saw a
        ;; phantom retry-attempt. Tighten to:
        ;;   - `(> attempt 1)`         — at least one retry actually fired
        ;;                               (we are on attempt 2+), OR
        ;;   - `(contains? on-set kind)` — this failure WAS retry-eligible
        ;;                               but attempts are now exhausted (the
        ;;                               documented final-exhaustion case).
        ;; A non-retried, non-eligible terminal failure emits nothing.
        (when (and interop/debug-enabled?
                   (some? max-attempts)
                   (> max-attempts 1)
                   (or (> attempt 1)
                       (contains? on-set kind)))
          ;; rf2-t55hxg.10 — same off-box `:omit` stamp on the terminal
          ;; exhaustion retry-attempt trace: its nested `:failure` may carry a
          ;; raw `:body` (a retry-eligible 4xx/5xx that exhausted attempts).
          (trace/emit! :info :rf.http/retry-attempt
                       (cond-> (privacy/prepare-emit-tags
                                 {:request-id      request-id
                                  :url             (:url ctx)
                                  :attempt         attempt
                                  :max-attempts    max-attempts
                                  :failure         failure
                                  :next-backoff-ms nil}
                                 (true? (:sensitive? ctx))
                                 {:frame (:frame ctx)})
                         (or (contains? failure :body)
                             (contains? failure :body-text))
                         (assoc :rf.http/off-box-body :omit))))
        (finalise-failure! ctx failure)))))

(defn- handle-response!
  "Shared 4xx/5xx/2xx/else response cascade. `result` is the platform
  transport's normalised response map (`{:ok? :status :status-text
  :headers :body-text}`). Per Spec 014 §Failure categories: status
  classification runs BEFORE decode. 4xx/5xx route to
  `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body-text —
  decode never fires on a non-success response, so an HTML 404 from a
  JSON endpoint classifies as `:rf.http/http-4xx` (not
  `:rf.http/decode-failure`). Decode runs only on 2xx; if that fails
  the failure category is `:rf.http/decode-failure`."
  [ctx result]
  (let [{:keys [decode accept request-id]} ctx
        {:keys [ok? status status-text headers body-text body-binary]} result]
    (cond
      (and (>= status 400) (< status 500))
      (maybe-retry! ctx
                    {:kind        :rf.http/http-4xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      (>= status 500)
      (maybe-retry! ctx
                    {:kind        :rf.http/http-5xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      ok?
      ;; rf2-rznrz — DECODE and ACCEPT are SEPARATE phases (Spec 014
      ;; §Classification order steps 3 + 4). They were previously fused
      ;; under one try/catch, so an `:accept` throw was misclassified as
      ;; `:rf.http/decode-failure` (step-4 error masquerading as step-3),
      ;; and a malformed `:accept` return (nil / a map without :ok/:failure)
      ;; fell through `finalise-success!`'s `cond` with no matching branch —
      ;; clearing the in-flight request and dispatching NO reply, so the
      ;; caller hung forever. Now:
      ;;
      ;;   - the decode try/catch catches ONLY decoder exceptions →
      ;;     `:rf.http/decode-failure`;
      ;;   - accept runs in its OWN try/catch and its return is SHAPE-
      ;;     VALIDATED (`encoding/valid-accept-return?`); an accept throw OR
      ;;     a malformed return classifies as `:rf.http/accept-failure`
      ;;     (the closed-set canonical bad-accept category) and ALWAYS
      ;;     dispatches a reply.
      (let [decode-result
            (try
              {:decoded
               (decode/decode-response-body
                 {:body-text        body-text
                  ;; rf2-5zj6t — the CLJS transport reads a native
                  ;; Blob / ArrayBuffer / FormData for binary decode
                  ;; modes and rides it here; `decode-response-body`
                  ;; returns it verbatim for `:blob` / `:array-buffer`
                  ;; / `:form-data`. Absent on the text path / on JVM.
                  :body-binary      body-binary
                  :headers          headers
                  :decode           decode
                  ;; rf2-wu1n5 — thread the keyword-cap from the
                  ;; normalised ctx into the decoder; nil means
                  ;; the reader uses its default.
                  :max-decoded-keys (:max-decoded-keys ctx)})}
              (catch #?(:clj Throwable :cljs :default) e
                {:decode-error e}))]
        (if-let [e (:decode-error decode-result)]
          (let [d (ex-data e)
                ;; rf2-mdxd7 — the keyword-interning DoS cap (Spec 014
                ;; §Keyword-interning cap) throws a structured
                ;; `:rf.error/malformed-json` ex-info carrying `:cause`
                ;; (`:too-many-keys`) and `:limit` (the configured cap).
                ;; The spec (lines 145, 285, 289) mandates the overflow
                ;; surface as `:rf.http/decode-failure` with
                ;; `:reason :too-many-keys` and that `:limit`. Propagate
                ;; both onto the failure map so a caller branching on
                ;; `:reason` sees the documented shape (and a DoS-cap
                ;; overflow is programmatically distinguishable from an
                ;; ordinary JSON syntax error — `:schema-validation-failure?`
                ;; is false for both). `:reason` carries the ex-data's
                ;; `:cause` keyword; the human-readable string `:cause`
                ;; slot below is the ex-message, unchanged.
                malformed? (= :rf.error/malformed-json (:rf.error/id d))
                reason     (when malformed? (:cause d))
                limit      (when malformed? (:limit d))]
            (maybe-retry!
              ctx
              (cond-> {:kind                       :rf.http/decode-failure
                       :body-text                  body-text
                       :cause                      #?(:clj (.getMessage ^Throwable e)
                                                      :cljs (.-message e))
                       :schema-validation-failure? (= :rf.error/http-schema-validation-failed
                                                      (:rf.error/id d))}
                ;; A bare JSON syntax error (`:rf.error/malformed-json`
                ;; with no `:cause`/`:limit`) carries no `:reason`/`:limit`
                ;; slots — only the cap-overflow shape does.
                (some? reason) (assoc :reason reason)
                (some? limit)  (assoc :limit limit))))
          ;; ---- ACCEPT phase (rf2-rznrz) -----------------------------
          ;; Decode succeeded. Run `:accept` in its OWN try/catch and
          ;; shape-validate the return. Three outcomes:
          ;;   - returns `{:ok v}` / `{:failure m}` → hand to
          ;;     `finalise-success!`, which dispatches the success reply
          ;;     or the `:rf.http/accept-failure` domain-failure reply;
          ;;   - throws → `:rf.http/accept-failure` (the throw is the
          ;;     app's accept bug; misclassifying it as decode-failure
          ;;     pointed telemetry at the wrong phase);
          ;;   - returns a malformed shape (nil / non-map / map without
          ;;     exactly one of :ok/:failure) → `:rf.http/accept-failure`.
          ;;     Previously this stranded the request with no reply.
          ;; `:rf.http/accept-failure` is non-retryable by construction
          ;; (Spec 014 §Failure categories), so we route straight to
          ;; `finalise-failure!` — never `maybe-retry!`.
          (let [decoded (:decoded decode-result)
                ctx'    (assoc ctx :decoded decoded)
                ;; rf2-ppkh3v — RESPONSE-BODY classification (EP-0015 §8,
                ;; issue 5). The pre-`:accept` decoded body rides at
                ;; `:decoded` on an `:rf.http/accept-failure` trace; apply
                ;; the request's `:decode` schema per-slot `:sensitive?`
                ;; marks so a sensitive body slot redacts even when the
                ;; request was not declared per-call `:sensitive?`. A
                ;; non-schema `:decode` is a no-op here (the per-call flag /
                ;; off-box disposition govern an unschematized body).
                decoded' (privacy-body/classify-decoded decoded decode)
                outcome (try
                          {:accepted (encoding/run-accept accept decoded)}
                          (catch #?(:clj Throwable :cljs :default) e
                            {:accept-error e}))]
            (cond
              (:accept-error outcome)
              (finalise-failure!
                ctx'
                {:kind       :rf.http/accept-failure
                 :detail     {:rf.http/bad-accept :threw
                              :message #?(:clj (.getMessage ^Throwable (:accept-error outcome))
                                          :cljs (.-message ^js (:accept-error outcome)))}
                 :decoded    decoded'
                 :request-id request-id})

              (encoding/valid-accept-return? (:accepted outcome))
              (finalise-success! ctx' (:accepted outcome))

              :else
              (finalise-failure!
                ctx'
                {:kind       :rf.http/accept-failure
                 :detail     {:rf.http/bad-accept :malformed-return
                              :returned (:accepted outcome)}
                 :decoded    decoded'
                 :request-id request-id})))))

      :else
      ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx that the
      ;; runtime didn't follow) — surface as 4xx-shaped failure with
      ;; the raw body-text. Per rf2-ee38b.7 this routes through
      ;; `maybe-retry!` (was `finalise-failure!` directly) so the retry
      ;; semantics match the `:rf.http/http-4xx` category label: a caller
      ;; with `:retry {:on #{:rf.http/http-4xx} …}` retries a real 4xx,
      ;; and this synthetic-4xx (1xx/3xx) now retries consistently rather
      ;; than silently never retrying. The branch is rare in practice (the
      ;; JVM `NORMAL` / Fetch stacks follow 3xx by default), but the
      ;; inconsistency is removed.
      (maybe-retry!
        ctx
        {:kind        :rf.http/http-4xx
         :status      status
         :status-text status-text
         :body        body-text
         :headers     headers}))))

(defn- prepare-body!
  "rf2-065xo — the managed request-preparation PHASE. Realizes the
  `:body` (re-invoking the thunk when `:body` is a `(fn body)`, per Spec
  014 §Body encoding — each attempt obtains a fresh handle) and encodes
  it, computing the effective request `:headers` (adding the encoder's
  `Content-Type` when the request did not already set one).

  Returns either:
   - `{:ok {:enc-body … :headers …}}` on success, or
   - `{:error failure}` where `failure` is a canonical `:rf.http/transport`
     shape. `:rf.http/transport` is the spec's category for an error
     \"before the HTTP transaction completed\" (Spec 014 §Failure
     categories) — body realization / encoding is exactly such a
     pre-transaction failure, so no new (spec-closed) category is minted.
     The shape carries a `:stage :request-prep` discriminator so telemetry
     can tell a prep failure apart from a network-transport failure, plus
     the usual `:message` / `:cause` tags the category documents.

  A throwing body thunk or a non-serialisable body (e.g. a value
  `JSON.stringify` / form-encode rejects) is caught here rather than
  escaping `run-attempt!` as a generic `:rf.error/fx-handler-exception`.
  `:rf.http/transport` is in the retryable subset, so a caller with
  `:retry {:on #{:rf.http/transport} …}` re-runs the whole attempt
  (re-invoking the thunk for a fresh handle) — consistent with the
  thunk-per-attempt contract."
  [request]
  (try
    (let [body          (let [b (:body request)] (if (fn? b) (b) b))
          [enc-body ct] (encoding/encode-body body (:request-content-type request))
          headers       (cond-> (or (:headers request) {})
                          (and ct (nil? (decode/content-type-of (:headers request))))
                          (assoc "Content-Type" ct))]
      {:ok {:enc-body enc-body :headers headers}})
    (catch #?(:clj Throwable :cljs :default) e
      {:error {:kind    :rf.http/transport
               :stage   :request-prep
               :message #?(:clj (.getMessage ^Throwable e)
                           :cljs (.-message ^js e))
               :cause   #?(:clj (.getName (class e))
                           :cljs (some-> (.-name ^js e)))}})))

(defn run-attempt!
  "Issue one HTTP attempt, then dispatch reply or retry. Platform-specific
  transport wiring (Fetch Promise on CLJS, CompletableFuture on JVM) is
  reader-conditional; the response cascade, retry decision, in-flight
  registry interaction, privacy redaction, and supersede suppression are
  all shared.

  rf2-065xo — body realization + encoding run as a managed preparation
  PHASE (`prepare-body!`) AFTER the in-flight handle is registered, so a
  throwing body thunk / encode failure is routed through the normal
  `maybe-retry!` → final-failure path (a `:rf.http/transport` reply to
  `:on-failure`) rather than escaping as `:rf.error/fx-handler-exception`."
  [ctx]
  (let [{:keys [request timeout-ms request-id actor-id abort-signal]} ctx
        method   (or (:method request) :get)
        url      (encoding/merge-params (:url request) (:params request))
        ;; rf2-065xo — body realization + encoding are DEFERRED past handle
        ;; registration and run inside `prepare-body!` as a managed
        ;; request-preparation PHASE (see below). Realizing a `:body` thunk
        ;; or encoding the body here, in the binding `let`, ran them BEFORE
        ;; `record-in-flight!` and the platform transport try/catch — a
        ;; throwing thunk or `encode-body` failure escaped `run-attempt!`
        ;; entirely and surfaced as a generic `:rf.error/fx-handler-exception`
        ;; (the fx walk's catch-all in `fx.cljc`), stranding the caller with
        ;; no `:on-failure` reply and skipping retry/abort semantics. Moving
        ;; the work below the handle means a prep throw is caught, classified
        ;; as `:rf.http/transport` (the spec category for an error "before the
        ;; HTTP transaction completed" — closed set, no new category), and
        ;; routed through the normal `maybe-retry!` path so `:on-failure`,
        ;; retry policy, trace metadata, abort precedence, and sensitivity
        ;; redaction all stay consistent.
        ctx-no-handle (assoc ctx :url url)
        ;; CLJS: per rf2-1jcpm always own an internal AbortController so
        ;; the per-attempt timeout fires even when the caller supplied
        ;; `:abort-signal`. `cljs-fetch` forwards the caller's signal into
        ;; this controller via `addEventListener "abort"`. JVM: no per-
        ;; attempt controller — abort signalling is host-specific and
        ;; lives outside the sendAsync future.
        #?@(:cljs [internal-controller (js/AbortController.)])
        ;; rf2-on7sj — once-only reply guard. The abort path AND the
        ;; subsequent natural-completion path (Fetch .catch on CLJS,
        ;; CompletableFuture .whenComplete on JVM) both fan into
        ;; finalise-*; without this CAS each slow-server abort would
        ;; dispatch TWO replies for the same request — first the
        ;; synthesised :rf.http/aborted (immediate), then the natural-
        ;; completion reply (much later, after the underlying transport
        ;; drains). The flag is stamped on the handle map; finalise-*
        ;; reads it via `(:finalised? (:handle ctx))` and the abort-fn
        ;; closure CASes the same atom lexically before dispatching the
        ;; abort reply itself. JVM additionally cancels the underlying
        ;; CompletableFuture so the work actually stops (not just the
        ;; reply path).
        finalised? (atom false)
        ;; rf2-wez75 — abort-precedence cell. The abort-fn flips this
        ;; BEFORE racing the once-only `:finalised?` CAS, so even if a
        ;; synchronously-completing decode wins the CAS, the finalise-*
        ;; entry sees the abort snapshot and reclassifies the reply as
        ;; `:rf.http/aborted` per Spec 014 §Abort precedence (abort
        ;; always wins). The cell carries the abort reason map so the
        ;; canonical reply shape (and the `:actor-id` slot, when
        ;; actor-destroy was the source) is reconstructable inside
        ;; finalise-failure! without re-deriving from `failure`.
        aborted?   (atom nil)
        ;; rf2-on7sj (JVM) — the abort-fn closure must `.cancel cf true`
        ;; on the underlying CompletableFuture, but cf only exists AFTER
        ;; this binding (built inside the try-body below). Forward via a
        ;; one-cell atom that the JVM body fills after construction; the
        ;; abort-fn reads it lazily through `@cf-holder`.
        #?@(:clj  [cf-holder (atom nil)])
        ;; rf2-lz7se — forward-reference cell for the stamped handle.
        ;; The abort-fn's registry cleanup must pass the handle to the
        ;; 2-arg `clear-in-flight!` so the actor-in-flight slot is cleared
        ;; by identity regardless of whether `request-id` is non-nil
        ;; (anonymous-from-actor requests carry a nil id and are indexed
        ;; ONLY in actor-in-flight). But the handle is the value being
        ;; computed by `record-in-flight!` below, so it cannot be a
        ;; lexical reference inside the closure. Forward it through this
        ;; one-cell atom — same idiom as `cf-holder` — filled immediately
        ;; after `record-in-flight!` returns the stamped handle; the
        ;; abort-fn reads it lazily via `@handle-holder` at fire time
        ;; (always after registration completes).
        handle-holder (atom nil)
        ;; Register the abort handle. The handle ref is stamped into ctx
        ;; so finalise-* can clear it from both indexes without needing
        ;; the request-id (handles anonymous-from-actor requests too —
        ;; rf2-wvkn).
        handle   (registry/record-in-flight!
                   request-id actor-id
                   {:abort-fn (fn [reason]
                                ;; rf2-wez75 — flip `:aborted?` BEFORE the
                                ;; once-only CAS so that a concurrently-
                                ;; running finalise-* (decode-failure,
                                ;; transport, http-5xx, success) that wins
                                ;; the CAS still reads the abort snapshot
                                ;; on entry and reclassifies. The reset!
                                ;; is idempotent across re-entrant aborts
                                ;; (supersede + actor-destroy in rapid
                                ;; succession): subsequent flips just
                                ;; overwrite with the same shape, which is
                                ;; fine because the once-only CAS below
                                ;; collapses everything after the first
                                ;; pass into a no-op. We do NOT CAS this
                                ;; cell because a racing finalise-* that
                                ;; samples it as nil and then later sees
                                ;; it as set is exactly the window we're
                                ;; closing — abort always wins regardless
                                ;; of which side flipped first.
                                (reset! aborted? {:reason   reason
                                                  :actor-id actor-id})
                                ;; rf2-on7sj — single-shot CAS guard.
                                ;; A re-entrant abort (e.g. supersede +
                                ;; actor-destroy firing in rapid
                                ;; succession against the same handle)
                                ;; is a no-op past the first call.
                                (when (compare-and-set! finalised? false true)
                                  #?(:cljs
                                     (try
                                       (when internal-controller
                                         (.abort internal-controller (clj->js reason)))
                                       (catch :default _ nil))
                                     :clj
                                     ;; rf2-on7sj — cancel the underlying
                                     ;; future so it stops running. The
                                     ;; CompletableFuture's whenComplete
                                     ;; will still fire (with a
                                     ;; CancellationException), but
                                     ;; finalise-failure! is guarded by
                                     ;; the same :finalised? flag and
                                     ;; bails before re-emitting.
                                     ;; `true` = may-interrupt-if-running.
                                     (when-let [^CompletableFuture cf @cf-holder]
                                       (try (.cancel cf true)
                                            (catch Throwable _ nil))))
                                  ;; rf2-lz7se — Registry cleanup happens
                                  ;; here once; finalise-failure! is
                                  ;; bypassed. Pass the stamped handle (via
                                  ;; `@handle-holder`) so the 2-arg form
                                  ;; walks BOTH indexes by identity,
                                  ;; independent of whether `request-id`
                                  ;; is non-nil. This is unconditionally
                                  ;; correct for every abort trigger
                                  ;; (managed-abort, supersede,
                                  ;; actor-destroy) AND for anonymous
                                  ;; (request-id-less) requests, whose
                                  ;; handle is indexed ONLY in
                                  ;; actor-in-flight. The earlier 1-arg
                                  ;; form resolved by request-id and
                                  ;; no-op'd on a nil id — correct only
                                  ;; under the implicit, load-bearing
                                  ;; invariant that an anonymous request
                                  ;; can be aborted solely via
                                  ;; actor-destroy (which pre-clears the
                                  ;; actor slot). Passing the handle drops
                                  ;; that fragility: the 2-arg
                                  ;; remove-from-actor-index! is an
                                  ;; identity-based vector remove that
                                  ;; no-ops against an already-cleared
                                  ;; (or absent) slot, so it is a safe
                                  ;; idempotent no-op on the actor-destroy
                                  ;; path (its eager dissoc already emptied
                                  ;; the vector) while closing the gap for
                                  ;; any future trigger that does not
                                  ;; pre-clear.
                                  (registry/clear-in-flight! request-id @handle-holder)
                                  ;; rf2-on7sj / rf2-wj8vv — the abort-fn
                                  ;; dispatches a synthesised reply directly
                                  ;; (no finalise-failure! re-entry). The
                                  ;; shared `dispatch-aborted!` reuses the
                                  ;; same trace-emit + reply shape the
                                  ;; backoff-window abort uses, so abort +
                                  ;; natural failures look identical to
                                  ;; consumers regardless of lifecycle phase.
                                  ;; Bypassing finalise-failure! keeps the
                                  ;; cancel + CAS + dispatch sequence atomic
                                  ;; and gives the once-only guard a single
                                  ;; owner.
                                  (dispatch-aborted! ctx-no-handle reason)))
                    :url url
                    ;; rf2-on7sj — once-only reply guard, see comment above.
                    :finalised? finalised?
                    ;; rf2-wez75 — abort-precedence cell read at finalise-*
                    ;; entry. See the `aborted?` binding above.
                    :aborted?   aborted?
                    ;; rf2-bma05 — propagate the :sensitive? flag onto
                    ;; the in-flight handle so the actor-destroy abort
                    ;; emit (lives in the registry ns) can stamp the
                    ;; trace event without re-resolving registration
                    ;; metadata.
                    :sensitive? (true? (:sensitive? ctx))
                    ;; Carry the originating frame for the actor-destroy abort
                    ;; trace's frame stamp. HTTP carrier redaction is
                    ;; process-global now (the :rf.http/managed `:carriers`
                    ;; registration, EP-0025) — no longer frame-resolved.
                    :frame      (:frame ctx)
                    ;; rf2-azcmd3 — carry the superseded attempt's reply-ctx
                    ;; identity facts on the handle so `supersede!` can build
                    ;; this (now-stale) attempt's canonical `:status :stale`
                    ;; reply-envelope trace when a fresh request replaces it.
                    :origin-event (:origin-event ctx)
                    :issuance     (:issuance ctx)
                    :attempt      (:attempt ctx)})
        ;; rf2-lz7se — publish the stamped handle so the abort-fn closure
        ;; (defined above, before `handle` was bound) can pass it to the
        ;; 2-arg `clear-in-flight!` via `@handle-holder`. The reset!
        ;; happens synchronously here, before any fetch is issued, so the
        ;; cell is always populated by the time any abort can fire.
        _        (reset! handle-holder handle)
        ctx'     (assoc ctx-no-handle :handle handle)
        ;; rf2-065xo — managed request-preparation PHASE. Runs AFTER the
        ;; handle is registered (so abort precedence, the once-only reply
        ;; guard, and registry cleanup all apply to a prep failure exactly
        ;; as they do to a network failure) but BEFORE the platform fetch is
        ;; issued. A throwing body thunk / encode failure becomes a
        ;; `:rf.http/transport` reply routed through `maybe-retry!` rather
        ;; than escaping as `:rf.error/fx-handler-exception`.
        prep     (prepare-body! request)]
    (if-let [prep-error (:error prep)]
      ;; Body-prep failed — route through the normal retry/final-failure
      ;; path on `ctx'` (which carries the registered `:handle`), so retry
      ;; (when configured for `:rf.http/transport`), trace metadata, abort
      ;; precedence, and the `:on-failure` reply shape all stay consistent.
      (maybe-retry! ctx' prep-error)
      (let [{:keys [enc-body headers]} (:ok prep)]
        #?(:cljs
           (-> (transport-cljs/cljs-fetch
                           {:method              method
                            :url                 url
                            :headers             headers
                            :body                enc-body
                            :credentials         (:credentials request)
                            :mode                (:mode request)
                            :redirect            (:redirect request)
                            :cache               (:cache request)
                            :referrer            (:referrer request)
                            :integrity           (:integrity request)
                            :timeout-ms          timeout-ms
                            :abort-signal        abort-signal
                            :internal-controller internal-controller
                            ;; rf2-5zj6t — the transport picks the Fetch
                            ;; body-reader (`.text()` vs `.blob()` /
                            ;; `.arrayBuffer()` / `.formData()`) from the
                            ;; resolved decode mode; pass it through.
                            :decode              (:decode ctx)
                            ;; rf2-f5pguu — thread `:sensitive?` + `:frame` so a
                            ;; Fetch `Headers.append` validation throw surfaces
                            ;; as a redacted `:rf.warning/http-header-invalid`
                            ;; trace (URL privacy-composed, frame-local carriers
                            ;; honoured) instead of escaping as a generic
                            ;; `:rf.error/fx-handler-exception`. Same shape the
                            ;; JVM branch threads into `jvm-fetch` below.
                            :sensitive?          (true? (:sensitive? ctx))
                            :frame               (:frame ctx)})
               (.then (fn [result] (handle-response! ctx' result)))
               ;; rf2-r40km — pass `url` so `classify-cljs-error` can
               ;; distinguish `:rf.http/cors` from `:rf.http/transport`
               ;; via the cross-origin heuristic.
               ;; rf2-on7sj — when the abort-fn fired and dispatch-aborted!
               ;; already replied, the Fetch promise still rejects (because
               ;; `.abort internal-controller` rejects the underlying
               ;; fetch); this .catch would call `maybe-retry!` →
               ;; `finalise-failure!` for a second pass. The once-only
               ;; `:finalised?` CAS on the handle short-circuits the second
               ;; dispatch inside finalise-*, so this path stays as the
               ;; natural-completion sink without a bespoke "did we abort?"
               ;; check here.
               (.catch (fn [err]
                         (maybe-retry! ctx' (transport-cljs/classify-cljs-error err url)))))
           :clj
           ;; rf2-a3wxe — monotonic start mark for the per-host timeout
           ;; `:elapsed-ms`. `System/nanoTime` is the JDK's monotonic clock
           ;; (immune to wall-clock adjustments); the delta is converted to
           ;; whole milliseconds at the failure site. Captured BEFORE the
           ;; request is issued so the measured elapsed spans the whole attempt.
           (let [started-ns (System/nanoTime)
                 elapsed-ms #(quot (- (System/nanoTime) started-ns) 1000000)]
             (try
               (let [^CompletableFuture cf
                     (transport-jvm/jvm-fetch
                                {:method     method
                                 :url        url
                                 :headers    headers
                                 :body       enc-body
                                 :timeout-ms timeout-ms
                                 ;; rf2-a3wxe — thread the resolved `:decode`
                                 ;; so `jvm-fetch` can read the response body
                                 ;; with `ofByteArray` for binary decode modes
                                 ;; (`:blob` / `:array-buffer` / `:form-data`)
                                 ;; and ride the raw bytes under `:body-binary`
                                 ;; instead of the lossy String fallback.
                                 :decode     (:decode ctx)
                                 ;; rf2-ee38b.7 — honour the spec's `:redirect`
                                 ;; envelope key on the JVM (default `:follow`).
                                 ;; Selects the redirect-policy-specific client.
                                 :redirect   (:redirect request)
                                 :sensitive? (true? (:sensitive? ctx))
                                 ;; rf2-ppkh3v — the originating frame so the
                                 ;; per-header validation warning's URL
                                 ;; redaction consults the frame's frame-local
                                 ;; HTTP carriers (EP-0015 §3).
                                 :frame      (:frame ctx)})]
                 ;; rf2-on7sj — publish cf to the abort-fn closure's holder
                 ;; BEFORE wiring whenComplete. A racing abort that arrives
                 ;; between `jvm-fetch` returning and `.whenComplete`
                 ;; registering still finds cf in the holder and can cancel
                 ;; it.
                 (reset! cf-holder cf)
                 ;; rf2-on7sj — the whenComplete callback fires even after
                 ;; `.cancel cf true`: the cancel completes-exceptionally
                 ;; with a CancellationException, which routes through this
                 ;; BiConsumer as `throwable`. `maybe-retry!` →
                 ;; `finalise-failure!` is then guarded by the once-only
                 ;; `:finalised?` CAS (the abort-fn already finalised), so
                 ;; the abort's reply is the only one that ever reaches the
                 ;; user.
                 (.whenComplete cf
                                (reify java.util.function.BiConsumer
                                  (accept [_ result throwable]
                                    (if throwable
                                      (maybe-retry! ctx' (transport-jvm/classify-jvm-error throwable timeout-ms (elapsed-ms)))
                                      (handle-response! ctx' result))))))
               (catch Throwable t
                 (maybe-retry! ctx' (transport-jvm/classify-jvm-error t timeout-ms (elapsed-ms)))))))))))
