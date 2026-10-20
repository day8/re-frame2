(ns re-frame.machine-cofx-attach-test
  "A machine's top-level `:rf.cofx/requires` is ensured before transition
  selection on every event addressed to the machine, whatever slot the runtime
  selects from; a declaration anywhere else is refused at registration."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private machine-state rf.machines.test-support/machine-state)

(defn- rolled-six [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))

(defn- reg-roll! [] (rf/reg-cofx :test/roll8 {:recordable? true} (fn [] 6)))

(defn- registration-error-id [machine]
  (try (rf.machines.lifecycle-fx.registration/registered-definition machine) nil
       (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest a-declaration-below-the-root-is-refused-at-registration
  (doseq [[label machine]
          [["a named :guards entry map"
            {:initial :idle
             :guards  {:g {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}}
             :states  {:idle {:on {:go {:target :done :guard :g}}}
                       :done {}}}]
           ["a :guards entry map with no :fn"
            {:initial :idle
             :guards  {:bad {:rf.cofx/requires [:rf/time-ms]}}
             :states  {:idle {:on {:go {:target :done :guard :bad}}}
                       :done {}}}]
           ["an inline :on transition map"
            {:initial :idle
             :states  {:idle {:on {:go {:rf.cofx/requires [:rf/time-ms] :target :done}}}
                       :done {}}}]
           ["a state node"
            {:initial :idle
             :states  {:idle {:rf.cofx/requires [:rf/time-ms]} :done {}}}]
           ["a region body"
            {:type    :parallel
             :regions {:a {:rf.cofx/requires [:rf/time-ms] :initial :x :states {:x {}}}}}]
           ["a :type :choice candidate"
            {:initial :idle
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type   :choice
                                  :choice [{:rf.cofx/requires [:rf/time-ms] :target :a}
                                           {:target :b}]}
                       :a {} :b {}}}]]]
    (is (= :rf.error/machine-cofx-requires-inline (registration-error-id machine)) label))
  (is (nil? (registration-error-id {:rf.cofx/requires [:rf/time-ms]
                                    :initial :idle
                                    :states  {:idle {}}}))
      "control: the machine root accepts it"))

;; Every transition slot refuses a declaration under the key the author wrote:
;; a `:timeout` / `:on-timeout` lowers onto `:after`, and a `:spawn`'s
;; `:on-error` / transition-shaped `:on-done` are slots apart from `:on`. An
;; authored `:on` and `:after` are the controls.
(deftest refusal-names-the-authored-key
  (let [inline {:rf.cofx/requires [:rf/time-ms] :target :done}]
    (doseq [[where machine]
            [["state :idle :on-timeout"
              {:initial :idle
               :states  {:idle {:timeout 100 :on-timeout inline} :done {}}}]
             ["state :idle :spawn :on-timeout"
              {:initial :idle
               :states  {:idle {:spawn {:machine-id :x/child :timeout 100 :on-timeout inline}}
                         :done {}}}]
             ["state :idle :spawn :on-error"
              {:initial :idle
               :states  {:idle {:spawn {:machine-id :x/child :on-error inline}} :done {}}}]
             ["state :idle :spawn :on-done"
              {:initial :idle
               :states  {:idle {:spawn {:machine-id :x/child :on-done [inline]}} :done {}}}]
             [":rf/root :on-timeout"
              {:type       :parallel
               :timeout    100
               :on-timeout (assoc inline :target [:a :done])
               :regions    {:a {:initial :idle :states {:idle {} :done {}}}}}]
             [":rf/root :spawn :on-error"
              {:initial :idle
               :spawn   {:machine-id :x/child :on-error inline}
               :states  {:idle {} :done {}}}]
             [":rf/root :spawn :on-done"
              {:initial :idle
               :spawn   {:machine-id :x/child :on-done inline}
               :states  {:idle {} :done {}}}]
             ["state :idle :on"
              {:initial :idle
               :states  {:idle {:on {:go inline}} :done {}}}]
             ["state :idle :after"
              {:initial :idle
               :states  {:idle {:after {100 inline}} :done {}}}]]]
      (let [e (try (rf/reg-machine (keyword "attach" (str (gensym "inline-"))) machine) nil
                   (catch ExceptionInfo e e))]
        (is (= :rf.error/machine-cofx-requires-inline (:rf.error/id (ex-data e))) where)
        (is (str/includes? (str (ex-message e)) (str "Found at: " where ".")) where)))))

(deftest candidate-action-and-always-closure-facts-are-ensured
  ;; :go's action, :b's :always action and :c's :always guard (two hops past
  ;; the candidate target) each read a fact the one ensure step generated
  ;; before the macrostep that settles the whole chain.
  (rf/reg-cofx :test/token {:recordable? true} (fn [] :GENERATED))
  (rf/reg-cofx :test/jitter {:recordable? true} (fn [] 42))
  (reg-roll!)
  (rf/reg-machine :attach/closure
    {:rf.cofx/requires [:test/token :test/jitter :test/roll8]
     :initial :a
     :data    {}
     :guards  {:rolled-six? rolled-six}
     :actions {:capture (fn [{:keys [data] cofx :rf.cofx}]
                          {:data (assoc data :captured (:test/token cofx))})
               :jitter  (fn [{:keys [data] cofx :rf.cofx}]
                          {:data (assoc data :jitter (:test/jitter cofx))})}
     :states  {:a    {:on {:go {:target :b :action :capture}}}
               :b    {:always {:action :jitter :target :c}}
               :c    {:always {:guard :rolled-six? :target :done}}
               :done {}}})
  (rf/dispatch-sync [:attach/closure [:go]])
  (is (= {:state :done :data {:captured :GENERATED :jitter 42}}
         (select-keys (rf.machines.test-support/snapshot :attach/closure) [:state :data]))))

