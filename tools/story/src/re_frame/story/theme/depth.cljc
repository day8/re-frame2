(ns re-frame.story.theme.depth
  "Story depth + atmospheric background tokens (rf2-ypd6h).

  The pre-rf2-ypd6h chrome shipped flat solid backgrounds — toolbar /
  sidebar / canvas / right-panel all rendered on identical `#252526`
  grounds with no `box-shadow` declarations on any surface. The canvas
  frame (the focal workshop region) was visually indistinguishable
  from every inspector panel; the eye had nowhere to land.

  This namespace ships:

  - **Shadow scale** (`shadows`) — three elevation tiers + a
    canvas-frame accent shadow so the workshop region snaps forward.
  - **Atmospheric backdrops** (`backdrops`) — gradient-mesh strings
    that lift the bare slate grounds into something that reads as
    'studio' rather than 'editor pane'. Used on the shell root + the
    canvas frame; chrome surfaces stay flat so the gradients don't
    fight the data they carry.
  - **Grain overlay** (`grain-css`) — an SVG-feTurbulence noise
    overlay injected as a `::before` pseudo on the shell root,
    blended at low opacity to cut the eyestrain pure-solid dark UIs
    induce. Reduces the 'screen-burning hole' effect on AMOLED + OLED
    panels.
  - **Canvas frame accent edge** — a 1px amber inner edge on the
    variant render surface so the user's eye lands there
    automatically.

  ## Composition

  Apply via `:background` (gradient backdrops) + `:box-shadow`
  (elevation) on inline `:style` maps. The grain overlay is injected
  one-shot via `inject-grain-css!` since `::before` pseudos can't
  live in an inline style.

  ## Why these specific gradients

  Two-layer radial mesh: a warm amber-tint blob top-left (~3% opacity)
  + a cool teal-tint blob bottom-right (~2% opacity), both blended
  against the base slate ground. Reads as 'studio lighting' rather
  than 'colour assignment' — the amber barely registers but the eye
  feels the warmth, and the teal counterweight stops the warm-amber
  cast from looking like a stain.

  ## CLJS / JVM split

  Token data is `.cljc`. The inject helper is CLJS-only."
  {:no-doc true}
  #?(:cljs (:require [re-frame.story.config :as config])))

(def shadows
  "Box-shadow tokens. Four slots:

  - `:elev-1` — subtle 1px lift, used on raised chrome (toolbar,
    sidebar widget foot).
  - `:elev-2` — standard 4px lift, used on lifted panels (controls,
    dispatch console, canvas frame).
  - `:elev-overlay` — dramatic 16px lift, used on floating dialogs
    (help, recorder, share popover).
  - `:canvas-edge` — Story's signature: a 1px amber inset on the
    canvas frame so the workshop region carries the brand accent.

  All shadows use rgba so they sit on top of any backdrop the surface
  is rendered against."
  {:elev-1       "0 1px 0 rgba(0, 0, 0, 0.25)"
   :elev-2       "0 4px 16px rgba(0, 0, 0, 0.45), 0 1px 0 rgba(255, 255, 255, 0.02) inset"
   :elev-overlay "0 24px 64px rgba(0, 0, 0, 0.65), 0 8px 24px rgba(0, 0, 0, 0.4), 0 1px 0 rgba(255, 255, 255, 0.04) inset"
   :canvas-edge  "0 0 0 1px rgba(245, 165, 36, 0.18) inset, 0 8px 32px rgba(0, 0, 0, 0.5), 0 1px 0 rgba(255, 255, 255, 0.03) inset"
   :focus-glow   "0 0 0 3px rgba(245, 165, 36, 0.25)"})

(def backdrops
  "Atmospheric backdrop strings. CSS `:background` values combining a
  base slate ground with one or two radial-gradient overlays for
  studio-lighting texture.

  - `:shell-root` — the shell's outer ground. Warm amber blob top-
    left + cool teal blob bottom-right, over the deepest slate.
  - `:canvas-frame` — the workshop region. A single warm amber
    radial centred to give the variant render surface a subtle halo
    that says 'work happens here'.
  - `:overlay-glass` — the help / dialog backdrop. Subtle radial
    centre-light + edge falloff so the dialog reads as 'lit on a
    stage'.

  Each backdrop is a single CSS `background` shorthand that drops
  straight into an inline style map."
  {:shell-root    "radial-gradient(1200px circle at 12% 0%, rgba(245, 165, 36, 0.045), transparent 60%), radial-gradient(900px circle at 88% 110%, rgba(67, 195, 208, 0.025), transparent 55%), #0B0D11"
   :canvas-frame  "radial-gradient(600px circle at 50% 0%, rgba(245, 165, 36, 0.04), transparent 70%), #15181F"
   :overlay-glass "radial-gradient(800px circle at 50% 0%, rgba(245, 165, 36, 0.035), transparent 65%), #1E222A"})

(def grain-css
  "Injects a subtle SVG-feTurbulence noise overlay as a `::before`
  pseudo on `[data-rf-story-root]`. The grain sits at `opacity: 0.04`
  with `mix-blend-mode: overlay` so it lifts the bare slate without
  becoming visible noise — the eye reads it as 'screen texture'
  rather than 'noisy gradient'.

  Pointer-events disabled so the overlay never swallows clicks. The
  pseudo lives BEHIND the root's content via `z-index: -1` +
  `position: absolute` from the root's `position: relative`.

  Self-contained SVG inline-encoded as a base-64-free data URI so no
  HTTP fetch is required."
  "[data-rf-story-root]{position:relative;isolation:isolate}
[data-rf-story-root]::before{content:'';position:absolute;inset:0;pointer-events:none;z-index:0;mix-blend-mode:overlay;opacity:0.04;background-image:url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='240' height='240'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/><feColorMatrix values='0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.4 0'/></filter><rect width='100%' height='100%' filter='url(%23n)'/></svg>\");background-repeat:repeat;background-size:240px 240px}
[data-rf-story-root] > *{position:relative;z-index:1}")

#?(:cljs
   (defonce ^:private grain-injected? (atom false)))

#?(:cljs
   (defn inject-grain-css!
     "Inject the grain-overlay CSS into `js/document.head`. Idempotent.
     Behind `config/enabled?` — production short-circuits."
     []
     (when (and config/enabled? (not @grain-injected?))
       (reset! grain-injected? true)
       (when (and (exists? js/document) (.-head js/document))
         (try
           (let [style (.createElement js/document "style")]
             (set! (.-type style) "text/css")
             (set! (.-innerHTML style) grain-css)
             (.appendChild (.-head js/document) style))
           (catch :default _ nil))))))
