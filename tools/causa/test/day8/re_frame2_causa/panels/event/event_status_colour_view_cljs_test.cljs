(ns day8.re-frame2-causa.panels.event.event-status-colour-view-cljs-test
  "Render-path smoke for the canonical event-lifecycle status-colour
  helper (rf2-b76v4).

  ## What this suite covers

  The pure-data layer (classifier + token map) is exercised in
  `event_status_colour_cljs_test.cljc` against the JVM. THIS suite
  asserts the three consumer sites in the rendered devtool pick up
  the helper's output — without that walk-through, a future
  refactor could leave the helper detached from its call sites and
  the suite would still pass.

  The render sites (per the bead's contract):

    1. **L2 event-list row** — `shell/event-row` carries
       `data-rf-causa-status` + an inset `box-shadow` painted with
       the lifecycle colour. Each lifecycle state surfaces in the
       expected anchor colour.

    2. **Trace timeline bar** — `panels/trace/Panel` renders a 3px
       cascade-status bar above the ribbon (cascade-scoped per
       rf2-ycoct so the bar represents every visible row's parent).

  The Event L4 header dot was a third site until rf2-ad7zx.17 removed
  the Event panel's top ribbon (matching `EventPanel.tsx`); the Event
  panel now renders no status dot at all.

  ## Pure hiccup walk

  Same approach as the surrounding panel suites — we walk the
  rendered tree by `data-testid` rather than mounting to a DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.theme.tokens :as tokens]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker ------------------------------------------------------
;; Thin aliases over re-frame.test-helpers. The local
;; `find-by-testid-prefix` returns the FIRST match (vs the framework's
;; `find-by-testid-prefix` which returns a vector of matches); the
;; thin wrapper here preserves the existing call-site contract.

(def ^:private find-by-testid th/find-by-testid)

(defn- find-by-testid-prefix [tree prefix]
  (first (th/find-by-testid-prefix tree prefix)))

;; ---- fixture builders --------------------------------------------------

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

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
  slot — `cascade-outcome` resolves to :error / red."
  [id dispatch-id]
  {:id        id
   :op-type   :error
   :operation :rf.error/handler-exception
   :tags      {:rf.trace/dispatch-id dispatch-id :rf.trace/event-id :foo}})

(defn- warning-ev
  "A real `:rf.warning/large-value-unschema` trace (an op the substrate
  actually emits — see implementation/core/src/re_frame/elision.cljc)
  pinned to `dispatch-id`. The cascade projection routes the trace into
  the cascade's `:other` bucket; `cascade-outcome` resolves to
  :warning / yellow via the universal :op-type severity axis."
  [id dispatch-id]
  {:id        id
   :op-type   :warning
   :operation :rf.warning/large-value-unschema
   :tags      {:rf.trace/dispatch-id dispatch-id}})

;; ---- (1) L2 event-list row pickups -------------------------------------

(deftest l2-row-success-cascade-rides-green
  (testing "rf2-b76v4 — a happy-path cascade carries
            data-rf-causa-status='settled-success' on its <li> and the
            row's box-shadow rides the canonical green token. ONE
            helper drives the value at the row level."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            row    (find-by-testid tree "rf-causa-event-row-1")
            attrs  (second row)
            status (:data-rf-causa-status attrs)
            shadow (get-in attrs [:style :box-shadow])]
        (is (some? row) "L2 row renders for the cascade")
        (is (= "settled-success" status)
            "settled-success vocabulary lands on the row")
        (is (string? shadow))
        (is (string/includes? shadow (:green tokens/tokens))
            "row's box-shadow accent uses the canonical :green token")))))

(deftest l2-row-errored-cascade-rides-red
  (testing "rf2-b76v4 — an errored cascade carries
            data-rf-causa-status='settled-error' + a red box-shadow
            on the row. Error vocabulary trumps the default ok."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            row    (find-by-testid tree "rf-causa-event-row-1")
            attrs  (second row)
            status (:data-rf-causa-status attrs)
            shadow (get-in attrs [:style :box-shadow])]
        (is (= "settled-error" status))
        (is (string/includes? shadow (:red tokens/tokens))
            "row's box-shadow accent uses the canonical :red token")))))

