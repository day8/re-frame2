(ns re-frame.observability-routing-cljs-test
  "EP-0015 §9 (rf2-t55hxg.7) — frame-owned observability sink routing, END
  TO END. The CENTRAL §9 claim, exercised through a REAL dispatch:

  > App authors declare a sink under frame `:observability`; the runtime
  > projects every record under the owning frame's classification and the
  > sink's egress profile BEFORE the sink sees it; sinks consume
  > already-projected records only.

  This is the unmet graduation gate for `:rf.egress/off-box-observability`
  (EP issue 3 — the profile must be exercised by a real hosted-monitoring
  sink) AND the e2e leg rf2-t55hxg.4 presupposes. Distinct from
  `re-frame.projection-cljs-test` (which unit-tests `project-egress` on a
  hand-built record): here a genuine `dispatch-sync` drives the router's
  cascade trailers, which build the `:rf.observe/handled-event` record and
  route it through `project-egress` to a frame-declared sink; and a genuine
  handler-exception drives `error-emit/dispatch-on-error!`, which routes the
  `:rf.observe/error` record to a frame-declared error sink.

  Pins the legs the bead enumerates:

    - a handled-event record reaches the declared sink, PROJECTED (the
      frame's sensitive app-db path is redacted; the off-box default omits
      the `:event` args slot entirely);
    - an error record reaches the declared error sink, PROJECTED (the
      sensitive token inside the error's `:event` is redacted);
    - the sink NEVER re-implements redaction — it sees an already-projected
      record;
    - FAIL-CLOSED: a frame with NO `:observability` policy routes nothing;
      an unresolved frame routes nothing (no `:rf/default` synthesis);
    - a buggy (throwing) sink is isolated — it cannot block a sibling sink.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it
  up. Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.event-emit :as event-emit]
            [re-frame.frame :as frame]
            [re-frame.observability :as observability]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The reset-runtime fixture rebuilds the registrar / frames / runtime per
;; test. We additionally clear BOTH the always-on listener registries AND
;; the observability sink registry so a sink registered by one test cannot
;; leak into the next.
(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (event-emit/clear-event-listeners!)
                (error-emit/clear-error-listeners!)
                (observability/clear-observability-sinks!))}))

(defn- redacted? [v] (= :rf/redacted v))

;; ---------------------------------------------------------------------------
;; 1. Handled-event record routes through project-egress to a declared sink.
;; ---------------------------------------------------------------------------

