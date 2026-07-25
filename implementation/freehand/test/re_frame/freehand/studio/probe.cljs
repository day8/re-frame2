;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md.
;;
;; The production-browser probe. One page, six arms (three witnesses x
;; interpreted/compiled), driven from Playwright, which reads
;; `window.__STUDIO__` when `window.__STUDIO_DONE__` goes true.
;;
;; Method, stated here because the numbers are worthless without it:
;;
;;   - Arms are INTERLEAVED. The sample loop is `for sample: for arm:`, so a
;;     drift in machine load lands on every arm equally instead of on
;;     whichever arm ran second.
;;   - Every mount is timed inside `react-dom/flushSync`, so `react-ms`
;;     brackets the substrate's element construction AND React's render and
;;     commit, with nothing scheduled out of the window. The DOM element
;;     count is asserted after each mount: a mount that had not settled
;;     inside the timed region fails the count rather than reporting a fast
;;     time.
;;   - `settle-ms` runs to the next animation frame, so it carries style,
;;     layout and paint of the frame the mount landed in.
;;   - `act` is deliberately NOT used: it throws in a production React build,
;;     which is the build this probe exists to measure.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.probe
  (:require ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.root :as root]
            [re-frame.freehand.studio.witnesses-compiled :as wc]
            [re-frame.freehand.studio.witnesses-interpreted :as wi]))

;; --- fixture parameters -----------------------------------------------------

(def ^:private w1-rows 300)      ; 300 boundaries x 4 elements + skeleton
(def ^:private w2-free 300)      ; sub-free leaves — the elision arm
(def ^:private w2-reads 60)      ; reactive leaves — the control
(def ^:private w3-fields 12)     ; an ordinary form

(def ^:private warmup 4)
(def ^:private samples 24)

;; --- the frame --------------------------------------------------------------

(def ^:private fid ::frame)

(defn- register! []
  (rf/reg-event ::seed  (fn [_ _] {:db {:tick 0 :fields {}}}))
  (rf/reg-event ::tick  (fn [{:keys [db]} _] {:db (update db :tick inc)}))
  (rf/reg-event :studio/type
                (fn [{:keys [db]} [_ idx value]]
                  {:db (assoc-in db [:fields idx] value)}))
  (rf/reg-event :studio/submit (fn [{:keys [db]} _] {:db db}))
  (rf/reg-sub :studio/tick  (fn [db _] (:tick db)))
  (rf/reg-sub :studio/field (fn [db [_ idx]] (or (get-in db [:fields idx]) "")))
  (rf/reg-sub :studio/error (fn [db [_ idx]]
                              (let [s (get-in db [:fields idx])]
                                (if (and s (< (count s) 3)) "too short" ""))))
  (rf/reg-sub :studio/submit-blocked? (fn [db _] (empty? (:fields db)))))

;; --- readings ---------------------------------------------------------------

(defn- now [] (js/performance.now))

(defn- heap []
  (if-let [m (.-memory js/performance)] (.-usedJSHeapSize m) 0))

(defn- rAF []
  (js/Promise. (fn [res] (js/requestAnimationFrame (fn [] (res (now))))))) ; NOSONAR

(defn- elements [container]
  (.-length (.querySelectorAll container "*")))

;; --- the arms ---------------------------------------------------------------

(defn- arms []
  [{:id :w1/interpreted :witness :w1 :mode :interpreted
    :view wi/w1 :props {:rows w1-rows}}
   {:id :w1/compiled    :witness :w1 :mode :compiled
    :view wc/w1 :props {:rows w1-rows}}
   {:id :w2/interpreted :witness :w2 :mode :interpreted
    :view wi/w2 :props {:free w2-free :reads w2-reads}}
   {:id :w2/compiled    :witness :w2 :mode :compiled
    :view wc/w2 :props {:free w2-free :reads w2-reads}}
   {:id :w3/interpreted :witness :w3 :mode :interpreted
    :view wi/w3 :props {:fields w3-fields}}
   {:id :w3/compiled    :witness :w3 :mode :compiled
    :view wc/w3 :props {:fields w3-fields}}])

;; --- one mount sample -------------------------------------------------------

(defn- mount-sample!
  "One mount of `arm`, timed. Answers a promise of the sample map with the
  live mount still attached, for the caller to release."
  [{:keys [view props id]} n]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (let [holder (atom nil)
          h0     (heap)
          t0     (now)]
      (react-dom/flushSync
        (fn []
          (reset! holder (v/mount [view props] container
                                  {:frame fid
                                   :disambiguator (keyword "studio" (str (name id) "-" n))}))))
      (let [t1 (now)
            h1 (heap)]
        (.then (rAF)
               (fn [t2]
                 {:container  container
                  :mounted    @holder
                  :react-ms   (- t1 t0)
                  :settle-ms  (- t2 t0)
                  :paint-ms   (- t2 t1)
                  :heap-bytes (- h1 h0)
                  :elements   (elements container)}))))))

