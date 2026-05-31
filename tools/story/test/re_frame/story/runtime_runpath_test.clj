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
  (rf/reg-event-db :rp/set-status (fn [db [_ v]] (assoc db :status v)))
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
