(ns re-frame.dispatched-trace-world-inputs-test
  "Per rf2-jt854w (EP-0010 observability completion) — the
  `:rf.event/dispatched` enqueue trace carries the envelope's causal
  `:rf.world/inputs` map so Xray's Event lens (rf2-9fyn40, the WORLD INPUTS
  surface) has data to render.

  Before this, `emit-dispatched-trace!` stamped
  `:rf.event/v` / `:frame` / `:rf.event/origin` / `:source` / `:rf.event/sync?`
  / `:source-detail` / the dispatch-id correlation slots, but NOT
  `:rf.world/inputs` — so the only trace-side view of the causal token was
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
  including the world-inputs stamp — rides that same whole-body elision. This
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
    (rf/register-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! ::rec)))
    @seen))

(defn- dispatched-of [evs]
  (filterv #(= :rf.event/dispatched (:operation %)) evs))

;; ---- the dispatched trace carries :rf.world/inputs ------------------------

(deftest dispatched-trace-carries-world-inputs-with-time-ms
  (testing ":rf.event/dispatched carries the envelope's :rf.world/inputs map,
   and that map carries the framework-stamped causal :time-ms"
    (rf/reg-event-db :rf2-jt854w/noop (fn [db _] db))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:rf2-jt854w/noop])))
          [enqueue]  (dispatched-of evs)
          ;; The op-type-specific payload slots (`:rf.event/v`,
          ;; `:rf.event/origin`, `:rf.world/inputs`, ...) ride under
          ;; `:tags`; `build-event` hoists only `:source` to top-level.
          wi         (get-in enqueue [:tags :rf.world/inputs])]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (contains? (:tags enqueue) :rf.world/inputs)
          ":rf.world/inputs is stamped on the dispatched trace (rf2-jt854w)")
      (is (map? wi) ":rf.world/inputs is a map")
      (is (contains? wi :time-ms)
          "the causal world-input map carries the framework-stamped :time-ms")
      (is (integer? (:time-ms wi))
          ":time-ms is an epoch-ms integer"))))

(deftest dispatched-trace-preserves-caller-supplied-world-inputs
  (testing "a caller-supplied :rf.world/inputs (test/replay/SSR fixture) rides
   onto the dispatched trace verbatim — the additional :uuid / :random keys
   are preserved alongside the framework-required :time-ms"
    (rf/reg-event-db :rf2-jt854w/scripted (fn [db _] db))
    (let [scripted   {:time-ms 1234567890123
                      :uuid    #uuid "00000000-0000-0000-0000-000000000001"
                      :random  0.42}
          evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/scripted]
                                           {:rf.world/inputs scripted})))
          [enqueue]  (dispatched-of evs)]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (= scripted (get-in enqueue [:tags :rf.world/inputs]))
          "the caller-supplied causal world-input map is stamped verbatim"))))

(deftest dispatched-trace-fills-missing-time-ms-from-supplied-map
  (testing "a caller-supplied map WITHOUT :time-ms has it filled by the router;
   the dispatched trace reflects the filled-and-preserved map"
    (rf/reg-event-db :rf2-jt854w/fill (fn [db _] db))
    (let [evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/fill]
                                           {:rf.world/inputs {:random 0.99}})))
          [enqueue]  (dispatched-of evs)
          wi         (get-in enqueue [:tags :rf.world/inputs])]
      (is (some? enqueue) ":rf.event/dispatched fired")
      (is (= 0.99 (:random wi)) "caller-supplied :random preserved")
      (is (integer? (:time-ms wi))
          ":time-ms filled by the router at the causal boundary"))))

(deftest world-inputs-rides-under-tags-alongside-event-payload-slots
  (testing ":rf.world/inputs rides under :tags alongside the other op-type-
   specific payload slots (:rf.event/v, :rf.event/origin, :rf.event/sync?) —
   build-event hoists only :source to top-level, so the Event lens reads the
   world-input map off (get-in event [:tags :rf.world/inputs])"
    (rf/reg-event-db :rf2-jt854w/placement (fn [db _] db))
    (let [evs       (record-traces
                      (fn [] (rf/dispatch-sync [:rf2-jt854w/placement])))
          [enqueue] (dispatched-of evs)]
      (is (some? enqueue))
      (is (contains? (:tags enqueue) :rf.world/inputs)
          ":rf.world/inputs lives under :tags")
      (is (not (contains? enqueue :rf.world/inputs))
          ":rf.world/inputs is NOT a top-level slot (only :source is hoisted)")
      ;; Co-located with the other dispatched payload slots under :tags.
      (is (contains? (:tags enqueue) :rf.event/v)
          ":rf.event/v also rides under :tags — same placement"))))
