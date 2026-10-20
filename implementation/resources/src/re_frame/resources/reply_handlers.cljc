(ns re-frame.resources.reply-handlers
  "The internal reply-handler substrate shared by the resource read path
  (`events`) and the mutation write path (`mutation-events`): the live-slot
  verifier, the stale-suppress reply builder and the stale-suppressed trace
  emitter. The two families differ only in the knobs they pass: `path-fn`
  (payload → durable slot path, nil for no slot), `work-kind`,
  `stale-reason`, `correlation-fn`, and at emit time the trace id and bespoke
  facts.

  A late reply carrying a superseded work-id or generation never writes a
  newer or another frame's slot.
  Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  (:require [re-frame.resources.reply :as rf.resources.reply]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]
            [re-frame.trace :as rf.trace]))

;; ---- live-slot verifier ---------------------------------------------------

(defn- cross-frame-reply?
  "True when `payload` carries a stamped `:rf.frame/id` other than
  `receiving-frame-id`. An unstamped payload is never cross-frame."
  [receiving-frame-id payload]
  (let [reply-frame-id (:rf.frame/id payload)]
    (and (some? reply-frame-id) (not= reply-frame-id receiving-frame-id))))

(defn ledger-row-for-reply
  "The receiving frame's work-ledger row for a reply's `:work/id`, or nil.
  The row records which scoped key the attempt serves, so a resource reply
  finds its entry through it. Nil for a cross-frame reply, because two frames
  can hold the same work-id, and nil when the row is gone (the reply is then
  stale)."
  [runtime-db receiving-frame-id {work-id :work/id :as payload}]
  (when-not (cross-frame-reply? receiving-frame-id payload)
    (rf.resources.work-ledger/get-record runtime-db work-id)))

(defn live-slot-for-reply
  "The live durable slot at `(path-fn payload)` when the reply still owns it,
  else nil (the reply MUST then be suppressed). It owns it when the stamped
  `:rf.frame/id` is the receiving frame, and the slot's `:current-work` and
  `:generation` equal the reply's `:work/id` and `:generation`.

  The frame check runs first, so a cross-frame reply is rejected even when
  both frames hold the same slot at the same generation. An unstamped payload
  (a direct-dispatch test) skips it. The generation check guards a transport
  that reuses a work-id. Spec 016 §Cancellation is opportunistic; stale suppression is mandatory."
  [runtime-db receiving-frame-id path-fn {work-id :work/id :keys [generation] :as payload}]
  (when-not (cross-frame-reply? receiving-frame-id payload)
    (when-let [slot (some->> (path-fn payload) (get-in runtime-db))]
      (when (and (= work-id (:current-work slot))
                 (= generation (:generation slot)))
        slot))))

(defn settle-suppressed-work!
  "Settles a suppressed reply's work in the receiving frame: clears the host
  handle and settles the ledger row terminal `:suppressed` with `outcome`.
  Returns the handler's effect map. A cross-frame reply settles nothing and
  returns `{}`, because the row and handle under that work-id here are the
  receiving frame's own live work."
  [runtime-db receiving-frame-id {work-id :work/id :as payload} outcome]
  (if (cross-frame-reply? receiving-frame-id payload)
    {}
    (do (rf.resources.work-ledger/clear-handle! receiving-frame-id work-id)
        {:rf.db/runtime (rf.resources.work-ledger/settle-terminal
                          runtime-db work-id :suppressed outcome)})))

;; ---- current-generation reader --------------------------------------------

(defn current-generation
  "The `:generation` of the slot now at `(path-fn payload)`, or nil when the
  slot is gone. It is the `:current` half of the stale gate's carried-vs-current
  pair; the reply token's `:generation` is the `:carried` half."
  [runtime-db path-fn payload]
  (some->> (path-fn payload) (get-in runtime-db) :generation))

;; ---- stale-suppress reply builder -----------------------------------------

(defn stale-suppress-reply
  "The canonical `:status :stale` outcome for a superseded or vanished reply,
  built through the shared `re-frame.reply` substrate so every managed-async
  family lowers stale replies the same way. Returns the
  `re-frame.reply/suppress` outcome map (`:deliver?` false). `extra` merges
  diagnostic facts onto the reply. Managed-Effects §Stale suppression."
  [runtime-db path-fn {work-id :work/id :as payload} {:keys [work-kind stale-reason correlation-fn]} extra]
  (let [carried-generation      (:generation payload)
        current-slot-generation (current-generation runtime-db path-fn payload)]
    (rf.resources.reply/stale-reply
      {:carried {:work/id work-id :generation carried-generation}
       :current {:generation current-slot-generation}
       :extra   (merge {:rf.reply/work-id      work-id
                        :rf.reply/work-kind    work-kind
                        :rf.reply/stale-reason stale-reason
                        :correlation  (assoc (correlation-fn payload)
                                             :generation {:carried carried-generation
                                                          :current current-slot-generation})}
                       extra)})))

;; ---- stale-suppressed trace emitter ---------------------------------------

(defn emit-stale-suppressed!
  "Emits the family's `…/stale-suppressed` trace `trace-id`: `bespoke-facts`
  with the reply-envelope `:rf.reply/*` facts merged on top, the same shape
  every managed-async stale path rides. `stale` is the `stale-suppress-reply`
  outcome. Managed-Effects §Tracing."
  [trace-id bespoke-facts stale]
  (let [trace-summary (rf.resources.reply/trace-reply (:reply stale))]
    (rf.trace/emit! :rf.event trace-id
                 (merge bespoke-facts
                        {:rf.reply/status      (:status trace-summary)
                         :rf.reply/work-status (:rf.reply/work-status trace-summary)
                         :rf.reply/work-id     (:rf.reply/work-id trace-summary)
                         :rf.reply/stale-reason (:rf.reply/stale-reason trace-summary)
                         :rf.reply/correlation (:correlation trace-summary)
                         ;; The shared substrate's stale-gate facts, so the
                         ;; reply-envelope view reads the gate without
                         ;; family-specific parsing.
                         :rf.reply/carried     (:rf.reply/carried (:trace stale))
                         :rf.reply/current     (:rf.reply/current (:trace stale))}))))
