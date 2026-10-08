(ns re-frame.substrate.derived-container-replaced-cljs-test
  "Spec 006 §`make-derived-value`: `replace-container!` on a derived container
  is a programmer error. The core's choke point
  (`re-frame.substrate.adapter/replace-container!`, which every app-db write
  flows through) throws the canonical `:rf.error/derived-container-replaced`
  ex-info without invoking the adapter, and in dev also emits the matching
  `:error` trace (Spec 009). The plain-atom adapter exercises both host branches
  of `replaceable-container?`: `.cljc` runs on the JVM and on node.

  The throw is production behaviour; the trace is a bare `rf.trace/emit!` site,
  elided under `-Dre-frame.debug=false`, so its assertions sit behind
  `rf.interop/debug-enabled?`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Load the tooling sibling so the late-bind hooks behind the
            ;; listener API resolve on both runtimes (mirrors
            ;; trace-listener-test).
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- tests ----------------------------------------------------------------

(defn- derived-container []
  (rf.substrate.adapter/make-derived-value [(rf.substrate.adapter/make-state-container {:n 7})] :n))

(defn- thrown-by [thunk]
  (try (thunk) nil (catch #?(:clj Throwable :cljs :default) e e)))

(deftest replace-on-derived-container-throws
  (is (= {:rf.error/id :rf.error/derived-container-replaced
          :where       'rf/replace-container!
          :recovery    :no-recovery}
         (select-keys (ex-data (thrown-by #(rf.substrate.adapter/replace-container!
                                              (derived-container) 42)))
                      [:rf.error/id :where :recovery]))))

(deftest replace-on-derived-container-emits-error-trace
  (let [seen (atom [])
        k    ::derived-replaced-capture]
    (rf.trace.tooling/register-listener! k (fn [ev]
                                             (when (= :error (:op-type ev))
                                               (swap! seen conj ev))))
    (try (thrown-by #(rf.substrate.adapter/replace-container! (derived-container) 99))
         (finally (rf.trace.tooling/unregister-listener! k)))
    (when rf.interop/debug-enabled?
      (is (= [{:operation :rf.error/derived-container-replaced
               :category  :rf.error/derived-container-replaced
               :recovery  :no-recovery
               :reason?   true}]
             (for [ev @seen
                   :when (= :rf.error/derived-container-replaced (:operation ev))]
               {:operation (:operation ev)
                :category  (get-in ev [:tags :category])
                :recovery  (:recovery ev)
                :reason?   (string? (get-in ev [:tags :reason]))}))))))
