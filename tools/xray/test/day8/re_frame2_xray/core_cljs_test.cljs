(ns day8.re-frame2-xray.core-cljs-test
  "Tests for `day8.re-frame2-xray.core` — the canonical user-facing
  facade promised by `spec/API.md`.

  ## Contract surfaces under test

  1. **Frame wiring.** `set-target-frame!` dispatches into the
     `:rf/xray` frame; the dispatch updates `:rf.xray/target-frame`
     in Xray's app-db, so the companion sub re-fires and
     `target-frame` reads back the new value. (There is no panel
     picker API — no `active-panel` / `set-active-panel!`; the 4-layer
     shell switches via `:rf.xray/selected-tab`.)

  2. **`load-theme!` is a safe no-op without a DOM.** It injects a
     host-supplied CSS override into `<head>` when a DOM is present
     (through `global-styles/set-host-theme-css!`);
     under node-test there is no `js/document`, so it must return nil
     without throwing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` is the one owner of per-test setup:
  ;; plain-atom adapter + the `:runtime` reset tier —
  ;; sentinels + trace-collector rings + the persisted Settings atom.
  ;; The settings reset matters because init! writes through to that atom,
  ;; so per-test mutations must not leak into the next test's read.
  (xray-test-support/make-xray-runtime-fixture {:tier :runtime}))

(defn- setup-xray-frame!
  "Per-test boot: register handlers, allocate the :rf/xray frame."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- (1) frame wiring --------------------------------------------------
;;
;; The facade's `set-target-frame!` calls `rf/dispatch` (async — the
;; user-facing contract; the runtime dispatch queues into the
;; `:rf/xray` frame's router so it lands inside the next drain). The
;; tests assert the same wiring contract via `dispatch-sync` so the
;; drain runs to completion inside the assertion — mirrors the pattern
;; the registry / shell tests use for the rest of the `:rf.xray/*`
;; surface.

(deftest set-target-frame-wires-target-frame
  (testing ":rf.xray/set-target-frame updates the slot target-frame reads"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-target-frame :app/main])
      (is (= :app/main (core/target-frame))
          "after dispatch the facade's read returns the new value")
      (rf/dispatch-sync [:rf.xray/set-target-frame nil])
      (is (nil? (core/target-frame))
          "nil resets to UNSELECTED (not through :rf/default)"))))

;; ---- (2) load-theme! — DOM-bearing impl, no-op without a DOM ------------

(deftest load-theme-is-safe-without-document
  ;; Under node-test there is no js/document; CSS injection is the browser
  ;; target's subject.
  (is (nil? (core/load-theme! ".foo { color: red; }"))))

;; ---- init! contract ----------------------------------------------------

(deftest init!-wires-theme-density-and-buffer-depths
  ;; A host's boot-time opts land in the slots the Settings popup writes.
  (setup-xray-frame!)
  (core/init! {:target-frame  :app/main
               :theme         :dark
               :density       :compact
               :buffer-depths {:epoch 75}})
  (is (= [:dark :compact 75]
         [(config/get-setting :theme nil)
          (config/get-setting :general :density)
          (config/get-setting :general :epoch-history)])))

(deftest init!-tolerates-unknown-opts-keys
  (testing "init! silently ignores keys it doesn't recognise
            (forward-compat: a host passing a key a future Xray release
            adds MUST NOT break the current Xray boot)."
    (setup-xray-frame!)
    (core/init! {:target-frame    :app/main
                 :unknown/future  :something
                 :rf.xray/another 42})
    (is true "init! did not throw on unknown keys")))

(deftest init!-buffer-depths-with-nil-epoch-is-noop
  (testing "init! ignores :buffer-depths shapes without a
            usable :epoch (nil, non-numeric, non-positive)."
    (setup-xray-frame!)
    (let [start (config/get-setting :general :epoch-history)]
      (core/init! {:buffer-depths {:trace 200}})
      (is (= start (config/get-setting :general :epoch-history))
          ":trace-only map left :epoch-history at the prior value")
      (core/init! {:buffer-depths {:epoch -5}})
      (is (= start (config/get-setting :general :epoch-history))
          "negative epoch depth was rejected by the apply-epoch-history! gate"))))
