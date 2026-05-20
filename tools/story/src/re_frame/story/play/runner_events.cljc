(ns re-frame.story.play.runner-events
  "re-frame-side driver for the rich-DSL play runner (rf2-8i2a9).

  The pure step state machine + script parsing lives in
  `re-frame.story.play.runner`. This namespace owns the impure seams
  the step types reach for:

  - `:dispatch` / `:dispatch-sync` → `rf/dispatch*` / `rf/dispatch-sync*`
    against the variant's frame.
  - `:wait` ms → JS `setTimeout` (CLJS) or `Thread/sleep` (JVM).
  - `:assert-db` path value → read from `rf/get-frame-db` and compare.
  - `:assert-db` path :pred fn-or-sym → invoke the predicate. A FN
    handed in directly is called as-is (advanced-CLJS-safe); a SYMBOL
    is resolved at run time via `requiring-resolve` (JVM) or a
    best-effort `goog.global` walk (CLJS — fragile under advanced
    compilation, see rf2-inbad).
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
            [re-frame.story.play        :as play]
            [re-frame.story.play.dom    :as dom]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.registrar   :as registrar]))

;; ---- per-variant run-state -----------------------------------------------

(defonce
  ^{:doc "frame-id → runner-state map. The UI's play-status chip derefs
         this atom and renders the per-variant status. The driver
         swaps the slot on every step transition. CLJS uses a Reagent
         ratom (so UI re-renders observe it); JVM uses a plain atom.

         rf2-tl7zk multi-play: the stored state carries a `:play-key`
         slot (the play's `:name` for `:plays` variants, nil for the
         single-script `:play-script` slot). The chip uses this to
         show which play was last run; per-play history lives in
         `runs-by-play` for finer-grained queries."}
  run-state
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "[frame-id play-key] → runner-state. rf2-tl7zk: per-play
         history so the toolbar dropdown can show each play's last
         outcome and the CI runner can read per-play terminal state.
         For single-script (`:play-script`) variants the only key is
         `[variant-id nil]`. CLJS uses a Reagent ratom."}
  runs-by-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defonce
  ^{:doc "frame-id → play-key. rf2-tl7zk: the play the toolbar is
         currently focused on. Default is the first play's name (or
         nil for single-script variants). Changed by the dropdown's
         `select-play!`. Reagent ratom on CLJS."}
  active-play
  #?(:cljs (r/atom {})
     :clj  (atom  {})))

(defn current-state
  "Read the current run-state for `frame-id`, or nil if no run exists."
  [frame-id]
  (get @run-state frame-id))

(defn current-state-for-play
  "Read the run-state for `(frame-id, play-key)`, or nil. rf2-tl7zk:
  exposes per-play state for the dropdown's per-row status badges +
  the CI runner's per-play outcome read."
  [frame-id play-key]
  (get @runs-by-play [frame-id play-key]))

(defn active-play-key
  "Return the play-key the toolbar is currently focused on for
  `frame-id`, or nil. rf2-tl7zk multi-play."
  [frame-id]
  (get @active-play frame-id))

(defn set-active-play!
  "Set the toolbar's focused play for `frame-id`. rf2-tl7zk multi-play.
  Idempotent — re-setting the same key is a no-op."
  [frame-id play-key]
  (swap! active-play assoc frame-id play-key)
  nil)

(defn clear-state!
  "Wipe the run-state for `frame-id`. Called from frame teardown +
  before each fresh run."
  [frame-id]
  (swap! run-state dissoc frame-id)
  (swap! runs-by-play (fn [m]
                        (into {} (remove (fn [[[fid _]] _]
                                           (= fid frame-id)) m))))
  (swap! active-play dissoc frame-id)
  nil)

