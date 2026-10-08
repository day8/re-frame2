(ns day8.re-frame2-xray.static.flows.panel-cljs-test
  "CLJS wiring + view tests for the Static Flows sub-tab.

  ## Scope

    1. **Registry wires the Static Flows subs + events** under
       `:rf.xray.static.flows/*`.

    2. **Pure projection** — `project-rows`, `filter-rows`,
       `project-data` cover the flat-list + filter shape with no
       runtime / DOM dependency.

    3. **Silent state** — no flows registered → empty body.

    4. **Flat-list rendering** — every registered flow surfaces as a
       row.

    5. **Search filter** — substring across flow-id + path + doc."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load-time hook so `reg-flow` / `flows-snapshot`
            ;; resolve. The Static Flows panel reads the PRODUCTION data
            ;; source `rf.flows/flows-snapshot` (the per-frame flows atom is the
            ;; sole store; the registrar `:flow` slot is
            ;; reserved-but-empty), so the live-source regression below
            ;; registers real flows through `rf/reg-flow`.
            [re-frame.flows]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.flows.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `reset-all!` folds the trace-collector ring reset in, so no direct
  ;; trace reset is needed. Default `:all` tier + plain-atom adapter.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers ------------------------------------------------------
;;
;; PLAIN DESCENT — nothing is CALLED. `rf.test-helpers/find-by-testid` /
;; `…/find-by-testid-prefix` EXPAND function components as they walk, which
;; is UNSAFE here. The panel CALLS every plain helper, so its markup is
;; already realized in the tree and a shallow walk suffices — but the one
;; fn-headed vector the panel emits is `[ei/edn-inspector-view …]`, a FRESCO
;; BOUNDARY: a React function component whose body may only run inside a
;; React render window. Applying it here would run `rf.fresco/sub` outside
;; the collector, which is not a leaf-expansion at all. So the widget stays a
;; LEAF, exactly as in `panels/app_db_diff_cljs_test`, for the same reason.

(defn- hiccup-nodes [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-nodes tree)))

(defn- find-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (some-> (:data-testid (second node))
                          (.startsWith prefix))))
           (hiccup-nodes tree)))

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- panel-tree
  "The hiccup the view rows below walk, driven through the pure projection.

  `panel/Panel` is an `rf.fresco/defview` boundary, a real React function
  component whose body may only run inside a React render window, so
  `(panel/Panel)` is not a callable that answers hiccup. This helper
  REPRODUCES THE BOUNDARY'S READ EXACTLY — the one
  `:rf.xray.static.flows/tab-data` query the boundary issues — and hands the
  value to `panel/panel-tree`, so every row below asserts on the hiccup the
  boundary renders.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.flows/tab-data]) nil))

(defn- inspector-view-forms
  "Every `[ei/edn-inspector-view {…}]` form in the tree — the widget's
  FRESCO head, left unexpanded (see the walker note above)."
  [tree]
  (filterv #(and (vector? %) (= ei/edn-inspector-view (first %)))
           (hiccup-nodes tree)))

;; ---- fixture data -------------------------------------------------------

