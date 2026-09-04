(ns day8.re-frame2-xray.frame-switcher-cljs-test
  "CLJS coverage for the hardened L1 frame-switcher slot (rf2-iwwou).

  Verifies the public contract documented in `frame_switcher.cljs`:

  1. Pure helpers (`internal-frames`, `distinct-frames`) — JVM-runnable
     shape, no re-frame machinery.
  2. Subs `:rf.xray/current-frame` + `:rf.xray/available-frames`
     resolve through the canonical chain (spine focus-slot + cascades
     projection).
  3. Event `:rf.xray/select-frame` writes through the spine + fires
     the persistence fx.
  4. EDN round-trip (`->edn` / `<-edn`) survives malformed inputs.
  5. Hydration restores the persisted frame at install-time.
  6. The Cmd-K palette's `:palette/select-frame` verb dispatches
     through the canonical `:rf.xray/select-frame` event — the
     ribbon, the palette, and any future frame-aware feature share
     one write path."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) replaces the bespoke
  ;; `xray-init!` (preload/registry/trace three-liner): the `:all` reset
  ;; tier — install (== preload's alias) + registry + mount sentinels + the
  ;; trace-collector rings; `:post-reset` carries this suite's tail.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (frame-switcher/clear!)
                   (frame-switcher/set-storage-key! nil))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  ;; Pre-mount seeds (via `seed-trace-for-test!`) land in the
  ;; frameless ring but can't reach `:rf/xray`'s app-db `:trace-buffer`
  ;; slot until the frame is registered. Re-sync now so the cascade
  ;; subs see the seeded events on the next subscribe.
  (trace-collector/refresh-trace-rings!))

(defn- xray-db []
  (rf/app-db-value :rf/xray))

;; -------------------------------------------------------------------------
;; (1) Pure helpers — distinct-frames + internal-frames
;; -------------------------------------------------------------------------

(deftest internal-frames-includes-xray-and-pair
  (testing "spec/018 §8 I1 — the canonical filter set excludes Xray's
            own frame plus the future re-frame2-pair frame"
    (is (contains? frame-switcher/internal-frames :rf/xray))
    (is (contains? frame-switcher/internal-frames :rf/re-frame2-pair))
    (is (not (contains? frame-switcher/internal-frames :rf/default))
        ":rf/default is a meaningful user frame; never filtered out")))

(deftest distinct-frames-excludes-internal-frames-by-default
  (testing "spec/018 §8 I1 — `:rf/xray` and other tool frames are
            filtered out of the picker option list unless the power-
            user toggle re-includes them"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame :rf/xray}
                    {:dispatch-id 3 :frame :app/main}
                    {:dispatch-id 4 :frame :rf/re-frame2-pair}]
          default-frames (frame-switcher/distinct-frames cascades false)
          power-frames   (frame-switcher/distinct-frames cascades true)]
      (is (= [:rf/default :app/main] default-frames)
          "default — :rf/xray and :rf/re-frame2-pair are excluded")
      (is (= [:rf/default :rf/xray :app/main :rf/re-frame2-pair] power-frames)
          "power-user — tool frames included in first-seen order"))))

(deftest distinct-frames-drops-nil-frame-cascades
  (testing "an :ungrouped cascade has nil :frame — it must NOT show
            up in the picker (the picker labels would render `nil`)"
    (let [cascades [{:dispatch-id 1 :frame :rf/default}
                    {:dispatch-id 2 :frame nil}
                    {:dispatch-id 3 :frame :app/main}]]
      (is (= [:rf/default :app/main]
             (frame-switcher/distinct-frames cascades false))
          "nil frame is filtered out"))))

(deftest distinct-frames-preserves-first-seen-order
  (testing "the helper preserves first-seen order so the picker's
            option list reads stably — re-orderings would shuffle the
            user's selection visually on every new cascade"
    (let [cascades [{:dispatch-id 1 :frame :app/b}
                    {:dispatch-id 2 :frame :app/a}
                    {:dispatch-id 3 :frame :app/b}  ;; duplicate
                    {:dispatch-id 4 :frame :app/c}
                    {:dispatch-id 5 :frame :app/a}]] ;; duplicate
      (is (= [:app/b :app/a :app/c]
             (frame-switcher/distinct-frames cascades false))
          "duplicates collapse, order preserved"))))

;; -------------------------------------------------------------------------
;; (2) EDN round-trip — persistence (de)serialisation
;; -------------------------------------------------------------------------

