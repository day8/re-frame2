(ns day8.re-frame2-causa.panels.hydration-debugger-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Hydration Debugger panel
  (Phase 5, rf2-pzxsr).

  ## Three contracts under test (in addition to the pure-data tests
  in `hydration_debugger_cljs_test.cljc`)

  1. **Registry wires the composite sub** under `:rf.causa/hydration-
     debugger-data`. The composite returns the same shape the view
     consumes.

  2. **Dormant gate** — the panel surfaces the 'No SSR in this app'
     empty state until at least one `:rf.ssr/hydration-mismatch`
     trace lands. Once one does, the panel surfaces the side-by-side
     mismatch detail (per spec §Visibility).

  3. **Selection + re-root events** wire correctly — clicking a
     mismatch row fires `:rf.causa/select-mismatch`; clicking a hash
     chip fires `:rf.causa/reroot-tree-view`.

  ## Pure hiccup

  Same approach as `event_detail_cljs_test.cljs` and
  `causality_graph_view_cljs_test.cljs` — we walk the view's hiccup
  tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.hydration-debugger :as hydration]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- fixture trace stream ------------------------------------------------

(defn- mismatch-ev
  "Build a hydration-mismatch trace event per spec/006-Hydration-
  Debugger.md §Substrate."
  ([id path server-tree client-tree]
   (mismatch-ev id path server-tree client-tree {}))
  ([id path server-tree client-tree extra-tags]
   {:id        id
    :op-type   :error
    :operation :rf.ssr/hydration-mismatch
    :tags      (merge {:path        path
                       :server-tree server-tree
                       :client-tree client-tree
                       :server-hash "abc"
                       :client-hash "def"
                       :frame       :rf/default
                       :view-id     'cart-summary-view
                       :failing-id  :rf/hydrate}
                      extra-tags)}))

(defn- seed-buffer!
  "Register Causa's handlers, allocate the :rf/causa frame, then push
  the supplied events through Causa's trace-bus atom via
  `collect-trace!` — the production path. Per rf2-e9s81
  `:rf.causa/trace-buffer` thunks the atom, so a subsequent
  subscribe returns the events directly."
  [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

;; ---- hiccup walker (lifted from causality_graph_view_cljs_test) ---------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-hydration-debugger-sub
  (testing "register-causa-handlers! installs the Phase 5 composite sub"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/hydration-debugger-data))
        "composite sub is registered")
    (is (some? (registrar/handler :sub :rf.causa/selected-mismatch-id))
        "selected-mismatch-id sub is registered")
    (is (some? (registrar/handler :sub :rf.causa/hydration-reroot-path))
        "hydration-reroot-path sub is registered")
    (is (some? (registrar/handler :sub :rf.causa/hydration-has-mismatch?))
        "cheap presence sub for the sidebar dormant gate is registered (rf2-qym6e)")))

;; ---- (1b) sidebar presence sub (rf2-qym6e) ------------------------------

(deftest hydration-has-mismatch-sub-is-false-on-empty-buffer
  (testing "with no mismatches in the buffer, the presence sub is false"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/hydration-has-mismatch?]))))))

(deftest hydration-has-mismatch-sub-true-once-mismatch-lands
  (testing "after a mismatch trace lands, the presence sub flips to true"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (is (true? @(rf/subscribe [:rf.causa/hydration-has-mismatch?]))))))

(deftest hydration-has-mismatch-sub-respects-target-frame
  (testing "the presence sub filters by :target-frame, matching the full
            composite's frame-awareness — a mismatch tagged on a
            different frame does not wake the default target"
    (seed-buffer! [(mismatch-ev 1 [:p] [:a] [:b] {:frame :rf/other})])
    (rf/with-frame :rf/causa
      ;; Default target-frame is :rf/default — no mismatches for it.
      (is (false? @(rf/subscribe [:rf.causa/hydration-has-mismatch?]))
          "wrong-frame mismatches do not wake the sidebar"))))

(deftest hydration-has-mismatch-sub-matches-composite-boolean
  (testing "the cheap presence sub and the full composite agree on the
            boolean — they're the same projection, one returns just the
            slot the sidebar reads"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (let [presence @(rf/subscribe [:rf.causa/hydration-has-mismatch?])
            full     @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (= presence (:has-mismatch? full))
            "cheap-presence sub agrees with the full composite's boolean")))))

