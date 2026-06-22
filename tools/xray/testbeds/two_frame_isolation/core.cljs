(ns two-frame-isolation.core
  "Two-frame isolation testbed — THE canonical multi-frame isolation
  surface for Xray.

  ## Shape

  One page, two columns of attention:

    ┌──────────────────────────┬──────────────┐
    │  ABOVE frame (:above)    │              │
    │  ┌────────────────────┐  │   Xray      │
    │  │ standard-epochs    │  │   (inline)   │
    │  │ button ladder      │  │   frame      │
    │  └────────────────────┘  │   picker     │
    │  BELOW frame (:below)    │   switches   │
    │  ┌────────────────────┐  │   :above /   │
    │  │ standard-epochs    │  │   :below     │
    │  │ button ladder      │  │              │
    │  └────────────────────┘  │              │
    └──────────────────────────┴──────────────┘

  ONE app code path — the `standard-epochs.core/root` button ladder —
  mounted in TWO frame-providers (`:above` and `:below`) on the LEFT,
  with a Xray instance on the RIGHT (auto-mounted inline via the
  preload). The exercise IS observing the two frames diverge as the user
  drives each independently.

  ## Why standard-epochs ×2

  This testbed's single job is the cleanest possible per-frame ISOLATION
  proof: mount the SAME deck twice and watch the two reactive contexts stay
  independent. The routing surface has its own home (`routes_epochs`, the
  gate's Routing-panel deck); the multi-frame isolation gate assertion lives
  on the framework `testbeds/multi_frame` surface.

  `standard-epochs.core` registers every event / sub / view / cofx / fx /
  flow / schema ONCE globally at namespace load (requiring it below is
  what installs them); its `root` step-ladder view is the shared app code
  path both frames render. We do NOT call `standard-epochs.core/run` —
  that would mount the deck once into `#app` on the default frame. We
  reuse only its registrations + its `root` Var, and supply our own
  two-frame harness + mount here.

  ## Per-frame run-step events — isolation-preserving

  `standard-epochs.core/root` is the shared step-driver runner
  (`runner.core`), parameterised over `[host-frame prefix run-step-event]`.
  The runner's cursor lives in app-db's `:step` slot — and per-frame
  app-db gives EACH frame mount its OWN `:step`, so the two cursors are
  isolated by construction. This testbed registers a DISTINCT run-step event
  per frame (each closing over its frame's host-frame so the step's child
  dispatches land in the right frame) and mounts `root` with the frame's own
  id + a distinct testid prefix + that event:

    :above → (:above, \"standard-epochs-above\", :two-frame/run-step-above)
    :below → (:below, \"standard-epochs-below\", :two-frame/run-step-below)

  Two genuinely independent cursors, each driving events ONLY into its own
  frame — no shared cursor, correct per-frame isolation. Pressing Step (or a
  RUN-THIS-STEP button) in `:above` moves ONLY `:above`'s app-db (including
  its `:step`) / sub-cache / epoch history. There is NO Reagent atom for step
  state anywhere.

  ## What this proves — per-frame isolation

  Each `frame-provider`-rooted subtree is a fully isolated reactive
  context (Spec 006 §The cache is held inside the frame container): its
  own `app-db`, its own sub-cache, its own epoch history. Handlers and
  subs are registered ONCE globally; they resolve against whichever frame
  the dispatch envelope targets. The `reg-view`-injected `dispatch` /
  `subscribe` close over the surrounding frame-provider's frame id via
  React context, so the SAME view source (`standard-epochs.core/root`)
  produces two independent reactive contexts.

  The load-bearing rules this testbed exhibits:

    - Trace, events, and epochs are scoped to their frame: pressing a
      button in `:above` moves ONLY `:above`'s `:step` / `:views`;
      `:below` stays put (and vice-versa).
    - Subs do NOT reach across frames — every sub reads the current
      frame's `app-db` only. There is no cross-frame projection helper,
      no shared root state. The cross-frame anti-pattern is structurally
      impossible: the injected accessors only ever see the current frame.

  ## Test surface, not tutorial

  No deliberate bugs, no teaching layers, no anti-pattern demos
  (feedback_testbeds_are_test_surfaces). The exception / slow-fx / schema
  buttons in the standard-epochs ladder are FEATURES being exercised —
  they light up Xray's Issues surface legitimately, per frame.

  This testbed carries no spec.cjs; regression coverage for multi-frame
  isolation lives in the substrate contract tests (`npm run test:cljs`)
  + the Xray feature-matrix gate's `multi-frame isolation substrate`
  scenario (on the framework `testbeds/multi_frame` surface)."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Requiring `standard-epochs.core` installs every handler /
            ;; sub / view / cofx / fx / flow / schema ONCE globally and
            ;; gives us its `root` step-ladder view + its `steps` vector. We
            ;; mount that view twice below (with a distinct per-frame
            ;; host-frame + prefix + run-step event); we do NOT call its
            ;; `run` (which would mount it once into `#app` on the default
            ;; frame) and we do NOT reuse its `:standard-epochs/run-step`
            ;; event (that targets `:rf/default`).
            [standard-epochs.core :as se]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's `configure!` to seed `:project-root` so the Event
            ;; lens 'open' chip resolves a classpath-relative `:file` slot
            ;; to an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper: derives the open-in-editor
            ;; project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; The shared step-driver runner. We register a DISTINCT
            ;; run-step event per frame (each targeting that frame's
            ;; host-frame) so each mount's Step button drives ONLY its own
            ;; frame — the isolation proof. The cursor itself lives in each
            ;; frame's app-db `:step`.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; FRAME IDS
;; ============================================================================

(def frame-above :above)
(def frame-below :below)

;; ============================================================================
;; PER-FRAME RUN-STEP EVENTS — distinct driver per mount
;; ============================================================================
;;
;; The standard-epochs `root` is the step-driver runner; the cursor lives in
;; each frame's app-db `:step`. We register a distinct run-step event per
;; frame, each closing over its frame's id so the step's child dispatches
;; land in the right frame. Pressing Step in `:above` writes ONLY `:above`'s
;; `:step` and dispatches ONLY into `:above`. The step vector is the SAME one
;; standard-epochs owns (`se/steps`); only the frame differs.

(def run-step-above :two-frame/run-step-above)
(def run-step-below :two-frame/run-step-below)

(runner/reg-runner! {:id run-step-above :steps se/steps :host-frame frame-above})
(runner/reg-runner! {:id run-step-below :steps se/steps :host-frame frame-below})

;; ============================================================================
;; PER-FRAME FRAME-LOCAL FEATURES — the flow + app-schema rungs
;; ============================================================================
;;
;; `standard-epochs.core` registers its `:standard-epochs/derived` reg-flow
;; and its `[:auth] → [:map [:token :string]]` app-schema under
;; `(with-frame :rf/default …)` (EP-0002: both are CONTEXT-REQUIRED
;; FRAME-LOCAL). Those registrations therefore live ONLY in `:rf/default` —
;; the frame the SINGLE-frame standalone deck mounts on. This two-frame deck
;; mounts the same ladder in `:above` / `:below` instead, where neither the
;; flow nor the schema is registered. Without re-registering them per frame
;; the FLOW rung (step 5) would bump `:base` but NEVER recompute `:derived`
;; (no flow in this frame), and the APP-SCHEMA rung (step 19) would write
;; an int into `[:auth :token]` and it would STICK (no schema → no
;; validation → no rollback) — both rungs INERT in these frames (rf2-4279q4).
;;
;; The cleanest fix is to register the frame-local features in EACH frame the
;; deck actually mounts on, so the per-frame isolation the deck exists to
;; prove holds for flows + app-schemas too: a flow recompute / a schema
;; rollback in one frame is genuinely scoped to that frame's reactive
;; context. The flow body + schema mirror standard-epochs' (the schema slice
;; `se/AuthSlice` is reused directly; the flow is the same `:base × 2 →
;; :derived` declaration).

(defn- register-frame-local-features!
  "Register the `:standard-epochs/derived` flow + the `[:auth] :string`
  app-schema INTO `frame-id` so the FLOW (step 5) + APP-SCHEMA (step 19)
  rungs actually fire when the ladder is driven in that frame. Both are
  context-required frame-local (EP-0002): the `with-frame` scope names the
  frame they install into. Run at the TOP LEVEL (from `run`, AFTER the
  frame is `reg-frame`'d) — never inside a handler cascade."
  [frame-id]
  (rf/with-frame frame-id
    (rf/reg-flow
      {:id          :standard-epochs/derived
       :inputs      [[:base]]
       :derive      (fn [base] (* 2 (or base 0)))
       :output-path [:derived]
       :doc         "Derived = 2 × :base (two-frame deck, per-frame). Recomputes on the post-handler flows pass."})
    (rf/reg-app-schema [:auth] {:schema se/AuthSlice})))

;; ============================================================================
;; ROOT VIEW — the standard-epochs ladder mounted twice, one per frame-provider
;; ============================================================================

(reg-view frame-card [frame-label host-frame prefix run-step-event]
  (let [accent (case frame-label
                 "above" "#2b7"
                 "below" "#36c"
                 "#444")]
    [:section {:data-testid (str frame-label "-frame")
               :style {:border        (str "1px solid " accent)
                       :border-radius "6px"
                       :padding       "0.75em 1em"
                       :background    (case frame-label
                                        "above" "#f7fff9"
                                        "below" "#f5f8ff"
                                        "#fafafa")
                       :margin        "0.5em 0"}}
     [:header {:style {:display "flex" :justify-content "space-between"
                       :align-items "baseline" :margin-bottom "0.5em"}}
      [:h3 {:style {:margin 0 :color accent :text-transform "uppercase"}}
       frame-label " frame "
       [:small {:style {:color "#666" :font-weight "normal"
                        :text-transform "none"}}
        "(" frame-label ")"]]
      [:span {:style {:font-size "11px" :color "#888"}}
       "isolated reactive context"]]
     ;; The SHARED app code path: the standard-epochs step-driver deck.
     ;; The reg-view-injected dispatch / subscribe close over THIS
     ;; frame-provider's frame id, so the same view source drives two
     ;; independent reactive contexts; the per-frame host-frame +
     ;; run-step event keep this mount's `:step` cursor + dispatches
     ;; isolated.
     [se/root host-frame prefix run-step-event]]))

(reg-view root []
  [:div {:data-testid "two-frame-isolation-root"
         :style {:font-family "system-ui, sans-serif"
                 :padding     "1em"
                 :max-width   "820px"}}
   [:header {:style {:margin-bottom "1em"}}
    [:h2 {:style {:margin 0}} "Two-frame isolation"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Same app — the "
     [:code "standard-epochs"]
     " step ladder — mounted in two isolated reactive contexts. Each "
     [:code "frame-provider"]
     " below renders the same view source against a separate "
     [:code "app-db"] " + sub-cache + epoch history (and its OWN runner "
     [:code ":step"] " cursor). Drive one frame; the other stays put. "
     "Switch frames in Xray (right) with the frame picker to compare "
     "trace / events / epochs per frame."]]
   [:div {:style {:display "flex" :flex-direction "column"}}
    [rf/frame-provider-existing {:frame frame-above}
     [frame-card "above" frame-above "standard-epochs-above" run-step-above]]
    [rf/frame-provider-existing {:frame frame-below}
     [frame-card "below" frame-below "standard-epochs-below" run-step-below]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; The open-in-editor project-root is derived from the build environment
;; (the build-time `re-frame.testbed.config/checkout-root` goog-define joined
;; with this testbed's tool-relative subdir), not a hardcoded personal
;; path. `?checkout-root=<path>` still overrides per session. See
;; `re-frame.testbed.config` for the cross-platform mechanism.
(defn- resolve-source-root []
  (testbed-config/resolve-source-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so the preload's auto-open reads
  ;; the right project-root on its first paint of any chip.
  (xray-config/configure! {:rf.xray/project-root (resolve-source-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Register the two frames. There is no routing in the standard-epochs
  ;; deck, so neither frame owns the URL — both are plain isolated
  ;; contexts. Each frame's `:initial-events` seed runs the standard-epochs
  ;; reset event, synchronously, against that frame's empty db. The
  ;; `:standard-epochs/reset` handler is registered once globally and
  ;; resolves against whichever frame the dispatch envelope targets, so
  ;; per-frame state evolution is automatic.
  (rf/reg-frame frame-above {:initial-events [[:standard-epochs/reset]]})
  (rf/reg-frame frame-below {:initial-events [[:standard-epochs/reset]]})
  ;; Register the frame-local FLOW + APP-SCHEMA into BOTH frames so the
  ;; step-5 (flow) and step-19 (app-schema) rungs fire here instead of
  ;; being inert (standard-epochs registers them only in :rf/default —
  ;; rf2-4279q4). reg-flow / reg-app-schema require a LIVE frame, so this
  ;; runs AFTER reg-frame. Top-level, never inside a handler cascade.
  (register-frame-local-features! frame-above)
  (register-frame-local-features! frame-below)
  ;; Re-seed each frame AFTER its flow is registered so the
  ;; `:standard-epochs/derived` flow computes its initial value (:derived =
  ;; 2 × :base) on this drain's flows pass — mirroring the standalone deck,
  ;; where the flow is registered (ns-load, in :rf/default) BEFORE `run`'s
  ;; seed dispatch. The `:initial-events` seed above ran while the frame had
  ;; no flow yet, so `:derived` would otherwise stay absent until the first
  ;; step-5 press. The re-seed leaves each frame at the deterministic
  ;; baseline (`:base 1`, `:derived 2`, `[:auth :token]` "seed-token") the
  ;; isolation assertions read.
  (rf/with-frame frame-above (rf/dispatch-sync [:standard-epochs/reset]))
  (rf/with-frame frame-below (rf/dispatch-sync [:standard-epochs/reset]))
  (rdc/render react-root [root]))
