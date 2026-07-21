(ns re-frame.ui.mounted-g3-cardinality-dom-cljs-test
  "rf2-vxgfnd.109 — mounted G-3 cardinality proof on the native
  `:rf.adapter/ui` path.

  Four explicit compiled children contain 1/4/8/16 lexical `ui/sub` sites.
  The fixture proves one ViewCell and one useSyncExternalStore subscriber per
  child, independent ownership per lexical site, and one body invocation per
  changed child. Twenty-nine queued events complete their update and commit phases before
  one post-quiescence read/render batch; event epochs are evidence, never a
  render-count model.

  Browser-only body — the `-dom-cljs-test` suffix enrols this namespace in
  `:browser-test`; the node runner loads it and skips the DOM body."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(def ^:private site-ids
  (mapv #(keyword (namespace ::s00) (str "s" (.padStart (str %) 2 "0")))
        (range 29)))

(defn- site-values
  [db]
  (mapv db site-ids))

(defn root-profiler [^js props]
  (React/createElement
   (.-Profiler React)
   #js {:id "mounted-g3-root"
        :onRender (fn [& _]
                    (swap! (.-evidence props) update :root-commits inc))}
   (.-children props)))

(defn- note-body!
  [evidence n]
  (swap! evidence update n inc))

(defview g3-one [{:keys [evidence]}]
  (let [_   (note-body! evidence 1)
        v00 (ui/sub [::s00])]
    [:output {:data-g3-sites "1"} (str v00)]))

(defview g3-four [{:keys [evidence]}]
  (let [_   (note-body! evidence 4)
        v01 (ui/sub [::s01])
        v02 (ui/sub [::s02])
        v03 (ui/sub [::s03])
        v04 (ui/sub [::s04])]
    [:output {:data-g3-sites "4"}
     (str/join "," [v01 v02 v03 v04])]))

(defview g3-eight [{:keys [evidence]}]
  (let [_   (note-body! evidence 8)
        v05 (ui/sub [::s05])
        v06 (ui/sub [::s06])
        v07 (ui/sub [::s07])
        v08 (ui/sub [::s08])
        v09 (ui/sub [::s09])
        v10 (ui/sub [::s10])
        v11 (ui/sub [::s11])
        v12 (ui/sub [::s12])]
    [:output {:data-g3-sites "8"}
     (str/join "," [v05 v06 v07 v08 v09 v10 v11 v12])]))

(defview g3-sixteen [{:keys [evidence]}]
  (let [_   (note-body! evidence 16)
        v13 (ui/sub [::s13])
        v14 (ui/sub [::s14])
        v15 (ui/sub [::s15])
        v16 (ui/sub [::s16])
        v17 (ui/sub [::s17])
        v18 (ui/sub [::s18])
        v19 (ui/sub [::s19])
        v20 (ui/sub [::s20])
        v21 (ui/sub [::s21])
        v22 (ui/sub [::s22])
        v23 (ui/sub [::s23])
        v24 (ui/sub [::s24])
        v25 (ui/sub [::s25])
        v26 (ui/sub [::s26])
        v27 (ui/sub [::s27])
        v28 (ui/sub [::s28])]
    [:output {:data-g3-sites "16"}
     (str/join "," [v13 v14 v15 v16 v17 v18 v19 v20
                    v21 v22 v23 v24 v25 v26 v27 v28])]))

