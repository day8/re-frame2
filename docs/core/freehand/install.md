# Install and boot

You want Freehand on the page. Four moves: **depend → install an adapter → mount →
tear down**. Everything upstream of the view is ordinary re-frame2 and does not
change.

!!! warning "Pre-alpha: no Clojars coordinate"

    `day8/re-frame2-freehand` is **not published**, and there is no date at which
    it will be. It lives in the re-frame2 monorepo, so today you resolve it — and
    `day8/re-frame2` with it — from a checkout using `:local/root`. Treat the
    snippets below as the shape of the dependency, not as a coordinate you can
    paste into a fresh project.

## Depend on the artefact

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:deps {day8/re-frame2          {:local/root "../re-frame2/implementation/core"}
        day8/re-frame2-freehand {:local/root "../re-frame2/implementation/freehand"}
        day8/re-frame2-uix      {:local/root "../re-frame2/implementation/adapters/uix"}}}
```

Two optional artefacts join the same way when you need them:
`day8/re-frame2-routing` for [`v/route-link`](#framework-views), and
`day8/re-frame2-ssr` for [server rendering](ssr.md).

## Boot: adapter, then mount

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.adapter.uix :as uix]))

(v/defview app-root [_]
  [:main [counter {:label "hello"}]])

(defn ^:export run []
  (rf/init! uix/adapter)
  (v/mount [app-root {}]
           (js/document.getElementById "app")
           {:frame {:id :my.app/main :initial-events [[:app/init]]}}))
```

Alias Freehand as **`v`**. That alias is load-bearing beyond taste: `::v/value`
only reads as `:re-frame.freehand/value` when `v` is the alias in that namespace.

### Why the adapter is still required

Freehand renders views, but re-frame2's **reactive substrate** is a separate
contract, and it is the adapter that fills it. Until `(rf/init! …)` has run,
minting a frame raises `:rf.error/no-adapter-installed`. Freehand's own
interactive browser tests run against the **UIx** adapter, and it is the one to
reach for on a new app; the Reagent and reagent-slim adapters implement the same
contract and work identically if you already have one installed.

Install it once, at boot, before the first frame exists. It is idempotent, so hot
reload is safe.

### Why the frame is seeded at the mount

`v/mount`'s third argument is a closed options map, and `:frame` is the one you
will use on day one. Given a `make-frame` options map carrying `:id`, the root
**ensures** that frame and owns its lifetime; given a bare frame-id keyword, the
root **scopes** a frame something else owns.

The plan runs to completion **before** React is handed anything, so a view whose
body reads a subscription on its first render finds app-db already seeded. That
is what stops the empty-then-flash first paint — you do not need a `dispatch-sync`
before `mount`.

### Teardown

```clojure
(v/unmount! root)
```

`v/mount` returns a root handle. Unmounting releases the root's id, container and
`identifierPrefix` claims, disconnects every view boundary below it, and destroys
the frame **if this root ensured it** — a frame it merely scoped is left alone.
It is guarded rather than throwing: unmounting twice, or unmounting a root a newer
root has superseded, is a no-op.

## Day-one checklist

- `[re-frame.freehand :as v]` in the namespace
- One `(rf/init! …)` at boot, before any frame is minted
- `(v/mount [root {}] el {:frame {:id … :initial-events […]}})`
- `(v/unmount! root)` on teardown

## Two roots on one page

A root's identity is **derived** from the mounted view's registered id, so a single
root authors nothing. Mount the same view twice and the derivation collides — say
so at the second site:

```clojure
(v/mount [panel {:side :left}]  left  {:disambiguator :left})
(v/mount [panel {:side :right}] right {:root-id :shop/right})
```

A root claims its id, its container and its `identifierPrefix` before it renders
anything, so a collision fails loud (`:rf.error/duplicate-root-id`,
`:rf.error/root-container-in-use`) with the roots already on the page untouched.

Re-mounting the **same** root-id into the **same** container re-renders the live
root rather than allocating a second one. That is the hot-reload path: a reload
mints a fresh descriptor for the redefined view, but the qualified id it keys on
does not move, so app-db survives the save.

## Shadow build settings

Interpreted views need no build configuration at all. The **compiled** tier does:
its analyzer and registries run at build time, behind one Shadow hook.

```clojure
;; shadow-cljs.edn
{:build-defaults {:build-hooks [(re-frame.freehand.compiler.build-hook/hook)]}}
```

Configure it once in `:build-defaults` rather than per build. There is no
`:cache-blockers` tax — the hook harvests from cache-durable carriers, so a warm
daemon start reuses Shadow's disk cache.

## Framework views

Two of the descriptors on the door reach into sibling artefacts, and each fails
loudly rather than degrading if that artefact is absent:

| Verb | Needs | Absent |
|---|---|---|
| `v/route-link` | `day8/re-frame2-routing` | `:rf.error/routing-artefact-missing` — never a dead link |
| `v/render-static` | `day8/re-frame2-ssr` at render time | `:rf.error/ssr-artefact-missing`, naming the coordinate |

`re-frame.freehand` takes no compile-time require on either, so a namespace that
requires only Freehand still compiles.

## If something feels wrong

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/no-adapter-installed` | no `rf/init!` before the first frame | install an adapter at boot |
| First paint empty, then values appear | seeding after mount | seed with the mount's `:frame` `:initial-events` |
| `:rf.error/duplicate-root-id` | one view mounted twice | `:disambiguator` or an explicit `:root-id` |
| `::v/value` is not filling | the namespace does not alias Freehand as `v` | `[re-frame.freehand :as v]` |
| Hot reload wipes app-db | remount into a fresh frame | same root-id, same container — the reload re-renders in place |
