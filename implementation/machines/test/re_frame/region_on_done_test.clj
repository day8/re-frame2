(ns re-frame.region-on-done-test
  "A parallel REGION reaching its own `:final?` child raises the region-local
  done, and the region body's `:on-done` takes it — per Spec 005 §The
  done-state signal (Parallel `:on-done`) and §`:final?` constraints. The
  region body is the root of its region's tree and a compound in its own
  right, so this is the compound case scoped to one region.

  - The region's `:on-done` runs in the macrostep that reaches the final
    child, at phase `:transition`, declared at the region body (`:decl-path
    []`, `:region` the region).
  - Its target resolves within the region, like the region root's `:on`: a
    keyword names a top-level state of that region. Registration refuses a
    target outside the region — a sibling region's name included — with
    `:rf.error/machine-unresolved-target`.
  - A sibling region does not take another region's done, even when it
    declares an `:on-done` of its own.
  - Ordering against the parallel root: region-local dones drain inside the
    macrostep, in the order the regions raised them; the root's `:on-done`
    then fires once, on the settled configuration, if every region is final.
  - A region born on its final child takes its done at birth.

  Live runtime (plain-atom substrate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
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
  (let [log (atom [])]
    (rf/reg-machine :rod/probe
      {:type    :parallel
       :data    {}
       :actions (assoc (recorders log [:y-done])
                       :mark (fn [{d :data}]
                               (swap! log conj :mark)
                               {:data (assoc d :marked? true)}))
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
    (testing "region :x's :on-done ran, in the macrostep that reached :x2"
      (is (= [:mark] @log))
      (is (= {:x :x2 :y :y1} (rf.machines.test-support/machine-state :rod/probe)))
      (is (true? (:marked? (rf.machines.test-support/machine-data :rod/probe)))))
    (testing "the action is declared at the region body"
      (is (= [[:mark :transition [] :x]] (ran))))
    (testing "region :y declared an :on-done too, and did not take :x's done"
      (is (not-any? #{:y-done} @log)))))

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
    (testing ":on-done :x1 names region :x's own :x1 — the region reached its
              final child and moved back, in one macrostep"
      (is (= [:x2-in :x2-out :again :x1-in] @log))
      (is (= {:x :x1 :y :y1} (rf.machines.test-support/machine-state :rod/loop))))))

(deftest region-on-done-targets-are-checked-at-registration
  (let [with-on-done (fn [on-done]
                       {:type    :parallel
                        :regions {:a {:initial :a1
                                      :on-done on-done
                                      :states  {:a1 {:on {:go :a2}}
                                                :a2 {:final? true}}}
                                  :b {:initial :b1
                                      :states  {:b1 {:on {:go :b2}}
                                                :b2 {:final? true}}}}})]
    (testing "a sibling region's name is refused, and the message says why"
      (try
        (rf.machines/make-machine-handler (with-on-done :b))
        (is false "registration must refuse a region :on-done naming a sibling region")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data e))))
          (is (= :on-done (:slot (ex-data e))))
          (is (re-find #"SIBLING REGION" (ex-message e)))
          (is (re-find #"ancestor fallback" (ex-message e))))))
    (testing "a target naming no state of the region is refused"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf.error/machine-unresolved-target"
            (rf.machines/make-machine-handler (with-on-done {:target [:nowhere]})))))
    (testing "controls: an in-region target, and an action-only :on-done, register"
      (is (fn? (rf.machines/make-machine-handler (with-on-done :a1))))
      (is (fn? (rf.machines/make-machine-handler
                 (-> (with-on-done {:action :log})
                     (assoc :actions {:log (fn [_] nil)}))))))))

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
    (testing "each region's done runs, in the order the regions raised them,
              then the root's :on-done on the settled all-final configuration"
      (is (= [:x-done :y-done :root-done] @log))
      (is (= {:x :x2 :y :y2} (rf.machines.test-support/machine-state :rod/order))))
    (testing "the machine rests all-final: a later event re-runs neither"
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
    (testing "region :x's done moved it back to :x1 before the macrostep
              settled, so the settled configuration is not all-final"
      (is (= {:x :x1 :y :y2} (rf.machines.test-support/machine-state :rod/leave)))
      (is (= [] @log)))))

(deftest a-region-born-on-its-final-child-takes-its-done-at-birth
  (let [log (atom [])]
    (rf/reg-machine :rod/born
      {:type    :parallel
       :actions (recorders log [:x-done])
       :regions {:x {:initial :x1
                     :on-done {:action :x-done}
                     :states  {:x1 {:final? true}}}
                 :y {:initial :y1 :states {:y1 {}}}}})
    (rf/dispatch-sync [:rod/born [:rf.machine/start]])
    (is (= [:x-done] @log))
    (is (= [[:x-done :transition [] :x]] (ran)))))
