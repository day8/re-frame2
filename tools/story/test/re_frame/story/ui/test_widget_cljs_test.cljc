(ns re-frame.story.ui.test-widget-cljs-test
  "Tests for the chrome-level test widget + sidebar status dots
  (rf2-q0irb — Storybook 9 Vitest-reporter parity).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `mark-test-running` /
    `record-test-run` / `clear-test-run` state transitions;
    `variant-test-status` lookup; `test-summary` aggregation across a
    fixture of variants in mixed states; `testable-variant-ids`
    filter (must be both `:test`-tagged AND `:play`-bearing); the
    `status->dot-style-key` projection and `dot-aria-label`.
  - **CLJS-only**: the rendered hiccup for the chrome widget carries
    the expected counts + headline; the sidebar's variant-row hiccup
    includes the status dot when the variant is testable; the
    'Run all' button renders disabled when any run is in flight."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.registrar :as story-registrar]
            [re-frame.story.ui.state :as state]
            #?@(:cljs [[re-frame.story.ui.sidebar :as sidebar]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- pure: state transitions --------------------------------------------

(deftest mark-test-running-stamps-status
  (testing "mark-test-running writes :running into [:tests :runs]"
    (let [s  (state/mark-test-running state/default-shell-state :story.x/a)]
      (is (= :running (get-in s [:tests :runs :story.x/a :status]))))))

(deftest record-test-run-pass
  (testing "a run with passed assertions records :pass + counts"
    (let [s (state/record-test-run state/default-shell-state :story.x/a
                                   {:total 3 :passed 3 :failed 0 :skipped 0
                                    :all-passed? true
                                    :ran-at-ms 100 :elapsed-ms 12})]
      (is (= :pass (get-in s [:tests :runs :story.x/a :status])))
      (is (= 3     (get-in s [:tests :runs :story.x/a :passed])))
      (is (= 0     (get-in s [:tests :runs :story.x/a :failed])))
      (is (= 12    (get-in s [:tests :runs :story.x/a :elapsed-ms]))))))

(deftest record-test-run-fail
  (testing "a run with any failure records :fail"
    (let [s (state/record-test-run state/default-shell-state :story.x/a
                                   {:total 3 :passed 1 :failed 2 :skipped 0
                                    :all-passed? false})]
      (is (= :fail (get-in s [:tests :runs :story.x/a :status])))
      (is (= 2     (get-in s [:tests :runs :story.x/a :failed]))))))

(deftest record-test-run-empty-is-pending
  (testing "a run that recorded zero assertions reads :pending — the
            variant ran but produced no signal, so the sidebar dot
            renders as 'not yet run' rather than green"
    (let [s (state/record-test-run state/default-shell-state :story.x/a
                                   {:total 0 :passed 0 :failed 0 :skipped 0
                                    :all-passed? false})]
      (is (= :pending (get-in s [:tests :runs :story.x/a :status]))))))

(deftest clear-test-run-drops-record
  (testing "clear-test-run removes the slot — the dot re-reads :pending"
    (let [s (-> state/default-shell-state
                (state/mark-test-running :story.x/a)
                (state/clear-test-run :story.x/a))]
      (is (nil? (get-in s [:tests :runs :story.x/a])))
      (is (= :pending (state/variant-test-status s :story.x/a))))))

(deftest variant-test-status-defaults-pending
  (testing "an un-stamped variant reads :pending"
    (is (= :pending (state/variant-test-status state/default-shell-state
                                               :story.unknown/x)))))

;; ---- pure: test-summary aggregation -------------------------------------

(deftest test-summary-fixture-of-5
  (testing "summary computes correctly from a fixture of 5 variants
            (3 pass / 1 fail / 1 pending) — the canonical case from
            the bead description"
    (let [pass-summary {:total 1 :passed 1 :failed 0 :skipped 0
                        :all-passed? true}
          fail-summary {:total 1 :passed 0 :failed 1 :skipped 0
                        :all-passed? false}
          s (-> state/default-shell-state
                (state/record-test-run :story.x/a pass-summary)
                (state/record-test-run :story.x/b pass-summary)
                (state/record-test-run :story.x/c pass-summary)
                (state/record-test-run :story.x/d fail-summary))
          ;; :story.x/e is not stamped — it reads :pending.
          summary (state/test-summary s [:story.x/a :story.x/b :story.x/c
                                         :story.x/d :story.x/e])]
      (is (= 5 (:total summary)))
      (is (= 3 (:passed summary)))
      (is (= 1 (:failed summary)))
      (is (= 0 (:running summary)))
      (is (= 1 (:pending summary)))
      (is (false? (:all-green? summary))))))

(deftest test-summary-all-green
  (testing ":all-green? is true only when every variant has a recorded
            green run"
    (let [pass {:total 1 :passed 1 :failed 0 :skipped 0 :all-passed? true}
          s (-> state/default-shell-state
                (state/record-test-run :story.x/a pass)
                (state/record-test-run :story.x/b pass))]
      (is (:all-green? (state/test-summary s [:story.x/a :story.x/b]))))))

(deftest test-summary-empty
  (testing "an empty seq of testable variants reads :all-green? false
            — a sea of pending is not green"
    (let [summary (state/test-summary state/default-shell-state [])]
      (is (= 0 (:total summary)))
      (is (false? (:all-green? summary))))))

(deftest test-summary-running-blocks-green
  (testing "a :running variant prevents :all-green?"
    (let [s (-> state/default-shell-state
                (state/mark-test-running :story.x/a))
          summary (state/test-summary s [:story.x/a])]
      (is (= 1 (:running summary)))
      (is (false? (:all-green? summary))))))

;; ---- pure: testable-variant-ids -----------------------------------------

(deftest testable-variant-ids-filters-by-tag-and-play
  (testing "only :test-tagged variants with non-empty :play count"
    (story/reg-variant :story.x/a {:tags #{:test} :events []
                                   :play [[:rf.assert/path-equals [:c] 0]]})
    (story/reg-variant :story.x/b {:tags #{:test} :events [] :play []})
    (story/reg-variant :story.x/c {:tags #{:dev} :events []
                                   :play [[:rf.assert/path-equals [:c] 0]]})
    (story/reg-variant :story.x/d {:tags #{:test :dev} :events []
                                   :play [[:rf.assert/path-equals [:c] 0]]})
    (let [vs (story-registrar/registrations :variant)
          testable (state/testable-variant-ids vs)]
      (is (= [:story.x/a :story.x/d] testable))
      (is (not (some #{:story.x/b} testable)))
      (is (not (some #{:story.x/c} testable))))))

(deftest testable-variant-ids-empty-on-no-registrations
  (testing "no :test variants → empty seq, widget renders 'no :test variants'"
    (is (empty? (state/testable-variant-ids {})))))

;; ---- pure: status → dot style key + aria label --------------------------

#?(:cljs
   (deftest status-dot-style-mapping
     (testing "each canonical status maps to a distinct style key"
       (is (= :dot-pass    (sidebar/status->dot-style-key :pass)))
       (is (= :dot-fail    (sidebar/status->dot-style-key :fail)))
       (is (= :dot-running (sidebar/status->dot-style-key :running)))
       (is (= :dot-pending (sidebar/status->dot-style-key :pending))))))

#?(:cljs
   (deftest status-dot-aria-labels
     (testing "every status produces a distinct accessible label"
       (is (= "tests passing"     (sidebar/dot-aria-label :pass)))
       (is (= "tests failing"     (sidebar/dot-aria-label :fail)))
       (is (= "tests running"     (sidebar/dot-aria-label :running)))
       (is (= "tests not yet run" (sidebar/dot-aria-label :pending)))
       ;; Unrecognised → safe fallback.
       (is (= "tests not yet run" (sidebar/dot-aria-label :unknown))))))

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
   (deftest widget-renders-headline-and-counts
     (testing "with 3 pass / 1 fail / 1 pending the widget headline and
               count chips reflect the fixture"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/b {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/c {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/d {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/e {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (let [pass {:total 1 :passed 1 :failed 0 :skipped 0 :all-passed? true}
             fail {:total 1 :passed 0 :failed 1 :skipped 0 :all-passed? false}]
         (state/swap-state! state/record-test-run :story.x/a pass)
         (state/swap-state! state/record-test-run :story.x/b pass)
         (state/swap-state! state/record-test-run :story.x/c pass)
         (state/swap-state! state/record-test-run :story.x/d fail))
       (let [tree     (sidebar/test-widget (state/get-state)
                                           (state/registry-snapshot))
             headline (first (find-by-data-test tree "story-test-widget-headline"))
             counts   (first (find-by-data-test tree "story-test-widget-counts"))]
         (is (some? headline))
         (is (some? counts))
         ;; Headline reads "Tests · 3/5" — three of five variants are green.
         ;; The text-node is the third element of the hiccup vec (after
         ;; the tag and props map).
         (is (re-find #"3/5" (nth headline 2)))))))

#?(:cljs
   (deftest widget-empty-when-no-testable-variants
     (testing "no :test variants → the widget renders the empty-state
               sub-line and skips the Run all button"
       (story/reg-variant :story.x/a {:tags #{:dev} :events []})
       (let [tree  (sidebar/test-widget (state/get-state)
                                        (state/registry-snapshot))
             empty (first (find-by-data-test tree "story-test-widget-empty"))
             btn   (first (find-by-data-test tree "story-test-widget-run-all"))]
         (is (some? empty))
         (is (nil? btn))))))

#?(:cljs
   (deftest sidebar-dot-reflects-per-variant-state
     (testing "the per-variant status dot reflects the variant's last-
               run state. Renders the dot component directly for each
               status keyword and asserts the rendered hiccup carries
               the expected `data-status` attribute."
       (let [dot-fail    (sidebar/status-dot :fail)
             dot-pending (sidebar/status-dot :pending)
             dot-pass    (sidebar/status-dot :pass)
             dot-running (sidebar/status-dot :running)]
         (is (= "fail"    (get (second dot-fail) :data-status)))
         (is (= "pending" (get (second dot-pending) :data-status)))
         (is (= "pass"    (get (second dot-pass) :data-status)))
         (is (= "running" (get (second dot-running) :data-status)))
         ;; aria-label round-trips for screen-reader users.
         (is (= "tests failing" (get (second dot-fail) :aria-label)))))))

;; ---- rf2-k3y92 — status-dot is decorative img (not a live region) -------

#?(:cljs
   (deftest sidebar-dot-uses-img-role-not-status
     (testing "rf2-k3y92 — the status-dot is a static decoration painted
               alongside the row label, not an out-of-band update channel.
               `role=\"status\"` adds an implicit `aria-live=\"polite\"`
               which made every mounted dot a live region — with ~50–200
               variant rows in a typical registry the AT noise was real.
               `role=\"img\"` keeps the `aria-label` exposed as the
               accessible name without the live-region announcement."
       (let [dot-fail (sidebar/status-dot :fail)
             dot-pass (sidebar/status-dot :pass)]
         (is (= "img" (get (second dot-fail) :role))
             "status-dot is exposed as an img with a label")
         (is (= "img" (get (second dot-pass) :role))
             "every status produces the same img role")
         (is (not= "status" (get (second dot-fail) :role))
             "must NOT use role=status (live-region noise)")))))

#?(:cljs
   (deftest widget-run-all-button-disabled-while-running
     (testing "if any variant is :running the Run all button disables"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (state/swap-state! state/mark-test-running :story.x/a)
       (let [tree (sidebar/test-widget (state/get-state)
                                       (state/registry-snapshot))
             btn  (first (find-by-data-test tree "story-test-widget-run-all"))]
         (is (some? btn))
         (is (true? (get (second btn) :disabled)))))))

;; ---- rf2-dtj61: 3-arity threads precomputed variant-ids ----------------

#?(:cljs
   (deftest widget-3-arity-uses-supplied-variant-ids
     (testing "the 3-arity overload uses the caller's supplied variant-ids
               instead of re-deriving from the registry — passes a
               restricted subset and asserts the headline counts reflect
               only that subset"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/b {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/c {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       ;; Supply a 1-variant subset. The registry has three; the widget
       ;; should report total=1 because we threaded a 1-element seq, not
       ;; the full registry-derived set.
       (let [tree     (sidebar/test-widget (state/get-state)
                                           (state/registry-snapshot)
                                           [:story.x/a])
             headline (first (find-by-data-test tree
                                                "story-test-widget-headline"))]
         (is (some? headline))
         ;; "Tests" headline reads "Tests" with a 1/1 or pending split;
         ;; the load-bearing assertion is that 3 variants did NOT land —
         ;; we did NOT see "0/3" or "0/2".
         (is (not (re-find #"/3" (nth headline 2))))
         (is (not (re-find #"/2" (nth headline 2))))))))

;; ---- regression: per-variant cell-overrides threading (rf2-zq6sn) -------

#?(:cljs
   (deftest run-opts-threads-per-variant-cell-overrides
     (testing "the chrome widget's Run-all path (run-opts-for-variant)
               threads each variant's OWN cell-overrides entry from
               shell state — the same lookup canvas / pane / share-url
               perform. The pre-fix bug passed `:cell-overrides nil`
               for every variant, dropping the user's controls-panel
               edits on a Run-all (rf2-zq6sn)."
       (let [shell (-> state/default-shell-state
                       (assoc :active-modes #{:dark}
                              :substrate :reagent)
                       (state/set-cell-override-scalar :story.x/a :n 5)
                       (state/set-cell-override-scalar :story.x/b :label "B"))
             opts-a (sidebar/run-opts-for-variant shell :story.x/a)
             opts-b (sidebar/run-opts-for-variant shell :story.x/b)
             opts-c (sidebar/run-opts-for-variant shell :story.x/c)]
         (testing ":story.x/a opts carry only :a's overrides"
           (is (= {:n 5} (:cell-overrides opts-a)))
           (is (= #{:dark} (:active-modes opts-a)))
           (is (= :reagent (:substrate opts-a))))
         (testing ":story.x/b opts carry only :b's overrides (NOT :a's)"
           (is (= {:label "B"} (:cell-overrides opts-b))))
         (testing "an unedited variant gets nil overrides (no leakage
                   from sibling variants)"
           (is (nil? (:cell-overrides opts-c))))))))

#?(:clj
   (deftest jvm-only-summary-from-fixture
     (testing "JVM corpus exercises the pure summary helper without
               booting Reagent — the fixture from the bead's
               description (3 pass / 1 fail / 1 pending) lands the
               same numbers as the CLJS path"
       (let [pass {:total 1 :passed 1 :failed 0 :skipped 0 :all-passed? true}
             fail {:total 1 :passed 0 :failed 1 :skipped 0 :all-passed? false}
             s    (-> state/default-shell-state
                      (state/record-test-run :story.x/a pass)
                      (state/record-test-run :story.x/b pass)
                      (state/record-test-run :story.x/c pass)
                      (state/record-test-run :story.x/d fail))
             summary (state/test-summary s
                       [:story.x/a :story.x/b :story.x/c :story.x/d :story.x/e])]
         (is (= {:total 5 :passed 3 :failed 1 :running 0 :pending 1
                 :all-green? false}
                summary))))))
