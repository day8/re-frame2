(ns re-frame.story.ui.element-inspector
  "Element-level click-to-code (rf2-h0jc0) — React-Devtools-style.

  Story's per-variant Open-in-editor chip opens the *story-spec* source;
  this inspector extends the same gesture to *every rendered element on
  the canvas*: toggle inspect mode, hover any DOM element, click → open
  the view-fn that produced it at the exact line.

  ## The DOM contract

  re-frame2's `reg-view` macro injects
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on the rendered
  root of every registered view (per Spec 006 §Source-coord annotation
  / rf2-z7f7 / rf2-z9n1). The annotation is dev-only; production
  builds elide via the universal `interop/debug-enabled?` gate. This
  inspector reads that attribute off the hovered / clicked DOM node
  (walking ancestor chain when the literal target doesn't carry one —
  view-fn boundaries are the granularity, not every hiccup element).

  The four colon-separated segments are a **public, parseable contract**:
  `<ns>` and `<sym>` together form the registered handler-id keyword;
  `<line>`/`<col>` are the source coords captured at `reg-view` macro-
  expansion time. We split on `:` to recover them, then look up
  `(rf/handler-meta :view <id>)` for the `:file` slot (the DOM attribute
  carries line/col only — see `docs/causa/05-click-to-source.md`).

  ## Inspector mode

  - **Toggle**: a toolbar chip flips a per-process atom. `aria-haspopup`
    not `aria-pressed` per the rf2-zll4h reset-gate convention (the
    recorder's reset assertion counts `[aria-pressed=\"true\"]` and we
    don't want the inspect chip to trip it).
  - **Hover**: a `mousemove` listener on the canvas root walks up from
    the event target to the nearest `[data-rf2-source-coord]` ancestor.
    The overlay component draws an absolute-positioned outline +
    tooltip at the ancestor's `getBoundingClientRect`. Hover state is
    throttled via `requestAnimationFrame` so a fast mouse doesn't
    thrash the React tree.
  - **Click**: when inspect mode is on, the click handler intercepts
    (capture-phase, `stopPropagation` + `preventDefault`) so the
    variant's own onClick handlers don't fire. The resolved coord is
    handed to `open-in-editor/open-source-coord!` — same launcher the
    chip uses.
  - **Esc**: a keydown listener exits inspect mode + clears hover state.

  ## Why not a re-frame fx

  The inspector's state is per-process transient UI hover state — not
  app-db material. Keeping it in a defonce'd atom (mirrors
  `recorder/dom-capture/installed-root`) avoids the latency + overhead
  of routing every mousemove through dispatch. Click DOES route through
  `open-in-editor/open-source-coord!` so the launcher + allowlist gate
  stay the single source of truth.

  ## Bundle isolation + production elision

  This ns is Story-side; production builds elide the entire Story UI
  shell at the `mount-shell!` call site (per Story config/enabled?), so
  the inspector ns is reachable only when Story is enabled. The DOM
  attribute it reads is itself dev-only (elides via
  `interop/debug-enabled?`) — under prod the inspector would find no
  matches and click would no-op. Per Spec 009 §Production builds."
  (:require [clojure.string :as str]
            #?@(:cljs [[reagent.core :as r]
                       [re-frame.core :as rf]
                       [re-frame.source-coords :as source-coords]
                       [re-frame.story.ui.open-in-editor :as open-in-editor]])))

;; ---- per-process state ---------------------------------------------------
;;
;; The inspector's mode flag + hover snapshot live in a single atom so
;; the overlay component watches one ratom (mirrors recorder's defonce
;; install/state pattern). Two separate atoms would race — the mode
;; toggle fires on a different tick from the mousemove tick.
;;
;; `:active?` — inspect mode on/off
;; `:hover`   — nil OR `{:coord-attr <string> :rect #js{...} :handler-id <kw>}`

#?(:cljs
   (defonce ^{:doc "Reagent ratom holding `{:active? bool :hover ...}`.
                    CLJS-only — JVM consumers exercise the pure helpers
                    below without touching this state."}
     state
     (r/atom {:active? false :hover nil})))

#?(:cljs
   (defn active?
     "Predicate — true iff inspect mode is on."
     []
     (boolean (:active? @state))))

;; ---- pure: parse + resolve coord ----------------------------------------

