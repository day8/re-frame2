;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md.
;;
;; The production-browser probe. One page, several arms, driven from
;; Playwright, which reads `window.__STUDIO__` when `window.__STUDIO_DONE__`
;; goes true.
;;
;; METHOD — the numbers are worthless without it.
;;
;;   - Arms are INTERLEAVED: the sample loop is `for sample: for arm:`, so a
;;     drift in machine load lands on every arm equally rather than on
;;     whichever arm happened to run second.
;;   - Every mount is timed inside `react-dom/flushSync`, so `react-ms`
;;     brackets the substrate's element construction AND React's render,
;;     commit and DOM mutation with nothing scheduled out of the window.
;;   - `layout-ms` is a FORCED synchronous reflow (`offsetHeight`) taken
;;     immediately after. Style recalculation and layout for what was just
;;     committed happen inside that read.
;;   - `settle-ms` = `react-ms` + `layout-ms`: the WORK a mount costs.
;;     `frame-ms` — to the next animation frame — rides along as evidence
;;     and is NOT the settlement figure, because it is quantised to the
;;     display's refresh interval and therefore reports the vsync wait, not
;;     the page.
;;   - Deterministic properties are asserted, not published: the element
;;     count and the `innerHTML` of every arm of a witness must agree. A
;;     mount that had not settled inside the timed region, or a floor whose
;;     hand transcription drifted, fails the parity check instead of
;;     reporting a fast time for a different page.
;;   - `act` is deliberately NOT used: it throws in a production React
;;     build, which is the build this probe exists to measure.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.probe
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as rdc]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.studio.floor :as floor]
            [re-frame.freehand.studio.witnesses-compiled :as wc]
            [re-frame.freehand.studio.witnesses-interpreted :as wi]))

;; --- fixture parameters -----------------------------------------------------

(def ^:private w1-rows 300)
(def ^:private w2-free 300)
(def ^:private w2-reads 100)
(def ^:private w3-fields 12)

(def ^:private warmup 5)
(def ^:private samples 30)

;; --- the frame --------------------------------------------------------------

(def ^:private fid ::frame)

(defn- register! []
  (rf/reg-event ::seed (fn [_ _] {:db {:tick 0 :fields {}}}))
  (rf/reg-event ::tick (fn [{:keys [db]} _] {:db (update db :tick inc)}))
  (rf/reg-event :studio/type
                (fn [{:keys [db]} [_ idx value]] {:db (assoc-in db [:fields idx] value)}))
  (rf/reg-event :studio/submit (fn [{:keys [db]} _] {:db db}))
  (rf/reg-sub :studio/tick  (fn [db _] (:tick db)))
  (rf/reg-sub :studio/field (fn [db [_ idx]] (or (get-in db [:fields idx]) "")))
  (rf/reg-sub :studio/error (fn [db [_ idx]]
                              (let [s (get-in db [:fields idx])]
                                (if (and s (< (count s) 3)) "too short" ""))))
  (rf/reg-sub :studio/submit-blocked? (fn [db _] (empty? (:fields db)))))

;; --- readings ---------------------------------------------------------------

(defn- now [] (js/performance.now))

(def ^:private sink
  "Where a forced-reflow reading goes. Without a live consumer Closure is
  free to drop the property read, and the layout it was taken to force
  never happens — which is exactly what a first run of this probe reported:
  `layout-ms` was 0.000 in every arm of every sample."
  (atom 0))

(defn- force-layout! []
  (swap! sink + (.-offsetHeight (.-documentElement js/document))))
