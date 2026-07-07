# Images (EP-0023 — the multi-frame public model)

## When to load

Deciding whether a multi-surface page, tool-beside-target mount, progressive doc example, library package, or isolated test/story frame needs an explicit `rf/image`; authoring `rf/image` values, `make-frame :images`, or an image hot-reload (re-`make-frame`); or answering "can two frames share registration ids?". For frame lifetimes, scoping, and the `frame-provider` shapes, see [`frames.md`](frames.md) — this leaf covers the *registration-set* half of the multi-frame story.

## The mental model: `image -> frame -> event stream`

Like a VM. An **image** is the *selected registration set* a frame runs (instruction set); a **frame** is the *isolated execution context* (its memory + the one image generation it resolves against); the **event stream** is the ordered events a frame processes over its life (the program). Events are instructions, the six-domino cascade is the ISA, your `reg-*` forms supply the instruction meanings, the image is the loaded instruction set, the frame is the VM executing the stream.

The everyday rule that falls out:

```text
same behaviour, different memory  -> same image, different frames
different behaviour               -> different images
```

You almost certainly do not need to name an image. The two facts an author should hold:

- **The ordinary `reg-*` path is unchanged — the default image is implicit.** `reg-*` writes to the process-wide registration source; a frame created with no explicit `:images` resolves against the *default image* projected over that source. A single-frame app never spells `image` or `make-frame` `:images`; the zero-ceremony path stays zero-ceremony. The image concept becomes visible only when the default process-wide registration set stops being the right boundary.
- **Registration ids are scoped to an image; frame ids are process-local.** Two images may both contain `:counter/inc`; two live frames may **not** both register as `:counter/main`. That split is the heart of the multi-frame story: a docs page can reuse teaching-friendly registration ids across examples, while each mounted example still needs a distinct frame id. (An anonymous `make-frame` value — created with no `:id` — is born and dies in a test/harness scope without claiming a name in the frame registry.)

**When you reach for explicit images:** two unrelated surfaces on one page (a todo surface beside a counter, each with its own local ids), a tool surface beside the thing it inspects (so their ids never collide), progressive doc examples that reuse one teaching vocabulary, library packaging, and isolated test/story frames. Each case is "different instruction set, isolated memory" — so each gets its own image, and each live instance gets its own frame.

## The landed public surface

`rf/image` is exported on `re-frame.core` today:

```clojure
(rf/image {:id        :todo/image                          ;; optional
           :select-ns {:include ["todo.**"]                ;; source-ns globs
                       :exclude ["todo.dev.*"]}            ;; optional subtraction
           :registrations {…}})                            ;; optional inline sections
;; => an INERT image value — pure data, no registrar, no side effect
```

- `:select-ns :include` selects already-loaded registrations by their *source* namespace (`:rf.provenance/ns`), **not** by the registration-id namespace. Glob grammar: `*` (one segment) / `**` (zero or more), case-sensitive, whole-namespace match. A pattern that matches **zero** descriptors fails image assembly loud; the optional `:exclude` leg subtracts.
- Inline `:registrations` (registrar-keyed sections mirroring `:reg-event` / `:reg-sub` / …) round out the spec map — `:id` / `:select-ns` / `:registrations` are the only three public keys (EP-0026).
- There are no `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` / `:rf.image/requires` keys — passing them fails loud.

**Constructing image-loaded frames.** Frame creation resolves one or more image values (always supplied as a vector under `:images`) into one sealed **image generation** the frame runs. Composition resolves by **image order** — the later image wins; read what it shadowed via `rf/frame-shadows`. `make-frame` takes `:images` alongside its record-config opts; re-calling `make-frame` against the SAME `:id` with a new `:images` vector swaps a live frame's image generation in place, preserving frame memory (no dedicated reload verb, rf2-lxwpob). Frame lifetimes are otherwise unchanged — `reg-frame` + `frame-provider {:frame …}` at the root, `frame-provider {:id …}` for a view-driven named frame, `make-frame` + `destroy-frame!` when a component owns teardown (see [`frames.md` §The merged `frame-provider` in views](frames.md#the-merged-frame-provider-in-views-ep-0024)).

## Frame isolation is the whole isolation story

You target a **frame** — a process-local frame id in mounted code, or a direct frame value from `make-frame` in tests/harnesses (EP-0024). The frame determines the image generation used for registration resolution; image assembly plus frame isolation are everything. There is **no public realm / app / module composition vocabulary**: a single-product SPA targets its one frame; a multi-frame app reaches for explicit `rf/image` values, not a container address. There is no `rf/migration-map` / `rf/migration-explain` data surface — those names do not exist.

Frame ids are **process-local and unique** — two live frames may not both claim `:counter/main` (registration ids, by contrast, are image-scoped and may repeat across images — see above). A frame id already live elsewhere surfaces as a loud error rather than a silent collision; the fix is distinct frame ids, or a direct frame value kept in local scope.

## Composing patterns

- **Override behaviour through a later image, not a global install** — compose a small overrides image *after* the app image (its `:registrations` shadow the earlier ones; image order decides), then read `rf/frame-shadows` to assert exactly what it overrode. The canonical test recipe is [`../cross-cutting/testing.md` §Behaviour isolation in tests](../cross-cutting/testing.md#behaviour-isolation-in-tests--image-not-a-global-install).
- **Isolate *behaviour* with a later overrides image; isolate *state* with a fresh frame.** A frame created with no `:images` resolves against the shared registrar.

## Deeper material

The image-generation seal, re-`make-frame` hot-reload semantics, and the EP-0026 spec-map contract: `SKILL-REDIRECT.md` → **EP — Image-loaded frames (023)**, **EP — Image spec (026)**.

---

*Derived from `re-frame.core` (`image`, `make-frame`, `frame-shadows`, `generation-diff`) @ main `89bd9c3`. Re-verify after image-assembly or frame-constructor changes.*
