(ns re-frame.flows-runtime-partition-input-test
  "The `[:rf.db/runtime …]` partition-qualified flow input at run time
  (EP-0001 §535-551), driven through `run-flows-on-db` with an explicit
  runtime-db: the dirty check keys on both partitions, mixed inputs resolve
  in declaration order against their own partitions, and a runtime input's
  trace value elides against its partition-relative declaration path."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- run-flows!
  "Re-make `:rf/default` running the flows `ids`."
  [& ids]
  (rf/make-frame {:id :rf/default :flows (vec ids)}))

(defn- pass!
  "Run `:rf/default`'s flow pass over `db` and `runtime-db`, and publish its
  memo as the router does once the candidate installs. Returns the candidate
  app-db."
  [db runtime-db]
  (let [{:keys [db publish!]} (rf.flows/run-flows-on-db :rf/default db runtime-db)]
    (when publish! (publish!))
    db))

(deftest runtime-only-change-recomputes-while-app-db-value-identical
  ;; The same app-db value is handed to every pass, so only runtime-db can
  ;; dirty the flow. A value-equal runtime-db skips `:derive`, and the pass
  ;; re-asserts the remembered output into the app-db it was handed.
  (let [calls  (atom [])
        app-db {:unrelated 1}
        rt     (fn [route-id] {:rf.runtime/routing {:current {:route-id route-id}}})]
    (rf/reg-flow :route-slug
      {:inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]] :output-path [:slug]}
      (fn [route-id] (swap! calls conj route-id) route-id))
    (run-flows! :route-slug)
    (is (= [{:unrelated 1 :slug :home} {:unrelated 1 :slug :about} {:unrelated 1 :slug :about}]
           (mapv #(pass! app-db (rt %)) [:home :about :about])))
    (is (= [:home :about] @calls))))

(deftest mixed-app-db-and-runtime-inputs-resolve-in-declaration-order
  (rf/reg-flow :mixed
    {:inputs [[:app-first] [:rf.db/runtime :rt :mid] [:app-last]] :output-path [:combined]}
    vector)
  (run-flows! :mixed)
  (is (= [:A :R :B]
         (:combined (pass! {:app-first :A :app-last :B} {:rt {:mid :R}})))))

(deftest runtime-input-trace-value-elides-at-stripped-declaration-path
  ;; The elision registry keys declarations by partition-relative path, so the
  ;; runtime input `[:rf.db/runtime :rt :val]` elides against `[:rt :val]`.
  (let [computed (atom [])]
    (rf.trace.tooling/register-listener!
      ::computed
      (fn [ev] (when (= :rf.flow/computed (:operation ev)) (swap! computed conj ev))))
    (try
      (rf/reg-flow :route-blob {:inputs [[:rf.db/runtime :rt :val]] :output-path [:out]} identity)
      (run-flows! :route-blob)
      (rf/reg-classification ::classified {:large [[:rt :val]]})
      (pass! {} (assoc-in (rf.frame/frame-runtime-db-value :rf/default) [:rt :val] {:big "payload"}))
      (let [[input] (get-in (peek @computed) [:tags :input-values])]
        (is (= [:rt :val] (get-in input [:rf.size/large-elided :path]))))
      (finally
        (rf.trace.tooling/unregister-listener! ::computed)))))
