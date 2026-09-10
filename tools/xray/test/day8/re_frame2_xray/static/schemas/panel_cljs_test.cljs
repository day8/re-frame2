(ns day8.re-frame2-xray.static.schemas.panel-cljs-test
  "CLJS wiring + view tests for the Static Schemas sub-tab
  (rf2-o5f5f.4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.schemas.panel :as panel]
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
  `:rf.xray.static.schemas/tab-data` query the boundary issues — and hands
  the value to `panel/panel-tree`, so every row below asserts on the same
  hiccup it asserted on before.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`. The boundary's OWN behaviour
  — first paint, liveness, frame targeting, evidence isolation, teardown
  and row identity — is `panel_fresco_boundary_dom_cljs_test`'s subject."
  []
  (panel/panel-tree @(rf/subscribe [:rf.xray.static.schemas/tab-data]) nil))

;; ---- fixture data -------------------------------------------------------

(def sample-registry
  {:schemas-by-frame
   {:rf/default
    {[:user]
     {:schema [:map [:id :int] [:name :string]]
      :doc    "user shape"
      :file   "src/user.cljs"
      :line   12
      :ns     'user}}}
   :events
   {:user/login
    {:schema [:tuple :keyword :string]
     :doc    "login event"
     :file   "src/user.cljs"
     :line   42}
    :no-schema/event
    {:doc "no :schema — should not surface"}}
   :subs
   {:user/full-name
    {:schema :string
     :doc    "full name sub"
     :file   "src/user.cljs"
     :line   88}}})

;; -------------------------------------------------------------------------
;; (1) pure helpers
;; -------------------------------------------------------------------------

(deftest project-app-schema-rows-flattens
  (let [rows (panel/project-app-schema-rows (:schemas-by-frame sample-registry))]
    (is (= 1 (count rows)))
    (let [{:keys [kind id frame schema source-coord]} (first rows)]
      (is (= :app-db kind))
      (is (= [:user] id))
      (is (= :rf/default frame))
      (is (= [:map [:id :int] [:name :string]] schema))
      (is (= "src/user.cljs" (:file source-coord))))))

(deftest project-registrar-rows-keeps-schema-bearing-only
  (let [event-rows (panel/project-registrar-rows :event (:events sample-registry))]
    (is (= 1 (count event-rows))
        "entry without :schema is dropped"))
  (let [sub-rows (panel/project-registrar-rows :sub (:subs sample-registry))]
    (is (= 1 (count sub-rows)))
    (is (= :sub (:kind (first sub-rows))))))

(deftest project-rows-combines-and-sorts
  (let [rows (panel/project-rows (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry))]
    (is (= 3 (count rows))
        "app-db + 1 event + 1 sub = 3 rows")))

(deftest filter-rows-substring
  (let [rows (panel/project-rows (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry))]
    (is (= rows (panel/filter-rows rows nil)))
    (is (= 1 (count (panel/filter-rows rows "login"))))
    (is (= 0 (count (panel/filter-rows rows "nope"))))))

(deftest project-data-shape
  ;; nil frame-id = list every frame's app-db schemas (see
  ;; scope-app-schemas-to-frame).
  (let [data (panel/project-data (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry)
                                 nil
                                 nil)]
    (is (= 3 (:total data)))
    (is (false? (:silent? data)))
    (is (true? (:silent? (panel/project-data {} {} {} nil nil))))))

(deftest scope-app-schemas-to-frame-narrows
  (let [multi {:rf/default {[:a] {:schema :int}}
               :rf/cart    {[:b] {:schema :int}}}]
    (testing "nil frame-id passes through verbatim"
      (is (= multi (panel/scope-app-schemas-to-frame multi nil))))
    (testing "a frame-id keeps only that frame's app-db schemas"
      (is (= {:rf/default {[:a] {:schema :int}}}
             (panel/scope-app-schemas-to-frame multi :rf/default))))
    (testing "an absent frame-id yields an empty map"
      (is (= {} (panel/scope-app-schemas-to-frame multi :rf/nope))))))

(deftest project-data-scopes-app-db-schemas-but-not-global-schemas
  (let [multi-by-frame {:rf/default {[:user] {:schema :map}}
                        :rf/cart    {[:cart] {:schema :map}}}
        events         (:events sample-registry)   ;; one schema-bearing event
        subs           (:subs   sample-registry)]  ;; one schema-bearing sub
    (testing "frame :rf/default → its 1 app-db schema + the 2 global schemas"
      (let [data (panel/project-data multi-by-frame events subs :rf/default nil)]
        (is (= 3 (:total data)) "1 app-db (default) + 1 event + 1 sub")
        (is (= 1 (count (filterv #(= :app-db (:kind %)) (:schemas data))))
            "only :rf/default's app-db schema, not :rf/cart's")))
    (testing "frame :rf/cart → its 1 app-db schema + the same 2 global schemas"
      (let [data (panel/project-data multi-by-frame events subs :rf/cart nil)]
        (is (= 3 (:total data)))
        (is (= [[:cart]]
               (mapv :id (filterv #(= :app-db (:kind %)) (:schemas data))))
            "only :rf/cart's app-db schema surfaces")))
    (testing "nil frame-id → both frames' app-db schemas + global schemas"
      (is (= 4 (:total (panel/project-data multi-by-frame events subs nil nil)))))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest install-registers-subs
  (setup-xray!)
  (rf/with-frame :rf/xray
    (is (nil? @(rf/subscribe [:rf.xray.static.schemas/query]))
        "query slot defaults nil")))

(deftest set-query-writes-the-slot
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static.schemas/set-query "user"])
    (is (= "user" @(rf/subscribe [:rf.xray.static.schemas/query])))))

(deftest registry-override-feeds-the-composite
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [data @(rf/subscribe [:rf.xray.static.schemas/tab-data])]
      (is (= 3 (:total data)))
      (is (false? (:silent? data))))))

;; -------------------------------------------------------------------------
;; (2b) THE PRODUCTION PATH — rows that reach the panel through REAL
;;      registrations, with no override seam anywhere (rf2-t8a8)
;; -------------------------------------------------------------------------
;;
;; Every row above this point feeds the panel a SYNTHETIC metadata map through
;; `:rf.xray.static.schemas/set-registry-override-for-test`. That is a hollow
;; gate for the registrar side of this panel in the precise sense the project
;; means it: a synthetic map can carry any key at all, so the whole suite stayed
;; green for the panel's entire life while it read the RETIRED `:spec` key and
;; showed zero event rows and zero sub rows against every real host app. A
;; synthetic map can carry `:spec`; a live `rf/reg-event` CANNOT — the
;; registrar hard-errors on it (`:rf.error/retired-registration-key`, pinned by
;; `re-frame.reg-meta-noswallow-cljs-test/retired-spec-key-hard-errors-per-registrar`).
;;
;; So these two rows take the path production takes. They register through the
;; PUBLIC registrars and read through the PRODUCTION `:rf.xray.static.schemas/
;; registry` sub — the one that assembles its three inputs from the live
;; registries and has no override branch to fall into. Revert `meta-row` to
;; `:spec` and both rows go red on a real absent row, which is the mechanical
;; test this repair had to satisfy: delete the signal, keep the fault, and the
;; row must NOT still pass.

(def ^:private live-event-id ::live-event)
(def ^:private live-sub-id   ::live-sub)
(def ^:private live-bare-id  ::live-event-without-schema)

(defn- setup-xray-production!
  "`setup-xray!` WITHOUT `install-test-overrides!`, so
  `:rf.xray.static.schemas/registry` stays the PRODUCTION sub. Nothing in
  this section can inject a registry value; the only way to move the panel
  is to register something."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- register-live-schemas!
  "Three REAL registrations through the public registrars: an event and a
  sub carrying the canonical `:schema` metadata key, plus one event
  carrying none (the non-vacuity control — the panel must drop it)."
  []
  (rf/reg-event live-event-id
    {:doc "a really-registered event" :schema [:tuple :keyword :string]}
    (fn [{:keys [db]} _] {:db db}))
  (rf/reg-sub live-sub-id
    {:doc "a really-registered sub" :schema :string}
    (fn [db _] (str db)))
  (rf/reg-event live-bare-id
    {:doc "carries no :schema — must not surface"}
    (fn [{:keys [db]} _] {:db db})))

(deftest live-registrations-surface-through-the-production-read
  (testing "rf2-t8a8 — an event and a sub registered through the PUBLIC
            registrars surface as rows in the panel's own composite, read
            through the PRODUCTION registry sub with no override installed"
    (setup-xray-production!)
    (register-live-schemas!)
    (is (= [:tuple :keyword :string]
           (:schema (rf/handler-meta {:source :store :kind :event :id live-event-id})))
        "PRECONDITION: the live registration really stored `:schema` under
         the id the panel will look for — so a missing row below is the
         PANEL failing to read the key, not the registrar failing to keep it")
    (rf/with-frame :rf/xray
      (let [data  @(rf/subscribe [:rf.xray.static.schemas/tab-data])
            by-id (into {} (map (juxt :id identity)) (:schemas data))
            evt   (get by-id live-event-id)
            sub   (get by-id live-sub-id)]
        (is (some? evt)
            (str "the really-registered EVENT is on the catalogue. Ids seen: "
                 (pr-str (mapv :id (:schemas data)))))
        (is (= :event (:kind evt)))
        (is (= [:tuple :keyword :string] (:schema evt))
            "carrying the Malli schema the registration declared")
        (is (= "a really-registered event" (:doc evt)))
        (is (nil? (:frame evt))
            "and cross-frame — the registrar is process-global (Spec 001)")
        (is (some? sub) "the really-registered SUB is on the catalogue too")
        (is (= :sub (:kind sub)))
        (is (= :string (:schema sub)))
        (is (not (contains? by-id live-bare-id))
            "NON-VACUITY: a real registration carrying NO `:schema` is
             dropped, so the two rows above are the key being read and not
             every registration being listed")))))

(deftest live-registration-renders-a-row-surface
  (testing "rf2-t8a8 — and it reaches the SCREEN: the live event's row
            surface is in the hiccup the boundary renders, so the repair is
            end-to-end and not merely a projection that nobody paints"
    (setup-xray-production!)
    (register-live-schemas!)
    (rf/with-frame :rf/xray
      (let [tree (panel-tree)]
        (is (some? (find-by-testid
                     tree
                     (str "rf-xray-static-schemas-row-event-" (pr-str live-event-id))))
            "the live event's row is rendered")
        (is (some? (find-by-testid
                     tree
                     (str "rf-xray-static-schemas-row-sub-" (pr-str live-sub-id))))
            "and the live sub's")
        (is (nil? (find-by-testid tree "rf-xray-static-schemas-empty"))
            "and the panel is NOT showing its empty state — which is exactly
             what it showed against every real registrar before this repair")))))

(def two-frame-registry
  "Two frames each carrying a distinct app-db schema, plus the shared
  process-global event + sub schemas — fixture for the picker-scoping
  regression."
  {:schemas-by-frame
   {:rf/default    {[:user] {:schema [:map [:id :int]]}}
    :rf/cart-frame {[:cart] {:schema [:map [:n :int]]}}}
   :events {:user/login {:schema [:tuple :keyword]}}
   :subs   {:user/full-name {:schema :string}}})

(deftest tab-data-scopes-app-db-schemas-to-picker-frame
  (testing "the L1 frame picker scopes the app-db-schema rows — switching
            the picker frame changes which frame's app-db schemas list,
            while the process-global event + sub schemas stay visible in
            every frame"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         two-frame-registry])
      (testing "picker on :rf/default → its 1 app-db schema + 2 global schemas"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/default])
        (let [data    @(rf/subscribe [:rf.xray.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)) "1 app-db (default) + 1 event + 1 sub")
          (is (= [[:user]] (mapv :id app-dbs))
              "only :rf/default's app-db schema, not :rf/cart-frame's")))
      (testing "picker on :rf/cart-frame → its 1 app-db schema + 2 global schemas"
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
        (let [data    @(rf/subscribe [:rf.xray.static.schemas/tab-data])
              app-dbs (filterv #(= :app-db (:kind %)) (:schemas data))]
          (is (= 3 (:total data)))
          (is (= [[:cart]] (mapv :id app-dbs))
              "only :rf/cart-frame's app-db schema surfaces — NOT the global 2")))
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test nil]))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-empty-state-when-silent
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       {:schemas-by-frame {} :events {} :subs {}}])
    (let [tree (panel-tree)]
      (is (some? (find-by-testid tree "rf-xray-static-schemas-empty"))))))

(deftest panel-renders-rows-from-override
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel-tree)
          rows (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
      (is (= 3 (count rows)) "three row surfaces rendered"))))

(deftest panel-renders-jump-to-source-chips
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    (let [tree (panel-tree)
          chips (find-by-testid-prefix tree "xray-open-in-editor")]
      ;; Every fixture row carries a :file slot, so every row should
      ;; have an open chip resolved through the editor config.
      (is (pos? (count chips))
          "at least one jump-to-source chip rendered"))))

;; -------------------------------------------------------------------------
;; (4) a11y list semantics (rf2-mq8wk)
;; -------------------------------------------------------------------------

(deftest panel-list-carries-list-semantics
  (testing "rf2-mq8wk — the schemas <ul> is role=list, rows role=listitem"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree (panel-tree)
            list-node (find-by-testid tree "rf-xray-static-schemas-list")
            rows (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
        (is (= "list" (:role (second list-node))) "<ul> carries role=list")
        (is (seq rows) "rows rendered")
        (is (every? #(= "listitem" (:role (second %))) rows)
            "every row carries role=listitem")))))

;; -------------------------------------------------------------------------
;; (5) schema EDN renders through the shared widget (rf2-2kwhw)
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

(deftest schema-edn-renders-through-the-widgets-fresco-head
  (testing "rf2-2kwhw + rf2-oqa60 — the Malli schema renders via the
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
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree  (panel-tree)
            heads (inspector-view-forms tree)]
        ;; One schema value per row; the fixture projects three rows.
        (is (= 3 (count heads))
            (str "every row's schema renders through the widget's Fresco "
                 "head. Mount ids: "
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
;; (5a) ONE APP-DB PATH, TWO FRAMES, ONE RENDER FRAME (rf2-uyg0)
;; -------------------------------------------------------------------------
;;
;; THE ROW ABOVE CANNOT SEE THIS. `sample-registry` is one frame's app-db
;; schema plus two process-global rows, so every row differs on `(kind, id)`
;; and the mount ids stay distinct however the node key is built. The browser
;; lane's W5 is no help either — it removes differently named rows from one
;; frame. Neither reaches the case the app-db schema registry explicitly
;; supports: per-frame registration, surfaced all at once by the browse-all
;; projection.

(def one-path-two-frames
  "ONE app-db schema path registered against TWO frames — the fixture
  nothing else in this file reaches.

  `scope-app-schemas-to-frame` with a nil frame-id (the default
  OBSERVED-target state, `defaults/default-target-frame` = UNSELECTED)
  passes every frame's app-db schemas through, so these two registrations
  project to TWO rows inside the ONE `:rf/xray` render frame the panel
  paints in. Each carries its own schema — they are two different
  registrations that happen to share a path.

  No event / sub rows: those are process-global and carry `:frame nil`
  unconditionally, so they cannot exercise a frame qualifier."
  {:schemas-by-frame {:app/a {[:shared] {:schema [:map [:a :int]]}}
                      :app/b {[:shared] {:schema [:map [:b :int]]}}}
   :events {}
   :subs   {}})

(def two-paths-one-frame
  "THE NON-VACUITY CONTROL, taken from the target in the same run rather
  than reasoned about: same row count, same walker, paths that genuinely
  differ. A `row-identity` answering a constant — or a walker finding
  nothing — reads red here while the fixture above would read green."
  {:schemas-by-frame {:app/a {[:one] {:schema [:map [:a :int]]}
                              [:two] {:schema [:map [:b :int]]}}}
   :events {}
   :subs   {}})

(defn- mount-ids-for-override [fixture]
  (rf/dispatch-sync
    [:rf.xray.static.schemas/set-registry-override-for-test fixture])
  (let [data  @(rf/subscribe [:rf.xray.static.schemas/tab-data])
        heads (inspector-view-forms (panel/panel-tree data nil))]
    {:rows      (:schemas data)
     :forms     (count heads)
     :mount-ids (mapv #(:mount-id (second %)) heads)}))

(deftest two-rows-sharing-a-schema-path-across-frames-get-distinct-mount-ids
  (testing "rf2-uyg0 — the inspector node key is qualified by the row's
            OWNING FRAME, so two rows sharing an app-db schema path across
            two frames do not collide on one `:mount-id`.

            The frame was already destructured BY NAME in `schema-row`, one
            line above the key that omitted it.

            WHY A COLLIDING `:mount-id` IS NOT COSMETIC. `edn-widget/
            inspect-view` hands the node key straight to the boundary as its
            `:mount-id` and derives the expansion `:panel-id` from the same
            string, and the Fresco head TRUSTS that id — unlike the Reagent
            head, which mints a UUID per mount and so cannot collide.
            `edn-inspector/container-ref-for` then MEMOISES the ref callback
            on `[render-frame mount-id]`, and the render frame is `:rf/xray`
            for both rows, so a shared node key means one ResizeObserver
            entry for two live mounts: the second never installs an observer,
            and detaching either row calls `release-mount!` for the
            SURVIVOR."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (let [{:keys [rows forms mount-ids]} (mount-ids-for-override
                                             one-path-two-frames)]
        (is (= [[:shared] [:shared]] (mapv :id rows))
            (str "PRECONDITION: two rows sharing ONE app-db path reached the "
                 "tree. Rows: " (pr-str (mapv (juxt :frame :id) rows))))
        (is (= #{:app/a :app/b} (set (map :frame rows)))
            "PRECONDITION: and they carry DIFFERENT owning frames — the
             browse-all projection is what puts both in one catalogue")
        (is (= 2 forms)
            (str "PRECONDITION: one schema value per row, two rows. Mount "
                 "ids: " (pr-str mount-ids)))
        (is (= 2 (count (set mount-ids)))
            (str "THE CLAIM: two mounts, two DISTINCT `:mount-id`s. Without "
                 "the frame in the key both rows are `static-schemas/"
                 "app-db-[:shared]`. Mount ids: " (pr-str mount-ids))))
      (testing "NON-VACUITY CONTROL — distinct paths in ONE frame still separate"
        (let [{:keys [rows forms mount-ids]} (mount-ids-for-override
                                               two-paths-one-frame)]
          (is (= [[:one] [:two]] (mapv :id rows))
              "control fixture projects two genuinely distinct paths")
          (is (= 2 forms) "same shape as the case above")
          (is (= 2 (count (set mount-ids)))
              (str "and they were already distinct — so a red above is the "
                   "frame qualifier, not a broken walker. Mount ids: "
                   (pr-str mount-ids)))))
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test nil]))))

