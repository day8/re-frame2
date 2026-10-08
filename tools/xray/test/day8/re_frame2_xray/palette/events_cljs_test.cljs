(ns day8.re-frame2-xray.palette.events-cljs-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.palette.recents :as recents]
            [day8.re-frame2-xray.palette.sources :as sources]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture -----------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (config/set-project-root! nil)
                   (recents/clear!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- helpers -----------------------------------------------------------

(def ^:private popout-calls (atom 0))

(defn- install-popout-counter!
  "Run AFTER `setup!`. Stubs the pop-out fx through the frame's
  `:fx-overrides` seam; re-registering the xray-owned fx id from this ns
  would fail the frame's default-image assembly."
  []
  (reset! popout-calls 0)
  (rf/make-frame {:id :rf/xray
                  :fx-overrides {:rf.xray.palette.fx/popout
                                 (fn [_ _] (swap! popout-calls inc) nil)}}))

(defn- xray-db []
  (rf/app-db-value :rf/xray))

(defn- invoke-item!
  ([item] (invoke-item! item false))
  ([item popout?]
   (rf/with-frame :rf/xray
     (rf/dispatch-sync [:rf.xray/palette-open])
     (rf/dispatch-sync [:rf.xray/palette-invoke item popout?]))))

(defn- xray-sub [query]
  (rf/with-frame :rf/xray @(rf/subscribe query)))

(defn- dispatched-row [id dispatch-id frame ev]
  {:id id :op-type :rf.event :operation :rf.event/dispatched
   :tags {:rf.trace/dispatch-id dispatch-id :frame frame :rf.event/v ev}})

;; ---- open / close / cursor ---------------------------------------------

(deftest palette-close-resets-state
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open]))
  (is (true? (:palette-open? (xray-db))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-set-query "abc"])
    (rf/dispatch-sync [:rf.xray/palette-cursor-set 3])
    (rf/dispatch-sync [:rf.xray/palette-close]))
  (is (= [false "" 0]
         ((juxt :palette-open? :palette-query :palette-cursor) (xray-db)))))

(deftest cursor-clamps-at-zero-and-max
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-cursor-up]))
  (is (= 0 (:palette-cursor (xray-db))))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-cursor-set 3])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4])
    (rf/dispatch-sync [:rf.xray/palette-cursor-down 4]))
  (is (= 4 (:palette-cursor (xray-db)))))

(deftest set-query-resets-cursor
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open])
    (rf/dispatch-sync [:rf.xray/palette-cursor-set 5])
    (rf/dispatch-sync [:rf.xray/palette-set-query "ev"]))
  (is (= ["ev" 0] ((juxt :palette-query :palette-cursor) (xray-db)))
      "a new query snaps the cursor back to the top result"))

;; ---- invoke ------------------------------------------------------------

(deftest invoke-select-panel-flips-tab-and-closes
  (setup!)
  (invoke-item! {:source :panel :id :trace :action [:palette/select-panel :trace]})
  (is (= [:trace false] ((juxt :selected-tab :palette-open?) (xray-db)))))

;; A recent-event pick drives the SHARED spine focus (`:rf.xray/focus-event`),
;; which the Epoch panel reads. Items are built by the real source fn.

(deftest invoke-select-event-focuses-the-chosen-dispatch-rf2-gwye-8
  ;; Choosing the OLDER of two identical event vectors focuses that dispatch
  ;; and its epoch, and lands on the Epoch tab.
  (setup!)
  (let [rows  [(dispatched-row 1 1 :rf/default [:counter/inc])
               (dispatched-row 2 2 :rf/default [:counter/inc])]
        older (first (sources/recent-event-items rows))]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer rows])
      (rf/dispatch-sync [:rf.xray/sync-epoch-history
                         [{:epoch-id 101 :dispatch-id 1 :frame :rf/default}
                          {:epoch-id 102 :dispatch-id 2 :frame :rf/default}]]))
    (is (= [2 102] ((juxt :dispatch-id :epoch-id) (xray-sub [:rf.xray/focus])))
        "precondition: focus is on the latest event")
    (invoke-item! older)
    (is (= [1 101 :rf/default]
           ((juxt :dispatch-id :epoch-id :frame) (xray-sub [:rf.xray/focus]))))
    (is (= 101 (get-in (xray-sub [:rf.xray/epoch-pipeline]) [:record :epoch-id]))
        "the Epoch panel's record is the chosen event's")
    (is (= [:epoch false] ((juxt :selected-tab :palette-open?) (xray-db))))))

