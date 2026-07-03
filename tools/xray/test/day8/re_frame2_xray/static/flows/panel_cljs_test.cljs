(ns day8.re-frame2-xray.static.flows.panel-cljs-test
  "CLJS wiring + view tests for the Static Flows sub-tab (rf2-uhsqb).

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
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-20359j — load-time hook so `reg-flow` / `flows-snapshot`
            ;; resolve. The Static Flows panel reads the PRODUCTION data
            ;; source `flows/flows-snapshot` (the per-frame flows atom is the
            ;; sole store after rf2-en00bk; the registrar `:flow` slot is
            ;; reserved-but-empty), so the live-source regression below
            ;; registers real flows through `rf/reg-flow`.
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.flows.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- hiccup walker ------------------------------------------------------

(declare expand-tree)
(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (let [result (apply (first tree) (rest tree))]
      ;; Form-2 Reagent components return an inner fn; call it with
      ;; the same args (per Reagent's re-render contract) to obtain
      ;; the rendered hiccup. rf2-oqa60 — the edn-inspector widget is
      ;; form-2 so the mount-id stays stable across re-renders.
      (expand-tree
        (if (fn? result)
          (apply result (rest tree))
          result)))
    (vector? tree) (mapv expand-tree tree)
    (seq? tree)    (map expand-tree tree)
    :else          tree))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-tree tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- setup-xray! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (frame/reg-frame :rf/xray {}))

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

(deftest project-rows-flattens-and-sorts
  (testing "project-rows flattens {frame {id flow}} into a sorted row vec"
    (let [rows (panel/project-rows sample-flows)]
      (is (= 2 (count rows)) "two rows")
      (is (= [:cart/total :user/full-name]
             (mapv :flow-id rows))
          "sorted by id ascending")
      (is (every? #(= :rf/default (:frame %)) rows)
          ":frame stamped on every row"))))

(deftest project-rows-empty-snapshot
  (is (= [] (panel/project-rows {}))
      "empty snapshot → empty rows"))

(deftest filter-rows-substring
  (let [rows (panel/project-rows sample-flows)]
    (testing "blank query returns rows verbatim"
      (is (= rows (panel/filter-rows rows nil)))
      (is (= rows (panel/filter-rows rows "")))
      (is (= rows (panel/filter-rows rows "   "))))
    (testing "case-insensitive substring across id"
      (is (= 1 (count (panel/filter-rows rows "CART"))))
      (is (= 1 (count (panel/filter-rows rows "user")))))
    (testing "matches against doc"
      (is (= 1 (count (panel/filter-rows rows "concat")))))
    (testing "no match → empty"
      (is (= 0 (count (panel/filter-rows rows "no-such-thing")))))))

(deftest project-data-shape
  ;; nil frame-id = list every frame's flows (see scope-to-frame).
  (let [data (panel/project-data sample-flows nil nil)]
    (testing "silent flag"
      (is (false? (:silent? data)))
      (is (true? (:silent? (panel/project-data {} nil nil)))))
    (testing "totals + filter flags"
      (is (= 2 (:total data)))
      (is (false? (:filtered? data)))
      (is (true? (:filtered? (panel/project-data sample-flows nil "cart")))))))

(deftest scope-to-frame-narrows-to-one-frame
  (let [multi {:rf/default {:a {:id :a}}
               :rf/cart    {:b {:id :b}}}]
    (testing "nil frame-id passes the snapshot through verbatim"
      (is (= multi (panel/scope-to-frame multi nil))))
    (testing "a frame-id keeps only that frame's entry"
      (is (= {:rf/default {:a {:id :a}}}
             (panel/scope-to-frame multi :rf/default)))
      (is (= {:rf/cart {:b {:id :b}}}
             (panel/scope-to-frame multi :rf/cart))))
    (testing "an absent frame-id yields an empty registry"
      (is (= {} (panel/scope-to-frame multi :rf/nope))))))

(deftest project-data-scopes-to-frame
  (let [multi {:rf/default {:a {:id :a :inputs [] :output-path [:a]}}
               :rf/cart    {:b {:id :b :inputs [] :output-path [:b]}
                            :c {:id :c :inputs [] :output-path [:c]}}}]
    (testing "frame A surfaces only frame A's flows, not the flattened global set"
      (let [data (panel/project-data multi :rf/default nil)]
        (is (= 1 (:total data)))
        (is (= [:a] (mapv :flow-id (:flows data))))))
    (testing "frame B surfaces only frame B's flows"
      (let [data (panel/project-data multi :rf/cart nil)]
        (is (= 2 (:total data)))
        (is (= [:b :c] (mapv :flow-id (:flows data))))))
    (testing "nil frame-id still lists every frame's flows"
      (is (= 3 (:total (panel/project-data multi nil nil)))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (testing "register-xray-handlers! installs the static-flows subs + events"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray.static.flows/query]))
          "query slot defaults nil"))))

