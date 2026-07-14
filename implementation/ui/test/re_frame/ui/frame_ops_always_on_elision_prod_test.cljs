(ns re-frame.ui.frame-ops-always-on-elision-prod-test
  "rf2-vxgfnd.230 — the PRODUCTION-elision leg for the `(frame)` operation-bundle
  failure fan-out.

  The dev-mode `frame-ops-cljs-test` legs assert the always-on record + the dev
  trace both fire, but they run with the trace surface LIVE — they cannot prove
  the central EP-0008 claim this bead rests on: that the always-on emission
  SURVIVES `:advanced` + `goog.DEBUG=false`, where the dev trace (axis 2) is
  DCE'd. A bundle op firing after its frame was destroyed used to fail loud
  through a BARE `error/throw-error!`, so under `goog.DEBUG=false` a boundary
  that swallowed the throw dropped the production failure entirely. This file
  pins that, under the prod build, the always-on record (axis 1) is the SOLE
  surviving channel and still carries the frame / op / attempted head.

  Sibling `re-frame.on-error-elision-prod-test` pins the SAME
  `:rf.error/frame-destroyed` survival for the router/subs RECOVER-and-emit
  surfaces; this file pins it for the ui `(frame)` bundle's THROWING surface,
  which routes through the identical `error-emit/emit-error-both!` seam.

  Naming convention: files ending `-elision-prod-test.cljs` are picked up ONLY
  by the `:browser-test-prod-elision` build (`:advanced` + `{goog.DEBUG false}`,
  `:ns-regexp \"-elision-prod-test$\"`, runner `re-frame.prod-elision-runner`).
  The default `:node-test` / `:browser-test` runners do NOT match this suffix,
  so these tests run only under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.error-emit           :as error-emit]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter       plain-atom/adapter
    :ambient-frame nil
    :init-fn       (fn []
                     (error-emit/clear-error-listeners!)
                     (frames/reset-frame-ops-cache!))}))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(deftest stale-bundle-op-fans-one-always-on-record-under-prod
  (testing "Per rf2-vxgfnd.230 / EP-0008: under `:advanced` + `goog.DEBUG=false`
            a stale `(frame)` bundle op (captured for a since-destroyed
            incarnation) fans EXACTLY ONE always-on `:rf.error/frame-destroyed`
            record out through the corpus-wide `register-error-listener!`
            substrate — the always-on axis is NOT `interop/debug-enabled?`-gated,
            so the record survives where the dev trace is DCE'd. The record is
            the SOLE surviving channel here; a bare `throw-error!` went silent."
    (rf/reg-event :prod.ops/set-n (fn [_ [_ n]] {:db {:n n}}))
    (make-frame! :prod.ops/doomed {:n 1})
    (let [b    (rf/with-frame :prod.ops/doomed (frames/frame-ops))
          seen (atom [])]
      (frame/destroy-frame! :prod.ops/doomed)
      (rf/register-listener! :errors :prod/recorder (fn [r] (swap! seen conj r)))
      (is (thrown? js/Error ((:dispatch-sync b) [:prod.ops/set-n 2]))
          "the stale bundle op still fails loud with the typed error under prod")
      (let [reports (filter #(= :rf.error/frame-destroyed (:error %)) @seen)]
        (is (= 1 (count reports))
            "EXACTLY ONE always-on record survives `:advanced` + goog.DEBUG=false")
        (let [r (first reports)]
          (is (= :rf.error/frame-destroyed (:error r)))
          (is (= :prod.ops/doomed (:frame r)) ":frame names the destroyed frame")
          (is (= :dispatch-sync (:op r)) ":op names the failing bundle operation")
          (is (= :prod.ops/set-n (:event-id r)) ":event-id is the attempted head")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

(deftest absent-capture-fans-one-always-on-record-under-prod
  (testing "Per rf2-vxgfnd.230: the absent/closing `(frame)` CAPTURE arm follows
            the same prod-survival contract — a `(frame)` read resolving a stamp
            that names no live incarnation fans ONE always-on record under
            `:advanced` + goog.DEBUG=false, attributed to the :capture arm."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder (fn [r] (swap! seen conj r)))
      (is (thrown? js/Error
                   (binding [frame/*current-frame* :prod.ops/ghost]
                     (frames/frame-ops)))
          "the capture fails loud under prod")
      (let [reports (filter #(= :rf.error/frame-destroyed (:error %)) @seen)]
        (is (= 1 (count reports))
            "EXACTLY ONE always-on record survives prod for the capture arm")
        (let [r (first reports)]
          (is (= :prod.ops/ghost (:frame r)))
          (is (= :capture (:op r)) ":op marks the capture-time failure"))))))
