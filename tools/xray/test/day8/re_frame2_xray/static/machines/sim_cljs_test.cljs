(ns day8.re-frame2-xray.static.machines.sim-cljs-test
  "CLJS wiring, view and integration tests for the Static Machines Sim
  sub-mode. The pure helpers are tested in `sim_helpers_cljs_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.machines :as rf.machines]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.sim :as sim]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom adapter + the default `:all`
  ;; reset tier, which includes the trace-collector ring reset.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

;; ---- hiccup walker -------------------------------------------------------

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

(defn- text-of [node]
  (->> (hiccup-seq node) (filter string?) (apply str)))

;; ---- fixture data --------------------------------------------------------

(def ^:private fixture-definition
  {:initial :idle
   :data    {:counter 0}
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(defn- select-static-machine! [machine-id]
  (rf/dispatch-sync [:rf.xray.static.machines/select machine-id]))

(defn- start-sim! [definition]
  (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                     {:machine-id :auth/login
                      :definition definition}]))

(defn- sim-state []
  @(rf/subscribe [:rf.xray.static.machines/sim-state]))

;; The sim plain-fn subtree (`body` / `SimRail` / `SimChart`) does not
;; self-subscribe; it receives the derefed sub values from the enclosing
;; `detail` boundary. These helpers deref the sim sub family under
;; `:rf/xray`, threading the same values the boundary would.

(defn- sim-rail-values []
  {:sim         (sim-state)
   :transitions @(rf/subscribe
                   [:rf.xray.static.machines/sim-available-transitions])
   :suggestions @(rf/subscribe
                   [:rf.xray.static.machines/sim-event-suggestions])})

(defn- sim-chart-values []
  {:current    @(rf/subscribe [:rf.xray.static.machines/sim-current-state])
   :last-trans @(rf/subscribe [:rf.xray.static.machines/sim-last-transition])})

(defn- sim-body-values []
  (merge {:machine-id :auth/login :definition fixture-definition}
         (sim-rail-values)
         (sim-chart-values)))

(defn- sim-chart-props
  "Render SimChart for `definition` and return the props it hands
  machine-canvas/Chart. A raw walk, so the Chart survives as data rather
  than being replaced by its render output."
  [definition]
  (let [tree (sim/SimChart rf/dispatch
                           (merge {:machine-id :auth/login
                                   :definition definition}
                                  (sim-chart-values)))]
    (some (fn [node]
            (when (and (vector? node)
                       (= machine-canvas/Chart (first node)))
              (second node)))
          (tree-seq (some-fn vector? seq?) seq tree))))

;; ---- events ---------------------------------------------------------------

(deftest sim-start-seeds-from-the-definition-not-the-live-snapshot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-machine-snapshots-override-for-test
                       {:auth/login {:state :idle :data {:counter 99}}}])
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (is (= 0 (get-in (sim-state) [:snapshot :data :counter])))))

(deftest sim-start-seeds-a-compound-root-at-the-engine-leaf
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! {:initial :auth
                 :states  {:auth {:initial :form
                                  :states  {:form    {:on {:submit :loading}}
                                            :loading {}}}
                           :done {}}})
    (is (= [:auth :form]
           @(rf/subscribe [:rf.xray.static.machines/sim-current-state])))))

(deftest sim-step-engine-throw-treated-as-fail
  ;; A host without the machines artefact throws at the engine call; the
  ;; step surfaces it as the rail's red error toast instead of crashing.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (with-redefs [rf.machines/machine-transition (fn [_d _s _e]
                                                   (throw (js/Error. "no artefact")))]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (is (= "error"
           (-> (sim/SimRail rf/dispatch (sim-rail-values))
               (find-by-testid "rf-xray-static-machines-sim-error")
               second
               :data-kind)))))

(deftest sim-reset-rewinds-snapshot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (let [started (sim-state)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login :event [:start]}])
      (is (= :authing (get-in (sim-state) [:snapshot :state]))
          "the step moved, so the reset has something to rewind")
      (rf/dispatch-sync [:rf.xray.static.machines/sim-reset
                         {:machine-id :auth/login}])
      (is (= started (sim-state))))))

(deftest sim-stop-disposes-per-machine-slot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (start-sim! fixture-definition)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-stop
                       {:machine-id :auth/login}])
    (is (= {} @(rf/subscribe [:rf.xray.static.machines/sim-by-machine])))))

;; ---- sim rail -------------------------------------------------------------

(deftest rail-renders-nothing-when-sim-inactive
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (is (nil? (sim/SimRail rf/dispatch (sim-rail-values)))
        "rail returns nil when sim is inactive")))

(deftest rail-mounts-when-sim-active
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (let [tree (sim/SimRail rf/dispatch (sim-rail-values))]
      (is (= [] (remove #(find-by-testid tree %)
                        ["rf-xray-static-machines-sim-rail"
                         "rf-xray-static-machines-sim-banner"
                         "rf-xray-static-machines-sim-current-state"
                         "rf-xray-static-machines-sim-event-input"
                         "rf-xray-static-machines-sim-data-input"
                         "rf-xray-static-machines-sim-step-button"
                         "rf-xray-static-machines-sim-reset-button"
                         "rf-xray-static-machines-sim-exit-button"
                         ;; :idle declares :start
                         "rf-xray-static-machines-sim-available-start"
                         "rf-xray-static-machines-sim-audit-empty"]))
          "the testids missing from the rail")
      ;; The seed never runs the initial-entry cascade — a difference from
      ;; running the machine that only the rendered rail can disclose.
      (is (re-find #"(?i)entry.*not run"
                   (text-of (find-by-testid
                              tree "rf-xray-static-machines-sim-entry-notice")))))))

(def ^:private timer-fixture-definition
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:after {5000 :timeout} :on {:loaded :ready}}
             :timeout {}
             :ready   {}}})

(deftest rail-renders-an-after-timer-row-that-fires-the-timer
  ;; The real engine, and the row's own :on-click against a synchronous
  ;; dispatcher, so this grades the wiring, not just the testid.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! timer-fixture-definition)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                       {:machine-id :auth/login :event [:start]}])
    (let [rows (find-all-by-testid-prefix
                 (sim/SimRail rf/dispatch-sync (sim-rail-values))
                 "rf-xray-static-machines-sim-available-after-")
          row  (first rows)]
      (is (= ["rf-xray-static-machines-sim-available-after-5000"]
             (map (comp :data-testid second) rows))
          "one timer row on :loading, its testid naming the delay key")
      (is (re-find #"5000ms.*timer" (text-of row)))
      ((:on-click (second row)) nil)
      (is (= :timeout (get-in (sim-state) [:snapshot :state]))
          "the engine honoured the epoch the row read off the stored fx"))))

;; ---- body -----------------------------------------------------------------

(deftest body-auto-starts-sim-when-definition-present
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    ;; With no sim-state yet, rendering the body dispatches :sim-start.
    (sim/body rf/dispatch (sim-body-values))
    ;; Drain the event queue so the dispatched :sim-start lands.
    (rf/dispatch-sync [:rf.xray.static.machines/sim-set-pending-data
                       {:machine-id :auth/login :text ""}])
    (is (= :idle (get-in (sim-state) [:snapshot :state])))))

(deftest sim-body-renders-chart-and-rail-panes
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (let [tree (sim/body rf/dispatch (sim-body-values))]
      (is (= [] (remove #(find-by-testid tree %)
                        ["rf-xray-static-machines-sim-body"
                         "rf-xray-static-machines-sim-chart"
                         "rf-xray-static-machines-sim-rail"]))
          "the testids missing from the body"))))

(deftest body-renders-no-machine-hint-when-missing
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (sim/body rf/dispatch (assoc (sim-body-values) :machine-id nil))]
      (is (some? (find-by-testid tree
                                 "rf-xray-static-machines-sim-no-machine"))))))

(deftest sim-plain-fns-do-not-self-subscribe-without-a-frame
  ;; body / SimRail / SimChart render in their own React cycle, where a bare
  ;; `subscribe` throws `:rf.error/no-frame-context` (Spec 000 §Plain Reagent
  ;; fns under non-default frames). Seed under :rf/xray, render OUTSIDE it.
  (setup-xray-frame!)
  (let [vals (rf/with-frame :rf/xray
               (select-static-machine! :auth/login)
               (start-sim! fixture-definition)
               (sim-body-values))]
    (is (every? some? [(sim/body rf/dispatch vals)
                       (sim/SimRail rf/dispatch vals)
                       (sim/SimChart rf/dispatch vals)]))))

;; ---- on-chart sim surface -------------------------------------------------

(deftest sim-chart-edge-click-steps-the-sim-and-rebinds-the-canvas
  ;; An edge click steps the real engine, and SimChart hands the canvas the
  ;; amber sim palette, the advanced state, the taken edge's highlights and
  ;; the click callback.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                       {:machine-id :auth/login :event-id :start}])
    (is (= [true :authing :idle :authing true]
           ((juxt :sim? :current-state :from-highlight :to-highlight
                  (comp fn? :on-edge-click))
            (sim-chart-props fixture-definition))))))

(def ^:private inferred-fixture-definition
  "No [:schemas :data], so the context shape is INFERRED from the initial
  :data."
  {:initial :idle
   :data    {:counter 0 :label "x"}
   :states  {:idle {:on {:start :authing}}
             :authing {:final? true}}})

(deftest sim-chart-forwards-inferred-context-shape-to-canvas
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (is (= [{:counter "number" :label "string"} true]
           ((juxt :context-band :context-band-inferred?)
            (sim-chart-props inferred-fixture-definition))))))

;; ---- React keys -------------------------------------------------------------
;;
;; The rail's two row loops wrap fn-call forms, so each row's key rides a
;; KEYED FRAGMENT around the value the call returns. Graded at the renderer,
;; not at `(meta …)`: `reagent-key` honours meta AND props, while
;; `fresco-key` — the codec's own hiccup→element door — takes a literal
;; `:key` from an attribute map and reads metadata nowhere, so only it goes
;; red if the key rides `with-meta`.

(defn- reagent-key [node]
  (.-key (r/as-element node)))

(defn- fresco-key [node]
  (.-key (rf.fresco.impl.codec/as-element node)))

(defn- keyed-children
  "The forms after hiccup container `node`'s attribute map."
  [node]
  (remove nil? (drop 2 node)))

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

(defn- rows-and-siblings
  "The rendered rows under `prefix`, and the keyed siblings of the container
  whose testid is `list-testid`."
  [tree prefix list-testid]
  (let [nodes      (raw-find-all-by-testid-prefix tree prefix)
        container? #(= list-testid (:data-testid (second %)))]
    [(remove container? nodes)
     (keyed-children (first (filter container? nodes)))]))

(deftest sim-available-transitions-reach-react-with-keys
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    (let [[rows siblings] (rows-and-siblings
                            (sim/SimRail rf/dispatch (sim-rail-values))
                            "rf-xray-static-machines-sim-available-"
                            "rf-xray-static-machines-sim-available-list")]
      (is (= 1 (count rows) (count siblings))
          "one keyed sibling per rendered row")
      (doseq [sib siblings]
        (is (some? (fresco-key sib)) "the key reaches a Fresco boundary")
        (is (= (reagent-key sib) (fresco-key sib))
            "the same key reaches React on both substrates")))))

(deftest sim-audit-trail-rows-reach-react-with-distinct-keys
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (start-sim! fixture-definition)
    ;; Two REAL steps, :idle → :authing → :done: distinctness needs two
    ;; rows, and a constant stub would report the second step as no change.
    (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                       {:machine-id :auth/login :event [:start]}])
    (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                       {:machine-id :auth/login :event [:ok]}])
    (let [[rows siblings] (rows-and-siblings
                            (sim/SimRail rf/dispatch (sim-rail-values))
                            "rf-xray-static-machines-sim-audit-"
                            "rf-xray-static-machines-sim-audit-list")]
      (is (= 2 (count rows) (count siblings))
          "one keyed sibling per rendered row")
      (doseq [sib siblings]
        (is (some? (fresco-key sib)) "the key reaches a Fresco boundary")
        (is (= (reagent-key sib) (fresco-key sib))
            "the same key reaches React on both substrates"))
      (is (= 2 (count (distinct (map fresco-key siblings))))
          "sibling keys are distinct"))))
