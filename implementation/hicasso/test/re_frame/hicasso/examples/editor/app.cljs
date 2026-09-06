(ns re-frame.hicasso.examples.editor.app
  "THE EDITOR'S ENTRY POINT — an adapter, a frame, a root.

  The three lines that start a Hicasso application, and the handle a hot
  reload needs. Nothing here is specific to this application except the
  frame keyword and the seed event.

  ## No route is registered, and that is deliberate

  The slice authoring report's finding 8: route **ids** are namespaced
  keywords and
  cannot collide, but route **paths** are plain strings in a
  process-global registry, and this repository's node test bundle loads
  every application in the tree into ONE process. The slice claimed `/`
  and broke twelve RealWorld assertions with nothing warning.

  A four-field editor needs no routing to be evidence about controlled
  fields, so it registers none. Nothing enforces that absence — the
  `ns` form below is the only record of it — so a route added here in
  future reaches the global registry, and must take a path no other
  application in the tree claims.

  Every other id this application mints IS namespaced and therefore safe
  by construction — the frame keyword below, every event id, every
  subscription id — because `::` resolves to the defining namespace and
  no two namespaces share one. That is the property route paths do not
  have.

  ## Nothing serves this build, and that is a stated limit

  There is no shadow-cljs build id for this application. Its namespaces
  are compiled because `hicasso/test` is a `:source-paths` entry, and its
  suites run on the node and browser lanes — which is the coverage that
  matters. Serving it needs a `:dev-http` entry in
  `implementation/shadow-cljs.edn`, which is a hot-zone file; the
  measurement lane that wants one adds it with the build it needs."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.editor.events :as rf.hicasso.examples.editor.events]
            [re-frame.hicasso.examples.editor.views :as rf.hicasso.examples.editor.views]))

(def frame-id
  "This application's frame. Namespaced, so two applications in one
  process cannot claim it."
  ::frame)

(def initial-events
  "What seeds a fresh frame. Public because every test that mounts this
  application seeds it the same way, and a witness that seeded itself
  differently from the application would be evidence about the witness."
  [[::rf.hicasso.examples.editor.events/seed]])

(defonce ^:private !root
  ;; `defonce`, because a reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to re-render.
  (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload. React reconciles the new
  tree against the one on the page, so the DOM, the subscriptions and the
  caret survive."
  []
  (when-some [root @!root]
    (rf.hicasso/render! root [rf.hicasso.examples.editor.views/editor {}])))

(defn ^:export -main
  "Mount the editor on `#app`.

  `rf/init!` first: `make-frame` raises `:rf.error/no-adapter-installed`
  until a reactive adapter is installed (Spec 006 §Adapter selection at
  boot), and this is an interactive mount, so the headless plain-atom
  adapter will not do — its derived value is not `IWatchable` and a
  moving subscription under it would notify nothing."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events initial-events})
  (reset! !root (rf.hicasso/mount! (js/document.getElementById "app") {:frame frame-id}
                          [rf.hicasso.examples.editor.views/editor {}]))
  nil)