(deftest set-query-writes-the-slot
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static.flows/set-query "cart"])
    (is (= "cart" @(rf/subscribe [:rf.xray.static.flows/query])))
    (rf/dispatch-sync [:rf.xray.static.flows/set-query ""])
    (is (nil? @(rf/subscribe [:rf.xray.static.flows/query]))
        "blank string dissocs the slot")))

(deftest registered-flows-override-feeds-the-composite
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])]
      (is (= 2 (:total data)) "override surfaces two flows")
      (is (false? (:silent? data))))
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test nil])))

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
  (testing "the L1 frame picker scopes the Flows catalogue — switching
            the picker frame changes which frame's flows the tab lists,
            rather than the flattened all-frames set"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         two-frame-flows])
      (testing "picker on :rf/default → only that frame's one flow"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/default])
        (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])]
          (is (= 1 (:total data)) "frame :rf/default has one flow")
          (is (= [:user/full-name] (mapv :flow-id (:flows data)))
              "only :rf/default's flow surfaces")))
      (testing "picker on :rf/cart-frame → only that frame's two flows"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
        (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])]
          (is (= 2 (:total data)) "frame :rf/cart-frame has two flows")
          (is (= [:cart/count :cart/total] (mapv :flow-id (:flows data)))
              "only :rf/cart-frame's flows surface — NOT the global set of 3")))
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test nil]))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test {}])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-empty"))
          "empty-state surface mounts"))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [tree (panel/Panel)
          rows (find-all-by-testid-prefix tree "rf-xray-static-flows-row-")]
      (is (= 2 (count rows)) "two row surfaces rendered")
      (is (some? (find-by-testid tree "rf-xray-static-flows-search"))
          "search box rendered"))))

(deftest panel-renders-filtered-state
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (rf/dispatch-sync [:rf.xray.static.flows/set-query "no-such-flow"])
    (let [tree (panel/Panel)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-empty-filtered"))
          "empty-filtered surface mounts when query removes every row"))))

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(defn- find-by-role [tree role]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= role (:role (second node))))
            node))
        (hiccup-seq tree)))

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the flows <ul> is role=list, rows are role=listitem"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree (panel/Panel)
            list-node (find-by-testid tree "rf-xray-static-flows-list")
            rows      (find-all-by-testid-prefix
                        tree "rf-xray-static-flows-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) EDN values render through the shared widget (rf2-2kwhw + rf2-f026h)
;; -------------------------------------------------------------------------

(deftest input-output-render-through-edn-widget
  (testing "rf2-2kwhw + rf2-oqa60 — input + output paths render via the
            shared EDN widget, which post-rf2-oqa60 phase 1 delegates
            to the first-class edn-inspector widget. The rendered tree
            contains `rf-xray-edn-inspector-*` container testids."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree (panel/Panel)
            widget-nodes (find-all-by-testid-prefix
                           tree "rf-xray-edn-inspector-")]
        (is (seq widget-nodes)
            "at least one edn-inspector widget container present")))))

;; -------------------------------------------------------------------------
;; (6) LIVE production data source regression (rf2-20359j)
;; -------------------------------------------------------------------------
;;
;; Every test above injects fixtures through the test-only OVERRIDE seam
;; (`set-registered-flows-override-for-test`), which is exactly why CI
;; stayed green after rf2-en00bk silently emptied the panel's real data
;; source: the override branch never touches the production read. This
;; section exercises the genuine PRODUCTION path — the
;; `:rf.xray.static.flows/registered-flows` sub's `registered-flows-value`
;; → `re-frame.flows/flows-snapshot` read — against REAL `reg-flow`
;; registrations, with NO override installed.
;;
;; rf2-en00bk made the per-frame `flows` atom the SOLE store and left the
;; registrar `:flow` slot RESERVED-but-empty. Before this PR the panel read
;; `(host-registry/registrations :flow)` (→ now `{}`), so the production
;; data source returned an EMPTY catalogue against real flows; after the
;; repoint it reads `flows/flows-snapshot` and surfaces them. Reverting the
;; `registered-flows-value` body back to the registrar read fails the first
;; assertion below (empty catalogue) while every override-based test above
;; stays green — proving the gap this regression closes.

