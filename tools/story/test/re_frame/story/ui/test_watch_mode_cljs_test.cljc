(ns re-frame.story.ui.test-watch-mode-cljs-test
  "Tests for the chrome-level test widget's watch-mode auto-re-run
  (rf2-z1h0f — Storybook 9 Vitest-addon watch-toggle parity).

  Runs on both the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; ns-regexp
  `cljs-test$` picks up this ns because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `set-test-watch-mode` / `test-watch-
    mode?` / `record-test-content-hashes` / `watch-mode-drift`. The
    drift helper is the load-bearing primitive — the detector uses it
    to decide which variants need a re-run.
  - **CLJS-only**: the chrome widget renders the eye-icon toggle chip
    with the correct `aria-pressed` state; toggling on flips the chip
    on, off clears the recorded hashes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.ui.state :as state]
            #?@(:cljs [[re-frame.story.ui.shell   :as shell]
                       [re-frame.story.ui.sidebar :as sidebar]])))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!))

(use-fixtures :each {:before reset-all!})

;; ---- pure: watch-mode flag ----------------------------------------------

(deftest set-test-watch-mode-defaults-off
  (testing "fresh shell-state reads watch-mode off"
    (is (false? (state/test-watch-mode? state/default-shell-state)))))

(deftest set-test-watch-mode-toggles-on
  (testing "set-test-watch-mode true → flag flips on"
    (let [s (state/set-test-watch-mode state/default-shell-state true)]
      (is (true? (state/test-watch-mode? s))))))

(deftest set-test-watch-mode-toggle-off-clears-hashes
  (testing "toggle-off resets [:tests :content-hashes] so the next
            toggle-on seeds fresh from the current registry"
    (let [seeded (-> state/default-shell-state
                     (state/set-test-watch-mode true)
                     (state/record-test-content-hashes
                       {:story.x/a "deadbeef"}))
          off    (state/set-test-watch-mode seeded false)]
      (is (false? (state/test-watch-mode? off)))
      (is (= {} (get-in off [:tests :content-hashes]))))))

;; ---- pure: drift detection ----------------------------------------------

(deftest watch-mode-drift-no-change-empty
  (testing "current == prev → no drift, empty seq"
    (let [prev    {:story.x/a "aaaa" :story.x/b "bbbb"}
          current {:story.x/a "aaaa" :story.x/b "bbbb"}]
      (is (= [] (state/watch-mode-drift prev current))))))

(deftest watch-mode-drift-changed-variant
  (testing "one hash changed → that variant drifted"
    (let [prev    {:story.x/a "aaaa" :story.x/b "bbbb"}
          current {:story.x/a "aaaa" :story.x/b "cccc"}]
      (is (= [:story.x/b] (state/watch-mode-drift prev current))))))

(deftest watch-mode-drift-multiple-changes-sorted
  (testing "multiple changes → all drifted variants, sorted"
    (let [prev    {:story.x/a "1" :story.x/b "2" :story.x/c "3"}
          current {:story.x/a "X" :story.x/b "2" :story.x/c "Y"}]
      (is (= [:story.x/a :story.x/c]
             (state/watch-mode-drift prev current))))))

(deftest watch-mode-drift-new-variant-treated-as-drifted
  (testing "a variant present in current but absent from prev counts
            as drifted (a fresh registration that the user wants
            exercised)"
    (let [prev    {:story.x/a "aaaa"}
          current {:story.x/a "aaaa" :story.x/b "bbbb"}]
      (is (= [:story.x/b] (state/watch-mode-drift prev current))))))

(deftest watch-mode-drift-deregistered-variant-silent
  (testing "a variant present in prev but absent from current is
            silently dropped — there's nothing to re-run"
    (let [prev    {:story.x/a "aaaa" :story.x/b "bbbb"}
          current {:story.x/a "aaaa"}]
      (is (= [] (state/watch-mode-drift prev current))))))

(deftest watch-mode-drift-nil-prev-seeds-all
  (testing "a nil prev (toggle-on edge case) treats every current entry
            as drifted — but the watch-mode wiring seeds the slot on
            toggle-on so this case is a defensive guard"
    (let [current {:story.x/a "aaaa" :story.x/b "bbbb"}]
      (is (= [:story.x/a :story.x/b] (state/watch-mode-drift nil current))))))

;; ---- pure: record-test-content-hashes ----------------------------------

(deftest record-test-content-hashes-stamps-slot
  (testing "record-test-content-hashes writes into [:tests :content-hashes]"
    (let [s (state/record-test-content-hashes state/default-shell-state
                                              {:story.x/a "aaaa"})]
      (is (= {:story.x/a "aaaa"} (get-in s [:tests :content-hashes]))))))

(deftest record-test-content-hashes-nil-clears
  (testing "nil input clears the slot — used by toggle-off"
    (let [s (-> state/default-shell-state
                (state/record-test-content-hashes {:story.x/a "aaaa"})
                (state/record-test-content-hashes nil))]
      (is (= {} (get-in s [:tests :content-hashes]))))))

;; ---- CLJS-only: widget renders the watch toggle ------------------------

#?(:cljs
   (defn- find-by-data-test
     "Walk a hiccup tree and return every element whose props map has
     `:data-test` equal to `tag`."
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
   (deftest widget-renders-watch-toggle-off-by-default
     (testing "the chrome widget renders the watch chip with aria-
               pressed=false when watch mode is off"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (let [tree (sidebar/test-widget (state/get-state)
                                       (state/registry-snapshot))
             chip (first (find-by-data-test tree "story-test-widget-watch-toggle"))]
         (is (some? chip))
         (is (= "false" (get (second chip) :aria-pressed)))
         (is (= "off"   (get (second chip) :data-state)))))))