(defn- cell-by-site-count
  [cells]
  (into {} (map (juxt #(count (reactive/committed-sites %)) identity)) cells))

(defn- records
  [cells-by-count]
  (mapcat (comp vals reactive/committed-sites val) cells-by-count))

(defn- structural-target-key
  [{:keys [kind frame-id query override-id]}]
  (case kind
    :subscription [:sub frame-id query]
    :story-override [:override override-id]))

(defn- ownership-snapshot
  [cells-by-count]
  (into {}
        (map (fn [[n cell]]
               [n (into {}
                        (map (fn [[sid {:keys [query handle]}]]
                               [sid {:query query :handle handle}]))
                        (reactive/committed-sites cell))]))
        cells-by-count))

(defn- body-evidence
  []
  {1 0, 4 0, 8 0, 16 0, :root-commits 0})

(defn- rendered-values
  [root n]
  (.-textContent (.querySelector root (str "[data-g3-sites='" n "']"))))

(defn- cache-entry
  [frame-id query]
  (get @(:sub-cache (frame/frame frame-id)) query))

(defn- assert-ownership!
  [frame-id cells-by-count]
  (let [rs          (vec (records cells-by-count))
        queries     (mapv :query rs)
        target-keys (mapv (comp structural-target-key :target) rs)
        handles      (mapv :handle rs)]
    (is (= [1 4 8 16] (sort (keys cells-by-count))))
    (is (= 29 (count rs)))
    (is (= 29 (count (set queries))) "all lexical sites read distinct queries")
    (is (= 29 (count (set target-keys))) "all lexical sites resolve distinct targets")
    (is (every? obs/owned? handles) "every committed site owns a live handle")
    (is (= 29 (count (set handles))) "each lexical site has an independent handle")
    (doseq [{:keys [query]} rs]
      (let [entry (cache-entry frame-id query)]
        (is (= 1 (:ref-count entry)))
        (is (= 1 (obs/active-owner-count (:reaction entry))))))))

(defn- assert-ownership-unchanged!
  [before cells-by-count]
  (doseq [[n cell] cells-by-count
          [sid after] (reactive/committed-sites cell)]
    (let [{before-query :query before-handle :handle} (get-in before [n sid])]
      (is (= before-query (:query after)) "query identity key stays stable")
      (is (identical? before-query (:query after)) "exact query object is retained")
      (is (identical? before-handle (:handle after)) "handle does not churn"))))

(defn- reject-unexpectedly!
  [done f label e]
  (rf/destroy-frame! f)
  (is false (str label ": " e))
  (done))

(deftest mounted-g3-proves-one-listener-per-viewcell-and-one-post-drain-batch
  (when (browser?)
    (doseq [sid site-ids]
      (rf/reg-sub sid (fn [db _] (get db sid))))
    (rf/reg-event
     ::write-next
     (fn [{:keys [db]} [_ i]]
       (cond-> {:db (-> db
                        (assoc (nth site-ids i) 1)
                        (update :writes inc))}
         (< i (dec (count site-ids)))
         (assoc :fx [[:dispatch [::write-next (inc i)]]]))))
    (let [seed       (into {:writes 0} (map (fn [sid] [sid 0])) site-ids)
          f          (rf/make-frame {:initial-events [[:rf/set-db seed]]})
          frame-id   (frame/frame-target->id f)
          baseline   (reactive/current-live-cells)
          evidence   (atom (body-evidence))]
      (async done
        (->
         (uit/with-root
           [root [root-profiler {:evidence evidence}
                  [ui/frame-provider {:frame f}
                   [:section
                    [g3-one {:evidence evidence}]
                    [g3-four {:evidence evidence}]
                    [g3-eight {:evidence evidence}]
                    [g3-sixteen {:evidence evidence}]]]]]
           (let [mounted          (set (remove baseline (reactive/current-live-cells)))
                 cells-by-count   (cell-by-site-count mounted)
                 revisions-before (into {} (map (fn [[n cell]]
                                                  [n (reactive/revision cell)]))
                                        cells-by-count)
                 ownership-before (ownership-snapshot cells-by-count)]
             (testing "four compiled children each have one host subscription"
               (is (= 4 (count mounted)))
               (doseq [[_ cell] cells-by-count]
                 (is (= 1 (reactive/listener-count cell))))
               (assert-ownership! frame-id cells-by-count))
             (reset! evidence (body-evidence))
             (let [p (uit/flush! #(rf/dispatch-sync [::write-next 0] {:frame f}))]
               (testing "the complete queued update and commit phases precede any read/render work"
                 (is (= 29 (:writes (rf/app-db-value f))))
                 (is (= (vec (repeat 29 1)) (site-values (rf/app-db-value f))))
                 (is (= "0" (rendered-values root 1)))
                 (is (= "0,0,0,0" (rendered-values root 4)))
                 (is (= (str/join "," (repeat 8 "0")) (rendered-values root 8)))
                 (is (= (str/join "," (repeat 16 "0")) (rendered-values root 16)))
                 (is (= (body-evidence) @evidence))
                 (is (= 4 (reactive/pending-cell-count)))
                 (doseq [[n cell] cells-by-count]
                   (is (= (get revisions-before n) (reactive/revision cell)))
                   (is (= 1 (reactive/listener-count cell)))))
               (->
                p
                (.then
                 (fn []
                   (testing "one post-quiescence batch re-renders each changed child once"
                     (is (= "1" (rendered-values root 1)))
                     (is (= (str/join "," (repeat 4 "1")) (rendered-values root 4)))
                     (is (= (str/join "," (repeat 8 "1")) (rendered-values root 8)))
                     (is (= (str/join "," (repeat 16 "1")) (rendered-values root 16)))
                     (is (= {1 1, 4 1, 8 1, 16 1, :root-commits 1} @evidence))
                     (is (zero? (reactive/pending-cell-count)))
                     (doseq [[n cell] cells-by-count]
                       (is (= (inc (get revisions-before n))
                              (reactive/revision cell)))
                       (is (= 1 (reactive/listener-count cell))))
                     (assert-ownership-unchanged! ownership-before cells-by-count)
                     (assert-ownership! frame-id cells-by-count))))))))
         (.then
          (fn []
            (testing "unmount returns every mounted ViewCell and owner to baseline"
              (is (= baseline (reactive/current-live-cells)))
              (is (empty? @(:sub-cache (frame/frame frame-id)))))
            (rf/destroy-frame! f)
            (done))
          (fn [e]
            (reject-unexpectedly! done f "mounted G-3 rejected" e))))))))
