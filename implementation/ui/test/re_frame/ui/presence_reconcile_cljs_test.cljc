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

(deftest incoming-reorder-is-ignored
  ;; The blessed order contract (rf2-1kb0v): children render in FIRST-APPEARANCE
  ;; order. EVERY kept key — not only a retained :unmounting one — holds its
  ;; committed slot, so an incoming reorder of still-:present keys changes
  ;; nothing, and a new key appends at the tail even when it arrives first in
  ;; the incoming vector.
  (let [committed [{:key "a" :phase :present} {:key "b" :phase :present}]]
    (testing "committed [a b] + incoming [b a] -> entries stay [a b], both :present"
      (is (= [["a" :present] ["b" :present]]
             (phases (presence/reconcile committed ["b" "a"])))))
    (testing "a prepended new key still appends at the tail: incoming [x a b] -> [a b x]"
      (is (= [["a" :present] ["b" :present] ["x" :mounting]]
             (phases (presence/reconcile committed ["x" "a" "b"])))))))

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

;; ---------------------------------------------------------------------------
;; Ownership identity — one key, one retained child (rf2-vxgfnd.96.2)
;;
;; A key IS a retained identity. `claim-identities` is the pure claim that runs
;; over the boundary's already-flattened children before `reconcile` sees them:
;; without it two children alias, reconcile derives two entries under one key,
;; and the single exit timer for that key removes both.
;; ---------------------------------------------------------------------------

(defn- claimed [pairs] (first (presence/claim-identities pairs)))
(defn- collided [pairs] (second (presence/claim-identities pairs)))

(deftest distinct-keys-pass-through-untouched
  (let [pairs [["a" :el-a] ["b" :el-b] ["c" :el-c]]]
    (is (= pairs (claimed pairs)) "no collision, no change — order preserved")
    (is (= [] (collided pairs)) "nothing to diagnose"))
  (is (= [[] []] (presence/claim-identities [])) "the empty boundary is fine"))

(deftest duplicate-keys-collapse-to-the-first-claimant
  (testing "explicit sibling collision — the FIRST child owns the identity"
    (let [pairs [["x" :first] ["x" :second]]]
      (is (= [["x" :first]] (claimed pairs)))
      (is (= ["x"] (collided pairs)) "the later claim is reported")))
  (testing "ADVERSARIAL: collision across separate flattened arrays, at depth"
    ;; two `for` sites whose dynamic keys happen to alias — the shape the
    ;; per-list-site duplicate check provably cannot see.
    (let [pairs [["a" :l1] ["b" :l1] ["b" :l2] ["c" :l2] ["a" :l2]]]
      (is (= [["a" :l1] ["b" :l1] ["c" :l2]] (claimed pairs))
          "each identity keeps its first claimant, in document order")
      (is (= ["b" "a"] (collided pairs))
          "every collision is reported, in the order it was found")))
  (testing "ADVERSARIAL: three children under one key report two collisions"
    (is (= ["k" "k"] (collided [["k" 1] ["k" 2] ["k" 3]])))))

(deftest reconcile-never-retains-two-children-under-one-key
  ;; the composed law — the reason the claim exists at all.
  (let [pairs   [["x" :first] ["x" :second] ["y" :other]]
        keys*   (mapv first (claimed pairs))
        entries (presence/reconcile [] keys*)]
    (is (= ["x" "y"] keys*) "the boundary offers each identity exactly once")
    (is (= [["x" :mounting] ["y" :mounting]] (phases entries)))
    (is (apply distinct? (map :key entries))
        "no two entries share a key — phase and exit-timer ownership stay 1:1"))
  (testing "and the claim is idempotent — a StrictMode double-render is stable"
    (let [pairs [["x" :first] ["x" :second]]]
      (is (= (claimed pairs) (claimed (claimed pairs)))))))
