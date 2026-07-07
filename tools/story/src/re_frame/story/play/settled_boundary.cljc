(ns re-frame.story.play.settled-boundary
  "The `settled-boundary` contract — the single name for what
  `[:dispatch event-vector]` waits for before the runner advances to the
  next script step (spec/017-Testing-Story.md
  §Script and `settled-boundary` + §Shared primitive lock).

  ## What a settled boundary is

  `settled-boundary` is NOT a new headless scheduler and NOT a second
  quiescence engine. It is a *name* for the settlement contract layered
  over the framework primitives that already exist:

  - `:headless` — the variant frame's event queue is drained AND all
    synchronous re-dispatches have settled. This is the existing
    `re-frame.router/dispatch-sync!` run-to-fixed-point drain, projected
    under a name — not reimplemented.
  - `:cljs-reactive` — the headless boundary AND reaction recomputation
    has flushed.
  - `:dom` — the above AND the adapter's `act()` / microtask flush has
    completed, within a declared maximum.
  - `:browser` — the above AND real browser layout / paint has settled.

  These are an **ordered ladder**: a richer boundary subsumes every
  cheaper one. `boundary-levels` is the canonical order; `boundary>=`
  compares two boundaries on it.

  ## The flush-hook seam

  The runner MUST NOT hard-code `dispatch-sync`. It takes a *flush-hooks*
  map from the adapter-aware caller:

      {:dispatch! (fn [frame-id event-vector] ...)  ; enqueue / fire the event
       :provides  :headless | :cljs-reactive | :dom | :browser
       :flush!    {:headless      (fn [frame-id] ...)   ; drain to fixed point
                   :cljs-reactive (fn [frame-id] ...)   ; + reaction flush
                   :dom           (fn [frame-id] ...)}  ; + act()/microtask
       :timeout-ms optional-number}                     ; richer-boundary bound

  `:timeout-ms` is a wall-clock budget on the flush phase. `dispatch-and-settle!`
  ENFORCES it: it stamps a deadline (`re-frame.interop/now-ms` +
  `:timeout-ms`) before the flush loop and, after EACH flush fn returns,
  checks whether the deadline has passed. An over-budget flush phase stops
  the ladder and returns `flush-timeout-result` (a fail-closed `:cannot-run`
  refusal, reason `:flush-timeout`) — NEVER a silent settled pass. Because
  the flush fns run synchronously, this bounds a *sequence* of slow flushes
  and detects an over-budget flush once it returns; it does NOT preempt a
  single flush fn that hangs forever (that needs the caller's own
  thread/timeout box — out of scope for this host-free contract ns). With
  no `:timeout-ms` the flush phase is unbounded (the headless default, whose
  only flush is the synchronous `dispatch-sync!` drain, cannot time out).

  `headless-flush-hooks` is the default the JVM / node-runtime headless
  runner uses: `:provides :headless`, `:dispatch!` and the `:headless`
  flush both routed through `re-frame.router/dispatch-sync!` so a queued
  `[:dispatch …]` step settles to fixed point synchronously, named and
  deterministic.

  Adapter-aware callers (the Reagent/UIx/Helix shell, a future `:dom`
  browser runner) register richer hooks declaring a higher `:provides`
  and supplying the reactive / DOM flush fns. Story core never reaches
  for React or the DOM directly; the flush fns are the only seam.

  ## `:cannot-run`

  A step declares the boundary it needs (`step-required-boundary`). When
  the active runner's `:provides` does not reach that boundary — e.g. a
  `:dom`-requiring `[:click …]` under a `:headless` runner — the boundary
  REFUSES with a `:cannot-run` refusal map (`cannot-run-refusal`) rather
  than under-flushing and passing falsely. A flush that times out reports
  `:cannot-run` or `:error` per the caller's policy; it MUST NEVER report
  a silent pass.

  This namespace is `.cljc` and stays free of `re-frame` view / DOM
  requires beyond the framework `re-frame.router/dispatch-sync!` fn, so the
  contract + the headless boundary are JVM-runnable and unit-testable. The richer
  flush fns are injected by adapter callers; this ns only *names* the
  ladder, *routes* a dispatch through the supplied hooks, and *refuses*
  when the supplied boundary is too weak."
  (:require [re-frame.router :as router]
            [re-frame.interop :as interop]))