(deftest invoke-select-event-selects-the-rows-own-frame-rf2-gwye-8
  ;; Two frames reuse dispatch id 1 — the chosen row's frame wins.
  (setup!)
  (let [rows   [(dispatched-row 1 1 :app/a [:counter/inc])
                (dispatched-row 2 1 :app/b [:counter/inc])]
        a-item (first (sources/recent-event-items rows))]
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer rows]))
    (is (= :app/b (:frame (xray-sub [:rf.xray/focus])))
        "precondition: focus is on the head frame")
    (invoke-item! a-item)
    (is (= [1 :app/a] ((juxt :dispatch-id :frame) (xray-sub [:rf.xray/focus]))))))

(deftest invoke-clear-trace-buffer-empties-buffer
  (setup!)
  (trace-collector/seed-trace-for-test! {:id 1 :op :rf.event/handled :event-id [:foo]})
  (is (pos? (count (trace-collector/buffer-for-test))) "precondition: the buffer is seeded")
  (invoke-item! {:source :command :id :clear-trace-buffer :action [:palette/clear-trace-buffer]})
  (is (zero? (count (trace-collector/buffer-for-test))))
  (is (false? (:palette-open? (xray-db)))))

(deftest popout-flag-routes-through-fx-when-popoutable
  (setup!)
  (install-popout-counter!)
  (invoke-item! {:source :panel :id :trace :action [:palette/select-panel :trace]
                 :popout? false}
                true)
  (is (zero? @popout-calls)
      "a non-popoutable item invokes normally even with the Ctrl-modifier flag set")
  (invoke-item! {:source :recent-event :id [:foo/bar 1]
                 :action [:palette/select-event 1 :rf/default] :popout? true}
                true)
  (is (= 1 @popout-calls) "popout? true + popoutable item → fx fires once"))

(deftest invoke-open-popout-fires-fx
  (setup!)
  (install-popout-counter!)
  (invoke-item! {:source :command :id :open-popout :action [:palette/open-popout]})
  (is (= 1 @popout-calls)))

;; ---- command verbs -----------------------------------------------------

(deftest invoke-cycle-reduced-motion-walks-the-tri-state
  (setup!)
  (config/update-setting! :general :reduced-motion-override :os)
  (let [item   {:source :command :id :cycle-reduced-motion
                :action [:palette/cycle-reduced-motion]}
        cycle! #(do (invoke-item! item)
                    (config/get-setting :general :reduced-motion-override))]
    (is (= [:always :never] [(cycle!) (cycle!)]) ":os → :always → :never")))

(deftest invoke-cycle-density-drives-the-settings-control-rf2-gwye-9
  ;; The palette's density command flips the SAME setting the Settings radio
  ;; writes (:cosy ↔ :compact), so the config value and the density sub agree.
  (setup!)
  (config/update-setting! :general :density :cosy)
  (let [item    (first (filter #(= :density-toggle (:id %)) (sources/setting-items)))
        density #(vector (config/get-setting :general :density)
                         (xray-sub [:rf.xray/density]))]
    (invoke-item! item)
    (is (= [:compact :compact] (density)))
    (is (false? (:palette-open? (xray-db))))
    (invoke-item! item)
    (is (= [:cosy :cosy] (density)) "a second invocation cycles back")))

(deftest invoke-jump-to-settings-opens-popup
  (setup!)
  (invoke-item! {:source :command :id :jump-to-settings :action [:palette/jump-to-settings]})
  (is (= [true false] ((juxt :settings-open? :palette-open?) (xray-db)))))

(deftest invoke-toggle-mode-flips-runtime-static
  (setup!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-mode :dynamic]))
  (invoke-item! {:source :command :id :toggle-mode :action [:palette/toggle-mode]})
  (is (= :static (:mode (xray-db)))))

(deftest invoke-select-static-tab-flips-static-slot
  (setup!)
  (invoke-item! {:source :panel :id [:static :routes]
                 :action [:palette/select-static-tab :routes]})
  (is (= [:routes false]
         ((juxt :rf.xray.static/selected-tab :palette-open?) (xray-db)))))

;; ---- recents tracking --------------------------------------------------

