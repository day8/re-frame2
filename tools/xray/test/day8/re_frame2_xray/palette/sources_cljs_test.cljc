(ns day8.re-frame2-xray.palette.sources-cljs-test
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.palette.sources :as sources]))

;; ---- fixture inputs -----------------------------------------------------

(def sample-panels
  [{:id :event    :label "Event"}
   {:id :trace    :label "Trace"}
   {:id :machines :label "Machines"}])

(def sample-trace-buffer
  ;; oldest → newest
  [{:id 100 :op :rf.event/handled :event-id [:user/login]}
   {:id 101 :op :rf.event/handled :event-id [:user/logout]}
   {:id 102 :op :rf.event/dispatched :event-id [:cart/add 42]}
   {:id 103 :op :trace/note}            ;; filtered — not an event op
   {:id 104 :op :rf.event/handled :event-id [:cart/remove]}])

(def sample-frames [:rf/default :rf/xray :app/main])

(def sample-handlers
  [{:id :user/login    :kind :event :file "user.cljs" :line 12}
   {:id :user/profile  :kind :sub   :doc "Profile sub" :file "u.cljs" :line 30}
   {:id :http/fetch    :kind :fx    :file "http.cljs" :line 8}])

;; ---- per-source shape ---------------------------------------------------

(deftest panel-items-shape
  (let [items (sources/panel-items sample-panels)]
    (is (= [[:palette/select-panel :event]
            [:palette/select-panel :trace]
            [:palette/select-panel :machines]]
           (mapv :action items)))
    (is (every? #(false? (:popout? %)) items) "panels are not popoutable")))

(deftest recent-event-items-skips-non-event-ops
  (let [items (sources/recent-event-items sample-trace-buffer)]
    (is (= [[100 3] [101 2] [102 1] [104 0]]
           (mapv (juxt :id :recency-rank) items))
        "the :trace/note row is filtered out; the latest event is rank 0")
    (is (every? #(true? (:popout? %)) items)
        "recent events pop out into event-detail on Ctrl+Enter")))

(deftest recent-event-action-carries-dispatch-and-frame-rf2-gwye-8
  ;; Two runs of one event vector, or one dispatch id in two frames, are
  ;; distinct selections.
  (let [row   (fn [id dispatch-id frame]
                {:id id :op-type :rf.event :operation :rf.event/dispatched
                 :tags {:rf.trace/dispatch-id dispatch-id
                        :frame                frame
                        :rf.event/v           [:counter/inc]}})
        items (sources/recent-event-items [(row 10 1 :rf/default)
                                           (row 11 2 :rf/default)
                                           (row 12 2 :app/other)])]
    (is (= [[:palette/select-event 1 :rf/default]
            [:palette/select-event 2 :rf/default]
            [:palette/select-event 2 :app/other]]
           (mapv :action items)))
    (is (every? #(= "[:counter/inc]" (:label %)) items)
        "the label still shows the event vector")))

(deftest recent-event-cap
  (let [big-buffer (vec (for [i (range 50)]
                          {:id i :op :rf.event/handled :event-id [:noise i]}))]
    (is (= (map vector (range 40 50) (range 9 -1 -1))
           (map (juxt :id :recency-rank) (sources/recent-event-items big-buffer 10)))
        "the cap keeps the latest rows, ranked within what it kept")))

(deftest build-index-frame-source-honours-internal-frames
  ;; The palette hides the same tool frames the ribbon picker does (spec/018 §8 I1).
  (let [index (sources/build-index
                {:frame-ids       [:rf/default :rf/xray :app/main :rf/re-frame2-pair]
                 :internal-frames #{:rf/xray :rf/re-frame2-pair}})]
    (is (= #{:rf/default :app/main}
           (set (keep #(when (= :frame (:source %)) (:id %)) index))))))

(deftest handler-items-include-meta
  (is (= ["user.cljs:12" true]
         ((juxt :hint :popout?) (first (sources/handler-items sample-handlers))))))

(deftest command-items-include-the-core-verbs
  ;; The verbs tools/xray/spec/API.md catalogues.
  (let [ids (set (map :id (sources/command-items)))]
    (is (= [] (remove ids [:clear-trace-buffer :reset-suppressed-counters :open-popout
                           :close-palette :toggle-theme :cycle-reduced-motion
                           :snapshot-app-db :jump-to-settings :toggle-mode])))))

(deftest static-tab-items-shape
  (is (= [[:static :machines] [:palette/select-static-tab :machines] #{:static}]
         ((juxt :id :action :modes)
          (first (sources/static-tab-items [{:id :machines :label "Machines"}]))))
      "id namespaced under :static so it doesn't collide with Dynamic panel ids"))

;; ---- build-index --------------------------------------------------------

(deftest build-index-includes-every-source
  (let [index (sources/build-index
                {:panels       sample-panels
                 :trace-buffer sample-trace-buffer
                 :frame-ids    sample-frames
                 :handlers     sample-handlers})]
    (is (= #{:command :panel :setting :recent-event :frame :handler}
           (set (map :source index))))))

(deftest build-index-dedups-on-source-id
  (let [index (sources/build-index {:panels [{:id :trace :label "Trace"}
                                             {:id :trace :label "Trace (dup)"}]})]
    (is (= ["Open Trace panel"]
           (keep #(when (= [:panel :trace] ((juxt :source :id) %)) (:label %)) index))
        "first :panel :trace wins, duplicates drop")))

;; ---- ranking ------------------------------------------------------------

(deftest rank-empty-query-keeps-everything-orders-by-boost
  (let [index   (sources/build-index
                  {:panels sample-panels
                   :trace-buffer sample-trace-buffer})
        results (sources/rank index "" 100)]
    (is (= (count index) (count results))
        "empty query keeps every item")
    (is (>= (-> results first :score) (-> results last :score))
        "results are sorted highest-score first")))

(deftest rank-non-matching-query-returns-empty
  (is (empty? (sources/rank (sources/build-index {:panels sample-panels}) "zzzzz"))))

(deftest rank-respects-limit
  (let [index (sources/build-index
                {:panels sample-panels :trace-buffer sample-trace-buffer})]
    (is (= 3 (count (sources/rank index "" 3))))))

;; ---- build-index mode-awareness ----------------------------------------

(deftest build-index-runtime-mode-filters-static-tabs
  (let [ids (set (map :id (sources/build-index
                            {:static-tabs [{:id :machines :label "Machines"}]
                             :mode        :dynamic})))]
    (is (not (contains? ids [:static :machines])) "Dynamic mode hides Static-only items")
    (is (contains? ids :toggle-mode) "mode toggle surfaces in BOTH modes")))

(deftest build-index-static-mode-hides-runtime-only-items
  (let [index (sources/build-index
                {:panels       sample-panels
                 :static-tabs  [{:id :machines :label "Machines"}]
                 :trace-buffer sample-trace-buffer
                 :frame-ids    sample-frames
                 :mode         :static})
        ids   (set (map :id index))]
    (is (= #{:command :panel :setting} (set (map :source index)))
        "recent events and frame shortcuts are Dynamic-only")
    (is (not (contains? ids :clear-trace-buffer)) "trace buffer clear is Dynamic-only")
    (is (contains? ids :toggle-mode) "mode toggle surfaces in BOTH modes")
    (is (contains? ids [:static :machines]) "Static tab jump surfaces in Static mode")))

;; ---- recents boost -----------------------------------------------------

(deftest build-index-recents-boost-decays-with-position
  (let [boost   (fn [recents id]
                  (:boost (first (filter #(= id (:id %))
                                         (sources/build-index {:recents recents})))))
        recents [:toggle-theme :toggle-mode :jump-to-settings]]
    (is (= (+ (boost [] :toggle-theme) sources/recents-boost-max)
           (boost recents :toggle-theme))
        "position 0 (most recent) gets the full recents-boost-max bump")
    (is (> (boost recents :toggle-theme)
           (boost recents :toggle-mode)
           (boost recents :jump-to-settings)))))

(deftest rank-empty-query-surfaces-recents-first
  (let [index (sources/build-index {:panels sample-panels :recents [:toggle-theme]})]
    (is (= [:command :toggle-theme]
           ((juxt :source :id) (first (sources/rank index "" 100)))))))