(defn parse-coord
  "Parse a `data-rf2-source-coord` attribute value into
  `{:ns :handler-id :line :col}` or nil. Per Spec 006 §Attribute value
  format — `<ns>:<sym>:<line>:<col>`, with `?` for missing coords.

  Mirrors the parser in `skills/re-frame2-pair/.../runtime.cljs` (the
  re-frame2-pair nREPL consumer that does the same DOM → id walk). Returns nil
  for malformed input (too few / too many segments, empty ns or
  handler-id, non-string input). Never throws."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")]
      (when (= 4 (count parts))
        (let [[ns-part sym-part line-part col-part] parts
              parse-int (fn [s]
                          (when (and (string? s) (re-matches #"\d+" s))
                            #?(:cljs (js/parseInt s 10)
                               :clj  (Long/parseLong s))))]
          (when (and (seq ns-part) (seq sym-part))
            {:ns         ns-part
             :handler-id sym-part
             :line       (parse-int line-part)
             :col        (parse-int col-part)}))))))

(defn coord->handler-keyword
  "Build the registered view id keyword from a parsed coord. Returns nil
  when either segment is missing."
  [{:keys [ns handler-id]}]
  (when (and ns handler-id)
    (keyword ns handler-id)))

#?(:cljs
   (defn resolve-source-coord
     "Walk parsed coord + the live registrar to produce the full
     source-coord map `editor-uri/editor-uri` expects (`{:file :line
     :column}`). The DOM attribute carries line + col; the `:file` slot
     comes from the registered view's metadata (see
     `re-frame.source-coords` / `docs/causa/05-click-to-source.md`).

     Falls back to the always-on `error-coords-by-id` registry
     (rf2-3un2g) when the public meta has been stripped by production
     elision — that registry survives `:advanced` + `goog.DEBUG=false`
     and keeps the `:file` slot reachable for tooling."
     [parsed]
     (when-let [view-id (coord->handler-keyword parsed)]
       (let [meta (rf/handler-meta :view view-id)
             err  (source-coords/error-coords-for :view view-id)
             file (or (:file meta) (:file err))]
         {:file   file
          :line   (or (:line parsed) (:line meta) (:line err) 1)
          :column (or (:col parsed) (:column meta) (:column err) 1)
          :ns     (or (:ns parsed) (some-> meta :ns str))}))))

;; ---- DOM helpers ---------------------------------------------------------

#?(:cljs
   (defn- nearest-source-coord-ancestor
     "Walk `el` and its ancestors looking for the first
     `[data-rf2-source-coord]` attribute. Returns the ancestor element,
     or nil when none is found within the DOM root (so a click outside
     any registered view's tree is a no-op).

     Bounded by `root` — the walk stops once it crosses `root`'s parent
     so the inspector never resolves to an element outside the canvas."
     [el root]
     (loop [n el]
       (cond
         (or (nil? n) (= n root)) nil
         (and (.-getAttribute n)
              (.getAttribute n "data-rf2-source-coord"))
         n
         :else
         (recur (.-parentElement n))))))

#?(:cljs
   (defn- rect->map
     "Convert a `getBoundingClientRect` DOMRect into a plain Clojure
     map. Decouples the React overlay component from the DOMRect
     prototype so test rebinds can stub the snapshot."
     [rect]
     {:top    (.-top rect)
      :left   (.-left rect)
      :width  (.-width rect)
      :height (.-height rect)}))

;; ---- mode toggle ---------------------------------------------------------

#?(:cljs
   (defn- set-cursor!
     "Toggle the document body's cursor between `crosshair` (inspect mode
     on) and the prior value (inspect mode off). The prior cursor is
     stashed on `state` so we restore exactly what was there — a plain
     `\"\"` reset would clobber a host-app stylesheet that sets a custom
     cursor on body."
     [on?]
     (when (and (exists? js/document) (.-body js/document))
       (let [body (.-body js/document)
             cur  @state]
         (if on?
           (do
             (swap! state assoc :prev-cursor (.. body -style -cursor))
             (set! (.. body -style -cursor) "crosshair"))
           (do
             (set! (.. body -style -cursor) (or (:prev-cursor cur) ""))
             (swap! state dissoc :prev-cursor)))))))

#?(:cljs
   (defn set-active!
     "Programmatic mode toggle. Public so tests + toolbar can drive without
     going through the DOM."
     [on?]
     (set-cursor! on?)
     (swap! state
            (fn [s] (cond-> (assoc s :active? (boolean on?))
                     (not on?) (assoc :hover nil))))))

#?(:cljs
   (defn toggle!
     "Flip the inspect-mode flag. Wired from the toolbar chip."
     []
     (set-active! (not (active?)))))

;; ---- event handlers ------------------------------------------------------
;;
;; All three handlers gate on `(active?)` so leaving them installed is
;; free — same pattern as `recorder/dom-capture`.

#?(:cljs (defonce ^:private rAF-handle (atom nil)))

