(ns re-frame.story.runtime-runpath-test
  "End-to-end run-path wiring tests (rf2-baah3 + rf2-9ikj0).

  These drive `story/run` to terminal on the JVM (where the future resolves
  synchronously) and assert the run-path now THREADS the requirements
  registry + routes the browser-tier a11y-structural executor — the two
  surfaces that existed but were orphaned from the run path:

  - rf2-baah3 — `re-frame.story.requirements` (`normalize-run-opts` →
    `select-runner` → `unmet-assertions` / `unmet-steps` →
    `validate-run-evidence`) is wired into `run-variant` / `run-inline-plan`:
    an UNMET requirement surfaces `:cannot-run` (the distinct THIRD status,
    never a false pass), the cheapest capable runner is selected, and the
    result carries `:runner` / `:required-runner`.

  - rf2-9ikj0 — `re-frame.story.play.browser/eval-browser-assertion` is
    routed from the run path's in-script `[:assert …]` executor: an
    `:rf.assert/a11y-structural` checkpoint EVALUATES (:pass / :fail) at the
    `:hiccup` tier against the rendered hiccup tree (the `:render-hiccup`
    seam); a tier that cannot supply the tree records `:cannot-run`.

  JVM-only (`.clj`): `story/run` returns a `CompletableFuture` that resolves
  synchronously to the unified result. The selection / requirement-function
  unit coverage lives in `re-frame.story.requirements-test`; the browser
  executor unit coverage lives in `re-frame.story.play.browser-test`. This
  suite proves the END-TO-END wiring through the run path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story     :as story]
            [re-frame.story.late-bind  :as late-bind]))

(defn- reset-rf! [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; Drop any stray :render-hiccup host from a prior test (the run-path
  ;; a11y-structural tier-proof seam) so the no-host :cannot-run case is clean.
  (swap! late-bind/hooks dissoc :render-hiccup)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (rf/reg-event :rp/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (rf/reg-event :rp/set-value  (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target
  ([target] (.get ^java.util.concurrent.CompletableFuture (story/run target)))
  ([target opts] (.get ^java.util.concurrent.CompletableFuture (story/run target opts))))

(defn- a11y-structural-record [result]
  (first (filter #(= :rf.assert/a11y-structural (:assertion %))
                 (:assertions result))))

;; ===========================================================================
;; rf2-baah3 — requirements selection / validation wired into the run path
;; ===========================================================================

(deftest unmet-requirement-surfaces-cannot-run
  (testing "a terminal :rf.assert/visual-snapshot (requires :pixels) under the
            default :headless runner makes the run :cannot-run — the distinct
            THIRD status, NEVER a false pass (rf2-baah3 — requirements wired
            into run-variant)"
    (let [result (run-target {:setup      [[:dispatch [:rp/set-status :ready]]]
                              :assertions [[:rf.assert/visual-snapshot]]})]
      (is (= :cannot-run (:status result))
          "the unmet :pixels requirement aggregates the run to :cannot-run")
      (is (seq (:cannot-run result))
          "the :cannot-run slot carries the per-requirement refusal(s)")
      (is (some #(contains? (set (:missing %)) :pixels) (:cannot-run result))
          "a refusal attributes the missing :pixels token")
      (is (= :headless (:runner result))
          "the chosen runner is surfaced on the result")
      (is (contains? (set (:required-runner result)) :pixels)
          "the plan's :required-runner capability set is surfaced + carries :pixels"))))

(deftest healthy-headless-run-is-not-falsely-refused
  (testing "a healthy headless run (an :app-db assertion the :headless runner
            CAN prove) reads :pass with NO :cannot-run — the requirements
            wiring must not false-refuse a run whose tokens the runner provides
            (rf2-baah3 / rf2-qoxw7 — empty-is-healthy slots impose no gate)"
    (let [result (run-target {:script [[:dispatch [:rp/set-status :loaded]]
                                       [:assert [:rf.assert/path-equals [:status] :loaded]]]})]
      (is (= :pass (:status result)) "a met-and-passing headless run is :pass")
      (is (empty? (:cannot-run result)) "no spurious :cannot-run refusal")
      (is (= :headless (:runner result)))
      (is (every? :passed? (:assertions result))))))

(deftest auto-selects-cheapest-capable-runner
  (testing "under :auto the cheapest CAPABLE runner is selected — :headless for
            an app-db-only plan, :hiccup for a :hiccup-structure requirement
            (rf2-baah3 — select-runner threaded through normalize-run-opts)"
    (let [headless (run-target {:script [[:dispatch [:rp/set-status :loaded]]
                                         [:assert [:rf.assert/path-equals [:status] :loaded]]]}
                               {:runner :auto})]
      (is (= :headless (:runner headless))
          "an app-db-only plan escalates no further than :headless under :auto"))
    ;; Install a render-hiccup host so a11y-structural can run at :hiccup; the
    ;; selection itself (cheapest = :hiccup for :hiccup-structure) is the point.
    (late-bind/set-fn! :render-hiccup (fn [_frame] [:div "ok"]))
    (let [hiccup (run-target {:assertions [[:rf.assert/a11y-structural]]}
                             {:runner :auto})]
      (is (= :hiccup (:runner hiccup))
          "a :hiccup-structure requirement escalates to the cheapest capable runner :hiccup")
      (is (contains? (set (:required-runner hiccup)) :hiccup-structure)))))

(deftest fixed-headless-refuses-hiccup-structure-requirement
  (testing "under fixed :headless a :hiccup-structure (a11y-structural)
            requirement refuses :cannot-run at PREFLIGHT — the fixed runner
            runs single-pass and refuses per-requirement (rf2-baah3)"
    (let [result (run-target {:assertions [[:rf.assert/a11y-structural]]}
                             {:runner :headless})]
      (is (= :cannot-run (:status result)))
      (is (some #(contains? (set (:missing %)) :hiccup-structure)
                (:cannot-run result))
          "the refusal attributes the missing :hiccup-structure token"))))

;; ===========================================================================
;; rf2-9ikj0 — a11y-structural executor routed into the run path
;; ===========================================================================

(deftest a11y-structural-evaluates-and-fails-at-hiccup
  (testing "an in-script [:assert [:rf.assert/a11y-structural]] checkpoint
            EVALUATES at :hiccup against the rendered tree and FAILS the run
            when the tree carries a structural issue (rf2-9ikj0 — the
            previously-orphaned executor is wired in)"
    ;; The host renders a tree with an :img missing :alt — a structural issue.
    (late-bind/set-fn! :render-hiccup (fn [_frame] [:div [:img {:src "/k.png"}]]))
    (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                       [:assert [:rf.assert/a11y-structural]]]}
                             {:runner :hiccup})]
      (is (= :fail (:status result))
          "a structural-a11y finding fails the run (no longer a no-op skip)")
      (let [rec (a11y-structural-record result)]
        (is (some? rec) "the a11y-structural assertion record landed on the slot")
        (is (false? (:passed? rec)) "the img-missing-alt issue is a :fail")
        (is (= :fail (:status rec)))))))

