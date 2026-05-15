(ns re-frame.story.ui.shell.rails
  "Resizable Story shell rails. Keeps persistence, clamping, and the
  accessible splitter widget out of the top-level shell composer."
  (:require [reagent.core :as r]
            [re-frame.story.ui.shell-styles :refer [styles]]
            [re-frame.story.ui.state :as state]))

(def ^:private storage-key
  "re-frame.story/rail-widths")

(def default-widths
  {:left 260 :right 320})

(def constraints
  {:left       {:min 180 :max 420}
   :right      {:min 240 :max 520}
   :canvas-min 360})

(defn- safe-local-storage []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

(defn- viewport-width []
  (if (and (exists? js/window) (.-innerWidth js/window))
    (.-innerWidth js/window)
    1200))

(defn narrow-viewport? []
  (< (viewport-width) 820))

(defn- clamp-number [n lo hi]
  (-> n (max lo) (min hi)))

(defn normalise-widths [widths]
  (let [left-in   (or (:left widths) (:left default-widths))
        right-in  (or (:right widths) (:right default-widths))
        left      (clamp-number left-in
                                (get-in constraints [:left :min])
                                (get-in constraints [:left :max]))
        right     (clamp-number right-in
                                (get-in constraints [:right :min])
                                (get-in constraints [:right :max]))
        budget    (- (viewport-width) (:canvas-min constraints) 20)
        overflow  (max 0 (- (+ left right) budget))
        right-cut (min overflow (- right (get-in constraints [:right :min])))
        right'    (- right right-cut)
        overflow' (- overflow right-cut)
        left'     (- left (min overflow' (- left (get-in constraints [:left :min]))))]
    {:left left' :right right'}))

(defn current-widths []
  (normalise-widths
    (merge default-widths (:rail-widths @state/shell-state-atom))))

(defn- read-widths []
  (try
    (when-let [ls (safe-local-storage)]
      (when-let [raw (.getItem ls storage-key)]
        (let [parsed (js/JSON.parse raw)
              left   (.-left parsed)
              right  (.-right parsed)]
          (cond-> {}
            (number? left)  (assoc :left left)
            (number? right) (assoc :right right)))))
    (catch :default _ nil)))

(defn- persist! [widths]
  (try
    (when-let [ls (safe-local-storage)]
      (.setItem ls storage-key (js/JSON.stringify (clj->js widths))))
    (catch :default _ nil))
  nil)

(defn hydrate! []
  (when-let [stored (read-widths)]
    (let [widths (normalise-widths (merge default-widths stored))]
      (state/swap-state! assoc :rail-widths widths)))
  nil)

(defn set-width! [side width]
  (let [widths (normalise-widths
                 (assoc (current-widths) side width))]
    (state/swap-state! assoc :rail-widths widths)
    (persist! widths)))

(defn- adjust-width! [side delta]
  (set-width! side (+ (get (current-widths) side) delta)))

(defn splitter
  "Accessible mouse + keyboard separator between shell panes."
  [side]
  (let [dragging? (r/atom false)]
    (r/create-class
      {:display-name (str "rf-story-rail-splitter-" (name side))
       :reagent-render
       (fn [side]
         (let [widths  (current-widths)
               c       (get constraints side)
               left?   (= side :left)
               label   (if left? "Resize stories sidebar" "Resize inspectors rail")
               test-id (if left?
                         "story-left-rail-splitter"
                         "story-right-rail-splitter")
               step    16]
           [:div {:style            (merge (:splitter styles)
                                           (when @dragging? (:splitter-active styles)))
                  :data-test        test-id
                  :role             "separator"
                  :aria-label       label
                  :aria-orientation "vertical"
                  :aria-valuemin    (:min c)
                  :aria-valuemax    (:max c)
                  :aria-valuenow    (get widths side)
                  :tab-index        "0"
                  :on-key-down      (fn [e]
                                      (let [k     (.-key e)
                                            mult  (if (.-shiftKey e) 4 1)
                                            delta (* step mult)]
                                        (case k
                                          "ArrowLeft"  (do (.preventDefault e)
                                                           (adjust-width! side (if left? (- delta) delta)))
                                          "ArrowRight" (do (.preventDefault e)
                                                           (adjust-width! side (if left? delta (- delta))))
                                          "Home"       (do (.preventDefault e)
                                                           (set-width! side (:min c)))
                                          "End"        (do (.preventDefault e)
                                                           (set-width! side (:max c)))
                                          nil)))
                  :on-mouse-down    (fn [e]
                                      (.preventDefault e)
                                      (let [start-x (.-clientX e)
                                            start-w (get (current-widths) side)
                                            move-fn (atom nil)
                                            up-fn   (atom nil)]
                                        (reset! dragging? true)
                                        (reset! move-fn
                                                (fn [move-e]
                                                  (let [dx (- (.-clientX move-e) start-x)]
                                                    (set-width!
                                                      side
                                                      (+ start-w (if left? dx (- dx)))))))
                                        (reset! up-fn
                                                (fn [_]
                                                  (reset! dragging? false)
                                                  (.removeEventListener js/document "mousemove" @move-fn)
                                                  (.removeEventListener js/document "mouseup" @up-fn)))
                                        (.addEventListener js/document "mousemove" @move-fn)
                                        (.addEventListener js/document "mouseup" @up-fn)))}
            [:span {:style (:splitter-grip styles)}]]))})))
