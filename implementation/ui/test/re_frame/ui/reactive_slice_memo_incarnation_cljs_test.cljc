(ns re-frame.ui.reactive-slice-memo-incarnation-cljs-test
  "rf2-vxgfnd.160 — the module-wide slice probe memo (`current-slice-memo`) must
  carry the EXACT frame incarnation, so a COMMIT-FREE reader never receives a
  destroyed incarnation's memoized value.

  `re-frame.ui.reactive/sub-read` outside a live cell is a one-shot headless
  read: it probes ownership-free through the module slice memo (the same handle
  a Tier-1 `ui.test/render` seeds — 03 §3). Outside a ViewCell there is NO
  commit step 5 to correct a stale memo hit. The
  `(frame, frame-epoch, registry-epoch)` tag TIES across a same-id
  destroy+recreate (the commit epoch restarts), so the memo tag must ALSO carry
  the incarnation token — validated by identity — for the commit-free path.

  `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`), so the module memo's incarnation-completeness is
  graft-checked on both hosts against the REAL observation port. The core-port
  primitive counterpart lives in `re-frame.observation-port-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(deftest commit-free-sub-read-does-not-serve-a-destroyed-incarnations-memo
  ;; The exact commit-free repro: seed A into the module memo with one headless
  ;; sub-read, reincarnate to same-id B with epochs that tie, then read again
  ;; WITHOUT resetting the scheduler. The second read must return B — the module
  ;; memo's incarnation-complete tag is what prevents A's stale parent hit.
  (rf/reg-sub :sm/parent (fn [db _] (:value db)))
  (rf/reg-sub :sm/value :<- [:sm/parent] (fn [v _] v))
  (let [mfid :sm/frame]
    ;; incarnation A — one state commit
    (live-frame/make-frame {:id mfid})
    (frame/replace-app-db! mfid {:value :A})
    (let [token-a (frame/frame-incarnation-token mfid)
          fe-a    (frame/frame-commit-epoch mfid)
          ;; a commit-free headless read seeds A into the MODULE slice memo
          va      (rf/with-frame mfid (reactive/sub-read [:sm/value]))]
      (is (= :A va) "A's value seeds the module memo")
      ;; destroy A; recreate same-id B with an EQUAL-epoch commit
      (frame/destroy-frame! mfid)
      (live-frame/make-frame {:id mfid})
      (frame/replace-app-db! mfid {:value :B})
      (testing "precondition — B ties A on the pre-token tag"
        (is (not (identical? token-a (frame/frame-incarnation-token mfid)))
            "B is a DISTINCT incarnation")
        (is (= fe-a (frame/frame-commit-epoch mfid))
            "frame-commit-epoch ties across the reincarnation"))
      ;; NO reset-scheduler! — the module handle persists (JVM) / is reused
      (let [vb (rf/with-frame mfid (reactive/sub-read [:sm/value]))]
        (is (= :B vb)
            "the commit-free re-read returns B — never A's stale memoized parent")))))

(deftest same-incarnation-commit-free-reads-are-unaffected
  ;; The economy still holds within ONE incarnation: repeated commit-free reads
  ;; against the same live incarnation return the live value and share the memo.
  (rf/reg-sub :sm/parent (fn [db _] (:value db)))
  (rf/reg-sub :sm/value :<- [:sm/parent] (fn [v _] v))
  (let [mfid :sm/frame2]
    (live-frame/make-frame {:id mfid})
    (frame/replace-app-db! mfid {:value :one})
    (is (= :one (rf/with-frame mfid (reactive/sub-read [:sm/value]))))
    (frame/replace-app-db! mfid {:value :two})
    (is (= :two (rf/with-frame mfid (reactive/sub-read [:sm/value])))
        "a live-incarnation state commit still moves the value (tag epoch bumps)")))
