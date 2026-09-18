(ns day8.re-frame2-xray.machines-shape-parity-cljs-test
  "Shape parity for the MACHINES fixture family (rf2-y8doi.28).

  The Static Sim suite feeds its panel machine-transition results it holds
  as fixtures (`ok-result`, `fail-result` in `sim-helpers-cljs-test`). A
  fixture is only evidence about the panel if it has the shape the engine
  returns: the `{:error {:reason :no-matching-transition}}` result that
  rf2-y8doi.21 replaced was a shape the engine never produces, and every row
  asserting against it stayed green, because the author typed both the
  fixture and the assertion.

  So the REAL side here comes from the producer, once, and the FIXTURE side
  is the suite's own var, never retyped. The key sets must be EQUAL at every
  level a consumer reads — the result, its `:snapshot`, its `:error`.

  `.cljc` on purpose: `rf.machines/machine-transition` is pure and runs on
  both hosts, so this pins the node lane and the JVM gate alike."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.machines :as rf.machines]
            [day8.re-frame2-xray.static.machines.sim-helpers-cljs-test :as sim-suite]))

(defn- key-set [m] (set (keys m)))

(def ^:private real-ok
  "The engine's `:status :ok` result for one matched transition."
  (rf.machines/machine-transition
    {:initial :a :data {} :states {:a {:on {:go :b}} :b {}}}
    {:state :a :data {}}
    [:go]))

(def ^:private real-error
  "The engine's `:status :error` result: an action threw mid-macrostep."
  (rf.machines/machine-transition
    {:initial :a
     :data    {}
     :actions {:boom (fn [_] (throw (ex-info "boom" {:why :parity})))}
     :states  {:a {:on {:go {:target :b :action :boom}}} :b {}}}
    {:state :a :data {}}
    [:go]))

(deftest sim-suite-results-have-the-engines-shape
  (testing "the Sim suite's transition-result fixtures carry exactly the
            engine's keys, at the result, snapshot and error levels"
    (let [ok  @#'sim-suite/ok-result
          err @#'sim-suite/fail-result]
      (is (= [:ok :error] [(:status real-ok) (:status real-error)])
          "PRECONDITION: the producer gave one result of each kind")
      (is (= (key-set real-ok) (key-set ok)))
      (is (= (key-set (:snapshot real-ok)) (key-set (:snapshot ok))))
      (is (= (key-set real-error) (key-set err)))
      (is (= (key-set (:error real-error)) (key-set (:error err))))
      (is (not= (key-set (:error real-error)) #{:kind :reason})
          "control: the fixture rf2-y8doi.21 replaced fails this parity"))))
