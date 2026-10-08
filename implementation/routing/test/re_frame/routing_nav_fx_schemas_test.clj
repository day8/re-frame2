(ns re-frame.routing-nav-fx-schemas-test
  "Runtime `:schema` on the four standard `:rf.nav/*` fx.

  [Spec-Schemas §Standard fx args schemas] says the standard fx ship with
  `:schema` set to the corresponding schema, and `re-frame.fx/handle-one-fx`
  consults the `:schemas/validate-fx!` hook only when the registration meta
  carries one (Spec 010 §Validation order step 5). Every check here reads
  the `:schema` off the LIVE registration, so a registration that lost its
  schema fails these tests rather than passing them.

  Two layers:

  1. ADJUDICATION — the registered schema, run through Malli, accepts what
     the runtime emits and rejects malformed args. `:saved-pos` members are
     `number?`: `window.scrollX/Y` are fractional at non-100% zoom and on
     HiDPI displays.
  2. BOUNDARY — the real `:schemas/validate-fx!` hook returns false for
     malformed args and emits `:rf.error/schema-validation-failure :where
     :fx-args` with `:recovery :skipped`.

  The end-to-end skip lives in `routing_nav_fx_schemas_cljs_test.cljs`: all
  four fx are `:platforms #{:client}`, so on the JVM `handle-one-fx` skips
  them before the validation branch.

  ## Posture split

  Layer 1 is `m/validate` against the registered schema, pure Malli with
  nothing gated, so it runs unguarded in `clojure -M:test` and in
  `scripts/test-routing-prod-gate.sh` (`-Dre-frame.debug=false`).

  Layer 2 is dev-only by design. `re-frame.schemas.validate/validate-fx!` is

      (if rf.interop/debug-enabled? (run-validation …) true)

  so under the gate the hot-path fx-args check accepts every input (Spec 010
  §Production builds: production validation is the opt-in `:boundary? true`
  flag, outside the gate). A hook call there would pass for the wrong
  reason, so the hook reads sit inside `(when rf.interop/debug-enabled? …)`
  arms marked `dev-instrumentation arm`, and beside them `schema-verdict`,
  the registered schema's own verdict that the hook relays, carries the
  posture-independent half."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :refer [with-trace-recorder!]]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- registered-schema
  "The `:schema` on the live `fx-id` registration: what the runtime validates against."
  [fx-id]
  (:schema (rf.registrar/lookup :fx fx-id)))

(deftest capture-scroll-meta-marks-its-url-carrier-sensitive
  (testing "the :rf.nav/capture-scroll registration marks its :url carrier
            :sensitive (EP-0015): the leaving route's URL is the cache key and
            carries that route's query and params"
    (is (= [[:url]] (:sensitive rf.routing.scroll/capture-scroll-meta)))))

;; =========================================================================
;; 1. Adjudication
;; =========================================================================

(deftest scroll-fx-schema-accepts-every-planner-output
  (let [schema (registered-schema :rf.nav/scroll)]
    (doseq [args [{:strategy :top}
                  {:strategy :restore}
                  {:strategy :preserve}
                  ;; the full planner output, descriptors carrying :params and :query
                  {:strategy  :restore
                   :from      {:id :route/cart :params {:id "7"} :query {:q "x"}}
                   :to        {:id :route/checkout}
                   :saved-pos [120 3400]
                   :fragment  "section-3"}
                  ;; fractional at non-100% zoom and on HiDPI displays, and
                  ;; negative under elastic overscroll
                  {:strategy :restore :saved-pos [-0.5 1234.75]}]]
      (is (m/validate schema args) (pr-str args)))))

