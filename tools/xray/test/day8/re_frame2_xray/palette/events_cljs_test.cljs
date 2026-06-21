(ns day8.re-frame2-xray.palette.events-cljs-test
  "CLJS end-to-end tests for the palette's open/close/cursor/invoke
  contracts (rf2-wm7z4).

  Drives the registered events against the live registrar so the
  test exercises the same code paths the keybinding + view dispatch
  through. Mirrors the per-panel facade-pair test pattern (`subs +
  events`, replay via `rf/dispatch-sync`).

  The palette pop-out fx is replaced with a counting stub so the
  Ctrl+Enter / open-popout invocations can be asserted without
  driving the mount layer (which would need a real `window.open`).

  rf2-mxzgg — the snapshot-app-db egress tests stub `js/console.log`
  and a clipboard `writeText` so the off-box payload (both sinks) can
  be captured and asserted to carry the redacted/size-elided
  projection rather than the raw secret."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.palette.recents :as recents]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture -----------------------------------------------------------

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!)
  (config/reset-suppressed-count!)
  (config/set-project-root! nil)
  ;; rf2-ybjkx — clear palette recents between tests so each scenario
  ;; starts with a clean slate. localStorage degrades silently in Node
  ;; runtimes (no window.localStorage); the clear is a no-op there.
  (recents/clear!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- helpers -----------------------------------------------------------

(def ^:private popout-calls (atom 0))

(defn- install-popout-counter!
  "Install the counting stub AFTER `setup!` has run — `register-xray-
  handlers!` re-registers `:rf.xray.palette.fx/popout` from
  events.cljs, so a stub installed before setup would be clobbered."
  []
  (reset! popout-calls 0)
  (rf/reg-fx :rf.xray.palette.fx/popout
             (fn [_ _] (swap! popout-calls inc) nil)))

(defn- xray-db []
  (rf/app-db-value :rf/xray))

;; ---- open / close / toggle --------------------------------------------

(deftest palette-open-flips-flag
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open]))
  (is (true? (boolean (:palette-open? (xray-db)))))
  (is (= "" (:palette-query (xray-db))))
  (is (= 0 (:palette-cursor (xray-db)))))

(deftest palette-close-resets-state
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-set-query "abc"])
    (rf/dispatch-sync [:rf.xray/palette-cursor-set 3])
    (rf/dispatch-sync [:rf.xray/palette-close]))
  (let [db (xray-db)]
    (is (false? (boolean (:palette-open? db))))
    (is (= "" (:palette-query db)))
    (is (= 0 (:palette-cursor db)))))

(deftest palette-toggle-cycles
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-toggle]))
  (is (true? (boolean (:palette-open? (xray-db)))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-toggle]))
  (is (false? (boolean (:palette-open? (xray-db))))))

;; ---- cursor ------------------------------------------------------------

(deftest cursor-up-clamps-at-zero
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-cursor-up])
    (rf/dispatch-sync [:rf.xray/palette-cursor-up]))
  (is (= 0 (:palette-cursor (xray-db)))))

(deftest cursor-down-respects-max
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    ;; one more — should clamp at 4
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4]))
  (is (= 4 (:palette-cursor (xray-db)))))

(deftest set-query-resets-cursor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-cursor-set 5])
    (rf/dispatch-sync [:rf.xray/palette-set-query "ev"]))
  (is (= "ev" (:palette-query (xray-db))))
  (is (= 0 (:palette-cursor (xray-db)))
      "changing the query should snap the cursor back to the top
       result — otherwise the cursor lands on a row that's no longer
       in the filtered set"))

;; ---- invoke ------------------------------------------------------------

(deftest invoke-select-panel-flips-tab-and-closes
  ;; rf2-qy0nu — palette-panels ids are L3 tab ids; the lowering
  ;; dispatches `:rf.xray/select-tab` so the visible tab flips. The
  ;; legacy `:rf.xray/select-panel` slot is no longer read.
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :panel
        :id     :trace
        :label  "Open Trace panel"
        :action [:palette/select-panel :trace]}
       false]))
  (is (= :trace (:selected-tab (xray-db))))
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-select-event-routes-to-epoch-tab
  ;; rf2-qy0nu — :palette/select-event writes :selected-tab to the
  ;; canonical "what happened in this epoch" L3 tab. rf2-5gl5r retired
  ;; the Event/Handler tab; the routing target is now `:epoch` (the
  ;; Epoch panel supersedes it).
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :recent-event
        :id     [:foo/bar 1]
        :label  "[:foo/bar]"
        :action [:palette/select-event [:foo/bar]]
        :popout? true}
       false]))
  (is (= :epoch (:selected-tab (xray-db))))
  (is (= [:foo/bar]    (:selected-event-id (xray-db))))
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-clear-trace-buffer-empties-buffer
  (setup!)
  ;; Seed the buffer first so we can observe the clear.
  (trace-collector/seed-trace-for-test! {:id 1 :op :rf.event/handled :event-id [:foo]})
  (is (pos? (count (trace-collector/buffer-for-test))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :clear-trace-buffer
        :label  "Clear trace buffer"
        :action [:palette/clear-trace-buffer]}
       false]))
  (is (zero? (count (trace-collector/buffer-for-test))))
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest popout-flag-routes-through-fx-when-popoutable
  (setup!)
  (install-popout-counter!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
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
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
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
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :open-popout
        :label  "Open Xray in a pop-out window"
        :action [:palette/open-popout]}
       false]))
  (is (= 1 @popout-calls)))

(deftest invoke-close-action-closes-palette
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :close-palette
        :label  "Close command palette"
        :action [:palette/close]}
       false]))
  (is (false? (boolean (:palette-open? (xray-db))))))

;; ---- rf2-ybjkx — new commands ------------------------------------------

(deftest invoke-clear-epoch-history-drops-slot
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    ;; Seed the slot so we can observe the clear.
    (rf/dispatch-sync [:rf.xray/sync-epoch-history
                       [{:epoch-id 1} {:epoch-id 2}]])
    (is (= 2 (count (:epoch-history (xray-db))))
        "sanity check — slot is seeded before the clear")
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :clear-epoch-history
        :label  "Clear epoch history"
        :action [:palette/clear-epoch-history]}
       false]))
  (is (nil? (:epoch-history (xray-db)))
      "clear-epoch-history dissocs the slot")
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-toggle-theme-flips-via-settings-update
  (setup!)
  ;; Reset the atom to a known starting theme so the cycle is deterministic.
  (config/update-setting! :theme nil :dark)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :toggle-theme
        :label  "Toggle theme (dark ↔ light)"
        :action [:palette/toggle-theme]}
       false]))
  (is (= :light (config/get-setting :theme nil))
      ":dark → :light cycles the canonical settings atom")
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-cycle-reduced-motion-walks-the-tri-state
  (setup!)
  (config/update-setting! :general :reduced-motion-override :os)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :cycle-reduced-motion
        :label  "Cycle reduced-motion override"
        :action [:palette/cycle-reduced-motion]}
       false]))
  (is (= :always (config/get-setting :general :reduced-motion-override))
      "first cycle: :os → :always")
  ;; Second invocation continues the cycle.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :cycle-reduced-motion
        :label  "Cycle reduced-motion override"
        :action [:palette/cycle-reduced-motion]}
       false]))
  (is (= :never (config/get-setting :general :reduced-motion-override))
      "second cycle: :always → :never"))

(deftest invoke-jump-to-settings-opens-popup
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :jump-to-settings
        :label  "Jump to Settings"
        :action [:palette/jump-to-settings]}
       false]))
  (is (true? (boolean (:settings-open? (xray-db))))
      "jump-to-settings opens the Settings popup")
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-toggle-mode-flips-runtime-static
  (setup!)
  ;; Force-set the slot so the cycle is deterministic.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-mode :dynamic])
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :toggle-mode
        :label  "Toggle mode (Dynamic ↔ Static)"
        :action [:palette/toggle-mode]}
       false]))
  (is (= :static (:mode (xray-db)))
      ":dynamic → :static via the canonical toggle-mode handler"))

(deftest invoke-select-static-tab-flips-static-slot
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :panel
        :id     [:static :routes]
        :label  "Open Routes (Static)"
        :action [:palette/select-static-tab :routes]}
       false]))
  (is (= :routes (:rf.xray.static/selected-tab (xray-db)))
      "Static tab selection lands on the Static-scoped slot")
  (is (false? (boolean (:palette-open? (xray-db))))))

(deftest invoke-select-frame-drives-canonical-set-frame
  (setup!)
  ;; Ensure :rf/cart-frame exists so the spine handler resolves
  ;; epoch-history without throwing — the canonical set-frame event
  ;; queries `rf/epoch-history` for the new target.
  (frame/reg-frame :rf/cart-frame {})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :frame
        :id     :rf/cart-frame
        :label  "Switch focus to frame :rf/cart-frame"
        :action [:palette/select-frame :rf/cart-frame]}
       false]))
  (is (= :rf/cart-frame (get-in (xray-db) [:focus :frame]))
      "select-frame routes through :rf.xray/set-frame so every per-
       frame composite (App-DB Diff, Views, Routing) re-fires off the
       new frame's slot")
  (is (= :rf/cart-frame (:target-frame (xray-db)))
      ":target-frame is also written by the canonical handler"))

;; ---- rf2-ybjkx — recents tracking --------------------------------------

(deftest invoking-a-command-records-it-in-recents
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :toggle-theme
        :label  "Toggle theme"
        :action [:palette/toggle-theme]}
       false]))
  (is (= [:toggle-theme] (:palette-recents (xray-db)))
      "command invocation bumps the recents vector"))

(deftest invoking-twice-keeps-recents-unique
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command :id :toggle-theme
        :action [:palette/toggle-theme]}
       false])
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command :id :jump-to-settings
        :action [:palette/jump-to-settings]}
       false])
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command :id :toggle-theme
        :action [:palette/toggle-theme]}
       false]))
  (is (= [:toggle-theme :jump-to-settings]
         (:palette-recents (xray-db)))
      "re-invoking an existing recent bubbles it to the head;
       it does not duplicate"))

(deftest non-command-items-do-not-record-recents
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :panel
        :id     :trace
        :action [:palette/select-panel :trace]}
       false]))
  (is (empty? (:palette-recents (xray-db)))
      "panel jumps don't pollute the recents vector"))

;; ---- rf2-mxzgg — snapshot-app-db routes off-box payload through safe egress

;; The `:palette/snapshot-app-db` verb fires the
;; `:rf.xray.palette.fx/snapshot-app-db` fx, which reads the focused
;; frame's app-db and ships it to TWO off-box sinks: `console.log` and
;; `navigator.clipboard.writeText`. Pre-rf2-mxzgg it shipped the RAW
;; `(rf/app-db-value tf)` — a frame-declared sensitive slot
;; (`{:auth {:password "shh"}}`) crossed both sinks unredacted. The fix
;; routes the value through `runtime/egress-value` (the same fail-closed
;; projection `get-app-db` uses) FIRST, so the off-box payload carries
;; `:rf/redacted` by default. These tests capture both sinks and assert
;; the secret never leaves the box on the command default.

(defn- capture-snapshot-sinks!
  "Stub `js/console.log` + a `navigator.clipboard.writeText` so the
  snapshot fx's two off-box payloads are captured rather than emitted.
  Returns `{:console (atom []) :clipboard (atom [])}` carrying every
  payload each sink received; the caller restores nothing because the
  test fixture resets the runtime + the node globals are per-process
  scratch (the real console.log is restored explicitly below)."
  []
  (let [console-payloads   (atom [])
        clipboard-payloads (atom [])
        orig-log           (when (and (exists? js/console) (.-log js/console))
                             (.-log js/console))]
    ;; console.log(tag, value) — capture the SECOND arg (the payload),
    ;; but ONLY when the first arg is the snapshot tag so we don't
    ;; swallow the test reporter's own output (CLJS `println` on the
    ;; node-test target routes through `js/console.log`; a blanket stub
    ;; would eat a FAIL banner emitted while the stub is installed).
    (set! (.-log js/console)
          (fn [& args]
            (when (and (string? (first args))
                       (re-find #"\[rf2-xray\] palette snapshot" (first args)))
              (swap! console-payloads conj (second args)))
            (when orig-log (apply orig-log args))
            nil))
    ;; Install a clipboard whose writeText records the EDN string. node
    ;; test runtimes have no navigator.clipboard, so we synthesise one.
    (let [nav (if (exists? js/navigator)
                js/navigator
                (let [n (js-obj)]
                  (set! js/navigator n)
                  n))]
      (gobj/set nav "clipboard"
                (js-obj "writeText"
                        (fn [s] (swap! clipboard-payloads conj s) nil))))
    {:console   console-payloads
     :clipboard clipboard-payloads
     :restore   (fn [] (when orig-log (set! (.-log js/console) orig-log)))}))

;; The focused frame is the HOST app's frame, NOT Xray's own `:rf/xray`
;; frame — the palette dispatches against `:rf/xray` but the snapshot
;; reads the frame the L1 picker focused (`:target-frame`). We model that
;; faithfully with a dedicated `:rf/host` frame: the secret + the schema
;; declarations live on `:rf/host`, and the fix's `(with-frame tf …)`
;; egress pin makes the walker resolve `:rf/host`'s declarations even
;; though the fx fires in the `:rf/xray` frame.

(def ^:private host-frame :rf/host)

(defn- seed-sensitive-host! [db-fn]
  ;; Classify [:auth :password] sensitive + seed the secret INTO the host
  ;; frame (not Xray's). EP-0025: durable app-db classification rides the
  ;; commit-plane classification effects — `elision/apply-classification-
  ;; effects` writes a `:source :effect` declaration (index-free :rf/path) on
  ;; the host frame's elision registry, the SAME registry the off-box egress
  ;; walker consults (the same write a reg-event returning `:sensitive` makes).
  (frame/reg-frame host-frame {})
  (frame/swap-runtime-db! host-frame
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  (rf/with-frame host-frame
    (rf/reg-event :test/seed-host (fn [{:keys [db]} _] {:db (db-fn db)}))
    (rf/dispatch-sync [:test/seed-host])))

(defn- drive-snapshot-of-host! []
  ;; Focus the host frame (the slot the L1 picker writes) then drive the
  ;; real `:palette/snapshot-app-db` verb from the `:rf/xray` frame.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-frame host-frame])
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync
      [:rf.xray/palette-invoke
       {:source :command
        :id     :snapshot-app-db
        :label  "Snapshot app-db"
        :action [:palette/snapshot-app-db]}
       false])))

(deftest snapshot-app-db-redacts-sensitive-slot-on-both-off-box-sinks
  ;; rf2-mxzgg — the command default MUST route the snapshot through
  ;; safe egress before EITHER off-box sink receives it. The egress is
  ;; pinned to the focused (host) frame so that frame's own schema
  ;; declarations govern the redaction.
  (setup!)
  (let [sinks (capture-snapshot-sinks!)]
    (try
      (seed-sensitive-host!
        (fn [db] (assoc db :auth {:username "ada" :password "hunter2"})))
      (drive-snapshot-of-host!)
      ;; --- console sink ---
      (let [payload (first @(:console sinks))]
        (is (some? payload) "the console.log sink received a payload")
        (is (= :rf/redacted (get-in payload [:auth :password]))
            "console payload redacts the frame-declared sensitive slot")
        (is (not= "hunter2" (get-in payload [:auth :password]))
            "the raw secret never crosses the console off-box sink")
        (is (= "ada" (get-in payload [:auth :username]))
            "non-sensitive sibling survives in the console payload"))
      ;; --- clipboard sink (pr-str of the SAME egressed payload) ---
      (let [edn (first @(:clipboard sinks))]
        (is (string? edn) "the clipboard sink received a pr-str payload")
        (is (re-find #":rf/redacted" edn)
            "clipboard payload carries the :rf/redacted marker")
        (is (not (re-find #"hunter2" edn))
            "the raw secret never crosses the clipboard off-box sink"))
      (finally ((:restore sinks))))))

(deftest snapshot-app-db-size-elides-large-slot-on-both-off-box-sinks
  ;; rf2-mxzgg — size minimisation rides the same safe-egress default
  ;; (polarity parity with the runtime accessors): a frame-declared
  ;; `:large` slot is replaced with the `:rf.size/large-elided` marker.
  (setup!)
  (let [sinks (capture-snapshot-sinks!)]
    (try
      (frame/reg-frame host-frame {})
      ;; EP-0025: commit-plane :large classification (index-free :rf/path) on
      ;; the host frame's elision registry — the size sibling of the sensitive
      ;; seed above (`:source :effect`).
      (frame/swap-runtime-db! host-frame
        (fn [rt] (elision/apply-classification-effects rt {:large [[:blob :payload]]})))
      (rf/with-frame host-frame
        (rf/reg-event :test/seed-blob
          (fn [{:keys [db]} _] {:db (assoc db :blob {:payload {:big "value"}})}))
        (rf/dispatch-sync [:test/seed-blob]))
      (drive-snapshot-of-host!)
      (let [payload (first @(:console sinks))]
        (is (some? payload) "the console.log sink received a payload")
        (is (contains? (get-in payload [:blob :payload]) :rf.size/large-elided)
            "console payload size-elides the frame-declared large slot")
        (is (not= {:big "value"} (get-in payload [:blob :payload]))
            "the raw large value never crosses the console off-box sink"))
      (finally ((:restore sinks))))))
