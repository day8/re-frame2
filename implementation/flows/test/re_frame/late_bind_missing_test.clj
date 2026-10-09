(ns re-frame.late-bind-missing-test
  "The flows artefact's missing-artefact contract (Spec 002 §The late-bind
  seam): a user-facing flow surface called while the artefact is absent
  raises `:rf.error/flows-artefact-missing`. The artefact is on this
  classpath, so each test clears the hook for its duration, the same
  mechanism a CLJS test would use.

  The framework-internal hooks (`:flows/run-flows-on-db`,
  `:flows/reset-flows!`, `:flows/teardown-on-frame-destroy!`) no-op when
  absent instead; every test lane without the flows artefact dispatches,
  resets and destroys frames through them, so a regression there fails
  those lanes."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- with-hook-as-nil
  "Run `f` with the late-bind hook `hook-key` cleared, restoring it after."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest reg-flow-raises-when-flows-artefact-missing
  ;; The macro expands to a runtime late-bind lookup, so clearing the hook at
  ;; runtime is enough.
  (with-hook-as-nil :flows/reg-flow
    (fn []
      (let [e (try (rf/reg-flow :late-bind-missing/probe {:inputs [] :output-path [:probe]} (fn [] 0))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (re-find #"\[:rf\.error/flows-artefact-missing\]" (ex-message e)))
        (is (= {:rf.error/id :rf.error/flows-artefact-missing :where 'rf/reg-flow :recovery :no-recovery}
               (select-keys (ex-data e) [:rf.error/id :where :recovery])))))))

(deftest flow-fxs-raise-when-flows-artefact-missing
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :late-bind-missing/fx})
  (rf/reg-event :late-bind-missing/flow-fxs
    (fn [_ _]
      {:fx [[:rf.fx/reg-flow [:late-bind-missing/flow
                              {:inputs [[:in]] :output-path [:out]}
                              (fn [v] v)]]
            [:rf.fx/clear-flow :late-bind-missing/flow]]}))
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::flow-fxs #(swap! records conj %))
    (try
      (with-hook-as-nil :flows/reg-flow
        (fn []
          (with-hook-as-nil :flows/clear-flow
            (fn []
              (rf/dispatch-sync [:late-bind-missing/flow-fxs]
                                {:frame :late-bind-missing/fx})))))
      (finally
        (rf.error-emit/unregister-error-listener! ::flow-fxs)))
    (is (= [[:rf.error/flows-artefact-missing :rf.fx/reg-flow :late-bind-missing/fx]
            [:rf.error/flows-artefact-missing :rf.fx/clear-flow :late-bind-missing/fx]]
           (mapv (juxt :error :failing-id :frame) @records))
        "each flow effect is refused with the missing-artefact id, naming the effect")))
