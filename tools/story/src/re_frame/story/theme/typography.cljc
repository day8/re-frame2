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
  Self-hosting (rather than CDN) is the v1 default — the story-shipped
  shell loads webfonts via the `inject-font-faces!` helper below,
  pointing at `/fonts/...` paths that consuming projects vendor into
  their public/ tree. CDN is also acceptable; the fallback chain is
  identical either way.

  Per `re-frame.story.config/enabled?` the inject helper short-circuits
  in production so published static builds never reach for dev-only
  webfont URLs.

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

(def font-faces-css
  "The `@font-face` declarations for self-hosted IBM Plex. Emitted as
  one CSS string so callers can drop it into a `<style>` element with
  one inject. `font-display: swap` is mandatory so the fallback
  chain is visible before the webfont resolves — the shell never
  ships invisible text waiting on a network request.

  Self-hosted under `/fonts/plex/` by convention; consuming projects
  vendor the woff2s into `public/fonts/plex/` (Plex is OFL-licensed —
  free to redistribute). Three weights ship: 400 / 500 / 700; the
  500-weight covers the `:semibold` token via `font-stretch`-free
  width matching since Plex 500 reads as the rubric calls 'semibold'
  in most pairings (Plex's 600 is closer to bold-light)."
  "@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:400;font-display:swap;src:local('IBM Plex Sans'),url('/fonts/plex/IBMPlexSans-Regular.woff2') format('woff2');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:500;font-display:swap;src:local('IBM Plex Sans Medium'),url('/fonts/plex/IBMPlexSans-Medium.woff2') format('woff2');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:600;font-display:swap;src:local('IBM Plex Sans SemiBold'),url('/fonts/plex/IBMPlexSans-SemiBold.woff2') format('woff2');}\n@font-face{font-family:'IBM Plex Sans';font-style:normal;font-weight:700;font-display:swap;src:local('IBM Plex Sans Bold'),url('/fonts/plex/IBMPlexSans-Bold.woff2') format('woff2');}\n@font-face{font-family:'IBM Plex Mono';font-style:normal;font-weight:400;font-display:swap;src:local('IBM Plex Mono'),url('/fonts/plex/IBMPlexMono-Regular.woff2') format('woff2');}\n@font-face{font-family:'IBM Plex Mono';font-style:normal;font-weight:500;font-display:swap;src:local('IBM Plex Mono Medium'),url('/fonts/plex/IBMPlexMono-Medium.woff2') format('woff2');}\n")

#?(:cljs
   (defonce ^:private font-faces-injected? (atom false)))

#?(:cljs
   (defn inject-font-faces!
     "Inject the IBM Plex `@font-face` rules into `js/document.head`.
     Idempotent — subsequent calls short-circuit on the
     `font-faces-injected?` sentinel.

     Falls back gracefully when webfont files are absent: `local()`
     entries in the `src:` chain pick up an OS-installed Plex (Mike's
     machines, IBM Carbon design system users) without any HTTP fetch,
     and a 404 on the woff2 just leaves the fallback chain visible.

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
