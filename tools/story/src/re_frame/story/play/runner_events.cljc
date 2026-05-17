(ns re-frame.story.play.runner-events
  "re-frame-side driver for the rich-DSL play runner (rf2-8i2a9).

  The pure step state machine + script parsing lives in
  `re-frame.story.play.runner`. This namespace owns the impure seams
  the step types reach for:

  - `:dispatch` / `:dispatch-sync` → `rf/dispatch*` / `rf/dispatch-sync*`
    against the variant's frame.
  - `:wait` ms → JS `setTimeout` (CLJS) or `Thread/sleep` (JVM).
  - `:assert-db` path value → read from `rf/get-frame-db` and compare.
  - `:assert-db` path :pred fn-sym → resolve the predicate via the
    framework registrar (`re-frame.story.predicates`) and invoke.
  - `:click` / `:type` / `:assert-dom` → delegate to
    `re-frame.story.play.dom` (no-op on JVM / no-DOM).

  The driver is async (returns a promise) so `:wait` and async
  dispatches compose naturally. On JVM, the driver runs the steps
  synchronously and returns a resolved future.

  ## Per-frame run state

  The runner stores a per-frame run-state map in a global atom
  (`run-state`) so the toolbar status chip can read it reactively.
  Keys: `{variant-id <runner state map>}`.

  ## Trace integration

  Each step emits a `:rf.story.play/step` trace event via
  `re-frame.trace.tooling/with-trace` so the Causa Trace tab shows the
  full play timeline with PASS/FAIL outcomes alongside the rest of the
  cascade."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string             :as str]
            [re-frame.core              :as rf]
            #?(:cljs [reagent.core      :as r])
            [re-frame.story.config      :as config]
            [re-frame.story.play.dom    :as dom]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.registrar   :as registrar]))

;; ---- per-variant run-state -----------------------------------------------

