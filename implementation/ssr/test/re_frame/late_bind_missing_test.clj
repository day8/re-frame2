(ns re-frame.late-bind-missing-test
  "The ssr artefact's `re-frame.core` registrar re-exports raise
  `:rf.error/ssr-artefact-missing` when the artefact is absent. The artefact
  is loaded here, so absence is simulated by setting the late-bind hook to nil.
  The wrapper's generic error shape is pinned by `re-frame.core-artefact-test`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr]))

(deftest registrar-re-exports-raise-when-ssr-artefact-missing
  (doseq [[hook where id register!]
          [[:ssr/reg-error-projector 'rf/reg-error-projector :probe/projector
            #(rf/reg-error-projector :probe/projector (fn [_trace-event] {}))]
           [:ssr/reg-head 'rf/reg-head :probe/head
            #(rf/reg-head :probe/head (fn [_db _route] {}))]]]
    (let [original (rf.late-bind/get-fn hook)]
      (try
        (rf.late-bind/set-fn! hook nil)
        (is (= {:rf.error/id :rf.error/ssr-artefact-missing :where where :id id}
               (try (register!)
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      (select-keys (ex-data e) [:rf.error/id :where :id])))))
        (finally
          (rf.late-bind/set-fn! hook original))))))