(deftest a11y-structural-evaluates-and-passes-at-hiccup
  (testing "a structurally-clean rendered tree PASSES :rf.assert/a11y-structural
            on the normal :hiccup run path (rf2-9ikj0)"
    (late-bind/set-fn! :render-hiccup
                       (fn [_frame] [:div [:img {:src "/k.png" :alt "a kitten"}]
                                     [:button "Go"]]))
    (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                       [:assert [:rf.assert/a11y-structural]]]}
                             {:runner :hiccup})]
      (is (= :pass (:status result)) "a clean tree passes structural a11y")
      (let [rec (a11y-structural-record result)]
        (is (some? rec))
        (is (true? (:passed? rec)))
        (is (= :pass (:status rec)))))))

(deftest a11y-structural-cannot-run-without-a-hiccup-tree
  (testing "with NO :render-hiccup host the :hiccup runner cannot supply a
            rendered tree, so :rf.assert/a11y-structural records :cannot-run —
            NEVER a vacuous pass over a nil tree (rf2-9ikj0 honesty floor)"
    ;; No :render-hiccup host installed (the fixture cleared it). The runner is
    ;; :hiccup so the PREFLIGHT capability check passes (:hiccup provides
    ;; :hiccup-structure); the executor's own tree-availability guard refuses.
    (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                       [:assert [:rf.assert/a11y-structural]]]}
                             {:runner :hiccup})]
      (is (= :cannot-run (:status result))
          "no rendered tree → :cannot-run, never a false pass/fail")
      (let [rec (a11y-structural-record result)]
        (is (some? rec) "a :cannot-run record landed on the slot")
        (is (= :cannot-run (:status rec)))
        (is (true? (:cannot-run? rec)))
        (is (false? (:passed? rec)))))))

