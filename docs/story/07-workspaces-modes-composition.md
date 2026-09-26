# 7. Workspaces, modes, composition

As a variant library grows, you want to lay variants out, render them under
different environments, wrap them, tag them, and share setup between them
without copying it. This chapter covers Docs mode, workspaces, toolbar modes,
viewports and backgrounds, decorators, tags, the shell's working tools, and
composition.

## Three things called modes

Two Story concepts and one browser-style control are all called modes.

**Mode tabs** are the tabs above the canvas: Canvas, Docs and Tests. They
change how the selected variant is presented.

**Toolbar modes** are registered sets of args:

```clojure
(rf.story/reg-mode :Mode.login/dark
  {:doc  "Dark theme."
   :axis :theme
   :args {:theme :dark}})
```

They change the environment the variant renders in.

The **viewport** and **background** pickers are chrome selections. They help
with review, but they are not registered with `reg-mode`.

## Docs mode

Docs mode renders the selected variant as a documentation page.

![Docs mode for the login error variant, showing status, args, decorators, parameters, evidence, and tags.](../images/story/story-tutorial-05-docs-mode.png)

The page is built from the registered variant. Under the variant's id, parent
story and `:doc`, its sections are:

| Section | What it shows |
|---|---|
| Status & fidelity | The last run's status, the fidelity rung, the world inputs and the runner requirements. |
| Prose | The prose blocks of any `:prose` workspace that shows this variant. |
| Args | Every resolved arg, with its default and its `:argtypes` description. |
| View-arg schema | The view's `:rf/props` schema, when it has one. |
| Decorators | The resolved decorator stack, outermost first. |
| Parameters | The variant's `:modes`, `:substrates` and `:platforms`, or its story's where it declares none. |
| Evidence | The beats of the last run, each with **Inspect in Xray**. |
| Tags | The tags, as chips that toggle the sidebar's tag filter. |

A Contents list beside the sections jumps between them. Select a story
header in the sidebar, rather than a variant, and Docs mode shows every
variant's sections one after another under the story's `:doc`.

Because the page is generated from the same variant the canvas and the test
runner use, it cannot drift out of date the way a hand-written Markdown
example can.

## Workspaces

Workspaces arrange variants. Each `:layout` needs its own slot:

| Layout | Needs | Use it when |
|---|---|---|
| `:grid` | `:variants` | You want explicit variants in a specific order. |
| `:variants-grid` | nothing, `:for` or `:variants` | You want every variant under a story parent, or a curated list of them. |
| `:tabs` | `:variants` | You want one variant visible at a time. |
| `:prose` | `:content` | You want prose interleaved with rendered variants. |

A missing slot, or a slot the body does not accept, throws
`:rf.error/workspace-shape` at registration. `:columns` fixes the column count
of a `:grid` or `:variants-grid`; without it the grid fits as many columns as
the width allows. A view wider than its cell scrolls inside the cell. A grid
renders its first 100 cells and offers **+N more** for the rest, up to 400.

`:tabs` mounts one variant at a time, under a strip of tabs:

```clojure
(rf.story/reg-workspace :Workspace.login/tabs
  {:layout   :tabs
   :variants [:story.login/idle :story.login/error]})
```

`:prose` interleaves Markdown with variants, in order:

```clojure
(rf.story/reg-workspace :Workspace.login/guide
  {:layout  :prose
   :content [{:type :prose   :body "## Signing in\nThe form starts empty."}
             {:type :variant :id   :story.login/idle}
             {:type :prose   :body "A rejected password re-enables the form."}
             {:type :variant :id   :story.login/error}]})
```

A variant's Docs mode also shows the prose of any `:prose` workspace that
includes it. Most projects start with `:grid` and `:variants-grid`, and add
`:tabs` or `:prose` when a component or workflow needs real documentation.

## Toolbar modes

A toolbar mode is a named set of args:

```clojure
(rf.story/reg-mode :Mode.app/light
  {:axis :theme
   :args {:theme :light}})

(rf.story/reg-mode :Mode.app/dark
  {:axis :theme
   :args {:theme :dark}})
```

Modes with the same `:axis` are mutually exclusive. Turning on dark turns off
light. The active mode args sit in the args precedence chain above the story's
args and below the variant's:

```text
global < story < mode < variant < live control override
```

That is Storybook's globals, written as registered data.

The toolbar's Modes cluster shows one labelled group per `:axis`, where
choosing a mode deselects the others in its group. Modes without an `:axis`
sit together after the groups, and any number of them can be on at once;
when two active modes set the same arg, the later one wins. **reset** appears
while any mode is active and turns them all off. The active modes persist in
the browser across reloads, and they apply to every variant and workspace
cell.