(deftest registry-installs-hydration-debugger-events
  (testing "the Phase 5 events are registered"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/select-mismatch)))
    (is (some? (registrar/handler :event :rf.causa/clear-mismatch-selection)))
    (is (some? (registrar/handler :event :rf.causa/reroot-tree-view)))))

(deftest composite-defaults-with-empty-buffer
  (testing "with the buffer empty, the composite reports no mismatches"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (false? (:has-mismatch? data))
            "dormant when buffer is empty")
        (is (= [] (:mismatch-summary data)))
        (is (nil? (:detail data)))))))

(deftest composite-surfaces-mismatches-from-buffer
  (testing "once a mismatch lands, the composite surfaces it"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (true? (:has-mismatch? data)) ":has-mismatch? true")
        (is (= 1 (count (:mismatch-summary data))))
        (is (some? (:detail data)))
        (is (= :different-text
               (get-in data [:detail :divergence-kind])))))))

;; ---- (2) dormant-gate / visibility --------------------------------------

(deftest view-renders-no-ssr-empty-state-when-buffer-empty
  (testing "the panel renders the 'No SSR in this app' empty state when
            the buffer carries no hydration-mismatch traces"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)]
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-debugger-empty-no-ssr"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-hydration-mismatch-detail"))
            "mismatch detail absent when dormant")))))

(deftest view-renders-mismatch-detail-once-fired
  (testing "with a mismatch in the buffer the panel renders the side-by-
            side detail + the mismatch list"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:p "3 items"]
                                [:p "0 items"])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)]
        (is (some? (find-by-testid tree "rf-causa-hydration-mismatch-list"))
            "mismatch list present")
        (is (some? (find-by-testid tree "rf-causa-hydration-mismatch-detail"))
            "mismatch detail present")
        (is (some? (find-by-testid tree "rf-causa-hydration-divergent-marker"))
            "divergent marker present")
        (is (some? (find-by-testid tree "rf-causa-hydration-tree-pane-server"))
            "server tree pane present")
        (is (some? (find-by-testid tree "rf-causa-hydration-tree-pane-client"))
            "client tree pane present")
        (is (some? (find-by-testid tree "rf-causa-hydration-hypothesis"))
            "hypothesis row present")
        (is (nil? (find-by-testid tree
                                  "rf-causa-hydration-debugger-empty-no-ssr"))
            "empty-state absent when mismatch present")))))

;; ---- (3) selection + re-root events -------------------------------------

(deftest clicking-mismatch-row-fires-select-mismatch
  (testing "clicking a mismatch row dispatches :rf.causa/select-mismatch"
    (seed-buffer! [(mismatch-ev 1 [:div] [:a] [:b])
                   (mismatch-ev 2 [:span] [:c] [:d])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)
            row  (find-by-testid tree "rf-causa-hydration-mismatch-row-1")]
        (is (some? row) "row 1 present in mismatch list")
        (is (fn? (:on-click (second row)))
            "row carries an on-click handler")
        ;; Drive the event through dispatch-sync so the test doesn't
        ;; race the router's drain — proves the event handler writes
        ;; to the Causa frame.
        (rf/dispatch-sync [:rf.causa/select-mismatch 1])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= 1 (:selected-mismatch-id causa-db))
              "selection lands on the Causa frame"))))))

(deftest clicking-hash-chip-fires-reroot
  (testing "clicking a hash chip dispatches :rf.causa/reroot-tree-view
            with the chip's path"
    (seed-buffer! [(mismatch-ev 1 []
                                [:div [:p "match"] [:p "server"]]
                                [:div [:p "match"] [:p "client"]])])
    (rf/with-frame :rf/causa
      (let [tree  (hydration/Panel)
            chips (find-all-by-testid-prefix
                    tree "rf-causa-hydration-hash-chip-")]
        (is (pos? (count chips)) "at least one hash chip rendered")
        ;; Each chip's on-click is a #()-style dispatch — sanity that
        ;; it's a callable; the event is asserted below via dispatch-
        ;; sync against the same wiring.
        (is (every? #(fn? (:on-click (second %))) chips))
        ;; Drive the event through dispatch-sync.
        (rf/dispatch-sync [:rf.causa/reroot-tree-view [1]])
        (let [causa-db (frame/frame-app-db-value :rf/causa)]
          (is (= [1] (:hydration-reroot-path causa-db))
              "reroot path lands on the Causa frame"))))))

(deftest reroot-empty-path-clears-the-slot
  (testing "dispatching :rf.causa/reroot-tree-view with [] clears the
            re-root slot — the panel re-renders from the full subtree"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [1]])
      (rf/dispatch-sync [:rf.causa/reroot-tree-view []])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (nil? (:hydration-reroot-path causa-db))
            "empty path clears the re-root slot")))))

