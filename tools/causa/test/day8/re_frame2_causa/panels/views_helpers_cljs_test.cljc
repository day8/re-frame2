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

;; ---- (6) sub-status classifier (rf2-r2s2l) ------------------------------
;;
;; Per spec §0ter.1 R3 — three statuses per sub in the Re-rendered
;; group's Rerendered-because list:
;;   :cache-miss-trigger — recomputed + value changed (`✱`)
;;   :cache-miss-equal   — recomputed + value equal   (`≈`)
;;   :cache-hit          — did not recompute          (`·`)

(deftest sub-status-trigger-classifies-as-cache-miss-trigger
  (testing "any row marked :trigger? true is :cache-miss-trigger
            regardless of :recomputed?"
    (is (= :cache-miss-trigger
           (h/sub-status {:sub-id :s :trigger? true :recomputed? true})))
    (is (= :cache-miss-trigger
           (h/sub-status {:sub-id :s :trigger? true :recomputed? false})))))

(deftest sub-status-recomputed-non-trigger-classifies-as-cache-miss-equal
  (testing "a non-trigger row whose sub recomputed this cascade is
            :cache-miss-equal — the recompute returned a value
            structurally equal to the prior, so React skipped the
            re-render of any view reading only this sub"
    (is (= :cache-miss-equal
           (h/sub-status {:sub-id :s :trigger? false :recomputed? true})))))

(deftest sub-status-non-recomputed-classifies-as-cache-hit
  (testing "a row that didn't recompute is :cache-hit — the view
            read the cached value; no work"
    (is (= :cache-hit
           (h/sub-status {:sub-id :s :trigger? false :recomputed? false})))))

;; ---- (7) build-sub-grouped (rf2-r2s2l) ---------------------------------
;;
;; Per spec §Group-by toggle — invert the component → subs mapping
;; into sub → components.

(deftest build-sub-grouped-empty
  (testing "no rendered items → empty sub-grouped list"
    (is (= [] (h/build-sub-grouped [])))))

(deftest build-sub-grouped-single-trigger
  (testing "one single-row item with one trigger sub → one sub-row
            with the consuming view listed underneath"
    (let [items [{:kind :single
                  :render {:render-key [:cart/list :tok-1]
                           :triggered-by :cart/items
                           :elapsed-ms 1.0}
                  :invalidated-by [{:sub-id :cart/items :trigger? true
                                    :recomputed? true}]}]
          out (h/build-sub-grouped items)]
      (is (= 1 (count out)))
      (let [sub-row (first out)]
        (is (= :cart/items (:sub-id sub-row)))
        (is (true? (:trigger? sub-row)))
        (is (= 1 (:view-count sub-row)))
        (is (= 1 (count (:views sub-row))))
        (is (= :cart/list (:view-id (first (:views sub-row)))))
        (is (true? (:trigger? (first (:views sub-row)))))))))

(deftest build-sub-grouped-many-views-per-sub
  (testing "two components consume the same sub → one sub-row whose
            :views list carries both"
    (let [items [{:kind :single
                  :render {:render-key [:cart/list :tok-1]
                           :triggered-by :auth/user
                           :elapsed-ms 1.0}
                  :invalidated-by [{:sub-id :auth/user :trigger? true
                                    :recomputed? true}]}
                 {:kind :single
                  :render {:render-key [:cart/footer :tok-2]
                           :triggered-by :auth/user
                           :elapsed-ms 0.5}
                  :invalidated-by [{:sub-id :auth/user :trigger? true
                                    :recomputed? true}]}]
          out (h/build-sub-grouped items)]
      (is (= 1 (count out)))
      (is (= :auth/user (:sub-id (first out))))
      (is (= 2 (:view-count (first out))))
      (is (= #{:cart/list :cart/footer}
             (set (map :view-id (:views (first out)))))))))

