(ns re-frame.story.theme.space
  "Story spacing + radius rhythm tokens (spec/018 §12.2 — 'high
  information density with stable spacing; strong alignment and
  grouping').

  Every padding / gap / radius value in the chrome resolves through
  this namespace's constructed 4px scale rather than a per-call-site
  raw string, so the spacing rhythm is explicit and regions align to
  a shared grid — no panel padded `12px` while its sibling sits at
  `10px`. A workshop UI the user leaves open all day lives or dies on
  stable spacing.

  ## The 4px scale

  Spacing keys onto a base unit of 4px — the smallest rhythm a dense
  operator UI can afford while staying scannable. Every padding / gap /
  margin in the chrome SHOULD resolve to one of these steps so regions
  align to a shared grid.

      :px-0   0      flush
      :px-1   2px    hairline gap (chip-internal)
      :px-2   4px    tight gap (signal chips, icon+label)
      :px-3   6px    row gap
      :px-4   8px    standard gap / section inner pad
      :px-5  12px    region pad (the workshop default)
      :px-6  16px    canvas frame pad / generous block
      :px-7  24px    empty-state / dialog pad
      :px-8  32px    hero empty-state pad

  ## Radius scale

  Story's surfaces round on a tight scale — chips/pills round fully
  (`:pill`), inputs + panels round subtly (`:sm` / `:md`), nothing
  rounds 'decoratively' (spec/018 §12.2 — avoid 'large rounded
  decorative surfaces').

      :none  0
      :xs    2px    inline highlights, search-hit marks
      :sm    3px    buttons, inputs
      :md    4px    panels, mounted-panel hosts
      :pill  10px   chips, badges, status pills

  ## How call sites consume it

      (:require [re-frame.story.theme.space :as space])

      {:padding (space/pad 5)            ; \"12px\"
       :gap     (space/gap 4)            ; \"8px\"
       :border-radius (:pill space/radius)}

  Pure data → data; JVM-portable."
  {:no-doc true})

(def scale
  "The 4px spacing scale. CSS strings so call sites drop them straight
  into inline `:style` maps. Keys are `:px-0` … `:px-8` so the rhythm
  step reads at the call site."
  {:px-0 "0"
   :px-1 "2px"
   :px-2 "4px"
   :px-3 "6px"
   :px-4 "8px"
   :px-5 "12px"
   :px-6 "16px"
   :px-7 "24px"
   :px-8 "32px"})

(def ^:private step->key
  {0 :px-0 1 :px-1 2 :px-2 3 :px-3 4 :px-4 5 :px-5 6 :px-6 7 :px-7 8 :px-8})

(defn pad
  "Return the CSS length for spacing step `n` (0–8). `(pad 5)` → \"12px\".
  Out-of-range `n` clamps to the nearest end of the scale. Pure."
  [n]
  (get scale (step->key (-> n (max 0) (min 8))) "0"))

;; `gap` is an alias for `pad` — same scale, named at the call site for
;; flexbox `:gap` slots so the intent reads (a gap vs an inner pad).
(def gap pad)

(def radius
  "Border-radius scale. Tight — Story rounds functionally, never
  decoratively (spec/018 §12.2)."
  {:none "0"
   :xs   "2px"
   :sm   "3px"
   :md   "4px"
   :pill "10px"})
