(ns re-frame.machine-cofx-sub-source-test
  "Sub-valued recordable coeffect source `{:rf/sub query-v :as fact-id}`
  (EP-0017 Option A rider, rf2-h6ggnt) — machines-only.

  It is a RECORDED TOKEN FACT, not permission for a callback to read the sub
  cache: a named guard/action declares the source in its `:rf.cofx/requires`;
  the query is evaluated ONCE against the committed pre-cascade frame-state and
  recorded on the causal token under the mandatory owner-qualified `:as`
  fact-id; strict replay re-presents it verbatim.

  Covers (each hitting the ACTUAL registration / dispatch path, per the
  project's acceptance discipline — not a routed-around green):

    1. PARSE — the machines `allow-sub?` arity accepts the map form and parses
       it to `{:id fact-id :arg ::no-arg :rf.cofx/sub query-v}` (dedup /
       delivery key on the owner-qualified fact-id, so two distinct `:rf/sub`
       sources do NOT collide); the event-handler arity REJECTS it.
    2. NEGATIVE / adversarial — a malformed source shape is rejected at
       registration with `:rf.error/cofx-request-invalid`: a missing `:as`,
       a non-vector `:rf/sub` query, a non-owner-qualified `:as`, an extra key.
    3. END-TO-END — a named action declaring the source reads the RESOLVED sub
       value off its `:rf.cofx` record (the query evaluated against the
       committed pre-cascade frame-state), recorded under fact-id.
    4. REPLAY — a fact-id PRESENT on the token is re-presented VERBATIM (the sub
       is NOT re-evaluated); ABSENT under `:strict` is
       `:rf.error/missing-required-cofx` (loud, never re-derived)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.machines :as machines]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A fixed wall-clock sentinel the host clock never spontaneously returns.
(def ^:private SCRIPTED-TIME-MS 1234500000)

;; ===========================================================================
;; 1. Parse — machines accept the map form; events reject it
;; ===========================================================================

(deftest machine-arity-parses-sub-source
  (testing "the machines `allow-sub?` arity parses `{:rf/sub qv :as fact-id}`
            into `{:id fact-id :arg ::no-arg :rf.cofx/sub qv}` — the :as
            fact-id is the dedup / delivery key, NOT the bare :rf/sub id"
    (let [parsed (cofx/parse-requires
                   [:actions :capture]
                   [{:rf/sub [:editor/can-submit?] :as :editor/can-submit}]
                   true)
          entry  (first parsed)]
      (is (= 1 (count parsed)))
      (is (= :editor/can-submit (:id entry))
          "the :as fact-id is the parsed entry's :id (dedup + delivery key)")
      (is (= [:editor/can-submit?] (:rf.cofx/sub entry))
          "the query rides under :rf.cofx/sub for the delivery step")))
  (testing "two DISTINCT sub queries under distinct fact-ids do NOT collide
            (the reason the map form is mandatory over `[id arg]`)"
    (is (= 2 (count (cofx/parse-requires
                      [:actions :capture]
                      [{:rf/sub [:a/x] :as :app/x}
                       {:rf/sub [:a/y] :as :app/y}]
                      true))))))

(deftest event-arity-rejects-sub-source
  (testing "a reg-event handler (the 2-arity / `allow-sub?` false) declaring a
            `{:rf/sub …}` source is `:rf.error/cofx-request-invalid` — its
            handler already holds :db"
    (let [e (is (thrown? ExceptionInfo
                  (cofx/parse-requires :some/evt
                                       [{:rf/sub [:a/x] :as :app/x}])))]
      (is (= :rf.error/cofx-request-invalid (:rf.error/id (ex-data e)))))))

;; ===========================================================================
;; 2. Negative / adversarial — malformed source rejected at registration
;; ===========================================================================

(defn- reg-rejected-id
  "Register a machine whose :capture action declares `requires`, and return the
  `:rf.error/id` of the registration throw (or nil if it did not throw)."
  [requires]
  (let [m {:initial :idle
           :data    {}
           :actions {:capture {:rf.cofx/requires requires
                               :fn (fn [_] nil)}}
           :states  {:idle {:on {:go {:target :done :action :capture}}}
                     :done {}}}]
    (-> (try (machines/make-machine-handler m) nil
             (catch ExceptionInfo e e))
        ex-data
        :rf.error/id)))

(deftest sub-source-missing-as-rejected
  (testing "a `{:rf/sub …}` source with NO `:as` fact-id fails registration —
            `:as` is MANDATORY (it is the recorded fact's id + dedup key)"
    (is (= :rf.error/cofx-request-invalid
           (reg-rejected-id [{:rf/sub [:a/x]}])))))

(deftest sub-source-non-vector-query-rejected
  (testing "a `{:rf/sub …}` source whose `:rf/sub` is not a query VECTOR fails"
    (is (= :rf.error/cofx-request-invalid
           (reg-rejected-id [{:rf/sub :not-a-vector :as :app/x}])))
    (is (= :rf.error/cofx-request-invalid
           (reg-rejected-id [{:rf/sub [] :as :app/x}]))
        "an empty query vector is rejected")))

(deftest sub-source-non-qualified-as-rejected
  (testing "a `{:rf/sub …}` source whose `:as` is not owner-qualified fails"
    (is (= :rf.error/cofx-request-invalid
           (reg-rejected-id [{:rf/sub [:a/x] :as :bare}]))
        "a bare (unqualified) `:as` keyword is rejected")))

(deftest sub-source-extra-key-rejected
  (testing "a `{:rf/sub …}` source carrying a key other than :rf/sub / :as fails
            — the source map is exactly `{:rf/sub query-v :as fact-id}`"
    (is (= :rf.error/cofx-request-invalid
           (reg-rejected-id [{:rf/sub [:a/x] :as :app/x :bogus 1}])))))

;; ===========================================================================
;; 3. End-to-end — a named action reads the RESOLVED sub value off :rf.cofx
;; ===========================================================================

(deftest action-records-resolved-sub-fact
  (testing "a named action declaring `{:rf/sub qv :as fact-id}` reads the
            RESOLVED sub value off its :rf.cofx record — the query evaluated
            against the committed pre-cascade frame-state, recorded on the
            token under fact-id (NOT an ambient in-callback sub read)"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:editor/dirty? true}}))
    (rf/reg-sub :editor/can-submit? (fn [db _] (boolean (:editor/dirty? db))))
    (rf/dispatch-sync [:seed])
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :actions {:capture
                       {:rf.cofx/requires [{:rf/sub [:editor/can-submit?]
                                            :as :editor/can-submit}]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:editor/can-submit cofx))
                              nil)}}
             :states  {:idle {:on {:go {:target :done :action :capture}}}
                       :done {}}}]
      (rf/reg-machine :sub/records m)
      ;; No :editor/can-submit on the token — the ensure step evaluates the sub
      ;; against the committed app-db ({:editor/dirty? true}) and records it.
      (rf/dispatch-sync [:sub/records [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= true @seen)
          "the action read the resolved (recorded) sub value under fact-id")
      (is (= :done (mtest/machine-state :sub/records))
          "the transition fired"))))

