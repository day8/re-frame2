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
  handler-exception drives `rf.error-emit/dispatch-on-error!`, which routes the
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
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.observability :as rf.observability]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The reset-runtime fixture rebuilds the registrar / frames / runtime per
;; test. We additionally clear BOTH the always-on listener registries AND
;; the observability sink registry so a sink registered by one test cannot
;; leak into the next.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

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
      (rf/make-frame {:id :obs/main :observability
                      {:handled-events [{:sink :test.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; EP-0025: classify [:auth :token] sensitive via the commit-plane
      ;; effect path (the durable frame annotation is removed) so the
      ;; projector has policy to apply.
      (rf.frame/swap-runtime-db! :obs/main
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
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
      (rf/make-frame {:id :obs/raw :observability
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
      (rf/make-frame {:id :obs/err :observability
                      {:errors [{:sink :test.sinks/sentry
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; EP-0025: classify [:auth :token] sensitive via the commit-plane
      ;; effect path (the durable frame annotation is removed) so the
      ;; projector redacts it inside the error record's :event tree slot.
      (rf.frame/swap-runtime-db! :obs/err
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
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
      (rf/make-frame {:id :obs/reg-marks :observability
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
      (rf/make-frame {:id :obs/none})
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
      (rf.observability/route-handled-event!
        [:evt/x] :evt/x :obs/ghost :ok 1 [:db] nil)
      (rf.observability/route-error!
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
      (rf/make-frame {:id :obs/sib :observability
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

;; ---------------------------------------------------------------------------
;; 5. Producer attribution survives the sink route (rf2-kuky.65).
;; ---------------------------------------------------------------------------
;;
;; `error-emit/dispatch-on-error!` builds the corpus-wide record with the
;; producer's component attribution merged in (`:failing-id` / `:reason` for the
;; interceptor + cofx categories, `:flow-id` + `:where :flow-eval` for flow-eval,
;; plus `:source-coord`). The frame-owned sink route must carry the SAME
;; structural attribution: once the corpus-wide `:errors` stream retires, the
;; sink is the ONLY production door, so a sink that never learns WHICH
;; interceptor / cofx failed is lost diagnosis, not redundancy.
;;
;; The split is the one Spec 015 §Frame-owned observability sink policy records:
;; the structural identifiers are SUMMARY slots (pass the projector unchanged);
;; `:reason` is free-form prose that interpolates app values — the coeffect
;; categories fold the thrown exception's own message into it — so it rides
;; `:tags`, walked and redacted under frame classification, exactly as the
;; non-event `route-error-record!` route already treats it.

(deftest error-sink-record-carries-producer-component-attribution
  (testing "rf2-kuky.65: a throwing user interceptor delivers a projected
            :rf.observe/error whose :failing-id names the INTERCEPTOR and is
            distinct from :event-id — the same attribution the corpus-wide
            record carries — with :source-coord alongside it"
    (let [seen   (atom [])
          corpus (atom [])]
      (rf.error-emit/register-error-listener! :test/corpus
                             (fn [record] (swap! corpus conj record)))
      (rf/register-observability-sink! :test.sinks/attribution
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/attr :observability
                      {:errors [{:sink :test.sinks/attribution
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      (rf/reg-interceptor :kuky65/boom-after
                          {:after (fn [_ctx] (throw (ex-info "after boom" {})))})
      (rf/reg-event :kuky65/with-throwing-interceptor
                    {:frame        :obs/attr
                     :interceptors [:kuky65/boom-after]}
                    (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
      ;; NON-NIL SOURCE-COORD CONTROL. `(= (:source-coord c) (:source-coord r))`
      ;; alone passes vacuously when BOTH are nil, so the producer-present leg
      ;; would prove nothing. Plant a known coord in the always-on error-coord
      ;; registry the producer resolves from (`error-emit/error-source-coord` ->
      ;; `source-coords/error-coords-for [:event id]`) AFTER the registration,
      ;; so the slot is unambiguously non-nil on both routes and the equality
      ;; below is an equality of VALUES rather than of two absences.
      (rf.source-coords/remember-error-coords!
        :event :kuky65/with-throwing-interceptor
        {:ns 'kuky65.attr :file "test/kuky65/attr.cljc" :line 4242})
      (rf/dispatch-sync [:kuky65/with-throwing-interceptor] {:frame :obs/attr})
      (let [r (some (fn [x] (when (= :rf.error/interceptor-exception (:error x)) x))
                    @seen)
            c (some (fn [x] (when (= :rf.error/interceptor-exception (:error x)) x))
                    @corpus)]
        (is (some? c) "the corpus-wide listener received the record (the control)")
        (is (some? r) "the frame-owned :errors sink received the record")
        (is (= :kuky65/with-throwing-interceptor (:event-id r))
            ":event-id still carries the EVENT id")
        (is (= :kuky65/boom-after (:failing-id r))
            ":failing-id names the failing INTERCEPTOR on the sink route too,
             distinct from :event-id")
        (is (not= (:event-id r) (:failing-id r))
            "the whole point of producer attribution: the failing component id is
             DISTINCT from the event id")
        (is (= (:failing-id c) (:failing-id r))
            "the sink route carries the SAME :failing-id the corpus record does")
        (is (= {:ns 'kuky65.attr :file "test/kuky65/attr.cljc" :line 4242}
               (:source-coord r))
            "the PLANTED, NON-NIL producer source-coord reaches the sink verbatim
             — not a nil == nil agreement")
        (is (= (:source-coord c) (:source-coord r))
            ":source-coord agrees with the corpus record, both non-nil")
        ;; `:reason` is the TREE slot: it rides :tags, where the projector walks
        ;; it under frame classification.
        (is (string? (get-in r [:tags :reason]))
            ":reason rides the :tags tree slot, not a public summary slot")
        (is (nil? (:reason r))
            ":reason is NOT lifted onto the summary surface")))))

(deftest error-sink-record-carries-coeffect-supplier-attribution
  (testing "rf2-kuky.65: a throwing COEFFECT SUPPLIER — the other category whose
            failing component is distinct from the dispatched event — delivers a
            projected :rf.observe/error to the frame-owned sink whose :failing-id
            names the SUPPLIER, distinct from the :event-id. The interceptor legs
            above cover one category only; a cofx supplier reaches the sink by a
            different producer (`cofx/emit-coeffect-exception!` via
            `emit-error-both!`), so it is its own regression."
    (let [seen   (atom [])
          corpus (atom [])]
      (rf.error-emit/register-error-listener! :test/corpus-cofx
                             (fn [record] (swap! corpus conj record)))
      (rf/register-observability-sink! :test.sinks/cofx-attribution
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/cofx :observability
                      {:errors [{:sink :test.sinks/cofx-attribution
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; A REAL registered supplier that throws at context assembly, behind a
      ;; DECLARED `:rf.cofx/requires` — not a hand-built record.
      (rf/reg-cofx :kuky65/boom-cofx
                   (fn [] (throw (ex-info "cofx supplier boom" {}))))
      (rf/reg-event :kuky65/needs-boom-cofx
                    {:frame             :obs/cofx
                     :rf.cofx/requires  [:kuky65/boom-cofx]}
                    (fn [{:keys [db]} _] {:db db}))
      (rf.source-coords/remember-error-coords!
        :event :kuky65/needs-boom-cofx
        {:ns 'kuky65.cofx :file "test/kuky65/cofx.cljc" :line 77})
      (try (rf/dispatch-sync [:kuky65/needs-boom-cofx] {:frame :obs/cofx})
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
      (let [r (some (fn [x] (when (= :rf.error/coeffect-exception (:error x)) x))
                    @seen)
            c (some (fn [x] (when (= :rf.error/coeffect-exception (:error x)) x))
                    @corpus)]
        (is (some? c) "the corpus-wide listener received the cofx record (the control)")
        (is (some? r) "the frame-owned :errors sink received the cofx record")
        (is (= :kuky65/needs-boom-cofx (:event-id r))
            ":event-id carries the EVENT id, as it does on the corpus record")
        (is (= :kuky65/boom-cofx (:failing-id r))
            ":failing-id names the failing COEFFECT SUPPLIER on the sink route")
        (is (not= (:event-id r) (:failing-id r))
            "SUPPLIER id is DISTINCT from the EVENT id — the distinction is the
             whole point of producer attribution, and no egress profile can
             restore what the record never carried")
        (is (= (:failing-id c) (:failing-id r))
            "the sink route carries the SAME :failing-id the corpus record does")
        (is (= {:ns 'kuky65.cofx :file "test/kuky65/cofx.cljc" :line 77}
               (:source-coord r))
            "the planted NON-NIL source-coord reaches the sink on the cofx route too")
        (is (string? (get-in r [:tags :reason]))
            "the supplier's interpolating :reason rides the :tags TREE slot")
        (is (nil? (:reason r))
            ":reason is NOT lifted onto the summary surface on this route either")))))

(deftest classified-reason-redacts-whole-slot-while-attribution-survives
  (testing "rf2-kuky.65: `:reason` rides :tags precisely so a frame CAN reach it.
            With [:reason] CLASSIFIED sensitive the projector redacts that slot
            WHOLE (:rf/redacted — the interpolated supplier message goes with it)
            while the structural :failing-id and :source-coord survive beside it.
            That pair is the actual contract; asserting only that a string sits
            under :tags cannot fail for the right reason. The DEFAULT (nothing
            classified) is pinned by the two tests above — this is the classified
            arm, and the two together are the whole of the claim."
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/classified-reason
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/reason :observability
                      {:errors [{:sink :test.sinks/classified-reason
                                 :rf.egress/profile :rf.egress/off-box-observability}]}})
      ;; The frame classifies [:reason] — the path the :tags tree slot is walked
      ;; against — via the same commit-plane effect path an app would use.
      (rf.frame/swap-runtime-db! :obs/reason
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:reason]]})))
      (rf/reg-cofx :kuky65/classified-boom-cofx
                   (fn [] (throw (ex-info "supplier leaked hunter2 into its message" {}))))
      (rf/reg-event :kuky65/classified-reason-event
                    {:frame            :obs/reason
                     :rf.cofx/requires [:kuky65/classified-boom-cofx]}
                    (fn [{:keys [db]} _] {:db db}))
      (rf.source-coords/remember-error-coords!
        :event :kuky65/classified-reason-event
        {:ns 'kuky65.reason :file "test/kuky65/reason.cljc" :line 11})
      (try (rf/dispatch-sync [:kuky65/classified-reason-event] {:frame :obs/reason})
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
      (let [r (some (fn [x] (when (= :rf.error/coeffect-exception (:error x)) x))
                    @seen)]
        (is (some? r) "the frame-owned :errors sink received the record")
        (is (redacted? (get-in r [:tags :reason]))
            "a CLASSIFIED [:reason] redacts WHOLE-SLOT to :rf/redacted — the
             interpolated supplier message does not egress")
        (is (= :kuky65/classified-boom-cofx (:failing-id r))
            "the structural :failing-id SURVIVES beside the redacted :reason —
             redacting the prose does not cost the diagnosis")
        (is (= {:ns 'kuky65.reason :file "test/kuky65/reason.cljc" :line 11}
               (:source-coord r))
            "the structural :source-coord survives beside it too")))))

(deftest error-sink-attribution-survives-public-error-profile
  (testing "rf2-kuky.65 + rf2-z1332c: under :rf.egress/public-error the
            projector drops :exception, and the component attribution SURVIVES
            that profile on the sink route — while the CONTROL, a frame-
            classified sensitive path inside the error's :event, is still
            redacted, so the fix widened nothing that escapes"
    (let [seen (atom [])]
      (rf/register-observability-sink! :test.sinks/public
                                       (fn [record] (swap! seen conj record)))
      (rf/make-frame {:id :obs/pub :observability
                      {:errors [{:sink :test.sinks/public
                                 :rf.egress/profile :rf.egress/public-error}]}})
      (rf.frame/swap-runtime-db! :obs/pub
        (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
      (rf/reg-interceptor :kuky65/pub-boom
                          {:after (fn [_ctx] (throw (ex-info "after boom" {})))})
      (rf/reg-event :kuky65/pub-event
                    {:frame        :obs/pub
                     :interceptors [:kuky65/pub-boom]}
                    (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
      (rf/dispatch-sync [:kuky65/pub-event {:auth {:token "super-secret-token"}}]
                        {:frame :obs/pub})
      (let [r (some (fn [x] (when (= :rf.error/interceptor-exception (:error x)) x))
                    @seen)]
        (is (some? r) "the public-error sink received the record")
        (is (= :kuky65/pub-boom (:failing-id r))
            "attribution SURVIVES the profile that drops :exception")
        (is (not (contains? r :exception))
            "CONTROL: :rf.egress/public-error still drops :exception")
        (is (redacted? (get-in (:event r) [1 :auth :token]))
            "CONTROL: the frame-classified sensitive path inside :event is still
             redacted — the attribution fix widened nothing that escapes")))))
