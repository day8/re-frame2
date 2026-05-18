(ns day8.re-frame2-causa.panels.machine-inspector-cluster-cljs-test
  "CLJS-side wiring + view tests for Causa's Machine Inspector UC2
  Mode C cluster view (rf2-juon8, Phase 3).

  Tests live under the `:node-test` build via the `cljs-test$` regex.
  Walks the rendered hiccup tree by `data-testid` rather than mounting
  to the DOM — same approach as `machine_inspector_view_cljs_test.cljs`.

  ## What's under test

    1. Registry wires the Mode C sub + event family.
    2. Mode C activation threshold — >10 instances suggests Mode C; the
       user can force any mode.
    3. ClusterView renders cluster rows with count badges + sparkline.
    4. Cluster row click toggles inline expansion; second click collapses.
    5. Shift+click on an instance accumulates the selection-set.
    6. Compare-table renders when ≥2 instances are selected; cells
       diff against each other."
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
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machine-inspector-cluster
             :as cluster]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (same pattern as machine_inspector_view_cljs_test) --

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (let [expanded (expand-fn-component tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

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

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-snapshots! [snapshots]
  (rf/dispatch-sync
    [:rf.causa/set-machine-snapshots-override-for-test snapshots]))

(defn- override-instances! [instances]
  (rf/dispatch-sync
    [:rf.causa/set-machine-instances-override-for-test instances]))

(defn- select-machine! [machine-id]
  (rf/dispatch-sync [:rf.causa/select-machine-id machine-id]))

(defn- gen-instances
  "Synthesise a vector of `n` instances for a fixture machine, spread
  across the supplied states + users so cluster-by-state /
  cluster-by-context-key both produce multi-cluster fixtures."
  [machine-id n]
  (let [states [:idle :authing :sending :done]
        users  ["ada" "bob" "cat"]]
    (mapv (fn [i]
            {:instance-id    (keyword (str "inst-" i))
             :machine-id     machine-id
             :state          (nth states (mod i (count states)))
             :context        {:user (nth users (mod i (count users)))
                              :counter i}
             :parent-machine nil})
          (range n))))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-mode-c-handlers
  (testing "register-causa-handlers! installs every Mode C handler"
    (registry/register-causa-handlers!)
    ;; subs
    (is (some? (registrar/handler :sub :rf.causa/mode-c-cluster-by)))
    (is (some? (registrar/handler :sub :rf.causa/mode-c-context-key)))
    (is (some? (registrar/handler :sub :rf.causa/mode-c-expanded)))
    (is (some? (registrar/handler :sub :rf.causa/mode-c-selection)))
    (is (some? (registrar/handler :sub :rf.causa/machine-instances)))
    (is (some? (registrar/handler :sub :rf.causa/machine-instances-override)))
    (is (some? (registrar/handler :sub :rf.causa/mode-c-clusters)))
    (is (some? (registrar/handler :sub :rf.causa/mode-c-compare-table)))
    (is (some? (registrar/handler :sub :rf.causa/forced-machine-mode)))
    ;; events
    (is (some? (registrar/handler :event :rf.causa/set-mode-c-cluster-by)))
    (is (some? (registrar/handler :event :rf.causa/set-mode-c-context-key)))
    (is (some? (registrar/handler :event :rf.causa/toggle-mode-c-cluster-expanded)))
    (is (some? (registrar/handler :event :rf.causa/toggle-mode-c-selection)))
    (is (some? (registrar/handler :event :rf.causa/clear-mode-c-selection)))
    (is (some? (registrar/handler :event :rf.causa/set-forced-machine-mode)))
    (is (some? (registrar/handler :event :rf.causa/set-machine-instances-override-for-test)))))

;; ---- (2) Mode C activation threshold ------------------------------------

(deftest resolve-mode-defaults-to-focused-event-lens
  (testing "per rf2-a9cke the panel default is the focused-event lens —
            with no forced override, resolve-mode returns :focused-event
            regardless of instance count. The picker-driven Mode A/B/C
            classifier remains available via `view-mode` for callers
            that want to hint at the auto pick."
    (is (= :focused-event (machine-inspector/resolve-mode 0   nil)))
    (is (= :focused-event (machine-inspector/resolve-mode 1   nil)))
    (is (= :focused-event (machine-inspector/resolve-mode 100 nil)))))

(deftest resolve-mode-honours-user-force
  (testing "with a forced override, resolve-mode returns it directly"
    (is (= :mode-c (machine-inspector/resolve-mode 0 :mode-c)))
    (is (= :mode-a (machine-inspector/resolve-mode 100 :mode-a)))))

(deftest set-forced-machine-mode-roundtrips
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (is (= :mode-c @(rf/subscribe [:rf.causa/forced-machine-mode])))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode nil])
    (is (nil? @(rf/subscribe [:rf.causa/forced-machine-mode])))))

