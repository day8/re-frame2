(ns day8.re-frame2-causa.control-axes-e2e.palette-invoke-e2e-cljs-test
  "Multi-frame e2e coverage for the Cmd-K palette invoke control axis
  (rf2-7icrs).

  The palette surface is opened via Cmd-K (or Ctrl-K); typing
  filters the list, ↑/↓ moves the cursor, Enter dispatches the
  selected item via `:rf.causa/palette-invoke`. At the e2e level we
  assert:

    1. `:rf.causa/palette-open` flips `:rf.causa/palette-open?` to true.
    2. `:rf.causa/palette-close` flips it back to false.
    3. `:rf.causa/palette-toggle` round-trips.
    4. Setting a query updates `:rf.causa/palette-query`.

  The full invoke path (palette-invoke → action dispatch) is
  exercised by the pure-fn `palette/events_cljs_test.cljs` and
  `palette/dispatch_routing_cljs_test.cljs`; this file focuses on
  the cross-frame reactivity contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-palette-defaults-closed
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (not (e2e/sub-causa [:rf.causa/palette-open?]))
          "palette should default to closed"))))

(deftest causa-palette-open-flips-open?
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-open])
      (is (e2e/sub-causa [:rf.causa/palette-open?])
          ":rf.causa/palette-open did not flip palette-open? to true"))))

(deftest causa-palette-toggle-round-trips
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-toggle])
      (is (e2e/sub-causa [:rf.causa/palette-open?])
          "first toggle did not open palette")
      (e2e/dispatch-causa [:rf.causa/palette-toggle])
      (is (not (e2e/sub-causa [:rf.causa/palette-open?]))
          "second toggle did not close palette"))))

(deftest causa-palette-query-updates
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-set-query "frame"])
      (is (= "frame" (e2e/sub-causa [:rf.causa/palette-query]))
          ":rf.causa/palette-set-query did not write through to palette-query"))))

(deftest causa-palette-state-survives-host-dispatch
  (testing "rf2-83d4x — palette state lives in :rf/causa, not host"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/palette-open])
        (e2e/dispatch-host [:counter/inc])
        (is (e2e/sub-causa [:rf.causa/palette-open?])
            "palette state cleared on host dispatch — wrong-frame class")))))

;; ---- rf2-w991t — Cmd-K palette execute: :toggle-theme + recents ----------
;;
;; The original Phase 3 bead (rf2-mpqxn) asked for an end-to-end exercise
;; of the Cmd-K palette's execute path: invoke the `:toggle-theme` command
;; item via `:rf.causa/palette-invoke`, assert the theme atom flips, then
;; re-invoke and assert recents-boost surfaces the just-executed verb.
;;
;; The toggle-theme item is built by `palette/sources.cljc`:
;;
;;   {:source :command :id :toggle-theme :action [:palette/toggle-theme] …}
;;
;; The invoke event reads the current theme via `config/get-setting :theme
;; nil` (the process-global atom, NOT app-db; the popup-seeded slot is a
;; mirror) and dispatches `:rf.causa/settings-update :theme nil <next>`
;; which dual-writes atom + app-db. The recents bump records `:toggle-
;; theme` at index 0 of `:rf.causa/palette-recents` and persists.

(def toggle-theme-item
  "Minimal item shape that drives the `:palette/toggle-theme` branch in
  `:rf.causa/palette-invoke`. The full item carried by the palette UI also
  has `:label`, `:icon`, `:boost`, etc.; the invoke event reads only
  `:source`, `:id`, and `:action`."
  {:source :command
   :id     :toggle-theme
   :action [:palette/toggle-theme]})

(deftest causa-palette-invoke-toggle-theme-flips-theme
  (testing "rf2-w991t — palette-invoke :toggle-theme flips the theme atom"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (config/get-setting :theme nil)]
          (e2e/dispatch-causa [:rf.causa/palette-invoke toggle-theme-item])
          (let [after (config/get-setting :theme nil)]
            (is (#{:dark :light} after)
                "post-invoke theme is not :dark or :light")
            (is (not= before after)
                "palette-invoke :toggle-theme did not flip the theme atom"))
          ;; Round-trip — a second invoke restores the prior value.
          (e2e/dispatch-causa [:rf.causa/palette-invoke toggle-theme-item])
          (is (= before (config/get-setting :theme nil))
              "second palette-invoke :toggle-theme did not round-trip"))))))

(deftest causa-palette-invoke-toggle-theme-bumps-recents
  (testing "rf2-w991t — :toggle-theme invoke records into :rf.causa/palette-recents"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/palette-invoke toggle-theme-item])
        (let [recents (e2e/sub-causa [:rf.causa/palette-recents])]
          (is (vector? recents)
              ":rf.causa/palette-recents did not resolve to a vector")
          (is (= :toggle-theme (first recents))
              ":toggle-theme did not bubble to index 0 of palette-recents"))))))

(deftest causa-palette-invoke-toggle-theme-recents-boost-survives
  (testing "rf2-w991t — re-invoking same command keeps recents head-stable"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        ;; First invoke records `:toggle-theme` at head.
        (e2e/dispatch-causa [:rf.causa/palette-invoke toggle-theme-item])
        (is (= :toggle-theme (first (e2e/sub-causa [:rf.causa/palette-recents])))
            "first invoke did not record :toggle-theme at head")
        ;; Re-invoke — the dedup in `recents/record` MUST keep `:toggle-
        ;; theme` at head without duplicating (the boost surface is what
        ;; the palette index's recents-aware ranking reads).
        (e2e/dispatch-causa [:rf.causa/palette-invoke toggle-theme-item])
        (let [recents (e2e/sub-causa [:rf.causa/palette-recents])]
          (is (= :toggle-theme (first recents))
              "second invoke did not keep :toggle-theme at head")
          (is (= 1 (count (filter #(= :toggle-theme %) recents)))
              ":toggle-theme appeared twice in recents — dedup regression"))))))
