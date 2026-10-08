(ns re-frame.subs-inline-normalization-cljs-test
  "Inline `:reg-sub` metadata is normalized through the SAME registrar contract
  as public `reg-sub` (EP-0026): `re-frame.subs/lower-inline-sub` applies the
  retired-key guard and the classification validator rather than projecting the
  raw metadata onto the runnable descriptor, and the runtime-owned slots win
  over metadata naming them. The production `:doc` strip of an assembled inline
  descriptor is pinned by `re-frame.image-inline-metadata-normalization-cljs-test`.

  `.cljc`, posture-independent: runs under `clojure -M:test`, the production
  gate and `npm run test:cljs`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.subs :as rf.subs]
            [re-frame.image :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (fn [test-fn]
    (let [snapshot (rf.test-support/snapshot-registrar)]
      (rf.registrar/clear-all!)
      (try (test-fn)
           (finally (rf.test-support/restore-registrar! snapshot))))))

(defn- caught-ex-data [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(def ^:private body (fn [_db _q] :ok))

(deftest bad-classification-hard-errors-on-inline-and-public
  (is (= [:rf.error/bad-classification :rf.error/bad-classification]
         (mapv (comp :rf.error/id caught-ex-data)
               [#(rf.subs/reg-sub :norm/pub-badcls {:sensitive :wrong} body)
                #(rf.subs/lower-inline-sub :norm/inline-badcls {:sensitive :wrong} body)]))))

(deftest valid-classification-survives-identically-on-both-paths
  (rf.subs/reg-sub :norm/pub-cls {:sensitive [[:token]]} body)
  (is (= [[[:token]] [[:token]]]
         [(:sensitive (rf.registrar/handler-meta :sub :norm/pub-cls))
          (:sensitive (rf.subs/lower-inline-sub :norm/inline-cls {:sensitive [[:token]]} body))])))

(deftest runtime-owned-slots-win-over-metadata
  (is (= {:handler-fn body :input-kind :db :input-signals []}
         (select-keys (rf.subs/lower-inline-sub :norm/inline-slots
                                                {:input-kind :parametric :input-signals [:x]}
                                                body)
                      [:handler-fn :input-kind :input-signals]))))

(deftest namespaced-extension-keys-survive-on-inline-path
  (is (= 7 (:myapp/analytics-id
            (rf.subs/lower-inline-sub :norm/inline-ext {:myapp/analytics-id 7} body)))))

(deftest retired-key-diagnostic-names-the-authored-inline-id
  ;; Through the REAL image assembly: a lowering that hardcoded a synthetic
  ;; `:rf.image/inline-sub` id would leave the diagnostic unable to name the
  ;; author's subscription.
  (let [assemble #(-> (rf.image/image
                        {:id            :norm/inline-image
                         :registrations {:reg-sub [[:counter/value {:spec [:map]} body]]}})
                      :rf.image/inline
                      first
                      rf.image-assembly/lower-inline-descriptor)]
    (is (= {:rf.error/id :rf.error/retired-registration-key :id :counter/value :replacement :schema}
           (select-keys (caught-ex-data assemble) [:rf.error/id :id :replacement])))))
