(ns day8.re-frame2-causa.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the interactive Machines canvas
  adapter (rf2-y3l8z).

  Covers:

    1. Registry wires the four subs + the canvas event family + the
       persist-view-mode fx.
    2. `:rf.causa.machine-canvas/apply-action` mutates the per-
       machine viewport slot in app-db.
    3. Drag start / move / end accumulate against the origin
       viewport.
    4. `:set-view-mode` updates the per-machine slot and fires the
       persist-view-mode fx with the latest map.
    5. `:measure` writes the viewport-dims slot.
    6. The `Chart` view returns hiccup carrying the canvas-host
       data-testids + the view-mode toggle + the controls toolbar."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.panels.machine-canvas :as mc]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- 1. Registry wires the canvas surface ------------------------------

(deftest registry-wires-canvas-subs
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (testing "every machine-canvas sub resolves through rf/subscribe"
      (doseq [q-v [[:rf.causa.machine-canvas/viewport-for :m]
                   [:rf.causa.machine-canvas/view-mode-for :m]
                   [:rf.causa.machine-canvas/view-mode-by-id]
                   [:rf.causa.machine-canvas/viewport-dims-for :m]]]
        (is (some? (rf/subscribe q-v))
            (str q-v " must resolve through rf/subscribe"))))))

;; ---- 2. apply-action mutates per-machine viewport ----------------------

(deftest apply-action-zooms-in
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/apply-action
       {:machine-id :m
        :action     {:type :zoom-in
                     :viewport-width 600 :viewport-height 400}}])
    (let [vp @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :m])]
      (is (some? vp))
      (is (> (:scale vp) 1.0)
          "zoom-in from identity moves scale above 1"))))

(deftest apply-action-fit-request-uses-measured-dims
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    ;; Measure first so :fit can fall back to the stored dims.
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/measure
       {:machine-id :m :width 800 :height 600}])
    ;; The toolbar emits :fit-request — apply-action expands it to
    ;; :fit using the measured dims + content-* keys when present.
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/apply-action
       {:machine-id :m
        :action     {:type           :fit-request
                     :content-width  400
                     :content-height 300}}])
    (let [vp @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :m])]
      (is (some? vp))
      (is (some? (:scale vp))))))

(deftest apply-action-isolated-per-machine
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/apply-action
       {:machine-id :alpha
        :action     {:type :zoom-in :viewport-width 600 :viewport-height 400}}])
    (let [a @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :alpha])
          b @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :beta])]
      (is (some? a))
      (is (nil? b)
          ":alpha mutation does not leak into :beta's viewport slot"))))

;; ---- 3. Drag accumulates against the origin viewport ------------------

(deftest drag-accumulates-against-origin
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    ;; Seed a viewport at (10, 20) so the drag has something to add to.
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/apply-action
       {:machine-id :m
        :action     {:type :pan-by :dx 10 :dy 20}}])
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/drag-start
       {:machine-id :m :x 100 :y 100}])
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/drag-move {:x 130 :y 150}])
    (let [vp @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :m])]
      (is (= 40 (:tx vp)) "10 (origin) + 30 (drag dx) = 40")
      (is (= 70 (:ty vp)) "20 (origin) + 50 (drag dy) = 70"))
    (rf/dispatch-sync [:rf.causa.machine-canvas/drag-end])))

(deftest drag-end-clears-drag-slot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/drag-start
       {:machine-id :m :x 0 :y 0}])
    (rf/dispatch-sync [:rf.causa.machine-canvas/drag-end])
    ;; After end, drag-move is a no-op (the slot is gone).
    (rf/dispatch-sync [:rf.causa.machine-canvas/drag-move {:x 100 :y 100}])
    (let [vp @(rf/subscribe [:rf.causa.machine-canvas/viewport-for :m])]
      (is (or (nil? vp)
              (= 0 (:tx vp))
              (zero? (:tx vp)))
          "drag-move after drag-end is a no-op"))))

;; ---- 4. View-mode toggle + persistence fx ------------------------------

(deftest set-view-mode-mutates-slot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/set-view-mode
       {:machine-id :m :mode :list}])
    (let [mode @(rf/subscribe [:rf.causa.machine-canvas/view-mode-for :m])
          by-id @(rf/subscribe [:rf.causa.machine-canvas/view-mode-by-id])]
      (is (= :list mode))
      (is (= {:m :list} by-id)))))

