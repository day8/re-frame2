(ns re-frame.story.run-result-hashes-test
  "`story/run` results carry the snapshot identity the frozen run-result
  contract promises — `:plan-hash` and `:run-hash` (rf2-7vz97,
  `tools/story/spec/017-Testing-Story.md` §Run result).

  JVM-only (`.clj`): each run is driven to completion with
  `deref-blocking`. The CLJS runner assembles its result through the SAME
  `.cljc` path (`re-frame.story.runtime` → `re-frame.story.result/run-result`).

  Assertions are about PRESENCE, AGREEMENT and DETERMINISM — never a literal
  digest. `:plan-hash` hashes the plan's `:world`, which carries
  `:effective-args`, so its VALUE legitimately moves when arg resolution
  changes; a pinned hex string would read that as a regression."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.render :as rf.story.render]))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (reset! rf.story.play.runner-events/run-state {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :hashes/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(def ^:private loaded-script
  {:script [[:dispatch-sync [:hashes/set-status :loaded]]
            [:assert-db [:status] :loaded]]})

(defn- run-blocking [target]
  (rf.story.async/deref-blocking (rf.story/run target) 30000))

(deftest registered-variant-run-carries-both-hashes
  (rf.story/reg-variant :story.hashes/loaded {:tags #{:test} :script loaded-script})
  (let [r1 (run-blocking :story.hashes/loaded)
        r2 (run-blocking :story.hashes/loaded)]
    (is (= :pass (:status r1)) "non-vacuity: a real run, not an error shape")
    (testing "both identity slots are present strings"
      (is (string? (:plan-hash r1)))
      (is (string? (:run-hash r1))))
    (testing "the result still conforms to the frozen schema"
      (is (rf.story/valid-run-result? r1)
          (str (rf.story/explain-run-result r1))))
    (testing ":plan-hash is the compiled plan's, by the public primitive"
      (is (= (rf.story/plan-hash (rf.story/variant-plan :story.hashes/loaded))
             (:plan-hash r1))))
    (testing ":plan-hash agrees with render-variant over the same plan"
      (is (= (:plan-hash (rf.story.render/prepare-render :story.hashes/loaded))
             (:plan-hash r1))))
    (testing ":run-hash is the public primitive over the result's own slice"
      (is (= (rf.story/run-hash r1) (:run-hash r1))))
    (testing "rerunning the same scenario reproduces both"
      (is (= (:plan-hash r1) (:plan-hash r2)))
      (is (= (:run-hash r1) (:run-hash r2))))))

(deftest a-different-scenario-moves-both-hashes
  (rf.story/reg-variant :story.hashes/loaded {:tags #{:test} :script loaded-script})
  (rf.story/reg-variant :story.hashes/idle
    {:tags   #{:test}
     :script {:script [[:dispatch-sync [:hashes/set-status :idle]]]}})
  (let [loaded (run-blocking :story.hashes/loaded)
        idle   (run-blocking :story.hashes/idle)]
    (is (not= (:plan-hash loaded) (:plan-hash idle)) "a different script is a different plan")
    (is (not= (:run-hash loaded) (:run-hash idle)) "different evidence is a different run")))

(deftest inline-plan-run-carries-both-hashes
  (let [plan {:story/id :story.hashes :script loaded-script}
        r1   (run-blocking plan)
        r2   (run-blocking plan)]
    (is (= :pass (:status r1)) "non-vacuity: a real run, not an error shape")
    (is (string? (:plan-hash r1)))
    (is (string? (:run-hash r1)))
    (is (= (:plan-hash r1) (:plan-hash r2))
        "the minted anonymous frame id does not leak into the plan identity")
    (is (= (:run-hash r1) (:run-hash r2)))))

(deftest story-level-args-run-and-render-agree
  ;; rf2-851t0 — the run compiles WITH the ambient arg layers, so render prep
  ;; must too: otherwise a story-level arg is missing from render's
  ;; `:effective-args`, and so from its `:plan-hash` (which hashes `:world`),
  ;; and a placeholder only the story resolves throws. The agreement check in
  ;; `registered-variant-run-carries-both-hashes` cannot see this, because
  ;; that variant has no args for the two paths to disagree about.
  ;; `plain` uses no placeholder, so without the fold render still compiles
  ;; and the defect shows as the hash disagreement itself; `inherits` uses
  ;; one, so without the fold render prep throws.
  (rf.story/reg-story :story.hashes.args {:args {:status :loaded}})
  (rf.story/reg-variant :story.hashes.args/plain {:tags #{:test} :script loaded-script})
  (rf.story/reg-variant :story.hashes.args/inherits
    {:tags   #{:test}
     :script {:script [[:dispatch-sync [:hashes/set-status [:arg :status]]]
                       [:assert-db [:status] :loaded]]}})
  (doseq [id [:story.hashes.args/plain :story.hashes.args/inherits]]
    (testing (str id)
      (let [run      (run-blocking id)
            prepared (rf.story.render/prepare-render id)]
        (is (= :pass (:status run)) "non-vacuity: a real run, not an error shape")
        (testing "render prep carries the story-level arg the run executed with"
          (is (= {:status :loaded} (:effective-args prepared)))
          (is (= (:effective-args run) (:effective-args prepared))))
        (testing ":plan-hash agrees between run and render"
          (is (string? (:plan-hash prepared)))
          (is (= (:plan-hash run) (:plan-hash prepared))))))))
