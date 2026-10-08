(ns re-frame.http-abort-config-validation-test
  "Spec 014 §`:abort-signal` (external): `:abort-signal` and `:request-id` are
  not mutually exclusive. Both attach a cancellation source to the one
  managed request; the CLJS transport forwards the external signal into the
  same framework-owned controller the `:request-id` path drives, and the
  once-only `:finalised?` CAS yields exactly one terminal outcome whichever
  source acts first. So no dispatch-site guard may reject the combination.

  `:abort-signal` is CLJS-only and ignored on the JVM, so the supersede and
  abort orderings here are the plain `:request-id` paths pinned elsewhere."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest both-abort-signal-and-request-id-accepted
  (is (nil? (try (rf.http.handlers/managed-handler {:frame :rf/default :event [:no-op]}
                                                   {:request      {:method :get :url "http://localhost/x"}
                                                    :request-id   :article/load
                                                    :abort-signal (Object.)
                                                    :reply-to     [:no-op]})
                 nil
                 (catch clojure.lang.ExceptionInfo e e)))
      "the dual-source config dispatches without throwing"))