(deftest l2-row-warning-cascade-rides-green-not-yellow
  (testing "rf2-b76v4 — :warning outcomes resolve to :settled-success
            at the row level (the row stays green). The yellow glyph
            ALREADY signals warning at the Event header glyph slot;
            re-amplifying it on the row would double-up the signal."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (warning-ev 99 1))
    (rf/with-frame :rf/causa
      (let [tree   (shell/shell-view)
            row    (find-by-testid tree "rf-causa-event-row-1")
            attrs  (second row)
            status (:data-rf-causa-status attrs)
            shadow (get-in attrs [:style :box-shadow])]
        (is (= "settled-success" status))
        (is (string/includes? shadow (:green tokens/tokens)))))))

(deftest l2-row-status-comes-from-the-canonical-helper
  (testing "rf2-b76v4 — the row's data-rf-causa-status MATCHES the
            name of the keyword `classify-status` resolves for the
            same input. ONE helper, ONE vocabulary."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (let [tree     (shell/shell-view)
            row      (find-by-testid tree "rf-causa-event-row-1")
            status   (:data-rf-causa-status (second row))
            ;; Same state shape the row builds:
            classify (event-status/classify-status {:outcome :error})]
        (is (= (name classify) status)
            "row's vocabulary matches the helper's classifier output")))))

;; ---- (2) Event panel no longer carries a status dot (rf2-ad7zx.17) -----
;;
;; The Event panel's top header/ribbon — which carried the lifecycle
;; status dot — was removed to match `EventPanel.tsx` (rf2-ad7zx.17). The
;; status-colour vocabulary now has TWO render sites (L2 row + Trace bar)
;; rather than three; the pure-data layer is exercised in
;; `event_status_colour_cljs_test.cljc`.

(deftest event-panel-no-longer-renders-a-status-dot
  (testing "rf2-ad7zx.17 — the Event panel has no top ribbon, so it
            renders NO lifecycle status dot and NO header carrying
            data-rf-causa-status. The vocabulary lives at the L2 row +
            Trace bar sites instead."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree (event-detail/Panel)]
        (is (nil? (find-by-testid tree "rf-causa-event-detail-header"))
            "no top header carrying data-rf-causa-status")
        (is (every? (fn [st]
                      (nil? (find-by-testid
                              tree
                              (str "rf-causa-event-detail-status-dot-" st))))
                    ["settled-success" "settled-error" "in-flight"
                     "paused-by-tool" "stale"])
            "no lifecycle status dot of any state")))))

;; ---- (3) Trace timeline bar pickups ------------------------------------

(deftest trace-cascade-status-bar-renders-with-canonical-colour
  (testing "rf2-b76v4 — the Trace tab's cascade-status bar fills the
            ribbon with the focused cascade's lifecycle colour. Wins
            its testid from the resolved status keyword so a future
            classifier shift surfaces here without a colour assertion."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree (trace/Panel)
            bar  (find-by-testid-prefix tree
                                        "rf-causa-trace-cascade-status-bar-")]
        (is (some? bar)
            "cascade-status bar renders when a cascade is in focus")
        (let [attrs (second bar)
              tid   (:data-testid attrs)]
          (is (re-find #"settled-success$" tid)
              "bar's testid carries the resolved status vocabulary")
          (is (= (:green tokens/tokens)
                 (get-in attrs [:style :background]))
              "bar's background is the canonical green hex"))))))

(deftest trace-cascade-status-bar-error
  (testing "rf2-b76v4 — an errored focused cascade flips the bar to
            red. Same helper drives the colour the L2 row picks up."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree (trace/Panel)
            bar  (find-by-testid tree
                                 "rf-causa-trace-cascade-status-bar-settled-error")]
        (is (some? bar))
        (is (= (:red tokens/tokens)
               (get-in (second bar) [:style :background])))))))

;; ---- (4) cross-site consistency — ONE vocabulary ------------------------

(deftest both-sites-agree-on-cascade-status
  (testing "rf2-b76v4 / rf2-ad7zx.17 — the L2 row + the Trace timeline
            bar resolve to the SAME status keyword for the same cascade.
            This is the bead's headline contract: ONE canonical map, NO
            per-call-site rolling. (The Event L4 header dot was a third
            site until rf2-ad7zx.17 removed the top ribbon.)"
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [shell-tree   (shell/shell-view)
            trace-tree   (trace/Panel)
            l2-row       (find-by-testid shell-tree "rf-causa-event-row-1")
            trace-bar    (find-by-testid-prefix
                           trace-tree
                           "rf-causa-trace-cascade-status-bar-")
            l2-status    (:data-rf-causa-status (second l2-row))
            trace-status (:data-rf-causa-status (second trace-bar))]
        (is (= l2-status trace-status "settled-error")
            (str "both consumers ride the same vocabulary — "
                 "l2: " l2-status
                 " · trace: " trace-status))))))
