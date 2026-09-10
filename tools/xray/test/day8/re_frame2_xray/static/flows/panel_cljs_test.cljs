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
            ;; source `rf.flows/flows-snapshot` (the per-frame flows atom is the
            ;; sole store after rf2-en00bk; the registrar `:flow` slot is
            ;; reserved-but-empty), so the live-source regression below
            ;; registers real flows through `rf/reg-flow`.
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.flows.panel :as panel]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `reset-all!` folds the trace-collector ring reset in, so the old
  ;; bespoke `xray-init!` (reset-all! + a REDUNDANT direct trace reset) is
  ;; gone (rf2-vj80u8). Default `:all` tier + plain-atom adapter.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers ------------------------------------------------------
;;
;; PLAIN DESCENT — nothing is CALLED. These rows used
;; `rf.test-helpers/find-by-testid` / `…/find-by-testid-prefix`, which EXPAND
;; function components as they walk (rf2-vj80u8 retired this file's private
;; copies in favour of them).
;;
;; rf2-k97c.3 made that expansion UNSAFE. Every plain helper the panel used
;; to head with is now CALLED, so its markup is already realized in the tree
;; and a shallow walk suffices again — but the one fn-headed vector that
;; REMAINS is `[ei/edn-inspector-view …]`, a FRESCO BOUNDARY: a React
;; function component whose body may only run inside a React render window.
;; Applying it here would run `rf.fresco/sub` outside the collector, which
;; is not a leaf-expansion at all. So the widget stays a LEAF, exactly as
;; `panels/app_db_diff_cljs_test` already settled for the same reason.

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

  rf2-k97c.3 — `panel/Panel` is now an `rf.fresco/defview` boundary, a real
  React function component whose body may only run inside a React render
  window, so `(panel/Panel)` is no longer a callable that answers hiccup.
  This helper REPRODUCES THE BOUNDARY'S READ EXACTLY — the one
  `:rf.xray.static.flows/tab-data` query the boundary issues — and hands the
  value to `panel/panel-tree`, so every row below asserts on the same hiccup
  it asserted on before.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`. The boundary's OWN behaviour
  — first paint, liveness, frame targeting, evidence isolation, teardown
  and row identity — is `panel_fresco_boundary_dom_cljs_test`'s subject."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.flows/tab-data]) nil))

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
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-empty"))
          "empty-state surface mounts"))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.flows/set-registered-flows-override-for-test
       sample-flows])
    (let [tree (panel-tree)
          rows (find-by-testid-prefix tree "rf-xray-static-flows-row-")]
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
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-flows-empty-filtered"))
          "empty-filtered surface mounts when query removes every row"))))

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the flows <ul> is role=list, rows are role=listitem"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree (panel-tree)
            list-node (find-by-testid tree "rf-xray-static-flows-list")
            rows      (find-by-testid-prefix
                        tree "rf-xray-static-flows-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) EDN values render through the shared widget (rf2-2kwhw + rf2-f026h)
;;     — since rf2-k97c.3, through its FRESCO head
;; -------------------------------------------------------------------------

(defn- inspector-view-forms
  "Every `[ei/edn-inspector-view {…}]` form in the tree — the widget's
  FRESCO head, and now the only fn-headed vector the panel emits.

  No expansion of any kind. The panel CALLS every plain helper since
  rf2-k97c.3, so the tree is already realized down to this leaf, and the
  leaf must STAY a leaf: applying a boundary here would run
  `rf.fresco/sub` outside the collector."
  [tree]
  (filterv #(and (vector? %) (= ei/edn-inspector-view (first %)))
           (hiccup-nodes tree)))

(deftest input-output-render-through-the-widgets-fresco-head
  (testing "rf2-2kwhw + rf2-oqa60 — input + output paths render via the
            shared EDN widget; rf2-k97c.3 — through its FRESCO head.

            This row used to assert on the widget's expanded
            `rf-xray-edn-inspector-*` CONTAINER testid, which only exists
            once the widget has been invoked. The walker above no longer
            invokes anything, and it must not: `ei/edn-inspector-view` is a
            boundary whose body may only run inside a React render window.
            So the claim moves up one level to the thing the panel actually
            emits — and in doing so it pins the HD-016 repair directly,
            which the old spelling could not: `ei/edn-inspector` is a plain
            fn, and a plain fn in hiccup head position is a loud error
            inside a Fresco body."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree  (panel-tree)
            heads (inspector-view-forms tree)]
        ;; Two flows: 2 + 1 input paths, plus one output path each = 5.
        (is (= 5 (count heads))
            (str "every input and output path renders through the widget's "
                 "Fresco head. Mount ids: "
                 (pr-str (mapv #(:mount-id (second %)) heads))))
        (is (empty? (filterv #(and (vector? %) (= ei/edn-inspector (first %)))
                             (hiccup-nodes tree)))
            "and NOT ONE `[ei/edn-inspector …]` Reagent head survives — that
             head is a plain fn, which is a loud error in a Fresco body, so
             a single survivor would take the whole panel down at runtime
             rather than degrade")
        (is (= (count heads) (count (set (map #(:mount-id (second %)) heads))))
            "each mount gets its OWN `:mount-id` — two mounts sharing one
             would share a width slot and a projection cache, which is the
             per-mount identity defect rf2-d2aj records")))))

;; -------------------------------------------------------------------------
;; (5b) the input-path seq's React keys actually REACH the renderer
;;      (rf2-k97c.3, RULING 2's key sweep)
;; -------------------------------------------------------------------------

(defn- keyed-input-fragments
  "Every `[:<> {:key …} [ei/edn-inspector-view {…}]]` fragment wrapping an
  INPUT path's value.

  Keyed off the boundary's `:mount-id`, which `edn-widget/inspect-view`
  derives from the node-key the panel passes —
  `rf-xray-inspect-static-flows/<flow>/input/<i>` for an input,
  `…/output` for the single output value. The output value is not in a seq
  and needs no key, so including it would make the claim below false for a
  correct panel."
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
  (testing "rf2-k97c.3 — each input-path value in a flow row's `for` seq
            carries a React key THE SHIPPED RENDERER CAN ACTUALLY READ.

            THIS ROW HAS BEEN WRONG TWICE, in two different ways, and the
            second way is the one worth recording. The key started life as
            `^{:key …}` reader metadata on the `(edn/inspect …)` CALL FORM —
            discarded on return, so nothing ever reached React. It was then
            repaired to `with-meta` on the returned VECTOR, and this row was
            written to assert on exactly that metadata.

            THAT ASSERTION IS NOW A HOLLOW GATE, and it would have stayed
            green through this migration while React received nothing:
            Fresco's codec reads `:key` from an ATTRIBUTE MAP and reads
            Clojure metadata NOWHERE. A lost key does not fail — it degrades
            into index-based reconciliation, which paints identically. So
            this row no longer looks at metadata at all; it reads the key off
            the keyed fragment's attribute map, which is the one spelling
            that reaches the renderer.

            The browser lane's W5 closes the loop from the other side, on a
            real React commit: it removes the HEAD of a two-row list and
            asserts the survivor is the same DOM node."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree      (panel-tree)
            fragments (keyed-input-fragments tree)]
        (is (<= 2 (count fragments))
            (str "PRECONDITION: at least two input-path values rendered in "
                 "one seq — a single-element seq needs no key, so a smaller "
                 "count would make the claim below vacuous. Got: "
                 (count fragments)))
        (is (every? #(some? (:key (second %))) fragments)
            (str "every input-path value carries a React key IN THE "
                 "ATTRIBUTE MAP, which is where both Reagent's "
                 "`get-react-key` and Fresco's codec look. Attribute maps "
                 "seen: " (pr-str (mapv second fragments))))
        ;; Uniqueness is a PER-SEQ property, not a global one: React only
        ;; needs a key to distinguish SIBLINGS, and each flow row owns its
        ;; own inputs seq. Grouping by the flow the mount-id names is what
        ;; makes this a real claim — asserted globally it reads red on a
        ;; correct panel, because two flows both start their seq at `in-0`.
        (is (every? (fn [[_ frags]]
                      (= (count frags)
                         (count (set (map #(:key (second %)) frags)))))
                    (group-by #(second (re-find #"^(.*)/input/"
                                                (fragment-mount-id %)))
                              fragments))
            "and within any ONE row's seq the keys are distinct")))))

(deftest row-keys-ride-the-attribute-map-not-metadata
  (testing "rf2-k97c.3, RULING 2's key sweep — the catalogue ROW key (a
            second, independent key site in this panel) is carried on a
            keyed FRAGMENT'S ATTRIBUTE MAP too.

            It was `^{:key …}` reader metadata on the row's vector literal.
            Reagent's `get-react-key` does read that, so it worked; Fresco's
            codec reads `:key` from an attribute map and reads Clojure
            metadata NOWHERE, so it would have gone inert the moment this
            panel started rendering through a boundary — silently, since a
            lost key degrades into index-based reconciliation rather than
            failing."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.flows/set-registered-flows-override-for-test
         sample-flows])
      (let [tree      (panel-tree)
            rows      (find-by-testid-prefix tree "rf-xray-static-flows-row-")
            fragments (filterv (fn [node]
                                 (and (vector? node)
                                      (= :<> (first node))
                                      (map? (second node))
                                      (contains? (second node) :key)
                                      ;; the ROW fragments, not the input-seq
                                      ;; ones, which wrap a boundary rather
                                      ;; than an `<li>`
                                      (let [child (nth node 2 nil)]
                                        (and (vector? child)
                                             (= :li (first child))))))
                               (hiccup-nodes tree))]
        (is (= 2 (count rows))
            "PRECONDITION: the fixture's two rows rendered — a smaller count
             would make the key claim vacuous")
        (is (= (count rows) (count fragments))
            (str "every row is wrapped in a keyed fragment. Keys seen: "
                 (pr-str (mapv #(:key (second %)) fragments))))
        (is (every? #(some? (:key (second %))) fragments)
            "and each key is non-nil IN THE ATTRIBUTE MAP")
        (is (= (count fragments) (count (set (map #(:key (second %)) fragments))))
            "and the row keys are distinct from one another")))))

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
;; `(rf/registrations :flow)` (→ which now THROWS
;; `:rf.error/registrar-kind-not-queryable`, framework rf2-kuky.30), so the production
;; data source returned an EMPTY catalogue against real flows; after the
;; repoint it reads `rf.flows/flows-snapshot` and surfaces them. Reverting the
;; `registered-flows-value` body back to the registrar read fails the first
;; assertion below (empty catalogue) while every override-based test above
;; stays green — proving the gap this regression closes.

(defn- production-setup-xray!
  "Install the PRODUCTION Static Flows wiring (no override seam) plus the
  two host frames the live-source flows register against."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :flows-test/frame-a :doc "host frame A"})
  (rf/make-frame {:id :flows-test/frame-b :doc "host frame B"}))

(deftest live-source-reads-flows-snapshot-not-empty-registrar-slot
  (testing "rf2-20359j — the PRODUCTION Static Flows data source reads
            `rf.flows/flows-snapshot` (the per-frame flows store) and surfaces
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
            only ever show the last registrant; `rf.flows/flows-snapshot` shows
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
      ;; reads (`flow-meta` with an explicit :frame) agrees, frame-by-frame.
      (is (= derive-a (:derive (rf.flows/flow-meta {:frame :flows-test/frame-a :id :user/full-name})))
          "flow-meta resolves frame A's divergent definition")
      (is (= derive-b (:derive (rf.flows/flow-meta {:frame :flows-test/frame-b :id :user/full-name})))
          "flow-meta resolves frame B's divergent definition"))))
