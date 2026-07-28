(ns re-frame.fx-aggregate-classification-cljs-test
  "rf2-32ffq1 — two classification-projector gaps at the fx-arg-bearing trace
  slots (`:rf.event/fx` on `:rf.fx/do-fx` + every `[:rf.fx/id :rf.fx/args]`-
  shaped slot), both pre-dating PR #5687 and unreachable by any app-side
  classification:

    GAP (a) — a `[:dispatch [target-event …]]` (or `:dispatch-later`) fx entry
    NESTED inside another handler's `:fx` vector did not inherit the TARGET
    event's own `:sensitive` registration at the DISPATCHING handler's trace.
    `:dispatch` (a reserved fx) declares no fx classification, so the rf2-6h3c02
    per-entry walk found nothing — the classified target's raw payload shipped
    at the parent's `:rf.event/fx` aggregate and `:dispatch` `:rf.fx/handled`
    slots even though the target's own `:rf.event/v` redacted correctly.

    GAP (b) — the same walker only understood STATIC per-fx `:sensitive`
    paths; it had no awareness of `:rf.http/managed`'s DYNAMIC privacy model
    (the per-call `:sensitive?` flag inside the args map,
    `re-frame.http.privacy/request-sensitive?`), which the dedicated
    `:rf.http/*` trace ops honour. A handler returning a `:sensitive?`-flagged
    managed request leaked the raw request body at its own `:rf.event/fx`
    aggregate. Closed via the `:http/project-managed-fx-args` late-bind hook
    (http publishes the SAME redaction its dedicated composers run; core stays
    decoupled).

  Both fixes route through ONE chokepoint
  (`re-frame.classification/project-fx-args`), so the deterministic teeth here
  drive `project-trace-event` directly on hand-built trace shapes (mirroring
  machine_routed_event_classification_cljs_test) and the live round-trips prove
  handlers / fx bodies still read RAW values (classification is egress-only).

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`, `cljs-test$` ns-regexp) AND the JVM `clojure -M:test`
  runner both run it (http + machines ride core's test-only classpath).

  ## Posture split (rf2-d2841)

  Section A is the chokepoint under a microscope — `project-trace-event` driven
  on hand-built shapes — and needs no trace stream at all, so ALL of it runs
  under `scripts/test-core-prod-gate.sh` unchanged. That is where the teeth are.

  Section B's live round-trips read the DEV TRACE stream, which emits nothing
  under `-Dre-frame.debug=false`. Their trace-reading steps are kept verbatim
  inside `(when interop/debug-enabled? …)` arms — INCLUDING the two closing
  whole-stream sweeps. Those sweeps are the reason the arm wraps the trace half
  as a block: `(is (not (some #(leaks? pw-sentinel %) @traces)))` over an EMPTY
  `@traces` is a redaction suite reporting green for having emitted nothing,
  the exact false-green shape rf2-d2841's third pass found in the two
  `machine-*-classification` suites.

  What stays OUTSIDE the arm is each round-trip's step 1 — the handler / fx
  body receiving the RAW value. Redaction here is EGRESS-ONLY, so that step is
  posture-independent, it is the half of the contract a production build
  actually executes, and without it \"the sentinel appears nowhere\" would be
  satisfied by a cascade that never carried the sentinel in the first place."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as classification]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Boot the optional http artefact so the
            ;; `:http/project-managed-fx-args` hook is bound (published at
            ;; `re-frame.http.managed` load — the artefact's load-time anchor).
            [re-frame.http.managed]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The reset fixture auto-registers + scope-pins a default frame (the ambient
;; scope a bare `dispatch-sync` cascades into).
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; UNIQUE sentinels that must never appear raw in any projected trace slot.
(def ^:private pw-sentinel "rf2-32ffq1-PW-9e5b27")
(def ^:private email "user@example.test")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- project [ev] (:tags (classification/project-trace-event ev)))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

;; The classified TARGET event — `:sensitive` rooted at its arg-map, exactly
;; the declaration that already redacts the target's own `:rf.event/v`.
(defn- register-target-classification! []
  (registrar/register! :event ::target {:sensitive [[:secret]]}))

;; =====================================================================
;; A. Deterministic projector teeth — hand-built trace shapes.
;; =====================================================================

(deftest aggregate-nested-dispatch-inherits-target-classification
  (testing "GAP (a): a [:dispatch [classified-target …]] entry in the
            :rf.event/fx aggregate redacts the TARGET's declared arg-map path;
            the non-secret sibling field and the fx-id survive"
    (register-target-classification!)
    (let [ev {:operation :rf.fx/do-fx
              :tags {:frame        :rf/default
                     :rf.event/fx [[:dispatch [::target {:secret pw-sentinel
                                                         :email  email}]]]}}
          t  (project ev)]
      (is (= privacy/redacted-sentinel
             (get-in t [:rf.event/fx 0 1 1 :secret]))
          "the nested dispatch's target payload :secret reads :rf/redacted")
      (is (= email (get-in t [:rf.event/fx 0 1 1 :email]))
          "the non-secret :email survives (path-precise, not whole-event)")
      (is (= :dispatch (get-in t [:rf.event/fx 0 0]))
          "shape retained — the fx-id survives")
      (is (not (leaks? pw-sentinel t))
          "the secret appears nowhere in the projected do-fx trace"))))

(deftest aggregate-dispatch-later-inherits-target-classification
  (testing "GAP (a): a [:dispatch-later {:ms … :event [classified-target …]}]
            entry redacts the carried TARGET event's payload too"
    (register-target-classification!)
    (let [ev {:operation :rf.fx/do-fx
              :tags {:frame        :rf/default
                     :rf.event/fx [[:dispatch-later
                                    {:ms    500
                                     :event [::target {:secret pw-sentinel}]}]]}}
          t  (project ev)]
      (is (= privacy/redacted-sentinel
             (get-in t [:rf.event/fx 0 1 :event 1 :secret]))
          "the deferred target payload :secret reads :rf/redacted")
      (is (= 500 (get-in t [:rf.event/fx 0 1 :ms]))
          "shape retained — :ms survives")
      (is (not (leaks? pw-sentinel t))))))

(deftest handled-slot-for-dispatch-inherits-target-classification
  (testing "GAP (a): the :dispatch-specific [:rf.fx/id :rf.fx/args] slot
            (:rf.fx/handled and the fx error traces share the shape) redacts
            the TARGET event's payload"
    (register-target-classification!)
    (doseq [op [:rf.fx/handled :rf.error/fx-handler-exception]]
      (let [t (project {:operation op
                        :tags {:frame      :rf/default
                               :rf.fx/id   :dispatch
                               :rf.fx/args [::target {:secret pw-sentinel}]}})]
        (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args 1 :secret]))
            (str op " :rf.fx/args target payload redacts"))
        (is (not (leaks? pw-sentinel t)) (str op " leaks no secret"))))))

(deftest aggregate-managed-http-honours-dynamic-sensitive-flag
  (testing "GAP (b): a :rf.http/managed entry flagged :sensitive? true redacts
            its request :body in the :rf.event/fx aggregate (mirroring the
            dedicated :rf.http/* composers), and a classified :on-success
            reply-address payload rides the target's own classification"
    (register-target-classification!)
    (let [args {:request    {:method :post
                             :url    "https://api.example.test/login"
                             :body   {:password pw-sentinel :email email}}
                :sensitive? true
                :decode     :json
                :on-success [::target {:secret pw-sentinel}]
                :on-failure [::noop]}
          t    (project {:operation :rf.fx/do-fx
                         :tags {:frame        :rf/default
                                :rf.event/fx [[:rf.http/managed args]]}})
          out  (get-in t [:rf.event/fx 0 1])]
      (is (= privacy/redacted-sentinel (get-in out [:request :body]))
          "the sensitive request's whole :body reads :rf/redacted")
      (is (= "https://api.example.test/login" (get-in out [:request :url]))
          "shape retained — the (query-free) url survives")
      (is (= privacy/redacted-sentinel (get-in out [:on-success 1 :secret]))
          "the classified :on-success reply-address payload redacts too")
      (is (= [::noop] (:on-failure out))
          "a bare reply address rides through untouched")
      (is (not (leaks? pw-sentinel t))
          "the password appears nowhere in the projected do-fx trace"))))

(deftest aggregate-managed-http-not-sensitive-stays-fail-open
  (testing "precision: a NON-sensitive managed request's body rides raw in the
            aggregate (the documented fail-open — no reflexive over-redaction),
            while denylisted headers still redact unconditionally"
    (let [t (project {:operation :rf.fx/do-fx
                      :tags {:frame        :rf/default
                             :rf.event/fx [[:rf.http/managed
                                            {:request {:method  :get
                                                       :url     "https://api.example.test/user"
                                                       :headers {"Authorization" pw-sentinel
                                                                 "Accept"        "application/json"}
                                                       :body    {:note pw-sentinel}}}]]}})
          req (get-in t [:rf.event/fx 0 1 :request])]
      (is (= pw-sentinel (get-in req [:body :note]))
          "an unflagged request body rides raw — per-call :sensitive? is the signal")
      (is (not= pw-sentinel (get-in req [:headers "Authorization"]))
          "…but the denylisted Authorization header redacts regardless")
      (is (= "application/json" (get-in req [:headers "Accept"]))
          "non-denylisted headers survive"))))

;; =====================================================================
;; B. Live round-trips — dispatch through the real drain; handlers and fx
;;    bodies read RAW values while every emitted trace slot redacts.
;; =====================================================================

(deftest live-nested-dispatch-redacts-at-parent-and-target-slots
  (testing "GAP (a) acceptance: event B returns {:fx [[:dispatch [A {…}]]]}
            with A classified — A's handler reads the RAW secret; B's
            :rf.event/fx aggregate, the :dispatch :rf.fx/handled slot, AND A's
            own :rf.event/v all redact"
    (let [captured (atom ::none)]
      (rf/reg-event ::target
        {:sensitive [[:secret]]}
        (fn [{:keys [db]} [_ {:keys [secret]}]]
          (reset! captured secret)
          {:db db}))
      (rf/reg-event ::parent
        (fn [_ _]
          {:fx [[:dispatch [::target {:secret pw-sentinel :email email}]]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::parent])
        (rf/unregister-listener! :trace ::probe)

        ;; 1. control flow untouched — the target handler read the raw secret.
        ;;    ALWAYS-ON (rf2-d2841): egress-only redaction, and the proof that
        ;;    the secret was ever in flight.
        (is (= pw-sentinel @captured)
            "the target handler received the RAW secret (egress-only redaction)")

       ;; rf2-d2841 — steps 2-5 read the dev trace stream; step 5's sweep would
       ;; certify "no leak" over an empty `@traces`. Kept verbatim in the arm.
       (when interop/debug-enabled?
        ;; 2. the parent's :rf.event/fx aggregate redacts the nested payload.
        (let [entries (for [ev    @traces
                            :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                            :when (vector? fx-vec)
                            [id args] fx-vec
                            :when (= :dispatch id)]
                        args)]
          (is (seq entries) "the do-fx aggregate carried the :dispatch entry")
          (doseq [args entries]
            (is (= privacy/redacted-sentinel (get-in args [1 :secret]))
                "the nested target payload :secret redacts in :rf.event/fx")
            (is (= email (get-in args [1 :email]))
                "the non-secret :email survives in :rf.event/fx")))

        ;; 3. the :dispatch :rf.fx/handled slot redacts too.
        (let [handled (filter #(= :dispatch (get-in % [:tags :rf.fx/id])) @traces)]
          (is (seq handled) ":dispatch emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args 1 :secret]))
                "the :dispatch :rf.fx/args target payload redacts")))

        ;; 4. the target's OWN dispatched-event trace still redacts (the pin —
        ;;    this always worked; the gap was the PARENT's view).
        (let [vs (->> @traces
                      (keep #(get-in % [:tags :rf.event/v]))
                      (filter #(= ::target (first %))))]
          (is (seq vs) "the target's own dispatched-event trace surfaced")
          (doseq [v vs]
            (is (= privacy/redacted-sentinel (get-in v [1 :secret]))
                "A's own :rf.event/v redacts (unchanged behaviour)")))

        ;; 5. the whole-stream sweep — the secret appears NOWHERE.
        (is (not (some #(leaks? pw-sentinel %) @traces))
            "no emitted trace event leaks the secret sentinel"))))))

(deftest live-managed-http-dynamic-flag-redacts-in-aggregate
  (testing "GAP (b) acceptance: a handler combining [:dispatch [bare]] with a
            :sensitive?-flagged :rf.http/managed request — the fx body receives
            the RAW body; the :rf.event/fx aggregate and the managed
            :rf.fx/handled slot both redact it"
    (let [http (atom [])]
      (rf/reg-event ::bare (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event ::issue
        (fn [_ _]
          {:fx [[:dispatch [::bare]]
                [:rf.http/managed
                 {:request    {:method :post
                               :url    "https://api.example.test/login"
                               :body   {:password pw-sentinel :email email}
                               :request-content-type :json
                               :sensitive? true}
                  :decode     :json
                  :on-success [::bare]
                  :on-failure [::bare]}]]}))
      (let [traces (record-traces!)]
        ;; A fn-value override neutralises the real transport (no network) but
        ;; keeps the ORIGINAL fx-id on both the aggregate and the handled slot,
        ;; so the trace pipeline under test runs unchanged.
        (rf/dispatch-sync [::issue]
                          {:fx-overrides {:rf.http/managed
                                          (fn [_ args] (swap! http conj args))}})
        (rf/unregister-listener! :trace ::probe)

        ;; 1. the fx body received the RAW body (egress-only redaction).
        ;;    ALWAYS-ON (rf2-d2841) — see step 1 of the deftest above.
        (is (= pw-sentinel (get-in (first @http) [:request :body :password]))
            "the managed fx received the RAW password")

       ;; rf2-d2841 — steps 2-4 read the dev trace stream; step 4's sweep would
       ;; certify "no leak" over an empty `@traces`. Kept verbatim in the arm.
       (when interop/debug-enabled?
        ;; 2. the :rf.event/fx aggregate redacts the managed entry's body.
        (let [entries (for [ev    @traces
                            :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                            :when (vector? fx-vec)
                            [id args] fx-vec
                            :when (= :rf.http/managed id)]
                        args)]
          (is (seq entries) "the do-fx aggregate carried the managed entry")
          (doseq [args entries]
            (is (= privacy/redacted-sentinel (get-in args [:request :body]))
                "the sensitive request's whole :body redacts in :rf.event/fx")
            (is (= "https://api.example.test/login" (get-in args [:request :url]))
                "shape retained — the url survives")))

        ;; 3. the managed [:rf.fx/id :rf.fx/args] slot redacts too.
        (let [handled (filter #(= :rf.http/managed (get-in % [:tags :rf.fx/id]))
                              @traces)]
          (is (seq handled) "the managed fx emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args :request :body]))
                "the managed :rf.fx/args request body redacts")))

        ;; 4. whole-stream sweep.
        (is (not (some #(leaks? pw-sentinel %) @traces))
            "no emitted trace event leaks the password sentinel"))))))