(defonce
  ^{:doc "frame-id → runner-state map. The UI's play-status chip derefs
         this atom and renders the per-variant status. The driver
         swaps the slot on every step transition. CLJS uses a Reagent
         ratom (so UI re-renders observe it); JVM uses a plain atom."}
  run-state
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defn current-state
  "Read the current run-state for `frame-id`, or nil if no run exists."
  [frame-id]
  (get @run-state frame-id))

(defn clear-state!
  "Wipe the run-state for `frame-id`. Called from frame teardown +
  before each fresh run."
  [frame-id]
  (swap! run-state dissoc frame-id)
  nil)

(defn- update-state!
  [frame-id f & args]
  (swap! run-state update frame-id #(apply f % args))
  nil)

(defn- set-state!
  [frame-id state]
  (swap! run-state assoc frame-id state)
  nil)

;; ---- wall-clock probe ----------------------------------------------------

(defn- now-ms []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

;; ---- spec resolution -----------------------------------------------------

(defn- handler-meta
  "Look up the variant body via the framework registrar."
  [variant-id]
  (try
    (registrar/handler-meta :variant variant-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn variant-play-script
  "Resolve the `:play-script` body on `variant-id` and parse it. Returns
  the normalised spec map per `runner/parse-spec`. Variants without
  `:play-script` return `{:script [] :auto-run? true}`."
  [variant-id]
  (runner/parse-spec
    (when-let [body (handler-meta variant-id)]
      (:play-script body))))

;; ---- trace emission ------------------------------------------------------

(defn- emit-trace!
  "Emit a `:rf.story.play/step` trace event for `step` against
  `variant-id`. Goes through `re-frame.core/emit-trace-event!` (which
  delegates to `re-frame.trace/emit!`) so the bus + ring buffer +
  listener fan-out all observe it. Safe under production elision —
  `re-frame.trace/emit!` short-circuits when `interop/debug-enabled?`
  is false."
  [variant-id name idx step result]
  (try
    (let [payload (runner/trace-record
                    {:variant-id variant-id
                     :name       name
                     :idx        idx
                     :step       step
                     :result     result})]
      ;; rf/emit-trace-event! arity is (op operation tags) per
      ;; re-frame.trace/emit!. We tag the envelope's :frame slot so
      ;; consumers (Trace tab, story.assertions listener) can route
      ;; per-frame.
      (rf/emit-trace-event!
        :rf.story.play/step
        runner/trace-event-id
        (merge {:frame variant-id} payload)))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

;; ---- step executors ------------------------------------------------------

(defn- exec-dispatch!
  "Execute a `:dispatch` step. Returns a no-assertion step-result."
  [frame-id idx step]
  (let [evec (runner/step-event step)]
    (try
      (rf/dispatch* evec {:frame frame-id})
      (runner/step-skip idx step)
      (catch #?(:clj Throwable :cljs :default) e
        (runner/step-exception idx step
                               #?(:clj  (.getMessage ^Throwable e)
                                  :cljs (str e)))))))

(defn- exec-dispatch-sync!
  [frame-id idx step]
  (let [evec (runner/step-event step)]
    (try
      (rf/dispatch-sync* evec {:frame frame-id})
      (runner/step-skip idx step)
      (catch #?(:clj Throwable :cljs :default) e
        (runner/step-exception idx step
                               #?(:clj  (.getMessage ^Throwable e)
                                  :cljs (str e)))))))

(defn- read-frame-db
  "Read the app-db for `frame-id`. Tolerant — returns nil if the frame
  is gone."
  [frame-id]
  (try
    (rf/get-frame-db frame-id)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- resolve-predicate
  "Resolve a predicate symbol to a callable fn. JVM uses
  `requiring-resolve`; CLJS resolves via `goog.global` lookup on
  munged dotted names (works for fns reachable from a global ns
  like `js/cljs.user.my_pred`). Returns nil on miss.

  CLJS resolution is best-effort — advanced-compiled fns have their
  namespace mangled, so author fns referenced via `:pred` should be
  defined `^:export`'d or under `js/window.<...>` for cross-target
  parity. JVM tests cover the common case end-to-end."
  [sym]
  (when sym
    (try
      #?(:clj
         (when-let [v (requiring-resolve (symbol sym))]
           (when (var? v) @v))
         :cljs
         (let [ns-part   (namespace sym)
               name-part (name sym)]
           (when (and ns-part name-part)
             (let [dotted (str ns-part "." name-part)
                   munged (str/replace dotted #"-" "_")
                   parts  (str/split munged #"\.")]
               (loop [obj js/window
                      ks  parts]
                 (cond
                   (nil? obj)  nil
                   (empty? ks) (when (fn? obj) obj)
                   :else       (recur (aget obj (first ks)) (rest ks))))))))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- exec-assert-db!
  [frame-id idx step]
  (let [{:keys [path mode expected pred-sym]} (runner/step-assert-db step)
        db (read-frame-db frame-id)]
    (case mode
      :equals
      (let [actual (get-in db path)]
        (if (= expected actual)
          (runner/step-pass idx step)
          (runner/step-fail idx step
                            {:expected expected
                             :actual   actual
                             :message  (str "assert-db " (pr-str path)
                                            " — expected " (pr-str expected)
                                            ", got " (pr-str actual))})))

      :pred
      (let [pred-fn (resolve-predicate pred-sym)
            actual  (get-in db path)]
        (cond
          (nil? pred-fn)
          (runner/step-fail idx step
                            {:message (str "assert-db :pred — could not resolve "
                                           pred-sym " in the predicates registry")})

          :else
          (try
            (if (pred-fn actual)
              (runner/step-pass idx step)
              (runner/step-fail idx step
                                {:expected (str "predicate " pred-sym " returns truthy")
                                 :actual   actual
                                 :message  (str "assert-db " (pr-str path)
                                                " — predicate " pred-sym " returned false")}))
            (catch #?(:clj Throwable :cljs :default) e
              (runner/step-fail idx step
                                {:message (str "assert-db :pred " pred-sym " threw — "
                                               #?(:clj (.getMessage ^Throwable e)
                                                  :cljs (str e)))}))))))))

(defn- exec-assert-dom!
  [_frame-id idx step]
  (let [{:keys [selector mode text]} (runner/step-assert-dom step)
        result (if (= :text mode)
                 (dom/assert-text selector text)
                 (dom/assert-visible selector mode))]
    (if (:passed? result)
      (runner/step-pass idx step)
      (runner/step-fail idx step result))))

(defn- exec-click!
  [_frame-id idx step]
  (let [selector (runner/step-selector step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot click " (pr-str selector))})

      (dom/click! selector)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "click failed — no node matched " (pr-str selector))}))))

(defn- exec-type!
  [_frame-id idx step]
  (let [[selector text] (runner/step-type-text step)]
    (cond
      (not (dom/dom-available?))
      (runner/step-fail idx step
                        {:skipped? true
                         :message  (str "no DOM — cannot type into " (pr-str selector))})

      (dom/type! selector text)
      (runner/step-skip idx step)

      :else
      (runner/step-fail idx step
                        {:message (str "type failed — no node matched " (pr-str selector))}))))

(defn exec-step!
  "Execute ONE step against `frame-id`. Returns a step-result record
  (per `runner/step-pass` / `step-fail` / `step-skip` / `step-exception`).
  Pure-shape return — the run-state mutation is the caller's job.

  `:wait` is special-cased OUT of this fn — it requires an async
  yield (`setTimeout` / `Thread/sleep`) the driver schedules around."
  [frame-id idx step]
  (case (runner/step-type step)
    :dispatch       (exec-dispatch!      frame-id idx step)
    :dispatch-sync  (exec-dispatch-sync! frame-id idx step)
    :assert-db      (exec-assert-db!     frame-id idx step)
    :assert-dom     (exec-assert-dom!    frame-id idx step)
    :click          (exec-click!         frame-id idx step)
    :type           (exec-type!          frame-id idx step)
    :wait           (runner/step-skip idx step)   ; driver handles the actual sleep
    (runner/unknown-step idx step)))

;; ---- async scheduler -----------------------------------------------------

(defn- schedule!
  "Run `f` after `ms` milliseconds. CLJS → `js/setTimeout`; JVM →
  block the calling thread via `Thread/sleep` then invoke. Returns a
  cancellable handle on CLJS, nil on JVM."
  [ms f]
  #?(:cljs (js/setTimeout f ms)
     :clj  (do (when (pos? ms) (Thread/sleep ^long ms))
               (f)
               nil)))

(defn- record-result!
  "Append `result` to the run-state for `frame-id` and emit the trace
  event."
  [frame-id name idx step result]
  (update-state! frame-id runner/record-step-result result)
  (emit-trace! frame-id name idx step result)
  nil)

(defn- finish!
  "Transition the run-state to `:pass` / `:fail` and resolve `done-cb`
  with the final state."
  [frame-id done-cb]
  (update-state! frame-id runner/finish (now-ms))
  (when done-cb
    (try (done-cb (current-state frame-id))
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- run-loop!
  "Iterate over the script, running each step. `:wait` steps yield
  to the scheduler and resume from the wait time onwards.

  `done-cb` is invoked with the final run-state once the loop ends."
  [frame-id done-cb]
  (let [state (current-state frame-id)]
    (cond
      ;; abort if state has gone missing (frame torn down mid-run)
      (nil? state)
      nil

      (runner/done? state)
      (finish! frame-id done-cb)

      :else
      (let [idx  (:step-idx state)
            step (runner/current-step state)
            nm   (:name state)]
        (cond
          (= :wait (runner/step-type step))
          (let [ms (runner/step-wait-ms step)]
            (record-result! frame-id nm idx step (runner/step-skip idx step))
            (schedule! (or ms 0) #(run-loop! frame-id done-cb)))

          :else
          (let [result (try
                         (exec-step! frame-id idx step)
                         (catch #?(:clj Throwable :cljs :default) e
                           (runner/step-exception idx step
                                                  #?(:clj  (.getMessage ^Throwable e)
                                                     :cljs (str e)))))]
            (record-result! frame-id nm idx step result)
            ;; Tail-call into the loop. On CLJS we yield via a 0-ms
            ;; setTimeout so a long async script doesn't blow the stack
            ;; and the UI gets a chance to repaint between steps.
            #?(:cljs (js/setTimeout #(run-loop! frame-id done-cb) 0)
               :clj  (recur frame-id done-cb))))))))

;; ---- public driver -------------------------------------------------------

(defn run!
  "Drive the play script for `variant-id`. Resets the per-variant
  run-state, walks every step in order, records results, and resolves
  `done-cb` with the terminal run-state.

  Returns the initial run-state (NOT a promise) so synchronous callers
  can immediately observe `:status :running` + `:total`. CLJS callers
  that need a promise can wrap with `js/Promise.` themselves;
  `done-cb` is the canonical completion hook.

  Idempotent w.r.t. concurrent runs — calling `run!` while a previous
  run is in flight cancels the previous run's `done-cb` (the new one
  takes over the run-state slot)."
  ([variant-id]
   (run! variant-id nil nil))
  ([variant-id done-cb]
   (run! variant-id nil done-cb))
  ([variant-id spec done-cb]
   (let [spec  (or spec (variant-play-script variant-id))
         init  (runner/initial-state spec)
         start (runner/start init (now-ms))]
     (set-state! variant-id start)
     (run-loop! variant-id done-cb)
     start)))

(defn re-run!
  "Re-run the play script for `variant-id`. Convenience wrapper around
  `run!` — distinct fn name so the toolbar's `[Re-run]` button has a
  one-call API."
  ([variant-id]
   (re-run! variant-id nil))
  ([variant-id done-cb]
   (run! variant-id done-cb)))

(defn auto-run!
  "Run the play script if `:auto-run?` is true. Called from the shell
  after the variant mounts. No-op when the variant has no
  `:play-script` slot or `:auto-run?` is false."
  ([variant-id]
   (auto-run! variant-id nil))
  ([variant-id done-cb]
   (when config/enabled?
     (let [spec (variant-play-script variant-id)]
       (when (and (:auto-run? spec) (seq (:script spec)))
         (run! variant-id spec done-cb))))))