(def sample-flows
  {:rf/default
   {:user/full-name
    {:id          :user/full-name
     :inputs      [[:user :first] [:user :last]]
     :derive      (fn [_] "")
     :output-path [:derived :full-name]
     :doc         "concat first + last"}
    :cart/total
    {:id          :cart/total
     :inputs      [[:cart :items]]
     :derive      (fn [_] 0)
     :output-path [:cart :total]
     :doc         "sum of cart items"}}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest filter-rows-substring
  (let [rows (panel/project-rows sample-flows)]
    (testing "a nil or blank query returns rows verbatim"
      (is (= rows (panel/filter-rows rows nil)))
      (is (= rows (panel/filter-rows rows "   "))))
    (testing "case-insensitive substring across id and doc"
      (are [query ids] (= ids (mapv :flow-id (panel/filter-rows rows query)))
        "CART"   [:cart/total]
        "concat" [:user/full-name]))))

(deftest project-data-shape
  ;; nil frame-id = list every frame's flows (see scope-to-frame).
  (is (= [false 2 false]
         ((juxt :silent? :total :filtered?) (panel/project-data sample-flows nil nil))))
  (is (true? (:filtered? (panel/project-data sample-flows nil "cart"))))
  (is (true? (:silent? (panel/project-data {} nil nil)))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(def two-frame-flows
  "Two frames each carrying distinct flows — fixture for the picker-
  scoping regression."
  {:rf/default
   {:user/full-name {:id          :user/full-name
                     :inputs      [[:user :first]]
                     :output-path [:derived :full-name]}}
   :rf/cart-frame
   {:cart/total {:id          :cart/total
                 :inputs      [[:cart :items]]
                 :output-path [:cart :total]}
    :cart/count {:id          :cart/count
                 :inputs      [[:cart :items]]
                 :output-path [:cart :count]}}})

(deftest tab-data-scopes-to-picker-frame
  (testing "the L1 frame picker scopes the Flows catalogue to the picked
            frame's flows rather than the flattened all-frames set of 3"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         two-frame-flows])
      (let [total-and-ids (fn []
                            (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])]
                              [(:total data) (mapv :flow-id (:flows data))]))]
        (rf/dispatch-sync [:rf.xray/select-frame :rf/default])
        (is (= [1 [:user/full-name]] (total-and-ids)))
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
        (is (= [2 [:cart/count :cart/total]] (total-and-ids)))))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-search"))
          "search box rendered")
      (is (= ["list" ["listitem" "listitem"]]
             [(:role (second (find-by-testid tree "rf-xray-static-flows-list")))
              (mapv #(:role (second %))
                    (find-by-testid-prefix tree "rf-xray-static-flows-row-"))])
          "a role=list <ul> holding one role=listitem row per flow"))))

(deftest panel-renders-filtered-state
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (rf/dispatch-sync [:rf.xray.static.flows/set-query "no-such-flow"])
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-empty-filtered"))
          "empty-filtered surface mounts when query removes every row"))))

;; -------------------------------------------------------------------------
;; (4) ONE FLOW-ID, TWO FRAMES, ONE RENDER FRAME
;; -------------------------------------------------------------------------
;;
;; The flows registry is frame-divergent per id (Spec 013), and the browse-all
;; projection lists every frame's flows at once, so one flow-id can reach the
;; tree twice inside the one `:rf/xray` render frame.

(def one-flow-id-two-frames
  "ONE flow-id registered against TWO frames, each with its own `:inputs` /
  `:output-path`. `scope-to-frame` with a nil frame-id (the default,
  unselected observed frame) passes both through, so they project to TWO
  rows."
  {:app/a {:shared/flow {:id          :shared/flow
                         :inputs      [[:a :in]]
                         :output-path [:a :out]}}
   :app/b {:shared/flow {:id          :shared/flow
                         :inputs      [[:b :in]]
                         :output-path [:b :out]}}})

