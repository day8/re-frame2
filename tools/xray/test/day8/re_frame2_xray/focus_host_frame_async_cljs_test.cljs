(ns day8.re-frame2-xray.focus-host-frame-async-cljs-test
  "rf2-2qtgt — `focus!` sent the way a HOST sends it: asynchronously, with
  the host frame named.

  Story's evidence-spine 'Xray: <panel>' link calls
  `(focus! variant-id {:epoch-id … :dispatch-id … :panel …})` from a click
  handler, and the testbed runner sends `{:frame … :epoch-id …}` from an
  epoch listener. Neither passes `:sync?`. Every `focus!` test in
  `focus_cljs_test.cljs` does, and under `:sync? true` each translated event
  drains before the next is dispatched — so none of them can see what the
  async path does with the frame step.

  The contract (`tools/xray/spec/008-Embedding-Contract.md` §Host-facing
  focus API) is FRAME-FIRST: `:rf.xray/set-frame` re-seeds the ring and
  clears the pinned dispatch-id BEFORE the spine pin lands.
  `:rf.xray/select-frame` reaches `set-frame` through a `:dispatch` fx, and
  the router appends that to the BACK of the queue (Spec 002
  §Run-to-completion). A pin queued beside the frame event therefore lands
  before `set-frame`, which then clears it and returns the spine to LIVE
  head — the focus never moves, and the Machines panel reads the head event.

  The fixture mirrors the Story walk that found it: a real machine
  transition, then an unrelated host event so the head is NOT the machine
  epoch, and a host frame Xray already observes."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.focus :as focus]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deep-machine :as deep-machine]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :async?  true}))

(def ^:private host-frame e2e/default-host-frame)

(def ^:private machine-event [:deep/main [:work/go]])

(defn- machine-epoch-then-unrelated-head!
  "Stand up the host + Xray frames, settle a real machine transition, then an
  unrelated host event so the spine's head is NOT the machine epoch. Set up
  inline rather than through `e2e/with-host-and-xray-frames`, whose `finally`
  resets the trace collector the moment a body returns — which, for an async
  body, is before the focus drain this suite waits on. Returns the machine
  epoch's `{:epoch-id … :dispatch-id …}`: the coordinates a Story beat carries."
  []
  (rf/make-frame {:id host-frame})
  (deep-machine/install-and-init!)
  (counter/install-and-init!)
  (e2e/install-xray-default!)
  (e2e/dispatch-host machine-event)
  (e2e/dispatch-host [:counter/inc])
  (let [record (some #(when (= machine-event (:trigger-event %)) %)
                     (rf/epoch-history host-frame))]
    {:epoch-id (:epoch-id record) :dispatch-id (:dispatch-id record)}))

(defn- preconditions [{:keys [epoch-id dispatch-id]}]
  (is (some? epoch-id) "precondition: the machine event settled an epoch")
  (is (some? dispatch-id) "precondition: that epoch carries its dispatch-id")
  (is (not= epoch-id (:epoch-id (e2e/sub-xray [:rf.xray/focus])))
      "precondition: the spine starts on the head, not the machine epoch")
  (is (empty? (e2e/sub-xray [:rf.xray/machine-transitions-for-focused-event]))
      "precondition: the head event targets no machine")
  (is (not= :machines (e2e/sub-xray [:rf.xray/selected-tab]))
      "precondition: the Machines tab is not already selected, so its selection marks the drain"))

(defn- after-drain
  "Resolve once the focus command's drain has settled — marked by the tab it
  selects, which lands in the same drain as every other event the command
  fires — then run `assertions` and finish the async test."
  [done assertions]
  (-> (rf.test-support/poll-until
        #(= :machines (e2e/sub-xray [:rf.xray/selected-tab]))
        {:label "the focus command's drain settled" :timeout-ms 2000})
      (.then (fn [_] (assertions)))
      (.catch (fn [e] (is false (str "focus drain never settled: " (.-message e))) nil))
      (.then (fn [_] (trace-collector/reset-for-test!) (done)))))

(defn- assert-pinned-and-machines-follow [{:keys [epoch-id dispatch-id]}]
  (let [f       (e2e/sub-xray [:rf.xray/focus])
        records (e2e/sub-xray [:rf.xray/machine-transitions-for-focused-event])]
    (is (= epoch-id (:epoch-id f)) "the spine is pinned to the machine epoch")
    (is (= dispatch-id (:dispatch-id f)) "the spine is pinned to the machine event")
    (is (= :retro (:mode f)) "a pin off the head is RETRO, not LIVE head")
    (is (= [[:deep/main :idle :active]]
           (mapv (juxt :machine-id :from-state :to-state) records))
        "the Machines focused-event lens returns the pinned epoch's transition")
    (is (= :deep/main (:event-id (e2e/sub-xray [:rf.xray/machine-focused-epoch-cascade])))
        "the Machines cascade rows bind to the pinned epoch's machine")))

(deftest host-frame-focus-sent-async-pins-the-epoch-and-machines-follow
  (testing "rf2-2qtgt — `(focus! host-frame {:epoch-id … :dispatch-id …})`
            without `:sync?`, exactly as Story's evidence link sends it, moves
            the spine onto that epoch and the Machines panel follows"
    (let [coords (machine-epoch-then-unrelated-head!)]
      (preconditions coords)
      (is (:ok? (focus/focus! host-frame (assoc coords :panel :machines))))
      (async done
        (after-drain done
                     (fn []
                       (assert-pinned-and-machines-follow coords)
                       (is (= host-frame (:frame (e2e/sub-xray [:rf.xray/focus])))
                           "the spine is bound to the host frame")
                       (is (= host-frame (e2e/sub-xray [:rf.xray/view-scope-frame]))
                           "the frame step still re-binds the L2 view scope")))))))

(deftest frameless-focus-sent-async-pins-the-epoch-and-machines-follow
  (testing "control — the same command WITHOUT `:frame` moves the spine too.
            When the test above is red and this one green, the spine pin and
            the Machines subs are sound and the fault is the frame step's
            ordering"
    (let [coords (machine-epoch-then-unrelated-head!)]
      (preconditions coords)
      (is (:ok? (focus/focus! (assoc coords :panel :machines))))
      (async done
        (after-drain done #(assert-pinned-and-machines-follow coords))))))
