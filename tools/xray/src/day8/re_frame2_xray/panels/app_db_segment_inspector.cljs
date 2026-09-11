(ns day8.re-frame2-xray.panels.app-db-segment-inspector
  "App-DB segment inspector popup (rf2-e9tb0).

  Per `tools/xray/spec/004-App-DB-Diff.md` §Clickable path segments,
  each segment in an App-DB Diff section breadcrumb is independently
  clickable. A click opens this transient overlay showing the app-db
  value at the clicked path-prefix — `:cart` shows app-db at `[:cart]`,
  `:items` shows app-db at `[:cart :items]`, and so on.

  Replaces the dropped pinned-watches strip (Mike 2026-05-19 Q13). The
  diff already identifies changes surgically; on-demand inspection at
  any prefix removes the need to pin paths up-front.

  ## Modal-light overlay

  Mounted at the shell-view root alongside the other modals so its
  subscribes resolve through the shell's `frame-provider` to
  `:rf/xray` (the same mount discipline as `settings.popup`,
  `palette`, and `popover.causality` — see those files' docstrings for
  the rationale). Closed-state cost is one subscribe + a `when` — the
  body short-circuits to nil when `:rf.xray/segment-inspector-open?`
  is false.

  Visually the popup is lighter than the Settings modal — a smaller
  centred dialog with a 15% dim backdrop, mirroring the causality
  popover's chrome (transient overlay, not a full-window modal). The
  body renders the value at the inspected path via
  `theme/data-edn/inspect` — the same primitive every L4 detail
  panel uses, so the renderer is uniform with the diff body.

  ## Three close affordances

  Per the bead's scope:

    1. `Esc` — handled by the popup's keydown listener.
    2. Click outside (backdrop) — backdrop's `:on-click` dispatches
       close; the dialog stops propagation so click-throughs on its
       body don't close.
    3. ✕ button in the header.

  ## Why every dispatch carries `{:frame :rf/xray}`

  Sister fix to rf2-smvvz (Settings popup) + rf2-w8lxg (Causality
  popover). Subscribes resolve through the React-context tier at
  render time; dispatches from `:on-click` / `:on-key-down` fire AFTER
  React pops the context, so a bare dispatch would otherwise resolve no
  frame at all and RAISE `:rf.error/no-frame-context` — there is no
  `:rf/default` floor under EP-0002 — and the close handler would fail
  rather than close. Carrying
  the `{:frame :rf/xray}` opt at call time pins the envelope to
  Xray's frame regardless of click-time React context."
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens sans-stack mono-stack type-scale]]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

;; ---- subs ----------------------------------------------------------------

(defn install-subs! []
  ;; The slot shape: nil = closed; `{:path <vec>}` = open at that path.
  ;; Storing the path in app-db lets the popup survive shadow-cljs
  ;; `:after-load` re-renders without losing the user's context.
  (rf/reg-sub :rf.xray/segment-inspector-slot
    (fn [db _]
      (get db :segment-inspector)))

  (rf/reg-sub :rf.xray/segment-inspector-open?
    {:inputs [[:rf.xray/segment-inspector-slot]]}
    (fn [[slot] _]
      (some? slot)))

  (rf/reg-sub :rf.xray/segment-inspector-path
    {:inputs [[:rf.xray/segment-inspector-slot]]}
    (fn [[slot] _]
      (:path slot)))

  ;; Resolve the value at the inspected path against the FOCUSED epoch's
  ;; state — the SAME image the App-DB panel body renders.
  ;;
  ;; rf2-jmucu — the popup must agree with the panel body it pops out of.
  ;; The body renders `:rf.xray/app-db-current+diff`'s `:value`, which
  ;; (post-rf2-02j4r) is the FOCUSED epoch's `:db-after` — the epoch's
  ;; own post-state, moving per epoch as the user scrubs. Reading the
  ;; popup through `:rf.xray/target-frame-db` (the LIVE deref) instead
  ;; made the popup disagree with the body off-head: scrub back to an
  ;; earlier epoch and the body showed THAT epoch's value while the
  ;; popup showed live state (everything later events did) — the same
  ;; later-event-bleed class rf2-02j4r killed in the body, surviving in
  ;; the popup. Reading through `app-db-current+diff`'s `:value` is the
  ;; single seam that keeps popup + body identical at every scrub
  ;; position: on-head `:value` == live; off-head it follows the focused
  ;; epoch; cold boot (no focus) it falls back to the live db (same as
  ;; the body's empty-state). Empty path returns the whole value (the
  ;; inspector renders the root map).
  (rf/reg-sub :rf.xray/segment-inspector-value
    {:inputs [[:rf.xray/segment-inspector-path] [:rf.xray/app-db-current+diff]]}
    (fn [[path {:keys [value]}] _]
      (if (seq path)
        (get-in value path)
        value))))

