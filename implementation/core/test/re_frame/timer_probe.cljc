(ns re-frame.timer-probe
  "TEST-ONLY managed-timer CONFORMANCE PROBE (rf2-zqefg3.6).

  ## Why this exists

  `spec/Managed-Effects.md` §How new managed-effect surfaces inherit the
  contract claims that ANY future surface — managed timers, IndexedDB
  transactions, background jobs — can adopt ALL nine managed-effect
  properties, and that property 9 (the uniform reply envelope) is a
  framework-wide law any new async writer inherits. That claim is
  UNPROVEN by the four shipped families, because each co-evolved WITH the
  contract; none is a fresh consumer that walked the settled substrate
  cold. An unproven plug-in claim either gets conformance-enforced with a
  test-only instance now, or becomes expensive archaeology later — the
  precedent the EP-0003 GraphQL ruling set when it mandated a test-only
  transport rather than blessing an unproven `:partial` claim.

  This namespace is that test-only instance: a MINIMAL managed-timer
  surface that adopts the nine properties by CONSUMING the shared
  `re-frame.reply` substrate (the same substrate HTTP / resources /
  mutations / machines / route-loaders lower onto). It is the FIRST FRESH
  consumer of the zqefg3.1 reply substrate — proof that a brand-new writer
  inherits the closed status taxonomy, the work-id correlation rule, the
  stale-suppression correctness boundary, and the reply-mapping functor
  law without re-spelling any of them.

  ## NOT a public surface

  Nothing here is a `re-frame.core` façade export, a reserved
  `:rf.timer/*` namespace, or a documented public API. The PUBLIC
  `:rf.timer/after` surface — and the `:rf.timer/*` Conventions
  reservation — are DEFERRED to rf2-5wuikc with named un-defer triggers;
  building either today would mint a second \"run an event later\" beside
  `:dispatch-later` and a THIRD debounce mechanism beside machine `:after`
  and HTTP request-id supersession, an EP-0007 hazard with no consumer to
  justify it. The probe proves the inheritance claim; it ships no surface.

  ## The nine properties, minimally

  1. fx-id — `probe-timer-fx` is the single (test-only) effect handler;
     its args map is the declarative timer spec (no caller code).
  2. closed args-map shape — `{:timer/id :after :rf/reply-to :retry ...}`,
     validated by `valid-args?`.
  3. failure taxonomy — timer failures carry `:kind :rf.timer/*`
     (`:rf.timer/elapsed-error`, `:rf.timer/cancelled`) under `:error` /
     `:rf.reply/cancel-reason`, the family `:kind` the reply-map contract requires.
  4. trace facts — `suppress` / completion produce data-only trace facts
     (`re-frame.reply/trace-summary`); the probe never invents a private
     trace shape.
  5. elision — every wire-bearing slot routes through the shared
     `re-frame.reply/trace-summary` → `re-frame.elision/elide-wire-value`
     walker (NOT a family-private elider).
  6. retry / abort / teardown AS DATA — the args map carries `:retry`
     (declarative backoff policy, data not code) and the abort is
     requested by `request-abort!` returning the abort outcome AS DATA;
     teardown (`teardown-frame!`) cancels in-flight timers and RECORDS the
     cancellation, never silently drops it. Property 6 is non-negotiable
     for a managed surface.
  7. in-flight registry — `registry` is the framework-private
     host-transient side-table keyed by `[frame-id work-id]`; the host
     handle (the timer's opaque cancel token) lives ONLY here, NEVER in a
     reply map or a target (the data-only invariant). `in-flight` is the
     registrar-style query.
  8. per-frame scoping — the registry is keyed BY frame-id, so two frames'
     in-flight timers are strictly disjoint; teardown is frame-scoped.
  9. uniform reply envelope — every completion (`complete-elapsed`,
     `complete-error`, the cancel / stale paths) produces a `re-frame.reply`
     reply map carrying one closed `:status`, `:work/id` correlation, and
     causal `:completed-at` (EP-0010 — supplied by the host completion, NOT
     a fresh ambient clock read), gated by an ordinary `:rf/reply-to`
     `:suppress {:generation N}` gate.

  Pure where it can be: the reply / suppression / work-id helpers are pure
  fns over the shared substrate. The in-flight registry is the one piece
  of host-transient state (a `defonce` atom), exactly as the machines
  `after-timers` table is — the registry holds the opaque host cancel
  token, never durable reply data."
  (:require [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Property 9 + work-id correlation (Managed-Effects §Work-id correlation).
;;
;; Timer head: `[:rf.work/timer logical-timer-id generation]` — the row
;; already acknowledged by the Managed-Effects work-kind table. One ATTEMPT
;; has one `:work/id` (EP-0007): a fresh schedule of the same logical timer
;; bumps `generation`, so a superseded attempt is discriminated by the
;; generation component AND gated by the `:generation` suppression key — the
;; same fact named once, never a second stale key.
;; ---------------------------------------------------------------------------

(def work-kind
  "The `:work/kind` a timer attempt carries (`:timer`). Per Managed-Effects
  §The reply map / §Work-id correlation."
  :timer)

(defn work-id
  "Build the timer work-id head for one attempt:
  `[:rf.work/timer logical-timer-id generation]` (Managed-Effects §Work-id
  correlation — the Timer row). `=`-comparable and EDN-serializable. Pure."
  [{:keys [timer/id generation]}]
  [:rf.work/timer id generation])

;; ---------------------------------------------------------------------------
;; Property 2 — the closed args-map shape (Managed-Effects §Closed args map).
;; The args map is the declarative timer SPEC the fx handler receives; it
;; carries the policy (delay, reply target, retry/abort/teardown as data),
;; never caller code.
;; ---------------------------------------------------------------------------

(defn valid-args?
  "True when `args` is a well-formed probe-timer spec:
    {:timer/id    <keyword>       ;; logical timer id (required)
     :after       <pos-int ms>    ;; the delay (required)
     :generation  <int>           ;; the supersession epoch (required)
     :rf/reply-to <reply target>  ;; the canonical reply continuation (required)
     :retry       <data map>      ;; OPTIONAL declarative retry-as-data
     :frame       <frame-id>}     ;; OPTIONAL frame stamp

  The reply target is pinned under the CANONICAL `:rf/reply-to` key — the
  single property-9 target spelling (Managed-Effects §The reply target). The
  probe is the core-surface conformance example a future managed async writer
  copies, so it carries no second reply-target spelling: a fresh consumer that
  copies the probe inherits `:rf/reply-to`, not an unqualified alias.

  Pure — a malformed spec is data the caller classifies, not a throw."
  [{:keys [timer/id after generation] :rf/keys [reply-to] :as args}]
  (boolean
    (and (map? args)
         (keyword? id)
         (integer? after) (pos? after)
         (integer? generation)
         (some? reply-to))))

;; ---------------------------------------------------------------------------
;; Property 6 — retry / abort / teardown AS DATA (Managed-Effects §Built-in
;; retry/abort/teardown). The retry policy is a declarative data map on the
;; args; the framework would execute it. This probe ships the policy
;; vocabulary as DATA — there is no caller callback.
;; ---------------------------------------------------------------------------

(defn retry-plan
  "Project the declarative `:retry` data on a timer spec into the per-attempt
  delay schedule it describes — a pure data→data transform, the retry policy
  executed as DATA not code. `{:max-attempts n :backoff {:base-ms b}}` yields
  the vector of attempt delays `[b 2b 3b ...]` (linear, the minimal policy a
  probe needs). No `:retry` ⇒ a single attempt at the base `:after`. Pure."
  [{:keys [after retry]}]
  (if-let [{:keys [max-attempts backoff]} retry]
    (let [base (or (:base-ms backoff) after)]
      (mapv #(* base (inc %)) (range (max 1 (or max-attempts 1)))))
    [after]))

;; ---------------------------------------------------------------------------
;; Property 7 + 8 — the framework-private, FRAME-SCOPED in-flight registry
;; (Managed-Effects §In-flight registry). Keyed by `[frame-id work-id]`. The
;; HOST HANDLE (the opaque cancel token a real timer scheduler would return)
;; lives ONLY here — never in a reply map or a reply target (the data-only
;; invariant). Two frames' timers are strictly disjoint (property 8).
;;
;; This is the one piece of host-transient state, exactly as the machines
;; `after-timers` table is. A `defonce` so a hot-reload of this test ns keeps
;; the table; tests reset it via `reset-registry!` in a fixture.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Framework-private host-transient in-flight registry:
  `{[frame-id work-id] {:handle <opaque cancel token> :started-at <ms>
                        :generation <int> :timer/id <kw> :status <kw>}}`.
  The host handle lives ONLY here — never in durable reply data. Not
  earmuffed (it is a runtime-owned side-table, not a dynamic var) — the
  same shape as the machines `after-timers` table."}
  registry
  (atom {}))

(defn reset-registry!
  "Clear the in-flight registry (test fixture isolation). Returns nil."
  []
  (reset! registry {})
  nil)

(defn register!
  "Record a freshly-scheduled timer in the frame-scoped in-flight registry
  and return its `:work/id`. `handle` is the opaque host cancel token (a
  gensym stand-in for a real `setTimeout` id) — it is stored HERE and never
  rides a reply. `started-at` is the causal host clock reading captured at
  schedule time (it later rides the reply as `:started-at`)."
  [frame-id {:keys [timer/id generation] :as args} handle started-at]
  (let [wid (work-id args)]
    (swap! registry assoc [frame-id wid]
           {:handle      handle
            :timer/id    id
            :generation  generation
            :started-at  started-at
            :status      :running})
    wid))

(defn in-flight
  "Registrar-style query: the in-flight timer work-ids for `frame-id` (or, in
  the 0-arity form, every frame's). Returns a vector of `:work/id`s. A tool
  would expose this via the public registrar API; the probe proves the
  registry is queryable by an addressable id (property 7)."
  ([] (mapv (fn [[[_ wid] _]] wid) @registry))
  ([frame-id]
   (->> @registry
        (filter (fn [[[fid _] _]] (= fid frame-id)))
        (mapv (fn [[[_ wid] _]] wid)))))

(defn- forget!
  "Drop a registry entry (the host handle is released by the caller before
  this runs). Returns the removed entry or nil."
  [frame-id wid]
  (let [entry (get @registry [frame-id wid])]
    (swap! registry dissoc [frame-id wid])
    entry))

;; ---------------------------------------------------------------------------
;; Property 9 — completion through the uniform reply envelope (Managed-Effects
;; §The reply map / §Status taxonomy). Every completion path produces a
;; `re-frame.reply` reply map carrying ONE closed `:status`, the timer
;; `:work/id`, and CAUSAL completion metadata. `:completed-at` is supplied by
;; the HOST COMPLETION (EP-0010) — this ns NEVER reads an ambient clock.
;; ---------------------------------------------------------------------------

(defn- base-reply
  "The correlation / identity facts every timer reply carries: `:rf.reply/work-id`,
  `:rf.reply/work-kind`, the carried frame stamp, and the causal timestamps. Optional
  facts are omitted when absent (Managed-Effects §The reply map). `started-at`
  / `completed-at` are causal epoch-ms readings the host completion supplies —
  never fresh clock reads."
  [{:keys [frame] :as args} {:keys [started-at completed-at]}]
  (cond-> {:rf.reply/work-id   (work-id args)
           :rf.reply/work-kind work-kind}
    (some? frame)        (assoc :rf.frame/id frame)
    (some? started-at)   (assoc :started-at started-at)
    (some? completed-at) (assoc :completed-at completed-at)))

(defn complete-elapsed
  "Build the canonical `:status :ok` reply for a timer that elapsed
  successfully. `value` is the elapsed payload (the reply result is `:value`
  EVERYWHERE — EP-0007). `:rf.reply/work-status :completed`. `times` supplies the
  causal `:started-at` / `:completed-at` (EP-0010 — the host completion's
  readings, not ambient). Per Managed-Effects §Status taxonomy."
  [args value times]
  (assoc (base-reply args times)
         :status      :ok
         :rf.reply/work-status :completed
         :value       value))

(defn complete-error
  "Build the canonical `:status :error` reply for a timer whose elapsed
  handler threw / could not deliver. `error` is a `:rf.timer/*` failure
  envelope carrying the family `:kind` the reply-map contract requires
  (property 3 + §Status taxonomy)."
  [args error times]
  (assoc (base-reply args times)
         :status      :error
         :rf.reply/work-status :failed
         :error       (cond-> error
                        (not (:kind error)) (assoc :kind :rf.timer/elapsed-error))))

(defn complete-cancelled
  "Build the canonical `:status :cancelled` reply for an EXPLICIT user
  cancellation that is still correlated with the target (Managed-Effects
  §Cancellation — cancellation is DATA, not the absence of a reply).
  `:rf.reply/cancel-reason` defaults to `:rf.timer/cancelled`."
  ([args times] (complete-cancelled args times :rf.timer/cancelled))
  ([args times reason]
   (assoc (base-reply args times)
          :status        :cancelled
          :rf.reply/work-status   :cancelled
          :cancelled?    true
          :rf.reply/cancel-reason reason)))

;; ---------------------------------------------------------------------------
;; Property 9 — stale suppression, THE correctness boundary (Managed-Effects
;; §Stale suppression). The timer's supersession epoch is the `:generation`;
;; it lowers to the ONE data-only `:suppress {:generation N}` gate. The
;; carried gate is captured at schedule time; the current gate is read from
;; the live logical-timer's generation at completion. Comparison delegates to
;; the shared `re-frame.reply/stale?` — the probe does NOT re-implement it.
;; ---------------------------------------------------------------------------

(def stale-reason
  "The `:rf.reply/stale-reason` a superseded timer completion carries: a fresh
  schedule advanced the generation. Named once (EP-0007)."
  :rf.timer/generation-stale)

(defn gate
  "The data-only suppression gate for a captured generation:
  `{:generation <n>}`. This is the ONE gate the timer family validates
  (Managed-Effects §Stale suppression — the same `:suppress {:generation N}`
  shape the EP-0011 §The reply target descriptor example uses). Pure."
  [generation]
  {:generation generation})

(defn suppress?
  "True when a completion carrying `carried-gen` is stale relative to the
  live `current-gen` (a fresh schedule advanced the timer's generation), so
  its app reply MUST be suppressed. Delegates to the shared
  `re-frame.reply/stale?` over the `:generation` gate — the probe does NOT
  re-implement the comparison."
  [carried-gen current-gen]
  (reply/stale? (gate carried-gen) (gate current-gen)))

(defn suppress
  "Produce the stale-suppression outcome for a timer completion whose carried
  generation has been superseded — WITHOUT dispatching the app reply target.
  Delegates to the shared `re-frame.reply/suppress` (the correctness boundary
  made concrete): `:reply` is `:status :stale` (no `:value`, no app mutation)
  and `:deliver?` is false. The returned `:trace` carries the carried +
  current gates joined to `:work/id`.

    `args`        — the timer spec (supplies `:work/id` / `:work/kind` /
                    `:rf.frame/id` and the CARRIED generation).
    `current-gen` — the LIVE generation read at completion.
    `times`       — `{:completed-at <causal ms>}` ridden onto the stale reply.
    `target`      — the (optional) reply target, consulted only for its
                    `:dispatch-stale?` opt-in (framework test/tool only)."
  ([args current-gen times] (suppress args current-gen times nil))
  ([{:keys [generation frame] :as args} current-gen {:keys [completed-at]} target]
   (reply/suppress target
                   (gate generation)
                   (gate current-gen)
                   (cond-> {:status       :stale
                            :rf.reply/work-id      (work-id args)
                            :rf.reply/work-kind    work-kind
                            :rf.reply/stale-reason stale-reason}
                     (some? frame)        (assoc :rf.frame/id frame)
                     (some? completed-at) (assoc :completed-at completed-at)))))

;; ---------------------------------------------------------------------------
;; Property 6 — abort REQUEST as data (Managed-Effects §Built-in abort). An
;; abort is requested through the registry: the host handle is released and
;; the abort is RECORDED, returning the cancellation outcome AS DATA (the
;; cancelled reply + the released handle). The app never sees a callback — it
;; sees a `:status :cancelled` reply map.
;; ---------------------------------------------------------------------------

(defn request-abort!
  "Request an explicit abort of an in-flight timer. Releases the host handle
  from the in-flight registry (property 7), and returns the cancellation
  outcome AS DATA (property 6):

    {:aborted?     <bool>            ;; false when no live entry (already gone)
     :released     <opaque handle>   ;; the host cancel token, released here
     :reply        <:status :cancelled reply>}   ;; nil when nothing to abort

  `times` supplies the causal `:completed-at`. The host handle is released
  here and NEVER rides the reply (the data-only invariant)."
  [frame-id {:keys [reason] :as args} times]
  (let [wid   (work-id args)
        entry (forget! frame-id wid)]
    (if entry
      {:aborted? true
       :released (:handle entry)
       :reply    (complete-cancelled
                   (assoc args :started-at (:started-at entry))
                   (assoc times :started-at (:started-at entry))
                   (or reason :rf.timer/cancelled))}
      {:aborted? false :released nil :reply nil})))

;; ---------------------------------------------------------------------------
;; Property 6 + 8 — frame teardown cancels in-flight timers and RECORDS the
;; cancellation (Managed-Effects §In-flight registry: "the runtime emits a
;; structured warning if a frame is destroyed while interactions are still
;; in-flight"). Frame-scoped (property 8): only the destroyed frame's timers
;; are released; sibling frames are untouched. Every cancellation is RECORDED
;; as a data fact, never silently dropped.
;; ---------------------------------------------------------------------------

(defn teardown-frame!
  "Cancel every in-flight timer owned by `frame-id` on frame destroy,
  releasing each host handle and RECORDING the cancellation. Returns a vector
  of cancellation records (data), one per cancelled timer:

    [{:work/id <wid> :released <handle> :reason :rf.timer/frame-destroyed} ...]

  Frame-scoped — sibling frames' registry entries are untouched (property 8).
  `times` supplies the causal `:completed-at` for any reply the caller builds
  from a record; the records themselves are pure data the trace stream emits."
  [frame-id]
  (let [entries (filter (fn [[[fid _] _]] (= fid frame-id)) @registry)
        records (mapv (fn [[[_ wid] entry]]
                        {:work/id  wid
                         :released (:handle entry)
                         :reason   :rf.timer/frame-destroyed})
                      entries)]
    (doseq [[[_ wid] _] entries]
      (forget! frame-id wid))
    records))

;; ---------------------------------------------------------------------------
;; Property 4 + 5 — trace summary. A data-only summary of a timer reply for a
;; managed-async trace row, routing every wire-bearing slot through the shared
;; `re-frame.reply/trace-summary` → `re-frame.elision/elide-wire-value` walker.
;; Never a family-private elider (Managed-Effects §Tracing).
;; ---------------------------------------------------------------------------

(defn trace-reply
  "Build a DATA-ONLY trace summary of a timer reply for a managed-async trace
  row, delegating to the shared `re-frame.reply/trace-summary`. Identity facts
  ride verbatim; wire slots elide through the one shared walker. `opts` is
  forwarded (e.g. `:frame`). Never a family-private elider."
  ([reply] (trace-reply reply nil))
  ([reply opts] (reply/trace-summary reply opts)))

;; ---------------------------------------------------------------------------
;; Property 1 + 9 — the (test-only) fx handler. A real managed-timer fx would
;; schedule a host timer, register it, and on elapse complete through the
;; reply envelope (suppressing if stale). The probe's handler is the same
;; shape minus a real clock: it validates the spec, registers the in-flight
;; timer (capturing the host handle + causal start), and returns a data
;; receipt the test drives the completion from. NOT exported — this is a
;; test-support fn, not a `re-frame.core` façade export.
;; ---------------------------------------------------------------------------

(defn probe-timer-fx
  "The test-only managed-timer fx handler. `ctx` carries the dispatching
  frame (`:frame`); `args` is the declarative timer spec (property 2).
  Validates the spec, schedules a timer (the `schedule!` fn returns the
  opaque host handle + the causal `:started-at`), and registers it in the
  frame-scoped in-flight registry (property 7/8). Returns the data receipt
  `{:work/id <wid> :handle <opaque> :started-at <ms>}` (the host handle stays
  in the registry; the receipt is a test convenience). Throws on a malformed
  spec — an fx-arg contract violation, not data the app classifies."
  [{:keys [frame]} args schedule!]
  (when-not (valid-args? args)
    (throw (ex-info "Invalid probe-timer args."
                    {:rf.error/kind :rf.timer/invalid-args :args args})))
  (let [{:keys [handle started-at]} (schedule! args)
        wid (register! frame args handle started-at)]
    {:work/id wid :handle handle :started-at started-at}))
