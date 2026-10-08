(ns day8.re-frame2-xray.settings.effects-cljs-test
  "Host-free half of the Settings side-effect tests: the substrate depth
  knobs, the boot-time width clamp, `init!`'s load-and-apply, density and
  the keybinding slot. Rows that read a DOM mutation live in
  `effects-dom-cljs-test`: node has no `js/document`, and the browser lane
  loads only `-dom-cljs-test` namespaces."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch.state :as rf.epoch.state]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset effects/detach-auto-open-watcher!}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- substrate depth knobs -----------------------------------------------

(deftest apply-epoch-history-writes-substrate-depth
  (setup!)
  (let [orig (rf.epoch.state/depth)]
    (try
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :general :epoch-history 150]))
      (is (= 150 (rf.epoch.state/depth))
          "the settings-update event reaches the substrate ring")
      (effects/apply-epoch-history! nil)
      (effects/apply-epoch-history! 0)
      (is (= 150 (rf.epoch.state/depth))
          "nil and 0 are dropped, so a malformed persisted value never zeroes the ring")
      (finally
        (rf/configure! {:epoch-history {:depth orig}})))))

(deftest apply-cascades-retained-writes-through-to-configure
  (setup!)
  (let [calls (atom [])]
    (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :buffer :events-retained 17]))
      (effects/apply-events-retained! nil)
      (effects/apply-events-retained! 0)
      (is (= 17 (config/get-setting :buffer :events-retained))
          "the settings-update event writes the config slot")
      (is (= [{:trace-buffer {:events-retained 17}}] @calls)
          "and reaches configure! once; nil and 0 are dropped at the effect"))))

;; ---- panel width clamp ----------------------------------------------------
;;
;; The boot path clamps as well as the drag handler: a width dragged wide on
;; a large monitor must not replay verbatim at boot on a narrow one, where
;; the host's `flex-basis` could squeeze the app to nothing.

(deftest apply-panel-width-never-persists-an-inherited-width
  (testing "with no persisted width the clamp fits the live map and leaves
            storage untouched — a stored 540 would outrank every later host
            width, even on a wide screen"
    (effects/apply-panel-width! config/default-panel-width-px 600)
    (is (= [540 nil]
           [(config/get-setting :general :panel-width-px)
            (#'config/storage-get config/settings-storage-key)]))))

(deftest apply-all-clamps-the-persisted-width
  (testing "`apply-all!`, the preload and `init!` boot path, clamps an
            oversize persisted width and writes the clamp back, so storage
            converges instead of re-clamping on every boot"
    (config/update-setting! :general :panel-width-px 4000)
    (effects/apply-all!)
    (reset! config/settings config/default-settings)
    (config/load-settings-from-storage!)
    ;; No `js/window` on node: the clamp uses its 2000px fallback viewport.
    (is (= 1800 (config/get-setting :general :panel-width-px)))))

;; ---- init! (the manual install path) ---------------------------------------

(deftest init-loads-and-applies-persisted-settings
  (#'config/storage-set! config/settings-storage-key
                         (pr-str {:general {:text-size 19 :epoch-history 123}
                                  :buffer  {:events-retained 21}}))
  (let [calls (atom [])]
    (with-redefs [rf/configure! (fn [config-map] (swap! calls conj config-map) nil)]
      (core/init!))
    (is (= 19 (config/get-setting :general :text-size))
        "init! loaded the persisted Settings")
    (is (every? (set @calls) [{:epoch-history {:depth 123 :trace-events-keep 123}}
                              {:trace-buffer {:events-retained 21}}])
        "and apply-all! routed both persisted depths to the substrate")))

(deftest init-opts-still-win-over-persisted-settings
  ;; spec/015: `init! opts` is the last-mile seam, applied after the load.
  (#'config/storage-set! config/settings-storage-key (pr-str {:theme :dark}))
  (core/init! {:theme :light})
  (is (= :light (config/get-setting :theme nil))))

;; ---- density ----------------------------------------------------------------

(deftest density->px-falls-back-to-cosy-on-unknown
  (is (= [12 13 13] (mapv effects/density->px [:compact :cosy :comfy]))
      "the two tiers, and a persisted :comfy (any unknown value) lands on cosy")
  (is (= tokens/font-size-default (str (effects/density->px :cosy) "px"))
      "cosy is the type scale's baseline default"))

(deftest density-effect-agrees-with-the-density-sub
  (testing "the px the effect writes for a stored density is the px of the
            tier `:rf.xray/density` reports, so font size and row rhythm agree"
    (setup!)
    (doseq [stored [:compact :comfy]]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :general :density stored])
        (let [reported @(rf/subscribe [:rf.xray/density])]
          (is (= (effects/density->px reported) (effects/density->px stored))
              (str "stored " stored " → sub reports " reported)))))))

;; ---- keybinding slot ----------------------------------------------------------

(deftest keybinding-enabled-update-flips-atom-and-mirrors-app-db
  (testing "the sub reads the live atom until the first dispatch; the event
            then flips the atom AND the app-db mirror, so the controlled
            checkbox re-fires in the same dispatch"
    (setup!)
    (rf/with-frame :rf/xray
      (let [state #(vector (config/keybinding-attach-enabled?)
                           @(rf/subscribe [:rf.xray/keybinding-enabled?]))]
        (config/set-keybinding-enabled! false)
        (is (= [false false] (state)) "pre-dispatch, the sub falls back to the atom")
        (rf/dispatch-sync [:rf.xray/keybinding-enabled-update true])
        (is (= [true true] (state)))
        (rf/dispatch-sync [:rf.xray/keybinding-enabled-update false])
        (is (= [false false] (state)))
        (config/set-keybinding-enabled! true)))))

;; `keybinding/attach!` reads the slot once, and on the preload path it has
;; already attached by the time the host's `configure!` runs, so the slot is
;; watched. These rows pin that a flip reaches the listener at all;
;; `keybinding_cljs_test` owns the listener mechanics.

(deftest keybinding-enabled-flip-detaches-and-reattaches
  (testing "flipping the slot drives the listener, through the setter and
            through the host-facing `configure!` key"
    (let [calls (atom [])]
      (with-redefs [keybinding/attach! (fn [] (swap! calls conj :attach) nil)
                    keybinding/detach! (fn [] (swap! calls conj :detach) nil)]
        (config/set-keybinding-enabled! false)
        (is (= [:detach] @calls)
            "a false flip removes the listener the preload already attached")
        (config/set-keybinding-enabled! true)
        (is (= [:detach :attach] @calls)
            "and flipping back re-attaches")
        (config/set-keybinding-enabled! nil)
        (is (= [:detach :attach] @calls)
            "`nil` resets to the default `true`, already current — no redundant attach")
        (config/configure! {:rf.xray/keybinding-enabled? false})
        (is (= [:detach :attach :detach] @calls)
            "the embed host's `configure!` surrender switch reaches the listener")
        (config/set-keybinding-enabled! true)))))
