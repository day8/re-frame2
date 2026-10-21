(ns re-frame.resources.reply
  "Lowers resource and mutation completions onto the uniform reply envelope.
  Their reply targets are framework-internal, so they receive the canonical
  reply map directly rather than the public-payload reshape. The transport's
  reply (`:value` or `:error`) is re-lifted together with the verification
  payload the lowering stamped. The reply map spells the result `:value`;
  the durable layers keep it under `:data` (resource entry) and `:result`
  (mutation instance), two facts in two layers. Pure: `:completed-at` is
  supplied by the caller and no clock is read here.

  There is no `target-obsolete?` gate as HTTP has. Resource and mutation
  replies never target a caller-supplied event, so obsolescence is decided by
  work-id and generation liveness in the events layer, and a stale reply is
  suppressed through `stale-reply`.
  Managed-Effects §The uniform reply envelope"
  (:require [re-frame.reply :as rf.reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- work kinds -----------------------------------------------------------

(def work-kind-resource
  "The `:work/kind` of a resource read attempt.
  Managed-Effects §Work-id correlation"
  :resource)

(def work-kind-mutation
  "The `:work/kind` of a mutation write attempt. Mutation work reuses the
  resource work-id head with a mutation-instance key, so the attempt's kind
  is what distinguishes the writer. Managed-Effects §Work-id correlation"
  :mutation)

;; ---- aborted-failure detector ---------------------------------------------

(def ^:private http-aborted-kind
  "The managed-HTTP failure `:kind` of an intentional abort."
  :rf.http/aborted)

(defn abort-failure?
  "True iff `error` is a managed-HTTP abort envelope, which lowers to
  `:status :cancelled` rather than a user-visible `:status :error`.
  Managed-Effects §Status taxonomy"
  [error]
  (= http-aborted-kind (:kind error)))

;; ---- transport-payload extractors -----------------------------------------

;; Managed HTTP appends its canonical reply envelope as the internal reply
;; event's last arg. The resource and mutation paths share these extractors
;; so they never drift; they differ only in the inline durable-layer key a
;; direct-dispatch test payload falls back to.

(defn transport-success-value
  "The decoded `:value` of the transport's success envelope `http-result`,
  falling back to `fallback-key` on the verification payload (`:data` for a
  resource, `:result` for a mutation) when no transport was in the loop.
  Spec 014 §Reply addressing"
  [verification-payload http-result fallback-key]
  (if (contains? http-result :value)
    (:value http-result)
    (get verification-payload fallback-key)))

(defn transport-failure-envelope
  "The `:rf.http/*` failure envelope under `:error` of `http-result`, falling
  back to an inline `:error` on the verification payload.
  Spec 016 §Status semantics"
  [verification-payload http-result]
  (if (contains? http-result :error)
    (:error http-result)
    (:error verification-payload)))

;; ---- canonical reply-map builders -----------------------------------------

(defn- base-reply
  "The status-independent facts of a reply: `:rf.reply/work-id`,
  `:rf.reply/work-kind`, `:rf.frame/id`, `:completed-at` and a
  `:correlation` map of the carried identities. Absent facts are omitted
  rather than set to nil. Managed-Effects §The reply map"
  [verification-payload {:keys [work-kind completed-at]}]
  (let [work-id (:work/id verification-payload)
        ;; Correlation is metadata only: the work-id is the single
        ;; stale-suppression identity.
        correlation (cond-> {}
                      (contains? verification-payload :scope)
                      (assoc :scope (:scope verification-payload))
                      (contains? verification-payload :generation)
                      (assoc :generation (:generation verification-payload))
                      (some? (:resource/key verification-payload))
                      (assoc :rf.reply/resource-key (:resource/key verification-payload))
                      (some? (:mutation-id verification-payload))
                      (assoc :mutation/id (:mutation-id verification-payload))
                      (some? (:instance-id verification-payload))
                      (assoc :instance/id (:instance-id verification-payload)))]
    (cond-> {:rf.reply/work-id     work-id
             :rf.reply/work-kind   work-kind}
      (some? (:rf.frame/id verification-payload))
      (assoc :rf.frame/id (:rf.frame/id verification-payload))
      (some? completed-at)      (assoc :completed-at completed-at)
      (seq correlation)         (assoc :correlation correlation))))

(defn success-reply
  "The canonical `:status :ok` reply map carrying `value`.
  Managed-Effects §The reply map"
  [vp value opts]
  (assoc (base-reply vp opts)
         :status      :ok
         :rf.reply/work-status :completed
         :value       value))

(defn failure-reply
  "The canonical reply map for a failed completion: an `:rf.http/aborted`
  `error` lowers to `:status :cancelled`, anything else to `:status :error`
  with the envelope verbatim under `:error`. Managed-Effects §Status taxonomy"
  [vp error opts]
  (if (abort-failure? error)
    (assoc (base-reply vp opts)
           :status        :cancelled
           :rf.reply/work-status   :cancelled
           :rf.reply/cancel-reason (:reason error)
           :error         error)
    (assoc (base-reply vp opts)
           :status      :error
           :rf.reply/work-status :failed
           :error       error)))

(defn stale-reply
  "The `re-frame.reply/suppress` outcome for a superseded or vanished
  completion: `{:deliver? :reply :rf.reply/work-status :trace}`, so the app
  reply target does not run. `carried` / `current` are the correlation gate
  maps; `extra` threads identity facts onto the stale reply.
  Managed-Effects §Stale suppression"
  [{:keys [carried current extra]}]
  (rf.reply/suppress carried current extra))

;; ---- trace summary --------------------------------------------------------

(defn trace-reply
  "A data-only trace summary of a reply map. Wire-bearing slots elide
  through the shared `re-frame.reply/trace-summary`, never a family-private
  elider; `opts` is forwarded to it. Managed-Effects §Tracing"
  ([reply] (trace-reply reply nil))
  ([reply opts] (rf.reply/trace-summary reply opts)))
