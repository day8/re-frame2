(ns re-frame.hicasso.consumer-app
  "THE CONSUMER APP — the smallest complete Hicasso application, written
  the way a consumer writes one (rf2-hic-008).

  Phase 0 exits when *a clean consumer can compile and run a minimal view
  without benchmark-tree imports* (product specification §12). Everything
  else this package owns is a test, and no test can answer that question,
  because every one of them is allowed to reach past the door it is
  proving: the package smoke renders through React's server renderer and
  drives it from `impl.codec` and `impl.mount`; the controlled testbed
  hangs a harness door on `window`; the HMR testbed patches React's own
  `useSyncExternalStore` and reads `impl.inventory`. Each reach is correct
  for what it measures and disqualifying for what this file claims.

  This file is allowed nothing. **Its `:require` list is the deliverable**
  — three namespaces, all public, not one of them under
  `re-frame.bench.*` or `tools/`:

  - `re-frame.core`, for the events and subscriptions any re-frame2 app
    has;
  - `re-frame.adapter.uix`, because the reactive substrate wants an
    adapter installed before the first frame is made (Spec 006 §Adapter
    selection at boot) and Hicasso deliberately ships none of its own —
    a consumer picks one, the way they already do for Reagent or UIx
    views;
  - `re-frame.hicasso`, the public door, for `defview`, `sub` and
    `root!`.

  ## Why it is also the release build's entry

  `:hicasso-release` compiles this namespace under `:advanced` with
  `goog.DEBUG` false, and until it existed nothing in the repo had ever
  compiled Hicasso the way a consumer ships it. A namespace that merely
  REQUIRED the door would be no use for that: Closure keeps what is
  reachable and an unused require is not, so the bundle would DCE to
  nearly nothing and report a cost no consumer pays. Everything below is
  reached from the mount instead — a declared view whose body reads a
  subscription, a controlled field that writes back through an intent,
  and a root that owns its frame.

  ## Why it lives under `test/`

  For the reason the sibling `re-frame.freehand.release-app` does:
  `hicasso/deps.edn` publishes `src` and `resources` alone, so a fixture
  that exists to be compiled stays out of the artefact a consumer
  resolves, while shadow-cljs — which carries `hicasso/test` on
  `:source-paths` — can still build it into a real production bundle. It
  is not a test and matches no test regexp: `:node-test-hicasso` selects
  `^re-frame\\.hicasso\\..+-cljs-test$`, and this namespace does not end
  in `-cljs-test`.

  ## The reload hook, and why it is here now (rf2-e2al)

  It was deliberately absent, and its absence was the evidence: writing
  the hook's body on public namespaces only *could not be done*, because
  the door exposed no re-render and its `release!` was a fixture door
  that reset the page-wide runtime and removed the container from the
  document. Rather than paper over that with a hook this file could not
  honestly recommend copying, it was reported.

  [[re-frame.hicasso/render!]] is that door now, so the hook below is
  three ordinary lines and every one of them is public. It re-renders the
  root React already has, which is what lets the reloaded view code meet
  its own DOM; a second `h/root!` would `createRoot` again and replace
  the tree, discarding every node and every scrap of component state.

  The handle is held in a `defonce` for the reason any hot-reloadable app
  holds one: a plain `def` is re-evaluated by the reload, and the handle
  would be replaced by the event it is there to survive.

  This is an exemplar, not a gate. The HMR path is still MEASURED by
  `hicasso/testbed/hmr` under `test:hicasso-hmr`, which drives a real
  shadow-cljs watch and a real reload; inventing a second gate here would
  add machinery without adding a witness."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(def ^:private frame-id ::frame)

;; ---------------------------------------------------------------------------
;; The app's model — ordinary re-frame2, and all of it. The root's frame is
;; seeded through `:initial-events`, so the view's first render reads a value
;; that is already there rather than a nil it has to defend against.
;; ---------------------------------------------------------------------------

(rf/reg-sub ::greeting (fn [db _] (:greeting db)))

(rf/reg-event ::seed (fn [_ _] {:db {:greeting "hello"}}))

(rf/reg-event ::typed
  (fn [{:keys [db]} [_ typed]]
    {:db (assoc db :greeting typed)}))

;; ---------------------------------------------------------------------------
;; The view — one boundary, one read, one intent, one controlled field
;; ---------------------------------------------------------------------------

(h/defview app
  "The whole application.

  The field is controlled in the substrate's own sense: `:value` is the
  subscription and `:on-input` is a plain event vector with `::h/value`
  standing in for what was typed. There is no ref, no `on-change`
  closure, no local draft and no effect reconciling the two — the model
  is the only place the text lives, and the paragraph beneath the field
  reads the same subscription to show it.

  The `<label>` is not decoration either: an interactive element with no
  accessible name is a warning under this package's own clj-kondo export
  (`:re-frame.hicasso/nameless-interactive-element`, rf2-hic-022), and an
  exemplar that trips the lint it ships would be a poor first thing to
  copy."
  [_]
  [:main#hicasso-consumer-app
   [:h1 "Hicasso"]
   [:label {:for "greeting"} "Greeting"]
   [:input#greeting {:type     "text"
                     :value    (h/sub [::greeting])
                     :on-input [::typed ::h/value]}]
   [:p.echo "Committed: " (h/sub [::greeting])]])

;; ---------------------------------------------------------------------------
;; The mount, and the reload
;; ---------------------------------------------------------------------------

(defonce ^:private !root
  ;; `defonce`, because the reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to re-render.
  (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload — the whole hook, and
  the shape to copy.

  Nothing is torn down and nothing is remade: React reconciles the new
  tree against the one on the page, so the DOM, the subscriptions and
  every scrap of component state survive the reload and only the changed
  view code is different."
  []
  (when-some [root @!root]
    (h/render! root [app {}])))

(defn ^:export -main
  "The `:hicasso-release` build's `:init-fn`, and the three lines that
  start a Hicasso application.

  `rf/init!` first, because `make-frame` raises
  `:rf.error/no-adapter-installed` until a reactive adapter is installed
  (Spec 006 §Adapter selection at boot). This is an INTERACTIVE mount — a
  keystroke dispatches an event, app-db changes, and the boundary
  re-renders — so the adapter has to bridge reactive change into React's
  re-render, which the headless plain-atom adapter does not: its derived
  value is not `IWatchable`, and a moving subscription under it would
  notify nothing at all.

  Then the frame, seeded; then the root, which is where a container, a
  frame id and a hiccup tree meet. `h/root!` returns a handle, and the
  handle is what [[reload!]] re-renders — the one reason a mount-once
  application keeps hold of it."
  []
  (rf/init! uix-adapter/adapter)
  (rf/make-frame {:id frame-id :initial-events [[::seed]]})
  (reset! !root (h/root! (js/document.getElementById "app") frame-id [app {}]))
  nil)
