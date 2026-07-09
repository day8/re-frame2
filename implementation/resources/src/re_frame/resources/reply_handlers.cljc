(ns re-frame.resources.reply-handlers
  "Spec 016 — the framework-INTERNAL reply-handler substrate SHARED by the
  resource read path (`re-frame.resources.events`) and the mutation write
  path (`re-frame.resources.mutation-events`).

  ## What this namespace is

  The layer ABOVE `re-frame.resources.reply` (the canonical reply-envelope
  builder, `rreply`). Both the resource and mutation families verify a late
  internal reply against the LIVE durable slot (frame + work-id + generation)
  and, on a superseded / vanished / cross-frame reply, lower the SAME stale-
  suppression outcome (`:status :stale` / `:rf.reply/work-status :suppressed`) and emit
  the SAME additively-shaped stale-suppressed trace. That trio of behaviours —
  the live-slot verifier, the stale-suppress reply builder, and the
  stale-suppressed trace emitter — was copy-pasted between `events.cljc` and
  `mutation_events.cljc`, differing ONLY in the durable slot they look up and a
  handful of vocabulary knobs. This ns hoists them ONCE, parameterized by those
  knobs (rf2-nnke18).

  ## The three knobs each family passes

  - **`path-fn`** — `(payload) → db-path` for the family's durable slot: the
    resource entry (`state/entry-path` keyed on `:resource/key`) or the
    mutation instance (`mstate/instance-path` keyed on `:instance-id`). Used
    by `live-for-reply?` (the verifier reads `:current-work` + `:generation`
    off the slot) and by `current-generation` (the `:current` half of the
    carried-vs-current stale gate reads the slot's live `:generation`).
  - **`work-kind`** — `rreply/work-kind-resource` | `rreply/work-kind-mutation`
    (Managed-Effects §Work-id correlation).
  - **`stale-reason`** — `:rf.resource/superseded` | `:rf.mutation/superseded`
    (the family's `:rf.reply/stale-reason` on the suppressed reply).

  …plus a `correlation-fn` (`(payload) → extra-correlation` — the family's
  diagnostic correlation keys beyond the carried/current `:generation` pair:
  `:resource/key` for a resource; `:instance/id` + `:mutation/id` for a
  mutation; both add `:scope`) and, at emit time, the `trace-id`
  (`:rf.resource/stale-suppressed` | `:rf.mutation/stale-suppressed`) plus the
  family's `bespoke-facts` (`:resource/key …` | `:instance …`). These ride the
  SAME mechanism — they are still per-family vocabulary, not behaviour.

  Pure extraction (rf2-nnke18): the verification logic, the emitted stale
  reply, the trace ids, and the carried-vs-current generation comparison are
  byte-identical to the pre-extraction copies in both families.

  ## Stale suppression is the correctness boundary

  A late reply carrying a superseded work-id / generation MUST NEVER mutate a
  newer (or another frame's) durable slot — Spec 016 §Cancellation is
  opportunistic; stale suppression is mandatory. The verifier checks the
  reply's stamped `:rf.frame/id` against the RECEIVING frame FIRST (a
  cross-frame reply is rejected before the per-frame slot lookup), then the
  slot's `:current-work` + `:generation` against the reply token. A reply with
  no stamped frame (a direct-dispatch test payload) skips the frame check (nil
  never collides with a concrete frame id) and is verified by work-id +
  generation alone."
  (:require [re-frame.resources.reply :as rreply]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; (1) Live-slot verifier (Spec 016 §Transport — stale suppression).
;; ---------------------------------------------------------------------------

(defn live-slot-for-reply
  "Look the live durable slot up for an internal reply and verify it is still
  the one the reply belongs to: the reply's stamped `:rf.frame/id` equals the
  RECEIVING frame (`receiving-frame-id`), the slot at `(path-fn payload)`
  exists, its `:current-work` equals the reply's `:work/id`, AND its
  `:generation` equals the reply's `:generation`. Returns the slot value on a
  match, nil on a cross-frame / stale / superseded / vanished reply (which MUST
  be suppressed).

  FRAME VERIFICATION (rf2-eu2ifi / rf2-jzh5gq): the runtime stamps the
  qualified `:rf.frame/id` into every reply payload at lowering; the reply
  handler runs in the RECEIVING frame's cofx. A reply whose payload frame does
  not match the receiving frame is REJECTED without touching this frame's slot
  or ledger — a misrouted reply (a cross-frame request-id collision, or a reply
  re-dispatched into the wrong frame) can never mutate the wrong frame's slot.
  The frame stamp is checked FIRST (before the per-frame slot lookup) so a
  cross-frame reply is rejected even when both frames happen to hold the same
  slot at the same generation. A reply with no stamped frame (a direct-dispatch
  test payload that omits `:rf.frame/id`) skips the frame check (nil never
  collides with a concrete frame id) and is verified by work-id + generation
  alone — the runtime-slice tests stay deterministic.

  The verification work identity is `:work/id` (EP-0007 — the qualified
  spelling the ledger row, the slot's `:current-work`, and the uniform reply
  envelope share). One attempt, one work id, one name. The generation check is
  belt-and-braces for a future transport that reuses a work-id.

  `path-fn` is the family's slot path: `(payload) → db-path` (the resource
  `state/entry-path`-by-`:resource/key`, or the mutation
  `mstate/instance-path`-by-`:instance-id`)."
  [runtime-db receiving-frame-id path-fn {work-id :work/id :keys [generation] :as payload}]
  (let [reply-frame (:rf.frame/id payload)]
    (when (or (nil? reply-frame) (= reply-frame receiving-frame-id))
      (when-let [slot (get-in runtime-db (path-fn payload))]
        (when (and (= work-id (:current-work slot))
                   (= generation (:generation slot)))
          slot)))))

;; ---------------------------------------------------------------------------
;; (2) Current-generation reader (the `:current` half of the stale gate).
;; ---------------------------------------------------------------------------

(defn current-generation
  "Read the LIVE counterpart generation for a stale-suppression gate: the
  `:generation` of the durable slot currently occupying `(path-fn payload)` at
  completion. nil when no slot occupies it (it was removed / GC'd / cleared —
  no live counterpart, exactly the supersession the gate records). This is the
  `:current` half of the carried-vs-current pair the canonical stale reply
  carries; the `:carried` half is the generation stamped on the reply token
  (`(:generation payload)`)."
  [runtime-db path-fn payload]
  (:generation (get-in runtime-db (path-fn payload))))

;; ---------------------------------------------------------------------------
;; (3) Stale-suppress reply builder (lowers through the shared substrate).
;; ---------------------------------------------------------------------------

(defn stale-suppress-reply
  "Build the canonical `:status :stale` reply outcome for a superseded /
  vanished reply through the SHARED `re-frame.reply` substrate (via
  `rreply/stale-reply`), so the family lowers its stale outcome exactly as
  every other managed-async family does (Managed-Effects §Stale suppression).
  The carried correlation is the reply token's `:work/id` + `:generation`; the
  current correlation is the live slot's `:generation` (nil when the slot is
  gone — no live counterpart). The result rides `:rf.reply/status :stale` /
  `:rf.reply/work-status :suppressed` / `:rf.reply/stale-reason` / the
  carried-vs-current generation pair ADDITIVELY onto the family's
  `…/stale-suppressed` trace via `emit-stale-suppressed!`.

  Returns the `re-frame.reply/suppress` outcome map (`:deliver?` is false — the
  app reply target MUST NOT run; `:reply` is the data-only `:status :stale`
  reply; `:rf.reply/work-status :suppressed`).

  The three family knobs:
  - `path-fn` reads the live slot's `:current` generation (see
    `current-generation`);
  - `work-kind` is `rreply/work-kind-resource` | `rreply/work-kind-mutation`;
  - `stale-reason` is the family's `:rf.reply/stale-reason`.
  `correlation-fn` is `(payload) → extra-correlation` (the family's diagnostic
  correlation keys merged ONTO the carried/current `:generation` pair —
  `:resource/key` / `:scope` for a resource; `:instance/id` / `:mutation/id` /
  `:scope` for a mutation). `extra` threads diagnostic facts (e.g. `:outcome`)
  onto the stale reply."
  [runtime-db path-fn {work-id :work/id :as payload} {:keys [work-kind stale-reason correlation-fn]} extra]
  (let [carried-gen (:generation payload)
        current-gen (current-generation runtime-db path-fn payload)]
    (rreply/stale-reply
      {:carried {:work/id work-id :generation carried-gen}
       :current {:generation current-gen}
       :extra   (merge {:rf.reply/work-id      work-id
                        :rf.reply/work-kind    work-kind
                        :rf.reply/stale-reason stale-reason
                        :correlation  (assoc (correlation-fn payload)
                                             :generation {:carried carried-gen
                                                          :current current-gen})}
                       extra)})))

;; ---------------------------------------------------------------------------
;; (4) Stale-suppressed trace emitter (additive reply-envelope vocabulary).
;; ---------------------------------------------------------------------------

(defn emit-stale-suppressed!
  "Emit the family's `…/stale-suppressed` trace (`trace-id`) for a suppressed
  late reply, carrying its bespoke facts (`bespoke-facts` — `:resource/key` /
  `:generation` / `:outcome` for a resource; `:instance` / `:generation` /
  `:outcome` for a mutation) PLUS the canonical reply-envelope vocabulary
  ADDITIVELY (the work identity rides as `:rf.reply/work-id` — rf2-o6c2jr, one
  name per fact): `:rf.reply/status :stale`, `:rf.reply/work-status
  :suppressed`, `:rf.reply/stale-reason`, `:rf.reply/work-id`, and
  `:rf.reply/correlation` (the carried-vs-current generation gate) — the SAME
  additive shape the machine `:rf.machine/done` and HTTP / probe stale paths
  ride (Managed-Effects §Tracing / EP-0011). `stale` is the
  `stale-suppress-reply` outcome; its trace summary routes wire slots through
  the shared elider via `rreply/trace-reply`.

  `bespoke-facts` is the family's leading per-trace map (its caller-side
  spelling of `:rf.frame/id` + the durable key + `:generation` / `:outcome`);
  the additive `:rf.reply/*` facts are MERGED on top."
  [trace-id bespoke-facts stale]
  (let [summary (rreply/trace-reply (:reply stale))]
    (trace/emit! :rf.event trace-id
                 (merge bespoke-facts
                        {;; reply-envelope vocabulary (Managed-Effects §9) — the
                         ;; canonical :status :stale reply produced via the
                         ;; shared substrate, recorded ADDITIVELY (the bespoke
                         ;; facts above are preserved).
                         :rf.reply/status      (:status summary)
                         :rf.reply/work-status (:rf.reply/work-status summary)
                         :rf.reply/work-id     (:rf.reply/work-id summary)
                         :rf.reply/stale-reason (:rf.reply/stale-reason summary)
                         :rf.reply/correlation (:correlation summary)
                         ;; rf2-waawic — the SHARED carried/current stale-gate
                         ;; facts `re-frame.reply/suppress` already computed on
                         ;; `(:trace stale)`. Projecting them here lets the
                         ;; uniform reply-envelope view read the stale gate
                         ;; without family-specific parsing (the
                         ;; `:rf.reply/correlation` above is the reply's bespoke
                         ;; generation pair; these are the shared substrate facts
                         ;; Xray's reply-envelope panel reads).
                         :rf.reply/carried     (:rf.reply/carried (:trace stale))
                         :rf.reply/current     (:rf.reply/current (:trace stale))}))))
