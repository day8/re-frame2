(ns re-frame.routing-scroll-always-on-elision-prod-test
  "The ACCEPTANCE probe for the unsupported-scroll-strategy
  rejection, under the one build configuration that can actually falsify it.

  ## The gap this file guards

  `:rf.nav/scroll`'s args schema admits no map form, and
  `scroll-fx-handler`'s default branch is loud rather than returning
  nil. Spec 012 and the source both call that default branch \"the
  ALWAYS-ON leg\" — the thing standing between the author and silence when
  the OPTIONAL schemas artefact is absent and the Spec 010 §step-5 `:fx-args`
  gate therefore soft-passes.

  Emitting through `trace/emit-error!` alone would not be always-on: its whole
  body is wrapped in `interop/debug-enabled?` and constant-folds away under
  `:advanced` + `goog.DEBUG=false`. Compose the two optional-ness conditions
  and the rejection would disappear exactly where it is needed:

    schemas present  + dev   → schema gate fires (rejection never needed)
    schemas present  + prod  → schema gate fires (rejection never needed)
    schemas ABSENT   + dev   → handler fires, dev trace delivers   ← the only
                                                                     leg the
                                                                     dev suite
                                                                     covers
    schemas ABSENT   + prod  → handler fires, trace DCE'd, NOTHING ← the gap

  The bottom row is a production app that loaded routing but not schemas: it
  would perform no scroll, emit no production-surviving record, and return
  nil — the accepted-and-ignored outcome the closed vocabulary rules out,
  reproduced for the consumers least likely to notice it.

  The handler therefore fans the category through the two-channel seam
  (`rf.error-emit/emit-error-both!`). Axis 1 — the `dispatch-on-error!`
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
  configuration in this repo (see
  `scroll-handler-emits-the-unsupported-strategy-error-directly` in the dev
  suite): it
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
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :as rf.test-support]
            ;; The dev trace listener surface. Registered here to
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
    (rf.error-emit/register-error-listener! :prod.scroll/recorder
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

(defn- committed!
  "`:rf.nav/scroll` touches the page only after the view
  substrate commits, through the installed adapter's `:adapter/after-render`.
  Run `f` with that hook replaced by a queue, then run what it queued — the
  commit this build has no renderer to make."
  [f]
  (let [original (rf.late-bind/get-fn :adapter/after-render)
        queued   (atom [])]
    (try
      (rf.late-bind/set-fn! :adapter/after-render (fn [g] (swap! queued conj g) nil))
      (f)
      (run! #(%) @queued)
      (finally (rf.late-bind/set-fn! :adapter/after-render original)))))

;; ===========================================================================
;; (a) The rejection SURVIVES `:advanced` + `goog.DEBUG=false` with the
;;     schemas artefact absent — the acceptance criterion.
;; ===========================================================================

(deftest unsupported-strategy-record-survives-prod-without-schemas
  (testing "under `:advanced` + `goog.DEBUG=false`, with the
            `:fx-args` schema gate soft-passed (schemas-less host), an
            unsupported `:rf.nav/scroll` strategy performs NO scroll and fans
            EXACTLY ONE `:rf.error/unsupported-scroll-strategy` record out
            through the always-on `register-error-listener!` substrate. A
            branch emitting only through the DCE'd
            `trace/emit-error!` would leave this assertion with zero records — the
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
              ;; The record that survives production is STRUCTURAL.
              ;; `record-attrs` are merged past the elision seam, so carrying
              ;; the rejected value verbatim would ship an arbitrary runtime
              ;; `:scroll` opt off-box whole and unbounded. Production is
              ;; exactly where that matters most, which is why the assertion
              ;; belongs here too.
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
  (testing "the two channels are genuinely INDEPENDENT under prod.
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
;; (c) Positive controls — the always-on rejection must not make the WORKING
;;     strategies loud in production.
;; ===========================================================================

(deftest supported-strategies-emit-no-record-under-prod
  (testing "POSITIVE control under prod: `:top` / `:restore` /
            `:preserve` each drive their own branch and fan NO always-on
            record. `:preserve` in particular is the SILENT documented
            no-op (Spec 012) — it must never become a rejection"
    (with-window-stub
      (fn []
        (let [records (record-always-on-errors!)]
          ;; :top — no fragment element, so it falls back to (0,0).
          (set-scroll! 0 700)
          (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame} {:strategy :top}))
          (is (= [0 0] (scroll-xy)) ":top scrolled to the top under prod")
          ;; :restore — drives .scrollTo with the saved position.
          (set-scroll! 0 700)
          (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                                            {:strategy :restore :saved-pos [0 420]}))
          (is (= [0 420] (scroll-xy)) ":restore restored the saved position")
          ;; :preserve — nothing moves, nothing emits.
          (set-scroll! 0 700)
          (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                    {:strategy :preserve})
          (is (= [0 700] (scroll-xy))
              ":preserve left the scroll position alone")
          (is (empty? (unsupported-records @records))
              "no always-on rejection for any supported strategy under prod"))))))
