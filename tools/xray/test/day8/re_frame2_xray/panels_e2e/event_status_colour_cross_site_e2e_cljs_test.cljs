(ns day8.re-frame2-xray.panels-e2e.event-status-colour-cross-site-e2e-cljs-test
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

  This suite uses `with-host-and-xray-frames` — REAL `:rf/default`
  host frame, REAL `:rf/xray` panel frame, REAL trace-bus mirror
  + REAL `:rf.xray/select-dispatch-id` event — then walks the
  consumer-site hiccup trees and asserts the SAME
  `data-rf-xray-status` keyword surfaces at every site.

  ## Consumer site under test

  Post rf2-pjjwh the status-colour vocabulary has a SINGLE render site —
  the L4 Trace event-bundle-status bar (`trace/Panel` <div>
  `data-testid='rf-xray-trace-event-bundle-status-bar-<status>'`). The L2
  event-row's trailing status stripe was retired (it was not in the Figma
  mock); the L4 Event header dot was removed earlier (rf2-ad7zx.17). This
  suite exercises the REAL trace bus → cascade projection → spine focus →
  hiccup render pipeline end-to-end against the surviving site.

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
            [day8.re-frame2-xray.panels.trace :as trace]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]
            [day8.re-frame2-xray.test-helpers.host-fixtures.deliberate-throw
             :as throws]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
  `:rf.xray/select-dispatch-id` event. Routes through the same
  reducer the L2 row-click path uses, so the test exercises the
  production focus seam."
  [dispatch-id]
  (rf/dispatch-sync [:rf.xray/select-dispatch-id dispatch-id]
                    {:frame :rf/xray}))

(defn- read-trace-status
  "Render the L4 Trace panel under `:rf/xray` and return the cascade-
  status bar's `:data-rf-xray-status` (or nil when absent). Post rf2-pjjwh
  the Trace bar is the single status-colour render site (the L2 row stripe
  was retired). Reads only — no state mutation."
  [_dispatch-id]
  (rf/with-frame :rf/xray
    (let [trace-tree (trace/Panel)
          trace-bar  (first (th/find-by-attr-prefix
                              trace-tree :data-testid
                              "rf-xray-trace-event-bundle-status-bar-"))]
      (get (th/attrs trace-bar) :data-rf-xray-status))))

;; ---- tests --------------------------------------------------------------

(deftest success-event-bundle-status-rides-trace-bar
  (testing "rf2-b8pui / rf2-pjjwh — happy-path host dispatch settles to
            :settled-success at the L4 Trace event-bundle-status bar through
            the REAL trace bus → projection → spine → render pipeline.
            (The L2 row stripe + Event header dot are both retired.)"
    (e2e/with-host-and-xray-frames
      {:install-host install-counter+throws!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [focus-id (:dispatch-id (e2e/sub-xray [:rf.xray/focus]))
              _        (focus-cascade! focus-id)
              status   (read-trace-status focus-id)]
          (is (= "settled-success" status)
              "L4 Trace event-bundle-status bar did not classify a clean :counter/inc as :settled-success"))))))

(deftest error-event-bundle-status-rides-trace-bar
  (testing "rf2-b8pui / rf2-pjjwh — a host handler-exception settles to
            :settled-error at the L4 Trace event-bundle-status bar. Catches
            the regression class where the trace projection drops the
            error trace, the spine focuses on the wrong cascade, or the
            consumer rolls its own colour decision."
    (e2e/with-host-and-xray-frames
      {:install-host install-counter+throws!}
      (fn []
        (e2e/dispatch-host [:deliberate-throw/throw-in-handler])
        (let [focus-id (:dispatch-id (e2e/sub-xray [:rf.xray/focus]))
              _        (focus-cascade! focus-id)
              status   (read-trace-status focus-id)]
          (is (= "settled-error" status)
              "L4 Trace event-bundle-status bar did not classify a handler-throw as :settled-error"))))))