(deftest two-rows-sharing-a-flow-id-across-frames-get-distinct-mount-ids
  (testing "the inspector node key carries the row's OWNING FRAME, so two rows
            sharing a flow-id across frames do not share a `:mount-id`.
            `edn-inspector/container-ref-for` memoises its ref callback on
            `[render-frame mount-id]`, so a shared id would give two live
            mounts one ResizeObserver entry, and detaching either row would
            release the survivor's."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         one-flow-id-two-frames])
      (let [mount-ids (mapv #(:mount-id (second %)) (inspector-view-forms (panel-tree)))]
        (is (= 4 (count mount-ids) (count (set mount-ids)))
            (str "2 rows x (1 input + 1 output) = 4 inspector mounts, all "
                 "distinct. Mount ids: " (pr-str mount-ids)))))))

;; -------------------------------------------------------------------------
;; (4b) the input-path seq's React keys actually REACH the renderer
;; -------------------------------------------------------------------------

(defn- keyed-input-fragments
  "Every `[:<> {:key …} [ei/edn-inspector-view {…}]]` fragment wrapping an
  INPUT path's value, picked out by the `/input/` segment of the boundary's
  `:mount-id`. The output value is not in a seq and needs no key."
  [tree]
  (filterv (fn [node]
             (and (vector? node)
                  (= :<> (first node))
                  (map? (second node))
                  (let [child (nth node 2 nil)]
                    (and (vector? child)
                         (= ei/edn-inspector-view (first child))
                         (re-find #"/input/"
                                  (str (:mount-id (second child))))))))
           (hiccup-nodes tree)))

(defn- fragment-mount-id [fragment]
  (str (:mount-id (second (nth fragment 2 nil)))))

(deftest input-path-rows-carry-react-keys-in-the-attribute-map
  (testing "each input-path value in a flow row's `for` seq carries its React
            key in the keyed fragment's ATTRIBUTE MAP — the one spelling
            Fresco's codec reads. Metadata reaches React nowhere, and a lost
            key degrades silently into index-based reconciliation."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [fragments (keyed-input-fragments (panel-tree))]
        (is (<= 2 (count fragments))
            (str "PRECONDITION: at least two input-path values rendered — the "
                 "claims below are vacuous over fewer. Got: " (count fragments)))
        (is (every? #(some? (:key (second %))) fragments)
            (str "every input-path value carries a React key in the attribute "
                 "map. Attribute maps seen: " (pr-str (mapv second fragments))))
        ;; Uniqueness is PER SEQ: each flow row owns its own inputs seq, and
        ;; two flows both start theirs at `in-0`.
        (is (every? (fn [[_ frags]]
                      (= (count frags)
                         (count (set (map #(:key (second %)) frags)))))
                    (group-by #(second (re-find #"^(.*)/input/"
                                                (fragment-mount-id %)))
                              fragments))
            "and within any ONE row's seq the keys are distinct")))))

;; -------------------------------------------------------------------------
;; (5) LIVE production data source regression
;; -------------------------------------------------------------------------
;;
;; Every test above injects fixtures through the test-only OVERRIDE seam,
;; whose branch never touches the production read. This one exercises the
;; PRODUCTION path — the `:rf.xray.static.flows/registered-flows` sub's
;; `registered-flows-value` → `re-frame.flows/flows-snapshot` read — against
;; REAL `reg-flow` registrations, with NO override installed.
;;
;; The per-frame `flows` atom is the SOLE store and the registrar `:flow`
;; slot is RESERVED-but-empty: `(rf/registrations :flow)` THROWS
;; `:rf.error/registrar-kind-not-queryable`. A `registered-flows-value` body
;; that read the registrar would return an EMPTY catalogue against real
;; flows, while every override-based test above stays green.

(defn- production-setup-xray!
  "Install the PRODUCTION Static Flows wiring (no override seam) plus the
  host frame the live-source flows register against."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :flows-test/frame-a :doc "host frame A"}))

(deftest live-source-reads-flows-snapshot-not-empty-registrar-slot
  (testing "the PRODUCTION Static Flows data source reads
            `rf.flows/flows-snapshot` and surfaces real `reg-flow`
            registrations"
    (production-setup-xray!)
    (rf/reg-flow :user/full-name
                 {:inputs      [[:user :first] [:user :last]]
                  :output-path [:derived :full-name]
                  :doc         "concat first + last"
                  :frame       :flows-test/frame-a}
                 (fn [first* last*] (str first* " " last*)))
    (rf/reg-flow :cart/total
                 {:inputs      [[:cart :items]]
                  :output-path [:cart :total]
                  :doc         "sum of cart items"
                  :frame       :flows-test/frame-a}
                 (fn [_] 0))
    ;; nil picker frame → list every frame's flows (see scope-to-frame).
    (rf/with-frame :rf/xray
      (is (= #{:user/full-name :cart/total}
             (set (keys (get @(rf/subscribe [:rf.xray.static.flows/registered-flows])
                             :flows-test/frame-a))))
          "frame A's two flows surface, keyed by frame in the per-frame shape")
      (is (= 2 (:total @(rf/subscribe [:rf.xray.static.flows/tab-data])))
          "both real flows reach the view-facing composite"))))
