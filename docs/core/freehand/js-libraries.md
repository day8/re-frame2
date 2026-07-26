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

Host **contracts** live on [Host boundaries](host-boundaries.md): **`v/defhost`**
(paved React in), a finished React element as a weaker child escape, registered
behaviors, and `v/->react` outward. This page is the **recipe** companion.

## First decision: what kind of library is it?

| Library shape | Freehand approach | Examples (illustrative) |
|---|---|---|
| **Only enter/exit retention** | **`v/presence` + CSS** first | toasts, simple panel fade |
| **React component**: values in, Freehand intents out | **`v/defhost`** (paved) | date pickers, many charts |
| **React element you already built** | child `createElement` + plain closures | ad-hoc islands when you skip a declaration |
| **Imperative DOM owner**: `new X(el)`, dispose | **Registered behavior** | Vega View, Mapbox GL, GSAP on a node |
| **Imperative owner you must *await***: construction answers a Promise | **Registered behavior** whose `:connect` returns a **cell** | `vegaEmbed(el, spec)`, a Maps `loader.load()`, a workbook `.ready` |
| **Your code must call React hooks** (or similar) | small React component of your own, then **`v/defhost`** or a child element | `useMotionValue`, custom scroll-linked motion |

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

## Pattern B — React via `v/defhost` (paved)

Use this when the library (or its React wrapper) is props and callbacks. Declare
the component once with **`v/defhost`**, mount the descriptor at a vector head,
and use the escape roster at declared callback positions. That is the paved path
in [Host boundaries](host-boundaries.md#declared-hosts-vdefhost).

```clojure
(ns app.ui.chart
  (:require ["react-sparkline" :default Sparkline]
            [re-frame.freehand :as v]))

(v/defhost sparkline
  Sparkline
  {:callbacks {:onSelect :event}
   :children  :none
   :ssr       :client-only})

(v/defview trend-sparkline [_]
  (v/client-only
   {:fallback [:div.sparkline-placeholder "…"]}
   [sparkline
    {:data     (clj->js (v/sub [:metrics/sparkline]))
     :onSelect (v/event [point]
                 [:metrics/point-selected
                  (js->clj point :keywordize-keys true)])}]))
```

Notes:

- **`v/event` is legal here** — Freehand owns the callback site and materializes a
  committed proxy. That is the opposite of a raw `createElement` `#js` prop.  
- Prop names on the host pass **exactly** (`:onSelect` → `onSelect`).  
- Server-side: `:ssr :client-only` on the declaration (or wrap with `v/client-only`).  

### Weaker escape: a finished React element as a child

When you already hold an element and do not want a declaration, put
`react/createElement` in a **child** position. Freehand does **not** walk those
`#js` props — use a plain closure over `(rf/capture-frame)`'s `:dispatch`, never
a roster carrier. Details:
[Host boundaries — element as child](host-boundaries.md#a-react-element-as-a-child).

## Pattern C — Framer Motion (compound React children)

**You stay on Freehand for the app.** Framer’s **component** API (`motion.div`,
`AnimatePresence`, `motion.button`, …) often needs **finished React elements as
children** (especially `AnimatePresence`, which filters with `isValidElement`).
That is the weaker child-element escape, not `v/defhost`: values and closures in
`#js` props, Freehand views exported with `v/->react` then **created as elements**.
Hooks *inside* Framer stay Framer’s business — you are not calling them from a
`v/defview`.

### Sketch (interpreted Freehand)

```clojure
(ns app.toast
  (:require ["react" :as react]
            ["framer-motion" :refer [AnimatePresence motion]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))

(v/defview toast [{:keys [id text]}]
  (let [{:keys [dispatch]} (rf/capture-frame)]
    [:div.toast-slot
     (react/createElement
       (.-div motion)
       #js {:initial #js {:opacity 0 :y 8}
            :animate #js {:opacity 1 :y 0}
            :exit    #js {:opacity 0}
            :onAnimationComplete (fn [] (dispatch [:toast/settled id]))}
       text)]))

;; `v/->react` answers a COMPONENT, and repeated exports of one view answer
;; the identical one — so hoist it and let React reconcile on it.
(def Toast (v/->react toast))

(v/defview toasts [_]
  [:div.toasts
   (react/createElement
     AnimatePresence
     #js {}
     (into-array
       (for [{:keys [id text]} (sub [:toast/visible])]
         (react/createElement Toast #js {:key (str id) :id id :text text}))))])
```

The last expression is where the two worlds actually meet, and three details in it
carry the crossing. **`v/->react` answers a component, not an element** —
`AnimatePresence` retains React *children*, and it filters what it is handed
through `isValidElement`, so a component value passed as a child is dropped and
the tray renders nothing. Each toast therefore becomes an element through
`react/createElement`. **The props object is the exported view's own props, by
exact name**: `id` and `text` arrive in the view's props map as `:id` and `:text`,
which is the one shallow rule the bridge states — no camelisation, no deep walk.
**And every child carries a `key`**, because `AnimatePresence` tracks an exit by
key; an unkeyed list gives it no identity to animate out.

What this is saying:

- re-frame still owns which toasts exist.  
- Freehand still owns the view tree and data events.  
- Framer still owns the motion implementation.  
- Callbacks in a foreign element's `#js` props are plain closures over
  `rf/capture-frame` — Freehand does not walk those props, so no roster carrier is
  materialised there.  
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
            [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; Plain React component — hooks allowed here only
(defn Scrubber [props]
  (let [props (js->clj props :keywordize-keys true)
        x     (useMotionValue 0)]
    ;; render using x; call (:on-change props) with plain values
    (react/createElement "div" nil …)))

(v/defview panel [_]
  (let [{:keys [dispatch]} (rf/capture-frame)]
    [:div.panel
     (react/createElement
       Scrubber
       #js {:progress  (v/sub [:scrub/progress])
            :onChange  (fn [x] (dispatch [:scrub/set x]))})]))
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
                 ;; :connect ESTABLISHES this connection's private memory, and
                 ;; nothing else ever writes it — so a handle that EVOLVES lives
                 ;; in a mutable cell the later entries swap in place.
                 (atom {:anim (start-fade! node config)}))
   :update     (fn [{:keys [node config prev-config memory]}]
                 ;; runs only when :config moved by rf=; the return is ignored
                 (swap! memory assoc
                        :anim (retarget-fade! node config prev-config
                                              (:anim @memory))))
   :disconnect (fn [{:keys [memory]}]
                 (some-> (:anim @memory) cancel!))})

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
| Only `:connect` establishes the memory | `:update`, a command and `:disconnect` receive it; their returns are ignored, so a void-returning player call cannot erase the handle |
| `:disconnect` exactly once | no leaked tweens or listeners |
| The child is one element | a behavior owns one node, and can reach no other |
| `:opaque true` | say so when the library owns the descendants, and Freehand children there become an error |