;; -------------------------------------------------------------------------
;; (6) row React keys reach the RENDERER, not just the reader (rf2-k97c.3)
;; -------------------------------------------------------------------------

(deftest row-keys-ride-the-attribute-map-not-metadata
  (testing "rf2-k97c.3, RULING 2's key sweep — each catalogue row's React key
            is carried on a keyed FRAGMENT'S ATTRIBUTE MAP, which is the one
            spelling the shipped renderer reads.

            It was `^{:key …}` reader metadata on the row's vector literal.
            Reagent's `get-react-key` does read that, so it worked; Fresco's
            codec reads `:key` from an attribute map and reads Clojure
            metadata NOWHERE, so it would have gone inert the moment this
            panel started rendering through a boundary — and silently, since
            a lost key does not fail but degrades into index-based
            reconciliation.

            ASSERTING ON THE METADATA HERE WOULD BE A HOLLOW GATE: it would
            pass while React received nothing. The browser lane's W5 closes
            the loop on a real React commit."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         sample-registry])
      (let [tree      (panel-tree)
            fragments (filterv #(and (vector? %) (= :<> (first %))
                                     (map? (second %))
                                     (contains? (second %) :key))
                               (hiccup-nodes tree))
            rows      (find-by-testid-prefix tree "rf-xray-static-schemas-row-")]
        (is (= 3 (count rows))
            "PRECONDITION: the fixture's three rows rendered — a smaller
             count would make the key claim vacuous")
        (is (= (count rows) (count fragments))
            (str "every row is wrapped in a keyed fragment. Keys seen: "
                 (pr-str (mapv #(:key (second %)) fragments))))
        (is (every? #(some? (:key (second %))) fragments)
            "and each key is non-nil IN THE ATTRIBUTE MAP")
        (is (= (count fragments) (count (set (map #(:key (second %)) fragments))))
            "and the row keys are distinct from one another")))))
