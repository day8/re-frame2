(ns re-frame.epoch.capture
  "Per-cascade trace-buffer write/read and the two read-only walks the
  drain-settle assembly path runs against the harvested events:

    capture-event!     -- the late-bind seam `re-frame.trace` invokes
                          on every emit, gated on `:frame`-tag presence
                          and the `skip-ops` self-emit catalogue.
    project-all        -- one fused reducer pass producing the
                          `:sub-runs`, `:renders`, `:effects` slots.
    find-trigger-event -- one walk extracting `:event-id` + `:event`
                          from the cascade's first `:event/run-start`,
                          with a `:event-id`-only fallback.

  Per rf2-0wi86 Phase-2 seam B: this namespace owns the cascade-buffer
  *behaviour*; the buffer atom itself and the low-level
  buffer-event! / harvest-buffer! mutators live in
  `re-frame.epoch.state` (seam A). Splitting the catalogue + capture
  + projection trio out of the facade keeps the cascade pipeline a
  single grep target."
  (:require [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]))

;; ---- skip-ops catalogue --------------------------------------------------
;;
;; Operations this namespace itself emits with a `:frame` tag, all of
;; which fire OUTSIDE a cascade (the drain has either not started, or
;; has just settled and the buffer has been harvested). If `capture-
;; event!` didn't skip them they would accrete into `capture-buffers`
;; and leak into the NEXT cascade's harvested record for the same
;; frame — a silent correctness bug surfacing as phantom `:trace-events`
;; and a wrong `:trigger-event` via `find-trigger-event`'s fallback arm.
;;
;; Enumeration (not a `:rf.epoch/*` namespace-prefix filter) is the
;; deliberate choice: a future in-cascade `:rf.epoch/*` op (e.g. an
;; in-drain cascade-rollback trace) must continue to surface in epoch
;; records. The companion test `skip-ops-catalogue-pins-every-rf-epoch-op`
;; pins this catalogue against every `:rf.epoch/*` op the namespace
;; emits, so an addition that forgets to update one or the other will
;; fail loudly rather than drift silently.
;;
;; `:rf.epoch.cb/silenced-on-frame-destroy` is a different op-type
;; (`:rf.epoch.cb`) and emits AFTER the frame's ring buffer has been
;; dropped, so it can never race a future cascade for that frame.
(def skip-ops
  #{;; Drain-settle emit (after harvest-buffer! has emptied the buffer).
    :rf.epoch/snapshotted
    ;; restore-epoch success + the five documented failure modes.
    :rf.epoch/restored
    :rf.epoch/restore-unknown-epoch
    :rf.epoch/restore-schema-mismatch
    :rf.epoch/restore-missing-handler
    :rf.epoch/restore-version-mismatch
    :rf.epoch/restore-during-drain
    :rf.epoch/restore-non-ok-record
    ;; reset-frame-db! success + its two failure modes (Tool-Pair §Pair-
    ;; tool writes, rf2-zq55). All three fire after the synthetic record
    ;; has been built and the cascade-buffer (if any) has been harvested.
    :rf.epoch/db-replaced
    :rf.epoch/reset-frame-db-during-drain
    :rf.epoch/reset-frame-db-schema-mismatch
    ;; Redact-fn exception warning (rf2-wp70d / Tool-Pair §Time-travel
    ;; §Redaction hook). Emitted by `maybe-redact` AFTER
    ;; `harvest-buffer!` has emptied the cascade buffer for this
    ;; frame; if left un-skipped, the `:frame`-tagged emit would
    ;; otherwise accrete into the NEXT cascade's harvested record
    ;; for this frame.
    :rf.warning/epoch-redact-fn-exception})

(defn capture-event!
  "Internal trace-capture entry point published through `re-frame.late-bind`
  under `:epoch/capture-event`. `re-frame.trace/emit!` and
  `re-frame.trace/emit-error!` invoke this for every event so the
  cascade buffer is populated regardless of which user listeners are
  registered.

  Going through late-bind (rather than registering as a listener via
  `register-trace-cb!`) ensures the user-facing `clear-trace-cbs!`
  call does NOT wipe the internal capture path — pair tools that reset
  the trace stream between sessions can do so without losing epoch
  recording.

  Events whose tags don't carry `:frame` are skipped — they can't be
  tied to a specific cascade. The `:rf.epoch/*` trace events this
  namespace emits OUTSIDE a cascade (catalogued in `skip-ops`) are
  also skipped, so a snapshotted/restored/db-replaced emit cannot leak
  into the next cascade's harvested record."
  [event]
  (when interop/debug-enabled?
    (let [op       (:operation event)
          tags     (:tags event)
          frame-id (or (:frame tags)
                       (:frame event))]
      (when (and frame-id (not (contains? skip-ops op)))
        (state/buffer-event! frame-id event)))))

;; ---- record projection ----------------------------------------------------

