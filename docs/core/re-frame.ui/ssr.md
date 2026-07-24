# SSR and hydration

A compiled `defview` renders on the JVM as readily as in the browser: the same
template lowers to a versioned structural tree there, and the SSR artefact folds
that tree to HTML. This page covers what the **view layer** owns — what renders on
a server, root identity, hydration, and the client-only escape. The server side of
the story — requests, payloads, responses, streaming — is the
[SSR corpus](../../ssr/index.md); this page points rather than repeats.

## What renders on the server

The JVM render is the *structural subset* — full semantics where rendering is
pure, honest fallbacks where only a browser could answer:

| Full semantics | Honest fallbacks |
|---|---|
| Structure, props, branches, keyed lists | `local` → its initial value |
| Subscriptions (pure snapshot reads) | effects do not run; refs are absent |
| Event intent — vectors ride the tree as data | `client-only` → its declared fallback |
| `ui/html` | presence → `:present` |
| | error boundaries render the child — server failure policy applies |

A view that *is* its host behaviour (a canvas chart, a map) wraps the leaf in
`ui/client-only` and keeps the shared markup outside.

## Roots and frames — two different things

A **root** is one React unit in one DOM container. A **frame** is one re-frame2
state world ([Frames](../frames.md)). A page can have several of each, and they mix
freely — two roots can reference one shared frame; one root can scope several.

Every root has a **root-id**. You usually write none: a single-root page derives it
from the mounted view's registered id — the [Build a view](build-a-view.md) counter
path, no extra config. Author identity the moment a page outgrows that:

```clojure
(ui/mount [ui/frame-root {:id :shop} [product-panel]] left-el
          {:root-id :panel/left})
(ui/mount [ui/frame-root {:id :shop} [product-panel]] right-el
          {:root-id :panel/right})
```

- The same view mounted twice with neither site authored fails loud with
  `:rf.error/duplicate-root-id` — at build time where both sites are visible, and
  at runtime *before any render*.
- Identity opts (`:root-id`, `:disambiguator`, `:identifier-prefix`) are
  compile-time literals; the opts map also carries plain-fn host error callbacks
  (`:on-uncaught-error`, …).
- Root forms are **literal** at every entry point — `mount`, `render!`,
  `hydrate-root`, `render-static`, and `ui.test/render` alike. A runtime-assembled
  vector is the compile error `:rf.ui.compile/runtime-root-form`.

Hosts needing more control than `mount` use the host tier directly —
`create-root` (identity without rendering; authored `:root-id` required),
`render!` (re-render a literal form into a Root), and `unmount!` (total teardown).
`mount` is exactly `create-root` + frame preflight + `render!`, one-shot and
idempotent per root — mounting again with the same id and container re-renders in
place, which is the hot-reload path.

## Hydration

A server-rendered page boots on the client in **two calls**: install the state
payload, then adopt the DOM.

```clojure
(ssr/hydrate! {:frame :app :payload payload})   ; state only — no :render-tree-fn
(ui/hydrate-root (js/document.getElementById "root")
                 [ui/frame-provider {:frame :app} [app-root]])
```

Two rules with no exceptions:

- **Identity comes from the server's manifest**, never from client opts — passing
  `:root-id` or `:identifier-prefix` to `hydrate-root` is an error, and a hydrate
  with no valid manifest fails loud with `:rf.error/root-manifest-invalid`. The
  server decided; the client matches or fails loudly.
- **Roots hydrate independently.** Each root ships its own manifest; frame
  payloads install idempotently (if two roots reference `:session`, whichever
  hydrates first installs it, the other finds it live); and a root that fails to
  boot (`:rf.error/root-boot-failed`) leaves its server markup standing, inert,
  without touching its neighbours.