(defn- production-setup-xray!
  "Install the PRODUCTION Static Flows wiring (no override seam) plus the
  two host frames the live-source flows register against."
  []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (frame/reg-frame :flows-test/frame-a {:doc "host frame A"})
  (frame/reg-frame :flows-test/frame-b {:doc "host frame B"}))

(deftest live-source-reads-flows-snapshot-not-empty-registrar-slot
  (testing "rf2-20359j — the PRODUCTION Static Flows data source reads
            `flows/flows-snapshot` (the per-frame flows store) and surfaces
            real `reg-flow` registrations. Pre-repoint it read the now-empty
            registrar `:flow` slot and returned an empty catalogue (the panel
            degraded silently); the override-based tests above could not see
            this regression."
    (production-setup-xray!)
    ;; Register REAL flows against host frame A via the public facade.
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
    ;; Read through the LIVE production sub — NOT the override seam — inside
    ;; the :rf/xray frame the panel seats in. nil picker frame → list every
    ;; frame's flows (see scope-to-frame), so the catalogue carries both.
    (rf/with-frame :rf/xray
      (let [snapshot @(rf/subscribe [:rf.xray.static.flows/registered-flows])]
        (is (seq snapshot)
            "production data source is NON-EMPTY against real flows (was {}
             when reading the now-empty registrar :flow slot)")
        (is (= #{:user/full-name :cart/total}
               (set (keys (get snapshot :flows-test/frame-a))))
            "frame A's two flows surface, keyed by frame in the per-frame shape"))
      (let [data @(rf/subscribe [:rf.xray.static.flows/tab-data])]
        (is (false? (:silent? data))
            "tab-data is not silent — the panel renders rows, not the empty state")
        (is (= 2 (:total data))
            "both real flows reach the view-facing composite")))))

(deftest live-source-surfaces-frame-divergent-definitions
  (testing "rf2-20359j / Spec 013 — the SAME flow-id registered against two
            frames carries each frame's OWN divergent definition in the
            production data source. The old frame-blind registrar slot could
            only ever show the last registrant; `flows/flows-snapshot` shows
            both, and the panel scopes per frame."
    (production-setup-xray!)
    (let [derive-a (fn [first* last*] (str first* " " last*))
          derive-b (fn [first* last*] (str last* ", " first*))]
      ;; Same flow-id :user/full-name, two frames, DIVERGENT :derive +
      ;; :output-path.
      (rf/reg-flow :user/full-name
                   {:inputs      [[:user :first] [:user :last]]
                    :output-path [:derived :natural]
                    :doc         "first last"
                    :frame       :flows-test/frame-a}
                   derive-a)
      (rf/reg-flow :user/full-name
                   {:inputs      [[:user :first] [:user :last]]
                    :output-path [:derived :sortable]
                    :doc         "last, first"
                    :frame       :flows-test/frame-b}
                   derive-b)
      (rf/with-frame :rf/xray
        (let [snapshot @(rf/subscribe [:rf.xray.static.flows/registered-flows])
              entry-a  (get-in snapshot [:flows-test/frame-a :user/full-name])
              entry-b  (get-in snapshot [:flows-test/frame-b :user/full-name])]
          (is (some? entry-a) "frame A's entry present")
          (is (some? entry-b) "frame B's entry present")
          (is (= derive-a (:derive entry-a))
              "frame A keeps its OWN :derive (not clobbered by frame B's later reg)")
          (is (= derive-b (:derive entry-b))
              "frame B keeps its OWN :derive")
          (is (= [:derived :natural] (:output-path entry-a))
              "frame A keeps its OWN :output-path")
          (is (= [:derived :sortable] (:output-path entry-b))
              "frame B keeps its OWN :output-path — divergent per frame")))
      ;; Cross-check the introspection surface the Epoch panel's source link
      ;; reads (`flow-meta-at` with an explicit :frame) agrees, frame-by-frame.
      (is (= derive-a (:derive (flows/flow-meta-at :user/full-name
                                                   {:frame :flows-test/frame-a})))
          "flow-meta-at resolves frame A's divergent definition")
      (is (= derive-b (:derive (flows/flow-meta-at :user/full-name
                                                   {:frame :flows-test/frame-b})))
          "flow-meta-at resolves frame B's divergent definition"))))
