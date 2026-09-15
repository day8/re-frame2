(ns re-frame.story.ui.shell-registry-tick-cljs-test
  "rf2-yemtm — a registration that no running frame reflects must still
  reach the shell state, or every pane that renders the registry (sidebar,
  test widget, Tests pane) stays stale until an unrelated click.

  `detect-and-tick!` compared only the running frames' decorator
  fingerprints, so a hot reload that added a variant, or a `:script` on a
  variant nobody had open, changed nothing it looked at: no swap, so no
  re-render. The registrar's mutation tick now rides the same comparison."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.shell :as rf.story.ui.shell]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(deftest a-registration-reaches-the-shell-state-without-a-rerun
  ;; No running frames, so the fingerprint half of the comparison is empty
  ;; and whatever the shell state does is the registry half's doing.
  (with-redefs [rf.story.frames/variant-frames (fn [] #{})]
    (rf.story.ui.state/reset-shell-state!)
    (try
      (rf.story.ui.shell/detect-and-tick!)
      (let [baseline (rf.story.ui.state/get-state)]
        (testing "control — a poll with nothing registered since leaves the shell state untouched"
          (rf.story.ui.shell/detect-and-tick!)
          (is (identical? baseline (rf.story.ui.state/get-state))))
        (rf.story.registrar/reg-variant* :story.registry-tick/added {:args {}})
        (rf.story.ui.shell/detect-and-tick!)
        (let [after (rf.story.ui.state/get-state)]
          (is (not (identical? baseline after))
              "the registration reached the shell state atom, so the panes that read it re-render")
          (is (= (rf.story.registrar/current-mutation-tick) (:registry-tick after)))
          (is (= (:hot-reload-tick baseline) (:hot-reload-tick after))
              "a registry change is not decorator drift — nothing re-mounts or re-runs")))
      (finally
        (rf.story.registrar/unregister! :variant :story.registry-tick/added)
        (rf.story.ui.state/reset-shell-state!)))))
