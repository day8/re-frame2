(ns day8.re-frame2-xray.manual-epoch-delivery-cljs-test
  "rf2-kuky.82 AMEND 2(c) — the MANUAL-ONLY Xray epoch-delivery witness.

  ## What this pins

  `install.cljs` carries a bare `[re-frame.epoch]` require whose only job
  is to LOAD the epoch PRODUCER namespace on the manual startup path.
  `day8.re-frame2-xray.core` requires `install`, never `preload`, so a
  host that boots Xray by calling `core/init!` gets the producer through
  that require and through nothing else. The facade reaches the producer
  through a late-bind hook an unloaded namespace never populates, so
  without the require `(rf/register-listener! :epoch …)` degrades to the
  silent no-op it correctly performs for a host that must tolerate the
  artefact's absence — and Xray's Time-Travel panel would sit empty on
  every manual boot with nothing anywhere reporting why.
  `tools/xray/spec/011-Launch-Modes.md` §Epoch artefact is a hard Xray
  dependency states that contract; this namespace is its witness.

  ## Why it needs its OWN build

  `preload_decoupling_cljs_test` already covers the manual facade's
  inertness, but it `:require`s `day8.re-frame2-xray.preload`, and
  `preload.cljs` carries the SAME bare `[re-frame.epoch]` anchor. So does
  most of the always-on `:node-test` bundle. In any graph that loads the
  preload, the producer is present however `install.cljs` is spelled, and
  a delivery assertion there passes in both worlds — which is exactly the
  gap this file closes.

  The discriminating surface is therefore the DEPENDENCY GRAPH, not the
  assertions. `:node-test-xray-manual-epoch` (implementation/shadow-cljs.edn)
  selects THIS namespace and nothing else, so the compiled bundle contains
  only what the manual host's own graph contains: `re-frame.core`, a
  substrate adapter, the test-support fixture, and `day8.re-frame2-xray.core`.
  Nothing in that set reaches `re-frame.epoch` except `install.cljs`'s
  anchor — verified by removing the anchor and watching this namespace go
  red under `npm run test:xray-manual-epoch`.

  This namespace also ends in `-cljs-test`, so it rides the always-on
  `:node-test` build's `cljs-test$` regexp and its ASSERTIONS are graded on
  every PR. Only the graph-isolation half needs the focused build; in the
  aggregate bundle these tests pass whether or not the anchor is there.

  ## Deliberately NOT required here

  `re-frame.epoch`, `re-frame.epoch.state` and `day8.re-frame2-xray.preload`
  are all absent from the require list ON PURPOSE — each would load the
  producer itself and rescue a removed anchor. The producer-loading claim
  is read through `rf/features`, which is a pure keyword lookup in the
  always-loaded late-bind hooks atom and requires nothing optional; the
  delivery claim is read through `rf/register-listener!` and
  `rf/epoch-history`, the same public doors a host uses."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.core :as xray]))

;; No DOM under `:node-test`; the plain-atom adapter is the right substrate
;; (same choice as `re-frame.epoch-cljs-test`). The fixture snapshot/restores
;; the registrar and drives the epoch reset hooks (`:epoch/clear-history!`,
;; `:epoch/clear-epoch-listeners!`, `:epoch/reset-config!`) so each test starts
;; from a shipped-default ring.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- (1) the graph claim -------------------------------------------------

(deftest manual-graph-alone-loads-the-epoch-producer
  (testing "requiring the manual `core` facade — and nothing else optional —
            LOADS the epoch producer, so the artefact reads as present
            through the public feature-inspection door"
    (is (true? (get-in (rf/features) [:epoch :loaded?]))
        "re-frame.epoch is loaded by install.cljs's bare require, reached
         through `day8.re-frame2-xray.core` alone — no preload, no direct
         require from this namespace")))

;; ---- (2) the delivery claim ----------------------------------------------

(deftest manual-init!-receives-a-dispatched-events-assembled-epoch
  (testing "after the manual public `core/init!`, an `:epoch` listener
            registered through the facade RECEIVES the assembled
            `:rf/epoch-record` for a dispatched event — the fan-out
            `install/register-epoch-collector!` itself rides, proving that
            registration is not the silent no-op an unloaded producer
            would make of it"
    (let [seen (atom [])]
      (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

      ;; The manual startup path under test. `init!` wires the registry
      ;; handlers, the trace + epoch collectors, the browser exports and the
      ;; keybinding; it is the documented alternative to `:devtools/preloads`.
      (xray/init!)

      ;; A probe on the same public stream Xray's own collector attaches to,
      ;; registered AFTER init! so nothing init! dispatches is counted.
      ;; Filtered to the host frame: `init!` seats Xray's own `:rf/xray`
      ;; frame, whose epochs are not this assertion's subject.
      (rf/register-listener! :epoch ::witness
                             (fn [record] (swap! seen conj record)))

      (rf/dispatch-sync [::bump])

      (let [records (filterv #(= :rf/default (:frame %)) @seen)
            record  (first records)]
        (is (= 1 (count records))
            "exactly one fan-out for one dequeued event on the host frame")
        (is (= ::bump (:event-id record))
            ":event-id names the dispatched event")
        (is (= [::bump] (:trigger-event record))
            ":trigger-event is the full event vector")
        (is (= {:n 1} (:db-after record))
            ":db-after is the post-settle snapshot — an ASSEMBLED record,
             not a bare notification")
        (is (= :ok (:outcome record))
            ":outcome :ok pins the event-settle outcome"))

      (let [history (rf/epoch-history :rf/default)]
        (is (= 1 (count history))
            "the same epoch is retained in the frame's ring — the vector
             Xray's Time-Travel panel re-reads")
        (is (= ::bump (:event-id (last history)))
            "and it is the dispatched event's record")))))
