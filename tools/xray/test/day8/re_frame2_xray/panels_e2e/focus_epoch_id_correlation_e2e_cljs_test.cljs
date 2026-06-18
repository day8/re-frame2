(ns day8.re-frame2-xray.panels-e2e.focus-epoch-id-correlation-e2e-cljs-test
  "Multi-frame end-to-end coverage for rf2-rly4a — the `:rf.xray/focus`
  epoch-id correlation.

  ## The bug (rf2-rly4a, P1 regression)

  After a page refresh the parallel-frames testbed rendered Xray's
  Views + Trace panels EMPTY because `:rf.xray/focus` returned
  `:epoch-id nil`. Those panels are epoch-scoped — `:rf.xray/reactive-
  data` (Views) and `:rf.xray/trace-feed` (Trace) read the focused
  epoch RECORD via `focus.epoch-id` — so a nil epoch-id starves them.

  ## Root cause

  `compose-focus` derives `:epoch-id` by correlating the focused
  cascade `:dispatch-id` (from the raw trace stream's cascade list,
  depth 1000) against `:rf.xray/epoch-history` via
  `epoch-id-for-cascade` → `dispatch-id-of-epoch`. Pre-fix that helper
  walked the record's `:trace-events` for a `:dispatch-id` tag — but
  `:trace-events-keep` ELIDES `:trace-events` from all but the most-
  recent N records, and the post-settle reactive back-fill (rf2-qs6dl
  / rf2-wi900) pads `:trace-events` with nil-`:dispatch-id` sub-run /
  render events. So any focused cascade whose epoch was outside the
  keep-window correlated to nil → empty panels.

  (Per Mike pair-debug 2026-05-27 the framework default for
  `:trace-events-keep` was lifted from 5 to 50 — matching `:depth`
  so trace evicts atomically with its epoch. To bite the elision
  path under test the elision-sensitive deftests below explicitly
  shrink `:trace-events-keep` via `rf/configure!`.)

  ## Fix

  `build-record` now pins the settling cascade `:dispatch-id` as a
  first-class record slot (from the `:rf.event/run-start` tag, surfaced by
  `find-trigger-event`); `dispatch-id-of-epoch` reads the slot directly,
  falling back to the legacy `:trace-events` walk. The slot survives
  elision + back-fill, so the correlation is stable for every retained
  epoch.

  These tests drive the REAL pipeline (host dispatches → trace bus →
  epoch ring → Xray's subs) — no synthetic-cascade seam — exactly as
  `parallel_frames_e2e_cljs_test` does."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private frame-below :below)

(defn- install-xray! []
  (trace-collector/reset-for-test!)
  (xray-test-support/reset-all!)
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  (preload/register-epoch-collector!)
  nil)

(defn- install-counter! []
  (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
  (rf/reg-sub :counter/value (fn [db _] (:counter db))))

(defn- mount-xray-with-target! [target]
  (frame/reg-frame :rf/xray {})
  (let [buffer (trace-collector/buffer-for-test)]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer])
      (rf/dispatch-sync [:rf.xray/set-target-frame target])))
  nil)

(defn- sub-xray [query]
  (rf/with-frame :rf/xray @(rf/subscribe query)))

(defn- dispatch-host-frame [event frame-id]
  (rf/dispatch-sync event {:frame frame-id})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-trace-buffer (trace-collector/buffer-for-test)])
    (rf/dispatch-sync [:rf.xray/set-target-frame frame-id])))

(defn- focus-cascade! [dispatch-id frame-id]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-cascade dispatch-id frame-id])))

