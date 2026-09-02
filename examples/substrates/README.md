# Substrates — which comparison do you want?

Everything in this folder exists to be read *against* something else. Each
example here is a second copy of an app you can already find elsewhere in the
tree, changed in exactly one place — and which place that is depends on the
comparison you came for. There are two.

## Track 1 — same authoring language, different package

Both sides are Reagent. You write the same views, with the same `reg-view`, and
the difference is entirely in what ships.

| Read this | Against this | What differs |
|---|---|---|
| [`reagent_slim/counter/`](reagent_slim/counter/) — `examples/counter-slim-and-fast` | [`core/counter/`](../core/counter/) — `examples/counter` | The package under the notation. `day8/reagent-slim` is a ground-up `reagent2.*` rewrite: every `reagent.*` import becomes `reagent2.*`, and `rf/init!` takes the slim adapter Var. The interest is in what the bundle does *not* contain. |

## Track 2 — one model, three view languages

One substrate-free model namespace, three view layers over it. This is the
comparison the login example exists to make.

[`login.model`](../core/login/model.cljc) is the ONE owner of the `auth.login`
dataflow — every schema, the demo HTTP fx, the five-state machine, the
form-slice events, the named subs, and the shared frame config. It names no
substrate. The three entry points below each `:require` that identical
namespace and add nothing but their own views and boot:

| View language | Entry point | Build id | The notation |
|---|---|---|---|
| Reagent | [`core/login/core.cljs`](../core/login/core.cljs) | `examples/login` | `reg-view` with `dispatch` / `subscribe` injected into the body |
| UIx | [`uix/login/core.cljs`](uix/login/core.cljs) | `examples/login-uix` | `defui` reading through the `use-subscribe` / `use-frame` hooks |
| Hicasso | [`hicasso/login/core.cljs`](hicasso/login/core.cljs) | `examples/login-hicasso` | `h/defview` with `h/sub`, and handlers stated as data — an event vector at `:on-change` |

Open any two of those three side by side. The subscription vectors are
identical, the event ids are identical, the app-db shape is identical, and the
visible behaviour — including the test ids — is identical. What changes is the
notation and the boot, and that is the whole finding.

**"One model" is meant literally.** The comparison holds the model constant not
by keeping three copies in step but by there being one copy. Two gates keep it
honest:

- `npm run test:examples-compile` compiles all three builds with a zero-warning
  bar;
- `npm run test:bundle-isolation` releases all three and greps each `main.js`
  for the other two's fingerprints. Each login bundle must carry its own view
  runtime and neither of the others — which is what proves the shared
  `login.model` drags in no view library or adapter
  (`implementation/scripts/check-login-bundle-isolation.cjs`).

### The Hicasso arm also renders on a server

The Hicasso login carries a fourth file the other two arms do not:
[`hicasso/login/server.cljs`](hicasso/login/server.cljs), a `:node-library`
build (`examples/login-hicasso-server`) that publishes the render module the
[`ssr-node`](../../implementation/ssr-node/README.md) sidecar loads, plus
[`hicasso/login/host.clj`](hicasso/login/host.clj), the JVM Ring handler that
dials it — the shape of a native Hicasso root rendered on Node while the JVM
keeps the request, the `<head>`, the payload and the shell (`rf2-8arzr`).

**Both files run, and a gate drives each.** `server.cljs` is exercised by the
CLJS product witness (`re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test`),
which drives the real views, the real `login.model` registrations and this
module's own published entry table through the sidecar's own request validator,
simulating only the transport. `host.clj` is exercised by
`re-frame.ssr.ring.login-host-crossing-test` (`implementation/ssr-ring/test/`,
CI's `jvm-node-crossing` job): requiring the namespace is the compile gate, and
its `:crossing` tests spawn the real launcher on a real socket and drive the
handler this host constructs.

What the pair is the clearest illustration of is **why render state is a policy
distinct from the hydration payload**: the server notice the page renders is
deliberately in one and not the other. Both halves read that policy from one
place — `policy.cljc`, a `.cljc` namespace, because neither neighbour can be
read by the other's compiler and a copy in either would be a copy that drifts.

The recipe these two files illustrate is
[Render on Node](../../docs/ssr/concepts.md#render-on-node).

## Running any of them

From `implementation/`:

```bash
npm run dev:example -- examples/login            # Reagent
npm run dev:example -- examples/login-uix        # UIx
npm run dev:example -- examples/login-hicasso    # Hicasso
npm run dev:example -- examples/counter          # Reagent counter
npm run dev:example -- examples/counter-uix      # UIx counter
npm run dev:example -- examples/counter-slim-and-fast
```

Each command stages the example's host page and the shared `_shared/` assets
beside the compiled bundle, serves it on a free loopback port, and prints the
URL. No backend ships: the login runs against a canned in-page HTTP stub, so
the password `correct-horse` succeeds and anything else fails the way the
machine expects.

## What else is here

| Example | What it demonstrates |
|---|---|
| [`uix/counter/`](uix/counter/) — `examples/counter-uix` | The [`core/counter/`](../core/counter/) dataflow through UIx. Deliberately a second copy of its tiny event/sub set rather than a shared model — extracting three one-line registrations would cost the smallest-app lesson more than it saves. |
| [`uix/dashboard/`](uix/dashboard/) — `examples/dashboard-uix` | Design-led: UIx driving a substantive multi-pane layout, sharing the "Editorial Warm" identity from [`_shared/css/style.css`](../_shared/css/style.css) with `core/notebook/`. |

Not every app is rendered on every view layer, and that is on purpose. The
substrate tree carries the comparisons worth making, not a cross-product.
