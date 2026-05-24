(ns day8.re-frame2-xray.panel-gallery-trace-fixtures-cljs-test
  "Fixture-shape coverage for the panel_gallery Trace tab (rf2-ofoqu).

  After PR #1958 (rf2-td380) the Trace panel reads the FOCUSED EPOCH's
  `:trace-events` — resolved via the shared `focus-resolver` over
  `:rf.xray/focus` + `:rf.xray/epoch-history` — instead of the trace
  bus. The gallery's Trace variants were rewired (rf2-ofoqu) to seed
  `:epoch-history` (a vector of `:rf/epoch-record` maps) via
  `:rf.xray/sync-epoch-history` rather than the bus via
  `:rf.xray/sync-trace-buffer`.

  This test pins that the rewired fixture builders produce the shape
  the panel actually reads:

    1. Each history is a vector of `:rf/epoch-record` maps, each
       carrying an `:epoch-id` and a `:trace-events` slice.
    2. Running the SAME resolver + projection the panel's
       `:rf.xray/trace-feed` sub runs (no focus pinned → head-fallback)
       yields the panel's data map — non-empty rows for the populated
       variants, the `:no-events` empty-kind for the empty variant.

  Pure data → data; no React, no live runtime. The testbed namespace
  is on shadow's `:source-paths` so the `:node-test` build's
  `cljs-test$` regex discovers this file."
  (:require [cljs.test :refer-macros [deftest is testing are]]
            [panel-gallery.fixtures-trace :as fx]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-xray.panels.trace-helpers :as h]))

(defn- feed-from-history
  "Mirror `:rf.xray/trace-feed` (trace.cljs install!) with no focus
  pinned: resolve the head epoch via the focus-resolver's head-fallback
  and project its `:trace-events`."
  [history]
  (let [focus-epoch-id nil
        status (focus/resolve-focus-status focus-epoch-id history)
        record (focus/find-epoch-record focus-epoch-id history)]
    (h/project-feed-from-epoch record status)))

(defn- epoch-record? [r]
  (and (map? r)
       (some? (:epoch-id r))
       (vector? (:trace-events r))))

(deftest histories-are-epoch-record-vectors
  (testing "every history builder yields a non-empty vector of :rf/epoch-record maps"
    (are [history] (and (vector? history)
                        (seq history)
                        (every? epoch-record? history))
      (fx/empty-trace-history)
      (fx/short-trace-history)
      (fx/medium-trace-history)
      (fx/long-trace-history)
      (fx/errors-trace-history)
      (fx/flows-trace-history)
      (fx/mixed-op-types-history)
      (fx/redacted-trace-history)
      (fx/cross-frame-history)
      (fx/source-coord-history))))

(deftest populated-variants-render-non-empty-rows
  (testing "head-fallback resolves the variant epoch and the panel feed is non-empty"
    (are [history expected-rows]
        (let [{:keys [rows empty-kind]} (feed-from-history history)]
          (and (nil? empty-kind)
               (= expected-rows (count rows))))
      (fx/short-trace-history)      10
      (fx/medium-trace-history)     100
      (fx/errors-trace-history)     5
      (fx/mixed-op-types-history)   10
      (fx/redacted-trace-history)   3
      (fx/cross-frame-history)      12
      (fx/source-coord-history)     6)))

(deftest long-trace-feed-is-capped
  (testing "the 1000-row trail projects 1000 rows; cap handled by the view's overflow/capped-list"
    (let [{:keys [rows total empty-kind]} (feed-from-history (fx/long-trace-history))]
      (is (nil? empty-kind))
      (is (= 1000 (count rows)))
      (is (= 1000 total)))))

(deftest empty-variant-renders-no-events
  (testing "a focused epoch with an empty :trace-events slice → :no-events empty-state"
    (let [{:keys [rows empty-kind]} (feed-from-history (fx/empty-trace-history))]
      (is (= :no-events empty-kind))
      (is (empty? rows)))))

(deftest flows-variant-head-is-the-flow-epoch
  (testing "multi-op history: head-fallback renders the flow cascade, not the leading counter epoch"
    (let [{:keys [rows empty-kind]} (feed-from-history (fx/flows-trace-history))
          op-types (set (map :op-type rows))]
      (is (nil? empty-kind))
      (is (= 7 (count rows)) "the flow epoch's trail is 7 rows")
      (is (contains? op-types :rf.flow/computed)
          "the focused epoch surfaces the flow op-type"))))

(deftest source-coord-variant-rows-carry-source-coord
  (testing "the source-coord variant's projected rows carry a :source-coord string"
    (let [{:keys [rows]} (feed-from-history (fx/source-coord-history))]
      (is (seq rows))
      (is (every? :source-coord rows)
          "every projected row carries a file:line source-coord"))))
