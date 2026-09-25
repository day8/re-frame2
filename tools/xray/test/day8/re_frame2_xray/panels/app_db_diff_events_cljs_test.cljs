(ns day8.re-frame2-xray.panels.app-db-diff-events-cljs-test
  "Per-leaf smoke test for `app-db-diff-events`.

  Calls the leaf's `install!` directly (NOT the umbrella
  `register-xray-handlers!`) so the leaf is pinned as an
  independently usable install unit, and asserts exactly which
  handlers it does and does not register.

  This leaf registers no pin / unpin / reorder events and no
  `:rf.xray/focus-slice-path` / `:rf.xray/clear-slice-focus` pair
  (there is no pinned-watches strip and no segment-inspector popup);
  the clipboard fx is its one registration."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fxs
  (events/install!)
  ;; The one registration on this leaf — the POSITIVE control
  ;; for the nil-asserts below, so a blanket "nothing installed" bug
  ;; cannot make them pass vacuously.
  (is (some? (rf.registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))
  ;; No pin events at this leaf.
  (is (nil? (rf.registrar/handler :event :rf.xray/pin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/unpin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/reorder-pinned-slices)))
  ;; No slice-focus pair: `:rf.xray/focus-slice-path` would write
  ;; `:focused-slice-path`, a slot nothing reads.
  (is (nil? (rf.registrar/handler :event :rf.xray/focus-slice-path)))
  (is (nil? (rf.registrar/handler :event :rf.xray/clear-slice-focus))))
