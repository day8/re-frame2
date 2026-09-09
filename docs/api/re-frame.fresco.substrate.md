# re-frame.fresco.substrate

Fresco's own reactive-substrate adapter — the value an application installs with
`(rf/init! substrate/adapter)` before it mounts anything.

```clojure
(:require [re-frame.fresco.substrate :as substrate])
```

**An adapter is not a renderer.** Fresco already renders itself, through
`react-dom/client`, and interprets its own Hiccup; what the substrate contract
asks for is the *observation* half — the container `app-db` lives in, and a
derived value that says when it moved. Owning both halves is what lets a Fresco
application depend on core plus Fresco and nothing else: without this namespace
an interactive one would have to add a **second** dependency coordinate purely to
obtain the second half, then never write a line of that substrate's notation.

This is an **option, not a replacement**. Reagent, reagent-slim and UIx remain
first-class, independently supported adapters, and installing one of them under a
Fresco tree is supported — a Fresco subtree and a UIx subtree resolve the same
frame, because every React-shaped adapter reads the one shared context object.

**An optional module, on purpose.** Nothing under the artefact's `src/` requires
this namespace, so a build that never asks for it carries neither it nor the
spine. That is what keeps the cost off an application that deliberately installs
Reagent instead, and it is why the adapter is not re-exported from the public
door.

Installation is taught in
[Installation](../core/fresco/00-installation.md#fresco-needs-a-substrate-adapter);
the shipped alternatives and their coordinates are in
[Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md).

## Adapter spec

### `adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/fresco
   :make-state-container      …
   :read-container            …
   :replace-container!        …
   :subscribe-container       …
   :make-derived-value        …
   :render                    …
   :render-to-string          …
   :register-context-provider …
   :flush-render!             …
   :dispose-adapter!          …}
  ```
- **Description**: The Fresco adapter map — the substrate contract plus
  `:kind :rf.adapter/fresco`. Install it before the first frame exists.
  Installation is explicit and there is no default-adapter registry, so a Fresco
  application that installs Reagent or UIx instead keeps working and never reaches
  this namespace.
  - It stands on `re-frame.substrate.spine`, core's shared spine for React-shaped
    adapters that lack a native reactive-atom primitive. The spine's
    `make-derived-value` wires **one watch per source at construction** and
    coalesces through a per-adapter epoch scheduler, so a derived value is live
    the moment it exists.
  - That push-from-birth property is the one Fresco's collector needs.
    `re-frame.substrate.plain-atom` cannot give it: its derived value registers no
    watch, so the runtime paints once and is deaf thereafter — which is why the
    headless adapter is right for an SSR render and wrong under a live view.
  - It takes no new dependency: Fresco already requires `react`, and the spine
    already lives in core.
  - Asking for a state container before `init!` throws
    `:rf.error/no-adapter-installed`.
- **Example**:
  ```clojure
  (require '[re-frame.core :as rf]
           '[re-frame.fresco :as h]
           '[re-frame.fresco.substrate :as substrate])

  (rf/init! substrate/adapter)
  (h/render! app-root [h/frame-root {:id :app/main} [app]]
             (js/document.getElementById "app"))
  ```

## See also

- [Installation](../core/fresco/00-installation.md#fresco-needs-a-substrate-adapter)
  — the chapter that teaches the boot line
- [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md) /
  [`re-frame.adapter.uix`](re-frame.adapter.uix.md) — the alternatives, on the
  same contract
- [`re-frame.fresco`](re-frame.fresco.md) — the door
