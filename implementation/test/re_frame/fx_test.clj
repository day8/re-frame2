(ns re-frame.fx-test
  "Comprehensive edge-case coverage for the fx subsystem (rf2-mpug).

  Per-feature fx tests already live alongside their owners (the http
  stubs in smoke-test/login-machine-flow, the SSR fixtures, and the
  fx/db-first / fx/ordering-source-order / fx/override-by-id /
  fx/platforms conformance fixtures). This file consolidates the cross-
  cutting edge cases that are awkward to express in EDN fixtures:

    1. Source-order ordering across mixed effect types in one return.
    2. :fx-overrides precedence: per-call > per-frame > registered.
    3. fx-handler exception recovery is :isolated — sibling fx still fire.
    4. Missing fx-id emits :rf.error/no-such-fx and skips that entry.
    5. :platforms gating skips with :rf.fx/skipped-on-platform.
    6. Effect-map shape (M-8): legacy v1 top-level keys are policed.

  Per Spec 002 §`:fx` ordering and atomicity guarantees and Spec 009
  §Error contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!)
  ;; Framework registrations live at namespace-load time; clear-all!
  ;; wiped them. Reload so :rf/route, :rf.route/* subs and the framework
  ;; fx (e.g. :rf.fx/reg-flow) survive between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that
  accumulates events. Tests must (rf/remove-trace-cb! id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- 1. Source-order ordering across mixed effect types -------------------
;;
;; Per Spec 002 §`:fx` ordering and atomicity guarantees, the runtime
;; walks the `:fx` vector strictly in source order regardless of fx-id.
;; A handler that mixes registered fx and the reserved `:dispatch` fx
;; in a single `:fx` vector must observe each entry in declared order;
;; subsequent entries (queued events included) drain after the source-
;; order walk finishes.

(deftest source-order-across-mixed-fx
  (testing "an :fx vector mixing :a, :b, :c (registered fx) fires in declared order"
    (let [log (atom [])]
      (rf/reg-fx :fx-test/a (fn [_ _] (swap! log conj :a)))
      (rf/reg-fx :fx-test/b (fn [_ _] (swap! log conj :b)))
      (rf/reg-fx :fx-test/c (fn [_ _] (swap! log conj :c)))
      (rf/reg-event-fx :fx-test/run-mixed
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/a]
                [:fx-test/b]
                [:fx-test/c]]}))
      (rf/dispatch-sync [:fx-test/run-mixed])
      (is (= [:a :b :c] @log)
          "registered fx fire in source order")
      (is (= true (:seeded? (rf/get-frame-db :rf/default)))
          ":db committed before :fx walked (Spec 002 rule 1)")))

  (testing "interleaving :dispatch with registered fx preserves source order"
    ;; :dispatch enqueues; the queued event drains after the original
    ;; handler's :fx walk completes. The walk itself is in declared order;
    ;; the queued events arrive in declared order on the FIFO.
    (let [log (atom [])]
      (rf/reg-fx :fx-test/sync-a (fn [_ _] (swap! log conj :sync-a)))
      (rf/reg-fx :fx-test/sync-b (fn [_ _] (swap! log conj :sync-b)))
      (rf/reg-event-db :fx-test/queued-a
        (fn [db _] (swap! log conj :queued-a) db))
      (rf/reg-event-db :fx-test/queued-b
        (fn [db _] (swap! log conj :queued-b) db))
      (rf/reg-event-fx :fx-test/run-interleaved
        (fn [_ _]
          {:fx [[:fx-test/sync-a]
                [:dispatch [:fx-test/queued-a]]
                [:fx-test/sync-b]
                [:dispatch [:fx-test/queued-b]]]}))
      (rf/dispatch-sync [:fx-test/run-interleaved])
      ;; The synchronous fx in the original :fx walk run first in declared
      ;; order; queued :dispatch events drain afterwards in declared order.
      (is (= [:sync-a :sync-b :queued-a :queued-b] @log)
          "synchronous fx fire in source order; queued events follow in source order"))))

;; ---- 2. :fx-overrides precedence ------------------------------------------
;;
;; Per Spec 002 §Per-frame and per-call overrides and router.cljc's
;; (merge per-frame-fx per-call-fx): the per-call override map wins
;; over the per-frame override map, which wins over the registered fx.
;;
;; This test layers all three: a registered :fx-test/email fx, a per-
;; frame override redirecting to :fx-test/email.frame, and a per-call
;; override redirecting to :fx-test/email.call. The per-call route
;; should win.

(deftest fx-overrides-per-call-beats-per-frame-beats-registered
  (testing "per-call override > per-frame override > registered fx"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/email
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :registered)))
      (rf/reg-fx :fx-test/email.frame
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :per-frame)))
      (rf/reg-fx :fx-test/email.call
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :per-call)))
      (rf/reg-event-fx :fx-test/send
        (fn [_ _] {:fx [[:fx-test/email {:to "alice"}]]}))

      (testing "no overrides — registered fx fires"
        (reset! fired [])
        (let [f (rf/make-frame {})]
          (rf/dispatch-sync [:fx-test/send] {:frame f})
          (is (= [:registered] @fired))))

      (testing "per-frame override applied"
        (reset! fired [])
        (let [f (rf/make-frame
                  {:fx-overrides {:fx-test/email :fx-test/email.frame}})]
          (rf/dispatch-sync [:fx-test/send] {:frame f})
          (is (= [:per-frame] @fired))))

      (testing "per-call override beats per-frame"
        (reset! fired [])
        (let [f (rf/make-frame
                  {:fx-overrides {:fx-test/email :fx-test/email.frame}})]
          (rf/dispatch-sync
            [:fx-test/send]
            {:frame         f
             :fx-overrides  {:fx-test/email :fx-test/email.call}})
          (is (= [:per-call] @fired)
              "per-call override redirects past the per-frame override"))))))

;; ---- 3. fx-handler exception → :rf.error/fx-handler-exception -------------
;;
;; Per Spec 009 §Error contract, an fx implementation that throws emits
;; a structured :rf.error/fx-handler-exception trace. Per Spec 002
;; §`:fx` ordering rule 4 ("one bad fx does not halt the rest"), the
;; recovery is :isolated — the offending fx is skipped but sibling fx
;; in the same handler's :fx vector still run.

(deftest fx-handler-exception-is-isolated
  (testing "a throwing fx emits :rf.error/fx-handler-exception, sibling fx still fire"
    (let [traces (collect-traces! ::fx-exc)
          fired  (atom [])]
      (rf/reg-fx :fx-test/boom
        (fn [_ _] (throw (ex-info "kaboom" {:why :test}))))
      (rf/reg-fx :fx-test/after
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event-fx :fx-test/with-bad-fx
        (fn [_ _]
          {:fx [[:fx-test/boom  {:reason :first}]
                [:fx-test/after {:reason :sibling}]]}))
      (rf/dispatch-sync [:fx-test/with-bad-fx])
      (rf/remove-trace-cb! ::fx-exc)
      ;; Sibling fx ran — recovery is :isolated.
      (is (= [{:reason :sibling}] @fired)
          "the :fx after the throwing entry still fires (Spec 002 rule 4)")
      ;; The structured trace is present.
      (let [exc-traces (filter #(= :rf.error/fx-handler-exception (:operation %))
                               @traces)]
        (is (= 1 (count exc-traces))
            "exactly one :rf.error/fx-handler-exception was emitted")
        (let [t (first exc-traces)]
          (is (= :error (:op-type t)))
          (is (= :fx-test/boom (get-in t [:tags :fx-id])))
          (is (= :fx-test/boom (get-in t [:tags :failing-id])))
          (is (= {:reason :first} (get-in t [:tags :fx-args]))
              ":fx-args carry the offending args")
          (is (string? (get-in t [:tags :exception-message])))
          (is (some? (get-in t [:tags :exception]))))))))

;; ---- 4. Missing fx-id → :rf.error/no-such-fx ------------------------------
;;
;; Per Spec 009 §Error contract, an unknown fx-id in a returned :fx
;; entry emits :rf.error/no-such-fx. Per Spec 002, the walk continues
;; past the offending entry — recovery is :logged-and-skipped (the
;; runtime currently flags this with :recovery :no-recovery in the trace
;; tags, which is what the existing fx.cljc emits; the *behavioural*
;; contract is "skip and keep going" regardless of the recovery tag's
;; verb).

(deftest unknown-fx-id-is-logged-and-skipped
  (testing "a registered handler returning [:no-such-fx ...] traces and skips"
    (let [traces (collect-traces! ::no-such)
          fired  (atom [])]
      (rf/reg-fx :fx-test/sibling
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event-fx :fx-test/missing
        (fn [_ _]
          {:fx [[:fx-test/never-registered  {:k 1}]
                [:fx-test/sibling           {:k 2}]]}))
      (rf/dispatch-sync [:fx-test/missing])
      (rf/remove-trace-cb! ::no-such)
      ;; Sibling still fires — the unknown fx-id did not halt the walk.
      (is (= [{:k 2}] @fired)
          "the next :fx entry still fires after an unknown fx-id")
      (let [missing-traces (filter #(= :rf.error/no-such-fx (:operation %))
                                   @traces)]
        (is (= 1 (count missing-traces))
            "exactly one :rf.error/no-such-fx trace was emitted")
        (let [t (first missing-traces)]
          (is (= :error (:op-type t)))
          (is (= :fx-test/never-registered (get-in t [:tags :fx-id])))
          (is (= :rf/default (get-in t [:tags :frame]))))))))

;; ---- 5. :platforms gating -------------------------------------------------
;;
;; Per Spec 011 §`:platforms` metadata on reg-fx, an fx with :platforms
;; #{:client} is gated off when the active platform is :server. The
;; runtime emits :rf.fx/skipped-on-platform with :recovery :skipped
;; instead of invoking the handler. JVM hosts default to :server (per
;; re-frame.interop/platform), so :client-only fx are silently inert
;; under JVM tests — exactly what we need for headless mode.

(deftest platforms-gating-skips-client-only-fx-on-jvm
  (testing ":platforms #{:client} fx is skipped on JVM (:server) and emits a trace"
    (let [traces (collect-traces! ::plat)
          fired? (atom false)]
      (rf/reg-fx :fx-test/local-storage
        {:platforms #{:client}}
        (fn [_ _] (reset! fired? true)))
      (rf/reg-event-fx :fx-test/save
        (fn [_ _] {:fx [[:fx-test/local-storage {:k "key" :v "val"}]]}))
      (rf/dispatch-sync [:fx-test/save])
      (rf/remove-trace-cb! ::plat)
      (is (false? @fired?)
          "the client-only handler did NOT run on the JVM (:server)")
      (let [skip-traces (filter #(= :rf.fx/skipped-on-platform (:operation %))
                                @traces)]
        (is (= 1 (count skip-traces))
            "exactly one :rf.fx/skipped-on-platform trace was emitted")
        (let [t (first skip-traces)]
          (is (= :warning (:op-type t)))
          (is (= :skipped (:recovery t)))
          (is (= :fx-test/local-storage (get-in t [:tags :fx-id])))
          (is (= :server (get-in t [:tags :platform])))
          (is (= #{:client} (get-in t [:tags :registered-platforms]))))))))

;; ---- 6. Effect-map shape policing (M-8) -----------------------------------
;;
;; Per docs/specification/MIGRATION.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect map is CLOSED: only :db and :fx live at the top level. A handler
;; returning a legacy v1 key (e.g. :dispatch / :dispatch-later / :dispatch-n /
;; :http at the top level) MUST raise a structured trace per Spec 009 §Error
;; contract; the runtime does NOT silently drop and does NOT silently route
;; the legacy key through the fx machinery.
;;
;; Enforcement landed in rf2-ooc5: re-frame.events/fx-handler->interceptor
;; calls re-frame.events/police-effect-map-shape! on every effect-map
;; returned, emitting :rf.error/effect-map-shape per offending top-level key
;; with :recovery :logged-and-skipped. The offending keys are dropped from
;; the threaded effects map.

(deftest legacy-effect-map-key-is-policed
  (testing "a handler returning {:dispatch ...} (legacy v1 top-level key) is policed"
    (let [traces (collect-traces! ::shape)
          fired? (atom false)]
      ;; Sentinel: if the runtime accidentally routes the legacy
      ;; top-level :dispatch through the FIFO, this would set fired?
      ;; — the M-8 contract says it must NOT.
      (rf/reg-event-db :fx-test/sentinel
        (fn [db _] (reset! fired? true) db))
      (rf/reg-event-fx :fx-test/legacy-dispatch
        (fn [_ _]
          ;; Legacy v1 shape — top-level :dispatch.
          {:dispatch [:fx-test/sentinel]}))
      (rf/dispatch-sync [:fx-test/legacy-dispatch])
      (rf/remove-trace-cb! ::shape)
      ;; The legacy top-level :dispatch is NOT performed (silently
      ;; routing it would defeat the M-8 migration).
      (is (false? @fired?)
          "the legacy top-level :dispatch must NOT silently dispatch the event")
      ;; A structured :rf.error/effect-map-shape trace is emitted (Spec
      ;; 009 §Error contract).
      (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                 @traces)]
        (is (= 1 (count shape-traces))
            "exactly one :rf.error/effect-map-shape trace was emitted")
        (let [t (first shape-traces)]
          (is (= :error (:op-type t)))
          (is (= :logged-and-skipped (:recovery t)))
          (is (= :dispatch (get-in t [:tags :offending-key]))
              ":offending-key carries the legacy top-level key")
          (is (= :fx-test/legacy-dispatch (get-in t [:tags :event-id]))
              ":event-id carries the dispatching event id")
          (is (= [:fx-test/sentinel] (get-in t [:tags :value]))
              ":value carries the offending key's value")
          (is (string? (get-in t [:tags :reason]))
              ":reason is a one-sentence human-facing description"))))))

(deftest multiple-legacy-effect-map-keys-each-emit-a-trace
  (testing "an effect map with several legacy top-level keys traces each one and still applies :db / :fx"
    (let [traces (collect-traces! ::shape-multi)
          fired  (atom [])]
      (rf/reg-fx :fx-test/sibling
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event-fx :fx-test/multi-legacy
        (fn [{:keys [db]} _]
          ;; Mixed: legal :db and :fx alongside two legacy keys.
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/sibling {:k :legit}]]
           :dispatch [:fx-test/never-runs]
           :http {:url "/api"}}))
      (rf/dispatch-sync [:fx-test/multi-legacy])
      (rf/remove-trace-cb! ::shape-multi)
      ;; The legitimate :fx entry still ran — policing didn't halt the cascade.
      (is (= [{:k :legit}] @fired)
          "the legal :fx entry still fires after policing rejects sibling top-level keys")
      ;; The legitimate :db still committed.
      (is (= true (:seeded? (rf/get-frame-db :rf/default)))
          ":db still committed alongside policed top-level keys")
      ;; One trace per offending key.
      (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                 @traces)
            offending    (set (map #(get-in % [:tags :offending-key]) shape-traces))]
        (is (= 2 (count shape-traces))
            "one :rf.error/effect-map-shape trace per offending top-level key")
        (is (= #{:dispatch :http} offending)
            "both legacy keys are flagged")))))
