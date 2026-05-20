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

  The three call sites (per the bead's contract):

    1. **L2 event-list row** — `shell/event-row` carries
       `data-rf-causa-status` + an inset `box-shadow` painted with
       the lifecycle colour. Each lifecycle state surfaces in the
       expected anchor colour.

    2. **Event L4 header dot** — `panels/event-detail` renders a
       leading status dot with `rf-causa-event-detail-status-dot-
       <status>` testid + the lifecycle colour as its background.

    3. **Trace timeline bar** — `panels/trace/Panel` renders a 3px
       cascade-status bar above the ribbon (cascade-scoped per
       rf2-ycoct so the bar represents every visible row's parent).

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
  "Minimal :event/dispatched fixture — same shape the shell tests
  use."
  [id event-vec]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :tags      {:event       event-vec
               :frame       :rf/default
               :dispatch-id id}})

(defn- handler-exception-ev
  "An :rf.error/handler-exception trace pinned to `dispatch-id`. The
  cascade projection routes the trace into the cascade's `:errors`
  slot — `cascade-outcome` resolves to :error / red."
  [id dispatch-id]
  {:id        id
   :op-type   :error
   :operation :rf.error/handler-exception
   :tags      {:dispatch-id dispatch-id :event-id :foo}})

(defn- warning-ev
  "An :rf.warning/depth-exceeded trace pinned to `dispatch-id`. The
  cascade projection routes the trace into the cascade's `:other`
  bucket; `cascade-outcome` resolves to :warning / yellow."
  [id dispatch-id]
  {:id        id
   :op-type   :warning
   :operation :rf.warning/depth-exceeded
   :tags      {:dispatch-id dispatch-id}})

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

;; ---- (2) Event L4 header status-dot pickups ----------------------------

(deftest event-header-status-dot-success
  (testing "rf2-b76v4 — happy-path cascade → Event L4 header renders
            a status dot with `rf-causa-event-detail-status-dot-
            settled-success` testid + the green background."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree (event-detail/Panel)
            dot  (find-by-testid tree
                                 "rf-causa-event-detail-status-dot-settled-success")]
        (is (some? dot) "status dot renders for the success-cascade")
        (is (= (:green tokens/tokens)
               (get-in (second dot) [:style :background]))
            "dot's background is the canonical green hex")))))

(deftest event-header-status-dot-error
  (testing "rf2-b76v4 — errored cascade → red dot with the
            settled-error testid suffix."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree (event-detail/Panel)
            dot  (find-by-testid tree
                                 "rf-causa-event-detail-status-dot-settled-error")]
        (is (some? dot) "settled-error dot renders for the error cascade")
        (is (= (:red tokens/tokens)
               (get-in (second dot) [:style :background])))))))

(deftest event-header-carries-rf-causa-status-attribute
  (testing "rf2-b76v4 — the <header> wrapper carries
            data-rf-causa-status so smoke tests + the pure-hiccup
            walker can assert the vocabulary without parsing inline
            styles. Same shape as the L2 row's attribute."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [tree   (event-detail/Panel)
            header (find-by-testid tree "rf-causa-event-detail-outcome")
            attrs  (second header)]
        (is (= "settled-success" (:data-rf-causa-status attrs)))))))

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
            red. Same helper drives the colour the L2 row + Event
            header pick up."
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

(deftest all-three-sites-agree-on-cascade-status
  (testing "rf2-b76v4 — the L2 row, Event L4 header, and Trace
            timeline bar ALL resolve to the SAME status keyword for
            the same cascade. This is the bead's headline contract:
            ONE canonical map, NO per-call-site rolling."
    (causa-setup!)
    (trace-bus/collect-trace! (dispatch-trace-ev 1 [:foo/bar]))
    (trace-bus/collect-trace! (handler-exception-ev 99 1))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-dispatch-id 1])
      (let [shell-tree   (shell/shell-view)
            event-tree   (event-detail/Panel)
            trace-tree   (trace/Panel)
            l2-row       (find-by-testid shell-tree "rf-causa-event-row-1")
            event-header (find-by-testid event-tree
                                         "rf-causa-event-detail-outcome")
            trace-bar    (find-by-testid-prefix
                           trace-tree
                           "rf-causa-trace-cascade-status-bar-")
            l2-status    (:data-rf-causa-status (second l2-row))
            event-status (:data-rf-causa-status (second event-header))
            trace-status (:data-rf-causa-status (second trace-bar))]
        (is (= l2-status event-status trace-status "settled-error")
            (str "all three consumers ride the same vocabulary — "
                 "l2: " l2-status
                 " · event: " event-status
                 " · trace: " trace-status))))))
