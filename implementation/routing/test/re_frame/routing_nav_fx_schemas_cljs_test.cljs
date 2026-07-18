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
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.routing :as routing]
            [re-frame.routing.scroll :as scroll]
            ;; The optional schemas artefact — publishes :schemas/validate-fx!.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  with-window-stub-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                (routing/reset-counters!)
                (routing/reset-scroll-cache!))}))

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
      (is (some? (:schema (registrar/lookup :fx fx-id)))
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
        (is (nil? (scroll/frame-scroll-cache :rf/default))
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
      (is (nil? (scroll/frame-scroll-cache :rf/default))
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
             (scroll/lookup-scroll-position
               (scroll/frame-scroll-cache :rf/default) "/cart"))
          "the fractional captured position round-tripped into the cache")
      (is (empty? (violations @traces))
          "a fractional scroll position is NOT a schema violation"))))

;; =========================================================================
;; 3. :rf.nav/scroll — the window is not scrolled
;; =========================================================================

(deftest scroll-with-a-non-standard-keyword-strategy-never-scrolls
  (testing "rf2-sqams: Spec 012 offers the MAP form for host extension; a
            bare non-standard keyword is a typo, and the handler's nil
            default branch silently swallowed it. It is now rejected at
            the args boundary and surfaced as a violation"
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

(deftest scroll-with-a-map-form-strategy-still-passes
  (testing "POSITIVE control: the host-extensible MAP strategy form (pinned
            by routing_scroll_test as flowing through verbatim) validates —
            the handler's default no-op branch is reached, not the gate"
    (set-scroll! 0 700)
    (rf/reg-event :test/map-strategy
                  (fn [_ _]
                    {:fx [[:rf.nav/scroll
                           {:strategy {:behavior :smooth :block :center}}]]}))
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/map-strategy])
      (is (= [0 700] (scroll-xy))
          "unknown map strategy → handler no-op, window untouched")
      (is (empty? (violations @traces))
          "a map-form strategy is NOT a schema violation"))))
