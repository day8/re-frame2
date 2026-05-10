(ns re-frame.schemas-test
  "JVM smoke tests for Spec 010 — Schemas (Malli runtime validation).

  Two surfaces here:

    1. **Elision toggle**. In dev builds (per Spec 010 §Dev builds) all
       registered schemas are checked at every validation point. In
       production builds (per Spec 010 §Production builds) validation is
       compile-time-elided via a host gate — `goog.DEBUG` on CLJS, the
       JVM mirror `re-frame.interop/debug-enabled?` here. These tests
       flip the gate via `with-redefs` and assert the dev-mode trace
       fires while the prod-mode call is silent.

    2. **Error projector → :rf/public-error mapping**. Per Spec 010 + 011
       §Default projector, the runtime ships a default projector mapping
       internal trace events to the locked four-key public-error shape
       (`:status :code :message :retryable?`). The schema-validation
       failure category maps to a 400 :bad-request. The default-projector
       fn proper isn't yet wired into the registrar (tracked as rf2-6528);
       we test the *mapping contract* here as a pure fn so the contract
       is locked even before the registry-side wiring lands.

  These tests exercise schemas on the JVM via the plain-atom adapter —
  the conformance fixtures cover the dispatch-time integration; this
  file covers the elision toggle (which fixtures cannot flip from EDN)
  and the projector-mapping shape (which is a separate surface from the
  trace emission)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.flows :as flows]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- elision toggle -------------------------------------------------------

(deftest app-db-validation-fires-when-debug-enabled
  (testing "validate-app-db! emits :rf.error/schema-validation-failure when debug-enabled? is true"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::dev (fn [ev] (swap! traces conj ev)))
      ;; Dev mode is the JVM default; validate-app-db! should walk the
      ;; registered schemas and emit on a malformed value.
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/remove-trace-cb! ::dev)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one schema-validation-failure trace fired")
        (let [v (first violations)]
          (is (= :app-db (-> v :tags :where)))
          (is (= [:count] (-> v :tags :path)))
          (is (= "not-an-int" (-> v :tags :value)))
          (is (= :test/handler (-> v :tags :failing-id))))))))

(deftest app-db-validation-elides-when-debug-disabled
  (testing "validate-app-db! is a no-op when debug-enabled? is false (production)"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::prod (fn [ev] (swap! traces conj ev)))
      ;; Production mode — the validation site elides; even with a
      ;; malformed value, no trace fires.
      (with-redefs [interop/debug-enabled? false]
        (schemas/validate-app-db! {:count "not-an-int"} :test/handler))
      (rf/remove-trace-cb! ::prod)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "no schema-validation-failure trace when validation is elided"))))

(deftest well-typed-value-passes-silently
  (testing "validate-app-db! with a conforming value emits no trace"
    (rf/reg-app-schema [:count] [:int])
    (let [traces (atom [])]
      (rf/register-trace-cb! ::ok (fn [ev] (swap! traces conj ev)))
      (with-redefs [interop/debug-enabled? true]
        (schemas/validate-app-db! {:count 42} :test/handler))
      (rf/remove-trace-cb! ::ok)
      (is (empty? (filter #(= :rf.error/schema-validation-failure
                              (:operation %))
                          @traces))
          "well-typed value triggers no validation-failure trace"))))

(deftest dispatch-fires-app-db-validation
  (testing "live dispatch through the runtime triggers app-db validation post-:db commit"
    (rf/reg-app-schema [:n] [:int])
    (rf/reg-event-db :n/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "boom")))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::live (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/init])
      (rf/dispatch-sync [:n/break])
      (rf/remove-trace-cb! ::live)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the malformed :n/break commit fires exactly one schema trace")
        (is (= :n/break (-> violations first :tags :failing-id))
            ":failing-id names the handler whose commit prompted the failure")))))

;; ---- rf2-jwm4 — event-payload validation ---------------------------------

(deftest dispatch-validates-event-payload-pre-handler
  (testing "Per Spec 010 §step 1 (rf2-jwm4): a malformed event vector fires
            :rf.error/schema-validation-failure :where :event before the
            handler runs; the handler is NOT invoked"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/register
        {:spec [:cat [:= :user/register]
                     [:map [:email :string] [:age :int]]]}
        (fn [db [_ payload]]
          (swap! calls inc)
          (update db :users (fnil conj []) payload)))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::ev (fn [ev] (swap! traces conj ev)))
        ;; Well-typed payload — passes; handler runs.
        (rf/dispatch-sync [:user/register {:email "alice@example.com" :age 30}])
        ;; Malformed payload — fails; handler must NOT run.
        (rf/dispatch-sync [:user/register {:email "carol@example.com" :age "no"}])
        (rf/remove-trace-cb! ::ev)
        (is (= 1 @calls)
            "handler ran exactly once — once for the well-typed payload, skipped for the bad one")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :event (-> v :tags :where)))
            (is (= :user/register (-> v :tags :failing-id)))
            (is (= :user/register (-> v :tags :spec-id)))))))))