(defn- heap [] (if-let [m (.-memory js/performance)] (.-usedJSHeapSize m) 0))
(defn- rAF [] (js/Promise. (fn [res] (js/requestAnimationFrame #(res (now))))))
(defn- elements [c] (.-length (.querySelectorAll c "*")))

(defn- canon
  "A canonical serialisation of a mounted subtree: tag, ATTRIBUTES IN NAME
  ORDER, and text, recursively.

  Not `innerHTML`, deliberately. `innerHTML` preserves attribute INSERTION
  order, and the two front ends write props in different orders — the
  interpreted walk writes the `.class` sugar before the authored attrs, the
  compiled emitter writes what the analyzer folded first. The first run of
  this probe reported W1 and W3 as disagreeing pages on exactly that, with
  identical attribute sets and identical values. Sorting the names compares
  the DOM instead of the serialiser."
  [node]
  (case (.-nodeType node)
    3 (str "#" (.-nodeValue node))
    1 (str "<" (.-tagName node)
           (apply str (sort (map (fn [a] (str " " (.-name a) "=" (pr-str (.-value a))))
                                 (array-seq (.-attributes node)))))
           ">"
           (apply str (map canon (array-seq (.-childNodes node))))
           "</" (.-tagName node) ">")
    ""))

;; --- the arms ---------------------------------------------------------------
;;
;; `:kind` is the mount path. `:freehand` goes through `v/mount`, which owns
;; the frame, the root registry and the boundary cache. `:react` is the FLOOR
;; and goes straight to `createRoot`, because a floor that mounted through
;; the substrate would be measuring the substrate.

(defn- arms []
  [{:id "w1-interpreted" :witness "w1" :kind :freehand :view wi/w1  :props {:rows w1-rows}}
   {:id "w1-compiled"    :witness "w1" :kind :freehand :view wc/w1  :props {:rows w1-rows}}
   {:id "w1-floor"       :witness "w1" :kind :react    :el #(floor/w1 w1-rows)}
   {:id "w2-interpreted" :witness "w2" :kind :freehand :view wi/w2  :props {:free w2-free}}
   {:id "w2-compiled"    :witness "w2" :kind :freehand :view wc/w2  :props {:free w2-free}}
   {:id "w2-floor"       :witness "w2" :kind :react    :el #(floor/w2 w2-free)}
   {:id "w2r-interpreted" :witness "w2r" :kind :freehand :view wi/w2r :props {:reads w2-reads}}
   {:id "w2r-compiled"    :witness "w2r" :kind :freehand :view wc/w2r :props {:reads w2-reads}}
   {:id "w3-interpreted" :witness "w3" :kind :freehand :view wi/w3  :props {:fields w3-fields}}
   {:id "w3-compiled"    :witness "w3" :kind :freehand :view wc/w3  :props {:fields w3-fields}}])

;; --- one mount sample -------------------------------------------------------

(defn- mount-sample!
  [{:keys [kind view props el id]} tag]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (let [holder (atom nil)
          h0     (heap)
          t0     (now)]
      (react-dom/flushSync
        (fn []
          (reset! holder
                  (if (= :react kind)
                    (doto (rdc/createRoot container) (.render (el)))
                    (v/mount [view props] container
                             {:frame fid
                              :disambiguator (keyword "studio" (str id "-" tag))})))))
      (let [t1 (now)
            _  (force-layout!)
            t2 (now)
            h1 (heap)]
        (.then (rAF)
               (fn [t3]
                 {:container  container
                  :mounted    @holder
                  :kind       kind
                  :react-ms   (- t1 t0)
                  :layout-ms  (- t2 t1)
                  :settle-ms  (- t2 t0)
                  :frame-ms   (- t3 t0)
                  :heap-bytes (- h1 h0)
                  :elements   (elements container)
                  :html       (canon container)}))))))

(defn- release! [{:keys [container mounted kind]}]
  (when mounted
    (react-dom/flushSync
      (fn [] (if (= :react kind)
               (.unmount ^js mounted)
               (.unmount (.-react-root ^root/Root mounted))))))
  (.remove container)
  nil)

;; --- one update sample ------------------------------------------------------

(defn- update-sample!
  [arm tag event]
  (-> (mount-sample! arm tag)
      (.then (fn [live]
               (let [t0 (now)]
                 (react-dom/flushSync (fn [] (rf/dispatch-sync event {:frame fid})))
                 (let [t1 (now)
                       _  (force-layout!)
                       t2 (now)]
                   (release! live)
                   {:react-ms  (- t1 t0)
                    :layout-ms (- t2 t1)
                    :settle-ms (- t2 t0)}))))))

;; --- the interleaved sample loops ------------------------------------------

(defn- sample-loop!
  "Run `f` over every entry of `plan`, `warmup + samples` times, interleaved,
  collecting the readings from the post-warmup rounds under `(key entry)`."
  [plan key f]
  (let [acc (atom {})]
    (-> (reduce
          (fn [p n]
            (reduce
              (fn [p2 entry]
                (.then p2 (fn [_]
                            (.then (f entry n)
                                   (fn [s]
                                     (when (>= n warmup)
                                       (swap! acc update (key entry) (fnil conj []) s))
                                     nil)))))
              p plan))
          (js/Promise.resolve nil)
          (range (+ warmup samples)))
        (.then (fn [_] @acc)))))

(defn- run-mounts! []
  (let [pages (atom {})]
    (-> (sample-loop! (arms) :id
                      (fn [arm n]
                        (.then (mount-sample! arm (str n))
                               (fn [live]
                                 (swap! pages assoc (:id arm)
                                        {:elements (:elements live) :html (:html live)})
                                 (release! live)
                                 (dissoc live :container :mounted :html)))))
        (.then (fn [m] {:mounts m :pages @pages})))))

(defn- run-updates! []
  (let [plan [["w2r-interpreted" (nth (arms) 6) [::tick]]
              ["w2r-compiled"    (nth (arms) 7) [::tick]]
              ["w3-interpreted"  (nth (arms) 8) [:studio/type 0 "abcd"]]
              ["w3-compiled"     (nth (arms) 9) [:studio/type 0 "abcd"]]]]
    (sample-loop! plan first
                  (fn [[_ arm event] n] (update-sample! arm (str "u" n) event)))))

;; --- the deterministic properties -------------------------------------------

(defn- parity
  "Every arm of a witness must have produced the SAME page. Answers a map of
  witness -> {:elements … :agree? …}."
  [pages]
  (reduce
    (fn [acc w]
      (let [ids  (mapv :id (filterv #(= w (:witness %)) (arms)))
            got  (mapv #(get pages %) ids)]
        (assoc acc w
               (let [htmls (mapv :html got)
                     base  (first htmls)]
                 (cond-> {:arms     ids
                          :elements (mapv :elements got)
                          :agree?   (apply = htmls)}
                   (not (apply = htmls))
                   (assoc :diffs
                          (vec (keep-indexed
                                 (fn [i h]
                                   (when (not= h base)
                                     (let [n (count (take-while true?
                                                                (map = base h)))]
                                       {:arm  (nth ids i)
                                        :at   n
                                        :base (subs base (max 0 (- n 40))
                                                    (min (count base) (+ n 90)))
                                        :got  (subs h (max 0 (- n 40))
                                                    (min (count h) (+ n 90)))})))
                                 htmls))))))))
    {}
    (distinct (mapv :witness (arms)))))

(defn- manifests []
  {"w1"      (:view-cell (v/manifest wc/w1))
   "w1-row"  (:view-cell (v/manifest wc/w1-row))
   "w2"      (:view-cell (v/manifest wc/w2))
   "w2-free" (:view-cell (v/manifest wc/w2-free))
   "w2r"     (:view-cell (v/manifest wc/w2r))
   "w2-read" (:view-cell (v/manifest wc/w2-read))
   "w3"      (:view-cell (v/manifest wc/w3))
   "w3-field" (:view-cell (v/manifest wc/w3-field))})

;; --- the focused profiling mode ---------------------------------------------
;;
;; `?profile=<arm-id>&n=<iterations>` runs ONE arm in a tight synchronous
;; mount/unmount loop with no canon serialisation, no heap reading and no
;; animation-frame wait, so a CPU profile taken across the page's lifetime is
;; almost entirely that arm. The whole-suite profile is useless for
;; attribution: the arms interleave, and the animation-frame waits between
;; them put 43% of the samples in `(idle)`.

(defn- profile-loop! [arm n]
  (dotimes [i n]
    (let [container (js/document.createElement "div")]
      (.appendChild js/document.body container)
      (let [holder (atom nil)]
        (react-dom/flushSync
          (fn []
            (reset! holder
                    (if (= :react (:kind arm))
                      (doto (rdc/createRoot container) (.render ((:el arm))))
                      (v/mount [(:view arm) (:props arm)] container
                               {:frame fid
                                :disambiguator (keyword "studio" (str (:id arm) "-p" i))})))))
        (react-dom/flushSync
          (fn [] (if (= :react (:kind arm))
                   (.unmount ^js @holder)
                   (.unmount (.-react-root ^root/Root @holder)))))
        (.remove container)))))

;; --- entry ------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! v/adapter)
  (register!)
  (let [boot (js/document.createElement "div")]
    (.appendChild js/document.body boot)
    (v/mount [wi/w2 {:free 0}] boot
             {:frame {:id fid :initial-events [[::seed]]}
              :disambiguator :studio/boot}))
  (let [params (js/URLSearchParams. (.-search js/location))
        target (.get params "profile")]
    (when target
      (let [arm (first (filterv #(= target (:id %)) (arms)))
            n   (js/parseInt (or (.get params "n") "150"))
            t0  (now)]
        (profile-loop! arm n)
        (set! (.-__STUDIO__ js/window)
              (clj->js {:profile-arm target :iterations n :total-ms (- (now) t0)}))
        (set! (.-__STUDIO_DONE__ js/window) true))))
  (when-not (.-__STUDIO_DONE__ js/window)
  (-> (run-mounts!)
      (.then (fn [{:keys [mounts pages]}]
               (.then (run-updates!)
                      (fn [updates]
                        (set! (.-__STUDIO__ js/window)
                              (clj->js {:mounts    mounts
                                        :updates   updates
                                        :parity    (parity pages)
                                        :manifests (manifests)
                                        :fixture   {:w1-rows w1-rows :w2-free w2-free
                                                    :w2-reads w2-reads :w3-fields w3-fields
                                                    :warmup warmup :samples samples}
                                        :precise-memory? (some? (.-memory js/performance))}))
                        (set! (.-__STUDIO_DONE__ js/window) true)))))
      (.catch (fn [e]
                (set! (.-__STUDIO_ERROR__ js/window) (str e))
                (set! (.-__STUDIO_DONE__ js/window) true))))))