;; ---- events --------------------------------------------------------------

(defn install-events! []
  (rf/reg-event :rf.xray/open-segment-inspector
    (fn [{:keys [db]} [_ path]]
      {:db (assoc db :segment-inspector {:path (vec path)})}))

  (rf/reg-event :rf.xray/close-segment-inspector
    (fn [{:keys [db]} _]
      {:db (dissoc db :segment-inspector)})))

;; ---- styles --------------------------------------------------------------
;;
;; Backdrop honours `:rf.xray/modal-positioning` (rf2-om6fa) so
;; Story testbeds get an in-cell overlay rather than a viewport-spanning
;; one. Same shape as the causality popover.

(defn- backdrop-style [positioning]
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
     :z-index          (if absolute? 101 2147483645)}))

(defn- dialog-style []
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

(defn- header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 14px"
   :background       (:bg-2 tokens)
   :border-bottom    (str "1px solid " (:border-subtle tokens))})

(defn- body-style []
  {:flex             1
   :overflow         "auto"
   :padding          "12px 14px"
   :background       (:bg-2 tokens)
   :font-family      mono-stack
   :font-size        (:body type-scale)
   :color            (:text-primary tokens)})

(defn- close-button-style []
  {:background       "transparent"
   :border           "none"
   :color            (:text-secondary tokens)
   :font-size        "16px"
   :line-height      1
   :cursor           "pointer"
   :padding          "2px 8px"
   :border-radius    "3px"})

;; ---- key handling --------------------------------------------------------

(defn- handle-keydown
  "Build the Esc-closes keydown handler, closing over the captured
  frame-aware `dispatch` (rf2-nesy9). Other keys bubble — the global
  keybindings (palette, causality) still resolve when the popup is open."
  [dispatch]
  (fn [^js e]
    (when (= "Escape" (.-key e))
      (.preventDefault e)
      (.stopPropagation e)
      (dispatch [:rf.xray/close-segment-inspector]))))

;; ---- view ----------------------------------------------------------------

(defn- header-title
  "Render the dialog header: a label + the inspected path. Empty path
  inspects the root db; the title says 'app-db (root)' so the user
  isn't left wondering what they're looking at."
  [path]
  [:span {:id          "rf-xray-segment-inspector-title"
          :data-testid "rf-xray-segment-inspector-title"
          :style {:color       (:text-primary tokens)
                  :font-weight 600
                  :font-size   (:body type-scale)
                  :font-family sans-stack
                  :display     "inline-flex"
                  :align-items "baseline"
                  :gap         "8px"}}
   [:span {:style {:color (:text-secondary tokens)}} "app-db at"]
   [:code {:style {:color       (:accent tokens)
                   :font-family mono-stack
                   :font-size   "12px"}}
    (if (seq path)
      (f/format-edn (vec path))
      "(root)")]])