(deftest edn-round-trips-keyword
  (let [edn (frame-switcher/->edn :rf/cart-frame)]
    (is (string? edn))
    (is (= :rf/cart-frame (frame-switcher/<-edn edn))
        "round-trip keeps the keyword intact")))

(deftest edn-load-tolerates-malformed-input
  (testing "the load path NEVER throws into init — malformed EDN
            yields nil; the caller treats nil as 'no selection'"
    (is (nil? (frame-switcher/<-edn "garbage"))
        "non-EDN string parses to nil")
    (is (nil? (frame-switcher/<-edn ""))
        "empty string parses to nil")
    (is (nil? (frame-switcher/<-edn "[1 2 3]"))
        "wrong shape (vector) parses to nil")
    (is (nil? (frame-switcher/<-edn "{:other :foo}"))
        "map without `:frame` key parses to nil")
    (is (nil? (frame-switcher/<-edn "{:frame \"not-a-keyword\"}"))
        "non-keyword `:frame` value parses to nil")))

;; -------------------------------------------------------------------------
;; (3) Subs — :rf.xray/current-frame + :rf.xray/available-frames
;; -------------------------------------------------------------------------

(defn- dispatch-trace
  "Seed the trace-bus with a single `:rf.event/dispatched` cascade row so
  the spine projection produces a cascade entry for the picker subs.
  Mirrors `shell_cljs_test.cljs`'s `dispatch-trace-ev` shape — the
  cascade key is `[frame dispatch-id]` and both must live under
  `:tags`."
  [dispatch-id frame-id]
  (trace-collector/seed-trace-for-test!
    {:id          dispatch-id
     :op-type     :rf.event
     :operation   :rf.event/dispatched
     :tags        {:rf.event/v       [:app/touch]
                   :frame       frame-id
                   :rf.trace/dispatch-id dispatch-id}}))

