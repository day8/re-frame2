(ns re-frame.classification-key-refused-cljs-test
  "A handler returning `:sensitive`, `:large`, `:clear-sensitive` or
  `:clear-large` beside `:db` is refused by `:rf.error/effect-map-shape`:
  nothing commits, and the dev trace's reason names `reg-classification`,
  where app-db classification is declared (Spec 015 §Static app-db
  classification, Spec-Schemas §`:rf/effect-map`).

  The always-on assertions read the `:errors` registry, so the namespace runs
  in the production gate too; the reason rides the dev trace. Dual-runtime
  `*_cljs_test.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(defn- refuse!
  "Seed `:n` to 1, dispatch an event returning `k` beside a `:db` write, and
  return `[records traces]`: the always-on records and dev traces it emitted."
  [k]
  (rf/reg-event ::seed (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
  (rf/dispatch-sync [::seed])
  (rf/reg-event ::classify
    (fn [{:keys [db]} _] {:db (assoc db :n 2 :token "t") k [[:token]]}))
  (let [records (atom [])
        traces  (atom [])]
    (rf.error-emit/register-error-listener! ::records #(swap! records conj %))
    (rf/register-listener! :trace ::traces #(swap! traces conj %))
    (try (rf/dispatch-sync [::classify])
         (finally
           (rf.error-emit/unregister-error-listener! ::records)
           (rf/unregister-listener! :trace ::traces)))
    [@records @traces]))

(deftest each-classification-key-is-refused-and-commits-nothing
  (doseq [k [:sensitive :large :clear-sensitive :clear-large]]
    (testing (str k)
      (let [[records traces] (refuse! k)]
        (is (= 1 (:n (rf.frame/frame-app-db-value :rf/default)))
            "the event is refused before its :db write commits")
        (is (= [k] (into []
                         (comp (filter #(= :rf.error/effect-map-shape (:rf.error/id %)))
                               (map :offending-key))
                         records))
            "one always-on :rf.error/effect-map-shape record names the key")
        (when rf.interop/debug-enabled?
          (is (some #(and (= :rf.error/effect-map-shape (:operation %))
                          (str/includes? (str (get-in % [:tags :reason])) "reg-classification"))
                    traces)
              "the dev trace's reason names reg-classification"))))))
