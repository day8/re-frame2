(ns re-frame.image-no-emit-trace-gate-cljs-test
  "rf2-x76af2.25 — `emit-dispatched-trace!`'s enqueue-time `:rf.trace/no-emit?`
  gate must be IMAGE-AWARE.

  The gate reads the target handler's registration meta to decide whether to
  suppress the `:rf.event/dispatched` enqueue trace (Spec 009 §Trace-emission
  opt-out). It ran at enqueue time via a BARE `(registrar/lookup :event
  event-id)` — OUTSIDE the `call-with-frame-resolution` binding that wraps
  `process-event!` — so an image-loaded frame's inline `:reg-event` handler
  (which lives ONLY in the frame's generation resolver, and whose inline
  descriptor CAN carry `:rf.trace/no-emit?` in `:metadata`) was MISSED: the bare
  lookup returned nil → `no-emit?` false → the `:rf.event/dispatched` trace
  flooded the very stream the handler is marked to stay out of. This is the
  exact flood rf2-qsjda closed for registrar-registered handlers, previously
  still open for image-registered ones.

  Mirrors `re-frame.trace-test`'s registrar-handler no-emit tests for the IMAGE
  case: an image inline no-emit handler must NOT emit `:rf.event/dispatched` at
  enqueue; a baseline image handler WITHOUT the flag still does (so the
  suppression is the difference, not that image dispatches never emit).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`.

  ## Posture split (rf2-d2841)

  The always-on half is that the image-inline handler is RESOLVED AND RUN —
  `:bookkeeping/ran?` / `:normal/ran?` land in the frame's app-db. That is the
  precondition the whole namespace rests on (the pre-fix bug was a
  generation-blind lookup returning nil), it is readable straight off
  `rf/app-db-value`, and it needs no trace surface — so it is asserted WITHOUT
  a posture guard and runs in `scripts/test-core-prod-gate.sh` too.

  The `:rf.event/dispatched` assertions are DEV-ONLY: `:rf.trace/no-emit?`
  gates a `trace/emit!` site, and under `-Dre-frame.debug=false` nothing is
  emitted at all. BOTH of them move inside the
  `(when interop/debug-enabled? …)` arm marked `rf2-d2841`, the negative
  included — and the negative is the point rather than tidiness. Left outside,
  `(not (contains? ops :rf.event/dispatched))` over an EMPTY `ops` would report
  that the no-emit flag correctly suppressed the enqueue trace when in fact
  nothing was emitted for any handler, flagged or not. \"The flag is the
  difference\" is a claim about a dev channel and is only meaningful where that
  channel is live."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as image]
            [re-frame.interop        :as interop]
            [re-frame.live-frame     :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support   :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter        plain-atom/adapter
                                            :ambient-frame  nil}))

(defn- dispatched-ops-for
  "Dispatch `event` to the image-loaded frame `fid` while recording every trace
  event whose event-id is `event-id`; return the set of `:operation`s seen for
  it. `event-id` is resolved off `:rf.trace/event-id` or the event vector tag."
  [fid event event-id]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    (try
      (rf/dispatch-sync event {:frame fid})
      (->> @recorded
           (filter (fn [ev]
                     (let [tags (:tags ev)
                           eid  (or (:rf.trace/event-id tags)
                                    (let [v (:rf.event/v tags)]
                                      (when (vector? v) (first v))))]
                       (= event-id eid))))
           (map :operation)
           set)
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(deftest image-inline-no-emit-handler-does-not-emit-dispatched-at-enqueue
  (testing "an image-loaded frame whose INLINE :reg-event handler is marked
            :rf.trace/no-emit? true does NOT emit :rf.event/dispatched at enqueue
            time — the gate resolves the handler meta through the frame's image
            generation, not a bare (generation-blind) registrar lookup
            (rf2-x76af2.25)"
    (rf/make-frame {:id :img/main :doc "image-loaded no-emit frame"})
    ;; The handler exists ONLY in the frame's image (never globally registered),
    ;; so a bare enqueue-time lookup would miss it entirely — the pre-fix bug.
    (let [img (image/image
                {:id :img/no-emit
                 :registrations
                 {:reg-event [[:bookkeeping/internal {:rf.trace/no-emit? true}
                               (fn [{:keys [db]} _]
                                 {:db (assoc db :bookkeeping/ran? true)})]]}})]
      (lf/make-frame {:id :img/main :images [img]} [])
      (let [ops (dispatched-ops-for :img/main [:bookkeeping/internal]
                                    :bookkeeping/internal)]
        ;; The handler body still ran (opt-out is of TRACE EMISSION, not
        ;; execution) — proves the image handler was genuinely resolved + run.
        (is (true? (:bookkeeping/ran? (rf/app-db-value :img/main)))
            "the inline image handler executed")
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture
        ;; split). A NEGATIVE over the trace stream: under the production gate
        ;; `ops` is empty for EVERY handler, so this would pass without the
        ;; no-emit flag doing anything at all.
        (when interop/debug-enabled?
          ;; The bug: :rf.event/dispatched was emitted for a no-emit handler.
          (is (not (contains? ops :rf.event/dispatched))
              (str "the :rf.trace/no-emit? image handler must NOT emit "
                   ":rf.event/dispatched at enqueue; saw ops: " (pr-str ops))))))))

(deftest image-inline-handler-without-flag-still-emits-dispatched
  (testing "baseline sanity: the SAME image-inline dispatch shape WITHOUT
            :rf.trace/no-emit? DOES emit :rf.event/dispatched — so the
            suppression above is the flag's effect, not that image-frame
            dispatches never emit (rf2-x76af2.25)"
    (rf/make-frame {:id :img/main :doc "image-loaded normal frame"})
    (let [img (image/image
                {:id :img/normal
                 :registrations
                 {:reg-event [[:normal/event {:doc "no no-emit flag"}
                               (fn [{:keys [db]} _]
                                 {:db (assoc db :normal/ran? true)})]]}})]
      (lf/make-frame {:id :img/main :images [img]} [])
      (let [ops (dispatched-ops-for :img/main [:normal/event] :normal/event)]
        (is (true? (:normal/ran? (rf/app-db-value :img/main)))
            "the inline image handler executed")
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture
        ;; split).
        (when interop/debug-enabled?
          (is (contains? ops :rf.event/dispatched)
              ":rf.event/dispatched fired for the un-flagged image handler"))))))
