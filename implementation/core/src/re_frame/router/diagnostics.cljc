(ns re-frame.router.diagnostics
  "Router diagnostics on cold or dev-gated paths: the no-handler and
  ambiguous-handler errors, the cross-frame dispatch-sync warning, the
  dispatch-opts and payload checks, and the always-on `:rf.cofx` boundary
  validation. Kept apart from `re-frame.router`, which reaches in only on
  these rare paths."
  (:require [clojure.string :as str]
            [re-frame.cofx.value-check :as rf.cofx.value-check]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.reply :as rf.reply]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(defn handle-ambiguous-handler!
  "Emit `:rf.error/ambiguous-registration` for an event whose id more than one
  namespace registers, on a frame made without `:images`; the event does not
  run and the drain continues. `ex` is the lookup's throw, whose ex-data names
  the `:registration`, the `:namespaces` and the remedy `:reason`. Always-on:
  a handler moved between namespaces or a late-loaded duplicate is a
  production mistake, and nothing else would say why the event did nothing."
  [ex event-id event frame]
  (when-let [emit-error-both!
             (rf.late-bind/get-fn :error-emit/emit-error-both)]
    (let [{:keys [registration namespaces reason]} (ex-data ex)]
      (emit-error-both!
        :rf.error/ambiguous-registration
        event
        event-id
        frame
        nil                                 ;; the event never ran
        0                                   ;; elapsed-ms
        (rf.interop/now-ms)                 ;; time
        {:rf.trace/event-id event-id
         :rf.event/v        event
         :frame             frame
         :registration      registration
         :namespaces        namespaces
         :reason            reason}
        {:registration registration
         :namespaces   namespaces
         :reason       reason}))))

(defn handle-no-handler!
  "Emit `:rf.error/no-such-handler` for an event with no handler on its
  resolved frame, on the always-on channel and the dev trace, since a
  dispatch to a never-registered handler is production-meaningful. Recovery
  is `:replaced-with-default`, a no-op. Late-bound, as requiring
  `re-frame.error-emit` here would be a load cycle."
  [event-id event frame]
  (when-let [emit-error-both!
             (rf.late-bind/get-fn :error-emit/emit-error-both)]
    (emit-error-both!
      :rf.error/no-such-handler
      event
      event-id
      frame
      nil                                 ;; no exception — invalid op
      0                                   ;; elapsed-ms
      (rf.interop/now-ms)                    ;; time
      {:rf.trace/event-id event-id
       :rf.event/v        event
       :frame             frame
       :kind              :event
       :recovery          :replaced-with-default})))

(defn find-other-draining-frame-id
  "The id of a live frame other than `target-frame-id` that is mid-drain, or
  nil. `dispatch-sync!` warns on that cross-frame interleave rather than
  refusing it, since frames are independent (Spec 002 §Cross-frame
  `dispatch-sync` during a sibling drain warns but proceeds). Dev-only."
  [target-frame-id]
  (some (fn [frame-id]
          (when (not= frame-id target-frame-id)
            (when-let [frame-record (rf.frame/frame frame-id)]
              (let [router-state @(:router frame-record)]
                (when (or (:in-sync-drain? router-state) (:in-drain? router-state))
                  frame-id)))))
        (rf.frame/frame-ids)))

(def ^:const known-dispatch-opts
  "The closed set of dispatch-opts keys `re-frame.router/build-envelope`
  reads. Any other key is ignored, so a typo like `:fram` would change
  nothing silently; `emit-unknown-dispatch-opts-warning!` warns instead
  (Conventions §No silent swallow). `:rf.frame/expected-incarnation` is
  internal, not app-facing."
  #{:frame :fx-overrides :interceptor-overrides :trace-id :source
    :source-detail :origin :rf.cofx :rf.cofx/mint-policy :rf.trace/call-site
    :step-index
    :rf.frame/expected-incarnation})

(def ^:const retired-draft-opt-hints
  "Did-you-mean hints for dispatch opts that existed only in spec drafts.
  Drafts never shipped, so they get no dedicated retired-name error
  (Conventions §The tombstone rule); the unknown-opt warning appends the hint
  instead. Dev-only."
  {:rf.world/inputs
   "did you mean `:rf.cofx`? (EP-0017 renamed the recordable-coeffect envelope field — supply the flat `fact-name → value` map under `:rf.cofx`; the framework time fact is `:rf/time-ms`)"
   :dispatched-at
   "there is no caller-supplied dispatch-time opt — the durable causal-time fact is `(:rf/time-ms (:rf.cofx envelope))`, stamped by the framework; for a diagnostic dispatch-time read the trace event's own `:time` stamp (Spec 009)"})

