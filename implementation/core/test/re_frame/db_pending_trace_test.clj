(ns re-frame.db-pending-trace-test
  "Per rf2-ta0y7 (Mike 2026-05-25): the framework stamps the full
  pending-`:db` value at two endpoints on the per-event trace stream so
  the Xray Handler panel can render the t1 returned-effects sub-block
  AND the t1→t2 flow reshape without a precomputed diff. This file
  pins the post-handler-chain (t1) emit against the core artefact —
  the flows-driven t2 emit is covered by `re-frame.flows-t2-trace-test`
  in the flows artefact (where the flows hook is wired in).

  Contract — Spec 009 §Canonical per-event trace sequence:

    `:rf.event/db-pending` (t1) fires at the post-handler-chain / pre-
    flow-transform position whenever the handler returned a `:db`
    slot. Stamped value = the full pending `:db` reference (persistent
    data — pointer-sized cost; no copy). Emitted in the canonical
    sequence BETWEEN the handler chain's `:after` walk and the first
    `:rf.flow/computed` emit (or, when flows are absent, before
    `:rf.event/db-changed`).

    `:rf.event/db-pending-post-flow` (t2) fires only when flows
    transformed the pending value; see `flows_t2_trace_test.clj`.

  Same-shape-as-`:fx` posture: the `:db` value rides under `:tags
  :rf.event/db`, alongside `:frame` — the same slot placement the
  `:rf.event/fx` tag uses on `:rf.fx/do-fx`. Mike's ruling (rf2-ta0y7
  notes) supersedes the earlier diff / DEBUG-gate options."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener that accumulates events; return the atom.
  Tests detach with `(rf/unregister-listener! :trace id)`."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- t1 fires when the handler returns `:db` -----------------------------