(deftest set-view-mode-defaults-to-canvas
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (let [mode @(rf/subscribe [:rf.causa.machine-canvas/view-mode-for :m])]
      (is (= :canvas mode)
          "Unset slot defaults to :canvas — the dominant surface"))))

(deftest set-view-mode-normalises-bad-input
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/set-view-mode
       {:machine-id :m :mode :nonsense}])
    (let [mode @(rf/subscribe [:rf.causa.machine-canvas/view-mode-for :m])]
      (is (= :canvas mode)
          "Unknown modes fall back to :canvas"))))

(deftest persist-view-mode-fx-registered
  (setup-causa-frame!)
  (is (some?
        (registrar/handler
          :fx :rf.causa.machine-canvas/persist-view-mode))
      "persist-view-mode fx is in the registrar"))

;; ---- 5. Measure writes viewport-dims slot ------------------------------

(deftest measure-stores-viewport-dims
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/measure
       {:machine-id :m :width 1024 :height 768}])
    (let [dims @(rf/subscribe
                  [:rf.causa.machine-canvas/viewport-dims-for :m])]
      (is (= 1024 (:width dims)))
      (is (= 768 (:height dims))))))

(deftest measure-ignores-degenerate-input
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync
      [:rf.causa.machine-canvas/measure
       {:machine-id :m :width 0 :height 0}])
    (let [dims @(rf/subscribe
                  [:rf.causa.machine-canvas/viewport-dims-for :m])]
      (is (nil? dims)
          "zero / nil dims are ignored — caller waits for a real measurement"))))

;; ---- 6. Chart view hiccup shape ----------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(deftest chart-view-emits-canvas-host
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (let [positioned {:nodes [{:node-id :a :x 0 :y 0 :width 40 :height 20
                               :path [:a] :label "a"}]
                      :edges []
                      :width 200 :height 100
                      :initial-id nil}
          tree (mc/Chart {:positioned positioned :machine-id :m})]
      (is (some? (find-by-testid tree "rf-causa-machine-canvas-host"))
          "canvas host wrapper present")
      (is (some? (find-by-testid tree "rf-causa-machine-canvas-toolbar"))
          "toolbar present"))))

(deftest chart-view-emits-view-mode-toggle
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (let [positioned {:nodes [] :edges [] :width 100 :height 50 :initial-id nil}
          tree (mc/Chart {:positioned positioned :machine-id :m})]
      (is (some? (find-by-testid tree "rf-causa-machine-canvas-view-mode-toggle"))))))

;; ---- 7. Static-mode opt-out knobs (rf2-md9oz) --------------------------

(deftest chart-view-omits-view-mode-toggle-when-knob-false
  (testing "rf2-md9oz — Static Topology consumer passes
            :show-view-mode-toggle? false to suppress the Canvas/List
            pill (Static panel owns sub-mode at L3)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [positioned {:nodes [] :edges [] :width 100 :height 50 :initial-id nil}
            tree (mc/Chart {:positioned positioned
                            :machine-id :m
                            :show-view-mode-toggle? false})]
        (is (some? (find-by-testid tree "rf-causa-machine-canvas-host"))
            "canvas host still mounts")
        (is (nil? (find-by-testid tree "rf-causa-machine-canvas-view-mode-toggle"))
            "view-mode toggle is suppressed")))))

(deftest chart-view-omits-controls-toolbar-when-knob-false
  (testing "Optional escape hatch — callers can suppress the controls
            toolbar without forking the adapter. Default-on (covered
            in chart-view-emits-canvas-host)."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [positioned {:nodes [] :edges [] :width 100 :height 50 :initial-id nil}
            tree (mc/Chart {:positioned positioned
                            :machine-id :m
                            :show-controls-toolbar? false})]
        (is (nil? (find-by-testid tree "rf-causa-machine-canvas-toolbar"))
            "controls toolbar suppressed when knob is false")))))

(deftest chart-view-forwards-testid-to-inner-svg
  (testing "rf2-md9oz — the static panel needs to override the inner
            SVG's data-testid so existing static-panel selectors keep
            matching. Chart forwards :testid to mv-svg/render."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [positioned {:nodes [{:node-id :a :x 0 :y 0
                                 :width 40 :height 20
                                 :path [:a] :label "a"}]
                        :edges []
                        :width 200 :height 100
                        :initial-id nil}
            tree (mc/Chart {:positioned positioned
                            :machine-id :m
                            :testid     "rf-causa-static-machines-topology-svg"})]
        (is (some? (find-by-testid tree
                                   "rf-causa-static-machines-topology-svg"))
            "inner SVG carries the overridden testid")))))
