(ns re-frame.story.theme.motion
  "Story motion tokens + entrance choreography (rf2-3lt89).

  The pre-rf2-3lt89 chrome shipped ZERO motion. Hover states snapped,
  the help overlay opened with no fade, and the shell threw every
  region onto the page in one synchronous render. The audit (rf2-s1r9a
  RED 2/10) called this out as the rubric's 'predictable layout that
  lacks context-specific character' anti-pattern.

  This namespace introduces:

  - Timing + easing tokens (`timing`, `easing`).
  - Pre-baked transition strings for the common chrome surfaces
    (`transition`).
  - A shell-mount entrance choreography that staggers
    toolbar → sidebar → main → right-rail with `animation-delay`
    offsets (`stagger`).
  - A `<style>` injection helper (`inject-motion-css!`) that lays
    down the `@keyframes` + `prefers-reduced-motion` overrides one
    time at shell mount.

  ## prefers-reduced-motion respect

  Every animation declared here is wrapped in a
  `@media (prefers-reduced-motion: reduce) { ... animation-duration:
  0.01ms; transition-duration: 0.01ms; }` override so users who have
  set OS-level reduced-motion get an instant-render shell. The
  helpers below emit BOTH the canonical motion rules AND the
  reduced-motion overrides in one `<style>` inject.

  ## CLJS / JVM split

  Token data is `.cljc`-portable so JVM tests can verify the
  choreography map. The inject helper is CLJS-only — it touches
  `js/document.head`."
  {:no-doc true}
  #?(:cljs (:require [re-frame.story.config :as config])))

(def timing
  "Duration tokens (CSS strings). Six slots covering the chrome's
  motion register:

  - `:micro-tap`     — 80ms — chip presses, button squashes.
  - `:hover`         — 140ms — colour / background transitions on hover.
  - `:overlay-fade`  — 180ms — help overlay, recorder dialogs.
  - `:panel-slide`   — 220ms — sidebar / inspector rail slide-ins.
  - `:entrance`      — 360ms — shell mount entrance steps (per region).
  - `:focus-ring`    — 120ms — focus-visible outline expansion."
  {:micro-tap    "80ms"
   :hover        "140ms"
   :overlay-fade "180ms"
   :panel-slide  "220ms"
   :entrance     "360ms"
   :focus-ring   "120ms"})

(def easing
  "Easing curves. Five expressive curves picked for distinct registers:

  - `:standard`     — gentle in/out for state-change transitions.
  - `:exit`         — fast-out — panel dismissals.
  - `:enter`        — slow-in — panel reveals.
  - `:emphatic`     — for the shell mount entrance choreography.
  - `:overshoot`    — playful — chip press / micro-tap rebound."
  {:standard  "cubic-bezier(0.4, 0.0, 0.2, 1)"
   :exit      "cubic-bezier(0.4, 0.0, 1.0, 1.0)"
   :enter     "cubic-bezier(0.0, 0.0, 0.2, 1.0)"
   :emphatic  "cubic-bezier(0.32, 0.72, 0, 1)"
   :overshoot "cubic-bezier(0.34, 1.56, 0.64, 1)"})

(defn transition
  "Build a CSS `transition` value for a property + a named timing /
  easing pair. Returns a single CSS shorthand string.

      (transition :background :hover :standard)
      ;; → \"background 140ms cubic-bezier(0.4, 0.0, 0.2, 1)\"

  Multi-property transitions compose by `str/join`-ing two of these.
  Pure data → data — JVM-testable."
  [prop timing-key easing-key]
  (let [t (get timing timing-key "140ms")
        e (get easing easing-key (:standard easing))]
    (str (name prop) " " t " " e)))

(def transitions
  "Pre-built transition strings for the most common chrome surfaces.
  Saves call sites from repeating `(transition …)` calls — drop the
  string into a `:transition` slot in an inline style map.

  Composed so the chrome reads as 'snappy hover, gentle reveal,
  playful tap':

  - `:chip`   — chips toggle background + colour on hover, with a
    sub-second overshoot on press.
  - `:row`    — sidebar rows highlight on hover with a slow standard
    fade.
  - `:focus`  — focus-visible outlines expand on the focus-ring
    timing.
  - `:overlay`— help / recorder dialogs fade in/out gracefully.
  - `:panel`  — panel slide-ins (e.g. command palette) use the
    emphatic curve."
  {:chip    (str (transition :background :hover :standard) ", "
                 (transition :color :hover :standard) ", "
                 (transition :transform :micro-tap :overshoot))
   :row     (str (transition :background :hover :standard) ", "
                 (transition :color :hover :standard))
   :focus   (str (transition :outline-offset :focus-ring :enter) ", "
                 (transition :outline-color :focus-ring :enter))
   :overlay (str "opacity " (:overlay-fade timing) " " (:enter easing) ", "
                 "transform " (:overlay-fade timing) " " (:enter easing))
   :panel   (str "transform " (:panel-slide timing) " " (:emphatic easing) ", "
                 "opacity " (:panel-slide timing) " " (:enter easing))})

(def stagger
  "Shell-mount entrance choreography. Each region carries an
  `animation-delay` offset so the four landmarks reveal in sequence:

      toolbar  (0ms)
      sidebar  (60ms)
      main     (120ms)
      right    (180ms)

  Per the frontend-design rubric: 'one well-orchestrated page load
  with staggered reveals (animation-delay) creates more delight than
  scattered micro-interactions.' This is Story's high-impact moment.

  Slots key on chrome region (matching the `data-test` landmarks the
  shell stamps). Call sites pull the delay + apply the
  `rf-story-mount-in` keyframe (defined in `motion-css`)."
  {:toolbar "0ms"
   :sidebar "60ms"
   :main    "120ms"
   :right   "180ms"})

(defn stagger-animation
  "Build the inline `:animation` value for a chrome region. The
  keyframe `rf-story-mount-in` is defined in `motion-css` below and
  injected once at shell mount.

      (stagger-animation :sidebar)
      ;; → \"rf-story-mount-in 360ms cubic-bezier(0.32, 0.72, 0, 1) 60ms both\"

  `both` keeps the keyframe's initial state applied before the delay
  fires — without `both` the region would flash visible during the
  delay window. Pure — JVM-testable."
  [region]
  (str "rf-story-mount-in "
       (:entrance timing) " "
       (:emphatic easing) " "
       (get stagger region "0ms")
       " both"))

(def motion-css
  "The `@keyframes` + `prefers-reduced-motion` overrides Story
  injects once at shell mount. Includes:

  - `rf-story-mount-in`     — the shell-entrance keyframe (10px lift +
    fade-in). Used by every region via `stagger-animation`.
  - `rf-story-overlay-in`   — the help / recorder dialog open
    keyframe (8px lift + fade-in).
  - `rf-story-overlay-out`  — paired close keyframe.
  - `rf-story-chip-press`   — the micro-tap chip-press rebound used
    on `:active` for chips.
  - `:focus-visible { outline: 2px solid amber; outline-offset: 2px; }`
    standardised focus ring across the chrome.
  - `@media (prefers-reduced-motion: reduce)` override that pins
    every Story-prefixed animation / transition to a 0.01ms duration
    so users with reduced-motion preferences get an instant render."
  "@keyframes rf-story-mount-in{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
@keyframes rf-story-overlay-in{from{opacity:0;transform:translateY(8px) scale(0.985)}to{opacity:1;transform:translateY(0) scale(1)}}
@keyframes rf-story-overlay-out{from{opacity:1;transform:translateY(0) scale(1)}to{opacity:0;transform:translateY(4px) scale(0.99)}}
@keyframes rf-story-chip-press{0%{transform:scale(1)}40%{transform:scale(0.94)}100%{transform:scale(1)}}
@keyframes rf-story-shimmer{0%{background-position:200% 0}100%{background-position:-200% 0}}
[data-rf-story-root] *:focus-visible{outline:2px solid #F5A524;outline-offset:2px;border-radius:3px;transition:outline-offset 120ms cubic-bezier(0.0, 0.0, 0.2, 1.0)}
@media (prefers-reduced-motion: reduce){
  [data-rf-story-root] *,[data-rf-story-root] *::before,[data-rf-story-root] *::after{animation-duration:0.01ms !important;animation-delay:0ms !important;transition-duration:0.01ms !important;transition-delay:0ms !important}
}")

#?(:cljs
   (defonce ^:private motion-css-injected? (atom false)))

#?(:cljs
   (defn inject-motion-css!
     "Inject the `@keyframes` + `prefers-reduced-motion` override
     stylesheet into `js/document.head`. Idempotent — subsequent
     calls short-circuit on the `motion-css-injected?` sentinel.

     Behind `re-frame.story.config/enabled?` — production builds
     short-circuit before touching the DOM."
     []
     (when (and config/enabled? (not @motion-css-injected?))
       (reset! motion-css-injected? true)
       (when (and (exists? js/document) (.-head js/document))
         (try
           (let [style (.createElement js/document "style")]
             (set! (.-type style) "text/css")
             (set! (.-innerHTML style) motion-css)
             (.appendChild (.-head js/document) style))
           (catch :default _ nil))))))
