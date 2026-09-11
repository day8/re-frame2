(ns day8.re-frame2-xray.views.edn-inspector-popup
  "Data-display popup overlay infra.

  ## What this is

  A drill-in surface that **floats over an Xray panel** and inspects
  a CLJS value at depth via the first-class edn-inspector widget
  (`views.edn-inspector`). Anchor scope is Xray-internal only — the
  popup overlays the Xray DOM; it never anchors to the debugged
  application's DOM.

  ## Public API

  Opening a popup is PROGRAMMATIC — dispatch the open event with a
  `mount-id` you mint, and the mounted stack view picks the entry up:

      (rf/dispatch [:rf.xray.edn-inspector-popup/open mount-id
                    {:value v :opts o}])

  `shell.cljs` mounts [[edn-inspector-popup-stack]] once at the shell's
  overlay container; [[edn-inspector-popup-stack-view]] is the Fresco
  boundary behind it, and [[popup-stack-tree]] is that same markup as
  pure data for the node lane. [[popup-chrome]] renders a SINGLE popup's
  chrome and is public so tests can drive it without the stack.

  rf2-bcub — an inline `[edn-inspector-popup value opts]` component used
  to sit here too, a form-2 wrapper minting its own `mount-id`. It is
  GONE: zero mounts tree-wide, and nothing but its own tests called it.

  `opts` (the `:opts` half of the open payload; all keys optional):

  - `:title`             — header label. Defaults `\"Inspect\"`.
  - `:panel-id`          — passed straight through to the embedded
                           `[edn-inspector value …]` so the popup's
                           expansion state is keyed under its own
                           `panel-id` (defaults to `:rf.xray.data-
                           display-popup/anon`).
  - `:default-expanded-depth` / `:max-inline-width` / `:max-depth` —
                           forwarded to the wrapped widget.
  - `:on-close`          — optional 0-arg fn called when the popup
                           closes (X / Esc / backdrop). The default
                           `:on-close` dispatches
                           `[:rf.xray.edn-inspector-popup/close mount-id]`
                           against the `:rf/xray` frame so the popup
                           closes itself via the registered handler.

  ## Per-popup mount-id

  The `mount-id` is the CALLER's — minted when the open event is
  dispatched, so a caller that wants to re-open a popup at a known id
  can. Each one:

  - identifies this popup's entry in the open-popups stack slot at
    `:rf.xray.edn-inspector-popup/stack`, so multiple popups stack
    without colliding,
  - composes the embedded widget's `:panel-id` —
    `[<panel-id-opt> mount-id]`-style namespacing — so the popup's
    expansion state is isolated from sibling popups + the panel
    underneath,
  - is the payload of the popup's `:close` event so each popup
    closes exactly itself.

  ## State model

  - `:rf.xray.edn-inspector-popup/stack` (vector of `mount-id`s) holds
    the open-popups stack. Top-of-stack is the topmost popup; it
    owns the Esc keystroke (the global Esc handler closes only the
    top entry, leaving any popups beneath it open).
  - Per-popup payload (the rendered value + opts snapshot) is held
    in app-db at `[:rf.xray.edn-inspector-popup/entries mount-id]`
    so a programmatic open call (e.g. from a context-menu handler
    in a future bead) survives shadow-cljs `:after-load` reloads
    and re-opening with the same id restores its expansion state
    (the underlying edn-inspector expansion slot is keyed on
    `[panel-id mount-id path]`, so the popup's contents survive
    unmount/remount when the same id is reused).

  ## Why this is its own namespace

  The umbrella widget at `views.edn-inspector.cljs` owns value
  rendering. This overlay infra is a separate surface — a wrapper
  component + its own state slot — so the widget file stays focused
  and the popup's lifecycle is self-contained. The shell wires the
  `edn-inspector-popup-stack` view into its overlay container; the
  `install!` fn here makes the registrations idempotent so that
  wire-up is a one-call addition.

  ## Z-index

  The popup paints **above the active Xray panel** but below any
  app-modal (palette / settings) — z-index `2147483640` (one tier
  below the segment-inspector at 2147483645, palette at 2147483646,
  settings at 2147483646, edit-popup at 2147483647). Stacking
  popups within this surface use sequential z-indexes derived from
  the stack position, so the topmost popup wins click + focus."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack type-scale]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; =========================================================================
;; state slots — public so tests + consumers can read/write them.
;; =========================================================================

(def stack-slot
  "App-db slot holding the open-popups stack (vector of `mount-id`s,
  oldest first; top-of-stack = last). Public so the Esc handler can
  peek the topmost entry and a consuming panel's 'close all' action
  can clear it."
  :rf.xray.edn-inspector-popup/stack)

