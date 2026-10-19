(ns re-frame.routing-nav-fx-schemas-cljs-test
  "End-to-end proof that the runtime `:schema` on the standard `:rf.nav/*` fx
  gates the handlers, and that where no schema gate runs an unsupported scroll
  strategy still surfaces, as the ordinary `:rf.error/fx-handler-exception`.

  All four nav fx are `:platforms #{:client}`, so on the JVM
  `re-frame.fx/handle-one-fx` skips them before the Spec 010 step-5
  validation branch. The JVM sibling (`routing_nav_fx_schemas_test.clj`)
  adjudicates the schemas and the wired `:schemas/validate-fx!` hook; the
  client host is where the skip itself happens, so it is proved here: a
  malformed fx is dropped, its sibling in the same `:fx` vector still runs,
  and a `:rf.error/schema-validation-failure :where :fx-args` trace fires.

  `re-frame.schemas` is required explicitly: the fx-args gate exists only
  once the optional schemas artefact has published `:schemas/validate-fx!`.

  Window / history / scroll stubs come from the shared
  `re-frame.routing-browser-test-support` fixture; its `scrollTo` mirrors
  onto the `scrollX` / `scrollY` fields the handlers read."
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
             :refer [*history-state* with-window-stub-fixture]])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.routing/reset-counters!)
                (rf.routing/reset-scroll-cache!))}))

;; ---- helpers -------------------------------------------------------------

(defn- own-the-url!
  "Declare `:rf/default` the URL owner. Without it the history fx no-op for a
  reason unrelated to schema validation, and a 'nothing was pushed'
  assertion would be vacuous."
  []
  (rf/make-frame {:id :rf/default :url-bound? true}))

(defn- sibling-calls
  "Register a plain user fx that counts its invocations: the witness that the
  malformed nav fx is the only casualty."
  []
  (let [calls (atom 0)]
    (rf/reg-fx :test/witness
               {:platforms #{:server :client}}
               (fn [_ _] (swap! calls inc)))
    calls))

(defn- violations [traces]
  (filterv #(= :rf.error/schema-validation-failure (:operation %)) traces))

(defn- scroll-xy []
  [(.-scrollX js/window) (.-scrollY js/window)])

(defn- set-scroll! [x y]
  (set! (.-scrollX js/window) x)
  (set! (.-scrollY js/window) y))

(defn- committed!
  "`:rf.nav/scroll` touches the page only after the view substrate commits,
  through the adapter's `:adapter/after-render`. Run `f` with that hook
  replaced by a queue, then run what it queued — the commit this build has no
  renderer to make."
  [f]
  (let [original (rf.late-bind/get-fn :adapter/after-render)
        queued   (atom [])]
    (try
      (rf.late-bind/set-fn! :adapter/after-render (fn [g] (swap! queued conj g) nil))
      (f)
      (run! #(%) @queued)
      (finally (rf.late-bind/set-fn! :adapter/after-render original)))))

;; =========================================================================
;; The fx-args gate skips the offending fx alone
;; =========================================================================

(deftest push-url-with-malformed-args-never-reaches-pushstate
  (testing "a non-string :rf.nav/push-url arg is rejected at the fx-args
            boundary: pushState is never called, and the sibling fx in the
            same :fx vector still runs"
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
            "no history entry was pushed: the handler never ran")
        (is (= 1 @witness)
            "the sibling fx still ran (Spec 010 row 5: :skipped, not halted)")
        (is (= [[:fx-args :rf.nav/push-url :skipped]]
               (mapv (juxt (comp :where :tags) (comp :rf.fx/id :tags) :recovery)
                     (violations @traces)))
            "exactly one :fx-args violation, recovery :skipped")))))

;; =========================================================================
;; An unsupported scroll strategy is an ordinary fx exception
;; =========================================================================

(defn- without-fx-schemas
  "Run `f` as a host without the optional schemas artefact runs it: with
  `:schemas/validate-fx!` unpublished, the `:fx-args` gate soft-passes."
  [f]
  (let [validate-fx! (rf.late-bind/get-fn :schemas/validate-fx!)]
    (try
      (rf.late-bind/set-fn! :schemas/validate-fx! nil)
      (f)
      (finally (rf.late-bind/set-fn! :schemas/validate-fx! validate-fx!)))))

(defn- always-on-records
  "Run `f` and return the always-on error records it fanned out."
  [f]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! records conj %))
    (try
      (f)
      (finally (rf.error-emit/unregister-error-listener! ::recorder)))
    @records))

(deftest a-directly-emitted-unsupported-strategy-is-one-fx-handler-exception
  (rf/reg-event :test/bogus-scroll
                (fn [_ _] {:fx [[:rf.nav/scroll {:strategy :bogus}]
                                [:test/witness  nil]]}))
  (let [witness (sibling-calls)]
    (testing "with no schemas artefact the handler throws, so exactly one
              always-on :rf.error/fx-handler-exception names the fx and says how
              to not scroll; nothing scrolls and the sibling fx still runs"
      (set-scroll! 0 700)
      (let [records (always-on-records
                      #(without-fx-schemas (fn [] (rf/dispatch-sync [:test/bogus-scroll]))))]
        (is (= [[:rf.error/fx-handler-exception :rf.nav/scroll]]
               (mapv (juxt :rf.error/id :failing-id) records)))
        (is (re-find #":scroll false" (str (:reason (first records)))))
        (is (= [1 [0 700]] [@witness (scroll-xy)]))))
    (testing "with the schemas artefact the :fx-args gate refuses it first, and
              the handler never runs"
      (reset! witness 0)
      (with-trace-recorder! [traces]
        (let [records (always-on-records #(rf/dispatch-sync [:test/bogus-scroll]))]
          (is (= [[:fx-args :rf.nav/scroll :skipped]]
                 (mapv (juxt (comp :where :tags) (comp :rf.fx/id :tags) :recovery)
                       (violations @traces))))
          (is (= [] (filterv #(= :rf.error/fx-handler-exception (:rf.error/id %)) records)))
          (is (= 1 @witness)))))))

(deftest scroll-handler-throws-on-every-strategy-outside-the-vocabulary
  (testing "called directly, the handler throws for :preserve, an unknown
            keyword and a map alike, and scrolls nothing"
    (doseq [strategy [:preserve :bogus {:to :element :selector "#article"}]]
      (set-scroll! 0 700)
      (is (= [:rf.error/fx-handler-exception [0 700]]
             [(try (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy strategy})
                   nil
                   (catch :default e (:rf.error/id (ex-data e))))
              (scroll-xy)])
          (pr-str strategy)))))

(deftest scroll-handler-positive-control-the-two-strategies
  (testing "each strategy drives its own branch and throws nothing"
    (doseq [[args expected] [;; no fragment element in the stub, so :top lands at (0,0)
                             [{:strategy :top}                            [0 0]]
                             [{:strategy :restore :saved-pos [12 3400.5]} [12 3400.5]]]]
      (set-scroll! 0 700)
      (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :rf/default} args))
      (is (= expected (scroll-xy)) (str (:strategy args))))))
