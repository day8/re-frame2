(ns re-frame.transition-slot-spec-path-test
  "The substrate stamps the selected transition's EXACT spec-path
  DISCRIMINATOR (`:transition-slot`) on the `:rf.machine/action-ran` trace
  so Xray's cascade rows can address the precise inline-source slot
  (candidate index, `:after` delay-key, root-vs-state) directly, rather
  than reconstructing the path from `source-state` / `event` / `phase` —
  a reconstruction that cannot pin the candidate index, name the delay-key,
  or distinguish a root `:on` from a `:states`-prefixed one.

  These tests drive the four enumerated cases through the live runtime
  and pin the discriminator the trace carries. The Xray-side
  discriminator → spec-path conversion is unit-tested in
  `day8.re-frame2-xray.panels.epoch.projection-cljs-test`
  (`transition-slot->spec-prefix` + the `cascade-row-source-key`
  per-case tests)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Routed through the shared `mtest/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces! [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- action-ran-slot
  "Return the `:transition-slot` discriminator off the action-ran trace
  for `action-id`, or nil."
  [evs action-id]
  (some (fn [ev]
          (when (and (= :rf.machine/action-ran (:operation ev))
                     (= action-id (-> ev :tags :action-id)))
            (-> ev :tags :transition-slot)))
        evs))

;; ---- (1) candidate-vector :on — the matched candidate's index ----------

(deftest candidate-vector-on-carries-matched-index
  (testing "an inline `:action` on a multi-candidate `:on` VECTOR stamps
            the EXACT matched candidate index (not 0) on its action-ran
            trace"
    (rf/reg-machine :slot/cand-on
      {:initial :idle
       :data    {:n 2}
       :guards  {:one? (fn [{d :data}] (= 1 (:n d)))
                 :two? (fn [{d :data}] (= 2 (:n d)))}
       :actions {:a0 (fn [_] nil)
                 :a1 (fn [_] nil)
                 :a2 (fn [_] nil)}
       :states  {:idle {:on {:go [{:guard :one? :target :done :action :a0}
                                  {:guard :two? :target :done :action :a2}]}}
                 :done {}}})
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:slot/cand-on [:go]])))
          slot (action-ran-slot evs :a2)]
      (is (some? slot) ":a2 (the second candidate) fired")
      (is (= :on (:slot slot)))
      (is (= :go (:event-key slot)))
      (is (= [:idle] (:decl-path slot)))
      (is (= 1 (:candidate-idx slot))
          "the matched candidate is index 1, NOT 0")
      (is (false? (:root? slot))))))

(deftest single-map-on-is-index-free
  (testing "a single-map `:on` (index-free, matching the macro's bare-slot
            keying) carries a nil candidate-idx"
    (rf/reg-machine :slot/single-on
      {:initial :idle
       :actions {:do-go (fn [_] nil)}
       :states  {:idle {:on {:go {:target :done :action :do-go}}}
                 :done {}}})
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:slot/single-on [:go]])))
          slot (action-ran-slot evs :do-go)]
      (is (= :on (:slot slot)))
      (is (= :go (:event-key slot)))
      (is (nil? (:candidate-idx slot))
          "single-map :on is index-free (bare-slot keying)"))))

;; ---- (2) :always nonzero candidate -------------------------------------

(deftest always-nonzero-candidate-carries-index
  (testing "an inline `:always` `:action` on a multi-candidate VECTOR
            stamps the matched nonzero candidate index"
    (rf/reg-machine :slot/always
      {:initial :a
       :data    {:pick 1}
       :guards  {:p0? (fn [{d :data}] (= 0 (:pick d)))
                 :p1? (fn [{d :data}] (= 1 (:pick d)))}
       :actions {:a0 (fn [_] nil)
                 :a1 (fn [_] nil)}
       :states  {:a {:on {:go {:target :b}}}
                 :b {:always [{:guard :p0? :target :c :action :a0}
                              {:guard :p1? :target :c :action :a1}]}
                 :c {}}})
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:slot/always [:go]])))
          slot (action-ran-slot evs :a1)]
      (is (some? slot) ":a1 (the second :always candidate) fired")
      (is (= :always (:slot slot)))
      (is (= [:b] (:decl-path slot)))
      (is (= 1 (:candidate-idx slot))
          "the matched :always candidate is index 1"))))

;; ---- (3) :after action — the exact delay-key ---------------------------

(deftest after-action-carries-delay-key
  (testing "an inline `:after` `:action` stamps the EXACT delay-key on its
            action-ran trace (the delay-key the reconstruct path could not
            name)"
    (rf/reg-machine :slot/after
      {:initial :loading
       :actions {:do-timeout (fn [_] nil)}
       :states  {:loading {:after {1000 {:target :done :action :do-timeout}}}
                 :done    {}}})
    (let [_    (rf/dispatch-sync [:slot/after [:rf.machine/start]])
          evs  (record-traces!
                 (fn []
                   (rf/dispatch-sync
                     [:slot/after [:rf.machine.timer/after-elapsed 1000 1 [:loading]]])))
          slot (action-ran-slot evs :do-timeout)]
      (is (some? slot) ":do-timeout fired")
      (is (= :after (:slot slot)))
      (is (= 1000 (:delay-key slot))
          "the discriminator names the exact :after delay-key")
      (is (= [:loading] (:decl-path slot))))))

;; ---- (4) root :on fallback — decl-path [] / :root? true ----------------

(deftest root-on-fallback-carries-root-flag
  (testing "a machine-root `:on` `:action` (the leaf→root fallback) stamps
            decl-path [] + :root? true so the Xray slot resolves
            root-relative (OUTSIDE :states)"
    (rf/reg-machine :slot/root-on
      {:initial :auth
       :actions {:do-logout (fn [_] nil)}
       ;; Root-level :on — every descendant inherits it; no state-node
       ;; handles :logout, so the leaf→root walk falls to the root.
       :on      {:logout {:target :idle :action :do-logout}}
       :states  {:auth {}
                 :idle {}}})
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:slot/root-on [:logout]])))
          slot (action-ran-slot evs :do-logout)]
      (is (some? slot) ":do-logout fired from the root :on fallback")
      (is (= :on (:slot slot)))
      (is (= :logout (:event-key slot)))
      (is (= [] (:decl-path slot))
          "root :on has an empty decl-path (outside :states)")
      (is (true? (:root? slot))))))

;; ---- boundary actions carry NO discriminator ---------------------------

(deftest boundary-actions-carry-no-slot
  (testing "`:exit` / `:entry` boundary actions are NOT transition actions —
            they carry no `:transition-slot` (the reconstruct-from-phase
            path handles them via :source-state / :target-state)"
    (rf/reg-machine :slot/boundary
      {:initial :idle
       :actions {:exit-idle (fn [_] nil)
                 :enter-done (fn [_] nil)
                 :do-go     (fn [_] nil)}
       :states  {:idle {:exit :exit-idle
                        :on   {:go {:target :done :action :do-go}}}
                 :done {:entry :enter-done}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:slot/boundary [:go]])))]
      (is (nil? (action-ran-slot evs :exit-idle))
          ":exit action carries no :transition-slot")
      (is (nil? (action-ran-slot evs :enter-done))
          ":entry action carries no :transition-slot")
      (is (some? (action-ran-slot evs :do-go))
          "but the transition :action DOES carry the discriminator"))))
