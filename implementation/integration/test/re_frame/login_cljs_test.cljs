(ns re-frame.login-cljs-test
  "The login example's `:auth.login/flow` machine attaches a
   `[:schemas :data]` schema (`login.model/AuthLoginData`) that validates the
   snapshot's `:data` slot at the `:where :machine-data` boundary
   (Spec 005 §Schema validation).

   The fixture fns live HERE (the adapter test tree), not under
   examples/core/login/ — the example source stays test-free per the
   test-free-examples policy. The ns requires the login
   feature's substrate-free model owner (`login.model`, the ONE owner of the
   `auth.login` registrations shared across the Reagent/UIx login
   examples) so its machine / events / schemas register at ns-load,
   then exercises the MACHINE directly.

   The machine is credential-free: `submit-form` owns the
   (sensitive) HTTP request, and the machine receives only signals —
   a bare `[:auth.login/flow [:auth.login/submit]]` to enter `:submitting`, and
   the framework-shaped reply as `[:auth.login/flow [:auth.login/failure] {…}]`
   on the way back. So these tests drive those signals directly (no HTTP round
   trip) — which is exactly the machine surface this ns pins.

   Coverage:
     - malformed-data-fails-boundary — driving the machine's `:failure` with a
       non-string message makes `:record-error` write a NON-string into `:error`
       (the schema requires `[:maybe :string]`), emitting exactly one
       `:rf.error/schema-validation-failure :where :machine-data` trace and
       rolling the snapshot back (the bad `:data` never sticks).
     - well-formed-data-passes      — a normal failing login (string
       message) settles without any `:where :machine-data` trace.
     - retry-clears-prior-error     — a direct retry from `:error-shown` clears
       the stale `:error` as the machine re-enters `:submitting`.
     - server-message-through-the-transport — a submit answered 401 by a stub
       transport, so the transport itself classifies the failure, shows the
       server's message rather than the generic fallback.
     - start-kick-starts-the-machine — the reserved `[:rf.machine/start]` kick
       the Story variants send brings the snapshot alive in `:idle`; as a
       runtime lifecycle event it never meets the machine's event `:schema`.
     - editing-a-field-drops-its-stale-error — after a refused submit,
       correcting a field clears that field's error and leaves the other's."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.integration-support :as rf.integration-support]
            [re-frame.test-support :as rf.test-support]
            ;; The schemas Malli adapter publishes the registered validator
            ;; the `:where :machine-data` boundary routes through; login.model
            ;; pulls it transitively, require here so the ns is self-sufficient.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.integration-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- machine-data [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :data]))

(defn- machine-state [f]
  (get-in (rf/frame-state-value f)
          [:rf.db/runtime :rf.runtime/machines :snapshots :auth.login/flow :state]))

(defn- failure-reply
  "The managed-HTTP failure envelope a 401 delivers, as the machine's
   `:record-error` action reads it: `{:status :error :error <failure>}`, where
   the failure is the `:rf.http/http-4xx` map the transport classifies, with the
   server's JSON error body parsed under `:body`. The action's `:event` sees it
   as the second element of the `:failure` sub-event — the same view a real
   reply-appended `[:auth.login/flow [:auth.login/failure] <this>]` produces."
  [message]
  {:status :error
   :error  {:kind        :rf.http/http-4xx
            :http-status 401
            :status-text "Unauthorized"
            :body        {:message message}}})

(defn- submit! [f]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/submit]] {:frame f}))

(defn- fail! [f message]
  (rf/dispatch-sync [:auth.login/flow [:auth.login/failure (failure-reply message)]]
                    {:frame f}))

;; ---------------------------------------------------------------------------
;; (1) malformed machine :data fails at :where :machine-data + rolls back
;; ---------------------------------------------------------------------------

(deftest malformed-data-fails-boundary
  (testing "a failure whose message is non-string drives :record-error to write a bad :error, failing the :machine-data boundary and rolling back"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; A bare :submit signal drives :idle → :submitting; then a :failure whose
      ;; message is a non-string map makes :record-error write that map into
      ;; :error — which AuthLoginData's [:maybe :string] rejects.
      (let [traces   (rf.integration-support/collect-machine-data-traces!
                       (fn []
                         (submit! f)
                         (fail! f {:not "a string"})))
            trace-ev (first traces)
            tag      (:tags trace-ev)]
        ;; `:recovery` rides the trace ENVELOPE, not :tags.
        (is (= [1 :auth.login/flow :no-recovery false]
               [(count traces) (:machine-id tag) (:recovery trace-ev)
                (= {:not "a string"} (:error (machine-data f)))])
            "exactly one :where :machine-data trace, naming the login machine, fired on the violating macrostep, and the violating :data was rolled back (the bad :error did not commit)")))))

