# 18 - Frames

You want two independent copies of your app on one screen — a Story canvas showing the same widget in three states, a split-pane editor with live and preview sides, a server render handling a hundred concurrent requests — and you want them to stop leaking state into each other. This chapter is **frames**, the isolated context that makes that not a nightmare. The good news up front: if your app is one app on one page, you already have exactly one frame, you've been using it the whole time, and you never had to know it existed.

## The request that breaks everything you've built

Let me set the scene with the kind of thing a product manager says on a Tuesday, because the motivating problem is the whole reason frames exist and it's worth feeling the pain before reaching for the cure.

You've got an analytics widget. It's a clean re-frame2 feature: a date-range picker, some events that load data for the selected range, a subscription that derives the chart series, a view that paints it. All the things this guide has been teaching you to build. It works. You're proud of it.

Then:

> *"On the analytics page, can we show today's numbers and last week's numbers side-by-side? Same widget — same buttons, same filters — just running against different data."*

Easy, you think. The view is just a function. Put two of them on the page:

```clojure
[:div.split
 [analytics-panel]      ;; today
 [analytics-panel]]     ;; last week — but how?
```

And then it isn't easy at all, and you feel the floor tilt. The handlers exist once. The subscription exists once. The view exists once. *What there isn't two of is state.* Both panels read the same `app-db`, both write the same `app-db`, so the moment you move the date picker in the left panel the data changes in **both**. You wanted two independent instances. You got one widget rendered twice, sharing a single brain.

Here's the part I want you to notice, because it's the trap. The React-shaped reflex at this exact moment is to reach for local state. Give each panel its own atom. Prop-drill it down into every leaf. Rewire every handler to take the atom as an argument instead of reading `app-db`. And it *works* — for about a day — and then look at what you've done: your handlers are no longer pure functions of `app-db`, your subs are no longer expressible as `reg-sub`, and the entire architecture that made the rest of your app tractable just evaporated the instant you needed two of one thing. You traded the framework for a closure full of atoms. That's not a refactor; that's a defection.

re-frame2's answer is to give each panel its own **frame** — and to do it without touching a single line of the handlers, the sub, or the view.

## What a frame actually is

Here's the one-sentence version, and it's the load-bearing idea the rest of the chapter is consequences of:

**A frame is one running instance of your re-frame2 app.**

That's it. Single-frame apps have one instance. Multi-frame apps have several. The framework treats them identically — nothing in the dispatch pipeline, the interceptor chain, or the subscription graph cares whether the frame it's running against is the default one or the fourteenth one you hand-rolled for a Story.

Mechanically, a frame is an **isolated runtime boundary** identified by a keyword (`:left`, `:test/auth-flow`, `:ssr.req/abc123`). It owns exactly three pieces of runtime state:

- An **`app-db`** — the one immutable map this frame's events read and write.
- A **router queue** — the events waiting to be drained for *this* frame.
- A **subscription cache** — the memoised values of every active `reg-sub` against *this* frame's `app-db`.

And — this is the part that surprises people — here is what a frame emphatically does **not** own:

- **The handler registry.** Every `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-view`, `reg-fx`, `reg-cofx` you've ever written populates a *single global registry* shared across every frame in the program. Two frames running `:counter/inc` are running the **same handler** against **different `app-db`s**. Say that one back to yourself, because it's the whole trick: **frames isolate state, not behaviour.** You register the behaviour once; you get as many isolated copies of the state as you have frames.

That distinction is what saves the analytics widget. The view, the sub, the handlers don't get duplicated, don't get parameterised, don't learn that there are now two of them. They stay exactly as written. What changes is only *which `app-db` they resolve against* — and the frame is the thing that answers that question.

## When you actually need more than one

Before you go frame-happy, the honest framing: the mental model "one app, one `app-db`, one queue, one sub-cache" is correct for the overwhelming majority of apps. You will write whole real applications and never type the word `frame`. This chapter is for the minority case, and for the one scrap of vocabulary even the majority case has to be able to recognise (`:rf/default`, coming up).

The cases that genuinely want more than one frame, roughly in order of how often you'll meet them:

- **Multiple live instances of the same widget.** The analytics panels. Embedded white-label widgets dropped onto a host page. A split-screen or multi-window UI where each pane is a full running copy.
- **Stories.** [Story](../story/index.md) gives every variant its own frame — *"show this view loaded, loading, and errored, side by side"* is three frames, one set of registered handlers, three different `app-db` values. The Story runner owns the frame allocation; you mostly don't see it.
- **Per-test fixtures.** [Chapter 13 — Testing](13-testing.md) spins up a fresh frame for each test and tears it down after, so no test can leak state into the next. That isolation is *why* re-frame2 tests don't need a browser or a reset-the-world dance between cases.
- **Per-request server-side render.** [Chapter 20 — The server side](20-server-side.md) creates a brand-new frame per HTTP request, runs the SSR cascade against it, serialises the resulting `app-db`, and destroys the frame. A hundred concurrent requests are a hundred isolated `app-db`s; none of them can see another.