Optional **commands** (export, scrub to time) address the `:target` from the use
site — see [Host boundaries — commands](host-boundaries.md#commands-one-shot-host-ops).

Most animation “play when props change” work is **`:passive`** timing. Use
**`:layout`** only when you must measure before paint and can prove no wrong-frame
flash. Silent forever-`rAF` loops are not a hidden policy.

## Pattern E — the handle arrives later (Promise-acquired hosts)

A large class of libraries cannot be constructed on the spot. `vegaEmbed(el, spec)`
answers a Promise. A Maps `loader.load()` answers a Promise. A workbook has a
`.ready`. So at the moment `:connect` runs, the thing you are supposed to own does
not exist yet.

This needs no new machinery, and it is worth being precise about why. **re-frame
event processing is one complete synchronous pass.** A Promise settling later does
not pause an event, resume a handler, or await anything — it just runs a callback,
which may dispatch a new and entirely ordinary event. There is nothing to
schedule, so there is no scheduler.

What the lifecycle needs is a *place to put the handle when it turns up*, and the
memory law already says where: `:connect` establishes the connection's private
memory **once**, and `:update`, a command and `:disconnect` only ever receive it.
So when the handle is not ready, what `:connect` returns is a **mutable cell** —
and the deferred continuation moves that one cell in place, exactly as a
void-returning mutator does.

```clojure
(ns app.ui.chart
  (:require [re-frame.freehand :as v]))

;; The library's own async door, and its handle:
;;   (acquire! node spec)     => Promise of a handle
;;   (set-spec! handle spec)  mutates, answers nothing
;;   (dispose! handle)        releases it, once

(v/defbehavior async-chart
  {:connect
   (fn [{:keys [node config dispatch]}]
     ;; The handle is not ready. The MEMORY is — so :connect returns a cell,
     ;; synchronously, and the continuation closes over that local (NOT over
     ;; (:memory ctx), which is still nil while :connect is running).
     (let [cell (atom {:phase :pending :spec config})]
       (.then (acquire! node config)
              (fn [handle]
                (if (= :closed (:phase @cell))
                  (dispose! handle)                     ; late success: finalise, never install
                  (do (swap! cell assoc :phase :ready :handle handle)
                      (set-spec! handle (:spec @cell))  ; the LATEST spec, not :connect's
                      (dispatch [:chart/ready]))))      ; an ordinary event, fenced to this connection
              (fn [_err]
                ;; :closed is TERMINAL — a late failure is evidence only
                (swap! cell #(cond-> % (not= :closed (:phase %)) (assoc :phase :failed)))))
       cell))

   :update
   (fn [{:keys [config memory]}]
     (swap! memory assoc :spec config)                  ; desired state, always
     (when (= :ready (:phase @memory))                  ; a host call only if there is a host
       (set-spec! (:handle @memory) config)))

   :disconnect
   (fn [{:keys [memory]}]
     ;; FENCE FIRST, then release: an acquisition still in flight must find a
     ;; closed cell and finalise itself.
     (let [{:keys [phase handle]} @memory]
       (swap! memory assoc :phase :closed)
       (when (= :ready phase) (dispose! handle))))})
```

Five rules, and each one is a bug you would otherwise ship:

| Rule | The bug it prevents |
|---|---|
| `:connect` returns the cell **synchronously** | returning the Promise leaves `:disconnect` with nothing to release |
| The continuation closes over the **local** cell | `(:memory ctx)` is still `nil` inside `:connect` |
| `:disconnect` **fences before it releases** | an in-flight acquisition installs into a connection that is gone |
| `:closed` is **terminal** | a late failure reopens a cell the teardown already settled |
| Take the **two-argument** `.then` | a trailing `.catch` lets a throw from the success arm masquerade as an acquisition failure |

**Commands do not queue.** While the phase is `:pending` there is no host, so a
command refuses — visibly, naming the phase — and is not remembered. Replaying it
when the handle finally arrives would fire an export the user asked for and gave
up on.

**The outward `dispatch` is already fenced.** A behavior context resolves its
connection at firing time, so a continuation that outlives its node dispatches
nothing and answers `false`. You do not need to null out your own callbacks; you
do need to release host listeners in `:disconnect`.

**A finalizer that fails is the host's problem, and the recipe survives it by
ordering rather than by catching.** `:disconnect` writes `:closed` before it
touches the host, and the substrate removes the connection record before
`:disconnect` runs at all — so a `dispose!` that throws still leaves the owner
terminal, the connection table and target index empty, and nothing holding a
reference to retry from. What it leaves behind is the host's own instance, which
no recipe can release: the library was asked exactly once and refused. Report
that honestly rather than papering over it.

Where the failure surfaces depends on which finalizer site threw, and one of the
two is quiet:

| The `dispose!` that throws | What you see |
|---|---|
| in `:disconnect`, at unmount | the throw comes straight back out of the unmount call — loud, and React neither swallows nor reroutes it |
| in the late-success continuation | an **unhandled promise rejection** — and everything after it in that arm is skipped, so a `dispatch` placed below a late `dispose!` never runs |

The second is the one to design around. If you need to know that a late handle
was abandoned, dispatch **before** you finalise it, or wrap the finalise in your
own `try`/`catch` — the announcement is not guaranteed to survive a host that
refuses to be released.

### Proven, not merely argued

Unlike the exit-animation path above, this one is mounted. The ordering that
matters — **unmount before the Promise resolves** — is asserted in
`behavior_async_dom_cljs_test.cljs` against a deterministic surrogate: the late
handle is disposed exactly once, never configured, and the library's book of
undisposed instances reads empty. **Finalizer failure** is asserted there too, on
both sites and on the *first* release rather than a second one: exactly one
release attempted, the owner already `:closed` when it was attempted, both
framework books empty afterwards, and the host's surviving instance asserted as a
leak instead of wished away. A real third-party witness is still outstanding.

## Animation checklist

| Question | Prefer |
|---|---|
| Fade/slide on enter/exit only? | **`v/presence` + CSS** |
| Framer components only (`motion.*`, `AnimatePresence`)? | **React elements in child positions** (+ pilot for exit) |
| You call Framer **hooks** (`useMotionValue`, …)? | a **small React component of your own** — hooks only inside that file |
| Drive a non-React player (GSAP on a node)? | **Behavior** |
| Construction answers a Promise? | **Behavior** whose `:connect` returns a **cell** ([Pattern E](#pattern-e--the-handle-arrives-later-promise-acquired-hosts)) |
| Must mid-animation state time-travel? | Put *intent* in re-frame; keep the player in the host |

**Reduced motion:** read a preference (a media query or an app setting, as data)
and pass a flag through props or `:config`. Freehand does not invent a global
motion bus.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Bare React component at a vector head | `v/defhost` and mount the descriptor, or createElement in a **child** position |
| `v/event` in `#js` props does nothing when the library calls it | Freehand does not walk those props — use `v/defhost` for roster callbacks, or a plain closure over `rf/capture-frame` |
| `:rf.error/no-frame-context` from a library callback | close over `(rf/capture-frame)`'s `:dispatch` during render |
| `AnimatePresence` shows nothing | pass **elements** (`createElement` of `v/->react` views), not component values; key each child |
| Instance / View object in app-db | host memory only — config and domain facts in re-frame |
| Promise from `:connect`, then disconnect races | return a **cell**; fence on disconnect; late success must not install |
| Full motion library for one opacity fade | `v/presence` + CSS first |

## When not

- **Headless** cores (TanStack Table core, etc.) — state in re-frame; Freehand
  markup; no host shape.  
- **Heavy Radix / `asChild` product shell** — UIx (or similar) for that region;
  Freehand as islands.  
- **Fade/slide only** — presence + CSS before any motion library.

## Other libraries, same shapes

| Library class | Pattern |
|---|---|
| Date picker / select (React) | **`v/defhost`** (paved); or child element + `rf/capture-frame` closures |
| Mapbox GL, a Vega `View` you construct yourself | behavior (+ commands if needed) |
| Vega Embed, a Maps `loader.load()`, a workbook `.ready` | behavior whose `:connect` returns a cell — [Pattern E](#pattern-e--the-handle-arrives-later-promise-acquired-hosts) |
| Props-only React kits | `v/defhost` |
| Hook APIs *you* call | small React component, then `v/defhost` or child element |
| Freehand view inside a React grid cell | `v/->react` + live frame |
