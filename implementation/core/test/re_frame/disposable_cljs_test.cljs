(ns re-frame.disposable-cljs-test
  "Direct unit coverage for the re-frame-owned `IDisposable` protocol
  (rf2-wx79g; follow-on from rf2-q1z1u F5).

  Background. Per rf2-jicu2 / rf2-ykqee the `IDisposable` protocol was
  lifted out of `reagent.ratom` into `re-frame.disposable` so the UIx
  and Helix adapters can satisfy the sub-cache teardown contract
  without dragging ~9KB of Reagent batching/ratom code into their
  bundles. The spine reifies this protocol on its derived-value
  containers; the substrate-spine integration test
  `spine_dispose_cljs_test` exercises it via the cache walk; this
  file pins the protocol contract directly at the function boundary so
  an external user (or a future internal reify) that satisfies the
  protocol gets a clean regression signal if the contract slips.

  Contract surface from `re-frame.disposable`:
  - `(-add-on-dispose this f)`: register a 0-arg callback. Multiple
    callbacks accumulate; they fire in registration order at `-dispose`.
  - `(-dispose this)`: tear down synchronously; fire every registered
    on-dispose. Idempotent — a second `-dispose` is a no-op (callbacks
    do NOT fire a second time).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.disposable :as rf-disposable]))

(defn- make-toy-disposable
  "Build a minimal reify of `rf-disposable/IDisposable` that records
  every registered on-dispose callback in a per-instance vector atom
  and fires them in registration order on `-dispose`. After firing,
  `:disposed?` flips true and subsequent `-dispose` calls no-op (the
  callbacks are NOT re-fired) — mirroring the protocol's docstring
  idempotence guarantee.

  Returned map carries the reify object and the recording cells so
  tests can assert against them."
  []
  (let [callbacks  (atom [])
        fire-log   (atom [])
        disposed?  (atom false)
        obj        (reify rf-disposable/IDisposable
                     (-add-on-dispose [_ f]
                       (swap! callbacks conj f))
                     (-dispose [_]
                       (when-not @disposed?
                         (reset! disposed? true)
                         (doseq [f @callbacks]
                           (f)))))]
    {:obj       obj
     :callbacks callbacks
     :fire-log  fire-log
     :disposed? disposed?}))

(deftest add-on-dispose-then-dispose-fires-callback
  (testing "a single registered on-dispose callback fires when -dispose is called"
    (let [{:keys [obj fire-log]} (make-toy-disposable)]
      (rf-disposable/-add-on-dispose obj #(swap! fire-log conj :cb-1))
      (is (= [] @fire-log)
          "precondition: no callback fired yet")
      (rf-disposable/-dispose obj)
      (is (= [:cb-1] @fire-log)
          "the registered callback fired exactly once on -dispose"))))

(deftest multiple-callbacks-fire-in-registration-order
  (testing "multiple on-dispose callbacks fire in registration order"
    (let [{:keys [obj fire-log]} (make-toy-disposable)]
      (rf-disposable/-add-on-dispose obj #(swap! fire-log conj :cb-a))
      (rf-disposable/-add-on-dispose obj #(swap! fire-log conj :cb-b))
      (rf-disposable/-add-on-dispose obj #(swap! fire-log conj :cb-c))
      (rf-disposable/-dispose obj)
      (is (= [:cb-a :cb-b :cb-c] @fire-log)
          "all three callbacks fired in registration order"))))

(deftest dispose-is-idempotent
  (testing "a second -dispose is a no-op: callbacks do not fire again"
    (let [{:keys [obj fire-log]} (make-toy-disposable)]
      (rf-disposable/-add-on-dispose obj #(swap! fire-log conj :only-once))
      (rf-disposable/-dispose obj)
      (is (= [:only-once] @fire-log)
          "first -dispose fired the callback")
      (rf-disposable/-dispose obj)
      (is (= [:only-once] @fire-log)
          "second -dispose did NOT re-fire the callback (idempotent per docstring)"))))
