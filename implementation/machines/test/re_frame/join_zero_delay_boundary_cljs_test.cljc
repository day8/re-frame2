(ns re-frame.join-zero-delay-boundary-cljs-test
  "rf2-21hsb1 — preserve the zero-delay scheduling boundary for join completions.

  #5881 routes join completions through the shared reserved-dispatch path, but
  `join-dispatch-fx` forwarded `:ms` only when `(pos? ms)`. An application-
  authored `:dispatch-later {:ms 0 ...}` completion therefore lost its delayed
  classification and folded IMMEDIATELY in the current drain. The canonical
  `re-frame.fx/child-dispatch!` path treats every numeric `:ms`, INCLUDING zero,
  as host-clock-delayed (Spec 002 §long-running work uses zero delay to yield
  through the host clock for a render/scheduling opportunity). Collapsing zero
  into immediate dispatch changed event order, drain batching, render
  opportunities, frame-owned timer cancellation, and observability, and dropped
  `:source-detail {:ms 0}`.

  The fix forwards `:ms` + delayed source-detail for EVERY numeric delay; a
  direct `:dispatch` (no numeric `:ms`) remains immediate.

  These fixtures control the host clock deterministically through the
  `rf.interop/set-timeout!` seam (capturing the deferred callback without firing or
  scheduling), so they pin the boundary identically on CLJ and CLJS.

  MUTATION TOOTH: restoring the `(pos? ms)` guard folds a zero-delay completion
  synchronously, failing `zero-delay-completion-defers-not-immediate`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.interop :as rf.interop]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- mk-child
  "A child completing through a DIRECT `:dispatch` (no numeric `:ms`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-child-delayed
  "A child completing through a `:dispatch-later` with delay `ms`."
  [parent-id ms]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch-later {:ms    ms
                                                      :event [parent-id [:child/done (:id data)]]}]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

;; ---------------------------------------------------------------------------
;; (1) zero-delay :dispatch-later DEFERS — the mutation tooth.
;; ---------------------------------------------------------------------------

(deftest zero-delay-completion-defers-not-immediate
  (testing "rf2-21hsb1 — a `:dispatch-later {:ms 0 ...}` join completion is armed
            on the host clock (numeric `:ms` 0 forwarded to `child-dispatch!`)
            and does NOT fold synchronously in the current drain. Restoring the
            `(pos? ms)` guard would fold it immediately, failing this test on CLJ
            and CLJS."
    (rf/reg-machine :hsb.z/ca (mk-child-delayed :hsb.z/rp 0))
    (rf/reg-machine :hsb.z/cb (mk-child-delayed :hsb.z/rp 0))
    (reg-parent! :hsb.z/rp :hsb.z/ca :hsb.z/cb)
    (rf/dispatch-sync [:hsb.z/rp [:start]])
    (let [a           (get-in (join-state :hsb.z/rp) [:children :a])
          captured-cb (atom nil)
          captured-ms (atom ::none)]
      (with-redefs [rf.interop/set-timeout!   (fn [f ms] (reset! captured-cb f) (reset! captured-ms ms) ::handle)
                    rf.interop/clear-timeout! (fn [_] nil)]
        (rf/dispatch-sync [a [:go]]))
      (is (= 0 @captured-ms)
          "the numeric :ms 0 was forwarded to the host-clock timer (host-clock-delayed)")
      (is (some? @captured-cb)
          "the zero-delay completion armed a frame-owned delayed dispatch")
      (is (= #{} (:done (join-state :hsb.z/rp)))
          "it did NOT fold synchronously in the current drain (async boundary)")
      (is (not (true? (:resolved? (join-state :hsb.z/rp))))
          "the join did not resolve on the un-folded (deferred) completion"))))

;; ---------------------------------------------------------------------------
;; (2) a DIRECT :dispatch completion is IMMEDIATE — no timer, no delayed detail.
;; ---------------------------------------------------------------------------

(deftest direct-dispatch-completion-is-immediate
  (testing "rf2-21hsb1 — a direct `:dispatch` join completion carries no numeric
            `:ms`, so it enqueues immediately and folds in the current drain — no
            host-clock timer is armed and no synthetic delayed metadata is
            attached."
    (rf/reg-machine :hsb.d/ca (mk-child :hsb.d/rp))
    (rf/reg-machine :hsb.d/cb (mk-child :hsb.d/rp))
    (reg-parent! :hsb.d/rp :hsb.d/ca :hsb.d/cb)
    (rf/dispatch-sync [:hsb.d/rp [:start]])
    (let [a           (get-in (join-state :hsb.d/rp) [:children :a])
          armed?      (atom false)]
      (with-redefs [rf.interop/set-timeout!   (fn [_f _ms] (reset! armed? true) ::handle)
                    rf.interop/clear-timeout! (fn [_] nil)]
        (rf/dispatch-sync [a [:go]]))
      (is (false? @armed?)
          "a direct :dispatch completion armed NO host-clock timer")
      (is (= #{:a} (:done (join-state :hsb.d/rp)))
          "the direct :dispatch completion folded synchronously in the current drain"))))

;; ---------------------------------------------------------------------------
;; (3) a POSITIVE-delay :dispatch-later completion also DEFERS (parity w/ ms=0).
;; ---------------------------------------------------------------------------

(deftest positive-delay-completion-defers
  (testing "rf2-21hsb1 — a positive-delay `:dispatch-later {:ms 500 ...}`
            completion also defers on the host clock and does not fold
            synchronously (the fix keeps every numeric delay — 0 and positive —
            host-clock-delayed, uniformly)."
    (rf/reg-machine :hsb.p/ca (mk-child-delayed :hsb.p/rp 500))
    (rf/reg-machine :hsb.p/cb (mk-child-delayed :hsb.p/rp 500))
    (reg-parent! :hsb.p/rp :hsb.p/ca :hsb.p/cb)
    (rf/dispatch-sync [:hsb.p/rp [:start]])
    (let [a           (get-in (join-state :hsb.p/rp) [:children :a])
          captured-cb (atom nil)
          captured-ms (atom ::none)]
      (with-redefs [rf.interop/set-timeout!   (fn [f ms] (reset! captured-cb f) (reset! captured-ms ms) ::handle)
                    rf.interop/clear-timeout! (fn [_] nil)]
        (rf/dispatch-sync [a [:go]]))
      (is (= 500 @captured-ms) "the positive :ms was forwarded to the host-clock timer")
      (is (some? @captured-cb) "the positive-delay completion armed a delayed dispatch")
      (is (= #{} (:done (join-state :hsb.p/rp)))
          "the positive-delay completion did NOT fold synchronously"))))
