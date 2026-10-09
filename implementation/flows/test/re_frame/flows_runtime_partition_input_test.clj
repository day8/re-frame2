(ns re-frame.flows-runtime-partition-input-test
  "The `[:rf.db/runtime …]` partition-qualified flow input at run time
  (EP-0001 §535-551), driven through `run-flows-on-db` with an explicit
  runtime-db: the dirty check keys on both partitions, mixed inputs resolve
  in declaration order against their own partitions, and a runtime input's
  trace value elides against its partition-relative declaration path."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest runtime-only-change-recomputes-while-app-db-value-identical
  ;; The same app-db value is handed to every pass, so only runtime-db can
  ;; dirty the flow; a value-equal runtime-db must skip.
  (let [calls  (atom [])
        app-db {:unrelated 1}
        rt     (fn [route-id] {:rf.runtime/routing {:current {:route-id route-id}}})]
    (rf/reg-flow :route-slug
      {:inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]] :output-path [:slug]}
      (fn [route-id] (swap! calls conj route-id) route-id))
    (is (= [{:unrelated 1 :slug :home} {:unrelated 1 :slug :about} app-db]
           (mapv #(rf.flows/run-flows-on-db :rf/default app-db (rt %)) [:home :about :about])))
    (is (= [:home :about] @calls))))

(deftest mixed-app-db-and-runtime-inputs-resolve-in-declaration-order
  (rf/reg-flow :mixed
    {:inputs [[:app-first] [:rf.db/runtime :rt :mid] [:app-last]] :output-path [:combined]}
    vector)
  (is (= [:A :R :B]
         (:combined (rf.flows/run-flows-on-db :rf/default {:app-first :A :app-last :B} {:rt {:mid :R}})))))

(deftest runtime-input-trace-value-elides-at-stripped-declaration-path
  ;; The elision registry keys declarations by partition-relative path, so the
  ;; runtime input `[:rf.db/runtime :rt :val]` elides against `[:rt :val]`.
  (let [computed (atom [])]
    (rf.trace.tooling/register-listener!
      ::computed
      (fn [ev] (when (= :rf.flow/computed (:operation ev)) (swap! computed conj ev))))
    (try
      (rf/reg-flow :route-blob {:inputs [[:rf.db/runtime :rt :val]] :output-path [:out]} identity)
      (rf.frame/swap-runtime-db! :rf/default
        (fn [rt] (rf.elision/apply-classification-effects rt {:large [[:rt :val]]})))
      (rf.flows/run-flows-on-db :rf/default {}
        (assoc-in (rf.frame/frame-runtime-db-value :rf/default) [:rt :val] {:big "payload"}))
      (let [[input] (get-in (peek @computed) [:tags :input-values])]
        (is (= [:rt :val] (get-in input [:rf.size/large-elided :path]))))
      (finally
        (rf.trace.tooling/unregister-listener! ::computed)))))