(defn project-all
  "Walk the captured trace events ONCE and emit the three `:sub-runs`,
  `:renders`, `:effects` projections in a single reducer pass. Returns
  `{:sub-runs <v> :renders <v> :effects <v>}` with each value a
  persistent vector built via transient accumulators.

  Per rf2-ecu37 (audit rf2-fzrav §M1): the prior shape was three
  independent `into []` transducer walks of the same buffer — for an
  N-event cascade that's 3·N operation reads where N suffice. The
  fused reducer mirrors `find-trigger-event`'s style (rf2-txrq9):
  one traversal, multiple accumulators, single allocation budget.

  Per-projection contracts preserved verbatim (no schema change):

    :sub-runs — Spec-Schemas §`:rf/epoch-record`. One entry per
      `:sub/run` trace event. Cache-hit subs (rf2-719e fast-path) do
      NOT emit `:sub/run` and are correctly absent.

    :renders — Spec-Schemas §`:rf/epoch-record` and Spec 004 §Render-tree
      primitives (rf2-t5tx Option C / rf2-piag). `:render-key` is the
      `[<view-id> <instance-token>]` tuple; renders bypassing reg-view
      (plain Reagent fns) use `[:rf.view/anonymous nil]` as the
      documented fallback.

    :effects — Spec-Schemas §`:rf/epoch-record` `:effects`. Every
      dispatched fx emits exactly one of:

        :fx :rf.fx/handled                    → :outcome :ok
        :warning :rf.fx/skipped-on-platform   → :outcome :skipped-on-platform
        :error :rf.error/fx-handler-exception → :outcome :error
        :error :rf.error/no-such-fx           → :outcome :error

      `:error-trace` (when present) references the corresponding error
      trace event by `:id`."
  [events]
  ;; Single reduce; the accumulator is a 3-key transient map of
  ;; transient vectors. `conj!` may rebind the inner transient vector
  ;; identity at chunk boundaries (every 32 elements), so we thread
  ;; the result back through `assoc!`. The outer transient map is
  ;; mutated in place — no per-step map allocation.
  ;;
  ;; Internal slot keys are `:s` / `:r` / `:e` purely to keep this
  ;; transient namespace local; the documented `:sub-runs` /
  ;; `:renders` / `:effects` shape is materialised once at the end.
  (let [acc (reduce
              (fn [acc ev]
                (let [op (:operation ev)
                      t  (:tags ev)]
                  (cond
                    (= :sub/run op)
                    (assoc! acc :s
                            (conj! (get acc :s)
                                   {:sub-id      (:sub-id t)
                                    :query-v     (:query-v t)
                                    :recomputed? true}))

                    (= :view/render op)
                    (assoc! acc :r
                            (conj! (get acc :r)
                                   {:render-key   (or (:render-key t)
                                                      [:rf.view/anonymous nil])
                                    :triggered-by (:triggered-by t)
                                    :elapsed-ms   (:elapsed-ms t)}))

                    (= :rf.fx/handled op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id   (:fx-id t)
                                    :args    (:fx-args t)
                                    :outcome :ok}))

                    (= :rf.fx/skipped-on-platform op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id   (:fx-id t)
                                    :args    (:fx-args t)
                                    :outcome :skipped-on-platform}))

                    (= :rf.error/fx-handler-exception op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:fx-id t)
                                    :args        (:fx-args t)
                                    :outcome     :error
                                    :error-trace (:id ev)}))

                    (= :rf.error/no-such-fx op)
                    (assoc! acc :e
                            (conj! (get acc :e)
                                   {:fx-id       (:fx-id t)
                                    :args        (:fx-args t)
                                    :outcome     :error
                                    :error-trace (:id ev)}))

                    :else acc)))
              (transient {:s (transient [])
                          :r (transient [])
                          :e (transient [])})
              events)]
    {:sub-runs (persistent! (get acc :s))
     :renders  (persistent! (get acc :r))
     :effects  (persistent! (get acc :e))}))

;; ---- trigger-event resolution --------------------------------------------

(defn find-trigger-event
  "Walk the buffered events to find the first :event/run-start trace.
  That carries the `:event` and `:event-id` for the cascade.

  When the cascade had no successful event handler (e.g. an unknown
  event id or a frame-destroyed dispatch), no :run-start fires; fall
  back to the first event we can find with an `:event-id` tag. Per
  rf2-7kxxx (audit r3 §F2): if that fallback event carries no `:event`
  tag we DO NOT synthesise `[eid]` — that would misrepresent an event
  that originally carried payload as payload-less. `build-record`'s
  conditional `cond->` (rf2-kl5p1) omits the `:trigger-event` slot
  when `:event` is nil, which the schema's open map admits.

  Per rf2-txrq9: single-walk reduction over `events` — the original
  two-pass `or`-of-`some` reordered both walks across the buffer
  on the degenerate path. We now accumulate the first
  `:event/run-start` AND the first fallback `:event-id` in one
  traversal and prefer the run-start. Either match short-circuits
  at the earliest moment it can — a run-start hit immediately
  reduces to the final result; a fallback-only stream walks once."
  [events]
  (let [result
        (reduce
          (fn [acc ev]
            (let [tags (:tags ev)]
              (if (and (= :event (:op-type ev))
                       (= :event (:operation ev))
                       (= :run-start (:phase tags)))
                ;; run-start beats the fallback; short-circuit.
                (reduced {:run-start {:event-id (:event-id tags)
                                      :event    (:event tags)}})
                ;; Capture the first :event-id we see as the fallback.
                ;; Per rf2-7kxxx: do NOT fabricate `:event` — when the
                ;; tag is absent we leave the field nil, and downstream
                ;; `build-record` (rf2-kl5p1) omits the
                ;; `:trigger-event` slot entirely rather than emit a
                ;; misleading synthesised vector.
                (if (or (:fallback acc) (nil? (:event-id tags)))
                  acc
                  (assoc acc :fallback {:event-id (:event-id tags)
                                        :event    (:event tags)})))))
          {}
          events)]
    (or (:run-start result) (:fallback result))))
