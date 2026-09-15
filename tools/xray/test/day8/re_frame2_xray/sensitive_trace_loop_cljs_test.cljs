(ns day8.re-frame2-xray.sensitive-trace-loop-cljs-test
  "Loop-proof tests for the `:sensitive?` trace-callback path
  (rf2-nk01x → rf2-qsjda).

  ## Why this file exists

  Xray registers `trace-collector/seed-trace-for-test!` as the
  `:rf.xray/trace-collector` callback at preload time. Whenever a
  `:sensitive?` trace event arrives, `collect-trace!` calls
  `config/note-suppressed!`, which itself dispatches
  `:rf.xray/note-sensitive-suppressed` into the `:rf/xray` frame so
  the reactive `[● REDACTED N]` indicator updates on the standard
  app-db-write path (rf2-0vxdn).

  Pre-fix that dispatch was the root of an infinite loop because the
  bookkeeping handler's `:rf.event/dispatched` trace would re-enter the
  collector. The fix landed `:rf.trace/no-emit? true` on the
  bookkeeping handlers' registration metadata; the framework's
  trace-emit fns short-circuit on the flag (Spec 009 §Trace-emission
  opt-out).

  NOTE: The handler-meta `:sensitive?` annotation has been removed in
  favour of path-marked sensitive classification. Sensitive trace
  events now come exclusively from the schema-derived overlap (the
  router's `prepare-handler-ctx` schema-sensitive computation drives
  the scope's `:sensitive?` stamp). The legacy end-to-end tests in
  this file that drove sensitivity via handler-meta `:sensitive? true`
  on user handlers are skipped — the loop-guard contract they covered
  is now exercised at the framework-trace level
  (`re-frame.trace-test`) and at the schemas-loaded story-side tests.

  The non-sensitive-mirror loop test remains relevant: it pins that
  trace events fanning out from the collector's bookkeeping handler
  do NOT re-enter the collector when the bookkeeping handler carries
  `:rf.trace/no-emit? true`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.trace :as rf.trace]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------
;;
;; `make-xray-runtime-fixture` (rf2-vj80u8) runs the `:all` reset tier
;; (Xray install/registry/mount sentinels + trace-collector rings) over the
;; plain-atom adapter; `:post-reset` re-registers Xray's handlers + the
;; `:rf/xray` frame, RE-installs the trace collector (the reset above cleared
;; it, so each test runs against the SAME wiring the production preload
;; installs), and clears the per-process counter + egress profile so each
;; test starts from the baseline. `:async? true` is the map form cljs.test
;; requires for section (6)'s `(async done …)` test, which has to cross a task
;; boundary to see the coalesced dispatch land.

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:async? true
     :post-reset
     (fn []
       (registry/register-xray-handlers!)
       ;; Allocate the :rf/xray frame so `note-suppressed!`'s dispatch
       ;; guard passes. Without the frame, `note-suppressed!` skips the
       ;; dispatch entirely — which would mask the loop scenario.
       (rf/make-frame {:id :rf/xray})
       ;; Re-install the trace collector. The reset tier above cleared it.
       (preload/register-trace-collector!)
       (trace-collector/reset-for-test!)
       (config/reset-suppressed-count!)
       (config/set-egress-profile! config/default-egress-profile))}))

;; ---- helpers ------------------------------------------------------------

(defn- register-non-sensitive-event! []
  (rf/reg-event :test/plain-bump
    (fn [{:keys [db]} [_ n]]
      {:db (assoc db :test/last-bump n)})))

