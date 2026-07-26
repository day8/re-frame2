;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd's ELISION ABLATION. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md §1a.
;;
;; The production-browser probe. One page, nine arms, driven from Playwright,
;; which reads `window.__STUDIO__` when `window.__STUDIO_DONE__` goes true.
;;
;; METHOD — the numbers are worthless without it.
;;
;;   - ONE boundary count, 300, for BOTH witnesses. The first pass compared
;;     300 sub-free boundaries against 100 reactive ones; that alone made the
;;     two speedup ratios incommensurable.
;;   - Arms are INTERLEAVED: the sample loop is `for sample: for arm:`, so a
;;     drift in machine load lands on every arm equally rather than on
;;     whichever arm happened to run second.
;;   - Every mount is timed inside `react-dom/flushSync`, so `react-ms`
;;     brackets the substrate's element construction AND React's render,
;;     commit and DOM mutation with nothing scheduled out of the window.
;;   - `layout-ms` is a FORCED synchronous reflow (`offsetHeight`) taken
;;     immediately after. `settle-ms` = the two: the WORK a mount costs.
;;     `frame-ms` rides along as evidence and is NOT the settlement figure,
;;     because it is quantised to the display's refresh interval.
;;   - Deterministic properties are asserted, not published: the element count
;;     and the canonical DOM of every arm of a witness must agree, and the
;;     kept clone must be running the very same compiled body object as the
;;     elided original.
;;   - `act` is deliberately NOT used: it throws in a production React build,
;;     which is the build this probe exists to measure.
;;
;; `?profile=<arm-id>&n=<iterations>` runs ONE arm in a tight synchronous
;; mount/unmount loop with no canon serialisation and no animation-frame wait,
;; for a CDP CPU profile. `window.__studioRun__(arm, n)` is the same loop
;; callable from the driver, which is how the CDP HEAP ALLOCATION profile gets
;; a window containing one arm and nothing else.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.probe
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as rdc]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.studio.ablation :as ab]
            [re-frame.freehand.studio.floor :as floor]
            [re-frame.freehand.studio.witnesses-compiled :as wc]
            [re-frame.freehand.studio.witnesses-interpreted :as wi]))

;; --- fixture parameters -----------------------------------------------------

(def ^:private n-leaves 300)
(def ^:private warmup 5)
(def ^:private samples 30)

;; --- the frame --------------------------------------------------------------

(def ^:private fid ::frame)

(defn- register! []
  (rf/reg-event ::seed (fn [_ _] {:db {:tick 0}}))
  (rf/reg-sub :studio/tick (fn [db _] (:tick db))))

;; --- readings ---------------------------------------------------------------

(defn- now [] (js/performance.now))

