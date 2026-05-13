(ns day8.re-frame2-causa.panels.schema-violation-timeline-cljs-test
  "CLJS-side wiring tests for Causa's Schema-violation Timeline panel
  (Phase 5, rf2-htffa).

  ## Contracts under test (in addition to the pure-data tests in
  `schema_violation_timeline_helpers_cljs_test.cljc`)

    1. **Registry wires the subs / events** under the `:rf.causa/*`
       namespace.
    2. **The composite sub** returns the panel's render data shape:
       rows + window + selection + filter.
    3. **Selection writes to the Causa frame, not the host.**
    4. **Schema filter narrows the rendered rows.**
    5. **View renders the three empty states** correctly.
    6. **View renders dots with recovery-correct colours.**

  ## Pure hiccup walk

  Same approach as `time_travel_cljs_test.cljs` — walk the view's
  hiccup tree by `data-testid` rather than mounting to a DOM. Keeps
  the suite fast + host-portable on node-test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.schema-violation-timeline :as panel]
            [day8.re-frame2-causa.panels.schema-violation-timeline-helpers :as h]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker (mirrors event_detail / time_travel tests) ----------

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
                 (string? (:data-testid (second node)))
                 (str/starts-with? (:data-testid (second node)) prefix)))
          (hiccup-seq tree)))

;; ---- buffer helpers -----------------------------------------------------

(defn- failure-ev
  ([id schema-id recovery]
   (failure-ev id schema-id recovery {}))
  ([id schema-id recovery {:keys [time path value]
                           :or {time 1000
                                path [:auth :email]
                                value nil}}]
   {:id        id
    :op-type   :error
    :operation :rf.error/schema-validation-failure
    :time      time
    :recovery  recovery
    :tags      {:where  :app-db
                :path   path
                :value  value
                :schema schema-id
                :frame  :rf/default}}))

(defn- push-failures! [evs]
  ;; Per rf2-iw5ym: `:rf.causa/trace-buffer` is reactive off Causa's
  ;; app-db, not the trace-bus atom. Tests drive the reactive write
  ;; path directly via `dispatch-sync` so the composite sub re-fires
  ;; synchronously on the next subscribe.
  (rf/with-frame :rf/causa
    (doseq [ev evs]
      (rf/dispatch-sync [:rf.causa/note-trace-event ev]))))

;; ---- (1) registry wires subs / events -----------------------------------

(deftest registry-installs-schema-timeline-subs
  (testing "register-causa-handlers! installs the Phase 5 subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/schema-violation-timeline)))
    (is (some? (registrar/handler :sub :rf.causa/registered-schemas)))
    (is (some? (registrar/handler :sub :rf.causa/schema-violations-window)))
    (is (some? (registrar/handler :sub :rf.causa/schema-timeline-window)))
    (is (some? (registrar/handler :sub :rf.causa/selected-violation-id)))
    (is (some? (registrar/handler :sub :rf.causa/schema-filter)))))

(deftest registry-installs-schema-timeline-events
  (testing "register-causa-handlers! installs the Phase 5 events"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/select-violation)))
    (is (some? (registrar/handler :event :rf.causa/clear-violation-selection)))
    (is (some? (registrar/handler :event :rf.causa/set-schema-filter)))
    (is (some? (registrar/handler :event :rf.causa/set-schema-timeline-window)))))

;; ---- (2) composite sub returns sane defaults ----------------------------

(deftest composite-sub-defaults-with-empty-buffer
  (testing ":rf.causa/schema-violation-timeline returns sane defaults on a
            fresh frame with an empty trace buffer"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [data @(rf/subscribe [:rf.causa/schema-violation-timeline])]
        (is (vector? (:rows data)))
        (is (map? (:window data)))
        (is (number? (:t0 (:window data))))
        (is (number? (:t1 (:window data))))
        (is (= 0 (:total-violations data)))
        (is (= 0 (:rendered-violations data)))
        (is (nil? (:selected-violation data)))
        (is (nil? (:schema-filter data)))))))

;; ---- (3) select-violation writes to the Causa frame ---------------------