(defn popup-tree
  "The OPEN popup's markup, as a pure function of the values it is handed
  — `:path`, `:value` and `:positioning`. The open/closed gate is the
  CALLER's, so this never answers nil.

  rf2-k97c.3 — split out of the view when the view became a Fresco
  boundary, so the dialog stays drivable from the node lane without a
  React commit. A boundary's body may only run inside a React render
  window (`rf.fresco/sub` REFUSES outside one, naming the query), so
  calling [[Popup]] is no longer a way to get hiccup; this is. It is PURE
  of its arguments — no read, no `subscribe-once`, no fallback arity.

  THE DISPATCHES ARE FRAME-CARRYING, captured here at render time
  (rf2-nesy9). `rf/current-frame-id` answers the declared frame inside a
  Fresco body — it neither reads nor dispatches, so the boundary's
  refusal tier does not touch it — and an explicitly carried
  `{:frame <id>}` still answers, because that tier deletes the ambient
  FIND and not the carrying. This is what replaced `reg-view`'s lexically
  injected bare `dispatch`, which a `defview` body does not bind.

  `:inspector` is the 2-arg `(fn [value node-key] → hiccup)` emitting the
  value-rendering head, and it DEFAULTS to the production-correct
  `edn/inspect-view`, so the boundary passes nothing and a caller who
  forgets gets the right head. It is a parameter rather than a branch for
  the reason the tree's `as-child` islands are: the spelling is the
  caller's, not a Fresco API. Its one other caller is the node lane, which
  substitutes the Reagent twin `edn/inspect` — the two heads share ONE
  private renderer and differ only in how each resolves the expansion
  slots and its per-mount identity, none of which a node-lane row asserts
  on. That substitution is what lets those rows walk the widget at all:
  a boundary may not be invoked as a hiccup render fn, so a walker that
  expands function components raises inside it."
  [{:keys [path value positioning inspector]
    :or   {inspector edn/inspect-view}}]
  (let [frame       (rf/current-frame-id)
        dispatch    (fn [ev] (rf/dispatch ev {:frame frame}))
        on-keydown  (handle-keydown dispatch)]
    ;; rf2-7oxvd — shared backdrop + dialog scaffold. This popover keeps
    ;; its own `backdrop-style` / `dialog-style` (the dim/blur/size
    ;; diverge from the other modals) + its own Esc handler on BOTH the
    ;; backdrop and the dialog, and the historical backdrop -1 / dialog 0
    ;; tab-index split. The `data-rf-xray-mode` marker rides in via
    ;; `:dialog-extra`. `modal-chrome` owns the positioning attribute,
    ;; the click-outside dismiss, the `a11y/dialog-attrs` (rf2-7389r
    ;; role/aria-modal/accessible name from the title id) and the
    ;; `a11y/dialog-ref` focus trap.
    (modal-chrome/modal-chrome
      {:positioning          positioning
       :backdrop-style       (backdrop-style positioning)
       :dialog-style         (dialog-style)
       :on-dismiss           #(dispatch [:rf.xray/close-segment-inspector])
       :labelled-by          "rf-xray-segment-inspector-title"
       :backdrop-testid      "rf-xray-segment-inspector-backdrop"
       :dialog-testid        "rf-xray-segment-inspector-dialog"
       :on-backdrop-key-down on-keydown
       :on-dialog-key-down   on-keydown
       :backdrop-tab-index   -1
       :dialog-tab-index     0
       :dialog-extra         {:data-rf-xray-mode "segment-inspector"}}
      ;; Header
      [:div {:style (header-style)}
       (header-title path)
       [:button {:data-testid "rf-xray-segment-inspector-close"
                 :aria-label  "Close segment inspector"
                 :title       "Close (Esc)"
                 :on-click    (fn [^js e]
                                (.stopPropagation e)
                                (dispatch [:rf.xray/close-segment-inspector]))
                 :style       (close-button-style)}
        "✕"]]
      ;; Body — the cljs-devtools-shaped expandable tree at the path.
      ;; A unique `node-key` per (open) keeps the inspector's per-node
      ;; expand-state from colliding with the App-db Diff panel's
      ;; renders of the same value. The path's pr-str is stable across
      ;; renders so toggle state survives shadow-cljs reloads.
      ;; rf2-k97c.3 — `inspect-view` by default, not `inspect`. Same
      ;; value, same opts, same renderer; the ONLY difference is the head,
      ;; and that is the whole point here — `inspect` emits
      ;; `[ei/edn-inspector …]`, a Reagent head, which grades `:invalid`
      ;; inside a Fresco body down the identical codec arm a plain `defn`
      ;; does. `node-key` does double duty as the boundary's required
      ;; `:mount-id`, and the path's `pr-str` is stable across renders, so
      ;; expansion state still survives a reload exactly as it did.
      [:div {:data-testid "rf-xray-segment-inspector-body"
             :style       (body-style)}
       (inspector (f/display-value value)
                  (str "segment-inspector/" (pr-str (vec path))))])))

(rf.fresco/defview PopupView
  "The App-DB segment inspector popup — a FRESCO BOUNDARY (rf2-k97c.3),
  not an `rf/reg-view`. Renders only when
  `:rf.xray/segment-inspector-open?` is true; closed-state is a single
  subscription and a `when` — cheap.

  ## WHY A BOUNDARY, AND WHAT ACTUALLY MOVED

  The frame reasoning of rf2-in6l2 is UNCHANGED and still the reason this
  is a component rather than a fn: a boundary reads its frame from the
  same `re-frame.adapter.context` React context `reg-view` consulted, so
  the reads still resolve through the surrounding `:rf/xray`. What changed
  is the OBSERVER — the reads are `rf.fresco/sub`, recorded by Fresco's
  own collector rather than by whichever reaction machinery the installed
  adapter supplies, which is this epic's coupling (3).

  ## CLOSED-STATE COST IS PRESERVED EXACTLY

  One subscription and a gate, as before. The other three reads sit inside
  the `when`, and `rf.fresco/sub` records its edge WHERE THE READ HAPPENS
  (HD-002), so a branch not taken contributes no edge.

  Two consequences of `defview`'s contract, both load-bearing here:

    * It binds NO name inside the body, so `reg-view`'s lexically injected
      bare `dispatch` is gone. [[popup-tree]] builds one from the carried
      frame instead — same target, same behaviour (rf2-nesy9).
    * It takes ONE props map, so the old 0-arg call shape is gone. The
      node lane drives [[popup-tree]] directly now.

  PUBLIC so the shell's root swap can head it directly. [[Popup]] in front
  of it is the name the Reagent shell still mounts — see that bridge's
  docstring for why the two names sit this way round here."
  [_props]
  (when (rf.fresco/sub [:rf.xray/segment-inspector-open?])
    (popup-tree
      {:path        (rf.fresco/sub [:rf.xray/segment-inspector-path])
       :value       (rf.fresco/sub [:rf.xray/segment-inspector-value])
       :positioning (rf.fresco/sub [:rf.xray/modal-positioning])})))