;; ---- the boundary ladder -------------------------------------------------

(def boundary-levels
  "The settlement ladder, cheapest → richest. Each level subsumes every
  level before it. `[:dispatch …]` declares the MINIMUM boundary it needs
  (`:headless`); richer steps (`:click`, `:type`, DOM assertions) declare
  a richer minimum. A runner is valid for a step iff the boundary it
  PROVIDES is `>=` the step's required boundary on this ladder."
  [:headless :cljs-reactive :dom :browser])

(def ^:private boundary-rank
  "boundary keyword → its 0-based index on `boundary-levels`."
  (into {} (map-indexed (fn [i b] [b i]) boundary-levels)))

(defn boundary?
  "True iff `b` is a recognised boundary keyword."
  [b]
  (contains? boundary-rank b))

(defn boundary>=
  "True iff boundary `a` is at least as rich as boundary `b` on the
  ladder. Unknown boundaries compare false (fail-closed)."
  [a b]
  (boolean
    (and (boundary? a) (boundary? b)
         (>= (boundary-rank a) (boundary-rank b)))))

(defn max-boundary
  "Return the richer of two boundaries (the one further along the
  ladder). Unknown boundaries are treated as the weakest. With no
  recognised boundary, returns `:headless`."
  ([] :headless)
  ([b] (if (boundary? b) b :headless))
  ([a b]
   (cond
     (and (boundary? a) (boundary? b)) (if (boundary>= a b) a b)
     (boundary? a) a
     (boundary? b) b
     :else :headless)))

;; ---- step → required boundary --------------------------------------------

(def default-step-boundaries
  "The minimum settled-boundary each script step type requires, per
  spec/017 §Script step grammar. `[:dispatch …]` needs only `:headless`
  (drain to fixed point); DOM-touching steps need `:dom`. `:wait-until` /
  `:wait` are runner-dependent (the predicate / the wall-clock dictates),
  so they declare the cheapest boundary and the caller's predicate decides
  the rest. `:dispatch-sync` is the explicit low-level escape — it IS the
  headless boundary."
  {:dispatch       :headless
   :dispatch-sync  :headless
   :wait           :headless
   :wait-until     :headless
   :assert         :headless   ; in-script checkpoint — the wrapped atom may need more (e.g. DOM); its capability tokens, not its boundary, gate that
   :assert-db      :headless
   :assert-dom     :dom
   :click          :dom
   :type           :dom
   :focus          :dom})

(defn step-required-boundary
  "The minimum settled-boundary `step` needs, from `default-step-boundaries`
  keyed by the step's tag. Unknown / untagged steps default to `:headless`
  (a bare dispatch). Pure data → data."
  [step]
  (let [tag (when (and (vector? step) (pos? (count step)))
              (let [h (first step)] (when (keyword? h) h)))]
    (get default-step-boundaries tag :headless)))

;; ---- :cannot-run refusal -------------------------------------------------

(defn cannot-run-refusal
  "Build the `:cannot-run` refusal map for a step whose required boundary
  the active runner cannot satisfy. Shape mirrors spec/017 §`:cannot-run`
  (a distinct third status, never a silent pass):

      {:status           :cannot-run
       :required-boundary <boundary>
       :provided-boundary <boundary>
       :reason            <keyword>
       :step              <step or nil>}

  `:reason` defaults to `:runner-below-required-boundary`; a flush timeout
  caller passes `:flush-timeout`."
  ([required provided step]
   (cannot-run-refusal required provided step :runner-below-required-boundary))
  ([required provided step reason]
   (cond-> {:status            :cannot-run
            :required-boundary  required
            :provided-boundary  provided
            :reason             reason}
     (some? step) (assoc :step step))))

(defn satisfies-boundary?
  "True iff a runner that PROVIDES `provided` can settle a step that
  REQUIRES `required`. Pure ladder comparison; fail-closed on unknowns."
  [provided required]
  (boundary>= provided required))

;; ---- the headless flush hooks (default) ----------------------------------