(deftest event-payload-validation-elides-when-debug-disabled
  (testing "validate-event! is a no-op when debug-enabled? is false (production)"
    (let [calls (atom 0)]
      (rf/reg-event-db :user/strict
        {:spec [:cat [:= :user/strict] :int]}
        (fn [db _] (swap! calls inc) db))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::ev2 (fn [ev] (swap! traces conj ev)))
        (with-redefs [interop/debug-enabled? false]
          (rf/dispatch-sync [:user/strict "not-an-int"]))
        (rf/remove-trace-cb! ::ev2)
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no validation trace when debug-enabled? is false")
        (is (= 1 @calls)
            "handler runs anyway — production validation is elided")))))

;; ---- rf2-wcam — sub-return validation ------------------------------------

(deftest sub-return-validation-fires-and-replaces-with-default
  (testing "Per Spec 010 §step 6 (rf2-wcam): a sub whose return value fails
            its :spec emits :rf.error/schema-validation-failure :where :sub-return
            and the caller sees nil (default :replaced-with-default recovery)"
    (rf/reg-event-db :items/init (fn [_ _] {:items ["a" "b" "c"]}))
    (rf/reg-event-db :items/break (fn [db _] (assoc db :items [1 2 3])))
    (rf/reg-sub :items
      {:spec [:vector :string]}
      (fn [db _] (:items db)))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::sr (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:items/init])
      ;; Well-typed: sub returns the vec.
      (is (= ["a" "b" "c"] (rf/subscribe-value [:items])))
      (rf/dispatch-sync [:items/break])
      ;; Malformed: sub yields nil per :replaced-with-default recovery.
      (is (nil? (rf/subscribe-value [:items])))
      (rf/remove-trace-cb! ::sr)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (pos? (count violations))
            "at least one sub-return validation failure fired")
        (let [v (first violations)]
          (is (= :sub-return (-> v :tags :where)))
          (is (= :items (-> v :tags :sub-id)))
          (is (= :items (-> v :tags :spec-id)))
          (is (= :replaced-with-default (:recovery v))))))))

