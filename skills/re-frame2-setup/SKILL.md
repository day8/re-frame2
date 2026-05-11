---
name: re-frame2-setup
description: >
  Scaffolds a fresh re-frame2 ClojureScript project from nothing — adds
  the artefact dependencies to deps.edn / package.json, writes a minimal
  shadow-cljs.edn build for a Reagent single-page app, lays down the
  canonical entry namespace shape (with `rf/init!` + the Reagent adapter),
  and walks the author through their first mounted counter. Use this skill
  whenever the user is starting a new re-frame2 project, bootstrapping
  a greenfield CLJS app from scratch, asking how to add re-frame2 to an
  empty repo, or wants to scaffold the smallest working re-frame2 setup
  before writing application code. Once the counter mounts, the user
  switches to the main `re-frame2` skill for code-writing inside the
  project, or `re-frame-pair2` for live-runtime pair-programming.
---

# re-frame2-setup

You are helping an author **bootstrap a fresh re-frame2 ClojureScript project**. The author starts with nothing (or close to nothing — a `deps.edn` they intend to fill in, an empty `package.json`, no source). When you are done, the author has a project that compiles under `shadow-cljs watch`, mounts a working counter in the browser, and is ready for them to switch to the **main `re-frame2` skill** for writing application code.

This skill teaches **only** the re-frame2-specific wiring. It does not teach what `deps.edn`, `npm`, or `shadow-cljs` are — assume the author already knows. It does not teach re-frame2's API (events, subs, machines, schemas, frames, fx, flows, routing, SSR) — that's the main `re-frame2` skill's job.

---

## When to use this skill

Use this skill when **any** of these are true:

- The author has just created a new directory and wants re-frame2 set up in it.
- The author has an existing CLJS project (Reagent or otherwise) but no re-frame2 wiring yet.
- The author says any of: *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"how do I set up re-frame2"*, *"add re-frame2 to my repo"*, *"give me a hello-world re-frame2 app"*.
- A counter / event / sub fails to compile because the build doesn't yet know what `re-frame.core` or `re-frame.adapter.reagent` is.

## When NOT to use this skill

Switch to a different skill when:

- The author has a working re-frame2 project and wants to **write application code** — events, subs, machines, schemas, views, fx. → Use the **`re-frame2`** skill.
- The author has a running re-frame2 app and wants to **inspect / debug / pair with it live** — dispatch from the REPL, walk app-db, trace events, time-travel. → Use the **`re-frame-pair2`** skill.
- The author is **migrating from re-frame v1 to v2**. → See [`MIGRATION`](SKILL-REDIRECT.md) (one of the entries in `SKILL-REDIRECT.md` at the repo root).

If the author asks anything beyond greenfield setup (any re-frame2 API question, any design question, any migration question), say so and point them at the right skill.

---

## Cardinal rules

1. **Never hardcode artefact versions in suggestions you write to disk.** re-frame2 ships ten artefacts in lockstep; versions change. Look them up first (see `reference/deps-versions.md`).
2. **All ten artefacts ship at the same VERSION.** The author picks the version once; every `day8/re-frame2-*` dep gets that same version. Mixing versions across artefacts is unsupported.
3. **Only add the per-feature artefacts the author actually uses.** Core + adapter are mandatory; schemas / machines / routing / flows / http / ssr / epoch are optional. Defer their inclusion until the author asks for the feature.
4. **The Reagent adapter is the default reference substrate.** Unless the author explicitly says UIx or Helix, scaffold against Reagent.
5. **Don't write tests for the author.** This skill stops at "the counter mounts". Anything after that is the main `re-frame2` skill (which itself defers test-writing to the author).

---

## Canonical greenfield path

The author wants a working re-frame2 app from nothing. Walk these steps in order. Each step links to a leaf when depth is useful; otherwise it's self-contained.

### Step 1 — Discover the current artefact versions

re-frame2 ships ten Maven artefacts in lockstep at a single VERSION. Find that VERSION before writing any deps. → See `reference/deps-versions.md` for where to look it up and which artefacts the author needs.

A typical greenfield project needs **only two artefacts on day one**:

- `day8/re-frame2` (the core)
- `day8/re-frame2-reagent` (the Reagent adapter)

Per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) come in **only when the author starts using that feature**.

### Step 2 — Add the deps to `deps.edn`

Drop the two artefacts into `:deps` under their canonical Maven coordinates. The shape is identical to any other tools.deps project — the only re-frame2-specific bit is the artefact names and the lockstep VERSION discipline. → See `reference/deps-versions.md` §`deps.edn`.

### Step 3 — Add the npm deps to `package.json`

The Reagent adapter requires `react` and `react-dom`. shadow-cljs is the build tool. → See `reference/deps-versions.md` §`package.json`.

Run `npm install`.

### Step 4 — Write the `shadow-cljs.edn` build

A re-frame2 single-page app needs **one browser build entry** — a `:target :browser`, an `:init-fn` pointing at `your-app.core/run`, and a `:source-paths` that includes your `src/` tree. No special compiler options for the basic case. → See `reference/shadow-cljs.md` for the exact shape, the optional `:devtools` block for hot-reload, and the index.html that loads the bundle.

### Step 5 — Write the entry namespace

`your-app/core.cljs` needs three re-frame2-specific moves:

1. `:require [re-frame.core :as rf]` — the core API.
2. `:require [re-frame.adapter.reagent :as reagent-adapter]` — the substrate adapter ships its adapter map as a var.
3. Call `(rf/init! reagent-adapter/adapter)` **before any dispatch or render**.

