# Writing interactive tutorials

> **Who this is for.** Authors adding *interactive* chapters to the guide — pages where the reader edits live re-frame2 code in the browser and watches it run. This is a contributor note, not a reader chapter. If you're here to *learn* re-frame2, you want [03 — Your first app](03-first-app.md) or its live companion, [Interactive: the counter](interactive-counter.md).
>
> **What it covers.** The three playground cell kinds, when to reach for each, the conventions for an editable-and-evaluable teaching cell, and the gotchas that bite the first time. There is one worked, end-to-end template tutorial — [Interactive: the counter](interactive-counter.md) — and this note explains the pattern it follows so you can write the next one.

The guide's prose teaches by reading. Static code blocks show the shape; the surrounding sentences explain *why it's shaped that way*. That's enough for most chapters and it's the right default — a reader skimming on a phone shouldn't need a JavaScript runtime to follow the argument.

But some ideas land harder when the reader can *change them and see what happens*. "Dispatch increments the counter" is a sentence. A live counter the reader clicks — then edits the handler to add two instead of one, re-evaluates, and clicks again — is an experience. The playground ([built under `tools/playground`](https://github.com/day8/re-frame2/tree/main/tools/playground)) makes that possible inside an ordinary mkdocs page: fenced code blocks become editable CodeMirror editors that evaluate in the browser.

This page is the **foundation** for that track. The [counter tutorial](interactive-counter.md) is the **template** the first interactive chapter follows. More interactive tutorials will follow the same shape; extend or redirect from here.

## The three cell kinds

A live cell is a fenced code block whose *info string* selects one of three behaviours. The fence text is the only difference between a static block and a live one:

| Fence | What the reader gets | When to use it |
|---|---|---|
| ` ```cljs ` | **Plain eval.** Evaluates the forms and prints the last form's value below the editor. No DOM, no re-frame. | Teaching ClojureScript itself — data literals, evaluation rules, builtins. The whole of [the CLJS reading guide](../cljs/index.md) is this kind. |
| ` ```cljs-render ` | **Stock live component.** Mounts the last form as a live Reagent component, backed by **stock** Reagent + re-frame (the original libraries). | Demonstrating something that's identical between stock re-frame and re-frame2, or where the stock surface is genuinely what you mean. Rare in this guide — the guide teaches re-frame2's own API. |
| ` ```cljs-rf2 ` | **Live re-frame2 component.** Same as above, but evaluated against re-frame2's **own public API** (`re-frame.core` v2) and rendered through reagent2. | Every interactive *re-frame2* tutorial. This is the one you almost always want. |

For a guide chapter, the answer is almost always ` ```cljs-rf2 `. You're teaching re-frame2; the live cells should run re-frame2. Reach for ` ```cljs ` only when the lesson is pure ClojureScript with no framework in sight, and for ` ```cljs-render ` essentially never — it exists for the rare stock-comparison case.

## The editable-and-evaluable convention

A teaching cell is not a screenshot. It's an invitation to experiment. Three conventions keep that invitation honest:

**1. The cell must run as written.** Whatever you put between the fences is what the reader sees, edits, and evaluates. There's no hidden setup. If the counter needs its `app-db` seeded before the view renders, the seeding form is *in the cell* where the reader can see it. A cell that only works because of state left behind by an earlier cell is a trap — write each interactive cell to stand alone.

**2. Tell the reader what to change.** After a cell, name the edit you want them to try and what they should expect. "Change `inc` to `(partial + 10)`, press the eval shortcut, and click `+` — the counter now jumps by ten." A live cell with no suggested experiment is just a slow screenshot.

**3. Name the eval shortcut once per page.** The reader evaluates a cell with `Ctrl-Enter` (or `Cmd-Enter` on macOS). Say so the first time a page asks them to evaluate, the same way [the CLJS reading guide](../cljs/index.md) does. After that you can just say "re-evaluate."

The shape of a good interactive section is: a sentence of *why*, the live cell, then a sentence of *what to try*. The reader reads the claim, sees it running, then makes it their own by changing it.

## What a `cljs-rf2` cell can call

A `cljs-rf2` cell evaluates against re-frame2's real public API. The names that resolve inside a cell:

- **Registrations** — `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-fx`, `reg-cofx`, and the rest of the `reg-*` family. (In compiled code these are plain functions; the macro forms only add source-location capture, which a browser cell doesn't need.)
- **Runtime verbs** — `dispatch`, `dispatch-sync`, `subscribe`, `inject-cofx`. (On the real public surface these are macros; the cell environment binds the same names to their underlying functions, so you write them exactly as in real code.)
- **The view substrate** — `reagent2.core` (require it `:as r`).

A cell's standard preamble is therefore:

```
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])
```

and from there `rf/reg-event-db`, `rf/reg-sub`, `rf/dispatch`, `rf/subscribe` all work as you'd expect.

### One difference from the static listings: `reg-view`

The static chapters register views with `reg-view`, which auto-injects `dispatch` and `subscribe` as lexical bindings inside the view body (see [07 — Views](07-views.md)). `reg-view` is a macro, and the live-cell environment is functions-only — so **inside a `cljs-rf2` cell you write plain `defn` views and call `rf/dispatch` / `rf/subscribe` explicitly**:

```
;; In a static chapter (macro available):
(rf/reg-view counter []
  [:button {:on-click #(dispatch [:counter/inc])} "+"])

;; In a live cell (plain defn, explicit rf/ verbs):
(defn counter []
  [:button {:on-click #(rf/dispatch [:counter/inc])} "+"])
```

This is the same component — `reg-view` is sugar over exactly this shape. When you port a static chapter to an interactive one, expand the `reg-view` forms into `defn` views and the injected `dispatch`/`subscribe` into qualified `rf/dispatch`/`rf/subscribe`. Mention the equivalence in prose so a reader who's seen the static chapter isn't tripped by the difference; the [counter tutorial](interactive-counter.md) does exactly this.

## Gotchas

A few things bite the first time you author a `cljs-rf2` cell.

**Top-level forms only — never wrap the cell in `(do …)`.** The cell's source is evaluated form-by-form at the top level. A leading `(require …)` only makes its aliases (`rf`, `r`) visible to its *sibling* top-level forms. Wrap the whole body in one `(do …)` and the `require`'s aliases stop resolving for everything inside — `rf/reg-event-db` becomes an "unresolved symbol" error. Keep every form at the top level.

**A `cljs-rf2` cell always renders — its last form must be hiccup.** There is no plain-eval path for a `cljs-rf2` cell; it *mounts* the value of its last form as a component. End every cell with a component vector — `[counter]` — or a literal hiccup vector (`[:div …]`). If the last form is a registration, a `dispatch-sync`, or a bare value like `{:counter/value 6}`, there's nothing renderable to mount and the cell renders blank (or errors). To *show* a computed value — a handler's return, a `compute-sub` result — wrap it in a tiny view that displays it: `(defn demo [] [:div "result: " (str the-value)])` then `[demo]`. (For a pure value-printing cell with no framework, that's what the plain ` ```cljs ` fence is for — but those run stock ClojureScript, not re-frame2.)

**Seed `app-db` before the view reads it.** Inside the cell, `(rf/dispatch-sync [:counter/initialise])` runs synchronously, so by the time the final `[counter]` form mounts, `app-db` already holds the seeded value. Use `dispatch-sync` for this seed-before-render step (same reasoning as [the static counter's mount](03-first-app.md#initialisation)); plain `dispatch` would queue the event and the first render would race an empty `app-db`.

**The re-frame2 engine loads on demand.** The page only pulls the re-frame2 evaluation bundle when it actually contains a `cljs-rf2` cell. Pages with no live re-frame2 cells never pay that cost — but it also means the bundle downloads on first visit to an interactive page, so the first cell may take a moment to come alive. That's expected; subsequent cells on the same page are instant.

**One registry per page, shared across cells.** Every `cljs-rf2` cell on a page evaluates into the *same* re-frame2 runtime. Two cells that both `reg-event-db :counter/inc` will clobber each other's handler, and `app-db` persists across cells on the page. For a linear tutorial that's usually fine — later cells build on earlier ones. But if you want two independent demos on one page, give them distinct, namespaced ids (`:demo-a/inc`, `:demo-b/inc`) so they don't collide. This is the same id-namespacing discipline the framework asks for everywhere; it just matters sooner in a multi-cell page.

## Writing the next interactive tutorial

The [counter tutorial](interactive-counter.md) is the template. To write another:

1. Pick an idea that *rewards being changed* — where the reader editing the code and re-running it teaches more than reading would. If a static block would do the job, write a static chapter.
2. Build each interactive section as *why → live cell → what to try*.
3. Make every `cljs-rf2` cell self-contained (require, registrations, seed, view, final renderable), with namespaced ids.
4. Add the page to `mkdocs.yml` under the Guide nav, next to its static sibling.
5. Build with `mkdocs build --strict` and load the page in a browser to confirm the cells mount and respond to clicks — a cell that compiles but doesn't render is worse than a static block.

Keep them tight. An interactive tutorial earns its keep with one or two well-chosen live cells, not a wall of them.
