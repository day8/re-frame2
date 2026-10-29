# guided-views — M-11: ambient frame reads after the render

The **M-11 sweep** — finding the views and callbacks whose ambient `rf/dispatch` / `rf/subscribe` run where no frame is in scope, and giving each one a frame it carries. The leaf holds the decision table, the `defn` ‒→ `reg-view` execution recipe (Form-1 / Form-2 / Form-3 and the `defmulti` shape) for the sites where conversion is the fix, the `reg-view*` **capture-once** contract, and the async listener / timer / error-handler class.

This is the **canonical migration recipe** for the Form-3 routes and the capture-once retarget invariance. The other Type B handler / state walkthroughs (M-3, M-5, M-10, M-12, M-13, M-14, M-15, M-15b, M-34, M-42) stay in the sibling leaf [`guided-handlers-state.md`](guided-handlers-state.md).

---

## Contents

- M-11 — Ambient frame reads in callbacks that run after the render
- Executing the conversion: views from defn to reg-view
- The async listener timer and error-handler class

## M-11 — Ambient frame reads in callbacks that run after the render

**What already works.** While a view renders, its ambient `subscribe` / `dispatch` resolve the frame from the component tree — the closest enclosing `frame-provider` (SCOPE) or `frame-root` (ENSURE) — for a `reg-view` and a plain `(defn …)` Reagent fn alike, and the provider wins over any `with-frame` around the render. A subscribing plain fn under the app's root boundary needs no conversion.

**Identify** — every bare fully-qualified `rf/dispatch` / `rf/subscribe` (the ambient 1-arity forms) that can run where no frame is in scope — outside a render under a boundary, with no frame binding around it — in a `reg-view` and a plain fn alike:

1. **A callback that runs after the render** — an `:on-*` handler, a timer, a promise continuation, a lifecycle method — and a helper such a callback calls.
2. **The body of a reaction a view creates** — `r/reaction`, `r/track` / `r/track!`, `reagent.ratom/make-reaction` — and a fn the view hands to a helper that runs it inside a reaction of its own. The reaction's first run sits inside the render and resolves; when a source changes, Reagent re-runs it from its own flush, outside every render.
3. **A `reg-sub` computation fn** — an `@(rf/subscribe …)` in the body (a dynamic dependency), and a callback the body builds into the sub's value (an `:on-click` in a returned menu item). A sub body is not a render and runs under no frame binding of its own, so a frame read there resolves only on a run that happens to sit inside a render or a `with-frame`, and the recomputes Reagent's flush runs sit in neither.
4. **A view under no boundary** — one that reads the frame ambiently but renders outside every `[rf/frame-provider {:frame <id>} …]` / `[rf/frame-root {:id <id>} …]` boundary, with no live `with-frame` around its render. (Two components, one verb each — *roots ensure; providers scope* — so match both component names when sweeping.)

**Risk**: code that runs outside a render has no tree to consult, so only the dynamic scope can answer — which has unwound by the time that code runs. The call carries no frame and raises `:rf.error/no-frame-context` (EP-0002 — no silent `:rf/default` routing); a boundary-less view raises the same way when it renders. Each surfaces at runtime, at its own moment: a callback when it first fires; a boundary-less view when it first renders; a reaction body or a `reg-sub` body on the first change to one of its sources, which a boot smoke that only renders never reaches. A `reg-sub` body fails **silently** besides: the sub machinery recovers the throw to `nil` (`:rf.error/sub-exception`), so the view renders `nil` rather than an error.

**Decision shape** — the fixes:

1. **Capture at render time.** Call the ops a `reg-view` injects (`dispatch` / `subscribe`, captured when the view renders), or take `(rf/capture-frame)` during the render — in a plain fn too — and call its `dispatch` from the callback: `(let [{:keys [dispatch]} (rf/capture-frame)] [:button {:on-click #(dispatch [:inc])} "+"])`. In a plain `defn` Form-2 or Form-3 the outer fn runs inside the component's first render, once per mount, and so does an `r/with-let` init: a capture there serves every callback and lifecycle hook they close over, with no `reg-view*` registration, provided the component mounts in hiccup position (`[my-view …]`), and route 2's capture-once caveat applies to it. A sub the callback reads takes a one-shot read, not the captured `subscribe` (below). Recommended.
2. **Carry the frame explicitly.** A frame api passed in as an argument or a prop — the `(rf/capture-frame)` the caller took at render, which follows that caller's frame, whichever it is; or `(rf/capture-frame frame-id)` locked to a named frame, or a `{:frame <id>}` opt on the call, where the frame is a fixed, known one.
3. **Scope the render** — for a boundary-less view, mount it under a `frame-provider` / `frame-root`.
4. **Leave as-is** — when the fn calls `rf/subscribe` / `rf/dispatch` only during its render under a boundary, or establishes / carries a frame explicitly in its own body. Do **not** describe a boundary-less frame-dependent fn as pinning to `:rf/default`: there is no `:rf/default` fall-through (EP-0002), so it raises `:rf.error/no-frame-context`; it does not silently route to a default. To *intentionally* target `:rf/default`, scope or pass that frame explicitly like any other; document why.
5. **Take the read out of the body** — for a `reg-sub` body that reads another sub: declare the read under `:inputs`, which the runtime resolves in the sub's own frame, or compute it from `db`.

**Which fix, per site:**

| Site | Fix |
|---|---|
| An ambient read during a render under a boundary | **4** — leave as-is. |
| A callback (Identify 1) | **1** — call the captured `dispatch` from the callback, and read a sub there once (below). |
| A reaction body (Identify 2) | **1**, with the captured `subscribe` called *inside the body* (below). |
| A helper a callback reaches, whether or not a render reaches it too | **2** — the helper takes the frame api as an argument (below). |
| A `reg-sub` body that reads a sub (Identify 3) | **5** (below). |
| A callback a `reg-sub` body builds (Identify 3) | **2** with a named frame, where the app reads the sub under one known frame (below). |
| A view under no boundary (Identify 4) | **3** — scope the render. |