(deftest set-forced-machine-mode-rejects-unknown-mode
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :bogus])
    (is (nil? @(rf/subscribe [:rf.causa/forced-machine-mode]))
        "an unknown mode is rejected, slot stays nil")))

(deftest mode-strip-renders-when-machines-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:auth/login])
    (let [tree (machine-inspector/Panel)
          strip (find-by-testid tree "rf-causa-machine-inspector-mode-strip")]
      (is (some? strip) "mode-strip is present whenever the panel renders content")
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-mode-tab-mode-a")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-mode-tab-mode-b")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-mode-tab-mode-c"))))))

(deftest mode-c-auto-suggested-when-instance-count-exceeds-threshold
  (testing "with >10 instances overridden the mode strip surfaces the
            mode-c suggestion attr"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:req/protocol])
      (select-machine! :req/protocol)
      (override-instances! (gen-instances :req/protocol 11))
      (let [tree (machine-inspector/Panel)
            strip (find-by-testid tree "rf-causa-machine-inspector-mode-strip")]
        (is (= "true" (:data-suggest-mode-c (second strip)))
            "the strip surfaces the suggestion attr when instances > 10")))))

(deftest mode-c-suggest-text-renders-when-not-already-in-mode-c
  (testing "with >10 instances + a forced :mode-b override, the inline
            mode-c suggestion text appears (the auto-resolve would already
            be mode-c which suppresses the nudge)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:req/protocol])
      (select-machine! :req/protocol)
      (override-instances! (gen-instances :req/protocol 11))
      (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-b])
      (let [tree (machine-inspector/Panel)]
        (is (some? (find-by-testid
                     tree "rf-causa-machine-inspector-mode-suggest"))
            "the inline suggestion text renders when the resolved mode != mode-c")))))

(deftest mode-c-not-suggested-at-or-below-threshold
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 10))
    (let [tree (machine-inspector/Panel)
          strip (find-by-testid tree "rf-causa-machine-inspector-mode-strip")]
      (is (= "false" (:data-suggest-mode-c (second strip)))
          "threshold is exclusive — exactly 10 instances stays in Mode B"))))

(deftest mode-c-renders-cluster-view-when-forced
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (let [tree (machine-inspector/Panel)
          root (find-by-testid tree "rf-causa-machine-inspector")]
      (is (= "mode-c" (:data-view-mode (second root))))
      (is (some? (find-by-testid tree "rf-causa-mode-c"))
          "cluster view mounts when mode resolves to :mode-c")
      (is (some? (find-by-testid tree "rf-causa-mode-c-cluster-list"))))))

;; ---- (3) Cluster rows ---------------------------------------------------

(deftest cluster-rows-render-with-count-badges-and-sparkline
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (let [tree    (machine-inspector/Panel)
          rows    (find-all-by-testid-prefix
                    tree "rf-causa-mode-c-cluster-")
          ;; subset: rows specifically prefixed `rf-causa-mode-c-cluster-:`
          ;; corresponds to actual cluster `<li>` items (state keywords).
          li-rows (filter
                    (fn [node]
                      (some-> (:data-testid (second node))
                              (.startsWith "rf-causa-mode-c-cluster-:")))
                    rows)]
      ;; gen-instances spreads across :idle :authing :sending :done (4)
      (is (= 4 (count li-rows)) "one row per distinct state")
      ;; Each row should have a count badge somewhere in its subtree
      (is (some? (find-by-testid tree "rf-causa-mode-c-cluster-count-:idle")))
      (is (some? (find-by-testid tree "rf-causa-mode-c-cluster-count-:authing")))
      ;; And a sparkline glyph
      (is (some? (find-by-testid tree "rf-causa-mode-c-cluster-sparkline-:idle"))))))

;; ---- (4) Cluster expansion toggle --------------------------------------

