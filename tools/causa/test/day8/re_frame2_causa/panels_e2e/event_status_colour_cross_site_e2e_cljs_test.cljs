(ns day8.re-frame2-causa.panels-e2e.event-status-colour-cross-site-e2e-cljs-test
  "Multi-frame end-to-end coverage for the canonical event-lifecycle
  status-colour helper's cross-site contract (rf2-b8pui, parent
  rf2-b76v4).

  ## Why this suite exists

  `event_status_colour_view_cljs_test.cljs` covers the cross-site
  vocabulary at the synthetic-trace layer — it pumps hand-crafted
  trace events into `trace-bus` and walks the rendered hiccup. The
  pure-data layer is covered by `event_status_colour_cljs_test.cljc`
  on the JVM.

  What was missing: a cross-site assertion that survives the REAL
  trace bus → cascade projection → spine focus → hiccup render
  pipeline. A regression in any of those layers (e.g. the trace cb
  drops the host's `:frame` tag, the cascade projection routes the
  error trace into the wrong cascade, the spine focus auto-track
  desyncs) would leave the synthetic-trace test green while the
  production devtool reads three different colours at the three
  consumer sites.

  This suite uses `with-host-and-causa-frames` — REAL `:rf/default`
  host frame, REAL `:rf/causa` panel frame, REAL trace-bus mirror
  + REAL `:rf.causa/select-dispatch-id` event — then walks the
  three consumer-site hiccup trees and asserts the SAME
  `data-rf-causa-status` keyword surfaces at all three.

  ## Consumer sites under test

  All three nodes carry `data-rf-causa-status` (the helper's
  classifier output `(name kw)`) so the walker keys on the same
  attribute everywhere:

    1. L2 event-row             — `shell/shell-view` <li>
       `data-testid='rf-causa-event-row-<id>'`
    2. L4 Event header dot      — `event-detail/Panel` <header>
       `data-testid='rf-causa-event-detail-outcome'`
    3. L4 Trace cascade-status  — `trace/Panel` <div>
       `data-testid='rf-causa-trace-cascade-status-bar-<status>'`

  ## Cascades exercised

    - `:settled-success` — host `:counter/inc` dispatch (no errors).
    - `:settled-error`   — host `:deliberate-throw/throw-in-handler`
                           dispatch (handler throws → trace bus emits
                           `:rf.error/handler-exception`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.event-detail :as event-detail]
            [day8.re-frame2-causa.panels.trace :as trace]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]
            [day8.re-frame2-causa.test-helpers.host-fixtures.deliberate-throw
             :as throws]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- combined host fixture ---------------------------------------------

(defn- install-counter+throws!
  "Stack the counter + deliberate-throw fixtures so the SAME harness
  can exercise both a success cascade and an error cascade without
  re-mounting frames. Idempotent — both fixtures' `install!` paths
  are."
  []
  (counter/install-and-init!)
  (throws/install-and-init!))

;; ---- focus + render helpers --------------------------------------------

(defn- focus-cascade!
  "Drive the spine onto the supplied dispatch-id via the canonical
  `:rf.causa/select-dispatch-id` event. Routes through the same
  reducer the L2 row-click path uses, so the test exercises the
  production focus seam."
  [dispatch-id]
  (rf/dispatch-sync [:rf.causa/select-dispatch-id dispatch-id]
                    {:frame :rf/causa}))

(defn- read-status-at-each-site
  "Render the three consumer views under `:rf/causa` and return a
  map of `{:l2 <status-str>, :event <status-str>, :trace <status-str>}`,
  reading `:data-rf-causa-status` off the canonical anchor node at
  each site. Any missing site reads as nil so a failing assertion
  pinpoints which surface fell off the helper.

  Reads only — no state mutation. Safe to call repeatedly per
  focused cascade."
  [dispatch-id]
  (rf/with-frame :rf/causa
    (let [shell-tree   (shell/shell-view)
          event-tree   (event-detail/Panel)
          trace-tree   (trace/Panel)
          l2-row       (th/find-by-attr shell-tree :data-testid
                                        (str "rf-causa-event-row-"
                                             (str dispatch-id)))
          event-header (th/find-by-attr event-tree :data-testid
                                        "rf-causa-event-detail-outcome")
          trace-bar    (first (th/find-by-attr-prefix
                                trace-tree :data-testid
                                "rf-causa-trace-cascade-status-bar-"))]
      {:l2    (get (th/attrs l2-row) :data-rf-causa-status)
       :event (get (th/attrs event-header) :data-rf-causa-status)
       :trace (get (th/attrs trace-bar) :data-rf-causa-status)})))

;; ---- tests --------------------------------------------------------------

(deftest success-cascade-status-agrees-across-all-three-sites
  (testing "rf2-b8pui — happy-path host dispatch settles to
            :settled-success at the L2 row, the L4 Event header dot,
            and the L4 Trace cascade-status bar. One vocabulary,
            one helper."
    (e2e/with-host-and-causa-frames
      {:install-host install-counter+throws!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [focus-id (:dispatch-id (e2e/sub-causa [:rf.causa/focus]))
              _        (focus-cascade! focus-id)
              status   (read-status-at-each-site focus-id)]
          (is (= "settled-success" (:l2 status))
              "L2 row did not classify a clean :counter/inc as :settled-success")
          (is (= "settled-success" (:event status))
              "L4 Event header did not classify a clean :counter/inc as :settled-success")
          (is (= "settled-success" (:trace status))
              "L4 Trace cascade-status bar did not classify a clean :counter/inc as :settled-success")
          (is (= (:l2 status) (:event status) (:trace status))
              (str "consumer sites disagree on status — "
                   "l2: "    (:l2 status)
                   " · event: " (:event status)
                   " · trace: " (:trace status))))))))

(deftest error-cascade-status-agrees-across-all-three-sites
  (testing "rf2-b8pui — a host handler-exception settles to
            :settled-error at all three sites. Catches the
            regression class where the trace projection drops the
            error trace, or the spine focuses on the wrong cascade,
            or one consumer rolls its own colour decision."
    (e2e/with-host-and-causa-frames
      {:install-host install-counter+throws!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [focus-id (:dispatch-id (e2e/sub-causa [:rf.causa/focus]))
              _        (focus-cascade! focus-id)
              status   (read-status-at-each-site focus-id)]
          (is (= "settled-error" (:l2 status))
              "L2 row did not classify a handler-throw as :settled-error")
          (is (= "settled-error" (:event status))
              "L4 Event header did not classify a handler-throw as :settled-error")
          (is (= "settled-error" (:trace status))
              "L4 Trace cascade-status bar did not classify a handler-throw as :settled-error")
          (is (= (:l2 status) (:event status) (:trace status))
              (str "consumer sites disagree on status — "
                   "l2: "    (:l2 status)
                   " · event: " (:event status)
                   " · trace: " (:trace status))))))))
