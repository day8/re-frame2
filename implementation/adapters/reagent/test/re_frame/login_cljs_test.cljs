(ns re-frame.login-cljs-test
  "Regression: the login example's `:auth.login/flow` machine attaches a
   top-level `:data-schema` (`login.core/AuthLoginData`) that validates the
   snapshot's `:data` slot at the `:where :machine-data` boundary
   (rf2-t5ky67 issue 2 / Spec 010 §Machine data schema).

   The fixture fns live HERE (the adapter test tree), not under
   examples/reagent/login/ — the example source stays test-free per the
   locked test-free-examples policy (rf2-8cevm). The ns requires the
   example's production source (`login.core`) so its machine / events /
   schemas register at ns-load, then exercises them directly.

   Coverage:
     - data-schema-attached         — `(machine-meta :auth.login/flow)`
       carries `AuthLoginData`; the schema rejects malformed `:data`.
     - malformed-data-fails-boundary — driving the machine so `:record-error`
       writes a NON-string into `:error` (the schema requires
       `[:maybe :string]`) emits exactly one
       `:rf.error/schema-validation-failure :where :machine-data` trace and
       rolls the snapshot back (the bad `:data` never sticks).
     - well-formed-data-passes      — a normal failing login (string
       message) settles without any `:where :machine-data` trace."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; login.core
            ;; pulls it transitively, require here so the ns is self-sufficient.
            [re-frame.schemas :as schemas]
            [re-frame.schemas.malli]
            ;; canned-failure stub (`:rf.http/managed-canned-failure`).
            [re-frame.http.test-support]
            [login.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- machine-data [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :data]))

(defn- machine-state [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :state]))

(defn- collect-machine-data-traces!
  "Run `f` while collecting `:rf.error/schema-validation-failure` traces with
   `:where :machine-data`. Returns the captured (filtered) trace vector."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
    (try (thunk)
         (finally (rf/unregister-listener! :trace ::collect)))
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))

(def ^:private valid-creds {:email "alice@example.com" :password "hunter2pw"})

(defn- reg-sync-failure-override!
  "Register a synchronous `:rf.http/managed` override delegating to the
   framework canned-FAILURE stub with the given `:tags` (no `:after-ms`, so
   the reply resolves inside the same drain)."
  [fx-id tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind :rf.http/http-4xx :tags tags))))))

;; ---------------------------------------------------------------------------
;; (1) attachment + schema rejects malformed :data
;; ---------------------------------------------------------------------------

(deftest data-schema-attached
  (testing "the login machine carries AuthLoginData on its :data-schema slot"
    (let [meta (machines/machine-meta :auth.login/flow)]
      (is (some? meta) "machine-meta resolves the registered login machine")
      (is (= login.core/AuthLoginData (:data-schema meta))
          "the :data-schema round-trips as login.core/AuthLoginData")))
  (testing "AuthLoginData validates the :data slot only (rejects a non-string :error)"
    (is (true?  (schemas/validate-with-registered-fn login.core/AuthLoginData {:attempts 0 :error nil})))
    (is (true?  (schemas/validate-with-registered-fn login.core/AuthLoginData {:attempts 1 :error "Login failed."})))
    (is (false? (schemas/validate-with-registered-fn login.core/AuthLoginData {:attempts 0 :error {:not "a string"}}))
        "a non-string :error fails the data-slot schema")
    (is (false? (schemas/validate-with-registered-fn login.core/AuthLoginData {:attempts "x" :error nil}))
        "a non-int :attempts fails the data-slot schema")))

;; ---------------------------------------------------------------------------
;; (2) malformed machine :data fails at :where :machine-data + rolls back
;; ---------------------------------------------------------------------------