(defn- drain-depth-exceeded?
  "True iff Xray's trace buffer contains a `:rf.error/drain-depth-
  exceeded` event. The framework's drain-depth limit terminates a
  runaway cascade; this is the signal that the loop wasn't contained."
  []
  (boolean
    (some (fn [ev] (= :rf.error/drain-depth-exceeded (:operation ev)))
          (trace-collector/buffer-for-test))))

;; ---- (1) + (2) removed --------------------------------------------------
;;
;; The end-to-end sensitive-cascade loop tests required handler-meta
;; `:sensitive? true` on a user handler to produce sensitive trace events.
;; That annotation has been removed; path-marked schema sensitivity is the
;; v2 driver and requires the schemas artefact (not loaded by this Xray
;; CLJS test build). The framework-level loop guard
;; (`:rf.trace/no-emit?`) is still covered at the trace-emit unit level.

;; ---- (3) non-sensitive mirror loop is also closed ----------------------

(deftest two-hundred-non-sensitive-dispatches-do-not-loop
  (testing "non-sensitive trace events flow into the buffer cleanly.
            Per rf2-e9s81 `collect-trace!` only swaps the buffer-state
            atom (no follow-on dispatch), so there is no
            `:rf.xray/note-trace-event` self-emit loop to close in
            the first place — the buffer fills purely from the
            host's own dispatches."
    (register-non-sensitive-event!)
    (dotimes [n 200]
      (rf/dispatch-sync [:test/plain-bump n]))
    (is (not (drain-depth-exceeded?))
        "no drain-depth-exceeded across 200 dispatches")
    ;; The buffer contains many trace events per dispatch (event/
    ;; dispatched, event/handled, event/db-changed, event/do-fx, ...)
    ;; — we don't assert an exact count, just that the runtime
    ;; survived all 200 dispatches.
    (is (pos? (count (trace-collector/buffer-for-test)))
        "buffer received the trace events from 200 plain dispatches")))

;; ---- (4) removed ---------------------------------------------------------
;;
;; The `opted-in-sensitive-dispatches-also-loop-proof` test required the
;; handler-meta `:sensitive? true` annotation. See the file-level note
;; above; this scenario now belongs in a schemas-loaded test surface.

;; ---- (5) Xray's own FRAMELESS sub reads are self-noise (rf2-izhgo) -------
;;
;; Mounting the shell cold-reads Xray's own subs inside one synchronous
;; render. Those reads run outside any event run, so core emits their
;; `:rf.sub/run` FRAMELESS (Spec 009 §Frame identity) and its sub-tag
;; classification fails a frameless read closed to `:sensitive? true`.
;; The frame-keyed `xray-internal-event?` cannot see them, so pre-fix each
;; one reached the privacy gate, was counted as a redacted host event, and
;; cost one `:rf.xray/note-sensitive-suppressed` dispatch into `:rf/xray`.
;; Measured on a Resources consumer: 126 such reads in one mount, past the
;; router's depth-100 cap — `:rf.error/drain-depth-exceeded` on every boot,
;; with Xray's own `:rf.xray.edn-inspector/set-width` events dropped
;; behind the flood.

(def ^:private shell-mount-burst
  "The measured boot burst: frameless `:rf.xray*` sub reads in one mount."
  126)

(defn- xray-queue-depth
  "Envelopes sitting undrained in `:rf/xray`'s router queue — the quantity
  the router's depth cap counts."
  []
  (count (:queue @(:router (rf.frame/frame :rf/xray)))))

(defn- emit-frameless-sub-run!
  "Emit a `:rf.sub/run` the way a render-time cold read does: through the
  framework's real emit path, outside any event run."
  [sub-id]
  (rf.trace/emit! :rf.sub :rf.sub/run
                  {:rf.sub/id      sub-id
                   :rf.sub/query-v [sub-id]
                   :rf.sub/value   1}))

(deftest xray-own-frameless-sub-reads-cost-no-queue-slot
  (testing "rf2-izhgo — a shell-mount burst of Xray's own frameless sub reads
            is structural self-noise: it bumps no REDACTED counter and adds
            nothing to `:rf/xray`'s queue. Pre-fix both moved by 126."
    ;; A BASELINE, not zero: the fixture's own `reset-suppressed-count!`
    ;; runs with `:rf/xray` already seated, so its reset dispatch is
    ;; legitimately sitting in the queue before the burst begins.
    (let [baseline (xray-queue-depth)]
      ;; Both a bare `rf.xray` id and a sub-namespace id — the shell mount
      ;; reads both shapes (`:rf.xray/mode`, `:rf.xray.edn-inspector/widths`).
      (doseq [sub-id (take shell-mount-burst
                           (cycle [:rf.xray/mode :rf.xray.edn-inspector/widths]))]
        (emit-frameless-sub-run! sub-id))
      (is (zero? (config/suppressed-count))
          "Xray's own reads are not redacted host data")
      (is (= baseline (xray-queue-depth))
          "so none of them costs a dispatch into Xray's own queue"))))

(deftest control-a-host-frameless-sub-read-is-still-redacted
  (testing "rf2-izhgo — the control for the regression above: the SAME
            frameless emit for a host sub still reaches the privacy gate and
            is counted. It proves the emit really arrives fail-closed
            sensitive (so a zero above is the filter, not an emit that never
            reached the gate), and that the filter is keyed on Xray's
            reserved namespace rather than dropping every frameless read."
    (dotimes [_ 3]
      (emit-frameless-sub-run! :app/current-user))
    (is (= 3 (config/suppressed-count))
        "a host sub's frameless read is still suppressed and counted")))

;; ---- (6) a HOST burst costs one dispatch per task (rf2-p03xh) ------------
;;
;; Section (5) removed the TRIGGER rf2-izhgo met (Xray counting its own reads)
;; but not the AMPLIFIER: one `:rf.xray/note-sensitive-suppressed` per
;; suppressed trace. A genuine host burst of more than ~100 frameless sensitive
;; traces in one task still carried `:rf/xray`'s queue past the router's
;; depth-100 cap, so the halt was misattributed to Xray and Xray's own queued
;; UI events were dropped behind it (the rf2-chs7 casualty shape).
;;
;; The burst runs through the real `rf.trace/emit!` → collector → privacy-gate
;; path. Dispatches are counted as ARRIVALS in `:rf/xray`'s router queue across
;; the whole run rather than read at one instant, because the un-coalesced and
;; coalesced shapes reach the queue on different ticks: a single read would
;; compare two different moments, not two behaviours.

(def ^:private host-burst
  "Past the router's depth-100 cap, with margin."
  150)

(defn- watch-note-arrivals!
  "Count every `:rf.xray/note-sensitive-suppressed` envelope that ARRIVES in
  `router`'s queue from now on, and return the counter atom. A drain only
  shrinks the queue, so summing the positive deltas counts arrivals."
  [router]
  (let [arrived (atom 0)
        notes   (fn [state]
                  (count (filter #(= :rf.xray/note-sensitive-suppressed
                                     (first (:event %)))
                                 (:queue state))))]
    (add-watch router ::note-arrivals
               (fn [_ _ old new]
                 (let [delta (- (notes new) (notes old))]
                   (when (pos? delta) (swap! arrived + delta)))))
    arrived))

(deftest a-host-burst-of-sensitive-traces-costs-one-dispatch-per-task
  (async done
    (let [router  (:router (rf.frame/frame :rf/xray))
          arrived (watch-note-arrivals! router)
          halts   (atom [])]
      (rf/register-listener! :trace ::drain-depth-spy
        (fn [ev]
          (when (= :rf.error/drain-depth-exceeded (:operation ev))
            (swap! halts conj ev))))
      (dotimes [_ host-burst]
        (emit-frameless-sub-run! :app/current-user))
      ;; Far past both the collector's coalescing task and the router's drain
      ;; task that follows it.
      (js/setTimeout
        (fn []
          (try
            (testing "rf2-p03xh — 150 frameless sensitive HOST traces emitted in
                      one task. Pre-fix: 150 dispatches, a drain halt at depth
                      100, and a badge stuck at 100."
              (is (= host-burst (config/suppressed-count))
                  "every suppressed trace is counted — the atom stays exact")
              (is (= 1 @arrived)
                  (str host-burst " suppressed traces in one task cost ONE "
                       "dispatch into :rf/xray"))
              (is (empty? @halts)
                  "no :rf.error/drain-depth-exceeded on :rf/xray")
              (is (= host-burst
                     (rf/with-frame :rf/xray
                       @(rf/subscribe [:rf.xray/suppressed-sensitive-count])))
                  "and the whole count reaches the REDACTED badge's sub"))
            (finally
              (remove-watch router ::note-arrivals)
              (rf/unregister-listener! :trace ::drain-depth-spy)
              (done))))
        100))))