;; ---- the migration bridges (rf2-k97c.3) ----------------------------------
;;
;; TWO callers mount this popup by name, and funnel-b's `Popup-bridge`
;; (below) took `panels/mount-segment-inspector!` off that list. The one
;; that remains is `shell.cljs`, which mounts `[app-db-segment-inspector/
;; Popup]` as a hiccup head — and it is FENCED to the shell root-swap
;; slice, so it cannot move in this step.
;;
;; THAT INVERTS THE NAMING, and it is worth saying plainly because the
;; comment this replaces predicted the other spelling. The mayor's
;; 2026-09-10 ruling is that a boundary keeps the NATURAL name and the
;; caller is handed a public bridge (#9581) — and it names the one
;; constraint that forces #9578's opposite spelling: a fenced `shell.cljs`
;; mounting the var BY NAME. That constraint is live here, so [[Popup]]
;; stays the public callable and the boundary above takes the `*View`
;; suffix. `machine_after_rings.cljs` records the same fork from the other
;; side, where the constraint did NOT apply. Nothing about funnel-b's
;; reasoning was wrong; only its assumption that `shell.cljs` would be
;; free to move in the same step.
;;
;; `rf.fresco/as-component` is Fresco's own outward door: it answers a real
;; React component for a boundary, which a React parent (Reagent here)
;; mounts UNDER THE FRAME IT IS ALREADY IN, taking the frame from React
;; context rather than from a second root. The crossing carries an EMPTY
;; props map, the case `as-component`'s own note calls sound — the
;; inspected value never crosses it; it is read inside the boundary.
;;
;; SCAFFOLDING WITH A DEFINED END. When the shell is itself a Fresco tree,
;; [[PopupView]] takes the name directly, the `[:>]` goes, and the
;; component plus both bridges below are deleted.

(def ^:private Popup-component
  "The React component [[PopupView]] presents as, for a non-Fresco parent.
  Declared ONCE at top level, as `rf.fresco/as-component`'s contract
  requires — deriving one per render mints a fresh element type and would
  remount the dialog on every pass, taking its focus trap with it."
  (rf.fresco/as-component PopupView))

(defn Popup
  "The segment-inspector popup's public callable — what `shell.cljs`
  mounts as a hiccup head at the shell root, and what [[Popup-bridge]]
  aliases for `panels.cljs`.

  Since rf2-k97c.3 it is the migration bridge rather than the view:
  Reagent-shaped hiccup interoping to the React component [[PopupView]]
  presents as. The enclosing `rf/frame-provider` is what puts `:rf/xray`
  in React context for it.

  The open/closed gate is inside [[PopupView]], so this is always mounted
  and renders nothing while the inspector is closed — the same shape a
  mounted `reg-view` returning nil had.

  Callers wanting the MARKUP as data — the node-lane rows — build it from
  [[popup-tree]] with the slots' values instead; this returns an interop
  vector, not a tree to walk."
  []
  [:> Popup-component {}])

(def Popup-bridge
  "The name `panels/mount-segment-inspector!` mounts this popup through.

  Still a plain alias of [[Popup]], and deliberately unchanged by the
  Fresco migration: [[Popup]] is itself the `as-component` bridge now, so
  this name needed no second bridge of its own and adding one would have
  duplicated it. `render-panel!` always builds the component VECTOR
  `[panel-view]`, so head position is the only position either name is
  used in, and the two remain indistinguishable downstream.

  Scaffolding with a defined end: deleted with every other `*-bridge`
  when the shell itself becomes a Fresco tree. See the comment above."
  Popup)

(defn install!
  "Idempotent install for the segment-inspector's Xray-side
  registrations. Returns nil per the facade convention."
  []
  (install-subs!)
  (install-events!)
  nil)
