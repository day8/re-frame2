(ns re-frame.machine-action-exception-always-on-cljs-test
  "A throwing machine action or guard fans `:rf.error/machine-action-exception`
  out on the always-on error-listener axis (the production-surviving channel)
  with `:failing-id` + `:state` attribution. That record is structural only:
  the thrown `ex-data` — which may embed app secrets the privacy-gated dev
  trace redacts — never rides it (Spec 009 §Error event catalogue)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            ;; Installs the late-bind hooks `reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; The always-on listener registry is a `defonce` atom, so it is cleared per test.
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(defn- throwable? [x]
  #?(:clj  (instance? Throwable x)
     :cljs (instance? js/Error x)))

(defn- action-exceptions
  "Dispatch `event` and return the `:rf.error/machine-action-exception` records
  it fans out: `:always-on` from the error-listener axis, with the raw
  `:exception` and `:time` reduced to type checks and `:state` read as a path
  (the slot is a leaf keyword or a root→leaf vector, Spec 005 §State paths),
  and `:dev` from the dev trace stream."
  [event]
  (let [always-on (atom [])
        dev       (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! always-on conj %))
    (rf.trace.tooling/register-listener! ::trace #(swap! dev conj %))
    (try
      (rf/dispatch-sync event)
      {:always-on (into [] (comp (filter #(= :rf.error/machine-action-exception (:error %)))
                                 (map #(-> %
                                           (update :exception throwable?)
                                           (update :time number?)
                                           (update :state (fn [s] (cond-> s (keyword? s) vector))))))
                        @always-on)
       :dev       (filterv #(= :rf.error/machine-action-exception (:operation %)) @dev)}
      (finally
        (rf.error-emit/unregister-error-listener! ::recorder)
        (rf.trace.tooling/clear-listeners!)))))

(deftest throwing-action-fans-out-on-always-on-axis-with-attribution
  (testing "ONE structural always-on record attributed to the throwing action
            and its state, carrying none of the thrown ex-data; the dev trace
            fires once from the same emit site"
    (rf/reg-machine :throw/action
      {:initial :idle
       :actions {:boom (fn [_] (throw (ex-info "boom" {:token "super-secret-jwt"})))}
       :states  {:idle {:on {:go {:target :done :action :boom}}}
                 :done {}}})
    (rf/dispatch-sync [:throw/action [:rf.machine/start]])
    (let [{:keys [always-on dev]} (action-exceptions [:throw/action [:go]])]
      (is (= [{:error      :rf.error/machine-action-exception
               :actor-id   :throw/action
               :failing-id :boom
               :state      [:idle]
               :frame      :rf/default
               :recovery   :no-recovery
               :exception  true
               :time       true}]
             always-on))
      (is (= [{:action-id :boom :failing-id :boom}]
             (mapv #(select-keys (:tags %) [:action-id :failing-id]) dev))))))

(deftest throwing-guard-fans-out-on-always-on-axis-with-attribution
  (testing "a throwing guard aborts the macrostep through the SAME surface,
            with `:failing-id` = the guard keyword"
    (rf/reg-machine :throw/guard
      {:initial :idle
       :guards  {:boom (fn [_] (throw (ex-info "guard boom" {})))}
       :states  {:idle {:on {:go [{:guard :boom :target :a}]}}
                 :a    {}}})
    (rf/dispatch-sync [:throw/guard [:rf.machine/start]])
    (is (= [{:error      :rf.error/machine-action-exception
             :actor-id   :throw/guard
             :failing-id :boom
             :state      [:idle]
             :frame      :rf/default
             :recovery   :no-recovery
             :exception  true
             :time       true}]
           (:always-on (action-exceptions [:throw/guard [:go]]))))))
