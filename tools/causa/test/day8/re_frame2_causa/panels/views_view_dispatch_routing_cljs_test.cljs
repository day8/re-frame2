(ns day8.re-frame2-causa.panels.views-view-dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Causa Views panel (rf2-83d4x).

  Sibling to `popup_dispatch_routing_cljs_test.cljs` (rf2-smvvz) and
  `palette/dispatch_routing_cljs_test.cljs` (rf2-w8lxg). Same bug
  class — every `rf/dispatch` from a deferred handler (`:on-click`,
  `:on-context-menu`) inside the Views panel MUST carry the explicit
  `{:frame :rf/causa}` opt, because React's `_currentValue` for the
  frame-provider pops back to `:rf/default` between render and
  click-time, so the 3-tier resolve chain otherwise lands on
  `:rf/default` and the panel-local handler reduces the WRONG db.

  ## The Views-specific symptom (rf2-83d4x)

  - Group-By pill click → `:rf.causa/views-set-group-by` runs against
    `:rf/default`'s db, leaving `:rf/causa`'s `:views/group-by` slot
    untouched → the panel never re-renders into sub-grouped / tree
    mode (rf2-dodq2 'Views Group-By stuck on Component').
  - Filter-chip clear / bottom-controls clear → same shape.
  - Row-toggle click → same shape (per-row expansion never opens).
  - Cluster-toggle click → same shape.
  - Right-click context menu (`right-click-filter-handler`) → same
    shape.

  ## How these tests reproduce the click-time path

  Each test plucks the handler off the rendered hiccup and invokes it
  OUTSIDE any `with-frame` binding — simulating the browser's
  click-fires-after-render reality. Asserts that the dispatch landed
  on `:rf/causa`'s app-db and NOT on `:rf/default`'s — the exact
  invariant the explicit `{:frame :rf/causa}` opt guarantees."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.views :as facade]
            [day8.re-frame2-causa.panels.views-view :as view]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture (map shape per cljs.test/async requirement) ---------------

(def ^:private fixture-snap (atom nil))

(defn- setup-runtime! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (reset! fixture-snap (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! plain-atom/adapter)
  (frame/ensure-default-frame!)
  ;; Views panel's :rf.causa/* registrations + the :rf/causa frame.
  (facade/install!)
  (frame/reg-frame :rf/causa {}))

(defn- teardown-runtime! []
  (when-let [snap @fixture-snap]
    (test-support/restore-registrar! snap)
    (reset! fixture-snap nil))
  (reset! frame/frames {}))

(use-fixtures :each
  {:before setup-runtime!
   :after  teardown-runtime!})

;; ---- click-time helpers ------------------------------------------------

(defn- fake-event []
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- await-causa-db
  "Poll until `pred` of `:rf/causa`'s app-db returns truthy. Settles
  the async router drain that queued click-dispatches go through."
  [pred label]
  (test-support/poll-until
    #(pred (rf/get-frame-db :rf/causa))
    {:label label :timeout-ms 1000}))

;; ---- tests --------------------------------------------------------------

(deftest group-by-pill-click-flips-causa-not-default
  (testing "rf2-83d4x — clicking the Group-By `sub` pill from OUTSIDE
            the :rf/causa frame-provider's render context flips
            :rf/causa's :views/group-by to :sub. Without the explicit
            `{:frame :rf/causa}` opt, the click would land on
            :rf/default and Causa's slot would stay :component
            (root cause of rf2-dodq2)."
    (let [;; Render the pill directly (private fn — same surface the
          ;; panel mounts). `:value :sub` is the pill we want to click.
          pill    (#'view/group-by-pill {:value :sub
                                         :selected? false
                                         :label "sub"})
          attrs   (second pill)
          handler (:on-click attrs)]
      (is (fn? handler) "group-by pill exposes an :on-click handler")
      (handler (fake-event)) ; outside any with-frame — same as a browser click
      (async done
        (-> (await-causa-db #(= :sub (:views/group-by %))
                            ":views/group-by flips to :sub after pill click")
            (.then (fn [_]
                     (is (= :sub (:views/group-by (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :views/group-by flips to :sub")
                     (is (nil? (:views/group-by (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest filter-chip-clear-click-flips-causa-not-default
  (testing "rf2-83d4x — clicking the filter-chip `×` clear button from
            OUTSIDE the :rf/causa frame-provider's render context
            dissocs :rf/causa's :views/component-filter. Without the
            explicit frame opt, the click would land on :rf/default
            and the chip would stay rendered."
    ;; Seed the filter on :rf/causa so there's something to clear.
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/views-set-component-filter :cart/list]))
    (let [chip    (#'view/filter-chip :cart/list)
          clear   (th/find-by-testid chip "rf-causa-views-filter-chip-clear")
          handler (:on-click (second clear))]
      (is (fn? handler) "filter-chip clear exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(not (contains? % :views/component-filter))
                            ":views/component-filter dissoced after clear")
            (.then (fn [_]
                     (is (nil? (:views/component-filter (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :views/component-filter is dissoced")
                     (is (nil? (:views/component-filter (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest right-click-row-handler-routes-to-causa
  (testing "rf2-83d4x — the `right-click-filter-handler` dispatch
            routes to :rf/causa even when invoked outside any
            with-frame binding (the exact click-time React-context-
            popped scenario)."
    (let [handler (#'view/right-click-filter-handler ::comp-a)]
      (is (fn? handler) "right-click-filter-handler returns a fn")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(= ::comp-a (:views/component-filter %))
                            ":views/component-filter set on :rf/causa after right-click")
            (.then (fn [_]
                     (is (= ::comp-a (:views/component-filter (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :views/component-filter set to the view-id")
                     (is (nil? (:views/component-filter (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))

(deftest row-toggle-click-flips-causa-not-default
  (testing "rf2-83d4x — clicking a row's body fires
            `:rf.causa/views-toggle-row` against :rf/causa, NOT
            :rf/default. Asserts :views/expanded-rows on :rf/causa
            after the click."
    (let [item    {:kind   :single
                   :render {:render-key   [::comp 0]
                            :triggered-by ::sub
                            :elapsed-ms   1.0}
                   :invalidated-by [{:sub-id ::sub :trigger? true}]}
          row     (#'view/single-row item :rendered false)
          attrs   (second row)
          handler (:on-click attrs)]
      (is (fn? handler) "single-row exposes an :on-click handler")
      (handler (fake-event))
      (async done
        (-> (await-causa-db #(seq (:views/expanded-rows %))
                            ":views/expanded-rows populated on :rf/causa after row click")
            (.then (fn [_]
                     (is (seq (:views/expanded-rows (rf/get-frame-db :rf/causa)))
                         ":rf/causa's :views/expanded-rows is populated")
                     (is (nil? (:views/expanded-rows (rf/get-frame-db :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")
                     (done)))
            (.catch (fn [e] (is false (.-message e)) (done))))))))
