(ns day8.re-frame2-xray.panels.app-db-diff-events-cljs-test
  "Per-leaf smoke test for `app-db-diff-events` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella
  `register-xray-handlers!`) so the leaf is pinned as an
  independently usable install unit, and asserts exactly which
  handlers it does and does not register.

  rf2-e9tb0 — the pin / unpin / reorder events were removed when the
  pinned-watches strip was superseded by the segment-inspector popup.
  rf2-y8doi.29 then retired that popup unreached, and with it this
  leaf's `:rf.xray/focus-slice-path` / `:rf.xray/clear-slice-focus`
  pair. Only the clipboard fx remains on this leaf."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-events :as events]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest leaf-install-registers-events-and-fxs
  (events/install!)
  ;; The one surviving registration on this leaf — the POSITIVE control
  ;; for the nil-asserts below, so a blanket "nothing installed" bug
  ;; cannot make them pass vacuously.
  (is (some? (rf.registrar/handler :fx :rf.xray.fx/copy-to-clipboard)))
  ;; rf2-e9tb0 — pin events were dropped at this leaf.
  (is (nil? (rf.registrar/handler :event :rf.xray/pin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/unpin-slice)))
  (is (nil? (rf.registrar/handler :event :rf.xray/reorder-pinned-slices)))
  ;; rf2-y8doi.29 — the slice-focus pair went with the segment-inspector
  ;; popup: `:rf.xray/focus-slice-path` wrote `:focused-slice-path`, a
  ;; slot whose only reader was a sub nothing subscribed.
  (is (nil? (rf.registrar/handler :event :rf.xray/focus-slice-path)))
  (is (nil? (rf.registrar/handler :event :rf.xray/clear-slice-focus))))
