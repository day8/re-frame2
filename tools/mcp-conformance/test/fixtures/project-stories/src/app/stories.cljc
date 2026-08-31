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
  (:require [re-frame.story :as story]))

(story/reg-story :story.fixture-app
  {:doc        "The fixture app's article card — the pre-authored project story the golden-path launch must expose on first connect."
   :component  :fixture-app.views/article-card
   :substrates #{:hicasso}
   :tags       #{:dev}})

(story/reg-variant :story.fixture-app/default
  {:doc  "Default article card. No :setup and no :script, so the variant is vacuously runnable on the headless JVM host; :component and :substrates fold down from the parent story."
   :args {:title "Hello from the fixture project"}
   :tags #{:dev}})
