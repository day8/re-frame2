(ns re-frame.story.runtime
  "Story runtime orchestration. Per IMPL-SPEC §3.2 + §5.

  Stage 3 (rf2-von3) lands the runtime that consumes Stage 2's
  registered artefacts and resolves them into a runnable variant:

  - `run-variant`     — allocate frame; run four-phase lifecycle;
                        return a promise/future of the result map.
  - `reset-variant`   — tear down and re-run.
  - `watch-variant`   — subscribe to lifecycle transitions.
  - `snapshot-identity` — re-export of `re-frame.story.identity/snapshot-identity`.

  ## The result map

  Per IMPL-SPEC §3.2 the resolved value of `run-variant` is:

      {:frame           <variant-id>
       :app-db          {...}
       :assertions      [{:assertion ... :passed? true ...} ...]
       :rendered-hiccup [...]               ; when :render? true
       :elapsed-ms      <number>
       :snapshot        {:variant-id ... :content-hash ...}
       :decorators      {:hiccup [...] :frame-setup [...]
                          :fx-override [...] :errors [...]}
       :effective-args  {...}
       :lifecycle       :ready | :error}

  Stage 5 (rf2-h8et) lands the play-sequence runtime that populates
  `:assertions` with full assertion semantics. Stage 3 leaves the slot
  present and empty.

  ## Elision

  Every entry point checks `re-frame.story.config/enabled?`. When
  false (production CLJS builds), the fns return an empty result map
  immediately — the inner body, the registrar lookups, and the frame
  allocation all elide. Per IMPL-SPEC §6.3 this is a *feature*:
  production code that accidentally calls `run-variant` does not throw
  — it returns empty."
  (:require [re-frame.core            :as rf]
            [re-frame.story.args      :as args]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async     :as async]
            [re-frame.story.config    :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames    :as frames]
            [re-frame.story.identity  :as ident]
            [re-frame.story.loaders   :as loaders]
            [re-frame.story.play      :as play]
            [re-frame.story.registrar :as registrar]
            [re-frame.interop         :as interop]
            [re-frame.trace           :as trace]))

;; ---- empty / disabled result ---------------------------------------------

(defn- empty-result
  "Per IMPL-SPEC §6.3: production callers see an empty result map
  rather than an exception. The shape matches a successful run with
  no registrations to act on."
  [variant-id]
  {:frame           variant-id
   :app-db          {}
   :assertions      []
   :rendered-hiccup nil
   :elapsed-ms      0
   :snapshot        nil
   :decorators      {:hiccup [] :frame-setup [] :fx-override [] :errors []}
   :effective-args  {}
   :lifecycle       :ready})

;; Forward declarations so phase fns (defined before record-error!) can
;; project exceptions per IMPL-SPEC §5.5 without reordering the file.
(declare record-error!)