(deftest compute-sub-validates-return-value
  (testing "compute-sub validates the return against :spec — the pure
            test-time path mirrors the live reactive path"
    (rf/reg-sub :nums
      {:spec [:vector :int]}
      (fn [db _] (:nums db)))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::cs (fn [ev] (swap! traces conj ev)))
      (is (= [1 2 3] (#'re-frame.subs/compute-sub [:nums] {:nums [1 2 3]})))
      (is (nil? (#'re-frame.subs/compute-sub [:nums] {:nums ["bad"]}))
          "compute-sub yields nil on validation failure")
      (rf/remove-trace-cb! ::cs)
      (let [violations (filter #(= :rf.error/schema-validation-failure
                                   (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "exactly one trace from the malformed compute-sub call")))))

;; ---- rf2-7leq — cofx validation ------------------------------------------

(deftest cofx-validation-fires-and-skips-handler
  (testing "Per Spec 010 §step 2 (rf2-7leq): a cofx whose injected value
            fails its :spec emits :rf.error/schema-validation-failure
            :where :cofx and the handler is NOT invoked"
    (rf/reg-cofx :app-version/bad
      {:spec :string}
      (fn [ctx]
        (assoc-in ctx [:coeffects :app-version/bad] 42)))
    (let [calls (atom 0)]
      (rf/reg-event-fx :cap/seed
        [(rf/inject-cofx :app-version/bad)]
        (fn [_cofx _]
          (swap! calls inc)
          {:db {:app-version "should-not-stash"}}))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::cf (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:cap/seed])
        (rf/remove-trace-cb! ::cf)
        (is (= 0 @calls)
            "handler was skipped because the cofx :spec failed")
        (let [violations (filter #(= :rf.error/schema-validation-failure
                                     (:operation %))
                                 @traces)]
          (is (= 1 (count violations)))
          (let [v (first violations)]
            (is (= :cofx (-> v :tags :where)))
            (is (= :cap/seed (-> v :tags :failing-id)))
            (is (= :app-version/bad (-> v :tags :cofx-id)))
            (is (= :app-version/bad (-> v :tags :spec-id)))
            (is (= :no-recovery (:recovery v)))))))))

(deftest cofx-validation-passes-when-conforming
  (testing "well-typed cofx values flow through to the handler — no trace, handler runs"
    (rf/reg-cofx :app-version/well
      {:spec :string}
      (fn [ctx]
        (assoc-in ctx [:coeffects :app-version/well] "1.4.5")))
    (let [seen-version (atom nil)]
      (rf/reg-event-fx :cap/seed-good
        [(rf/inject-cofx :app-version/well)]
        (fn [cofx _]
          (reset! seen-version (:app-version/well cofx))
          {}))
      (let [traces (atom [])]
        (rf/register-trace-cb! ::cf2 (fn [ev] (swap! traces conj ev)))
        (rf/dispatch-sync [:cap/seed-good])
        (rf/remove-trace-cb! ::cf2)
        (is (= "1.4.5" @seen-version)
            "handler ran and saw the well-typed cofx value")
        (is (empty? (filter #(= :rf.error/schema-validation-failure
                                (:operation %))
                            @traces))
            "no schema-validation-failure trace fires for a conforming cofx")))))

;; ---- error projector → :rf/public-error mapping --------------------------

(defn- default-error-projector
  "The default projector contract per Spec 011 §Default projector. The
  runtime-resident registry-backed projector isn't yet wired (rf2-6528),
  but the mapping is locked. This fn implements the table verbatim so
  these tests pin the mapping contract.

  Returns the locked four-key public-error shape:
    {:status :code :message :retryable?}

  In dev mode (:dev-error-detail? true) the public shape carries an
  additional :details key with the original trace event."
  ([trace-event] (default-error-projector trace-event {}))
  ([trace-event {:keys [dev-error-detail?]}]
   (let [base (case (:operation trace-event)
                :rf.error/no-such-handler
                {:status 404 :code :not-found
                 :message "Page not found" :retryable? false}

                :rf.error/schema-validation-failure
                {:status 400 :code :bad-request
                 :message "Invalid input" :retryable? false}

                :rf.error/handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/sub-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/fx-handler-exception
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                :rf.error/drain-depth-exceeded
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false}

                ;; default — generic 500
                {:status 500 :code :internal-error
                 :message "Something went wrong" :retryable? false})]
     (cond-> base
       dev-error-detail? (assoc :details trace-event)))))

(deftest projector-maps-schema-failure-to-bad-request
  (testing "schema-validation-failure projects to a locked 400 :bad-request"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :op-type   :error
                       :tags      {:where :event :event-id :user/register
                                   :event [:user/register {:age "no"}]}
                       :recovery  :no-recovery}
          public      (default-error-projector trace-event)]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (= "Invalid input" (:message public)))
      (is (false? (:retryable? public))
          "schema validation failure is NOT retryable — the input is the bug")
      (is (= #{:status :code :message :retryable?} (set (keys public)))
          "prod-mode public shape is the locked four keys only — no :details leak"))))

(deftest projector-includes-details-in-dev
  (testing "with :dev-error-detail? true the public shape carries the original trace under :details"
    (let [trace-event {:operation :rf.error/schema-validation-failure
                       :tags      {:where :app-db :path [:user] :value "bad"}}
          public      (default-error-projector trace-event {:dev-error-detail? true})]
      (is (= 400 (:status public)))
      (is (= :bad-request (:code public)))
      (is (contains? public :details)
          ":details carries the original trace event in dev mode")
      (is (= trace-event (:details public))
          ":details is the trace event verbatim — full internal detail"))))

(deftest projector-falls-back-to-generic-500
  (testing "an unknown error category projects to the locked generic-500 shape"
    (let [trace-event {:operation :rf.error/something-unmapped
                       :tags      {}}
          public      (default-error-projector trace-event)]
      (is (= 500 (:status public)))
      (is (= :internal-error (:code public)))
      (is (= "Something went wrong" (:message public)))
      (is (false? (:retryable? public))))))

(deftest projector-maps-no-such-handler-to-404
  (testing ":rf.error/no-such-handler in routing context projects to 404"
    (let [trace-event {:operation :rf.error/no-such-handler
                       :tags      {:url "/no-such-page"}}
          public      (default-error-projector trace-event)]
      (is (= 404 (:status public)))
      (is (= :not-found (:code public))))))

