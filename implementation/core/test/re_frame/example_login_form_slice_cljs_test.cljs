(ns re-frame.example-login-form-slice-cljs-test
  "The login feature's form slice and password privacy, driven through
   `login.model`'s own events (docs/core/how-to/keep-secrets-out-of-traces.md).
   The password crosses three boundaries, each with its own owner: the app-db
   draft (classified `:sensitive` at slice-init), the `:auth.login/edit-password`
   event (`:sensitive [[:value]]`), and the managed-HTTP request that
   `:auth.login/submit-form` issues itself with `:sensitive? true`, handing the
   machine only a credential-free signal."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.privacy :as rf.privacy]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.schemas]
            [re-frame.machines]
            [login.model])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- slice [f]
  (get-in (rf/app-db-value f) [:auth :login-form]))

(defn- seed-draft!
  "Boot the slice, then type `email` and `password` through the real edit events."
  [f email password]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email email] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-password {:value password}] {:frame f}))

(defn- record-traces!
  [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    a))

(defn- submit-capturing!
  "Dispatch `:auth.login/submit-form`, capturing the machine `:dispatch` signal
   and the `:rf.http/managed` request instead of running either."
  [f dispatched http]
  (rf/dispatch-sync [:auth.login/submit-form]
                    {:frame        f
                     :fx-overrides {:dispatch        (fn [_ ev]  (swap! dispatched conj ev))
                                    :rf.http/managed (fn [_ req] (swap! http conj req))}}))

(deftest clean-submit-issues-sensitive-request-and-credential-free-machine-signal
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (seed-draft! f "alice@example.com" "hunter2pw")
    (let [dispatched (atom [])
          http       (atom [])]
      (submit-capturing! f dispatched http)
      (is (= {:draft             {:email "alice@example.com" :password ""}
              :submit-attempted? true
              :errors            {}
              :touched           #{:email :password}}
             (slice f))
          "only the password is blanked; errors cleared; no status mirror beside the machine")
      (is (= [[:auth.login/flow [:auth.login/submit]]] @dispatched)
          "exactly one machine signal, carrying no password")
      (is (= [{:body       {:email "alice@example.com" :password "hunter2pw"}
               :sensitive? true}]
             (mapv #(select-keys (:request %) [:body :sensitive?]) @http))
          "exactly one login request, carrying the real password and marked :sensitive?"))))

(deftest owner1-password-redacted-in-app-db-egress
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (seed-draft! f "alice@example.com" "hunter2pw")
    (is (= {:email "alice@example.com" :password rf.privacy/redacted-sentinel}
           (get-in (rf/project-egress (rf/app-db-value f)
                                      {:frame f :rf.egress/profile :rf.egress/off-box-tool})
                   [:auth :login-form :draft]))
        "the draft password reads :rf/redacted at egress; the email rides through")))

(deftest owner2-edit-password-event-redacted-in-trace
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
    (let [traces (record-traces! ::edit-probe)]
      (rf/dispatch-sync [:auth.login/edit-field :email "alice@example.com"] {:frame f})
      (rf/dispatch-sync [:auth.login/edit-password {:value "hunter2pw"}] {:frame f})
      (rf/unregister-listener! :trace ::edit-probe)
      (let [pw-payloads (->> @traces
                             (keep (fn [ev]
                                     (when (= :auth.login/edit-password
                                              (first (get-in ev [:tags :rf.event/v])))
                                       (get-in ev [:tags :rf.event/v 1]))))
                             (filter map?))]
        (is (seq pw-payloads)
            "the edit-password event surfaced on the trace with its payload map")
        (doseq [p pw-payloads]
          (is (= rf.privacy/redacted-sentinel (:value p))
              "the edit-password :value is :rf/redacted in the event trace")))
      (let [email-vals (->> @traces
                            (keep (fn [ev]
                                    (when (= :auth.login/edit-field
                                             (first (get-in ev [:tags :rf.event/v])))
                                      (get-in ev [:tags :rf.event/v 2])))))]
        (is (some #{"alice@example.com"} email-vals)
            "the non-secret email rides visibly in the edit-field trace")))))

;; The capture-based submit test above bypasses the trace pipeline. Here only
;; the transport is neutralised: the `:rf.event/fx` aggregate and the managed
;; fx's `:rf.fx/args` slot both redact through `:rf.http/managed`'s per-call
;; `:sensitive?` flag.

(def ^:private submit-pw-sentinel "PW-LOGIN-SENTINEL-32ffq1-c4d9")

(defn- leaks-submit-pw? [x] (str/includes? (pr-str x) submit-pw-sentinel))

(deftest clean-submit-trace-stream-never-leaks-password
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (seed-draft! f "alice@example.com" submit-pw-sentinel)
    (let [traces (record-traces! ::submit-sweep)]
      (rf/dispatch-sync [:auth.login/submit-form]
                        {:frame        f
                         :fx-overrides {:rf.http/managed (fn [_ _] nil)}})
      (rf/unregister-listener! :trace ::submit-sweep)
      (let [entries (for [ev    @traces
                          :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                          :when (vector? fx-vec)
                          [id args] fx-vec
                          :when (= :rf.http/managed id)]
                      args)]
        (is (seq entries) "the do-fx aggregate carried the managed entry")
        (doseq [args entries]
          (is (= rf.privacy/redacted-sentinel (get-in args [:request :body]))
              "the request's whole :body reads :rf/redacted in :rf.event/fx")))
      (let [handled (->> @traces
                         (filter #(= :rf.http/managed
                                     (get-in % [:tags :rf.fx/id]))))]
        (is (seq handled) "the managed fx emitted a :rf.fx/handled trace")
        (doseq [ev handled]
          (is (= rf.privacy/redacted-sentinel
                 (get-in ev [:tags :rf.fx/args :request :body]))
              "the managed :rf.fx/args request body reads :rf/redacted")))
      (is (= [] (mapv :operation (filter #(leaks-submit-pw? (:tags %)) @traces)))
          "the password appears raw in no trace tag"))))

(deftest invalid-submit-issues-nothing-and-retains-password
  (with-new-frame [f (rf.frame/make-anon-frame-record! {})]
    (seed-draft! f "not-an-email" "short")
    (let [dispatched (atom [])
          http       (atom [])]
      (submit-capturing! f dispatched http)
      (let [s (slice f)]
        (is (= #{:email :password} (set (keys (:errors s))))
            "each invalid field produced a field error")
        (is (true? (:submit-attempted? s))
            ":submit-attempted? latches on the invalid branch too")
        (is (= "short" (get-in s [:draft :password]))
            "the password is retained for the fix-up; it never left the box")
        (is (= [[] []] [@dispatched @http])
            "no machine signal and no HTTP request")))))
