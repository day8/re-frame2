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
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Routed through the shared `rf.machines.test-support/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
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
                 :a2 (fn [_] nil)}
       :states  {:idle {:on {:go [{:guard :one? :target :done :action :a0}
                                  {:guard :two? :target :done :action :a2}]}}
                 :done {}}})
    (let [evs (record-traces! (fn [] (rf/dispatch-sync [:slot/cand-on [:go]])))]
      (is (= [:on :go [:idle] 1 false]
             ((juxt :slot :event-key :decl-path :candidate-idx :root?) (action-ran-slot evs :a2)))))))

(deftest single-map-on-is-index-free
  (testing "a single-map `:on` (index-free, matching the macro's bare-slot
            keying) carries a nil candidate-idx"
    (rf/reg-machine :slot/single-on
      {:initial :idle
       :actions {:do-go (fn [_] nil)}
       :states  {:idle {:on {:go {:target :done :action :do-go}}}
                 :done {}}})
    (let [evs (record-traces! (fn [] (rf/dispatch-sync [:slot/single-on [:go]])))]
      (is (= [:on :go nil]
             ((juxt :slot :event-key :candidate-idx) (action-ran-slot evs :do-go)))))))

;; ---- (2) :always nonzero candidate -------------------------------------

(deftest always-nonzero-candidate-carries-index
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
  (let [evs (record-traces! (fn [] (rf/dispatch-sync [:slot/always [:go]])))]
    (is (= [:always [:b] 1]
           ((juxt :slot :decl-path :candidate-idx) (action-ran-slot evs :a1))))))

;; ---- (3) :after action — the exact delay-key ---------------------------

(deftest after-action-carries-delay-key
  (rf/reg-machine :slot/after
    {:initial :loading
     :actions {:do-timeout (fn [_] nil)}
     :states  {:loading {:after {1000 {:target :done :action :do-timeout}}}
               :done    {}}})
  (rf/dispatch-sync [:slot/after [:rf.machine/start]])
  (let [evs (record-traces!
              (fn [] (rf/dispatch-sync
                       [:slot/after [:rf.machine.timer/after-elapsed 1000 1 [:loading]]])))]
    (is (= [:after 1000 [:loading]]
           ((juxt :slot :delay-key :decl-path) (action-ran-slot evs :do-timeout))))))

;; ---- (4) root :on fallback — decl-path [] / :root? true ----------------

(deftest root-on-fallback-carries-root-flag
  (testing "a machine-root `:on` `:action` (the leaf→root fallback) stamps
            decl-path [] + :root? true so the Xray slot resolves
            root-relative (OUTSIDE :states)"
    (rf/reg-machine :slot/root-on
      {:initial :auth
       :actions {:do-logout (fn [_] nil)}
       :on      {:logout {:target :idle :action :do-logout}}
       :states  {:auth {}
                 :idle {}}})
    (let [evs (record-traces! (fn [] (rf/dispatch-sync [:slot/root-on [:logout]])))]
      (is (= [:on :logout [] true]
             ((juxt :slot :event-key :decl-path :root?) (action-ran-slot evs :do-logout)))))))

;; ---- boundary actions carry NO discriminator ---------------------------

(deftest boundary-actions-carry-no-slot
  (testing "`:exit` / `:entry` boundary actions carry no `:transition-slot`;
            the transition `:action` does"
    (rf/reg-machine :slot/boundary
      {:initial :idle
       :actions {:exit-idle  (fn [_] nil)
                 :enter-done (fn [_] nil)
                 :do-go      (fn [_] nil)}
       :states  {:idle {:exit :exit-idle
                        :on   {:go {:target :done :action :do-go}}}
                 :done {:entry :enter-done}}})
    (let [evs (record-traces! (fn [] (rf/dispatch-sync [:slot/boundary [:go]])))]
      (is (= [nil nil true]
             [(action-ran-slot evs :exit-idle)
              (action-ran-slot evs :enter-done)
              (some? (action-ran-slot evs :do-go))])))))