(deftest toggle-cluster-expanded-event-roundtrips
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :authing])
    (is (= #{:authing} @(rf/subscribe [:rf.causa/mode-c-expanded])))
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :authing])
    (is (= #{} @(rf/subscribe [:rf.causa/mode-c-expanded])))))

(deftest cluster-row-click-dispatches-toggle-expanded
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (let [tree   (machine-inspector/Panel)
              row    (find-by-testid tree "rf-causa-mode-c-cluster-:idle")
              ;; The clickable element is the inner :div wrapper. Walk
              ;; into the row's body and pick the first node with an
              ;; :on-click handler.
              clickables (filter (fn [n]
                                   (and (vector? n)
                                        (map? (second n))
                                        (:on-click (second n))))
                                 (hiccup-seq row))
              handler   (some-> clickables first second :on-click)]
          (is (some? row) "cluster row is present")
          (is (some? handler) "row carries an :on-click handler")
          (when handler (handler #js {}))
          (is (some #(= [:rf.causa/toggle-mode-c-cluster-expanded :idle] %)
                    @dispatches)
              "clicking the row dispatches :toggle-mode-c-cluster-expanded"))))))

(deftest expanded-cluster-shows-instance-list
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :idle])
    (let [tree     (machine-inspector/Panel)
          expand   (find-by-testid tree "rf-causa-mode-c-cluster-expansion-:idle")
          ;; Instances under :idle: with 12 instances spread across 4
          ;; states (idle, authing, sending, done) by `mod 4`, :idle gets
          ;; indices 0, 4, 8 → 3 instances.
          insts    (find-all-by-testid-prefix tree "rf-causa-mode-c-instance-")]
      (is (some? expand) "expansion <ul> is present")
      (is (= 3 (count insts))
          "three :idle instances visible (indices 0, 4, 8)"))))

;; ---- (5) Shift+click multi-select --------------------------------------

(deftest toggle-mode-c-selection-event-roundtrips
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-1])
    (is (= #{:inst-1} @(rf/subscribe [:rf.causa/mode-c-selection])))
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-2])
    (is (= #{:inst-1 :inst-2} @(rf/subscribe [:rf.causa/mode-c-selection])))
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-1])
    (is (= #{:inst-2} @(rf/subscribe [:rf.causa/mode-c-selection])))))

(deftest clear-mode-c-selection-event-clears-the-set
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-1])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-2])
    (rf/dispatch-sync [:rf.causa/clear-mode-c-selection])
    (is (= #{} @(rf/subscribe [:rf.causa/mode-c-selection])))))

(deftest shift-click-instance-dispatches-toggle-selection
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :idle])
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (let [tree    (machine-inspector/Panel)
              inst-li (find-by-testid tree "rf-causa-mode-c-instance-:inst-0")
              handler (:on-click (second inst-li))
              ;; Fake a React synthetic event with shiftKey true; the
              ;; handler reads `(.-shiftKey e)` + `(.preventDefault e)`.
              fake-ev #js {:shiftKey true
                           :preventDefault (fn [] nil)}]
          (is (some? inst-li) "instance <li> is present")
          (is (some? handler))
          (when handler (handler fake-ev))
          (is (some #(= [:rf.causa/toggle-mode-c-selection :inst-0] %)
                    @dispatches)
              "shift+click dispatches :toggle-mode-c-selection"))))))

(deftest plain-click-on-instance-does-not-dispatch
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :idle])
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (let [tree    (machine-inspector/Panel)
              inst-li (find-by-testid tree "rf-causa-mode-c-instance-:inst-0")
              handler (:on-click (second inst-li))
              fake-ev #js {:shiftKey false
                           :preventDefault (fn [] nil)}]
          (when handler (handler fake-ev))
          (is (not (some #(= :rf.causa/toggle-mode-c-selection (first %))
                         @dispatches))
              "plain click is intentionally a no-op for selection"))))))

;; ---- (6) Compare table --------------------------------------------------

(deftest compare-table-sub-returns-nil-when-fewer-than-two
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (is (nil? @(rf/subscribe [:rf.causa/mode-c-compare-table])))
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-0])
    (is (nil? @(rf/subscribe [:rf.causa/mode-c-compare-table]))
        "still nil with one selected")))

(deftest compare-table-renders-when-two-instances-selected
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-0])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-4])
    (let [tree    (machine-inspector/Panel)
          compare (find-by-testid tree "rf-causa-mode-c-compare-table")]
      (is (some? compare) "compare-table renders when ≥2 selected")
      (is (= 2 (:data-instance-count (second compare))))
      ;; :state row should be present and (in this fixture) consistent
      ;; (both :inst-0 and :inst-4 are :idle, so no diff on state)
      (let [state-row (find-by-testid tree "rf-causa-mode-c-compare-row-:state")]
        (is (some? state-row))
        (is (= "false" (:data-diff (second state-row))))))))

(deftest compare-table-marks-divergent-cells
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
    ;; :inst-0 is :idle / user "ada"; :inst-1 is :authing / user "bob"
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-0])
    (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-1])
    (let [tree      (machine-inspector/Panel)
          state-row (find-by-testid tree "rf-causa-mode-c-compare-row-:state")
          user-row  (find-by-testid tree "rf-causa-mode-c-compare-row-:user")]
      (is (= "true" (:data-diff (second state-row)))
          ":idle vs :authing diverges")
      (is (= "true" (:data-diff (second user-row)))
          "ada vs bob diverges"))))

