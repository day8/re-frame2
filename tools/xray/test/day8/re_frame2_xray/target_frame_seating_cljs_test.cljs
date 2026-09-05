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

  ## The three deftests

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
