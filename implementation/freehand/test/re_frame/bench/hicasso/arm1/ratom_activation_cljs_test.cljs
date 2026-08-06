(ns re-frame.bench.hicasso.arm1.ratom-activation-cljs-test
  "**ARM 1 UNDER THE STOCK REAGENT ADAPTER** — a committed cell is on the
  substrate's push path, so a write after the mount is re-render work
  (rf2-2kshh).

  THE DEFECT THIS PINS. `runtime/wire-cell!` is the whole of a cell's
  attachment to the substrate, and it used to perform three acts:
  subscribe, deref once for the baseline, `add-watch`. Under the ratom
  family that is a channel that cannot fire. A subscription there IS a
  bare `reagent.ratom/Reaction`, built deliberately without `:auto-run`,
  and a Reaction learns its sources only through `deref-capture`: the
  baseline deref is taken outside `*ratom-context*`, so it runs the body
  raw and leaves `watching` nil. The reaction is then absent from
  app-db's watcher set, the `add-watch` above never fires,
  `runtime/mark-dirty!` never fires — that watch is its only caller —
  `flush!` finds an empty dirty set, and React is handed nothing. **The
  arm painted once at mount and was deaf from that instant.**

  It is the identical defect the observation port carried as rf2-8cnxg
  and repaired at `re-frame.substrate.observation/build-node-handle!`
  (\"ACTIVATE, then watch, then observe — and the order is the whole
  fix\"). Arm 1's runtime is a second consumer of the same channel that
  never received the call. The port's own unit arms are
  `re-frame.observation-port-activates-ratom-node-cljs-test`, whose
  docstring already names and excludes the cheap wrong diagnosis: **even
  `reagent.core/flush` moves nothing** when the node never captured.

  WHY IT WENT UNCAUGHT FOR SO LONG. Every other suite in this arm
  installs the **UIx** adapter, whose React-hook spine wires one watch
  per source at construction and is push-based from birth — the activate
  op is a routed no-op there and the channel works without it. The
  Hicasso clock bench likewise gives the candidate its own UIx segment
  on purpose. rf2-2rtt6.76's P0 allocation row was the first time in the
  programme a write was driven at lad/hicasso with the *Reagent* adapter
  installed, and the arm read flat at the FLOOR's figure — an arm with
  no subscription at all reads the same, because both re-render never.

  WHAT EACH ARM IS FOR. The first is the bead's own smallest
  reproduction, taken through the arm's real seam rather than by hand:
  render a body, commit it at [[re-frame.bench.hicasso.arm1.runtime/commit-boundary!]]
  — the same `subscribe` closure `useSyncExternalStore` calls — write,
  drain, and count the notifications React would have received. The rest
  are the adversarial half, because \"make it notify\" has cheap wrong
  answers a movement-only test rates green: a chatty channel that fires
  on writes that moved nothing, and an attachment that works at birth
  but not on the rebuild `invalidate-cell!` performs.

  No DOM and no React: the claim is about the notification channel, and
  the mounted counterpart where a real page repaints is
  `re-frame.bench.hicasso.arm1.ratom-activation-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     ;; The rebuild claim rides a macrotask — `invalidate-cell!` defers
     ;; the re-attachment on purpose — so the map shape, with `async`.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-ratom-activation)

(defn- register! []
  (rf/reg-sub :hic/n (fn [db _] (:n db)))
  (rf/reg-event :hic/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
  (rf/reg-event :hic/set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)})))

(defn- fresh!
  ([] (fresh! 1))
  ([n]
   (rt/reset-runtime!)
   (register!)
   (rf/make-frame {:id frame-id :initial-events [[:hic/set-n n]]})
   frame-id))

(defn- key-of [query] [frame-id query])

;; White-box, and only ever COROBORATING: `watching` is the Reagent field
;; that says "this reaction is subscribed to its sources". Read alone it
;; would go vacuously nil against any object that is not a Reaction, so
;; every row that consults it also asserts the behaviour it explains.
;; `^clj` because it is a deftype field rather than a JS property.
(defn- capturing? [rx] (some? (.-watching ^clj rx)))

(defn- mounted!
  "A boundary at the seam React occupies: render the body, commit its
  reads, and hand back the notification counter and the release. The
  same shape `arm1/runtime-cljs-test` uses, under a different adapter."
  [body-fn]
  (rt/render-body frame-id body-fn {})
  (let [entry (rt/last-reads)
        hits  (volatile! 0)]
    {:entry    entry
     :hits     hits
     :release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))}))

(defn- write!
  "The arm's synchronous door, then Reagent's own drain.

  `reagent.core/flush` is the drain the P0 bench performs for this
  segment (inside one `flushSync`), and it is what turns an ACTIVATED
  reaction's enqueued recompute into the `notify-w` that reaches the
  cell's watch. It is deliberately the only thing added here: the bead's
  falsified hypothesis was that the bench's drain was at fault, and a
  reaction that never captured is not enqueued by anything, so this call
  moves precisely nothing until [[wire-cell!]] activates."
  [event]
  (rt/dispatch! frame-id event)
  (r/flush)
  nil)

(def ^:private !last-read
  "What the body's `sub` read on its most recent run — so a row can assert
  the boundary reads the MOVED value back through the cell it holds, and
  not merely that it was pinged."
  (atom ::never))

(defn- readout-body [_]
  (let [n (rt/sub [:hic/n])]
    (reset! !last-read n)
    [:span (str n)]))

;; ===========================================================================
;; the bead's reproduction: a committed cell is on the push path
;; ===========================================================================

(deftest a-committed-cell-notifies-its-boundary-under-the-reagent-adapter
  (testing "rf2-2kshh's exact reproduction. One boundary, one query, the
            REAGENT adapter installed: the commit must leave the cell's
            reaction CAPTURING, so a later write reaches React's own
            `onStoreChange`. Before the fix the cell held a watch on a
            reaction that could not fire and the count stayed at zero
            for the life of the mount"
    (fresh! 1)
    (let [b  (mounted! readout-body)
          rx (rt/cell-reaction (key-of [:hic/n]))]
      (try
        (is (some? rx)
            "precondition — the commit built a cell and it holds a reaction")
        (is (satisfies? ratom/IRunnable rx)
            "precondition — under this adapter a subscription IS a bare
             Reagent Reaction, so a silent channel here is the arm's fault
             and not the host's")
        (is (capturing? rx)
            "the commit ACTIVATED it: the reaction is subscribed to its
             sources. Before the fix this was nil — watchable, watched,
             and unable to notify")
        (is (zero? @(:hits b))
            "and the activation itself fanned nothing at the boundary — it
             runs BEFORE the watch, so there is no priming notification")

        (write! [:hic/set-n 2])
        (is (= 1 @(:hits b))
            "THE MEASUREMENT THAT WAS ZERO: the write became re-render
             work. This is the whole of the arm's dirty channel — the
             cell's watch is `mark-dirty!`'s only caller")

        (testing "…and the channel stays armed rather than firing once
                  and lapsing"
          (write! [:hic/set-n 3])
          (is (= 2 @(:hits b))))

        (testing "…and the boundary reads the moved value back through the
                  cell it holds, so the notification is not a bare ping"
          (reset! !last-read ::never)
          (rt/render-body frame-id readout-body {})
          (is (= 3 @!last-read)
              "the re-render the notification bought read 3 — a WARM read,
               straight off the cell's reaction"))
        (finally
          ((:release! b)))))))

;; ===========================================================================
;; the adversarial half — activation must not make the channel chatty
;; ===========================================================================

(deftest a-write-that-moved-nothing-notifies-no-boundary
  (testing "the cheap wrong answer a movement-only test would rate green:
            a channel that fires on every write. An equal re-write and a
            write to a key the sub never reads must both stay silent"
    (fresh! 7)
    (let [b (mounted! readout-body)]
      (try
        (write! [:hic/set-n 7])
        (is (zero? @(:hits b))
            "an equal re-write moved nothing, so nothing was fanned")

        (write! [:hic/set-other :noise])
        (is (zero? @(:hits b))
            "…and neither did a write to an unrelated app-db key")

        (testing "positive control — the two silences above are silences,
                  not a dead channel that would make this row vacuous"
          (write! [:hic/set-n 8])
          (is (= 1 @(:hits b))))
        (finally
          ((:release! b)))))))

;; ===========================================================================
;; the rebuild path — one insertion has to cover both callers
;; ===========================================================================

(deftest a-rebuilt-cell-is-activated-too
  (testing "`wire-cell!` is called from `acquire-cell!` at birth and again
            from `invalidate-cell!`'s deferred rebuild, so a re-registered
            query must come back on the push path rather than silently
            deaf. A `reg-sub` replacement evicts the sub-cache entry and
            disposes the reaction; the rebuild lands on a macrotask"
    (fresh! 1)
    (let [b (mounted! readout-body)]
      (write! [:hic/set-n 2])
      (is (= 1 @(:hits b)) "precondition — the original attachment notifies")
      ;; The replacement. Same query id, a different computation.
      (rf/reg-sub :hic/n (fn [db _] (* 10 (:n db))))
      (async done
        (js/setTimeout
          (fn []
            (try
              (let [rx     (rt/cell-reaction (key-of [:hic/n]))
                    before @(:hits b)]
                (is (some? rx) "the rebuild re-subscribed")
                (is (capturing? rx)
                    "and ACTIVATED the replacement — the second caller of
                     `wire-cell!` gets the same repair as the first")
                (write! [:hic/set-n 3])
                (is (= (inc before) @(:hits b))
                    "a write after the re-registration still becomes
                     re-render work: the rebuilt attachment notifies, once"))
              (finally
                ((:release! b))
                (done))))
          0)))))
