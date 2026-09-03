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
  `(if rf.interop/debug-enabled? <stamped> <plain>)` shape in
  `emit-dispatched-trace!` — the dev arm carries the slot, the prod arm
  omits it. This is the rf2-7ynhyn-correct idiom (NOT a `cond->`
  test-position gate). The PRODUCTION-ELISION counterpart is the CLJS
  prod-elision probe (`npm run test:elision`): the whole `:rf.event/dispatched`
  emit DCE's under `:advanced` + `goog.DEBUG=false` (the `event/dispatched`
  op keyword is a `check-elision.cjs` dev-only sentinel), so the dev arm —
  including the `:rf.cofx` stamp — rides that same whole-body elision. This
  JVM test pins the DEV-SIDE PRESENCE contract (`rf.interop/debug-enabled?` is
  true by default on the JVM).

  JVM-only — the trace-listener mechanism is platform-agnostic.

  ## Posture split (rf2-d2841)

  The STAMP is dev-gated; the CAUSAL TOKEN it stamps is not. `:rf.cofx` is a
  slot on the DISPATCH ENVELOPE (`router/build-envelope` calls it the EP-0017
  recordable-coeffect map), the router fills `:rf/time-ms` there at the causal
  boundary, and a user fx-handler receives that envelope as `(:envelope m)`
  — the production surface
  `cascade-envelope-propagation-test/fx-handler-ctx-carries-envelope-slot`
  pins. Reading the map off the `:rf.event/dispatched` trace was one way to see
  it, and the one that disappears under `-Dre-frame.debug=false`.

  So the three CONTENT claims — the framework stamps `:rf/time-ms`, a
  caller-supplied map rides verbatim, a map missing `:rf/time-ms` has it filled
  — are now read off the envelope and hold in both postures. What stays
  inside the `(when rf.interop/debug-enabled? ...)` arms is the narrower claim the
  trace still owns: that the slot is STAMPED on `:rf.event/dispatched`, under
  `:tags` rather than at top level, which is what the Xray Event lens reads."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/make-frame {:id :rf/default})
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

;; rf2-d2841 — the ALWAYS-ON read of the same map. `:rf.cofx` lives on the
;; dispatch envelope; a user fx-handler is handed that envelope verbatim.
(def ^:private envelopes (atom {}))

(defn- register-probe! []
  (reset! envelopes {})
  (rf/reg-fx :rf2-jt854w/probe
    (fn [m [k]] (swap! envelopes assoc k (:envelope m)))))

;; ---- the dispatched trace carries :rf.cofx ------------------------

(deftest dispatched-trace-carries-cofx-with-time-ms
  (testing ":rf.event/dispatched carries the envelope's :rf.cofx map,
   and that map carries the framework-stamped causal :time-ms"
    (register-probe!)
    (rf/reg-event :rf2-jt854w/noop
      (fn [{:keys [db]} _] {:db db :fx [[:rf2-jt854w/probe [:noop]]]}))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:rf2-jt854w/noop])))
          [enqueue]  (dispatched-of evs)
          ;; The op-type-specific payload slots (`:rf.event/v`,
          ;; `:rf.event/origin`, `:rf.cofx`, ...) ride under
          ;; `:tags`; `build-event` hoists only `:source` to top-level.
          rf-cofx    (get-in enqueue [:tags :rf.cofx])
          env-cofx   (:rf.cofx (:noop @envelopes))]
      ;; ---- ALWAYS-ON: the causal token on the envelope -------------------
      (is (map? env-cofx) ":rf.cofx is a map on the dispatch envelope")
      (is (contains? env-cofx :rf/time-ms)
          "the recordable-coeffect map carries the framework-stamped :rf/time-ms")
      (is (integer? (:rf/time-ms env-cofx))
          ":rf/time-ms is an epoch-ms integer")
      ;; ---- rf2-d2841 dev arm: the STAMP onto the trace -------------------
      (when rf.interop/debug-enabled?
        (is (some? enqueue) ":rf.event/dispatched fired")
        (is (contains? (:tags enqueue) :rf.cofx)
            ":rf.cofx is stamped on the dispatched trace (rf2-jt854w)")
        (is (map? rf-cofx) ":rf.cofx is a map")
        (is (contains? rf-cofx :rf/time-ms)
            "the recordable-coeffect map carries the framework-stamped :rf/time-ms")
        (is (integer? (:rf/time-ms rf-cofx))
            ":rf/time-ms is an epoch-ms integer")))))

