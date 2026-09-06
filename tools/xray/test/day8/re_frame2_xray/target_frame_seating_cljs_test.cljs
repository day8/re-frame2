(ns day8.re-frame2-xray.target-frame-seating-cljs-test
  "rf2-avi7 — Xray's own frame is seated when the host RUNTIME comes up, not
  when Xray's UI opens, so a host can drive Xray by dispatch without ever
  opening the shell.

  ## The gap this closes

  Xray registers its whole `:rf.xray/*` instruction set at preload, but the
  `:rf/xray` frame those handlers write to used to be created by `open!` — a
  React commit. Between the two, Xray was ADDRESSABLE BUT NOT WRITABLE: every
  dispatch into `:rf/xray` recovered-but-emitted `:rf.error/frame-destroyed`
  (Spec 002 §Run-to-completion, the rf2-2hvga recover-but-emit ruling), the
  host's intent was silently dropped, and — because `error-emit/error-source-
  coord` resolves a `frame-destroyed` coord out of the `[:event id]` registry —
  the diagnostic named the HANDLER's registration site rather than the caller.

  A host that sets `:rf.xray/auto-open? false` — a supported, documented
  config — never left that window at all until it opened a panel of its own,
  so `core/set-target-frame!`, `core/focus!` and any direct `:rf.xray/*`
  dispatch were dead on arrival for its whole session. That is the general
  case; the field report was a Story feature-load run emitting one such record
  per page load.

  ## The deftests

  1. `control-…` proves the emission is REAL on an unseated frame, and pins
     the exact record shape the field report carried. Without it, (2)'s empty
     capture would be vacuous — an `:errors` listener that never fires reads
     identically to one with nothing to report.
  2. `host-dispatch-…` is the REGRESSION: with auto-open OFF and the shell
     never mounted, the dispatch lands and the slot reads back.
  3. `reading-a-destroyed-…` pins the REFUTATION. The original diagnosis put
     the fault in `epoch.cljs`'s `(rf/epoch-history target)` read against a
     destroyed TARGET frame. That read cannot emit: it resolves through the
     `:epoch/epoch-history` late-bind hook to a plain ring-buffer map lookup
     that consults no frame registry. Keeping the case here stops the fix
     drifting back into the handler.
  4. `the-facade-seats-…` is rf2-88f1's regression. rf2-avi7's seat runs from
     `boot-on-runtime-ready!`'s BOUNDED 50ms POLL, so it closes the window for
     a host that dispatches later — not for one whose boot calls `rf/init!`
     and re-orients the target on the same turn, which lands INSIDE the poll
     window and got `frame-destroyed` anyway (measured twice per Story page).
     `core/set-target-frame!` now seats before it dispatches, so the facade is
     correct at boot instant for every host rather than for the punctual ones.
     Deftest (1) is its control and stays green: the raw hand-rolled dispatch
     still emits, which is why hosts must take the facade.
  5. `the-facade-seat-does-not-burn-…` pins WHICH seat the facade takes —
     `mount/ensure-seated!`, not `mount/ensure-xray-frame!`. The latter also
     runs the run-once first-mount hook fan-out, whose whole job is to harvest
     the rings the user filled before opening Xray; firing it from a setter
     would spend that one run on an empty boot-time ring. It ALSO pins the
     other half of that split: deferring the fan-out means the fan-out then
     lands on a frame the host has already targeted, so first-open discovery
     must not overwrite the explicit target it finds there.
  6. `first-open-discovery-yields-…` is the same contract with a COMPETING
     candidate: an explicit target survives even when the ring offers a
     focusable bundle from a different frame.
  7. `first-open-discovery-selects-…` is (6)'s control — same ring, same
     entry point, no explicit target — proving the preservation in (5)/(6) is
     a deference to the host and not a disabled discovery policy.

  Node-test (`npm run test:cljs`) — no DOM needed, because the whole point is
  that nothing mounts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; `mount/teardown!` clears BOTH the mount state and the `boot-on-runtime-
;; ready!` run-once latch, which `mount/reset-for-test!` (the `seeded-frame-ids`
;; guard only) does not touch — without it the second deftest's boot call is a
;; no-op on the first one's latch and passes for the wrong reason.
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier :runtime :post-reset mount/teardown!}))

(def ^:private capture-id ::error-capture)

(defn- capture-errors!
  "Attach an always-on `:errors` listener collecting records into an atom."
  []
  (let [seen (atom [])]
    (rf/register-listener! :errors capture-id (fn [record] (swap! seen conj record)))
    seen))

(defn- frame-destroyed-records [seen]
  (rf/unregister-listener! :errors capture-id)
  (filterv #(= :rf.error/frame-destroyed (:error %)) @seen))

(defn- dispatch-target-frame! [frame-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame frame-id])))

(defn- seeded-frame-ids
  "The set of frame-ids whose first-mount hooks have already fired. Read
  through the var because the atom is `mount`-private: what is under test is
  precisely that the facade's seat does NOT touch this guard, and no public
  reader exists (or should — nothing in production asks)."
  []
  @@#'mount/seeded-frame-ids)

(defn- flush-xray-queue!
  "Drain whatever `:rf/xray`'s router is holding, without waiting on the host
  task scheduler. `dispatch-sync!` seeds at the FRONT of the queue and then
  runs the drain loop to fixed point, so a benign chrome event flushes any
  async dispatch already enqueued behind it. `:rf.xray/clear-reset-flash` is
  that benign event — a `dissoc` of a slot nothing here sets."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/clear-reset-flash])))

(defn- xray-read [query]
  (rf/with-frame :rf/xray (rf/subscribe-once query)))

(defn- focus-frame
  "The `[:focus :frame]` axis, read through the spine's raw `:rf.xray/focus-
  slot` sub. `:rf.xray/set-target-frame` writes this in lockstep with
  `:target-frame` (rf2-ulpp8), so a survival claim about one is only half a
  claim: the axes encode the same gesture and both have to hold."
  []
  (:frame (xray-read [:rf.xray/focus-slot])))

(def ^:private main-epochs
  "Stand-in for the framework's per-frame epoch ring on `:app/main`. Non-empty
  on purpose: `:rf.xray/set-target-frame` re-seeds `:epoch-history` from
  `(rf/epoch-history target)`, so a target silently reset to nil leaves the
  slot `[]` — a discriminating value the empty ring could not supply."
  [{:epoch-id      :e-main-1
    :frame         :app/main
    :db-before     {}
    :db-after      {:ready? true}
    :trigger-event [:app/boot]
    :event-id      :app/boot
    :trace-events  []}])

(defn- stub-epoch-history
  "`rf/epoch-history` narrowed to the frames these deftests name. In production
  the framework's ring already carries these records; stubbing keeps the
  assertion about Xray's seeding, not about the ring."
  [frame-id]
  (case frame-id
    :app/main main-epochs
    []))

(defn- pre-mount-dispatch-event
  "A trace event the projection groups into a focusable event-bundle for
  `frame-id` — the shape `event/dispatched` emits. Seeded into the ring so
  first-open discovery has a candidate to find."
  [id dispatch-id frame-id event-id]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id dispatch-id
               :frame                frame-id
               :rf.event/v           [event-id]}})

;; ---- (1) control — the emission is real on an unseated frame -------------

(deftest control-an-unseated-xray-frame-drops-the-dispatch-and-emits
  (testing "With `:rf/xray` unseated, a host dispatch of
            `:rf.xray/set-target-frame` recovers-but-emits
            `:rf.error/frame-destroyed` attributed to `:rf/xray` — the exact
            record the field report carried, and the machinery deftest (2)
            relies on being live."
    (registry/register-xray-handlers!)
    (is (nil? (rf.frame/frame :rf/xray))
        "precondition: nothing has seated Xray's frame")
    (let [seen (capture-errors!)]
      (dispatch-target-frame! :app/main)
      (let [records (frame-destroyed-records seen)]
        (is (= 1 (count records))
            "exactly one recover-but-emit per rejected dispatch")
        (is (= {:error    :rf.error/frame-destroyed
                :event-id :rf.xray/set-target-frame
                :frame    :rf/xray}
               (select-keys (first records) [:error :event-id :frame]))
            "the `:frame` slot names the DISPATCH TARGET — Xray's own frame,
             which is the frame that is missing")))))

;; ---- (2) the regression — a host drives Xray without opening it ----------

(deftest host-dispatch-lands-without-ever-opening-xray
  (testing "rf2-avi7 — `boot-on-runtime-ready!` seats `:rf/xray` as soon as a
            substrate adapter exists, even with `:rf.xray/auto-open? false`,
            so a host that never opens the shell can still tell Xray which
            frame to observe."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (mount/boot-on-runtime-ready!)
    (is (false? (mount/mounted?))
        "the shell is NOT opened — only the frame is seated")
    (is (some? (rf.frame/frame :rf/xray))
        "the runtime-ready boot seated Xray's frame")
    (let [seen (capture-errors!)]
      (dispatch-target-frame! :app/main)
      (is (empty? (frame-destroyed-records seen))
          "the dispatch landed — nothing recovered-but-emitted"))
    (is (= :app/main (core/target-frame))
        "and the host's intent is readable through the public facade")))

;; ---- (3) refutation pin — the epoch read is not the emitter --------------

(deftest reading-a-destroyed-target-frames-epoch-history-is-silent
  (testing "rf2-avi7 — targeting a DESTROYED host frame emits nothing.
            `:rf.xray/set-target-frame`'s `(rf/epoch-history target)` is a ring
            lookup keyed by frame-id (`re-frame.epoch.state/history-for`); it
            consults no frame registry and cannot raise `frame-destroyed`. An
            unresolvable target simply reads the empty ring, which is the
            documented contract — so no guard belongs on that read."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (mount/boot-on-runtime-ready!)
    (rf/make-frame {:id :host/gone})
    (rf/destroy-frame! :host/gone)
    (let [seen (capture-errors!)]
      (dispatch-target-frame! :host/gone)
      (is (empty? (frame-destroyed-records seen))
          "reading a destroyed frame's epoch history is not an error"))
    (is (= :host/gone (core/target-frame))
        "the target is recorded even though the frame is gone — Xray's picker
         is free to name a frame that has since been torn down")
    (is (= [] (rf/with-frame :rf/xray (rf/subscribe-once [:rf.xray/epoch-history])))
        "and its history reads as the empty ring")))

;; ---- (4) rf2-88f1 — the facade is correct at BOOT INSTANT ----------------

(deftest the-facade-seats-xrays-frame-before-it-dispatches
  (testing "rf2-88f1 — `core/set-target-frame!` seats `:rf/xray` itself, so a
            host that re-orients the target on the same turn as `rf/init!`
            (inside `boot-on-runtime-ready!`'s 50ms poll window, before its
            seat has run) still lands its intent instead of collecting
            `:rf.error/frame-destroyed`. `boot-on-runtime-ready!` is
            DELIBERATELY not called here: this deftest stands exactly where
            deftest (1)'s control stands, and the only difference is which
            entry point the host reached for."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (is (nil? (rf.frame/frame :rf/xray))
        "precondition: nothing has seated Xray's frame — same start state as
         the control in deftest (1)")
    (let [seen (capture-errors!)]
      (core/set-target-frame! :app/main)
      (is (some? (rf.frame/frame :rf/xray))
          "the facade seated Xray's frame before dispatching")
      (flush-xray-queue!)
      (is (empty? (frame-destroyed-records seen))
          "and nothing recovered-but-emitted — the dispatch had a frame to
           land in"))
    (is (= :app/main (core/target-frame))
        "the host's intent is readable back through the public facade")))

(deftest the-facade-seat-does-not-burn-the-first-mount-seed
  (testing "rf2-88f1 — the facade takes `mount/ensure-seated!`, NOT
            `mount/ensure-xray-frame!`. The difference is the first-mount hook
            fan-out, which harvests the trace + epoch rings the user produced
            BEFORE opening Xray and is run-once per frame-id: firing it from a
            setter would snapshot an empty ring at boot and then skip, leaving
            a later first open with no pre-open history to show. So after the
            facade has seated the frame, `ensure-xray-frame!` must still have
            its hooks to run.

            Deferring the fan-out is only half a contract, and the other half
            is asserted below: because the hooks now run AFTER the host has
            targeted a frame, first-open discovery meets an explicit target
            that was not there before rf2-88f1. It must defer to it. On a cold
            ring `spine/focusable-head-frame-id` finds no candidate and the
            discovery seed is `defaults/default-target-frame` = nil, which
            `:rf.xray/set-target-frame` writes as a RESET — dissoc'ing
            `:target-frame`, clearing `[:focus :frame]` and emptying
            `:epoch-history`. The host's boot intent would be gone at first
            open, silently, with no error to read."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (with-redefs [rf/epoch-history stub-epoch-history]
      (core/set-target-frame! :app/main)
      (is (some? (rf.frame/frame :rf/xray))
          "the facade seated the frame")
      (is (not (contains? (seeded-frame-ids) :rf/xray))
          "but did NOT mark it seeded — the first-mount hooks are still pending")
      (flush-xray-queue!)
      (is (= :app/main (core/target-frame))
          "precondition: the host's explicit pre-open target has landed")
      (mount/ensure-xray-frame!)
      (is (contains? (seeded-frame-ids) :rf/xray)
          "and first open still gets its one hook fan-out")
      (is (= :app/main (core/target-frame))
          "the explicit pre-open target SURVIVES first open — discovery on an
           empty ring must not reset a target the host already chose")
      (is (= :app/main (focus-frame))
          "and so does the `[:focus :frame]` axis the reducer moves with it —
           a surviving `:target-frame` beside a cleared focus slot would leave
           the L2 list unfiltered against the frame the panels name")
      (is (= main-epochs (xray-read [:rf.xray/epoch-history]))
          "and `:epoch-history` still reads the target's ring rather than the
           `[]` a reset to nil leaves behind"))))

;; ---- (6) the same contract against a COMPETING discovery candidate -------

(deftest first-open-discovery-yields-to-an-explicit-target
  (testing "rf2-88f1 — the empty-ring case above resets the target to nil; a
            ring that DOES carry a focusable bundle reaches the same loss by
            the other road, replacing the host's target with whichever frame
            happens to head the pre-open trace. Deftest (7) is this deftest
            with the explicit target removed and everything else identical,
            so the pair pins deference rather than a disabled policy."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (with-redefs [rf/epoch-history stub-epoch-history]
      (trace-collector/seed-trace-for-test!
        (pre-mount-dispatch-event 1 100 :other/frame :other/event))
      (core/set-target-frame! :app/main)
      (flush-xray-queue!)
      (is (= :app/main (core/target-frame))
          "precondition: the host targeted `:app/main` before first open")
      (mount/ensure-xray-frame!)
      (is (= :app/main (core/target-frame))
          "the host's target outranks the discovered head bundle's frame")
      (is (= :app/main (focus-frame))
          "on the focus axis too")
      (is (= main-epochs (xray-read [:rf.xray/epoch-history]))
          "and the epoch history stays keyed on the surviving target"))))

;; ---- (7) the control — discovery still runs when nothing was chosen ------

(deftest first-open-discovery-selects-when-no-explicit-target
  (testing "rf2-88f1 control — same ring and same entry point as deftest (6),
            with no pre-open `set-target-frame!`. The mount-time discovery
            policy (EP-0002's operator-present tier) still resolves the head
            focusable bundle's frame, so the preservation above is a deference
            to an explicit host choice and NOT a first-open seed that stopped
            writing."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (with-redefs [rf/epoch-history stub-epoch-history]
      (trace-collector/seed-trace-for-test!
        (pre-mount-dispatch-event 1 100 :other/frame :other/event))
      (mount/ensure-seated!)
      (is (nil? (core/target-frame))
          "precondition: nothing has chosen a target")
      (mount/ensure-xray-frame!)
      (is (= :other/frame (core/target-frame))
          "discovery selects the head focusable bundle's frame, as it did
           before rf2-88f1")
      (is (= :other/frame (focus-frame))
          "aligning the focus axis with it"))))
