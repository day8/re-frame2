(ns re-frame.source-coord-prod-elision-test
  "Source-coord production elision. Under the disabled debug gate the public
  `rf/handler-meta` carries no coord keys, because Xray's Open-in-editor and
  re-frame-pair are dev-only. The always-on `error-coords-by-id` registry keeps
  them for the error-emit record that off-box shippers read; that half is pinned
  by `re-frame.source-coords-test` and `re-frame.source-coord-jvm-test`.

  JVM-only (`_test.clj`) so the gate can be modelled with `with-redefs`; under
  `scripts/test-core-prod-gate.sh` the redef is a no-op over an already-false
  flag. The CLJS bundle-level absence is pinned by `scripts/check-elision.cjs`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest registry-meta-strips-coord-keys-under-disabled-debug-gate
  (with-redefs [rf.interop/debug-enabled? false]
    (rf/reg-event :rf2-3un2g/prod-elide-event (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta {:source :store :kind :event :id :rf2-3un2g/prod-elide-event})]
      (is (some? meta))
      (is (= {} (select-keys meta [:ns :file :line :column]))))))

(deftest programmatic-registration-no-source-coord-in-error-record
  ;; A fn-form registration binds no `*pending-coords*`, so the always-on error
  ;; record omits `:source-coord` rather than carrying nil.
  ((requiring-resolve 're-frame.events/reg-event)
   :rf2-3un2g/programmatic
   (fn [_cofx _] (throw (ex-info "boom" {}))))
  (let [seen (atom nil)]
    (rf.error-emit/register-error-listener! :rf2-3un2g/programmatic-recorder
                                            (fn [record] (reset! seen record)))
    (rf/dispatch-sync [:rf2-3un2g/programmatic])
    (is (some? @seen))
    (is (not (contains? @seen :source-coord)))))