**A callback reads a sub once.** The captured `subscribe` is a reactive read, and a callback is no reactive owner: each call there takes a sub-cache share that nothing releases — one more per fire — and returns the right value, so nothing shows the leak. Read the current value with `(rf/subscribe-once query-v {:frame frame})`, `frame` taken at render (the `:frame` of the `(rf/capture-frame)` taken there, or `(rf/current-frame-id)` in a `reg-view`): it retains nothing. Where the callback should see the value as it stood at render, read it at render and close over it. A live read outside a render belongs only in a reaction body, which owns it (below), or in a hook that pairs it with an `unsubscribe`, as route 2's imperative recipe does. `{:frame frame}` addresses the frame by id where the captured ops are pinned to the incarnation live at capture, so where a frame can be destroyed and re-created under the same id while the callback is live, the one-shot read reaches the successor.

**A reaction body keeps a reactive read.** Capture at render — the `reg-view`-injected `subscribe`, or `(rf/capture-frame)` taken during the render — and call the captured `subscribe` inside the reaction body. The reaction is that read's reactive owner and releases it when the reaction disposes, so nothing outlives the reaction. Keep it `subscribe`: `subscribe-once` is a non-reactive read, so the reaction would not re-run when that sub changes. For a fn the view hands to a helper's reaction, close that fn over the view's captured `subscribe`.

```clojure
;; before — the reaction's first run is in the render and resolves; Reagent's
;; flush re-runs it on the first change, where the ambient subscribe raises
(defn visible-rows []
  (let [rows (r/reaction (filter-rows @(rf/subscribe [:rows]) @(rf/subscribe [:filter])))]
    (fn [] [:ul (for [row @rows] ^{:key (:id row)} [:li (:label row)])])))

;; after — captured in the outer fn, which runs in the first render; read in the body
(defn visible-rows []
  (let [{:keys [subscribe]} (rf/capture-frame)
        rows (r/reaction (filter-rows @(subscribe [:rows]) @(subscribe [:filter])))]
    (fn [] [:ul (for [row @rows] ^{:key (:id row)} [:li (:label row)])])))
```

**A helper a callback reaches takes the frame api as an argument.** Every caller passes the `(rf/capture-frame)` it took at render, and the helper calls that api's ops, so it follows each caller's frame. A helper that only dispatches may keep its signature instead: it takes `(rf/capture-frame)` at entry, and each caller in a callback wraps the call in `(rf/with-frame (:frame api) …)` with the api it captured at render. A helper that reads a sub keeps the reactive read on the render path — hoist the read into the render and pass the value in — and gives its callback path a one-shot `(rf/subscribe-once query-v {:frame (:frame api)})`. Wrapping a subscribing helper in `with-frame` stops the raise, but its `@(subscribe …)` then runs with no reactive owner and takes a sub-cache share that nothing releases. In a migration fanned out across units the helper's shape binds every caller's unit, so its owner fixes and publishes it before any caller's unit converts a call site, as Wave 0 publishes ids ([`orchestrating-a-large-migration.md` §2](orchestrating-a-large-migration.md#2-the-wave-0-id-contract--coordinate-without-sharing-files)).

