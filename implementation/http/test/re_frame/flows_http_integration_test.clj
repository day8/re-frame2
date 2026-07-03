(ns re-frame.flows-http-integration-test
  "Integration coverage for the http × flows × cancellation composition
  (rf2-qm8m3, second of the two gaps the rf2-hm4gi flows delta audit
  flagged as not pinned end-to-end after rf2-vug0k's four-test cluster).

  The four sibling deftests in
  `re-frame.flows-integration-test` (under the ssr artefact's test dir)
  pin machine-macrostep × flows, schema-rollback × flows, flow-throw ×
  flows, SSR × flows, child-dispatch × flows, and routing × flows. This
  file pins the LAST composition the audit listed: http × flows ×
  cancellation.

  This namespace lives in the http artefact's test tree (not the ssr
  bundle) because http/deps.edn already pulls flows + schemas + routing
  + ssr in as test-only deps, but the ssr artefact does NOT depend on
  http — adding http to ssr/deps.edn just for this one cross-cut would
  alter the canonical ssr test classpath unnecessarily. The http
  artefact already carries the full closure (flows × http) plus the
  managed-HTTP cancellation surface, so the test sits naturally here.

  ## The atomicity contract this file pins
  (`bd remember event-pipeline-atomicity`, Mike CONFIRMED 2026-05-24)

  An event handler that drives managed-HTTP cancellation typically
  returns:

      {:db  (update db :http/in-flight dissoc :req-1)
       :fx  [[:rf.http/managed-abort :req-1]]}

  The framework's drain shape is:

      handler runs → pending :db assembled (with the dissoc applied)
        → flows transform pending :db at the outermost :after
        → SINGLE deferred install (the :db commit boundary)
        → :fx walks AFTER install (`:rf.http/managed-abort` fires here)

  The two pinned facts:

  - (cancellation happy path) A flow whose :inputs overlap the
    `[:http/in-flight]` slot re-evals over the POST-handler db (i.e.
    with the dissoc already applied) — the flow's derived value reflects
    the CLEARED in-flight slot in the SAME install as the dissoc, NOT a
    delayed eval on the next event. The `:rf.http/managed-abort` fx
    fires after install and calls the registered abort-fn.

  - (flow-throw atomicity) A flow throw on a cancellation event aborts
    the WHOLE event PRE-install: the dissoc does NOT land (the in-flight
    slot stays populated), `:rf.http/managed-abort` does NOT fire (fx is
    the post-install stage that the flow-throw path skips wholesale per
    rule a), and NO `:rf.event/db-changed` emits. This is critical: a
    regression that let the cancel-fx fire even on a flow-throw abort
    would issue a network abort against a request whose app-db state
    says it's still pending — split-brain across the substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.registry :as http-registry]
            ;; rf2-cdmle — required only to keep the test-support
            ;; canned-stub fx ids registered (mirrors http-managed-test's
            ;; require closure). This file does NOT use the stubs; the
            ;; abort path is exercised by writing directly into the
            ;; in-flight registry with a recording abort-fn (mirrors
            ;; routing_http_composed_corners_test.clj's pattern).
            [re-frame.http.test-support]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- per-test reset / trace recorder -------------------------------------

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`, and
  ;; the managed-HTTP / flow fxs now require a carried frame stamp. Register
  ;; `:rf/default` explicitly and pin it as the established scope for the body.
  (frame/ensure-default-frame!)
  ;; clear-all! wiped the ns-load-time registrations of these artefacts;
  ;; :reload re-evaluates each ns body so its registrations resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.http.managed :reload)
  (require 're-frame.http.test-support :reload)
  (http-managed/clear-all-in-flight!)
  (rf/with-frame :rf/default
    (let [captured (atom [])]
      (binding [*captured* captured]
        (trace/register-listener!
          ::flows-http-recorder
          (fn [ev] (swap! captured conj ev)))
        (try
          (test-fn)
          (finally
            (trace/unregister-listener! ::flows-http-recorder)))))))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- ops
  "All captured trace `:operation`s, in capture order."
  []
  (mapv :operation @*captured*))

(defn- by-op
  "Captured trace events whose `:operation` is `op`, in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

(defn- prime-in-flight!
  "Seed an in-flight handle the way `:rf.http/managed` does at request-
  issue time, via `seed-in-flight-for-test!` so the request-id and
  actor-id indexes stay consistent (rather than a raw `swap!` of the
  `in-flight` atom that bypasses those invariants). The provided
  `abort-fn` is the recording closure the test uses to observe whether
  `:rf.http/managed-abort` fired."
  [request-id abort-fn]
  (http-registry/seed-in-flight-for-test!
    {:request-id request-id
     :actor-id   nil
     :url        (str "/api/" (name request-id))
     :abort-fn   abort-fn}))

;; ===========================================================================
;; 1. http × flows × cancellation — flow re-evals over the post-handler db
;;    with the cleared in-flight slot in the SAME install as the cancel.
;;
;; A handler models its own in-flight bookkeeping in app-db under
;; [:http/in-flight], parallel to the framework's side-channel registry.
;; (Apps that want to drive sub-derived UI off in-flight state commonly
;; mirror it into app-db so a sub can read it.) A flow over
;; [:http/in-flight] derives a count into [:derived :pending-count].
;;
;; The cancel event:
;;   1. Removes the request's slot from [:http/in-flight] in :db.
;;   2. Emits :rf.http/managed-abort as :fx so the transport-level abort
;;      fires.
;;
;; The atomicity contract this test pins: the flow's re-eval over the
;; post-handler pending :db (with the dissoc already applied) lands the
;; cleared count in the SAME install as the dissoc — they commit
;; together. THEN the post-install :fx walks and fires the abort-fn.
;; ===========================================================================

(deftest cancellation-event-flow-reevals-over-cleared-in-flight-slot-atomically
  (testing "an event that dissocs an in-flight request from app-db AND
            emits :rf.http/managed-abort drains the flow's :after over the
            POST-handler pending :db (cleared slot) — the cleared count
            and the dissoc land in the SAME install; :fx walks AFTER, and
            the abort-fn fires"
    (let [abort-calls (atom [])
          flow-evals  (atom [])]
      ;; Prime the side-channel registry as if a real :rf.http/managed
      ;; fx had issued the request — the abort-fn here records the abort
      ;; reason the framework passes (the public managed-abort path
      ;; calls (abort-fn :user)).
      (prime-in-flight! :load-articles
                        (fn [reason] (swap! abort-calls conj reason)))

      ;; A flow over the app-db-mirrored in-flight map derives a pending
      ;; count. Logging the inputs proves which pending :db each eval saw.
      (rf/reg-flow :http/pending-count {:inputs [[:http/in-flight]] :output-path [:derived :pending-count]} (fn [in-flight]
                              (swap! flow-evals conj (set (keys in-flight)))
                              (count in-flight)))

      ;; :issue mirrors what an app handler would do alongside a real
      ;; :rf.http/managed fx — drop a marker into app-db so subs / flows
      ;; can read the in-flight state. (We've already primed the side-
      ;; channel registry above; the app-db marker is the substrate the
      ;; flow drives off.)
      (rf/reg-event :http/issue
                       (fn [{:keys [db]} [_ request-id]]
                         {:db (assoc-in db [:http/in-flight request-id] true)}))

      ;; :cancel is the event the test exercises: dissoc app-db slot AND
      ;; emit :rf.http/managed-abort. Both compose with the flow's
      ;; outermost :after over the pending :db.
      (rf/reg-event :http/cancel
                       (fn [{:keys [db]} [_ request-id]]
                         {:db (update db :http/in-flight dissoc request-id)
                          :fx [[:rf.http/managed-abort request-id]]}))

      ;; Seed: mark :load-articles as in-flight in app-db. The flow runs
      ;; once on this seed event and the count lands at 1.
      (rf/dispatch-sync [:http/issue :load-articles])
      (is (= 1 (get-in (rf/app-db-value :rf/default) [:derived :pending-count]))
          "precondition: flow ran on the seed event; pending count is 1")
      (is (= [#{:load-articles}] @flow-evals)
          "precondition: flow saw the populated in-flight slot")
      (is (empty? @abort-calls)
          "precondition: no abort fired yet")

      ;; Dispatch the cancellation. The handler's :db is the dissoced
      ;; pending value; the flow's :after transforms over THAT pending :db
      ;; (cleared slot) BEFORE the single deferred install. The :fx
      ;; (:rf.http/managed-abort) walks AFTER install.
      (reset! *captured* [])
      (reset! flow-evals [])
      (rf/dispatch-sync [:http/cancel :load-articles])

      ;; Post-install assertions: the cleared slot AND the cleared count
      ;; landed together in the SAME install.
      (is (nil? (get-in (rf/app-db-value :rf/default)
                        [:http/in-flight :load-articles]))
          "the in-flight slot was cleared by the handler's :db effect")
      (is (zero? (get-in (rf/app-db-value :rf/default)
                         [:derived :pending-count]))
          "the flow's derived count reflects the CLEARED in-flight slot
           — landed in the SAME install as the dissoc, not on the next
           event")

      ;; The flow's single eval for this dispatch saw the POST-handler
      ;; pending :db (cleared slot), not the pre-handler one.
      (is (= [#{}] @flow-evals)
          "the cancellation event's single flow eval observed the CLEARED
           in-flight map — proving the flow ran over the post-handler
           pending :db, not the pre-handler value")
      (let [computes (by-op :rf.flow/computed)]
        (is (= 1 (count computes))
            "exactly one :rf.flow/computed for the cancellation dispatch
             — no double / missed eval")
        (is (= [{}] (-> computes first :tags :input-values))
            "the :rf.flow/computed trace's :input-values carries the
             post-handler (cleared) in-flight map"))

      ;; :fx walked after install — :rf.http/managed-abort looked up the
      ;; registered abort-fn and fired it with `:user`. (The real
      ;; abort-fn closure also calls finalise-failure! to clear the
      ;; side-channel in-flight registry per rf2-plngk; we use a recording
      ;; stub here so the assertion targets only the fx-firing contract,
      ;; not the closure's downstream effects.)
      (is (= [:user] @abort-calls)
          ":rf.http/managed-abort fired exactly once with reason :user —
           the post-install :fx stage walked normally for a clean cancel"))))

;; ===========================================================================
;; 2. http × flows × cancellation — atomicity under flow-throw.
;;
;; A flow throw on the cancellation event aborts the WHOLE event
;; PRE-install (atomicity contract rule a): the handler's dissoc does
;; NOT land AND the :rf.http/managed-abort fx does NOT fire. This split-
;; brain prevention is the load-bearing claim of the contract for the
;; cancellation surface: without it, a regression could land the
;; transport-level abort while the app-db still says the request is
;; in-flight (or vice-versa).
;; ===========================================================================

(deftest flow-throw-on-cancellation-event-aborts-managed-abort-fx-too
  (testing "a flow throw on a cancellation event aborts the WHOLE event
            PRE-install — the dissoc does NOT land (app-db's in-flight
            slot stays populated), the :rf.http/managed-abort fx does
            NOT fire (post-install stage skipped per atomicity contract
            rule a), and NO :rf.event/db-changed emits"
    (let [abort-calls (atom [])]
      ;; Prime the side-channel registry — if the abort-fn were
      ;; reached we'd see :user appended to @abort-calls.
      (prime-in-flight! :load-articles
                        (fn [reason] (swap! abort-calls conj reason)))

      ;; Mirror the in-flight state into app-db. Use :issue separately
      ;; (without the throwing flow registered) so the seed is clean.
      (rf/reg-event :http/issue
                       (fn [{:keys [db]} [_ request-id]]
                         {:db (assoc-in db [:http/in-flight request-id] true)}))
      (rf/reg-event :http/cancel
                       (fn [{:keys [db]} [_ request-id]]
                         {:db (update db :http/in-flight dissoc request-id)
                          :fx [[:rf.http/managed-abort request-id]]}))

      ;; Seed BEFORE registering the throwing flow, so the seed event
      ;; settles cleanly.
      (rf/dispatch-sync [:http/issue :load-articles])
      (is (true? (get-in (rf/app-db-value :rf/default)
                         [:http/in-flight :load-articles]))
          "precondition: app-db's in-flight slot is populated for
           :load-articles before the cancel")
      (let [baseline-db (rf/app-db-value :rf/default)]

        ;; Now register a flow over [:http/in-flight] that throws on
        ;; every eval. It will throw on the :cancel drain.
        (rf/reg-flow :http/boom {:inputs [[:http/in-flight]] :output-path [:derived :http-doomed]} (fn [_]
                                (throw (ex-info "flow boom on cancel"
                                                {:why :test}))))

        (reset! *captured* [])
        (rf/dispatch-sync [:http/cancel :load-articles])

        ;; The handler's :db did NOT land — pre-install throw discards
        ;; the entire pending :db (handler dissoc + flow write alike).
        (is (= baseline-db (rf/app-db-value :rf/default))
            "app-db is byte-for-byte the pre-cancel value — the handler's
             dissoc was rolled in with the flow's pending write and
             discarded wholesale by the flow throw")
        (is (true? (get-in (rf/app-db-value :rf/default)
                           [:http/in-flight :load-articles]))
            "app-db's in-flight slot for :load-articles is STILL populated
             — the cancel's dissoc did NOT install")

        ;; The :rf.http/managed-abort fx did NOT fire — :fx is the
        ;; post-install stage that the flow-throw path skips wholesale.
        (is (empty? @abort-calls)
            ":rf.http/managed-abort did NOT fire — fx walks AFTER install,
             and a flow throw aborts the event BEFORE install (atomicity
             contract rule a; split-brain prevention: no transport abort
             on a request whose app-db state still says it's pending)")
        (is (contains? (http-managed/in-flight-snapshot) :load-articles)
            "the side-channel in-flight registry STILL holds the request
             — finalise-failure! never ran because the abort-fn was never
             called")

        ;; Trace signature: :rf.flow/failed fired but NO :rf.event/db-changed.
        (is (seq (by-op :rf.flow/failed))
            ":rf.flow/failed fired — the flow eval threw")
        (is (not-any? #(= :rf.event/db-changed %) (ops))
            "NO :rf.event/db-changed in the throw stream — the event
             aborted before install (split-brain prevention's load-
             bearing assertion: no install signal means no observer can
             see a partial commit)")))))
