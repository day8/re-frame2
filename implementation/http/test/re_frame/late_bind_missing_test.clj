(ns re-frame.late-bind-missing-test
  "The documented missing-artefact error contract for the http artefact's
  `re-frame.core` re-export (Spec 002 §The late-bind seam).

  The http artefact IS on the classpath here (requiring `re-frame.http.managed`
  publishes its late-bind hooks), so the absent state is simulated by setting
  the hook to nil for the assertion and restoring it after. The stub family
  (`with-request-stubs` and the install/uninstall pair) is not a
  `re-frame.core` export and has no hook, so `reg-http-interceptor` is the
  re-export that carries the contract."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.http.managed]))

(deftest reg-http-interceptor-raises-when-http-artefact-missing
  (testing "rf/reg-http-interceptor raises :rf.error/http-artefact-missing when the :http/reg-http-interceptor hook is nil"
    (let [original (rf.late-bind/get-fn :http/reg-http-interceptor)
          thrown   (try
                     (rf.late-bind/set-fn! :http/reg-http-interceptor nil)
                     (rf/reg-http-interceptor ::probe {:before identity})
                     nil
                     (catch clojure.lang.ExceptionInfo e e)
                     (finally
                       (rf.late-bind/set-fn! :http/reg-http-interceptor original)))]
      (is (re-find #"\[:rf\.error/http-artefact-missing\]" (.getMessage thrown))
          "the message carries the [:rf.error/http-artefact-missing] token")
      (is (= {:rf.error/id :rf.error/http-artefact-missing
              :where       'rf/reg-http-interceptor
              :recovery    :no-recovery}
             (select-keys (ex-data thrown) [:rf.error/id :where :recovery]))))))
