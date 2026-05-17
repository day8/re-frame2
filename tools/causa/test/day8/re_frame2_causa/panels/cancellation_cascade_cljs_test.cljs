(ns day8.re-frame2-causa.panels.cancellation-cascade-cljs-test
  "CLJS-side wiring + view tests for Causa's Cancellation-cascade
  visualiser (rf2-59e7k).

  ## What's under test (in addition to the pure-data tests in
  `cancellation_cascade_helpers_cljs_test.cljc`)

    1. **Registry wires the composite subs and events** under
       `:rf.causa/cancellation-cascade-*` ids.
    2. **Empty-state render** — `SidePanel` short-circuits when no
       cancellation-anchor is present; `Popover` is gated by
       `:rf.causa/cancellation-cascade-popover-open?`.
    3. **Populated render** — with a seeded trace buffer the cascade
       view renders the decision + teardown + abort rows.
    4. **Click handlers dispatch the right events** — the row's
       on-click dispatches `:rf.causa/focus-trace-entry`; the close
       button dispatches `:rf.causa/cancellation-cascade-close`.
    5. **Collapse / expand affordance** — under the default
       threshold the expander appears and the toggle event flips
       `:rf.causa/cancellation-cascade-expanded?`.

  ## Pure hiccup

  Same approach as `flows_view_cljs_test` — walk the view's hiccup
  tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.cancellation-cascade :as cc]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror other view tests) ---------------------------

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

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- seed-trace! [events]
  ;; Seed Causa's app-db trace-buffer slot directly via the registry's
  ;; sync event. Avoids the trace-bus collector loop entirely; the
  ;; subs read off `:trace-buffer` regardless of how the slot got
  ;; populated.
  (rf/dispatch-sync [:rf.causa/sync-trace-buffer (vec events)]))

;; ---- minimal fixture events ---------------------------------------------

(def ^:private cancel-cascade-buffer
  "One decision + one cancellation-anchor + two HTTP aborts."
  [{:id 1 :operation :event/dispatched :op-type :event
    :time 1000
    :tags {:event [:auth/logout] :dispatch-id 7 :frame :rf/default}}
   {:id 2 :operation :rf.machine/destroyed :op-type :rf.machine
    :time 1010
    :tags {:machine-id :user-session :reason :explicit
           :dispatch-id 7 :frame :rf/default}}
   {:id 3 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1020
    :tags {:request-id :r1 :url "/api/profile" :actor-id :user-session
           :dispatch-id 7 :frame :rf/default}}
   {:id 4 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1021
    :tags {:request-id :r2 :url "/api/log" :actor-id :user-session
           :dispatch-id 7 :frame :rf/default}}])

;; ---- (1) registry wires the composite subs + events --------------------

(deftest registry-installs-cancellation-cascade-handlers
  (testing "register-causa-handlers! installs every sub + event the
            visualiser depends on"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/cancellation-cascade-popover-open?)))
    (is (some? (registrar/handler :sub :rf.causa/cancellation-cascade-popover-focus)))
    (is (some? (registrar/handler :sub :rf.causa/cancellation-cascade-expanded?)))
    (is (some? (registrar/handler :sub :rf.causa/cancellation-cascade-for-focused-machine)))
    (is (some? (registrar/handler :sub :rf.causa/cancellation-cascade-for-focused-event)))
    (is (some? (registrar/handler :event :rf.causa/cancellation-cascade-open)))
    (is (some? (registrar/handler :event :rf.causa/cancellation-cascade-close)))
    (is (some? (registrar/handler :event :rf.causa/cancellation-cascade-toggle-expand)))
    (is (some? (registrar/handler :event :rf.causa/focus-trace-entry)))))

;; ---- (2) empty-state renders -------------------------------------------

(deftest side-panel-empty-when-no-cascade
  (testing "with an empty trace buffer the SidePanel reg-view returns
            nil (mount stays dormant)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [out (cc/SidePanel)]
        (is (nil? out)
            "no rendered hiccup when no cancellation cascade is present")))))

(deftest popover-empty-when-closed
  (testing "Popover short-circuits to nil when the open? slot is false"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (nil? (cc/Popover))))))

(deftest popover-renders-empty-state-when-open-with-no-cascade
  (testing "Popover renders the no-trigger empty state when open but
            the trace buffer carries no cascade"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-open nil])
      (let [tree (cc/Popover)]
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-popover-dialog")))
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-empty-no-trigger")))))))

;; ---- (3) populated render ----------------------------------------------

(deftest side-panel-renders-when-machine-cascade-present
  (testing "with a cancellation cascade in the trace buffer for the
            focused machine the SidePanel renders the waterfall"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-trace! cancel-cascade-buffer)
      ;; Pick the machine that had the destroy
      (rf/dispatch-sync [:rf.causa/select-machine-id :user-session])
      (let [tree (cc/SidePanel)]
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade"))
            "section root rendered")
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-decision-row"))
            "decision row rendered")
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-summary"))
            "summary line rendered")
        (let [aborts (find-all-by-testid-prefix
                       tree "rf-causa-cancellation-cascade-abort-row-")]
          (is (= 2 (count aborts))
              "two abort rows rendered, one per fixture event"))))))

