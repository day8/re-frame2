(ns re-frame.routing-scroll-always-on-elision-prod-test
  "rf2-2hkfy — the ACCEPTANCE probe for the unsupported-scroll-strategy
  rejection, under the one build configuration that can actually falsify it.

  ## The gap this file closes

  rf2-px26m (#6333) removed the map form from `:rf.nav/scroll`'s args schema
  and made `scroll-fx-handler`'s default branch loud instead of returning
  nil. Spec 012 and the source both called that default branch \"the
  ALWAYS-ON leg\" — the thing standing between the author and silence when
  the OPTIONAL schemas artefact is absent and the Spec 010 §step-5 `:fx-args`
  gate therefore soft-passes.

  It was not always-on. It emitted through `trace/emit-error!`, whose whole
  body is wrapped in `interop/debug-enabled?` and constant-folds away under
  `:advanced` + `goog.DEBUG=false`. Compose the two optional-ness conditions
  and the rejection disappeared exactly where it was needed:

    schemas present  + dev   → schema gate fires (rejection never needed)
    schemas present  + prod  → schema gate fires (rejection never needed)
    schemas ABSENT   + dev   → handler fires, dev trace delivers   ← the only
                                                                     leg the
                                                                     old tests
                                                                     covered
    schemas ABSENT   + prod  → handler fires, trace DCE'd, NOTHING ← the gap

  The bottom row is a production app that loaded routing but not schemas: it
  performed no scroll, emitted no production-surviving record, and returned
  nil. That is the accepted-and-ignored option rf2-px26m set out to remove,
  reproduced for the consumers least likely to notice it.

  rf2-2hkfy fans the category through the existing two-channel seam
  (`rf.error-emit/emit-error-both!`) instead. Axis 1 — the `dispatch-on-error!`
  listener registry — is NOT gated on `interop/debug-enabled?`, so the record
  survives here.

  ## Why the dev suite cannot prove this

  The dev-mode legs in `re-frame.routing-nav-fx-schemas-cljs-test` assert the
  always-on listener fires, but they run with the trace surface LIVE. They
  cannot distinguish a record that rides the always-on axis from one that
  rides the dev trace — under `goog.DEBUG=true` both deliver. Only a
  `goog.DEBUG=false` build separates them, which is what this file is.
  (Exactly the argument `re-frame.teardown-always-on-elision-prod-test`
  makes for the EP-0008 rows.)

  ## Why calling the handler directly IS the schemas-absent path

  `re-frame.schemas` publishes `:schemas/validate-fx!` through `late-bind` at
  ns-load time, and the prod-elision bundle is shared across suites — so a
  sibling suite's require would install the gate process-wide and no
  `:fx`-driven dispatch here could honestly represent a schemas-less host.
  Invoking `scroll-fx-handler` directly is the established idiom for that
  configuration in this repo (see the rf2-px26m leg in the dev suite): it
  reaches the handler with the fx-args gate bypassed, which is precisely what
  a soft-pass does.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, `:ns-regexp \"-elision-prod-test$\"`, runner
  `re-frame.prod-elision-runner`). The default `:browser-test` / `:node-test`
  runners use regexes that do NOT match this suffix, so these tests run only
  under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :as rf.test-support]
            ;; rf2-qwm0a — the dev trace listener surface. Registered here to
            ;; prove the dev channel is genuinely DCE'd in this build, which
            ;; is what makes the always-on assertion non-vacuous.
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                ;; The always-on listener registry is a `defonce` atom.
                (rf.error-emit/clear-error-listeners!)
                (rf.routing.scroll/reset-cache!))}))

;; ---- helpers --------------------------------------------------------------

(defn- record-always-on-errors! []
  (let [seen (atom [])]
    (rf/register-listener! :errors :prod.scroll/recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- unsupported-records [records]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:error %)) records))

;; A window stub: the prod-elision build has no DOM fixture of its own, and
;; the handler's supported branches touch `window.scrollTo` / `scrollX` /
;; `scrollY`. Mirrors `routing-browser-test-support`'s stub, kept local so
;; this suite carries no cross-artefact test dependency.
(defn- with-window-stub [f]
  (let [original-scroll-to (.-scrollTo js/window)]
    (set! (.-scrollX js/window) 0)
    (set! (.-scrollY js/window) 0)
    (set! (.-scrollTo js/window)
          (fn [x y]
            (set! (.-scrollX js/window) x)
            (set! (.-scrollY js/window) y)))
    (try (f)
         (finally (set! (.-scrollTo js/window) original-scroll-to)))))

(defn- scroll-xy []
  [(.-scrollX js/window) (.-scrollY js/window)])

(defn- set-scroll! [x y]
  (set! (.-scrollX js/window) x)
  (set! (.-scrollY js/window) y))

;; ===========================================================================
;; (a) The rejection SURVIVES `:advanced` + `goog.DEBUG=false` with the
;;     schemas artefact absent — the acceptance criterion.
;; ===========================================================================