#?(:cljs
   (defn- schedule-hover-update!
     "rAF-throttle hover updates so a fast mouse doesn't thrash the React
     tree (the overlay subscribes to `state` — every snapshot triggers
     one render). Coalesces multiple mousemoves between two animation
     frames into a single state write."
     [f]
     (when @rAF-handle
       (.cancelAnimationFrame js/window @rAF-handle))
     (reset! rAF-handle
             (.requestAnimationFrame js/window
               (fn [_]
                 (reset! rAF-handle nil)
                 (f))))))

#?(:cljs
   (defn- handle-mousemove!
     "Mousemove handler on the canvas root. Walks ancestors to find the
     nearest `[data-rf2-source-coord]` element; writes a snapshot to
     `state :hover`. No-op when inspect mode is off (the listener stays
     installed for cheapness; gating happens here)."
     [root ev]
     (when (active?)
       (schedule-hover-update!
         (fn []
           (let [el      (.-target ev)
                 ancestor (nearest-source-coord-ancestor el root)]
             (if ancestor
               (let [attr  (.getAttribute ancestor "data-rf2-source-coord")
                     rect  (.getBoundingClientRect ancestor)
                     parsed (parse-coord attr)]
                 (swap! state assoc
                        :hover {:coord-attr attr
                                :handler-id (coord->handler-keyword parsed)
                                :parsed     parsed
                                :rect       (rect->map rect)}))
               (swap! state assoc :hover nil))))))))

#?(:cljs
   (defn- handle-click!
     "Click handler on the canvas root. When inspect mode is on, this
     fires CAPTURE-PHASE so the variant's own onClick handlers do not
     run (we don't want to commit a button submit while picking).
     Resolves the click target → nearest `[data-rf2-source-coord]`
     ancestor → source-coord → editor URI → `open!`."
     [root ev]
     (when (active?)
       (.stopPropagation ev)
       (.preventDefault ev)
       (let [el (.-target ev)]
         (when-let [ancestor (nearest-source-coord-ancestor el root)]
           (let [attr   (.getAttribute ancestor "data-rf2-source-coord")
                 parsed (parse-coord attr)]
             (when-let [coord (resolve-source-coord parsed)]
               (open-in-editor/open-source-coord! coord)))))
       ;; Exit inspect mode after a successful pick — matches React
       ;; Devtools' "pick once" gesture. The user re-clicks the chip
       ;; to start another pick.
       (set-active! false))))

#?(:cljs
   (defn- handle-keydown!
     "Esc exits inspect mode + clears hover. Listener lives on
     `js/document` (not the canvas root) so the user can hit Esc with
     focus anywhere."
     [ev]
     (when (and (active?) (= "Escape" (.-key ev)))
       (.preventDefault ev)
       (set-active! false))))

;; ---- install / remove ----------------------------------------------------
;;
;; Mirrors `recorder/dom-capture/install!`'s shape: defonce'd ref to
;; the currently-installed root, attach/detach via the same key. Caller
;; (shell) drives install at mount-time, remove at unmount.

#?(:cljs (defonce ^:private installed-root (atom nil)))
#?(:cljs (defonce ^:private bound-handlers (atom nil)))

#?(:cljs
   (defn- canvas-root
     "The shell canvas root, looked up via the framework-stable
     `[data-test=\"story-canvas-frame\"]` hook (same selector
     `recorder/dom-capture` uses)."
     []
     (when (and (exists? js/document) (.-querySelector js/document))
       (try
         (.querySelector js/document "[data-test=\"story-canvas-frame\"]")
         (catch :default _ nil)))))

#?(:cljs
   (defn- attach-listeners! [root]
     (let [on-move (fn [ev] (handle-mousemove! root ev))
           on-clk  (fn [ev] (handle-click! root ev))
           on-key  handle-keydown!]
       (reset! bound-handlers {:move on-move :click on-clk :key on-key})
       ;; mousemove on root — bubble is fine; we always walk up to find
       ;; the ancestor.
       (.addEventListener root "mousemove" on-move false)
       ;; click in CAPTURE phase — fire before the variant's own onClick
       ;; gets a chance to react.
       (.addEventListener root "click" on-clk true)
       (.addEventListener js/document "keydown" on-key false))))

#?(:cljs
   (defn- detach-listeners! [root]
     (when-let [h @bound-handlers]
       (.removeEventListener root "mousemove" (:move h) false)
       (.removeEventListener root "click" (:click h) true)
       (.removeEventListener js/document "keydown" (:key h) false)
       (reset! bound-handlers nil))))