And the cases that *look* like multi-frame and absolutely are not — getting this wrong is the most common frames mistake:

- **Different routes in one app.** Routing changes *which slice of `app-db` matters right now*; it does not change which frame is in play. One frame, many routes. ([Chapter 19](19-routing.md) is the whole story.)
- **Different components on one page.** You do not isolate by component. The entire point of `app-db` is that components compose by sharing slices of it through subs. Two components on a page are not two frames; they're two views over one frame.
- **Different *apps* on one page.** That's micro-frontends, and it's explicitly out of scope. Use iframes — the host-page boundary is already the isolation you want, and trying to do it with frames is using a scalpel where you wanted a wall.

If you want a single discriminator to settle any borderline case, it's this question: **would these two instances ever sensibly share a piece of state?** If yes — they're slices of the *same* frame. If no, if they're genuinely two separate runs of the same app — they're separate frames. The analytics panels never want to share their date range; that "no" is the signal that they're two frames.

## `:rf/default` — the frame you've had all along

Every example before this chapter has been running inside a frame. The counter in chapter 03, the cascade in chapter 04, the subscription graph in chapter 05 — all of it. You never saw the frame because the framework pre-registers one for you at load time, named `:rf/default`, and every `dispatch` / `subscribe` that doesn't name a frame quietly resolves against it.

```clojure
;; What you've been writing all this time:
(rf/dispatch [:counter/inc])

;; What the framework actually routes:
(rf/dispatch [:counter/inc] {:frame :rf/default})
```

`:rf/default` is not a special case bolted onto the side. It's a completely ordinary frame sitting in the registry, listable by your tooling, addressable by keyword like any other. The *only* special thing about it is that the framework registers it on your behalf, so single-frame apps get to pretend frames don't exist.

And that's the real payoff of the design: the mental shift from "this is *the* `app-db`" to "this is *one frame's* `app-db`" costs you **nothing** in single-frame code — `:rf/default` is invisible scaffolding — and it's *exactly* the shift you'll already have made the day you grow a second frame. You don't refactor your way into multi-frame. You just stop letting the default be implicit.

## Creating a frame

Two shapes, and you pick between them on one question: will you dispatch into this frame by name from somewhere else?

```clojure
;; Named, registered up front. You'll address this frame
;; from elsewhere using its keyword.
(rf/reg-frame :left
  {:doc       "Left-hand counter."
   :on-create [:counter/initialise]})

;; Anonymous, gensym'd id. You hold the returned keyword
;; and tear it down explicitly.
(let [f (rf/make-frame {:on-create [:counter/initialise]})]
  ;; ... use f for as long as the surrounding code needs it ...
  (rf/destroy-frame! f))
```

`reg-frame` is for frames whose identity is fixed at app-load — the two analytics panels, named Story variants, the SSR frame you'll dispatch into from request-handler code. `make-frame` is for frames whose whole lifecycle is owned by the surrounding code — tests, per-mount devcards, a modal stack — where there's no name worth choosing and the gensym is exactly right. Both end in the same place: a frame in the registry, addressable by keyword, its `app-db` seeded by `:on-create`.

### `:on-create` — how a frame's state gets seeded

A freshly-created frame's `app-db` is **always `{}`**. There is no `:db` config slot, no "initial state" parameter, no escape hatch. This is deliberate and it's consistent with everything else in the framework: state arrives the one and only way state ever arrives in re-frame2 — *via an event*. `:on-create` is the single event vector the framework `dispatch-sync`s into the new frame the instant it's created.

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_ _] {:count 0 :history [0]}))

(rf/reg-frame :left {:on-create [:counter/initialise]})
;; By the time reg-frame returns, :left's app-db is {:count 0 :history [0]}.
```

Need to fire several init events? The single `:on-create` handler does it through its effect map — `:fx [[:dispatch [:counter/restore]] [:dispatch [:prefs/load]]]` — and run-to-completion guarantees those cascades fully settle before `reg-frame` returns. There's a symmetric `:on-destroy` slot for teardown effects, and `reg-frame` accepts a broader metadata grammar (`:interceptors`, `:on-error`, `:platform`, and the presets below) that downstream chapters introduce as each surface needs it.

## Targeting a frame — and why you mostly won't have to

From *outside* a view — a REPL session, a test, framework-level code — you name the frame explicitly:

```clojure
(rf/dispatch  [:counter/inc] {:frame :left})   ;; dispatch — opts map
(rf/subscribe :right [:count])                 ;; subscribe — frame-id positional
```

(The shapes differ for historical reasons — `dispatch` carries other opts in that map; `subscribe`'s out-of-view callers are tooling-shaped. Don't read meaning into the asymmetry.)

But here's the thing you'll actually do day-to-day: *inside* a registered view, you **never write `{:frame ...}`**. Look back at the analytics panels — the whole point was that the view function doesn't know it's been instantiated twice. So how does its `dispatch` know which frame to hit?

The answer is `frame-provider`, and it's the mechanism the entire chapter has been building toward. It wraps a subtree, carries a frame keyword down through React context, and the `dispatch` / `subscribe` that `reg-view` auto-injects into the view's body resolve against it:

```clojure
[:div.analytics
 [rf/frame-provider {:frame :left}
  [analytics-panel]]                   ;; this subtree reads/writes :left
 [rf/frame-provider {:frame :right}
  [analytics-panel]]]                  ;; this subtree reads/writes :right
