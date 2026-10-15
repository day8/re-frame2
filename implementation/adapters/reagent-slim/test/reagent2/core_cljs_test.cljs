(ns reagent2.core-cljs-test
  "Public state, argv, force-update and reaction contracts."
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent2.core :as r]
            [reagent2.ratom]))

(deftest force-update-1-arity-calls-forceupdate
  (let [calls (atom [])
        this  #js {:forceUpdate (fn [] (swap! calls conj :force-update))}]
    (r/force-update this)
    (is (= [:force-update] @calls))))

(deftest state-reads-the-cell
  (let [this #js {}]
    (is (nil? (r/state this)))
    (reset! (r/state-atom this) {:count 3})
    (is (= {:count 3} (r/state this)))))

(deftest set-state-merges
  (let [this #js {}]
    (r/set-state this {:a 1 :b 2})
    (r/set-state this {:b 20 :c 3})
    (is (= {:a 1 :b 20 :c 3} (r/state this)))))

(deftest replace-state-resets
  (let [this #js {}]
    (r/set-state this {:a 1 :b 2})
    (r/replace-state this {:only :this})
    (is (= {:only :this} (r/state this)))))

(defn- fake-instance [argv]
  #js {:cljsArgv argv})

(deftest props-returns-map-second-arg
  (let [render-fn (fn [_])]
    (is (= [{:k :v} nil]
           [(r/props (fake-instance [render-fn {:k :v} :child]))
            (r/props (fake-instance [render-fn :child]))]))))

(deftest children-skip-props-map
  (let [render-fn (fn [_])]
    (is (= [[:a :b] [:a :b]]
           [(vec (r/children (fake-instance [render-fn {:k :v} :a :b])))
            (vec (r/children (fake-instance [render-fn :a :b])))]))))

(deftest reaction-body-is-deferred-until-deref
  (let [runs (atom 0)
        rx   (r/reaction (do (swap! runs inc) :computed))]
    (is (zero? @runs))
    (is (= :computed @rx))
    (is (= 1 @runs))))
