(ns day8.re-frame2-causa.panels.views-helpers-cljs-test
  "Pure-data tests for Causa's Views panel helpers (rf2-21ob3).

  Per `tools/causa/spec/012-Views.md` the panel splits a cascade's
  render activity into three groups (mounted / re-rendered /
  unmounted), clusters grid-explosions, and surfaces per-render
  invalidated-by sub lists. The algebra lives in
  `views_helpers.cljc`; this ns covers each helper + the assembled
  `build-views-data` projection.

  Naming follows the dual-target `_cljs_test.cljc` pattern other
  helper tests use (Cognitect runner picks up the `.*-test$` ns name
  via clj; Shadow `:node-test` build picks it up via the `cljs-test$`
  regex)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.views-helpers :as h]))

(defn- mk-render
  ([view-id instance-token]
   (mk-render view-id instance-token nil 1.0))
  ([view-id instance-token triggered-by]
   (mk-render view-id instance-token triggered-by 1.0))
  ([view-id instance-token triggered-by elapsed-ms]
   {:render-key   [view-id instance-token]
    :triggered-by triggered-by
    :elapsed-ms   elapsed-ms}))

;; ---- (1) classify-renders ------------------------------------------------

(deftest classify-renders-empty-cascade
  (testing "no renders in any cascade → every group is empty"
    (is (= {:mounted [] :rendered [] :unmounted []}
           (h/classify-renders [] [])))))

(deftest classify-renders-first-cascade-everything-mounted
  (testing "no prior cascade → every current render lands in :mounted"
    (let [current [(mk-render :view/a 1) (mk-render :view/b 2)]
          out     (h/classify-renders current nil)]
      (is (= 2 (count (:mounted out))))
      (is (= 0 (count (:rendered out))))
      (is (= 0 (count (:unmounted out)))))))

(deftest classify-renders-token-match-is-rerender
  (testing "an instance-token present in both cascades is a re-render"
    (let [prior   [(mk-render :view/a 1)]
          current [(mk-render :view/a 1 :sub/x)]
          out     (h/classify-renders current prior)]
      (is (= 0 (count (:mounted out))))
      (is (= 1 (count (:rendered out))))
      (is (= 0 (count (:unmounted out)))))))

(deftest classify-renders-detects-mount-and-unmount
  (testing "new tokens land in :mounted; missing-from-current tokens
            land in :unmounted; the row keeps the prior cascade's data"
    (let [prior   [(mk-render :view/a 1)
                   (mk-render :view/b 2 :sub/y 5.0)]
          current [(mk-render :view/a 1 :sub/x)
                   (mk-render :view/c 3)]
          out     (h/classify-renders current prior)]
      (is (= 1 (count (:mounted out)))   "view/c just appeared")
      (is (= 1 (count (:rendered out)))  "view/a re-rendered")
      (is (= 1 (count (:unmounted out))) "view/b disappeared")
      (let [unmounted (first (:unmounted out))]
        (is (= [:view/b 2] (:render-key unmounted))
            "the unmounted row carries the prior cascade's render-key")
        (is (= 5.0 (:elapsed-ms unmounted))
            "the unmounted row carries the prior cascade's elapsed-ms")))))

;; ---- (2) cluster-renders -------------------------------------------------

