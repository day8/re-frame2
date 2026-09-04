(ns day8.re-frame2-xray.panels.app-db-diff-events-cljs-test
  "Per-leaf smoke test for `app-db-diff-events` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella
  `register-xray-handlers!`) so the leaf is pinned as an
  independently usable install unit. Dispatches one happy-path
  event and asserts the resulting :rf/xray app-db transition.

  rf2-e9tb0 — the pin / unpin / reorder events were removed when the
  pinned-watches strip was superseded by the segment-inspector
  popup. Only focus-slice-path + the clipboard fx remain on this
  leaf; segment-inspector events live on the
  `app-db-segment-inspector` leaf."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fxs
  (events/install!)
  (is (some? (rf.registrar/handler :event :rf.xray/focus-slice-path)))
  (is (some? (rf.registrar/handler :event :rf.xray/clear-slice-focus)))
  (is (some? (rf.registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))
  ;; rf2-e9tb0 — pin events were dropped at this leaf.
  (is (nil? (rf.registrar/handler :event :rf.xray/pin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/unpin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/reorder-pinned-slices))))

(deftest focus-slice-path-dispatch-writes-xray-frame
  (events/install!)
  (rf/make-frame {:id :rf/xray})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-slice-path [:cart :items]]))
  (is (= [:cart :items]
         (:focused-slice-path (rf.frame/frame-app-db-value :rf/xray)))))
