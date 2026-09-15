(ns re-frame.story.ui.shell-registry-tick-cljs-test
  "rf2-yemtm — a registration that no running frame reflects must still
  reach the shell state, or every pane that renders the registry (sidebar,
  test widget, Tests pane) stays stale until an unrelated click.

  `detect-and-tick!` compared only the running frames' decorator
  fingerprints, so a hot reload that added a variant, or a `:script` on a
  variant nobody had open, changed nothing it looked at: no swap, so no
  re-render. The registrar's mutation tick now rides the same comparison.

  rf2-0ae7o.14 — the other half: a hot reload that edits a MOUNTED
  variant's own body is stale-variant drift, so the canvas re-mounts and
  shows the edit. An edit to a variant with no running frame is not."
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

(deftest a-mounted-variant-body-edit-re-mounts-and-an-unmounted-one-does-not
  ;; Only `mounted` has a running frame, as after selecting it in the shell.
  (let [mounted :story.hot-reload-body/mounted
        sibling :story.hot-reload-body/sibling
        tick    #(:hot-reload-tick (rf.story.ui.state/get-state))]
    (with-redefs [rf.story.frames/variant-frames (fn [] #{mounted})]
      (rf.story.ui.state/reset-shell-state!)
      (try
        (rf.story.registrar/reg-story* :story.hot-reload-body {:args {:heading "Sign in"}})
        (rf.story.registrar/reg-variant* mounted {:setup [] :args {}})
        (rf.story.registrar/reg-variant* sibling {:setup [] :args {}})
        (rf.story.ui.shell/detect-and-tick!)
        (let [baseline (tick)]
          (testing "control — re-registering the mounted variant with the same body, as every hot reload of its file does, re-mounts nothing"
            (rf.story.registrar/reg-variant* mounted {:setup [] :args {}})
            (rf.story.ui.shell/detect-and-tick!)
            (is (= baseline (tick))))
          (testing "a cell-override edit on the mounted variant is not drift — run-key already re-runs it"
            (rf.story.ui.state/swap-state! assoc-in [:cell-overrides mounted] {:heading "typed"})
            (rf.story.ui.shell/detect-and-tick!)
            (is (= baseline (tick))))
          (testing "an unmounted sibling re-registered with a changed body does not bump the tick"
            (rf.story.registrar/reg-variant* sibling {:setup [] :args {:heading "Sibling PROBE"}})
            (rf.story.ui.shell/detect-and-tick!)
            (is (= (rf.story.registrar/current-mutation-tick)
                   (:registry-tick (rf.story.ui.state/get-state)))
                "the registration still reached the shell state")
            (is (= baseline (tick))))
          (testing "the mounted variant re-registered with a changed body bumps the tick"
            (rf.story.registrar/reg-variant* mounted {:setup [] :args {:heading "Sign in PROBE"}})
            (rf.story.ui.shell/detect-and-tick!)
            (is (= (inc baseline) (tick)))
            (testing "and only once — the next poll sees no further drift"
              (rf.story.ui.shell/detect-and-tick!)
              (is (= (inc baseline) (tick))))))
        (finally
          (rf.story.registrar/unregister! :variant mounted)
          (rf.story.registrar/unregister! :variant sibling)
          (rf.story.registrar/unregister! :story :story.hot-reload-body)
          (rf.story.ui.state/reset-shell-state!))))))
