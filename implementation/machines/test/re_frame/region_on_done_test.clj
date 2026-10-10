(ns re-frame.region-on-done-test
  "A parallel REGION reaching its own `:final?` child raises the region-local
  done, and the region body's `:on-done` takes it, per Spec 005 §The
  done-state signal: in the same macrostep, declared at the region body, its
  target resolving within the region, never taken by a sibling region, and
  drained before the parallel root's `:on-done`. Registration refuses a
  region `:on-done` target outside the region and dangling guard / action
  refs in any of its candidates."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- recorders
  "An `:actions` map whose every entry appends its own name to `log`."
  [log ks]
  (into {} (map (fn [k] [k (fn [_] (swap! log conj k) nil)])) ks))

(defn- ran
  "Every captured action-ran as `[action-id phase decl-path region]`."
  []
  (mapv (fn [{{:keys [action-id phase decl-path region]} :tags}]
          [action-id phase decl-path region])
        (rf.machines.test-support/events-of :rf.machine/action-ran)))

(deftest region-reaching-its-final-child-runs-its-on-done
  (rf/reg-machine :rod/probe
    {:type    :parallel
     :actions {:mark (fn [_] nil) :y-done (fn [_] nil)}
     :regions {:x {:initial :x1
                   :on-done {:action :mark}
                   :states  {:x1 {:on {:go :x2}}
                             :x2 {:final? true}}}
               :y {:initial :y1
                   :on-done {:action :y-done}
                   :states  {:y1 {:on {:fin :y2}}
                             :y2 {:final? true}}}}})
  (rf/dispatch-sync [:rod/probe [:rf.machine/start]])
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:rod/probe [:go]])
  (is (= [[:mark :transition [] :x]] (ran))
      "only region :x's :on-done ran, in the macrostep reaching :x2, declared at the region body"))

(deftest region-on-done-target-resolves-within-the-region
  (let [log (atom [])]
    (rf/reg-machine :rod/loop
      {:type    :parallel
       :actions (recorders log [:x1-in :x2-in :x2-out :again])
       :regions {:x {:initial :x1
                     :on-done {:target :x1 :action :again}
                     :states  {:x1 {:entry :x1-in :on {:go :x2}}
                               :x2 {:entry :x2-in :exit :x2-out :final? true}}}
                 :y {:initial :y1 :states {:y1 {}}}}})
    (rf/dispatch-sync [:rod/loop [:rf.machine/start]])
    (reset! log [])
    (rf/dispatch-sync [:rod/loop [:go]])
    (is (= [:x2-in :x2-out :again :x1-in] @log)
        ":on-done :x1 names region :x's own :x1, taken in the same macrostep")))

(defn- with-on-done
  [on-done]
  {:type    :parallel
   :regions {:a {:initial :a1
                 :on-done on-done
                 :states  {:a1 {:on {:go :a2}}
                           :a2 {:final? true}}}
             :b {:initial :b1
                 :states  {:b1 {}}}}})

(defn- refusal
  [m]
  (try (rf.machines.lifecycle-fx.registration/make-machine-handler m) nil
       (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) ::message (ex-message e)))))

(deftest region-on-done-targets-are-checked-at-registration
  (let [d (refusal (with-on-done :b))]
    (is (= {:rf.error/id :rf.error/machine-unresolved-target :slot :on-done}
           (select-keys d [:rf.error/id :slot])))
    (is (re-find #"SIBLING REGION" (::message d))
        "a sibling region's name is refused, and the message says why")))

(deftest region-on-done-missing-refs-are-refused
  (is (= {:rf.error/id :rf.error/machine-unresolved-action :action :absent :state :a}
         (select-keys (refusal (with-on-done {:action :absent})) [:rf.error/id :action :state]))
      "the refusal names the declaring region")
  (is (= :rf.error/machine-unresolved-action
         (:rf.error/id (refusal (with-on-done [{:guard (fn [_] false) :target :a1}
                                               {:action :absent}]))))
      "every candidate is checked, not only the first"))

(deftest region-dones-drain-before-the-parallel-root-on-done
  (let [log (atom [])]
    (rf/reg-machine :rod/order
      {:type    :parallel
       :actions (recorders log [:x-done :y-done :root-done])
       :on      {:fin {:target [[:x :x2] [:y :y2]]}}
       :on-done {:action :root-done}
       :regions {:x {:initial :x1
                     :on-done {:action :x-done}
                     :states  {:x1 {} :x2 {:final? true}}}
                 :y {:initial :y1
                     :on-done {:action :y-done}
                     :states  {:y1 {} :y2 {:final? true}}}}})
    (rf/dispatch-sync [:rod/order [:rf.machine/start]])
    (rf/dispatch-sync [:rod/order [:fin]])
    (testing "each region's done runs in raise order, then the root's :on-done,
              and the machine rests all-final"
      (is (= [:x-done :y-done :root-done] @log))
      (is (= {:x :x2 :y :y2} (rf.machines.test-support/machine-state :rod/order))))
    (testing "a later event while resting re-runs neither"
      (reset! log [])
      (rf/dispatch-sync [:rod/order [:nothing]])
      (is (= [] @log)))))

(deftest a-region-on-done-leaving-its-final-child-holds-back-the-root-on-done
  (let [log (atom [])]
    (rf/reg-machine :rod/leave
      {:type    :parallel
       :actions (recorders log [:root-done])
       :on-done {:action :root-done}
       :regions {:x {:initial :x1
                     :on-done :x1
                     :states  {:x1 {:on {:go :x2}} :x2 {:final? true}}}
                 :y {:initial :y2
                     :states  {:y2 {:final? true}}}}})
    (rf/dispatch-sync [:rod/leave [:rf.machine/start]])
    (rf/dispatch-sync [:rod/leave [:go]])
    (is (= {:x :x1 :y :y2} (rf.machines.test-support/machine-state :rod/leave)))
    (is (= [] @log) "the settled configuration is not all-final")))

(deftest a-region-born-on-its-final-child-takes-its-done-at-birth
  (rf/reg-machine :rod/born
    {:type    :parallel
     :actions {:x-done (fn [_] nil)}
     :regions {:x {:initial :x1
                   :on-done {:action :x-done}
                   :states  {:x1 {:final? true}}}
               :y {:initial :y1 :states {:y1 {}}}}})
  (rf/dispatch-sync [:rod/born [:rf.machine/start]])
  (is (= [[:x-done :transition [] :x]] (ran))))