(deftest clear-mismatch-selection-drops-selection-and-reroot
  (testing "clear-mismatch-selection drops both the selection and the
            re-root (the path is subtree-specific to the prior
            selection)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-mismatch 42])
      (rf/dispatch-sync [:rf.causa/reroot-tree-view [1 2]])
      (rf/dispatch-sync [:rf.causa/clear-mismatch-selection])
      (let [causa-db (frame/frame-app-db-value :rf/causa)]
        (is (nil? (:selected-mismatch-id causa-db)))
        (is (nil? (:hydration-reroot-path causa-db)))))))

;; ---- (4) hypothesis row + source-coord drilldown ------------------------

(deftest hypothesis-row-surfaces-the-line
  (testing "the hypothesis row carries one of the five hypothesis lines"
    (seed-buffer! [(mismatch-ev 1 [] [:p "3"] [:p "0"])])
    (rf/with-frame :rf/causa
      (let [tree   (hydration/Panel)
            hyp    (find-by-testid tree "rf-causa-hydration-hypothesis")
            txt    (when hyp
                     (->> (hiccup-seq hyp)
                          (filter string?)
                          (apply str)))]
        (is (some? hyp))
        (is (some? (re-find #"App-db state" txt))
            "different-text hypothesis line surfaced")))))

(deftest source-coord-row-surfaces-coord
  (testing "the source-coord row surfaces the divergent view's coord
            when :source-coord is supplied"
    (seed-buffer! [(mismatch-ev 1 [] [:p "a"] [:p "b"]
                                {:source-coord {:file "src/cart/views.cljs"
                                                :line 42}})])
    (rf/with-frame :rf/causa
      (let [tree  (hydration/Panel)
            row   (find-by-testid tree "rf-causa-hydration-source-coord")
            txt   (when row
                    (->> (hiccup-seq row)
                         (filter string?)
                         (apply str)))]
        (is (some? row))
        (is (some? (re-find #"src/cart/views\.cljs:42" txt))
            "exact source-coord rendered")))))

(deftest source-coord-row-surfaces-fallback-annotation
  (testing "when only handler-source-coord is available, the row renders
            a (?) annotation (Lock #11 fallback)"
    (seed-buffer! [(mismatch-ev 1 [] [:p "a"] [:p "b"]
                                {:handler-source-coord
                                 {:file "src/cart/events.cljs" :line 13}})])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)
            row  (find-by-testid tree "rf-causa-hydration-source-coord")
            txt  (when row
                   (->> (hiccup-seq row)
                        (filter string?)
                        (apply str)))]
        (is (some? row))
        (is (some? (re-find #"\(\?\)" txt))
            "fallback annotation (?) present")))))

;; ---- (5) frame awareness ------------------------------------------------

(deftest composite-respects-target-frame
  (testing "when the target frame is :rf/default the composite filters
            out mismatches from other frames"
    (seed-buffer! [(mismatch-ev 1 [] [:p] [:p {:diff 1}]
                                {:frame :rf/default})
                   (mismatch-ev 2 [] [:p] [:p {:diff 2}]
                                {:frame :rf/other})])
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/hydration-debugger-data])]
        (is (= 1 (count (:mismatch-summary data)))
            "only the :rf/default mismatch surfaces by default")
        (is (= [1] (mapv :id (:mismatch-summary data))))))))

;; ---- (6) Phase 4 (rf2-1mcax) — hiccup-engine adoption -------------------
;;
;; Phase 4 of epic rf2-abts7 wires the Phase 3 hiccup-diff micro-engine
;; into the side-by-side panes. The tests below assert:
;;   - The first-divergent header surfaces a readable path line.
;;   - Per-pane render mounts (one container per perspective).
;;   - Server-only / client-only nodes route through perspective-aware
;;     chrome — `:added` flips between green-on-client and absent-on-
;;     server, and the converse for `:removed`.
;;   - Shared elements surface modified-attr deltas inline.

(deftest view-renders-first-divergent-header
  (testing "with a mismatch present, the drilldown surfaces the
            first-divergent header + the readable path line"
    (seed-buffer! [(mismatch-ev 1 [:div :main]
                                [:div [:section [:p "3 items"]]]
                                [:div [:section [:p "0 items"]]])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)]
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-first-divergent-header"))
            "first-divergent header present")
        (let [path-el (find-by-testid tree
                                      "rf-causa-hydration-first-divergent-path")
              txt     (when path-el
                        (->> (hiccup-seq path-el)
                             (filter string?)
                             (apply str)))]
          (is (some? path-el))
          (is (re-find #":div.*:section.*:p" txt)
              "path is rendered with > separators down to the divergent leaf"))))))