(defn- release! [{:keys [container mounted]}]
  (when mounted
    (react-dom/flushSync
      (fn [] (.unmount (.-react-root ^root/Root mounted)))))
  (.remove container)
  nil)

;; --- one update sample ------------------------------------------------------

(defn- update-sample!
  "Mount `arm`, then time ONE state change through to a committed DOM. The
  mount is outside the reading; only the update is inside it."
  [{:keys [view props id] :as arm} n event]
  (-> (mount-sample! arm (str "upd-" n))
      (.then (fn [live]
               (let [t0 (now)]
                 (react-dom/flushSync (fn [] (rf/dispatch-sync event {:frame fid})))
                 (let [t1 (now)]
                   (.then (rAF)
                          (fn [t2]
                            (release! live)
                            {:react-ms  (- t1 t0)
                             :settle-ms (- t2 t0)
                             :paint-ms  (- t2 t1)})))))))))

;; --- the interleaved sample loop -------------------------------------------

(defn- run-mounts!
  "Warm up, then take `samples` interleaved mount readings for every arm."
  []
  (let [acc (atom {})]
    (-> (reduce
          (fn [p n]
            (reduce
              (fn [p2 arm]
                (.then p2 (fn [_]
                            (.then (mount-sample! arm n)
                                   (fn [live]
                                     (when (>= n warmup)
                                       (swap! acc update (:id arm) (fnil conj [])
                                              (dissoc live :container :mounted)))
                                     (release! live))))))
              p
              (arms)))
          (js/Promise.resolve nil)
          (range (+ warmup samples)))
        (.then (fn [_] @acc)))))

(defn- run-updates!
  "The update reading, for the two witnesses that have reactive state."
  []
  (let [acc  (atom {})
        pick (fn [w] (filterv #(= w (:witness %)) (arms)))
        plan (concat (map (fn [a] [a [::tick]]) (pick :w2))
                     (map (fn [a] [a [:studio/type 0 "abcd"]]) (pick :w3)))]
    (-> (reduce
          (fn [p n]
            (reduce
              (fn [p2 [arm event]]
                (.then p2 (fn [_]
                            (.then (update-sample! arm n event)
                                   (fn [s]
                                     (when (>= n warmup)
                                       (swap! acc update (:id arm) (fnil conj []) s)))))))
              p
              plan))
          (js/Promise.resolve nil)
          (range (+ warmup samples)))
        (.then (fn [_] @acc)))))

;; --- the static facts -------------------------------------------------------

(defn- manifests
  "The compiled arm's own analysis verdicts — the elision claim, read off
  the manifest rather than asserted here."
  []
  {:w1      (:view-cell (v/manifest wc/w1))
   :w1-row  (:view-cell (v/manifest wc/w1-row))
   :w2      (:view-cell (v/manifest wc/w2))
   :w2-free (:view-cell (v/manifest wc/w2-free))
   :w2-read (:view-cell (v/manifest wc/w2-read))
   :w3      (:view-cell (v/manifest wc/w3))
   :w3-field (:view-cell (v/manifest wc/w3-field))})

;; --- entry ------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! v/adapter)
  (register!)
  ;; One frame, created by mounting a trivial root, then reused by every arm
  ;; so no arm pays another arm's frame-creation cost.
  (let [boot (js/document.createElement "div")]
    (.appendChild js/document.body boot)
    (v/mount [wi/w2 {:free 0 :reads 0}] boot
             {:frame {:id fid :initial-events [[::seed]]}
              :disambiguator :studio/boot}))
  (-> (run-mounts!)
      (.then (fn [mounts]
               (.then (run-updates!)
                      (fn [updates]
                        (set! (.-__STUDIO__ js/window)
                              (clj->js {:mounts    mounts
                                        :updates   updates
                                        :manifests (manifests)
                                        :fixture   {:w1-rows w1-rows
                                                    :w2-free w2-free
                                                    :w2-reads w2-reads
                                                    :w3-fields w3-fields
                                                    :warmup warmup
                                                    :samples samples}
                                        :precise-memory? (some? (.-memory js/performance))}))
                        (set! (.-__STUDIO_DONE__ js/window) true)))))
      (.catch (fn [e]
                (set! (.-__STUDIO_ERROR__ js/window) (str e))
                (set! (.-__STUDIO_DONE__ js/window) true)))))
