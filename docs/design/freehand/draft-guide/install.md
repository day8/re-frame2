# Install and boot (target)

You want Freehand on the page: **require → adapter → seed → mount**. This is the
target day-one boot story. Coordinates and exact helper names are still landing —
use the sequence, not guessed package pins.

!!! warning "Pre-alpha"

    Freehand is not yet a “paste this and ship” product. When Maven coordinates and
    the adapter var publish, update this page and drop the placeholders.

## Happy path

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))

(defn ^:export run []
  (rf/init! freehand-adapter)   ; placeholder name — same pattern as other adapters
  ;; Prefer frame preflight so [:app/init] drains before paint (see below).
  (v/mount [app-root {}]
           (js/document.getElementById "app")))

;; later
(v/unmount! mounted)   ; total teardown; not a domain event in the tree
```

Alias Freehand as **`v`**. Prefer `(sub …)` only where the ns `:refer`s it;
otherwise write `(v/sub …)`.

```text
1. Install Freehand adapter  (rf/init! … — same as Reagent/UIx adapters)
2. Seed a live frame before first paint when you care about flash
3. v/mount the root view into a DOM container
4. On leave: v/unmount!
```

You are installing a **view layer**, not a second framework. Upstream re-frame2
stays as it is.

## Day-one checklist

- `[re-frame.freehand :as v]` in the ns  
- Adapter installed once at boot  
- Seed before paint when empty flash matters  
- `(v/mount [root {}] el)` for the paved path  
- `(v/unmount! handle)` on teardown  

### Adapter install

Other re-frame2 view layers use `(rf/init! some-adapter)`. Freehand follows that
pattern. Until the public adapter var ships, treat the name as a **placeholder**:

```clojure
(rf/init! freehand-adapter)
```

Do not invent a second Freehand-only boot protocol.

### Seeding vs mount (do not conflate them)

| Concern | Owner |
|---|---|
| Ordered history / seed events | **Frame preflight** — same idea as `rf/make-frame`’s `:initial-events` in tests |
| Attach a Freehand tree to a DOM node | **`v/mount`** (paved path) |
| Root identity, SSR, advanced create/render/hydrate | **Root Descriptor** family (names land with implementation) |

The spine does **not** define “third argument to `v/mount` carries
`:initial-events`” as the public contract. Prefer:

1. mint/bind the frame with preflight so `[:app/init]` (etc.) drains **before**
   paint, then  
2. `(v/mount [app-root {}] el)`  

Advanced create/render/**hydrate** operations consume the **same** Root
Descriptor shape as the paved mount path and return an opaque handle — exact
helper names land with implementation. Structural tests reuse that plan so boot
does not fork.

### Dev-style sketch (temporary)

Until polished preflight helpers ship, a temporary boot may seed with
`dispatch-sync` (accept a possible empty first paint in dev):

```clojure
(defn ^:export run []
  (rf/init! freehand-adapter)
  (rf/dispatch-sync [:app/init])
  (v/mount [app-root {}]
           (js/document.getElementById "app")))
```

### Production obligations

| Concern | Contract |
|---|---|
| **Seed before paint** | frame preflight / `:initial-events` (or equivalent), not “hope first render seeds” |
| **HMR** | reuse the live frame; do not mint a fresh app-db every save |
| **Multi-root** | supply `:root-id` only when identity could collide |
| **Teardown** | `(v/unmount! handle)` — total host teardown |

## Shadow / build (when available)

Expect a documented Shadow build that:

- puts Freehand on the classpath with re-frame2  
- does **not** pull tools into production bundles  
- keeps test/debug evidence stripable in production  

Exact `shadow-cljs.edn` / deps snippets ship with implementation. Do not invent a
second compiler plugin for Freehand — the design absorbs donor `re-frame.ui`
machinery under Freehand ownership.

## Target API status (names)

| Name | Stability for guide purposes |
|---|---|
| `v/defview`, `v/sub`, event vectors, `::v/value` / `::v/checked` / `::v/key` | Design-stable authoring core |
| `v/mount`, `v/unmount!` | Design-stable paved path |
| Root Descriptor, preflight, advanced create/render/hydrate | Design-stable **contracts**; public helper names may polish |
| `v/check`, `{:compiled true}`, `v/markup` | Design-stable compile story |
| `:children-policy` on descriptor / `defview` options | Design-stable ABI field |
| `v/inspect-boundary`, `v/hot-views`, `v/orphans`, `v/behaviors` | Design-target tool surface; shapes may refine |
| `re-frame.freehand.test` (`t/render`, settle, presence clock) | Design-target test surface |
| Adapter install (`rf/init! …`) | re-frame2 pattern; Freehand adapter var name TBD |
| Frame provider retarget | Design-required **law**; public form unpublished |

## What not to do

| Don’t | Do |
|---|---|
| Design new product on `re-frame.ui` | Freehand absorbs the donor; ui is temporary |
| Put frame ids on raw DOM as the boot path | Mount Freehand roots; frames bind to Freehand trees |
| Treat mount kwargs as the home of seed events | Frame preflight owns ordered seed history |
| Seed only after first paint in production | Preflight before paint |
| Expect Clojars coordinates from this draft alone | Wait for release notes / published install how-to |