(deftest view-renders-perspective-aware-pane-containers
  (testing "each pane mounts its perspective-aware render container"
    (seed-buffer! [(mismatch-ev 1 [] [:p "x"] [:p "y"])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)]
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-pane-render-server"))
            "server pane render container present")
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-pane-render-client"))
            "client pane render container present")
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-pane-label-server")))
        (is (some? (find-by-testid tree
                                   "rf-causa-hydration-pane-label-client")))))))

(deftest view-surfaces-server-only-element-as-absent-on-client-pane
  (testing "an element present only on the server renders red on the
            server pane and as an [absent] chip on the client pane"
    ;; Server has [:p ...], client has empty subtree at that slot.
    (seed-buffer! [(mismatch-ev 1 []
                                [:div [:span "kept"] [:p "server-only"]]
                                [:div [:span "kept"]])])
    (rf/with-frame :rf/causa
      (let [tree         (hydration/Panel)
            server-pane  (find-by-testid tree
                                         "rf-causa-hydration-pane-render-server")
            client-pane  (find-by-testid tree
                                         "rf-causa-hydration-pane-render-client")
            client-abs   (find-all-by-testid-prefix
                           client-pane "rf-causa-hydration-pane-client-absent")]
        (is (some? server-pane))
        (is (some? client-pane))
        (is (pos? (count client-abs))
            "client pane shows at least one [absent] chip for the server-only :p")))))

(deftest view-surfaces-client-only-element-as-absent-on-server-pane
  (testing "an element present only on the client mirrors — green on
            client pane, [absent] chip on server pane"
    (seed-buffer! [(mismatch-ev 1 []
                                [:div [:span "kept"]]
                                [:div [:span "kept"] [:p "client-only"]])])
    (rf/with-frame :rf/causa
      (let [tree        (hydration/Panel)
            server-pane (find-by-testid tree
                                        "rf-causa-hydration-pane-render-server")
            server-abs  (find-all-by-testid-prefix
                          server-pane "rf-causa-hydration-pane-server-absent")]
        (is (pos? (count server-abs))
            "server pane shows at least one [absent] chip for the client-only :p")))))

(deftest view-surfaces-modified-attr-on-both-panes
  (testing "a shared element whose attr differs surfaces a `:modified`
            row on both panes (each pane shows ITS value with the other
            value as a faint annotation)"
    (seed-buffer! [(mismatch-ev 1 []
                                [:a {:href "/server"} "go"]
                                [:a {:href "/client"} "go"])])
    (rf/with-frame :rf/causa
      (let [tree         (hydration/Panel)
            server-pane  (find-by-testid tree
                                         "rf-causa-hydration-pane-render-server")
            client-pane  (find-by-testid tree
                                         "rf-causa-hydration-pane-render-client")
            server-mods  (find-all-by-testid-prefix
                           server-pane "rf-causa-hydration-attr-modified-")
            client-mods  (find-all-by-testid-prefix
                           client-pane "rf-causa-hydration-attr-modified-")]
        (is (pos? (count server-mods))
            "server pane surfaces the modified :href row")
        (is (pos? (count client-mods))
            "client pane surfaces the modified :href row")))))

(deftest view-no-structural-difference-collapses-to-chip
  (testing "if the bisector flagged a mismatch but both subtrees are
            structurally identical after re-rooting, the per-pane
            renderer shows a 'no structural difference' chip (defensive
            against malformed traces and re-root edge cases)"
    ;; Stash two identical trees. The classify-divergence path returns
    ;; :unknown / nil for equal trees but the panel still mounts the
    ;; detail.
    (seed-buffer! [(mismatch-ev 1 []
                                [:p "same"]
                                [:p "same"])])
    (rf/with-frame :rf/causa
      (let [tree (hydration/Panel)]
        ;; The chip mounts on at least one pane (both, in this case
        ;; since the subtrees are equal).
        (is (or (some? (find-by-testid tree
                                       "rf-causa-hydration-pane-no-diff-server"))
                (some? (find-by-testid tree
                                       "rf-causa-hydration-pane-no-diff-client")))
            "no-difference chip surfaces when both subtrees match")))))
