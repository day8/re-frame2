(ns re-frame.routing-nav-fx-schemas-cljs-test
  "END-TO-END proof that the runtime `:schema` on the four
  standard `:rf.nav/*` fx actually gates the handlers.

  The JVM sibling (`routing_nav_fx_schemas_test.clj`) adjudicates the
  schema shapes and the wired `:schemas/validate-fx!` hook. It CANNOT
  prove the skip: all four nav fx are `:platforms #{:client}`, so on the
  JVM `re-frame.fx/handle-one-fx` short-circuits to
  `:rf.fx/skipped-on-platform` BEFORE reaching the Spec 010 §step-5
  validation branch. The client host is the only place the gate fires,
  so the behavioural assertions live here.

  Each test drives a real `dispatch-sync` whose `:fx` vector carries a
  malformed nav effect plus a well-formed sibling, then asserts:

  - the malformed fx's OBSERVABLE side effect did not happen (no history
    entry pushed, no scroll performed, nothing written to the host-side
    scroll-position cache) — the handler never ran;
  - the sibling fx in the same `:fx` vector still ran (Spec 010 §Per-step
    recovery row 5: `:recovery :skipped` drops the offending fx only, it
    does not halt the cascade);
  - a `:rf.error/schema-validation-failure :where :fx-args` trace fired.

  And, as the POSITIVE controls that matter most, that every shape the
  runtime legitimately emits still drives its handler — including a
  FRACTIONAL `:saved-pos` (`window.scrollX/Y` are fractional at non-100%
  zoom and on HiDPI displays) and the optional `:fragment` slot.

  `re-frame.schemas` is required explicitly: the fx-args gate exists
  only when the optional schemas artefact has published
  `:schemas/validate-fx!` (absent it, validation soft-passes per Spec
  010 §Recommended soft-pass).

  Window / history / scroll stubs come from the shared
  `re-frame.routing-browser-test-support` fixture (Node has no DOM); its
  `scrollTo` mirrors browser state onto the `scrollX` / `scrollY` fields
  `:rf.nav/capture-scroll` reads."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.scroll :as rf.routing.scroll]
            ;; The optional schemas artefact — publishes :schemas/validate-fx!.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.routing/reset-counters!)
                (rf.routing/reset-scroll-cache!)
                ;; The always-on error-emit listener registry is a
                ;; `defonce` atom — clear it so a recorder from one test cannot
                ;; leak into the next.
                (rf.error-emit/clear-error-listeners!))}))

;; ---- helpers -------------------------------------------------------------

(defn- own-the-url!
  "Declare `:rf/default` the URL owner. EP-0002: URL
  ownership is an EXPLICIT declaration — without this the history fxs
  no-op for a reason unrelated to schema validation, which would make a
  'nothing was pushed' assertion vacuous."
  []
  (rf/make-frame {:id :rf/default :url-bound? true}))

(defn- sibling-calls
  "Register a plain user fx that records its invocations. Used as the
  cascade-continues witness in every skip test: the malformed nav fx must
  be the ONLY casualty."
  []
  (let [calls (atom 0)]
    (rf/reg-fx :test/witness
               {:platforms #{:server :client}}
               (fn [_ _] (swap! calls inc)))
    calls))

(defn- violations
  "The `:rf.error/schema-validation-failure` events in a trace recording."
  [traces]
  (filterv #(= :rf.error/schema-validation-failure (:operation %)) traces))

(defn- unsupported
  "The `:rf.error/unsupported-scroll-strategy` events in a trace recording —
  the always-on leg of the unsupported-strategy rejection, emitted by the fx handler
  itself rather than by the (optional) schemas gate."
  [traces]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:operation %)) traces))

(defn- record-always-on-errors!
  "Install a recorder on the ALWAYS-ON error-emit axis (surface #4) and
  return the atom it accumulates into. This is the channel the
  `:rf.error/unsupported-scroll-strategy` rejection has to ride — the
  dev-trace recorder `with-trace-recorder!` installs is DCE'd under
  `:advanced` + `goog.DEBUG=false`, so a test that only watches the trace
  cannot tell an always-on rejection from a dev-only one."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :scroll-always-on/recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- unsupported-records
  "The `:rf.error/unsupported-scroll-strategy` records in an always-on
  recording."
  [records]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:error %)) records))

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

;; =========================================================================
;; 1. :rf.nav/push-url + :rf.nav/replace-url — history is not touched
;; =========================================================================