(deftest invoke-records-only-command-recents-deduped
  (setup!)
  (doseq [item [{:source :command :id :toggle-theme :action [:palette/toggle-theme]}
                {:source :panel :id :trace :action [:palette/select-panel :trace]}
                {:source :command :id :jump-to-settings :action [:palette/jump-to-settings]}
                {:source :command :id :toggle-theme :action [:palette/toggle-theme]}]]
    (invoke-item! item))
  (is (= [:toggle-theme :jump-to-settings] (:palette-recents (xray-db)))
      "a re-invoked command bubbles to the head without duplicating; panel jumps are not recorded"))

;; ---- snapshot-app-db routes its off-box payload through safe egress ----

;; `:palette/snapshot-app-db` ships the focused frame's app-db to TWO off-box
;; sinks, `console.log` and `navigator.clipboard.writeText`, and must route it
;; through `egress/egress-value` first so a frame-declared sensitive slot
;; never leaves the box raw.

(defn- capture-snapshot-sinks!
  "Capture the snapshot fx's console.log and clipboard payloads. Returns
  `{:console (atom []) :clipboard (atom []) :restore f}`; call `:restore` to
  put the real console.log back."
  []
  (let [console-payloads   (atom [])
        clipboard-payloads (atom [])
        orig-log           (when (and (exists? js/console) (.-log js/console))
                             (.-log js/console))]
    ;; Capture only the snapshot-tagged call, and pass everything through:
    ;; the node-test reporter prints its FAIL banners via console.log.
    (set! (.-log js/console)
          (fn [& args]
            (when (and (string? (first args))
                       (re-find #"\[rf2-xray\] palette snapshot" (first args)))
              (swap! console-payloads conj (second args)))
            (when orig-log (apply orig-log args))
            nil))
    ;; Node has no navigator.clipboard, so synthesise one.
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

;; The snapshot reads the HOST frame the L1 picker focused, not `:rf/xray`
;; where the palette dispatches, so the secret and its classification live on
;; a dedicated `:rf/host` frame.

(def ^:private host-frame :rf/host)

(defn- seed-sensitive-host! [db-fn]
  (rf/make-frame {:id host-frame})
  (rf.frame/swap-runtime-db! host-frame
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  (rf/with-frame host-frame
    (rf/reg-event :test/seed-host (fn [{:keys [db]} _] {:db (db-fn db)}))
    (rf/dispatch-sync [:test/seed-host])))

(defn- drive-snapshot-of-host! []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-frame host-frame]))
  (invoke-item! {:source :command :id :snapshot-app-db :action [:palette/snapshot-app-db]}))

(deftest snapshot-app-db-redacts-sensitive-slot-on-both-off-box-sinks
  (setup!)
  (let [sinks (capture-snapshot-sinks!)]
    (try
      (seed-sensitive-host!
        (fn [db] (assoc db :auth {:username "ada" :password "hunter2"})))
      (drive-snapshot-of-host!)
      (is (= ["ada" :rf/redacted]
             ((juxt :username :password) (:auth (first @(:console sinks)))))
          "console payload redacts the sensitive slot and keeps its sibling")
      (let [edn (first @(:clipboard sinks))]
        (is (re-find #":rf/redacted" edn)
            "clipboard payload carries the :rf/redacted marker")
        (is (not (re-find #"hunter2" edn))
            "the raw secret never crosses the clipboard off-box sink"))
      (finally ((:restore sinks))))))

(deftest snapshot-app-db-size-elides-large-slot-on-both-off-box-sinks
  (setup!)
  (let [sinks (capture-snapshot-sinks!)]
    (try
      (rf/make-frame {:id host-frame})
      (rf.frame/swap-runtime-db! host-frame
        (fn [rt] (rf.elision/apply-classification-effects rt {:large [[:blob :payload]]})))
      (rf/with-frame host-frame
        (rf/reg-event :test/seed-blob
          (fn [{:keys [db]} _] {:db (assoc db :blob {:payload {:big "value"}})}))
        (rf/dispatch-sync [:test/seed-blob]))
      (drive-snapshot-of-host!)
      (is (contains? (get-in (first @(:console sinks)) [:blob :payload])
                     :rf.size/large-elided)
          "console payload size-elides the frame-declared large slot")
      (finally ((:restore sinks))))))
