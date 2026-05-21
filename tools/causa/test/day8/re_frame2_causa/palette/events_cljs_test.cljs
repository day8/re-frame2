(ns day8.re-frame2-causa.palette.events-cljs-test
  "CLJS end-to-end tests for the palette's open/close/cursor/invoke
  contracts (rf2-wm7z4).

  Drives the registered events against the live registrar so the
  test exercises the same code paths the keybinding + view dispatch
  through. Mirrors the per-panel facade-pair test pattern (`subs +
  events`, replay via `rf/dispatch-sync`).

  The palette pop-out fx is replaced with a counting stub so the
  Ctrl+Enter / open-popout invocations can be asserted without
  driving the mount layer (which would need a real `window.open`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.palette.recents :as recents]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (config/set-project-root! nil)
  ;; rf2-ybjkx — clear palette recents between tests so each scenario
  ;; starts with a clean slate. localStorage degrades silently in Node
  ;; runtimes (no window.localStorage); the clear is a no-op there.
  (recents/clear!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- helpers -----------------------------------------------------------

(def ^:private popout-calls (atom 0))

(defn- install-popout-counter!
  "Install the counting stub AFTER `setup!` has run — `register-causa-
  handlers!` re-registers `:rf.causa.palette.fx/popout` from
  events.cljs, so a stub installed before setup would be clobbered."
  []
  (reset! popout-calls 0)
  (rf/reg-fx :rf.causa.palette.fx/popout
             (fn [_ _] (swap! popout-calls inc) nil)))

(defn- causa-db []
  (rf/get-frame-db :rf/causa))

;; ---- open / close / toggle --------------------------------------------

(deftest palette-open-flips-flag
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open]))
  (is (true? (boolean (:palette-open? (causa-db)))))
  (is (= "" (:palette-query (causa-db))))
  (is (= 0 (:palette-cursor (causa-db)))))

(deftest palette-close-resets-state
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync [:rf.causa/palette-set-query "abc"])
    (rf/dispatch-sync [:rf.causa/palette-cursor-set 3])
    (rf/dispatch-sync [:rf.causa/palette-close]))
  (let [db (causa-db)]
    (is (false? (boolean (:palette-open? db))))
    (is (= "" (:palette-query db)))
    (is (= 0 (:palette-cursor db)))))

(deftest palette-toggle-cycles
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-toggle]))
  (is (true? (boolean (:palette-open? (causa-db)))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-toggle]))
  (is (false? (boolean (:palette-open? (causa-db))))))

;; ---- cursor ------------------------------------------------------------

(deftest cursor-up-clamps-at-zero
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync [:rf.causa/palette-cursor-up])
    (rf/dispatch-sync [:rf.causa/palette-cursor-up]))
  (is (= 0 (:palette-cursor (causa-db)))))

(deftest cursor-down-respects-max
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4])
    ;; one more — should clamp at 4
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.causa/palette-cursor-down 4]))
  (is (= 4 (:palette-cursor (causa-db)))))

(deftest set-query-resets-cursor
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync [:rf.causa/palette-cursor-set 5])
    (rf/dispatch-sync [:rf.causa/palette-set-query "ev"]))
  (is (= "ev" (:palette-query (causa-db))))
  (is (= 0 (:palette-cursor (causa-db)))
      "changing the query should snap the cursor back to the top
       result — otherwise the cursor lands on a row that's no longer
       in the filtered set"))

;; ---- invoke ------------------------------------------------------------

(deftest invoke-select-panel-flips-tab-and-closes
  ;; rf2-qy0nu — palette-panels ids are L3 tab ids; the lowering
  ;; dispatches `:rf.causa/select-tab` so the visible tab flips. The
  ;; legacy `:rf.causa/select-panel` slot is no longer read.
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :panel
        :id     :trace
        :label  "Open Trace panel"
        :action [:palette/select-panel :trace]}
       false]))
  (is (= :trace (:selected-tab (causa-db))))
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-select-event-routes-to-event-tab
  ;; rf2-qy0nu — :palette/select-event now writes :selected-tab :event
  ;; (the L3 tab id that mounts event-detail/Panel) instead of the
  ;; dead `:selected-panel :event-detail` slot.
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :recent-event
        :id     [:foo/bar 1]
        :label  "[:foo/bar]"
        :action [:palette/select-event [:foo/bar]]
        :popout? true}
       false]))
  (is (= :event (:selected-tab (causa-db))))
  (is (= [:foo/bar]    (:selected-event-id (causa-db))))
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-clear-trace-buffer-empties-buffer
  (setup!)
  ;; Seed the buffer first so we can observe the clear.
  (trace-bus/seed-buffer-for-test! {:id 1 :op :event/handled :event-id [:foo]})
  (is (pos? (count (trace-bus/buffer))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :clear-trace-buffer
        :label  "Clear trace buffer"
        :action [:palette/clear-trace-buffer]}
       false]))
  (is (zero? (count (trace-bus/buffer))))
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest popout-flag-routes-through-fx-when-popoutable
  (setup!)
  (install-popout-counter!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :recent-event
        :id     [:foo/bar 1]
        :label  "[:foo/bar]"
        :action [:palette/select-event [:foo/bar]]
        :popout? true}
       true]))
  (is (= 1 @popout-calls)
      "popout? true + popoutable item → fx fires once"))

(deftest popout-flag-ignored-when-item-opts-out
  (setup!)
  (install-popout-counter!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :panel
        :id     :trace
        :label  "Open Trace panel"
        :action [:palette/select-panel :trace]
        :popout? false}
       true]))
  (is (zero? @popout-calls)
      "non-popoutable items invoke normally even with the
       Ctrl-modifier flag set — no surprise pop-out windows"))

(deftest invoke-open-popout-fires-fx
  (setup!)
  (install-popout-counter!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :open-popout
        :label  "Open Causa in a pop-out window"
        :action [:palette/open-popout]}
       false]))
  (is (= 1 @popout-calls)))

(deftest invoke-close-action-closes-palette
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :close-palette
        :label  "Close command palette"
        :action [:palette/close]}
       false]))
  (is (false? (boolean (:palette-open? (causa-db))))))

;; ---- rf2-ybjkx — new commands ------------------------------------------

(deftest invoke-clear-epoch-history-drops-slot
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    ;; Seed the slot so we can observe the clear.
    (rf/dispatch-sync [:rf.causa/sync-epoch-history
                       [{:epoch-id 1} {:epoch-id 2}]])
    (is (= 2 (count (:epoch-history (causa-db))))
        "sanity check — slot is seeded before the clear")
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :clear-epoch-history
        :label  "Clear epoch history"
        :action [:palette/clear-epoch-history]}
       false]))
  (is (nil? (:epoch-history (causa-db)))
      "clear-epoch-history dissocs the slot")
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-toggle-theme-flips-via-settings-update
  (setup!)
  ;; Reset the atom to a known starting theme so the cycle is deterministic.
  (config/update-setting! :theme nil :dark)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :toggle-theme
        :label  "Toggle theme (dark ↔ light)"
        :action [:palette/toggle-theme]}
       false]))
  (is (= :light (config/get-setting :theme nil))
      ":dark → :light cycles the canonical settings atom")
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-cycle-reduced-motion-walks-the-tri-state
  (setup!)
  (config/update-setting! :general :reduced-motion-override :os)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :cycle-reduced-motion
        :label  "Cycle reduced-motion override"
        :action [:palette/cycle-reduced-motion]}
       false]))
  (is (= :always (config/get-setting :general :reduced-motion-override))
      "first cycle: :os → :always")
  ;; Second invocation continues the cycle.
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :cycle-reduced-motion
        :label  "Cycle reduced-motion override"
        :action [:palette/cycle-reduced-motion]}
       false]))
  (is (= :never (config/get-setting :general :reduced-motion-override))
      "second cycle: :always → :never"))

(deftest invoke-jump-to-settings-opens-popup
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :jump-to-settings
        :label  "Jump to Settings"
        :action [:palette/jump-to-settings]}
       false]))
  (is (true? (boolean (:settings-open? (causa-db))))
      "jump-to-settings opens the Settings popup")
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-toggle-mode-flips-runtime-static
  (setup!)
  ;; Force-set the slot so the cycle is deterministic.
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-mode :dynamic])
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :toggle-mode
        :label  "Toggle mode (Dynamic ↔ Static)"
        :action [:palette/toggle-mode]}
       false]))
  (is (= :static (:mode (causa-db)))
      ":dynamic → :static via the canonical toggle-mode handler"))

(deftest invoke-select-static-tab-flips-static-slot
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :panel
        :id     [:static :routes]
        :label  "Open Routes (Static)"
        :action [:palette/select-static-tab :routes]}
       false]))
  (is (= :routes (:rf.causa.static/selected-tab (causa-db)))
      "Static tab selection lands on the Static-scoped slot")
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-select-frame-drives-canonical-set-frame
  (setup!)
  ;; Ensure :rf/cart-frame exists so the spine handler resolves
  ;; epoch-history without throwing — the canonical set-frame event
  ;; queries `rf/epoch-history` for the new target.
  (frame/reg-frame :rf/cart-frame {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :frame
        :id     :rf/cart-frame
        :label  "Switch focus to frame :rf/cart-frame"
        :action [:palette/select-frame :rf/cart-frame]}
       false]))
  (is (= :rf/cart-frame (get-in (causa-db) [:focus :frame]))
      "select-frame routes through :rf.causa/set-frame so every per-
       frame composite (App-DB Diff, Views, Routing) re-fires off the
       new frame's slot")
  (is (= :rf/cart-frame (:target-frame (causa-db)))
      ":target-frame is also written by the canonical handler"))

;; ---- rf2-ybjkx — recents tracking --------------------------------------

(deftest invoking-a-command-records-it-in-recents
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command
        :id     :toggle-theme
        :label  "Toggle theme"
        :action [:palette/toggle-theme]}
       false]))
  (is (= [:toggle-theme] (:palette-recents (causa-db)))
      "command invocation bumps the recents vector"))

(deftest invoking-twice-keeps-recents-unique
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command :id :toggle-theme
        :action [:palette/toggle-theme]}
       false])
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command :id :jump-to-settings
        :action [:palette/jump-to-settings]}
       false])
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :command :id :toggle-theme
        :action [:palette/toggle-theme]}
       false]))
  (is (= [:toggle-theme :jump-to-settings]
         (:palette-recents (causa-db)))
      "re-invoking an existing recent bubbles it to the head;
       it does not duplicate"))

(deftest non-command-items-do-not-record-recents
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :panel
        :id     :trace
        :action [:palette/select-panel :trace]}
       false]))
  (is (empty? (:palette-recents (causa-db)))
      "panel jumps don't pollute the recents vector"))