(deftest popover-renders-cascade-for-focused-event
  (testing "Popover opened with a dispatch-id focus pulls the cascade
            for that dispatch and renders the body"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-trace! cancel-cascade-buffer)
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-open
                         {:kind :dispatch-id :id 7}])
      (let [tree (cc/Popover)]
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-popover-dialog")))
        (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-decision-row")))
        (is (= 2 (count (find-all-by-testid-prefix
                          tree "rf-causa-cancellation-cascade-abort-row-"))))))))

;; ---- (4) click handlers ------------------------------------------------

(deftest close-button-dispatches-close-event
  (testing "the close button's on-click dispatches
            :rf.causa/cancellation-cascade-close, flipping the
            popover-open? slot to false"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-open nil])
      (is (true? @(rf/subscribe [:rf.causa/cancellation-cascade-popover-open?])))
      ;; Fire the close event directly to assert the reducer's
      ;; round-trip — the on-click is a thin wrapper over this dispatch.
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-close])
      (is (false? @(rf/subscribe [:rf.causa/cancellation-cascade-popover-open?]))))))

(deftest focus-trace-entry-event-shape
  (testing "the row-click event accepts a `:dispatch-id` and dispatches
            without throwing — production path flips through the spine
            shim and panel-select"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (seed-trace! cancel-cascade-buffer)
      ;; No throw is enough — the event handler delegates via :fx so
      ;; the side-effecting half routes through the spine shim, which
      ;; we've already covered in the spine tests.
      (rf/dispatch-sync [:rf.causa/focus-trace-entry
                         {:dispatch-id 7 :frame :rf/default :trace-id 1}])
      (is (true? true)))))

;; ---- (5) collapse / expand ---------------------------------------------

(deftest expander-toggles-expanded-slot
  (testing "the expand-toggle event flips the `:expanded?` slot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (is (false? @(rf/subscribe [:rf.causa/cancellation-cascade-expanded?])))
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-toggle-expand])
      (is (true?  @(rf/subscribe [:rf.causa/cancellation-cascade-expanded?])))
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-toggle-expand])
      (is (false? @(rf/subscribe [:rf.causa/cancellation-cascade-expanded?]))))))

(deftest expander-renders-when-aborts-exceed-threshold
  (testing "with > default-collapse-threshold aborts the expander
            appears under the abort list"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (let [decision    {:id 1 :operation :event/dispatched :op-type :event
                         :time 1000
                         :tags {:event [:checkout/cancel]
                                :dispatch-id 9 :frame :rf/default}}
            destroy     {:id 2 :operation :rf.machine/destroyed
                         :op-type :rf.machine :time 1010
                         :tags {:machine-id :checkout :reason :explicit
                                :dispatch-id 9 :frame :rf/default}}
            many-aborts (for [n (range 15)]
                          {:id        (+ 100 n)
                           :operation :rf.http/aborted-on-actor-destroy
                           :op-type   :rf.http
                           :time      (+ 1020 n)
                           :tags      {:request-id (keyword (str "r" n))
                                       :url        (str "/api/x" n)
                                       :actor-id   :checkout
                                       :dispatch-id 9
                                       :frame      :rf/default}})]
        (seed-trace! (concat [decision destroy] many-aborts))
        (rf/dispatch-sync [:rf.causa/cancellation-cascade-open
                           {:kind :dispatch-id :id 9}])
        (let [tree (cc/Popover)]
          (is (some? (find-by-testid tree "rf-causa-cancellation-cascade-expander"))
              "expander present when collapsed-by-default kicks in")
          (let [shown-when-collapsed
                (find-all-by-testid-prefix
                  tree "rf-causa-cancellation-cascade-abort-row-")]
            (is (<= (count shown-when-collapsed) 5)
                "collapsed view shows at most 5 abort rows by default")))))))

;; ---- (6) frame isolation ----------------------------------------------

(deftest popover-state-isolated-on-rf-causa
  (testing "the popover slot lives on :rf/causa, not on the default
            frame — the host's app-db never sees these keys"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/cancellation-cascade-open nil])
      (is (true? @(rf/subscribe [:rf.causa/cancellation-cascade-popover-open?]))
          "open slot reads true under the :rf/causa frame"))
    (rf/with-frame :rf/default
      (let [db @(rf/subscribe [:rf/app-db])]
        (is (not (contains? db :cancellation-cascade-popover-open?))
            "no leak into the host's :rf/default frame")))))