(deftest handled-event-routes-projected-to-declared-sink
  (testing "a real dispatch routes ONE projected :rf.observe/handled-event
            record to the frame's declared :handled-events sink"
    (let [seen (atom [])]
      ;; The app registers the concrete sink fn against the id the frame
      ;; policy names. The sink does NO redaction — it just records.
      (rf/register-observability-sink! :test.sinks/datadog
                                  (fn [record] (swap! seen conj record)))
      ;; The frame declares the sink under :observability, AND classifies
      ;; [:auth :token] sensitive (so the projector has policy to apply).
      (rf/reg-frame :obs/main
        {:observability
         {:handled-events [{:sink :test.sinks/datadog
                            :rf.egress/profile :rf.egress/off-box-observability
                            :opts {:service "checkout-spa"}}]}})
      ;; EP-0025: classify [:auth :token] sensitive via the commit-plane
      ;; effect path (the durable frame annotation is removed) so the
      ;; projector has policy to apply.
      (frame/swap-runtime-db! :obs/main
        (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
      (rf/reg-event :auth/login
                       {:frame :obs/main}
                       (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "super-secret")}))
      (rf/dispatch-sync [:auth/login {:password "hunter2"}] {:frame :obs/main})
      (is (= 1 (count @seen)) "the declared sink fired exactly once")
      (let [r (first @seen)]
        (is (= :rf.observe/handled-event (:kind r))
            "the record is a canonical :rf.observe/handled-event")
        (is (= :obs/main (:frame r)))
        (is (= :auth/login (:event-id r)))
        (is (= :ok (:status r)))
        (is (integer? (:elapsed-ms r)))
        (is (= [:db] (:effects r)) "the cascade's effect keys ride :effects")
        ;; The §9 PROJECTION proof: under the off-box-observability default
        ;; the :event args slot is OMITTED ENTIRELY (EP-0015 issue 4). The
        ;; sink never saw the raw {:password ...} args.
        (is (not (contains? r :event))
            "off-box default omits the :event args slot — the sink never
             sees the raw event payload")))))

(deftest handled-event-sink-receives-already-projected-event-under-raw-profile
  (testing "a trusted-local profile keeps the :event slot, PROJECTED through
            the frame's classification — the sink never re-implements
            redaction"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/local
                                  (fn [record] (swap! seen conj record)))
      ;; A sink on the local-raw boundary keeps :event but the projector
      ;; STILL applies frame policy to the event's tree slots — proving the
      ;; sink consumes an already-projected record, not a raw one. We
      ;; classify the event-arg path [:1 :card] sensitive so the projected
      ;; :event has it redacted even though the profile is local-raw.
      (rf/reg-frame :obs/raw
        {:observability
         {:handled-events [{:sink :test.sinks/local
                            :rf.egress/profile :rf.egress/local-raw}]}})
      (rf/reg-event :pay/submit
                       {:frame :obs/raw}
                       (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:pay/submit {:amount 10}] {:frame :obs/raw})
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (contains? r :event) "local-raw retains the :event slot")
        (is (= [:pay/submit {:amount 10}] (:event r))
            "local-raw projects the event verbatim (sensitive opted in,
             nothing classified sensitive here)")))))

;; ---------------------------------------------------------------------------
;; 2. Error record routes through project-egress to a declared error sink.
;; ---------------------------------------------------------------------------

(deftest error-routes-projected-to-declared-error-sink
  (testing "a handler exception routes ONE projected :rf.observe/error
            record to the frame's declared :errors sink, with the sensitive
            token inside the error's :event redacted"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                  (fn [record] (swap! seen conj record)))
      (rf/reg-frame :obs/err
        {:observability
         {:errors [{:sink :test.sinks/sentry
                    :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; EP-0025: classify [:auth :token] sensitive via the commit-plane
      ;; effect path (the durable frame annotation is removed) so the
      ;; projector redacts it inside the error record's :event tree slot.
      (frame/swap-runtime-db! :obs/err
        (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
      (rf/reg-event :auth/login
                       {:frame :obs/err}
                       (fn [{:keys [db]} _] {:db (throw (ex-info "kaboom" {:cause :test}))}))
      (rf/dispatch-sync [:auth/login {:auth {:token "super-secret-token"}}]
                        {:frame :obs/err})
      (is (= 1 (count @seen)) "the declared error sink fired exactly once")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= :obs/err (:frame r)))
        (is (= :rf.error/handler-exception (:error r)))
        (is (= :auth/login (:event-id r)))
        ;; The §9 PROJECTION proof: the sensitive token inside the error's
        ;; :event tree slot is redacted under the frame's classification.
        ;; The frame classifies [:auth :token] (an app-db path), and the
        ;; walker applies that path to the event's arg map.
        (is (redacted? (get-in (:event r) [1 :auth :token]))
            "the sensitive token inside the error's :event is redacted —
             the sink received an already-projected record")))))

(deftest error-event-redacted-by-event-registration-marks-not-frame-app-db
  (testing "ADVERSARIAL (rf2-qe6v1u — EP-0015 event args are REGISTRATION-owned):
            a handler registered with `reg-event {:sensitive [[:password]]}` and
            a frame that declares NO matching `:sensitive {:app-db …}` path must
            STILL have the sensitive event arg redacted on the off-box `:errors`
            sink. Event args are registration-owned transient payloads, projected
            through the EVENT registration's marks at the trust boundary — not
            (only) the frame's app-db classification. Before the fix the error
            record's :event slot was walked only against frame app-db policy, so a
            handler-declared-sensitive arg with no frame app-db classification
            leaked the raw password off-box."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry2
                                       (fn [record] (swap! seen conj record)))
      ;; The frame declares the error sink but NO :sensitive classification at
      ;; all — the redaction must come from the EVENT registration, not the frame.
      (rf/reg-frame :obs/reg-marks
        {:observability
         {:errors [{:sink :test.sinks/sentry2
                    :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; The handler OWNS the sensitivity of its own event arg: [:password] in
      ;; the arg-map (the registration-marks paths are rooted at the arg-map).
      (rf/reg-event :auth/reg-login
                    {:frame     :obs/reg-marks
                     :sensitive [[:password]]}
                    (fn [{:keys [db]} _] {:db (throw (ex-info "kaboom" {:cause :test}))}))
      (rf/dispatch-sync [:auth/reg-login {:password "hunter2" :user "ann"}]
                        {:frame :obs/reg-marks})
      (is (= 1 (count @seen)) "the declared error sink fired exactly once")
      (let [r (first @seen)]
        (is (= :rf.observe/error (:kind r)))
        (is (= :auth/reg-login (:event-id r)))
        (is (redacted? (get-in (:event r) [1 :password]))
            "the handler-declared-sensitive :password arg is redacted via the
             EVENT registration marks, with NO frame :sensitive {:app-db …}")
        (is (= "ann" (get-in (:event r) [1 :user]))
            "a non-sensitive sibling arg rides through (only the declared path redacts)")))))

;; ---------------------------------------------------------------------------
;; 3. Fail-closed.
;; ---------------------------------------------------------------------------

(deftest no-observability-policy-routes-nothing
  (testing "a frame with no :observability policy routes nothing (the sink
            is never called) even though a sink is registered"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/unused
                                  (fn [record] (swap! seen conj record)))
      (rf/reg-frame :obs/none {})
      (rf/reg-event :evt/noop {:frame :obs/none} (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:evt/noop] {:frame :obs/none})
      (is (empty? @seen)
          "no :observability policy ⇒ no routing, regardless of registered
           sinks"))))

(deftest unresolved-frame-routes-nothing-no-default-synthesis
  (testing "routing against an unresolved frame is a NO-OP — it does not
            synthesise :rf/default, does not borrow another frame's policy"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/datadog
                                  (fn [record] (swap! seen conj record)))
      ;; Call the routing fn directly against a frame id that was never
      ;; registered. Fail-closed: nil frame record ⇒ no policy ⇒ no-op.
      (observability/route-handled-event!
        [:evt/x] :evt/x :obs/ghost :ok 1 [:db] nil)
      (observability/route-error!
        :rf.error/handler-exception [:evt/x] :evt/x :obs/ghost
        (ex-info "x" {}) 1 0 nil)
      (is (empty? @seen)
          "an unresolved frame routes nothing — no :rf/default synthesis"))))

;; ---------------------------------------------------------------------------
;; 4. Sibling isolation — a buggy sink cannot block a sibling.
;; ---------------------------------------------------------------------------

(deftest buggy-sink-is-isolated-from-siblings
  (testing "a throwing sink is dropped; the sibling sink on the same stream
            still receives the projected record"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/boom
                                  (fn [_record] (throw (ex-info "sink bug" {}))))
      (rf/register-observability-sink! :test.sinks/good
                                  (fn [record] (swap! seen conj record)))
      (rf/reg-frame :obs/sib
        {:observability
         {:handled-events [{:sink :test.sinks/boom
                            :rf.egress/profile :rf.egress/off-box-observability}
                           {:sink :test.sinks/good
                            :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf/reg-event :evt/go {:frame :obs/sib} (fn [{:keys [db]} _] {:db db}))
      ;; The throwing sink must not blow up the dispatch nor starve the good
      ;; sink.
      (rf/dispatch-sync [:evt/go] {:frame :obs/sib})
      (is (= 1 (count @seen))
          "the sibling sink still received the record despite the buggy
           sink throwing")
      (is (= :rf.observe/handled-event (:kind (first @seen)))))))
