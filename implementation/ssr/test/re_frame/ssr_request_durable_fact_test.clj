(ns re-frame.ssr-request-durable-fact-test
  "rf2-aqwvhh — durable request-derived facts use the RECORDABLE boundary
  pattern, NOT the ambient `:rf.server/request` read.

  EP-0017 §1 + Spec 011 §Durable request-derived facts: `:rf.server/request`
  is an AMBIENT, host-transient read — its supplier re-runs on replay and never
  re-presents the value a recorded run saw (and reads nil after per-request
  frame teardown). So a setup handler that folds a request-derived fact into
  DURABLE app-db (auth user / session state that ships in the hydration
  payload) must NOT read it through the ambient cofx; it takes the fact as a
  RECORDABLE leaf so the causal token carries it and replay re-presents it
  verbatim. Two slice-A-legal shapes:

    1. EVENT PAYLOAD — the host dispatches the setup event WITH the sanitized
       derived fact; it rides `:event`, recorded as part of the dispatch.
    2. PROVIDED recordable `:rf.cofx` LEAF — an app-owned
       `{:recordable? true :provided? true}` cofx the host stamps onto the boot
       token; a record missing it fails LOUDLY with
       `:rf.error/missing-required-cofx` rather than silently re-reading the
       host (the strict-replay contract).

  This pins both shapes producing the durable app-db slice, the fail-loud on a
  missing provided fact, AND the contrast that the ambient `:rf.server/request`
  value is NOT recorded on the token (the symptom the pattern avoids)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- shape 1: event payload (the sanitized fact rides :event) -------------

(deftest durable-app-db-from-request-fact-via-event-payload
  (testing "a setup handler writes durable app-db from a request-derived fact
            supplied as EVENT PAYLOAD — the fact rides :event (recorded), the
            handler never reads the ambient request cofx"
    (let [server-frame (frame/make-anon-frame-record! {:platform :server})]
      ;; The handler reads the SANITIZED session off its event arg, NOT off the
      ;; ambient :rf.server/request cofx — so the durable write folds a recorded
      ;; fact (the event), replay-stable.
      (rf/reg-event :auth/server-init
        {:platforms #{:server}}
        (fn [{:keys [db]} [_ {:keys [user authed?]}]]
          {:db (assoc db :auth/user user
                         :auth/state (if authed? :authed :idle))}))
      ;; Host adapter sanitizes the request and dispatches WITH the fact.
      (rf/dispatch-sync [:auth/server-init {:user "alice" :authed? true}]
                        {:frame server-frame})
      (let [db (frame/frame-app-db-value server-frame)]
        (is (= "alice" (:auth/user db))
            "durable app-db carries the request-derived user from event payload")
        (is (= :authed (:auth/state db)))))))

;; ---- shape 2: provided recordable :rf.cofx leaf ---------------------------

(deftest durable-app-db-from-request-fact-via-provided-cofx
  (testing "a setup handler writes durable app-db from a PROVIDED recordable
            :rf.cofx leaf the host stamped onto the token after sanitizing the
            request — the value is recorded + replay-stable, delivered flat"
    (let [server-frame (frame/make-anon-frame-record! {:platform :server})]
      (rf/reg-cofx :auth.session/user
        {:recordable? true :provided? true
         :doc "Sanitized session user, stamped by the SSR host adapter."})
      (rf/reg-event :auth/server-init-cofx
        {:platforms        #{:server}
         :rf.cofx/requires [:auth.session/user]}
        (fn [{:keys [db auth.session/user]} _]
          {:db (assoc db :auth/user user
                         :auth/state (if user :authed :idle))}))
      ;; Host adapter stamps the sanitized derived fact onto the boot token.
      (rf/dispatch-sync [:auth/server-init-cofx]
                        {:frame   server-frame
                         :rf.cofx {:auth.session/user "bob"}})
      (let [db (frame/frame-app-db-value server-frame)]
        (is (= "bob" (:auth/user db))
            "durable app-db carries the request-derived user from the recorded :rf.cofx leaf")
        (is (= :authed (:auth/state db)))))))

(deftest missing-provided-request-fact-fails-loud
  (testing "a PROVIDED recordable request fact absent from the token fails
            LOUDLY with :rf.error/missing-required-cofx — NOT a silent
            fall-back to get-request / nil (the strict-replay contract)"
    (let [server-frame (frame/make-anon-frame-record! {:platform :server})
          traces       (collect-traces! ::missing)]
      (rf/reg-cofx :auth.session/user
        {:recordable? true :provided? true
         :doc "Sanitized session user, stamped by the SSR host adapter."})
      (rf/reg-event :auth/server-init-strict
        {:platforms        #{:server}
         :rf.cofx/requires [:auth.session/user]}
        (fn [{:keys [db auth.session/user]} _]
          {:db (assoc db :auth/user user)}))
      ;; Host did NOT stamp :auth.session/user — the provided fact is absent.
      (let [ex (try (rf/dispatch-sync [:auth/server-init-strict] {:frame server-frame})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (rf/unregister-listener! :trace ::missing)
        (is (some? ex) "dispatch threw rather than silently delivering nil")
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
            "the throw is :rf.error/missing-required-cofx (fail-closed)")
        (is (seq (filter #(= :rf.error/missing-required-cofx (:operation %)) @traces))
            "a :rf.error/missing-required-cofx error trace was emitted")
        ;; And no durable write landed.
        (is (nil? (:auth/user (frame/frame-app-db-value server-frame)))
            "no durable app-db slice was written from a missing provided fact")))))

;; ---- contrast: the ambient :rf.server/request value is NOT recorded -------

(deftest ambient-request-read-is-not-recorded-on-the-token
  (testing "the ambient :rf.server/request value does NOT appear on the causal
            token's :rf.cofx record — proving it is unrecorded (replay re-runs
            the supplier), which is exactly why a DURABLE write must not fold it
            (it would diverge on replay / read nil after teardown)"
    (let [server-frame (frame/make-anon-frame-record! {:platform :server})
          seen-cofx    (atom ::unset)]
      (ssr/set-request! server-frame {:request-method :get :uri "/x"
                                      :headers {"cookie" "session=raw-secret"}})
      ;; A handler that READS the ambient request (a legal NON-durable use:
      ;; here just to capture the recorded :rf.cofx for the assertion).
      (rf/reg-event :req/inspect-record
        {:platforms        #{:server}
         :rf.cofx/requires [:rf.server/request]}
        (fn [{:as ctx :keys [rf.server/request]} _]
          (reset! seen-cofx (:rf.cofx ctx))
          {}))
      (rf/dispatch-sync [:req/inspect-record] {:frame server-frame})
      (is (not (contains? (or @seen-cofx {}) :rf.server/request))
          "the ambient request value is NOT on the token's :rf.cofx record")
      (is (not (.contains (pr-str @seen-cofx) "raw-secret"))
          "the raw request/cookie never rides the causal token"))))
