(ns re-frame.flows-output-reassert-cljs-test
  "A flow owns its `:output-path` (Spec 013 §Dirty-check semantics). A handler
  that replaces the db, rebuilds the map holding a flow's output, or writes the
  output slot itself, while leaving the flow's inputs `=`-equal, still commits
  the flow's output: the equal-input visit re-asserts the value the flow last
  computed, without calling `:derive`.

  `*-cljs-test.cljc`, so the `:node-test` build and the JVM runner both run it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.flows]
   [re-frame.flows.registry :as rf.flows.registry]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

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
    (rf/dispatch-sync [:set 5])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls]))
    (rf/dispatch-sync [:set 5])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls])
        "the replacement db gets the remembered output back, and :derive does not run")
    (rf/dispatch-sync [:set 6])
    (is (= [{:n 6 :doubled 12} 2] [(db) @calls]) "a changed input still recomputes")))

(deftest parent-map-rebuild-with-equal-inputs-keeps-the-output
  (let [calls (atom 0)]
    (rf/reg-event :init (fn [_ _] {:db {:cart {:items [1 2 3]}}}))
    (rf/reg-event :trim-cart
      (fn [{:keys [db]} _] {:db (assoc db :cart (select-keys (:cart db) [:items]))}))
    (rf/reg-flow :total {:inputs [[:cart :items]] :output-path [:cart :total]}
      (counted calls #(reduce + %)))
    (rf/dispatch-sync [:init])
    (is (= {:cart {:items [1 2 3] :total 6}} (db)))
    (rf/dispatch-sync [:trim-cart])
    (is (= [{:cart {:items [1 2 3] :total 6}} 1] [(db) @calls]))))

(deftest a-handler-write-to-the-output-slot-loses-to-the-flow
  (let [calls (atom 0)]
    (reg-replace-db!)
    (rf/reg-event :clobber (fn [{:keys [db]} _] {:db (assoc db :doubled 99)}))
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (counted calls #(* 2 %)))
    (rf/dispatch-sync [:set 5])
    (rf/dispatch-sync [:clobber])
    (is (= [{:n 5 :doubled 10} 1] [(db) @calls]))))

(defn- editor-slice [] {:draft {:title ""} :baseline {:title ""}})

(deftest editor-slice-reset-keeps-the-derived-flag
  ;; The route-entry reset shape: the flow registers through `:rf.fx/reg-flow`
  ;; at initialise, and a later reset rebuilds the slice holding its output
  ;; with draft and baseline unchanged.
  (let [calls (atom 0)
        flow  [:editor/can-submit?
               {:inputs [[:editor :draft] [:editor :baseline]]
                :output-path [:editor :can-submit?]}
               (counted calls (fn [draft baseline]
                                (boolean (and (seq (:title draft)) (not= draft baseline)))))]]
    (rf/reg-event :editor/initialise
      (fn [{:keys [db]} _] {:db (assoc db :editor (editor-slice)) :fx [[:rf.fx/reg-flow flow]]}))
    (rf/reg-event :editor/reset (fn [{:keys [db]} _] {:db (assoc db :editor (editor-slice))}))
    (rf/reg-event :editor/type
      (fn [{:keys [db]} [_ title]] {:db (assoc-in db [:editor :draft :title] title)}))
    (rf/dispatch-sync [:editor/initialise])
    (is (= [(assoc (editor-slice) :can-submit? false) 1] [(:editor (db)) @calls]))
    (rf/dispatch-sync [:editor/reset])
    (is (= [(assoc (editor-slice) :can-submit? false) 1] [(:editor (db)) @calls])
        "a reset that leaves draft and baseline unchanged keeps :can-submit?")
    (rf/dispatch-sync [:editor/type "x"])
    (is (true? (get-in (db) [:editor :can-submit?])))
    (rf/dispatch-sync [:editor/reset])
    (is (= [(assoc (editor-slice) :can-submit? false) 3] [(:editor (db)) @calls]))))

(deftest a-chained-dependent-is-repaired-in-topological-order
  ;; Registered dependent-first, so only the topological sort puts :a ahead.
  (let [calls (atom {})
        count! (fn [id f] (fn [x] (swap! calls update id (fnil inc 0)) (f x)))]
    (reg-replace-db!)
    (rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} (count! :b #(vector :b %)))
    (rf/reg-flow :a {:inputs [[:n]] :output-path [:a]} (count! :a #(* 2 %)))
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
  (rf/dispatch-sync [:init])
  (is (= {:items [] :first nil} (:cart (db))))
  (rf/dispatch-sync [:trim-cart])
  (is (= {:items [] :first nil} (:cart (db))) "a legitimate nil is a value, not an absence"))

(deftest a-repair-in-one-frame-leaves-a-sibling-frame-untouched
  (reg-replace-db!)
  (doseq [f [:x :y]]
    (rf/make-frame {:id f})
    (rf/reg-flow :doubled {:frame f :inputs [[:n]] :output-path [:doubled]} #(* 2 %)))
  (rf/dispatch-sync [:set 5] {:frame :x})
  (rf/dispatch-sync [:set 7] {:frame :y})
  (let [y-db   (rf/app-db-value :y)
        y-rows (rf.flows.registry/frame-last-inputs-snapshot :y)]
    (rf/dispatch-sync [:set 5] {:frame :x})
    (is (= {:n 5 :doubled 10} (rf/app-db-value :x)))
    (is (identical? y-db (rf/app-db-value :y)))
    (is (= {:n 7 :doubled 14} (rf/app-db-value :y)))
    (is (= y-rows (rf.flows.registry/frame-last-inputs-snapshot :y)))))

(deftest a-repair-is-tagged-on-the-skip-trace
  (let [skips (atom [])]
    (rf.trace.tooling/register-listener! ::skips
      (fn [ev] (when (= :rf.flow/skip (:operation ev)) (swap! skips conj (:tags ev)))))
    (try
      (reg-replace-db!)
      (rf/reg-event :keep (fn [{:keys [db]} _] {:db db}))
      (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} #(* 2 %))
      (rf/dispatch-sync [:set 5])
      (rf/dispatch-sync [:set 5])
      (rf/dispatch-sync [:keep])
      (is (= [[:repaired? true] nil] (mapv #(find % :repaired?) @skips))
          "the repairing skip says so; a plain skip carries no :repaired? key")
      (is (= [:inputs-value-equal :inputs-value-equal] (mapv :reason @skips)))
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
    (rf/dispatch-sync [:init])
    (let [before (db)]
      (rf/dispatch-sync [:vectorise])
      (is (= {:n 5 :out {:doubled 10}} before (db)) "the event aborted before its install")
      (is (= [{:error :rf.error/flow-eval-exception :where :flow-eval
               :flow-id :doubled :phase :output-write}]
             (mapv #(select-keys % [:error :where :flow-id :phase]) @errors))))))
