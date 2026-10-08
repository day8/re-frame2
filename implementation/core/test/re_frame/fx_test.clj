(ns re-frame.fx-test
  "Cross-cutting fx-subsystem edge cases (Spec 002 §`:fx` ordering and
  atomicity guarantees, Spec 009 §Error contract): source-order walking,
  `:fx-overrides` precedence and its reserved-fx tiers, `:isolated` recovery
  past a throwing or unknown fx, and effect-map / `:fx`-entry shape policing.

  Posture: execution facts and the ALWAYS-ON `:errors` records are asserted
  unguarded, so they also run under `scripts/test-core-prod-gate.sh`
  (`-Dre-frame.debug=false`). The `:trace` stream is dev-only, so trace reads
  sit inside `(when rf.interop/debug-enabled? …)` arms — a negative over the
  empty ring would otherwise pass for free — or in `^:requires-debug` tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; `clear-all!` leaves the always-on error-listener registry alone.
  (rf.error-emit/clear-error-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id :rf/default})
  ;; Restore the framework registrations `clear-all!` wiped (`:rf.machine/*`,
  ;; `:rf.route/*`).
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- observe
  "Run `f`, returning its `:result`, the ALWAYS-ON error records (`:errors`)
  and the dev-only trace events (`:traces`) it produced."
  [f]
  (let [errors (atom [])
        traces (atom [])]
    (rf.error-emit/register-error-listener! ::observe #(swap! errors conj %))
    (rf/register-listener! :trace ::observe #(swap! traces conj %))
    (try
      (let [result (f)]
        {:result result :errors @errors :traces @traces})
      (finally
        (rf.error-emit/unregister-error-listener! ::observe)
        (rf/unregister-listener! :trace ::observe)))))

(defn- of-category [category records] (filterv #(= category (:error %)) records))

(defn- of-op [op traces] (filterv #(= op (:operation %)) traces))

;; ---- 1. Source-order ordering ---------------------------------------------

(deftest source-order-across-mixed-fx
  (testing "sync fx fire in source order; :dispatch-queued events drain afterwards, in source order"
    (let [log (atom [])]
      (rf/reg-fx :fx-test/sync-a (fn [_ _] (swap! log conj :sync-a)))
      (rf/reg-fx :fx-test/sync-b (fn [_ _] (swap! log conj :sync-b)))
      (rf/reg-event :fx-test/queued-a
        (fn [{:keys [db]} _] (swap! log conj :queued-a) {:db db}))
      (rf/reg-event :fx-test/queued-b
        (fn [{:keys [db]} _] (swap! log conj :queued-b) {:db db}))
      (rf/reg-event :fx-test/run-interleaved
        (fn [_ _]
          {:fx [[:fx-test/sync-a]
                [:dispatch [:fx-test/queued-a]]
                [:fx-test/sync-b]
                [:dispatch [:fx-test/queued-b]]]}))
      (rf/dispatch-sync [:fx-test/run-interleaved])
      (is (= [:sync-a :sync-b :queued-a :queued-b] @log)))))

;; ---- 2. :fx-overrides precedence ------------------------------------------

(deftest fx-overrides-per-call-beats-per-frame-beats-registered
  (let [fired (atom [])]
    (rf/reg-fx :fx-test/email       (fn [_ _] (swap! fired conj :registered)))
    (rf/reg-fx :fx-test/email.frame (fn [_ _] (swap! fired conj :per-frame)))
    (rf/reg-fx :fx-test/email.call  (fn [_ _] (swap! fired conj :per-call)))
    (rf/reg-event :fx-test/send
      (fn [_ _] {:fx [[:fx-test/email {:to "alice"}]]}))
    (let [f (rf.frame/make-anon-frame-record!
              {:fx-overrides {:fx-test/email :fx-test/email.frame}})]
      (testing "a per-frame override beats the registered fx"
        (rf/dispatch-sync [:fx-test/send] {:frame f})
        (is (= [:per-frame] @fired)))
      (testing "a per-call override beats the per-frame one"
        (reset! fired [])
        (rf/dispatch-sync [:fx-test/send]
                          {:frame f :fx-overrides {:fx-test/email :fx-test/email.call}})
        (is (= [:per-call] @fired))))))

(deftest with-fx-overrides-binds-fx-overrides-lexically
  (let [fired (atom [])]
    (rf/reg-fx :fx-test/wo-email      (fn [_ _] (swap! fired conj :registered)))
    (rf/reg-fx :fx-test/wo-email.stub (fn [_ _] (swap! fired conj :lexical)))
    (rf/reg-fx :fx-test/wo-email.call (fn [_ _] (swap! fired conj :per-call)))
    (rf/reg-event :fx-test/wo-send
      (fn [_ _] {:fx [[:fx-test/wo-email {:to "alice"}]]}))
    (rf/with-fx-overrides {:fx-test/wo-email :fx-test/wo-email.stub}
      (testing "a dispatch inside the body inherits the lexical override"
        (rf/dispatch-sync [:fx-test/wo-send])
        (is (= [:lexical] @fired)))
      (testing "a per-call opt outranks the lexical binding"
        (reset! fired [])
        (rf/dispatch-sync [:fx-test/wo-send]
                          {:fx-overrides {:fx-test/wo-email :fx-test/wo-email.call}})
        (is (= [:per-call] @fired))))))

;; ---- 3. fx-handler exception is :isolated ---------------------------------

(deftest fx-handler-exception-is-isolated
  (testing "a throwing fx is skipped, its siblings still fire, and an always-on record names it"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/boom  (fn [_ _] (throw (ex-info "kaboom" {:why :test}))))
      (rf/reg-fx :fx-test/after (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/with-bad-fx
        (fn [_ _]
          {:fx [[:fx-test/boom  {:reason :first}]
                [:fx-test/after {:reason :sibling}]]}))
      (let [{:keys [errors]} (observe #(rf/dispatch-sync [:fx-test/with-bad-fx]))
            records          (of-category :rf.error/fx-handler-exception errors)]
        (is (= [{:reason :sibling}] @fired))
        (is (= [{:failing-id :fx-test/boom
                 :event-id   :fx-test/with-bad-fx
                 :event      [:fx-test/with-bad-fx]
                 :frame      :rf/default}]
               (map #(select-keys % [:failing-id :event-id :event :frame]) records)))
        (is (some? (:exception (first records))))))))

;; ---- 4. Unknown fx-id is logged and skipped -------------------------------

(deftest unknown-fx-id-is-logged-and-skipped
  (testing "an unknown fx-id is skipped, the walk continues, and an always-on record names it"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/sibling (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/missing
        (fn [_ _]
          {:fx [[:fx-test/never-registered {:k 1}]
                [:fx-test/sibling          {:k 2}]]}))
      (let [{:keys [errors]} (observe #(rf/dispatch-sync [:fx-test/missing]))
            records          (of-category :rf.error/no-such-fx errors)]
        (is (= [{:k 2}] @fired))
        (is (= [{:event-id   :fx-test/missing
                 :event      [:fx-test/missing]
                 :frame      :rf/default
                 :failing-id :fx-test/never-registered}]
               (map #(select-keys % [:event-id :event :frame :failing-id]) records)))
        ;; The record egresses off-box, so its key set is pinned CLOSED. No
        ;; `:rf.fx/args`: an unregistered fx has no `:sensitive` declaration.
        (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms
                 :failing-id :source-coord}
               (set (keys (first records)))))))))

;; `(rf/clear :fx id)` routes to `rf.registrar/unregister!`.
(deftest clear-fx-removes-and-restores
  (let [counter (atom 0)
        touch   (fn [_ _] (swap! counter inc))]
    (rf/reg-fx :test.634y/touch touch)
    (rf/reg-event :test.634y/run (fn [_ _] {:fx [[:test.634y/touch :payload]]}))
    (rf/dispatch-sync [:test.634y/run])
    (is (= 1 @counter) "the registered fx fired")
    (rf/clear :fx :test.634y/touch)
    (rf/dispatch-sync [:test.634y/run])
    (is (= 1 @counter) "the cleared fx did not fire")
    (rf/reg-fx :test.634y/touch touch)
    (rf/dispatch-sync [:test.634y/run])
    (is (= 2 @counter) "re-registering after a clear fires again")))

;; ---- 6. Effect-map envelope policing (M-8) --------------------------------

(deftest malformed-effect-map-envelope-refuses-the-event
  (testing "a map :fx value — seqable into a valid-looking [:dispatch …] entry —
            refuses the whole event"
    (let [fired? (atom false)]
      (rf/reg-event :fx-test/sentinel
        (fn [{:keys [db]} _] (reset! fired? true) {:db db}))
      (rf/reg-event :fx-test/malformed-envelope
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx {:dispatch [:fx-test/sentinel]}}))
      (let [{:keys [traces]} (observe #(rf/dispatch-sync [:fx-test/malformed-envelope]))]
        (is (false? @fired?) "nothing inside the refused envelope ran")
        (is (nil? (:seeded? (rf/app-db-value :rf/default))) "the legal :db did not commit")
        (when rf.interop/debug-enabled?
          (let [[t :as ts] (of-op :rf.error/effect-map-shape traces)]
            (is (= 1 (count ts)))
            (is (= :fix-effect (:recovery t)))
            (is (= {:offending-key     :fx
                    :rf.trace/event-id :fx-test/malformed-envelope
                    :value             {:dispatch [:fx-test/sentinel]}}
                   (select-keys (:tags t) [:offending-key :rf.trace/event-id :value])))))))))

(deftest multiple-legacy-effect-map-keys-refuse-the-event-once
  (testing "legacy top-level keys beside a legal :db and :fx refuse the whole event, once"
    (let [fired      (atom [])
          never-ran  (atom 0)
          http-fired (atom 0)]
      (rf/reg-fx :fx-test/sibling (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/never-runs
        (fn [{:keys [db]} _] (swap! never-ran inc) {:db db}))
      (rf/reg-fx :http (fn [_ _] (swap! http-fired inc)))
      (rf/reg-event :fx-test/multi-legacy
        (fn [{:keys [db]} _]
          {:db       (assoc db :seeded? true)
           :fx       [[:fx-test/sibling {:k :legit}]]
           :dispatch [:fx-test/never-runs]
           :http     {:url "/api"}}))
      (let [{:keys [traces]} (observe #(rf/dispatch-sync [:fx-test/multi-legacy]))]
        (is (= [[] 0 0 nil]
               [@fired @never-ran @http-fired (:seeded? (rf/app-db-value :rf/default))])
            "nothing applied: no :fx, no legacy key routed, no :db")
        (when rf.interop/debug-enabled?
          (let [shape (of-op :rf.error/effect-map-shape traces)]
            (is (= 1 (count shape)) "the first defect ends the event")
            (is (contains? #{:dispatch :http}
                           (get-in (first shape) [:tags :offending-key])))))))))

;; ---- 6c. Per-entry :fx shape policing -------------------------------------

(deftest malformed-fx-entry-is-policed-and-skipped
  ;; The two vector shapes a vector-only guard would wave through to
  ;; `handle-one-fx`. The non-vector typo is pinned by
  ;; `re-frame.effect-map-shape-record-cljs-test`.
  (doseq [[label bad-entry]
          [["a non-keyword head — a bad fx-id TYPE, not an unknown fx-id"
            ["not-a-keyword" {:x 1}]]
           ["a surplus 3rd field — dropped, NOT truncated to a 2-tuple and fired"
            [:fx-test/entry-sibling {:used true} {:dropped true}]]]]
    (testing label
      (let [fired (atom [])]
        (rf/reg-fx :fx-test/entry-sibling (fn [_ args] (swap! fired conj args)))
        (rf/reg-event :fx-test/malformed-entry
          (fn [{:keys [db]} _]
            {:db (assoc db :seeded label)
             :fx [[:fx-test/entry-sibling {:k 1}]
                  bad-entry
                  [:fx-test/entry-sibling {:k 2}]]}))
        (let [{:keys [errors traces]} (observe #(rf/dispatch-sync [:fx-test/malformed-entry]))]
          (is (= [{:k 1} {:k 2}] @fired) "only the malformed entry was dropped")
          (is (= label (:seeded (rf/app-db-value :rf/default))) ":db committed")
          (is (empty? (of-category :rf.error/no-such-fx errors))
              "not mis-reported as an unknown fx-id")
          (when rf.interop/debug-enabled?
            (let [[t :as ts] (of-op :rf.error/effect-map-shape traces)]
              (is (= 1 (count ts)))
              (is (= :logged-and-skipped (:recovery t)))
              (is (= {:offending-key     :fx
                      :rf.trace/event-id :fx-test/malformed-entry
                      :frame             :rf/default
                      :value             bad-entry}
                     (select-keys (:tags t)
                                  [:offending-key :rf.trace/event-id :frame :value]))))))))))

(deftest legal-fx-spellings-fire-commit-and-emit-no-shape-trace
  ;; nil / [] entries are the conditional-fx idiom; [:fx-id] is the no-args shorthand.
  (doseq [[label fx expected-fired]
          [["a nil :fx value" nil []]
           ["nil and [] entries beside well-shaped ones"
            [[:fx-test/legal-sink {:k 1}] nil [] [:fx-test/legal-sink {:k 2}]] [{:k 1} {:k 2}]]
           ["the no-args [:fx-id] shorthand, which fires with nil args"
            [[:fx-test/legal-sink]] [nil]]]]
    (testing label
      (let [fired (atom [])]
        (rf/reg-fx :fx-test/legal-sink (fn [_ args] (swap! fired conj args)))
        (rf/reg-event :fx-test/legal-fx
          (fn [{:keys [db]} _] {:db (assoc db :seeded label) :fx fx}))
        (let [{:keys [traces]} (observe #(rf/dispatch-sync [:fx-test/legal-fx]))]
          (is (= expected-fired @fired))
          (is (= label (:seeded (rf/app-db-value :rf/default))))
          (when rf.interop/debug-enabled?
            (is (empty? (of-op :rf.error/effect-map-shape traces)))))))))

;; ---- 7. :fx-overrides fn-value and fall-through ---------------------------

(deftest fx-overrides-fn-value-branch
  (testing "a fn-value override runs in place of the registered fx, with its args and fx ctx"
    (let [original-fired (atom 0)
          calls          (atom [])
          override       (fn [m args] (swap! calls conj [args (:frame m) (:event m)]))]
      (rf/reg-fx :fx-test/http (fn [_ _] (swap! original-fired inc)))
      (rf/reg-event :fx-test/issue-request
        (fn [_ _] {:fx [[:fx-test/http {:method :get :url "/me"}]]}))
      (let [{:keys [traces]} (observe #(rf/dispatch-sync
                                         [:fx-test/issue-request :payload-1]
                                         {:fx-overrides {:fx-test/http override}}))]
        (is (= 0 @original-fired))
        (is (= [[{:method :get :url "/me"} :rf/default [:fx-test/issue-request :payload-1]]]
               @calls))
        (when rf.interop/debug-enabled?
          (is (= [:fx-test/http]
                 (map #(get-in % [:tags :rf.fx/from])
                      (of-op :rf.fx/override-applied traces)))))))))

(deftest reserved-fx-fn-value-override-pre-empts-reserved-body
  (testing ":dispatch fn-value override fires in place of the reserved body"
    (let [calls      (atom [])
          target-ran (atom 0)]
      (rf/reg-event :fx-test.nrpj1/target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :fx-test.nrpj1/emits-dispatch
        (fn [_ _] {:fx [[:dispatch [:fx-test.nrpj1/target :payload]]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/emits-dispatch]
        {:fx-overrides {:dispatch (fn [m args] (swap! calls conj [args (:frame m)]))}})
      (is (= [[[:fx-test.nrpj1/target :payload] :rf/default]] @calls))
      (is (= 0 @target-ran) "the reserved :dispatch body did not run")))
  (testing ":dispatch-later fn-value override pre-empts the reserved body too"
    (let [later-args (atom nil)]
      (rf/reg-event :fx-test.nrpj1/emits-later
        (fn [_ _] {:fx [[:dispatch-later {:ms 50 :event [:fx-test.nrpj1/target]}]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/emits-later]
        {:fx-overrides {:dispatch-later (fn [_ args] (reset! later-args args))}})
      (is (= {:ms 50 :event [:fx-test.nrpj1/target]} @later-args)))))

(deftest reserved-fx-id-redirect-override-unchanged
  (testing "a keyword redirect TO an unregistered id (the reserved :dispatch lives
            outside the registrar) falls through to the original fx, loudly"
    (let [original-fired (atom 0)]
      (rf/reg-fx :fx-test.nrpj1/my-fx (fn [_ _] (swap! original-fired inc)))
      (rf/reg-event :fx-test.nrpj1/issue-redirect
        (fn [_ _] {:fx [[:fx-test.nrpj1/my-fx {}]]}))
      (let [{:keys [errors]} (observe #(rf/dispatch-sync
                                         [:fx-test.nrpj1/issue-redirect]
                                         {:fx-overrides {:fx-test.nrpj1/my-fx :dispatch}}))]
        (is (= 1 @original-fired))
        (is (= [{:failing-id :fx-test.nrpj1/my-fx
                 :event-id   :fx-test.nrpj1/issue-redirect
                 :frame      :rf/default}]
               (map #(select-keys % [:failing-id :event-id :frame])
                    (of-category :rf.error/override-fallthrough errors))))))))

(deftest malformed-fx-override-value-fails-loud
  ;; A map is callable but not a fn, so it pins the `fn?` (not `ifn?`) test.
  (doseq [bad-value [42 {:also :bad}]]
    (testing (pr-str bad-value)
      (let [original-fired (atom 0)]
        (rf/reg-fx :fx-test.3az1vn/target (fn [_ _] (swap! original-fired inc)))
        (rf/reg-event :fx-test.3az1vn/issue
          (fn [_ _] {:fx [[:fx-test.3az1vn/target {}]]}))
        (let [{:keys [errors]} (observe #(rf/dispatch-sync
                                           [:fx-test.3az1vn/issue]
                                           {:fx-overrides {:fx-test.3az1vn/target bad-value}}))]
          (is (= 1 @original-fired) "the original fx ran")
          (is (= [:fx-test.3az1vn/target]
                 (map :failing-id (of-category :rf.error/override-fallthrough errors)))
              "one always-on record names the overridden fx"))))))

(deftest nil-and-false-fx-override-values-stay-silent
  ;; The `{:some-fx (when cond? stub)}` idiom: nil/false is the no-op placeholder,
  ;; and must not spray records at an off-box shipper on every dispatch.
  (doseq [noop-value [nil false]]
    (testing (pr-str noop-value)
      (let [original-fired (atom 0)]
        (rf/reg-fx :fx-test.3az1vn/noop-target (fn [_ _] (swap! original-fired inc)))
        (rf/reg-event :fx-test.3az1vn/noop-issue
          (fn [_ _] {:fx [[:fx-test.3az1vn/noop-target {}]]}))
        (let [{:keys [errors]} (observe #(rf/dispatch-sync
                                           [:fx-test.3az1vn/noop-issue]
                                           {:fx-overrides {:fx-test.3az1vn/noop-target noop-value}}))]
          (is (= 1 @original-fired))
          (is (empty? (of-category :rf.error/override-fallthrough errors))))))))

;; ---- 7c. Reserved-fx REJECT tier ------------------------------------------
;;
;; A reserved fx whose body installs or clears durable frame runtime state
;; (`:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`,
;; `:rf.fx/clear-flow`, `:rf.route/with-nav-token`) rejects an override: it
;; emits `:rf.error/reserved-fx-override` and runs the real body. Three layers
;; enforce it — the dev per-call reject in `handle-one-fx`, the production
;; `strip-rejected-overrides`, and the cascade exclusion in `child-dispatch-opts`.

(deftest reject-tier-fn-value-override-ignored-reserved-body-runs
  (testing "a fn-value override of :rf.fx/reg-flow is ignored, loudly; the flow is registered"
    (let [stub-fired (atom 0)
          stub       (fn [_ _] (swap! stub-fired inc))
          flow       [:fx-test.snsup5/a-flow
                      {:inputs [[:fx-test.snsup5 :seed]] :output-path [:fx-test.snsup5 :out]}
                      (fn [_] 42)]]
      (rf/reg-event :fx-test.snsup5/install-flow
        (fn [_ _] {:fx [[:rf.fx/reg-flow flow]]}))
      (let [{:keys [errors traces]} (observe #(rf/dispatch-sync
                                                [:fx-test.snsup5/install-flow]
                                                {:fx-overrides {:rf.fx/reg-flow stub}}))]
        (is (= 0 @stub-fired))
        (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :fx-test.snsup5/a-flow))
        (is (= [{:failing-id :rf.fx/reg-flow
                 :event      [:fx-test.snsup5/install-flow]
                 :event-id   :fx-test.snsup5/install-flow
                 :frame      :rf/default}]
               (map #(select-keys % [:failing-id :event :event-id :frame])
                    (of-category :rf.error/reserved-fx-override errors))))
        (when rf.interop/debug-enabled?
          (is (= [:reserved-body-ran]
                 (map :recovery (of-op :rf.error/reserved-fx-override traces)))))))))

(deftest production-prod-strip-drops-reject-tier-loudly
  ;; The router calls the strip only when debug is off, so the dev lane reaches it here.
  (let [stub (fn [_ _] :stub)
        {:keys [result errors traces]}
        (observe #(rf.fx/strip-rejected-overrides
                    {:rf.machine/spawn        stub
                     :rf.fx/reg-flow          :some-redir
                     :rf.route/with-nav-token stub
                     :dispatch                stub
                     :my-app/http             stub}
                    :rf/default
                    [:some/event]))]
    (is (= #{:dispatch :my-app/http} (set (keys result)))
        "reject-tier keys are stripped; overridable and user keys survive")
    (is (= {:rf.machine/spawn 1 :rf.fx/reg-flow 1 :rf.route/with-nav-token 1}
           (frequencies (map :failing-id (of-category :rf.error/reserved-fx-override errors))))
        "one always-on record per stripped key")
    (when rf.interop/debug-enabled?
      (is (= [:production-strip :production-strip :production-strip]
             (map #(get-in % [:tags :where]) (of-op :rf.error/reserved-fx-override traces)))))))

(deftest reject-tier-nil-false-placeholder-stays-silent
  ;; nil/false is the no-op placeholder in the reject tier too: no spurious
  ;; always-on record per dispatched event, and the reserved body still runs.
  (doseq [noop-value [nil false]]
    (testing (pr-str noop-value)
      (rf.flows/reset-flows!)
      (let [flow [:fx-test.x76af2-27/flow
                  {:inputs [[:fx-test.x76af2-27 :seed]] :output-path [:fx-test.x76af2-27 :out]}
                  (fn [_] 1)]]
        (rf/reg-event :fx-test.x76af2-27/install-flow
          (fn [_ _] {:fx [[:rf.fx/reg-flow flow]]}))
        (let [{:keys [errors]} (observe #(rf/dispatch-sync
                                           [:fx-test.x76af2-27/install-flow]
                                           {:fx-overrides {:rf.fx/reg-flow noop-value}}))]
          (is (empty? (of-category :rf.error/reserved-fx-override errors)))
          (is (contains? (get (rf.flows/flows-snapshot) :rf/default)
                         :fx-test.x76af2-27/flow)))))))

(deftest cascade-exclusion-reject-tier-not-inherited
  (testing "a reject-tier override on the parent envelope is not inherited by a [:dispatch …] child"
    (let [child-stub (atom 0)
          stub       (fn [_ _] (swap! child-stub inc))]
      (rf/reg-event :fx-test.snsup5/child-installs-flow
        (fn [_ _] {:fx [[:rf.fx/reg-flow [:fx-test.snsup5/child-flow
                                          {:inputs      [[:fx-test.snsup5 :seed]]
                                           :output-path [:fx-test.snsup5 :child]}
                                          (fn [_] 7)]]]}))
      (rf/reg-event :fx-test.snsup5/parent-cascades
        (fn [_ _] {:fx [[:dispatch [:fx-test.snsup5/child-installs-flow]]]}))
      (let [{:keys [errors]} (observe #(rf/dispatch-sync
                                         [:fx-test.snsup5/parent-cascades]
                                         {:fx-overrides {:rf.fx/reg-flow stub}}))]
        (is (= 0 @child-stub))
        (is (contains? (get (rf.flows/flows-snapshot) :rf/default) :fx-test.snsup5/child-flow))
        ;; The production strip legitimately reports the PARENT's override; only a
        ;; CHILD-attributed record would mean the override was inherited.
        (is (empty? (filter #(= :fx-test.snsup5/child-installs-flow (:event-id %))
                            (of-category :rf.error/reserved-fx-override errors))))))))

;; ---- 7d. The SOURCE policy is the only policy -----------------------------

(deftest source-nonoverridable-is-redirectable-target
  (testing "a non-overridable SOURCE is still a redirectable TARGET — no target policy exists"
    (doseq [id [:rf.machine/spawn :rf.machine/destroy]]
      (is (= {:disposition :applied-redirect :target id}
             (rf.fx/classify-fx-override {:my/custom id} :my/custom))))))

;; ---- 7e. The reject diagnostic's REASON is id-specific --------------------

(defn- reject-reason-for
  "The always-on `:reason` the reject diagnostic emits for `fx-id`."
  [fx-id]
  (->> (observe #(rf.fx/strip-rejected-overrides {fx-id (fn [_ _] :stub)} :rf/default [:some/event]))
       :errors
       (of-category :rf.error/reserved-fx-override)
       first
       :reason))

(deftest reject-diagnostic-reason-is-id-specific
  (testing "every non-overridable source has its own rationale, not the generic fallback clause"
    (doseq [id [:rf.machine/spawn :rf.machine/destroy
                :rf.fx/reg-flow :rf.fx/clear-flow :rf.route/with-nav-token]]
      (is (not (re-find #"is a non-overridable SOURCE \(" (reject-reason-for id)))
          (str id)))))

;; ---- Trace stamps the Xray Event lens reads -------------------------------

(deftest ^:requires-debug event-do-fx-stamps-fx-and-db-present
  (testing ":rf.fx/do-fx reports the returned :fx vector and whether a :db slot came back"
    (rf/reg-fx :fx-test/do-fx-shape (fn [_ _] :ok))
    (doseq [[effects db-present?]
            [[{:db {:seeded? true} :fx [[:fx-test/do-fx-shape {:k 1}]]} true]
             [{:fx [[:fx-test/do-fx-shape {}]]} false]]]
      (rf/reg-event :fx-test/returns (fn [_ _] effects))
      (let [{:keys [traces]} (observe #(rf/dispatch-sync [:fx-test/returns]))]
        (is (= {:rf.event/fx (:fx effects) :rf.event/db-present? db-present?}
               (select-keys (:tags (first (of-op :rf.fx/do-fx traces)))
                            [:rf.event/fx :rf.event/db-present?])))))))

(deftest ^:requires-debug event-run-end-stamps-user-injected-coeffects-without-fx
  (testing "a handler that injects a cofx and returns only :db still stamps it on
            :rf.event/run-end — a :db-only return emits no :rf.fx/do-fx to carry it"
    (rf/reg-cofx :fx-test/now (fn [] "2026-05-18T19:00:00Z"))
    (rf/reg-event :fx-test/db-only-with-cofx
      {:rf.cofx/requires [:fx-test/now]}
      (fn [{:keys [fx-test/now]} _] {:db {:stamped-at now}}))
    (let [{:keys [traces]} (observe #(rf/dispatch-sync [:fx-test/db-only-with-cofx]))]
      (is (= {:fx-test/now "2026-05-18T19:00:00Z"}
             (get-in (first (of-op :rf.event/run-end traces)) [:tags :rf.event/coeffects]))))))

;; ---- reg-fx requires a handler --------------------------------------------

(deftest reg-fx-with-no-handler-is-registration-invalid
  ;; Fail at registration, not later as a misleading fire-time fx-handler-exception.
  (doseq [[label id register!]
          [["metadata only, the handler omitted"
            :fx-test.x76af2-26/no-handler
            #(rf/reg-fx :fx-test.x76af2-26/no-handler {:doc "typo — handler omitted"})]
           ["a non-callable handler — the guard is ifn?, not merely non-nil"
            :fx-test.x76af2-26/bad-handler
            #(rf/reg-fx :fx-test.x76af2-26/bad-handler 42)]]]
    (testing label
      (let [ex (try (register!) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:rf.error/id :rf.error/fx-registration-invalid :rf.fx/id id}
               (select-keys (ex-data ex) [:rf.error/id :rf.fx/id])))
        (is (nil? (rf.registrar/lookup :fx id)) "nothing was registered")))))