**A `reg-sub` body takes its reads out of the body.** An inner query built from the outer query vector or from constants is declared under `:inputs` — a literal vector, or a producer `(fn [[_ id]] [[:item/by-id id]])` ([M-75](auto-call-site-rewrites.md#m-75--subscription-inputs-move-into-inputs), [M-71](guided-interceptors-subs.md#m-71--the-v1-signal-function-reg-sub-form-3-arity--v2-input-fns)). One built from a value the body computes cannot be, because a producer reads no state (M-71 case 4): thread that value through the outer query vector at the call site, or, in a layer-1 sub, compute the inner value from `db` without subscribing — a plain fn, or `(rf/compute-sub query-v db)`, which is pure and needs no frame.

```clojure
;; before — resolves only while a render runs the body; nil from the first change
(rf/reg-sub :cart/lines
  (fn [db _] (mapv #(deref (rf/subscribe [:product/by-id %])) (:cart/ids db))))

;; after — the ids come from db, so each read computes against the same db
(rf/reg-sub :cart/lines
  (fn [db _] (mapv #(rf/compute-sub [:product/by-id %] db) (:cart/ids db))))
```

**A callback a `reg-sub` body builds carries a named frame.** The body is no capture site: a 0-arity `(rf/capture-frame)` there raises on the first recompute, and the sub yields `nil`. Where the app reads the sub under one known frame, put `{:frame <id>}` on the callback's call, or close the callback over `(rf/capture-frame <id>)`. Under more than one frame only the other fix holds — the sub returns data (the event vector) and the view builds the callback with its render-captured `dispatch` — and it changes the sub's value shape, so hand that site to the author.

**Size it in the Phase-0a inventory, with a parse.** Count the deferred call sites up front, reaction and `reg-sub` bodies included. A v1 app dispatched from every `:on-click` with a bare `rf/dispatch`, because v1 had one global frame, so this is usually the larger half of M-11. Whether an ambient op is a site turns on the position of its innermost enclosing fn — a render body, or a callback, reaction or `reg-sub` body — which a grep cannot see; nor can a grep follow a let-bound fn to the handler slot it fills, a helper to the callbacks that reach it, or a fn handed on through a prop, a keyword arg or a destructuring default. So the grep finds candidates, not the set. Parse the sources (rewrite-clj, or any reader that keeps positions), and for each ambient `rf/dispatch` / `rf/subscribe` classify it by its innermost enclosing fn's position, resolve a let-bound fn by its uses and a helper by its callers, and treat a prop or keyword arg that receives a fn as a callback position. Then reconcile against a comment-stripped lexical count of the same ops, so every lexical hit ends up a site or a named non-site.

### Executing the conversion: views from defn to reg-view

Where the fix for a site is converting its view to `reg-view` — so its callbacks use the injected, render-captured ops — or where the author opts into O-2, this is the **execution mechanics**: the per-component rewrite, form by form. The three form-by-form recipes below are the reference for that pass.

**Before you sweep — import re-frame2's clj-kondo config, or you cannot assert the conversion is "lint clean."** clj-kondo cannot read `reg-view` unaided, and the export `day8/re-frame2` ships carries a hook that rewrites it to its `defn` shape; importing it is a one-time step after M-0, [`setup.md` §Import the clj-kondo config](setup.md#import-the-clj-kondo-config).

Without that config the converted views lint dirty with two **false positives** — name them up front so an agent does not chase either as a real break:

- **`Unresolved symbol` on the view's own name** — `(reg-view account-summary [label] …)` reads `account-summary` as undefined, because unaided clj-kondo does not know `reg-view` introduces a `defn`-like binding. The hook clears it.
- **`subscribe` / `dispatch` `:refer` "referred but never used"** — a converted ns needs no `:refer [subscribe dispatch]` (the bare `dispatch` / `subscribe` are the macro-injected frame-bound locals, per the require note below); a leftover refer carried over from the v1 source is genuinely redundant, so clj-kondo flags it — drop the refer, the view is not broken.

**The require — usually nothing to add.** A subscribing view ns already requires `re-frame.core` (it calls `subscribe` / `dispatch`), and `reg-view` is reached the same way, as `rf/reg-view`. `re-frame.core` self-requires its own macros (`:require-macros [re-frame.core … :refer [reg-view …]]`), so the single M-1 import — `(:require [re-frame.core :as rf])` — already carries the `reg-view` macro with **no** separate `:refer-macros [reg-view]`. The explicit-refer idiom (`[re-frame.core :as rf :refer-macros [reg-view]]`) is only needed by a ns that subscribes yet somehow omits the `re-frame.core` require — rare, since subscribing already requires it.

**Form-1 (canonical render fn) — the 80% case.** A render fn becomes a `reg-view` of the same symbol and arg-vector; the body is unchanged except that any hand-rolled frame-capture in it is dropped and each qualified `rf/dispatch` / `rf/subscribe` becomes the bare local — `dispatch` / `subscribe` arrive as auto-injected, frame-aware lexical locals, and a qualified `rf/dispatch` left in an `:on-*` callback still raises `:rf.error/no-frame-context` when it fires, even inside a `reg-view` (`reg-view` auto-defs the symbol and auto-derives the id from `(keyword *ns* sym)`).

```clojure
;; before — the click's fresh rf/dispatch runs after the render:
;; throws :rf.error/no-frame-context when clicked
(defn account-summary [label]
  [:div label ": " @(rf/subscribe [:account/balance])
   [:button {:on-click #(rf/dispatch [:account/refresh])} "refresh"]])

;; after — registered; the injected dispatch was captured at render
(rf/reg-view account-summary [label]
  [:div label ": " @(subscribe [:account/balance])
   [:button {:on-click #(dispatch [:account/refresh])} "refresh"]])
```

**Form-2 (closure — setup work in an outer fn).** `reg-view` supports the inner render fn: a body that *returns a fn* keeps that shape. The injected `dispatch` / `subscribe` locals are visible to **both** the outer (once-per-mount) body and the inner render fn through ordinary Clojure lexical closure, so the closure converts intact.

```clojure
;; before
(defn counter-with-init [label]
  (rf/dispatch [:counter/init label])
  (fn [label]
    [:button {:on-click #(rf/dispatch [:counter/inc])}
     (str label ": " @(rf/subscribe [:counter/value]))]))

;; after — same closure, injected ops
(rf/reg-view counter-with-init [label]
  (dispatch [:counter/init label])
  (fn [label]
    [:button {:on-click #(dispatch [:counter/inc])}
     (str label ": " @(subscribe [:counter/value]))]))
```

Form-1 + an explicit setup event is the preferred shape over Form-2, but a migration preserves behaviour — convert the closure as-is and leave the Form-2 → Form-1 modernisation to the author.

**Form-3 (`reagent.core/create-class` — lifecycle).** The `reg-view` macro **rejects** `(reg-view sym (create-class …))` at macroexpand (its second argument must be an args vector; the error points the author at `reg-view*`), so you cannot wrap the class in `reg-view` and have it pick up `:contextType` the way a function component does. The class's render reads the tree's frame like any component's; what needs a frame carried in is its lifecycle hooks, which run after the render. Two routes, both spec-supported:

1. **Extract the subscribing content into a `reg-view` child (preferred).** Keep the class for its lifecycle, but move the part that derefs subs / dispatches into a small `reg-view`-registered child the class renders. The child carries its own `:contextType` and reads the frame; the class manages its DOM/lifecycle and subscribes nothing.

   **Audit the class's update hooks before you extract — route 1 moves the update out of the class.** React calls `componentDidUpdate` "immediately after your component has been re-rendered with updated props or state" ([React — `componentDidUpdate`](https://react.dev/reference/react/Component#componentdidupdate)), and a child that re-renders on its own does not re-render its parent. Once the reactive read lives in the child, a new value commits the child alone: the class's `:component-did-update` no longer runs, and the DOM work keyed to it (scrolling to the newest line, measuring a textarea's height, re-feeding a widget) silently stops, with no error. So read each update hook first. If none depends on what the child would take, route 1 stands. If one does, keep update ownership in the class by putting the read **above** it instead of below: the registered Form-1 outer reads the sub and passes the plain value into the class as a prop (the outer / inner pattern in [`implementation/adapters/reagent/README.md`](https://github.com/day8/re-frame2/blob/main/implementation/adapters/reagent/README.md#outer--inner-pattern)), so a new value re-renders the class with new props and its hook runs.

   ```clojure
   ;; BEFORE (v1) — the class derefs the sub in render, so a new line re-renders
   ;; the class and its did-update scrolls the node the class owns.
   (defn transcript []
     (let [!el (atom nil)]
       (reagent.core/create-class
         {:component-did-update
          (fn [_ _ _ _] (when-let [el @!el] (set! (.-scrollTop el) (.-scrollHeight el))))
          :reagent-render
          (fn []
            [:div.transcript {:ref (fn [el] (reset! !el el))}
             (for [{:keys [id text]} @(rf/subscribe [:chat/lines])] ^{:key id} [:p text])])})))

   ;; AFTER — the read moves ABOVE the class. Extracting the lines into a
   ;; subscribing child (route 1) would leave this did-update with nothing to
   ;; fire it, and the scroll would stop.
   (defn transcript-pane [_lines]     ; subscribes and dispatches nothing: needs no frame
     (let [!el (atom nil)]            ; per-MOUNT node handle, as in v1
       (reagent.core/create-class
         {:component-did-update
          (fn [this old-argv _ _]
            (when (not= old-argv (reagent.core/argv this))   ; act on a real props change only
              (when-let [el @!el] (set! (.-scrollTop el) (.-scrollHeight el)))))
          :reagent-render
          (fn [lines]
            [:div.transcript {:ref (fn [el] (reset! !el el))}  ; React passes nil on unmount
             (for [{:keys [id text]} lines] ^{:key id} [:p text])])})))

   (rf/reg-view transcript []
     [transcript-pane @(subscribe [:chat/lines])])
   ```

   Keep explicit what the v1 class made explicit: the handle stays per-mount (never module scope; see the hoisting warning below), the hook acts only on a real change, as React's own caveat asks of a `componentDidUpdate`, and cleanup stays in the class. Do not replace the hook with a DOM write in a render body, the child's or the class's: React may render without committing, and StrictMode renders twice in development. The other way to keep the work is a child with its own `:component-did-update` that calls back into the class, guarded the same way; that child is a Form-3 itself and takes route 2. The long-lived imperative subscription below stays a separate choice, for a class that must follow a value outside render.
2. **Capture the frame's *non-reactive* ops *once, in the outer fn*, before you build the class.** The outer fn that returns the `create-class` runs during the component's first render, under the live resolver scope, and once per mount — the one site in a Form-3 that both resolves a frame and closes over every lifecycle hook. That holds alike for a plain `defn` mounted in hiccup position (`[chart series]`) and for the same callable registered through `re-frame.core/reg-view*` (the plain-fn surface — no auto-def, no auto-inject, no compile check; `spec/API.md`'s `reg-view*` row names Reagent Form-3 as one of its uses), so registration is not what makes the capture resolve: keep the `defn`, and register through `reg-view*` where the view wants a registry id. Bind the frame ops the **lifecycle hooks** need from `(rf/capture-frame)` **there**, before constructing `create-class`, and close that locked handle over the render fn and every lifecycle hook. Destructure `{:keys [dispatch frame]}` (add `dispatch-sync` if you fire synchronously) — the **non-reactive** ops plus the captured `:frame` handle — for the hooks; for **ordinary** lifecycle work do **not** destructure `subscribe` (the next paragraph explains why a reactive read belongs in render, not a hook, and why a hook that does a one-shot read still needs the captured `:frame`). The **one** documented exception is the genuinely imperative long-lived subscription below — the rare case that truly needs a live reactive read *outside* render — which **does** destructure `:subscribe`; it is the sole lifecycle use of the captured `subscribe` op, and it balances every acquire with a matching release (the worked example follows the two bullets). Do **not** move the capture into a lifecycle callback (`:component-did-mount` / `:component-will-unmount` / `:component-did-update`): those fire after the resolver scope has unwound, so a `(rf/capture-frame)` inside one re-raises `:rf.error/no-frame-context`. The handle taken in the outer callable, by contrast, is a locked value that survives into the lifecycle callbacks' async boundary — which is exactly why the render fn and the callbacks share it rather than each re-capturing.

   **Reactive reads stay in the reactive context — never `@(subscribe …)` in a lifecycle hook.** The captured bundle *does* carry `:subscribe`, but its lane is different from `:dispatch`'s. `dispatch` is a fire-and-forget frame-targeted send — safe to close over any callback. `subscribe` returns a **reactive handle**, and a lifecycle body has **no reactive owner**: dereferencing the captured `:subscribe` inside `:component-did-mount` / `:component-did-update` neither establishes render ownership nor tears itself down. Prefer the registered Form-1 outer: it reads the sub reactively and passes the plain value inward as a prop. If a Form-3 renderer exceptionally retains the captured reaction itself, acquire it with `r/with-let` inside `:reagent-render` and deref it there — and **never release it by hand**, neither from `with-let`'s `finally` nor from the class's `:component-will-unmount`. A subscribe taken during render is owned by the render owner, which releases it when it disposes; a second, manual release disposes the reaction early the moment a sibling holds the same query.

   **When a hook genuinely must read a sub, pass the captured `:frame` — a bare read throws.** A lifecycle hook fires *after* the resolver scope has unwound (the same unwind that makes a fresh `(rf/capture-frame)` inside a hook re-raise), so a **bare** `(rf/subscribe-once query-v)` there finds no ambient frame and raises `:rf.error/no-frame-context` just as loudly. Reach for the `:frame` you captured in the outer callable and target it explicitly. Teardown needs no frame: it hands back the reaction the hook acquired.

   - **One-shot *current* value at mount** (e.g. seeding a JS lib): `(rf/subscribe-once query-v {:frame frame})` reads against the captured frame and retains no handle, so it cannot leak.
   - **A genuinely imperative long-lived subscription** (the rare case that truly needs a live reactive read outside render): acquire it in `:component-did-mount` through the captured `subscribe` op, **give it a reactive owner in the same hook** — a per-mount `(r/track! …)` that derefs it — and pair the two with `(r/dispose! …)` **then** `(rf/unsubscribe reaction)` — the reaction the mount acquired — in `:component-will-unmount`. The owner is not optional garnish — without it the acquired reaction is never activated, and the widget is fed once at mount and deaf thereafter (the mechanism is spelled out under the worked example below, and a bare `add-watch` does **not** substitute for it). React StrictMode replays acquire/release as did-mount → will-unmount → did-mount, so each release has a matching acquisition. A teardown that skips the release leaks the sub-cache slot for the frame's life; one written with the query vector, `(rf/unsubscribe query-v)`, throws `:rf.error/bad-unsubscribe-arg`. The release is identity-guarded, so if a hot reload or `clear-sub-cache!` evicted the slot while the widget was mounted, the late release is a no-op rather than a decrement of whatever another view rebuilt there. This is not ordinary Form-3 code.

   Mind the call-shape split (per [`spec/006-ReactiveSubstrate.md` §`unsubscribe`](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md#unsubscribe-r--nil)): `subscribe-once` takes the frame as a `{:frame frame}` opts map, mirroring `subscribe`; `unsubscribe` takes no frame and no query — `(unsubscribe reaction)` hands back the reaction `subscribe` returned. Keep the frame explicit on the read, and keep the reaction for the release.

**The exceptional imperative-subscription Form-3 — copy-pasteable.** The two bullets above are prose; this is the one complete worked form of route 2's rare imperative branch, and the **only** Form-3 that destructures the captured `:subscribe` for lifecycle use. Reach for it **only** when a live reactive read genuinely must live *outside* render — here a JS widget re-fed imperatively as a sub's value changes, driven from a hook rather than React's render commit; the ordinary reactive value belongs in the registered Form-1 outer and arrives as a prop (route 1 / decision-shape 1), and a Form-3 renderer that can do its own deref belongs in `r/with-let` inside `:reagent-render`. The shape: capture **once** in the outer callable and destructure `:subscribe`; keep the acquired reaction in a per-mount atom for teardown; **acquire** the subscription in `:component-did-mount` through the captured `subscribe` op; **own** it there with a per-mount `(r/track! …)` — the tracker is the mount's reactive owner, and its eager first run both seeds the widget and puts the shared reaction on the push path; and **release** in `:component-will-unmount` by disposing the tracker first, then `(rf/unsubscribe @!reaction)`. React StrictMode replays that pair as did-mount → will-unmount → did-mount, so every acquire has a matching release and the ref-count balances on replay as well as on a real unmount. This mirrors the stock-Reagent summary in `implementation/adapters/reagent/README.md` §Outer / inner pattern.

```clojure
;; The EXCEPTIONAL imperative-subscription Form-3 (route 2's rare branch): a live
;; reactive read held OUTSIDE render, re-feeding an imperative JS widget as the
;; sub's value changes. This is the sole lifecycle use of the captured `subscribe`.
(re-frame.core/reg-view* ::live-gauge
  (fn [gauge-id]
    (let [{:keys [subscribe]} (rf/capture-frame)        ; captured ONCE — the live resolver site
          query-v   [:gauge/reading gauge-id]           ; the query — closed over by the mount hook
          !reaction (r/atom nil)                        ; per-MOUNT reaction — what teardown releases
          !driver   (r/atom nil)                        ; per-MOUNT reactive OWNER (the tracker)
          !widget   (r/atom nil)]                       ; per-MOUNT JS instance
      (reagent.core/create-class
        {:reagent-render (fn [_] [:div.gauge])
         :component-did-mount
         (fn [this]
           (reset! !widget (mk-gauge! this))
           (let [reaction (subscribe query-v)]          ; ACQUIRE via captured subscribe (ref-count +1)
             (reset! !reaction reaction)
             (reset! !driver                            ; OWN it — this mount's reactive owner.
               (r/track!                                ; track! runs the body EAGERLY once: that
                 (fn []                                 ; first run is the seed AND the deref-capture
                   (feed-gauge! @!widget @reaction))))))  ; that puts the reaction on the push path
         :component-will-unmount
         (fn [_]
           (some-> @!driver r/dispose!)                 ; STOP the owner first — no feed after teardown
           (rf/unsubscribe @!reaction)                  ; RELEASE — the acquired reaction; balances the acquire
           (destroy-gauge! @!widget)
           (reset! !driver nil)
           (reset! !reaction nil)
           (reset! !widget nil))}))))
```

The acquire (`(subscribe query-v)`, ref-count +1) and the release (`(rf/unsubscribe @!reaction)`, ref-count −1) are the only two touches of the cache slot; every did-mount runs the acquire and every will-unmount runs the release, so StrictMode's did-mount → will-unmount → did-mount replay leaves the slot balanced.

**The tracker is what makes the widget live — this is the whole point of the recipe.** Under the stock-Reagent adapter a subscription *is* a bare `reagent.ratom/Reaction`, built deliberately **without** `:auto-run`, and a Reaction learns its sources only through `deref-capture`. Deref it from a lifecycle hook — outside any reactive context — and it takes the non-reactive branch: it computes the body raw, leaves `watching` nil, and so never enters `app-db`'s watcher set. Nothing can then tell it the value moved. That is why the obvious-looking `add-watch` on the acquired reaction is a **trap** rather than a shortcut: the watch is registered against a node that can never fire, so the widget is fed once at mount and is deaf for the rest of its life, silently and with no error. `(r/track! …)` is the fix because it *is* a reactive owner: its eager first run supplies the `deref-capture` (and doubles as the seed, so there is no separate plain deref to forget), and every later change re-runs it. **The re-feed arrives on a Reagent flush**, not as an inline callback fired from inside the `app-db` write: the tracker is queued like any other reaction and rides the same batched drain a mounted view does. (The Reagent adapter's commit path performs that flush itself, so under `dispatch-sync` the widget is already up to date when the call returns; do not build on that — write the widget as though the feed lands on the following commit, because that is the contract.)

**One tracker per mount, and no watch keys at all.** Equal `(frame, query-v)` subscriptions share **one** cached reaction — the slot the ref-count above tracks — so two mounts of the same gauge observe the same node. Because each mount creates its own tracker, the two observers are independent by construction: no shared callback registry, nothing to key, nothing for a sibling to clobber, and disposing one leaves the other running. Do the teardown in that order — **`r/dispose!` the tracker first, then `unsubscribe`** — so the owner is gone before the cache slot is released and no feed can run against a destroyed widget. A teardown written with the query vector, `(rf/unsubscribe query-v)`, throws `:rf.error/bad-unsubscribe-arg`; one that skips the release leaks the sub-cache slot for the frame's life. Everything else — the ordinary reactive value, and a reaction the *renderer itself* retains (`r/with-let` inside `:reagent-render`, released by its render owner, per the paragraph above) — stays out of the hooks.

**What you register through `reg-view*` is the *outer closure fn*, not a bare singleton `(create-class …)`.** The singleton form — `(reg-view* ::id (reagent.core/create-class {…}))` — is valid **unchanged only when the class is frame-independent**: it subscribes and dispatches nothing frame-scoped (pure DOM/lifecycle over its props), or it targets a fixed frame explicitly on every op (`{:frame …}`). A bare singleton has **no per-mount outer callable**, so it has **no capture site its lifecycle hooks can reach** — a `(rf/capture-frame)` in a lifecycle hook runs after the render, finds no frame, and re-raises `:rf.error/no-frame-context`, while one in `:reagent-render` resolves the provider but is local to that render. A **frame-dependent** singleton is therefore under-specified as written and must be rewritten to acquire a capture site: give it the per-mount outer factory (route 2) so the class is constructed under the live resolver scope, or extract its frame-touching content into a `reg-view` child (route 1). Concretely:

```clojure
;; BEFORE — a frame-dependent singleton: no outer callable, so did-mount runs
;; after the render with no captured frame; its bare dispatch raises
;; :rf.error/no-frame-context at mount.
(re-frame.core/reg-view* ::ticker
  (reagent.core/create-class
    {:reagent-render      (fn [] [:div.ticker])
     :component-did-mount  (fn [_] (rf/dispatch [:ticker/started]))}))   ; no frame in scope

;; AFTER (route 2) — wrap in a per-mount outer callable; capture the frame there.
(re-frame.core/reg-view* ::ticker
  (fn []
    (let [{:keys [dispatch]} (rf/capture-frame)]                         ; live resolver site
      (reagent.core/create-class
        {:reagent-render      (fn [] [:div.ticker])
         :component-did-mount  (fn [_] (dispatch [:ticker/started]))}))))
```

The dominant real Form-3 (charts, maps, editors: per-instance JS state + lifecycle + props) wraps the class in an **outer fn** that closes over per-mount `r/atom`s + props and *returns* the `create-class`. `reg-view*`'s render-fn argument is **any callable** — `spec/API.md` types it `(reg-view* id render-fn)` with no compile check on the body — and that outer fn is a callable, so where the view wants a registry id you register **it** — `(reg-view* ::id my-outer-fn)`, optionally `(def my-view (rf/view ::id))` for a hiccup handle; otherwise keep the `defn`, whose outer fn runs in the same first render (route 2). The per-mount state stays in the closure exactly as the v1 source had it; capture the frame **once in the outer callable** — the live resolver site — as in route 2 above, never inside a lifecycle hook. A behaviour-preserving migration **keeps the outer-fn closure** — do not rewrite it into a Reagent `get-initial-state`/`reagent-state` shape.

```clojure
;; v1 Form-3 — the dominant shape: an OUTER fn closes over per-mount instance
;; state + props and RETURNS the class.
(defn chart [series]
  (let [!inst (r/atom nil)]                          ; per-MOUNT state — one atom per instance
    (reagent.core/create-class
      {:reagent-render         (fn [series] [:div.chart])
       :component-did-mount    (fn [this] (reset! !inst (mk-chart! this series)))
       :component-will-unmount (fn [_] (destroy! @!inst))})))

;; v2 — register the OUTER fn through reg-view* (any callable). The per-mount
;; !inst and props stay in the closure; capture the frame ONCE here in the outer
;; callable — the live resolver site — before building the class, then close that
;; one locked handle over the render fn AND the lifecycle hooks.
(re-frame.core/reg-view* ::chart
  (fn [series]
    (let [{:keys [dispatch]} (rf/capture-frame)      ; captured ONCE, in the live outer callable
          !inst (r/atom nil)]                        ; still per-MOUNT — unchanged
      (reagent.core/create-class
        {:reagent-render
         (fn [series]
           [:div.chart {:on-click #(dispatch [:chart/clicked])}])  ; closes over the locked handle
         ;; the lifecycle hooks fire AFTER the resolver scope has unwound — they
         ;; reach the frame ONLY through the outer-captured `dispatch`, never a
         ;; fresh (rf/capture-frame) of their own (that would re-raise
         ;; :rf.error/no-frame-context). The locked handle survives the boundary.
         :component-did-mount    (fn [this]
                                   (reset! !inst (mk-chart! this series))
                                   (dispatch [:chart/mounted]))
         :component-will-unmount (fn [_]
                                   (destroy! @!inst)
                                   (dispatch [:chart/unmounted]))}))))
;; optional hiccup handle for call sites — [chart series]:
(def chart (rf/view ::chart))
```

**Capture-once is locked to *one* frame — the mount must not be retargetable, or use route 1.** The route-2 handle is captured when the outer callable runs, which is **once per mount**; it is a locked value, not a live re-resolver. That is correct as long as the instance stays under the same frame for its whole life. It breaks if a *surviving* Form-3 instance can be **retargeted from provider A to provider B** — the same React subtree re-parented under a different `frame-provider`, or a provider whose `:frame` prop changes without unmounting the child. React keeps the instance mounted, so the outer callable does **not** re-run, and the locked handle keeps sending render/lifecycle actions to the stale **A** — never **B**. Two frame-safe choices, no mutable-capture machinery:

- **Force a remount when the frame can change** — give the component a React `key` derived from the frame id (`^{:key (str frame-id)} [chart series]`). A frame change then changes the key, React remounts, the outer callable re-runs, and the capture re-locks to **B**. Use this when the capture-once route is otherwise the right fit and retargeting is rare.
- **Use route 1 (the registered `reg-view` child)** — the child reads its frame from React context on **every** render via its `:contextType`, so it follows A→B with no remount and no re-capture. Prefer this when the provider genuinely changes under a long-lived instance.

Do **not** reach for a re-pointable bundle or a mutable frame handle to "fix" this — capture-once is a locked value by design; frame-invariance (or the context-reading child) is the mechanism.

**Do not hoist the per-mount `r/atom` to module scope to fit a bare-singleton example.** A module-level atom is created once and *shared across every mount*, silently fusing all instances' state — a per-instance-state bug that compiles clean and surfaces only when a second instance mounts. The per-mount atom must stay inside the outer fn so each mount gets its own; that is exactly why the outer fn, not a singleton `(create-class …)` value, is what you mount or register.

**A fourth body shape — `defmulti` / `defmethod`-dispatched views.** A large app commonly renders a polymorphic view as a `defmulti` plus one `defmethod` per variant. This shape maps to none of Form-1/2/3, and (like Form-3) it cannot be `reg-view`-wrapped: a multimethod is not defn-shaped, so there is no `(reg-view sym [args] body)` form to give it. Its methods are invoked in function position, so a method body runs inside its caller's render and its reads resolve the frame that render resolves; a method needs a fix only where a callback it sets up dispatches with a fresh `rf/dispatch`. Then take `(rf/capture-frame)` in the method body, which runs during the render, and call the captured ops from the callback — or extract the method's hiccup into its own `reg-view` child and reduce the `defmethod` to a thin dispatcher that returns `[child-view props]` in *hiccup* position, so the child mounts as a component with its own injected ops. (The `defmulti` declaration is unchanged.)

**Scope — fix the call sites, do not over-convert.** The fix is keyed on *where the read runs*, not on being a view. A component needs one **iff** a fresh `rf/subscribe` / `rf/dispatch` sits in a callback or a reaction body it sets up, or it reads the frame while rendering under no boundary. **Leave as `defn`** every other fn — subscribing renders under a boundary included, since they resolve the tree's frame — and every pure value-taking presentational component. This is option 4 of the decision table applied at conversion time.

**Compile-silent — only the boot-smoke catches a miss.** A missed site is valid ClojureScript: it compiles **0 errors / 0 warnings** and is invisible in the build log. The gap surfaces only at runtime: `:rf.error/no-frame-context` when the callback first fires, or when a boundary-less view first renders. Because it throws on *interaction or render* (not at ns-load), it is precisely the silent class the Phase-4 [boot smoke-test](runtime-smoke-test.md) exists to catch: boot the dev build, walk every page the loop's step-0 list names, drive the controls of each M-11 site attached to it, and watch the console for `:rf.error/no-frame-context`. A reaction or `reg-sub` body raises only on the first change to one of its sources, so let each screen's controls change state, and read a `:rf.error/sub-exception` as the same miss. Treat the sweep as unverified until a clean boot drives the screens' controls — which is why Phase-0a **sizes** it rather than discovering the extent one click at a time.

**After converting — fix any function-position call site of a converted view.** A `reg-view` mounts as its own component only when it appears as the head of a hiccup vector, `[my-view props]`. A v1 codebase that called the old plain render fn in **function position**, `(my-view props)`, still compiles after the conversion but bypasses the mount: no component is minted, so the view has no component identity of its own and its frame is whatever its caller's render resolves. So add one identify/verify step to the sweep: after converting the view symbols, grep each converted symbol used in call position — `(my-view …)` — and rewrite it to hiccup, `[my-view …]`. (The bracket-head lookup form `[(rf/view :my-view) …]` is also hiccup and mounts normally; only the **bare** `(my-view …)` call bypasses the mount.)

> **Not M-11 — an in-handler `@(rf/subscribe …)` deref.** M-11 is the no-frame-context footgun for a *view* (or an escaped callback) that has no surrounding scope to read a sub from. An `@(rf/subscribe …)` deref **inside a `reg-event` handler** is a different shape: the handler already holds app-db as the `:db` coeffect, so recompute the value from `:db`, and a framework sub such as `[:rf/machine <id>]` from the `:rf.db/runtime` coeffect, with `rf/compute-sub` — it needs no `reg-view` / `(rf/capture-frame)` / `with-frame`. Triage it by source, not by context: see [`causal-world-inputs.md` §source triage](causal-world-inputs.md#triage-in-handler-reads-by-source).

### The async listener timer and error-handler class

M-11 above operationalises the no-frame-context footgun for the callbacks a **view** sets up; M-40 ([`auto-cross-cutting.md` §Boot-sequence invariant](auto-cross-cutting.md#boot-sequence-invariant--init-must-run-before-the-first-dispatch-and-the-first-render)) operationalises it for the **synchronous boot kick**. Both are instances of one general rule:

> **Under EP-0002, ANY bare `rf/dispatch` / `rf/subscribe` reachable OUTSIDE a render AND OUTSIDE a frame scope (a `with-frame` body, an event's run, or a captured `(rf/capture-frame)`) raises `:rf.error/no-frame-context`.** Frame identity is carried, not found; there is no ambient `:rf/default` the runtime synthesises in its place.

The remaining instance is the **async / non-render class** — code that registers a callback *outside any view* and bare-dispatches when it **later fires**:

- a module-level `(.addEventListener js/window "popstate" …)` / `"hashchange"` routing listener;
- a `js/setTimeout` / `js/setInterval` / `js/requestAnimationFrame` timer;
- a `js/window` `'error'` / `.-onerror` global error handler;
- a third-party JS-lib lifecycle callback (a map's `moveend`, a chart's `onClick`, a socket's `onmessage`).

Each is registered outside any `frame-provider` and fires **after** boot, so there is no render around the callback body to read a frame from — its bare `dispatch` / `subscribe` raises `:rf.error/no-frame-context` at first fire.

**An event's run is a frame scope; what it schedules is not.** The router binds the event's frame around the whole run — its interceptors, its `reg-cofx` suppliers, the handler, and every `reg-fx` body its effects reach — so a synchronous bare `rf/dispatch` or `(rf/capture-frame)` in any of those bodies resolves that frame and is not a site ([Spec 002 §Dispatches issued from inside a handler body](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body)). A callback such a body schedules — a timer, a promise continuation, an XHR completion — fires after the binding has unwound, so it is a site of this class: take the capture in the body, before scheduling, and reply through the captured `dispatch` — in a `reg-fx`, `(rf/capture-frame (:frame m))` on the handler's first argument ([M-51](auto-call-site-rewrites.md#effect-handler-arity-m-51)).

**CRUCIAL — the remedy differs from the M-40 boot kick.** The boot kick is fixed by a `with-frame` *around the synchronous boot dispatch*. That does **not** carry here: a `with-frame` (or a `frame-provider`) established at **registration time** is a dynamic binding that is **already unwound by the time the async callback fires** — wrapping the `addEventListener` / `setTimeout` call itself in `with-frame` leaves the callback *body* running under no scope, and it still raises `:rf.error/no-frame-context`. Re-establish the frame **inside the callback**, two ways:

1. **Wrap the dispatch in `(rf/with-frame app-frame …)` at FIRE time** — inside the callback body, not around its registration. A sub read there is still a one-shot `(rf/subscribe-once query-v {:frame app-frame})`: an `@(rf/subscribe …)` in the body resolves the frame and takes a share nothing releases.
2. **Close the callback over a `(rf/capture-frame)` taken where a frame IS in scope** — capture the frame api under an existing scope, then call its `:dispatch` from the callback, and read a sub there once, `(rf/subscribe-once query-v {:frame (:frame api)})`, as an M-11 callback does. The captured handle, unlike the ambient dynamic binding, **survives the async boundary** (the property M-11 decision-shape 2 above relies on).

```clojure
;; v1 — a module-level routing listener bare-dispatches. In v1 the global
;; app-db ratom + ambient registry made this fine.
(defn init-router! []
  (.addEventListener js/window "popstate"
    (fn [_] (rf/dispatch [:route/changed (-> js/window .-location .-pathname)]))))

;; v2 WRONG — with-frame around REGISTRATION does not reach the callback. By the
;; time popstate fires the dynamic binding is gone, so the bare dispatch in the
;; listener body raises :rf.error/no-frame-context.
(defn init-router! []
  (rf/with-frame app-frame                              ; unwound before the listener fires
    (.addEventListener js/window "popstate"
      (fn [_] (rf/dispatch [:route/changed (-> js/window .-location .-pathname)])))))

;; v2 RIGHT (1) — re-establish the frame INSIDE the callback, at fire time.
(defn init-router! []
  (.addEventListener js/window "popstate"
    (fn [_]
      (rf/with-frame app-frame
        (rf/dispatch [:route/changed (-> js/window .-location .-pathname)])))))

;; v2 RIGHT (2) — capture the frame api where a frame IS in scope, then close
;; the callback over it; the captured dispatch survives async.
(rf/with-frame app-frame
  (let [{:keys [dispatch]} (rf/capture-frame)]          ; captured under the scope
    (.addEventListener js/window "popstate"
      (fn [_] (dispatch [:route/changed (-> js/window .-location .-pathname)])))))
```

The same two remedies cover `setTimeout` / `setInterval` / `requestAnimationFrame` callbacks, a `js/window` `'error'` handler, and any JS-lib lifecycle callback that dispatches. **Phase-0a grep these** as candidates for the M-11 census above (`addEventListener`, `set(Timeout|Interval)`, `requestAnimationFrame`, `onerror` / `'error'`, JS-lib callbacks that bare-dispatch — see [`inventory-and-plan.md`](inventory-and-plan.md)); and the boot smoke-test must **exercise** each one (fire the listener / let the timer elapse / raise the error), because the throw lands only at first fire, not at boot — a boot-without-firing scan misses it ([`runtime-smoke-test.md`](runtime-smoke-test.md)).