(def ^:private sink
  "Where a forced-reflow reading goes. Without a live consumer Closure is free
  to drop the property read, and the layout it was taken to force never
  happens — which is exactly what a first run of the earlier probe reported:
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
  order, and the front ends write props in different orders. Sorting the names
  compares the DOM instead of the serialiser."
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
;; and goes straight to `createRoot`, because a floor that mounted through the
;; substrate would be measuring the substrate.
;;
;; Read the five FREE arms as a ladder. Each step changes exactly one thing:
;;
;;   free-floor  no substrate at all
;;   free-cc     compiled parent  + compiled leaf, ViewCell ELIDED
;;   free-ic     INTERPRETED parent + compiled leaf, ViewCell ELIDED
;;   free-ik     interpreted parent + compiled leaf, ViewCell KEPT     <- ablation
;;   free-i      interpreted parent + INTERPRETED leaf (ViewCell kept, as it
;;                                    must be — an interpreted body can never
;;                                    be proved sub-free)

(defn- arms []
  [{:id "free-floor" :witness "free" :kind :react    :el #(floor/free n-leaves)}
   {:id "free-cc"    :witness "free" :kind :freehand :view wc/list-free   :props {:n n-leaves}}
   {:id "free-ic"    :witness "free" :kind :freehand :view ab/list-free-c :props {:n n-leaves}}
   {:id "free-ik"    :witness "free" :kind :freehand :view ab/list-free-k :props {:n n-leaves}}
   {:id "free-i"     :witness "free" :kind :freehand :view wi/list-free   :props {:n n-leaves}}
   {:id "read-floor" :witness "read" :kind :react    :el #(floor/reads n-leaves)}
   {:id "read-cc"    :witness "read" :kind :freehand :view wc/list-read   :props {:n n-leaves}}
   {:id "read-ic"    :witness "read" :kind :freehand :view ab/list-read-c :props {:n n-leaves}}
   {:id "read-i"     :witness "read" :kind :freehand :view wi/list-read   :props {:n n-leaves}}])

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

;; --- the interleaved sample loop --------------------------------------------

(defn- sample-loop!
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

;; --- the deterministic properties -------------------------------------------

(defn- parity
  "Every arm of a witness must have produced the SAME page."
  [pages]
  (reduce
    (fn [acc w]
      (let [ids (mapv :id (filterv #(= w (:witness %)) (arms)))
            got (mapv #(get pages %) ids)]
        (assoc acc w
               (let [htmls (mapv :html got)
                     base  (first htmls)]
                 (cond-> {:arms ids :elements (mapv :elements got) :agree? (apply = htmls)}
                   (not (apply = htmls))
                   (assoc :diffs
                          (vec (keep-indexed
                                 (fn [i h]
                                   (when (not= h base)
                                     (let [k (count (take-while true? (map = base h)))]
                                       {:arm (nth ids i) :at k
                                        :base (subs base (max 0 (- k 40))
                                                    (min (count base) (+ k 90)))
                                        :got  (subs h (max 0 (- k 40))
                                                    (min (count h) (+ k 90)))})))
                                 htmls))))))))
    {}
    (distinct (mapv :witness (arms)))))

(defn- verdicts
  "The manifest verdicts the ablation turns on, read from the runtime rather
  than asserted, plus the proof that the kept clone runs the SAME compiled
  body object as the elided original."
  []
  {:leaf-free      (:view-cell (v/manifest wc/leaf-free))
   :leaf-free-kept (:view-cell (v/manifest ab/leaf-free-kept))
   :leaf-read      (:view-cell (v/manifest wc/leaf-read))
   :list-free      (:view-cell (v/manifest wc/list-free))
   :list-read      (:view-cell (v/manifest wc/list-read))
   :same-body?     (ab/same-body?)})

;; --- the focused loop, for CPU and ALLOCATION profiles ----------------------

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
    (v/mount [wi/list-free {:n 0}] boot
             {:frame {:id fid :initial-events [[::seed]]}
              :disambiguator :studio/boot}))
  ;; The driver's allocation window: start the CDP heap sampler, call this,
  ;; stop it. Everything between is one arm.
  (set! (.-__studioRun__ js/window)
        (fn [id n] (profile-loop! (first (filterv #(= id (:id %)) (arms))) n) true))
  (set! (.-__STUDIO_VERDICTS__ js/window) (clj->js (verdicts)))
  (let [params (js/URLSearchParams. (.-search js/location))
        target (.get params "profile")]
    (cond
      target
      (let [n  (js/parseInt (or (.get params "n") "150"))
            t0 (now)]
        (profile-loop! (first (filterv #(= target (:id %)) (arms))) n)
        (set! (.-__STUDIO__ js/window)
              (clj->js {:profile-arm target :iterations n :total-ms (- (now) t0)}))
        (set! (.-__STUDIO_DONE__ js/window) true))

      ;; Allocation mode: the driver drives `__studioRun__` itself, so the page
      ;; only has to say it is ready.
      (.get params "alloc")
      (do (set! (.-__STUDIO__ js/window) (clj->js {:alloc-ready true}))
          (set! (.-__STUDIO_DONE__ js/window) true))

      :else
      (-> (run-mounts!)
          (.then (fn [{:keys [mounts pages]}]
                   (set! (.-__STUDIO__ js/window)
                         (clj->js {:mounts   mounts
                                   :parity   (parity pages)
                                   :verdicts (verdicts)
                                   :fixture  {:n n-leaves :warmup warmup :samples samples}
                                   :precise-memory? (some? (.-memory js/performance))}))
                   (set! (.-__STUDIO_DONE__ js/window) true)))
          (.catch (fn [e]
                    (set! (.-__STUDIO_ERROR__ js/window) (str e))
                    (set! (.-__STUDIO_DONE__ js/window) true)))))))