(deftest cluster-renders-below-threshold-leaves-singles
  (testing "fewer than `threshold` renders sharing identity → each
            renders individually as :single items"
    (let [renders [(mk-render :view/a 1 :sub/x)
                   (mk-render :view/a 2 :sub/x)
                   (mk-render :view/a 3 :sub/x)]
          out     (h/cluster-renders renders 50)]
      (is (= 3 (count out)))
      (is (every? #(= :single (:kind %)) out)))))

(deftest cluster-renders-at-threshold-collapses-to-cluster
  (testing "≥ `threshold` renders with the same (view-id, triggered-by)
            collapse into ONE :cluster row carrying aggregate stats"
    (let [n       50
          renders (mapv #(mk-render :view/cell % :grid/cell-data 0.1)
                       (range n))
          out     (h/cluster-renders renders 50)]
      (is (= 1 (count out)))
      (let [c (first out)]
        (is (= :cluster (:kind c)))
        (is (= n (:count c)))
        (is (= :view/cell (:view-id c)))
        (is (= :grid/cell-data (:triggered-by c)))
        (is (> (:total-ms c) 0))
        (is (= n (count (:renders c)))
            "cluster preserves every original render for expand-cluster")))))

(deftest cluster-renders-grid-explosion-1000
  (testing "spec §Grid-explosion clustering — a 1000-cell grid all
            collapses to one cluster row, not 1000 row entries"
    (let [grid (mapv #(mk-render :view/cell % :grid/cell-data 0.012)
                     (range 1000))
          out  (h/cluster-renders grid)]
      (is (= 1 (count out)))
      (is (= 1000 (:count (first out)))))))

(deftest cluster-renders-mixed-cluster-and-singles
  (testing "renders below threshold stay single; renders above threshold
            cluster — both kinds coexist in one output vector"
    (let [renders (concat
                    (mapv #(mk-render :view/cell % :grid/cell-data 0.1)
                         (range 60))
                    [(mk-render :view/banner 9000 :ui/theme 2.0)
                     (mk-render :view/header 9001 :ui/theme 3.0)])
          out     (h/cluster-renders renders 50)
          clusters (filterv #(= :cluster (:kind %)) out)
          singles  (filterv #(= :single  (:kind %)) out)]
      (is (= 1 (count clusters)))
      (is (= 2 (count singles))))))

;; ---- (3) re-render invalidated-by ---------------------------------------

(deftest re-render-invalidated-by-with-trigger
  (testing "render's :triggered-by becomes the ✱ trigger row"
    (let [render (mk-render :view/a 1 :sub/x)
          sub-runs [{:sub-id :sub/x :query-v [:sub/x] :recomputed? true}
                    {:sub-id :sub/y :query-v [:sub/y] :recomputed? true}]
          out (h/re-render-invalidated-by render sub-runs)]
      (is (true? (:trigger? (first out))))
      (is (= :sub/x (:sub-id (first out))))
      (is (some #(and (= :sub/y (:sub-id %)) (not (:trigger? %))) out)
          "non-trigger subs appear as · also-consumed entries"))))

(deftest re-render-invalidated-by-parent-forced
  (testing "nil :triggered-by → synthetic ::parent-forced trigger row"
    (let [render (mk-render :view/a 1 nil)
          out    (h/re-render-invalidated-by render [])]
      (is (= 1 (count out)))
      (is (= :day8.re-frame2-causa.panels.views-helpers/parent-forced
             (:sub-id (first out))))
      (is (true? (:trigger? (first out)))))))

;; ---- (4) build-views-data assembly --------------------------------------

(deftest build-views-data-empty-cascade
  (testing "no renders → zero totals + empty groups"
    (let [out (h/build-views-data [] [] [] {})]
      (is (= 0 (:mounted   (:totals out))))
      (is (= 0 (:rendered  (:totals out))))
      (is (= 0 (:unmounted (:totals out))))
      (is (= 0 (:cascade-ms (:totals out)))))))

(deftest build-views-data-three-groups
  (testing "mounted + re-rendered + unmounted classify correctly"
    (let [prior   [(mk-render :view/a 1 :sub/x 1.0)
                   (mk-render :view/b 2 :sub/y 1.0)]
          current [(mk-render :view/a 1 :sub/x 1.5)
                   (mk-render :view/c 3 nil 0.5)]
          out     (h/build-views-data current prior [] {})]
      (is (= 1 (:mounted   (:totals out))))
      (is (= 1 (:rendered  (:totals out))))
      (is (= 1 (:unmounted (:totals out)))))))

(deftest build-views-data-clusters-rendered-group
  (testing "a re-rendered grid with ≥ threshold copies clusters; the
            cluster row carries an :invalidated-by entry per spec
            §Per-row content (Re-rendered) → Clustered renders"
    (let [prior   (mapv #(mk-render :view/cell % :grid/cell-data 1.0)
                        (range 60))
          current (mapv #(mk-render :view/cell % :grid/cell-data 1.1)
                        (range 60))
          out     (h/build-views-data current prior [] {})
          rendered (:rendered (:groups out))]
      (is (= 1 (count rendered)))
      (is (= :cluster (:kind (first rendered))))
      (is (= 60 (:count (first rendered))))
      (is (some? (:invalidated-by (first rendered)))
          "cluster row carries an :invalidated-by trigger entry")
      (is (true? (-> rendered first :invalidated-by first :clustered?))))))

(deftest build-views-data-component-filter
  (testing ":component-filter narrows the projection to one view-id;
            other view-ids drop out of every group"
    (let [current [(mk-render :view/a 1 nil)
                   (mk-render :view/b 2 nil)]
          out     (h/build-views-data current [] []
                                      {:component-filter :view/a})]
      (is (= 1 (:mounted (:totals out))))
      (let [m (first (:mounted (:groups out)))]
        (is (= :view/a (h/render-key->view-id
                         (:render-key (:render m)))))))))

;; ---- (5) view-id formatting ---------------------------------------------

(deftest format-view-id-keyword-drops-colon
  (is (= "cart/order-row" (h/format-view-id :cart/order-row))))

(deftest format-view-id-anonymous-renders-placeholder
  (is (= "<anonymous>" (h/format-view-id :rf.view/anonymous))))

(deftest render-key-projections
  (is (= :view/a (h/render-key->view-id [:view/a 7])))
  (is (= 7 (h/render-key->instance-token [:view/a 7])))
  (is (nil? (h/render-key->view-id nil))))

(deftest cluster-count-helper
  (let [items [{:kind :single} {:kind :cluster} {:kind :single}
               {:kind :cluster} {:kind :cluster}]]
    (is (= 3 (h/cluster-count items)))))

(deftest group-order-is-canonical
  (is (= [:mounted :rendered :unmounted] h/group-order)))

(deftest group-glyph-has-every-group
  (doseq [g h/group-order]
    (is (some? (get h/group-glyph g))
        (str "glyph for " g))))
