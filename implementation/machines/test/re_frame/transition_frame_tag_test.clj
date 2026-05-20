(ns re-frame.transition-frame-tag-test
  "Per rf2-hwuki: `:rf.machine/transition` MUST carry the `:frame` tag so
  `re-frame.epoch.capture/capture-event!` admits it into the cascade's
  trace buffer.

  Discovered by the Causa matrix P1 gaps worker (rf2-bz72m chart-render
  scenario) while building a chart-render assertion: the framework
  emitted `:rf.machine/transition` WITHOUT `:frame`, so the epoch
  capture gate (`(when (and frame-id ...) (state/buffer-event! ...))`)
  silently dropped the event. The trace fanned out to direct trace
  listeners (so unit tests that registered a `register-trace-listener!`
  observed it) but never reached the epoch-history `:trace-events`
  slot the Causa Machine Inspector reads from. The chart 'never
  rendered' for real cascades; Causa's own unit tests sidestepped it
  via a `:rf.causa/set-epoch-history-for-test` injection seam.

  This test lives in the machines artefact (which does not depend on
  epoch) so the site contract is exercised even when the epoch artefact
  isn't on the test classpath. The end-to-end harvested-record
  assertion lives upstack in the epoch / Causa gates — they read the
  same `:tags :frame` slot we lock here, so a future regression that
  drops the tag fails at this site test first (clear cause)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- shared spec ----------------------------------------------------------

(def ^:private traffic-light
  {:initial :red
   :states  {:red    {:on {:tick {:target :green}}}
             :green  {:on {:tick {:target :yellow}}}
             :yellow {:on {:tick {:target :red}}}}})

(defn- record-traces!
  []
  (let [seen (atom [])]
    (rf/register-trace-listener! ::rec (fn [ev] (swap! seen conj ev)))
    [seen #(rf/unregister-trace-listener! ::rec)]))

(defn- transitions-of [evs]
  (filterv #(= :rf.machine/transition (:operation %)) evs))

;; ---- :rf.machine/transition tags carry :frame -----------------------------

(deftest transition-tags-carry-frame
  (testing ":rf.machine/transition tags carry `:frame` so the epoch-capture
   gate admits the event into the cascade buffer (rf2-hwuki — the gate
   silently drops trace events whose tags lack `:frame`)"
    (rf/reg-machine :rf2-hwuki/tl traffic-light)
    (let [[seen unreg] (record-traces!)]
      (try
        (rf/dispatch-sync [:rf2-hwuki/tl [:tick]])
        (let [[t] (transitions-of @seen)]
          (is (some? t) "one :rf.machine/transition fired")
          (is (contains? (:tags t) :frame)
              ":frame tag is present on the trace's tags map")
          (is (= :rf/default (-> t :tags :frame))
              ":frame tag value is the dispatching frame's id (:rf/default for the bare dispatch)"))
        (finally (unreg))))))

(deftest transition-frame-matches-explicit-dispatch-frame
  (testing "explicit `{:frame <id>}` on dispatch flows through to the tag —
   verifies the tag tracks the live dispatching frame, not a hard-coded
   default"
    (rf/reg-frame :rf2-hwuki/frame-A {:doc "explicit dispatch frame"})
    (rf/reg-machine :rf2-hwuki/tl traffic-light)
    (let [[seen unreg] (record-traces!)]
      (try
        (rf/dispatch-sync [:rf2-hwuki/tl [:tick]] {:frame :rf2-hwuki/frame-A})
        (let [[t] (transitions-of @seen)]
          (is (some? t))
          (is (= :rf2-hwuki/frame-A (-> t :tags :frame))
              ":frame tag tracks the dispatching frame id, not :rf/default"))
        (finally (unreg))))))
