(ns re-frame.flows-concurrency-stress-test
  "Flows under parallel registration, activation, dirty evaluation and
  teardown. Each thread owns its own frame (drain locks are per frame, Spec
  002 §Rules rule 1, and same-frame `dispatch-sync` from two threads is
  refused) and registers its flows, makes its frame, runs `stress-iters`
  input changes, probes a cycle by re-making the frame, then destroys it and
  clears its flows. The invariants: each dirty evaluation runs `:derive`
  exactly once (per thread and in total, so a drop on one thread cannot hide
  behind a double on another), the activation cycle check still throws under
  contention, and teardown leaves no memo row and no `:flow` registration.

  Tagged `^:stress`, so it runs in the `:slow-test` lane only.
  `RF2_ZTW5P_STRESS_ITERS` overrides the per-thread iteration count.
  JVM-only: CLJS has no threads."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_ZTW5P_STRESS_ITERS") Long/parseLong)
      5000))

(def ^:private n-threads 8)

(defn- stress-id [prefix i] (keyword "ztw5p.stress" (str prefix i)))

(deftest ^:stress flow-reg-eval-clear-stress
  (let [global-counter (AtomicLong. 0)
        cycle-hits     (AtomicLong. 0)
        threads        (vec (for [i (range n-threads)]
                              {:frame-id (stress-id "f" i)
                               :flow-id  (stress-id "double-f" i)
                               ;; A frame running :cyc-a and :cyc-b has a cycle.
                               :cyc-a    (stress-id "cyc-a-f" i)
                               :cyc-b    (stress-id "cyc-b-f" i)
                               :counter  (atom 0)}))
        latch          (CountDownLatch. 1)]
    (rf/reg-event :ztw5p.stress/bump-input (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
    (let [futures
          (mapv (fn [{:keys [frame-id flow-id cyc-a cyc-b counter]}]
                  (future
                    (.await latch)
                    (rf/reg-flow flow-id {:inputs [[:n]] :output-path [:doubled]}
                      (fn [n]
                        (.incrementAndGet global-counter)
                        (swap! counter inc)
                        (* 2 (or n 0))))
                    (rf/reg-flow cyc-a {:inputs [[cyc-b]] :output-path [cyc-a]} identity)
                    (rf/reg-flow cyc-b {:inputs [[cyc-a]] :output-path [cyc-b]} identity)
                    (rf/make-frame {:id frame-id :flows [flow-id]})
                    ;; A distinct :n each iteration, so no dirty check skips.
                    (dotimes [i stress-iters]
                      (rf/dispatch-sync [:ztw5p.stress/bump-input (inc i)] {:frame frame-id}))
                    (try
                      (rf/make-frame {:id frame-id :flows [flow-id cyc-a cyc-b]})
                      (catch Throwable t
                        (when (re-find #":rf.error/flow-cycle" (or (ex-message t) ""))
                          (.incrementAndGet cycle-hits))))
                    (rf/destroy-frame! frame-id)
                    (doseq [id [flow-id cyc-a cyc-b]] (rf/clear :flow id))))
                threads)]
      (.countDown latch)
      (is (not-any? #{::timeout} (map #(deref % 120000 ::timeout) futures))
          "every thread finished within 120s"))
    (let [ids (mapcat (juxt :flow-id :cyc-a :cyc-b) threads)]
      (is (= {:per-thread    (repeat n-threads stress-iters)
              :total         (* n-threads stress-iters)
              :cycle-hits    n-threads
              :frame-rows    (repeat n-threads {})
              :last-inputs   {}
              :registrar     (repeat (* 3 n-threads) nil)}
             {:per-thread    (map (comp deref :counter) threads)
              :total         (.get global-counter)
              :cycle-hits    (.get cycle-hits)
              :frame-rows    (map #(rf.flows.registry/frame-last-inputs-snapshot (:frame-id %)) threads)
              :last-inputs   (select-keys (rf.flows/last-inputs-snapshot) ids)
              :registrar     (mapcat #(map (partial rf.registrar/lookup :flow) ((juxt :flow-id :cyc-a :cyc-b) %))
                                     threads)})))))
