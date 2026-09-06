(ns re-frame.login-cljs-test
  "Regression: the login example's `:auth.login/flow` machine attaches a
   `[:schemas :data]` schema (`login.model/AuthLoginData`) that validates the
   snapshot's `:data` slot at the `:where :machine-data` boundary
   (rf2-t5ky67 issue 2 / Spec 005 §Schema validation).

   The fixture fns live HERE (the adapter test tree), not under
   examples/core/login/ — the example source stays test-free per the
   locked test-free-examples policy (rf2-8cevm). The ns requires the login
   feature's substrate-free model owner (`login.model`, the ONE owner of the
   `auth.login` registrations shared across the Reagent/UIx login
   examples — rf2-ppbvav) so its machine / events / schemas register at ns-load,
   then exercises the MACHINE directly.

   Post rf2-3fc89f.33 the machine is credential-free: `submit-form` owns the
   (sensitive) HTTP request, and the machine receives only signals —
   a bare `[:auth.login/flow [:auth.login/submit]]` to enter `:submitting`, and
   the framework-shaped reply as `[:auth.login/flow [:auth.login/failure] {…}]`
   on the way back. So these tests drive those signals directly (no HTTP round
   trip) — which is exactly the machine surface this ns pins.

   Coverage:
     - data-schema-attached         — `(machine-meta :auth.login/flow)`
       carries `AuthLoginData`; the schema rejects malformed `:data`.
     - malformed-data-fails-boundary — driving the machine's `:failure` with a
       non-string message makes `:record-error` write a NON-string into `:error`
       (the schema requires `[:maybe :string]`), emitting exactly one
       `:rf.error/schema-validation-failure :where :machine-data` trace and
       rolling the snapshot back (the bad `:data` never sticks).
     - well-formed-data-passes      — a normal failing login (string
       message) settles without any `:where :machine-data` trace.
     - retry-clears-prior-error     — a direct retry from `:error-shown` clears
       the stale `:error` as the machine re-enters `:submitting`."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; login.model
            ;; pulls it transitively, require here so the ns is self-sufficient.
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.malli]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

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
  "Run `thunk` while collecting `:rf.error/schema-validation-failure` traces with
   `:where :machine-data`. Returns the captured (filtered) trace vector."
  [thunk]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
    (try (thunk)
         (finally (rf/unregister-listener! :trace ::collect)))
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-data (-> % :tags :where)))
             @traces)))

(defn- failure-reply
  "The framework-shaped managed-HTTP failure envelope the machine's
   `:record-error` action reads: `{:status :error :error {… :message …}}`, with
   `:message` riding under `:error`. The action's `:event` sees it as the second
   element of the `:failure` sub-event — the same view a real reply-appended
   `[:auth.login/flow [:auth.login/failure] <this>]` produces."
  [message]
  {:status :error
   :error  {:kind :rf.http/http-4xx :status 401 :message message}})

(defn- submit! [f]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit]] {:frame f}))

(defn- fail! [f message]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/failure (failure-reply message)]]
                    {:frame f}))

;; ---------------------------------------------------------------------------
;; (1) attachment + schema rejects malformed :data
;; ---------------------------------------------------------------------------

(deftest data-schema-attached
  (testing "the login machine carries AuthLoginData on its [:schemas :data] slot"
    (let [meta (rf.machines/machine-meta :auth.login/flow)]
      (is (some? meta) "machine-meta resolves the registered login machine")
      (is (= login.model/AuthLoginData (get-in meta [:schemas :data]))
          "the [:schemas :data] schema round-trips as login.model/AuthLoginData")))
  (testing "AuthLoginData validates the :data slot only (rejects a non-string :error)"
    (is (true?  (rf.schemas/validate-with-registered-fn login.model/AuthLoginData {:attempts 0 :error nil})))
    (is (true?  (rf.schemas/validate-with-registered-fn login.model/AuthLoginData {:attempts 1 :error "Login failed."})))
    (is (false? (rf.schemas/validate-with-registered-fn login.model/AuthLoginData {:attempts 0 :error {:not "a string"}}))
        "a non-string :error fails the data-slot schema")
    (is (false? (rf.schemas/validate-with-registered-fn login.model/AuthLoginData {:attempts "x" :error nil}))
        "a non-int :attempts fails the data-slot schema")))

;; ---------------------------------------------------------------------------
;; (2) malformed machine :data fails at :where :machine-data + rolls back
;; ---------------------------------------------------------------------------

(deftest malformed-data-fails-boundary
  (testing "a failure whose message is non-string drives :record-error to write a bad :error, failing the :machine-data boundary and rolling back"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; A bare :submit signal drives :idle → :submitting; then a :failure whose
      ;; message is a non-string map makes :record-error write that map into
      ;; :error — which AuthLoginData's [:maybe :string] rejects.
      (let [traces   (collect-machine-data-traces!
                       (fn []
                         (submit! f)
                         (fail! f {:not "a string"})))
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
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (let [traces (collect-machine-data-traces!
                     (fn []
                       (submit! f)
                       (fail! f "Invalid credentials.")))]
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
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; Drive idle → submitting → (string failure) → error-shown.
      (submit! f)
      (fail! f "Invalid credentials.")
      (is (= :error-shown (machine-state f))
          "the failed login settled in :error-shown")
      (is (= "Invalid credentials." (:error (machine-data f)))
          "the prior failure message is visible in :error-shown")
      ;; Resubmit directly from :error-shown; with no reply issued the machine
      ;; parks in :submitting and the :error slot is observable mid-flight.
      (submit! f)
      (is (= :submitting (machine-state f))
          "the retry re-entered :submitting (no reply issued)")
      (is (nil? (:error (machine-data f)))
          "the :clear-error action cleared the stale error on the retry transition"))))