(deftest malformed-data-fails-boundary
  (testing "a failure reply whose message is non-string drives :record-error to write a bad :error, failing the :machine-data boundary and rolling back"
    (reg-sync-failure-override! :login.test/canned-bad-message
                                {:status 401 :message {:not "a string"}})
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:fx-overrides {:rf.http/managed
                                         :login.test/canned-bad-message}})]
      ;; A valid submit drives :idle → :submitting and fires :issue-request;
      ;; the synchronous canned-failure reply dispatches :auth.login/failure
      ;; whose :record-error action writes the non-string message into
      ;; :error — which AuthLoginData's [:maybe :string] rejects.
      (let [traces   (collect-machine-data-traces!
                       #(rf/dispatch-sync
                          [:auth.login/flow [:auth.login/submit valid-creds]]
                          {:frame f}))
            trace-ev (first traces)
            tag      (:tags trace-ev)]
        (is (= 1 (count traces))
            "exactly one :where :machine-data trace fired on the violating macrostep")
        (is (= :auth.login/flow (:machine-id tag))
            "the trace names the login machine")
        ;; `:recovery` rides the trace ENVELOPE, not :tags (rf2-twt7m).
        (is (= :no-recovery (:recovery trace-ev)))
        ;; Rollback: the bad :data never sticks. The snapshot's :error must
        ;; NOT be the rejected map.
        (is (not= {:not "a string"} (:error (machine-data f)))
            "the violating :data was rolled back (the bad :error did not commit)")))))

;; ---------------------------------------------------------------------------
;; (3) well-formed :data passes the boundary cleanly
;; ---------------------------------------------------------------------------

(deftest well-formed-data-passes
  (testing "a normal failing login (string message) settles with no :machine-data trace"
    (reg-sync-failure-override! :login.test/canned-ok-message
                                {:status 401 :message "Invalid credentials."})
    (with-new-frame [f (frame/make-anon-frame-record!
                         {:fx-overrides {:rf.http/managed
                                         :login.test/canned-ok-message}})]
      (let [traces (collect-machine-data-traces!
                     #(rf/dispatch-sync
                        [:auth.login/flow [:auth.login/submit valid-creds]]
                        {:frame f}))]
        (is (zero? (count traces))
            "no :where :machine-data trace for a well-formed (string) :error")
        (is (= "Invalid credentials." (:error (machine-data f)))
            "the string failure message committed into the machine's :data")
        (is (= 1 (:attempts (machine-data f)))
            "the attempt counter advanced (the transition committed)")))))

;; ---------------------------------------------------------------------------
;; (4) direct retry from :error-shown clears the prior :error (rf2-qx9b1y)
;; ---------------------------------------------------------------------------

(deftest retry-clears-prior-error
  (testing "a direct retry from :error-shown clears the stale :error as the machine re-enters :submitting"
    ;; First request: a synchronous failure lands the flow in :error-shown with
    ;; a non-nil :error (the message the view renders).
    (reg-sync-failure-override! :login.test/retry-failure
                                {:status 401 :message "Invalid credentials."})
    ;; Second request (the RETRY): a no-op managed-HTTP fx that issues NO reply,
    ;; so the machine parks in :submitting and we can observe the :error slot
    ;; while the request is still in flight.
    (rf/reg-fx :login.test/retry-noop
      {:platforms #{:client :server}}
      (fn [_frame-ctx _args] nil))
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      ;; Drive idle → submitting → error-shown (per-call override → failure).
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit valid-creds]]
                        {:frame        f
                         :fx-overrides {:rf.http/managed :login.test/retry-failure}})
      (is (= :error-shown (machine-state f))
          "the failed login settled in :error-shown")
      (is (= "Invalid credentials." (:error (machine-data f)))
          "the prior failure message is visible in :error-shown")
      ;; Resubmit directly from :error-shown with the no-op override so the
      ;; machine parks in :submitting and the :error slot is observable
      ;; mid-flight.
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit valid-creds]]
                        {:frame        f
                         :fx-overrides {:rf.http/managed :login.test/retry-noop}})
      (is (= :submitting (machine-state f))
          "the retry re-entered :submitting (no reply issued by the no-op fx)")
      (is (nil? (:error (machine-data f)))
          "the :clear-error action cleared the stale error on the retry transition"))))
