(ns re-frame.epoch.listeners
  "Epoch-listener fan-out + frame-destroy hook.

  Two responsibilities live here:

    1. `notify-listeners!` — fan a built record out to every registered
       `register-epoch-listener!` callback. Each invocation is wrapped in a
       try/catch so one broken listener cannot break the runtime or
       block other listeners (Spec 009 §Listener invocation rules); a
       failing cb emits `:rf.epoch.cb/listener-exception` so devtools
       can surface the broken consumer (rf2-i5khp).

    2. `on-frame-destroyed!` — the late-bind hook
       `re-frame.frame/destroy-frame!` invokes against the
       `:epoch/on-frame-destroyed` slot. Coordinates the four-step
       destroy contract (rf2-d656 + rf2-v0jwt + rf2-zzper):

         (a) mid-drain destroy detection → commit a `:halted-destroy`
             partial record to listeners (NOT to the ring buffer).
         (b) emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb
             whose observed-frames set contained `frame-id`.
         (c) drop the per-frame ring buffer.
         (d) drop the in-flight capture buffer.

  Per the Phase-1 finding (rf2-0wi86): on-frame-destroyed straddles
  state (the three atoms it clears), capture (its mid-drain detection
  reads the cascade buffer), and assembly (it builds a partial
  record). This namespace is the rightful home — the destroy contract
  IS the listener-side surface for frame teardown, and the
  cross-cutting calls are now explicit cross-namespace requires
  rather than forward-declares within a single 1500-LoC file.

  Public re-frame.epoch facade fns (register-epoch-listener!,
  unregister-epoch-listener!, clear-epoch-listeners!, on-frame-destroyed!) delegate
  here; the listener-registry atom + record-observation bookkeeping
  live in `re-frame.epoch.state` (seam A)."
  (:require [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.state :as state]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

(defn notify-listeners!
  "Fan `record` out to every registered `:rf/epoch-record` listener.

  Each cb is invoked once per record, wrapped in failure isolation:
  Per Spec 009 §Listener invocation rules listener failures don't
  stop the loop; per rf2-i5khp a failing cb emits a structured
  `:rf.epoch.cb/listener-exception` trace so devtools can surface
  the broken listener (silently swallowing the throw left tool
  authors with no signal that their callback failed).

  Op-type `:rf.epoch.cb` matches the sibling
  `:rf.epoch.cb/silenced-on-frame-destroy` event (per Spec 009
  §Op-type vocabulary catalogue and `epoch.cljc` row).
  `:recovery :no-recovery` mirrors the `:rf.http/aborted` trace
  shape — the listener's invocation is over; the next cascade
  re-invokes the same fn afresh, no automatic remediation happens
  between now and then."
  [record]
  (let [frame-id (:frame record)]
    (doseq [[id f] (state/listeners-snapshot)]
      (state/record-observation! id frame-id)
      (try
        (f record)
        (catch #?(:clj Throwable :cljs :default) ex
          (trace/emit-error! :rf.epoch.cb/listener-exception
                             {:frame    frame-id
                              :cb-id    id
                              :epoch-id (:epoch-id record)
                              :message  #?(:clj  (.getMessage ^Throwable ex)
                                           :cljs (.-message ex))
                              :recovery :no-recovery}))))))

;; ---- post-settle render back-fill (rf2-qs6dl) -----------------------------

(defn- render-row
  "Project a `:view/render` trace event into its structured `:renders`
  row, mirroring `capture/project-all`'s `:view/render` arm. Returns nil
  for non-`:view/render` render ops (`:rf.view/rendered` /
  `:rf.view/rendered-cap-reached`) — those ride only the `:trace-events`
  slot, exactly as `project-all` already treats them (it projects no
  `:renders` row for them)."
  [event]
  (when (= :view/render (:operation event))
    (let [t (:tags event)]
      {:render-key   (or (:render-key t) [:rf.view/anonymous nil])
       :triggered-by (:triggered-by t)
       :elapsed-ms   (:elapsed-ms t)})))

(defn record-render!
  "Attribute a post-settle render emit to the cascade that CAUSED it
  (rf2-qs6dl). A `:view/render` / `:rf.view/rendered` trace fires at
  React commit time — AFTER the causing cascade settled — so it cannot
  ride the in-flight cascade buffer (there is none). This back-fills the
  render into the frame's most-recently-settled epoch record and re-fans
  the updated record out to epoch listeners so snapshot consumers (Causa
  Views / Reactive panel, which cache `epoch-history` at settle time)
  re-sync to the corrected `:renders`.

  No-op when the frame has no settled epoch yet (a render before the
  first cascade) or when the target epoch has been evicted from the ring
  — `back-fill-render!` returns nil and we skip the re-notify."
  [frame-id event]
  (when interop/debug-enabled?
    (when-let [epoch-id (state/last-settled-epoch-id frame-id)]
      (when-let [updated (state/back-fill-render! frame-id epoch-id
                                                  event (render-row event))]
        ;; Re-fan the corrected record so snapshot consumers re-read the
        ;; ring. The fan-out is failure-isolated per listener (same
        ;; contract as the settle-time fan-out); a render-driven Causa
        ;; pump (`:rf.causa/epoch-recorded`) is `:rf.trace/no-emit?` so
        ;; it commits no new epoch and cannot loop back into this path.
        (notify-listeners! updated)))))

(defn on-frame-destroyed!
  "Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656)
  and rf2-v0jwt §Outcomes (`:halted-destroy`):

    1. Mid-drain destroy detection — if `capture-buffers[frame-id]`
       holds buffered events at destroy time, this is a mid-drain
       destroy (the handler that called `destroy-frame!` was running
       inside the drain; its trace events were captured into the
       in-flight buffer). Notify epoch listeners with a partial
       `:halted-destroy` record carrying the cascade's traces. The
       record is NOT appended to the ring buffer — step 3 wipes the
       ring buffer for the destroyed frame regardless. Devtools that
       care about destroyed cascades receive the record via the
       listener fan-out before the ring buffer is dropped.
    2. Emit `:rf.epoch.cb/silenced-on-frame-destroy` once per cb whose
       observed-frames set contains `frame-id`, then drop `frame-id`
       from each cb's entry so a re-registration of a same-keyed frame
       re-arms the silencing trace for a future destroy.
    3. Drop the destroyed frame's per-frame ring buffer so subsequent
       `(rf/epoch-history frame-id)` calls return the empty vector
       (the read-empty shape the contract commits to).
    4. Drop any in-flight capture buffer entry for `frame-id`
       (rf2-zzper) so a mid-drain destroy can't leak its pre-destroy
       events into the first cascade of the next same-keyed frame.

  Called from `re-frame.frame/destroy-frame!` via the
  `:epoch/on-frame-destroyed` late-bind hook. Idempotent across
  repeated destroys of the same frame — once a cb's entry no longer
  contains the frame-id, no further trace fires for that pair, and
  the (already-cleared) ring-buffer / capture-buffer entries stay
  absent."
  [frame-id]
  (when interop/debug-enabled?
    ;; Step 1: mid-drain destroy detection. The capture-buffer holds
    ;; every emit tagged with this frame, including non-cascade emits
    ;; that fire OUTSIDE a drain (e.g. `:frame/created` at reg-frame
    ;; time). Distinguish a real mid-drain destroy from
    ;; registration-time tagalongs by gating on the presence of an
    ;; `:event/run-start` emit — that is the canonical "a cascade
    ;; started inside this drain" signal. When present, commit a
    ;; partial `:halted-destroy` record so devtools (Causa,
    ;; re-frame2-pair) receive the cascade context for the destroyed-
    ;; mid-drain case. We can't read the frame's container here
    ;; (destroy-frame!'s step 6 already dissoc'd the frame record);
    ;; the partial record's `:db-before` / `:db-after` slots are nil
    ;; — the schema allows `:any`, and consumers tolerate the absent
    ;; state given `:outcome :halted-destroy` signals the destroy
    ;; context. The record is delivered to listeners only — the ring
    ;; buffer gets wiped in step 3.
    (let [buffered-events  (state/buffer-for frame-id)
          in-cascade?      (some (fn [ev]
                                   (and (= :event (:op-type ev))
                                        (= :event (:operation ev))
                                        (= :run-start (-> ev :tags :phase))))
                                 buffered-events)]
      (when in-cascade?
        ;; Per rf2-wp70d: even on the halted-destroy partial-record
        ;; commit, `maybe-redact` runs once between `build-record`
        ;; and listener fan-out so listener consumers see the SAME
        ;; redacted shape they would see for an :ok cascade record.
        (let [record (assembly/maybe-redact
                       (assembly/build-record frame-id nil nil buffered-events
                                              :halted-destroy
                                              {:operation :rf.frame/destroyed-mid-drain}))]
          (trace/emit! :rf.epoch :rf.epoch/snapshotted
                       {:frame    frame-id
                        :epoch-id (:epoch-id record)
                        :event-id (:event-id record)
                        :outcome  :halted-destroy})
          (notify-listeners! record))))
    (let [silenced-cbs (->> (state/observations-snapshot)
                            (keep (fn [[cb-id frames]]
                                    (when (contains? frames frame-id) cb-id)))
                            vec)]
      (doseq [cb-id silenced-cbs]
        (trace/emit! :rf.epoch.cb :rf.epoch.cb/silenced-on-frame-destroy
                     {:frame  frame-id
                      :cb-id  cb-id})))
    (state/drop-frame-observation! frame-id)
    ;; Drop the per-frame ring buffer; epoch-history returns [] from
    ;; here on. (`reset-frame! :app/main` calls destroy-frame! followed
    ;; by reg-frame, so the ring buffer for the new same-keyed frame
    ;; starts empty per Spec 002 §reset-frame!.)
    (state/drop-frame-history! frame-id)
    ;; Per rf2-zzper: also drop any in-flight capture buffer. A
    ;; mid-drain destroy that surfaces a halted record above leaves
    ;; the buffer behind (the partial-record commit doesn't harvest);
    ;; explicitly clear here so the next cascade against a same-keyed
    ;; frame starts from an empty buffer. Symmetric to the ring-buffer
    ;; drop above.
    (state/drop-frame-buffer! frame-id)
    ;; Per rf2-qs6dl: forget the last-settled epoch-id so a post-destroy
    ;; render emit (a torn-down component's final flush) can't back-fill
    ;; into a record belonging to the destroyed frame's history — which
    ;; was just dropped above anyway.
    (state/drop-last-settled-epoch! frame-id)))