(deftest visual-snapshot-cannot-run-headless-through-run-path
  (testing "a :rf.assert/visual-snapshot checkpoint routed through the run-path
            executor records :cannot-run headless (browser-only :pixels) — the
            executor's browser-available? guard, surfaced end-to-end (rf2-9ikj0)"
    (let [result (run-target {:script [[:dispatch [:rp/set-status :ready]]
                                       [:assert [:rf.assert/visual-snapshot]]]}
                             {:runner :browser})]
      ;; :browser is selected (so preflight does NOT refuse the :pixels token),
      ;; but the JVM has no real browser → the executor refuses :cannot-run.
      (is (= :cannot-run (:status result)))
      (let [rec (first (filter #(= :rf.assert/visual-snapshot (:assertion %))
                               (:assertions result)))]
        (is (some? rec) "the visual-snapshot record landed (no longer dropped)")
        (is (= :cannot-run (:status rec)))))))

;; ===========================================================================
;; rf2-2cpoo — run opts (:cell-overrides / :active-modes) thread into PLAN
;; compilation, so the EXECUTED `[:arg …]` substitutions match the REPORTED
;; `:effective-args`. Before the fix the runtime compiled the plan with the
;; static variant args while reporting `args/resolve-args` (override/mode
;; aware), so a cell override or active mode executed a DIFFERENT scenario
;; than the one the result claimed — a false pass/fail + misleading snapshot.
;; ===========================================================================

