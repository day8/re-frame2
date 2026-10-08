(ns day8.re-frame2-xray.panels.event.event-status-colour-view-cljs-test
  "Render-path smoke for the canonical event-lifecycle status-colour
  helper.

  ## What this suite covers

  The pure-data layer (classifier + token map) is exercised in
  `event_status_colour_cljs_test.cljc` against the JVM. THIS suite
  asserts the consumer site in the rendered devtool picks up
  the helper's output — without that walk-through, a future
  refactor could leave the helper detached from its call site and
  the suite would still pass.

  The render site is the **Trace timeline bar** — `panels/trace/Panel`
  renders a 3px event-bundle-status bar above the ribbon (cascade-scoped
  so the bar represents every visible row's parent).

  The L2 event-list row carries no lifecycle status stripe, and there
  is no Event-panel status dot.

  ## Pure hiccup walk

  Same approach as the surrounding panel suites — we walk the
  rendered tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.theme.tokens :as tokens]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom +
  ;; `:all` tier, which also resets the trace-collector rings);
  ;; `:post-reset` clears the suppressed-count.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (config/reset-suppressed-count!))}))

;; ---- the Trace panel's status bar --------------------------------------
;;
;; The Trace row below cannot call `trace/Panel` and hand the result to a
;; walker. Neither half works.
;;
;; `trace/Panel` is an `rf.fresco/defview`: a real React function component
;; whose body reads through Fresco's collector, which refuses a read outside
;; a render extent by name (`:rf.error/fresco-sub-outside-render`).
;; `trace/panel-tree` is the same body as a pure fn of the four values the
;; boundary reads, so this suite performs the reads itself.
;;
;; And the scan has to be NON-EXPANDING. The panel's body carries two
;; `rt/resizable-table-view` heads — boundaries too — which
;; `rf.test-helpers/expand-tree` would invoke and which would refuse for the
;; same reason. The status bar is a CALLED helper returning a native div and
;; sits above the table, so a plain depth-first scan reaches it. The Trace
;; panel's own suite (`panels/trace_view_cljs_test`) owns the walking of the
;; table's interior; this row only needs the bar.

(defn- trace-status-bar
  "The Trace panel's event-bundle status-bar node whose `:data-testid`
  satisfies `match?`, or nil."
  [match?]
  (let [tree (trace/panel-tree
               {:feed                 @(rf/subscribe [:rf.xray/trace-feed])
                :focus                @(rf/subscribe [:rf.xray/focus])
                :focused-event-bundle @(rf/subscribe [:rf.xray.trace/focused-event-bundle])
                :expanded-ids         @(rf/subscribe [:rf.xray/trace-expanded-row-ids])})]
    (some (fn [node]
            (when (and (vector? node)
                       (map? (second node))
                       (some-> (:data-testid (second node)) match?))
              node))
          (tree-seq (some-fn vector? seq?) seq tree))))

(def ^:private status-bar-prefix "rf-xray-trace-event-bundle-status-bar-")

;; ---- fixture builders --------------------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-trace-ev
  "Minimal :rf.event/dispatched fixture — same shape the shell tests
  use."
  [id event-vec]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.event/v       event-vec
               :frame       :rf/default
               :rf.trace/dispatch-id id}})

(defn- handler-exception-ev
  "An :rf.error/handler-exception trace pinned to `dispatch-id`. The
  cascade projection routes the trace into the cascade's `:errors`
  slot — `event-bundle-outcome` resolves to :error / red."
  [id dispatch-id]
  {:id        id
   :op-type   :error
   :operation :rf.error/handler-exception
   :tags      {:rf.trace/dispatch-id dispatch-id :rf.trace/event-id :foo}})

;; ---- the Trace timeline bar --------------------------------------------

(deftest trace-event-bundle-status-bar-error
  (testing "an errored focused cascade flips the bar to
            red, through the same helper, and the bar carries the
            canonical status keyword from the one status map."
    (xray-setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-collector/seed-trace-for-test! (handler-exception-ev 99 1))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-dispatch-id 1])
      (let [bar (trace-status-bar #(= % (str status-bar-prefix "settled-error")))]
        (is (= (:red tokens/tokens)
               (get-in (second bar) [:style :background])))
        (is (= "settled-error" (:data-rf-xray-status (second bar)))
            "the bar rides the canonical status vocabulary")))))
