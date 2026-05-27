(ns day8.re-frame2-xray.views.diff-mode-toggle-cljs-test
  "Unit tests for the shared three-mode diff toggle widget
  (`views.diff-mode-toggle` — rf2-yqjrd / rf2-n2jig).

  Cluster F (rf2-hf6gb) — the widget is consumed by every Xray diff
  surface (App-DB, Machine Inspector snapshot, Epoch HANDLER `:db`,
  Epoch SUBSCRIPTIONS) and exercised in 10+22 stories, but the widget
  itself shipped without direct unit tests. This file pins the
  regression-fragile contracts:

    - per-mode label rendering (`:diff` / `:full` / `:full+diff` +
      `name` fallback for unknown keywords).
    - per-mode testid suffix (`:full+diff` → `full-with-diff`; other
      modes use their `name`).
    - the public `reg-mode-sub+event!` helper installs a sub + an
      event-db with the canonical naming scheme for both top-level
      (`:rf.xray.app-db`) and sub-surface (`:rf.xray.epoch/db`)
      surface-id shapes.
    - the rendered hiccup shape — three buttons keyed by mode, active
      button reports `aria-pressed=\"true\"`, per-button testids follow
      the `<prefix>-<suffix>` convention.
    - the rf2-fytu4 `:label` opt — optional prefix label wraps the bar
      with a deterministic label-span testid.
    - rf2-xlmhh — the bar no longer carries `:data-mode` (retired axis
      per `tools/xray/spec/Conventions.md` §264).

  Pure-data unit tests; no DOM mount. Default for new Causa/Story
  tests per `feedback-causa-story-cljs-unit-tests-not-playwright`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.views.diff-mode-toggle :as widget]))

;; Fresh re-frame runtime per test so `reg-sub` + `reg-event-db`
;; installs from one test don't bleed into the next.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- private-fn handles -------------------------------------------------
;;
;; The two internal helpers are marked `^:private` (rf2-2zq54) — reach
;; through the var to exercise them under test without weakening the
;; widget's public surface.

