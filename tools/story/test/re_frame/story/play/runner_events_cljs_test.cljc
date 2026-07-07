(ns re-frame.story.play.runner-events-cljs-test
  "Integration tests for the rich-DSL play runner against a live
  re-frame frame (rf2-8i2a9).

  Most tests live under a JVM `#?(:clj ...)` reader gate because
  `re-frame.story.async/deref-blocking` is JVM-only. CLJS tests of
  the runner-events surface live under the `dom` cljs corpus or the
  Playwright browser specs (see TESTING matrix). The pure
  `parse-spec` / `coerce-script` paths are exercised by
  `runner-test` (no re-frame deps); those run on both runtimes.

  CLJS-side coverage that survives the JVM gate:

  - The runner's pure state machine (via `runner-test`).
  - The chip + banner label helpers (via `play-status-cljs-test`).
  - The dispatch→assertion outcome-matching bridge (`failed-since` +
    `dispatch-step-result`, exercised directly below — rf2-uhq5j)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.play.runner :as runner]
            [re-frame.core              :as rf]
            [re-frame.frame             :as frame]
            [re-frame.story             :as story]
            [re-frame.story.loaders     :as loaders]
            [re-frame.story.play        :as legacy-play]
            [re-frame.story.play.runner-events :as re]
            ;; rf2-rkd14 — the EXACT narrative attribution tests read a live
            ;; epoch tape via `rf/epoch-history`; requiring the epoch artefact
            ;; installs its late-bind capture hooks so the tape is real (it
            ;; degrades to `[]` without the dep, mirroring artifact-test). The
            ;; fixture's `epoch/clear-history!` runs on both runtimes, so this
            ;; require is unconditional.
            [re-frame.epoch             :as epoch]
            [re-frame.machines          :as machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.registrar         :as registrar]
            ;; `re-frame.story.async/deref-blocking` is JVM-only (it
            ;; blocks a thread) and `config/set-global-args!` is only
            ;; needed by the JVM `reset-all` lineage — the integration
            ;; tests below that use them are themselves JVM-gated. The
            ;; rf2-rkd14 EXACT-attribution tests are likewise `:clj`-gated, so
            ;; their `evidence` / `fingerprint` deps ride this block too.
            #?@(:clj [[re-frame.story.async  :as async]
                      [re-frame.story.config :as config]
                      [re-frame.story.play.evidence :as evidence]
                      [re-frame.story.fingerprint :as fingerprint]])))

;; ---- CLJS+JVM: the dispatch→assertion outcome-matching bridge -----------
;;
;; `failed-since` + `dispatch-step-result` (runner_events.cljc) turn a
;; handler-recorded `:rf.assert/*` failure into a runner `step-fail` so
;; the play's terminal status flips. Before rf2-uhq5j these were hit only
;; transitively via the heavy JVM `run-blocking` integration tests below;
;; this section drives them directly on BOTH runtimes by seeding a known
;; `:rf.story/assertions` vector on a live frame, then asserting the
;; step-fail / step-skip shape + `:message`/`:expected`/`:actual`
;; projection. Both fns are private — reached via var-quote (the
;; established Story-test seam pattern, e.g. play/listener-for-frame).

(def ^:private failed-since        @#'re/failed-since)
(def ^:private dispatch-step-result @#'re/dispatch-step-result)

(def ^:private bridge-frame :story.bridge/frame)

(defn- seed-assertions!
  "Append each record in `recs` to `bridge-frame`'s `:rf.story/assertions`
  via a real `dispatch-sync`, mirroring how the `:rf.assert/*` handlers
  land their records. Returns the post-seed assertion count."
  [recs]
  (doseq [r recs]
    (rf/dispatch-sync [::seed r] {:frame bridge-frame}))
  (count (:rf.story/assertions (rf/app-db-value bridge-frame))))

(defn- bridge-reset!
  "Single namespace fixture (rf2-uhq5j unified the prior JVM-only
  `reset-all`). Resets every Story side-table + the re-frame frame
  registry, reinstalls the canonical vocabulary, and registers the
  bridge test frame + its `::seed` event. Runs on both runtimes; the
  JVM-only `:reload` of machines + `config/set-global-args!` are gated."
  [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  ;; rf2-rkd14 — clear the epoch ring + listeners so each EXACT-attribution
  ;; test reads only its own freshly-captured tape.
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  #?(:clj (require 're-frame.machines :reload))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  #?(:clj (config/set-global-args! {}))
  (reset! legacy-play/stepper-state {})
  (reset! re/run-state {})
  ;; rf2-vkdam — reset the per-dispatch-step settle boundaries so a test
  ;; observes only its own run's boundaries, not a prior test's accumulation.
  (reset! re/step-boundaries {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (frame/reg-frame bridge-frame {:doc "outcome-matching bridge test frame"})
  (rf/reg-event ::seed
    (fn [{:keys [db]} [_ rec]]
      {:db (update db :rf.story/assertions (fnil conj []) rec)}))
  (test-fn))

(use-fixtures :each bridge-reset!)

(deftest failed-since-filters-to-failures-after-prev-count
  (testing "failed-since returns only the :passed? false records appended
            after prev-count — the dispatched event's contribution"
    (let [prev (seed-assertions! [{:passed? true  :id :rf.assert/path-equals}])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :expected :loaded :actual :idle :message "boom"}
                                  {:passed? true  :id :rf.assert/path-equals}])
          out  (failed-since bridge-frame prev)]
      (is (= 1 (count out))
          "only the one new failing record is returned — the earlier pass
           (before prev-count) and the trailing pass are excluded")
      (is (= :loaded (:expected (first out))))
      (is (= :idle   (:actual   (first out)))))))

(deftest failed-since-empty-when-no-new-failures
  (testing "failed-since is empty when nothing failed since prev-count"
    (let [prev (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])]
      ;; prior failure is BEFORE prev-count; a clean pass lands after.
      (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])
      (is (empty? (failed-since bridge-frame prev))
          "the pre-prev failure is excluded; the post-prev pass is filtered"))))

(deftest failed-since-tolerates-prev-count-overshoot
  (testing "failed-since clamps prev-count to the current length (subvec
            never throws even if the accumulator shrank)"
    (seed-assertions! [{:passed? false :id :rf.assert/path-equals}])
    (is (= [] (failed-since bridge-frame 999))
        "an out-of-range prev-count yields an empty slice, not an error")))

(deftest dispatch-step-result-projects-failure-to-step-fail
  (testing "a new failing assertion since prev becomes a runner step-fail
            carrying the record's :expected / :actual / :message"
    (let [step [:dispatch-sync [:rf.assert/path-equals [:status] :loaded]]
          prev (seed-assertions! [])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :expected :loaded :actual :idle
                                   :message  "expected :loaded, got :idle"}])
          out  (dispatch-step-result bridge-frame prev 3 step)]
      (is (false? (:passed? out)) "the step result flips to fail")
      (is (= :dispatch-sync (:type out)) "step-type is preserved")
      (is (= 3 (:idx out)))
      (is (= step (:step out)))
      (is (= :loaded (:expected out)))
      (is (= :idle   (:actual out)))
      (is (= "expected :loaded, got :idle" (:message out))))))