A mode's args join the variant's effective args, so the view receives them like
any other arg, and a `:hiccup` decorator's `:wrap` function receives them as its
second argument, which is where a theme wrapper reads them. A test can run a
variant under a mode by passing it:

```clojure
(rf.story/is :story.login/error {:active-modes [:Mode.app/dark]})
```

## Viewports and backgrounds

The View cluster's two pickers resize the canvas and change what sits behind
it, and the background also sits behind each view in a workspace's cells. The
viewport presets are Full, Mobile portrait (375×667), Mobile landscape
(667×375), Tablet (768×1024), Desktop (1280×800) and Desktop wide (1920×1080),
plus a custom width and height. The background presets are Light (`#ffffff`),
Dark (`#1a1a1a`), Paper (`#f9f9f9`), Midnight (`#0a0a0a`) and Transparent, a
checkerboard, plus a custom colour.

A story or variant can fix either one:

```clojure
(rf.story/reg-variant :story.login/phone
  {:extends    :story.login/idle
   :viewport   :mobile-portrait        ; or {:width 390 :height 844}
   :background :paper})                ; or a CSS colour such as "#fafafa"
```

The preset ids are `:full`, `:mobile-portrait`, `:mobile-landscape`, `:tablet`,
`:desktop` and `:desktop-wide` for the viewport, and `:light`, `:dark`,
`:paper`, `:midnight` and `:transparent` for the background. A body's value
beats the toolbar's choice, so the picker has no effect on a variant that fixes
its own. An id Story does not know falls back to Full, or to Light.

## Decorators

A decorator wraps or prepares a variant, and a story or variant lists the ones
it wants under `:decorators`, each as a vector of the decorator's id and any
arguments. A variant's decorators sit inside its story's. Register one with
`reg-decorator`, in one of three kinds:

```clojure
;; :hiccup wraps the rendered view. :wrap gets the body and the effective args.
(rf.story/reg-decorator :app/card-frame
  {:kind :hiccup
   :wrap (fn [body args] [:div.card {:class (name (:theme args :light))} body])})

;; :frame-setup prepares the variant's frame before it renders.
(rf.story/reg-decorator :app/signed-in
  {:kind         :frame-setup
   :init         [[:session/restore {:user "ada"}]]
   :app-db-patch {:feature-flags {:beta true}}
   :teardown     [[:session/clear]]})

;; :fx-override stands a stub in for an effect.
(rf.story/reg-decorator :app/no-analytics
  {:kind     :fx-override
   :fx-id    :analytics/track
   :response nil})
```

```clojure
(rf.story/reg-variant :story.login/signed-in
  {:decorators [[:app/card-frame] [:app/signed-in] [:app/no-analytics]]})
```

`:init` events are dispatched and `:app-db-patch` is merged into app-db before
the view renders; `:teardown` events run when the variant's frame is destroyed.
A `:frame-setup` decorator needs at least one of the three. An `:fx-override`
stub records each call instead of performing the effect, and
`:rf.assert/effect-emitted` still sees the effect as emitted.

Four decorators are built in, each named by a Var so a typo fails to compile:

| Var | What it does |
|---|---|
| `rf.story/force-fx-stub-id` | Stubs one effect for this reference: `[rf.story/force-fx-stub-id :rf.http/managed {}]`. |
| `rf.story/layout-debug-measure-id` | Overlays element sizes and spacing. |
| `rf.story/layout-debug-outline-id` | Outlines every element in its own colour. |
| `rf.story/layout-debug-pseudo-id` | Forces pseudo-states, `#{:hover}` by default, or any of `:hover`, `:focus`, `:active` and `:visited`: `[rf.story/layout-debug-pseudo-id #{:focus}]`. |

The Layout-debug panel in the right rail switches the three layout-debug
overlays on and off for the selected variant, without touching its source.

## A decorator for every story

Storybook keeps project-wide wrappers such as a theme provider in `preview.ts`.
In Story it is one registration, made once in your stories namespace:

```clojure
(rf.story/reg-global-decorator :app/theme
  {:kind :hiccup
   :wrap (fn [body _args] [:div.app-theme body])})
```