;; ===========================================================================
;; 4. Replay — present re-presented verbatim; strict-absent is missing-required
;; ===========================================================================

(deftest present-fact-re-presented-verbatim
  (testing "a fact-id PRESENT on the token is re-presented VERBATIM under
            `:strict` — the sub is NOT re-evaluated (replay / same-macrostep
            raise re-presentation). The recorded value DIFFERS from what the
            sub would compute, proving no re-derivation."
    (rf/reg-event :seed2 (fn [_ _] {:db {:editor/dirty? true}}))
    ;; the live sub would compute `true`; the recorded token carries `false`.
    (rf/reg-sub :editor/live? (fn [db _] (boolean (:editor/dirty? db))))
    (rf/dispatch-sync [:seed2])
    (let [seen (atom ::unset)
          m {:initial :idle
             :data    {}
             :actions {:capture
                       {:rf.cofx/requires [{:rf/sub [:editor/live?]
                                            :as :editor/live}]
                        :fn (fn [{cofx :rf.cofx}]
                              (reset! seen (:editor/live cofx))
                              nil)}}
             :states  {:idle {:on {:go {:target :done :action :capture}}}
                       :done {}}}]
      (rf/reg-machine :sub/replay m)
      (rf/dispatch-sync [:sub/replay [:go]]
                        {:rf.cofx/mint-policy :strict
                         :rf.cofx {:rf/time-ms  SCRIPTED-TIME-MS
                                   :editor/live false}})
      (is (= false @seen)
          "the recorded fact wins — the sub was NOT re-evaluated (would be true)"))))

(deftest strict-absent-is-missing-required
  (testing "white-box: a sub-valued source ABSENT from the token under `:strict`
            is `:rf.error/missing-required-cofx` from the ensure step — loud,
            never re-derived from a host sub read. Driven directly on
            `ensure-cofx` (frame-id nil, empty token, `:strict`) so the throw
            surfaces verbatim — dispatch-sync would capture it into the error
            pipeline (macrostep fails atomically, machine does not advance)"
    (let [m (cofx-attach/index-ensure-sets
              {:initial :idle
               :data    {}
               :actions {:capture
                         {:rf.cofx/requires [{:rf/sub [:editor/gone?]
                                              :as :editor/gone}]
                          :fn (fn [{cofx :rf.cofx}] (:editor/gone cofx))}}
               :states  {:idle {:on {:go {:target :done :action :capture}}}
                         :done {}}})
          e (is (thrown? ExceptionInfo
                  (cofx-attach/ensure-cofx m {:state :idle :data {}} [:go]
                                           {} nil :sub/strict :strict)))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "strict refused to re-derive the absent sub fact"))))
