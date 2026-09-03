(ns re-frame.routing-nav-fx-schemas-cljs-test
  "rf2-sqams — END-TO-END proof that the runtime `:schema` on the four
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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.registrar :as rf.registrar]
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
                ;; rf2-2hkfy: the always-on error-emit listener registry is a
                ;; `defonce` atom — clear it so a recorder from one test cannot
                ;; leak into the next.
                (rf.error-emit/clear-error-listeners!))}))

;; ---- helpers -------------------------------------------------------------

(defn- own-the-url!
  "Declare `:rf/default` the URL owner. EP-0002 (rf2-9o48ih): URL
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
  the always-on leg of the rf2-px26m rejection, emitted by the fx handler
  itself rather than by the (optional) schemas gate."
  [traces]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:operation %)) traces))

(defn- record-always-on-errors!
  "Install a recorder on the ALWAYS-ON error-emit axis (surface #4) and
  return the atom it accumulates into. rf2-2hkfy: this is the channel the
  `:rf.error/unsupported-scroll-strategy` rejection has to ride — the
  dev-trace recorder `with-trace-recorder!` installs is DCE'd under
  `:advanced` + `goog.DEBUG=false`, so a test that only watches the trace
  cannot tell an always-on rejection from a dev-only one."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors :scroll-always-on/recorder
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

;; =========================================================================
;; 0. Precondition — the gate is actually installed on this host
;; =========================================================================

(deftest nav-fx-registrations-carry-schema-on-cljs
  (testing "rf2-sqams: the four standard nav fx carry a :schema on the CLJS
            host too — the .cljc registrations are shared, but the gate only
            ever FIRES here, so the precondition is worth pinning where it
            matters"
    (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url
                   :rf.nav/scroll :rf.nav/capture-scroll]]
      (is (some? (:schema (rf.registrar/lookup :fx fx-id)))
          (str fx-id " carries a runtime :schema")))))

;; =========================================================================
;; 1. :rf.nav/push-url + :rf.nav/replace-url — history is not touched
;; =========================================================================

