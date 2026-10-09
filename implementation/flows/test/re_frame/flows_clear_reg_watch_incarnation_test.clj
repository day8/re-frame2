(ns re-frame.flows-clear-reg-watch-incarnation-test
  "Exact-incarnation fence for the callback-bearing writes of `clear-flow` and
  of a path-moving `reg-flow` replacement.

  Each op vacates the old output leaf and writes the output marks before its
  bare-id registry, dirty-check and trace tail. A synchronous container watch
  on the vacation destroys incarnation A and publishes a same-id B, so the
  vacation must land only in A's detached container, the mark write must stay
  exact, and the op must recheck A's ownership before its tail: every B store
  stays as B left it. The watch runs on the test thread, so the ordering is
  deterministic."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-watching-adapter!
  "A plain-atom adapter whose `replace-container!` runs `on-write` once, after
  the first physical write made while `armed?` holds."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(defn- b-stores [id flow-id]
  {:token  (rf.frame/frame-incarnation-token id)
   :row    (get-in (rf.flows.registry/flows-snapshot) [id flow-id])
   :dirty  (rf.flows.registry/get-frame-flow-last-inputs id flow-id)
   :epoch  (rf.frame/frame-commit-epoch id)
   :app-db (rf.frame/frame-app-db-value id)
   :marks  (rf.elision/sensitive-declarations id)})

(defn- publish-b!
  "Destroy A and publish a same-id B owning `flow-id`, with its own output
  leaf, marks and dirty-check row. Returns B's stores."
  [id flow-id]
  (rf.frame/destroy-frame! id)
  (rf/make-frame {:id id})
  (rf.frame/swap-frame-db! id assoc :bout ::b-sentinel)
  (rf/reg-flow flow-id
    {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
    (fn [n] (or n 0)))
  (rf.flows.registry/set-frame-flow-last-inputs! id flow-id [::b-input])
  (b-stores id flow-id))

(defn- a-detached-app-db
  "A's detached container keeps its last value after the destroy, so the
  vacation A wrote before losing ownership is readable here."
  [container]
  (get (rf.substrate.adapter/read-container container) rf.frame/app-partition-key))

(defn- with-materialized-a!
  "Register A's flow with marks, materialize its `[:out]` leaf, and return A's
  container."
  [id flow-id]
  (rf/make-frame {:id id})
  (rf/reg-flow flow-id
    {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
    (fn [n] (or n 0)))
  (rf.frame/swap-frame-db! id assoc :out ::a-output)
  (rf.flows.registry/set-frame-flow-last-inputs! id flow-id [::a-input])
  (:frame-state (rf.frame/frame id)))

(deftest clear-flow-app-db-vacation-watch-loss-does-not-corrupt-successor
  (let [id     :flow.incarnation/clear-vacate-loss
        f      :flow.incarnation/f
        armed? (atom false)
        b      (atom nil)]
    (install-watching-adapter! armed? #(reset! b (publish-b! id f)))
    (try
      (let [a-container (with-materialized-a! id f)]
        (reset! armed? true)
        (is (= f (rf/clear :flow f {:frame id})))
        (is (not (contains? (a-detached-app-db a-container) :out))
            "A's vacation landed in A's own detached container")
        (is (some? @b) "the vacation watch published a same-id B")
        (is (= @b (b-stores id f)) "A's stale tail left every B store as B left it"))
      (finally
        (restore-plain-adapter!)))))

(deftest reg-flow-move-app-db-vacation-watch-loss-does-not-corrupt-successor
  (let [id     :flow.incarnation/reg-move-loss
        g      :flow.incarnation/g
        armed? (atom false)
        b      (atom nil)]
    (install-watching-adapter! armed? #(reset! b (publish-b! id g)))
    (try
      (let [a-container (with-materialized-a! id g)]
        (reset! armed? true)
        ;; A replacement with a moved output path vacates the old leaf first.
        (rf/reg-flow g
          {:frame id :inputs [[:n2]] :output-path [:out2] :sensitive [[:out2]]}
          (fn [n] (* 2 (or n 0))))
        (is (not (contains? (a-detached-app-db a-container) :out))
            "A's old-path vacation landed in A's own detached container")
        (is (some? @b) "the vacation watch published a same-id B")
        (is (= @b (b-stores id g)) "A's stale tail left every B store as B left it"))
      (finally
        (restore-plain-adapter!)))))
