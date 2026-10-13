(ns day8.re-frame2-xray.frame-switcher-cljs-test
  "CLJS coverage for the L1 frame-switcher slot: the pure
  `internal-frames` / `distinct-frames` filter, the
  `:rf.xray/available-frames` sub, the Cmd-K palette's route through the
  canonical `:rf.xray/select-frame` event, the rendered picker, and the
  persisted slot's storage key."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` at the `:all` reset
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

(deftest distinct-frames-excludes-internal-frames-unconditionally
  (testing "spec/018 §8 I1 — `:rf/xray` and the other tool frames are
            filtered out of the picker option list UNCONDITIONALLY:
            there is no re-include arity and no setting to drive one. An
            :ungrouped cascade's nil :frame is dropped too (the picker would
            label it `nil`), and duplicates collapse in first-seen order so
            the option list reads stably."
    (is (= [:app/b :rf/default :app/a]
           (frame-switcher/distinct-frames
             (map-indexed (fn [i frame] {:dispatch-id i :frame frame})
                          [:app/b :rf/xray :rf/default nil :app/a :app/b
                           :rf/re-frame2-pair :app/a]))))))

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

(deftest available-frames-sub-filters-tool-frames-and-preserves-order
  (testing "the sub composes off `:rf.xray/event-bundles` so it picks up
            every frame present in the trace stream — minus the
            internal-frames filter set (spec/018 §8 I1)"
    ;; Seed BEFORE setup! so the sub's first compute reads the seeded ring.
    (dispatch-trace 1 :rf/default)
    (dispatch-trace 2 :app/main)
    (dispatch-trace 3 :rf/xray)              ;; tool frame, filtered
    (setup!)
    (rf/with-frame :rf/xray
      (is (= [:rf/default :app/main]
             @(rf/subscribe [:rf.xray/available-frames]))
          "tool frames filtered, first-seen order"))))

;; -------------------------------------------------------------------------
;; (5) Cmd-K palette routes through the canonical contract
;; -------------------------------------------------------------------------

(deftest palette-select-frame-routes-through-canonical-contract
  (testing "the Cmd-K palette's `:palette/select-frame` invocation
            dispatches `:rf.xray/select-frame`, NOT the spine's
            `:rf.xray/set-frame` primitive directly. The end state
            (focus :frame + :target-frame + persistence) is identical
            to a ribbon picker click."
    (setup!)
    (rf/make-frame {:id :rf/cart-frame})
    (let [persisted (atom nil)]
      ;; Route through the frame's :fx-overrides seam (fn-value
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
      (is (= [:rf/cart-frame :rf/cart-frame :rf/cart-frame false]
             [(get-in (xray-db) [:focus :frame]) (:target-frame (xray-db)) @persisted
              (boolean (:palette-open? (xray-db)))])
          "the ribbon picker's spine slot, target frame and persistence, and the
           palette closes"))))

;; -------------------------------------------------------------------------
;; (6) View — frame-switcher-view reads the contract
;; -------------------------------------------------------------------------

;; Depth-first nodes over the expanded tree, for the `:option` filter.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

(deftest view-renders-frame-dropdown-button-always
  ;; The face shows the selected frame; with a single frame the native
  ;; <select> stays ENABLED and lists that frame, checked, and carries a
  ;; non-empty aria-label so screen readers announce its purpose on focus.
  (dispatch-trace 1 :app/main)
  (setup!)
  (rf/with-frame :rf/xray
    (let [tree    (static-shell-tree/frame-switcher-tree rf/dispatch)
          label   (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-label")
          picker  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")
          options (filter (fn [n]
                            (and (vector? n) (= :option (first n))))
                          (hiccup-seq tree))]
      (is (= [":app/main" :select false [[":app/main" "✓ :app/main"]] true]
             [(last label) (first picker) (boolean (:disabled (second picker)))
              (mapv (juxt (comp :value second) last) options)
              (let [aria-label (:aria-label (second picker))]
                (boolean (and (string? aria-label) (seq aria-label))))])))))

;; -------------------------------------------------------------------------
;; (7) Storage-key plumbing — per-instance isolation
;; -------------------------------------------------------------------------

(deftest storage-key-defaults-to-canonical-value
  ;; tools/xray/spec/007 publishes this key.
  (is (= "re-frame2.xray.frame-switcher.v1"
         frame-switcher/default-storage-key)))

(deftest storage-key-set-then-clear-round-trips
  (testing "a host can override the key; passing nil resets to the
            default"
    (frame-switcher/set-storage-key! "story.scenario-1.xray.frame.v1")
    (is (= "story.scenario-1.xray.frame.v1"
           (frame-switcher/get-storage-key)))
    (frame-switcher/set-storage-key! nil)
    (is (= frame-switcher/default-storage-key
           (frame-switcher/get-storage-key))
        "nil resets to the canonical default")))

;; -------------------------------------------------------------------------
;; (8) A pinned view-scope frame that leaves the stream keeps a matching option
;; -------------------------------------------------------------------------
;;
;; An explicitly-picked (or host-seeded) view-scope frame can leave the
;; stream (buffer cleared / frame destroyed). `:rf.xray/view-scope-frame`
;; keeps the pin rather than retargeting it, and the picker surfaces the
;; pinned frame as its own `<option>`. Without that option the controlled
;; `<select>` would carry a value with NO matching `<option>` (React "value
;; not in options" warning + blank render + an unpickable label).

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
    (let [tree    (static-shell-tree/frame-switcher-tree rf/dispatch)
          picker  (rf.test-helpers/find-by-testid tree "rf-xray-ribbon-frame-picker")
          options (filter (fn [n] (and (vector? n) (= :option (first n))))
                          (hiccup-seq tree))]
      {:value         (:value (second picker))
       :option-values (set (map (fn [o] (:value (second o))) options))})))

(deftest stale-pin-still-has-a-matching-select-option
  (testing "when the pinned view-scope frame leaves the stream
            (buffer cleared / frame destroyed) the controlled <select>'s
            value STILL matches a rendered <option>: the pinned frame is
            surfaced as its own option, so there is no React 'value not in
            options' mismatch. The empty scope itself is preserved, so
            current-frame keeps the pin — it is NOT silently retargeted."
    ;; Two frames in the stream; the user pins the OLDER one (:app/admin).
    (dispatch-trace 1 :app/main)
    (dispatch-trace 2 :app/admin)
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/select-frame :app/admin]))
    ;; The pinned frame leaves the stream; only :app/main remains available.
    (trace-collector/reset-for-test!)
    (dispatch-trace 3 :app/main)
    (is (= [:app/main] (available-frames))
        ":app/admin is gone from the available (pickable) set")
    (is (= {:value ":app/admin" :option-values #{":app/admin" ":app/main"}}
           (picker-value+options))
        "the value is the still-pinned frame, which is surfaced as an option
         beside the still-available one the user can re-scope to")))

(deftest no-pick-empty-stream-renders-clean-empty-select
  (testing "with no pick and no frames, the select carries the
            empty value and renders no options (value \"\" with zero
            options is React's clean 'nothing selected' — no mismatch)"
    (setup!)  ;; no cascades seeded
    (is (= {:value "" :option-values #{}} (picker-value+options)))))
