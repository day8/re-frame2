(ns re-frame.emit-substrate-test
  "Direct unit coverage for `re-frame.emit-substrate/make-listener-registry`.

  The substrate factory underpins BOTH `re-frame.event-emit` and
  `re-frame.error-emit` — bugs at this layer fan out across the
  always-on emit surface (Spec 009 §What IS available in production).
  Higher-level tests catch downstream symptoms; this file locks the
  contract directly.

  Coverage:
    1. `:register` returns the id and the listener is reachable.
    2. `:unregister` drops a single listener; siblings unaffected.
    3. `:clear` drops every listener.
    4. `:fan-out` hands each listener the record (`register-installs-listener`)
       and walks every listener (`fan-out-listener-exception-isolated`); over
       an emptied registry (after `:clear`) it is a quiet no-op.
    5. Listener exceptions are caught — the cascade continues, sibling
       listeners still fire, and `:fan-out` returns nil.
    6. An externally-held `:listeners` atom is used as the backing
       store (the hot-reload-survives contract — consumers
       `defonce` their atom and pass it through).
    7. Idempotent re-register on the same id replaces the listener fn."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.emit-substrate :as rf.emit-substrate]))

(defn- fresh-registry [] (rf.emit-substrate/make-listener-registry {}))

(deftest register-installs-listener
  (testing ":register stores the listener and returns the id"
    (let [{:keys [register fan-out]} (fresh-registry)
          seen (atom [])
          ret  (register :alpha (fn [r] (swap! seen conj r)))]
      (is (= :alpha ret) ":register returns the id")
      (fan-out {:rec 1})
      (is (= [{:rec 1}] @seen) "the listener received the fan-out record"))))

(deftest unregister-drops-only-target
  (testing ":unregister removes a single listener; siblings keep firing"
    (let [{:keys [register unregister fan-out]} (fresh-registry)
          a-seen (atom 0)
          b-seen (atom 0)]
      (register :alpha (fn [_] (swap! a-seen inc)))
      (register :beta  (fn [_] (swap! b-seen inc)))
      (fan-out {})
      (is (= 1 @a-seen)) (is (= 1 @b-seen))
      (unregister :alpha)
      (fan-out {})
      (is (= 1 @a-seen) ":alpha was dropped")
      (is (= 2 @b-seen) ":beta still fires"))))

(deftest clear-drops-every-listener
  (testing ":clear removes every registration"
    (let [{:keys [register clear fan-out listeners]} (fresh-registry)
          seen (atom 0)]
      (register :alpha (fn [_] (swap! seen inc)))
      (register :beta  (fn [_] (swap! seen inc)))
      (is (= 2 (count @listeners)))
      (clear)
      (is (= {} @listeners) ":clear empties the listener atom")
      (fan-out {})
      (is (zero? @seen) "no listener fired after :clear"))))

(deftest fan-out-listener-exception-isolated
  (testing "a buggy listener throws — the cascade continues; siblings still fire"
    (let [{:keys [register fan-out]} (fresh-registry)
          after-bad (atom 0)
          before-bad (atom 0)]
      (register :before (fn [_] (swap! before-bad inc)))
      (register :bad    (fn [_] (throw (ex-info "boom" {}))))
      (register :after  (fn [_] (swap! after-bad inc)))
      (is (nil? (fan-out {}))
          "fan-out swallows listener exceptions and returns nil")
      (is (= 1 @before-bad) "the :before listener fired")
      (is (= 1 @after-bad)  "the :after listener fired despite :bad throwing"))))

(deftest external-listeners-atom-is-honoured
  (testing "an externally-supplied `:listeners` atom is the backing store"
    ;; Production consumers `defonce` their atom and pass it through so
    ;; hot reload of the consuming ns doesn't drop registrations.
    (let [external (atom {})
          {:keys [register listeners]} (rf.emit-substrate/make-listener-registry
                                          {:listeners external})]
      (is (identical? external listeners)
          "the surfaced :listeners atom IS the externally-supplied one")
      (register :hello (fn [_] :ok))
      (is (contains? @external :hello)
          "registration writes through to the external atom"))))

(deftest reregister-replaces
  (testing "re-registering the same id replaces the listener fn"
    (let [{:keys [register fan-out]} (fresh-registry)
          seen (atom [])]
      (register :only (fn [_] (swap! seen conj :v1)))
      (register :only (fn [_] (swap! seen conj :v2)))
      (fan-out {})
      (is (= [:v2] @seen)
          "only the second registration fires — first was replaced"))))
