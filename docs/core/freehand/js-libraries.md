# Using Freehand with a JavaScript library

Freehand is not a wall around the browser. Real apps use charts, maps, date
pickers, animation engines, and grid widgets written in JavaScript. The rule is
simple:

> **Keep Freehand’s tree data-oriented. Put the JS library behind an explicit host
> boundary.**

This page stays **inside Freehand**. It does not introduce another view substrate.
When a library needs a tiny React component of your own (for example so *you* can
call hooks), that component is still registered as a Freehand **host leaf** — a
local interop file, not a second app architecture.

Host **contracts** (leaf, behavior, wrapper, `->react`, error-boundary) are the
law; this page is the **recipe** companion.

## First decision: what kind of library is it?

| Library shape | Freehand approach | Examples (illustrative) |
|---|---|---|
| **Only enter/exit retention** | **`v/presence` + CSS** first | toasts, simple panel fade |
| **React component**: values in, callbacks out (hooks only *inside* the lib) | **Qualified host leaf** | date pickers, many charts, **Framer `motion.*` / `AnimatePresence`** |
| **Imperative DOM owner**: `new X(el)`, dispose | **Registered behavior** | Vega View, Mapbox GL, GSAP on a node |
| **Your code must call React hooks** (or similar) | **Small React component** as a qualified host leaf | `useMotionValue`, custom scroll-linked motion |

The fence is **hooks in your Freehand view body**, not “any JS library.” A foreign
React component that uses hooks **internally** is fine as a leaf. If *you* need to
call hooks, put them in a small React function component, register it with
`host/component`, and keep the rest of the screen in Freehand.

If the need is only “keep this node a moment so CSS can fade it,” start with
`v/presence` before any motion library.

## What never goes in app-db

The JS object, the DOM node, the timeline instance, and cleanup functions are
**host memory**. They must not ride in event vectors or be stored as “state” in
app-db.

What *does* live in re-frame:

- whether a panel is open  
- which step of a wizard is active  
- whether a toast is in the visible list  
- configuration you would time-travel or test (spec, series data, duration as data)  

What stays in the host boundary:

- `connect` / `disconnect` of an imperative library  
- `update` when config changes  
- animation players, tweens, and observers  
- Framer’s internal motion state  

## Pattern A — often enough: presence + CSS (no JS library)

For many toasts and panels, Freehand’s own presence API is the smaller design:

```clojure
(v/presence {:timeout-ms 300}
  (for [t (v/sub [:toasts/visible])]
    [:div.toast
     {:key (:id t)
      :class ["opacity-100"]
      ::v/unmounting {:class ["opacity-0"] :inert true :aria-hidden true}}
     (:message t)]))
```

Domain events only say which toasts exist. Presence keeps the node for exit; CSS
animates. Full guide: [Presence](presence.md).

Reach for GSAP or Framer when you need choreography, springs, shared layout,
gestures, or motion that CSS and presence cannot express cleanly.

## Pattern B — React leaf (values in, callbacks out)

Use this when the library (or its React wrapper) is basically props and callbacks.
Qualify the foreign head. Do not put a bare npm component in the Freehand tree
without a host descriptor.

```clojure
(ns app.ui.chart
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.host :as host]
            ["react-sparkline" :default Sparkline]))

(def sparkline-host
  (host/component ::sparkline Sparkline))

(v/defview trend-sparkline [_]
  (v/client-only
   {:fallback [:div.sparkline-placeholder "…"]}
   [sparkline-host
    {:data     (v/sub [:metrics/sparkline])
     :onSelect (v/event [point]
                 [:metrics/point-selected (js->clj point :keywordize-keys true)])}]))
```

Notes:

- Foreign callbacks use `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` — not
  a bare `fn`. See [Events — escape roster](events-and-handlers.md#the-escape-roster).  
- Browser-only leaves need `client-only` (or a real SSR adapter).  
- Open foreign props may pass JS objects through; that is leaf-specific, not the
  rule for Freehand’s own DOM nodes (those use keyword props and conversion).  

## Pattern C — Framer Motion as Freehand host leaves

**You stay on Freehand for the app.** Framer’s **component** API (`motion.div`,
`AnimatePresence`, `motion.button`, …) sits in Freehand as **qualified foreign
heads**: values in, callbacks out, children in one React tree. Hooks that live
*inside* those Framer components are Framer’s business — you are not calling them
from a `v/defview`.

### Sketch (interpreted Freehand)

```clojure
(ns app.toast
  (:require [re-frame.freehand :as v :refer [sub]]
            [re-frame.freehand.host :as host]
            ["framer-motion" :refer [AnimatePresence motion]]))

(def motion-div
  (host/component ::motion-div (.-div motion)))

(def animate-presence
  (host/component ::animate-presence AnimatePresence))

(v/defview toast [{:keys [id text]}]
  [motion-div
   {:initial #js {:opacity 0 :y 8}   ; foreign leaf: open props pass through
    :animate #js {:opacity 1 :y 0}
    :exit    #js {:opacity 0}
    :onAnimationComplete (v/event [] [:toast/settled id])}
   text])

(v/defview toasts [_]
  [animate-presence {}
   (for [{:keys [id text]} (sub [:toast/visible])]
     [toast {:key id :id id :text text}])])
```

What this is saying:

- re-frame still owns which toasts exist.  
- Freehand still owns the view tree and data events.  
- Framer still owns the motion implementation.  
- Callbacks use Freehand’s roster (`v/event`), not bare functions.  
- Heads are **qualified** with `host/component` — Freehand’s foreign-leaf rule.  

Conditional render stays familiar: drop the toast from app-db, remove the child;
`AnimatePresence` is supposed to retain it through exit while `motion` reads
presence through React context (one React tree under the Freehand host).

### Honesty: designed, not yet a proven pilot

The **AnimatePresence-through-a-Freehand-boundary** path is **designed and
argued**, not yet proven in a mounted exit pilot. Treat exit animation across a
Freehand shell as a hypothesis until a test shows:

1. toast removed from app-db  
2. exit motion runs  
3. node is gone afterward  

Enter-only motion is a weaker claim; exit is where composition usually breaks.
Compiled mode is a separate cliff: dynamic foreign heads and `#js` props may need
different factoring under `v/check`.

### When Framer needs a tiny React component of your own

Use a **small React function component** (registered as a Freehand host leaf) only
when **your** code must call Framer hook APIs, for example:

- `useMotionValue`, `useTransform`, `useScroll`  
- `useAnimate`, `useInView`  
- custom gesture wiring that only makes sense as hooks  

That component is interop glue. It is **not** a second view substrate and not a
reason to move the rest of the screen out of Freehand.

```clojure
(ns app.ui.scrubber
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.host :as host]
            ["react" :as react]
            ["framer-motion" :refer [useMotionValue]]))

;; Plain React component — hooks allowed here only
(defn Scrubber [props]
  (let [props (js->clj props :keywordize-keys true)
        x     (useMotionValue 0)]
    ;; render using x; call (:on-change props) with plain values
    (react/createElement "div" nil …)))

(def scrubber-host
  (host/component ::scrubber Scrubber))

(v/defview panel [_]
  [scrubber-host
   {:progress  (v/sub [:scrub/progress])
    :on-change (v/event [v] [:scrub/set v])}])
```

Same boundary as any other leaf: Freehand outside, host component at the edge.

## Pattern D — Imperative library on a DOM node (behavior)

Use this when the library wants an element and a lifecycle: create, update,
destroy. GSAP, anime.js, or raw Web Animations behind your own glue often look
like this.

```clojure
(ns app.ui.motion
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.host :as host]))

(defn connect-fade! [el {:keys [open? duration-ms]}]
  {:el el
   :open? open?
   :duration-ms (or duration-ms 250)
   :anim nil})

(defn update-fade! [mem cfg]
  ;; when open? flips, run enter or leave on (:el mem); never put el in app-db
  (merge mem cfg))

(defn disconnect-fade! [{:keys [anim]}]
  ;; cancel tweens, remove listeners
  nil)

(host/defbehavior fade-panel
  {:connect    connect-fade!
   :update     update-fade!
   :disconnect disconnect-fade!
   :ssr        :inert})

(v/defview animated-panel [{:keys [title]}]
  (let [open? (v/sub [:ui/panel-open?])]
    [:div.panel
     {::v/behavior [fade-panel {:open? open? :duration-ms 280}]}
     [:h2 title]
     (when open?
       [:div.body "…"])]))
```

| Rule | Why |
|---|---|
| `connect` after **commit** | speculative renders must not create players |
| Config is Clojure data | `open?`, durations as values |
| `update` on `rf=` config change | library reacts to re-frame facts |
| `disconnect` once | no leaked tweens or listeners |
| One behavior per node | do not co-own descendants with React if the library owns them |

Optional **commands** (export, scrub to time) use a semantic `:instance` target —
see [Host boundaries — commands](host-boundaries.md#commands-one-shot-host-ops).

Most animation “play when props change” work is **`:passive`** timing. Use
**`:layout`** only when you must measure before paint and can prove no wrong-frame
flash. Silent forever-`rAF` loops are not a hidden policy.

## Animation checklist

| Question | Prefer |
|---|---|
| Fade/slide on enter/exit only? | **`v/presence` + CSS** |
| Framer components only (`motion.*`, `AnimatePresence`)? | **Qualified Freehand host leaves** (+ pilot for exit) |
| You call Framer **hooks** (`useMotionValue`, …)? | **Small React host leaf** (hooks only inside that file) |
| Drive a non-React player (GSAP on a node)? | **Behavior** |
| Must mid-animation state time-travel? | Put *intent* in re-frame; keep the player in the host |

**Reduced motion:** read a preference (media query or app setting as data) and
pass a flag into the leaf or behavior. Freehand does not invent a global motion
bus.

## Common mistakes

| Mistake | Better |
|---|---|
| “Framer means leave Freehand for another UI stack” | Freehand page + Framer host leaves |
| Store the GSAP/Framer instance in app-db | Host memory only |
| Domain “unmount” events to own lifetime | re-frame owns whether the fact exists; motion is presentation |
| Bare foreign head with no host qualification | `host/component` |
| Bare `fn` on a foreign callback | `v/event` / `v/handler` / … |
| Assume AnimatePresence exit works untested | mounted pilot before you depend on it |
| Full motion library for one opacity fade | presence + CSS |

## Other libraries, same shapes

| Library class | Pattern |
|---|---|
| Date picker / select (React) | qualified leaf + `v/event` |
| Vega / Mapbox / SpreadJS | behavior (+ commands if needed) |
| Props-only React kits | qualified leaf |
| Hook APIs *you* call | small React component as host leaf |
| Freehand view inside a React grid cell | `v/->react` + live frame |
