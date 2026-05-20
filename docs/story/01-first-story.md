# 1. Your first story

> **What you'll build.** A working `:story.counter` with one variant — `:story.counter/empty` — that initialises a counter to zero, renders it in the Story canvas, and asserts that `app-db` ends up where you expected. Two macro calls and a route. Roughly twelve lines of source.
>
> **You should have working before you start.** A re-frame2 app that boots (the `re-frame2-template` is fine; any working counter is fine). The Story dep on your `:dev` classpath. `shadow-cljs watch` running. A browser pointed at your app's dev URL. If you got the welcome page's *Where to install* section to work, you're set.

There's a particular pleasure in seeing a new tool render your code for the first time. Storybook nailed the experience back in 2017 — you `init`, you run a command, a sidebar appears with one story already in it, and somewhere a developer who has been fighting webpack for six hours feels a small hit of dopamine. That feeling is the entire reason developer tools have onboarding stories at all. Lose it and you lose adoption.

Story aims for the same gesture: three lines of code, one registered variant, one canvas paint.

Here is the smallest interesting `stories.cljs` you can write. (We're going to use a counter as the worked example throughout the tutorial, because counters are the smallest domain that lets the abstractions show their shape without burying them in business logic. If your reaction is *"another counter example, really?"* — yes. We are not above this.)

```clojure
(ns counter-with-stories.stories
  (:require [re-frame.story :as story]
            [counter-with-stories.events]    ; loads the event handlers
            [counter-with-stories.subs]      ; loads the subscriptions
            [counter-with-stories.views]))   ; loads the view registry

(story/reg-story :story.counter
  {:doc        "The counter — every state, all in one place."
   :component  :counter-with-stories.views/counter-card
   :args       {:label "Count"}
   :tags       #{:dev :docs}
   :substrates #{:reagent}})

(story/reg-variant :story.counter/empty
  {:doc         "Fresh counter at zero."
   :events      [[:counter/initialise 0]]
   :play-script [[:dispatch-sync [:rf.assert/path-equals [:count] 0]]]
   :tags        #{:dev :docs :test}
   :substrates  #{:reagent}})
```

Save. Reload the dev build. Open `#/stories`. You're in.

> 📸 **Screenshot needed**: the Story shell immediately after the variant loads. Annotate (1) `:story.counter` in the sidebar tree, (2) the `/empty` child variant selected (highlighted), (3) the counter rendered in the canvas, (4) the *Canvas / Docs / Tests* mode-tab strip across the top of the main pane.
>
> Save as: `/docs/images/story/01-variant-loaded.png`

**You should now see:** a sidebar entry called `story.counter` with an `empty` variant under it; clicking that variant renders a counter (whatever your `counter-card` view paints) inside the main pane; the controls panel on the right has one row labelled "label" showing the string "Count".

If you do, congratulations — that's your first story. The rest of this chapter is about what those twelve lines actually said.

## What `reg-story` does, and doesn't

`reg-story` registers the **parent story** — a logical grouping of variants that share a component, default args, decorators, and tags. It carries no state on its own; it's a registry entry. Think of it as the README for a folder of related scenarios. The folder is the story (`:story.counter`); the files are the variants (`:story.counter/empty`, `:story.counter/loaded`, and so on).

The id grammar is locked. Stories live under `:story.<dotted-path>`. Variants live under `:story.<path>/<variant-name>`. The sidebar tree is built from the dotted path — there is no separate `:title` field, no separate `:folder` slot. We've internalised a lot of weird shapes from JS framework configurations over the years (`title: 'Components/Buttons/Primary/Default'` and that whole genre); we are deliberately not going to do that. The keyword *is* the structure.

`:component` is a view-id keyword, not a function reference. This will look unusual if you're coming from Storybook, where stories typically wrap a JSX element or a render fn. In Story, components are registered through `reg-view` and referenced by keyword. The substrate adapter (Reagent / UIx / Helix) knows how to invoke a registered view-id; the variant body never holds a function.

This single design call — *keyword references, not function references* — is most of why variant bodies stay EDN. If we put a function in there, the body's no longer round-trippable. Round-trippable bodies are the contract that MCP and the visual-regression services and the agent input pipelines all hang off. So the keyword indirection is load-bearing. It looks like a small ergonomic difference; it's actually the architecture.

## What `reg-variant` does

`reg-variant` registers **one variant** — one scenario, one frame, one canvas paint. Three slots carry the meat:

- **`:events`** — a sequence of regular event vectors. They dispatch in source order through the same router the live app uses. By the time the canvas paints, the variant frame's `app-db` is exactly what those events left behind. This is your *setup phase*: how do you get the world into the state this variant exercises?

- **`:args`** — the variant's prop overrides. Deep-merged into the parent story's args (and into any active mode's args, and any global args, and any live cell-overrides from the controls panel). The resolved map is what the view renders against.

- **`:play-script`** — the assertion sequence. Runs after `:events` settle and the canvas mounts. Each step in the script is a vector with a step-id (`:dispatch-sync`, `:wait`, `:click`, `:type`, `:assert-db`, `:assert-dom`) and its payload. The canonical assertion events from re-frame2 (`:rf.assert/path-equals`, `:rf.assert/sub-equals`, and the rest of the seven) ride the `:dispatch-sync` step.

> 💡 **Note for Storybook refugees.** Storybook's `play` function is JavaScript — you write async functions that call `userEvent.click(...)` and `expect(...).toBeVisible()`. Story's `:play-script` is *data* — a vector of step tuples that a runner interprets. Same role; different substance. The runner lives at `re-frame.story.play.runner`; the step grammar lives in [`API.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md) §`:play-script`. The advantage of the data shape is that the recorder (chapter 3) can *generate* a `:play-script` body from your interactions and you can paste it directly into source. Try generating a TypeScript function from a recording and you'll appreciate the difference.

A variant body is **plain EDN**. No fn slots; no closures; no JSX-shaped DSL. We mentioned this in the welcome; we will mention it again throughout this tutorial because every time you reach for "I'll just put a function here" the right answer is "register the thing the function does, reference it by id." It is the discipline that makes the rest of Story work.

## The four-layer args chain

A variant's effective args are the deep-merge of four sources:

```
global-args   ← set once via (story/configure! {:rf.story/global-args {...}})
     ↓
mode-args     ← active :Mode/* tuple (chapter 4)
     ↓
story-args    ← :args slot on reg-story (parent default)
     ↓
variant-args  ← :args slot on reg-variant (per-scenario override)
     ↓
cell-overrides ← live edits from the controls panel
     ↓
effective args (what the view receives)
```

(The spec calls this five layers because it counts cell-overrides separately. Same idea.)

Deep-merge for nested maps, override-by-replacement for vectors, later layers win. Variants that don't say anything inherit; variants that override win. This is the same model Storybook uses for `parameters` and `args`, and we adopted it on purpose — the precedence story is well-understood.

The **controls panel** in the right-side pane auto-derives editors from the resolved args plus the view's schema. If the parent story carries `:argtypes {:label {:control :text}}`, the panel renders a text input wired to dispatch a cell-override. If you've gone further and registered the view's Malli schema through `reg-view`, the panel reads the schema directly — a `:keyword` schema becomes a select, an `:int` schema with `:min`/`:max` becomes a number-input or slider, an `:enum` becomes a radio group. **No `argTypes` plumbing.** The schema you wrote for runtime validation is the same schema that drives the controls UI.

This is the Story-side of a broader re-frame2 bet that *schemas pay for themselves several times over*. You write them for runtime validation and you get controls. You write them for controls and you get docs. You write them for docs and you get visual-regression diffing. We are not going to stop reaching into your schema for tooling; you might as well let us.

## The seven canonical assertions

The `:rf.assert/*` events register at Story load — you don't write them, they're already there. They're regular re-frame2 events; the difference is that they don't throw on failure. They record into `[:rf.story/assertions]` on the variant frame's `app-db`. The test runner reads the accumulator at play-script completion and reports the aggregate.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals`     | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches`    | `[path schema]` | Malli validates the value at `path` against `schema` |
| `:rf.assert/sub-equals`      | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?`     | `[event-vec]` | Did this event dispatch during play? |
| `:rf.assert/state-is`        | `[machine-id state]` | Is the machine in `state`? |
| `:rf.assert/no-warnings`     | `[]` | Zero `:rf.warn/*` events since play start? |
| `:rf.assert/effect-emitted`  | `[fx-id]` (or `[fx-id pred]`) | Was this fx-id emitted? |

The **record-don't-throw** shape is the design call worth dwelling on. An assertion library that throws on the first failure is fine for unit tests where you want a short feedback loop. It's *terrible* for a visual playground where you want to see the full state of a broken variant. A play sequence with eight assertions where three fail should still run all eight; you should get the full picture; the test runner should ask "did every entry pass?" at the end. Story does this. Storybook 8's interaction tests also do this. The pattern is sound.

```clojure
;; cljs test against the variant — same accumulator, same shape:
(deftest counter-empty
  (cljs.test/async done
    (-> (story/run-variant :story.counter/empty)
        (.then (fn [result]
                 (is (story/assertions-passing? result))
                 (story/destroy-variant! :story.counter/empty)
                 (done))))))
```

`run-variant` returns a promise of `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms :snapshot :lifecycle}` — the same result map the MCP surface returns when an agent asks for a preview. Same shape; same vocabulary across surfaces.

## Decorators — three kinds

Decorators are the only `reg-*` registration where Story authoring legally holds a closure, and even then the closure is at the registration site, not in any variant body. They come in three flavours:

- **`:hiccup` decorators** — wrap the rendered tree. The closure lives at the decorator's registration site.
  ```clojure
  (story/reg-decorator :app/centered-layout
    {:kind :hiccup
     :wrap (fn [body _args]
             [:div {:style {:display         "flex"
                            :justify-content "center"
                            :padding         "1em"}}
              body])})

  (story/reg-variant :story.counter/loaded
    {:decorators [[:app/centered-layout]]
     :events     [[:counter/initialise 7]]
     :substrates #{:reagent}})
  ```

- **`:frame-setup` decorators** — patch the variant's frame at allocation time. Used when several variants share a setup like "assume the user is logged in." The events dispatch before the variant's own `:events`; the patch merges into the variant frame's `app-db`. Pure data, no closures.

- **`:fx-override` decorators** — stub a network call (or *any* fx — analytics, websockets, geolocation, storage, navigation). The marquee shape is `force-fx-stub`, which Story ships built-in:

  ```clojure
  (story/reg-variant :story.counter/save-stubbed
    {:events      [[:counter/initialise 5]]
     :decorators  [[story/force-fx-stub-id :counter/sync-to-server {:ok? true}]]
     :play-script [[:dispatch-sync [:counter/save]]
                   [:dispatch-sync [:rf.assert/path-equals [:saving?] true]]
                   [:dispatch-sync [:rf.assert/effect-emitted :counter/sync-to-server]]]
     :substrates  #{:reagent}})
  ```

The `force-fx-stub-id` Var holds the well-known id for the built-in decorator; we expose it as a Var so you're not memorising keyword paths. Three lines stubs *any* effect handler — `reg-fx`'d HTTP, analytics, websocket, geolocation, storage, navigation. The Storybook ecosystem has a separate addon for each of these (`msw-storybook-addon` for HTTP, separate stuff for analytics, etc.); Story has the one primitive because re-frame2 already names the seam — every side effect runs through a registered `reg-fx` handler the runtime calls. Stubbing it is a one-liner because the seam already exists.

Variant bodies reference decorators by id; the closure lives on the decorator registration. This is the discipline that keeps variant bodies serialisable.

## What you should see now

After the registrations above (the parent `:story.counter` plus the `:story.counter/empty` variant), the playground at `#/stories` should show:

- The `story.counter` parent in the sidebar tree (collapsible), with `empty` underneath.
- Click `empty`; the canvas paints a counter at zero.
- The mode-tab strip across the top of the canvas: *Canvas* (selected by default), *Docs*, *Tests*.
- The right-side controls panel with one row, "label", showing "Count".
- A green status dot in the sidebar next to `empty` if the variant has `:test` tagged and its play-script passed. (The chrome runs `:test`-tagged variants in the background on registration so sidebar dots reflect current status; you don't need to switch to *Tests* mode to populate them.)

If the variant didn't show up, the most likely causes are below.

## When it doesn't work

A few diagnostic notes from the trenches. Tutorials that pretend everything works the first time are lying to you, so we're going to be honest about the failure modes.

- **The sidebar's empty.** You forgot to `:require` the stories namespace from your app's entry. Story's registrations live on a side-table built by `reg-*` calls; if the namespace never loads, nothing registers. The fix: add `[my-app.stories]` to your entry namespace's `:require` block. Triple-check there's no underscore-vs-hyphen typo; CLJS namespace mismatches give *very* unhelpful errors.

- **The variant shows up but the canvas is blank.** Your view-id keyword doesn't resolve to a registered view. Story has no way to render a view that wasn't `reg-view`'d. Check the spelling of the keyword on `:component`; check that the view's namespace is loaded (transitively); check that the `reg-view` macro actually ran (a typo in `defn` vs `reg-view` is silent until render).

- **You see *Canvas* / *Docs* / *Tests* tabs but the *Tests* tab says "no assertions recorded".** You probably wrote `:play` instead of `:play-script`, or you wrote the play-script body as `[:rf.assert/path-equals [:count] 0]` (a bare event vector) instead of `[:dispatch-sync [:rf.assert/path-equals [:count] 0]]` (a `:dispatch-sync` step containing the event vector). The play-script grammar is *step-tuples*; the canonical assertions ride the `:dispatch-sync` step. (This is a recent rename; some external blog posts still show the old shape.)

- **Variant renders but the right-side controls panel doesn't show your arg.** You probably haven't registered a schema on the view, and your parent story doesn't carry `:argtypes`. The controls panel needs *some* hint about how to render an editor; the schema is the preferred source, `:argtypes` is the override channel. We'll get into this more in chapter 4.

- **The whole shell short-circuits to a blank page on prod build.** That's the elision behaving correctly. Story's production-build short-circuit is by design; `mount-shell!` no-ops when `re-frame.story.config/enabled?` is false. If you actually *want* the playground on a deployed build (against a staging environment for design review, for instance), flip the `:closure-defines` for that build only.

## Where we go next

Chapter 2 walks the chrome — the *Canvas / Docs / Tests* tabs above the canvas and the four orthogonal toolbars below them (viewport, background, a11y, locale). The pattern is: every chrome surface is a thin layer over the variant frame's data, so once you understand the variant, the chrome is just projections.

Next: [the mode tabs](02-mode-tabs.md).