(deftest unsupported-strategy-record-survives-prod-without-schemas
  (testing "rf2-2hkfy: under `:advanced` + `goog.DEBUG=false`, with the
            `:fx-args` schema gate soft-passed (schemas-less host), an
            unsupported `:rf.nav/scroll` strategy performs NO scroll and fans
            EXACTLY ONE `:rf.error/unsupported-scroll-strategy` record out
            through the always-on `register-error-listener!` substrate. Before
            rf2-2hkfy this branch emitted only through the DCE'd
            `trace/emit-error!`, so this assertion saw zero records — the
            regression this test exists to catch"
    (with-window-stub
      (fn []
        (set-scroll! 0 700)
        (let [records (record-always-on-errors!)]
          (rf.routing.scroll/scroll-fx-handler
            {:frame :prod.scroll/frame}
            {:strategy {:to :element :selector "#article"}})
          (let [errs (unsupported-records @records)]
            (is (= 1 (count errs))
                "the rejection SURVIVES production on a schemas-less host")
            (is (= [0 700] (scroll-xy))
                "and still performs no scroll — :recovery :no-scroll")
            (let [r (first errs)]
              (is (= :rf.error/unsupported-scroll-strategy (:error r)))
              ;; rf2-s3n6h — the record that survives production is
              ;; STRUCTURAL. It carried the rejected value verbatim until
              ;; this test was corrected: `record-attrs` are merged past the
              ;; elision seam, so an arbitrary runtime `:scroll` opt shipped
              ;; off-box whole and unbounded. Production is exactly where that
              ;; mattered most, which is why the assertion belongs here too.
              (is (nil? (:strategy r))
                  "the rejected value does NOT ride the off-box record")
              (is (= :map (:strategy-type r))
                  "a closed-vocabulary SHAPE tag stands in for it, in
                   production as in dev")
              (is (= [:top :restore :preserve] (:supported r))
                  "the supported vocabulary is named")
              (is (= :no-scroll (:recovery r)))
              (is (= :prod.scroll/frame (:frame r))
                  ":frame names the navigating frame")
              (is (string? (:reason r))
                  "the human diagnostic survives goog.DEBUG=false — the
                   rejection is unconditional, and so is its explanation")
              (is (not (str/includes? (:reason r) "#article"))
                  "…and it is a CONSTANT, carrying no fragment of the value")
              (is (number? (:time r))))))))))

;; ===========================================================================
;; (b) The dev trace channel IS elided here — which is what makes (a)
;;     meaningful rather than vacuous.
;; ===========================================================================

(deftest dev-trace-channel-is-elided-while-the-record-still-fires
  (testing "rf2-2hkfy: the two channels are genuinely INDEPENDENT under prod.
            A trace listener sees NOTHING (the `trace/emit-error!` half is
            constant-folded away by `interop/debug-enabled?`) while the
            always-on listener still receives the record. If this test ever
            observed a trace event, the elision contract would be broken and
            test (a) would no longer be proving production survival"
    (with-window-stub
      (fn []
        (let [traces  (atom [])
              records (record-always-on-errors!)
              cb-key  :prod.scroll/trace-recorder]
          (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! traces conj ev)))
          (try
            (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                      {:strategy :bogus})
            (finally
              (rf.trace.tooling/unregister-listener! cb-key)))
          (is (empty? @traces)
              "no trace events under :advanced + goog.DEBUG=false — the dev
               half of the two-channel seam is DCE'd, as designed")
          (is (= 1 (count (unsupported-records @records)))
              "yet the always-on record still fired — production is NOT less
               safe than dev"))))))

;; ===========================================================================
;; (c) Positive controls — promoting the rejection must not make the WORKING
;;     strategies loud in production.
;; ===========================================================================

(deftest supported-strategies-emit-no-record-under-prod
  (testing "rf2-2hkfy POSITIVE control under prod: `:top` / `:restore` /
            `:preserve` each drive their own branch and fan NO always-on
            record. `:preserve` in particular remains the SILENT documented
            no-op (Spec 012) — it must never become a rejection"
    (with-window-stub
      (fn []
        (let [records (record-always-on-errors!)]
          ;; :top — no fragment element, so it falls back to (0,0).
          (set-scroll! 0 700)
          (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame} {:strategy :top})
          (is (= [0 0] (scroll-xy)) ":top scrolled to the top under prod")
          ;; :restore — drives .scrollTo with the saved position.
          (set-scroll! 0 700)
          (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                    {:strategy :restore :saved-pos [0 420]})
          (is (= [0 420] (scroll-xy)) ":restore restored the saved position")
          ;; :preserve — nothing moves, nothing emits.
          (set-scroll! 0 700)
          (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                    {:strategy :preserve})
          (is (= [0 700] (scroll-xy))
              ":preserve left the scroll position alone")
          (is (empty? (unsupported-records @records))
              "no always-on rejection for any supported strategy under prod"))))))
