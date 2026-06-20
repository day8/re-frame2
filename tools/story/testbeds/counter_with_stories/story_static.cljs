(ns counter-with-stories.story-static
  "Static-export entry point — per tools/story/spec/013-Static-Build.md.

  The canonical counter-with-stories example ships with two entry
  points:

  - `core.cljs` (build `:examples/counter-with-stories`) — the
    development-flavoured hash-routed SPA. `#/` renders the live counter;
    `#/stories` mounts the Story shell. This is what `shadow-cljs watch`
    serves and what the Playwright spec drives.

  - `story-static.cljs` (this ns; build `:story-static/counter-with-
    stories`) — the **static-export entry point**. Mounts the Story
    shell directly (no hash routing, no live-counter view). The build
    runs under `:advanced` + `:closure-defines
    {re-frame.story.config/static-mode? true}` so the shell drops its
    dev-time affordances (registrar-fingerprint poll, first-visit help
    auto-open) and the bundle is suitable for publishing to GitHub
    Pages / Netlify / S3.

  This entry-point is the sanity-test rig for the `story:build`
  invocation; the published bundle for the canonical counter-with-
  stories example is the artefact a downstream consumer can clone,
  point at their own stories ns, and re-run."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Source the stories ns so all `reg-*` calls fire on namespace
            ;; load. The variant bodies reference views / events / subs by
            ;; id; the stories ns transitively requires them.
            [counter-with-stories.stories]))

(defn ^:export run
  "Mount the Story shell at `#app`. Idempotent on hot-reload (which
  doesn't happen under `release` builds, but the path is correct under
  `compile` too)."
  []
  (rf/init! reagent-adapter/adapter)
  ;; No explicit `(story/install-canonical-vocabulary!)` call — the
  ;; `:require [counter-with-stories.stories]` above already loaded the
  ;; stories ns, whose first `reg-*` call auto-installed the canonical
  ;; vocabulary per rf2-p1ydc (audit D-2 / rf2-y8gag).
  ;; Story global configuration — pinned defaults a published docs site
  ;; can ship with. Locale defaults to :en.
  ;;
  ;; NO `:rf.story/project-root` here. The project-root is a DEV-time
  ;; affordance — it builds an absolute on-disk `editor://` URI for the
  ;; open-in-editor chip, which (a) doesn't resolve from a published HTML
  ;; page and (b) would bake the BUILD machine's checkout root into a
  ;; publicly-served bundle. A static export must be self-contained, so it
  ;; does NOT inherit the dev testbed root helper
  ;; (`re-frame.testbed.config/resolve-source-root`, which is for dev
  ;; testbeds) — and the framework guard in `re-frame.story.config/
  ;; set-project-root!` fails closed in `static-mode?` regardless. A
  ;; downstream "published site that links back into the author's editor"
  ;; opts in explicitly via `config/set-allow-static-project-root!` +
  ;; passing a root; this canonical export does not.
  (story/configure! {:rf.story/global-args {:locale :en}})
  ;; NO shell-level `(rf/dispatch-sync [:counter/initialise 5])` here.
  ;; A bare global dispatch carries no frame context, and per EP-0002 the
  ;; runtime never synthesises a `:rf/default` frame — so the router throws
  ;; `:rf.error/no-frame-context` at `build-envelope`, which under `:advanced`
  ;; minified to the `jj` pageerror that aborted the mount and left the shell
  ;; unrendered (rf2-oki92v). The seed was also redundant: every counter
  ;; variant renders inside its OWN isolated variant frame and seeds its
  ;; `:count` slot via that variant's `:setup [[:counter/initialise N]]`
  ;; (stories.cljs). There is no shell-level live-counter view to seed — this
  ;; entry mounts only the Story shell — so the dispatch had no legitimate
  ;; target. Frames are isolated contexts; the static shell holds no app-db
  ;; of its own to seed.
  ;; Mount the Story shell directly onto `#app`. No hash routing — this
  ;; ns is the static-export entry only, the SPA lives in core.cljs.
  (story/mount-shell! (js/document.getElementById "app")))
