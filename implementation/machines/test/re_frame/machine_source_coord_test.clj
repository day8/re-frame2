(ns re-frame.machine-source-coord-test
  "`reg-machine` and `defmachine` co-locate each guard / action fn's source on
  its entry, and each inline fn's source on its enclosing node (Spec 005
  §Source-coord stamping). The JVM reader puts no position on map literals, so
  the map nodes' own `:source-coords` are covered in
  machine_source_coord_cljs_test.cljs."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- machine-spec [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(defn- element-meta [kind machine-id id]
  (rf/handler-meta {:source :store :kind kind :id [machine-id id]}))

(deftest reg-machine-stamps-named-element-source
  (rf/reg-machine :src/named
    {:initial :idle
     :guards  {:ok? (fn [_] true)}
     :actions {:do (fn [_] {})}
     :states  {:idle {:on {:submit {:target :done :guard :ok? :action :do}}}
               :done {}}})
  (let [spec                     (machine-spec :src/named)
        {:keys [ns line column]} (get-in spec [:guards :ok? :source-coords])]
    (is (= 're-frame.machine-source-coord-test ns))
    (is (every? integer? [line column]))
    (is (= ["(fn [_] true)" "(fn [_] {})"]
           [(get-in spec [:guards :ok? :source-code]) (get-in spec [:actions :do :source-code])]))
    (is (nil? (get-in spec [:states :idle :on :submit :source-code]))
        "a keyword reference carries no inline source: its body is the named entry's")))

(deftest reg-machine-stamps-inline-fn-source-on-the-enclosing-node
  ;; An inline slot keeps its bare fn, so its source rides the enclosing map
  ;; node's `:source-code`, keyed by slot.
  (rf/reg-machine :src/inline
    {:initial :a
     :states
     {:a {:entry (fn [_] {:data {:entered? true}})
          :exit  (fn [_] {:data {:exited? true}})
          :on    {:go {:target :b
                       :guard  (fn [{data :data}] (:ready? data))
                       :action (fn [_] {:data {:went? true}})}}}
      :b {:always {:target :c
                   :guard  (fn [{data :data}] (:pending? data))
                   :action (fn [_] {:data {:single? true}})}}
      :c {:always [{:target :a :guard (fn [_] false) :action (fn [_] {:data {:first? true}})}
                   {:target :b :action (fn [_] {:data {:second? true}})}]}}})
  (let [expected {[:states :a]           {:entry "(fn [_] {:data {:entered? true}})"
                                          :exit  "(fn [_] {:data {:exited? true}})"}
                  [:states :a :on :go]   {:guard  "(fn [{data :data}] (:ready? data))"
                                          :action "(fn [_] {:data {:went? true}})"}
                  [:states :b :always]   {:guard  "(fn [{data :data}] (:pending? data))"
                                          :action "(fn [_] {:data {:single? true}})"}
                  [:states :c :always 0] {:guard  "(fn [_] false)"
                                          :action "(fn [_] {:data {:first? true}})"}
                  [:states :c :always 1] {:action "(fn [_] {:data {:second? true}})"}}
        spec     (machine-spec :src/inline)]
    (is (= expected (into {} (for [path (keys expected)]
                               [path (get-in spec (conj path :source-code))]))))))

;; A value-registered machine: `reg-machine` sees only the symbol, so only
;; `defmachine` can stamp it.
(rf/defmachine value-door-machine
  {:initial :closed
   :guards  {:may-close? (fn guard-may-close? [{data :data}] (not (:held-open? data)))}
   :actions {:clear-hold (fn action-clear-hold [{data :data}] {:data (assoc data :held-open? false)})}
   :states  {:closed {:exit :clear-hold :on {:door/push :open}}
             :open   {:on {:door/close {:target :closed :guard :may-close?}}}}})

(def plain-door-machine
  {:initial :closed
   :guards  {:may-close? (fn guard-may-close? [{data :data}] (not (:held-open? data)))}
   :actions {:clear-hold (fn action-clear-hold [{data :data}] {:data (assoc data :held-open? false)})}
   :states  {:closed {:exit :clear-hold :on {:door/push :open}}
             :open   {:on {:door/close {:target :closed :guard :may-close?}}}}})

(deftest plain-def-value-registered-has-no-per-element-source
  (rf/reg-machine :src/plain-door plain-door-machine)
  (is (= plain-door-machine (machine-spec :src/plain-door))
      "registered verbatim: nothing is co-located")
  (is (= [nil nil] [(element-meta :machine-guard :src/plain-door :may-close?)
                    (element-meta :machine-action :src/plain-door :clear-hold)])))

(deftest defmachine-value-registered-carries-per-element-source
  (rf/reg-machine :src/value-door value-door-machine)
  (let [guard  (element-meta :machine-guard :src/value-door :may-close?)
        action (element-meta :machine-action :src/value-door :clear-hold)]
    (is (= [{:rf/guard-id       :may-close?
             :ns                're-frame.machine-source-coord-test
             :rf.handler/source "(fn guard-may-close? [{data :data}] (not (:held-open? data)))"}
            {:rf/action-id      :clear-hold
             :ns                're-frame.machine-source-coord-test
             :rf.handler/source "(fn action-clear-hold [{data :data}] {:data (assoc data :held-open? false)})"}]
           [(select-keys guard [:rf/guard-id :ns :rf.handler/source])
            (select-keys action [:rf/action-id :ns :rf.handler/source])]))
    (is (every? integer? (map :line [guard action])))))

(deftest defmachine-accepts-optional-docstring
  (rf/defmachine documented-machine
    "A documented machine."
    {:initial :a
     :guards  {:g? (fn [_] true)}
     :states  {:a {}}})
  (is (= ["A documented machine." "(fn [_] true)"]
         [(:doc (meta #'documented-machine)) (get-in documented-machine [:guards :g? :source-code])])))
