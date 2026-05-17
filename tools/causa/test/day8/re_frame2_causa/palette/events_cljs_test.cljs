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
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (config/set-project-root! nil))

(use-fixtures :each
  (test-support/reset-runtime-fixture
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

(deftest invoke-select-panel-dispatches-and-closes
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
  (is (= :trace (:selected-panel (causa-db))))
  (is (false? (boolean (:palette-open? (causa-db))))))

(deftest invoke-select-event-routes-to-event-detail
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
  (is (= :event-detail (:selected-panel (causa-db))))
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

(deftest invoke-copilot-toggle-dispatches-copilot-toggle
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/palette-open])
    (rf/dispatch-sync
      [:rf.causa/palette-invoke
       {:source :setting
        :id     :copilot-toggle
        :label  "Toggle co-pilot rail"
        :action [:palette/copilot-toggle]}
       false]))
  (is (true? (boolean (:copilot-open? (causa-db))))
      "palette-invoke lowers :palette/copilot-toggle into the
       :rf.causa/copilot-toggle event the co-pilot already owns"))

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
