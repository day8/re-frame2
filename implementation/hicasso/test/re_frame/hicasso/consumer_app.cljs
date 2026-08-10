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

  ## What is deliberately absent

  A `^:dev/after-load` hook. The HMR path is demonstrated by
  `hicasso/testbed/hmr` under the `test:hicasso-hmr` gate, which drives a
  real shadow-cljs watch and a real reload; inventing a second one here
  would add machinery without adding a witness. Writing the hook's body
  on public namespaces only is a separate matter and currently cannot be
  done — the door exposes no re-render, and its `release!` is a fixture
  door that resets the page-wide runtime and removes the container from
  the document. That is reported as a follow-up rather than papered over
  with a hook this file cannot honestly recommend copying."
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
;; The mount
;; ---------------------------------------------------------------------------

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
  frame id and a hiccup tree meet. `h/root!` returns a handle, and this
  app has no use for one: it mounts once and lives for the life of the
  page."
  []
  (rf/init! uix-adapter/adapter)
  (rf/make-frame {:id frame-id :initial-events [[::seed]]})
  (h/root! (js/document.getElementById "app") frame-id [app {}])
  nil)