#?(:cljs
   (defn install!
     "Install the inspector listeners on `root` (or the canvas root when
     called with no arg). Idempotent. Returns the root on success, nil
     otherwise."
     ([] (install! (canvas-root)))
     ([root]
      (when root
        (when-let [prev @installed-root]
          (detach-listeners! prev))
        (attach-listeners! root)
        (reset! installed-root root)
        root))))

#?(:cljs
   (defn remove!
     "Tear down any installed listeners. Idempotent. Also flips inspect
     mode off — a re-mount mid-pick would otherwise leave the cursor
     stuck on `crosshair`."
     []
     (when-let [root @installed-root]
       (detach-listeners! root)
       (reset! installed-root nil))
     (set-active! false)
     nil))

(defn installed?
  "True iff `install!` has attached listeners. Public for tests."
  []
  #?(:cljs (some? @installed-root)
     :clj  false))

;; ---- overlay component + chip --------------------------------------------

(def ^:private overlay-styles
  {:outline {:position       "fixed"
             :pointer-events "none"
             :z-index        "999999"
             :border         "2px solid #61dafb"   ;; React-Devtools-ish blue
             :background     "rgba(97, 218, 251, 0.15)"
             :box-sizing     "border-box"
             :border-radius  "2px"
             :transition     "none"}
   :tooltip {:position       "fixed"
             :pointer-events "none"
             :z-index        "1000000"
             :background     "#1e1e1e"
             :color          "#cccccc"
             :border         "1px solid #444"
             :border-radius  "3px"
             :padding        "3px 8px"
             :font-family    "monospace"
             :font-size      "11px"
             :white-space    "nowrap"
             :box-shadow     "0 2px 8px rgba(0,0,0,0.4)"}
   :chip-on    {:padding         "3px 8px"
                :background      "#0e639c"
                :color           "white"
                :border          "none"
                :border-radius   "10px"
                :cursor          "pointer"
                :font-family     "monospace"
                :font-size       "11px"
                :user-select     "none"}
   :chip-off   {:padding         "3px 8px"
                :background      "#37373d"
                :color           "#cccccc"
                :border          "none"
                :border-radius   "10px"
                :cursor          "pointer"
                :font-family     "monospace"
                :font-size       "11px"
                :user-select     "none"}})

#?(:cljs
   (defn overlay
     "The hover-overlay component. Mounted once by the shell; subscribes
     to `state` and renders nothing when inspect mode is off OR when
     `:hover` is nil. Renders an absolute-positioned outline + tooltip
     when both are present.

     Tooltip placement: above the hovered element when there's room
     (top - 22px), below otherwise. Mirrors React Devtools' tooltip
     placement heuristic."
     []
     (let [s          @state
           {:keys [active? hover]} s]
       (when (and active? hover)
         (let [{:keys [coord-attr handler-id rect parsed]} hover
               {:keys [top left width height]} rect
               above? (> top 26)
               tip-top (if above? (- top 24) (+ top height 4))]
           [:div {:data-test "story-element-inspector-overlay"}
            ;; The outline
            [:div {:style (merge (:outline overlay-styles)
                                 {:top    (str top "px")
                                  :left   (str left "px")
                                  :width  (str width "px")
                                  :height (str height "px")})}]
            ;; The tooltip
            [:div {:style       (merge (:tooltip overlay-styles)
                                       {:top  (str tip-top "px")
                                        :left (str left "px")})
                   :data-test   "story-element-inspector-tooltip"
                   :data-handler-id (str handler-id)}
             (str (or handler-id coord-attr)
                  (when (:line parsed) (str " @ " (:line parsed)
                                            (when (:col parsed) (str ":" (:col parsed))))))]])))))

#?(:cljs
   (defn inspect-chip
     "The toolbar chip. Click → toggle inspect mode. Uses
     `aria-haspopup`/`aria-expanded` (NOT `aria-pressed`) per the
     rf2-zll4h reset-gate convention — the toolbar's reset assertion
     in the e2e suite counts `[aria-pressed=\"true\"]` and we don't
     want the inspect chip to trip it.

     Hidden when production-elided: the chip lives in the Story bundle
     and the entire shell ns is gated on `config/enabled?`."
     []
     (let [on? (boolean (:active? @state))]
       [:button {:style (if on?
                          (:chip-on overlay-styles)
                          (:chip-off overlay-styles))
                 :data-test "story-toolbar-inspect"
                 :aria-haspopup "true"
                 :aria-expanded (str on?)
                 :title (if on?
                          "Element inspector ON — hover any element, click to open its view-fn"
                          "Element inspector — click any element to open its view-fn source")
                 :on-click (fn [_] (toggle!))}
        (if on? "Inspect ●" "Inspect")])))
