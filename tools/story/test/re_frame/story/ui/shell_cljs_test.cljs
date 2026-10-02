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
  (:require [cljs.test :refer-macros [are deftest]]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private mount-time-autorun-vid @#'rf.story.ui.shell/mount-time-autorun-vid)

(deftest mount-time-autorun-vid-schedules-only-an-unchanged-selection
  (are [pre post expected] (= expected (mount-time-autorun-vid pre post))
    ;; selection unchanged across hydration (persisted / re-mount): the
    ;; watcher never saw a change, so the mount-time block is the ONLY
    ;; scheduler and returns the vid
    :story.counter/default :story.counter/default :story.counter/default

    ;; a fresh mount whose URL hydration just selected the variant: the
    ;; watcher already scheduled it, so the mount-time block must skip
    nil :story.counter/deep-linked nil

    ;; hydration changed an existing selection: the watcher fired, skip
    :story.counter/a :story.counter/b nil

    ;; nothing selected before or after hydration: nothing to auto-run
    nil nil nil))
