;;;; tests/runtime/snapshot_test.clj
;;;;
;;;; Babashka-runnable verification of the `snapshot-state` mega-op in
;;;; `preload/re_frame_pair2/runtime.cljs` — the rf2-x70e coarse-grained
;;;; per-frame state reader the pair2 MCP server exposes as the
;;;; `snapshot` tool.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame_pair2/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   depends on the live re-frame2 frame registry, sub-cache, trace
;;;;   buffer, and epoch history — none of which run under bb. The real
;;;;   shadow-cljs test build (planned per `docs/TESTING.md` §1) will
;;;;   exercise the `.cljs` source in place under Node; until then this
;;;;   file mirrors the slice routing and the per-frame composer and
;;;;   asserts behaviour against stubbed framework surfaces.
;;;;
;;;;   KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs §Coarse-grained
;;;;   snapshot.
;;;;
;;;; Run:    bb tests/runtime/snapshot_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns snapshot-test
  (:require [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Stubbed framework surfaces.
;; ---------------------------------------------------------------------------
;;
;; Two registered frames with predictable per-slice state. The composer
;; under test routes by `:include` keys and assembles one map per
;; frame-id.

(def fixture-frames
  {:rf/default {:app-db    {:cart {:items 3 :total 4200}
                            :rf/machines {:auth {:state :authed}}}
                :sub-cache {[:cart/total] {:value 4200 :ref-count 2}}
                :epochs    [{:epoch-id "e1" :event-id :app/init}
                            {:epoch-id "e2" :event-id :cart/add}]
                :traces    [{:id 1 :operation :event/dispatched :tags {:frame :rf/default}}
                            {:id 2 :operation :sub/run        :tags {:frame :rf/default}}]}
   :stories     {:app-db    {:scenarios {:checkout :ready}
                             :rf/machines {}}
                 :sub-cache {[:stories/active] {:value :checkout :ref-count 1}}
                 :epochs    [{:epoch-id "s1" :event-id :stories/load}]
                 :traces    [{:id 3 :operation :event/dispatched :tags {:frame :stories}}]}})

(defn stub-frame-ids [] (keys fixture-frames))
(defn stub-get-frame-db [fid] (get-in fixture-frames [fid :app-db]))
(defn stub-sub-cache    [fid] (get-in fixture-frames [fid :sub-cache]))
(defn stub-epoch-history [fid] (get-in fixture-frames [fid :epochs]))
(defn stub-machines [] [:auth :session])  ;; global registrar surface
(defn stub-trace-buffer [opts]
  (let [fid (:frame opts)]
    (filter #(= fid (get-in % [:tags :frame]))
            (mapcat (fn [[_ slices]] (:traces slices)) fixture-frames))))

;; Mirror of preload/re_frame_pair2/runtime.cljs §Coarse-grained snapshot.
;; KEEP IN SYNC.

(def ^:private all-snapshot-slices
  [:app-db :sub-cache :machines :epochs :traces])

(defn- snapshot-frame-slice [frame-id slice]
  (case slice
    :app-db     (stub-get-frame-db frame-id)
    :sub-cache  (stub-sub-cache frame-id)
    :machines   {:ids   (vec (stub-machines))
                 :state (or (get (stub-get-frame-db frame-id) :rf/machines) {})}
    :epochs     (vec (stub-epoch-history frame-id))
    :traces     (vec (stub-trace-buffer {:frame frame-id}))
    nil))

(defn- snapshot-frame [frame-id slices]
  (reduce (fn [m slice]
            (assoc m slice (snapshot-frame-slice frame-id slice)))
          {}
          slices))

(defn snapshot-state
  ([] (snapshot-state {}))
  ([{:keys [frames include]
     :or   {frames :all include all-snapshot-slices}}]
   (let [registered (vec (stub-frame-ids))
         fids       (cond
                      (= :all frames)    registered
                      (sequential? frames) (vec frames)
                      :else               registered)
         slices     (vec include)]
     (reduce (fn [m fid] (assoc m fid (snapshot-frame fid slices))) {} fids))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest defaults-include-all-frames-and-all-slices
  (let [snap (snapshot-state)]
    (testing "every registered frame is present"
      (is (= #{:rf/default :stories} (set (keys snap)))))
    (testing "every slice is present per frame"
      (doseq [fid [:rf/default :stories]]
        (is (= #{:app-db :sub-cache :machines :epochs :traces}
               (set (keys (get snap fid))))
            (str fid " missing slice keys"))))))

(deftest app-db-slice-delegates-to-get-frame-db
  (let [snap (snapshot-state {:include [:app-db]})]
    (is (= {:cart {:items 3 :total 4200}
            :rf/machines {:auth {:state :authed}}}
           (get-in snap [:rf/default :app-db])))
    (is (= {:scenarios {:checkout :ready} :rf/machines {}}
           (get-in snap [:stories :app-db])))))

(deftest sub-cache-slice-delegates-to-rf-sub-cache
  (let [snap (snapshot-state {:include [:sub-cache]})]
    (is (= {[:cart/total] {:value 4200 :ref-count 2}}
           (get-in snap [:rf/default :sub-cache])))))

(deftest machines-slice-combines-registrar-and-per-frame-state
  (let [snap (snapshot-state {:include [:machines]})]
    (is (= [:auth :session]
           (get-in snap [:rf/default :machines :ids]))
        "global registrar ids appear under :ids")
    (is (= {:auth {:state :authed}}
           (get-in snap [:rf/default :machines :state]))
        "per-frame state appears under :state")
    (is (= {} (get-in snap [:stories :machines :state]))
        "frames with no machines snapshot get an empty state map")))

(deftest epochs-slice-returns-history-vector
  (let [snap (snapshot-state {:include [:epochs]})]
    (is (vector? (get-in snap [:rf/default :epochs])))
    (is (= 2 (count (get-in snap [:rf/default :epochs]))))
    (is (= "e1" (-> snap :rf/default :epochs first :epoch-id)))))

(deftest traces-slice-is-frame-scoped
  (let [snap (snapshot-state {:include [:traces]})]
    (is (every? #(= :rf/default (get-in % [:tags :frame]))
                (get-in snap [:rf/default :traces]))
        "trace buffer filtered by frame, no leaks across frames")
    (is (every? #(= :stories (get-in % [:tags :frame]))
                (get-in snap [:stories :traces])))))

(deftest include-subset-honours-pick-list
  (let [snap (snapshot-state {:include [:app-db :epochs]})]
    (is (= #{:app-db :epochs}
           (set (keys (get snap :rf/default))))
        "only the requested slices appear")))

(deftest frames-vector-narrows-to-listed-frames
  (let [snap (snapshot-state {:frames [:stories]})]
    (is (= #{:stories} (set (keys snap)))
        "only :stories appears in the result")))

(deftest frames-all-keyword-matches-registered-frames
  (is (= (set (stub-frame-ids))
         (set (keys (snapshot-state {:frames :all}))))))

(let [{:keys [fail error]} (run-tests 'snapshot-test)]
  (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))