(deftest push-url-with-malformed-args-never-reaches-pushstate
  (testing "rf2-sqams: a non-string :rf.nav/push-url arg is rejected at the
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

(deftest push-url-with-a-well-formed-url-still-pushes
  (testing "POSITIVE control: the schema does not break working navigation —
            a path-form URL string drives pushState exactly as before"
    (own-the-url!)
    (rf/reg-event :test/good-push
                  (fn [_ _] {:fx [[:rf.nav/push-url "/cart"]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/good-push])
      (is (= "/cart" (current-url *history-state*))
          "the URL was pushed onto the history stack")
      (is (empty? (violations @traces))
          "no schema-validation-failure for a conforming URL"))))

(deftest replace-url-with-malformed-args-never-reaches-replacestate
  (testing "rf2-sqams: :rf.nav/replace-url carries the SAME gate as its
            push sibling — the two history fxs must not have asymmetric
            args validation any more than asymmetric drain survival"
    (own-the-url!)
    (rf/reg-event :test/good-push
                  (fn [_ _] {:fx [[:rf.nav/push-url "/cart"]]}))
    (rf/dispatch-sync [:test/good-push])
    (let [witness (sibling-calls)]
      (rf/reg-event :test/bad-replace
                    (fn [_ _]
                      {:fx [[:rf.nav/replace-url 42]         ;; bad: not a string
                            [:test/witness       nil]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:test/bad-replace])
        (is (= "/cart" (current-url *history-state*))
            "replaceState was NOT called — the URL is unchanged")
        (is (= 1 @witness) "the sibling fx still ran")
        (is (= 1 (count (violations @traces))))
        (is (= :rf.nav/replace-url
               (-> (violations @traces) first :tags :rf.fx/id)))))))

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

(deftest capture-scroll-with-malformed-args-never-writes-the-cache
  (testing "rf2-sqams: :url is the cache KEY, so a capture without one (or
            with a non-string one) must fail BEFORE the handler writes the
            host-side per-frame scroll-position cache"
    (set-scroll! 0 640)
    (let [witness (sibling-calls)]
      (rf/reg-event :test/bad-capture
                    (fn [_ _]
                      {:fx [[:rf.nav/capture-scroll {:position [1 2]}] ;; bad: no :url
                            [:test/witness          nil]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:test/bad-capture])
        (is (nil? (rf.routing.scroll/frame-scroll-cache :rf/default))
            "nothing was written to the scroll-position cache")
        (is (= 1 @witness) "the sibling fx still ran")
        (is (= 1 (count (violations @traces))))
        (is (= :rf.nav/capture-scroll
               (-> (violations @traces) first :tags :rf.fx/id)))))))

(deftest capture-scroll-with-a-non-string-url-never-writes-the-cache
  (testing "rf2-sqams: a keyword :url would key the LRU cache with a value
            the symmetric restore lookup can never reconstruct"
    (rf/reg-event :test/kw-capture
                  (fn [_ _]
                    {:fx [[:rf.nav/capture-scroll {:url :route/cart}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/kw-capture])
      (is (nil? (rf.routing.scroll/frame-scroll-cache :rf/default))
          "a keyword :url never reached the cache")
      (is (= 1 (count (violations @traces)))))))

(deftest capture-scroll-with-a-well-formed-url-still-captures
  (testing "POSITIVE control: {:url <string>} still captures — and the
            FRACTIONAL window.scrollX/Y a HiDPI / zoomed browser reports is
            stored verbatim, which is exactly why rf2-cmdpj relaxed the
            spec's :saved-pos members from :int to number?"
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

(deftest scroll-with-a-non-standard-keyword-strategy-never-scrolls
  (testing "rf2-sqams: a bare non-standard keyword is a typo, and the
            handler's nil default branch silently swallowed it. It is now
            rejected at the args boundary and surfaced as a violation"
    (set-scroll! 0 500)
    (let [witness (sibling-calls)]
      (rf/reg-event :test/bad-scroll
                    (fn [_ _]
                      {:fx [[:rf.nav/scroll {:strategy :smooth}] ;; bad: not :top/:restore/:preserve
                            [:test/witness  nil]]}))
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:test/bad-scroll])
        (is (= [0 500] (scroll-xy))
            "the window was not scrolled")
        (is (= 1 @witness) "the sibling fx still ran")
        (is (= 1 (count (violations @traces))))
        (is (= :rf.nav/scroll
               (-> (violations @traces) first :tags :rf.fx/id)))))))

(deftest scroll-with-a-malformed-saved-pos-never-scrolls
  (testing "rf2-sqams: a :restore whose :saved-pos is not a two-number tuple
            would previously reach `.scrollTo` with garbage coordinates
            (the handler's `sequential?` guard admits [\"0\" \"0\"])"
    (set-scroll! 0 500)
    (rf/reg-event :test/bad-saved-pos
                  (fn [_ _]
                    {:fx [[:rf.nav/scroll {:strategy :restore
                                           :saved-pos ["0" "0"]}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/bad-saved-pos])
      (is (= [0 500] (scroll-xy))
          "the window was not scrolled to the string coordinates")
      (is (= 1 (count (violations @traces)))))))

(deftest scroll-restore-with-a-fractional-saved-pos-still-scrolls
  (testing "POSITIVE control (the one that matters most): a FRACTIONAL
            :saved-pos — the shape a non-100%-zoom / HiDPI browser actually
            captures — still drives `.scrollTo`. The pre-rf2-cmdpj spec
            shape [:tuple :int :int] would have rejected this and silently
            broken Back-button scroll restoration for every zoomed user"
    (set-scroll! 0 0)
    (rf/reg-event :test/restore
                  (fn [_ _]
                    {:fx [[:rf.nav/scroll {:strategy  :restore
                                           :saved-pos [0.5 1234.75]}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/restore])
      (is (= [0.5 1234.75] (scroll-xy))
          "the window was scrolled to the fractional saved position")
      (is (empty? (violations @traces))))))

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
      (rf/dispatch-sync [:test/full-scroll])
      (is (= [12 3400.5] (scroll-xy))
          "the full planner args drove the restore")
      (is (empty? (violations @traces))
          "no violation for the canonical planner output"))))

(deftest scroll-top-with-an-optional-fragment-still-scrolls
  (testing "POSITIVE control: the optional :fragment slot rf2-cmdpj added to
            the spec shape validates. The stub's getElementById returns nil,
            so the handler falls through to `.scrollTo 0 0` — the point is
            that the ARGS passed the gate, not which branch ran"
    (set-scroll! 0 900)
    (rf/reg-event :test/top-fragment
                  (fn [_ _]
                    {:fx [[:rf.nav/scroll {:strategy :top
                                           :fragment "install"}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/top-fragment])
      (is (= [0 0] (scroll-xy))
          "the :top branch ran — args carrying :fragment were not rejected")
      (is (empty? (violations @traces))))))

;; ---- rf2-px26m: the map form is REJECTED, not accepted-and-ignored ------
;;
;; This is the bug's red-before/green-after pin, and it replaces the old
;; `scroll-with-a-map-form-strategy-still-passes` positive control, which
;; asserted precisely the defect: `{:behavior :smooth :block :center}`
;; validated, emitted no violation, and left the window untouched — a
;; documented-looking option that was accepted and then silently ignored.
;;
;; Nothing in the runtime ever interpreted a map strategy (no registry, no
;; callback, no late-bound hook), so the map form is gone from the schema
;; and from Spec 012. Both of Spec 012's own advertised examples are
;; exercised here, plus the empty map, because `[:or [:enum …] :map]` used
;; to wave all three through.

(deftest scroll-with-a-map-form-strategy-is-rejected-at-the-args-boundary
  (testing "rf2-px26m: a MAP strategy — including the exact
            {:to :element :selector \"#article\"} shape Spec 012 used to
            advertise as host-extensible — is now a violation, not a silent
            no-op. The window is still untouched, but the author is TOLD"
    (doseq [bad [{:to :element :selector "#article"}   ;; the old Spec 012 example
                 {:behavior :smooth :block :center}    ;; the shape the bead names
                 {}]]                                  ;; the degenerate map
      (set-scroll! 0 700)
      (let [witness (sibling-calls)]
        (rf/reg-event :test/map-strategy
                      (fn [_ _]
                        {:fx [[:rf.nav/scroll {:strategy bad}]
                              [:test/witness  nil]]}))
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:test/map-strategy])
          (is (= [0 700] (scroll-xy))
              (str "no scroll for " (pr-str bad)))
          (is (= 1 @witness)
              "the sibling fx still ran — only the offending fx is skipped")
          (is (= 1 (count (violations @traces)))
              (str "a map strategy is a schema violation: " (pr-str bad)))
          (is (= :rf.nav/scroll
                 (-> (violations @traces) first :tags :rf.fx/id))))))))

(deftest scroll-handler-emits-the-unsupported-strategy-error-directly
  (testing "rf2-px26m: the ALWAYS-ON leg. The `:schema` gate above only
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

(deftest scroll-handler-rejection-rides-the-always-on-error-axis
  (testing "rf2-2hkfy: the rejection must ride the ALWAYS-ON error-emit axis,
            not the dev trace alone. rf2-px26m routed the default branch
            through `trace/emit-error!`, which is wrapped in
            `interop/debug-enabled?` and DCEs under `:advanced` +
            `goog.DEBUG=false`. So on a PRODUCTION host without the optional
            schemas artefact — precisely the configuration this branch exists
            to cover — the handler ran, scrolled nothing, emitted nothing and
            returned nil: the original defect, intact, for the consumers least
            likely to notice. The record must reach a listener registered on
            the production-survivable axis"
    (set-scroll! 0 700)
    (let [records (record-always-on-errors!)]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                {:strategy {:to :element :selector "#article"}})
      (let [errs (unsupported-records @records)]
        (is (= 1 (count errs))
            "exactly ONE always-on record — the rejection survives production")
        (is (= [0 700] (scroll-xy))
            "and still performs no scroll")
        (let [r (first errs)]
          (is (= :rf.error/unsupported-scroll-strategy (:error r)))
          ;; rf2-s3n6h: the record is STRUCTURAL. It named the rejected value
          ;; verbatim until this test was corrected — `record-attrs` bypass the
          ;; elision seam, so an arbitrary runtime `:scroll` opt rode off-box
          ;; whole and unbounded (measured: 4.8 MB for a 2000-key value). The
          ;; raw value now rides the dev trace alone; see
          ;; `re-frame.routing-scroll-record-bounded-cljs-test`.
          (is (nil? (:strategy r))
              "the rejected value does NOT ride the production-surviving record")
          (is (= :map (:strategy-type r))
              "a closed-vocabulary SHAPE tag stands in for it")
          (is (= [:top :restore :preserve] (:supported r))
              "the supported vocabulary is named")
          (is (= :no-scroll (:recovery r))
              ":recovery :no-scroll — navigation is unaffected, only the scroll")
          (is (= :rf/default (:frame r))
              ":frame names the navigating frame")
          (is (string? (:reason r))
              "the human diagnostic rides the record, not only the DCE'd trace")
          (is (not (str/includes? (:reason r) "#article"))
              "…and it is a CONSTANT — never an interpolation of the value")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

(deftest scroll-handler-rejection-emits-once-per-channel
  (testing "rf2-2hkfy: fanning through `rf.error-emit/emit-error-both!` must not
            DOUBLE-emit. One unsupported strategy produces exactly one
            always-on record AND exactly one dev trace — the dev-trace tag map
            being the one rf2-px26m shipped, so existing trace consumers
            (Xray, epoch capture) see no change"
    (set-scroll! 0 700)
    (let [records (record-always-on-errors!)]
      (with-trace-recorder! [traces]
        (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :bogus})
        (is (= 1 (count (unsupported @traces)))
            "exactly one dev trace — no double emission on the trace channel")
        (let [tags (:tags (first (unsupported @traces)))]
          (is (= :bogus (:strategy tags)))
          (is (= [:top :restore :preserve] (:supported tags)))
          (is (= :rf/default (:frame tags)))
          (is (string? (:reason tags)))
          (is (= :no-scroll (:recovery (first (unsupported @traces))))
              ":recovery is still hoisted to the envelope by build-event")))
      (is (= 1 (count (unsupported-records @records)))
          "exactly one always-on record — no double emission on that channel"))))

(deftest scroll-handler-supported-strategies-emit-no-always-on-record
  (testing "rf2-2hkfy POSITIVE control on the always-on channel: promoting the
            rejection must not make the WORKING strategies loud in production.
            `:top` / `:restore` / `:preserve` each drive their own branch and
            fan NO always-on record — `:preserve` in particular stays the
            silent documented no-op it is specified to be"
    (let [records (record-always-on-errors!)]
      ;; :top — no fragment element in the stub, so it falls back to (0,0).
      (set-scroll! 0 700)
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :top})
      (is (= [0 0] (scroll-xy)) ":top scrolled to the top")
      ;; :restore — drives .scrollTo with the saved position.
      (set-scroll! 0 700)
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                {:strategy :restore :saved-pos [0 420]})
      (is (= [0 420] (scroll-xy)) ":restore restored the saved position")
      ;; :preserve — the silent documented no-op. Nothing moves, nothing emits.
      (set-scroll! 0 700)
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :preserve})
      (is (= [0 700] (scroll-xy)) ":preserve left the scroll position alone")
      (is (empty? (unsupported-records @records))
          "no always-on rejection for any supported strategy — :preserve is a
           silent no-op, not a rejection"))))

(deftest scroll-handler-rejection-attributes-the-originating-event
  (testing "rf2-2hkfy: when the fx context carries the originating event
            vector (Spec 002 §The binary fx-handler signature — `do-fx`
            threads `:event` onto the handler ctx), the always-on record is
            attributed to it, so an off-box shipper can tell WHICH navigation
            carried the bad strategy. A direct handler call with no `:event`
            leaves both slots nil rather than inventing attribution"
    (let [records (record-always-on-errors!)]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default
                                 :event [:test/navigate-somewhere 42]}
                                {:strategy :bogus})
      (let [r (first (unsupported-records @records))]
        (is (= [:test/navigate-somewhere 42] (:event r))
            ":event carries the originating event vector")
        (is (= :test/navigate-somewhere (:event-id r))
            ":event-id is the event-vector head")))
    (let [records (record-always-on-errors!)]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :bogus})
      (let [r (first (unsupported-records @records))]
        (is (nil? (:event r))    "no event vector for a direct handler call")
        (is (nil? (:event-id r)) "no event-id for a direct handler call")
        (is (= :rf/default (:frame r))
            "the frame stamp is still present")))))

(deftest scroll-handler-adversarial-near-miss-strategies
  (testing "rf2-px26m adversarial: values that LOOK like a supported strategy
            must still be rejected — a misspelt keyword, the string spelling,
            and a map that merely NAMES a supported strategy (the shape a
            'named strategy registry' would have used) get no special pass"
    (doseq [bad [:restored                 ;; one letter off
                 :scroll-top               ;; plausible synonym
                 "top"                     ;; string, not keyword
                 {:strategy :top}          ;; a map that names a real strategy
                 [:top]]]                  ;; a vector wrapping one
      (set-scroll! 0 700)
      (with-trace-recorder! [traces]
        (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy bad})
        (is (= 1 (count (unsupported @traces)))
            (str "rejected: " (pr-str bad)))
        (is (= [0 700] (scroll-xy))
            (str "no scroll for " (pr-str bad)))))))

(deftest scroll-handler-positive-control-the-three-supported-strategies
  (testing "rf2-px26m POSITIVE control — the essential one. Making the
            handler loud must not make it loud on the strategies that WORK:
            each of :top / :restore / :preserve still drives its own branch
            and emits NO unsupported-strategy error"
    ;; :top — no fragment element in the stub, so it falls back to (0,0).
    (set-scroll! 0 700)
    (with-trace-recorder! [traces]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :top})
      (is (= [0 0] (scroll-xy)) ":top scrolled to the top")
      (is (empty? (unsupported @traces)) ":top emitted no rejection"))
    ;; :restore — drives .scrollTo with the saved position.
    (set-scroll! 0 700)
    (with-trace-recorder! [traces]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                {:strategy :restore :saved-pos [12 3400.5]})
      (is (= [12 3400.5] (scroll-xy)) ":restore scrolled to the saved position")
      (is (empty? (unsupported @traces)) ":restore emitted no rejection"))
    ;; :preserve — deliberately does nothing, and that is NOT an error.
    (set-scroll! 0 700)
    (with-trace-recorder! [traces]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :preserve})
      (is (= [0 700] (scroll-xy)) ":preserve left the window alone")
      (is (empty? (unsupported @traces))
          ":preserve is a DOCUMENTED no-op — it must stay silent, which is
           exactly what distinguishes it from the removed map form"))))