(deftest select-violation-writes-to-causa-frame
  (testing ":rf.causa/select-violation lands on :rf/causa, not :rf/default"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-violation 42]))
    (is (= 42 (:selected-violation-id (frame/frame-app-db-value :rf/causa))))
    (is (nil? (:selected-violation-id (frame/frame-app-db-value :rf/default))))))

(deftest select-violation-nil-clears-selection
  (testing "dispatching :rf.causa/select-violation with nil clears the slot"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-violation 7])
      (rf/dispatch-sync [:rf.causa/select-violation nil]))
    (is (nil? (:selected-violation-id (frame/frame-app-db-value :rf/causa))))))

(deftest clear-violation-selection-event
  (testing ":rf.causa/clear-violation-selection drops the slot"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-violation 1])
      (rf/dispatch-sync [:rf.causa/clear-violation-selection]))
    (is (nil? (:selected-violation-id (frame/frame-app-db-value :rf/causa))))))

;; ---- (4) schema-filter narrows the rendered rows ------------------------

(deftest set-schema-filter-narrows-rows
  (testing "dispatching :rf.causa/set-schema-filter restricts the composite
            to the matching schema-id"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [now-ms (.getTime (js/Date.))]
      (push-failures! [(failure-ev 1 :s/auth :skip-handler {:time now-ms})
                       (failure-ev 2 :s/cart :rollback-db  {:time now-ms})])
      (rf/with-frame :rf/causa
        ;; First, both rows surface as orphans (no schemas registered).
        (let [data @(rf/subscribe [:rf.causa/schema-violation-timeline])]
          (is (= 2 (:rendered-violations data))
              "both violations render before filter"))
        ;; Apply a filter — only :s/auth's row remains.
        (rf/dispatch-sync [:rf.causa/set-schema-filter :s/auth])
        (let [data @(rf/subscribe [:rf.causa/schema-violation-timeline])]
          (is (= 1 (:rendered-violations data))
              "filter narrows to one violation")
          (is (= :s/auth (:schema-filter data))
              "filter slot exposed to the view")
          (is (some #(= :s/auth (:schema-id %)) (:rows data))
              "the filtered row is present"))
        ;; Clearing the filter restores both.
        (rf/dispatch-sync [:rf.causa/set-schema-filter nil])
        (let [data @(rf/subscribe [:rf.causa/schema-violation-timeline])]
          (is (= 2 (:rendered-violations data))
              "filter cleared — both violations render again")
          (is (nil? (:schema-filter data))))))))

;; ---- (5) time-axis window set/clear -------------------------------------

(deftest set-schema-timeline-window-validates-shape
  (testing "valid {:t0 :t1} maps set the slot; invalid maps clear it"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      ;; Valid window — written.
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window
                         {:t0 1000 :t1 2000}])
      (is (= {:t0 1000 :t1 2000}
             (:schema-timeline-window (frame/frame-app-db-value :rf/causa))))
      ;; Inverted window — dropped.
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window
                         {:t0 2000 :t1 1000}])
      (is (nil? (:schema-timeline-window (frame/frame-app-db-value :rf/causa))))
      ;; nil — clears.
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window
                         {:t0 1000 :t1 2000}])
      (rf/dispatch-sync [:rf.causa/set-schema-timeline-window nil])
      (is (nil? (:schema-timeline-window (frame/frame-app-db-value :rf/causa)))))))

;; ---- (6) empty states render -------------------------------------------

(deftest empty-state-no-schemas-renders
  (testing "with no registered schemas + no violations, the no-schemas
            empty state renders"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (rf/with-frame :rf/causa
      (let [tree (panel/schema-violation-timeline-view)]
        (is (some? (find-by-testid tree "rf-causa-schema-violation-timeline")))
        (is (some? (find-by-testid tree "rf-causa-schema-timeline-empty-no-schemas")))))))

;; (no-violations + violations-rendered live states use the real
;; registered-schemas sub. The schemas artefact is not on the test
;; classpath, so `(rf/app-schemas)` returns {} and the no-schemas
;; branch is the one the view paints when there are no failures.
;; The violations-rendered branch is asserted below — orphan rows
;; surface as rows even when no schemas are registered.)

