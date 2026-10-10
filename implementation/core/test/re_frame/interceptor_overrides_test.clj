(ns re-frame.interceptor-overrides-test
  "`:interceptor-overrides` (Spec 002): replacements are references only,
  per-call wins over per-frame, a bare-keyword key matches only an entry
  authored as that keyword, and a reference an override removes or replaces is
  never built.
  Removal, replacement and unmatched keys are pinned on the chain that ran by
  `re-frame.interceptor-override-summary-trace-test`, and exact `[id arg]`
  matching by `re-frame.interceptor-runtime-complete-cljs-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
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

(deftest overridden-reference-is-never-built
  ;; Overrides edit the authored references before any of them resolves, so a
  ;; factory whose reference an override removes or replaces never runs — not
  ;; even one a hot reload has made throw.
  (let [built (atom [])
        ran   (atom [])]
    (rf/reg-interceptor ::fact
      {:factory (fn [arg] (swap! built conj [::fact arg]) {:before identity})})
    (rf/reg-interceptor ::stand-in
      {:factory (fn [arg] (swap! built conj [::stand-in arg]) {:before identity})})
    (rf/reg-event :test/run
      {:interceptors [[::fact 1]]}
      (fn [{:keys [db]} [_ tag]] (swap! ran conj tag) {:db db}))
    (is (= [] @built) "registration checks the reference without building it")
    (rf/dispatch-sync [:test/run :removed] {:interceptor-overrides {[::fact 1] nil}})
    (rf/dispatch-sync [:test/run :replaced] {:interceptor-overrides {[::fact 1] [::stand-in 2]}})
    (is (= [[::stand-in 2]] @built) "only the replacement was built")
    (rf/reg-interceptor ::fact {:factory (fn [_] (throw (ex-info "factory boom" {})))})
    (rf/dispatch-sync [:test/run :removed-after-reload] {:interceptor-overrides {[::fact 1] nil}})
    (is (= [:removed :replaced :removed-after-reload] @ran))))

(deftest bare-keyword-key-never-matches-by-entry-id
  ;; Every resolved entry carries an `:id`: a factory-built one its factory
  ;; id, the framework's handler wrapper `:rf/event-handler`. Neither is an
  ;; authored bare-keyword reference, so neither key below matches anything.
  (let [log (atom [])]
    (rf/reg-interceptor ::tag
      {:factory (fn [tag] {:before (fn [ctx] (swap! log conj tag) ctx)})})
    (rf/reg-event :test/run
      {:interceptors [[::tag :a]]}
      (fn [{:keys [db]} _] (swap! log conj :handler) {:db db}))
    (rf/dispatch-sync [:test/run]
                      {:interceptor-overrides {::tag nil :rf/event-handler nil}})
    (is (= [:a :handler] @log))))
