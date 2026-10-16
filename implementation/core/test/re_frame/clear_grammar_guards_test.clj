(ns re-frame.clear-grammar-guards-test
  "`(rf/clear kind id ?opts)`'s own argument validation, which runs before any
  frame is resolved or registry row touched (spec/API.md §Clearing
  registrations): opts on a kind that is not frame-scoped, and an unknown kind.
  The `:flow` suites cover the frame-scoped arm. The positive
  control — `clear` really deregistering a `:sub` — is
  `re-frame.sub-cache-test/clear-sub-id-leaves-cache-intact`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- refused
  "Run `thunk`, expecting it to throw. Returns its ex-data, or nil if it returned normally."
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest explicit-opts-on-a-non-frame-scoped-kind-fail-closed
  (rf/reg-sub :guard/total (fn [db _] (:total db)))
  (is (= {:rf.error/id :rf.error/registrar-clear-bad-request
          :reason      :opts-on-a-non-frame-scoped-kind
          :kind        :sub
          :where       'rf/clear
          :recovery    :fix-the-clear-call}
         (select-keys (refused #(rf/clear :sub :guard/total {:frame :guard/elsewhere}))
                      [:rf.error/id :reason :kind :where :recovery])))
  (is (some? (rf.registrar/handler-meta :sub :guard/total))
      "zero residue: the refused call cleared nothing"))

(deftest unknown-kind-fails-closed-and-reports-the-closed-set
  (let [data            (refused #(rf/clear :widget :guard/nope))
        kinds          (set (:clear-kinds data))]
    (is (= {:rf.error/id :rf.error/registrar-clear-bad-request
            :reason      :unknown-kind
            :kind        :widget}
           (select-keys data [:rf.error/id :reason :kind])))
    ;; Registrar kinds, `:http-interceptor` among them, and the removed `:frame`
    ;; (a live runtime object torn down by `destroy-frame!`).
    (is (every? kinds [:event :http-interceptor]))
    (is (not (contains? kinds :frame)))))
