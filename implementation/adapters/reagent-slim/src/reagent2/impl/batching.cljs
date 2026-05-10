(ns reagent2.impl.batching
  "Render scheduler for the day8/reagent-slim artefact (rf2-6hyy Stage 4-B).

  Per IMPL-SPEC §2.9 + §4: a microtask-based scheduler that composes
  cleanly with React 19's concurrent rendering. Replaces stock
  Reagent's three-phase `requestAnimationFrame` queue (which doesn't
  cooperate with React 18+ concurrent rendering — see Stage 1 §1.12 +
  rewrite-analysis.md §2.4).

  The contract per IMPL-SPEC §4.6:

    After (flush!) returns:
      - All currently-dirty components have re-rendered.
      - All Reactions whose dependencies changed have recomputed.
      - All :after-render callbacks queued before flush! fired.

  The user-facing `(reagent2.dom.client/flush-views!)` test primitive
  composes this synchronous drain with `react/act` (per §4.2). The
  microtask path is the **scheduling** mechanism; `flush!` is the
  **synchronous-drain** mechanism. Both share the same queues.

  Public surface (consumed by other ns'es in the artefact):

    queue-render!   ;; component → enqueue + schedule microtask
    do-after-render ;; fn → run after the next render
    do-before-flush ;; fn → run before each flush turn
    schedule        ;; force a microtask schedule (no-op if already scheduled)
    flush!          ;; synchronous drain (test primitive's worker)
    mark-rendered   ;; clear a component's dirty flag (called by render)

  Stage 4-A wired `reagent2.ratom/rea-schedule` as a `clojure.core/atom`
  hook. This ns installs a scheduler fn into that atom at load time so
  a Reaction whose dependency changes triggers a microtask drain
  automatically — closing the loop opened in 4-A's docstring."
  (:require [reagent2.ratom :as ratom]))

;; ---------------------------------------------------------------------------
;; Microtask primitive
;;
;; queueMicrotask is universal in any environment supporting React 19.
;; Promise.resolve().then is the strict fallback. Per IMPL-SPEC §4.1
;; no requestAnimationFrame fallback is required.
;; ---------------------------------------------------------------------------

(defn- schedule-microtask [f]
  (if (exists? js/queueMicrotask)
    (js/queueMicrotask f)
    (.then (js/Promise.resolve) f)))

;; ---------------------------------------------------------------------------
;; Dirty flag on component instances
;;
;; Per IMPL-SPEC §4.5: dedup is keyed on component identity. We mark
;; each component with a `cljsIsDirty` field on first enqueue and
;; skip the enqueue if it's already set. mark-rendered clears the
;; flag after the React `forceUpdate` (or commit) has run.
;;
;; The same field name `cljsIsDirty` is used by stock Reagent so any
;; introspection / 10x v2 hooks observing it keep working.
;; ---------------------------------------------------------------------------

(defn- dirty? [^js c]
  (true? (.-cljsIsDirty c)))

(defn- mark-dirty! [^js c]
  (set! (.-cljsIsDirty c) true))

(defn mark-rendered
  "Clear `c`'s dirty flag. Called by the component's render path after
  the React commit so the next dependency change re-enqueues."
  [^js c]
  (set! (.-cljsIsDirty c) false))

;; ---------------------------------------------------------------------------
;; The render queue
;;
;; A single `RenderQueue` instance holds three sub-queues:
;;
;;   - components       — JS array of component instances to forceUpdate
;;   - before-flush     — fns run at the start of each flush turn
;;   - after-render     — fns run at the end of each flush turn
;;
;; Per IMPL-SPEC §4.4 components flow through three pathways:
;;
;;   1. Reactive recompute → notify-watches → queue-render!
;;   2. Form-2 closure deref → watch fires → queue-render!
;;   3. Form-3 :component-did-mount → force-update → queue-render!
;;
;; Per IMPL-SPEC §4.5 a component re-queued *during* a drain is held
;; for the next turn — the scheduler does not flatten cascades into
;; the current drain. This matches React 19's transition semantics.
;; ---------------------------------------------------------------------------

(deftype RenderQueue [^:mutable ^boolean scheduled?
                      ^:mutable component-queue
                      ^:mutable before-flush-queue
                      ^:mutable after-render-queue]
  Object
  (schedule [this]
    (when-not scheduled?
      (set! scheduled? true)
      (schedule-microtask #(.run-queues this))))

  (queue-render [this c]
    (when (nil? component-queue)
      (set! component-queue #js []))
    (.push component-queue c)
    (.schedule this))

  (add-before-flush [this f]
    (when (nil? before-flush-queue)
      (set! before-flush-queue #js []))
    (.push before-flush-queue f)
    (.schedule this))

  (add-after-render [this f]
    (when (nil? after-render-queue)
      (set! after-render-queue #js []))
    (.push after-render-queue f))

  (run-queues [this]
    (set! scheduled? false)
    (.flush-queues this))

  (flush-before-flush [_this]
    (when-some [fs before-flush-queue]
      (set! before-flush-queue nil)
      (dotimes [i (alength fs)]
        ((aget fs i)))))

  (flush-render [_this]
    (when-some [fs component-queue]
      (set! component-queue nil)
      (dotimes [i (alength fs)]
        (let [^js c (aget fs i)]
          (when (dirty? c)
            (mark-rendered c)
            (when-some [fu (.-forceUpdate c)]
              ;; .call so `this` binds to the component (React requirement).
              (.call fu c)))))))

  (flush-after-render [_this]
    (when-some [fs after-render-queue]
      (set! after-render-queue nil)
      (dotimes [i (alength fs)]
        ((aget fs i)))))

  (flush-queues [this]
    (.flush-before-flush this)
    ;; Drain the reactive queue first — Reaction recomputes may enqueue
    ;; further component renders into component-queue, which we then
    ;; pick up in flush-render below.
    (ratom/flush!)
    (.flush-render this)
    (.flush-after-render this)))

(defonce ^:private render-queue
  (->RenderQueue false nil nil nil))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn queue-render!
  "Enqueue `c` for re-render at the next microtask turn. No-op if `c`
  is already dirty (dedup per IMPL-SPEC §4.5). Schedules a microtask
  if one isn't already pending."
  [^js c]
  (when-not (dirty? c)
    (mark-dirty! c)
    (.queue-render render-queue c)))

(defn schedule
  "Force a microtask schedule. No-op if one is already pending. Called
  from `reagent2.ratom/rea-schedule` when the rea-queue gets its
  first entry — see ns docstring."
  []
  (.schedule render-queue))

(defn do-before-flush
  "Register `f` to run at the start of the next flush turn. Used by
  internal lifecycle plumbing; users prefer `do-after-render`."
  [f]
  (.add-before-flush render-queue f))

(defn do-after-render
  "Register `f` to run at the end of the next flush turn — i.e. after
  every dirty component has re-rendered. The public ABI for
  `reagent2.core/after-render`.

  Schedules a microtask drain so `f` fires even if no component is
  dirty (matches stock Reagent semantics)."
  [f]
  (.add-after-render render-queue f)
  (.schedule render-queue))

(defn flush!
  "Synchronously drain the queues. The implementation hook for
  `reagent2.dom.client/flush-views!` — wraps the same drain that the
  microtask body runs.

  Per IMPL-SPEC §4.6: after this returns, every dirty component has
  re-rendered, every queued Reaction has recomputed, and every
  :after-render callback queued before this call has fired."
  []
  ;; Mark scheduled? false so any microtask still in flight is a no-op
  ;; against a now-empty queue when it eventually runs.
  (set! (.-scheduled? render-queue) false)
  (.flush-queues render-queue))

;; ---------------------------------------------------------------------------
;; Wire reagent2.ratom/rea-schedule
;;
;; Stage 4-A left rea-schedule as a clojure.core/atom containing nil.
;; A Reaction whose dependency changes calls `(when-let [s @rea-schedule] (s))`
;; on the first enqueue (rea-queue empty → fresh array). Stage 4-B's
;; job is to install a scheduler fn into that atom so a Reaction-side
;; dep change triggers a microtask drain on the render side too.
;;
;; The wiring runs at namespace-load time — requiring this ns is the
;; signal the consumer wants the render-side scheduler.
;; ---------------------------------------------------------------------------

(defonce ^:private installed?
  (do
    (reset! ratom/rea-schedule schedule)
    true))