→ See `reference/entry-namespace.md` for the canonical shape, including how `rf/init!` interacts with `rdc/create-root` / `rdc/render`, why `defonce` matters for the React root, and how `(rf/init!)` differs from re-frame v1's implicit boot.

### Step 6 — Write the first counter

A registered event, a registered sub, a registered view (via the `reg-view` macro), and a mount. End-to-end in one file. → See `reference/first-counter.md` for the worked example with explanatory comments. This is the smallest piece of code that exercises every layer (event → handler → app-db change → sub recompute → view re-render).

### Step 7 — Run it and verify

```
npm install            # if you haven't already
npx shadow-cljs watch app
```

Open `http://localhost:<port>/` in a browser (port comes from the `:devtools` block in `shadow-cljs.edn` — typically `:8020`). The counter is visible. Clicking `+` / `-` flips the number. **You're done with setup.**

If the build fails, see *Troubleshooting* below.

---

## Done checklist

Tell the author you're done when **all** of these are true:

- [ ] `deps.edn` lists `day8/re-frame2` and `day8/re-frame2-reagent` at the same VERSION.
- [ ] `package.json` lists `react`, `react-dom`, and `shadow-cljs` (correct versions, see leaf).
- [ ] `npm install` has completed without errors.
- [ ] `shadow-cljs.edn` has exactly one `:builds` entry for the app, with an `:init-fn` pointing at the entry ns.
- [ ] The entry ns calls `(rf/init! reagent-adapter/adapter)` before any render.
- [ ] `shadow-cljs watch <build-id>` compiles cleanly.
- [ ] The browser shows the counter and clicking `+` / `-` updates it.

Then tell the author: *"Setup is done. From here, switch to the **`re-frame2`** skill — it'll teach you re-frame2's API (events, subs, machines, schemas, frames, fx). If you also want to inspect the running app live from the REPL, install **`re-frame-pair2`**."*

---

## Troubleshooting

A few build failures are specific enough to flag here. Everything else, defer to standard CLJS / shadow-cljs / npm guidance — assume the author already knows those tools.

**`Could not locate re-frame/core.cljs`** — the artefact isn't on the classpath. Re-check `deps.edn` (correct artefact name and version) and that `shadow-cljs.edn` reads from `deps.edn` automatically (it does by default; if the author set `:deps {:aliases [...]}` they may have shadowed the default). Run `clj -Stree | grep re-frame2` to confirm the JAR resolved.

**`Could not locate reagent/dom/client.cljs`** — `react` / `react-dom` aren't installed (or the wrong major). `npm install react react-dom`. Reagent 2.x requires React 18+.

**Counter doesn't update on click, no errors** — `(rf/init! reagent-adapter/adapter)` wasn't called, or was called after `rdc/render`. The adapter spec map must be installed before any subscription or render runs. Move `rf/init!` to the top of `run`.

**Browser shows a blank page, no errors in console** — `index.html` is missing `<div id="app">`, or the entry ns is looking up a different id. Mismatch between `(js/document.getElementById "app")` and the actual DOM id is by far the most common cause.

**Build compiles but browser console says `Uncaught ReferenceError: re_frame is not defined`** — the `:init-fn` in `shadow-cljs.edn` doesn't match the entry ns / `run` symbol. shadow-cljs needs to find the export; check it's `(defn ^:export run [] ...)` and that `:init-fn your-app.core/run` matches.

If the author hits anything else, point them at the `re-frame2` skill or `SKILL-REDIRECT.md` (the latter has links to the full spec corpus and guide).

---

## Deep dives — reference files

For depth on any step, read the matching leaf — they're all one level deep so you can read them in full:

- **`reference/deps-versions.md`** — How to discover the current re-frame2 VERSION; the lockstep contract; deciding which per-feature artefacts to pull in on day one; the `deps.edn` and `package.json` shape.
- **`reference/shadow-cljs.md`** — The minimal `shadow-cljs.edn` for a single-page Reagent app; the `index.html` that loads the bundle; `:asset-path` and `:devtools` notes.
- **`reference/entry-namespace.md`** — The canonical `core.cljs` shape; why `rf/init!` must run before any render; the Reagent root pattern (`defonce` + `rdc/create-root`); the order-of-operations contract.
- **`reference/first-counter.md`** — End-to-end worked example: a registered event, a registered sub, a `reg-view`-defined view, and the mount. Mirrors `examples/reagent/counter/core.cljs` in the re-frame2 repo, trimmed for greenfield.

---

## When the author asks anything beyond setup

Setup-out-of-scope questions to re-route:

| Author asks... | Route to... |
|---|---|
| "How do I write a sub that depends on another sub?" | **`re-frame2`** skill |
| "How do I add a state machine?" | **`re-frame2`** skill |
| "How do I structure my events folder?" | **`re-frame2`** skill |
| "I'm migrating from re-frame v1." | `SKILL-REDIRECT.md` → MIGRATION |
| "Can I inspect app-db from the REPL?" | **`re-frame-pair2`** skill |
| "Help me debug why my view isn't re-rendering." | **`re-frame-pair2`** skill |
| "Are there worked examples I can study?" | `SKILL-REDIRECT.md` → Examples directory |
| "Where's the API reference?" | `SKILL-REDIRECT.md` → API |

Don't try to answer these here; this skill is greenfield setup only. Pointing the author at the right next skill is more useful than improvising.
