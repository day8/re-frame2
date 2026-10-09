(ns re-frame.flows-output-write-attribution-cljs-test
  "A flow's output-path write failure is attributed to the write, not to the
  application's `:derive` fn: the callback and the install sit in separate
  `try` forms, so `:output-write` rides the dev `:rf.flow/failed` trace, the
  thrown ex-data and the always-on error record, and the message sends the
  programmer to the path rather than to code that returned.

  `*-cljs-test.cljc`, so the `:node-test` build and the JVM runner both run
  it: `assoc` on a vector with a keyword key throws on both hosts."
  (:require
   [clojure.string :as str]
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.trace.tooling :as rf.trace.tooling]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(deftest output-write-failure-is-attributed-to-the-write-not-to-derive
  ;; app-db holds a vector at [:items], so the legal leaf [:items :total]
  ;; fails only at install time, after :derive has returned.
  (let [traces (atom [])
        errors (atom [])
        calls  (atom 0)
        path   [:items :total]]
    (rf.trace.tooling/register-listener! ::failed
      (fn [ev] (when (= :rf.flow/failed (:operation ev)) (swap! traces conj ev))))
    (rf.error-emit/register-error-listener! ::errors #(swap! errors conj %))
    (rf/reg-event :init (fn [_ _] {:db {:source 1 :items []}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (assoc db :source 2)}))
    (rf/dispatch-sync [:init])
    (rf/reg-flow :flow/output-write {:inputs [[:source]] :output-path path}
      (fn [_] (swap! calls inc) 42))
    (let [before (rf/app-db-value :rf/default)]
      (rf/dispatch-sync [:bump])
      (is (= [1 before] [@calls (rf/app-db-value :rf/default)])
          ":derive returned once, and the event aborted before its install")
      (is (= [{:flow-id :flow/output-write :phase :output-write :path path :frame :rf/default}]
             (mapv #(select-keys (:tags %) [:flow-id :phase :path :frame]) @traces)))
      (let [[r & more] @errors
            data       (ex-data (:exception r))
            msg        (ex-message (:exception r))]
        (is (nil? more))
        (is (= {:error :rf.error/flow-eval-exception :where :flow-eval
                :flow-id :flow/output-write :phase :output-write}
               (select-keys r [:error :where :flow-id :phase])))
        (is (= {:rf.flow/failed-id :flow/output-write :rf.flow/failed-phase :output-write
                :rf.flow/output-path path}
               (select-keys data [:rf.flow/failed-id :rf.flow/failed-phase :rf.flow/output-path])))
        (is (some? (:cause data)))
        (is (= [false false true true]
               (mapv #(str/includes? msg %)
                     [":derive fn threw" "Fix the :derive fn" ":output-path" (pr-str path)]))
            "the message neither blames nor advises fixing :derive, and quotes the path")))))
