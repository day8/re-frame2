(ns re-frame.emit-substrate-test
  "`re-frame.emit-substrate/make-listener-registry`, the registry behind both
  `re-frame.event-emit` and `re-frame.error-emit` (Spec 009 §What IS available
  in production)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.emit-substrate :as rf.emit-substrate]))

(deftest registry-lifecycle
  (let [{:keys [register unregister clear fan-out]} (rf.emit-substrate/make-listener-registry {})
        seen     (atom [])
        listener (fn [tag] (fn [r] (swap! seen conj [tag r])))]
    (is (= :a (register :a (listener :a))) ":register returns the id")
    (register :b (listener :replaced))
    (register :b (listener :b))
    (fan-out 1)
    (is (= [[:a 1] [:b 1]] (sort @seen))
        "each listener receives the record once; re-registering an id replaces it")
    (unregister :a)
    (fan-out 2)
    (is (= [[:a 1] [:b 1] [:b 2]] (sort @seen)) ":unregister drops only its target")
    (clear)
    (fan-out 3)
    (is (= [[:a 1] [:b 1] [:b 2]] (sort @seen)) ":clear drops every listener")))

(deftest fan-out-listener-exception-isolated
  (testing "a throwing listener does not stop its siblings, and claims nothing"
    (let [{:keys [register fan-out]} (rf.emit-substrate/make-listener-registry {})
          fired (atom [])]
      (register :before (fn [_] (swap! fired conj :before)))
      (register :bad    (fn [_] (throw (ex-info "boom" {}))))
      (register :after  (fn [_] (swap! fired conj :after)))
      (is (false? (fan-out {})))
      (is (= {:before 1 :after 1} (frequencies @fired))))))

(deftest fan-out-reports-a-claim-only-for-a-literal-true
  (let [{:keys [register unregister fan-out]} (rf.emit-substrate/make-listener-registry {})]
    (is (false? (fan-out {})) "an empty registry claims nothing")
    (register :truthy (fn [_] :yes))
    (is (false? (fan-out {})) "a truthy non-`true` return is not a claim")
    (register :claims (fn [_] true))
    (is (true? (fan-out {})) "one listener returning `true` claims the record")
    (unregister :claims)
    (register :claims-after-throw (fn [_] true))
    (register :throws (fn [_] (throw (ex-info "boom" {}))))
    (is (true? (fan-out {})) "a sibling's throw does not undo another listener's claim")))

(deftest fan-out-claims-over-the-delivered-snapshot-only
  (let [{:keys [register fan-out]} (rf.emit-substrate/make-listener-registry {})
        live? (atom true)
        seen  (atom 0)]
    (register :claims-then-stops (fn [_] (swap! seen inc) (reset! live? false) true))
    (testing "a walk `continue?` stops before the first listener delivers nothing,
              so nothing is claimed"
      (is (false? (fan-out {} (constantly false))))
      (is (zero? @seen)))
    (testing "a claim made by a delivered listener stands when the walk then stops"
      (is (true? (fan-out {} (fn [] @live?))))
      (is (= 1 @seen)))))
