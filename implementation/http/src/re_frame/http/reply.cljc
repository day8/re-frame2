(ns re-frame.http.reply
  "Spec 014 — lower `:rf.http/managed` onto the uniform reply envelope
  (EP-0011; the canonical contract is `spec/Managed-Effects.md` §The
  uniform reply envelope).

  ## What this namespace is

  The internal seam that turns a managed-HTTP completion into the ONE
  canonical reply map every managed *async* family produces — the SAME
  map that is delivered to the app reply target. There is no second
  public dialect: the unified `:reply-to` and the `:on-success` /
  `:on-failure` split sugar all deliver the canonical map verbatim,
  appended as the reply target's last argument (rf2-et4c1s — the
  co-located `:rf/reply`-merge default was retired pre-alpha).
  The old `{:kind :success/:failure}` reshape (`reply->public-payload` /
  the `:rf.http/compat-reply` layer) was DELETED per rf2-ibksxg — one
  canonical async-reply envelope, no compat dialect.

  Two concerns:

   1. **Work-id correlation** (`work-id`). One HTTP attempt has one
      `:rf.reply/work-id` head `[:rf.work/http logical-id issuance attempt]`
      (`issuance` is the monotonic per-`request-id` re-issuance counter so a
      superseded attempt and its superseder carry distinct work ids;
      `attempt` discriminates transport retries within one issuance — see
      `work-id`) (Managed-Effects §Work-id correlation). The HTTP `:request-id` is
      NOT a second stale-suppression key — it rides as `:correlation`
      metadata on the reply map. The frame-qualified transport
      request-id `[:rf.req frame-id work-id]` (landed in Spec 016) is the
      sanctioned second identity for process-global transport
      correlation; the Resources work-ledger's `managed-request-id` is
      its only shipped constructor.

   2. **Canonical reply map** (`success-reply` / `failure-reply`). The
      transport's success / failure / abort
      facts become a single `re-frame.reply`-conformant reply map with one
      closed `:status` (`:ok` / `:error` / `:cancelled`), `:value` on
      `:ok`, the classified `:rf.http/*` failure map riding verbatim
      under `:error`, plus `:rf.reply/work-id`, `:rf.reply/work-kind :http`,
      `:rf.reply/work-status`, `:attempt`, `:rf.frame/id`, `:completed-at` (read
      ONCE from the host completion — never re-read in the reply
      handler), and `:correlation {:request-id …}`. Timeout lowers to
      `:status :error` + `:rf.reply/work-status :timed-out` (NOT a top-level
      status); abort lowers to `:status :cancelled` with an
      `:rf.http/aborted` `:error`. This map is delivered to the app
      target as-is — there is no public reshape.

  Pure — no atoms, no dispatch, no I/O. `:completed-at` is supplied by the
  caller (the transport reads the host clock once at finalisation and
  threads it in); this namespace never reads a clock. Trace summaries
  route every wire-bearing slot through the shared
  `re-frame.reply/trace-summary` (which calls `re-frame.elision/
  elide-wire-value`) — never a family-private elider (Managed-Effects
  §Tracing)."
  (:require [re-frame.reply :as rf.reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; HTTP head: `[:rf.work/http logical-id issuance attempt]`. The logical
;; identity is the caller's `:request-id` when supplied (a stable, =-
;; comparable handle the caller already chose for supersede/abort), else
;; the originating event-id (the default reply target's identity). HTTP has
;; no generation counter of its own — supersession is keyed on `:request-id`
;; equality — so two discriminators ride the tuple:
;;
;;  - `issuance` — the monotonic per-request-id ISSUANCE number (rf2-azcmd3,
;;    allocated by `http-registry/next-issuance!`). It bumps on each fresh
;;    request issued under the same `:request-id`, so a SUPERSEDED attempt
;;    (issuance N) and the SUPERSEDING one (issuance N+1) carry DISTINCT work
;;    ids even though both reset their retry `:attempt` to 1. Without it both
;;    computed `[:rf.work/http logical-id 1]` and tooling/conformance could
;;    not tell the old suppressed attempt from the new one (the EP-0011
;;    single-attempt-identity break this fixes).
;;  - `attempt`  — the retry attempt number WITHIN one issuance, which
;;    discriminates transport retries of the same issuance.
;;
;; Together: one ATTEMPT (issuance × retry) has one `:rf.reply/work-id` (the EP-0007
;; rule — Managed-Effects §Work-id correlation §184).
;; ---------------------------------------------------------------------------

(defn work-id
  "Build the HTTP work-id head for one attempt.

  `[:rf.work/http logical-id issuance attempt]` where `logical-id` is the
  caller's `:request-id` (when non-nil) else the originating event-id;
  `issuance` is the monotonic per-request-id issuance number (rf2-azcmd3 —
  bumped on each fresh request under the same `:request-id`, so a superseded
  attempt and its superseder carry distinct work ids); `attempt` is the retry
  attempt within that issuance. `=`-comparable and EDN-serializable
  (Managed-Effects §Work-id correlation). `issuance` defaults to 1 (the first
  issuance, and the only value an anonymous / non-superseding request ever
  sees)."
  [{:keys [request-id origin-event issuance attempt]}]
  (let [logical-id (if (some? request-id) request-id (first origin-event))]
    [:rf.work/http logical-id (or issuance 1) (or attempt 1)]))

;; ---------------------------------------------------------------------------
;; Self-identifying failure maps (rf2-1u9dja; debugging-dx finding 3). Every
;; public HTTP failure map (the classified `:rf.http/*` shape that rides under
;; `:error` on the canonical reply) carries WHICH request failed, not just what
;; KIND of failure it was: `:request {:method :url}`, `:request-id`, `:attempt`
;; / `:max-attempts`, `:work/id`. The framework has this identity at finalise
;; time — it was previously stamped only onto the DEV-ONLY trace and dropped at
;; the public boundary, leaving production triage of ':rf.http/timeout spiking
;; — which endpoint?' unanswerable. These fields make the one surface that
;; survives production (the reply the app's error reporting is built from)
;; self-identifying, exactly like every diagnostic trace already is.
;;
;; The `:url` on the in-app reply is on-box data the caller supplied in the
;; first place, so it rides verbatim there; OFF-BOX egress (the trace surface)
;; redacts it for a `:sensitive?` request exactly as the trace already does
;; (the privacy layer walks `:request :url` on egress).
;; ---------------------------------------------------------------------------

(defn self-identify-failure
  "Stamp the four self-identifying fields (rf2-1u9dja) onto a classified
  `:rf.http/*` failure map, from the request's identity `ctx`:

   - `:request {:method :url}` — an echo of the caller's own wire envelope;
   - `:request-id`             — uniform (was aborted-only before);
   - `:attempt` / `:max-attempts` — the retry accounting (already on the
                                    canonical envelope);
   - `:work/id`                — correlation to the trace stream.

  `ctx` supplies `:request-id` / `:origin-event` / `:issuance` / `:attempt`
  (for `work-id`), plus `:method` / `:url` (the wire envelope) and
  `:max-attempts` (the retry ceiling). Fields already present on `failure`
  (e.g. an `:rf.http/aborted` map's own `:request-id`) are OVERWRITTEN with
  the ctx value for uniformity; `:max-attempts` is omitted when absent (no
  retry policy). Applies to ALL eight failure categories — the field set is
  category-independent identity, distinct from each category's own tags."
  [failure {:keys [method url request-id max-attempts attempt] :as ctx}]
  (cond-> (assoc failure
                 :request    (cond-> {}
                               (some? method) (assoc :method method)
                               (some? url)    (assoc :url url))
                 :request-id request-id
                 :attempt    (or attempt 1)
                 :work/id    (work-id ctx))
    (some? max-attempts) (assoc :max-attempts max-attempts)))

;; ---------------------------------------------------------------------------
;; Canonical reply map (Managed-Effects §The reply map / §Status taxonomy).
;;
;; The transport's success / failure / abort facts → one reply-map-
;; conformant map. `:correlation` carries the HTTP `:request-id` as metadata
;; (NOT a second stale key). `:completed-at` is supplied by the caller; this
;; ns never reads a clock.
;; ---------------------------------------------------------------------------

(defn- base-reply
  "The correlation/identity facts every HTTP reply carries, independent of
  status. `:rf.reply/work-id`, `:rf.reply/work-kind :http`, `:attempt`, `:rf.frame/id`,
  `:completed-at`, and `:correlation {:request-id …}`. Optional facts
  (`:completed-at`, `:correlation`) are omitted when absent rather than
  filled with nil sentinels (Managed-Effects §The reply map)."
  [{:keys [request-id attempt frame completed-at] :as ctx}]
  (let [wid (work-id ctx)]
    (cond-> {:rf.reply/work-id     wid
             :rf.reply/work-kind   :http
             :attempt     (or attempt 1)}
      (some? frame)        (assoc :rf.frame/id frame)
      (some? completed-at) (assoc :completed-at completed-at)
      ;; `:request-id` is correlation metadata, NOT a second stale key
      ;; (EP-0007 / Managed-Effects §Work-id correlation). Carry it only
      ;; when the caller supplied one.
      (some? request-id)   (assoc :correlation {:request-id request-id}))))

(defn success-reply
  "Build the canonical `:status :ok` reply map for a successful HTTP
  completion. `value` is the decoded-and-`:accept`-projected payload.
  `:rf.reply/work-status :completed`.

  `response-meta` (rf2-lddbk) is the successful response's wire facts —
  `{:status <int> :status-text <string> :headers <normalized map>}` as the
  transport normalized them — riding under the envelope's `:meta`
  family-extension slot (Managed-Effects §The reply map: `:meta`
  effect-family-data) so `:after` middleware and the app reply target can
  read the actual response status and headers on success, exactly as the
  failure paths already expose them on the `:error` map. `:headers` is the
  SAME cross-host normalized shape the failure maps carry (lower-cased
  names; string value, or vector-of-strings for a multi-valued header) —
  never a second representation. When nil/absent (a canned stub that
  supplied none, or a synthetic caller), `:meta` is OMITTED rather than
  fabricated (Managed-Effects: omit optional fields when absent). `:meta`
  is a core wire-bearing slot, so trace egress elides/redacts it through
  the shared `re-frame.reply/trace-summary` walker like `:value`."
  ([ctx value] (success-reply ctx value nil))
  ([ctx value response-meta]
   (cond-> (assoc (base-reply ctx)
                  :status      :ok
                  :rf.reply/work-status :completed
                  :value       value)
     (some? response-meta) (assoc :meta response-meta))))

(defn- timed-out?
  "A `:rf.http/timeout` failure lowers to `:rf.reply/work-status :timed-out` (NOT a
  top-level reply status — timeout is an error KIND + a work status per
  Managed-Effects §Status taxonomy)."
  [failure]
  (= :rf.http/timeout (:kind failure)))

(defn failure-reply
  "Build the canonical reply map for a failed HTTP completion. `failure` is
  the closed-set `:rf.http/*` failure map the transport classified.

  Status mapping (Managed-Effects §Status taxonomy):
   - `:rf.http/aborted`  → `:status :cancelled` (the PRODUCTION abort path —
     `dispatch-failure!` always lowers aborts through `failure-reply`);
   - `:rf.http/timeout`  → `:status :error` + `:rf.reply/work-status :timed-out`
     (timeout is NOT a top-level status);
   - everything else     → `:status :error` + `:rf.reply/work-status :failed`.

  The classified `:rf.http/*` failure map rides verbatim as `:error` (it
  already carries the family `:kind` the reply-map contract requires)."
  [ctx failure]
  (let [aborted? (= :rf.http/aborted (:kind failure))]
    (if aborted?
      (assoc (base-reply ctx)
             :status        :cancelled
             :rf.reply/work-status   :cancelled
             :cancelled?    true
             :rf.reply/cancel-reason (:reason failure)
             :error         failure)
      (assoc (base-reply ctx)
             :status      :error
             :rf.reply/work-status (if (timed-out? failure) :timed-out :failed)
             :error       failure))))

;; ---------------------------------------------------------------------------
;; Stale suppression for HTTP supersession (Managed-Effects §Stale
;; suppression; rf2-azcmd3). When a fresh request supersedes a prior one with
;; the same `:request-id`, the prior (superseded) attempt's app reply MUST NOT
;; run, its ledger row reaches `:suppressed`, and the trace stream records a
;; `:status :stale` / `:rf.reply/work-status :suppressed` reply-envelope row carrying
;; the CARRIED (the superseded attempt's work-id) and CURRENT (the superseding
;; attempt's work-id) correlation. The supersession gate is data-only: the
;; carried `:work/id` no longer being the current `:work/id` under the
;; request-id IS the supersession. Delegates to the shared
;; `re-frame.reply/suppress` correctness boundary, exactly as routing /
;; resources do — no bespoke HTTP suppression shape.
;; ---------------------------------------------------------------------------

(def stale-reason
  "The `:rf.reply/stale-reason` a superseded HTTP completion carries: a fresh request
  under the same `:request-id` replaced this one. Named once (EP-0007)."
  :rf.http/request-id-superseded)

(defn suppress
  "Produce the stale-suppression outcome for a SUPERSEDED HTTP attempt —
  WITHOUT dispatching the app reply target (rf2-azcmd3). Delegates to the
  shared `re-frame.reply/suppress` (the correctness boundary made concrete):
  the returned `:reply` is `:status :stale` (no `:value`, no app mutation),
  `:deliver?` is false, and `:trace` carries the carried/current work-id
  correlation joined to `:rf.reply/work-id`.

  `ctx` is the superseded attempt's reply-ctx (`{:request-id … :origin-event
  … :issuance … :attempt … :frame …}`) — it supplies the carried HTTP
  work-id. `current-work-id` is the SUPERSEDING attempt's work-id (the live
  `:work/id` now registered under the same `:request-id`); when nil the gate
  still suppresses (a carried id against a nil current is stale). The
  superseding work-id is `=`-distinct from the carried one because the
  issuance counter bumped (`work-id` embeds the per-request-id issuance)."
  [ctx current-work-id]
  (let [carried-wid (work-id ctx)]
    (rf.reply/suppress nil
                    {:work/id carried-wid}
                    {:work/id current-work-id}
                    (cond-> {:rf.reply/work-id      carried-wid
                             :rf.reply/work-kind    :http
                             :attempt      (or (:attempt ctx) 1)
                             :rf.reply/stale-reason stale-reason}
                      (some? (:frame ctx))      (assoc :rf.frame/id (:frame ctx))
                      (some? (:request-id ctx)) (assoc :correlation {:request-id (:request-id ctx)})))))

;; ---------------------------------------------------------------------------
;; Actor-destroy obsolescence (rf2-yrrpe2; Managed-Effects §Cancellation —
;; "`:status :cancelled` when the actor-bound target is still meaningful,
;; `:status :stale`/`:suppressed` when teardown made the target obsolete
;; before delivery"; EP-0011 §Status taxonomy line 763-768). An
;; actor-destroy abort whose reply target ADDRESSES THE DESTROYED ACTOR ITSELF
;; (the machine-shape wrapper's `[self-id [:rf.http/failed]]` default, or any
;; request whose origin / explicit target names the actor under teardown) has
;; an OBSOLETE target — that event-id no longer names a live actor — so its
;; app reply MUST NOT run. It lowers to the canonical `:status :stale` /
;; `:rf.reply/work-status :suppressed` outcome, exactly like supersession. A request
;; whose reply target is an ordinary (non-actor) event is STILL MEANINGFUL —
;; it keeps the live `:status :cancelled` delivery (rf2-wvkn). The
;; obsolescence determinant is STRUCTURAL, not a live-DB read: when
;; `abort-on-actor-destroy` fires, the actor IS being torn down (its snapshot
;; is still present mid-cascade — `destroy-single-actor!` aborts in-flight
;; HTTP BEFORE the snapshot teardown), so a reply addressing that same actor
;; is obsolete by construction, no liveness probe required.

(def actor-destroy-stale-reason
  "The `:rf.reply/stale-reason` an obsolete actor-bound HTTP completion carries: the
  reply target's owning actor was destroyed before delivery, so the target is
  obsolete (Managed-Effects §Cancellation). Named once (EP-0007)."
  :rf.http/actor-destroyed-target-obsolete)

(defn actor-destroy-target-obsolete?
  "True when an actor-destroy abort's reply target is OBSOLETE — its event-id
  names the destroyed actor itself, so dispatching it would address a now-dead
  actor (Managed-Effects §Cancellation; rf2-yrrpe2).

  `reply-target-id` is the head of the reply event the abort would dispatch:
  the explicit `:on-failure` vector's head when supplied, else the originating
  event-id (`resolve-origin-event`'s head). `actor-id` is the destroyed
  actor's address. Obsolete iff the target names that actor — the
  machine-shape wrapper's `[self-id [:rf.http/failed]]` target, or any request
  whose default reply addresses its own actor.

  A target naming an ORDINARY (different) event is still meaningful and stays a
  live `:cancelled` delivery (the rf2-wvkn `:on-failure [:reply/recorder]`
  shape). A nil `actor-id` (the request was not actor-bound) is never
  obsolete."
  [reply-target-id actor-id]
  (and (some? actor-id)
       (some? reply-target-id)
       (= reply-target-id actor-id)))

(defn actor-destroy-suppress
  "Produce the stale-suppression outcome for an actor-destroy abort whose
  reply target is OBSOLETE (rf2-yrrpe2) — WITHOUT dispatching the app reply
  target. Delegates to the shared `re-frame.reply/suppress` correctness
  boundary: the returned `:reply` is `:status :stale` (no `:value`, no app
  mutation), `:deliver?` is false, and `:trace` carries the carried correlation
  joined to `:rf.reply/work-id`.

  `ctx` is the aborted attempt's reply-ctx (`{:request-id … :origin-event …
  :issuance … :attempt … :frame …}`) — it supplies the carried HTTP work-id.
  The carried gate is the attempt's own work-id; the current gate is nil (the
  actor that owned the target is gone — there is no live successor), so the
  shared `stale?` predicate suppresses (a carried id against a nil current is
  stale, the same shape supersession uses when the superseding work-id is
  unknown)."
  [ctx]
  (let [carried-wid (work-id ctx)]
    (rf.reply/suppress nil
                    {:work/id carried-wid}
                    nil
                    (cond-> {:rf.reply/work-id      carried-wid
                             :rf.reply/work-kind    :http
                             :attempt      (or (:attempt ctx) 1)
                             :rf.reply/stale-reason actor-destroy-stale-reason}
                      (some? (:frame ctx))      (assoc :rf.frame/id (:frame ctx))
                      (some? (:request-id ctx)) (assoc :correlation {:request-id (:request-id ctx)})))))

;; rf2-ibksxg — the `reply->public-payload` reshape (the `:rf.http/compat-reply`
;; body that projected the canonical reply back onto the retired
;; `{:kind :success/:failure}` dialect) was DELETED. Every reply family now
;; delivers the canonical envelope verbatim; there is no second public dialect.
;; A SUPPRESSED (`:status :stale`) reply is still never delivered to the app
;; target — the transport's supersede / actor-destroy paths gate that BEFORE
;; dispatch (`reply-suppressing-abort-reason?` and the stale-trace emitters),
;; so no public-boundary projection is needed here.

;; ---------------------------------------------------------------------------
;; Trace summary (Managed-Effects §Tracing). A data-only summary of the
;; canonical reply for a managed-async trace row, routing every wire-bearing
;; slot through the shared `re-frame.reply/trace-summary` → `re-frame.
;; elision/elide-wire-value` walker. Never a family-private elider.
;; ---------------------------------------------------------------------------

(defn trace-reply
  "Build a DATA-ONLY trace summary of a canonical HTTP reply map for a
  managed-async trace row. The wire-bearing slots (`:value`, `:error`,
  `:correlation`, `:meta`) elide through the single shared
  `re-frame.elision/elide-wire-value` walker (via
  `re-frame.reply/trace-summary`) — never a family-private elider
  (Managed-Effects §Tracing); the identity facts (`:status`, `:rf.reply/work-id`,
  `:rf.reply/work-kind`, `:rf.reply/work-status`, `:attempt`, `:rf.frame/id`,
  `:completed-at`) ride verbatim. CORE owns the wire-bearing slot set — this
  namespace no longer mirrors it.

  `opts`:
   - `:frame`      — the carried wire-egress frame (EP-0002); forwarded to
                     the elider so a known frame's policy applies and the
                     walker does not fail closed.
   - `:sensitive?` — Spec 014 §Privacy per-call sensitivity. When true, the
                     per-call policy is TRANSLATED into core's generic
                     `:rf.privacy/force-redact-wire?` option, so CORE (the
                     sole owner of the wire-bearing slot set) redacts EVERY
                     wire slot to the framework sentinel before egress — the
                     coarse escape hatch for an ad-hoc sensitive request whose
                     payload carries no schema marks, matching the existing
                     `:rf.http/*` trace redaction posture. When false/absent
                     the shared walker applies the frame's frame-declared
                     `:sensitive?` / `:large?` app-db policy as usual."
  ([reply] (trace-reply reply nil))
  ([reply {:keys [sensitive?] :as opts}]
   (rf.reply/trace-summary reply
                        (cond-> (dissoc opts :sensitive?)
                          sensitive? (assoc :rf.privacy/force-redact-wire? true)))))
