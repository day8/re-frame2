(ns day8.re-frame2-causa.panels.app-db-diff-events-cljs-test
  "Per-leaf smoke test for `app-db-diff-events` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella
  `register-causa-handlers!`) so the leaf is pinned as an
  independently usable install unit. Dispatches one happy-path
  event and asserts the resulting :rf/causa app-db transition.

  rf2-e9tb0 — the pin / unpin / reorder events were removed when the
  pinned-watches strip was superseded by the segment-inspector
  popup. Only focus-slice-path + the clipboard fx remain on this
  leaf; segment-inspector events live on the
  `app-db-segment-inspector` leaf."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-diff-events :as events]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fxs
  (events/install!)
  (is (some? (registrar/handler :event :rf.causa/focus-slice-path)))
  (is (some? (registrar/handler :event :rf.causa/clear-slice-focus)))
  (is (some? (registrar/handler :fx :rf.causa.fx/copy-to-clipboard)))
  ;; rf2-e9tb0 — pin events were dropped at this leaf.
  (is (nil? (registrar/handler :event :rf.causa/pin-slice)))
  (is (nil? (registrar/handler :event :rf.causa/unpin-slice)))
  (is (nil? (registrar/handler :event :rf.causa/reorder-pinned-slices))))

(deftest focus-slice-path-dispatch-writes-causa-frame
  (events/install!)
  (frame/reg-frame :rf/causa {})
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/focus-slice-path [:cart :items]]))
  (is (= [:cart :items]
         (:focused-slice-path (frame/frame-app-db-value :rf/causa)))))