(deftest build-sub-grouped-cluster-row-contributes-cluster-count
  (testing "a :cluster item contributes its :count to :view-count
            (per spec §Per-row content (Re-rendered) — clustered
            renders share one trigger sub)"
    (let [items [{:kind         :cluster
                  :view-id      :grid/cell
                  :triggered-by :grid/data
                  :count        80
                  :total-ms     8.0
                  :avg-ms       0.1
                  :p95-ms       0.2
                  :renders      []
                  :invalidated-by [{:sub-id :grid/data :trigger? true
                                    :recomputed? true :clustered? true}]}]
          out (h/build-sub-grouped items)]
      (is (= 1 (count out)))
      (is (= 80 (:view-count (first out))))
      (is (= 1 (count (:views (first out)))))
      (is (true? (:clustered? (first (:views (first out)))))))))

(deftest build-sub-grouped-preserves-source-order
  (testing "sub-rows sort by first-occurrence index in the source
            items so the inverted view's row order is stable across
            renders of the same projection"
    (let [items [{:kind :single
                  :render {:render-key [:b :tok-1] :triggered-by :sub-b
                           :elapsed-ms 1.0}
                  :invalidated-by [{:sub-id :sub-b :trigger? true
                                    :recomputed? true}]}
                 {:kind :single
                  :render {:render-key [:a :tok-2] :triggered-by :sub-a
                           :elapsed-ms 1.0}
                  :invalidated-by [{:sub-id :sub-a :trigger? true
                                    :recomputed? true}]}]
          out (h/build-sub-grouped items)]
      (is (= [:sub-b :sub-a] (mapv :sub-id out))
          "sub-b appeared in the first source item → ordered first"))))

;; ---- (8) flow attribution — 3-link sub-flow chain (rf2-tv8t1) -----------
;;
;; Per `ai/findings/2026-05-19-causa-machine-inspector-mode-s.md` §13 +
;; §11 Comment 8. Each Re-rendered row's trigger sub carries a third
;; link — `via :flow-z` — when a flow fired this cascade. Handler-
;; effect-only cascades stay 2-link (no `:via-flow-ids`).

(defn- mk-flow-computed
  "Synthesise one `:rf.flow/computed` trace event in the shape the
  framework emits per spec/009 + spec/013. Mirrors the projection
  shape `flows-fired-this-cascade` consumes."
  [flow-id write-path]
  {:operation :rf.flow/computed
   :tags      {:flow-id flow-id
               :path    write-path}})

(deftest flows-fired-this-cascade-empty
  (testing "no trace events → empty flow-writes vector"
    (is (= [] (h/flows-fired-this-cascade nil)))
    (is (= [] (h/flows-fired-this-cascade [])))))

(deftest flows-fired-this-cascade-projects-flow-computed
  (testing "every `:rf.flow/computed` trace projects into a
            `{:flow-id … :write-path …}` record"
    (let [events [(mk-flow-computed :cart-total [:cart :total])
                  (mk-flow-computed :tax-due    [:tax :due])]
          out    (h/flows-fired-this-cascade events)]
      (is (= 2 (count out)))
      (is (= {:flow-id :cart-total :write-path [:cart :total]}
             (first out)))
      (is (= {:flow-id :tax-due :write-path [:tax :due]}
             (second out))))))

(deftest flows-fired-this-cascade-skips-non-flow-events
  (testing "trace events of other operations are ignored — only
            `:rf.flow/computed` projects"
    (let [events [{:operation :event/dispatched :tags {:event-id :foo}}
                  (mk-flow-computed :cart-total [:cart :total])
                  {:operation :sub/run :tags {:sub-id :foo}}]
          out    (h/flows-fired-this-cascade events)]
      (is (= 1 (count out)))
      (is (= :cart-total (:flow-id (first out)))))))