(deftest projector-output-shape-is-stable
  (testing "every projection returns the locked four keys (no extras in prod)"
    (doseq [op [:rf.error/no-such-handler
                :rf.error/schema-validation-failure
                :rf.error/handler-exception
                :rf.error/sub-exception
                :rf.error/fx-handler-exception
                :rf.error/drain-depth-exceeded
                :rf.error/some-future-category]]
      (let [public (default-error-projector {:operation op :tags {}})]
        (is (= #{:status :code :message :retryable?} (set (keys public)))
            (str "projection for " op " must carry exactly the four locked keys"))
        (is (integer? (:status public)))
        (is (keyword? (:code public)))
        (is (string? (:message public)))
        (is (boolean? (:retryable? public)))))))

;; ---- rf2-xfa2 — frame-scoped app-db schemas ------------------------------

(deftest reg-app-schema-defaults-to-current-frame
  (testing "Per Spec 010 §Per-frame schemas — reg-app-schema with no opts
            registers against (current-frame), which is :rf/default outside
            (with-frame ...)."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (is (= [:map [:id :uuid]] (rf/app-schema-at [:user]))
        "schema is visible from the active frame's lookup")
    (is (= [:map [:id :uuid]] (rf/app-schema-at [:user] :rf/default))
        "schema is visible from explicit :rf/default lookup")
    (is (= {[:user] [:map [:id :uuid]]} (rf/app-schemas))
        "app-schemas returns the active frame's schema set")
    (is (= {[:user] [:map [:id :uuid]]} (rf/app-schemas :rf/default))
        "app-schemas with explicit :rf/default returns the same map")))

(deftest reg-app-schema-explicit-frame-opt-isolates-schemas
  (testing "Per Spec 010 §Per-frame schemas — :frame opt registers against
            a named frame; sibling frames don't see that schema."
    (rf/reg-frame :test/story {})
    (rf/reg-app-schema [:user] [:map [:id :uuid]] {:frame :rf/default})
    (rf/reg-app-schema [:user] [:map [:nick :string]] {:frame :test/story})
    (is (= [:map [:id :uuid]]   (rf/app-schema-at [:user] :rf/default))
        "default frame keeps its own schema at [:user]")
    (is (= [:map [:nick :string]] (rf/app-schema-at [:user] :test/story))
        "story frame has its own (different) schema at [:user]")
    (is (= {[:user] [:map [:id :uuid]]}     (rf/app-schemas :rf/default)))
    (is (= {[:user] [:map [:nick :string]]} (rf/app-schemas {:frame :test/story})))))

(deftest sibling-frame-schemas-do-not-fire-on-each-others-dispatches
  (testing "Per Spec 010 §Per-frame schemas — validate-app-db! only walks the
            schemas registered against THIS dispatch's frame; a malformed
            commit on frame A must not fire a schema-validation-failure for
            a schema registered against frame B."
    (rf/reg-frame :test/main  {})
    (rf/reg-frame :test/other {})
    ;; Schema only on :test/other; commit happens on :test/main.
    (rf/reg-app-schema [:n] [:int] {:frame :test/other})
    (rf/reg-event-db :n/break-on-main (fn [db _] (assoc db :n "not-an-int")))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::sib (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/break-on-main] {:frame :test/main})
      (rf/remove-trace-cb! ::sib)
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                          @traces))
          "no schema fires on :test/main because the schema lives on :test/other"))))

(deftest schema-fires-only-on-the-frame-it-registers-against
  (testing "Per Spec 010 §Per-frame schemas — a malformed commit on the same
            frame the schema is registered against DOES fire the failure trace."
    (rf/reg-frame :test/main {})
    (rf/reg-app-schema [:n] [:int] {:frame :test/main})
    (rf/reg-event-db :n/break (fn [db _] (assoc db :n "not-an-int")))
    (let [traces (atom [])]
      (rf/register-trace-cb! ::same (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:n/break] {:frame :test/main})
      (rf/remove-trace-cb! ::same)
      (let [violations (filter #(= :rf.error/schema-validation-failure (:operation %))
                               @traces)]
        (is (= 1 (count violations))
            "the schema registered against :test/main fires when :test/main commits a violation")
        (is (= :test/main (-> violations first :tags :frame))
            ":frame tag carries the failing frame's id")))))

(deftest app-schemas-with-keyword-and-opts-arities-agree
  (testing "(app-schemas frame-id) is documented as sugar for
            (app-schemas {:frame frame-id}); both must return the same map."
    (rf/reg-frame :test/k {})
    (rf/reg-app-schema [:k] [:int] {:frame :test/k})
    (is (= (rf/app-schemas :test/k)
           (rf/app-schemas {:frame :test/k}))
        "keyword form == opts-map form")))