(deftest violations-render-as-orphan-rows
  (testing "violations against unregistered schemas still surface as rows
            (per spec §Substrate — Causa renders what the buffer remembers)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [now-ms (.getTime (js/Date.))]
      (push-failures! [(failure-ev 1 :s/auth :skip-handler {:time now-ms})])
      (rf/with-frame :rf/causa
        (let [tree (panel/schema-violation-timeline-view)]
          (is (some? (find-by-testid tree "rf-causa-schema-violation-timeline")))
          (is (some? (find-by-testid tree "rf-causa-schema-timeline-rows"))
              "violations render the rows container")
          (is (nil? (find-by-testid tree "rf-causa-schema-timeline-empty-no-schemas"))
              "empty state is replaced by the live rows"))))))

;; ---- (7) recovery → colour mapping is honoured by the rendered dot -----

(deftest re-raised-renders-thicker-stroke-in-view
  (testing "the view paints :re-raised violations with the thicker stroke
            width from the helpers"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [now-ms (.getTime (js/Date.))]
      (push-failures! [(failure-ev 9 :s/critical :re-raised {:time now-ms})])
      (rf/with-frame :rf/causa
        (let [tree (panel/schema-violation-timeline-view)
              dots (find-all-by-testid-prefix tree "rf-causa-schema-timeline-row-")]
          (is (some
                (fn [node]
                  (let [attrs (when (vector? node) (second node))]
                    (and (map? attrs)
                         (= h/colour-red (:fill attrs))
                         (= h/default-re-raised-stroke-width
                            (:stroke-width attrs)))))
                (hiccup-seq tree))
              "at least one rendered <circle> carries the :re-raised stroke")
          ;; Sanity — the tree contains the rows container.
          (is (some? (find-by-testid tree "rf-causa-schema-timeline-rows"))))))))

(deftest replaced-with-default-paints-yellow
  (testing "the view paints :replaced-with-default violations with the
            yellow colour from the helpers (the framework recovered
            cleanly)"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [now-ms (.getTime (js/Date.))]
      (push-failures! [(failure-ev 11 :s/soft :replaced-with-default
                                   {:time now-ms})])
      (rf/with-frame :rf/causa
        (let [tree (panel/schema-violation-timeline-view)]
          (is (some
                (fn [node]
                  (let [attrs (when (vector? node) (second node))]
                    (and (map? attrs)
                         (= h/colour-yellow (:fill attrs)))))
                (hiccup-seq tree))
              "at least one rendered <circle> is yellow"))))))

;; ---- (8) selection round-trip surfaces detail panel --------------------

(deftest selecting-a-violation-renders-detail-panel
  (testing "after dispatching :rf.causa/select-violation, the detail side
            panel appears in the view tree"
    (registry/register-causa-handlers!)
    (frame/reg-frame :rf/causa {})
    (let [now-ms (.getTime (js/Date.))]
      (push-failures! [(failure-ev 77 :s/user :rollback-db
                                   {:time now-ms
                                    :path [:user :email]
                                    :value "not-an-email"})])
      (rf/with-frame :rf/causa
        ;; Before selection — no detail panel.
        (let [tree (panel/schema-violation-timeline-view)]
          (is (nil? (find-by-testid tree
                                    "rf-causa-schema-violation-detail-77"))))
        ;; Select.
        (rf/dispatch-sync [:rf.causa/select-violation 77])
        (let [tree (panel/schema-violation-timeline-view)]
          (is (some? (find-by-testid tree
                                     "rf-causa-schema-violation-detail-77"))
              "the detail side panel renders for the selected violation"))
        ;; Clear.
        (rf/dispatch-sync [:rf.causa/clear-violation-selection])
        (let [tree (panel/schema-violation-timeline-view)]
          (is (nil? (find-by-testid tree
                                    "rf-causa-schema-violation-detail-77"))
              "clearing selection unmounts the detail side panel"))))))
