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
  (testing "a throwing listener does not stop its siblings, and fan-out returns nil"
    (let [{:keys [register fan-out]} (rf.emit-substrate/make-listener-registry {})
          fired (atom [])]
      (register :before (fn [_] (swap! fired conj :before)))
      (register :bad    (fn [_] (throw (ex-info "boom" {}))))
      (register :after  (fn [_] (swap! fired conj :after)))
      (is (nil? (fan-out {})))
      (is (= {:before 1 :after 1} (frequencies @fired))))))

(deftest external-listeners-atom-is-honoured
  (testing "registration writes through to a caller-held `:listeners` atom, which
            consumers `defonce` so a hot reload keeps their listeners"
    (let [external (atom {})
          {:keys [register]} (rf.emit-substrate/make-listener-registry {:listeners external})]
      (register :hello (fn [_] :ok))
      (is (contains? @external :hello)))))