(deftest flows-fired-this-cascade-defensive-against-missing-tags
  (testing "events missing :flow-id or :path tags are skipped
            (defensive — spec/013 guarantees them but older fixtures
            may not)"
    (let [events [{:operation :rf.flow/computed :tags {:flow-id :no-path}}
                  {:operation :rf.flow/computed :tags {:path [:no :id]}}
                  (mk-flow-computed :ok [:ok])]
          out    (h/flows-fired-this-cascade events)]
      (is (= 1 (count out)))
      (is (= :ok (:flow-id (first out)))))))

(deftest attribute-trigger-to-flows-trigger-with-flows
  (testing "a trigger row + one flow-write → the flow-id attribution"
    (let [row    {:sub-id :sub/x :trigger? true :recomputed? true}
          writes [{:flow-id :cart-total :write-path [:cart :total]}]]
      (is (= [:cart-total] (h/attribute-trigger-to-flows row writes))))))

(deftest attribute-trigger-to-flows-trigger-with-multiple-flows
  (testing "a trigger row + multiple flow-writes → every flow-id in
            source order (the renderer surfaces 'via :z, :w')"
    (let [row    {:sub-id :sub/x :trigger? true :recomputed? true}
          writes [{:flow-id :cart-total :write-path [:cart :total]}
                  {:flow-id :tax-due    :write-path [:tax :due]}]]
      (is (= [:cart-total :tax-due]
             (h/attribute-trigger-to-flows row writes))))))

(deftest attribute-trigger-to-flows-dedups-flow-ids
  (testing "a single flow that wrote multiple paths surfaces as ONE
            flow-id in the attribution (deduplication)"
    (let [row    {:sub-id :sub/x :trigger? true :recomputed? true}
          writes [{:flow-id :cart-total :write-path [:cart :total]}
                  {:flow-id :cart-total :write-path [:cart :subtotal]}]]
      (is (= [:cart-total]
             (h/attribute-trigger-to-flows row writes))))))

(deftest attribute-trigger-to-flows-non-trigger-no-attribution
  (testing "non-trigger rows (:trigger? false) never carry a flow
            link — the third link decorates the cause, not the
            also-consumed subs"
    (let [row    {:sub-id :sub/x :trigger? false :recomputed? true}
          writes [{:flow-id :cart-total :write-path [:cart :total]}]]
      (is (= [] (h/attribute-trigger-to-flows row writes))))))

(deftest attribute-trigger-to-flows-parent-forced-no-attribution
  (testing "parent-forced trigger rows never carry a flow link —
            the parent component is the cause, not a sub
            invalidation"
    (let [row    {:sub-id      :day8.re-frame2-causa.panels.views-helpers/parent-forced
                  :trigger?    true
                  :recomputed? false}
          writes [{:flow-id :cart-total :write-path [:cart :total]}]]
      (is (= [] (h/attribute-trigger-to-flows row writes))))))