;; ---- rf2-0z1z — app-schemas-digest --------------------------------------

(deftest app-schemas-digest-is-canonical-wire-form
  (testing "Per Spec 010 §Digest algorithm — the digest is the literal
            prefix \"sha256:\" followed by 16 lowercase hex characters."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [d (rf/app-schemas-digest)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d)
          "digest is exactly \"sha256:\" + 16 lowercase hex chars"))))

(deftest app-schemas-digest-is-stable
  (testing "Per Spec 010 §Digest algorithm — registering the same schema
            set produces the same digest (cross-runtime byte-stable)."
    (rf/reg-app-schema [:user]  [:map [:id :uuid]])
    (rf/reg-app-schema [:todos] [:vector :string])
    (let [d1 (rf/app-schemas-digest)]
      ;; Re-register the SAME schemas — last-write-wins, but the map is
      ;; structurally identical, so the digest must not move.
      (rf/reg-app-schema [:todos] [:vector :string])
      (rf/reg-app-schema [:user]  [:map [:id :uuid]])
      (is (= d1 (rf/app-schemas-digest))
          "byte-identical schema set → byte-identical digest"))))

(deftest app-schemas-digest-changes-on-schema-change
  (testing "A schema-set change perturbs the digest. Two different schema
            sets must produce distinct digests."
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (let [before (rf/app-schemas-digest)]
      (rf/reg-app-schema [:user] [:map [:id :string]])
      (is (not= before (rf/app-schemas-digest))
          "tightening / changing a schema flips the digest"))))

(deftest app-schemas-digest-frame-isolated
  (testing "Per Spec 010 §Per-frame schemas — two frames with different
            schema sets produce different digests; a frame with no schemas
            has a stable empty-set digest distinct from any non-empty
            frame's digest."
    (rf/reg-frame :test/a {})
    (rf/reg-frame :test/b {})
    (rf/reg-app-schema [:user] [:map [:id :uuid]] {:frame :test/a})
    ;; :test/b has no schemas registered.
    (let [da (rf/app-schemas-digest :test/a)
          db (rf/app-schemas-digest :test/b)]
      (is (not= da db)
          "frames with different schema sets have different digests")
      (is (= db (rf/app-schemas-digest :test/b))
          "the empty-schema digest is stable across calls")
      (is (= db (rf/app-schemas-digest {:frame :test/b}))
          "keyword-sugar arity equals opts-map arity"))))

(deftest app-schemas-digest-keyword-and-opts-arities-agree
  (testing "(app-schemas-digest frame-id) is sugar for
            (app-schemas-digest {:frame frame-id}); both must return the
            same string."
    (rf/reg-frame :test/d {})
    (rf/reg-app-schema [:k] [:int] {:frame :test/d})
    (is (= (rf/app-schemas-digest :test/d)
           (rf/app-schemas-digest {:frame :test/d}))
        "keyword form == opts-map form")))

(deftest app-schemas-digest-empty-set-is-defined
  (testing "Empty schema set has a defined, stable digest (the SHA-256 of
            the empty string, prefixed). Hosts with no schemas registered
            still get a usable digest, not nil."
    (rf/reg-frame :test/empty {})
    (let [d (rf/app-schemas-digest :test/empty)]
      (is (string? d))
      (is (re-matches #"sha256:[0-9a-f]{16}" d))
      ;; SHA-256 of "" is e3b0c44298fc1c14...; first 16 hex chars are
      ;; e3b0c44298fc1c14.
      (is (= "sha256:e3b0c44298fc1c14" d)
          "empty schema set hashes the empty concatenation per Spec 010"))))

(deftest app-schemas-digest-independent-of-registration-order
  (testing "Per Spec 010 §Digest algorithm step 4 — lines are sorted
            lexicographically before final hashing, so the registration
            order of schemas must not affect the digest."
    (rf/reg-frame :test/o1 {})
    (rf/reg-frame :test/o2 {})
    ;; Same schemas, different registration order.
    (rf/reg-app-schema [:user]  [:map [:id :uuid]]   {:frame :test/o1})
    (rf/reg-app-schema [:todos] [:vector :string]    {:frame :test/o1})
    (rf/reg-app-schema [:todos] [:vector :string]    {:frame :test/o2})
    (rf/reg-app-schema [:user]  [:map [:id :uuid]]   {:frame :test/o2})
    (is (= (rf/app-schemas-digest :test/o1)
           (rf/app-schemas-digest :test/o2))
        "same schema set, different registration order → same digest")))
