(ns re-frame.interceptor-overrides-test
  "`:interceptor-overrides` (Spec 002): replacements are references only,
  per-call wins over per-frame, and a bare-keyword key matches by entry `:id`.
  Removal, replacement and unmatched keys are pinned on the chain that ran by
  `re-frame.interceptor-override-summary-trace-test`, and exact `[id arg]`
  matching by `re-frame.interceptor-runtime-complete-cljs-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; `init!` does not create `:rf/default`, and framework operations need a
  ;; carried frame.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest value-valued-override-replacement-rejected
  (rf/reg-interceptor ::log-y {:before identity})
  (rf/reg-event :test/run
    {:interceptors [::log-y]}
    (fn [{:keys [db]} _] {:db db}))
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":rf\.error/interceptor-override-invalid"
        (rf/dispatch-sync [:test/run]
                          {:interceptor-overrides
                           {::log-y (rf.interceptor/->interceptor*
                                      :id ::inline :before identity)}}))))

(deftest per-call-overrides-per-frame-on-key-conflict
  (let [log (atom [])]
    (rf/reg-interceptor ::log {:before (fn [ctx] (swap! log conj :log) ctx)})
    (rf/reg-interceptor ::frame-stub {:before (fn [ctx] (swap! log conj :frame-stub) ctx)})
    (rf/reg-interceptor ::call-stub {:before (fn [ctx] (swap! log conj :call-stub) ctx)})
    (rf/make-frame {:id :test/scoped :interceptor-overrides {::log ::frame-stub}})
    (rf/reg-event :test/run
      {:interceptors [::log]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:test/run]
                      {:frame :test/scoped
                       :interceptor-overrides {::log ::call-stub}})
    (is (= [:call-stub] @log))))

(deftest bare-keyword-key-matches-a-factory-built-entry-by-id
  ;; A resolved `[id arg]` entry carries its factory id as `:id`, so a
  ;; bare-keyword key reaches every instance of that factory.
  (is (true? (rf.interceptor-registry/override-key-matches?
               :my/ic
               {:id :my/ic rf.interceptor-registry/authored-ref-key [:my/ic [:cart]]}))))