(defn- update-state!
  [frame-id play-key f & args]
  (swap! run-state update frame-id #(apply f % args))
  (swap! runs-by-play update [frame-id play-key] #(apply f % args))
  nil)

(defn- set-state!
  [frame-id play-key state]
  (let [tagged (assoc state :play-key play-key)]
    (swap! run-state    assoc frame-id tagged)
    (swap! runs-by-play assoc [frame-id play-key] tagged))
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
  `:play-script` return `{:script [] :auto-run? true}`.

  Note (rf2-tl7zk): variants declaring `:plays` resolve to the FIRST
  play's spec — preserves legacy single-script call sites + matches the
  toolbar's 'default play' behaviour. Callers that need the full plays
  vector should use `variant-plays` instead."
  [variant-id]
  (let [body  (handler-meta variant-id)
        plays (runner/variant-body->plays body)]
    (cond
      ;; Multi-play (:plays slot) — default to the first play.
      (and (seq plays) (contains? body :plays))
      (first plays)

      ;; Single-script (:play-script slot) — legacy path.
      :else
      (runner/parse-spec (when body (:play-script body))))))

;; ---- multi-play warning (one-shot) ---------------------------------------

(defonce ^:private ^{:doc "Set of variant ids that have already received
                          the both-:play-script-and-:plays console
                          warning. One warning per variant per page
                          lifetime keeps the console quiet."}
  warned-both-slots
  (atom #{}))

(defn- warn-both-slots-once!
  [variant-id]
  (when (and variant-id (not (contains? @warned-both-slots variant-id)))
    (swap! warned-both-slots conj variant-id)
    #?(:cljs
       (try
         (js/console.warn
           (str "[re-frame.story.play] " (pr-str variant-id)
                " declares BOTH :play-script and :plays — preferring :plays."
                " Pick one per variant to silence this warning."))
         (catch :default _ nil))
       :clj
       (binding [*out* *err*]
         (println (str "[re-frame.story.play] " (pr-str variant-id)
                       " declares BOTH :play-script and :plays — preferring :plays."
                       " Pick one per variant to silence this warning."))))))

(defn variant-plays
  "Resolve the canonical vector of parsed plays for `variant-id`. Pure
  data → data; works on JVM + CLJS. rf2-tl7zk multi-play.

  - `:plays` present → returns the parsed plays vector (size >= 1).
  - `:play-script` present → returns a single-entry vector wrapping the
    parsed single-script spec.
  - Both present → warns once, prefers `:plays`.
  - Neither → returns `[]`."
  [variant-id]
  (let [body (handler-meta variant-id)]
    (when (and body
               (contains? body :play-script)
               (contains? body :plays))
      (warn-both-slots-once! variant-id))
    (runner/variant-body->plays body)))

(defn resolve-play
  "Resolve a `(variant-id, play-key)` pair to the parsed play spec, or
  nil. `play-key` may be nil — meaning 'the default play' (first
  entry for multi-play, the single script for `:play-script`).
  rf2-tl7zk multi-play."
  [variant-id play-key]
  (let [plays (variant-plays variant-id)]
    (if (nil? play-key)
      (first plays)
      (runner/find-play plays play-key))))

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
  "Execute a `:dispatch` step. Returns a no-assertion step-result.

  Drains any handler-exception trace events captured by the play
  listener into `:rf.story/assertions` so the test-mode pane + Causa
  assertions panel see the failure (rf2-z2dq8). The re-frame router
  catches handler exceptions and emits `:rf.error/handler-exception`
  rather than re-throwing, so the local catch fires only for
  exceptions that escape the interceptor chain entirely."
  [frame-id idx step]
  (let [evec   (runner/step-event step)
        result (try
                 (rf/dispatch* evec {:frame frame-id})
                 (runner/step-skip idx step)
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    result))

(defn- exec-dispatch-sync!
  [frame-id idx step]
  (let [evec   (runner/step-event step)
        result (try
                 (rf/dispatch-sync* evec {:frame frame-id})
                 (runner/step-skip idx step)
                 (catch #?(:clj Throwable :cljs :default) e
                   (runner/step-exception idx step
                                          #?(:clj  (.getMessage ^Throwable e)
                                             :cljs (str e)))))]
    (play/drain-pending-exceptions! frame-id :phase-4-play)
    result))

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

  CLJS symbol resolution is BEST-EFFORT and FRAGILE under advanced
  compilation — the closure compiler mangles author namespace names,
  so the munged-dotted-name walk won't find them. The advanced-safe
  authoring path is to pass the predicate as a FN DIRECTLY in the
  `:assert-db [path] :pred <fn>` step; the runner short-circuits the
  resolver in that case (see `exec-assert-db!`).

  rf2-inbad: this fn remains for the symbol-form escape hatch (JVM
  tests + :dev CLJS) but is NOT the primary authoring path."
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

(defn- pred-label
  "Human-readable label for a `:pred` ref — the symbol literal when
  the author handed a symbol, the marker `<fn>` when they handed a
  fn directly. Used in failure messages so authors can spot which
  predicate failed without leaking compiler-munged identifiers."
  [ref]
  (cond
    (symbol? ref) (str ref)
    (fn? ref)     "<fn>"
    :else         (pr-str ref)))

(defn- exec-assert-db!
  [frame-id idx step]
  (let [{:keys [path mode expected pred-ref pred-fn?]} (runner/step-assert-db step)
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
      ;; rf2-inbad: prefer the fn-direct path (advanced-CLJS-safe). Fall
      ;; back to symbol resolution for the JVM / :dev CLJS escape hatch.
      (let [pred-fn (if pred-fn? pred-ref (resolve-predicate pred-ref))
            label   (pred-label pred-ref)
            actual  (get-in db path)]
        (cond
          (nil? pred-fn)
          (runner/step-fail idx step
                            {:message (str "assert-db :pred — could not resolve "
                                           label
                                           " (symbol resolution is fragile under"
                                           " advanced CLJS; pass the predicate as a"
                                           " fn directly to avoid this)")})

          :else
          (try
            (if (pred-fn actual)
              (runner/step-pass idx step)
              (runner/step-fail idx step
                                {:expected (str "predicate " label " returns truthy")
                                 :actual   actual
                                 :message  (str "assert-db " (pr-str path)
                                                " — predicate " label " returned false")}))
            (catch #?(:clj Throwable :cljs :default) e
              (runner/step-fail idx step
                                {:message (str "assert-db :pred " label " threw — "
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
  [frame-id play-key name idx step result]
  (update-state! frame-id play-key runner/record-step-result result)
  (emit-trace! frame-id name idx step result)
  nil)

(defn- finish!
  "Transition the run-state to `:pass` / `:fail` and resolve `done-cb`
  with the final state."
  [frame-id play-key done-cb]
  (update-state! frame-id play-key runner/finish (now-ms))
  (when done-cb
    (try (done-cb (current-state-for-play frame-id play-key))
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- run-loop!
  "Iterate over the script, running each step. `:wait` steps yield
  to the scheduler and resume from the wait time onwards.

  `done-cb` is invoked with the final run-state once the loop ends."
  [frame-id play-key done-cb]
  (let [state (current-state-for-play frame-id play-key)]
    (cond
      ;; abort if state has gone missing (frame torn down mid-run)
      (nil? state)
      nil

      (runner/done? state)
      (finish! frame-id play-key done-cb)

      :else
      (let [idx  (:step-idx state)
            step (runner/current-step state)
            nm   (:name state)]
        (cond
          (= :wait (runner/step-type step))
          (let [ms (runner/step-wait-ms step)]
            (record-result! frame-id play-key nm idx step (runner/step-skip idx step))
            (schedule! (or ms 0) #(run-loop! frame-id play-key done-cb)))

          :else
          (let [result (try
                         (exec-step! frame-id idx step)
                         (catch #?(:clj Throwable :cljs :default) e
                           (runner/step-exception idx step
                                                  #?(:clj  (.getMessage ^Throwable e)
                                                     :cljs (str e)))))]
            (record-result! frame-id play-key nm idx step result)
            ;; Tail-call into the loop. On CLJS we yield via a 0-ms
            ;; setTimeout so a long async script doesn't blow the stack
            ;; and the UI gets a chance to repaint between steps.
            #?(:cljs (js/setTimeout #(run-loop! frame-id play-key done-cb) 0)
               :clj  (recur frame-id play-key done-cb))))))))

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
  takes over the run-state slot).

  Arities:
  - `[variant-id]`                — run the default play (rf2-tl7zk:
                                    the first play of `:plays`, or the
                                    single `:play-script`).
  - `[variant-id done-cb]`        — as above + completion callback.
  - `[variant-id spec done-cb]`   — legacy: drive an explicit spec.
                                    The play-key is read off the spec's
                                    `:name` (nil for unnamed scripts).
  - `[variant-id play-key spec done-cb]` — rf2-tl7zk multi-play form:
                                    drive a specific play. `play-key`
                                    is the play's `:name` string (or
                                    nil for the single-script case)."
  ([variant-id]
   (run! variant-id nil nil nil))
  ([variant-id done-cb]
   ;; Legacy two-arity: variant-id + done-cb. Picks the default play.
   (run! variant-id nil nil done-cb))
  ([variant-id spec done-cb]
   ;; Legacy three-arity: variant-id + spec + done-cb. The play-key is
   ;; derived from the spec's :name (nil for unnamed scripts). Preserved
   ;; for back-compat with callers that hand-build a spec.
   (run! variant-id (when spec (:name spec)) spec done-cb))
  ([variant-id play-key spec done-cb]
   (let [spec  (or spec
                   (resolve-play variant-id play-key)
                   ;; Fall back to the legacy single-script path so the
                   ;; default play of a `:play-script` variant Just Works.
                   (variant-play-script variant-id))
         pk    (or play-key (:name spec))
         init  (runner/initial-state spec)
         started (runner/start init (now-ms))]
     (set-state! variant-id pk started)
     (set-active-play! variant-id pk)
     (run-loop! variant-id pk done-cb)
     started)))

(defn re-run!
  "Re-run the play script for `variant-id`. Convenience wrapper around
  `run!` — distinct fn name so the toolbar's `[Re-run]` button has a
  one-call API.

  rf2-tl7zk: with no explicit `play-key`, re-runs the currently active
  play (set by the dropdown). For single-script variants the active
  play is nil, so this matches the legacy behaviour."
  ([variant-id]
   (re-run! variant-id nil))
  ([variant-id done-cb]
   (let [pk (active-play-key variant-id)]
     (run! variant-id pk nil done-cb))))

(defn run-play!
  "rf2-tl7zk multi-play: run the play identified by `play-key` (a
  play's `:name`) for `variant-id`. Passing nil picks the default
  play (first entry for multi-play, the single script for
  `:play-script`)."
  ([variant-id play-key]
   (run-play! variant-id play-key nil))
  ([variant-id play-key done-cb]
   (run! variant-id play-key nil done-cb)))

(defn select-play!
  "rf2-tl7zk: set `variant-id`'s active play to `play-key` WITHOUT
  running it. Used by the toolbar dropdown when the user picks a play
  but the user hasn't pressed Re-run yet."
  [variant-id play-key]
  (set-active-play! variant-id play-key))

(declare run-plays-sequentially!)

(defn auto-run!
  "Run the play script if `:auto-run?` is true. Called from the shell
  after the variant mounts. No-op when the variant has no
  `:play-script` / `:plays` slot or no play declares `:auto-run? true`.

  rf2-tl7zk multi-play: every play with `:auto-run? true` is run in
  ORDER (sequentially) so they don't race against the same frame. By
  the per-play default (first play true, rest false) only the first
  play auto-runs on mount; subsequent plays opt in explicitly."
  ([variant-id]
   (auto-run! variant-id nil))
  ([variant-id done-cb]
   (when config/enabled?
     (let [plays      (variant-plays variant-id)
           auto-plays (filterv (fn [p] (and (:auto-run? p)
                                            (seq (:script p))))
                               plays)]
       (cond
         (empty? auto-plays)
         nil

         ;; Single auto-run play — direct fire so the legacy
         ;; single-script callers keep their existing run shape.
         (= 1 (count auto-plays))
         (let [spec (first auto-plays)]
           (run! variant-id (:name spec) spec done-cb))

         ;; Multiple auto-run plays — sequence them so a later play
         ;; doesn't trample the frame mid-run.
         :else
         (run-plays-sequentially! variant-id auto-plays done-cb))))))

;; ---- run-all (sequential) — rf2-tl7zk ------------------------------------

(defn- run-plays-sequentially!
  "Internal: run `plays` against `variant-id` one after another.
  Resolves `done-cb` with a vector of terminal states once every play
  has finished (or the loop is interrupted by a missing frame)."
  [variant-id plays done-cb]
  (let [acc (atom [])]
    (letfn [(step! [remaining]
              (if (empty? remaining)
                (when done-cb
                  (try (done-cb @acc)
                       (catch #?(:clj Throwable :cljs :default) _ nil)))
                (let [spec (first remaining)
                      pk   (:name spec)]
                  (run! variant-id pk spec
                        (fn [final]
                          (swap! acc conj final)
                          (step! (rest remaining)))))))]
      (step! plays))))

(defn run-all-plays!
  "rf2-tl7zk multi-play: run every play declared on `variant-id` in
  order, sequentially. Calls `done-cb` with a vector of per-play
  terminal states once every play has completed. Returns nil.

  No-op when the variant carries no plays."
  ([variant-id]
   (run-all-plays! variant-id nil))
  ([variant-id done-cb]
   (let [plays (variant-plays variant-id)]
     (when (seq plays)
       (run-plays-sequentially! variant-id (vec plays) done-cb)))))
