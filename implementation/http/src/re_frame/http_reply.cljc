(ns re-frame.http-reply
  "Spec 014 — lower `:rf.http/managed` onto the uniform reply envelope
  (EP-0011 §Managed HTTP Lowering / §Public Compatibility Sugar; the
  canonical contract is `spec/Managed-Effects.md` §The uniform reply
  envelope).

  ## What this namespace is

  The internal seam that turns a managed-HTTP completion into the ONE
  canonical reply map every managed *async* family produces — and then
  reshapes that canonical reply back into the PUBLIC Spec 014 event
  payload, so `:on-success` / `:on-failure` and the co-located
  `(:rf/reply msg)` merge stay valid sugar with the exact shapes Spec 014
  promises. The public API does NOT change; the lowering is internal.

  Three concerns:

   1. **Work-id correlation** (`work-id`). One HTTP attempt has one
      `:work/id` head `[:rf.work/http logical-id attempt]` (the `attempt`
      number is HTTP's generation slot — see `work-id`)
      (Managed-Effects §Work-id correlation). The HTTP `:request-id` is
      NOT a second stale-suppression key — it rides as `:correlation`
      metadata on the reply map. The frame-qualified transport
      request-id `[:rf.req frame-id work-id]` (landed in Spec 016) is the
      sanctioned second identity for process-global transport
      correlation; `transport-request-id` builds it.

   2. **Canonical reply map** (`success-reply` / `failure-reply` /
      `aborted-reply`). The transport's success / failure / abort facts
      become a single `re-frame.reply`-conformant reply map with one
      closed `:status`, value-or-error, `:work/id`, `:work/kind :http`,
      `:work/status`, `:attempt`, `:rf.frame/id`, `:completed-at` (read
      ONCE from the host completion — never re-read in the reply
      handler), and `:correlation {:request-id …}`. Timeout lowers to
      `:status :error` + `:work/status :timed-out` (NOT a top-level
      status); abort lowers to `:status :cancelled` with an
      `:rf.http/aborted` `:error`.

   3. **Public compatibility reshape** (`reply->public-payload`). The
      inverse projection: the canonical reply map → the Spec 014
      `{:kind :success :value v}` / `{:kind :failure :failure f}`
      payload. This is the `:rf.http/compat-reply` body — it reshapes the
      uniform reply back into the promised public event shape, so a
      handler branching on `(:rf/reply msg)` or reading the appended
      last-arg sees exactly what Spec 014 §Reply payload shape documents.

  Pure — no atoms, no dispatch, no I/O. `:completed-at` is supplied by the
  caller (the transport reads the host clock once at finalisation and
  threads it in); this namespace never reads a clock. Trace summaries
  route every wire-bearing slot through the shared
  `re-frame.reply/trace-summary` (which calls `re-frame.elision/
  elide-wire-value`) — never a family-private elider (Managed-Effects
  §Tracing)."
  (:require [re-frame.privacy :as privacy]
            [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work-id correlation (Managed-Effects §Work-id correlation).
;;
;; HTTP head: `[:rf.work/http logical-id attempt]` (the trailing `attempt`
;; is HTTP's generation slot — see below). The logical
;; identity is the caller's `:request-id` when supplied (a stable, =-
;; comparable handle the caller already chose for supersede/abort), else
;; the originating event-id (the default reply target's identity). HTTP has
;; no generation counter of its own — supersession is keyed on `:request-id`
;; equality, not a monotonic generation — so the generation slot carries the
;; attempt number, which is what discriminates retries of the same logical
;; request. One ATTEMPT, one `:work/id` (the EP-0007 rule): attempt 1 and
;; attempt 2 of the same request are distinct work ids.
;; ---------------------------------------------------------------------------

(defn work-id
  "Build the HTTP work-id head for one attempt.

  `[:rf.work/http logical-id attempt]` where `logical-id` is the caller's
  `:request-id` (when non-nil) else the originating event-id. The trailing
  `attempt` is the generation slot — HTTP discriminates retries by attempt
  number, not a separate monotonic generation. `=`-comparable and EDN-
  serializable (Managed-Effects §Work-id correlation)."
  [{:keys [request-id origin-event attempt]}]
  (let [logical-id (if (some? request-id) request-id (first origin-event))]
    [:rf.work/http logical-id (or attempt 1)]))

(defn transport-request-id
  "Build the frame-qualified transport request-id `[:rf.req frame-id
  work-id]` (Managed-Effects §Work-id correlation — the sanctioned second
  identity for process-global transport-level in-flight correlation, landed
  in Spec 016). The frame-local `:work/id` carries no frame id, so it is not
  a safe process-global transport token on its own; this qualified form is.
  Intra-frame stale suppression still keys on `:work/id`, not this."
  [frame-id wid]
  [:rf.req frame-id wid])

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
  status. `:work/id`, `:work/kind :http`, `:attempt`, `:rf.frame/id`,
  `:completed-at`, and `:correlation {:request-id …}`. Optional facts
  (`:completed-at`, `:correlation`) are omitted when absent rather than
  filled with nil sentinels (Managed-Effects §The reply map)."
  [{:keys [request-id origin-event attempt frame completed-at] :as ctx}]
  (let [wid (work-id ctx)]
    (cond-> {:work/id     wid
             :work/kind   :http
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
  `:work/status :completed`."
  [ctx value]
  (assoc (base-reply ctx)
         :status      :ok
         :work/status :completed
         :value       value))

(defn- timed-out?
  "A `:rf.http/timeout` failure lowers to `:work/status :timed-out` (NOT a
  top-level reply status — timeout is an error KIND + a work status per
  Managed-Effects §Status taxonomy)."
  [failure]
  (= :rf.http/timeout (:kind failure)))

(defn failure-reply
  "Build the canonical reply map for a failed HTTP completion. `failure` is
  the closed-set `:rf.http/*` failure map the transport classified.

  Status mapping (Managed-Effects §Status taxonomy):
   - `:rf.http/aborted`  → `:status :cancelled` (see `aborted-reply` for the
     dedicated builder; this fn handles it too for completeness);
   - `:rf.http/timeout`  → `:status :error` + `:work/status :timed-out`
     (timeout is NOT a top-level status);
   - everything else     → `:status :error` + `:work/status :failed`.

  The classified `:rf.http/*` failure map rides verbatim as `:error` (it
  already carries the family `:kind` the reply-map contract requires)."
  [ctx failure]
  (let [aborted? (= :rf.http/aborted (:kind failure))]
    (if aborted?
      (assoc (base-reply ctx)
             :status        :cancelled
             :work/status   :cancelled
             :cancelled?    true
             :cancel/reason (:reason failure)
             :error         failure)
      (assoc (base-reply ctx)
             :status      :error
             :work/status (if (timed-out? failure) :timed-out :failed)
             :error       failure))))

(defn aborted-reply
  "Build the canonical `:status :cancelled` reply map for a cancelled HTTP
  request (Managed-Effects §Cancellation). `failure` is the
  `:rf.http/aborted` shape (`:kind` / `:reason` / `:request-id` /
  `:actor-id`). Carries `:cancelled? true`, `:cancel/reason`, and the
  abort failure under `:error` (Managed-Effects: `:error` MAY carry
  compatibility failure data for `:status :cancelled`). `:work/status
  :cancelled`."
  [ctx failure]
  (assoc (base-reply ctx)
         :status        :cancelled
         :work/status   :cancelled
         :cancelled?    true
         :cancel/reason (:reason failure)
         :error         failure))

;; ---------------------------------------------------------------------------
;; Public compatibility reshape (Managed-Effects §Public Compatibility
;; Sugar; EP-0011 §Public Compatibility Sugar). The inverse projection:
;; canonical reply map → the Spec 014 public reply payload. This is the
;; `:rf.http/compat-reply` body — it preserves the public HTTP contract
;; (Spec 014 §Reply payload shape) while the runtime / ledger / trace /
;; cancellation / stale paths all see the same canonical reply map.
;; ---------------------------------------------------------------------------

(defn reply->public-payload
  "Reshape a canonical HTTP reply map back into the PUBLIC Spec 014 reply
  payload (`{:kind :success :value v}` or `{:kind :failure :failure f}`).

  The compat layer that keeps `:on-success` / `:on-failure` and the
  co-located `(:rf/reply msg)` merge spelling their documented shapes
  (Spec 014 §Reply payload shape) even though the request lowered through
  the uniform reply envelope internally:

   - `:status :ok`        → `{:kind :success :value (:value reply)}`;
   - `:status :error`     → `{:kind :failure :failure (:error reply)}`;
   - `:status :cancelled` → `{:kind :failure :failure (:error reply)}`
     (Spec 014 surfaces an abort as a `:rf.http/aborted` FAILURE reply);
   - `:status :stale`     → nil (a suppressed reply is never delivered to
     the app target — the caller MUST NOT dispatch it).

  Pure projection over the canonical reply; the inverse of `success-reply`
  / `failure-reply` / `aborted-reply` at the public boundary."
  [reply]
  (case (:status reply)
    :ok        {:kind :success :value (:value reply)}
    :error     {:kind :failure :failure (:error reply)}
    :cancelled {:kind :failure :failure (:error reply)}
    :stale     nil
    ;; :partial is not emitted by plain managed HTTP (Managed-Effects
    ;; §Status taxonomy) — defensive nil if it ever appears.
    nil))

;; ---------------------------------------------------------------------------
;; Trace summary (Managed-Effects §Tracing). A data-only summary of the
;; canonical reply for a managed-async trace row, routing every wire-bearing
;; slot through the shared `re-frame.reply/trace-summary` → `re-frame.
;; elision/elide-wire-value` walker. Never a family-private elider.
;; ---------------------------------------------------------------------------

(def ^:private wire-slots
  "Reply-map slots carrying user/wire data — redacted wholesale for a
  per-call-sensitive request (Spec 014 §Privacy). Identity / correlation-
  free facts ride verbatim. Mirrors `re-frame.reply`'s private slot set."
  [:value :error :correlation :meta])

(defn trace-reply
  "Build a DATA-ONLY trace summary of a canonical HTTP reply map for a
  managed-async trace row. The wire-bearing slots (`:value`, `:error`,
  `:correlation`, `:meta`) elide through the single shared
  `re-frame.elision/elide-wire-value` walker (via
  `re-frame.reply/trace-summary`) — never a family-private elider
  (Managed-Effects §Tracing); the identity facts (`:status`, `:work/id`,
  `:work/kind`, `:work/status`, `:attempt`, `:rf.frame/id`,
  `:completed-at`) ride verbatim.

  `opts`:
   - `:frame`      — the carried wire-egress frame (EP-0002); forwarded to
                     the elider so a known frame's policy applies and the
                     walker does not fail closed.
   - `:sensitive?` — Spec 014 §Privacy per-call sensitivity. When true,
                     EVERY wire slot is redacted to the framework sentinel
                     BEFORE the shared walker runs (the coarse escape hatch
                     for an ad-hoc sensitive request whose payload carries no
                     schema marks), matching the existing `:rf.http/*`
                     trace redaction posture. When false/absent the shared
                     walker applies the frame's frame-declared
                     `:sensitive?` / `:large?` app-db policy as usual."
  ([reply] (trace-reply reply nil))
  ([reply {:keys [sensitive?] :as opts}]
   (let [;; Per-call sensitivity is the coarse escape hatch — redact every
         ;; wire slot wholesale before the schema-policy walk (Spec 014
         ;; §Privacy: a sensitive request redacts all payload slots).
         reply (if sensitive?
                 (reduce (fn [m slot]
                           (if (contains? m slot)
                             (assoc m slot privacy/redacted-sentinel)
                             m))
                         reply
                         wire-slots)
                 reply)]
     ;; The shared walker still runs (the EP mandate): on a non-sensitive
     ;; reply it applies the frame's frame-declared elision; on a
     ;; pre-redacted one the sentinel passes through untouched.
     (reply/trace-summary reply (dissoc opts :sensitive?)))))
