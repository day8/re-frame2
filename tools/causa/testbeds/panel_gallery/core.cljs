(ns panel-gallery.core
  "Boot for the Causa panel gallery testbed (rf2-1o7mp).

  ## What this testbed is

  A visual gallery of Causa panel states, framed exactly like
  Storybook frames UI components: scroll the workspace, see what each
  panel looks like with 0 / 3 / 30 / 300 cascades, with the redacted
  marker present, with the large-elision marker, with errors. The
  gallery IS the surface a developer (Mike first) reaches for when
  asking 'what does this panel look like under load X?'. There is no
  CI gate at this stage; the panel rendering without throwing is
  itself the cheap regression check Story's variant lifecycle gives
  for free.

  See `ai/findings/story-on-causa-feasibility-2026-05-16.md` for the
  full panel coverage plan (8 panels, ~80 variants total). This
  testbed lands the first panel — event-detail, 12 variants — under
  Phase 1a (rf2-1o7mp).

  ## What this boot does

  1. Initialises re-frame with the Reagent adapter.
  2. Registers Causa's `:rf.causa/*` events / subs / fxs (without
     mounting Causa's own shell — we want the bare panels embedded in
     variants, not the host's overlay).
  3. Installs Story's canonical vocabulary (the seven reg-* macros and
     the canonical tags / modes).
  4. Loads the per-panel stories namespaces (their `register-all!`
     fires at namespace load).
  5. Mounts the Story shell into `#app` on `#/stories`; otherwise
     renders a tiny landing page with a link in.

  ## Why no Causa preload

  The Causa preload (`day8.re-frame2-causa.preload`) does three
  things: register handlers, register the trace collector, and
  auto-open the shell into the host's `[data-rf-causa-host]`. The
  gallery wants only the first; we don't want the shell auto-mounting
  and we don't need live trace collection (variants supply synthetic
  trace events). Calling `register-causa-handlers!` directly skips the
  parts we don't need.

  ## Bundle isolation

  This testbed is dev-only by construction: it `:requires` from
  `tools/causa/src/` and `tools/story/src/`, both of which are
  excluded from production builds via shadow-cljs build gates and the
  bundle-isolation enforcement at `implementation/scripts/test-bundle-
  isolation.sh`. Production app code never `:requires` anything under
  `tools/causa/testbeds/`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-causa.registry :as causa-registry]
            [panel-gallery.gallery-views :as gallery-views]
            ;; Side-effecting story registrations — namespaces fire
            ;; their `register-all!` at load time.
            [panel-gallery.event-detail-stories]
            [panel-gallery.app-db-diff-stories]
            [panel-gallery.subscriptions-stories]))

;; ============================================================================
;; LANDING — the URL `/` view (no `#/stories` hash)
;; ============================================================================

(defn- landing-view []
  [:div {:class "gallery-landing"}
   [:h1 "Causa panel gallery"]
   [:p "A visual gallery of Causa's panels under varying state magnitude."
    " Phase 1a (rf2-1o7mp) ships the event-detail panel; Phase 2 adds the
    remaining seven panels."]
   [:p "Open the gallery at "
    [:a {:href "#/stories"} [:code "#/stories"]]
    " to scroll the workspace."]
   [:p {:style {:margin-top "2em" :font-size "13px" :color "#7c8088"}}
    "Each variant seeds its frame's "
    [:code ":trace-buffer"]
    " (and selection slots where relevant) by firing real Causa init
    events. The panel renders bare in a card — Causa's own shell is
    not mounted. Frame isolation: every variant has its own state."]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce ^:private app-root (atom nil))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (js/document.getElementById "app"))))
  @app-root)

(defn- tear-down-app-root! []
  (when-let [root @app-root]
    (try (rdc/unmount root) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-landing! []
  (story/unmount-shell!)
  (ensure-app-root!)
  (rdc/render @app-root [landing-view]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (js/document.getElementById "app")))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-landing!))))

(defn- register-testbed-seed-events!
  "Register testbed-local seed events that don't exist on Causa's
  public surface. Currently exposes ONE event,
  `:panel-gallery/seed-sub-cache-and-errors`, used by the
  subscriptions-stories `error` variant — Causa registers
  `:rf.causa/set-sub-cache-override-for-test` for the cache half but
  has no public init event for `:sub-error-cache` (the runtime fills
  it from the trace bus). The testbed event seeds both slots in one
  `assoc` so Story's lifecycle slots are preserved per
  `tools/story/spec/002-Runtime.md` §Coexistence with hosting
  application state. Lives here, not in any panel — ZERO source-side
  changes to Causa panels per the rf2-5nvk2 contract."
  []
  (rf/reg-event-db :panel-gallery/seed-sub-cache-and-errors
    (fn [db [_ sub-cache error-cache]]
      (-> db
          (assoc :sub-cache-override sub-cache)
          (assoc :sub-error-cache    error-cache)))))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Causa's :rf.causa/* events / subs / fxs land on the registry once.
  ;; The handlers operate on the current frame's app-db, so each
  ;; variant frame the Story canvas allocates becomes its own isolated
  ;; "Causa frame" for the duration of the variant render.
  (causa-registry/register-causa-handlers!)
  (register-testbed-seed-events!)
  ;; Story's canonical vocabulary (seven reg-* macros / tags / modes /
  ;; canvas decorators) installed once at boot. Each
  ;; `<panel>_stories.cljs` namespace also calls
  ;; `install-canonical-vocabulary!` defensively at registration so
  ;; ns reload after `:after-load` is safe.
  (story/install-canonical-vocabulary!)
  ;; Defensive — register the gallery view-id even if the stories ns
  ;; loaded before this fn ran (CLJS reload order isn't guaranteed at
  ;; the application level).
  (gallery-views/register!)
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