;; ---------------------------------------------------------------------------
;; (2) well-formed :data passes the boundary cleanly
;; ---------------------------------------------------------------------------

(deftest well-formed-data-passes
  (testing "a normal failing login (string message) settles with no :machine-data trace"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (let [traces (rf.integration-support/collect-machine-data-traces!
                     (fn []
                       (submit! f)
                       (fail! f "Invalid credentials.")))]
        (is (= [0 "Invalid credentials." 1]
               [(count traces) (:error (machine-data f)) (:attempts (machine-data f))])
            "no :where :machine-data trace for a well-formed (string) :error: the message committed into the machine's :data and the attempt counter advanced")))))

;; ---------------------------------------------------------------------------
;; (3) direct retry from :error-shown clears the prior :error
;; ---------------------------------------------------------------------------

(deftest retry-clears-prior-error
  (testing "a direct retry from :error-shown clears the stale :error as the machine re-enters :submitting"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      ;; Drive idle → submitting → (string failure) → error-shown.
      (submit! f)
      (fail! f "Invalid credentials.")
      (is (= [:error-shown "Invalid credentials."] [(machine-state f) (:error (machine-data f))])
          "the failed login settled in :error-shown, its failure message visible")
      ;; Resubmit directly from :error-shown; with no reply issued the machine
      ;; parks in :submitting and the :error slot is observable mid-flight.
      (submit! f)
      (is (= [:submitting nil] [(machine-state f) (:error (machine-data f))])
          "the retry re-entered :submitting (no reply issued), its :clear-error action clearing the stale error"))))

;; ---------------------------------------------------------------------------
;; (4) the server's message survives the transport's own failure shape
;; ---------------------------------------------------------------------------

(def ^:private rejecting-backend
  "Answers every request the way the demo backend answers a wrong password.
   No delay, so the reply settles inside the submitting `dispatch-sync`."
  (rf.http.stub-transport/route-transport
    (constantly {:status 401 :status-text "Unauthorized" :body {:message "Invalid credentials."}})))

(deftest server-message-through-the-transport
  (testing "a submit the backend rejects shows the server's message, read from the failure the transport classified"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (rf.http.stub-transport/with-transport rejecting-backend
        (fn []
          (doseq [event [[:auth.login/initialise-form]
                         [:auth.login/edit-field :email "ada@example.com"]
                         [:auth.login/edit-password {:value "wrong-password"}]
                         [:auth.login/submit-form]]]
            (rf/dispatch-sync event {:frame f}))))
      (is (= [:error-shown "Invalid credentials." 1]
             [(machine-state f) (:error (machine-data f)) (:attempts (machine-data f))])
          "the 401 settled the flow in :error-shown with the body's message, not the generic fallback"))))

;; ---------------------------------------------------------------------------
;; (5) the reserved start kick starts the machine
;; ---------------------------------------------------------------------------

(deftest start-kick-starts-the-machine
  (testing "the eager [:rf.machine/start] kick skips the event schema and brings the machine alive in :idle"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (let [traces (atom [])]
        (rf/register-listener! :trace ::start-kick (fn [ev] (swap! traces conj ev)))
        (try
          (rf/dispatch-sync [:auth.login/flow [:rf.machine/start]] {:frame f})
          (finally (rf/unregister-listener! :trace ::start-kick)))
        (is (= [:idle []]
               [(machine-state f)
                (filterv #(= :rf.error/schema-validation-failure (:operation %)) @traces)])
            "the kick reached the machine, which seeded its snapshot, and no schema refused it")))))

;; ---------------------------------------------------------------------------
;; (6) an edit answers its field's error
;; ---------------------------------------------------------------------------

(deftest editing-a-field-drops-its-stale-error
  (testing "after a refused submit, correcting a field clears that field's error and leaves the other's"
    (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
      (let [field-errors (fn []
                           (let [db (rf/frame-state-value f)]
                             (mapv #(some? (rf/compute-sub [:auth.login/field-error %] db))
                                   [:email :password])))]
        (doseq [event [[:auth.login/initialise-form]
                       [:auth.login/edit-field :email "nope"]
                       [:auth.login/edit-password {:value "short"}]
                       [:auth.login/submit-form]]]
          (rf/dispatch-sync event {:frame f}))
        (is (= [true true] (field-errors))
            "the refused submit put an error under each field")
        (rf/dispatch-sync [:auth.login/edit-field :email "ada@example.com"] {:frame f})
        (is (= [false true] (field-errors))
            "correcting the email dropped its error and left the password's")
        (rf/dispatch-sync [:auth.login/edit-password {:value "long-enough"}] {:frame f})
        (is (= [false false] (field-errors))
            "correcting the password dropped its error too")))))
