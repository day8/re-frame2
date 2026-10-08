(ns re-frame.story.ui.test-widget-cljs-test
  "Tests for the chrome-level test widget + sidebar status dots
  (Storybook 9 Vitest-reporter parity).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `record-test-run` status derivation;
    `test-summary` aggregation across a fixture of variants in mixed
    states; `testable-variant-ids` filter (must be both `:test`-tagged
    AND carry tests — a play surface or declarative `:assertions` /
    `:checks`); `aggregate-summary`'s `:error` tally.
  - **CLJS-only**: the chrome widget's headline, empty state and Run all
    button; the per-variant status dot; Run all's per-variant opts."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.state :as rf.story.ui.state]
            #?@(:cljs [[re-frame.story.ui.sidebar :as rf.story.ui.sidebar]
                       [re-frame.story.theme.status :as rf.story.theme.status]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- pure: state transitions --------------------------------------------

(deftest record-test-run-derives-status-from-the-summary
  (are [summary status]
       (= status (get-in (rf.story.ui.state/record-test-run
                           rf.story.ui.state/default-shell-state :story.x/a summary)
                         [:tests :runs :story.x/a :status]))
    ;; every assertion passed
    {:total 3 :passed 3 :failed 0 :skipped 0 :all-passed? true}
    :pass

    ;; zero assertions: the variant ran but produced no signal, so the
    ;; sidebar dot reads 'not yet run' rather than green
    {:total 0 :passed 0 :failed 0 :skipped 0 :all-passed? false}
    :pending))

;; ---- pure: test-summary aggregation -------------------------------------

(deftest test-summary-fixture-of-5
  (testing "summary computes correctly from a fixture of 5 variants
            (3 pass / 1 fail / 1 pending) — the canonical case"
    (let [pass-summary {:total 1 :passed 1 :failed 0 :skipped 0
                        :all-passed? true}
          fail-summary {:total 1 :passed 0 :failed 1 :skipped 0
                        :all-passed? false}
          s (-> rf.story.ui.state/default-shell-state
                (rf.story.ui.state/record-test-run :story.x/a pass-summary)
                (rf.story.ui.state/record-test-run :story.x/b pass-summary)
                (rf.story.ui.state/record-test-run :story.x/c pass-summary)
                (rf.story.ui.state/record-test-run :story.x/d fail-summary))
          ;; :story.x/e is not stamped — it reads :pending.
          summary (rf.story.ui.state/test-summary s [:story.x/a :story.x/b :story.x/c
                                         :story.x/d :story.x/e])]
      (is (= {:total 5 :passed 3 :failed 1 :cannot-run 0 :running 0
              :pending 1 :all-green? false}
             summary)))))

(deftest test-summary-all-green
  (let [pass {:total 1 :passed 1 :failed 0 :skipped 0 :all-passed? true}
        s    (-> rf.story.ui.state/default-shell-state
                 (rf.story.ui.state/record-test-run :story.x/a pass)
                 (rf.story.ui.state/record-test-run :story.x/b pass))]
    (is (:all-green? (rf.story.ui.state/test-summary s [:story.x/a :story.x/b])))))

;; Nothing to run is not green.
(deftest test-summary-empty
  (is (false? (:all-green? (rf.story.ui.state/test-summary
                             rf.story.ui.state/default-shell-state [])))))

(deftest test-summary-running-blocks-green
  (let [s       (-> rf.story.ui.state/default-shell-state
                    (rf.story.ui.state/mark-test-running :story.x/a))
        summary (rf.story.ui.state/test-summary s [:story.x/a])]
    (is (= 1 (:running summary)))
    (is (false? (:all-green? summary)))))

;; ---- pure: testable-variant-ids -----------------------------------------

(deftest testable-variant-ids-filters-by-tag-and-play
  (testing "only :test-tagged variants with non-empty :script count"
    (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                   :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
    (rf.story/reg-variant :story.x/b {:tags #{:test} :setup [] :script []})
    (rf.story/reg-variant :story.x/c {:tags #{:dev} :setup []
                                   :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
    (rf.story/reg-variant :story.x/d {:tags #{:test :dev} :setup []
                                   :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
    (let [vs (rf.story.registrar/registrations :variant)
          testable (rf.story.ui.state/testable-variant-ids vs)]
      (is (= [:story.x/a :story.x/d] testable)))))

(deftest testable-variant-ids-counts-declarative-expectations
  (testing "a :test variant whose only tests are declarative
            :assertions or :checks is testable beside a :script control;
            empty expectation vectors and a non-:test tag still prune"
    (rf.story/reg-check :story.x/c-is-zero
      {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/assertions-only
      {:tags #{:test} :setup [] :assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-variant :story.x/checks-only
      {:tags #{:test} :setup [] :checks [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/script
      {:tags #{:test} :setup []
       :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
    (rf.story/reg-variant :story.x/empty-expectations
      {:tags #{:test} :setup [] :assertions [] :checks []})
    (rf.story/reg-variant :story.x/dev-assertions
      {:tags #{:dev} :setup [] :assertions [[:rf.assert/path-equals [:c] 0]]})
    (is (= [:story.x/assertions-only :story.x/checks-only :story.x/script]
           (rf.story.ui.state/testable-variant-ids
             (rf.story.registrar/registrations :variant))))))

(deftest testable-variant-ids-counts-inherited-and-composed-checks
  (testing "a :test variant whose only tests are :checks it
            receives — from an :extends ancestor at any depth, or through a
            :compose of a check id — is testable, because the compiled plan
            hands those checks to the run; an :extends of a check-free
            parent and a :compose of a fragment still prune"
    (rf.story/reg-check :story.x/c-is-zero
      {:assertions [[:rf.assert/path-equals [:c] 0]]})
    (rf.story/reg-fragment :fragment.x/seed {:setup []})
    (rf.story/reg-variant :story.x/root  {:tags #{:dev} :setup [] :checks [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/mid   {:tags #{:dev} :extends :story.x/root})
    (rf.story/reg-variant :story.x/plain {:tags #{:dev} :setup []})
    (rf.story/reg-variant :story.x/extends-child
      {:tags #{:test} :extends :story.x/root})
    (rf.story/reg-variant :story.x/extends-grandchild
      {:tags #{:test} :extends :story.x/mid})
    (rf.story/reg-variant :story.x/compose-check
      {:tags #{:test} :setup [] :compose [:story.x/c-is-zero]})
    (rf.story/reg-variant :story.x/extends-plain
      {:tags #{:test} :extends :story.x/plain})
    (rf.story/reg-variant :story.x/compose-fragment
      {:tags #{:test} :setup [] :compose [:fragment.x/seed]})
    (is (= [:story.x/compose-check :story.x/extends-child :story.x/extends-grandchild]
           (rf.story.ui.state/testable-variant-ids
             (rf.story.registrar/registrations :variant))))))

;; ---- CLJS-only: rendered hiccup contains the widget ---------------------

#?(:cljs
   (defn- find-by-data-test
     "Walk a hiccup tree and return every element whose props map has
     `:data-test` equal to `tag`. Cheap recursion suitable for the
     small trees the sidebar renders in tests."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node)
                        (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node)
                       (doseq [c (drop 2 node)] (walk c)))

                   (vector? node)
                   (doseq [c (rest node)] (walk c))

                   (seq? node)
                   (doseq [c node] (walk c))

                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (deftest widget-empty-when-no-testable-variants
     (testing "no :test variants → the widget renders the empty-state
               sub-line, and neither the Run all button nor the watch chip"
       (rf.story/reg-variant :story.x/a {:tags #{:dev} :setup []})
       (let [tree (rf.story.ui.sidebar/test-widget (rf.story.ui.state/get-state)
                                                   (rf.story.ui.state/registry-snapshot))]
         (is (some? (first (find-by-data-test tree "story-test-widget-empty"))))
         (is (nil? (first (find-by-data-test tree "story-test-widget-run-all"))))
         (is (nil? (first (find-by-data-test tree "story-test-widget-watch-toggle"))))))))

#?(:cljs
   (deftest sidebar-dot-reflects-per-variant-state
     (testing "the per-variant status dot reflects the variant's last-
               run state. Renders the dot component directly for each
               status keyword and asserts the rendered hiccup carries
               the expected `data-status` attribute."
       (let [dot-fail    (rf.story.ui.sidebar/status-dot :fail)
             dot-pending (rf.story.ui.sidebar/status-dot :pending)
             dot-pass    (rf.story.ui.sidebar/status-dot :pass)
             dot-running (rf.story.ui.sidebar/status-dot :running)]
         (is (= "fail"    (get (second dot-fail) :data-status)))
         (is (= "pending" (get (second dot-pending) :data-status)))
         (is (= "pass"    (get (second dot-pass) :data-status)))
         (is (= "running" (get (second dot-running) :data-status)))
         ;; aria-label round-trips for screen-reader users.
         (is (= "tests: Fail" (get (second dot-fail) :aria-label)))))))

;; ---- :cannot-run ≠ :pending THROUGH the dot -----------------------------

;; spec/017 §:cannot-run — a refusal never wears :pending's paint, name or
;; data-status; pending stays reserved for the genuinely unknown slot.
#?(:cljs
   (deftest sidebar-dot-cannot-run-distinct-from-pending
     (let [props-cannot  (second (rf.story.ui.sidebar/status-dot :cannot-run))
           props-pending (second (rf.story.ui.sidebar/status-dot :pending))]
       (is (= "cannot-run" (:data-status props-cannot)))
       (is (= "pending"    (:data-status props-pending)))
       (is (not= (:style props-cannot) (:style props-pending))
           ":cannot-run must not wear the pending ring")
       (is (= (str "1px solid " (:border (rf.story.theme.status/descriptor :cannot-run)))
              (:border (:style props-cannot))))
       (is (= "tests: Can't run" (:aria-label props-cannot)))
       (is (= "tests: Pending"   (:aria-label props-pending)))
       (is (= "tests: Can't run" (:title props-cannot))))))

;; ---- status-dot is decorative img (not a live region) -----------------

;; `role="status"` carries an implicit `aria-live="polite"`, which would make
;; every mounted dot (one per variant row) a live region.
#?(:cljs
   (deftest sidebar-dot-uses-img-role-not-status
     (is (= "img" (get (second (rf.story.ui.sidebar/status-dot :fail)) :role)))))

#?(:cljs
   (deftest widget-run-all-button-disabled-while-running
     (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story.ui.state/swap-state! rf.story.ui.state/mark-test-running :story.x/a)
     (let [tree (rf.story.ui.sidebar/test-widget (rf.story.ui.state/get-state)
                                                 (rf.story.ui.state/registry-snapshot))]
       (is (true? (get (second (first (find-by-data-test tree "story-test-widget-run-all")))
                       :disabled))))))

;; ---- 3-arity threads precomputed variant-ids ---------------------------

;; Three variants registered, one supplied: the headline counts the
;; supplied subset only.
#?(:cljs
   (deftest widget-3-arity-uses-supplied-variant-ids
     (rf.story/reg-variant :story.x/a {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story/reg-variant :story.x/b {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (rf.story/reg-variant :story.x/c {:tags #{:test} :setup []
                                    :script [[:dispatch-sync [:rf.assert/path-equals [:c] 0]]]})
     (let [tree (rf.story.ui.sidebar/test-widget (rf.story.ui.state/get-state)
                                                 (rf.story.ui.state/registry-snapshot)
                                                 [:story.x/a])]
       (is (= "Tests · 0/1"
              (nth (first (find-by-data-test tree "story-test-widget-headline")) 2))))))

;; ---- per-variant cell-overrides threading ------------------------------

;; Run all threads each variant's OWN cell-overrides entry; one blanket map
;; (or nil) for every variant would drop the user's controls-panel edits.
#?(:cljs
   (deftest run-opts-threads-per-variant-cell-overrides
     (let [shell (-> rf.story.ui.state/default-shell-state
                     (assoc :active-modes #{:dark}
                            :substrate :reagent)
                     (rf.story.ui.state/set-cell-override-scalar :story.x/a :n 5)
                     (rf.story.ui.state/set-cell-override-scalar :story.x/b :label "B"))]
       (is (= {:active-modes #{:dark} :cell-overrides {:n 5} :substrate :reagent}
              (rf.story.ui.sidebar/run-opts-for-variant shell :story.x/a)))
       (is (= {:label "B"}
              (:cell-overrides (rf.story.ui.sidebar/run-opts-for-variant shell :story.x/b))))
       (is (nil? (:cell-overrides (rf.story.ui.sidebar/run-opts-for-variant shell :story.x/c)))
           "an unedited variant gets no sibling's overrides"))))

;; ---- aggregate-summary counts :error records as failures

;; A derived record with `:exception` / `:error` and no explicit `:status`
;; counts as failed, so a thrown handler never reads false-green. The
;; per-record derivation is `re-frame.story.result-test/record-status-derivation`.
(deftest aggregate-summary-counts-error-records-as-failed
  (let [summary (rf.story.ui.state/aggregate-summary
                  [{:passed? true}
                   {:exception (ex-info "boom" {})}
                   {:error "boom"}])]
    (is (= 1 (:passed summary)))
    (is (= 2 (:failed summary)))
    (is (false? (:all-passed? summary)))))
