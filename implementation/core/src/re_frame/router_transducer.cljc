(ns re-frame.router-transducer
  "Phase-1 reference scaffold for the v1.1 transducer-shaped router design
  (rf2-cl8me). Non-normative: this namespace is NOT wired into the live
  runtime. It exists so the design in spec/Design-TransducerRouter.md is
  exercisable from the REPL and pinned by a CLJS/CLJ unit-test surface.

  The design intent:
   - `frame-transducer-factory` returns a transducer that maps dispatch
     envelopes to step-results. The xforms own per-event work (resolve
     handler, run pipeline, compute new state); they do not commit and do
     not schedule.
   - Three reducing-function presets (`sync-rf`, `queued-rf`, `batch-rf`)
     decide how successive states are accumulated and when they are
     committed. The reducing function is what differentiates v1's drain
     loop from a synchronous transduce from a batched cascade-settle
     commit.
   - The scaffold ships a `manual-driver` only — enough to exercise the
     reducing functions deterministically in tests. Real drivers
     (microtask / raf / virtual-time) are Phase 2.

  Substrate-agnostic boundary: nothing in this namespace touches the
  rendering substrate or the live frame registry. The 'frame' value
  passed in is a plain map carrying a `:handlers` lookup (event-id ->
  handler-fn) and the current `:db` value. Real wiring resolves these
  against `re-frame.frame` / `re-frame.events`; the scaffold keeps them
  injectable so the unit tests are pure.

  See spec/Design-TransducerRouter.md for the full contract."
  (:require [re-frame.interop :as interop]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the per-event xforms -------------------------------------------------
;;
;; Each xform takes (and returns) a step-result map per the design. The
;; transducer itself is `(comp xf-resolve-handler xf-validate-event
;; xf-run-handler xf-stage-effects xf-tag-step)`. All five are pure: no
;; container writes, no scheduling, no trace side-effects (the design has
;; trace emit-sites here but the scaffold elides them — the trace surface
;; is tested elsewhere and the scaffold's purpose is the reducing-function
;; story, not the trace projection).

(defn xf-resolve-handler
  "Map step: look up the event-id's handler in the envelope's frame and
  attach it as `:handler`. When the handler is missing, tag the step
  `:rf/step :no-handler` and stop further xforms from touching it."
  []
  (map (fn [{:keys [envelope] :as step}]
         (let [event    (:event envelope)
               event-id (first event)
               handlers (get-in envelope [:frame :handlers])
               handler  (get handlers event-id)]
           (if handler
             (assoc step :handler handler :event event :event-id event-id)
             (assoc step
                    :event     event
                    :event-id  event-id
                    :rf/step   :no-handler
                    :db-after  (:db-before step)
                    :effects   {}))))))

(defn xf-validate-event
  "Map step: when the envelope carries `:validate-event` (a predicate
  fn), invoke it. Failed validation short-circuits with
  `:rf/step :validation`. No-op when absent. This is the per-step seam
  for Spec 010 §Per-step recovery row 1."
  []
  (map (fn [{:keys [envelope event] :as step}]
         (let [validator (:validate-event envelope)]
           (cond
             (= (:rf/step step) :no-handler)
             step

             (and validator (not (validator event)))
             (assoc step
                    :rf/step  :validation
                    :db-after (:db-before step)
                    :effects  {})

             :else
             step)))))

(defn xf-run-handler
  "Map step: when the step is still `:ok`, invoke the handler against
  `:db-before` and the event. The handler signature is
  `(fn [db event] -> effects-map)` where effects-map is at minimum
  `{:db new-db}`; effects without `:db` carry the previous db through.

  Handler exceptions are caught and surfaced as `:rf/step :handler-throw`
  with the structured error attached. The pre-cascade `:db` is preserved
  on `:db-after` so a downstream `batch-rf` can decide whether to roll
  back or commit-with-error."
  []
  (map (fn [{:keys [handler event db-before] :as step}]
         (if (#{:no-handler :validation} (:rf/step step))
           step
           (try
             (let [effects  (handler db-before event)
                   db-after (if (contains? effects :db)
                              (:db effects)
                              db-before)]
               (assoc step
                      :rf/step  :ok
                      :db-after db-after
                      :effects  effects))
             (catch #?(:clj Throwable :cljs :default) ex
               (assoc step
                      :rf/step  :handler-throw
                      :db-after db-before
                      :effects  {}
                      :error    {:operation :rf.error/handler-throw
                                 :event     event
                                 :ex        ex})))))))

(defn xf-stage-effects
  "Map step: extract non-:db effects into a flat `:fx` seq so the
  reducing function can apply them in a single walk. v1's `do-fx`
  walks `:fx` in source order; the scaffold preserves that contract.

  An effects-map like `{:db ... :fx [[:dispatch [:x]] [:http {...}]]}`
  yields `:fx [[:dispatch [:x]] [:http {...}]]`. A non-`:fx`-shaped
  effects-map like `{:db ... :dispatch [:x]}` is normalised into the
  same seq form so reducing fns don't need to know about the legacy
  shape."
  []
  (map (fn [{:keys [effects] :as step}]
         (let [fx-vec    (vec (:fx effects))
               extras    (for [[k v] (dissoc effects :db :fx)
                               :when (some? v)]
                           [k v])
               staged    (into fx-vec extras)]
           (assoc step :fx staged)))))

(defn xf-tag-step
  "Map step: normalise `:rf/step` to `:ok` when no earlier xform has
  tagged the step. Reducing functions key off this to decide commit
  vs. rollback vs. emit-error-and-continue."
  []
  (map (fn [step]
         (cond-> step
           (nil? (:rf/step step)) (assoc :rf/step :ok)))))

(defn frame-transducer
  "The composed transducer: envelope -> step-result. Stateless; reusable
  across many envelopes and many reducing functions. Closes over no
  per-frame state — the frame is carried inside each envelope so the
  same transducer instance can route across frames if a host wants."
  []
  (comp
    (map (fn [envelope]
           {:envelope  envelope
            :db-before (get-in envelope [:frame :db])
            :event     (:event envelope)}))
    (xf-resolve-handler)
    (xf-validate-event)
    (xf-run-handler)
    (xf-stage-effects)
    (xf-tag-step)))

(defn frame-transducer-factory
  "Per the design's public surface. Returns a transducer that maps
  envelopes onto step-results for `frame`.

  The factory exists for symmetry with the design's public API. The
  frame is currently carried inside each envelope (so one transducer
  instance can service many frames); the factory is the future seam
  for per-frame customisations (e.g. interceptor-override pre-bound
  into the transducer)."
  [_frame]
  (frame-transducer))

;; ---- reducing-function presets -------------------------------------------
;;
;; Each reducing function closes over a frame-record-like atom holding
;; `{:db <current-db> :events-applied <count> :fx-applied <vec>
;;   :errors <vec>}`. The reducing function receives step-results from
;; the transducer and decides how to fold them into the accumulator.

(defn- apply-fx-eager
  "Apply the staged `:fx` seq immediately. The scaffold's fx-apply is a
  no-op shape — real wiring would dispatch into `re-frame.fx`. Returns
  the input acc with `:fx-applied` extended for test observability."
  [acc fx]
  (update acc :fx-applied into fx))

(defn sync-rf
  "Reducing function: every step commits eagerly. `:db-after` is written
  into the accumulator's `:db` slot, `:fx` is applied inline. This is
  the equivalent of v1's `dispatch-sync` — synchronous, fixed-point on
  return.

  The returned accumulator carries the final db plus a trace of every
  step under `:steps` for test observability. Production wiring would
  drop `:steps` and write through to the substrate container."
  []
  (fn
    ([] {:db nil :steps [] :fx-applied [] :errors []})
    ([acc] acc)                          ;; completing arity
    ([acc step]
     (let [{:keys [rf/step db-after fx error]} step]
       (cond-> acc
         true            (assoc :db db-after)
         true            (update :steps conj step)
         (seq fx)        (apply-fx-eager fx)
         (some? error)   (update :errors conj error)
         (= step :ok)    identity)))))

(defn queued-rf
  "Reducing function: commits eagerly per step (same as `sync-rf` for
  the accumulator surface), but `:fx [[:dispatch ...]]` entries are
  *enqueued* on the accumulator's `:queue` rather than applied inline.
  This is the v1 drain shape — the driver pumps the queue between
  outer transduce calls.

  A real wiring's driver (microtask / raf) re-enters the transduce
  with the dequeued envelope; the scaffold just exposes the queue for
  tests to drain manually via `manual-driver`."
  []
  (fn
    ([] {:db nil :steps [] :queue interop/empty-queue
         :fx-applied [] :errors []})
    ([acc] acc)
    ([acc step]
     (let [{:keys [rf/step db-after fx error]} step
           [dispatches non-dispatches] ((juxt filter remove)
                                        (fn [[k _]] (= k :dispatch))
                                        (or fx []))]
       (cond-> acc
         true                    (assoc :db db-after)
         true                    (update :steps conj step)
         (seq non-dispatches)    (apply-fx-eager (vec non-dispatches))
         (seq dispatches)        (update :queue into (map second dispatches))
         (some? error)           (update :errors conj error))))))

(defn batch-rf
  "Reducing function: defers the commit until the completing arity.
  Intermediate `:db-after` values are folded onto the accumulator's
  `:db` but `:fx` is NOT applied until completing. The completing
  arity then walks the staged-fx vector once.

  This is the natural fit for cascades that touch `:db` N times but
  should pay one commit + one fx-walk at cascade boundary (Spec 005
  `:always`, SSR loader fan-in)."
  []
  (fn
    ([] {:db nil :staged-fx [] :steps [] :fx-applied [] :errors []
         :committed? false})
    ([acc]
     (-> acc
         (apply-fx-eager (:staged-fx acc))
         (assoc :committed? true
                :staged-fx [])))
    ([acc step]
     (let [{:keys [rf/step db-after fx error]} step]
       (cond-> acc
         true            (assoc :db db-after)
         true            (update :steps conj step)
         (seq fx)        (update :staged-fx into fx)
         (some? error)   (update :errors conj error))))))

;; ---- manual driver --------------------------------------------------------
;;
;; The simplest driver: tests construct it, push envelopes, and tick the
;; pump explicitly. Real drivers wrap the same shape around the host's
;; microtask / raf / virtual-time scheduler.

(defn manual-driver
  "Construct a manual-pump driver. Returns a handle map with three
  closures over a single mutable cell:
    :push!  (push! envelope) — enqueue an envelope
    :tick!  (tick! frame rf) — pop one envelope and transduce it,
                               returning the new accumulator
    :drain! (drain! frame rf) — tick! to fixed point; returns the
                               final accumulator after the queue empties"
  []
  (let [state (atom {:queue interop/empty-queue :acc nil})]
    {:push!  (fn [envelope]
               (swap! state update :queue conj envelope))
     :tick!  (fn [frame rf]
               (let [{:keys [queue acc]} @state]
                 (if (empty? queue)
                   acc
                   (let [envelope (peek queue)
                         next-acc (transduce
                                    (frame-transducer-factory frame)
                                    (fn
                                      ([] (or acc (rf)))
                                      ([a] (rf a))
                                      ([a step] (rf a step)))
                                    [envelope])]
                     (swap! state assoc :queue (pop queue) :acc next-acc)
                     next-acc))))
     :drain! (fn [frame rf]
               (loop []
                 (let [{:keys [queue]} @state]
                   (if (empty? queue)
                     (:acc @state)
                     (do
                       (let [envelope (peek queue)
                             cur-acc  (or (:acc @state) (rf))
                             next-acc (transduce
                                        (frame-transducer-factory frame)
                                        (fn
                                          ([] cur-acc)
                                          ([a] a)
                                          ([a step] (rf a step)))
                                        cur-acc
                                        [envelope])]
                         (swap! state assoc
                                :queue (pop queue)
                                :acc next-acc))
                       (recur))))))
     :peek   (fn [] @state)}))
