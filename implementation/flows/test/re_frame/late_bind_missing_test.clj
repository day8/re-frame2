(ns re-frame.late-bind-missing-test
  "The flows artefact's missing-artefact contract (Spec 002 §The late-bind
  seam): `reg-flow`, and a frame naming `:flows`, raise
  `:rf.error/flows-artefact-missing` while the artefact is absent. The
  artefact is on this classpath, so each test clears the hooks for its
  duration, the same mechanism a CLJS test would use.

  The framework-internal hooks (`:flows/run-flows-on-db`,
  `:flows/reset-flows!`, `:flows/teardown-on-frame-destroy!`) no-op when
  absent instead; every test lane without the flows artefact dispatches,
  resets and destroys frames through them, so a regression there fails
  those lanes."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
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

(deftest a-frame-naming-flows-raises-when-flows-artefact-missing
  ;; The activation checks need the artefact too; with both hooks absent the
  ;; frame engine's preflight refuses the construction before any record
  ;; exists. A frame naming no flows needs nothing.
  (rf/init! rf.substrate.plain-atom/adapter)
  (with-hook-as-nil :flows/check-activation!
    (fn []
      (with-hook-as-nil :flows/run-flows-on-db
        (fn []
          (let [e (try (rf/make-frame {:id :late-bind-missing/frame :flows [:late-bind-missing/flow]})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= {:rf.error/id :rf.error/flows-artefact-missing :where 'rf/make-frame}
                   (select-keys (ex-data e) [:rf.error/id :where])))
            (is (nil? (rf.frame/frame :late-bind-missing/frame))))
          (rf/make-frame {:id :late-bind-missing/frame :flows []})
          (is (some? (rf.frame/frame :late-bind-missing/frame)))
          (rf/destroy-frame! :late-bind-missing/frame))))))
