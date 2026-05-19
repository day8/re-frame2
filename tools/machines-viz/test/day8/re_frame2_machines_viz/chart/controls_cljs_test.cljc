(ns day8.re-frame2-machines-viz.chart.controls-cljs-test
  "Pure-data tests for the chart viewport controls (rf2-y3l8z).

  Asserts the reducer's contract, the zoom-about cursor invariant,
  the fit-viewport maths, the wheel / drag / keyboard event helpers,
  and the toolbar's hiccup shape. JVM-runnable so `clojure -M:test`
  picks them up."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.controls :as ctl]))

;; ---- clamp + identity ---------------------------------------------------

(deftest clamp-scale-bounds
  (is (= ctl/zoom-min (ctl/clamp-scale -1)))
  (is (= ctl/zoom-min (ctl/clamp-scale 0)))
  (is (= 1.0          (double (ctl/clamp-scale 1))))
  (is (= ctl/zoom-max (ctl/clamp-scale 10)))
  (is (= 1.0          (double (ctl/clamp-scale 1)))))

(deftest identity-viewport-shape
  (let [id (ctl/identity-viewport)]
    (is (= 1 (:scale id)))
    (is (= 0 (:tx id)))
    (is (= 0 (:ty id)))))

;; ---- zoom-about — cursor invariant --------------------------------------

(deftest zoom-about-keeps-cursor-anchored
  (testing "zoom about (200,150) — the chart-world point under (200,150) survives"
    (let [vp0    (ctl/identity-viewport)
          ;; chart-world point that's currently under (200,150)
          ;; with the identity viewport is just (200,150).
          vp1    (ctl/zoom-about vp0 2.0 200 150)
          ;; after zoom, the same world point should still be at (200,150)
          ;; i.e. (200 - tx) / scale = 200 (world wx)
          ;;      (150 - ty) / scale = 150 (world wy)
          {:keys [scale tx ty]} vp1
          wx     (/ (- 200 tx) scale)
          wy     (/ (- 150 ty) scale)]
      (is (= 2.0 (double scale)))
      (is (< (Math/abs (- wx 200)) 0.0001))
      (is (< (Math/abs (- wy 150)) 0.0001)))))

(deftest zoom-about-respects-clamp
  (let [vp0 {:scale ctl/zoom-max :tx 0 :ty 0}
        vp1 (ctl/zoom-about vp0 10.0 100 100)]
    (is (= ctl/zoom-max (:scale vp1))
        "zooming in past the upper bound clamps at zoom-max")))

;; ---- fit-viewport -------------------------------------------------------

(deftest fit-viewport-centres-content
  (let [vp (ctl/fit-viewport
             {:content-width   400
              :content-height  300
              :viewport-width  800
              :viewport-height 600})
        ;; The content is half the viewport size — fit at scale 1
        ;; (since the natural fit math gives ~ (800-48)/400 ≈ 1.88,
        ;; clamp-scale leaves it at 1.88, NOT 1 — read the source
        ;; carefully: only over-scale is clamped at zoom-max).
        ;; Re-derive: sx = (800-48)/400 = 1.88; sy = (600-48)/300 = 1.84.
        ;; min = 1.84. So content scales up to fit.
        s  (:scale vp)
        tx (:tx vp)
        ty (:ty vp)]
    (is (< (- 1.84 s) 0.01))
    ;; Centre check: (viewport - content*scale)/2 == tx,ty
    (is (< (Math/abs (- tx (/ (- 800 (* 400 s)) 2.0))) 0.01))
    (is (< (Math/abs (- ty (/ (- 600 (* 300 s)) 2.0))) 0.01))))

(deftest fit-viewport-handles-degenerate-inputs
  (is (= (ctl/identity-viewport)
         (ctl/fit-viewport {:content-width 0 :content-height 0
                            :viewport-width 800 :viewport-height 600}))
      "zero content size → identity viewport")
  (is (= (ctl/identity-viewport)
         (ctl/fit-viewport {:content-width 100 :content-height 100
                            :viewport-width 0 :viewport-height 0}))
      "zero viewport size → identity viewport")
  (is (= (ctl/identity-viewport)
         (ctl/fit-viewport nil))
      "nil dimensions → identity viewport"))

(deftest fit-viewport-clamps-to-zoom-max
  ;; Tiny content in a huge viewport — natural fit would be 100x,
  ;; clamped at zoom-max.
  (let [vp (ctl/fit-viewport
             {:content-width   10
              :content-height  10
              :viewport-width  1200
              :viewport-height 1200})]
    (is (= ctl/zoom-max (:scale vp)))))

;; ---- reducer ------------------------------------------------------------

(deftest reducer-zoom-in
  (let [vp1 (ctl/apply-action {:scale 1 :tx 0 :ty 0}
              {:type :zoom-in
               :viewport-width 600 :viewport-height 400})]
    (is (= ctl/zoom-step (double (:scale vp1))))))

(deftest reducer-zoom-out
  (let [vp1 (ctl/apply-action {:scale 1 :tx 0 :ty 0}
              {:type :zoom-out
               :viewport-width 600 :viewport-height 400})]
    (is (< (Math/abs (- (/ 1.0 ctl/zoom-step) (double (:scale vp1))))
           0.0001))))

(deftest reducer-pan-by
  (let [vp1 (ctl/apply-action {:scale 1 :tx 10 :ty 20}
              {:type :pan-by :dx 5 :dy -3})]
    (is (= 15 (:tx vp1)))
    (is (= 17 (:ty vp1)))))

(deftest reducer-reset
  (let [vp1 (ctl/apply-action {:scale 2.5 :tx 30 :ty 40}
              {:type :reset})]
    (is (= (ctl/identity-viewport) vp1))))

(deftest reducer-nil-current
  (let [vp1 (ctl/apply-action nil {:type :reset})]
    (is (= (ctl/identity-viewport) vp1))))

(deftest reducer-unknown-action
  (let [cur {:scale 1.5 :tx 7 :ty 8}
        vp1 (ctl/apply-action cur {:type :unknown-action})]
    (is (= cur vp1)
        "unknown actions pass through unchanged")))

;; ---- event helpers ------------------------------------------------------

(deftest wheel-handler-zoom-in-on-up-scroll
  (let [a (ctl/wheel->action {:delta-y -100 :x 50 :y 60})]
    (is (= :zoom-at (:type a)))
    (is (= 50 (:x a)))
    (is (= 60 (:y a)))
    (is (= ctl/wheel-zoom-step (double (:factor a))))))

(deftest wheel-handler-zoom-out-on-down-scroll
  (let [a (ctl/wheel->action {:delta-y 100 :x 50 :y 60})]
    (is (= :zoom-at (:type a)))
    (is (< (Math/abs (- (/ 1.0 ctl/wheel-zoom-step) (double (:factor a))))
           0.0001))))

(deftest wheel-handler-noops-on-zero-delta
  (is (nil? (ctl/wheel->action {:delta-y 0 :x 50 :y 60}))))

(deftest drag-step-uses-origin-viewport
  (let [drag-state (ctl/drag-state-zero 100 100 {:scale 1 :tx 10 :ty 20})
        vp1 (ctl/drag-step drag-state 150 130)]
    (is (= 60 (:tx vp1))
        "origin tx (10) + cursor dx (50) = 60")
    (is (= 50 (:ty vp1))
        "origin ty (20) + cursor dy (30) = 50")))

(deftest drag-step-no-drag-state-returns-nil
  (is (nil? (ctl/drag-step nil 100 100)))
  (is (nil? (ctl/drag-step {:dragging? false} 100 100))))

(deftest key-handler-recognised-keys
  (is (= :zoom-in  (:type (ctl/key->action {:key "+"}))))
  (is (= :zoom-in  (:type (ctl/key->action {:key "="}))))
  (is (= :zoom-out (:type (ctl/key->action {:key "-"}))))
  (is (= :zoom-out (:type (ctl/key->action {:key "_"}))))
  (is (= :reset    (:type (ctl/key->action {:key "0"}))))
  (is (= :fit      (:type (ctl/key->action {:key "f"}))))
  (is (= :fit      (:type (ctl/key->action {:key "F"}))))
  (is (= :pan-by   (:type (ctl/key->action {:key "ArrowLeft"}))))
  (is (= :pan-by   (:type (ctl/key->action {:key "ArrowRight"}))))
  (is (= :pan-by   (:type (ctl/key->action {:key "ArrowUp"}))))
  (is (= :pan-by   (:type (ctl/key->action {:key "ArrowDown"}))))
  (is (nil? (ctl/key->action {:key "Tab"}))
      "unrecognised keys return nil"))

(deftest key-handler-pan-directions
  (is (pos? (:dx (ctl/key->action {:key "ArrowLeft"}))))
  (is (neg? (:dx (ctl/key->action {:key "ArrowRight"}))))
  (is (pos? (:dy (ctl/key->action {:key "ArrowUp"}))))
  (is (neg? (:dy (ctl/key->action {:key "ArrowDown"})))))

;; ---- toolbar hiccup -----------------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(deftest toolbar-renders-four-buttons
  (let [tree (ctl/toolbar {:viewport (ctl/identity-viewport)
                           :on-action (fn [_])})]
    (is (some? (find-by-testid tree "rf-mv-chart-controls")))
    (is (some? (find-by-testid tree "rf-mv-chart-controls-zoom-in")))
    (is (some? (find-by-testid tree "rf-mv-chart-controls-zoom-out")))
    (is (some? (find-by-testid tree "rf-mv-chart-controls-fit")))
    (is (some? (find-by-testid tree "rf-mv-chart-controls-reset")))
    (is (some? (find-by-testid tree "rf-mv-chart-controls-scale-chip")))))

(deftest toolbar-scale-chip-shows-percent
  (let [tree (ctl/toolbar {:viewport {:scale 2 :tx 0 :ty 0}})
        chip (find-by-testid tree "rf-mv-chart-controls-scale-chip")]
    (is (some #{"200%"} (rest chip)))))

(deftest toolbar-button-click-fires-action
  (let [fired (atom nil)
        tree  (ctl/toolbar {:viewport (ctl/identity-viewport)
                            :on-action #(reset! fired %)})
        [_ attrs] (find-by-testid tree "rf-mv-chart-controls-zoom-in")
        handler (:on-click attrs)]
    (is (fn? handler))
    (handler #?(:cljs nil :clj nil))
    (is (= :zoom-in (:type @fired)))))

(deftest toolbar-respects-custom-testid-prefix
  (let [tree (ctl/toolbar {:viewport (ctl/identity-viewport)
                           :testid-prefix "rf-my-prefix"})]
    (is (some? (find-by-testid tree "rf-my-prefix")))
    (is (some? (find-by-testid tree "rf-my-prefix-zoom-in")))))