(defn- validate-supplied-cofx-values!
  "Throw `:rf.error/cofx-value-invalid` (`:non-edn-recordable-value`) at the
  first value of a supplied `:rf.cofx` that is not recordable EDN; the
  framework's `:rf/time-ms` is checked separately. Always-on, because a host
  handle folded into the durable causal record corrupts it in production too.
  The `:supplied` arm of `rf.cofx.value-check/check-edn-value!`; a declared
  `:schema` is checked later, in `re-frame.cofx`."
  [supplied event]
  (let [event-id (first event)]
    (reduce-kv
      (fn [_ cofx-id value]
        ;; `:rf/time-ms` is already shape-checked above (integer); skip it.
        (when-not (= cofx-id :rf/time-ms)
          (rf.cofx.value-check/check-edn-value! :supplied cofx-id value event-id event nil))
        nil)
      nil
      supplied)))

(defn validate-cofx!
  "Throw `:rf.error/invalid-cofx` when a caller-supplied `:rf.cofx` is not nil
  or a map, or carries a non-integer `:rf/time-ms` (Spec-Schemas
  §`:rf.cofx`); then check every other value is recordable EDN. Always-on:
  the token folds into durable writes and replay, so a malformed one is a
  production data-integrity bug, and one check at the public dispatch
  boundary covers every caller. Messages describe the rejected value by its
  `rf.error/diag-value-summary` and never print it, since a cyclic host object
  would overflow the printer; the ex-data carries the raw value. Runs before
  the router reads the clock."
  [opts event]
  (when (contains? opts :rf.cofx)
    (let [supplied (:rf.cofx opts)]
      (when-not (or (nil? supplied) (map? supplied))
        (rf.error/throw-error!
          :rf.error/invalid-cofx 're-frame.router/build-envelope
          (str ":rf.cofx must be nil or a MAP "
               "of recordable coeffect facts (Spec 002 "
               "§Recordable coeffects), got "
               (pr-str (rf.error/diag-value-summary supplied))
               ". The map's `:rf/time-ms` (wall-clock "
               "epoch ms) is the durable causal token "
               "durable writes fold; a non-map value "
               "cannot carry it.")
          {:extra {:event-id (first event)
                   :event    event
                   :supplied supplied}}))
      (when (and (map? supplied)
                 (contains? supplied :rf/time-ms)
                 (not (int? (:rf/time-ms supplied))))
        (rf.error/throw-error!
          :rf.error/invalid-cofx 're-frame.router/build-envelope
          (str ":rf.cofx :rf/time-ms must be an "
               "INTEGER (wall-clock epoch milliseconds "
               "— Spec-Schemas.md §:rf.cofx), "
               "got " (pr-str (rf.error/diag-value-summary (:rf/time-ms supplied)))
               ". This value becomes the durable "
               "causal time the frame fold reads "
               "(epoch `:committed-at`, resource "
               "`:settled-at`, …); a non-integer "
               "corrupts the durable fold and breaks "
               "replay determinism.")
          {:extra {:event-id   (first event)
                   :event      event
                   :rf/time-ms (:rf/time-ms supplied)}}))
      ;; After the shape checks, and before the per-supplier `:schema` check
      ;; in `re-frame.cofx`.
      (when (map? supplied)
        (validate-supplied-cofx-values! supplied event)))))

(defn unknown-dispatch-opts
  "The keys of `opts` outside `known-dispatch-opts`, or nil when every key is
  known. Dev-only."
  [opts]
  (when rf.interop/debug-enabled?
    (seq (remove known-dispatch-opts (keys opts)))))

(defn emit-unknown-dispatch-opts-warning!
  "Emit `:rf.warning/unknown-dispatch-opt` naming every unrecognised key and
  the known set, with a did-you-mean for a retired draft key. Warn-only: the
  dispatch proceeds. Dev-only, gated wholesale so production DCEs it."
  [unknown event]
  (when rf.interop/debug-enabled?
    (let [event-id (first event)
          unknown  (vec unknown)
          hints    (into []
                         (keep (fn [k]
                                 (when-let [h (retired-draft-opt-hints k)]
                                   (str "`" k "` — " h))))
                         unknown)
          reason   (str "Dispatch of `" event-id "` was given unrecognised "
                        "opts key" (when (> (count unknown) 1) "s") " "
                        (pr-str unknown) ". The runtime reads only "
                        (pr-str (vec (sort known-dispatch-opts)))
                        " — any other key is silently ignored, so a typo "
                        "(e.g. `:fram` for `:frame`) changes nothing and "
                        "gives no signal. Check for a misspelt opt; if you "
                        "intended a custom payload, put it inside the event "
                        "vector, not the dispatch opts map."
                        (when (seq hints)
                          (str " " (str/join " " hints))))]
      (rf.trace/emit! :warning
                   :rf.warning/unknown-dispatch-opt
                   {:event        event
                    :event-id     event-id
                    :unknown-keys unknown
                    :known-keys   (vec (sort known-dispatch-opts))
                    :detected-at  (rf.interop/now-ms)
                    :reason       reason
                    :recovery     :no-recovery}))))

