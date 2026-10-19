(ns re-frame.flows-output-reassert-cljs-test
  "Equal inputs skip derive while restoring remembered output, including nil and chained outputs."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.flows]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- run-flows!
  "Re-make `:rf/default` running the flows `ids`."
  [& ids]
  (rf/make-frame {:id :rf/default :flows (vec ids)}))

(defn- counted
  "`f`, bumping `calls` once per invocation."
  [calls f]
  (fn [& args] (swap! calls inc) (apply f args)))

(defn- reg-replace-db!
  "`:set` replaces the whole db with `{:n n}`."
  []
  (rf/reg-event :set (fn [_ [_ n]] {:db {:n n}})))

(deftest whole-db-replace-with-equal-inputs-keeps-the-output
  (let [calls (atom 0)]
    (reg-replace-db!)
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (counted calls #(* 2 %)))
    (run-flows! :doubled)
    (rf/dispatch-sync [:set 5])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls]))
    (rf/dispatch-sync [:set 5])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls])
        "the replacement db gets the remembered output back, and :derive does not run")
    (rf/dispatch-sync [:set 6])
    (is (= [{:n 6 :doubled 12} 2] [(db) @calls]) "a changed input still recomputes")))

(deftest a-handler-write-to-the-output-slot-loses-to-the-flow
  (let [calls (atom 0)]
    (reg-replace-db!)
    (rf/reg-event :clobber (fn [{:keys [db]} _] {:db (assoc db :doubled 99)}))
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (counted calls #(* 2 %)))
    (run-flows! :doubled)
    (rf/dispatch-sync [:set 5])
    (rf/dispatch-sync [:clobber])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls]))))

(deftest a-chained-dependent-is-repaired-in-topological-order
  ;; Registered and named dependent-first, so only the topological sort puts
  ;; :a ahead.
  (let [calls (atom {})
        count! (fn [id f] (fn [x] (swap! calls update id (fnil inc 0)) (f x)))]
    (reg-replace-db!)
    (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} (count! :b #(vector :b %)))
    (rf/reg-flow :a {:inputs [[:n]] :output-path [:a]} (count! :a #(* 2 %)))
    (run-flows! :b :a)
    (rf/dispatch-sync [:set 5])
    (is (= {:n 5 :a 10 :b [:b 10]} (db)))
    (rf/dispatch-sync [:set 5])
    (is (= [{:n 5 :a 10 :b [:b 10]} {:a 1 :b 1}] [(db) @calls])
        "both dropped outputs return, and neither :derive runs again")))

(deftest a-nil-output-comes-back-as-a-present-key
  (rf/reg-event :init (fn [_ _] {:db {:cart {:items []}}}))
  (rf/reg-event :trim-cart
    (fn [{:keys [db]} _] {:db (assoc db :cart (select-keys (:cart db) [:items]))}))
  (rf/reg-flow :first-item {:inputs [[:cart :items]] :output-path [:cart :first]} first)
  (run-flows! :first-item)
  (rf/dispatch-sync [:init])
  (is (= {:items [] :first nil} (:cart (db))))
  (rf/dispatch-sync [:trim-cart])
  (is (= {:items [] :first nil} (:cart (db))) "a legitimate nil is a value, not an absence"))

(deftest a-repair-is-tagged-on-the-skip-trace
  (let [skips (atom [])]
    (rf.trace.tooling/register-listener! ::skips
      (fn [ev] (when (= :rf.flow/skip (:operation ev)) (swap! skips conj (:tags ev)))))
    (try
      (reg-replace-db!)
      (rf/reg-event :keep (fn [{:keys [db]} _] {:db db}))
      (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} #(* 2 %))
      (run-flows! :doubled)
      (rf/dispatch-sync [:set 5])
      (rf/dispatch-sync [:set 5])
      (rf/dispatch-sync [:keep])
      (is (= [[:repaired? true] nil] (mapv #(find % :repaired?) @skips))
          "the repairing skip says so; a plain skip carries no :repaired? key")
      (finally
        (rf.trace.tooling/unregister-listener! ::skips)))))

(deftest a-repair-that-cannot-be-installed-is-an-output-write-failure
  ;; app-db holds a vector where the output's parent map should be, so the
  ;; re-assertion fails at install time with the inputs unchanged.
  (let [errors (atom [])]
    (rf.error-emit/register-error-listener! ::errors #(swap! errors conj %))
    (rf/reg-event :init (fn [_ _] {:db {:n 5 :out {}}}))
    (rf/reg-event :vectorise (fn [{:keys [db]} _] {:db (assoc db :out [])}))
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:out :doubled]} #(* 2 %))
    (run-flows! :doubled)
    (rf/dispatch-sync [:init])
    (let [before (db)]
      (rf/dispatch-sync [:vectorise])
      (is (= {:n 5 :out {:doubled 10}} before (db)) "the event aborted before its install")
      (is (= [{:rf.error/id :rf.error/flow-eval-exception :where :flow-eval
               :flow-id :doubled :phase :output-write}]
             (mapv #(select-keys % [:rf.error/id :where :flow-id :phase]) @errors))))))