**How mismatches surface.** A compiled root verifies by *React-native adoption*:
React diffs the root's first server-phase render against the server DOM, and the
recoverable divergences it patches — divergent text, a missing or extra element —
surface as a dev-only `:rf.ssr/hydration-mismatch` diagnostic (composed over any
`:on-recoverable-error` you pass, never replacing it). React has already recovered
by the time the diagnostic fires, so it is a signal to fix the drift, not a crash.
One honest limit: **attribute-only** mismatches — a stale `class` or ARIA value on
an element whose tag and text still match — take React's development-only warning
path and produce **no** trace, by design. The walkthrough, including the hiccup
substrates' hash-based tier, is the [SSR tutorial](../../ssr/tutorial.md).

## Browser-only subtrees: `client-only` and the phase flip

```clojure
(ui/client-only {:fallback [:div.map-shell "Map loads in the browser"]}
  [MapboxView {:center center}])
```

The fallback is **mandatory and capability-free** — plain markup, compiler-checked
(`:rf.ui.compile/capability-in-fallback` otherwise) — because it renders three
times over: on the JVM, and on the client's *first* hydration pass, so React adopts
markup structurally identical to the server's.

After a hydrating root's first commit, the runtime flips that root from its
`:server` phase to `:client` — **once per root, as one root-scoped write** — and
every `client-only` site under it swaps to its client subtree in that single
update. There is no per-site flip and no page-wide barrier: each root flips when
its own hydration commits, and a failed root never flips (its fallback markup is
exactly the part designed to stand without a runtime). Non-hydrating mounts are
born in `:client` phase and never flip — a `client-only` site under plain
`ui/mount` just renders its client subtree.

## Static output is a decision, not a guess

A root with no client capabilities can ship as inert HTML — no manifest, no
payload, no hydration, no phase flip — but only when you say so:

```clojure
(ui/render-static [site-footer {:year 2026}])   ; ⇒ an HTML string
```

`ui/render-static` is a **macro, JVM/server only** (a CLJS expansion is a compile
error). It enforces the same literal root form as the other entry points, and the
compiler proves the tree needs no client runtime — declaring static something that
is not fails the build (`:rf.ui.compile/static-root-requires-runtime`). Nothing is
silently stripped by inference. The HTML folding itself lives in the SSR artefact
(`re-frame.ssr/emit-ui-tree`), which `render-static` reaches by late resolution —
calling it without `day8/re-frame2-ssr` on the classpath fails loud with
`:rf.error/ssr-artefact-missing`.

## Troubleshooting

| Symptom | Named error | Fix |
|---|---|---|
| Hydrating with client identity opts, or no valid server manifest | `:rf.error/root-manifest-invalid` | Let the manifest own identity; check the server actually emitted one |
| Two roots deriving the same id | `:rf.error/duplicate-root-id` | Author `:root-id` (or `:disambiguator`) at each site |
| A root's boot throws | `:rf.error/root-boot-failed` — that root only; siblings unaffected | Read the dev diagnostic; the server markup stays standing |
| Text/structural drift between server and client render | Dev diagnostic `:rf.ssr/hydration-mismatch`; React patches the DOM | Make the first client render match the server's — usually a `client-only` fallback that drifted |
| `render-static` on a tree that needs a client runtime | Compile error `:rf.ui.compile/static-root-requires-runtime` | Hydrate it instead, or push the capability behind `client-only` |
| `render-static` without the SSR artefact | `:rf.error/ssr-artefact-missing` | Add `day8/re-frame2-ssr` to the server classpath |

## When not

- A page with no server rendering needs nothing on this page — `ui/mount` and the
  [boot recipe](../how-to/boot-and-mount-an-app.md) are the whole story.
- Request handling, payload allowlists, response control, streaming, and the head
  grammar are the [SSR corpus](../../ssr/concepts.md)'s territory — don't rebuild
  them from view-layer parts.
- Reminder: `re-frame.ui` is experimental — the retained adapters are the default
  choice, and their SSR path (the hash-verified hiccup tier) is covered by the same
  SSR corpus.