(deftest guard-facts-are-ensured-for-root-parallel-root-and-choice-selection
  (reg-roll!)
  (doseq [[id machine expected]
          [[:attach/root-on
            {:initial :idle
             :guards  {:g rolled-six}
             :on      {:go {:target :done :guard :g}}
             :states  {:idle {} :done {}}}
            :done]
           [:attach/parallel-root-on
            {:type    :parallel
             :guards  {:g rolled-six}
             :on      {:go {:target [[:a :two] [:b :two]] :guard :g}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
            {:a :two :b :two}]
           [:attach/region-root-on
            {:type    :parallel
             :guards  {:g rolled-six}
             :regions {:a {:initial :idle
                           :on      {:go {:target :done :guard :g}}
                           :states  {:idle {} :done {}}}
                       :b {:initial :x :states {:x {}}}}}
            {:a :done :b :x}]
           [:attach/choice
            {:initial :idle
             :guards  {:g rolled-six}
             :states  {:idle     {:on {:go :checking}}
                       :checking {:type :choice :choice [{:guard :g :target :hit} {:target :miss}]}
                       :hit {} :miss {}}}
            :hit]]]
    (rf/reg-machine id (assoc machine :rf.cofx/requires [:test/roll8]))
    (rf/dispatch-sync [id [:go]])
    (is (= expected (machine-state id)) (str id))))

;; The reg-machine macro walks an inline literal; both arms must keep the
;; top-level :rf.cofx/requires.
(deftest inline-literal-declaration-fires-in-both-macro-arms
  (reg-roll!)
  (doseq [[id dev?] [[:attach/inline-dev true] [:attach/inline-prod false]]]
    (with-redefs [rf.interop/debug-enabled? dev?]
      (rf/reg-machine id
        {:rf.cofx/requires [:test/roll8]
         :initial :idle
         :guards  {:rolled-six? (fn [{cofx :rf.cofx}] (= 6 (:test/roll8 cofx)))}
         :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
                   :done {}}}))
    (rf/dispatch-sync [id [:go]])
    (is (= :done (machine-state id)) (str id))))

;; The declaration is ensured on every event addressed to the machine — one
;; whose transition reads nothing included — so each mints its own value.
(deftest every-event-ensures-the-whole-declaration
  (let [calls (atom 0)]
    (rf/reg-cofx :test/count {:recordable? true} (fn [] (swap! calls inc)))
    (rf/reg-machine :attach/every
      {:rf.cofx/requires [:test/count]
       :initial :idle
       :data    {}
       :actions {:capture (fn [{:keys [data] cofx :rf.cofx}] {:data (assoc data :n (:test/count cofx))})}
       :states  {:idle {:on {:tick {} :go {:target :done :action :capture}}}
                 :done {}}})
    (rf/dispatch-sync [:attach/every [:tick]])
    (rf/dispatch-sync [:attach/every [:tick]])
    (rf/dispatch-sync [:attach/every [:go]])
    (is (= [3 {:n 3}] [@calls (rf.machines.test-support/machine-data :attach/every)]))))

;; A state-level :timeout lowers onto :after at registration; the timer event
;; that fires it is addressed to the machine like any other.
(deftest on-timeout-guard-and-action-facts-are-ensured
  (reg-roll!)
  (rf/reg-cofx :test/token {:recordable? true} (fn [] :GENERATED))
  (doseq [[id on-timeout] [[:attach/timeout-guard  {:guard :rolled-six? :target :done}]
                           [:attach/timeout-action {:action :capture :target :done}]]]
    (rf/reg-machine id
      {:rf.cofx/requires [:test/roll8 :test/token]
       :initial :idle
       :data    {}
       :guards  {:rolled-six? rolled-six}
       :actions {:capture (fn [{:keys [data] cofx :rf.cofx}]
                            {:data (assoc data :captured (:test/token cofx))})}
       :states  {:idle    {:on {:go :waiting}}
                 :waiting {:timeout 5000 :on-timeout on-timeout}
                 :done    {}}})
    (rf/dispatch-sync [id [:go]])
    (let [epoch (get-in (rf.machines.test-support/snapshot id) [:rf/runtime :after-epoch [:waiting]])]
      (rf/dispatch-sync [id [:rf.machine.timer/after-elapsed 5000 epoch [:waiting]]]))
    (is (= :done (machine-state id)) (str id " took its :on-timeout transition")))
  (is (= :GENERATED (get-in (rf.machines.test-support/snapshot :attach/timeout-action) [:data :captured]))
      "the :on-timeout action read its ensured fact"))
