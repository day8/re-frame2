(ns day8.re-frame2-xray.panels.shared.coord-chip
  "Shared `coord-chip` — the canonical 'open in editor' icon-only chip
  used across panels (Epoch, future surfaces).

  ## Why this exists

  Every icon-only open-in-editor chip is the same hiccup — a `<button>`
  carrying the lucide `external-link` glyph, dispatching
  `[:rf.xray/open-in-editor {:source-coord coord}]` on the `:rf/xray`
  frame, hidden when the coord has no `:file`. Only two pixel knobs
  vary, and they are options here:

  - **`:color`** — `\"inherit\"` by default (the chip rides whatever
    text colour its parent set), or `(:accent tokens)` for a chip
    standing alone.
  - **`:margin-left`** — `4px` by default, `6px` for a wider tap target.

  One shared component keeps the chips identical and makes UX changes
  to the open-in-editor affordance a one-file edit.

  ## Style hoist

  The chip's button style is hoisted to a ns-top `def` so a single
  immutable map is reused across every chip render rather than
  allocating a fresh map per call. The two pixel knobs are resolved
  at call-time via a tiny `assoc` overlay (still cheap — no nested
  map traversal).

  ## What this chip is NOT

  `open_in_editor/open-chip` is a SEPARATE surface: it renders an
  `<a href=\"…\">` anchor with the URI pre-resolved against
  `editor-uri/editor-uri` (used by demo surfaces + the standalone
  static page). The button chip here dispatches via the trace bus so
  the click is observable as a first-class operation on the
  `:rf/xray` frame, and the URI resolution happens inside the
  `:rf.xray/open-in-editor` reg-event → `:rf.xray.fx/open-in-editor` reg-fx
  pipeline. Both surfaces co-exist by design."
  (:require [day8.re-frame2-xray.panels.event.icons :as icons]
            [day8.re-frame2-xray.panels.shared.coord-link :as coord-link]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]))

;; ---- hoisted style maps -------------------------------------------------

(def ^:private chip-style-base
  "Base style for the open-in-editor chip's `<button>`. The `:color`
  and `:margin-left` are overlaid per call (the only two pixel knobs
  that vary between call-sites). Resolved at ns-load — `tokens` is a
  static const map (spec/007-UX-IA §CSS custom-property surface)."
  {:background  "transparent"
   :border      "none"
   :padding     "0 4px"
   :cursor      "pointer"
   :display     "inline-flex"
   :align-items "center"
   :line-height 1})

;; ---- public chip --------------------------------------------------------

(defn coord-chip
  "Render the canonical 'open in editor' icon-only chip — a tab-focusable
  `<button>` carrying the lucide `external-link` glyph. Returns `nil`
  when `coord` lacks a `:file` (so call-sites can drop the chip
  cleanly).

  Clicking dispatches `[:rf.xray/open-in-editor {:source-coord coord}]`
  through the caller-supplied frame-aware dispatcher so the
  open-in-editor event lands on the surrounding instance frame; the
  trace bus records the click, the `:rf.xray.fx/open-in-editor` fx resolves the
  URI through the editor-URI allowlist, and `Location.assign` fires.

  Accessibility: the chip is a `<button>` (so Enter / Space activate
  natively), `aria-label` reads 'open in editor', and the inline SVG
  carries `aria-hidden=\"true\"` so screen readers walk the label,
  not the path geometry.

  Arguments:

  - `coord` — the source-coord map `{:file :line :column :ns}`. Nil
    or any value without a `:file` returns nil.
  - `testid` — a stable `data-testid` for the chip; call-sites suffix
    it with their row's stable suffix.
  - `opts` (optional) — pixel-knob overrides:
    - `:color`        — chip colour. Defaults to `\"inherit\"` (rides
                        whatever text colour the parent set, the Epoch
                        idiom). Pass `(:accent tokens)` where the chip
                        stands alone in a `:text-primary` row.
    - `:margin-left`  — chip left-margin. Defaults to `\"4px\"`. Pass
                        `\"6px\"` for a slightly wider tap target.
    - `:dispatch-fn`  — the frame-aware dispatcher captured
                        by the surrounding view body so the
                        open-in-editor click lands on the instance
                        frame. Defaults to `coord-link/default-dispatch`
                        (the production singleton frame) for call-sites
                        that pass no captured dispatcher."
  ([coord testid]
   (coord-chip coord testid nil))
  ([coord testid {:keys [color margin-left dispatch-fn]
                  :or   {color       "inherit"
                         margin-left "4px"}}]
   (when (and (map? coord) (seq (:file coord)))
     [:button {:data-testid testid
               :aria-label  "open in editor"
               :title       "open in editor"
               :on-click    (fn [e]
                              (.stopPropagation e)
                              ((or dispatch-fn coord-link/default-dispatch)
                               [:rf.xray/open-in-editor
                                {:source-coord coord}]))
               :style       (assoc chip-style-base
                                   :color       color
                                   :margin-left margin-left)}
      (icons/external-link)])))
