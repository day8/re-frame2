(ns re-frame.dispatched-trace-cofx-test
  "Per rf2-jt854w (EP-0010 observability completion) — the
  `:rf.event/dispatched` enqueue trace carries the envelope's causal
  `:rf.cofx` map so Xray's Event lens (rf2-9fyn40, the RECORDABLE
  COEFFECTS surface — renamed from WORLD INPUTS by EP-0017 §9) has data
  to render.

  Before this, `emit-dispatched-trace!` stamped
  `:rf.event/v` / `:frame` / `:rf.event/origin` / `:source` / `:rf.event/sync?`
  / `:source-detail` / the dispatch-id correlation slots, but NOT
  `:rf.cofx` — so the only trace-side view of the causal token was
  the filtered framework-default cofx (the user-cofx projection drops it via
  `fx/framework-coeffect-keys`), leaving the lens with no input map.

  The stamp is DEBUG-GATED via the canonical outermost
  `(if interop/debug-enabled? <stamped> <plain>)` shape in
  `emit-dispatched-trace!` — the dev arm carries the slot, the prod arm
  omits it. This is the rf2-7ynhyn-correct idiom (NOT a `cond->`
  test-position gate). The PRODUCTION-ELISION counterpart is the CLJS
  prod-elision probe (`npm run test:elision`): the whole `:rf.event/dispatched`
  emit DCE's under `:advanced` + `goog.DEBUG=false` (the `event/dispatched`
  op keyword is a `check-elision.cjs` dev-only sentinel), so the dev arm —
  including the `:rf.cofx` stamp — rides that same whole-body elision. This
  JVM test pins the DEV-SIDE PRESENCE contract (`interop/debug-enabled?` is
  true by default on the JVM).

  JVM-only — the trace-listener mechanism is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- dispatched-of [evs]
  (filterv #(= :rf.event/dispatched (:operation %)) evs))

;; ---- the dispatched trace carries :rf.cofx ------------------------

(deftest dispatched-trace-carries-cofx-with-time-ms
  (testing ":rf.event/dispatched carries the envelope's :rf.cofx map,
   and that map carries the framework-stamped causal :time-ms"
    (rf/reg-event :rf2-jt854w/noop (fn [{:keys [db]} _] {:db db}))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:rf2-jt854w/noop])))
          [enqueue]  (dispatched-of evs)
          ;; The op-type-specific payload slots (`:rf.event/v`,
          ;; `:rf.event/origin`, `:rf.cofx`, ...) ride under
          ;; `:tags`; `build-event` hoists only `:source` to top-level.
          rf-cofx    (get-in enqueue [:tags :rf.cofx])]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (contains? (:tags enqueue) :rf.cofx)
          ":rf.cofx is stamped on the dispatched trace (rf2-jt854w)")
      (is (map? rf-cofx) ":rf.cofx is a map")
      (is (contains? rf-cofx :rf/time-ms)
          "the recordable-coeffect map carries the framework-stamped :rf/time-ms")
      (is (integer? (:rf/time-ms rf-cofx))
          ":rf/time-ms is an epoch-ms integer"))))

(deftest dispatched-trace-preserves-caller-supplied-cofx
  (testing "a caller-supplied :rf.cofx (test/replay/SSR fixture) rides
   onto the dispatched trace verbatim — additional owner-qualified facts
   are preserved alongside the framework-required :rf/time-ms"
    (rf/reg-event :rf2-jt854w/scripted (fn [{:keys [db]} _] {:db db}))
    (let [scripted   {:rf/time-ms 1234567890123
                      :todo/id    #uuid "00000000-0000-0000-0000-000000000001"
                      :todo/score 0.42}
          evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/scripted]
                                           {:rf.cofx scripted})))
          [enqueue]  (dispatched-of evs)]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (= scripted (get-in enqueue [:tags :rf.cofx]))
          "the caller-supplied causal :rf.cofx map is stamped verbatim"))))

(deftest dispatched-trace-fills-missing-time-ms-from-supplied-map
  (testing "a caller-supplied map WITHOUT :rf/time-ms has it filled by the router;
   the dispatched trace reflects the filled-and-preserved map"
    (rf/reg-event :rf2-jt854w/fill (fn [{:keys [db]} _] {:db db}))
    (let [evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/fill]
                                           {:rf.cofx {:todo/score 0.99}})))
          [enqueue]  (dispatched-of evs)
          rf-cofx    (get-in enqueue [:tags :rf.cofx])]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (= 0.99 (:todo/score rf-cofx)) "caller-supplied fact preserved")
      (is (integer? (:rf/time-ms rf-cofx))
          ":rf/time-ms filled by the router at the causal boundary"))))

(deftest cofx-rides-under-tags-alongside-event-payload-slots
  (testing ":rf.cofx rides under :tags alongside the other op-type-
   specific payload slots (:rf.event/v, :rf.event/origin, :rf.event/sync?) —
   build-event hoists only :source to top-level, so the Event lens reads the
   :rf.cofx map off (get-in event [:tags :rf.cofx])"
    (rf/reg-event :rf2-jt854w/placement (fn [{:keys [db]} _] {:db db}))
    (let [evs       (record-traces
                      (fn [] (rf/dispatch-sync [:rf2-jt854w/placement])))
          [enqueue] (dispatched-of evs)]
      (is (some? enqueue))
      (is (contains? (:tags enqueue) :rf.cofx)
          ":rf.cofx lives under :tags")
      (is (not (contains? enqueue :rf.cofx))
          ":rf.cofx is NOT a top-level slot (only :source is hoisted)")
      ;; Co-located with the other dispatched payload slots under :tags.
      (is (contains? (:tags enqueue) :rf.event/v)
          ":rf.event/v also rides under :tags — same placement"))))