(defn emit-cross-frame-warning!
  "Emit `:rf.warning/cross-frame-dispatch-sync-during-drain` when
  `dispatch-sync!` targets `target-frame-id` while `other-frame-id` is
  mid-drain. A warning, not a refusal: the interleave is legal but rarely
  intended. The caller frame is `*current-frame*`, or `:rf/none` when
  unbound."
  [target-frame-id other-frame-id event]
  (let [caller-frame-id (or rf.frame/*current-frame* :rf/none)
        reason          (str "dispatch-sync! against `" target-frame-id "` while frame `"
                             other-frame-id "` is mid-drain. The two cascades will "
                             "interleave: `" target-frame-id "`'s drain runs to settled "
                             "while `" other-frame-id "` is still in flight, then `"
                             other-frame-id "` continues. Frames are independent state "
                             "machines so this does not violate either frame's "
                             "contract (per Spec 002 §Rules "
                             "rule 1 — no cross-frame drain), but the interleaved "
                             "ordering is rarely the caller's intent. If the goal "
                             "is fire-and-forget cross-frame coordination, prefer "
                             "the async form `(rf/dispatch event {:frame other})` "
                             "— it queues on the target frame's router and drains "
                             "on a later cycle, after the caller's cascade settles.")]
    (rf.trace/emit! :warning
                 :rf.warning/cross-frame-dispatch-sync-during-drain
                 {:caller-frame caller-frame-id
                  :target-frame target-frame-id
                  :other-frame  other-frame-id
                  :event        event
                  :reason       reason
                  :recovery     :no-recovery})))

;; ---- event-payload serialisability lint -----------------------------------
;;
;; Conventions §Event payloads SHOULD be serialisable data. A SHOULD, unlike
;; the `:rf.cofx` MUST, because permissive payloads and ad-hoc CLJS tests
;; still carry a live fn or object, so this is a dev-only warning.

(def ^:const payload-lint-node-budget
  "Node budget for the payload walk, so the lint cannot become a hot-path
  cost. An exhausted walk reports nothing: a false negative is the safe
  direction for an advisory."
  500)

(defn- payload-host-handle?
  "The lint's detection set: `re-frame.reply/host-handle?` minus an instant.
  An instant (`#inst`, read as a `java.util.Date` / `js/Date`) is EDN and
  round-trips through `pr-str` / `read-string`, so an event carrying one is
  recordable data. `host-handle?` itself keeps refusing a `Date`, because a
  durable reply timestamp is an epoch-millisecond long."
  [v]
  (and (rf.reply/host-handle? v)
       (not (inst? v))))

(defn find-non-serialisable-payload-path
  "The path to the first host handle (`payload-host-handle?`) in `event`, or
  nil when it is clean or the walk budget ran out. Dev-only; the caller
  gates it."
  [event]
  (rf.reply/walk-find-host-handle-bounded event payload-lint-node-budget
                                          payload-host-handle?))

(defn emit-non-serialisable-event-payload-warning!
  "Emit `:rf.warning/non-serialisable-event-payload` for the host handle at
  `path` in `event`. Advisory: the dispatch proceeds. Dev-only, gated
  wholesale."
  [event path]
  (when rf.interop/debug-enabled?
    (let [event-id (first event)
          reason   (str "Dispatch of `" event-id "` carries a non-"
                        "serialisable value (fn / Promise / AbortController / "
                        "DOM node / RegExp) in its payload at "
                        (pr-str path) ". Event vectors SHOULD contain "
                        "recordable, serialisable data (Conventions §Event "
                        "payloads SHOULD be serialisable data) — replay, SSR "
                        "hydration, and epoch history all assume a value-"
                        "shaped event. Replace the handle with its data "
                        "projection (an id, a snapshot, a plain value).")]
      (rf.trace/emit! :warning
                   :rf.warning/non-serialisable-event-payload
                   {:event    event
                    :event-id event-id
                    :path     path
                    :reason   reason
                    :recovery :no-recovery}))))