;; ---- phase exception capture --------------------------------------------
;;
;; re-frame's interceptor chain catches handler exceptions internally and
;; emits a `:rf.error/handler-exception` trace event rather than re-
;; throwing. Stage 3's phase runners need to convert those trace events
;; into assertion records (per IMPL-SPEC §5.5).
;;
;; The capture pattern: register a trace listener around each phase
;; that collects matching errors into an atom. After the phase, walk
;; the atom and record each into the variant frame's `:rf.story/
;; assertions` accumulator.

(defonce ^:private capture-counter (atom 0))

(defn- capture-phase-errors
  "Run `body-fn` (a 0-arg thunk) with a registered trace listener that
  collects `:rf.error/handler-exception` events targeting `variant-id`'s
  frame. After the body returns, walks the captured errors and records
  each as a phase-tagged assertion via `record-error!`. Returns `body-fn`'s
  return value."
  [variant-id phase body-fn]
  (let [collected (atom [])
        cb-id     (keyword "re-frame.story.runtime"
                           (str "capture-" (swap! capture-counter inc)))
        listener  (fn [ev]
                    (when (and (= :rf.error/handler-exception (:operation ev))
                               (= variant-id (get-in ev [:tags :frame])))
                      (swap! collected conj ev)))]
    (trace/register-trace-cb! cb-id listener)
    (try
      (let [result (body-fn)]
        (doseq [ev @collected]
          (record-error! variant-id phase
                         (get-in ev [:tags :event])
                         (get-in ev [:tags :exception])))
        result)
      (finally
        (trace/remove-trace-cb! cb-id)))))

;; ---- phase-2 events execution --------------------------------------------

(defn- run-events!
  "Phase 2: dispatch every event in `:events` (story-level concat
  variant-level) into the variant's frame, draining between each. Per
  IMPL-SPEC §5.4 phase 2."
  [variant-id]
  (let [variant-body (frames/variant-body variant-id)
        story-id     (args/parent-story-id variant-id)
        story-body   (when story-id (registrar/handler-meta :story story-id))
        story-events (or (:events story-body) [])
        var-events   (or (:events variant-body) [])
        all-events   (concat story-events var-events)]
    (capture-phase-errors
      variant-id :phase-2-events
      (fn []
        (doseq [ev all-events]
          (try
            (rf/dispatch-sync ev {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) e
              ;; Synchronous throws (rare — re-frame's interceptor chain
              ;; usually catches and re-emits via trace) record here.
              (record-error! variant-id :phase-2-events ev e))))))))

;; ---- phase-1 loaders execution -------------------------------------------

(defn- run-loaders!
  "Phase 1: dispatch every event in `:loaders` into the variant's
  frame, evaluating `:loaders-complete-when` after each. Per IMPL-SPEC
  §5.4 phase 1.

  In Stage 3 the simple synchronous path is the load-bearing one:
  `dispatch-sync` drains run-to-completion before returning, so the
  default predicate's 'no further events in flight' check passes
  trivially. Variants with long-lived fx (websocket / interval) supply
  `:loaders-complete-when` to override; Stage 3 routes those through
  the predicate evaluator (Stage 5 adds the full assertion runtime)."
  [variant-id]
  (let [variant-body (frames/variant-body variant-id)
        loader-events (or (:loaders variant-body) [])]
    (loaders/start-loaders! variant-id)
    (capture-phase-errors
      variant-id :phase-1-loaders
      (fn []
        (doseq [ev loader-events]
          (try
            (rf/dispatch-sync ev {:frame variant-id})
            (catch #?(:clj Throwable :cljs :default) e
              (record-error! variant-id :phase-1-loaders ev e))))))
    ;; Evaluate :loaders-complete-when. In Stage 3 the predicate
    ;; resolves synchronously; Stage 6+ might add an async-retry shape.
    (let [complete? (loaders/evaluate-complete-when variant-id variant-body)]
      (when complete?
        (loaders/finish-loaders! variant-id)))))

;; ---- error recording -----------------------------------------------------

(defn- error-record
  "Build an error projection map per IMPL-SPEC §5.5."
  [phase event err]
  {:assertion :rf.error/exception
   :phase     phase
   :event     event
   :error     {:message #?(:clj  (.getMessage ^Throwable err)
                           :cljs (str err))
               :data    (when (instance? #?(:clj clojure.lang.ExceptionInfo
                                            :cljs ExceptionInfo) err)
                          (ex-data err))}
   :passed?   false})

(defn record-error!
  "Append an error record to the variant frame's `[:rf.story/assertions]`
  accumulator. Per IMPL-SPEC §5.5 errors continue the play sequence
  rather than aborting — the full picture is captured."
  [variant-id phase event err]
  (let [record (error-record phase event err)]
    (try
      (rf/dispatch-sync [::append-assertion record] {:frame variant-id})
      (catch #?(:clj Throwable :cljs :default) _
        ;; The frame may already be torn down; swallow.
        nil))
    record))

;; ---- helper event registrations ------------------------------------------

(defn install-helpers!
  "Register the runtime's internal helper events. Idempotent."
  []
  (when config/enabled?
    (rf/reg-event-db
      ::append-assertion
      (fn [db [_ record]]
        (update db :rf.story/assertions (fnil conj []) record)))))

;; ---- assertions read -----------------------------------------------------

(defn read-assertions
  "Return the assertions vector for `variant-id`'s frame, or `[]`."
  [variant-id]
  (or (:rf.story/assertions (rf/get-frame-db variant-id)) []))

;; ---- run-variant ---------------------------------------------------------

(defn- record-result-map
  "Build the result map returned by `run-variant`. Pure data; gathers
  whatever the runtime accumulated against the variant's frame."
  [variant-id decorator-stack effective-args snapshot start-ms]
  (let [app-db (rf/get-frame-db variant-id)]
    {:frame           variant-id
     :app-db          (or app-db {})
     :assertions      (or (:rf.story/assertions app-db) [])
     :rendered-hiccup nil       ;; Stage 4 fills this in
     :elapsed-ms      (- (interop/now-ms) start-ms)
     :snapshot        snapshot
     :decorators      decorator-stack
     :effective-args  effective-args
     :lifecycle       (loaders/current-state variant-id)}))

(defn run-variant
  "Per IMPL-SPEC §3.2. Allocate a frame for `variant-id`, run the four-
  phase lifecycle, and return a promise/future of the result map.

  `opts`:
    :active-modes    coll of registered mode ids; deep-merged into args
    :cell-overrides  runtime arg overrides (controls panel)
    :substrate       active substrate (`:reagent`, `:uix`, ...)
    :render?         when truthy, Stage 4's UI shell renders into
                     `:rendered-hiccup`. Stage 3 leaves the slot nil.
    :assertions      Stage 5's hook. Stage 3 accepts the slot but
                     leaves the runtime semantics to Stage 5.

  Returns a Promise (CLJS) / CompletableFuture (JVM). Per IMPL-SPEC
  §13.2 this is Stage 3's locked async-return shape.

  Production callers (CLJS `:advanced` with `enabled?` false) get an
  immediately-resolved promise of the empty result map — per IMPL-SPEC
  §6.3 the runtime doesn't throw when nothing is registered."
  ([variant-id] (run-variant variant-id nil))
  ([variant-id opts]
   (if-not config/enabled?
     (async/resolved (empty-result variant-id))
     (let [start-ms        (interop/now-ms)
           {:keys [active-modes cell-overrides substrate]} opts
           variant-body    (frames/variant-body variant-id)]
       (cond
         (nil? variant-body)
         (async/resolved (assoc (empty-result variant-id)
                                :lifecycle :error
                                :assertions [{:assertion :rf.error/unknown-variant
                                              :variant-id variant-id
                                              :passed? false}]))

         :else
         (async/promise
           (fn [resolve]
             (try
               (install-helpers!)
               (let [decorator-stack (decorators/resolve-decorators variant-id
                                                                    {:active-modes active-modes})
                     effective-args  (args/resolve-args variant-id opts)
                     snapshot        (ident/snapshot-identity variant-id opts)
                     play-events     (or (:play variant-body) [])]
                 ;; Phase 0: allocate frame, run :frame-setup decorators,
                 ;; drive lifecycle to :mounting.
                 (frames/allocate! variant-id decorator-stack)
                 ;; Install the play-runner's per-frame trace listener
                 ;; BEFORE the loader phase begins (rf2-v2g9). The
                 ;; listener feeds the assertions module's dispatched-
                 ;; events accumulator, which `:loaders-complete-when`'s
                 ;; vector form consults to gate the loaders-complete
                 ;; transition. Installing it post-loaders (the original
                 ;; Stage 5 placement) means the vector form sees an
                 ;; empty accumulator during the loader phase and never
                 ;; matches. We seed the accumulators here so the
                 ;; listener has a clean slot to write into; `execute-
                 ;; play!` resets them again at play start so play-time
                 ;; assertion semantics ("during play") are preserved.
                 (assertions/reset-trace-accumulators! variant-id)
                 (play/install-trace-listener! variant-id)
                 ;; Phase 1: loaders.
                 (run-loaders! variant-id)
                 ;; Phase 2: events.
                 (run-events! variant-id)
                 (loaders/finish-events! variant-id)
                 ;; Phase 3 (render): Stage 4's UI shell does the actual
                 ;; render. Stage 3 leaves :rendered-hiccup nil.
                 ;; Phase 4 (play + assertions): Stage 5 (rf2-h8et).
                 ;; `execute-play!` runs the play sequence synchronously
                 ;; on the JVM and via dispatch-sync on CLJS (re-frame's
                 ;; drain settles before each call returns); the returned
                 ;; promise resolves immediately to the assertions vector.
                 (let [play-promise (play/execute-play! variant-id play-events)]
                   ;; The execute-play! contract guarantees the promise
                   ;; resolves to the assertions vector once play
                   ;; completes. We chain `then` so the final result-map
                   ;; build sees the post-play app-db.
                   (-> play-promise
                       (async/then
                         (fn [_]
                           (resolve (record-result-map variant-id decorator-stack
                                                       effective-args snapshot
                                                       start-ms))
                           nil)))))
               (catch #?(:clj Throwable :cljs :default) e
                 (record-error! variant-id :phase-0-setup nil e)
                 (loaders/error! variant-id (ex-data e))
                 (resolve (record-result-map variant-id nil nil nil start-ms)))))))))))

;; ---- reset-variant -------------------------------------------------------

(defn reset-variant
  "Tear down the variant frame and re-run `run-variant` with `opts`.
  Per IMPL-SPEC §3.2. Used by Stage 4's UI shell on hot-reload + user-
  triggered 'reset' button.

  Returns a promise/future of the new result map."
  ([variant-id] (reset-variant variant-id nil))
  ([variant-id opts]
   (when config/enabled?
     (frames/destroy! variant-id))
   (run-variant variant-id opts)))

;; ---- watch-variant -------------------------------------------------------

(defn watch-variant
  "Per IMPL-SPEC §3.2 — subscribe to lifecycle transitions for
  `variant-id`'s frame. `callback` is invoked on every state change
  with `{:frame-id ... :from <state> :to <state> :event <event>}`.

  Returns a 0-arity unsubscribe fn.

  Stage 4's UI shell + Stage 5's assertions runtime consume this. The
  watcher table is per-frame so destroyed frames clean up automatically
  via `frames/destroy!`."
  [variant-id callback]
  (when config/enabled?
    (loaders/add-watcher! variant-id callback)))

;; ---- snapshot-identity re-export ----------------------------------------

(defn snapshot-identity
  "Per IMPL-SPEC §3.2. Compute the content-hash for
  `(variant × active-modes × cell-overrides × substrate)`. See
  `re-frame.story.identity/snapshot-identity` for the canonical form."
  ([variant-id] (ident/snapshot-identity variant-id))
  ([variant-id opts] (ident/snapshot-identity variant-id opts)))
