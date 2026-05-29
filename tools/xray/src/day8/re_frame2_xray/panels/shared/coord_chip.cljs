(ns day8.re-frame2-xray.panels.shared.coord-chip
  "Shared `coord-chip` — the canonical 'open in editor' icon-only chip
  used across panels (Epoch, future surfaces).

  ## Why this exists (rf2-xjgdk, audit L2)

  Before rf2-xjgdk two near-identical icon-only chip components lived
  as private `defn-`s in `panels/epoch/view.cljs` and (the now-
  retired) `panels/event_detail.cljs`. The hiccup was 99% the same —
  a `<button>` carrying the lucide `external-link` glyph, dispatching
  `[:rf.xray/open-in-editor {:source-coord coord}]` on the `:rf/xray`
  frame, hidden when the coord has no `:file`. Only two pixel knobs
  varied:

  - **`:color`** — Epoch used `\"inherit\"` (the chip rides whatever
    text colour its parent set); the retired Event-detail panel used
    `(:accent tokens)`.
  - **`:margin-left`** — Epoch `4px`, Event-detail `6px`.

  Folding the two into one shared component removes the duplication
  and makes UX changes to the open-in-editor affordance a one-file
  edit. Per the bead the Epoch panel's coord-chip (rf2-ehd8v +
  rf2-80u5a) is the canonical version; this ns mirrors its shape and
  exposes the two knobs as options.

  ## Style hoist (rf2-xjgdk, audit F4)

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
  `:rf.xray/open-in-editor` reg-event-fx → `:rf.editor/open` reg-fx
  pipeline. Both surfaces co-exist by design (rf2-evgf5 / rf2-g5q8d
  decision)."
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
  through the caller-supplied frame-aware dispatcher (rf2-r0o63) so the
  open-in-editor event lands on the surrounding instance frame; the
  trace bus records the click, the `:rf.editor/open` fx resolves the
  URI through the rf2-cm93v allowlist, and `Location.assign` fires.

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
                        idiom). Pass `(:accent tokens)` for the
                        Event-detail idiom where the chip stands
                        alone in a `:text-primary` row.
    - `:margin-left`  — chip left-margin. Defaults to `\"4px\"`. Pass
                        `\"6px\"` to match Event-detail's slightly
                        wider tap target.
    - `:dispatch-fn`  — (rf2-r0o63) the frame-aware dispatcher captured
                        by the surrounding `reg-view` body so the
                        open-in-editor click lands on the instance
                        frame. Defaults to `coord-link/default-dispatch`
                        (the production singleton frame) for call-sites
                        the rf2-nesy9 sweep hasn't yet threaded a
                        captured dispatcher through."
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
