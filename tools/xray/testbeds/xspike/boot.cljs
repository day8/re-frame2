(ns xspike.boot
  "SPIKE (rf2-k97c.1) — shared boot + a FRAMEWORK-FREE control strip.

  The control strip is raw DOM on purpose: it must not itself be a view
  in either substrate, so neither arm's readings are perturbed by the
  driver.

  The Xray install here is the PRODUCTION one minus the auto-mount:
  registry, trace collector, epoch collector, settings, image seating and
  the first-mount hooks all run exactly as `day8.re-frame2-xray.preload`
  runs them. Only `mount/boot-on-runtime-ready!` — the auto-open that
  calls the HOST adapter's `:render` — is not called, because owning the
  root is the whole subject of the spike.

  THROWAWAY."
  (:require [re-frame.core :as rf]
            [re-frame.epoch]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.install :as xray-install]
            [day8.re-frame2-xray.mount :as xray-mount]
            [day8.re-frame2-xray.registry :as xray-registry]
            [day8.re-frame2-xray.core :as xray-core]
            [xspike.app :as app]
            [xspike.arm-a :as arm-a]
            [xspike.arm-b :as arm-b]
            [xspike.observe :as obs]
            [xspike.probe :as probe]))

(defn install-xray! []
  (probe/arm-view-render-listener!)
  (xray-config/set-auto-open! false)
  (xray-config/load-settings-from-storage!)
  (xray-registry/register-xray-handlers!)
  (xray-install/register-trace-collector!)
  (xray-install/register-epoch-collector!)
  (xray-install/install-browser-api-exports!)
  ;; Seat `:rf/xray` and run the first-mount hooks — the production
  ;; sequence a first `open!` would run, minus the mount itself.
  (xray-mount/ensure-xray-frame!)
  nil)

;; ---------------------------------------------------------------------------
;; Raw-DOM control strip
;; ---------------------------------------------------------------------------

(defn- el [tag attrs & kids]
  (let [n (.createElement js/document tag)]
    (doseq [[k v] attrs] (.setAttribute n (name k) v))
    (doseq [k kids] (.appendChild n (if (string? k) (.createTextNode js/document k) k)))
    n))

(defn- button [id label f]
  (let [b (el "button" {:id id :data-testid id
                        :style "margin:2px;padding:4px 8px;font:12px system-ui"})]
    (.appendChild b (.createTextNode js/document label))
    (.addEventListener b "click" (fn [_] (f)))
    b))

(defonce ^:private out (atom nil))

(defn say! [s]
  (when-let [o @out]
    (set! (.-textContent o) s))
  (js/console.log s)
  s)

(defn report! []
  (say! (pr-str (assoc (probe/snapshot)
                       :armA {:mounted (arm-a/mounted?) :observe (obs/stats)}
                       :armB {:mounted (arm-b/mounted?)}))))

(defn- earliest-epoch-id [frame]
  (:epoch-id (first (probe/epoch-history frame))))

(defn control-strip! [host]
  (let [bar (el "div" {:id "xspike-controls"
                       :style "position:sticky;top:0;background:#eee;padding:6px;border-bottom:1px solid #999;z-index:9"})
        pre (el "pre" {:id "xspike-out" :data-testid "xspike-out"
                       :style "font:11px monospace;white-space:pre-wrap;background:#111;color:#0f0;padding:6px;margin:0;max-height:180px;overflow:auto"})]
    (reset! out pre)
    (doseq [b [(button "bump-above" "bump ABOVE"
                       #(do (rf/dispatch [:xspike/bump] {:frame app/frame-above}) (js/setTimeout report! 50)))
               (button "bump-below" "bump BELOW"
                       #(do (rf/dispatch [:xspike/bump] {:frame app/frame-below}) (js/setTimeout report! 50)))
               (button "target-above" "target ABOVE"
                       #(do (xray-core/set-target-frame! app/frame-above) (js/setTimeout report! 50)))
               (button "target-below" "target BELOW"
                       #(do (xray-core/set-target-frame! app/frame-below) (js/setTimeout report! 50)))
               (button "mount-a" "mount ARM A"
                       #(do (arm-a/mount! (.getElementById js/document "tool-a")) (js/setTimeout report! 50)))
               (button "mount-a-leak" "mount ARM A (LEAK control: scoped to :above)"
                       #(do (arm-a/mount-leak! (.getElementById js/document "tool-a")) (js/setTimeout report! 50)))
               (button "mount-a-slim" "mount ARM A (slim root)"
                       #(do (arm-a/mount-slim! (.getElementById js/document "tool-a")) (js/setTimeout report! 50)))
               (button "unmount-a" "unmount ARM A"
                       #(do (arm-a/unmount!) (js/setTimeout report! 50)))
               (button "mount-b" "mount ARM B"
                       #(do (arm-b/mount! (.getElementById js/document "tool-b")) (js/setTimeout report! 50)))
               (button "unmount-b" "unmount ARM B"
                       #(do (arm-b/unmount!) (js/setTimeout report! 50)))
               (button "restore-above" "restore EARLIEST epoch into ABOVE"
                       #(do (rf/restore-epoch! app/frame-above (earliest-epoch-id app/frame-above))
                            (js/setTimeout report! 50)))
               (button "snapshot" "snapshot" report!)]]
      (.appendChild bar b))
    (.appendChild host bar)
    (.appendChild host pre)
    nil))

(defn export! []
  (probe/export!)
  (let [o (.-SPIKE js/globalThis)]
    (set! (.-mountA o) (fn [] (arm-a/mount! (.getElementById js/document "tool-a")) true))
    (set! (.-unmountA o) (fn [] (arm-a/unmount!) true))
    (set! (.-mountB o) (fn [] (arm-b/mount! (.getElementById js/document "tool-b")) true))
    (set! (.-unmountB o) (fn [] (arm-b/unmount!) true))
    (set! (.-bump o) (fn [f] (rf/dispatch [:xspike/bump] {:frame (keyword f)}) true))
    (set! (.-target o) (fn [f] (xray-core/set-target-frame! (keyword f)) true))
    (set! (.-restore o) (fn [f]
                          (let [k (keyword f)]
                            (rf/restore-epoch! k (earliest-epoch-id k)))))
    (set! (.-observe o) (fn [] (clj->js (obs/stats))))
    (set! (.-armA o) (fn [] (arm-a/mounted?)))
    (set! (.-armB o) (fn [] (arm-b/mounted?)))
    (set! (.-full o) (fn []
                       (clj->js (assoc (probe/snapshot)
                                       :armA {:mounted (arm-a/mounted?) :observe (obs/stats)}
                                       :armB {:mounted (arm-b/mounted?)}))))
    (set! (.-fullEdn o) (fn []
                          (pr-str (assoc (probe/snapshot)
                                         :armA {:mounted (arm-a/mounted?) :observe (obs/stats)}
                                         :armB {:mounted (arm-b/mounted?)}))))
    o))
