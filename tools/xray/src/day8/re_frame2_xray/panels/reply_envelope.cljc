(ns day8.re-frame2-xray.panels.reply-envelope
  "ONE work/reply vocabulary for Xray + the trace tooling, reading the
  canonical *uniform reply envelope* (EP-0011) rather than per-family
  private callback shapes (rf2-zqefg3.7).

  ## Why this namespace exists

  Before EP-0011 each managed-async family spelled its completion
  continuation differently, and each Xray panel learned that family's
  private vocabulary: the resources panel reads `:rf.resource/work-*`
  ops, the cancellation-cascade visualiser reads
  `:rf.http/aborted-on-actor-destroy` + `:rf.machine.timer/cancelled`,
  the managed-fx panel reads `:rf.http/handled` /
  `:rf.machine.lifecycle/spawned`. That is N vocabularies for one
  concept — \"this async work was issued, ran, retried, was cancelled,
  completed with a status, was suppressed as stale, and either
  delivered or did not.\"

  [`spec/Managed-Effects.md` §The uniform reply
  envelope](../../../../spec/Managed-Effects.md) gives the runtime ONE
  shape for that: every managed-async family (HTTP / resources /
  mutations / route loaders / machine async work / future managed
  timers) completes through a single **reply map** carrying one closed
  `:status`, value/error data, `:work/id` work correlation,
  `:work/kind`, `:work/status`, `:attempt`, `:rf.frame/id`, the durable
  timestamps, the cancellation/staleness facts, and a `:correlation`
  side-bag (`re-frame.reply` is the framework substrate). Property 9 /
  §Tracing mandate that families emit their trace rows FROM those
  reply-envelope facts.

  This namespace is the **consumer-side mirror** of that contract: a
  pure-data reader that projects the canonical envelope facts off either
  a reply map (the appended last-arg of a reply-target event) or a trace
  row's `:tags`, into ONE render-safe row vocabulary keyed on
  `:work/id` — so a panel answers \"issued / running / retried /
  cancellation-requested / completed-:ok|:partial|:error|:cancelled|
  :stale / stale-suppressed / delivered\" the SAME way across every
  family.

  ## Bundle isolation

  Xray does NOT `:require` `re-frame.reply` (the substrate ALGEBRA) — the
  closed vocabularies below are MIRRORED as literal data, exactly as
  `resources-helpers` mirrors the reserved runtime-db keys. The mirror is
  the bundle-isolation-safe price of not adding a require edge into the
  reply substrate; a drift would be caught by the closed-vocabulary
  wiring test. It DOES `:require` `re-frame.trace` for the contract-owned
  canonical RAW trace-event frame reader (`trace-event-frame`,
  rf2-7737vq) — the same trace-CONTRACT reader the rest of Xray already
  consumes (`self-noise`, `trace-collector`, `runtime`); that is a read of
  the published trace contract, not the substrate fns. The bundle
  contract is the no-tool-imports-CORE direction — nothing in
  `implementation/` may require `tools/` — which neither edge violates;
  Xray consumes the WIRE FACTS + the trace contract, never the substrate
  algebra.

  ## PRIVACY

  Same posture as `resources-helpers`: the wire-bearing slots
  (`:value`, `:error`, `:correlation`, `:meta`) are summarized through
  `resources-helpers/summarize` — the runtime already routed them
  through the shared `re-frame.elision/elide-wire-value` walker on the
  trace egress (Managed-Effects §Tracing), so a `:sensitive?` slot
  arrives as `:rf/redacted` and a `:large?` slot as
  `:rf.size/large-elided`; we never render a raw payload. The IDENTITY
  facts (work id, kind, status, attempt, frame, timestamps, cancel/stale
  reasons) are framework bookkeeping and ride verbatim.

  ## Pure

  Every fn here is pure data → data, JVM-runnable so the algebra runs
  under the unit-test target without a CLJS runtime."
  (:require [day8.re-frame2-xray.panels.resources-helpers :as rh]
            ;; the contract-owned canonical RAW trace-event frame reader
            ;; (`[:tags :frame]` — Spec 009 §Frame identity on the raw event,
            ;; rf2-7737vq). NOT the substrate algebra (`re-frame.reply`) the
            ;; closed vocabularies below MIRROR: `re-frame.trace` is the trace
            ;; CONTRACT reader xray already consumes (`self-noise`,
            ;; `trace-collector`, `runtime`); the no-tool-imports-core bundle
            ;; direction (core MUST NOT require tools) is unaffected.
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; The closed vocabularies (MIRRORED from re-frame.reply — Managed-Effects
;; §Status taxonomy / §The reply map). Closed by design: a managed-async
;; completion is exactly one of these statuses. Mirrored as literal data so
;; Xray never requires the substrate (bundle isolation); the wiring test pins
;; the mirror against the substrate's `re-frame.reply/statuses` set.
;; ---------------------------------------------------------------------------

(def reply-statuses
  "The CLOSED reply `:status` vocabulary (Managed-Effects §Status taxonomy
  — mirror of `re-frame.reply/statuses`). Exactly one is a reply's status:

    - `:ok`        — completed successfully, reply current; `:value` present.
    - `:partial`   — completed with usable `:value` AND structured family
                     problems under `:error` (GraphQL is the motivating case).
    - `:error`     — completed with a failure, reply current; `:error` present.
    - `:cancelled` — intentionally cancelled while still correlated;
                     `:cancel/reason` present.
    - `:stale`     — completed/observed after correlation became obsolete;
                     `:stale? true` + `:stale/reason`; NO app-state mutation."
  #{:ok :partial :error :cancelled :stale})

(def work-statuses
  "The operational `:work/status` vocabulary on a reply map / ledger row
  (mirror of `re-frame.reply/work-statuses`). Narrower / more operational
  than reply `:status`: `:timed-out` is an error work-status (timeout is an
  error KIND + a work status, NOT a top-level reply status); `:suppressed`
  is the stale terminal."
  #{:completed :failed :timed-out :suppressed :cancelled})

(def work-kinds
  "The `:work/kind` vocabulary across the managed-async families
  (Managed-Effects §Work-id correlation — the work-id tuple heads). The
  family a work id / reply belongs to. `:resource` covers mutations too —
  a mutation reuses the resource work-id head and is distinguished by the
  ledger row's `:work/kind :mutation`, so both `:resource` and `:mutation`
  appear as kinds on rows."
  #{:http :resource :mutation :route :machine :timer})

(defn reply-status?
  "True iff `status` is a member of the closed reply-status vocabulary."
  [status]
  (contains? reply-statuses status))

(def terminal-reply-statuses
  "The reply statuses that are TERMINAL for the attempt (the work settled,
  one way or another). All five reply statuses are terminal — a reply map
  is only produced at completion. `:running` is a ledger/issuance status,
  NOT a reply status (issuance has no reply map yet)."
  reply-statuses)

;; ---------------------------------------------------------------------------
;; Reply-map field readers (Managed-Effects §The reply map). A reply map is
;; the appended last-arg of a reply-target event, or the data-only trace
;; summary a family emits on completion. The work-correlation reads the
;; canonical `:work/id` (EP-0011 one-name-per-fact: every family — HTTP,
;; resources, mutations — emits the qualified `:work/id` on both the reply
;; map and the trace row's `:tags`; no bare `:work-id` trace-tag spelling
;; remains to tolerate). The frame reader still accepts either the canonical
;; `:rf.frame/id` or the bare `:frame-id` some trace rows stamp, so one
;; reader works on both the dispatched reply map and the trace row's `:tags`.
;; ---------------------------------------------------------------------------

(defn reply-map?
  "Best-effort detector: is `m` a uniform reply map (Managed-Effects §The
  reply map)? True iff `m` is a map carrying a closed reply `:status`. Used
  to recognise the appended last-arg of a reply-target event vector."
  [m]
  (and (map? m) (reply-status? (:status m))))

(defn work-id-of
  "Read the work-correlation off a reply map or trace tags. The single
  attempt identity (Managed-Effects §Work-id correlation — `=`-comparable,
  EDN-serializable); the key the stale-races view groups on.

  rf2-o6c2jr — the CANONICAL trace-tag spelling is `:rf.reply/work-id`; the
  bare `:work/id` trace-tag duplicate was dropped (one name per fact —
  Conventions rule 1). Prefer `:rf.reply/work-id`; fall back to `:work/id`,
  which is still the canonical key ON THE REPLY MAP itself (the durable
  work-ledger identity — untouched by the tag-duplicate cull). nil when
  absent (a non-ledger-backed managed async)."
  [m]
  (or (:rf.reply/work-id m) (:work/id m)))

(defn work-kind-of
  "Read the `:work/kind` family tag off a reply map or trace tags (one of
  `work-kinds`), tolerating `:work/kind` or bare `:work-kind`/`:kind`. nil
  when absent. When absent on a reply, `infer-work-kind` derives it from the
  work-id tuple head."
  [m]
  (or (:work/kind m) (:work-kind m) (:kind m)))

(defn infer-work-kind
  "Infer the `:work/kind` from a `:work/id` tuple head when the reply / row
  did not carry an explicit `:work/kind`. The tuple heads are owned by each
  family (Managed-Effects §Work-id correlation):

    `[:rf.work/http  …]`     → `:http`
    `[:rf.work/resource …]`  → `:resource` (mutation reuses this head; the
                               row's explicit `:work/kind :mutation` wins)
    `[:rf.work/route …]`     → `:route`
    `[:rf.work/machine …]`   → `:machine`
    `[:rf.work/timer …]`     → `:timer`

  Returns nil for an unrecognised / non-tuple work id (let the caller fall
  back to an explicit `:work/kind`)."
  [work-id]
  (when (and (vector? work-id) (keyword? (first work-id)))
    (case (first work-id)
      :rf.work/http     :http
      :rf.work/resource :resource
      :rf.work/route    :route
      :rf.work/machine  :machine
      :rf.work/timer    :timer
      nil)))

(defn resolve-work-kind
  "The work-kind for a reply map / row: the explicit `:work/kind` when
  present, else inferred from the `:work/id` tuple head. Pure."
  [m]
  (or (work-kind-of m)
      (infer-work-kind (work-id-of m))))

;; ---------------------------------------------------------------------------
;; The uniform reply row (rf2-zqefg3.7 — the ONE vocabulary). Projects a
;; reply map (Managed-Effects §The reply map) into a render-safe row that
;; reads the SAME across every family. PRIVACY: the wire-bearing slots
;; (`:value` / `:error` / `:correlation` / `:meta`) are summarized; the
;; identity facts ride verbatim.
;; ---------------------------------------------------------------------------

(defn reply-row
  "Project ONE uniform reply map into a render-safe row reading the
  canonical EP-0011 envelope facts (Managed-Effects §The reply map /
  §Status taxonomy). The SINGLE vocabulary a panel renders regardless of
  the family the reply came from:

      {:work-id      [:rf.work/http :article/by-id 1]   ; the attempt identity
       :work-kind    :http                              ; explicit or inferred
       :status       :ok                                ; closed reply status
       :work-status  :completed                         ; operational work status
       :attempt      1
       :frame        <frame-id>
       :started-at   …  :completed-at … :deadline-at …  ; durable causal ms
       :value        <summary>   ; PRIVACY — summarized (nil for :error/:stale)
       :error        <summary>   ; PRIVACY — summarized (failure envelope)
       :error-kind   :rf.http/timeout                   ; the family error :kind
       :correlation  <summary>   ; PRIVACY — request-id / nav-token side-bag
       :stale?       false
       :stale-reason nil
       :cancelled?   false
       :cancel-reason nil
       :delivered?   true        ; false ⇔ a stale/suppressed reply not dispatched
       :meta         <summary>}  ; PRIVACY — family-specific data

  `:delivered?` is the EP-0011 delivery-vs-non-delivery fact (Managed-
  Effects §Tracing): a `:stale` reply is NOT delivered to the app target
  (`:delivered? false`) unless a framework test/tool target opted into
  `:dispatch-stale?` (which the runtime would record on the trace row as
  `:rf.reply/delivered? true`); every non-stale terminal reply is delivered.

  PRIVACY: `:value` / `:error` / `:correlation` / `:meta` are summarized
  via `resources-helpers/summarize` (the runtime already elided sensitive /
  large slots on the wire). Pure."
  [reply]
  (let [work-id      (work-id-of reply)
        status       (:status reply)
        error        (:error reply)
        ;; the runtime emits the delivered? fact on the trace row when it
        ;; differs from the derivable default; absent ⇒ derive it (a stale
        ;; reply is non-delivered, everything else delivered).
        delivered?   (cond
                       (contains? reply :rf.reply/delivered?) (boolean (:rf.reply/delivered? reply))
                       (= status :stale)                      false
                       :else                                  true)]
    {:work-id       work-id
     :work-kind     (resolve-work-kind reply)
     :status        status
     :work-status   (or (:work/status reply) (:work-status reply))
     :attempt       (:attempt reply)
     ;; The reply MAP's frame is the canonical EP-0002 carried-frame stamp
     ;; `:rf.frame/id` — UNIFORM across every family on the reply map
     ;; (Managed-Effects §The reply map — "there is no second frame spelling";
     ;; HTTP's reply builder maps its internal `:frame` ctx ONTO `:rf.frame/id`
     ;; on the dispatched map). This reply-map layer is distinct from the raw
     ;; trace-event layer (where HTTP rows ride the bare `[:tags :frame]`
     ;; carve-out — see `work-event-row`), so the canonical raw-event reader
     ;; `trace-event-frame` does not apply to a reply map. The historical dead
     ;; `:frame-id` / bare `:frame` reply-map aliases are gone (rf2-l9vb09).
     :frame         (:rf.frame/id reply)
     :started-at    (:started-at reply)
     :completed-at  (:completed-at reply)
     :deadline-at   (:deadline-at reply)
     :value         (when (contains? reply :value) (rh/summarize (:value reply)))
     :error         (when (some? error) (rh/summarize error))
     :error-kind    (when (map? error) (:kind error))
     :correlation   (when (contains? reply :correlation) (rh/summarize (:correlation reply)))
     :stale?        (boolean (:stale? reply))
     :stale-reason  (:stale/reason reply)
     :cancelled?    (boolean (:cancelled? reply))
     :cancel-reason (:cancel/reason reply)
     :delivered?    delivered?
     :meta          (when (contains? reply :meta) (rh/summarize (:meta reply)))}))

;; ---------------------------------------------------------------------------
;; Status presentation — ONE colour-class + label mapping for the five reply
;; statuses, shared across every family panel + the Trace tab so a `:stale`
;; HTTP reply and a `:stale` resource reply render the SAME badge
;; (Cross-Cutting F.11 — the unified cross-surface STALE badge).
;; ---------------------------------------------------------------------------

(def status->class
  "The semantic colour class for a reply `:status`, shared across families.
  Keys the panel + Trace-tab colour mapping uniformly so the cross-surface
  badge (Cross-Cutting F.11) renders identically regardless of family:

    `:ok`        → `:success`
    `:partial`   → `:partial`     (usable data + structured problems)
    `:error`     → `:failure`
    `:cancelled` → `:cancellation`
    `:stale`     → `:suppression` (the correctness-boundary event)"
  {:ok        :success
   :partial   :partial
   :error     :failure
   :cancelled :cancellation
   :stale     :suppression})

(def status->label
  "Short human label for a reply `:status` — uppercase badge text."
  {:ok        "OK"
   :partial   "PARTIAL"
   :error     "ERROR"
   :cancelled "CANCELLED"
   :stale     "STALE"})

(defn status-class
  "The colour class for a reply `:status` (one of `:success` / `:partial` /
  `:failure` / `:cancellation` / `:suppression`), or nil for a non-status."
  [status]
  (get status->class status))

(defn status-label
  "The badge label for a reply `:status`, or the bare status name for an
  unknown status."
  [status]
  (get status->label status (some-> status name)))

;; ---------------------------------------------------------------------------
;; The cross-family reply trace operation vocabulary (Managed-Effects
;; §Tracing). Property 9 / §Tracing require families to emit trace rows FROM
;; the reply-envelope facts. The runtime emits these as the family-namespaced
;; ops listed below; this section gives Xray ONE classifier that recognises
;; the reply-envelope LIFECYCLE PHASE a family op represents, so a panel reads
;; \"issuance / retry / cancellation-requested / completion / stale-suppression
;; / delivery\" uniformly across families instead of memorising each family's
;; op set.
;; ---------------------------------------------------------------------------

(def reply-phases
  "The closed set of reply-envelope lifecycle PHASES a managed-async family
  trace row can represent (Managed-Effects §Tracing):

    - `:issued`         — issuance/start (work-ledger row created; carries
                          `:work/id`, frame, owner/cause, target summary).
    - `:retry`          — a retry / intermediate transition (a new attempt).
    - `:cancel-requested`— cancellation-requested (reason + whether a host
                          handle existed); opportunistic, not yet terminal.
    - `:completed`      — completion classified as one of the five reply
                          statuses (the row carries the `:status`).
    - `:stale-suppressed`— stale suppression (the correctness boundary —
                          carried/current correlation; reply NOT delivered).
    - `:delivered`      — delivery-or-explicit-non-delivery of the reply."
  #{:issued :retry :cancel-requested :completed :stale-suppressed :delivered})

(def ^:private op->phase
  "Map each family's emitted trace `:operation` onto the reply-envelope
  PHASE it represents (Managed-Effects §Tracing). The families spell their
  ops in their own reserved namespace (Conventions §Reserved namespaces);
  this is the single cross-family lowering Xray reads so a panel groups by
  phase, not by family. Covers the families lowered onto the envelope today
  (HTTP / resources / mutations / routing / machines / timers); a family op
  not listed falls through to `phase-of`'s namespace+suffix heuristic so a
  newly-added op is still classified before this table is extended."
  {;; ---- issuance / start ----
   ;; Resources lower their issuance onto the work-ledger row (`.3`, landed):
   :rf.resource/work-started               :issued
   :rf.resource/fetch-started              :issued
   ;; Mutations reuse the resource work-ledger substrate (`:work/kind
   ;; :mutation`); `:rf.mutation/started` is the issuance row (EP-0016 D1 —
   ;; the mutation phase order, phase 2: the managed request is issued under
   ;; runtime-owned reply addressing).
   :rf.mutation/started                    :issued
   ;; HTTP issues the request through the `:rf.http/managed` fx (`.2`, landed);
   ;; the canonical completion is `:rf.http/replied` (see :completed below).
   ;; Machines (`.4`) + routing (`.5`) issuance ops are forward-looking; the
   ;; suffix heuristic classifies them until those PRs land their literals.
   :rf.machine.timer/scheduled             :issued
   ;; ---- retry / intermediate ----
   :rf.http/retry-attempt                  :retry
   :rf.resource/refetch-decision           :retry
   ;; ---- cancellation-requested (opportunistic; not yet terminal) ----
   :rf.resource/work-abort-requested       :cancel-requested
   :rf.http/aborted-on-actor-destroy       :cancel-requested
   :rf.http/managed-abort                  :cancel-requested
   :rf.machine.timer/cancelled             :cancel-requested
   :rf.machine.spawn/cancelled-on-join-resolution :cancel-requested
   :rf.route/cancel                        :cancel-requested
   ;; ---- completion (carries the reply :status) ----
   ;; `:rf.http/replied` is the canonical EP-0011 HTTP completion row — built
   ;; FROM the reply-envelope facts (`http-reply/trace-reply`), carrying
   ;; `:status` / `:work/id` / `:work/kind` / `:work/status` (rf2-zqefg3.2).
   :rf.http/replied                        :completed
   :rf.http/succeeded                      :completed
   :rf.http/failed                         :completed
   :rf.http/transport                      :completed
   :rf.http/timeout                        :completed
   :rf.http/decode-failure                 :completed
   :rf.http/aborted                        :completed
   :rf.resource/work-completed             :completed
   :rf.resource/succeeded                  :completed
   :rf.resource/failed                     :completed
   :rf.resource/refresh-failed             :completed
   ;; Mutation settlement (EP-0016 D1 — phase 5, instance + work-ledger
   ;; settled; the row carries the accepted reply `:status` via the canonical
   ;; reply map). A `:rf.mutation/succeeded` is `:ok`; `:rf.mutation/failed` is
   ;; `:error` or an accepted terminal `:cancelled`.
   :rf.mutation/succeeded                  :completed
   :rf.mutation/failed                     :completed
   :rf.machine.timer/fired                 :completed
   :rf.machine/done                        :completed
   ;; ---- stale suppression (the correctness boundary) ----
   ;; Resources emit `:rf.resource/stale-suppressed` (landed). The shared
   ;; substrate suppression trace is `:rf.reply/suppressed` (re-frame.reply
   ;; §suppress). Routing emits `:rf.route.nav-token/stale-suppressed`.
   ;; rf2-waawic — the machine `:after` timer emits
   ;; `:rf.machine.timer/stale-after` (a reply-shaped stale completion); the
   ;; suffix heuristic catches `stale-suppress` / `suppressed` but NOT
   ;; `stale-after`, so an EP-0011 managed async stale completion from
   ;; machines was invisible to the uniform reply-envelope view. Enumerate it
   ;; explicitly. rf2-azcmd3 — HTTP supersession emits
   ;; `:rf.http/stale-suppressed` (a canonical superseded-attempt stale row).
   :rf.resource/stale-suppressed           :stale-suppressed
   :rf.route.nav-token/stale-suppressed    :stale-suppressed
   :rf.machine.timer/stale-after           :stale-suppressed
   :rf.http/stale-suppressed               :stale-suppressed
   ;; Mutations emit `:rf.mutation/stale-suppressed` for a superseded / cleared
   ;; / cross-frame reply (EP-0016 D1 — a stale reply NEVER fires the
   ;; `:reply-to` continuation; it suppresses here instead), carrying the
   ;; identical `:rf.reply/*` correlation shape (carried-vs-current generation).
   :rf.mutation/stale-suppressed           :stale-suppressed
   :rf.reply/suppressed                    :stale-suppressed
   ;; ---- delivery ----
   ;; `:rf.mutation/replied` is the call-site `:reply-to` continuation dispatch
   ;; (EP-0016 D1 — phase 6, after cache consequences + instance settlement). A
   ;; row here is the accepted reply CONTINUING into app workflow; it is emitted
   ;; only for an accepted terminal reply, never a stale one.
   :rf.mutation/replied                    :delivered
   :rf.reply/delivered                     :delivered})

(def ^:private suffix->phase
  "Heuristic fallback: the reply-envelope phase a family op's NAME suffix
  implies, used when an op is not in the explicit `op->phase` table (a
  newly-added family op). Matched against the op's `name` by substring so a
  family that adds e.g. `:rf.stream/work-started` is still classified
  `:issued` before this code learns the literal op."
  [["stale-suppress" :stale-suppressed]
   ["suppressed"      :stale-suppressed]
   ["abort"           :cancel-requested]
   ["cancel"          :cancel-requested]
   ["retry"           :retry]
   ["work-started"    :issued]
   ["started"         :issued]
   ["issued"          :issued]
   ["scheduled"       :issued]
   ["delivered"       :delivered]
   ["completed"       :completed]
   ["succeeded"       :completed]
   ["failed"          :completed]
   ["done"            :completed]
   ["fired"           :completed]])

(defn phase-of
  "The reply-envelope lifecycle phase a family trace `:operation` represents
  (one of `reply-phases`), or nil for a non-managed-async op. Reads the
  explicit `op->phase` table first; falls back to the `suffix->phase`
  heuristic so a family op not yet enumerated is still classified
  (Managed-Effects §Tracing — families emit FROM the envelope facts).
  Pure."
  [operation]
  (when (keyword? operation)
    (or (get op->phase operation)
        (let [nm (name operation)]
          (some (fn [[needle phase]]
                  (when #?(:clj (.contains ^String nm ^String needle)
                           :cljs (not= -1 (.indexOf nm needle)))
                    phase))
                suffix->phase)))))

(defn reply-trace-op?
  "True iff `operation` is a managed-async reply-envelope trace op — one
  that maps onto a reply-envelope `phase-of`. Lets a panel filter the trace
  stream to the cross-family work/reply rows without memorising each
  family's op set."
  [operation]
  (boolean (phase-of operation)))

;; ---------------------------------------------------------------------------
;; Cross-family trace-row projection. A managed-async trace row (any family)
;; → one uniform work/reply row keyed on `:work/id`, carrying the phase + the
;; envelope facts the row could observe. PRIVACY: wire-bearing tags
;; summarized. The work-id is the join key the stale-races + live-work views
;; group on.
;; ---------------------------------------------------------------------------

(defn- trace-op [ev] (or (:operation ev) (:op ev)))
(defn- trace-tags [ev] (or (:tags ev) {}))

(defn work-event-row
  "Project ONE managed-async trace event (any family) into a uniform
  work/reply row — the SAME shape regardless of which family emitted it
  (Managed-Effects §Tracing — families emit FROM the envelope facts).

      {:id          <trace-id>
       :operation   :rf.resource/work-completed
       :phase       :completed                      ; the reply-envelope phase
       :work-kind   :resource
       :work-id     [:rf.work/resource <key> 4]     ; the join key
       :frame       <frame-id>
       :status      :ok | :error | …                ; on a :completed row
       :status-class :success | …                   ; cross-surface colour
       :work-status :completed
       :stale?      false                           ; on a :stale-suppressed row
       :carried     {:work/id … :generation …}      ; suppression carried gate
       :current     {:work/id … :generation …}      ; suppression current gate
       :cancel-reason :actor-destroyed              ; on a :cancel-requested row
       :delivered?  true                            ; on a :delivered row
       :time        <ms>
       :dispatch-id <id>}

  Reads the envelope facts the family stamped on the row per §Tracing: the
  `:work/id`, the frame, the completion `:status`, the
  carried/current correlation on a stale-suppression row, the cancel reason
  on a cancel-requested row. nil for a non-managed-async op. PRIVACY: the
  carried/current correlation + any value/error tags are summarized. Pure."
  [ev]
  (let [op    (trace-op ev)
        phase (phase-of op)]
    (when phase
      (let [tags    (trace-tags ev)
            work-id (work-id-of tags)
            status  (cond
                      (= phase :completed)
                      ;; read the closed reply status from EITHER the bare
                      ;; `:status` OR the additive production `:rf.reply/status`
                      ;; key (machine / resource / mutation completion rows stamp
                      ;; the latter ALONGSIDE a bespoke `:status`). Some resource
                      ;; completion rows stamp the LEDGER status
                      ;; (`:completed`/`:failed`) under bare `:status`; the
                      ;; closed reply status is what the cross-surface badge keys
                      ;; on, so only surface a real reply status, preferring the
                      ;; unambiguous `:rf.reply/status` when present.
                      (let [s (or (:rf.reply/status tags) (:status tags))]
                        (when (reply-status? s) s))

                      (= phase :stale-suppressed)
                      ;; EP-0011 treats `:stale` as one of the five closed reply
                      ;; statuses (Managed-Effects §Status taxonomy). A failed
                      ;; stale-suppression gate makes the reply outcome
                      ;; `:status :stale`, stamped on the production row as
                      ;; `:rf.reply/status`. Read ONLY the unambiguous
                      ;; `:rf.reply/status` here — NOT the bare `:status`, which
                      ;; on a suppression row may carry the LEDGER status
                      ;; (`:completed`/`:failed`/`:suppressed`), not a reply
                      ;; status — so the row carries the canonical `:stale`
                      ;; status + the cross-surface `:suppression` badge that
                      ;; `status->class` keys on, the SAME contract a completed
                      ;; row renders.
                      (let [s (:rf.reply/status tags)]
                        (when (reply-status? s) s)))]
        (cond-> {:id           (:id ev)
                 :operation    op
                 :phase        phase
                 :work-kind    (or (work-kind-of tags) (infer-work-kind work-id))
                 :work-id      work-id
                 ;; Frame attribution across families (rf2-l9vb09). Two
                 ;; legitimate frame spellings ride a managed-async reply trace
                 ;; row, by family:
                 ;;   - resources / machines / mutations stamp the EP-0002
                 ;;     carried-frame stamp `:rf.frame/id` in `:tags`
                 ;;     (Managed-Effects §The reply map — the reply-envelope
                 ;;     facts the family emits its row FROM);
                 ;;   - HTTP stamps the bare `:frame` in `:tags` — the generic
                 ;;     raw-event carve-out read by the contract-owned canonical
                 ;;     reader `re-frame.trace/trace-event-frame` ([:tags :frame],
                 ;;     rf2-7737vq).
                 ;; Prefer `:rf.frame/id`, falling back to the canonical reader
                 ;; for the bare `[:tags :frame]` slot. The historical dead
                 ;; aliases — bare `:frame-id` in `:tags` (rf2-shaa1 dropped it;
                 ;; no emit site produces it) and a top-level `:frame` on the
                 ;; raw event (raw events carry frame ONLY under `:tags`) — are
                 ;; gone.
                 :frame        (or (:rf.frame/id tags) (trace/trace-event-frame ev))
                 ;; rf2-waawic — tolerate the additive `:rf.reply/work-status`
                 ;; production key so machine / resource / mutation rows do not
                 ;; lose their work status.
                 :work-status  (or (:work/status tags) (:work-status tags)
                                   (:rf.reply/work-status tags))
                 :time         (:time ev)
                 :dispatch-id  (or (:rf.trace/dispatch-id tags) (:dispatch-id tags))}
          status
          (assoc :status status :status-class (status-class status))

          (= phase :stale-suppressed)
          (assoc :stale?  true
                 :carried (when (contains? tags :rf.reply/carried)
                            (rh/summarize (:rf.reply/carried tags)))
                 :current (when (contains? tags :rf.reply/current)
                            (rh/summarize (:rf.reply/current tags)))
                 :stale-reason (or (:stale/reason tags) (:rf.reply/stale-reason tags)
                                   (:reason tags) (:outcome tags)))

          (= phase :cancel-requested)
          (assoc :cancelled?    true
                 :cancel-reason (or (:cancel/reason tags) (:reason tags) (:cancel-cause tags)))

          (= phase :delivered)
          (assoc :delivered? (if (contains? tags :rf.reply/delivered?)
                               (boolean (:rf.reply/delivered? tags))
                               true)))))))

(defn project-work-events
  "Project a trace buffer into the uniform cross-family work/reply rows
  (oldest-first, preserving buffer order). Every managed-async trace event —
  HTTP, resource, mutation, route, machine, timer — projects through the
  SAME `work-event-row` vocabulary; non-managed-async rows are dropped. Pure
  over the trace vector."
  [trace-buffer]
  (into [] (keep work-event-row) (or trace-buffer [])))

;; ---------------------------------------------------------------------------
;; Stale-races view — keyed on :work/id (rf2-zqefg3.7 bead requirement /
;; Cross-Cutting F-C5 stale-suppression tally / F.11 cross-surface badge).
;; Groups the cross-family work/reply rows by `:work/id` so the operator sees
;; each attempt's arc (issued → … → completed/suppressed) and the races where
;; one work id was superseded by another (carried ≠ current correlation).
;; ---------------------------------------------------------------------------

(defn stale-suppressions
  "The cross-family stale-suppression rows from a trace buffer — every
  family's `:stale-suppressed` phase row, projected through the uniform
  vocabulary, keyed by `:work/id` (Managed-Effects §Stale suppression — the
  correctness boundary; the row carries the carried + current correlation).
  This is the Cross-Cutting F-C5 stale-suppression tally / F.11 cross-surface
  STALE badge, now UNIFORM across HTTP / resources / mutations / routing /
  machines (each family's stale-suppressed op lowers to the same phase). Pure."
  [trace-buffer]
  (->> (project-work-events trace-buffer)
       (filterv #(= :stale-suppressed (:phase %)))))

(defn stale-tally-by-kind
  "Count stale suppressions per `:work-kind` across the trace buffer — the
  cross-surface stale-suppression tally (Cross-Cutting F-C5). Returns
  `{:resource 2 :http 1 …}`. A high count for one family flags a
  supersession storm there. Pure."
  [trace-buffer]
  (->> (stale-suppressions trace-buffer)
       (group-by :work-kind)
       (reduce-kv (fn [m k rows] (assoc m k (count rows))) {})))

(defn races-by-work-id
  "Group every cross-family work/reply trace row by its `:work/id` — the
  attempt-arc view the stale-races panel renders (Managed-Effects §Work-id
  correlation — `:work/id` is the SINGLE attempt identity, the key stale
  suppression and the ledger and this view all share). Returns

      {<work-id> {:work-id … :work-kind … :rows [<row> …]
                  :phases #{:issued :completed …}
                  :terminal-status :ok|:error|:cancelled|:stale|nil
                  :suppressed? bool}}

  `:terminal-status` is the completion `:status` if the arc reached a
  `:completed` row, `:stale` if it was stale-suppressed, else nil (still
  in-flight). `:suppressed?` flags an arc that hit the stale-suppression
  correctness boundary. Rows with no `:work/id` (a non-ledger-backed managed
  async) are grouped under the `nil` key. Pure."
  [trace-buffer]
  (let [rows (project-work-events trace-buffer)]
    (->> rows
         (group-by :work-id)
         (reduce-kv
           (fn [acc work-id grp]
             (let [phases       (into #{} (map :phase) grp)
                   completed    (some #(when (= :completed (:phase %)) (:status %)) grp)
                   suppressed?  (contains? phases :stale-suppressed)
                   terminal     (cond suppressed? :stale
                                      completed   completed
                                      :else       nil)]
               (assoc acc work-id
                      {:work-id         work-id
                       :work-kind       (some :work-kind grp)
                       :rows            (vec grp)
                       :phases          phases
                       :terminal-status terminal
                       :suppressed?     suppressed?})))
           {}))))

;; ---------------------------------------------------------------------------
;; \"What is still running?\" — work-ledger rows joined to reply status +
;; trace cause, UNIFORM across families (rf2-zqefg3.7 bead requirement /
;; Cross-Cutting F-C4 active managed-effects dashboard). The work ledger is
;; the durable substrate (Managed-Effects §Work-ledger integration — a ledger
;; row IS the reified continuation); a non-terminal row is live work. Joining
;; the ledger row to the latest trace phase for its `:work/id` answers \"is it
;; issued / retrying / cancellation-requested?\" across every family with ONE
;; query.
;; ---------------------------------------------------------------------------

(def ledger-key
  "Reserved runtime-db key for the frame work ledger (Managed-Effects
  §Work-ledger integration / Conventions §Reserved runtime-db keys —
  `:rf.runtime/work-ledger`). Mirror of `resources-helpers/work-ledger-key`,
  named here as the cross-family vocabulary's own home so a work/reply
  consumer reads one place. Bundle-isolation-safe literal."
  :rf.runtime/work-ledger)

(def non-terminal-work-statuses
  "The non-terminal ledger statuses an attempt moves through while still live
  (Managed-Effects §Work-ledger integration — issuance writes a `:running`
  row; `:abort-requested` is non-terminal until the transport settles). A
  ledger row in one of these IS live work. Complement of `work-statuses`'s
  terminal members."
  #{:queued :running :abort-requested})

(defn live?
  "True iff a ledger row `:status` is non-terminal (the attempt is still
  running / queued / abort-requested). Pure."
  [status]
  (contains? non-terminal-work-statuses status))

(defn ledger-row
  "Project ONE work-ledger record `[work-id record]` into the uniform
  work/reply row vocabulary (Managed-Effects §Work-ledger integration). The
  cross-family mirror of `resources-helpers/work-row`, but keyed on the
  ENVELOPE vocabulary (`:work-kind` / `:work/id` / live?) rather than
  resource-specific fields, so HTTP / route / machine / timer ledger rows (as
  later families write them) project the SAME way:

      {:work-id      <id>
       :work-kind    :resource | :mutation | :http | :route | :machine | :timer
       :status       :running                    ; the ledger row status
       :live?        true                        ; non-terminal ⇒ still running
       :frame        <frame-id>
       :owners       [<owner> …]                 ; who needs this work
       :causes       [<summary> …]               ; what triggered it (summarized)
       :cancellable? true
       :started-at   …  :deadline-at …
       :attempt      <n>
       :transport    :rf.http/managed
       :outcome      <summary>}                  ; terminal-row outcome summary

  PRIVACY: `:causes` + `:outcome` are summarized (a cause may carry data).
  The host handles (AbortControllers / promises / timer handles) live in a
  side table and are STRUCTURALLY absent from the serializable record
  (Managed-Effects §The reply map — the data-only invariant). Pure."
  [[map-key record]]
  (let [status (:status record)
        ;; The production `:rf.runtime/work-ledger` map is keyed on the opaque
        ;; CEDN-1 byte `work-id-id` STRING (resources/work-ledger §record-path);
        ;; the kind-preserving work-id VECTOR is carried on the record as
        ;; `:work/id`. Read the canonical vector from there — fall back to the
        ;; map key only for a legacy/nonconforming record that lacks the stamp —
        ;; so the displayed `:work-id`, the `:work-kind` inference, AND the
        ;; live-work trace join all key on the kind-preserving identity, NOT the
        ;; byte string. Mirror of resources-helpers/work-row.
        work-id (or (:work/id record) map-key)]
    {:work-id      work-id
     :work-kind    (or (:work/kind record) (:kind record) (infer-work-kind work-id))
     :status       status
     :live?        (live? status)
     :frame        (or (:work/frame record) (:frame-id record) (:rf.frame/id record))
     :owners       (vec (:owners record))
     :causes       (mapv rh/summarize (:causes record))
     :cancellable? (boolean (:cancellable? record))
     :started-at   (:started-at record)
     :deadline-at  (or (:deadline-at record) (:deadline record))
     :attempt      (or (:attempt record) (:generation record))
     :transport    (:transport record)
     :outcome      (when (contains? record :outcome) (rh/summarize (:outcome record)))}))

(defn project-ledger
  "Project a frame's work-ledger map `{<work-id> <record>}` into the uniform
  cross-family work/reply rows, live (non-terminal) work first then the
  terminal recent-races tail, each group newest-first by `:attempt`. Per
  Managed-Effects §Work-ledger integration. Pure."
  [ledger]
  (->> (or ledger {})
       (mapv ledger-row)
       (sort-by (juxt (complement :live?) (comp - (fnil identity 0) :attempt)))
       vec))

(defn latest-phase-by-work-id
  "Index the LATEST reply-envelope trace phase observed per `:work/id` across
  a trace buffer (the most-recent `work-event-row` for each work id, by
  `:time` then `:id`). Used to join a live ledger row to its current trace
  cause — \"is this running work issued / retrying / cancellation-requested?\"
  Returns `{<work-id> <work-event-row>}`. Pure."
  [trace-buffer]
  (->> (project-work-events trace-buffer)
       (filter :work-id)
       (sort-by (juxt (comp (fnil identity 0) :time) (comp (fnil identity 0) :id)))
       (reduce (fn [acc row] (assoc acc (:work-id row) row)) {})))

(defn live-work
  "\"What is still running?\" — the live (non-terminal) work-ledger rows
  joined to their latest reply-envelope trace phase + completion status,
  UNIFORM across HTTP / resources / mutations / routing / machines / timers
  (Managed-Effects §Work-ledger integration / Cross-Cutting F-C4). Each live
  ledger row gains the trace cause for its `:work/id`:

      {:work-id … :work-kind … :status :running :live? true
       :owners … :causes … :attempt … :frame …
       :latest-phase :issued | :retry | :cancel-requested   ; from the trace
       :latest-op    :rf.resource/work-abort-requested}      ; the emitting op

  Answers the operator's \"what is the app waiting on, and why\" across every
  family with ONE join. `ledger` is the frame's work-ledger map (read off the
  runtime-db at `ledger-key`); `trace-buffer` supplies the latest phase. Pure.

  The single-arity form (ledger only) returns the live rows without the trace
  join (the ledger row's own `:status` is the live fact; the trace phase is
  the enrichment)."
  ([ledger] (live-work ledger nil))
  ([ledger trace-buffer]
   (let [phase-by-id (when (seq trace-buffer) (latest-phase-by-work-id trace-buffer))]
     (->> (project-ledger ledger)
          (filterv :live?)
          (mapv (fn [row]
                  (if-let [ph (get phase-by-id (:work-id row))]
                    (assoc row :latest-phase (:phase ph)
                               :latest-op    (:operation ph))
                    row)))))))

(defn live-work-tally-by-kind
  "Count live (still-running) work per `:work-kind` across the ledger — the
  active managed-effects dashboard headline (Cross-Cutting F-C4). Returns
  `{:resource 2 :http 1 …}`. Pure."
  [ledger]
  (->> (live-work ledger)
       (group-by :work-kind)
       (reduce-kv (fn [m k rows] (assoc m k (count rows))) {})))