(deftest t1-emits-when-handler-returns-db
  (testing ":rf.event/db-pending fires once per dispatch when the handler
   returned a :db slot, carrying the returned value under :tags :rf.event/db"
    (rf/reg-event :t1/seed-db
      (fn [{:keys [db]} _] {:db {:counter 42 :seeded? true}}))
    (let [acc (collect-traces! ::t1-db-only)]
      (try
        (rf/dispatch-sync [:t1/seed-db])
        (let [pendings (filterv #(= :rf.event/db-pending (:operation %)) @acc)]
          (is (= 1 (count pendings))
              "exactly one :rf.event/db-pending emit for the dispatch")
          (let [[p] pendings]
            (is (= :rf.event (:op-type p))
                ":op-type rides the :rf.event family")
            (is (= {:counter 42 :seeded? true} (-> p :tags :rf.event/db))
                ":tags :rf.event/db carries the handler-returned value")
            (is (= :rf/default (-> p :tags :frame))
                ":tags :frame is canonical (per Spec 009 §canonical per-frame routing key)")))
        (finally
          (rf/unregister-listener! :trace ::t1-db-only))))))

(deftest t1-suppressed-when-handler-returns-no-db
  (testing "an :fx-only handler return (no :db slot) does NOT emit
   :rf.event/db-pending — mirrors how :rf.event/db-present? rides on
   :rf.fx/do-fx as `false` when no :db was returned"
    (rf/reg-fx :t1/noop (fn [_ _] :ok))
    (rf/reg-event :t1/fx-only
      (fn [_ _] {:fx [[:t1/noop {}]]}))
    (let [acc (collect-traces! ::t1-fx-only)]
      (try
        (rf/dispatch-sync [:t1/fx-only])
        (let [pendings (filterv #(= :rf.event/db-pending (:operation %)) @acc)]
          (is (zero? (count pendings))
              "no :rf.event/db-pending when the handler returned no :db slot"))
        (finally
          (rf/unregister-listener! :trace ::t1-fx-only))))))

(deftest t1-value-is-identical-by-reference-no-copy
  (testing "Mike's ruling: the t1 stamp is the SAME persistent reference
   the handler returned — no `into`, no walk, no copy. Persistent data
   structures + structural sharing make the cost pointer-sized; the
   `day8/de-dupe` wire layer collapses repeated subtrees at egress."
    (let [shared-payload {:big (vec (range 1000))
                          :nested {:k :v}}
          captured       (atom nil)]
      (rf/reg-event :t1/return-shared
        (fn [{:keys [db]} _] {:db shared-payload}))
      (rf/register-listener! :trace
        ::t1-identity
        (fn [ev]
          (when (= :rf.event/db-pending (:operation ev))
            (reset! captured (-> ev :tags :rf.event/db)))))
      (try
        (rf/dispatch-sync [:t1/return-shared])
        (is (identical? shared-payload @captured)
            "t1's stamped value is the same persistent reference the handler returned")
        (finally
          (rf/unregister-listener! :trace ::t1-identity))))))

;; ---- ordering against the canonical sequence -----------------------------

(deftest t1-precedes-db-changed-and-do-fx
  (testing "Spec 009 §Canonical per-event trace sequence — :rf.event/db-pending
   sits AFTER :rf.event/run-start and BEFORE :rf.event/db-changed (and BEFORE
   :rf.fx/do-fx). The atomicity-contract pipeline is reflected in the trace
   stream order: handler returns :db -> [t1] -> flows -> commit -> fx -> tail"
    (rf/reg-fx :t1/tail-fx (fn [_ _] :ok))
    (rf/reg-event :t1/order-probe
      (fn [_ _] {:db {:tick 1}
                 :fx [[:t1/tail-fx {}]]}))
    (let [acc (collect-traces! ::t1-order)]
      (try
        (rf/dispatch-sync [:t1/order-probe])
        (let [ops (mapv :operation @acc)
              idx (fn [op] (first (keep-indexed (fn [i x] (when (= x op) i)) ops)))]
          (is (some? (idx :rf.event/run-start))   ":rf.event/run-start was emitted")
          (is (some? (idx :rf.event/db-pending))  ":rf.event/db-pending was emitted")
          (is (some? (idx :rf.event/db-changed))  ":rf.event/db-changed was emitted")
          (is (some? (idx :rf.fx/do-fx))          ":rf.fx/do-fx was emitted")
          (is (some? (idx :rf.event/run-end))     ":rf.event/run-end was emitted")
          (is (< (idx :rf.event/run-start) (idx :rf.event/db-pending))
              "run-start precedes db-pending")
          (is (< (idx :rf.event/db-pending) (idx :rf.event/db-changed))
              "db-pending precedes db-changed (the commit boundary)")
          (is (< (idx :rf.event/db-changed) (idx :rf.fx/do-fx))
              "db-changed precedes do-fx (Spec 002 atomicity)")
          (is (< (idx :rf.fx/do-fx) (idx :rf.event/run-end))
              "do-fx precedes run-end (the cascade trailer)"))
        (finally
          (rf/unregister-listener! :trace ::t1-order))))))

;; ---- t2 contract from the core perspective -------------------------------

(deftest t2-never-fires-without-flows-artefact
  (testing "with no flows artefact loaded, t2 (:rf.event/db-pending-post-flow)
   is by definition impossible — no flow could have transformed the pending
   value. The flows artefact is NOT a dependency of the core test fixture
   (no `(require 're-frame.flows :reload)` above), so this confirms the
   no-flows-app posture."
    (rf/reg-event :t2/seed (fn [{:keys [db]} _] {:db {:x 1}}))
    (let [acc (collect-traces! ::t2-no-flows)]
      (try
        (rf/dispatch-sync [:t2/seed])
        (let [t2s (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @acc)]
          (is (zero? (count t2s))
              "no :rf.event/db-pending-post-flow without the flows artefact"))
        (finally
          (rf/unregister-listener! :trace ::t2-no-flows))))))

;; ---- payload-shape pin ---------------------------------------------------

(deftest db-pending-payload-rides-under-tags
  (testing "payload-shaped slots (:rf.event/db, :frame) ride under :tags
   — top level is reserved for substrate-hoisted slots
   (:rf.trace/call-site, :rf.trace/trigger-handler, :source, etc.).
   Mirrors the rf2-twt7m Change 2 placement of :rf.event/fx on :rf.fx/do-fx."
    (rf/reg-event :t1/payload-shape
      (fn [{:keys [db]} _] {:db {:x 9}}))
    (let [acc (collect-traces! ::payload-shape)]
      (try
        (rf/dispatch-sync [:t1/payload-shape])
        (let [[p] (filterv #(= :rf.event/db-pending (:operation %)) @acc)]
          (is (some? p) ":rf.event/db-pending fired")
          (is (contains? (:tags p) :rf.event/db)
              ":rf.event/db lives under :tags")
          (is (contains? (:tags p) :frame)
              ":frame lives under :tags (Spec 009 canonical routing key)")
          (is (not (contains? p :rf.event/db))
              ":rf.event/db is NOT at top level"))
        (finally
          (rf/unregister-listener! :trace ::payload-shape))))))