(def entries-slot
  "App-db slot holding per-popup payload maps, keyed by `mount-id`.
  Each entry shape: `{:value <any> :opts <map>}`. Public so tests
  can assert the open-popup contract end-to-end without going
  through the view layer."
  :rf.xray.edn-inspector-popup/entries)

(def default-panel-id
  "Default `:panel-id` the embedded edn-inspector widget reads when
  the caller doesn't pass one. Distinct keyword (not reusing
  `:rf.xray.edn-inspector/anon`) so popup-vs-panel expansion state
  never collide on a coincidental empty path."
  :rf.xray.edn-inspector-popup/anon)

;; =========================================================================
;; pure helpers — JVM-portable so the state math has a unit-test surface
;; =========================================================================

(defn push-entry
  "Append `mount-id` to the open-popups stack vector. Idempotent —
  re-pushing an existing id moves it to the top (so `open` semantics
  match window-manager 'raise' behaviour).

  Pure data → vector."
  [stack mount-id]
  (let [without (filterv #(not= % mount-id) (or stack []))]
    (conj without mount-id)))

(defn pop-entry
  "Remove `mount-id` from the stack. Returns the stack vector with
  the entry filtered out (preserves order of the remaining ids).

  Pure data → vector."
  [stack mount-id]
  (filterv #(not= % mount-id) (or stack [])))

(defn top-entry
  "Return the top-of-stack mount-id, or nil when the stack is empty.

  Pure data → string-or-nil."
  [stack]
  (last (or stack [])))

(defn z-index-for
  "Compose the per-popup z-index. Base layer (`2147483640`) is just
  below the segment-inspector's `2147483645`; per-stack-position
  offset is `+ position` so deeper popups paint above earlier ones.

  Returns a number; the inline-style consumer stringifies via the
  browser's CSS engine."
  [position]
  (+ 2147483640 (or position 0)))

;; =========================================================================
;; subs + events
;; =========================================================================

(defn install-subs!
  "Register the popup's subscription surface.

  - `:rf.xray.edn-inspector-popup/stack`   — the open-popups stack vector
  - `:rf.xray.edn-inspector-popup/entries` — the per-popup payload map
  - `:rf.xray.edn-inspector-popup/open?`   — boolean; true when stack non-empty
  - `:rf.xray.edn-inspector-popup/top`     — top-of-stack mount-id or nil
  - `:rf.xray.edn-inspector-popup/entry`   — `[mount-id]` → that entry
                                            (so a view can pick the
                                            value + opts for its own
                                            mount without reading
                                            siblings)"
  []
  (rf/reg-sub stack-slot
    (fn [db _] (get db stack-slot)))

  (rf/reg-sub entries-slot
    (fn [db _] (get db entries-slot)))

  (rf/reg-sub :rf.xray.edn-inspector-popup/open?
    {:inputs [[stack-slot]]}
    (fn [[stack] _]
      (boolean (seq stack))))

  (rf/reg-sub :rf.xray.edn-inspector-popup/top
    {:inputs [[stack-slot]]}
    (fn [[stack] _]
      (top-entry stack)))

  (rf/reg-sub :rf.xray.edn-inspector-popup/entry
    {:inputs [[entries-slot]]}
    (fn [[entries] [_ mount-id]]
      (get entries mount-id))))

(defn install-events!
  "Register the popup's open / close / clear events. Every dispatch
  carries the `{:frame :rf/xray}` envelope at call time so React's
  click/keydown context pop doesn't leak the event to `:rf/default`
  (same fix as the App-DB segment-inspector + settings popup)."
  []
  (rf/reg-event :rf.xray.edn-inspector-popup/open
    (fn [{:keys [db]} [_ mount-id payload]]
      ;; payload shape: {:value <any> :opts <map>}
      {:db (-> db
          (update stack-slot push-entry mount-id)
          (assoc-in [entries-slot mount-id] payload))}))

  (rf/reg-event :rf.xray.edn-inspector-popup/close
    (fn [{:keys [db]} [_ mount-id]]
      {:db (-> db
          (update stack-slot pop-entry mount-id)
          (update entries-slot dissoc mount-id))}))

  (rf/reg-event :rf.xray.edn-inspector-popup/close-top
    ;; Esc handler — close the topmost popup only. No-op when stack
    ;; is empty.
    (fn [{:keys [db]} _]
      {:db (let [top (top-entry (get db stack-slot))]
        (if top
          (-> db
              (update stack-slot pop-entry top)
              (update entries-slot dissoc top))
          db))}))

  (rf/reg-event :rf.xray.edn-inspector-popup/close-all
    (fn [{:keys [db]} _]
      {:db (-> db
          (assoc stack-slot [])
          (assoc entries-slot {}))})))

(defn install!
  "Idempotent install for the popup's Xray-side registrations.
  Returns nil per the facade convention. Call this from
  `registry.cljs` (follow-on wire-up bead) alongside the other
  per-feature `install!` fns; calling it more than once re-registers
  the same handlers and is harmless."
  []
  (install-subs!)
  (install-events!)
  nil)

;; =========================================================================
;; styles
;; =========================================================================

(defn backdrop-style
  "Stack backdrop style. Honours the `positioning` arg the same way
  the other Xray modals do — `:absolute` confines the overlay to the
  parent cell (Story testbed mode); `:fixed` (default) spans the
  viewport in production. The backdrop is a click-trap (clicking it
  closes the topmost popup, mirroring the segment-inspector's
  click-outside-closes contract); the dialog stops propagation."
  [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position         (if absolute? "absolute" "fixed")
     :top              0
     :left             0
     :right            0
     :bottom           0
     :background       "rgba(0,0,0,0.20)"
     :display          "flex"
     :align-items      "flex-start"
     :justify-content  "center"
     :padding-top      (if absolute? "6%" "10vh")
     :z-index          (if absolute? 100 (z-index-for 0))}))

(defn dialog-style
  "Per-popup dialog chrome. Width caps at 560px so a deeply-nested
  value's horizontal scroll stays inside the dialog rather than
  forcing the whole shell to scroll."
  []
  {:width            "560px"
   :max-width        "92vw"
   :max-height       "72vh"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 20px 56px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 14px"
   :background       (:bg-2 tokens)
   :border-bottom    (str "1px solid " (:border-subtle tokens))})

(defn body-style []
  {:flex             1
   :overflow         "auto"
   :padding          "12px 14px"
   :background       (:bg-2 tokens)
   :color            (:text-primary tokens)})

(defn close-button-style []
  {:background       "transparent"
   :border           "none"
   :color            (:text-secondary tokens)
   :font-size        "16px"
   :line-height      1
   :cursor           "pointer"
   :padding          "2px 8px"
   :border-radius    "3px"})

;; =========================================================================
;; key handling
;; =========================================================================

(defn close-fn
  "Build the canonical close-handler for `mount-id`. Resolves to a
  0-arg fn that dispatches the popup's close event against the
  captured instance frame, or calls the caller-supplied `:on-close` if
  present. Public so tests can drive close paths without DOM.

  `opts` may carry `:on-close` (0-arg fn). When present the caller
  owns the close lifecycle and the default rf-dispatch is skipped.

  `frame` is the surrounding instance frame captured by `popup-chrome`
  at render time so the close dispatch lands on it, not a
  `{:frame :rf/xray}` literal. Defaults to `(rf/current-frame-id)` for
  the test seam / direct callers."
  ([mount-id opts] (close-fn mount-id opts (rf/current-frame-id)))
  ([mount-id {:keys [on-close]} frame]
   (fn []
     (if on-close
       (on-close)
       (rf/dispatch [:rf.xray.edn-inspector-popup/close mount-id]
                    {:frame frame})))))

(defn handle-keydown
  "Esc closes the topmost popup; other keys bubble. The popup's own
  key handler dispatches `:close-top` rather than its own
  `:close mount-id` so the layered-popups contract holds — if the
  user opens A, then B over A, Esc closes B and leaves A standing.

  `frame` is the surrounding instance frame captured by `popup-chrome`
  at render time. Defaults to `(rf/current-frame-id)` for the test
  seam."
  ([^js e] (handle-keydown e (rf/current-frame-id)))
  ([^js e frame]
   (when (= "Escape" (.-key e))
     (.preventDefault e)
     (.stopPropagation e)
     (rf/dispatch [:rf.xray.edn-inspector-popup/close-top]
                  {:frame frame}))))

;; =========================================================================
;; popup chrome — header + body + close affordance
;; =========================================================================

(defn- header-title
  "Render the dialog header label. The label is a caller-supplied
  string (default `\"Inspect\"`); we render it through `sans-stack`
  at the standard dialog title weight so popup chrome matches the
  rest of the Xray modal family."
  [mount-id title]
  [:span {:id          (str "rf-xray-edn-inspector-popup-title-" mount-id)
          :data-testid (str "rf-xray-edn-inspector-popup-title-" mount-id)
          :style {:color       (:text-primary tokens)
                  :font-weight 600
                  :font-size   (:body type-scale)
                  :font-family sans-stack}}
   title])

;; =========================================================================
;; the embedded widget's head — ONE PER LANE (rf2-k97c.3)
;; =========================================================================
;;
;; `popup-chrome` is shared by two lanes and the only thing that differs
;; between them is the head it embeds the value under. `views.edn-inspector`
;; already ships BOTH (the T4 dual-head facade): `edn-inspector` is the
;; Reagent `reg-view` head and `edn-inspector-view` is the Fresco boundary,
;; over one renderer.
;;
;; The choice is therefore a PARAMETER here rather than a branch: the head
;; is passed in, exactly as the tree's `as-child` islands pass `identity`
;; or `reagent.core/as-element` rather than reaching for a Fresco API. The
;; default is the Reagent one, so every existing direct caller of
;; `popup-chrome` — the node-lane rows — keeps the hiccup it already had,
;; and only the boundary opts in.
;;
;; THIS WAS FILED AS SCAFFOLDING WITH A DEFINED END, AND THAT PREDICTION WAS
;; WRONG — rf2-bcub MEASURED IT. The note here used to say that once
;; `edn-inspector-popup` became a Fresco body "or goes", [[fresco-inspector]]
;; would be the only lane and this parameter would go with its default.
;; `edn-inspector-popup` HAS now gone (rf2-bcub: zero mounts tree-wide), and
;; the parameter and its Reagent default STAY, because what depends on them
;; is the NODE LANE rather than that var: ELEVEN `popup-chrome` tests call it
;; without `:inspector` and so render through [[reagent-inspector]], and one
;; walks the body asserting a THREE-element fn mount — which is
;; `[ei/edn-inspector value opts]`, and which [[fresco-inspector]]'s
;; two-element `[ei/edn-inspector-view {…}]` would fail. [[reagent-inspector]]
;; is load-bearing once more in `edn_inspector_popup_wireup_cljs_test`, where
;; its head grading `:invalid` is what keeps the boundary row beside it
;; non-vacuous. Dropping either makes a live assertion UNWRITABLE, not idle.

(defn reagent-inspector
  "The embedded widget as a REAGENT head — `[ei/edn-inspector value opts]`,
  the shape this file emitted before rf2-k97c.3 and still the right one
  under a Reagent parent. `mount-id` is unused: the Reagent head is a
  form-2 component and mints its own per-mount identity."
  [_mount-id value opts]
  [ei/edn-inspector value opts])

(defn fresco-inspector
  "The embedded widget as a FRESCO BOUNDARY head —
  `[ei/edn-inspector-view {:mount-id … :value … :opts …}]`.

  `edn-inspector-view` REFUSES a missing or non-string `:mount-id`, and
  deliberately: a React function component has no form-2 outer body to
  allocate one in, so an id minted in its body would be fresh on every
  render and the widget would lose its expansion state each pass. The
  popup's own `mount-id` is exactly the stable name it asks for — it is a
  UUID minted once per popup and it already keys this popup's entry in
  app-db — so it is qualified with the surface's name and handed down."
  [mount-id value opts]
  [ei/edn-inspector-view
   {:mount-id (str "rf-xray-edn-inspector-popup-" mount-id)
    :value    value
    :opts     opts}])

(defn popup-chrome
  "Render a single popup's chrome — header + body + close button.
  Public so tests can drive the chrome without mounting the
  full stack view.

  `mount-id`   — string id; identifies the popup in app-db.
  `value`      — the CLJS value the embedded widget renders.
  `opts`       — option map (see ns docstring); merged with
                 `:panel-id` derived from `mount-id`.
  `positioning`— `:fixed` / `:absolute` (modal-positioning sub).
  `stack-pos`  — integer position in the stack; used for z-index
                 layering.
  `inspector`  — 3-arg fn `(fn [mount-id value opts] → hiccup)` emitting
                 the embedded widget's head. Defaults to
                 [[reagent-inspector]]; the Fresco boundary passes
                 [[fresco-inspector]]. See the section comment above."
  [{:keys [mount-id value opts positioning stack-pos inspector]
    :or   {inspector reagent-inspector}}]
  (let [{:keys [title panel-id default-expanded-depth
                max-inline-width max-depth on-close]
         :or   {title "Inspect"
                panel-id default-panel-id
                default-expanded-depth 2
                max-inline-width 60
                max-depth 16}} opts
        ;; Capture the surrounding instance frame at render time so the
        ;; deferred close handlers dispatch into it, not a
        ;; `:rf/xray` literal. popup-chrome renders inside the panels'
        ;; reg-views (and the stack reg-view), so current-frame-id resolves
        ;; through the React-context tier here.
        frame         (rf/current-frame-id)
        close-handler (close-fn mount-id {:on-close on-close} frame)
        keydown       (fn [^js e] (handle-keydown e frame))
        ;; Stack-aware z-index so multiple popups paint in order.
        ;; The shared backdrop owns z-index for position 0; dialogs
        ;; ride on top of their own backdrop tier.
        dialog-z      (z-index-for (inc (or stack-pos 0)))]
    ;; Shared backdrop + dialog scaffold. This popup keeps its own
    ;; (public) `backdrop-style` / `dialog-style`, its stack-position
    ;; z-index OVERRIDE (folded onto the backdrop style),
    ;; its `data-rf-mount-id` markers (via the `:*-extra` slots), the
    ;; backdrop -1 / dialog 0 tab-index split and the Esc-closes-top
    ;; `keydown` on both. `modal-chrome` owns the positioning attribute,
    ;; the click-outside dismiss (it `stopPropagation`s on the backdrop
    ;; so a stacked popup's backdrop click does not bubble to the popup
    ;; beneath), `a11y/dialog-attrs` + the `a11y/dialog-ref` focus trap.
    (modal-chrome/modal-chrome
      {:positioning          positioning
       :backdrop-style       (assoc (backdrop-style positioning) :z-index dialog-z)
       :dialog-style         (dialog-style)
       :on-dismiss           close-handler
       :labelled-by          (str "rf-xray-edn-inspector-popup-title-" mount-id)
       :backdrop-testid      (str "rf-xray-edn-inspector-popup-backdrop-" mount-id)
       :dialog-testid        (str "rf-xray-edn-inspector-popup-dialog-" mount-id)
       :on-backdrop-key-down keydown
       :on-dialog-key-down   keydown
       :backdrop-tab-index   -1
       :dialog-tab-index     0
       :backdrop-extra       {:data-rf-mount-id mount-id}
       :dialog-extra         {:data-rf-mount-id mount-id}}
      [:div {:style (header-style)}
       (header-title mount-id title)
       [:button {:data-testid (str "rf-xray-edn-inspector-popup-close-" mount-id)
                 :aria-label  "Close popup"
                 :title       "Close (Esc)"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (close-handler))
                 :style       (close-button-style)}
        "✕"]]
      [:div {:data-testid (str "rf-xray-edn-inspector-popup-body-" mount-id)
             :style       (body-style)}
       ;; Embed the first-class edn-inspector widget. We thread the
       ;; mount-id into the embedded widget's :panel-id so the
       ;; popup's expansion state is isolated from any other
       ;; edn-inspector mount on the page (the panel underneath
       ;; almost certainly already mounts the same value at the
       ;; same path).
       ;;
       ;; rf2-k97c.3 — CALLED rather than headed, so the lane's head
       ;; (Reagent `reg-view` or Fresco boundary) is the caller's to
       ;; choose. The opts map below is unchanged and is what both
       ;; lanes carry.
       (inspector mount-id value
                  {:panel-id (keyword "rf.xray.edn-inspector-popup"
                                      (str (name panel-id) "-" mount-id))
                   :default-expanded-depth default-expanded-depth
                   :max-inline-width       max-inline-width
                   :max-depth              max-depth})])))

;; =========================================================================
;; stack view — renders every open popup over the active panel
;; =========================================================================

(defn popup-stack-tree
  "The OPEN stack's markup, as a pure function of the values it is handed
  — `:stack`, `:entries` and `:positioning`. The empty/non-empty gate is
  the CALLER's, so this never answers nil.

  rf2-k97c.3 — split out of the view when the view became a Fresco
  boundary, so the stack stays drivable from the node lane without a
  React commit. A boundary's body may only run inside a React render
  window (`rf.fresco/sub` REFUSES outside one, naming the query), so
  calling the view var directly is no longer a way to get hiccup; this
  is. It is PURE of its arguments — no read, no `subscribe-once`, no
  fallback arity — which is precisely what the migration removes.

  The embedded widget's head is [[fresco-inspector]] here rather than
  `popup-chrome`'s Reagent default: this tree is what the boundary
  renders, and a `reg-view` head grades `:invalid` down the same codec
  arm a plain `defn` does."
  [{:keys [stack entries positioning]}]
  (into [:div {:data-testid "rf-xray-edn-inspector-popup-stack"
               :data-rf-popup-count (count stack)}]
        (map-indexed
          (fn [idx mount-id]
            (let [{:keys [value opts]} (get entries mount-id)]
              ;; rf2-a38l — KEYED FRAGMENT rather than `with-meta` on
              ;; the vector `popup-chrome` returns. Reagent reads that
              ;; metadata; Fresco's codec takes a literal `:key` from
              ;; an ATTRIBUTE MAP and reads Clojure metadata nowhere,
              ;; so under a boundary every popup in the stack would
              ;; lose its identity and React would reconcile them by
              ;; position. The key does NOT go in the opts map — that
              ;; is `popup-chrome`'s domain data, and `:key` is
              ;; React's.
              [:<> {:key mount-id}
               (popup-chrome
                 {:mount-id    mount-id
                  :value       value
                  :opts        opts
                  :positioning positioning
                  :stack-pos   idx
                  :inspector   fresco-inspector})]))
          stack)))

(rf.fresco/defview edn-inspector-popup-stack-view
  "Stack view that renders every open popup in z-index order — a FRESCO
  BOUNDARY (rf2-k97c.3), not an `rf/reg-view`. Mounted once at the
  shell's overlay container; each entry's payload is the value + opts
  the caller passed to `:open`.

  This view is the entry point for **programmatic** opens — a
  context-menu handler dispatches
  `[:rf.xray.edn-inspector-popup/open mount-id {:value v :opts o}]`
  and this view picks the entry up and renders it. Since rf2-bcub it is
  the ONLY entry point: the plain `[edn-inspector-popup v opts]`
  component that served **inline** opens (a panel controlling the popup
  imperatively from its own view tree) had no mount anywhere and is gone.

  ## WHY A BOUNDARY, AND WHAT ACTUALLY MOVED

  The frame reasoning is UNCHANGED from the `reg-view` this replaced: a
  boundary reads its frame from the same `re-frame.adapter.context` React
  context that `rf/frame-provider` writes, so the reads still resolve
  through the surrounding `:rf/xray` frame, and a plain `defn` here would
  still raise `:rf.error/no-frame-context` (Spec 006 §Plain-fn footgun,
  EP-0002 having removed the `:rf/default` floor). What changed is the
  OBSERVER — the reads are `rf.fresco/sub`, recorded by Fresco's own
  collector rather than by whichever reaction machinery the installed
  adapter supplies, which is this epic's coupling (3).

  ## CLOSED-STATE COST IS NOW WHAT IT ALWAYS CLAIMED TO BE

  One subscription and a gate. The `reg-view` read all three slots
  unconditionally and then gated, so its docstring's \"one subscribe + a
  `when`\" was aspirational; `rf.fresco/sub` records its edge WHERE THE
  READ HAPPENS, so moving the other two inside the `when` makes a closed
  stack hold one edge instead of three. A branch not taken contributes
  none — Fresco's documented behaviour (HD-002), not an accident.

  PUBLIC, like `edn-inspector-view` and `resizable-table-view` beside it:
  this is the head a Fresco parent writes, and the shell's root swap is
  what will write it. [[edn-inspector-popup-stack]] in front of it is the
  name the Reagent shell still mounts — see that bridge's docstring for
  why the two names sit this way round here."
  [_props]
  (let [stack (rf.fresco/sub [stack-slot])]
    (when (seq stack)
      (popup-stack-tree
        {:stack       stack
         :entries     (rf.fresco/sub [entries-slot])
         :positioning (rf.fresco/sub [:rf.xray/modal-positioning])}))))

;; ---- the migration bridge (rf2-k97c.3) ----------------------------------
;;
;; Xray's shell is still a `reg-view` tree rendered by the installed
;; adapter, and `shell.cljs` mounts `[edn-inspector-popup/edn-inspector-
;; popup-stack]` as a hiccup head — which a React component is not.
;;
;; `rf.fresco/as-component` is Fresco's own outward door for exactly this:
;; it answers a real React component for a boundary, which a React parent
;; (Reagent here) mounts UNDER THE FRAME IT IS ALREADY IN, taking the
;; frame from React context rather than from a second root. No second
;; root, no adapter-kind branch, no props ABI. The crossing carries an
;; EMPTY props map, which is the case `as-component`'s own note calls
;; sound — the inspected VALUES never cross it; they are read inside the
;; boundary and reach the widget as ordinary CLJS.
;;
;; NAMING: the mayor's 2026-09-10 ruling is that a boundary keeps the
;; NATURAL name and the caller is handed a PUBLIC bridge (#9581). The
;; constraint that forced #9578's opposite spelling applies HERE and is
;; why the names sit this way round: `shell.cljs` mounts this var BY NAME
;; and is fenced to the root-swap slice, so the public name has to go on
;; the thing that head site already writes. `machine_after_rings.cljs`
;; records the same fork from the other side.
;;
;; SCAFFOLDING WITH A DEFINED END. When the shell is itself a Fresco
;; tree, `edn-inspector-popup-stack-view` takes this name directly, the
;; `[:>]` goes, and both defs below are deleted.

(def ^:private edn-inspector-popup-stack-component
  "The React component [[edn-inspector-popup-stack-view]] presents as, for
  a non-Fresco parent. Declared ONCE at top level, as
  `rf.fresco/as-component`'s contract requires — deriving one per render
  mints a fresh element type and would remount every open popup, taking
  its expansion state with it."
  (rf.fresco/as-component edn-inspector-popup-stack-view))

(defn edn-inspector-popup-stack
  "The popup stack's public callable — what `shell.cljs` mounts as a
  hiccup head at the shell root.

  Since rf2-k97c.3 it is the migration bridge rather than the view:
  Reagent-shaped hiccup interoping to the React component
  [[edn-inspector-popup-stack-view]] presents as. The enclosing
  `rf/frame-provider` is what puts `:rf/xray` in React context for it.

  The empty/non-empty gate is inside the boundary, so this is always
  mounted and renders nothing while no popup is open — the same shape a
  mounted `reg-view` returning nil had.

  Callers wanting the MARKUP as data — the node-lane rows — build it from
  [[popup-stack-tree]] with the slots' values instead; this returns an
  interop vector, not a tree to walk."
  []
  [:> edn-inspector-popup-stack-component {}])
