(ns app.stories
  "Pre-authored Story registrations for the golden-path consumer fixture.

  This is the namespace the fixture project's launch alias preloads —
  `:main-opts [\"-e\" \"(require 'app.stories)\" \"-m\"
  \"re-frame.story-mcp.server\"]` — so the story below is already in the
  server JVM's registry when the first MCP connect arrives. It stands in
  for a consuming project's own story namespace.

  Deliberately a HICASSO-substrate story: the body is pure data and
  `:component` is a view-id keyword, so the namespace is CLJ/CLJC-loadable
  in the headless server JVM with no renderer on the classpath. Reagent /
  UIx `.cljs` story files are browser-side registrations — a running
  browser's CLJS registry is reached through re-frame2-pair's `eval-cljs`,
  never through this server.

  Keep load-time printing off stdout: the story-mcp stdio loop owns stdout
  for JSON-RPC frames, so a stray `println` here would corrupt the wire."
  (:require [re-frame.core                 :as rf]
            [re-frame.story                :as rf.story]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; The consuming project's own boot step, and a PRECONDITION of the execution
;; witness below rather than boilerplate. `story-mcp`'s server installs Story's
;; canonical vocabulary but deliberately does NOT choose a reactive substrate —
;; per spec/006 §Adapter selection at boot that choice belongs to the app, and
;; in a browser app it is already made (Reagent / UIx) before any story runs.
;; The headless server JVM has no such prior boot, so a project that wants its
;; variants to RUN there makes the choice here, in the namespace the launch
;; alias preloads. `plain-atom` is the renderer-free substrate for exactly this
;; case.
;;
;; Without it `run-variant` is INERT and silent: setup dispatches reach no
;; adapter, the script plays nothing, and the run still returns `:status
;; "pass"` over an empty app-db and zero assertions — the same success envelope
;; a genuine run returns (measured under rf2-3n3dk). `init!` is idempotent.
(rf/init! rf.substrate.plain-atom/adapter)

;; The fixture project's own event — an ordinary `reg-event`, registered at
;; namespace load exactly like a consuming project's. Pure data → data: no
;; renderer, no substrate, no clock, no I/O, so it runs deterministically on
;; the headless server JVM. The literal it seeds is stated a second time, by
;; hand, in the variant's `:script` assertion below: an expectation that reads
;; its own production site cannot fail, so the two literals are deliberately
;; independent and a drift between them is meant to go red.
(rf/reg-event :fixture-app/seed-article
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:fixture-app/article :headline] "Seeded by app.stories/setup")}))

(rf.story/reg-story :story.fixture-app
  {:doc        "The fixture app's article card — the pre-authored project story the golden-path launch must expose on first connect."
   :component  :fixture-app.views/article-card
   :substrates #{:hicasso}
   :tags       #{:dev}})

;; The variant carries REAL lifecycle work, and that is load-bearing rather
;; than decorative: `end-to-end-project-stories.cjs` phase 5 claims the
;; project-authored variant EXECUTED on first connect, and the only way to
;; witness execution is evidence that cannot exist without it. Story's result
;; contract deliberately grades an assertion-free variant `:pass`
;; (tools/story/src/re_frame/story/result.cljc §Status), so a variant with no
;; :setup and no :script returns the SAME success envelope whether the
;; lifecycle ran or not — which is exactly the false green rf2-3n3dk removed.
;;
;;   phase 2 :setup  — dispatches the fixture project's own event, seeding
;;                     `[:fixture-app/article :headline]` in the frame's app-db
;;   phase 4 :script — asserts that seeded value back out
;;
;; The resulting `:rf.assert/path-equals` record is the execution witness: it
;; is minted by the assertion handler DURING the run, so it is absent if the
;; script never played, and it reads `:passed? false` with `:actual nil` if
;; the setup event never fired. Both halves are pure app-db work — no
;; renderer, no browser — so the headless JVM host runs it as-is.
(rf.story/reg-variant :story.fixture-app/default
  {:doc    "Default article card. :setup seeds the headline the card renders and :script asserts it back — deterministic, renderer-free lifecycle work that gives the golden-path witness something execution-only to observe. :component and :substrates fold down from the parent story."
   :args   {:title "Hello from the fixture project"}
   :setup  [[:fixture-app/seed-article]]
   :script [[:dispatch-sync [:rf.assert/path-equals [:fixture-app/article :headline] "Seeded by app.stories/setup"]]]
   :tags   #{:dev}})