```

Two instances of the *same* registered view; two frames; each subtree's `dispatch` / `subscribe` silently routed to its own `app-db`. The view function is frame-blind — it just calls `(dispatch [...])` and `(subscribe [...])` — and the framework routes correctly based on which provider it finds itself under. That frame-blindness is the load-bearing reason to register views with `reg-view` rather than writing bare `defn` functions: a registered view's body can be instantiated under any frame and Just Works.

(`frame-provider` is React-context-driven, so it's Reagent-specific in *mechanism*. The *pattern* — every dispatch and subscribe targets a specific frame, by whatever means the host language offers — is what survives across substrates and ports.)

## The split-counter, end to end

Let's put it all together with the smallest example that exercises every piece. Two counters, side by side, fully isolated, zero shared state:

```clojure
;; Registered once — same handlers, sub, and view for BOTH counters.
(rf/reg-event-db :counter/initialise (fn [_ _] {:count 0}))
(rf/reg-event-db :counter/inc        (fn [db _] (update db :count inc)))
(rf/reg-sub      :count              (fn [db _] (:count db)))

(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:count])]])

;; Two frames, one per side.
(rf/reg-frame :left  {:on-create [:counter/initialise]})
(rf/reg-frame :right {:on-create [:counter/initialise]})

;; Mount both, each scoped to its frame.
(defn ^:export run []
  (rdc/render root
    [:div.split
     [rf/frame-provider {:frame :left}  [counter]]
     [rf/frame-provider {:frame :right} [counter]]]))
```

That's the whole thing, and I want you to notice what isn't in it. There's no parameter threaded through the view. There's no atom. There's no "which counter am I" argument on the handler. The handlers, the sub, and the view *do not know there are two of them* — they were written for one app and they're running in two, unchanged. Click `+` on the left and only the left number moves; the right frame's `app-db` never hears about it. The architecture did the isolation. You just told each subtree which `app-db` it lives in.

Go back and reread the analytics problem from the top of the chapter. The "but how?" is now a `frame-provider` and a `reg-frame`, and not one line of the feature changed.

## Frames are not "components with state"

There's a failure mode that shows up the moment frames click for someone, and I want to head it off, because it's seductive and it's wrong. The temptation is: *frames give per-instance isolation — so I'll give every reusable widget its own frame and scope all its local state inside it.* A frame per tooltip. A frame per dropdown. A frame as a fancy `useState`.

**Don't.** This is the single biggest way to misuse the feature.

Frames are **heavyweight runtime objects**. Each one carries a full `app-db`, its own event queue, its own subscription cache, its own router context. They exist for the case where the *whole app* genuinely runs in isolation — Story variants, SSR requests, multi-window apps, the side-by-side analytics panels. They are not, and were never meant to be, a `useState` replacement.

For honest component-level state — a tooltip's hover bit, a dropdown's open flag, a form's draft text — you do what re-frame2 has always done: put it in `app-db` (the *current* frame's `app-db`, whichever frame you're in), write an event to update it, write a sub to read it. Sharing *parts* of state with specific UI surfaces is the job of sub and event composition, not frame allocation. The frame is the wrong granularity for "this dropdown is open" by several orders of magnitude.

The same discriminator from earlier settles it cleanly: *if two instances might ever want to share state, they aren't separate frames — they're separate slices of one frame's `app-db`.* A tooltip and the form it annotates absolutely share state. They're one frame.

## Presets — making intent visible at the call site

Most frames you'll ever register fall into one of four shapes: a normal client app, a per-test fixture, a Story variant, a per-request SSR frame. Hand-writing the metadata bundle for each one every time would be both repetitive *and* — worse — would bury the intent of the call site under boilerplate. So re-frame2 ships a closed set of four canonical presets that expand at registration into a fixed bundle of metadata:

```clojure
(rf/reg-frame :test/auth-flow      {:preset :test})
(rf/reg-frame :story.counter/empty {:preset :story})
(rf/reg-frame :ssr.req/abc123      {:preset :ssr-server})
```

The win is legibility: a reader skimming the source can tell at a glance that *this* is a test frame and *that* one is a Story variant, without decoding a metadata map. The expansion is locked — four presets, no more — which keeps the set canonical for AI scaffolding and for cross-codebase recognition. [Chapter 20](20-server-side.md), [chapter 13](13-testing.md), and the [Story tutorial](../story/index.md) each introduce the preset they need in the context that needs it.

The chapters that exercise multi-frame in anger are all downstream of this one — [testing](13-testing.md) uses the per-test fixture, [Story](../story/index.md) the frame-per-variant, [the server side](20-server-side.md) the per-request frame. Each of those walks its own surface. This chapter is the substrate they all stand on: a frame is one isolated instance of your app, the framework gives you the first one free, and everything you've already learned runs inside it unchanged.
