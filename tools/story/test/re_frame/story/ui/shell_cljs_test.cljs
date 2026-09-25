(ns re-frame.story.ui.shell-cljs-test
  "CLJS-side regression net for `re-frame.story.ui.shell`'s pure
  mount-time decision logic.

  `component-did-mount` installs `selection-watcher`, then
  `hydrate-url-state!` may itself change `:selected-variant` (a
  `?variant=…` deep link), which fires the already-installed watcher and
  schedules an auto-run. A mount-time block that unconditionally read the
  (now-hydrated) selection would schedule a SECOND auto-run for the same
  variant, so a deep-linked auto-run variant would execute its `:script`
  TWICE; the guard has to distinguish 'already selected before mount'
  from 'just selected by this mount's URL hydration'.

  `mount-time-autorun-vid` is that mount-time guard as a pure decision
  (see the comment above its definition in `shell.cljs`); it needs no
  DOM / mount to exercise."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private mount-time-autorun-vid @#'rf.story.ui.shell/mount-time-autorun-vid)

(deftest mount-time-autorun-vid-schedules-when-unchanged
  (testing "selection unchanged across hydration (persisted / re-mount
            selection the watcher never saw as a change) — the mount-time
            block is the ONLY scheduler, so it returns the vid"
    (is (= :story.counter/default
           (mount-time-autorun-vid :story.counter/default :story.counter/default)))))

(deftest mount-time-autorun-vid-skips-when-hydration-just-selected
  (testing "the core case — a FRESH mount with no prior selection,
            where `hydrate-url-state!` deep-links a `?variant=…` selection
            during THIS mount, must SKIP the mount-time schedule: the
            selection-watcher already fired (selection went nil -> vid)
            and scheduled its own auto-run. Scheduling here too would run
            the variant's :script twice."
    (is (nil? (mount-time-autorun-vid nil :story.counter/deep-linked)))))

(deftest mount-time-autorun-vid-skips-when-hydration-changed-selection
  (testing "hydration changing an EXISTING selection to a different deep
            link also skips — same reasoning, the watcher already
            scheduled the new selection's auto-run"
    (is (nil? (mount-time-autorun-vid :story.counter/a :story.counter/b)))))

(deftest mount-time-autorun-vid-nil-when-nothing-selected
  (testing "no selection before or after hydration — nothing to auto-run"
    (is (nil? (mount-time-autorun-vid nil nil)))))