(deftest attribute-trigger-to-flows-handler-effect-only-stays-2-link
  (testing "no flows fired this cascade → empty attribution → row
            stays 2-link (the bead's 'handler-effect writes still
            surface as 2-link' clause)"
    (let [row    {:sub-id :sub/x :trigger? true :recomputed? true}
          writes []]
      (is (= [] (h/attribute-trigger-to-flows row writes))))))

(deftest re-render-invalidated-by-attaches-via-flow-ids-to-trigger
  (testing "the trigger row carries :via-flow-ids populated from
            flow-writes; non-trigger rows carry an empty :via-flow-ids
            slot so callers can rely on the key being present"
    (let [render   (mk-render :view/a 1 :sub/x)
          sub-runs [{:sub-id :sub/x :recomputed? true}
                    {:sub-id :sub/y :recomputed? true}]
          writes   [{:flow-id :cart-total :write-path [:cart :total]}]
          out      (h/re-render-invalidated-by render sub-runs writes)
          trigger  (first out)
          others   (rest out)]
      (is (true? (:trigger? trigger)))
      (is (= [:cart-total] (:via-flow-ids trigger))
          "trigger row carries the flow attribution")
      (is (every? #(= [] (:via-flow-ids %)) others)
          "non-trigger rows ship with an empty :via-flow-ids slot"))))

(deftest re-render-invalidated-by-parent-forced-empty-via-flow-ids
  (testing "parent-forced trigger has :via-flow-ids [] — even when
            flows fired this cascade — because the parent is the
            cause, not a sub invalidation"
    (let [render (mk-render :view/a 1 nil)
          writes [{:flow-id :cart-total :write-path [:cart :total]}]
          out    (h/re-render-invalidated-by render [] writes)]
      (is (= 1 (count out)))
      (is (= [] (:via-flow-ids (first out)))))))

(deftest re-render-invalidated-by-backward-compatible-2-arg
  (testing "the 2-arg arity (no flow-writes) still works — :via-flow-ids
            comes through as [] so existing callers stay correct"
    (let [render (mk-render :view/a 1 :sub/x)
          out    (h/re-render-invalidated-by render [])]
      (is (= [] (:via-flow-ids (first out)))))))

(deftest build-views-data-threads-flow-attribution
  (testing "`build-views-data` consumes `:trace-events`, projects
            flow-writes once, and surfaces :via-flow-ids on every
            Re-rendered trigger row"
    (let [prior     [(mk-render :view/a 1 :sub/x 1.0)]
          current   [(mk-render :view/a 1 :sub/x 1.5)]
          sub-runs  [{:sub-id :sub/x :recomputed? true}]
          events    [(mk-flow-computed :cart-total [:cart :total])]
          out       (h/build-views-data current prior sub-runs
                                        {:trace-events events})
          rendered  (:rendered (:groups out))
          trigger   (-> rendered first :invalidated-by first)]
      (is (= 1 (count rendered)))
      (is (true? (:trigger? trigger)))
      (is (= [:cart-total] (:via-flow-ids trigger))
          "the 3-link chain — :sub/x trigger 'via :cart-total'")
      (is (= [{:flow-id :cart-total :write-path [:cart :total]}]
             (:flow-writes out))
          "the cascade-level flow projection is surfaced as
           :flow-writes for any downstream consumer"))))

(deftest build-views-data-handler-effect-only-stays-2-link
  (testing "no `:rf.flow/computed` traces → the assembly stays
            2-link (handler-effect-only path — bead's policy that
            handler writes 'still surface as 2-link')"
    (let [prior    [(mk-render :view/a 1 :sub/x 1.0)]
          current  [(mk-render :view/a 1 :sub/x 1.5)]
          sub-runs [{:sub-id :sub/x :recomputed? true}]
          out      (h/build-views-data current prior sub-runs
                                       {:trace-events []})
          rendered (:rendered (:groups out))
          trigger  (-> rendered first :invalidated-by first)]
      (is (= [] (:via-flow-ids trigger))
          "no flows fired → no third link → 2-link stays")
      (is (= [] (:flow-writes out))))))

(deftest build-views-data-clustered-trigger-carries-flow-attribution
  (testing "clustered Re-rendered rows ALSO carry :via-flow-ids on
            their synthetic trigger — a 1000-cell grid invalidated
            by a flow chain reports 'via :flow-z' in the cluster
            row, same shape as single rows"
    (let [prior   (mapv #(mk-render :view/cell % :grid/data 1.0)
                        (range 60))
          current (mapv #(mk-render :view/cell % :grid/data 1.1)
                        (range 60))
          events  [(mk-flow-computed :grid-source [:grid :data])]
          out     (h/build-views-data current prior [] {:trace-events events})
          rendered (:rendered (:groups out))
          cluster  (first rendered)
          trigger  (first (:invalidated-by cluster))]
      (is (= :cluster (:kind cluster)))
      (is (= [:grid-source] (:via-flow-ids trigger))
          "cluster trigger row carries the same flow attribution as
           single rows so the third link surfaces in both layouts"))))
