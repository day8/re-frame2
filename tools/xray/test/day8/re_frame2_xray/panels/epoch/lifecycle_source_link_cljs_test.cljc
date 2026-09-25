(ns day8.re-frame2-xray.panels.epoch.lifecycle-source-link-cljs-test
  "The source key of a root's, a region body's and a region state's own
  `:entry` / `:exit` cascade row resolves against the source metadata the
  `reg-machine` macro co-locates on a literal spec.

  Every row comes from the producer: a literal machine is registered through
  the macro, runs, and its traces for one dispatch project through
  `proj/machine-cascade-rows`. `fmt/cascade-row-source-key` builds the key
  from the row's `:decl-path` / `:region`, and the key's enclosing node — the
  key minus its slot — is the map the macro stamped:

  - that node's `:source-code` carries the inline fn's source under the slot;
  - that node carries its own `:source-coords` where the reader puts
    positions on map literals, which the CLJS reader does and Clojure's
    LispReader does not, so the coord assertions are CLJS-only. A region
    key's coord also resolves through `proj/state-node-source-coords`.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [day8.re-frame2-xray.panels.epoch.format :as fmt]
   [day8.re-frame2-xray.panels.epoch.projection :as proj]
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- cascade-of
  "Dispatch `event` synchronously and return the machine cascade rows the
  producer's traces for that one dispatch project to."
  [event]
  (let [evs (atom [])]
    (rf.trace.tooling/register-listener! ::capture (fn [ev] (swap! evs conj ev)))
    (try
      (rf/dispatch-sync event)
      (finally
        (rf.trace.tooling/unregister-listener! ::capture)))
    (proj/machine-cascade-rows @evs)))

(defn- registered
  "The registered spec of `machine-id` — the metadata Xray resolves keys
  against."
  [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(defn- key-of-row-running
  "The source key of the `:action` cascade row whose action is `f`."
  [rows f]
  (some #(when (and (= :action (:kind %)) (identical? f (:action-id %)))
           (fmt/cascade-row-source-key %))
        rows))

(defn- inline-source
  "The source `spec` carries for the inline fn at key `k`: the slot's entry in
  the `:source-code` map of `k`'s enclosing node."
  [spec k]
  (get-in spec (conj (pop k) :source-code (peek k))))

(defn- enclosing-coords
  "The `:source-coords` of `k`'s enclosing node on `spec`."
  [spec k]
  (:source-coords (get-in spec (pop k))))

(deftest root-lifecycle-rows-resolve-against-registered-source
  (rf/reg-machine :lsl/flat
    {:initial :a
     :entry   (fn [_] {:data {:at :root-in}})
     :exit    (fn [_] {:data {:at :root-out}})
     :states  {:a    {:on {:fin :done}}
               :done {:final? true}}})
  (let [spec  (registered :lsl/flat)
        entry (key-of-row-running (cascade-of [:lsl/flat [:rf.machine/start]])
                                  (:entry spec))
        exit  (key-of-row-running (cascade-of [:lsl/flat [:fin]])
                                  (:exit spec))]
    (testing "the root's own :entry row"
      (is (= [:entry] entry))
      (is (= "(fn [_] {:data {:at :root-in}})" (inline-source spec entry)))
      #?(:cljs (is (map? (enclosing-coords spec entry))
                   "the spec root carries its own coord")))
    (testing "the root's own :exit row, at teardown"
      (is (= [:exit] exit))
      (is (= "(fn [_] {:data {:at :root-out}})" (inline-source spec exit))))))

(deftest region-lifecycle-rows-resolve-against-registered-source
  (rf/reg-machine :lsl/parallel
    {:type    :parallel
     :regions {:r1 {:initial :x
                    :entry   (fn [_] {:data {:at :r1-in}})
                    :states  {:x {:entry (fn [_] {:data {:at :x-in}})}}}
               :r2 {:initial :p
                    :states  {:p {}}}}})
  (let [spec (registered :lsl/parallel)
        rows (cascade-of [:lsl/parallel [:rf.machine/start]])
        body (key-of-row-running rows (get-in spec [:regions :r1 :entry]))
        x    (key-of-row-running rows (get-in spec [:regions :r1 :states :x :entry]))]
    (testing "a region body's own :entry row"
      (is (= [:regions :r1 :entry] body))
      (is (= "(fn [_] {:data {:at :r1-in}})" (inline-source spec body)))
      #?(:cljs (do (is (map? (enclosing-coords spec body))
                       "the region body carries its own coord")
                   (is (= (enclosing-coords spec body)
                          (proj/state-node-source-coords spec body))))))
    (testing "the :entry row of a state inside a region"
      (is (= [:regions :r1 :states :x :entry] x))
      (is (= "(fn [_] {:data {:at :x-in}})" (inline-source spec x)))
      #?(:cljs (do (is (map? (enclosing-coords spec x))
                       "the state inside the region carries its own coord")
                   (is (= (enclosing-coords spec x)
                          (proj/state-node-source-coords spec x))))))))
