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
| **Your code must call React hooks** (or similar) | a **small React component** of your own, entered as a child | `useMotionValue`, custom scroll-linked motion |

The fence is **hooks in your Freehand view body**, not “any JS library.” A foreign
React component that uses hooks **internally** is fine as a child. If *you* need
to call hooks, put them in a small React function component of your own and keep
the rest of the screen in Freehand.

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
(v/defview toast-card [{:keys [toast]}]
  (let [exiting? (= :unmounting (v/presence-phase))]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     (:message toast)]))

(v/defview toast-tray [_]
  (v/presence {:timeout-ms 300}
    (for [t (v/sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

Domain events only say which toasts exist. Presence keeps the node for exit; CSS
animates. Full guide: [Presence](presence.md).

Reach for GSAP or Framer when you need choreography, springs, shared layout,
gestures, or motion that CSS and presence cannot express cleanly.

## Pattern B — React leaf (values in, callbacks out)

Use this when the library (or its React wrapper) is basically props and callbacks.
Create the React **element** and put it in a child position — a bare React
component at a vector head is not a Freehand descriptor.

```clojure
(ns app.ui.chart
  (:require ["react" :as react]
            ["react-sparkline" :default Sparkline]
            [re-frame.freehand :as v]))

(v/defview trend-sparkline [_]
  (v/client-only
   {:fallback [:div.sparkline-placeholder "…"]}
   [:div.sparkline
    (react/createElement
      Sparkline
      #js {:data     (clj->js (v/sub [:metrics/sparkline]))
           :onSelect (v/event [point]
                       [:metrics/point-selected
                        (js->clj point :keywordize-keys true)])})]))
```

Notes:

- Foreign callbacks use `v/event` / `v/handler` / `v/render-fn` / `v/raw-fn` — not
  a bare `fn`. See [Events — escape roster](events-and-handlers.md#the-escape-roster).  
- The JVM structural renderer accepts no React elements, so a server-rendered root
  needs `v/client-only` around the child.  
- Props on the foreign element are the library's own ABI — JS objects and all.
  That is not the rule for Freehand's own DOM nodes, which take keyword props.  

## Pattern C — Framer Motion as Freehand host leaves

**You stay on Freehand for the app.** Framer’s **component** API (`motion.div`,
`AnimatePresence`, `motion.button`, …) enters a Freehand tree as **React elements
in child positions**: values in, callbacks out, one shared React tree. Hooks that
live *inside* those Framer components are Framer’s business — you are not calling
them from a `v/defview`.

### Sketch (interpreted Freehand)

```clojure
(ns app.toast
  (:require ["react" :as react]
            ["framer-motion" :refer [AnimatePresence motion]]
            [re-frame.freehand :as v :refer [sub]]))

(v/defview toast [{:keys [id text]}]
  [:div.toast-slot
   (react/createElement
     (.-div motion)
     #js {:initial #js {:opacity 0 :y 8}
          :animate #js {:opacity 1 :y 0}
          :exit    #js {:opacity 0}
          :onAnimationComplete (v/event [] [:toast/settled id])}
     text)])

(v/defview toasts [_]
  [:div.toasts
   (react/createElement
     AnimatePresence
     #js {}
     (clj->js (for [{:keys [id]} (sub [:toast/visible])]
                (v/->react toast) )))])
```

The outer half of that last expression is where the two worlds actually meet, and
it is worth being explicit about: `AnimatePresence` wants React **children** it can
retain, so the Freehand content going into it crosses back out through
`v/->react`.

What this is saying:

- re-frame still owns which toasts exist.  
- Freehand still owns the view tree and data events.  
- Framer still owns the motion implementation.  
- Callbacks use Freehand’s roster (`v/event`), not bare functions.  
- Foreign components are **elements in child positions**, never vector heads.  

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
Compiled mode is a separate cliff: the compiled grammar refuses `v/client-only`
outright and cannot see through a `createElement` call, so a view doing this work
stays interpreted — which is fine, because promotion is per declaration.

### When Framer needs a tiny React component of your own

Use a **small React function component** of your own, entered as a child, only
when **your** code must call Framer hook APIs, for example:

- `useMotionValue`, `useTransform`, `useScroll`  
- `useAnimate`, `useInView`  
- custom gesture wiring that only makes sense as hooks  

That component is interop glue. It is **not** a second view substrate and not a
reason to move the rest of the screen out of Freehand.

```clojure
(ns app.ui.scrubber
  (:require ["react" :as react]
            ["framer-motion" :refer [useMotionValue]]
            [re-frame.freehand :as v]))

;; Plain React component — hooks allowed here only
(defn Scrubber [props]
  (let [props (js->clj props :keywordize-keys true)
        x     (useMotionValue 0)]
    ;; render using x; call (:on-change props) with plain values
    (react/createElement "div" nil …)))

(v/defview panel [_]
  [:div.panel
   (react/createElement
     Scrubber
     #js {:progress  (v/sub [:scrub/progress])
          :onChange  (v/event [x] [:scrub/set x])})])
```

Same boundary as any other React child: Freehand outside, hooks confined to that
one file.

## Pattern D — Imperative library on a DOM node (behavior)

Use this when the library wants an element and a lifecycle: create, update,
destroy. GSAP, anime.js, or raw Web Animations behind your own glue often look
like this.

```clojure
(ns app.ui.motion
  (:require [re-frame.freehand :as v]))

;; Every lifecycle entry takes ONE context map.
(v/defbehavior fade-panel
  {:connect    (fn [{:keys [node config]}]
                 ;; return value becomes this connection's private memory
                 {:anim (start-fade! node config)})
   :update     (fn [{:keys [node config prev-config memory]}]
                 ;; runs only when :config moved by rf=
                 (retarget-fade! node config prev-config memory))
   :disconnect (fn [{:keys [memory]}]
                 (some-> (:anim memory) cancel!))})

(v/defview animated-panel [{:keys [title]}]
  (let [open? (v/sub [:ui/panel-open?])]
    [v/behavior {:use    fade-panel
                 :target :ui/fade-panel
                 :config {:open? open? :duration-ms 280}}
     [:div.panel
      [:h2 title]
      (when open? [:div.body "…"])]]))
```

The behavior owns **one element**, and that element is the boundary's single
child. `:config` is data at every depth — a node, a ref or a preconstructed player
is refused, which is exactly what keeps the use site readable by a test.

| Rule | Why |
|---|---|
| `:connect` after **commit** | a render React abandons creates no player |
| `:config` is data at every depth | `open?` and durations as values, never a node |
| `:update` on `rf=` config change | the library reacts to re-frame facts |
| `:disconnect` exactly once | no leaked tweens or listeners |
| The child is one element | a behavior owns one node, and can reach no other |
| `:opaque true` | say so when the library owns the descendants, and Freehand children there become an error |

Optional **commands** (export, scrub to time) address the `:target` from the use
site — see [Host boundaries — commands](host-boundaries.md#commands-one-shot-host-ops).

Most animation “play when props change” work is **`:passive`** timing. Use
**`:layout`** only when you must measure before paint and can prove no wrong-frame
flash. Silent forever-`rAF` loops are not a hidden policy.

## Animation checklist

| Question | Prefer |
|---|---|
| Fade/slide on enter/exit only? | **`v/presence` + CSS** |
| Framer components only (`motion.*`, `AnimatePresence`)? | **React elements in child positions** (+ pilot for exit) |
| You call Framer **hooks** (`useMotionValue`, …)? | a **small React component of your own** — hooks only inside that file |
| Drive a non-React player (GSAP on a node)? | **Behavior** |
| Must mid-animation state time-travel? | Put *intent* in re-frame; keep the player in the host |

**Reduced motion:** read a preference (a media query or an app setting, as data)
and pass a flag through props or `:config`. Freehand does not invent a global
motion bus.

## Common mistakes

| Mistake | Better |
|---|---|
| “Framer means leave Freehand for another UI stack” | a Freehand page with Framer elements as children |
| Store the GSAP/Framer instance in app-db | Host memory only |
| Domain “unmount” events to own lifetime | re-frame owns whether the fact exists; motion is presentation |
| Bare React component at a vector head | create the element; put it in a child position |
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
