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
  ;; can ship with. Locale defaults to :en; the editor preference
  ;; doesn't matter because the open-in-editor affordance is dev-only
  ;; (file:// hrefs in a published site are not actionable).
  ;;
  ;; rf2-r1uod — `:project-root` is plumbed here for parity with
  ;; the dev-flavoured `core.cljs` entry. In a published static build
  ;; the open-in-editor chip is effectively dev-only (custom URI
  ;; schemes don't resolve from a published HTML page), so the slot
  ;; is harmless in this entry; mirroring the dev entry's wiring
  ;; keeps the two `run` fns structurally identical and makes future
  ;; "live-on-static" experiments (e.g. a published site that links
  ;; back into the author's editor) trivial.
  (story/configure! {:rf.story/global-args  {:locale :en}
                     :rf.story/project-root "C:/Users/miket/code/re-frame2/tools/story/testbeds"})
  ;; Seed the live-app's :count slot so any embedded `counter-card`
  ;; view that renders under the variant canvas starts from a
  ;; deterministic value rather than `nil`.
  (rf/dispatch-sync [:counter/initialise 5])
  ;; Mount the Story shell directly onto `#app`. No hash routing — this
  ;; ns is the static-export entry only, the SPA lives in core.cljs.
  (story/mount-shell! (js/document.getElementById "app")))
