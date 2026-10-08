(ns re-frame.story.ui.shell-cljs-test
  "`mount-time-autorun-vid`, the shell's mount-time auto-run guard as a pure
  decision. `component-did-mount` installs `selection-watcher` before
  `hydrate-url-state!` runs, so a `?variant=…` deep link fires the watcher,
  which schedules the auto-run; the mount-time block scheduling it again
  would run the variant's `:script` TWICE."
  (:require [cljs.test :refer-macros [are deftest]]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

(def ^:private mount-time-autorun-vid @#'rf.story.ui.shell/mount-time-autorun-vid)

(deftest mount-time-autorun-vid-schedules-only-an-unchanged-selection
  (are [pre post expected] (= expected (mount-time-autorun-vid pre post))
    ;; selection unchanged across hydration (persisted / re-mount): the
    ;; watcher never saw a change, so the mount-time block is the ONLY
    ;; scheduler
    :story.counter/default :story.counter/default :story.counter/default

    ;; this mount's URL hydration just selected the variant: the watcher
    ;; already scheduled it, so the mount-time block must skip
    nil :story.counter/deep-linked nil))
