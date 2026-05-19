(ns re-frame.story.theme.typography
  "Story typography token system (rf2-2rwdc).

  Story is a developer-facing **workshop / playground** UI — the
  surface where component authors hover over their work-in-progress
  variants in a controlled chrome. The aesthetic direction is
  **editorial-dark**: high-contrast, characterful type, generous
  letter-spacing on labels, a recognisable display voice that asserts
  Story's identity while sitting cleanly next to Causa (the dev-time
  diagnostic surface that already ships its own tokens at
  `tools/causa/src/day8/re_frame2_causa/theme/tokens.cljc`).

  ## Why this font family

  **IBM Plex Sans + IBM Plex Mono** — chosen explicitly to satisfy the
  rubric's anti-generic posture:

  - NOT `Inter` (Causa's pick — and the AI-slop default).
  - NOT `system-ui` / `Roboto` / `Arial` (the cookie-cutter floor).
  - NOT `Space Grotesk` (the rubric's named convergence point).

  Plex carries IBM's editorial bias — geometric without being sterile,
  with characterful italics, a confident `g`, and a mono sibling tuned
  to the same proportions. The sans + mono pair share design DNA so
  chrome that mixes them (e.g. a variant id rendered next to its
  status text) holds together typographically. Distinguishes Story from
  Causa's JetBrains Mono + Inter at a glance — both tools land in the
  RHS together; the type contrast signals 'two surfaces, two roles'.

  ## How call sites consume these tokens

  Inline `:style` maps reference the stacks directly:

      (:require [re-frame.story.theme.typography :refer [sans-stack mono-stack]])

      {:font-family sans-stack}                  ; chrome / labels / prose
      {:font-family mono-stack}                  ; code / EDN / variant ids

  Zero raw `font-family` strings outside this ns is the contract
  (rf2-2rwdc acceptance criterion #5).

  ## Web font delivery

  The stacks include the canonical web-font name first, then a
  thoughtful fallback chain so a missing webfont degrades gracefully.
  The dev-time shell uses `local()`-only `@font-face` declarations
  (injected via `inject-font-faces!` below) so an OS-installed Plex
  picks up automatically (Mike's machines, IBM Carbon design system
  users) and no HTTP fetch is ever attempted. Consuming projects that
  want self-hosted or CDN webfonts inject their own `@font-face` rules
  with `url()` entries pointing at their vendored woff2s — the
  `local()`-only auto-inject does not interfere with project-side
  declarations layered above it.

  Per `re-frame.story.config/enabled?` the inject helper short-circuits
  in production so published static builds never touch the DOM.

  ## What lives here

  - `sans-stack` — IBM Plex Sans + fallbacks. Chrome / labels / prose.
  - `mono-stack` — IBM Plex Mono + fallbacks. Code / EDN / variant ids.
  - `display-stack` — IBM Plex Sans, tracked tight, for hero / overlay
    titles. Same family as `sans-stack`; the difference is letter-
    spacing applied at the call site (`-0.01em` on display surfaces).
  - `inject-font-faces!` — CLJS-only helper that lazily injects
    `<style>` `@font-face` rules into `js/document.head` so the dev-
    time shell can self-host webfonts without forcing a build-time
    asset pipeline."
  {:no-doc true}
  #?(:cljs (:require [re-frame.story.config :as config])))

(def sans-stack
  "IBM Plex Sans stack — the chrome / labels / prose font. Distinctive
  versus Causa's Inter, distinctive versus the AI-slop floor
  (system-ui / Roboto / Arial), with characterful glyphs that hold up
  at small sizes (the 11px-and-down labels Story is full of).

  Fallback chain after Plex: a soft sans humanist (Optima, Avenir) for
  systems missing the webfont, then ui-sans-serif so the OS-default
  picker covers the long tail."
  "\"IBM Plex Sans\", \"Optima\", \"Avenir Next\", ui-sans-serif, -apple-system, BlinkMacSystemFont, sans-serif")

(def mono-stack
  "IBM Plex Mono stack — Story's code / EDN / variant-id font.
  Deliberately NOT JetBrains Mono (Causa's pick) so the two surfaces
  read as distinct register at-a-glance when they live side-by-side
  in the RHS. Plex Mono pairs visually with Plex Sans (same family
  DNA) so a row mixing `:my.app/event` (mono) with `dispatched`
  (sans) holds together.

  Fallback chain: ui-monospace covers macOS / Windows / Linux
  defaults; the named fallbacks are mono workhorses present on
  developer machines."
  "\"IBM Plex Mono\", \"JetBrains Mono\", ui-monospace, \"SF Mono\", \"Cascadia Code\", \"Source Code Pro\", monospace")

(def display-stack
  "IBM Plex Sans tuned for display use — same family as `sans-stack`
  so chrome composes consistently. Call sites layer
  `letter-spacing: -0.01em` and `font-weight: 600` on top of this
  stack to lift headlines / hero titles into the display register."
  "\"IBM Plex Sans\", \"Optima\", ui-sans-serif, sans-serif")

(def weights
  "Weight tokens for IBM Plex Sans + Mono. Plex ships every increment
  from 100 to 700; Story uses four — regular for body, medium for
  emphasised body / sidebar entries, semibold for chrome headers /
  active tabs, bold for hero / display.

  Stored as integer-cast strings so they drop into inline `:style`
  maps and emit as CSS values without quotation acrobatics."
  {:regular  400
   :medium   500
   :semibold 600
   :bold     700})

(def type-scale
  "Story type scale tokens (rf2-juxha).

  Stepped scale tuned for an info-dense workshop UI — Story's chrome
  packs sidebar / toolbar / controls / inspector / canvas-title
  surfaces into one viewport, so the scale runs tight (10–18 px) with
  display steps reserved for hero / welcome overlay titles.

  Mirrors Causa's `type-scale` shape (`:display` / `:body` /
  `:body-tight` / `:mono-body` / `:caption` / `:micro` plus
  `:line-height-*` tokens) so the two surfaces compose cleanly.
  Differences vs Causa:

  - `:hero` (24px) is Story-only — the welcome overlay headline.
  - `:display` runs 16 (vs Causa 14) — Story's mode-tab strip + section
    headers carry a heading register the diagnostic surface doesn't need.
  - `:body` runs 13 (matches Causa) — info density wins for the
    info-dense chrome.

  Values are CSS strings so call sites drop them into inline `:style`
  maps without unit acrobatics."
  {;; Headings + prose
   :hero         "24px"    ; welcome overlay title, hero copy
   :display      "16px"    ; section titles, mode-tab labels
   :body         "13px"    ; default body / labels
   :body-tight   "12px"    ; sidebar entries, toolbar chips, controls labels
   :mono-body    "12px"    ; mono code / EDN / variant ids
   :caption      "11px"    ; hints, secondary labels, badge text
   :micro        "10px"    ; uppercase axis labels, micro chips, badges
   :nano          "9px"    ; tag-badge text (sidebar)
   ;; Vertical rhythm
   :line-height-tight "1.4"   ; denser blocks (sidebar / controls)
   :line-height-body  "1.5"   ; prose / help overlay
   :line-height-mono  "1.4"   ; mono needs a touch more leading
   })

(def letter-spacing
  "Letter-spacing tokens. Three slots:

  - `:label` — uppercase chrome labels (axis labels, section headers)
    take a wide tracking to read as 'system signal' at small sizes.
  - `:display` — display headlines tighten 1% so Plex Sans's natural
    spacing doesn't look loose at hero scale.
  - `:body` — normal tracking, no kerning override.
  - `:label-wide` — extra-wide tracking for the toolbar's :axis-label
    surface, where the label sits above a chip row and benefits from
    extra letter separation."
  {:label      "0.5px"
   :label-wide "0.6px"
   :display    "-0.01em"
   :body       "normal"})

(def font-faces-css
  "The `@font-face` declarations for IBM Plex. Emitted as one CSS
  string so callers can drop it into a `<style>` element with one
  inject. `font-display: swap` is mandatory so the fallback chain is
  visible before the webfont resolves — the shell never ships
  invisible text waiting on a network request.

  Per rf2-2rwdc + the rf2-s1r9a Phase 1 browser-gate trace: the
  auto-injected `@font-face` rules are `local()`-only — an
  OS-installed Plex picks up automatically, otherwise the fallback
  chain in `sans-stack` / `mono-stack` (Optima / Avenir Next /
  ui-sans-serif; ui-monospace / SF Mono / Cascadia Code) takes over.
  No `url()` entry is emitted because the testbed (and most consuming
  projects) does not vendor the woff2 files at `/fonts/plex/...` —
  every missing URL surfaced as a console 404 the browser-gate test
  runner counts as a failure. Consuming projects that DO want
  self-hosted or CDN webfonts inject their own `@font-face`
  declarations with `url()` entries pointing at their vendored
  woff2s; CSS allows a later `@font-face` with the same family name +
  weight to layer additional `src:` candidates.

  Three weights ship: 400 / 500 / 700; the 500-weight covers the
  `:semibold` token via `font-stretch`-free width matching since
  Plex 500 reads as the rubric calls 'semibold' in most pairings
  (Plex's 600 is closer to bold-light)."
  "@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:400;font-display:swap;src:local('IBM Plex Sans');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:500;font-display:swap;src:local('IBM Plex Sans Medium');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:600;font-display:swap;src:local('IBM Plex Sans SemiBold');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:700;font-display:swap;src:local('IBM Plex Sans Bold');}\n@font-face{font-family:'IBM Plex Mono';font-style:normal;font-weight:400;font-display:swap;src:local('IBM Plex Mono');}\n@font-face{font-family:'IBM Plex Mono';font-style:normal;font-weight:500;font-display:swap;src:local('IBM Plex Mono Medium');}\n")

#?(:cljs
   (defonce ^:private font-faces-injected? (atom false)))

#?(:cljs
   (defn inject-font-faces!
     "Inject the IBM Plex `@font-face` rules into `js/document.head`.
     Idempotent — subsequent calls short-circuit on the
     `font-faces-injected?` sentinel.

     The injected rules are `local()`-only — an OS-installed Plex
     picks up automatically (Mike's machines, IBM Carbon design
     system users); otherwise the fallback chain in `sans-stack` /
     `mono-stack` takes over. No HTTP fetch is attempted, so missing
     vendored woff2s never surface as console 404s (which the
     browser-gate test runner gates on).

     Consuming projects that want self-hosted or CDN webfonts inject
     their own `@font-face` declarations with `url()` entries
     pointing at their vendored woff2s; CSS allows a later
     `@font-face` with the same family + weight to layer additional
     `src:` candidates.

     Behind `re-frame.story.config/enabled?` — production builds
     short-circuit before touching the DOM."
     []
     (when (and config/enabled? (not @font-faces-injected?))
       (reset! font-faces-injected? true)
       (when (and (exists? js/document) (.-head js/document))
         (try
           (let [style (.createElement js/document "style")]
             (set! (.-type style) "text/css")
             (set! (.-innerHTML style) font-faces-css)
             (.appendChild (.-head js/document) style))
           (catch :default _ nil))))))
