(ns day8.re-frame2-xray.static.schemas.panel-cljs-test
  "CLJS wiring + view tests for the Static Schemas sub-tab."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.schemas.panel :as panel]
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

(defn- find-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (some-> (:data-testid (second node))
                          (.startsWith prefix))))
           (hiccup-nodes tree)))

(defn- inspector-view-forms
  "Every `[ei/edn-inspector-view {…}]` form in the tree — the widget's
  FRESCO head, left unexpanded (see the walker note above)."
  [tree]
  (filterv #(and (vector? %) (= ei/edn-inspector-view (first %)))
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
  `:rf.xray.static.schemas/tab-data` query the boundary issues — and hands
  the value to `panel/panel-tree`, so every row below asserts on the
  hiccup the boundary renders.

  The dispatcher is nil: no row here types into the search box, and the
  search box only calls it from `:on-change`."
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
  (is (= [{:kind         :app-db
           :id           [:user]
           :frame        :rf/default
           :schema       [:map [:id :int] [:name :string]]
           :doc          "user shape"
           :source-coord {:file "src/user.cljs" :line 12 :ns 'user}}]
         (panel/project-app-schema-rows (:schemas-by-frame sample-registry)))))

(deftest filter-rows-substring
  (let [rows (panel/project-rows (:schemas-by-frame sample-registry)
                                 (:events sample-registry)
                                 (:subs   sample-registry))]
    (is (= [:user/login] (mapv :id (panel/filter-rows rows "login"))))))

(deftest project-data-shape
  ;; nil frame-id = list every frame's app-db schemas (see
  ;; scope-app-schemas-to-frame). `:no-schema/event` carries no `:schema`.
  (is (= [3 false]
         ((juxt :total :silent?)
          (panel/project-data (:schemas-by-frame sample-registry)
                              (:events sample-registry)
                              (:subs   sample-registry)
                              nil
                              nil))))
  (is (true? (:silent? (panel/project-data {} {} {} nil nil)))))

;; -------------------------------------------------------------------------
;; (2) registry wiring
;; -------------------------------------------------------------------------

(deftest set-query-writes-the-slot
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray.static.schemas/set-query "user"])
    (is (= "user" @(rf/subscribe [:rf.xray.static.schemas/query])))))

;; -------------------------------------------------------------------------
;; (2b) THE PRODUCTION PATH — rows that reach the panel through REAL
;;      registrations, with no override seam anywhere
;; -------------------------------------------------------------------------
;;
;; A synthetic metadata map fed through the override seam can carry any key
;; at all, so a panel reading the wrong metadata key — `:spec` rather than
;; `:schema` — would show zero event rows and zero sub rows against every
;; real host app while every override-based row stayed green. A live
;; `rf/reg-event` CANNOT carry `:spec`: the registrar hard-errors on it
;; (`:rf.error/retired-registration-key`, pinned by
;; `re-frame.reg-meta-noswallow-cljs-test/retired-spec-key-hard-errors-per-registrar`).
;;
;; So this row registers through the PUBLIC registrars and reads through the
;; PRODUCTION `:rf.xray.static.schemas/registry` sub, which has no override
;; branch to fall into.

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
  (testing "an event and a sub registered through the PUBLIC
            registrars surface as rows in the panel's own composite, read
            through the PRODUCTION registry sub with no override installed"
    (setup-xray-production!)
    (register-live-schemas!)
    (rf/with-frame :rf/xray
      (let [by-id (into {} (map (juxt :id identity))
                        (:schemas @(rf/subscribe [:rf.xray.static.schemas/tab-data])))]
        (is (= {:kind   :event
                :frame  nil
                :schema [:tuple :keyword :string]
                :doc    "a really-registered event"}
               (select-keys (get by-id live-event-id) [:kind :frame :schema :doc]))
            (str "the really-registered EVENT is a cross-frame row — the "
                 "registrar is process-global (Spec 001). Ids seen: "
                 (pr-str (keys by-id))))
        (is (= {:kind :sub :schema :string}
               (select-keys (get by-id live-sub-id) [:kind :schema]))
            "the really-registered SUB is on the catalogue too")
        (is (not (contains? by-id live-bare-id))
            "NON-VACUITY: a real registration carrying NO `:schema` is
             dropped, so the two rows above are the key being read and not
             every registration being listed")))))

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
  (testing "the L1 frame picker scopes the app-db-schema rows, while the
            process-global event + sub schemas stay visible in every frame"
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         two-frame-registry])
      (let [total-and-app-db-ids
            (fn []
              (let [data @(rf/subscribe [:rf.xray.static.schemas/tab-data])]
                [(:total data)
                 (mapv :id (filter #(= :app-db (:kind %)) (:schemas data)))]))]
        (rf/dispatch-sync [:rf.xray/select-frame :rf/default])
        (is (= [3 [[:user]]] (total-and-app-db-ids))
            "1 app-db (:rf/default's) + 1 event + 1 sub")
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
        (is (= [3 [[:cart]]] (total-and-app-db-ids))
            "1 app-db (:rf/cart-frame's) + 1 event + 1 sub")))))

;; -------------------------------------------------------------------------
;; (3) view rendering
;; -------------------------------------------------------------------------

(deftest panel-renders-jump-to-source-chips
  (setup-xray!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.static.schemas/set-registry-override-for-test
       sample-registry])
    ;; Every fixture row carries a :file slot, so every row renders one chip.
    (is (= [1 1 1]
           (mapv #(count (find-by-testid-prefix % "xray-open-in-editor"))
                 (find-by-testid-prefix (panel-tree) "rf-xray-static-schemas-row-")))
        "each of the three rows renders exactly one jump-to-source chip")))

;; -------------------------------------------------------------------------
;; (4) ONE APP-DB PATH, TWO FRAMES, ONE RENDER FRAME
;; -------------------------------------------------------------------------
;;
;; App-db schemas are registered per frame, and the browse-all projection
;; lists every frame's at once, so one path can reach the tree twice inside
;; the one `:rf/xray` render frame. Event / sub rows are process-global and
;; carry `:frame nil`, so they cannot exercise a frame qualifier.

(def one-path-two-frames
  "ONE app-db schema path registered against TWO frames, each with its own
  schema. `scope-app-schemas-to-frame` with a nil frame-id (the default,
  unselected observed frame) passes both through, so they project to TWO
  rows."
  {:schemas-by-frame {:app/a {[:shared] {:schema [:map [:a :int]]}}
                      :app/b {[:shared] {:schema [:map [:b :int]]}}}
   :events {}
   :subs   {}})

(deftest two-rows-sharing-a-schema-path-across-frames-get-distinct-mount-ids
  (testing "the inspector node key carries the row's OWNING FRAME, so two rows
            sharing an app-db schema path across frames do not share a
            `:mount-id`. `edn-inspector/container-ref-for` memoises its ref
            callback on `[render-frame mount-id]`, so a shared id would give
            two live mounts one ResizeObserver entry, and detaching either row
            would release the survivor's."
    (setup-xray!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.static.schemas/set-registry-override-for-test
         one-path-two-frames])
      (let [mount-ids (mapv #(:mount-id (second %)) (inspector-view-forms (panel-tree)))]
        (is (= 2 (count mount-ids) (count (set mount-ids)))
            (str "one schema mount per row, two rows, two distinct ids. Mount "
                 "ids: " (pr-str mount-ids)))))))