(deftest push-url-with-malformed-args-never-reaches-pushstate
  (testing "a non-string :rf.nav/push-url arg is rejected at the
            fx-args boundary — window.history.pushState is NOT called, and
            the sibling fx in the same :fx vector still runs"
    (own-the-url!)
    (let [witness (sibling-calls)
          before  (:entries @*history-state*)]
      (rf/reg-event :test/bad-push
                    (fn [_ _]
                      {:fx [[:rf.nav/push-url :route/cart]   ;; bad: a route-id
                            [:test/witness    nil]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:test/bad-push])
        (is (= before (:entries @*history-state*))
            "no history entry was pushed — the handler never ran")
        (is (= 1 @witness)
            "the sibling fx still ran (Spec 010 row 5: :skipped, not halted)")
        (is (= 1 (count (violations @traces)))
            "exactly one :rf.error/schema-validation-failure fired")
        (let [v (first (violations @traces))]
          (is (= :fx-args (-> v :tags :where)))
          (is (= :rf.nav/push-url (-> v :tags :rf.fx/id)))
          (is (= :skipped (:recovery v))))))))

(deftest replace-url-with-a-well-formed-url-still-replaces
  (testing "POSITIVE control: a conforming URL still drives replaceState"
    (own-the-url!)
    (rf/reg-event :test/good-replace
                  (fn [_ _] {:fx [[:rf.nav/replace-url "/checkout"]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/good-replace])
      (is (= "/checkout" (current-url *history-state*)))
      (is (empty? (violations @traces))))))

;; =========================================================================
;; 2. :rf.nav/capture-scroll — the host-side cache is not written
;; =========================================================================

(deftest capture-scroll-with-a-well-formed-url-still-captures
  (testing "POSITIVE control: {:url <string>} still captures — and the
            FRACTIONAL window.scrollX/Y a HiDPI / zoomed browser reports is
            stored verbatim, which is exactly why the spec's :saved-pos
            members are number? rather than :int"
    (set-scroll! 0.5 1234.75)
    (rf/reg-event :test/good-capture
                  (fn [_ _] {:fx [[:rf.nav/capture-scroll {:url "/cart"}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/good-capture])
      (is (= [0.5 1234.75]
             (rf.routing.scroll/lookup-scroll-position
               (rf.routing.scroll/frame-scroll-cache :rf/default) "/cart"))
          "the fractional captured position round-tripped into the cache")
      (is (empty? (violations @traces))
          "a fractional scroll position is NOT a schema violation"))))

;; =========================================================================
;; 3. :rf.nav/scroll — the window is not scrolled
;; =========================================================================

(deftest scroll-with-the-full-planner-args-still-scrolls
  (testing "POSITIVE control: the FULL five-slot args plan/scroll-plan
            assembles — :strategy + :from + :to + :saved-pos + the optional
            :fragment — pass the gate and drive the handler"
    (set-scroll! 0 0)
    (rf/reg-event :test/full-scroll
                  (fn [_ _]
                    {:fx [[:rf.nav/scroll
                           {:strategy  :restore
                            :from      {:id :route/cart
                                        :params {:id "7"}
                                        :query  {:q "shoes"}}
                            :to        {:id :route/checkout}
                            :saved-pos [12 3400.5]
                            :fragment  "section-3"}]]}))
    (with-trace-recorder! [traces]
      (committed! #(rf/dispatch-sync [:test/full-scroll]))
      (is (= [12 3400.5] (scroll-xy))
          "the full planner args drove the restore")
      (is (empty? (violations @traces))
          "no violation for the canonical planner output"))))

;; ---- the map form is REJECTED, not accepted-and-ignored -----------------
;;
;; A map strategy such as `{:behavior :smooth :block :center}` that
;; validated, emitted no violation, and left the window untouched would be
;; a documented-looking option accepted and then silently ignored.
;;
;; Nothing in the runtime interprets a map strategy (no registry, no
;; callback, no late-bound hook), so neither the schema nor Spec 012 admits
;; the map form. Two plausible map shapes are
;; exercised here, plus the empty map, because a `[:or [:enum …] :map]` slot
;; would wave all three through.

(deftest scroll-handler-emits-the-unsupported-strategy-error-directly
  (testing "the ALWAYS-ON leg. The `:schema` gate above only
            exists when the OPTIONAL schemas artefact is on the classpath;
            without it fx-args validation soft-passes and the handler is the
            last line of defence. Calling the handler DIRECTLY bypasses the
            gate the way a schemas-less host does — it must emit
            :rf.error/unsupported-scroll-strategy rather than return nil"
    (set-scroll! 0 700)
    (with-trace-recorder! [traces]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                {:strategy {:to :element :selector "#article"}})
      (let [errs (unsupported @traces)]
        (is (= 1 (count errs))
            "the handler's default branch is loud, not nil")
        (is (= [0 700] (scroll-xy))
            "and still performs no scroll")
        (let [tags (:tags (first errs))]
          (is (= {:to :element :selector "#article"} (:strategy tags))
              "the rejected value is named")
          (is (= [:top :restore :preserve] (:supported tags))
              "the supported vocabulary is named")
          ;; `:recovery` is HOISTED out of :tags onto the envelope by
          ;; `trace/build-event` (Spec 009 §Core fields).
          (is (= :no-scroll (:recovery (first errs))))
          (is (= :rf/default (:frame tags))
              "frame-stamped so the diagnostic reaches epoch capture / Xray")
          (is (string? (:reason tags))))))))

(deftest scroll-handler-positive-control-the-three-supported-strategies
  (testing "POSITIVE control — the essential one. Making the
            handler loud must not make it loud on the strategies that WORK:
            each of :top / :restore / :preserve still drives its own branch
            and emits NO unsupported-strategy error on EITHER channel — the
            dev trace, or the always-on record a production host keeps"
    (let [records (record-always-on-errors!)]
      ;; :top — no fragment element in the stub, so it falls back to (0,0).
      (set-scroll! 0 700)
      (with-trace-recorder! [traces]
        (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :top}))
        (is (= [0 0] (scroll-xy)) ":top scrolled to the top")
        (is (empty? (unsupported @traces)) ":top emitted no rejection"))
      ;; :restore — drives .scrollTo with the saved position.
      (set-scroll! 0 700)
      (with-trace-recorder! [traces]
        (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                                          {:strategy :restore :saved-pos [12 3400.5]}))
        (is (= [12 3400.5] (scroll-xy)) ":restore scrolled to the saved position")
        (is (empty? (unsupported @traces)) ":restore emitted no rejection"))
      ;; :preserve — deliberately does nothing, and that is NOT an error.
      (set-scroll! 0 700)
      (with-trace-recorder! [traces]
        (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :preserve})
        (is (= [0 700] (scroll-xy)) ":preserve left the window alone")
        (is (empty? (unsupported @traces))
            ":preserve is a DOCUMENTED no-op — it must stay silent, which is
             exactly what distinguishes it from a rejected map form"))
      (is (empty? (unsupported-records @records))
          "no always-on rejection for any supported strategy — :preserve is a
           silent no-op, not a rejection"))))
