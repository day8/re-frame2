(ns re-frame.emit-substrate-test
  "Direct unit coverage for `re-frame.emit-substrate/make-listener-registry`.

  Per the rf2-o7ayf audit (rf2-byut1 round-2 core review): the
  substrate factory underpins BOTH `re-frame.event-emit` and
  `re-frame.error-emit` — bugs at this layer fan out across the
  always-on emit surface (Spec 009 §What IS available in production).
  Higher-level tests caught downstream symptoms; this file locks the
  contract directly.

  Coverage:
    1. `:register` returns the id and the listener is reachable.
    2. `:unregister` drops a single listener; siblings unaffected.
    3. `:clear` drops every listener.
    4. `:fan-out` invokes every listener with the record argument.
    5. `:fan-out` short-circuits on empty registry (no deref-then-doseq
       cost when no one is listening).
    6. Listener exceptions are caught — the cascade continues and
       sibling listeners still fire.
    7. An externally-held `:listeners` atom is used as the backing
       store (the hot-reload-survives contract — consumers
       `defonce` their atom and pass it through).
    8. Idempotent re-register on the same id replaces the listener fn."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.emit-substrate :as emit]))

(defn- fresh-registry [] (emit/make-listener-registry {}))

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

(deftest fan-out-invokes-every-listener-with-record
  (testing ":fan-out walks every registered listener with the record argument"
    (let [{:keys [register fan-out]} (fresh-registry)
          captured (atom [])]
      (register :one (fn [r] (swap! captured conj [:one r])))
      (register :two (fn [r] (swap! captured conj [:two r])))
      (fan-out {:k :v})
      (is (= 2 (count @captured)) "both listeners fired")
      (is (every? #(= {:k :v} (second %)) @captured)
          "each listener saw the same record"))))

(deftest fan-out-empty-registry-is-noop
  (testing ":fan-out short-circuits when the registry is empty"
    ;; Hot-path cost reduces to one deref + `empty?` when no listener
    ;; is registered. We can't measure the cost directly; we DO assert
    ;; that calling fan-out without any listeners is exception-free
    ;; (catches a future regression where the doseq runs on nil).
    (let [{:keys [fan-out]} (fresh-registry)]
      (is (nil? (fan-out {:any :record}))
          "fan-out returns nil on empty"))))

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
          {:keys [register listeners]} (emit/make-listener-registry
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