Every variant now renders inside `:app/theme`. Globals are the outermost layer,
so the stack reads global, then story, then variant, with the earliest-registered
global outermost. Unlike `preview.ts`, the chain is data:
`rf.story/variant-plan` carries the resolved stack under `[:world :decorators]`,
and Docs mode's Decorators table lists the global first. Neither
`rf.story/explain` nor the Explain panel lists the stack, so check the plan or
Docs mode when you want to know what wraps a variant. The
[registration reference](api/registration.md#reg-global-decorator) covers
`clear-global-decorator` and the `configure!` form.

## Tags

Story registers the seven inclusion tags from chapter 1 and five size tags,
`:state/empty`, `:state/small`, `:state/medium`, `:state/large` and
`:state/special`, which say how much data a variant's state holds. Any other
tag must be registered before a variant uses it, or registration throws
`:rf.error/unknown-tag`:

```clojure
(rf.story/reg-tag :status/beta
  {:doc  "Shipped behind a flag."
   :axis :status})
```

The sidebar's tag filter groups tags by `:axis`, one row per axis, with the
tags that have none in a row headed OTHER. Selecting tags narrows the tree to
variants carrying any of them; selecting none shows everything. The convention
is to name a tag after its axis, as in `:status/beta`, `:team/checkout` or
`:feature/payments`.

A variant that `:extends` another unions its tags with its parent's. To drop an
inherited tag, write it with a `!`: `#{:!dev}` removes `:dev`, and the marker
itself never shows up as a tag.

## Working tools in the shell

**Dispatch** in the toolbar opens a console in the right rail for the selected
variant. Type an event id, which autocompletes from the registered events, and
an EDN payload, then press **Dispatch** or **Dispatch-sync**; the event lands in
the variant's frame exactly as the app's own would. When the event requires
coeffects, the console names them and takes them in a `cofx` field. A history
of the last 20 dispatches per variant, kept in the browser, replays any one of
them on a click. A story or variant with `:dispatch-console? true` opens the
console by default.

**Inspect** turns on a picker: hover the canvas to highlight elements, and
click one to open the source of the view that rendered it. The **open** link
beside the variant's title opens its registration. Both open your editor, and
two `configure!` keys point them at it:

```clojure
(rf.story/configure! {:rf.story/editor       :cursor   ; :vscode (default), :cursor, :idea, or {:custom "<template>"}
                      :rf.story/project-root "/path/to/my-app"})
```

`:rf.story/project-root` is the directory that source paths are relative to;
Xray's source links use it too.

## Extends

`:extends` specializes another variant:

```clojure
(rf.story/reg-variant :story.login/retrying
  {:extends    :story.login/error
   :script     [[:dispatch [:login/flow [:login/retry {:email    "ada@example.com"
                                                       :password "correct-horse"}]]]]
   :assertions [[:rf.assert/state-is :login/flow :submitting-retry]]})
```

The child starts from the error state its parent's setup reaches, keeps the
parent's stubbed HTTP effect, and runs only its own script and assertions.

The inheritance rule is: **context flows down, verdict is local.**

| Field | Rule |
|---|---|
| `:setup` | parent then child, appended in order. |
| args | deep-merged; the child's win. |
| `:decorators` | the parent's, unless the child declares its own, which replace them. |
| `:network` and the other world inputs | inherited. |
| `:fx-overrides`, `:interceptor-overrides` | inherited; the child's own values win. |
| `:checks` | inherited. |
| `:script` | child-only. |
| ordinary `:assertions` | child-only. |
| tags | union. |

A child inherits the world its parent set up. It does not run the parent's
script or inherit the parent's verdict, so a change to the parent's
assertions never changes a child's result.

## Fragments and checks

Use a fragment for reusable setup, script or world context:

```clojure
(rf.story/reg-fragment :fragment.login/submitted-wrong-password
  {:setup [[:login/flow [:login/submit {:email    "ada@example.com"
                                        :password "wrong"}]]]})
```

Use a check for reusable expectations:

```clojure
(rf.story/reg-check :check/no-runtime-warnings
  {:assertions [[:rf.assert/no-warnings]]})
```

Compose them explicitly:

```clojure
(rf.story/reg-variant :story.login/rejected
  {:compose    [:fragment.login/submitted-wrong-password
                :check/no-runtime-warnings]
   :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
   :script     [[:dispatch [:login/flow [:login/failure {}]]]]
   :assertions [[:rf.assert/state-is :login/flow :error]]})
```

A fragment's setup and script come before the variant's own, in the order
`:compose` lists them. A fragment's args are deep-merged in, and a check's
assertions run with the variant's.

Fragments are flat. A fragment does not compose another fragment: a fragment
body carrying `:compose` or `:extends` throws `:rf.error/fragment-shape`. That
keeps the order of setup easy to read and rules out cycles.

`:fx-overrides` and `:interceptor-overrides` are strict. A value the variant
sets itself always wins. When two composed fragments set different values for
the same effect or interceptor and the variant sets none, the variant cannot
compile: `explain` throws `:rf.error/story-compose-conflict` and a run errors
with it, naming the field and the key. The variant resolves it by stating the
value it wants.

## Explain is the receipt

When composition is involved, use `rf.story/explain` or the Explain panel
([chapter 4](04-the-variant-is-a-test.md#explain)). It shows the source chain,
merge decisions, setup order, script order, checks, assertion locations,
runner requirements and source coordinates.
