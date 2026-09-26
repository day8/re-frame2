# 2. Every state, side by side

UI bugs hide in the states nobody drives by hand: loading, error, retry. This
chapter gives the login form five variants, puts them side by side in a
workspace grid, and uses Controls to edit a variant's inputs.

## Five login states

The login-form testbed carries five variants:

```clojure
:story.login/idle
:story.login/submitting
:story.login/error
:story.login/submitting-retry
:story.login/authenticated
```

The error variant looks like this:

```clojure
(rf.story/reg-variant :story.login/error
  {:doc "Server rejected credentials. Form re-enabled; the error is visible."
   :setup [[:login/flow
            [:login/submit {:email "ada@example.com"
                            :password "wrong"}]]
           [:login/flow
            [:login/failure
             {:failure {:status 401
                        :message "Invalid credentials."}}]]]
   :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
   :script [[:assert [:rf.assert/state-is :login/flow :error]]
            [:assert [:rf.assert/sub-equals [:login/error] "Invalid credentials."]]]
   :tags #{:dev :docs :test}})
```

The setup submits the form and then delivers the failure event, so the
machine reaches `:error` along the same path the app takes. The
`force-fx-stub-id` decorator takes over the HTTP effect, so no request is
sent; the view itself is untouched.

To reach the error through the HTTP reply itself, rather than a hand-fired
failure event, stub the request with `:network`. Each key is a `[method url]`
route and each value says how to reply:

```clojure
(rf.story/reg-variant :story.login/error-from-401
  {:doc     "The server answers 401, and the reply drives the form to :error."
   :setup   [[:login/flow [:login/submit {:email    "ada@example.com"
                                          :password "wrong"}]]]
   :network {[:post "/api/login"] {:reply {:failure {:kind :rf.http/http-4xx
                                                     :tags {:status 401}}}}}
   :script  [[:assert [:rf.assert/state-is :login/flow :error]]]
   :tags    #{:dev :docs :test}})
```

`{:reply {:ok data}}` answers with a decoded success value instead. `:network`
stubs `:rf.http/managed` requests, and a request that matches no route fails as
a transport error instead of reaching the network. `rf.story/force-fx-stub-id`
is the coarser tool: it takes over every call to one effect and answers nothing,
which is what the submitting variant wants, a request frozen in flight.

## Workspaces

A workspace arranges variants together. The simplest useful form is an explicit
grid:

```clojure
(rf.story/reg-workspace :Workspace.login/all-states
  {:doc      "The five login states side by side."
   :layout   :grid
   :variants [:story.login/idle
              :story.login/submitting
              :story.login/error
              :story.login/submitting-retry
              :story.login/authenticated]
   :columns  3
   :tags     #{:docs}})
```

Open the workspace and all five states render together.

![A login workspace rendering idle, submitting, error, retry, and authenticated states side by side.](../images/story/story-tutorial-02-workspace-grid.png)

Each cell gets its own frame. If a cell dispatches an event, it changes that
cell's frame and no other, so reviewing one state cannot disturb the others.

A frame isolates state, not the page. Each cell's frame has its own app-db,
event queue, subscription cache and epoch history, while every frame runs the
same registered handlers ([What a frame is](../core/frames.md#what-a-frame-is)).
Every cell also renders into the one page that hosts the shell. That page's
stylesheets reach every cell, only one element on it can hold focus, and a
modal that a view portals into `document.body` lands on the shared page,
outside its cell. Text colour and font are reset at the cell boundary, so a
view renders in the browser's default text styles rather than the shell's, and
a view that relies on inherited text styles from its app shell must set them
(a decorator can). Storybook renders stories in a preview iframe,
although its docs pages can render them inline in the page itself; Story's
canvas and workspaces have no iframe mode. So a job such as checking that a
design system's CSS holds up without the host page's stylesheets around it may
need an iframe boundary, and Story does not provide one.

There is also `:variants-grid`, which lists every variant under a parent story
for you:

```clojure
(rf.story/reg-workspace :Workspace.login/auto-grid
  {:layout  :variants-grid
   :for     :story.login
   :columns 3})
```

Use an explicit grid when the order is part of the story you want to tell. Use
`:variants-grid` when you want every variant under a parent to appear without
maintaining the list by hand. The cells come in variant-id order. `:for` names
the story; without it, the workspace id does, so `:Workspace.login/auto-grid`
enumerates `:story.login`.

A `:variants-grid` mounts every cell at once, each in its own frame. A view
that creates its own frame provider internally defeats that, because its cells
end up sharing state. For such a view, `:isolation :shared` mounts one cell at
a time, with previous and next buttons to move between them.

## The bigger wall

The `nine_states` example puts one todos view on screen in nine states:
Nothing, Loading, Empty, One, Some, Too Many, Incorrect, Correct and Done.

![The nine_states workspace showing a matrix of todo UI states.](../images/story/story-tutorial-08-nine-states.png)

With every state on one page, the question changes from "can I reach the empty
state?" to "does every state this screen can show look right?"

## Controls

Controls, in the right rail, edits the selected variant's args and its view
state overrides. It also summarises the variant's setup, network and effect
inputs, and holds the save actions.

For ordinary args, Story derives controls from the view's schema where it can:

| Schema shape | Control |
|---|---|
| `:boolean` | checkbox |
| `[:enum ...]` | select |
| `:int`, `:double` | number field |
| `:string` | text field |
| `:keyword` | text field, read back as a keyword |
| `[:maybe X]` | the control for `X` |
| `:map` | a group of fields, one per key |
| `:vector`, `:set` | a list of fields, with rows to add and remove |
| `:tuple` | one field per position |

`:argtypes` picks a different control where the derived one is not right
(chapter 1).

Controls edits **inputs**, not arbitrary component internals. Changing
`:heading` changes an arg. Pinning a subscription value creates a view-state
override. Saving the current canvas state tells you which parts can be
written as a variant and which cannot.

## Save the current state as a variant

When Controls edits give you a state worth keeping, press **save as new
variant…** under Controls. The dialog shows a `reg-variant` form that extends
the selected variant with the current args, under an id you can edit. Below
the form it lists each part of the state it could not take from the live
canvas, such as sub-overrides, db seed, route, network, effect overrides or
viewport. Each is marked "captured as declared" when the new variant inherits
it from the source, or "not yet projectable" when the form leaves it out.
Story never writes your source; copy the form into your stories namespace.

Saving is an authoring gesture: it names a state you built by hand. Its
testing counterpart, promoting a run to a regression variant (chapter 4),
turns a run that failed into a variant, and the shell keeps the two as
separate actions.
