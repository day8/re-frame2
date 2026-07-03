(ns re-frame.story.panels-e2e.xray-embed-spine-e2e-cljs-test
  "Regression coverage for rf2-9k43e — the Story RHS Xray embed showed
  ONLY the final/focused event: no event spine, no way to navigate to a
  variant's PAST events in-place. The only workaround was the
  Ctrl+Shift+C / `Pop out` full-shell escape hatch (which DOES carry the
  L2 event spine).

  Mike RULED scope = A (2026-06-01): add the COMPACT, clickable
  recent-events SPINE to the embed so past events/epochs are focusable
  IN-PLACE; keep the full-shell pop-out for deep history. The spine
  REUSES the full-shell L2 component (`shell/event-list`) via the new
  `panels/mount-event-spine!` — NOT a parallel spine.

  This file pins both halves of the ruling:

  ## 1 — the embed RENDERS the spine band (hiccup level)

  The expanded embed's hiccup must carry the `story-xray-spine-band`
  region + its `:event-spine` mount slot (the `panel-host-component`
  argv driving `mount-event-spine!`). Pre-fix neither existed. Collapsing
  the embed drops the spine too (lazy-diff deferral, rf2-ba86n.19).

  ## 2 — selecting a PAST event focuses it IN-PLACE (contract level)

  Per project policy regressions pin at the CLJS contract layer, not
  Playwright (modelled on `sync_epoch_focus_e2e_cljs_test` for rf2-mdpfz).
  We seed THREE real host cascades through the trace bus, then dispatch
  the EXACT event the spine row's body-click fires
  (`:rf.xray/focus-event <past-id> <frame>`) for a PAST cascade — and
  assert the spine sub `:rf.xray/focus` AND the focus-keyed panel subs
  (`:rf.xray/app-db-current+diff`, `:rf.xray/epoch-pipeline`) re-bind to
  the CHOSEN PAST epoch, not the latest. Pre-fix the embed had no
  affordance to drive that focus from a past row at all; this is the
  behaviour the inline spine unlocks."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.story :as story]
            [re-frame.story.ui.xray-embed :as xray-embed]
            [re-frame.story.ui.state :as ui-state]
            [re-frame.story.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as xray-e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private variant-id :story.counter/loaded)

(defn- register-variant! []
  (story/reg-story :story.counter
    {:doc "Counter parent story for the spine e2e tests."})
  (story/reg-variant variant-id
    {:doc    "Counter seeded at 5 — exercises the inline spine path."
     :events [[:counter/initialise]]}))

;; ---- 1 · the embed renders the spine band (hiccup level) ----------------

(deftest mount-event-spine-resolves-to-callable
  (testing "rf2-9k43e — `:event-spine` resolves to a callable mount-fn
            (the isolated L2 `shell/event-list` via mount-event-spine!),
            reusing the same require/case shape every chip panel uses"
    (is (fn? (xray-embed/mount-fn-for :event-spine))
        "mount-fn-for :event-spine returned a callable mount-fn")
    (testing "the spine is NOT a chip-row panel (not in the catalog)"
      (is (not (contains? xray-embed/panel-ids :event-spine))
          ":event-spine is a persistent band, not a chip-selected panel"))))

(deftest expanded-embed-renders-spine-band
  (testing "rf2-9k43e — an EXPANDED embed renders the recent-events spine
            band ABOVE the chip-selected panel, with the :event-spine
            mount slot driving mount-event-spine!"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (e2e/select-variant! variant-id)
        (let [tree    (xray-embed/xray-embed-panel)
              band    (e2e/find-by-test-id tree "story-xray-spine-band")
              caption (e2e/find-by-test-id tree "story-xray-spine-caption")
              ;; The spine's mount slot is the `[panel-host-component
              ;; :event-spine …]` vector — fn-headed, with `:event-spine`
              ;; as the second element (the panel-id argv).
              spine-slot (some (fn [node]
                                 (when (and (vector? node)
                                            (fn? (first node))
                                            (= :event-spine (second node)))
                                   node))
                               (e2e/hiccup-seq band))]
          (is (some? band)
              "spine band present in the expanded embed (rf2-9k43e
               `[data-test=\"story-xray-spine-band\"]`)")
          (is (some? caption)
              "spine caption present so the affordance reads as the
               variant's clickable recent-events timeline")
          (is (some? spine-slot)
              "the :event-spine mount slot is present — drives
               mount-event-spine! (the isolated full-shell L2 list)"))))))

(deftest collapsed-embed-drops-spine-band
  (testing "rf2-9k43e + rf2-ba86n.19 — collapsing the embed drops the
            spine band too (no spine mount → no L2 compute) and restores
            it on expand"
    (e2e/with-story-and-xray-frames
      {:register-stories register-variant!}
      (fn []
        (e2e/select-variant! variant-id)
        (is (some? (e2e/find-by-test-id (xray-embed/xray-embed-panel)
                                        "story-xray-spine-band"))
            "expanded (default) embed shows the spine band")
        (ui-state/swap-state! ui-state/set-xray-embed-collapsed true)
        (is (nil? (e2e/find-by-test-id (xray-embed/xray-embed-panel)
                                       "story-xray-spine-band"))
            "COLLAPSED embed renders NO spine band → mount-event-spine!
             never fires → the L2 list's compute is deferred")
        (ui-state/swap-state! ui-state/set-xray-embed-collapsed false)
        (is (some? (e2e/find-by-test-id (xray-embed/xray-embed-panel)
                                        "story-xray-spine-band"))
            "expanding restores the spine band")))))

;; ---- 2 · selecting a PAST event focuses it IN-PLACE (contract) ----------
;;
;; The spine row's body-click handler (reused verbatim from the full
;; shell's `event-row`) dispatches `[:rf.xray/focus-event <id> <frame>]`.
;; Driving that event for a PAST cascade IS clicking a past spine row.
;; We assert the spine sub + the focus-keyed panel subs follow it
;; in-place to the chosen PAST epoch — the behaviour the inline spine
;; unlocks (pre-fix the embed only ever surfaced the latest/head event).

(defn- seed-three-host-cascades! []
  ;; THREE real dispatches through the trace bus → three cascades /
  ;; three epochs in Xray's `:rf/xray` frame. `:counter/inc` is the head.
  (xray-e2e/dispatch-host [:counter/inc])   ; value 6
  (xray-e2e/dispatch-host [:counter/dec])   ; value 5
  (xray-e2e/dispatch-host [:counter/inc]))  ; value 6 — HEAD

(deftest spine-row-click-focuses-past-event-in-place
  (testing "rf2-9k43e — dispatching the spine row's focus event for a
            PAST cascade re-binds :rf.xray/focus to that PAST epoch
            (NOT the head); the focus-keyed panels follow in-place"
    (xray-e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (seed-three-host-cascades!)
        (let [cascades  (xray-e2e/xray-cascades)
              head      (last cascades)        ; oldest-first → head is last
              ;; The OLDEST recorded cascade — a PAST event several steps
              ;; behind the head (the install fixture's `:counter/initialise`
              ;; lands first, then our three dispatches). This is the row a
              ;; user would click in the spine to inspect an earlier moment
              ;; of the run.
              past      (first cascades)
              head-id   (:dispatch-id head)
              past-id   (:dispatch-id past)
              past-frame (:frame past)]
          (is (<= 3 (count cascades))
              "precondition: at least three host cascades recorded through
               the trace bus (initialise + inc/dec/inc)")
          (is (not= head-id past-id)
              "precondition: the chosen PAST cascade is not the head")
          ;; Baseline: focus tracks the HEAD (LIVE) before any click.
          (is (= head-id (:dispatch-id (xray-e2e/sub-xray [:rf.xray/focus])))
              "baseline: the spine focus tracks the latest/head event
               (the only thing the pre-fix embed ever surfaced)")
          ;; Click a PAST spine row == dispatch its body-click event.
          (xray-e2e/dispatch-xray [:rf.xray/focus-event past-id past-frame])
          (let [focus (xray-e2e/sub-xray [:rf.xray/focus])]
            (is (= past-id (:dispatch-id focus))
                "spine focus re-bound to the chosen PAST cascade in-place")
            (is (= :retro (:mode focus))
                "focusing a non-head row pins the spine to RETRO — the
                 head no longer steals focus"))
          ;; The focus-keyed panels follow the chosen PAST epoch in-place.
          (let [{adb-epoch :epoch-id} (xray-e2e/sub-xray [:rf.xray/app-db-current+diff])
                {ep-status :status ep-epoch :epoch-id}
                (xray-e2e/sub-xray [:rf.xray/epoch-pipeline])
                past-epoch (:epoch-id (xray-e2e/sub-xray [:rf.xray/focus]))]
            (is (some? past-epoch)
                "the focused PAST cascade resolves a settling epoch-id")
            (is (= past-epoch adb-epoch)
                "App-db panel sub follows the PAST epoch in-place (not the
                 head) — the embed's app-db view reflects the chosen epoch")
            (is (= :focused ep-status)
                "Epoch panel resolves :focused on the chosen PAST epoch
                 (not :no-focus, not the head)")
            (is (= past-epoch ep-epoch)
                "Epoch panel sub follows the PAST epoch in-place")))))))