;; ---- (7) cluster-by selector ------------------------------------------

(deftest cluster-by-selector-roundtrips
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-mode-c-cluster-by :parent-machine])
    (is (= :parent-machine @(rf/subscribe [:rf.causa/mode-c-cluster-by])))))

(deftest set-mode-c-cluster-by-rejects-invalid-selector
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/set-mode-c-cluster-by :bogus])
    (is (= :state @(rf/subscribe [:rf.causa/mode-c-cluster-by]))
        "invalid selector is rejected; default :state stays put")))

(deftest cluster-by-context-key-rejoins-on-the-sub-selector
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines! [:req/protocol])
    (select-machine! :req/protocol)
    (override-instances! (gen-instances :req/protocol 12))
    (rf/dispatch-sync [:rf.causa/set-mode-c-cluster-by :context-key])
    (rf/dispatch-sync [:rf.causa/set-mode-c-context-key :user])
    (let [clusters @(rf/subscribe [:rf.causa/mode-c-clusters])
          ks       (set (map :cluster-key clusters))]
      ;; users in gen-instances are ada/bob/cat (3 distinct)
      (is (= #{"ada" "bob" "cat"} ks)
          "cluster-by-context-key on :user yields 3 clusters"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard.
;;
;; Four `for` loops in the cluster view previously wrapped function-call
;; list forms — `(instance-row …)`, `(compare-cell …)`, `(compare-row …)`,
;; `(cluster-row …)` — under `^{:key …}` reader meta. Reagent's
;; `get-react-key` only reads `:key` from vector meta, so the keys were
;; silently lost. The fix routes each per-row child through `with-meta`
;; so the `:key` lands on the returned vector. This test renders the
;; cluster view under populated fixtures and asserts every per-row
;; child carries `:key` meta. (rf2-ppzid)
;;
;; Note: the `expand-fn-component` walker above strips element meta
;; (via `mapv`), so the assertions here re-walk the raw rendered tree
;; without fn expansion to keep keyed vectors intact.
;; ---------------------------------------------------------------------------

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest cluster-list-rows-carry-key-meta
  (testing "cluster-row for-loop ships per-cluster <li> children with
            :key meta on the returned vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:req/protocol])
      (select-machine! :req/protocol)
      (override-instances! (gen-instances :req/protocol 12))
      (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
      (let [tree    (machine-inspector/Panel)
            li-rows (filter
                      (fn [n]
                        (some-> (:data-testid (second n))
                                (.startsWith "rf-causa-mode-c-cluster-:")))
                      (raw-find-all-by-testid-prefix
                        tree "rf-causa-mode-c-cluster-"))]
        (is (>= (count li-rows) 2) "at least two cluster rows for the fixture")
        (doseq [row li-rows]
          (is (vector? row) "cluster row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "cluster row carries :key meta — got "
                   (pr-str (meta row)))))))))

(deftest instance-rows-inside-expansion-carry-key-meta
  (testing "instance-row for-loop ships per-instance <li> children with
            :key meta on the returned vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:req/protocol])
      (select-machine! :req/protocol)
      (override-instances! (gen-instances :req/protocol 12))
      (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
      (rf/dispatch-sync [:rf.causa/toggle-mode-c-cluster-expanded :idle])
      (let [tree  (machine-inspector/Panel)
            insts (raw-find-all-by-testid-prefix tree "rf-causa-mode-c-instance-")]
        (is (>= (count insts) 2))
        (doseq [row insts]
          (is (vector? row) "instance row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "instance row carries :key meta — got "
                   (pr-str (meta row)))))))))

(deftest compare-table-rows-carry-key-meta
  (testing "compare-row + compare-cell for-loops ship per-row + per-cell
            children with :key meta on the returned vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:req/protocol])
      (select-machine! :req/protocol)
      (override-instances! (gen-instances :req/protocol 12))
      (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-c])
      (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-0])
      (rf/dispatch-sync [:rf.causa/toggle-mode-c-selection :inst-1])
      (let [tree (machine-inspector/Panel)
            ;; per-row <tr> compare-row children
            rows (raw-find-all-by-testid-prefix tree "rf-causa-mode-c-compare-row-")]
        ;; The :state row is rendered eagerly (no for-loop key), but the
        ;; remaining rows come through `(for [row rows] (with-meta
        ;; (compare-row row) …))`. Assert at least one row carries
        ;; :key meta — the non-state rows go through the for-loop.
        (is (some #(some-> (meta %) :key) rows)
            "at least one compare-row child carries :key meta")))))