#?(:cljs
   (deftest widget-renders-watch-toggle-on-when-flag-on
     (testing "with [:tests :watch-mode?] true the chip reads aria-pressed=
               true and the data-state attribute reads 'on'"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (state/swap-state! state/set-test-watch-mode true)
       (let [tree (sidebar/test-widget (state/get-state)
                                       (state/registry-snapshot))
             chip (first (find-by-data-test tree "story-test-widget-watch-toggle"))]
         (is (some? chip))
         (is (= "true" (get (second chip) :aria-pressed)))
         (is (= "on"   (get (second chip) :data-state)))))))

#?(:cljs
   (deftest widget-hides-watch-toggle-when-no-testable-variants
     (testing "no :test variants → the widget renders the empty-state
               sub-line and the watch chip is absent (the chip lives
               beneath the count chips and only renders when there's
               something to watch)"
       (story/reg-variant :story.x/a {:tags #{:dev} :events []})
       (let [tree (sidebar/test-widget (state/get-state)
                                       (state/registry-snapshot))
             chip (first (find-by-data-test tree "story-test-widget-watch-toggle"))]
         (is (nil? chip))))))

;; ---- watch-rerun! re-runs only the drifted variants --------------------
;;
;; This test exercises the public surface that the shell's detector
;; calls — `sidebar/watch-rerun!` for the drifted variant-ids. We assert
;; that calling it stamps `:running` for each variant before the async
;; result lands. The async resolution is covered by the existing test-
;; widget tests' 'Run all' coverage; the drift→rerun wiring is the new
;; surface here.

#?(:cljs
   (deftest watch-rerun-stamps-running-for-each-variant
     (testing "watch-rerun! marks every passed variant :running up
               front so the sidebar dots flip yellow in unison — the
               same contract as 'Run all', but driven by the detector"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (story/reg-variant :story.x/b {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (sidebar/watch-rerun! [:story.x/a :story.x/b])
       ;; Both variants stamp :running synchronously before the async
       ;; resolution lands.
       (let [s (state/get-state)]
         (is (= :running (get-in s [:tests :runs :story.x/a :status])))
         (is (= :running (get-in s [:tests :runs :story.x/b :status])))))))

#?(:cljs
   (deftest watch-rerun-empty-seq-noop
     (testing "watch-rerun! on an empty seq is a no-op — the detector
               only calls it when drift is detected, but the function
               handles the no-drift edge gracefully"
       (story/reg-variant :story.x/a {:tags #{:test} :events []
                                      :play [[:rf.assert/path-equals [:c] 0]]})
       (sidebar/watch-rerun! [])
       (let [s (state/get-state)]
         (is (nil? (get-in s [:tests :runs :story.x/a])))))))

;; ---- rf2-mclvi regression: cell-override edit triggers a re-run -------
;;
;; Before rf2-mclvi the watch-mode hash cache key explicitly omitted
;; `:cell-overrides` while `snapshot-tuple` (via `resolve-args`) DID
;; thread overrides into the hash input. The cache served stale answers
;; on every control edit; the detector saw no drift and missed the
;; re-run. The cache now includes `:cell-overrides` in the key + threads
;; per-variant overrides into the snapshot-identity opts.

#?(:cljs
   (deftest cell-override-edit-perturbs-watch-mode-hash
     (testing "editing :cell-overrides on a :test-tagged variant
               produces a fresh content-hash via detect-watch-drift!
               and stamps the variant :running (rf2-mclvi)"
       (story/reg-variant :story.x/a
         {:component :app.ui/echo
          :args      {:n 0}
          :tags      #{:test}
          :events    []
          :play      [[:rf.assert/path-equals [:c] 0]]})
       ;; Flip watch-mode on and seed the initial hashes with no overrides.
       (state/swap-state! state/set-test-watch-mode true)
       (state/swap-state!
         (fn [s]
           (state/record-test-content-hashes
             s
             ;; Seed from the current registry — these are the baseline
             ;; hashes the detector diffs against.
             {})))
       ;; First detection pass — should seed hashes + (because prev is
       ;; empty) treat every testable variant as drifted, re-running it.
       (shell/detect-watch-drift!)
       (let [seeded-hashes (get-in (state/get-state) [:tests :content-hashes])]
         (is (contains? seeded-hashes :story.x/a)
             "after the first pass the slot is seeded from the registry"))
       ;; Re-seed against the post-baseline so the next pass starts from
       ;; the registry's current truth; cell-edit happens after.
       (let [post-seed (get-in (state/get-state) [:tests :content-hashes])
             baseline  (get post-seed :story.x/a)]
         ;; Drop the per-variant :running stamp so we can detect a fresh one.
         (state/swap-state! state/clear-test-run :story.x/a)
         ;; Now simulate a control-panel edit — write into :cell-overrides.
         (state/swap-state! state/set-cell-override :story.x/a [:n] 42)
         (shell/detect-watch-drift!)
         (let [after-hashes (get-in (state/get-state) [:tests :content-hashes])
               after-hash   (get after-hashes :story.x/a)
               run-state    (get-in (state/get-state) [:tests :runs :story.x/a])]
           (testing "the recomputed hash differs from the baseline (cell-overrides
                     ARE threaded through snapshot-tuple)"
             (is (not= baseline after-hash)
                 (str "expected fresh hash after cell-override edit; got "
                      (pr-str baseline) " → " (pr-str after-hash))))
           (testing "the detector dispatched watch-rerun! → :running stamp lands"
             (is (= :running (:status run-state))
                 (str "expected the variant marked :running after the cell-
                       override edit; run-state was " (pr-str run-state)))))))))
