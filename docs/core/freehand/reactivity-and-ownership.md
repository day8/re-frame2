# Reactivity and ownership

When `[:count]` changes, the view that read it should re-render — only that
boundary, once, if the value really changed. You do not manage subscription
handles. You do need **what re-runs**, **when ownership attaches**, and **what
unmount does not clean up**.

> **Render only probes. Commit owns. Unmount is not domain cleanup.**

## What re-computes when something changes

### One reactive unit per declared boundary

Each time you write `[some-view props]`, Freehand mounts (or reuses) a **boundary**.
That boundary owns the subscription reads it made during its last **committed**
render.

If a subscription that boundary depends on moves, **that boundary** is marked
dirty. Its parent is not re-run just because a child is dirty. Siblings that never
read that subscription stay quiet.

### Same meaning, both modes

**Interpreted** mode captures the queries the body actually reads. **Compiled**
mode knows a finite set of `sub` sites and can drop the reactive shell when a view
is proved sub-free. As an author: plain value, owning boundary invalidates when an
active target changes, no user-facing handle.

### Only real value changes matter

Subscription results are stabilised with re-frame value equality (`rf=`). If a
handler writes `2` where the value was already `2`, dependent views do not churn.
Equal values keep the previous reference where possible so memoised children stay
cheap.

### Children skip work when props are equal

Each boundary is memoised on its one props map using the same value equality. A
parent can re-render without forcing a child to re-render, as long as the child’s
props did not actually change.

Practical tip: pass named slots of stable data. Avoid rebuilding a huge map or an
anonymous function on every parent render and threading it downward — that defeats
memo for no good reason. Freehand will warn about some of the worst cases in
development.

### Renders batch at the host checkpoint

When an event settles, several subscriptions may move. Freehand does not re-render
a dirty boundary once per subscription. Dirty work coalesces into **one flush** at
the next host checkpoint.

There is one important exception: the **controlled-input synchronous door** (see
[Events](events-and-handlers.md#controlled-inputs)). Typing into a controlled field
must update app-db and the controlled value inside the same discrete browser event,
or the caret and IME break. That path is allowed to flush sooner on purpose.

**In one sentence:** changing a value re-runs exactly the boundaries that read it,
once, and only if the value really changed. Fix structure first (narrower subs,
keys, windowing); compile later if interpretation cost still shows up.

## When not

- Over-narrowing every leaf into a subscription when props-only presentation is
  clearer — read at the boundary that needs the value.  
- Compiling to fix over-broad subs — narrow the graph first.

## Recap

- Prefer narrow subscriptions and stable props.  
- Key repeated children with domain ids.  
- Do not call `v/sub` off the render thread.  
- Do not put domain cleanup on unmount.  
- Measure before compile.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Whole list re-renders on one edit | per-id subs; keyed row boundaries |
| High churn, equal output | unstable props (inline maps/fns) or over-broad subs |
| Wrong row’s data for one frame | acquire-before-release on conditional targets — usually automatic; check keys/ids |
| `v/sub` from `future` / worker | realize on render thread or extract a child view |
| Draft still in app-db after leave | owner/route clear — unmount does not wipe controllers |

## Render only reads; commit owns

Modern React can run a render and then throw the result away. Freehand is built for
that world.

The rule is strict:

> **While rendering, Freehand only probes. Ownership starts when the host commits
> the selected render.**

During render, `sub` resolves values and records candidates. Freehand does not
treat that speculative work as the live subscription set yet.

Only when React (or the host) **selects** that render for commit does Freehand:

- acquire subscription handles for what was read  
- publish the event-handler table for that generation  
- connect host behaviors for that generation  

If the render is abandoned or throws, it owns nothing. Strict Mode’s double render
balances out under that design.

When a boundary disconnects (unmount or generation retirement), Freehand releases
observation and retires the committed callbacks for that occurrence. It does
**not** dispatch domain cleanup events for you. Routes, resources, and machines
own causal lifetime. Unmount is not “delete my form draft from app-db.”

## Conditional subscriptions

If a branch is not taken this render, its subscriptions are not part of the
capture (interpreted) or are inactive in the site table (compiled).

When the next render takes a different branch, Freehand **acquires the new
targets before releasing the old ones**. That is what prevents a single frame of
“order A’s details with order B’s id.”

**Same-thread only.** Do not call `v/sub` from a `future`, `pmap`, `bound-fn`, or
other conveyed thread. Capture is defined on the render thread. The error will tell
you to realize work on the render thread or extract a keyed child view.

## Keys and list identity

```clojure
(v/defview todo-list [_]
  [:ul.todo-list
   (for [id (v/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

`:key` chooses sibling **occurrence** identity among repeated children. Freehand
strips it before the props map reaches your function body, so your view does not
see `:key` as a normal prop.

Keys answer: “across reorders and re-renders, which row instance is this?”  
They do **not** answer: “where does this field’s draft live in app-db?” For that,
use domain ids or an explicit `:control` address — see [State](state.md).

Unkeyed dynamic lists are a development finding, and a hard failure under compiled
mode. Prefer stable domain ids over array indexes when identity matters.

### Window large lists before you compile

Mounting ten thousand keyed rows is a data problem first. Prefer a **window** of
visible ids from a subscription (or a host virtualizer for true virtual scroll):

```clojure
(v/defview people-page [_]
  (let [ids (v/sub [:people/visible-window-ids])]  ; e.g. ~40 rows for the viewport
    [:ul.people
     (for [id ids]
       [person-row {:key id :id id}])]))
```

| Approach | When |
|---|---|
| Sub that returns a **window of ids** | Most SPA tables; keeps Freehand trees small |
| Purpose-built host grid / virtualizer | Huge grids, spreadsheet UX — [JS libraries](js-libraries.md) |
| Compile every row | After windowing still shows interpretation cost on hot templates |

Do not expect `{:compiled true}` to make an unwindowed 10k-row tree cheap.

## Frames, hot reload, and retarget

Frames are created at **host preflight**, not from inside a view render. That is
what makes hot reload a designed workflow: you save a file, view code swaps, and
app-db in the live frame can survive.

On a compatible reload, Freehand keeps the shell, invalidates the descriptor
revision, and commits the next dependency and event set together. If the host or
descriptor signature is incompatible, the shell remounts cleanly rather than
lying about identity.

If a frame provider retargets a subtree from frame A to frame B, children rebind
even when their props look equal. Ordinary shells always observe frame context.
Compiled shells may skip some frame machinery only when the manifest proves the
view and its events do not need it.

## Performance levers (before you compile)

Compilation is not the first tool. In short: narrow subs → choose boundaries →
window lists → measure → **then** compile. High `:stable-renders` means fix subs
or props, not compile.

## What tools can answer

In development, committed boundaries expose a versioned evidence surface (stripped
from production). A good tool session can answer:

1. Which occurrence rendered, and where is its source?  
2. Which frame and descriptor generation did it use?  
3. Was the cause props, a subscription, frame retarget, HMR, presence, host work,
   or mount?  
4. Which reads and event sites were selected in that same generation?  
5. Is the evidence statically complete (compiled), only realized (interpreted),
   opaque (host interior), or capped?

Evidence should always say how complete it is — scope, basis, completeness, and
loss. Freehand does not claim omniscience for full-Clojure interpreted views.
There is no separate author-facing `:reads […]` language in v1; reads stay inline
`(v/sub …)`.