(defn- below-host-cascades []
  (filterv #(= frame-below (:frame %)) (sub-xray [:rf.xray/cascades])))

;; ---------------------------------------------------------------------------

(deftest head-focus-resolves-epoch-id-and-feeds-panels
  (testing "LIVE head focus — `:rf.xray/focus :epoch-id` is non-nil and
  the head epoch's `:dispatch-id` slot matches the head cascade. Views +
  Trace see the focused record (rf2-rly4a)."
    (install-xray!)
    (frame/reg-frame frame-below {})
    (install-counter!)
    (mount-xray-with-target! frame-below)
    (dispatch-host-frame [:counter/inc] frame-below)
    (let [focus    (sub-xray [:rf.xray/focus])
          history  (sub-xray [:rf.xray/epoch-history])
          head     (last (below-host-cascades))
          reactive (sub-xray [:rf.xray/reactive-data])
          trace    (sub-xray [:rf.xray/trace-feed])]
      (is (:head? focus) "test setup: focus is on the LIVE head")
      (is (some? (:epoch-id focus))
          (str "head focus epoch-id is nil — Views + Trace would render "
               "empty (rf2-rly4a). focus: " (pr-str focus)))
      ;; The head epoch's pinned :dispatch-id slot links it to the head cascade.
      (let [head-record (some #(when (= (:epoch-id focus) (:epoch-id %)) %)
                              history)]
        (is (= (:dispatch-id head) (:dispatch-id head-record))
            "head epoch's :dispatch-id slot must equal the head cascade id"))
      (is (:has-cascade? reactive)
          (str "Views (:rf.xray/reactive-data) saw no focused epoch record — "
               "rf2-rly4a. reactive-data: " (pr-str (select-keys reactive
                                                                 [:has-cascade?
                                                                  :epoch-id]))))
      (is (= (:epoch-id focus) (:epoch-id trace))
          "Trace (:rf.xray/trace-feed) epoch-id must equal the focused epoch-id")
      (is (pos? (:total trace))
          (str "Trace feed has no rows for the focused epoch — rf2-rly4a. "
               "trace: " (pr-str (select-keys trace [:total :epoch-id :empty-kind])))))))

(deftest retro-focus-on-trace-elided-epoch-resolves-epoch-id
  (testing "RETRO focus on an OLD cascade whose epoch had `:trace-events`
  elided by the `:trace-events-keep` window. Pre-rf2-rly4a the
  `dispatch-id-of-epoch` `:trace-events` walk returned nil for these
  records, so `focus.epoch-id` was nil and Views + Trace went empty. The
  first-class `:dispatch-id` slot survives elision — the correlation
  must resolve.

  Framework default for `:trace-events-keep` is now 50 (matches
  `:depth`); shrink to 3 here so the 10 cascades below produce
  trace-elided records that exercise the rly4a fix."
    (install-xray!)
    (frame/reg-frame frame-below {})
    (install-counter!)
    (mount-xray-with-target! frame-below)
    (rf/configure! {:epoch-history {:trace-events-keep 3}})
    ;; Drive 10 cascades so the earliest ones fall well outside the
    ;; keep-3 window (their `:trace-events` get dissoc'd).
    (dotimes [_ 10] (dispatch-host-frame [:counter/inc] frame-below))
    (let [history    (sub-xray [:rf.xray/epoch-history])
          ;; Pick the OLDEST host cascade — its epoch is trace-elided.
          old-id     (-> (below-host-cascades) first :dispatch-id)
          ;; Its epoch record (matched by the pinned slot).
          old-record (some #(when (= old-id (:dispatch-id %)) %) history)]
      (is (some? old-id) "test setup: there is an old host cascade")
      (is (some? old-record)
          "test setup: the old cascade correlates to a retained epoch record")
      (is (not (contains? old-record :trace-events))
          (str "test setup: the old epoch must have had :trace-events elided "
               "(it must be outside the keep-window for this test to bite). "
               "epoch-id: " (pr-str (:epoch-id old-record))))
      ;; Focus the old cascade — flips spine to RETRO.
      (focus-cascade! old-id frame-below)
      (let [focus    (sub-xray [:rf.xray/focus])
            reactive (sub-xray [:rf.xray/reactive-data])
            trace    (sub-xray [:rf.xray/trace-feed])]
        (is (= :retro (:mode focus)) "test setup: focusing a non-head cascade pins RETRO")
        (is (= (:epoch-id old-record) (:epoch-id focus))
            (str "RETRO focus on a trace-elided epoch resolved the WRONG (or nil) "
                 "epoch-id — rf2-rly4a. focus: " (pr-str focus)
                 " expected epoch-id: " (pr-str (:epoch-id old-record))))
        (is (:has-cascade? reactive)
            "Views saw no record for the trace-elided focused epoch — rf2-rly4a")
        (is (= (:epoch-id old-record) (:epoch-id trace))
            "Trace feed did not scope to the trace-elided focused epoch — rf2-rly4a")))))

(deftest dispatch-id-slot-pinned-on-every-retained-epoch
  (testing "Every retained epoch record carries the first-class
  `:dispatch-id` slot independent of `:trace-events` retention — the
  structural property the correlation now leans on (rf2-rly4a).

  Framework default for `:trace-events-keep` is now 50 (matches
  `:depth`); shrink to 3 here so 8 cascades produce a mix of
  trace-retained and trace-elided records."
    (install-xray!)
    (frame/reg-frame frame-below {})
    (install-counter!)
    (mount-xray-with-target! frame-below)
    (rf/configure! {:epoch-history {:trace-events-keep 3}})
    (dotimes [_ 8] (dispatch-host-frame [:counter/inc] frame-below))
    (let [history (sub-xray [:rf.xray/epoch-history])
          elided  (filterv #(not (contains? % :trace-events)) history)]
      (is (seq elided)
          "test setup: at least one record should have :trace-events elided")
      (is (every? #(some? (:dispatch-id %)) history)
          (str "some retained epoch lacks the pinned :dispatch-id slot — "
               "rf2-rly4a. epochs missing the slot: "
               (pr-str (mapv :epoch-id (remove #(some? (:dispatch-id %)) history)))))
      (is (every? #(some? (:dispatch-id %)) elided)
          "a trace-elided record must STILL carry the pinned :dispatch-id slot"))))
