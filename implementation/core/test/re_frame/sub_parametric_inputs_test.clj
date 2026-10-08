(ns re-frame.sub-parametric-inputs-test
  "Parametric subscription inputs, `(reg-sub id {:inputs input-fn} body)`
  (docs/EP/EP-0004-subscription-inputs.md §Test Plan; Spec 006 §Subscription
  input producers). The `input-fn` is a pure function from the outer `query-v`
  to a vector of input query-vectors; each realized input resolves in the outer
  subscription's frame, and the cache entry records the realized query-vectors.
  The grammar and vector delivery are pinned by `re-frame.sub-declared-inputs-test`.

  The three error ids fire loudly. `:rf.error/reg-sub-bad-args`'s trace is a
  bare dev `emit-error!`; `sub-input-fn-exception` and `sub-input-fn-bad-return`
  are promoted to the always-on error registry, so their occurrence, sub-id and
  query-v are readable in production too (`:where` rides only the dev trace)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            ;; load the tooling sibling so the late-bind hooks behind the
            ;; public listener API resolve.
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default` and ambient reads need a
  ;; carried frame (EP-0002), so register it and pin it as the scope.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- capture-errors!
  "Collect every dev trace event whose `:operation` is `error-kw`. Returns
  `[errs-atom unregister-fn]`."
  [error-kw]
  (let [errs (atom [])
        k    (keyword "rf2-7brl74" (str (name error-kw) "-" (gensym)))]
    (rf/register-listener! :trace k
                           (fn [ev]
                             (when (= error-kw (:operation ev))
                               (swap! errs conj ev))))
    [errs #(rf/unregister-listener! :trace k)]))

(defn- capture-error-records!
  "Collect every always-on error-registry record for `error-kw`. Returns
  `[records-atom unregister-fn]`."
  [error-kw]
  (let [recs (atom [])
        k    (keyword "rf2-d2841" (str (name error-kw) "-rec-" (gensym)))]
    (rf.error-emit/register-error-listener! k
                           (fn [r] (when (= error-kw (:error r))
                                     (swap! recs conj r))))
    [recs #(rf.error-emit/unregister-error-listener! k)]))

(defn- entry [frame-id query-v]
  (get-in @(:sub-cache (rf.frame/frame frame-id)) [query-v]))

(defn- reg-item-title! []
  (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
  (rf/reg-sub :item/title
              {:inputs (fn [[_ id]] [[:item/by-id id]])}
              (fn [[item] _] (:title item))))

(deftest normalize-rejects-every-shape-but-a-vector-of-query-vectors
  (doseq [bad [:viewer/current        ;; not a vector
               [(atom [:a])]          ;; a vector of reactions, not query vectors
               [[:a] [42 :b]]]]       ;; a query vector whose head is not a keyword
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (rf.subs/normalize-sub-inputs bad))
        (pr-str bad))))

(deftest parametric-recomputes-reactively-on-upstream-change
  (reg-item-title!)
  (rf/reg-event :seed   (fn [_ _]               {:db {:items {:x {:title "v1"}}}}))
  (rf/reg-event :rename (fn [{:keys [db]} [_ t]] {:db (assoc-in db [:items :x :title] t)}))
  (rf/dispatch-sync [:seed])
  (let [r      (rf/subscribe [:item/title :x])
        before @r]
    (rf/dispatch-sync [:rename "v2"])
    (is (= ["v1" "v2"] [before @r]))))

(deftest distinct-outer-query-vs-get-distinct-realized-inputs
  ;; Each concrete cache entry records the edges it realized.
  (reg-item-title!)
  (rf/reg-event :seed (fn [_ _] {:db {:items {:x {:title "X"} :y {:title "Y"}}}}))
  (rf/dispatch-sync [:seed])
  (rf/subscribe [:item/title :x])
  (rf/subscribe [:item/title :y])
  (is (= [[[:item/by-id :x]] [[:item/by-id :y]]]
         [(:inputs (entry :rf/default [:item/title :x]))
          (:inputs (entry :rf/default [:item/title :y]))])))

(deftest hot-reload-invalidates-downstream-of-realized-upstream
  ;; Re-registering a realized upstream evicts its transitive dependents.
  (reg-item-title!)
  (rf/reg-event :seed (fn [_ _] {:db {:items {:x {:title "X"}}}}))
  (rf/dispatch-sync [:seed])
  (rf/subscribe [:item/title :x])
  (rf/reg-sub :item/by-id (fn [db [_ id]] (assoc (get-in db [:items id]) :hot true)))
  (is (nil? (entry :rf/default [:item/title :x]))))

(deftest multi-frame-realized-inputs-resolve-in-outer-frame
  (rf/make-frame {:id :frame-a})
  (rf/make-frame {:id :frame-b})
  (reg-item-title!)
  (rf/reg-event :seed (fn [_ [_ title]] {:db {:items {:x {:title title}}}}))
  (rf/dispatch-sync [:seed "A-title"] {:frame :frame-a})
  (rf/dispatch-sync [:seed "B-title"] {:frame :frame-b})
  (is (= ["A-title" "B-title"]
         (mapv #(rf/subscribe-once [:item/title :x] {:frame %}) [:frame-a :frame-b]))))

(deftest reg-sub-bad-args-rejects-and-emits
  ;; Three trailing args (input-fn and two fns) is not an accepted shape.
  (let [[errs unreg] (capture-errors! :rf.error/reg-sub-bad-args)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reg-sub-bad-args"
            (rf/reg-sub :bad (fn [_] [[:a]]) (fn [a _] a) (fn [x _] x))))
      (when rf.interop/debug-enabled?
        (is (some #(= :bad (get-in % [:tags :rf.sub/id])) @errs)
            "the dev trace carries the offending sub id"))
      (finally (unreg)))))

(deftest sub-input-fn-exception-fires-and-recovers
  ;; Both read paths recover the sub to nil and report the throw.
  (let [[errs unreg] (capture-errors! :rf.error/sub-input-fn-exception)
        [recs unrec] (capture-error-records! :rf.error/sub-input-fn-exception)]
    (try
      (rf/reg-sub :boom
                  {:inputs (fn [_] (throw (ex-info "input-fn boom" {})))}
                  (fn [[v] _] v))
      (rf/reg-event :seed (fn [_ _] {:db {:leaf 1}}))
      (rf/dispatch-sync [:seed])
      (is (= [nil nil] [(rf/compute-sub [:boom] {:leaf 1}) (rf/subscribe-once [:boom])]))
      (is (= [:boom :boom] (mapv :event-id @recs)) "an always-on record from each path")
      (when rf.interop/debug-enabled?
        (is (= #{:compute-sub :reactive} (set (map #(get-in % [:tags :where]) @errs)))))
      (finally (unrec) (unreg)))))

(deftest sub-input-fn-bad-return-fires-and-recovers
  ;; A bad input return recovers the sub to nil without running the body, and
  ;; is never treated as an empty input set.
  (let [[errs unreg] (capture-errors! :rf.error/sub-input-fn-bad-return)
        [recs unrec] (capture-error-records! :rf.error/sub-input-fn-bad-return)]
    (try
      (rf/reg-sub :leaf (fn [db _] (:leaf db)))
      (rf/reg-sub :bad-shape {:inputs (fn [_] [:leaf :extra])} (fn [_ _] :ran))
      (rf/reg-event :seed (fn [_ _] {:db {:leaf 1}}))
      (rf/dispatch-sync [:seed])
      (is (= [nil nil] [(rf/compute-sub [:bad-shape] {:leaf 1}) (rf/subscribe-once [:bad-shape])]))
      ;; The record carries the query-vector verbatim as its `:event`.
      (is (= (repeat 2 {:event [:bad-shape] :event-id :bad-shape})
             (map #(select-keys % [:event :event-id]) @recs)))
      (when rf.interop/debug-enabled?
        (is (= [#{:compute-sub :reactive} true]
               [(set (map #(get-in % [:tags :where]) @errs))
                (boolean (some #(= [:bad-shape] (get-in % [:tags :rf.sub/query-v])) @errs))])))
      (finally (unrec) (unreg)))))

;; `handler?` is fn-or-Var, not bare `ifn?`: HoF and `requiring-resolve` call
;; sites register with a Var, and storing the Var lets a REPL redefinition take
;; effect.
(defn- a-var-layer-1-handler [db _] (:v db))

(deftest var-valued-handler-is-accepted
  (rf/reg-sub :vh #'a-var-layer-1-handler)
  (rf/reg-event :seed-vh (fn [_ _] {:db {:v 42}}))
  (rf/dispatch-sync [:seed-vh])
  (is (= [true 42] [(var? (:handler-fn (rf.registrar/lookup :sub :vh)))
                    (rf/subscribe-once [:vh])])))
