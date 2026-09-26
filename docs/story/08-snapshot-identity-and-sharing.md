# Snapshot identity and sharing

This page covers how Story identifies a variant's content and how you hand a
state to someone else. Each share path says how much of the state the
recipient can reproduce.

## Content identity

A variant has a name, but names are not stable enough for every job. You can
rename `:story.login/error` to `:story.auth/login-error` without changing the
state it renders. You can also keep the name and change the state completely.

So Story also identifies a variant by a hash of its canonical content:

| Hash | Answers |
|---|---|
| plan hash | Is this the same normalized plan? |
| run hash | Did this run behave the same way? |
| snapshot identity | What should a visual-regression capture key use? |

A visual-regression tool can use these to tell a rename from a real change of
state. Story does not compare pixels itself; it gives the tool that does a
stable key.

## Local visual review

Snapshot identity is what a visual-regression tool keys its baselines on.
[Local visual review](08-local-visual-review.md) is a Playwright recipe that
captures every `:test` variant under each theme mode, compares the pixels
against baselines kept outside the repository, and reports which content
hashes moved.

## Sharing

The Share dialog exposes the common handoff paths.

![The Story Share dialog with URL, EDN, screenshot, and static build options.](../images/story/story-tutorial-06-share-dialog.png)

**Share ▸** in the toolbar opens it. Each row copies one thing to the clipboard:

| Command | What it copies |
|---|---|
| Share URL | The address-bar URL, which lands a recipient on this exact view. |
| Copy EDN | A `reg-variant` form for the selected variant's current state. It registers a new variant extending the selected one, so the original stays as it is. |
| Screenshot | A PNG of the variant canvas. |
| Static build | The `npm run story:build` command, for producing a standalone Story site ([Static builds](08-static-builds.md)). |

Copy EDN appears only while a variant is selected. With a workspace selected
instead, the Share URL shares the workspace.

Copy EDN, like **save as new variant…** ([chapter 2](02-every-state-side-by-side.md#save-the-current-state-as-a-variant))
and **promote run → regression variant…** ([chapter 4](04-the-variant-is-a-test.md#promoting-a-run)),
emits a whole `rf.story/reg-variant` form that pastes and runs as-is in a
stories namespace that requires `[re-frame.story :as rf.story]`.

The browser address bar is already meaningful. Selecting the error state in the
login-form testbed from [chapter 1](01-first-variant.md), where it is registered
as `:story.login-form/error`, produces a URL like:

```text
http://localhost:8043/?variant=story.login-form%2Ferror#/stories
```

The URL carries everything you can choose in the shell:

| Parameter | Holds |
|---|---|
| `variant` | The selected variant. |
| `workspace` | The selected workspace. |
| `mode-tab` | `docs` or `test`, when the variant is not on the Canvas tab. |
| `modes` | The active toolbar modes, comma-separated. |
| `viewport` | A viewport preset id, or `WxH`. |
| `background` | A background preset id, or a colour. |
| `tag-filter` | The selected tags, comma-separated. |
| `overrides` | The selected variant's live Controls edits, as an EDN map. |
| `substrate` | The substrate, when it is not `reagent`. |

Selecting things pushes a new browser history entry, so Back returns to the
previous view. When a pasted URL carries overrides for args the variant no
longer has, the shell drops them and says so in a banner over the canvas.

Adding `embed=1` renders the canvas alone, with no toolbar, sidebar or right
rail, for embedding a variant in another page:

```text
http://localhost:8043/?variant=story.login-form%2Ferror&embed=1#/stories
```

## Reproducibility labels

Each share path states how reproducible it is:

| Label | Meaning |
|---|---|
| Fully reproducible | The recipient can land on the same state. |
| Partially reproducible | Some state carries, but something is omitted or approximate. |
| View-only | The artifact shows the state but cannot replay it. |

A screenshot is view-only.

Share URL and Copy EDN are fully reproducible when every input that drives the
variant survives as EDN. When one does not, the row's label drops instead of
pretending, and the dialog lists why under it. A Controls edit or subscription
override whose value is a function makes the row view-only. A Controls edit
whose value does not survive as EDN, a network reply given as a function, a
setup or script step carrying a function, and overrides the URL could not
apply each make it partially reproducible. The lowest reason wins, so the label
never promises more than the recipient gets.

## Static builds

The Share dialog's **Static build** row copies `npm run story:build`, the
script that publishes your catalogue as a standalone site. [Static
builds](08-static-builds.md) sets that script up.

## Privacy boundaries

Two questions are easy to blend: can this artifact reproduce the state, and
should this value leave the machine? The reproducibility labels answer the
first. The second is answered where values leave your machine without you
choosing each one: Story-MCP and logs redact sensitive values there.

The share URL, copied EDN, screenshot and static build carry real values,
unredacted. Pressing Share or Copy is you handing over your own app's state,
the same as pasting console output, so anything sensitive in the variant goes
with it. Read what you share.