(deftest current-frame-sub-resolves-the-spine-slot
  (testing "the sub reads through `:rf.xray/focus-slot` so it re-fires
            on any code path that writes through the spine — picker,
            palette, headless drivers"
    (setup!)
    (rf/with-frame :rf/xray
      (is (nil? @(rf/subscribe [:rf.xray/current-frame]))
          "pre-selection — sub returns nil (no :focus slot written)")
      (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
      (is (= :rf/cart-frame @(rf/subscribe [:rf.xray/current-frame]))
          "post-selection — sub returns the frame the user picked"))))

(deftest available-frames-sub-empty-with-no-cascades
  (testing "no cascades yet — the sub returns an empty vec"
    (setup!)
    (rf/with-frame :rf/xray
      (is (= [] @(rf/subscribe [:rf.xray/available-frames]))
          "empty list, no picker options"))))

(deftest available-frames-sub-filters-tool-frames-and-preserves-order
  (testing "the sub composes off `:rf.xray/event-bundles` so it picks up
            every frame present in the trace stream — minus the
            internal-frames filter set (spec/018 §8 I1)"
    ;; Seed BEFORE setup! so the sub's first compute reads the populated
    ;; trace-bus atom (the sub falls back to `(trace-collector/buffer-for-test)` when
    ;; the db's `:trace-buffer` slot is unwritten). Mirrors the shell
    ;; tests' seed-then-subscribe order.
    (dispatch-trace 1 :rf/default)
    (dispatch-trace 2 :app/main)
    (dispatch-trace 3 :rf/xray)              ;; tool frame, filtered
    (setup!)
    (rf/with-frame :rf/xray
      (is (= [:rf/default :app/main]
             @(rf/subscribe [:rf.xray/available-frames]))
          "tool frames filtered, first-seen order"))))

;; -------------------------------------------------------------------------
;; (4) Event — :rf.xray/select-frame
;; -------------------------------------------------------------------------

(deftest select-frame-writes-through-spine
  (testing "the canonical event-fx dispatches the spine's `:rf.xray/
            set-frame` so every per-frame composite (App-DB Diff,
            Views, Routing) re-fires off the new frame's slot"
    (setup!)
    ;; Register a frame so the spine's epoch-history lookup doesn't
    ;; throw — the canonical handler queries rf/epoch-history.
    (rf/make-frame {:id :rf/cart-frame})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame])
      (is (= :rf/cart-frame (get-in (xray-db) [:focus :frame]))
          "the spine's :focus :frame slot lands on the picked frame")
      (is (= :rf/cart-frame (:target-frame (xray-db)))
          "the spine's :target-frame slot follows (rf2-ug1r6)"))))

(deftest select-frame-fires-persistence-fx
  (testing "the canonical event-fx fires the `:rf.xray.frame-switcher/
            persist` fx so the user's selection survives a reload"
    (setup!)
    (rf/make-frame {:id :rf/cart-frame})
    (let [persisted (atom nil)]
      ;; Swap the fx with a counting stub so we don't touch
      ;; localStorage in the test runtime (Node has no jsdom).
      ;; rf2-h1vqa4: route through the frame's :fx-overrides seam (fn-value
      ;; form) instead of re-registering the xray-owned fx id — a cross-ns
      ;; re-registration fails the frame's default-image assembly loud.
      (rf/make-frame {:id :rf/xray
                      :fx-overrides {:rf.xray.frame-switcher/persist
                                     (fn [_ctx frame-id]
                                       (reset! persisted frame-id))}})
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/select-frame :rf/cart-frame]))
      (is (= :rf/cart-frame @persisted)
          "the persist fx fired with the new frame id"))))

;; -------------------------------------------------------------------------
;; (5) Cmd-K palette routes through the canonical contract (rf2-iwwou)
;; -------------------------------------------------------------------------
;;
;; The palette's `:palette/select-frame` verb MUST dispatch the canonical
;; `:rf.xray/select-frame` event-fx — NOT the spine's `:rf.xray/set-
;; frame` primitive directly. That way every persistence /
;; instrumentation layer attached to the frame-switcher contract lives
;; behind one event surface, so the ribbon + the palette + any future
;; frame-aware feature share the same write path.

(deftest palette-select-frame-routes-through-canonical-contract
  (testing "the Cmd-K palette's `:palette/select-frame` invocation
            dispatches `:rf.xray/select-frame`, NOT the spine's
            `:rf.xray/set-frame` primitive directly. The end state
            (focus :frame + :target-frame + persistence) is identical
            to a ribbon picker click."
    (setup!)
    (rf/make-frame {:id :rf/cart-frame})
    (let [persisted (atom nil)]
      ;; rf2-h1vqa4: route through the frame's :fx-overrides seam (fn-value
      ;; form) instead of re-registering the xray-owned fx id — a cross-ns
      ;; re-registration fails the frame's default-image assembly loud.
      (rf/make-frame {:id :rf/xray
                      :fx-overrides {:rf.xray.frame-switcher/persist
                                     (fn [_ctx frame-id]
                                       (reset! persisted frame-id))}})
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/palette-open])
        (rf/dispatch-sync
          [:rf.xray/palette-invoke
           {:source :frame
            :id     :rf/cart-frame
            :label  "Switch focus to frame :rf/cart-frame"
            :action [:palette/select-frame :rf/cart-frame]}
           false]))
      (is (= :rf/cart-frame (get-in (xray-db) [:focus :frame]))
          "palette write lands on the same spine slot the ribbon picker uses")
      (is (= :rf/cart-frame (:target-frame (xray-db)))
          ":target-frame is also written — every per-frame composite re-fires")
      (is (= :rf/cart-frame @persisted)
          "the persist fx fired — palette writes are persisted too")
      (is (false? (boolean (:palette-open? (xray-db))))
          "palette closes on invocation as usual"))))

;; -------------------------------------------------------------------------
;; (6) View — frame-switcher-view reads the contract
;; -------------------------------------------------------------------------

;; The private expand-tree / find-by-testid copies were semantically identical
;; to `re-frame.test-helpers`; tests call `rf.test-helpers/find-by-testid` directly
;; (rf2-vj80u8 — no Xray walker facade). `hiccup-seq` (depth-first nodes over
;; the expanded tree) is not exposed by test-helpers, so it is kept as a thin
;; wrapper over `rf.test-helpers/expand-tree` for the `:option`-node filter below.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

(deftest view-renders-frame-dropdown-button-always
  (testing "rf2-pjjwh — the Figma chrome ribbon ALWAYS shows a frame
            dropdown button whose face shows the CURRENTLY-SELECTED frame
            value (live). rf2-ad7zx.14 — with a SINGLE frame the overlaid
            <select> is ENABLED (a native select with one option opens +
            shows it; it is not inert) and lists that lone frame as its
            only option, carrying its `✓`."
    (dispatch-trace 1 :app/main)
    (setup!)
    (rf/with-frame :rf/xray
      (let [tree    (frame-switcher/frame-switcher-view {})
            button  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame")
            label   (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-label")
            picker  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")
            options (filter (fn [n]
                              (and (vector? n) (= :option (first n))))
                            (hiccup-seq tree))]
        (is (some? button) "the Frame dropdown button always renders")
        (is (some? label) "the frame label renders")
        (is (= ":app/main" (last label))
            "the button face shows the currently-selected frame value (rf2-pjjwh)")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-chevron"))
            "the `▾` chevron renders, marking it a dropdown")
        (is (some? picker) "the native <select> overlay is present for a11y")
        (is (not (:disabled (second picker)))
            "rf2-ad7zx.14 — single-frame: the overlaid select is ENABLED so
             clicking it opens a 1-entry dropdown (not inert)")
        (is (nil? (:multiple (second picker)))
            "strictly single-select — no :multiple attribute even with one frame")
        (is (= 1 (count options))
            "exactly one option — the lone available frame")
        (is (= ":app/main" (:value (second (first options))))
            "the single option is bound to the available frame's value")
        (is (= "✓ :app/main" (last (first options)))
            "the lone frame is the active selection, carrying its checkmark")))))

(deftest view-renders-dropdown-when-multiple-frames
  (testing "the view renders a strictly-single-select <select> overlay
            when multiple distinct frames are present; the visible
            affordance's face shows the active frame value (rf2-pjjwh)"
    (dispatch-trace 1 :app/main)
    (dispatch-trace 2 :app/admin)
    (setup!)
    (rf/with-frame :rf/xray
      (let [tree   (frame-switcher/frame-switcher-view {})
            picker (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")
            label  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-label")]
        (is (some? picker) "dropdown renders for multi-frame")
        (is (= :select (first picker))
            "it's a <select>, not a custom multi-select")
        (is (nil? (:multiple (second picker)))
            "strictly single-select — no :multiple attribute")
        (is (not (:disabled (second picker)))
            "multi-frame — the select is enabled so the popup opens")
        ;; rf2-pjjwh — the button face shows the active frame value (the
        ;; head-frame default is the most recent event's frame, :app/admin).
        (is (string? (last label))
            "the button face shows a concrete frame value, not a placeholder")
        (is (not= "Frame" (last label))
            "the button no longer shows the static `Frame` placeholder")))))

;; -------------------------------------------------------------------------
;; (7) Storage-key plumbing — per-instance isolation
;; -------------------------------------------------------------------------

(deftest storage-key-defaults-to-canonical-value
  (testing "the default storage key matches the documented canonical
            string — hosts that don't override get a stable slot"
    (is (= "re-frame2.xray.frame-switcher.v1"
           frame-switcher/default-storage-key))
    (is (= frame-switcher/default-storage-key
           (frame-switcher/get-storage-key))
        "the runtime key matches the default at boot")))

(deftest storage-key-set-then-clear-round-trips
  (testing "Story testbeds override the key for per-scenario isolation;
            passing nil resets to the default"
    (frame-switcher/set-storage-key! "story.scenario-1.xray.frame.v1")
    (is (= "story.scenario-1.xray.frame.v1"
           (frame-switcher/get-storage-key)))
    (frame-switcher/set-storage-key! nil)
    (is (= frame-switcher/default-storage-key
           (frame-switcher/get-storage-key))
        "nil resets to the canonical default")))

;; -------------------------------------------------------------------------
;; (7) Realm grouping REMOVED (rf2-70owfr)
;; -------------------------------------------------------------------------
;;
;; The picker's realm `<optgroup>` grouping (`group-frames-by-realm` /
;; `multi-realm?` / the `:rf.xray/available-frame-realm-groups` sub) was removed
;; with the afdlyr realm-substrate collapse — a single default realm never
;; produced more than one group, so the grouping never branched away from the
;; flat option list. The picker renders the flat list directly; the public
;; partition is image -> frame (EP-0023).

;; -------------------------------------------------------------------------
;; (8) rf2-v8bule — stale view-scope frame reconciles to an available frame
;; -------------------------------------------------------------------------
;;
;; An explicitly-picked (or host-seeded) view-scope frame used to win
;; forever, with no membership check against `:rf.xray/available-frames`.
;; Once that frame left the stream (buffer cleared / frame destroyed) the
;; controlled `<select>` carried a value with NO matching `<option>`
;; (React "value not in options" warning + blank render + an unpickable
;; label). `:rf.xray/view-scope-frame` now clamps to availability.

(defn- current-frame []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/current-frame])))

(defn- available-frames []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/available-frames])))

(defn- picker-value+options
  "Render the ribbon frame-switcher view and return the controlled
  `<select>`'s `:value` plus the set of its `<option>` values, so a test
  can assert the value always matches a rendered option (the controlled-
  input invariant)."
  []
  (rf/with-frame :rf/xray
    (let [tree    (frame-switcher/frame-switcher-view {})
          picker  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")
          options (filter (fn [n] (and (vector? n) (= :option (first n))))
                          (hiccup-seq tree))]
      {:value         (:value (second picker))
       :option-values (set (map (fn [o] (:value (second o))) options))})))

(deftest stale-pin-still-has-a-matching-select-option
  (testing "rf2-v8bule — when the pinned view-scope frame leaves the stream
            (buffer cleared / frame destroyed) the controlled <select>'s
            value STILL matches a rendered <option>: the pinned frame is
            surfaced as its own option, so there is no React 'value not in
            options' mismatch. The scope itself is preserved (rf2-4vp5j
            empty-scope), so current-frame keeps the pin — it is NOT
            silently retargeted."
    ;; Two frames in the stream; the user pins the OLDER one (:app/admin).
    (dispatch-trace 1 :app/main)
    (dispatch-trace 2 :app/admin)
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-frame :app/admin]))
    (is (= :app/admin (current-frame)) "precondition — the pin is live")
    ;; The pinned frame leaves the stream; only :app/main remains available.
    (trace-collector/reset-for-test!)
    (dispatch-trace 3 :app/main)
    (is (= [:app/main] (available-frames))
        ":app/admin is gone from the available (pickable) set")
    (is (= :app/admin (current-frame))
        "the empty scope survives — the pin is NOT retargeted (rf2-4vp5j)")
    (let [{:keys [value option-values]} (picker-value+options)]
      (is (contains? option-values value)
          "the controlled <select> value matches a rendered option — no mismatch")
      (is (= ":app/admin" value) "the value is the pinned frame")
      (is (contains? option-values ":app/main")
          "the still-available frame is offered so the user can re-scope"))))

(deftest pinned-frame-with-empty-stream-has-matching-option
  (testing "rf2-v8bule — with the pinned frame gone AND no frames left, the
            disabled control still carries a value that matches its sole
            (pinned) option — no orphan value, no React warning"
    (dispatch-trace 1 :app/admin)
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-frame :app/admin]))
    (is (= :app/admin (current-frame)))
    ;; Every frame leaves the stream (buffer cleared).
    (trace-collector/reset-for-test!)
    (trace-collector/refresh-trace-rings!)
    (is (= [] (available-frames)) "no frames remain in the pickable set")
    (is (= :app/admin (current-frame)) "the empty scope survives")
    (let [{:keys [value option-values]} (picker-value+options)]
      (is (contains? option-values value)
          "the value still matches an option (the pinned frame is surfaced)")
      (is (= #{":app/admin"} option-values)
          "the pinned frame is the sole option"))))

(deftest no-pick-empty-stream-renders-clean-empty-select
  (testing "rf2-v8bule — with no pick and no frames, the select carries the
            empty value and renders no options (value \"\" with zero
            options is React's clean 'nothing selected' — no mismatch)"
    (setup!)  ;; no cascades seeded
    (is (nil? (current-frame)) "no frame to scope to")
    (let [{:keys [value option-values]} (picker-value+options)]
      (is (= "" value) "empty value")
      (is (empty? option-values) "no options"))))

(deftest available-pick-adds-no-synthetic-option
  (testing "rf2-v8bule — the surfaced option is added ONLY when the active
            frame is not available; in the normal case (the pick IS
            available) the option set is exactly the available frames — no
            synthetic duplicate"
    (dispatch-trace 1 :app/main)
    (dispatch-trace 2 :app/admin)
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-frame :app/main]))
    (is (= :app/main (current-frame)))
    (let [{:keys [value option-values]} (picker-value+options)]
      (is (= #{":app/main" ":app/admin"} option-values)
          "exactly the available frames — no synthetic duplicate")
      (is (contains? option-values value)))))
