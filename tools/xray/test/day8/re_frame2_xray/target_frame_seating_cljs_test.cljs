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
     would spend that one run on an empty boot-time ring.

  Node-test (`npm run test:cljs`) — no DOM needed, because the whole point is
  that nothing mounts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

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
            its hooks to run."
    (registry/register-xray-handlers!)
    (config/set-auto-open! false)
    (core/set-target-frame! :app/main)
    (is (some? (rf.frame/frame :rf/xray))
        "the facade seated the frame")
    (is (not (contains? (seeded-frame-ids) :rf/xray))
        "but did NOT mark it seeded — the first-mount hooks are still pending")
    (mount/ensure-xray-frame!)
    (is (contains? (seeded-frame-ids) :rf/xray)
        "and first open still gets its one hook fan-out")))
