(ns day8.re-frame2-xray.views.edn-inspector-popup
  "Data-display popup overlay infra.

  ## What this is

  A drill-in surface that **floats over an Xray panel** and inspects
  a CLJS value at depth via the first-class edn-inspector widget
  (`views.edn-inspector`). Anchor scope is Xray-internal only — the
  popup overlays the Xray DOM; it never anchors to the debugged
  application's DOM.

  ## Public API

      [edn-inspector-popup value]
      [edn-inspector-popup value opts]

  `opts` keys (all optional):

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

  Two-arg overload matches `[edn-inspector value opts]` (D2=a) so the
  popup's call shape is the same as the widget it wraps.

  ## Per-mount UUID

  Each `[edn-inspector-popup …]` mount allocates a fresh UUID
  `mount-id` on first render via the form-2 outer fn. The `mount-id`:

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
    :<- [stack-slot]
    (fn [stack _]
      (boolean (seq stack))))

  (rf/reg-sub :rf.xray.edn-inspector-popup/top
    :<- [stack-slot]
    (fn [stack _]
      (top-entry stack)))

  (rf/reg-sub :rf.xray.edn-inspector-popup/entry
    :<- [entries-slot]
    (fn [entries [_ mount-id]]
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
;; mount-id generator
;; =========================================================================

(defn- gen-mount-id
  "Generate a stable per-mount UUID. Public so programmatic callers
  (a context-menu handler that wants to open a popup at a known id)
  can mint a matching id ahead of time."
  []
  (str (random-uuid)))

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
                 layering."
  [{:keys [mount-id value opts positioning stack-pos]}]
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
       [ei/edn-inspector value
        {:panel-id (keyword "rf.xray.edn-inspector-popup"
                            (str (name panel-id) "-" mount-id))
         :default-expanded-depth default-expanded-depth
         :max-inline-width       max-inline-width
         :max-depth              max-depth}]])))

;; =========================================================================
;; public component — `edn-inspector-popup`
;; =========================================================================

(defn edn-inspector-popup
  "Floating popup overlay that wraps `[ei/edn-inspector value opts]`.
  Form-2 Reagent component: the outer fn allocates a stable
  `mount-id` (UUID) in closure; the inner fn reads the popup's
  stack position from app-db (so multiple popups
  layer in z-index order) and renders the chrome.

  The popup is **Xray-internal** — the backdrop spans the Xray shell
  only (or the Story cell when `:rf.xray/modal-positioning` resolves
  to `:absolute`); it never anchors to the debugged application's DOM.

  The auto-generated UUID on mount lives until unmount; two
  side-by-side `[edn-inspector-popup …]` mounts get independent
  expansion state.

  Usage:

      [edn-inspector-popup value]
      [edn-inspector-popup value {:title \"Sub :app/cart\"
                                 :panel-id :rf.xray.sub-detail
                                 :default-expanded-depth 3}]

  The popup self-registers in the stack slot on first render and
  self-removes on unmount via the `:close` event in `on-close`. A
  caller can pass an explicit `:on-close` to override the default
  rf-dispatch (e.g. for a parent component that controls its own
  open/closed flag)."
  ([value] (edn-inspector-popup value nil))
  ([_value _opts]
   (let [mount-id (gen-mount-id)]
     (fn [value opts]
       (let [positioning @(rf/subscribe [:rf.xray/modal-positioning])
             stack       @(rf/subscribe [stack-slot])
             pos         (or (.indexOf (vec (or stack [])) mount-id)
                             -1)]
         (popup-chrome
           {:mount-id     mount-id
            :value        value
            :opts         opts
            :positioning  positioning
            :stack-pos    (max 0 pos)}))))))

;; =========================================================================
;; stack view — renders every open popup over the active panel
;; =========================================================================

(rf/reg-view edn-inspector-popup-stack
  "Stack view that renders every open popup in z-index order. Mount
  this once at the shell's overlay container (follow-on bead wires
  it into `mount.cljs`); each entry's payload is the value + opts
  the caller passed to `:open`.

  Closed-state cost is one subscribe + a `when` — when the stack is
  empty the body short-circuits to nil.

  This view is the entry point for **programmatic** opens — a
  context-menu handler dispatches
  `[:rf.xray.edn-inspector-popup/open mount-id {:value v :opts o}]`
  and this view picks the entry up and renders it. The plain
  `[edn-inspector-popup v opts]` component is the entry point for
  **inline** opens (a panel that wants to control the popup
  imperatively from its own view tree).

  `reg-view`-registered so its `subscribe` / `dispatch` calls resolve
  against the frame it is mounted under rather than silently routing
  to `:rf/default` (the class of bug
  `:rf.warning/plain-fn-under-non-default-frame-once` catches). The
  view-id is auto-derived from the symbol per the canonical reg-view
  convention; the surrounding shell mounts this stack under `:rf/xray`,
  so all its `subscribe` calls resolve through the React-context tier
  to the surrounding frame."
  []
  (let [stack   @(rf/subscribe [stack-slot])
        entries @(rf/subscribe [entries-slot])
        positioning @(rf/subscribe [:rf.xray/modal-positioning])]
    (when (seq stack)
      (into [:div {:data-testid "rf-xray-edn-inspector-popup-stack"
                   :data-rf-popup-count (count stack)}]
            (map-indexed
              (fn [idx mount-id]
                (let [{:keys [value opts]} (get entries mount-id)]
                  (with-meta
                    (popup-chrome
                      {:mount-id    mount-id
                       :value       value
                       :opts        opts
                       :positioning positioning
                       :stack-pos   idx})
                    {:key mount-id})))
              stack)))))