(defn drain-sync!
  "The headless `settled-boundary`: dispatch `event-vector` into
  `frame-id` and drain the router to fixed point — synchronous
  re-dispatches included. This is `re-frame.router/dispatch-sync!` projected
  under the boundary name; it is the SAME run-to-fixed-point drain the
  framework already owns (Spec 002 §dispatch-sync), not a new scheduler.

  Returns nil. Raises nothing the framework would not already raise (a
  `dispatch-sync` issued from inside a running drain surfaces
  `:rf.error/dispatch-sync-in-handler` through the trace bus, as usual).

  The optional 3-arity `(drain-sync! frame-id event-vector opts)` merges
  caller-supplied dispatch opts (e.g. a captured `{:rf.cofx <map>}`
  envelope) over `{:frame frame-id}` so a replayed `[:dispatch evec
  {:rf.cofx …}]` step re-presents its recorded recordable coeffects. The
  2-arity is the bare path with no captured opts."
  ([frame-id event-vector]
   (drain-sync! frame-id event-vector nil))
  ([frame-id event-vector opts]
   (router/dispatch-sync! event-vector (merge {:frame frame-id} opts))
   nil))

(def headless-flush-hooks
  "Default flush-hooks map for the headless runner (JVM + node-runtime).
  `:provides :headless`; both `:dispatch!` and the `:headless` flush route
  through the framework `dispatch-sync!` drain (`drain-sync!`), so a
  `[:dispatch …]` step settles to fixed point synchronously and
  deterministically.

  Adapter callers that can flush reactions / DOM register their own hooks
  declaring a higher `:provides` and supplying `:flush!` fns for the
  richer levels. The runner consumes ONLY this map — it never reaches for
  `dispatch-sync` directly (spec/017 §Script and `settled-boundary`: the
  runner takes a flush-fn from the adapter-aware caller)."
  {:provides  :headless
   :dispatch! drain-sync!
   :flush!    {:headless (fn headless-flush [_frame-id] nil)}})

(defn hooks-provided-boundary
  "The boundary a flush-hooks map PROVIDES. Reads `:provides`; defaults to
  `:headless` when absent (a hooks map with no declared ceiling is assumed
  headless-only — fail-closed for richer steps)."
  [hooks]
  (let [p (:provides hooks)]
    (if (boundary? p) p :headless)))

;; ---- timeout helper ------------------------------------------------------

