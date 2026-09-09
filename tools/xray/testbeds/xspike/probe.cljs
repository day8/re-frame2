(ns xspike.probe
  "SPIKE (rf2-k97c.1) — measurement instruments.

  Everything the report's four decision numbers and the six per-arm
  observations are read from. Exported onto `window.xspike` so a browser
  driver can read them without a test runner.

  THROWAWAY."
  (:require [clojure.string]
            [re-frame.core :as rf]
            [re-frame.subs.tooling :as rf.subs.tooling]
            [re-frame.substrate.adapter :as rf.adapter]))

;; ---------------------------------------------------------------------------
;; (1) Did the tool invoke the HOST adapter's rendering entry?  It must not.
;;     `rf.adapter/render` is the single call-through every substrate paint
;;     takes (Xray's production mount calls it three times).
;; ---------------------------------------------------------------------------

(defonce host-render-calls (atom 0))
(defonce ^:private spy-armed? (atom false))

(defn arm-render-counter!
  "Count every call through the substrate's public `render` door."
  []
  (when-not @spy-armed?
    (let [orig rf.adapter/render]
      (set! rf.adapter/render
            (fn xspike-render-spy [& args]
              (swap! host-render-calls inc)
              (apply orig args)))
      (reset! spy-armed? true)))
  :armed)

;; ---------------------------------------------------------------------------
;; (2) Reader / listener baseline — the frame's sub-cache
;; ---------------------------------------------------------------------------

(defn sub-cache
  "Public tooling snapshot of `frame-id`'s sub-cache:
  `{query-v {:value v :ref-count n ...}}`."
  [frame-id]
  (rf.subs.tooling/sub-cache-snapshot frame-id))

(defn sub-cache-count [frame-id]
  (count (sub-cache frame-id)))

(defn sub-cache-refcount-total
  "Sum of live consumer ref-counts across the frame's cached reactions.
  THE leak instrument: a tool that unmounts without releasing leaves
  ref-counts standing."
  [frame-id]
  (reduce + 0 (keep :ref-count (vals (sub-cache frame-id)))))

(defn sub-cache-detail [frame-id]
  (->> (sub-cache frame-id)
       (map (fn [[q e]] [(pr-str q) (:ref-count e)]))
       (sort-by first)
       vec))

;; ---------------------------------------------------------------------------
;; (3) Evidence integrity — tool activity in the APPLICATION's epoch :renders
;; ---------------------------------------------------------------------------

(defn epoch-history [frame-id]
  (try (rf/epoch-history frame-id) (catch :default _ nil)))

(defn render-view-ids
  "Every view id appearing in `frame-id`'s epoch `:renders`, whole
  retained history. An Xray view id here is the rf2-tqlmq leak."
  [frame-id]
  (->> (epoch-history frame-id)
       (mapcat :renders)
       (map (fn [r] (or (:view-id r) (first (:render-key r)))))
       (remove nil?)
       (map str)
       distinct
       vec))

(defn xray-renders-in
  "The subset of `render-view-ids` naming an XRAY view or one of the two
  ARM panels. MUST be empty for the application frames — this is the
  rf2-tqlmq evidence-integrity probe. The spike's own APPLICATION views
  (`xspike.slim/...`, `xspike.hic/...`) are the host's and are excluded
  deliberately; only tool-side ids count as a leak."
  [frame-id]
  (vec (filter (fn [s] (or (re-find #"(?i)xray" s)
                           (re-find #"xspike\.arm" s)))
               (render-view-ids frame-id))))

(defn epoch-count [frame-id]
  (count (epoch-history frame-id)))

;; ---- (3b) a SECOND evidence instrument, independent of the epoch window ---
;;
;; `:renders` rows are captured inside a dispatch cascade; a tool paint
;; scheduled on an animation frame lands outside every epoch and is
;; invisible to `xray-renders-in`. This listener sees every view render
;; the framework emits, with the frame it resolved to, whenever it
;; happens — so a tool render stamped with an APPLICATION frame is
;; detectable even when no epoch is open.

(defonce view-renders (atom []))
(defonce ^:private renders-armed? (atom false))

(defn arm-view-render-listener! []
  (when-not @renders-armed?
    (rf/register-listener! :trace ::xspike-view-renders
      (fn [event]
        (let [operation (str (:operation event))]
          (when (clojure.string/includes? operation "rf.view")
            (swap! view-renders (fn [v] (conj (if (> (count v) 200) (vec (drop 50 v)) v)
                                              (pr-str (select-keys event [:op :operation :tags]))))))))) 
    (reset! renders-armed? true))
  :armed)

(defn renders-mentioning
  "Recorded render events whose text names `needle`."
  [needle]
  (vec (filter (fn [s] (clojure.string/includes? s needle)) @view-renders)))

;; ---------------------------------------------------------------------------
;; (4) DOM readings
;; ---------------------------------------------------------------------------

(defn text-of [selector]
  (when-let [el (.querySelector js/document selector)]
    (.-textContent el)))

(defn node-count [selector]
  (.-length (.querySelectorAll js/document selector)))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn frame-report [frame-id]
  {:subCache    (sub-cache-count frame-id)
   :refTotal    (sub-cache-refcount-total frame-id)
   :epochs      (epoch-count frame-id)
   :xrayInEpoch (xray-renders-in frame-id)
   :renderIds   (render-view-ids frame-id)})

(defn snapshot []
  {:adapter     (str (:kind (rf.adapter/current-adapter)))
   :hostRenders @host-render-calls
   :above       (frame-report :above)
   :below       (frame-report :below)
   :xray        {:subCache (sub-cache-count :rf/xray)
                 :refTotal (sub-cache-refcount-total :rf/xray)
                 :detail   (sub-cache-detail :rf/xray)}})

(defn export! []
  (let [o (or (.-SPIKE js/globalThis)
              (let [n (js-obj)] (set! (.-SPIKE js/globalThis) n) n))]
    (set! (.-snapshot o) (fn [] (clj->js (snapshot))))
    (set! (.-snapshotEdn o) (fn [] (pr-str (snapshot))))
    (set! (.-hostRenders o) (fn [] @host-render-calls))
    (set! (.-viewRenders o) (fn [] (clj->js @view-renders)))
    (set! (.-rendersMentioning o) (fn [s] (clj->js (renders-mentioning s))))
    o))