(deftest dispatch-step-result-synthesizes-message-when-record-has-none
  (testing "when the failing record carries no :message, the bridge
            synthesizes one from :id / :payload / :expected / :actual"
    (let [step [:dispatch [:rf.assert/path-equals [:k] 1]]
          prev (seed-assertions! [])
          _    (seed-assertions! [{:passed? false :id :rf.assert/path-equals
                                   :payload  [[:k] 1] :expected 1 :actual 0}])
          out  (dispatch-step-result bridge-frame prev 0 step)]
      (is (false? (:passed? out)))
      (is (re-find #":rf.assert/path-equals" (:message out))
          "the synthesized message names the assertion id")
      (is (re-find #"expected 1" (:message out)))
      (is (re-find #"actual 0"   (:message out))))))

(deftest dispatch-step-result-skips-when-no-failure
  (testing "a dispatch that contributed no failing assertion is a
            step-skip — :passed? nil, so it doesn't count toward failures"
    (let [step [:dispatch-sync [:some/event]]
          prev (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])]
      ;; A clean pass lands after prev — no failures contributed.
      (seed-assertions! [{:passed? true :id :rf.assert/path-equals}])
      (let [out (dispatch-step-result bridge-frame prev 1 step)]
        (is (nil? (:passed? out)) "step-skip leaves :passed? nil")
        (is (= :dispatch-sync (:type out)))
        (is (= 1 (:idx out)))
        (is (= step (:step out)))))))

;; ---- CLJS+JVM: terminal assertions auto-run (rf2-nyjoa) -----------------
;;
;; `run-terminal-assertions!` is the lifecycle entry the runtime calls after
;; the script phase settles. It reuses the ONE in-script executor
;; (`exec-assert!`) per terminal atom, so a handler-backed atom records its
;; verdict on `:rf.story/assertions` (same path the checkpoints use) and a
;; tape-evaluated atom is NEVER dispatched (no double-processing). Exercised
;; directly here on a live bridge frame on both runtimes.

(defn- seed-db!
  "Merge `m` into `bridge-frame`'s app-db via a real dispatch-sync, so the
  terminal assertion has a FINAL settled state to read."
  [m]
  (rf/dispatch-sync [::seed-db m] {:frame bridge-frame})
  (:rf.story/assertions (rf/app-db-value bridge-frame)))

(deftest run-terminal-assertions-records-handler-backed-pass-and-fail
  (testing "run-terminal-assertions! dispatches each handler-backed terminal
            atom through the ONE executor, recording the canonical pass/fail
            record on :rf.story/assertions — no parallel evaluator"
    (rf/reg-event ::seed-db (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
    (seed-db! {:status :loaded})
    (re/run-terminal-assertions!
      bridge-frame
      [[:rf.assert/path-equals [:status] :loaded]    ; passes
       [:rf.assert/path-equals [:status] :idle]])    ; fails
    (let [recs (vec (:rf.story/assertions (rf/app-db-value bridge-frame)))
          pe   (filterv #(= :rf.assert/path-equals (:assertion %)) recs)]
      (is (= 2 (count pe)) "both handler-backed terminal atoms recorded")
      (is (true?  (:passed? (first pe)))  "the matching expectation passed")
      (is (false? (:passed? (second pe))) "the mismatching expectation failed"))))

(deftest run-terminal-assertions-skips-tape-evaluated-no-double-process
  (testing "a tape-evaluated terminal atom (:rf.assert/schema-error) is NOT
            dispatched by run-terminal-assertions! — it carries no
            reg-event handler; the result boundary owns its verdict
            against the tape, so no handler-backed record is minted here
            (rf2-nyjoa critical guard against double-processing)"
    (rf/reg-event ::seed-db (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
    (seed-db! {:status :loaded})
    (re/run-terminal-assertions!
      bridge-frame
      [[:rf.assert/path-equals [:status] :loaded]
       [:rf.assert/schema-error {:where :event :event :some/evt}]])
    (let [recs (vec (:rf.story/assertions (rf/app-db-value bridge-frame)))]
      (is (= 1 (count (filterv #(= :rf.assert/path-equals (:assertion %)) recs)))
          "the handler-backed atom recorded exactly once")
      (is (empty? (filterv #(= :rf.assert/schema-error (:assertion %)) recs))
          "the tape-evaluated schema-error atom minted NO record here —
           it is not dispatched (no double-processing)"))))

(deftest run-terminal-assertions-empty-is-noop
  (testing "run-terminal-assertions! with no atoms records nothing"
    (rf/reg-event ::seed-db (fn [{:keys [db]} [_ m]] {:db (merge db m)}))
    (seed-db! {:status :loaded})
    (re/run-terminal-assertions! bridge-frame [])
    (re/run-terminal-assertions! bridge-frame nil)
    (is (empty? (:rf.story/assertions (rf/app-db-value bridge-frame)))
        "an empty / nil terminal-assertions vector is a clean no-op")))

;; ---- CLJS-runnable: pure-data coverage of the script lift ---------------
;;
;; (rf2-uhq5j) the former `bare-event-vector-lift-via-coerce` test here
;; was removed: it duplicated the pure `coerce-script` / `parse-spec`
;; contract already asserted in `runner-test`
;; (coerce-script-lifts-bare-event-vectors + parse-spec-lifts-bare-
;; vectors-inside-map). The wrong-layer copy added no signal.

;; ---- CLJS+JVM: every run-loop! exit path settles done-cb (rf2-9x5fm) -----
;;
;; The run loop has TWO silent-abort branches that previously returned `nil`
;; WITHOUT invoking `done-cb`:
;;
;;   1. `(nil? state)` — the frame was torn down mid-run (run-state entry
;;      gone). Reachable on CLJS when a hot-reload reset / teardown clears the
;;      run-state during an async `:wait` yield.
;;   2. token mismatch (rf2-ftow6) — a concurrent `run!` took over the
;;      run-state slot, stamping a fresher `:run-token`.
;;
;; When either fired, `done-cb` was never called, so the awaiting continuation
;; (`runtime/run-phase-4!`'s `step!`) never resolved the play-promise, and the
;; outer `run-variant` promise hung forever (it chains only `then`, no `catch`
;; / timeout). The fix routes both branches through `settle-abort!`, which
;; ALWAYS settles `done-cb` (with the last-known/aborted state) WITHOUT
;; mutating the shared run-state slot — so the stale loop releases its
;; continuation but never clobbers a newer run's state.
;;
;; These drive `run-loop!` directly (private, var-quoted — the established
;; Story-test seam). Both abort branches are the FIRST `cond` clauses and
;; return synchronously (no `setTimeout` yield), so the test is deterministic
;; on both runtimes: it asserts `done-cb` fired, which is the exact fact a hang
;; violates. Runs under `npm run test:cljs` — the CLJS runtime where the hang
;; was reachable.

(def ^:private run-loop!    @#'re/run-loop!)
(def ^:private set-state!   @#'re/set-state!)
(def ^:private settle-abort! @#'re/settle-abort!)

(deftest run-loop-aborts-on-missing-state-still-settles-done-cb
  (testing "rf2-9x5fm — the `(nil? state)` abort branch (frame torn down
            mid-run) STILL invokes done-cb so the awaiting continuation
            resolves rather than hanging the play-promise forever"
    (let [frame-id :story.abort/torn-down
          settled  (atom :unset)]
      ;; No run-state entry exists for this [frame-id nil] slot — the frame
      ;; was torn down (clear-state! wiped it). The loop's first cond clause
      ;; `(nil? state)` fires.
      (is (nil? (re/current-state-for-play frame-id nil))
          "precondition: no run-state for the torn-down frame")
      (run-loop! frame-id nil "tok-A" (fn [final] (reset! settled final)))
      (is (not= :unset @settled)
          "done-cb fired on the torn-down-frame abort — the continuation is
           released, so the play-promise (and the outer run-variant promise)
           cannot hang")
      (is (nil? @settled)
          "the aborted continuation settles with the last-known state — nil
           when the slot is gone"))))

(deftest run-loop-aborts-on-token-mismatch-still-settles-done-cb
  (testing "rf2-9x5fm — the token-mismatch abort branch (a newer run! took
            over the slot, rf2-ftow6) STILL invokes the STALE loop's done-cb
            so its continuation resolves; it does NOT clobber the newer run's
            run-state (no finish! / update-state!)"
    (let [frame-id :story.abort/token-swap
          settled  (atom :unset)
          ;; Seed the slot with a NEWER token than the stale loop carries.
          newer    (runner/start
                     (runner/initial-state {:script [[:dispatch [:noop]]] :name nil})
                     1)]
      (set-state! frame-id nil (assoc newer :run-token "tok-NEWER"))
      ;; The stale loop carries "tok-STALE" — it mismatches the slot's
      ;; "tok-NEWER", so the second cond clause fires.
      (run-loop! frame-id nil "tok-STALE" (fn [final] (reset! settled final)))
      (is (not= :unset @settled)
          "the stale loop's done-cb fired on token mismatch — its continuation
           is released, never stranded")
      ;; The newer run still OWNS the slot — the stale abort must not have
      ;; transitioned it via finish!/update-state!.
      (let [slot (re/current-state-for-play frame-id nil)]
        (is (= "tok-NEWER" (:run-token slot))
            "the slot still carries the NEWER run's token — the stale abort
             did not clobber it")
        (is (= :running (:status slot))
            "the slot's status was NOT transitioned to a terminal status by the
             stale abort (finish! would have set :pass/:fail/:cannot-run)")))))

(deftest settle-abort-tolerates-nil-done-cb
  (testing "rf2-9x5fm — settle-abort! is a clean no-op when done-cb is nil
            (a run! called with no completion hook) and never throws"
    (is (nil? (settle-abort! :story.abort/no-cb nil nil))
        "no done-cb → settle-abort! returns nil without throwing")))

;; ---- JVM-only: integration tests against a live re-frame frame ----------
;;
;; These reuse the namespace-wide `bridge-reset!` fixture above (which
;; unified the prior JVM-only `reset-all`).

#?(:clj
   (defn- run-blocking
     "Drive `run!` and block until terminal status is reached or
     `timeout-ms` expires."
     ([variant-id] (run-blocking variant-id 5000))
     ([variant-id timeout-ms]
      (let [done (atom nil)]
        (re/run! variant-id (fn [state] (reset! done state)))
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (cond
              @done @done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "run-blocking timeout"
                              {:variant-id variant-id
                               :state      @re/run-state}))
              :else (do (Thread/sleep 5) (recur)))))))))

;; ---- dispatch + dispatch-sync round-trip --------------------------------

#?(:clj
   (deftest dispatch-sync-fires-event
     (testing ":dispatch-sync step fires the event into the variant's frame"
       (let [seen (atom [])]
         (rf/reg-event :rt/inc
           (fn [{:keys [db]} _] (swap! seen conj :hit)
             {:db (update db :n (fnil inc 0))}))
         (story/reg-variant :story.runner/sync
           {:events []
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]]}})
         (async/deref-blocking (story/run-variant :story.runner/sync) 5000)
         (let [final (run-blocking :story.runner/sync)]
           (is (= :pass (:status final)))
           (is (= [:hit :hit] @seen))
           (is (= 2 (count (:results final)))))))))

;; ---- rf2-l2cn5d (EP-0017): captured :rf.cofx replays into the handler ----
;;
;; The headline acceptance criterion — a replayed `[:dispatch evec {:rf.cofx …}]`
;; / `[:dispatch-sync evec {:rf.cofx …}]` step re-presents the RECORDED
;; recordable coeffects (a PROVIDED fact + the framework `:rf/time-ms`) to the
;; handler, rather than restamping `:rf/time-ms` or failing
;; `:rf.error/missing-required-cofx` for the provided fact. Without the
;; envelope, the same provided-fact handler MUST fail loudly (proving the
;; capture/replay is load-bearing, not decorative).

#?(:clj
   (deftest dispatch-replays-captured-cofx-into-handler
     (testing "a captured :rf.cofx envelope on a dispatch step delivers the
              recorded provided fact + the recorded :rf/time-ms to the handler"
       ;; The bridge fixture clears the registrar and re-inits the runtime;
       ;; ensure the framework `:rf/time-ms` recordable cofx is registered for
       ;; this test's frame registrar (the variant frame's registrar does not
       ;; auto-carry it under the story test harness). Idempotent — matches the
       ;; framework default (cofx.cljc §:rf/time-ms — recordable, provided).
       (when-not (registrar/lookup :cofx :rf/time-ms)
         (rf/reg-cofx :rf/time-ms {:recordable? true :provided? true}))
       ;; A PROVIDED recordable fact has no generator — absent on the token it
       ;; is :rf.error/missing-required-cofx in every mode, so re-presenting the
       ;; recorded value is the only way replay succeeds.
       (rf/reg-cofx :rf2-l2cn5d.delta/v
         {:recordable? true :provided? true
          :doc "A provided recordable delta the recorder captured."})
       (let [seen (atom [])]
         (rf/reg-event :rf2-l2cn5d/inc-by
           {:doc "Increment by a replayable delta + stamp the replayed time."
            :rf.cofx/requires [:rf/time-ms :rf2-l2cn5d.delta/v]}
           (fn [{:keys [db] t :rf/time-ms delta :rf2-l2cn5d.delta/v} _]
             (swap! seen conj {:t t :delta delta})
             {:db (-> db (update :n (fnil + 0) delta) (assoc :last-t t))}))
         ;; The recorded envelope: a provided fact + a recorded (NOT current)
         ;; :rf/time-ms. Replay must re-present BOTH verbatim.
         (story/reg-variant :story.runner/cofx-replay
           {:events []
            :play-script {:auto-run? false
                          :script [[:dispatch      [:rf2-l2cn5d/inc-by]
                                    {:rf.cofx {:rf/time-ms 1781078400123
                                               :rf2-l2cn5d.delta/v 4}}]
                                   [:dispatch-sync [:rf2-l2cn5d/inc-by]
                                    {:rf.cofx {:rf/time-ms 1781078400999
                                               :rf2-l2cn5d.delta/v 10}}]]}})
         (async/deref-blocking (story/run-variant :story.runner/cofx-replay) 5000)
         (let [final (run-blocking :story.runner/cofx-replay)]
           (is (= :pass (:status final))
               "both steps replay cleanly — the provided fact was re-presented")
           (is (= [{:t 1781078400123 :delta 4}
                   {:t 1781078400999 :delta 10}]
                  @seen)
               "the handler saw the RECORDED :rf/time-ms + provided fact, not a
                fresh stamp")
           (is (= 14 (:n (rf/app-db-value :story.runner/cofx-replay)))
               "4 (:dispatch) + 10 (:dispatch-sync) folded from the recorded
                deltas")
           (is (= 1781078400999 (:last-t (rf/app-db-value :story.runner/cofx-replay)))
               "the last replayed :rf/time-ms landed in app-db verbatim"))))))

#?(:clj
   (deftest dispatch-without-cofx-fails-missing-required-provided-fact
     (testing "a provided-fact handler replayed WITHOUT the captured envelope
              fails loudly (:rf.error/missing-required-cofx) — proving the
              captured cofx is load-bearing, not decorative"
       (rf/reg-cofx :rf2-l2cn5d.token/v
         {:recordable? true :provided? true
          :doc "A provided recordable token with no generator."})
       (rf/reg-event :rf2-l2cn5d/needs-token
         {:rf.cofx/requires [:rf2-l2cn5d.token/v]}
         (fn [{:keys [db] tok :rf2-l2cn5d.token/v} _]
           {:db (assoc db :tok tok)}))
       ;; The bare 2-element step (no envelope) — the pre-fix recorder
       ;; behaviour. The provided fact is absent → the step must fail.
       (story/reg-variant :story.runner/cofx-missing
         {:events []
          :play-script {:auto-run? false
                        :script [[:dispatch-sync [:rf2-l2cn5d/needs-token]]]}})
       (async/deref-blocking (story/run-variant :story.runner/cofx-missing) 5000)
       (let [final (run-blocking :story.runner/cofx-missing)]
         (is (not= :pass (:status final))
             "the missing provided fact surfaces as a non-pass step outcome")))))

#?(:clj
   (deftest assert-db-equals-pass-and-fail
     (testing ":assert-db pass / fail outcomes against frame app-db. Per
              rf2-5x1wt.19 the runtime consumes the folded plan: a shipping
              :assert-db step is rewritten to the canonical
              [:assert [:rf.assert/path-equals …]] checkpoint, so the
              recorded step type is :assert."
       (rf/reg-event :rt/set-status
         (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
       (story/reg-variant :story.runner/assert-db
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-status :loaded]]
                                    [:assert-db [:status] :loaded]
                                    [:assert-db [:status] :wrong]]}})
       (async/deref-blocking (story/run-variant :story.runner/assert-db) 5000)
       (let [final (run-blocking :story.runner/assert-db)]
         (is (= :fail (:status final)))
         (is (= 1 (:failures final)))
         ;; rf2-5x1wt.19 — folded :assert-db steps run as :assert checkpoints.
         (let [assert-results (filterv #(= :assert (:type %)) (:results final))]
           (is (= 2 (count assert-results)))
           (is (true?  (:passed? (nth assert-results 0))))
           (is (false? (:passed? (nth assert-results 1)))))
         ;; The canonical :rf.assert/path-equals records carry the diagnostics.
         (let [slot (story/read-assertions :story.runner/assert-db)
               pe   (filterv #(= :rf.assert/path-equals (:assertion %)) slot)]
           (is (= 2 (count pe)))
           (is (true?  (:passed? (nth pe 0))))
           (is (false? (:passed? (nth pe 1))))
           (is (= :wrong  (:expected (nth pe 1))))
           (is (= :loaded (:actual   (nth pe 1)))))))))

;; ---- rf2-ee38b.3 / rf2-5x1wt.19: folded assert outcomes reach the slot ---
;;
;; Before rf2-ee38b.3 a failing `:assert-db` / `:assert-dom` step landed
;; ONLY in run-state; the `:rf.story/assertions` app-db slot stayed empty
;; so `run-variant`'s :assertions slot, `assertions-passing?`, the test
;; pane, the inline strip, and the Xray panel all read FALSE-GREEN.
;;
;; rf2-5x1wt.19 — the runtime now consumes the `.18`-folded plan: a shipping
;; `:assert-db` step is rewritten to the canonical `[:assert
;; [:rf.assert/path-equals …]]` checkpoint, whose reg-event handler
;; records the CANONICAL `:rf.assert/path-equals` record on the slot. There
;; is no longer a synthetic `:rf.assert/db` / `:rf.assert/dom` rail — one
;; assertion-record vocabulary.

#?(:clj
   (deftest assert-db-failure-lands-in-assertions-slot
     (testing "a failing folded :assert-db step records a :passed? false
              :rf.assert/path-equals record into :rf.story/assertions so the
              slot consumers + assertions-passing? observe the failure"
       (rf/reg-event :rt/set-status
         (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
       (story/reg-variant :story.bridge/db-fail
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-status :idle]]
                                    [:assert-db [:status] :loaded]]}})
       (async/deref-blocking (story/run-variant :story.bridge/db-fail) 5000)
       (run-blocking :story.bridge/db-fail)
       (let [slot (story/read-assertions :story.bridge/db-fail)
             pe-recs (filterv #(= :rf.assert/path-equals (:assertion %)) slot)]
         (is (= 1 (count pe-recs))
             "the failing folded :assert-db landed exactly one canonical record")
         (is (false? (:passed? (first pe-recs)))
             "the slot record carries :passed? false")
         (is (= :loaded (:expected (first pe-recs))))
         (is (= :idle   (:actual   (first pe-recs))))
         (is (false? (story/assertions-passing? slot))
             "assertions-passing? sees the folded failure")))))

#?(:clj
   (deftest assert-db-pass-lands-in-assertions-slot
     (testing "a passing folded :assert-db step records a :passed? true
              :rf.assert/path-equals entry so the slot is non-empty + passes"
       (rf/reg-event :rt/set-status
         (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
       (story/reg-variant :story.bridge/db-pass
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-status :loaded]]
                                    [:assert-db [:status] :loaded]]}})
       (async/deref-blocking (story/run-variant :story.bridge/db-pass) 5000)
       (run-blocking :story.bridge/db-pass)
       (let [slot    (story/read-assertions :story.bridge/db-pass)
             pe-recs (filterv #(= :rf.assert/path-equals (:assertion %)) slot)]
         (is (= 1 (count pe-recs)))
         (is (true? (:passed? (first pe-recs))))
         (is (true? (story/assertions-passing? slot)))))))

#?(:clj
   (deftest run-variant-result-reflects-rich-dsl-assert-failure
     (testing "the run-variant result map's :assertions slot carries the
              folded failure as a canonical :rf.assert/path-equals record —
              the cljs.test adapter path (assertions-passing? on the result)
              is no longer false-green; and the unified :status is :fail"
       (rf/reg-event :rt/set-status
         (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
       (story/reg-variant :story.bridge/result
         {:events      []
          :play-script {:script [[:dispatch-sync [:rt/set-status :idle]]
                                 [:assert-db [:status] :loaded]]}})
       (let [result (async/deref-blocking
                      (story/run-variant :story.bridge/result) 5000)]
         (is (= :fail (:status result))
             "the unified run-result :status is :fail (rf2-5x1wt.19)")
         (is (some (fn [r] (and (= :rf.assert/path-equals (:assertion r))
                                (false? (:passed? r))))
                   (:assertions result))
             "the result map's :assertions slot includes the failed assertion")
         (is (false? (story/assertions-passing? result))
             "assertions-passing? on the result map flips to false")))))

;; ---- rf2-rkd14: EXACT narrative attribution on the LIVE run path ---------
;;
;; The runtime records each dispatch step's settle boundary
;; (`runner-events/step-boundaries`) and `record-result-map` feeds it to
;; `project-evidence` as `:attribution`, so the unified result's `:narrative`
;; is attributed EXACTLY (`:rf.story/script-idx` stamps) instead of the EVEN
;; forward partition that mis-groups re-dispatch fan-out. The discriminating
;; case: a SECOND dispatch step that re-dispatches — EVEN [2 1] mis-groups
;; the first re-dispatched epoch onto step 0; EXACT keeps the fan-out under
;; the step that produced it.

#?(:clj
   (deftest run-variant-narrative-exact-attribution-of-redispatch-fanout
     (testing "the unified result's :narrative attributes a re-dispatching
              step's fan-out to THAT step's span — EXACT, not the EVEN
              partition that mis-groups it (rf2-rkd14, live run path)"
       (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
       ;; :rkd/c re-dispatches :rkd/d, so step 1 settles to 2 epochs.
       (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
       (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
       (story/reg-variant :story.rkd/redispatch
         {:events      []
          :play-script {:script [[:dispatch-sync [:rkd/a]]
                                  [:dispatch-sync [:rkd/c]]]}})
       (let [result (async/deref-blocking
                      (story/run-variant :story.rkd/redispatch) 5000)
             ;; Group the flattened beats by their owning :step. The leading
             ;; (nil-step) span collects the lifecycle setup-phase epochs the
             ;; runtime commits before the script runs.
             by     (->> (evidence/narrative-beats (:narrative result))
                         (reduce (fn [m {:keys [step trigger-event]}]
                                   (update m step (fnil conj []) trigger-event))
                                 {}))]
         (is (= :pass (:status result)))
         ;; The two authored steps committed THREE script epochs (a, c, c's
         ;; re-dispatch d) — the rest of the tape is the leading setup phase.
         (let [script-beats (concat (get by [:dispatch-sync [:rkd/a]])
                                    (get by [:dispatch-sync [:rkd/c]]))]
           (is (= [[:rkd/a] [:rkd/c] [:rkd/d]] (vec script-beats))
               "the three script epochs land under the two authored steps"))
         ;; EXACT: step 0 owns ONLY :rkd/a; step 1 owns :rkd/c AND its
         ;; re-dispatched :rkd/d (the fan-out attaches to the producing step).
         (is (= [[:rkd/a]] (get by [:dispatch-sync [:rkd/a]]))
             "step 0's span holds ONLY its own leaf epoch")
         (is (= [[:rkd/c] [:rkd/d]] (get by [:dispatch-sync [:rkd/c]]))
             "step 1's span holds its dispatch AND its re-dispatch — EXACT")
         ;; RED-before pin: the EVEN partition (5 script-attributable epochs
         ;; split [3 2] front-loaded, or any forward split) front-loads
         ;; :rkd/c onto step 0; EXACT keeps :rkd/c under step 1.
         (is (not= [[:rkd/a] [:rkd/c]] (get by [:dispatch-sync [:rkd/a]]))
             "step 0 does NOT swallow step 1's epoch (the EVEN mis-grouping)")
         ;; The leading setup epochs are NOT mis-attributed to step 0 — they
         ;; lead under the nil span (the EXACT model's leading-setup behaviour;
         ;; the EVEN partition would have front-loaded them onto step 0).
         (is (seq (get by nil))
             "the setup-phase epochs lead under the nil span — not step 0")))))

#?(:clj
   (deftest run-variant-narrative-stamp-does-not-perturb-run-hash
     (testing "the :rf.story/script-idx stamp is stripped by the determinism
              projection — the EXACT-attributed result's run-hash equals its
              narrative-free baseline, and the :epoch-tape slot stays raw
              (rf2-rkd14 determinism guard)"
       (rf/reg-event :rkd/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
       (rf/reg-event :rkd/c (fn [_ _] {:fx [[:dispatch [:rkd/d]]]}))
       (rf/reg-event :rkd/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
       (story/reg-variant :story.rkd/hash
         {:events      []
          :play-script {:script [[:dispatch-sync [:rkd/a]]
                                  [:dispatch-sync [:rkd/c]]]}})
       (let [result    (async/deref-blocking
                         (story/run-variant :story.rkd/hash) 5000)
             tape-keys (into #{} (mapcat keys) (:epoch-tape result))]
         (is (not (contains? tape-keys :rf.story/script-idx))
             "the retained :epoch-tape slot is the RAW tape (no stamp leak)")
         (is (= (fingerprint/run-hash result)
                (fingerprint/run-hash (dissoc result :narrative)))
             "dropping the stamped :narrative does not change the run-hash")))))

;; ---- rf2-76l69l: multi-play auto-run attribution spans EVERY play --------
;;
;; The unified result's :narrative spans the CONCATENATED auto-run play
;; scripts. The per-play settle boundaries MUST accumulate across the whole
;; sequence — the sequencer clears once up front and each play APPENDS its
;; absolute boundaries (the append-only epoch tape is never reset between
;; plays). Before the fix every `run!` cleared the boundaries on entry, so
;; after two+ auto-run plays only the LAST play's boundaries survived; the
;; concatenated-script narrative then mis-attributed later-play effects to
;; earlier-play steps while earlier effects appeared as unattributed setup —
;; a green run with false provenance.

#?(:clj
   (deftest run-variant-multi-play-narrative-attributes-every-play-step
     (testing "two :auto-run? plays (the second re-dispatching) attribute each
              epoch beat to its OWN step across the concatenated script —
              earlier-play effects are NOT lost to setup, later-play effects
              are NOT mis-credited to earlier steps (rf2-76l69l)"
       (rf/reg-event :ml/a (fn [{:keys [db]} _] {:db (assoc db :a true)}))
       (rf/reg-event :ml/b (fn [{:keys [db]} _] {:db (assoc db :b true)}))
       ;; :ml/c re-dispatches :ml/d, so the second play's second step settles
       ;; to two epochs — the discriminating re-dispatch the bead calls for.
       (rf/reg-event :ml/c (fn [_ _] {:fx [[:dispatch [:ml/d]]]}))
       (rf/reg-event :ml/d (fn [{:keys [db]} _] {:db (assoc db :d true)}))
       (story/reg-variant :story.ml/two-plays
         {:events []
          :plays  [{:name "alpha" :auto-run? true
                    :script [[:dispatch-sync [:ml/a]]
                             [:dispatch-sync [:ml/b]]]}
                   {:name "beta" :auto-run? true
                    :script [[:dispatch-sync [:ml/c]]]}]})
       (let [result (async/deref-blocking
                      (story/run-variant :story.ml/two-plays) 5000)
             by     (->> (evidence/narrative-beats (:narrative result))
                         (reduce (fn [m {:keys [step trigger-event]}]
                                   (update m step (fnil conj []) trigger-event))
                                 {}))]
         (is (= :pass (:status result)))
         ;; alpha's two steps own exactly their own leaf epochs…
         (is (= [[:ml/a]] (get by [:dispatch-sync [:ml/a]]))
             "play alpha step 0 owns ONLY :ml/a")
         (is (= [[:ml/b]] (get by [:dispatch-sync [:ml/b]]))
             "play alpha step 1 owns ONLY :ml/b — NOT lost to the setup span")
         ;; …and beta's re-dispatching step owns its dispatch AND its fan-out.
         (is (= [[:ml/c] [:ml/d]] (get by [:dispatch-sync [:ml/c]]))
             "play beta's step owns its dispatch AND its re-dispatch — EXACT")
         ;; RED-before pin: per-play clearing kept only beta's single boundary,
         ;; which the concatenated script (3 dispatch steps) zipped onto step 0
         ;; (alpha's :ml/a) — so :ml/c/:ml/d effects landed under :ml/a and the
         ;; later steps held nothing. Assert that mis-grouping is gone.
         (is (not (contains? (set (get by [:dispatch-sync [:ml/a]])) [:ml/c]))
             "beta's :ml/c is NOT mis-attributed to alpha's first step")
         (is (not (contains? (set (get by [:dispatch-sync [:ml/a]])) [:ml/d]))
             "beta's re-dispatched :ml/d is NOT mis-attributed to alpha's step")))))

#?(:clj
   (deftest assert-dom-skipped-on-jvm-is-cannot-run
     (testing "a no-DOM :assert-dom step (JVM) records NO slot pass — it
              folds to :rf.assert/dom-visible, is evaluated by the DOM
              executor (no DOM → skipped), and the run is :cannot-run, not a
              false-green pass (rf2-5x1wt.19, spec/017 §`:cannot-run`)"
       (story/reg-variant :story.bridge/dom-skip
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:assert-dom "div.foo" :visible]]}})
       (async/deref-blocking (story/run-variant :story.bridge/dom-skip) 5000)
       (let [final (run-blocking :story.bridge/dom-skip)
             slot  (story/read-assertions :story.bridge/dom-skip)]
         (is (= :cannot-run (:status final))
             "a DOM-skip-only run is :cannot-run, not :pass or :fail")
         (is (empty? (filterv #(true? (:passed? %)) slot))
             "a skipped (no-DOM) :assert-dom contributes no passing record")))))

#?(:clj
   (deftest assert-dom-skipped-unified-result-is-cannot-run
     (testing "the UNIFIED run-result (story/run-variant's resolved value),
              not just run-state, reads :cannot-run for a DOM-skip-only
              variant on the headless JVM (rf2-q5jw4, spec/017 §Unified run
              result). Before the fix record-result-map dropped the
              run-state's :cannot-run refusals: a no-DOM [:assert-dom …] skip
              records NO :rf.story/assertions entry, so result/run-result
              aggregated zero records + a clean tape to :pass (vacuous
              green) while run-state correctly read :cannot-run — the exact
              consumer-disagreement false-GREEN .19 set out to kill. This
              exercises the unified-result PATH (the existing
              assert-dom-skipped-on-jvm-is-cannot-run test discards the
              result and checks only run-blocking's run-state)."
       (story/reg-variant :story.bridge/dom-skip-unified
         {:events      []
          :play-script {:script [[:assert-dom "div.foo" :visible]]}})
       (let [result (async/deref-blocking
                      (story/run-variant :story.bridge/dom-skip-unified) 5000)]
         (is (= :cannot-run (:status result))
             "the unified result :status is :cannot-run, NOT a vacuous :pass")
         (is (seq (:cannot-run result))
             "the unified result surfaces the run-state's :cannot-run refusals")
         (is (false? (story/result-passed? result))
             "story/result-passed? on the unified result is false — a
              :cannot-run run proved nothing, so it is never a silent pass")
         (is (empty? (filterv #(true? (:passed? %)) (:assertions result)))
             "no assertion record reads :passed? true — the skip is not
              folded into a green record")))))

#?(:clj
   (deftest assert-db-pred-form
     (testing ":assert-db :pred folds to :rf.assert/path-matches [:fn sym];
              the symbol resolves at validation time (rf2-5x1wt.19)"
       (rf/reg-event :rt/set-n
         (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
       (story/reg-variant :story.runner/pred
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred 'clojure.core/pos?]
                                    [:assert-db [:n] :pred 'clojure.core/neg?]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred) 5000)
       (let [final  (run-blocking :story.runner/pred)
             ;; folded :assert-db :pred → [:assert [:rf.assert/path-matches …]]
             results (filterv #(= :assert (:type %)) (:results final))
             pm      (filterv #(= :rf.assert/path-matches (:assertion %))
                              (story/read-assertions :story.runner/pred))]
         (is (= :fail (:status final)))
         (is (= 2 (count results)))
         (is (true?  (:passed? (nth pm 0))) "pos? against 7 passes")
         (is (false? (:passed? (nth pm 1))) "neg? against 7 fails")))))

#?(:clj
   (deftest assert-db-pred-fn-direct
     (testing ":assert-db :pred accepts a fn directly (rf2-inbad —
              advanced-CLJS-safe); it folds to :rf.assert/path-matches
              [:fn fn] which Malli validates by calling the fn (rf2-5x1wt.19)"
       (rf/reg-event :rt/set-n
         (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
       (story/reg-variant :story.runner/pred-fn
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred pos?]
                                    [:assert-db [:n] :pred neg?]
                                    [:assert-db [:n] :pred (fn [x] (= x 7))]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred-fn) 5000)
       (let [final   (run-blocking :story.runner/pred-fn)
             pm      (filterv #(= :rf.assert/path-matches (:assertion %))
                              (story/read-assertions :story.runner/pred-fn))]
         (is (= :fail (:status final))
             "neg? against 7 fails — overall status is :fail")
         (is (= 3 (count pm)))
         (is (true?  (:passed? (nth pm 0))) "pos? against 7 passes")
         (is (false? (:passed? (nth pm 1))) "neg? against 7 fails")
         (is (true?  (:passed? (nth pm 2))) "anonymous fn passes")))))

#?(:clj
   (deftest assert-db-pred-bogus-symbol-fails-gracefully
     (testing ":assert-db :pred with an unresolvable symbol fails gracefully:
              it folds to :rf.assert/path-matches [:fn 'bogus]; the symbol
              cannot resolve, so the assertion reports a readable failure
              rather than an opaque sci error (rf2-5x1wt.19)"
       (rf/reg-event :rt/set-n
         (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
       (story/reg-variant :story.runner/pred-bogus
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/set-n 7]]
                                    [:assert-db [:n] :pred 'no.such.ns/missing-pred]]}})
       (async/deref-blocking (story/run-variant :story.runner/pred-bogus) 5000)
       (let [final (run-blocking :story.runner/pred-bogus)
             pm    (filterv #(= :rf.assert/path-matches (:assertion %))
                            (story/read-assertions :story.runner/pred-bogus))]
         (is (= :fail (:status final)))
         (is (= 1 (count pm)))
         (is (false? (:passed? (first pm))))))))

#?(:clj
   (deftest run-records-results-in-order
     (testing "results vector reflects step order"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
       (story/reg-variant :story.runner/order
         {:events []
          :play-script
          {:auto-run? false
           :script [[:dispatch-sync [:rt/touch]]
                    [:dispatch-sync [:rt/touch]]
                    [:dispatch-sync [:rt/touch]]
                    [:assert-db [:touches] 3]]}})
       (async/deref-blocking (story/run-variant :story.runner/order) 5000)
       (let [final (run-blocking :story.runner/order)]
         (is (= :pass (:status final)))
         (is (= 4 (count (:results final))))
         (is (= [0 1 2 3] (mapv :idx (:results final))))))))

;; ---- rf2-vkdam: the public driver OWNS the boundary reset ----------------
;;
;; `record-settle-boundary!` APPENDS a per-dispatch-step settle boundary onto
;; the process-global `step-boundaries` atom (keyed by frame-id). Previously
;; only the orchestrator (`runtime/run-phase-4!`) cleared, so a non-orchestrator
;; re-run via the public `run!` (interactive Re-run / replay-in-place) kept
;; accumulating boundaries until teardown — a latent hazard for any future
;; consumer reading `settle-boundaries` after such a run (stale offsets →
;; mis-attributed narrative). `run!` now resets the frame's boundaries at the
;; SAME entry that writes them, so two consecutive public `run!`s leave exactly
;; ONE run's worth — not the doubled accumulation.

#?(:clj
   (deftest run-resets-step-boundaries-public-driver
     (testing "two consecutive public `run!`s of the same play leave the
              frame's settle-boundaries reset to THIS run's worth — `run!`
              owns the reset, so the count does not accumulate (rf2-vkdam)"
       (rf/reg-event :vk/touch
         (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
       (story/reg-variant :story.vkdam/rerun
         {:events []
          :play-script {:auto-run? false
                        :script [[:dispatch-sync [:vk/touch]]
                                 [:dispatch-sync [:vk/touch]]
                                 [:dispatch-sync [:vk/touch]]]}})
       ;; Allocate the frame so `run!` has a live frame to dispatch into.
       (async/deref-blocking (story/run-variant :story.vkdam/rerun) 5000)
       ;; First public run via `run!` (NOT the orchestrator).
       (run-blocking :story.vkdam/rerun)
       (let [after-first (count (re/settle-boundaries :story.vkdam/rerun))]
         (is (= 3 after-first)
             "three dispatch steps record three boundaries")
         ;; Second public run — an interactive Re-run. WITHOUT the reset this
         ;; would accumulate to six; WITH it, `run!` clears at start so the
         ;; frame again holds exactly THIS run's three boundaries.
         (run-blocking :story.vkdam/rerun)
         (is (= 3 (count (re/settle-boundaries :story.vkdam/rerun)))
             "the public driver reset its boundaries — no stale accumulation")))))

;; ---- rf2-96qsjr: narrative attribution survives epoch-ring eviction ------
;;
;; End-to-end sibling of the pure `evidence-test` stamp-tape gates: a REAL
;; run, through the orchestrator (`story/run-variant`), whose dispatch-step
;; count exceeds a small configured epoch-history ring depth. Proves the
;; producer (`runner-events/last-epoch-id`) and the consumer
;; (`runtime/record-result-map` + `evidence/stamp-tape`) agree end-to-end —
;; not just that the pure fns compose correctly in isolation.

#?(:clj
   (deftest narrative-attribution-survives-epoch-ring-eviction
     (testing "rf2-96qsjr: five dispatch steps against a depth-3 epoch-
              history ring still attribute each SURVIVING epoch to its
              correct originating script step — the boundary bookkeeping
              records the framework's genuine monotonic :epoch-id (never
              plateaus), so ring eviction can only ever drop beats, never
              misattribute a surviving one"
       (try
         (epoch/configure! {:depth 3})
         (rf/reg-event :re-eviction/set
           (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
         (story/reg-variant :story.runner/eviction
           {:events []
            :play-script {:script [[:dispatch-sync [:re-eviction/set 1]]
                                   [:dispatch-sync [:re-eviction/set 2]]
                                   [:dispatch-sync [:re-eviction/set 3]]
                                   [:dispatch-sync [:re-eviction/set 4]]
                                   [:dispatch-sync [:re-eviction/set 5]]]}})
         (let [result    (async/deref-blocking (story/run-variant :story.runner/eviction) 5000)
               narrative (:narrative result)
               span-for  (fn [want]
                           (some #(when (= [:dispatch-sync [:re-eviction/set want]] (:step %)) %)
                                 narrative))]
           (is (= 5 (:n (:app-db result)))
               "sanity: the run itself executed every step (the LAST
                :set wins on app-db) — attribution below is what's under
                test, not whether the run happened")
           (is (= [] (mapv :trigger-event (:epochs (span-for 1))))
               "step 0's own epoch (n=1) was evicted by the depth-3 ring
                — no beats, but critically NOT a later step's epoch
                misattributed here")
           (is (= [] (mapv :trigger-event (:epochs (span-for 2))))
               "step 1's own epoch (n=2) was likewise evicted")
           (is (= [[:re-eviction/set 3]] (mapv :trigger-event (:epochs (span-for 3))))
               "step 2's surviving epoch is attributed to step 2 — NOT
                step 0, which is where the old ring-length-snapshot
                boundary scheme (plateaued at the ring depth) would have
                shifted it")
           (is (= [[:re-eviction/set 4]] (mapv :trigger-event (:epochs (span-for 4))))
               "step 3's surviving epoch is attributed to step 3")
           (is (= [[:re-eviction/set 5]] (mapv :trigger-event (:epochs (span-for 5))))
               "step 4's surviving epoch is attributed to step 4"))
         (finally
           ;; `:depth` is a process-global epoch-history knob — restore
           ;; the framework default so it doesn't leak into sibling tests.
           (epoch/configure! {:depth 50}))))))

#?(:clj
   (deftest bare-vector-with-unknown-event-still-dispatches
     (testing "a bare event vector with no registered handler still dispatches; the play status does not fail because dispatch is a no-assertion step"
       (story/reg-variant :story.runner/bare-unknown
         {:events []
          :play-script {:auto-run? false
                        :script    [[:does-not-exist :nope]]}})
       (async/deref-blocking (story/run-variant :story.runner/bare-unknown) 5000)
       (let [final (run-blocking :story.runner/bare-unknown)]
         (is (some? final))
         (is (= 1 (count (:results final))))))))

;; ---- variant-play-script resolution --------------------------------------

#?(:clj
   (deftest variant-play-script-from-body
     (testing "variant-play-script resolves the :play-script slot on a variant"
       (story/reg-variant :story.runner/resolved
         {:events []
          :play-script {:script [[:dispatch [:foo]]]
                        :auto-run? false
                        :name "named"}})
       (let [spec (re/variant-play-script :story.runner/resolved)]
         (is (= [[:dispatch [:foo]]] (:script spec)))
         (is (false? (:auto-run? spec)))
         (is (= "named" (:name spec)))))))

#?(:clj
   (deftest variant-play-script-missing
     (testing "variants without :play-script resolve to an empty spec"
       (story/reg-variant :story.runner/no-script {:events []})
       (let [spec (re/variant-play-script :story.runner/no-script)]
         (is (= [] (:script spec)))
         (is (true? (:auto-run? spec)))))))

;; ---- auto-run gating ------------------------------------------------------

#?(:clj
   (deftest auto-run-skips-when-disabled
     (testing "auto-run! is a no-op when :auto-run? is false"
       (let [seen (atom 0)]
         (rf/reg-event :rt/touch
           (fn [{:keys [db]} _] (swap! seen inc) {:db db}))
         (story/reg-variant :story.runner/no-auto
           {:events []
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/touch]]]}})
         (async/deref-blocking (story/run-variant :story.runner/no-auto) 5000)
         (re/auto-run! :story.runner/no-auto)
         (is (zero? @seen))))))

;; ---- run-state lifecycle ----------------------------------------------

#?(:clj
   (deftest run-state-clears-and-resets
     (testing "successive runs reset :results and re-walk every step"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
       (story/reg-variant :story.runner/reset
         {:events []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]
                                    [:assert-db [:touches] 1]]}})
       (async/deref-blocking (story/run-variant :story.runner/reset) 5000)
       (run-blocking :story.runner/reset)
       (let [first-state (re/current-state :story.runner/reset)]
         (is (= :pass (:status first-state))))
       (run-blocking :story.runner/reset)
       (let [second-state (re/current-state :story.runner/reset)]
         (is (= :fail (:status second-state)))
         (is (= 1 (:failures second-state)))
         (is (= 2 (count (:results second-state))))))))

;; ---- trace integration --------------------------------------------------

#?(:clj
   (deftest each-step-emits-a-trace-event
     (testing "the runner emits a :rf.story.play/step trace event per step"
       (let [trace-events (atom [])
             listener-id  ::play-trace-test]
         (rf/reg-event :rt/touch
           (fn [{:keys [db]} _] {:db (update db :touches (fnil inc 0))}))
         (try
           (require '[re-frame.trace.tooling :as trace-tooling])
           (let [reg!  (resolve 're-frame.trace.tooling/register-listener!)
                 unreg (resolve 're-frame.trace.tooling/unregister-listener!)]
             (reg! listener-id
               (fn [ev]
                 (when (= :rf.story.play/step (:operation ev))
                   (swap! trace-events conj ev))))
             (story/reg-variant :story.runner/trace
               {:events []
                :play-script {:auto-run? false
                              :script    [[:dispatch-sync [:rt/touch]]
                                          [:assert-db [:touches] 1]]}})
             (async/deref-blocking (story/run-variant :story.runner/trace) 5000)
             (run-blocking :story.runner/trace)
             (is (>= (count @trace-events) 2)
                 "at least one trace per step landed on the bus")
             (let [first-ev (first @trace-events)]
               (is (= :rf.story.play/step (:operation first-ev)))
               (is (= :story.runner/trace (get-in first-ev [:tags :frame]))))
             (unreg listener-id))
           (finally
             (try
               (let [unreg (resolve 're-frame.trace.tooling/unregister-listener!)]
                 (when unreg (unreg listener-id)))
               (catch Throwable _ nil))))))))

;; ---- DOM-step skip on JVM (no DOM available) ----------------------------

#?(:clj
   (deftest dom-step-skipped-on-jvm
     (testing "DOM-touching steps record :skipped? on JVM (no DOM); a run
              whose only unmet steps are no-DOM skips is :cannot-run — the
              distinct THIRD status, not a fail or a silent pass
              (rf2-5x1wt.19, spec/017 §`:cannot-run`)"
       (story/reg-variant :story.runner/dom
         {:events []
          :play-script {:auto-run? false
                        :script    [[:click "button.foo"]
                                    [:assert-dom "div" :visible]]}})
       (async/deref-blocking (story/run-variant :story.runner/dom) 5000)
       (let [final   (run-blocking :story.runner/dom)
             results (:results final)]
         (is (= :cannot-run (:status final))
             "no-DOM click + assert-dom → :cannot-run (a refusal, not a fail)")
         (is (every? (fn [r] (or (:skipped? r) (true? (:passed? r)))) results))))))

;; ---- multi-play (rf2-tl7zk) ----------------------------------------------

#?(:clj
   (defn- run-play-blocking
     "Drive a specific play via `run-play!` and block until terminal."
     ([variant-id play-key] (run-play-blocking variant-id play-key 5000))
     ([variant-id play-key timeout-ms]
      (let [done (atom nil)]
        (re/run-play! variant-id play-key (fn [s] (reset! done s)))
        (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
          (loop []
            (cond
              @done @done
              (> (System/currentTimeMillis) deadline)
              (throw (ex-info "run-play-blocking timeout"
                              {:variant-id variant-id
                               :play-key   play-key}))
              :else (do (Thread/sleep 5) (recur)))))))))

#?(:clj
   (deftest variant-plays-resolves-plays-vector
     (testing "variant-plays returns a vector of parsed plays"
       (rf/reg-event :rt/inc
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/two
         {:events []
          :plays  [{:name "happy"
                    :auto-run? false
                    :script    [[:dispatch-sync [:rt/inc]]
                                [:assert-db [:n] 1]]}
                   {:name "error"
                    :auto-run? false
                    :script    [[:dispatch-sync [:rt/inc]]
                                [:assert-db [:n] 99]]}]})
       (let [plays (re/variant-plays :story.multi/two)]
         (is (= 2 (count plays)))
         (is (= ["happy" "error"] (mapv :name plays)))
         ;; First play's auto-run? was explicitly false here, so no
         ;; positional default applies.
         (is (every? false? (map :auto-run? plays)))))))

#?(:clj
   (deftest variant-plays-wraps-legacy-play-script
     (testing "variant-plays wraps a legacy :play-script body in a one-entry vector"
       (story/reg-variant :story.multi/legacy
         {:events []
          :play-script {:name "lone" :script [[:dispatch [:a]]]}})
       (let [plays (re/variant-plays :story.multi/legacy)]
         (is (= 1 (count plays)))
         (is (= "lone" (:name (first plays))))))))

#?(:clj
   (deftest run-play-keys-state-per-play
     (testing "running each play stores state under [variant-id play-key]"
       (rf/reg-event :rt/inc
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/keyed
         {:events []
          :plays  [{:name "first"  :auto-run? false
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 1]]}
                   {:name "second" :auto-run? false
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 2]]}]})
       (async/deref-blocking (story/run-variant :story.multi/keyed) 5000)
       (let [first-state  (run-play-blocking :story.multi/keyed "first")
             second-state (run-play-blocking :story.multi/keyed "second")]
         (is (= :pass (:status first-state)))
         (is (= :pass (:status second-state)))
         ;; Per-play state preserved.
         (is (= :pass (:status (re/current-state-for-play :story.multi/keyed "first"))))
         (is (= :pass (:status (re/current-state-for-play :story.multi/keyed "second"))))
         ;; The latest run-state slot tracks the most recent run.
         (let [latest (re/current-state :story.multi/keyed)]
           (is (= :pass (:status latest)))
           (is (= "second" (:play-key latest))))))))

#?(:clj
   (deftest run-play-sets-active-play
     (testing "run-play! also sets the active-play key the toolbar reads"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/active
         {:events []
          :plays  [{:name "alpha" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}
                   {:name "beta"  :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/active) 5000)
       (run-play-blocking :story.multi/active "beta")
       (is (= "beta" (re/active-play-key :story.multi/active))))))

#?(:clj
   (deftest select-play-without-running
     (testing "select-play! changes the active key but does not run"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/select
         {:events []
          :plays  [{:name "one" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}
                   {:name "two" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/select) 5000)
       (re/select-play! :story.multi/select "two")
       (is (= "two" (re/active-play-key :story.multi/select)))
       ;; No run-state slot was populated by select-play! alone.
       (is (nil? (re/current-state-for-play :story.multi/select "two"))))))

#?(:clj
   (deftest run-all-plays-sequences-runs
     (testing "run-all-plays! drives every play and resolves with per-play results"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/all
         {:events []
          :plays  [{:name "a" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 1]]}
                   {:name "b" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 2]]}
                   {:name "c" :auto-run? false
                    :script [[:dispatch-sync [:rt/touch]]
                             [:assert-db [:n] 3]]}]})
       (async/deref-blocking (story/run-variant :story.multi/all) 5000)
       (let [done    (atom nil)
             _       (re/run-all-plays! :story.multi/all
                                        (fn [final] (reset! done final)))
             deadline (+ (System/currentTimeMillis) 5000)]
         (loop []
           (cond
             (some? @done) nil
             (> (System/currentTimeMillis) deadline)
             (throw (ex-info "run-all-plays timeout" {}))
             :else (do (Thread/sleep 5) (recur))))
         (let [results @done]
           (is (= 3 (count results)))
           (is (every? #(= :pass (:status %)) results))
           (is (= ["a" "b" "c"] (mapv :play-key results))))))))

#?(:clj
   (deftest auto-run-multi-runs-only-opted-in-plays
     (testing "auto-run! runs only plays whose :auto-run? is true (per-position defaults)"
       (rf/reg-event :rt/inc
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.multi/auto
         {:events []
          :plays  [{:name "first-default-true"
                    ;; :auto-run? omitted → first defaults to true
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 1]]}
                   {:name "second-default-false"
                    :script [[:dispatch-sync [:rt/inc]]
                             [:assert-db [:n] 99]]}
                   {:name "third-opted-in"
                    :auto-run? true
                    :script [[:dispatch-sync [:rt/inc]]]}]})
       (async/deref-blocking (story/run-variant :story.multi/auto) 5000)
       (let [done (atom nil)]
         ;; Multi-play auto-run sequences the opted-in plays + resolves
         ;; the done-cb ONCE with the per-play terminal-state vector.
         (re/auto-run! :story.multi/auto (fn [final] (reset! done final)))
         (let [deadline (+ (System/currentTimeMillis) 5000)]
           (loop []
             (cond
               (some? @done) nil
               (> (System/currentTimeMillis) deadline)
               (throw (ex-info "auto-run multi timeout" {:done @done}))
               :else (do (Thread/sleep 5) (recur)))))
         (let [results @done]
           (is (= 2 (count results))
               "first + third auto-ran; second did not")
           (is (= ["first-default-true" "third-opted-in"]
                  (mapv :play-key results))))
         ;; second play was NOT auto-run — its state slot stays empty.
         (is (nil? (re/current-state-for-play :story.multi/auto
                                              "second-default-false")))))))

#?(:clj
   (deftest mutual-exclusion-warning-emits-once
     (testing "variants declaring BOTH :play-script and :plays warn ONCE per variant"
       ;; Bypass schema validation by writing directly into the side-table.
       (require '[re-frame.story.registrar :as registrar])
       (let [registrar (resolve 're-frame.story.registrar/kind->id->body)]
         (swap! @registrar assoc-in
                [:variant :story.multi/both]
                {:play-script [[:dispatch [:legacy]]]
                 :plays       [{:name "p" :script [[:dispatch [:plays]]]}]})
         ;; First read warns; the call returns the :plays-derived plays vector.
         (let [out1 (with-out-str
                      (binding [*err* *out*]
                        (re/variant-plays :story.multi/both)))
               out2 (with-out-str
                      (binding [*err* *out*]
                        (re/variant-plays :story.multi/both)))]
           (is (re-find #":play-script.*:plays" out1)
               "first read prints the both-slots warning")
           (is (empty? out2)
               "subsequent reads stay silent — warning is one-shot per variant"))))))

;; rf2-a1lvd — the one-shot warning cache (`warned-both-slots`) is re-armed
;; by `clear-all-runs!` (the test-fixture path). Without the reset, a prior
;; test (or a prior hot-reload session) that warned for a variant id would
;; suppress the warning forever after. Verify the full re-arm cycle: warn
;; fires → clear-all-runs! → warn fires AGAIN.
#?(:clj
   (deftest both-slots-warning-re-arms-after-clear-all-runs
     (testing "clear-all-runs! resets warned-both-slots so the both-slots
               warning fires again for a previously-warned variant"
       (require '[re-frame.story.registrar :as registrar])
       (let [registrar (resolve 're-frame.story.registrar/kind->id->body)]
         (swap! @registrar assoc-in
                [:variant :story.multi/both-rearm]
                {:play-script [[:dispatch [:legacy]]]
                 :plays       [{:name "p" :script [[:dispatch [:plays]]]}]})
         (let [warn1 (with-out-str
                       (binding [*err* *out*]
                         (re/variant-plays :story.multi/both-rearm)))
               ;; one-shot in effect: a second read before the clear is silent
               silent (with-out-str
                        (binding [*err* *out*]
                          (re/variant-plays :story.multi/both-rearm)))
               _      (re/clear-all-runs!)
               ;; after the reset the suppression set is empty → warns again
               warn2  (with-out-str
                        (binding [*err* *out*]
                          (re/variant-plays :story.multi/both-rearm)))]
           (is (re-find #":play-script.*:plays" warn1)
               "first read warns")
           (is (empty? silent)
               "second read before the clear stays silent — one-shot holds")
           (is (re-find #":play-script.*:plays" warn2)
               "after clear-all-runs! the warning re-arms and fires again"))))))

;; ---- rf2-ftow6: concurrent-run race fix ---------------------------------
;;
;; The Playwright matrix-before-load scenario could overshoot counter
;; increments when `runtime/run-phase-4!` and the shell's
;; `selection-watcher` both fired `runner-events/run!` against the same
;; variant in quick succession. The blanket setTimeout-0 between every
;; step let the second `run!` reset state mid-script, and BOTH loops
;; then walked the shared state, double-dispatching the increment
;; events. The fix stamps a unique `:run-token` on every fresh run and
;; bails any loop whose token no longer matches the state slot — see
;; `run-loop!`.

#?(:clj
   (deftest run-stamps-token-on-state
     (testing "run! writes a :run-token onto the started state map so
              concurrent-run detection can compare loops"
       (rf/reg-event :rt/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.runner/token
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]]}})
       (async/deref-blocking (story/run-variant :story.runner/token) 5000)
       (let [final (run-blocking :story.runner/token)]
         (is (some? (:run-token final))
             "every run! call stamps a non-nil token onto the started state")))))

#?(:clj
   (deftest fresh-run-token-replaces-prior
     (testing "back-to-back run! calls stamp DIFFERENT tokens — the newer
              token wins and the stale loop (had it still been queued)
              would bail on mismatch"
       (rf/reg-event :rt/touch (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.runner/token-rotate
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]]}})
       (async/deref-blocking (story/run-variant :story.runner/token-rotate) 5000)
       (let [first-final  (run-blocking :story.runner/token-rotate)
             _            (run-blocking :story.runner/token-rotate)
             second-state (re/current-state :story.runner/token-rotate)]
         (is (some? (:run-token first-final)))
         (is (some? (:run-token second-state)))
         (is (not= (:run-token first-final) (:run-token second-state))
             "the second run! stamps a fresh token — concurrent stale loops
              detect the swap and abort")))))

#?(:clj
   (deftest sync-steps-do-not-yield-between
     (testing "rf2-ftow6: a script of pure :dispatch-sync + :assert-db steps
              runs end-to-end without intermediate yields — no extra event
              dispatches sneak in mid-script. Probes the legacy execute-play!
              semantics the migration accidentally lost."
       (let [n (atom 0)]
         (rf/reg-event :rt/inc (fn [{:keys [db]} _] (swap! n inc) {:db (update db :n (fnil inc 0))}))
         (story/reg-variant :story.runner/sync-tight
           {:events      [[:rt/inc] [:rt/inc] [:rt/inc]] ; seed: n=3
            :play-script {:auto-run? false
                          :script    [[:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]
                                      [:dispatch-sync [:rt/inc]]
                                      [:assert-db [:n] 6]]}})
         (async/deref-blocking (story/run-variant :story.runner/sync-tight) 5000)
         (let [final (run-blocking :story.runner/sync-tight)]
           (is (= :pass (:status final))
               "the three increments + assertion run atomically — final count is exactly 6")
           (is (= 6 @n)
               "no stray increments leaked from concurrent paths"))))))

;; ---- tape-evaluated in-script [:assert …] checkpoints (rf2-8y47c + fh7g4) --
;;
;; An in-script `[:assert [:rf.assert/schema-error …]]` /
;; `[:assert [:rf.assert/caused …]]` / `[:assert [:rf.assert/no-cascade-
;; rerender …]]` checkpoint names a TAPE-EVALUATED assertion family: these
;; ids carry NO `reg-event` handler — the result boundary mints their
;; verdict against the epoch tape (`re-frame.story.result`). Before the
;; `tape-evaluated-assertion?` guard, `exec-assert!` special-cased ONLY the
;; DOM family, so every other id (schema-error / causal) fell through to
;; `boundary/dispatch-and-settle!` — i.e. it was DISPATCHED into the frame.
;; With no handler that hit `re-frame.router.diagnostics/handle-no-handler!`
;; → a spurious `:rf.error/no-such-handler` error trace on the tape (which
;; can trip `evidence/tape-shows-failure?` into a false `:fail`), AND the
;; real tape evaluation was skipped. The fix routes these to a no-op
;; step-skip — the result boundary still owns the verdict.
;;
;; These tests drive the in-script checkpoint end-to-end and assert (a) NO
;; `:rf.error/no-such-handler` trace lands, and (b) the checkpoint records a
;; no-assertion step-skip (`:passed? nil`) — never a dispatch.

#?(:clj
   (defn- run-capturing-no-handler-errors
     "Drive `variant-id` to terminal with a trace listener that collects any
     `:rf.error/no-such-handler` events targeting the variant's frame.
     Returns `[final-state no-handler-events]`."
     [variant-id]
     (require '[re-frame.trace.tooling :as trace-tooling])
     (let [reg!      (resolve 're-frame.trace.tooling/register-listener!)
           unreg     (resolve 're-frame.trace.tooling/unregister-listener!)
           collected (atom [])
           lid       ::no-handler-capture]
       (reg! lid
             (fn [ev]
               (when (and (= :rf.error/no-such-handler (:operation ev))
                          (= variant-id (get-in ev [:tags :frame])))
                 (swap! collected conj ev))))
       (try
         (async/deref-blocking (story/run-variant variant-id) 5000)
         [(run-blocking variant-id) @collected]
         (finally (when unreg (unreg lid)))))))

#?(:clj
   (deftest in-script-schema-error-checkpoint-is-tape-evaluated-not-dispatched
     (testing "an in-script [:assert [:rf.assert/schema-error …]] checkpoint
              is recorded as a no-op step-skip — NOT dispatched into the
              frame — so no :rf.error/no-such-handler trace lands (rf2-8y47c)"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.tape/schema-error
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]
                                    [:assert [:rf.assert/schema-error
                                              {:where :event :event :rt/touch}]]]}})
       (let [[final no-handler] (run-capturing-no-handler-errors
                                  :story.tape/schema-error)
             checkpoint (->> (:results final)
                             (filter #(= :assert (:type %)))
                             first)]
         (is (empty? no-handler)
             "NO :rf.error/no-such-handler trace fired — the schema-error
              atom was NOT dispatched into the frame")
         (is (some? checkpoint) "the [:assert …] checkpoint produced a result")
         (is (nil? (:passed? checkpoint))
             "the tape-evaluated checkpoint is a no-op step-skip — the result
              boundary owns its verdict, so it neither passes nor fails here")
         (is (empty? (filterv #(= :rf.assert/schema-error (:assertion %))
                              (story/read-assertions :story.tape/schema-error)))
             "no :rf.assert/schema-error slot record was minted by a dispatch")))))

#?(:clj
   (deftest in-script-no-cascade-rerender-checkpoint-is-tape-evaluated-not-dispatched
     (testing "an in-script [:assert [:rf.assert/no-cascade-rerender …]]
              checkpoint (the causal family) is a no-op step-skip — NOT
              dispatched — so no :rf.error/no-such-handler trace lands
              (rf2-fh7g4)"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.tape/no-cascade
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]
                                    [:assert [:rf.assert/no-cascade-rerender
                                              {:event :rt/touch :sub :some/sub}]]]}})
       (let [[final no-handler] (run-capturing-no-handler-errors
                                  :story.tape/no-cascade)
             checkpoint (->> (:results final)
                             (filter #(= :assert (:type %)))
                             first)]
         (is (empty? no-handler)
             "NO :rf.error/no-such-handler trace fired — the causal atom was
              NOT dispatched into the frame")
         (is (some? checkpoint) "the [:assert …] checkpoint produced a result")
         (is (nil? (:passed? checkpoint))
             "the tape-evaluated causal checkpoint is a no-op step-skip")
         (is (empty? (filterv #(= :rf.assert/no-cascade-rerender (:assertion %))
                              (story/read-assertions :story.tape/no-cascade)))
             "no causal slot record was minted by a dispatch")))))

#?(:clj
   (deftest in-script-caused-checkpoint-is-tape-evaluated-not-dispatched
     (testing "an in-script [:assert [:rf.assert/caused …]] checkpoint is a
              no-op step-skip — NOT dispatched — so no
              :rf.error/no-such-handler trace lands (rf2-fh7g4)"
       (rf/reg-event :rt/touch
         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
       (story/reg-variant :story.tape/caused
         {:events      []
          :play-script {:auto-run? false
                        :script    [[:dispatch-sync [:rt/touch]]
                                    [:assert [:rf.assert/caused
                                              {:event :rt/touch :sub :some/sub}]]]}})
       (let [[final no-handler] (run-capturing-no-handler-errors
                                  :story.tape/caused)
             checkpoint (->> (:results final)
                             (filter #(= :assert (:type %)))
                             first)]
         (is (empty? no-handler)
             "NO :rf.error/no-such-handler trace fired for the :caused atom")
         (is (nil? (:passed? checkpoint))
             "the tape-evaluated causal checkpoint is a no-op step-skip")))))

;; ---- unit: the tape-evaluated-assertion? classifier (rf2-8y47c + fh7g4) ----
;;
;; The single authoritative classifier the in-script executor consults so a
;; tape-evaluated family is never dispatched. Runs on BOTH runtimes (pure
;; data → boolean), reached via var-quote (the established Story-test seam).

(def ^:private tape-evaluated-assertion? @#'re/tape-evaluated-assertion?)

(deftest tape-evaluated-assertion?-classifies-every-non-dispatched-family
  (testing "schema-error and the causal family are tape-evaluated (no
            reg-event handler); the dispatchable seven, the DOM family,
            and the browser-tier oracle family are NOT"
    (is (true? (boolean (tape-evaluated-assertion? [:rf.assert/schema-error {}]))))
    (is (true? (boolean (tape-evaluated-assertion? [:rf.assert/caused {:event :e}]))))
    (is (true? (boolean (tape-evaluated-assertion? [:rf.assert/no-cascade-rerender {:event :e}]))))
    ;; rf2-9ikj0 — the browser-tier oracle family now has its OWN inline
    ;; executor (`exec-assert-browser-atom!`), so it is NO LONGER
    ;; tape-evaluated: a11y-structural EVALUATES at :hiccup, visual / a11y
    ;; fail closed to :cannot-run headless — neither is a no-op skip.
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/visual-snapshot])))
        "visual-snapshot is routed to the browser executor, not this classifier")
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/a11y])))
        "a11y is routed to the browser executor, not this classifier")
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/a11y-structural])))
        "a11y-structural is routed to the browser executor, not this classifier")
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/path-equals [:k] 1])))
        "a dispatchable canonical assertion (has a reg-event handler) is NOT tape-evaluated")
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/state-is :loaded])))
        "another of the dispatchable seven is NOT tape-evaluated")
    (is (false? (boolean (tape-evaluated-assertion? [:rf.assert/dom-visible "div"])))
        "the DOM family is routed to its OWN inline executor, not this classifier")))