(defn flush-timeout-result
  "Build the result for a richer-boundary flush that exceeded its declared
  maximum (the hooks' `:timeout-ms`). Per spec/017 a flush timeout reports
  `:cannot-run` or `:error` per the caller's policy — NEVER a silent pass.
  `policy` is `:cannot-run` (default) or `:error`. `dispatch-and-settle!`
  calls this with the default `:cannot-run` policy when the flush phase
  exceeds `:timeout-ms`."
  ([required provided step] (flush-timeout-result required provided step :cannot-run))
  ([required provided step policy]
   (case policy
     :error {:status :error
             :error  (str "settled-boundary flush for " required
                          " exceeded its declared maximum")
             :step   step}
     ;; default :cannot-run
     (cannot-run-refusal required provided step :flush-timeout))))

;; ---- the dispatch-and-settle entry point ---------------------------------

(defn dispatch-and-settle!
  "Dispatch `event-vector` into `frame-id` and settle to the boundary the
  step requires, using the caller-supplied `hooks`. This is what
  `[:dispatch event-vector]` MEANS (spec/017 §Script and
  `settled-boundary`): dispatch, then wait for `settled-boundary`.

  `hooks` is a flush-hooks map (see `headless-flush-hooks`). `required`
  is the boundary the step needs (default `:headless`); when omitted, a
  bare dispatch settles to the headless drain.

  Returns one of:

  - `{:status :settled :boundary <required>}` — the dispatch fired and
    the runner flushed up to (and including) the required boundary.
  - a `cannot-run-refusal` map — the runner's `:provides` does not reach
    `required`; the event is NOT dispatched (fail-closed, no partial /
    under-flushed pass). The same `:cannot-run` shape (reason
    `:flush-timeout`, via `flush-timeout-result`) is returned when the
    flush phase exceeds the hooks' `:timeout-ms` budget.
  - `{:status :error :error <message> :step …}` — a flush fn threw or the
    `:dispatch!` hook threw. NEVER a silent pass.

  Settlement runs every flush registered in `(:flush! hooks)` whose level
  is `<= required`, in ladder order — so a `:dom` step under a runner that
  provides `:dom` runs the `:headless`, `:cljs-reactive`, and `:dom`
  flushes in turn. A missing flush fn for a level the runner claims to
  provide is treated as a no-op for that level (the cheaper level still
  drained the queue).

  When `hooks` declares a `:timeout-ms`, the flush phase is bounded: a
  deadline is stamped before the loop and re-checked after each flush fn
  returns. An over-budget flush phase stops and returns
  `flush-timeout-result` (`:cannot-run`, reason `:flush-timeout`) — the
  event has already been dispatched, but the settle is refused rather than
  falsely reported settled. The synchronous-flush caveat (a single hanging
  flush fn is not preempted) is documented on the ns docstring's flush-hook
  seam."
  ([frame-id event-vector hooks]
   (dispatch-and-settle! frame-id event-vector hooks :headless nil))
  ([frame-id event-vector hooks required]
   (dispatch-and-settle! frame-id event-vector hooks required nil))
  ([frame-id event-vector hooks required step]
   (dispatch-and-settle! frame-id event-vector hooks required step nil))
  ;; The 6-arity threads `dispatch-opts` (e.g. a captured `{:rf.cofx <map>}`
  ;; envelope) into the dispatch! hook so a replayed step re-presents its
  ;; recorded recordable coeffects.
  ([frame-id event-vector hooks required step dispatch-opts]
   (let [required (if (boundary? required) required :headless)
         provided (hooks-provided-boundary hooks)]
     (if-not (satisfies-boundary? provided required)
       ;; Fail-closed: do not dispatch, do not under-flush. A step that
       ;; needs a richer boundary than the runner provides is refused.
       (cannot-run-refusal required provided step)
       (try
         (let [dispatch!  (or (:dispatch! hooks) drain-sync!)
               flushes    (:flush! hooks)
               timeout-ms (:timeout-ms hooks)
               deadline   (when (number? timeout-ms)
                            (+ (interop/now-ms) timeout-ms))
               ;; The flush levels to run: every registered level up to and
               ;; including `required`, in ladder order. The headless flush
               ;; is folded into `:dispatch!` (`drain-sync!`), so its hook is
               ;; a no-op; the richer flushes carry the adapter's reactive /
               ;; DOM work.
               levels     (take-while #(boundary>= required %) boundary-levels)
               ;; A replayed `[:dispatch evec {:rf.cofx …}]` step carries a
               ;; captured recordable-coeffect envelope. The caller
               ;; (`exec-dispatch!`) extracts it off the step and passes it as
               ;; `dispatch-opts`; thread it into the dispatch opts (via the
               ;; hook's optional 3-arity) so the handler's declared recordable
               ;; coeffects replay from the recorded value rather than being
               ;; restamped. Absent cofx uses the bare 2-arity call.
               ]
           (if (and (map? dispatch-opts) (seq dispatch-opts))
             (dispatch! frame-id event-vector dispatch-opts)
             (dispatch! frame-id event-vector))
           ;; Run each flush, re-checking the `:timeout-ms` deadline after
           ;; each returns. An over-budget flush phase stops the ladder and
           ;; refuses with a fail-closed `:flush-timeout` (`flush-timeout-result`)
           ;; rather than reporting a settled pass it did not earn.
           (loop [[level & more] levels]
             (cond
               ;; The deadline is re-checked BEFORE this branch returns so it
               ;; also covers the TERMINAL flush: the last (richest) flush can
               ;; run within budget on entry yet blow the wall-clock budget as
               ;; it returns. Checking here — not only at the top of the next
               ;; iteration — means an over-budget terminal flush refuses with
               ;; a fail-closed `:flush-timeout` instead of a settled pass it
               ;; did not earn.
               (and deadline (> (interop/now-ms) deadline))
               (flush-timeout-result required provided step)

               (nil? level)
               {:status :settled :boundary required}

               :else
               (do (when-let [f (get flushes level)]
                     (f frame-id))
                   (recur more)))))
         (catch #?(:clj Throwable :cljs :default) e
           {:status :error
            :error  #?(:clj (.getMessage ^Throwable e) :cljs (str e))
            :step   step}))))))
