(ns re-frame.clear-grammar-guards-test
  "`(rf/clear kind id ?opts)`'s own argument validation, which runs before any
  frame is resolved or registry row touched (spec/API.md §Clearing
  registrations): opts on a kind that is not frame-scoped, and an unknown kind.
  The `:http-interceptor` suites cover the frame-scoped arm. The positive
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
  "Run `thunk`, expecting it to throw. Returns `{:data … :message …}` for the
  thrown `ExceptionInfo`, or nil if it returned normally."
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      {:data (ex-data e) :message (ex-message e)})))

(deftest explicit-opts-on-a-non-frame-scoped-kind-fail-closed
  ;; `:sub` takes no opts at all, so a well-formed `{:frame f}` is refused just
  ;; as a typo'd one is: the kind check runs before the map's shape is read.
  (rf/reg-sub :guard/total (fn [db _] (:total db)))
  (doseq [opts [{:frame :guard/elsewhere} {:fram :guard/elsewhere}]]
    (is (= {:rf.error/id :rf.error/registrar-clear-bad-request
            :reason      :opts-on-a-non-frame-scoped-kind
            :kind        :sub
            :where       'rf/clear
            :recovery    :fix-the-clear-call}
           (select-keys (:data (refused #(rf/clear :sub :guard/total opts)))
                        [:rf.error/id :reason :kind :where :recovery]))
        (pr-str opts)))
  (is (some? (rf.registrar/handler-meta :sub :guard/total))
      "zero residue: the refused calls cleared nothing"))

(deftest unknown-kind-fails-closed-and-reports-the-closed-set
  (let [{:keys [data message]} (refused #(rf/clear :widget :guard/nope))
        kinds                  (set (:clear-kinds data))]
    (is (= {:rf.error/id :rf.error/registrar-clear-bad-request
            :reason      :unknown-kind
            :kind        :widget}
           (select-keys data [:rf.error/id :reason :kind])))
    ;; One registrar kind, the added `:http-interceptor`, and the removed `:frame`
    ;; (a live runtime object torn down by `destroy-frame!`).
    (is (every? kinds [:event :http-interceptor]))
    (is (not (contains? kinds :frame)))
    (is (re-find #":resource-scope" (str message))
        "the message spells the closed set out")))
