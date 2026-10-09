(ns re-frame.late-bind-missing-test
  "`re-frame.core`'s `reg-app-schema` macro raises the documented
  `:rf.error/schemas-artefact-missing` when the schemas artefact is absent
  (Spec 002 §The late-bind seam). The artefact is on this classpath, so the
  absent state is simulated by setting its late-bind hook to nil."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas]))

(deftest reg-app-schema-raises-when-schemas-artefact-missing
  (let [original (rf.late-bind/get-fn :schemas/reg-app-schema)
        thrown   (try
                   (rf.late-bind/set-fn! :schemas/reg-app-schema nil)
                   (rf/reg-app-schema [:probe] :int)
                   nil
                   (catch clojure.lang.ExceptionInfo e e)
                   (finally
                     (rf.late-bind/set-fn! :schemas/reg-app-schema original)))]
    (is (= {:rf.error/id :rf.error/schemas-artefact-missing
            :where       'rf/reg-app-schema
            :path        [:probe]
            :recovery    :no-recovery}
           (select-keys (ex-data thrown) [:rf.error/id :where :path :recovery])))
    (is (re-find #"\[:rf\.error/schemas-artefact-missing\]" (ex-message thrown))
        "the message ends with the error-id token")))