(deftest cell-override-threads-into-script-arg-substitution
  (testing "a :cell-override drives an `[:arg …]` placeholder in the SCRIPT;
            the executed app-db AND the reported :effective-args BOTH reflect
            the override value — not the static story arg (rf2-2cpoo)"
    (story/reg-variant
      :story.opts/scripted
      {:args   {:value "static"}
       ;; `[:arg :value]` is resolved at plan-compile time; the run opts must
       ;; thread into compilation so the dispatched value IS the override.
       :script [[:dispatch-sync [:rp/set-value [:arg :value]]]
                [:assert [:rf.assert/path-equals [:value] "override"]]]})
    (let [result (run-target :story.opts/scripted
                             {:cell-overrides {:value "override"}})]
      (is (= "override" (get-in result [:app-db :value]))
          "the SCRIPT dispatched the OVERRIDE value (plan compiled with run opts)")
      (is (= "override" (get-in result [:effective-args :value]))
          "the reported :effective-args carries the override")
      (is (= :pass (:status result))
          "the [:arg]-driven assertion against the override value PASSES — the
           executed plan and the reported effective args agree")
      (is (every? :passed? (:assertions result))))))

(deftest cell-override-threads-into-db-seed-arg-substitution
  (testing "a :cell-override drives an `[:arg …]` placeholder in the :db-seed;
            the seeded app-db reflects the override, matching :effective-args
            (rf2-2cpoo — db-seed substitution uses the run-opts effective args)"
    (story/reg-variant
      :story.opts/seeded
      {:args    {:value "static"}
       :db-seed {:seeded [:arg :value]}})
    (let [result (run-target :story.opts/seeded
                             {:cell-overrides {:value "override"}})]
      (is (= "override" (get-in result [:app-db :seeded]))
          "the :db-seed seeded the OVERRIDE value (plan compiled with run opts)")
      (is (= "override" (get-in result [:effective-args :value]))
          "the reported :effective-args carries the override")
      (is (= :pass (:status result))))))

(deftest active-mode-threads-into-arg-substitution
  (testing "an :active-mode's :args drive an `[:arg …]` placeholder for an arg
            the variant does NOT itself set; the executed app-db AND the
            reported :effective-args BOTH reflect the mode value (rf2-2cpoo —
            mode args fold into plan compilation at `mode < variant` precedence,
            so the mode supplies args the variant leaves open)"
    (story/reg-mode :Mode.test/big {:args {:value "from-mode"}})
    (story/reg-variant
      :story.opts/moded
      ;; the variant declares NO :value, so the mode's :value flows through
      ;; (precedence `mode < variant`: the mode fills args the variant omits).
      {:script [[:dispatch-sync [:rp/set-value [:arg :value]]]
                [:assert [:rf.assert/path-equals [:value] "from-mode"]]]})
    (let [result (run-target :story.opts/moded
                             {:active-modes [:Mode.test/big]})]
      (is (= "from-mode" (get-in result [:app-db :value]))
          "the SCRIPT dispatched the MODE value (mode args threaded into compile)")
      (is (= "from-mode" (get-in result [:effective-args :value]))
          "the reported :effective-args carries the mode value")
      (is (= :pass (:status result)))
      (is (every? :passed? (:assertions result))))))

(deftest cell-override-wins-over-active-mode-in-arg-substitution
  (testing "precedence holds end-to-end: mode < cell-override; the SCRIPT
            dispatches the override (highest layer), matching :effective-args
            (rf2-2cpoo — the plan folds layers in resolve-args precedence)"
    (story/reg-mode :Mode.test/mid {:args {:value "from-mode"}})
    (story/reg-variant
      :story.opts/precedence
      {:args   {:value "static"}
       :script [[:dispatch-sync [:rp/set-value [:arg :value]]]]})
    (let [result (run-target :story.opts/precedence
                             {:active-modes   [:Mode.test/mid]
                              :cell-overrides {:value "override"}})]
      (is (= "override" (get-in result [:app-db :value]))
          "cell-override beats the active mode in the EXECUTED substitution")
      (is (= "override" (get-in result [:effective-args :value]))
          "and in the reported :effective-args — executed == reported"))))

(deftest no-run-opts-still-uses-static-args
  (testing "with NO run opts the run still substitutes the STATIC variant args —
            the run-args threading is purely additive (rf2-2cpoo regression
            guard: the absent-opts path is unchanged)"
    (story/reg-variant
      :story.opts/plain
      {:args   {:value "static"}
       :script [[:dispatch-sync [:rp/set-value [:arg :value]]]]})
    (let [result (run-target :story.opts/plain)]
      (is (= "static" (get-in result [:app-db :value])))
      (is (= "static" (get-in result [:effective-args :value]))))))

;; ===========================================================================
;; rf2-5fv445 — a plan-construction failure routes to `plan-error-result`
;; REGARDLESS of the prior frame's lifecycle state. Before the fix
;; `handle-run-error!` gated the plan-error branch on
;; `(= :pre-mount (loaders/current-state variant-id))`; the plan compiles in
;; `prepare-context` BEFORE this run resets its frame, so when a PRIOR
;; `run-variant` had already driven the same-id frame to `:ready`, the guard
;; saw `:ready` and fell through to the frame-bound `:else` branch — recording
;; an opaque `:rf.error/exception` AND reading the prior run's stale `:app-db`
;; into the error result (spec/017 §Run result: `:app-db` is THIS run's final
;; db; the error language must read identically across UI/MCP).
;; ===========================================================================

(deftest plan-error-after-prior-ready-run-reports-structured-error-not-stale-db
  (testing "a plan-construction failure (missing [:arg :missing]) on the SECOND
            run of a variant that the FIRST run drove to :ready surfaces the
            structured :rf.error/story-missing-arg assertion directly and does
            NOT leak the prior run's app-db value (rf2-5fv445)"
    ;; Run 1: a valid variant that seeds app-db with {:value \"old\"} and runs
    ;; to a healthy terminal — the frame ends at :ready with that app-db.
    (story/reg-variant
      :story.stale/v
      {:script [[:dispatch-sync [:rp/set-value "old"]]
                [:assert [:rf.assert/path-equals [:value] "old"]]]})
    (let [first-result (run-target :story.stale/v)]
      (is (= :pass (:status first-result)) "the first run is a healthy pass")
      (is (= "old" (get-in first-result [:app-db :value]))
          "the first run leaves {:value \"old\"} on the frame's app-db"))
    ;; Run 2: RE-REGISTER the same id with a plan-time error — a [:arg :missing]
    ;; the variant (and its parents) never declares. Plan construction throws in
    ;; `prepare-context` (before the fresh-frame reset), and the prior frame is
    ;; still registered at :ready.
    (story/reg-variant
      :story.stale/v
      {:script [[:dispatch-sync [:rp/set-value [:arg :missing]]]]})
    (let [result (run-target :story.stale/v)]
      (is (= :error (:status result))
          "the second run reports :error (plan construction failed)")
      (let [rec (first (filter #(= :rf.error/story-missing-arg (:assertion %))
                               (:assertions result)))]
        (is (some? rec)
            "the plan failure surfaces as a STRUCTURED :rf.error/story-missing-arg
             assertion — NOT an opaque :rf.error/exception")
        (is (false? (:passed? rec))))
      (is (not (some #(= :rf.error/exception (:assertion %)) (:assertions result)))
          "no opaque :rf.error/exception assertion is recorded for a plan failure")
      (is (not= "old" (get-in result [:app-db :value]))
          "the error result does NOT leak the prior run's stale {:value \"old\"}")
      (is (= {} (:app-db result))
          "a plan-construction failure allocates no frame, so :app-db is the
           frame-free empty-result default ({}) — never the prior frame's db"))))
