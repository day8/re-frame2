(ns re-frame.routing-nav-fx-schemas-cljs-test
  "End-to-end proof that the runtime `:schema` on the standard `:rf.nav/*` fx
  gates the handlers, and that the scroll handler's own always-on rejection
  holds where no schema gate runs.

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
                (rf.routing/reset-scroll-cache!)
                ;; The always-on error-emit listener registry is a `defonce`
                ;; atom, so a recorder from one test would leak into the next.
                (rf.error-emit/clear-error-listeners!))}))

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

(defn- unsupported [traces]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:operation %)) traces))

(defn- record-always-on-errors!
  "Record the ALWAYS-ON error-emit channel and return the atom it fills. The
  dev-trace recorder is compiled out under `:advanced` + `goog.DEBUG=false`,
  so only this channel tells an always-on rejection from a dev-only one."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :scroll-always-on/recorder
                           (fn [record] (swap! seen conj record)))
    seen))

(defn- unsupported-records [records]
  (filterv #(= :rf.error/unsupported-scroll-strategy (:error %)) records))

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
;; The scroll handler's always-on leg
;; =========================================================================

(deftest scroll-handler-emits-the-unsupported-strategy-error-directly
  (testing "with no schemas artefact, fx-args validation soft-passes and the
            handler is the last line of defence; called directly, it emits
            :rf.error/unsupported-scroll-strategy for a map-form strategy and
            scrolls nothing"
    (set-scroll! 0 700)
    (with-trace-recorder! [traces]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                           {:strategy {:to :element :selector "#article"}})
      (let [errs (unsupported @traces)]
        ;; `:recovery` rides on the envelope, not in :tags (Spec 009 §Core fields).
        (is (= [{:strategy  {:to :element :selector "#article"}
                 :supported [:top :restore :preserve]
                 :frame     :rf/default
                 :recovery  :no-scroll}]
               (mapv #(assoc (select-keys (:tags %) [:strategy :supported :frame])
                             :recovery (:recovery %))
                     errs))
            "one loud rejection naming the value, the vocabulary and the frame")
        (is (string? (:reason (:tags (first errs)))))
        (is (= [0 700] (scroll-xy)) "and no scroll")))))

(deftest scroll-handler-positive-control-the-three-supported-strategies
  (testing "each supported strategy drives its own branch and emits no
            unsupported-strategy error on either channel: the dev trace, or the
            always-on record a production host keeps"
    (let [records (record-always-on-errors!)]
      (doseq [[args expected] [;; no fragment element in the stub, so :top lands at (0,0)
                               [{:strategy :top}                            [0 0]]
                               [{:strategy :restore :saved-pos [12 3400.5]} [12 3400.5]]
                               ;; a documented no-op, which is not a rejection
                               [{:strategy :preserve}                       [0 700]]]]
        (set-scroll! 0 700)
        (with-trace-recorder! [traces]
          (committed! #(rf.routing.scroll/scroll-fx-handler {:frame :rf/default} args))
          (is (= [expected []] [(scroll-xy) (unsupported @traces)])
              (str (:strategy args)))))
      (is (empty? (unsupported-records @records))
          "no always-on rejection for any supported strategy"))))
