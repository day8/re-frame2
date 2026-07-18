(ns re-frame.ui.presence-reconcile-cljs-test
  "The PURE three-phase entry derivation behind the presence boundary
  (re-frame.ui.presence/reconcile). Host-agnostic (`.cljc`), so the enter →
  present → exit → re-entry state machine is pinned without React on either
  host (Spec 004 §Presence)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.presence-runtime :as presence]))

(defn- phases [entries] (mapv (juxt :key :phase) entries))

;; ---------------------------------------------------------------------------
;; Enter — a brand-new key mounts
;; ---------------------------------------------------------------------------

(deftest new-key-enters-mounting
  (testing "a key present for the first time is appended as :mounting"
    (is (= [["a" :mounting]] (phases (presence/reconcile [] ["a"]))))
    (is (= [["a" :mounting] ["b" :mounting]]
           (phases (presence/reconcile [] ["a" "b"]))))))

(deftest committed-mounting-stays-mounting-until-flipped
  ;; reconcile does not flip :mounting → :present (that is the boundary effect's
  ;; job); it preserves a still-incoming key's committed phase.
  (is (= [["a" :mounting]]
         (phases (presence/reconcile [{:key "a" :phase :mounting}] ["a"]))))
  (is (= [["a" :present]]
         (phases (presence/reconcile [{:key "a" :phase :present}] ["a"])))))

;; ---------------------------------------------------------------------------
;; Exit — a removed key is RETAINED :unmounting (not dropped)
;; ---------------------------------------------------------------------------

(deftest removed-key-is-retained-unmounting
  (testing "a present key no longer incoming becomes :unmounting, held in slot"
    (is (= [["a" :unmounting]]
           (phases (presence/reconcile [{:key "a" :phase :present}] [])))))
  (testing "an already-:unmounting key stays :unmounting while retained"
    (is (= [["a" :unmounting]]
           (phases (presence/reconcile [{:key "a" :phase :unmounting}] [])))))
  (testing "reconcile NEVER drops a key — removal is the timer's job, not here"
    (is (= 1 (count (presence/reconcile [{:key "a" :phase :unmounting}] []))))))

;; ---------------------------------------------------------------------------
;; Re-entry — removal-then-reinsertion interrupts the exit
;; ---------------------------------------------------------------------------

(deftest reinserted-key-reenters-present
  (testing "an :unmounting key that reappears flips back to :present (re-entry)"
    (is (= [["a" :present]]
           (phases (presence/reconcile [{:key "a" :phase :unmounting}] ["a"]))))))

;; ---------------------------------------------------------------------------
;; Order — retained exiting keys hold their slot; new keys append
;; ---------------------------------------------------------------------------

(deftest retained-keys-hold-slot-new-keys-append
  (let [committed [{:key "a" :phase :present}
                   {:key "b" :phase :present}
                   {:key "c" :phase :present}]
        ;; b leaves, d arrives
        next (presence/reconcile committed ["a" "c" "d"])]
    (is (= [["a" :present] ["b" :unmounting] ["c" :present] ["d" :mounting]]
           (phases next))
        "b holds its slot as :unmounting; d appends at the end")))

;; ---------------------------------------------------------------------------
;; Idempotence — a StrictMode double-render is harmless
;; ---------------------------------------------------------------------------

(deftest reconcile-is-idempotent-for-fixed-incoming
  (let [committed [{:key "a" :phase :present} {:key "b" :phase :mounting}]
        once  (presence/reconcile committed ["a" "b"])
        twice (presence/reconcile once ["a" "b"])]
    (is (= (phases once) (phases twice))
        "re-running reconcile with the same incoming keys is stable")))

;; ---------------------------------------------------------------------------
;; presence-phase — :present outside a boundary (host-agnostic default)
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest presence-phase-is-present-on-jvm
     (is (= :present (presence/presence-phase))
         "the JVM structural subset always reads :present")))