(deftest dispatched-trace-preserves-caller-supplied-cofx
  (testing "a caller-supplied :rf.cofx (test/replay/SSR fixture) rides
   onto the dispatched trace verbatim — additional owner-qualified facts
   are preserved alongside the framework-required :rf/time-ms"
    (register-probe!)
    (rf/reg-event :rf2-jt854w/scripted
      (fn [{:keys [db]} _] {:db db :fx [[:rf2-jt854w/probe [:scripted]]]}))
    (let [scripted   {:rf/time-ms 1234567890123
                      :todo/id    #uuid "00000000-0000-0000-0000-000000000001"
                      :todo/score 0.42}
          evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/scripted]
                                           {:rf.cofx scripted})))
          [enqueue]  (dispatched-of evs)]
      ;; ---- ALWAYS-ON: the caller's map reaches the cascade verbatim ------
      (is (= scripted (:rf.cofx (:scripted @envelopes)))
          "the caller-supplied causal :rf.cofx map rides the envelope verbatim")
      ;; ---- rf2-d2841 dev arm --------------------------------------------
      (when rf.interop/debug-enabled?
        (is (some? enqueue) ":rf.event/dispatched fired")
        (is (= scripted (get-in enqueue [:tags :rf.cofx]))
            "the caller-supplied causal :rf.cofx map is stamped verbatim")))))

(deftest dispatched-trace-fills-missing-time-ms-from-supplied-map
  (testing "a caller-supplied map WITHOUT :rf/time-ms has it filled by the router;
   the dispatched trace reflects the filled-and-preserved map"
    (register-probe!)
    (rf/reg-event :rf2-jt854w/fill
      (fn [{:keys [db]} _] {:db db :fx [[:rf2-jt854w/probe [:fill]]]}))
    (let [evs        (record-traces
                       (fn []
                         (rf/dispatch-sync [:rf2-jt854w/fill]
                                           {:rf.cofx {:todo/score 0.99}})))
          [enqueue]  (dispatched-of evs)
          rf-cofx    (get-in enqueue [:tags :rf.cofx])
          env-cofx   (:rf.cofx (:fill @envelopes))]
      ;; ---- ALWAYS-ON: the FILL happens at the causal boundary, not at the
      ;;      trace-stamping site — which is the whole point of the claim.
      (is (= 0.99 (:todo/score env-cofx)) "caller-supplied fact preserved")
      (is (integer? (:rf/time-ms env-cofx))
          ":rf/time-ms filled by the router at the causal boundary")
      ;; ---- rf2-d2841 dev arm --------------------------------------------
      (when rf.interop/debug-enabled?
        (is (some? enqueue) ":rf.event/dispatched fired")
        (is (= 0.99 (:todo/score rf-cofx)) "caller-supplied fact preserved")
        (is (integer? (:rf/time-ms rf-cofx))
            ":rf/time-ms filled by the router at the causal boundary")))))

(deftest cofx-rides-under-tags-alongside-event-payload-slots
 ;; rf2-d2841 — a claim about the TRACE EVENT's SHAPE (which slot is hoisted,
 ;; which rides under `:tags`), not about the coeffect map, whose content the
 ;; three deftests above now pin in both postures. Kept verbatim.
 (when rf.interop/debug-enabled?
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
          ":rf.event/v also rides under :tags — same placement")))))