(def ^:private mode-label        @#'widget/mode-label)
(def ^:private mode-testid-suffix @#'widget/mode-testid-suffix)

;; ---- (1) mode-label coverage -------------------------------------------

(deftest mode-label-canonical-modes
  (testing "`mode-label` returns the documented human label for each
            canonical mode keyword. The literal `+` in `full+diff` is
            intentional per the findings-doc grammar lock (the symbol
            IS the cue)."
    (is (= "diff"      (mode-label :diff)))
    (is (= "full"      (mode-label :full)))
    (is (= "full+diff" (mode-label :full+diff)))))

(deftest mode-label-fallback-uses-name
  (testing "`mode-label` falls through to `(name m)` for any unknown
            keyword so future modes paint a label without an explicit
            case branch."
    (is (= "custom"        (mode-label :custom)))
    (is (= "experimental"  (mode-label :experimental)))))

;; ---- (2) mode-testid-suffix coverage -----------------------------------

(deftest mode-testid-suffix-canonical-modes
  (testing "`mode-testid-suffix` sanitises `:full+diff` to a hyphenated
            alias because `+` is not a valid CSS-selector tail. All
            other modes use their `name` verbatim."
    (is (= "diff"            (mode-testid-suffix :diff)))
    (is (= "full"            (mode-testid-suffix :full)))
    (is (= "full-with-diff"  (mode-testid-suffix :full+diff)))))

(deftest mode-testid-suffix-fallback-uses-name
  (testing "Unknown keywords fall through to `(name m)` — the `+`
            sanitisation is the ONLY special case."
    (is (= "custom" (mode-testid-suffix :custom)))))

;; ---- (3) reg-mode-sub+event! — top-level surface -----------------------

(deftest reg-mode-sub+event!-top-level-surface
  (testing "rf2-0cyjm / rf2-44xya helper — for a top-level surface
            (unqualified keyword) it installs a sub at
            `:rf.xray.<surface>/diff-mode` + an event-db at
            `:rf.xray.<surface>/set-diff-mode`. The sub reads from
            the same slot the event writes; default mode is
            `:full+diff` per pair-debug 2026-05-27."
    (let [{:keys [sub-id event-id slot]}
          (widget/reg-mode-sub+event! :rf.xray.app-db)]
      (is (= :rf.xray.app-db/diff-mode sub-id)
          "sub-id is `<surface>/diff-mode`")
      (is (= :rf.xray.app-db/set-diff-mode event-id)
          "event-id is `<surface>/set-diff-mode`")
      (is (= sub-id slot)
          "slot key in app-db === sub-id (one-slot-per-surface)")
      (is (some? (registrar/handler :sub sub-id))
          "sub is registered")
      (is (some? (registrar/handler :event event-id))
          "event-db is registered")
      ;; Default-mode fallback when the slot is absent.
      (is (= :full+diff @(rf/subscribe [sub-id]))
          "sub returns `:full+diff` as the default when the slot is unset")
      ;; Dispatch the set-event, sub re-reads through the new value.
      (rf/dispatch-sync [event-id :diff])
      (is (= :diff @(rf/subscribe [sub-id]))
          "sub returns the mode just written by the event"))))

(deftest reg-mode-sub+event!-top-level-surface-custom-default
  (testing "The helper accepts `:default-mode` to override the
            widget's `:full+diff` default — surfaces with no `before`
            available may prefer `:full` as the starting lens."
    (let [{:keys [sub-id]}
          (widget/reg-mode-sub+event! :rf.xray.app-db
                                      {:default-mode :full})]
      (is (= :full @(rf/subscribe [sub-id]))
          "sub returns the supplied custom default when the slot is unset"))))

;; ---- (4) reg-mode-sub+event! — sub-surface ------------------------------

(deftest reg-mode-sub+event!-sub-surface
  (testing "For a sub-surface (namespaced keyword) the helper folds
            the keyword's `name` into the sub + event names — e.g.
            `:rf.xray.epoch/db` registers `:rf.xray.epoch/db-diff-mode`
            + `:rf.xray.epoch/set-db-diff-mode`. Matches the four
            cluster-F production sites' naming."
    (let [{:keys [sub-id event-id]}
          (widget/reg-mode-sub+event! :rf.xray.epoch/db)]
      (is (= :rf.xray.epoch/db-diff-mode sub-id))
      (is (= :rf.xray.epoch/set-db-diff-mode event-id))
      (is (some? (registrar/handler :sub sub-id)))
      (is (some? (registrar/handler :event event-id)))
      (is (= :full+diff @(rf/subscribe [sub-id])))
      (rf/dispatch-sync [event-id :full])
      (is (= :full @(rf/subscribe [sub-id]))))))

(deftest reg-mode-sub+event!-subs-value-surface
  (testing "The SUBSCRIPTIONS value-mode surface is the most-tortured
            naming case — `:rf.xray.epoch/subs-value` folds to
            `:rf.xray.epoch/subs-value-diff-mode` + matching set
            event. Pin the exact shape post-cluster-F."
    (let [{:keys [sub-id event-id]}
          (widget/reg-mode-sub+event! :rf.xray.epoch/subs-value)]
      (is (= :rf.xray.epoch/subs-value-diff-mode sub-id))
      (is (= :rf.xray.epoch/set-subs-value-diff-mode event-id)))))

;; ---- (5) widget render shape — bare bar (no label) ----------------------

(deftest diff-mode-toggle-renders-three-buttons
  (testing "Without `:label` the widget returns a single bar `<span>`
            whose `:data-testid` is the supplied prefix; three child
            `<button>` elements, one per default mode."
    (let [tree (widget/diff-mode-toggle
                 {:mode :full+diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"})
          bar (th/find-by-testid tree "rf-xray-test-diff-mode")
          btn-diff (th/find-by-testid tree "rf-xray-test-diff-mode-diff")
          btn-full (th/find-by-testid tree "rf-xray-test-diff-mode-full")
          btn-fwd  (th/find-by-testid
                     tree "rf-xray-test-diff-mode-full-with-diff")]
      (is (some? bar) "the bar carries the supplied testid")
      (is (some? btn-diff)       "diff button is present")
      (is (some? btn-full)       "full button is present")
      (is (some? btn-fwd)        "full+diff button is present")
      (is (= "diff"      (last btn-diff)) ":diff button label")
      (is (= "full"      (last btn-full)) ":full button label")
      (is (= "full+diff" (last btn-fwd))  ":full+diff button label"))))

(deftest diff-mode-toggle-active-button-aria-pressed
  (testing "rf2-xlmhh — `aria-pressed=\"true\"` on the active button
            is the single source of truth for which mode is selected
            (the bar's retired `:data-mode` is gone). Inactive
            buttons report `aria-pressed=\"false\"`."
    (let [tree (widget/diff-mode-toggle
                 {:mode :diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"})
          btn-diff (th/find-by-testid tree "rf-xray-test-diff-mode-diff")
          btn-full (th/find-by-testid tree "rf-xray-test-diff-mode-full")
          btn-fwd  (th/find-by-testid
                     tree "rf-xray-test-diff-mode-full-with-diff")]
      (is (= "true"  (:aria-pressed (second btn-diff)))
          ":diff button is active when :mode = :diff")
      (is (= "false" (:aria-pressed (second btn-full)))
          ":full button is inactive when :mode = :diff")
      (is (= "false" (:aria-pressed (second btn-fwd)))
          ":full+diff button is inactive when :mode = :diff"))))

(deftest diff-mode-toggle-bar-has-no-data-mode-attribute
  (testing "rf2-xlmhh — the bar `<span>` must NOT carry a `:data-mode`
            attribute. Per `tools/xray/spec/Conventions.md` §264 the
            attribute was retired as a duplicate axis."
    (let [tree (widget/diff-mode-toggle
                 {:mode :full+diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"})
          bar (th/find-by-testid tree "rf-xray-test-diff-mode")]
      (is (some? bar))
      (is (not (contains? (second bar) :data-mode))
          "the bar carries no :data-mode attribute"))))

;; ---- (6) widget render shape — with :label (rf2-fytu4) -----------------

(deftest diff-mode-toggle-with-label-wraps-bar
  (testing "rf2-fytu4 — when `:label` is supplied the widget wraps
            the bar in a `<span>` carrying `<testid>-wrapper` + a
            prefix-label `<span>` carrying `<testid>-label` whose
            last child is the label text. The bar still renders
            inside the wrapper with the original testid."
    (let [tree (widget/diff-mode-toggle
                 {:mode :full+diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"
                  :label "View"})
          wrapper (th/find-by-testid
                    tree "rf-xray-test-diff-mode-wrapper")
          label   (th/find-by-testid
                    tree "rf-xray-test-diff-mode-label")
          bar     (th/find-by-testid tree "rf-xray-test-diff-mode")]
      (is (some? wrapper) "wrapper span exists when :label supplied")
      (is (some? label)   "prefix label span exists when :label supplied")
      (is (= "View" (last label))
          "the prefix label renders the supplied text verbatim")
      (is (some? bar)
          "the bar still mounts inside the wrapper with its original testid"))))

(deftest diff-mode-toggle-without-label-no-wrapper
  (testing "rf2-fytu4 — when `:label` is OMITTED the widget renders
            the bare bar — no wrapper, no label-span. Backwards-
            compatible with any future caller that wants the bar
            unadorned."
    (let [tree (widget/diff-mode-toggle
                 {:mode :full+diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"})]
      (is (nil? (th/find-by-testid
                  tree "rf-xray-test-diff-mode-wrapper"))
          "no wrapper span when :label omitted")
      (is (nil? (th/find-by-testid
                  tree "rf-xray-test-diff-mode-label"))
          "no prefix-label span when :label omitted"))))

;; ---- (7) :modes opt overrides ordering ----------------------------------

(deftest diff-mode-toggle-respects-custom-modes-opt
  (testing "When `:modes` is supplied the widget renders ONLY those
            modes in the supplied order. Surfaces that want to omit
            a mode (rare; mode-3 is the canonical full bar) pass a
            subset."
    (let [tree (widget/diff-mode-toggle
                 {:mode :diff
                  :on-change (fn [_])
                  :testid "rf-xray-test-diff-mode"
                  :modes [:diff :full]})]
      (is (some? (th/find-by-testid tree "rf-xray-test-diff-mode-diff")))
      (is (some? (th/find-by-testid tree "rf-xray-test-diff-mode-full")))
      (is (nil?  (th/find-by-testid
                   tree "rf-xray-test-diff-mode-full-with-diff"))
          "omitted modes do not paint"))))
