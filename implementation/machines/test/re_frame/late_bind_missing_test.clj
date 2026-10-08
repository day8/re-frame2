(ns re-frame.late-bind-missing-test
  "The documented missing-artefact error contract for the machines surface on
  the `re-frame.core` facade (Spec 002 §The late-bind seam): `rf/reg-machine`
  raises `:rf.error/machines-artefact-missing` when the machines artefact is
  absent. The artefact IS loaded here, so the test nils the late-bind hook for
  the duration of the call. `reg-machine` is the only machine surface on the
  facade; the fn surfaces live on `re-frame.machines`, whose require implies the
  artefact."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]))

(deftest reg-machine-macro-raises-when-machines-artefact-missing
  (let [original (rf.late-bind/get-fn :machines/reg-machine)
        thrown   (try
                   (rf.late-bind/set-fn! :machines/reg-machine nil)
                   (rf/reg-machine :probe/macro-machine {:initial :idle :states {:idle {}}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e)
                   (finally (rf.late-bind/set-fn! :machines/reg-machine original)))]
    (is (= {:rf.error/id :rf.error/machines-artefact-missing
            :where       'rf/reg-machine
            :machine-id  :probe/macro-machine
            :recovery    :no-recovery}
           (select-keys (ex-data thrown) [:rf.error/id :where :machine-id :recovery])))))