(deftest scroll-fx-schema-rejects-malformed-args
  (doseq [[fx-id bad] [[:rf.nav/scroll {}]   ;; :strategy is required
                       ;; the vocabulary is closed: nothing would interpret a map form
                       [:rf.nav/scroll {:strategy {:to :element :selector "#article"}}]
                       [:rf.nav/scroll {:strategy :restore :saved-pos [0]}]
                       [:rf.nav/scroll {:strategy :restore :saved-pos ["0" "0"]}]
                       [:rf.nav/scroll {:strategy :top :fragment :install}]
                       ;; a non-string cache key the restore lookup can never rebuild
                       [:rf.nav/capture-scroll {:url :route/cart}]
                       ;; the push-url args shape
                       [:rf.nav/capture-scroll "/cart"]]]
    (is (not (m/validate (registered-schema fx-id) bad))
        (str fx-id " rejects " (pr-str bad)))))

;; =========================================================================
;; 2. Boundary — the real :schemas/validate-fx! hook re-frame.fx calls
;; =========================================================================
;;
;; `re-frame.fx/handle-one-fx` resolves `:schemas/validate-fx!` through
;; late-bind and skips the fx when it returns false. These tests call that
;; exact fn with the live registration meta.

(defn- validate-through-hook
  "Call the live `:schemas/validate-fx!` hook with the live registration meta
  for `fx-id`. Dev-only verdict: under `-Dre-frame.debug=false` it returns
  `true` for everything (see the ns docstring)."
  [fx-id args]
  (let [validate-fx! (rf.late-bind/get-fn :schemas/validate-fx!)]
    (validate-fx! fx-id :test/originating-event args
                  (rf.registrar/lookup :fx fx-id))))

(defn- schema-verdict
  "The always-on half of the wired gate: `m/validate` against the `:schema`
  on `fx-id`'s live registration, the schema `validate-fx!` would consult."
  [fx-id args]
  (m/validate (registered-schema fx-id) args))

(deftest nav-fx-args-pass-the-real-validation-hook-when-conforming
  (testing "everything the runtime emits passes the wired gate, fractional
            :saved-pos included"
    (let [conforming {:rf.nav/push-url       "/search?q=shoes#install"
                      :rf.nav/replace-url    "/search?q=shoes#install"
                      :rf.nav/capture-scroll {:url "/articles/42?ref=email#install"}
                      :rf.nav/scroll         {:strategy  :restore
                                              :from      {:id :route/cart}
                                              :to        {:id :route/checkout}
                                              :saved-pos [0.5 1234.75]
                                              :fragment  "section-3"}}]
      ;; Under the gate the hook accepts everything, so the registered
      ;; schema's own verdict is what proves these args conform.
      (doseq [[fx-id args] conforming]
        (is (true? (schema-verdict fx-id args)) (str fx-id)))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (doseq [[fx-id args] conforming]
            (is (true? (validate-through-hook fx-id args)) (str fx-id)))
          (is (empty? (filter #(= :rf.error/schema-validation-failure (:operation %))
                              @traces))
              "no schema-validation-failure trace for conforming nav args"))))))

(deftest nav-fx-args-fail-the-real-validation-hook-when-malformed
  (testing "one malformed shape per fx fails the wired gate, so handle-one-fx
            skips the fx (Spec 010 §Per-step recovery row 5) before its handler
            touches history, the scroll position or the capture cache"
    (doseq [[fx-id bad-args] {:rf.nav/push-url       :route/cart
                              :rf.nav/replace-url    42
                              :rf.nav/scroll         {:strategy :smooth}
                              :rf.nav/capture-scroll {:position [0 0]}}]
      (is (false? (schema-verdict fx-id bad-args))
          (str fx-id "'s registered schema rejects " (pr-str bad-args)))
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (with-trace-recorder! [traces]
          (is (false? (validate-through-hook fx-id bad-args))
              (str fx-id " with " (pr-str bad-args) " fails the wired gate"))
          (is (= [{:where    :fx-args
                   :rf.fx/id fx-id
                   :failing-id fx-id
                   :event-id :test/originating-event
                   :recovery :skipped}]
                 (->> @traces
                      (filter #(= :rf.error/schema-validation-failure (:operation %)))
                      (mapv #(assoc (select-keys (:tags %) [:where :rf.fx/id :failing-id :event-id])
                                    :recovery (:recovery %)))))
              (str fx-id " emits one :fx-args violation, recovery :skipped")))))))
