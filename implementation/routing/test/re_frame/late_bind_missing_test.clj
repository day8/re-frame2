(ns re-frame.late-bind-missing-test
  "Routing wrappers fail loudly when their optional artefact hook is absent (Spec 002)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading routing registers the late-bind hooks this file nils.
            [re-frame.routing]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest reg-route-raises-when-routing-artefact-missing
  (testing "rf/reg-route (macro) raises :rf.error/routing-artefact-missing when the :routing/reg-route hook is nil"
    (with-hook-as-nil :routing/reg-route
      (fn []
        (let [data (try (rf/reg-route :route/probe {} "/probe")
                        nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= {:rf.error/id :rf.error/routing-artefact-missing
                  :where       'rf/reg-route
                  :route-id    :route/probe
                  :recovery    :no-recovery}
                 (select-keys data [:rf.error/id :where :route-id :recovery]))))))))

(deftest route-link-raises-when-routing-artefact-missing
  (testing "rf/route-link raises :rf.error/routing-artefact-missing when the :routing/route-link hook is nil"
    (with-hook-as-nil :routing/route-link
      (fn []
        (let [data (try (rf/route-link {:to :route/probe})
                        nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= {:rf.error/id :rf.error/routing-artefact-missing
                  :where       'rf/route-link
                  :recovery    :no-recovery}
                 (select-keys data [:rf.error/id :where :recovery]))))))))
