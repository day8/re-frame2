(ns re-frame.security.fail-closed-invariant-security-cljs-test
  "Adversarial tests for untrusted-input boundaries that must fail closed.

  A malformed schema rejects rather than validates, at the boundary seam and
  at the dev-time validation surfaces. The hydration guard that rejects a
  malformed payload must not over-reject: a server slice installs, and the
  documented client-only payload (a map with no `:rf/app-db` slice) still
  hydrates."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and a malformed schema
            ;; never throws (so there is nothing to fail-closed against).
            [re-frame.schemas.malli]
            [re-frame.schemas :as rf.schemas]
            [re-frame.ssr.hydrate :as rf.ssr.hydrate]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])))

;; App schemas are frame-local. Bind a scope for registration without creating
;; an adapter-backed frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture)
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

(defn- verdict-and-malformed-emit
  "Run the validation thunk `f`; return `[verdict malformed-schema-emitted?]`."
  [f]
  (with-trace-recorder! [traces]
    (let [verdict (f)]
      [verdict (boolean (some #(= :rf.error/malformed-schema (:operation %)) @traces))])))

(deftest malformed-schema-never-passes-validation
  ;; A childless op registers but makes Malli throw when first validated. A
  ;; pass would run a handler on, or commit, an unvalidated value.
  (let [schema [:vector]]
    (is (false? (rf.schemas/validate-with-registered-fn schema [:anything]))
        "boundary seam: the validator throw becomes a reject, never a pass")
    (is (= [false true]
           (verdict-and-malformed-emit
             #(rf.schemas/validate-event! :ev/x [:ev/x 1] {:schema schema})))
        "event / fx / sub surfaces")
    (rf/reg-app-schema [:root] schema)
    (is (= [false true]
           (verdict-and-malformed-emit
             #(rf.schemas/validate-app-schema! {:root {:anything 1}} :root/bad)))
        "app-db surface (false rolls the event back)")))

(deftest wellformed-hydration-payload-still-installs
  ;; A server slice replaces app-db; a map with no slice keeps the client's
  ;; app-db and stashes the version in runtime-db.
  (let [existing-db {:client/seeded true :count 0}
        hydrate     #(rf.ssr.hydrate/hydrate-event-handler
                       {:db existing-db :rf.frame/id :rf/default}
                       [:rf/hydrate %])]
    (is (= {:db {:count 7 :title "seeded"} :fx []}
           (hydrate {:rf/app-db {:count 7 :title "seeded"}})))
    (is (= {:db existing-db :fx [] :rf.db/runtime {:rf.runtime/ssr {:hydration {:version 1}}}}
           (hydrate {:rf/version 1})))))
