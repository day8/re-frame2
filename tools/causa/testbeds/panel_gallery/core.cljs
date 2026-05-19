(ns panel-gallery.core
  "Boot for the Causa panel gallery testbed (rf2-sszlr — rebuilt for
  the new 7-tab Causa shape).

  ## What this testbed is

  A visual gallery of the seven L4 tab panels (Event / App-db / Views
  / Trace / Machines / Issues) plus the full 4-layer Causa chrome,
  framed exactly like Storybook frames UI components. Scroll the
  workspace; see what each panel looks like under varying state
  magnitude / payload shape / privacy posture. The gallery IS the
  surface a developer (Mike first) reaches for when asking 'what
  does this look like under load X?'.

  Per `tools/causa/spec/018-Event-Spine.md` the new chrome is four
  stacked layers — top ribbon + event list + tab bar + detail panel
  — with seven L4 tabs replacing the legacy sidebar's 16+ panels.
  Time Travel is folded into the spine.

  ## What this boot does

  1. Initialises re-frame with the Reagent adapter.
  2. Registers Causa's `:rf.causa/*` events / subs / fxs (without
     mounting Causa's own shell — we want the bare panels embedded
     in variants where relevant, plus the shell mounted via the
     chrome gallery's per-variant frame-provider, NOT auto-mounted).
  3. Installs Story's canonical vocabulary (the seven reg-* macros
     and the canonical tags / modes).
  4. Registers testbed-local seed events (notably
     `:panel-gallery.chrome/seed!` — cross-frame writer for the
     chrome gallery; see gallery_chrome.cljs §A note on shared
     :rf/causa state).
  5. Loads the per-tab gallery namespaces (their `register-all!`
     fires at namespace load).
  6. Mounts the Story shell into `#app` on `#/stories`; otherwise
     renders a tiny landing page with a link in.

  ## Why no Causa preload

  The Causa preload (`day8.re-frame2-causa.preload`) does three
  things: register handlers, register the trace collector, and
  auto-open the shell into the host's `[data-rf-causa-host]`. The
  gallery wants only the first; we don't want the shell auto-
  mounting and we don't need live trace collection (variants supply
  synthetic trace events). Calling `register-causa-handlers!`
  directly skips the parts we don't need.

  ## Bundle isolation

  This testbed is dev-only by construction: it `:requires` from
  `tools/causa/src/` and `tools/story/src/`, both of which are
  excluded from production builds via shadow-cljs build gates and
  the bundle-isolation enforcement at
  `implementation/scripts/test-bundle-isolation.sh`. Production app
  code never `:requires` anything under `tools/causa/testbeds/`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-causa.registry :as causa-registry]
            [panel-gallery.panel-views :as panel-views]
            ;; Side-effecting story registrations — namespaces fire
            ;; their `register-all!` at namespace load.
            [panel-gallery.gallery-event]
            [panel-gallery.gallery-app-db]
            [panel-gallery.gallery-views]
            [panel-gallery.gallery-trace]
            [panel-gallery.gallery-machines]
            [panel-gallery.gallery-routing]
            [panel-gallery.gallery-issues]
            [panel-gallery.gallery-chrome]
            ;; Chrome follow-on galleries — rf2-mpn8m settings popup,
            ;; rf2-kbrkx auto-filter pill / edit-popup.
            [panel-gallery.gallery-settings]
            [panel-gallery.gallery-filters]))

;; ============================================================================
;; LANDING — the URL `/` view (no `#/stories` hash)
;; ============================================================================

(defn- landing-view []
  [:div {:class "gallery-landing"}
   [:h1 "Causa panel gallery"]
   [:p "A visual gallery of the new 7-tab Causa chrome (per "
    [:code "tools/causa/spec/018-Event-Spine.md"]
    ") and each of the seven L4 tab panels."]
   [:p "Open the gallery at "
    [:a {:href "#/stories"} [:code "#/stories"]]
    " to scroll the workspaces."]
   [:p {:style {:margin-top "2em" :font-size "13px" :color "#7c8088"}}
    "Each per-tab variant seeds its frame's slots (trace-buffer,
    epoch-history, machine overrides) via real Causa init events.
    The chrome gallery additionally re-seeds the global "
    [:code ":rf/causa"]
    " frame (the chrome's hardcoded frame-provider) via a testbed
    seed event; the chrome workspace uses "
    [:code ":layout :tabs"]
    " so state never bleeds between variants."]])

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
  public surface. Lives here, not in any panel — ZERO source-side
  changes to Causa panels per the rf2-5nvk2 contract (carried into
  rf2-sszlr).

  - `:panel-gallery.chrome/seed!` — chrome gallery cross-frame
    seeder. Story's variant runtime dispatches every `:events`
    entry into the variant frame, but the chrome (`shell/shell-view`)
    hardcodes its own `[rf/frame-provider {:frame :rf/causa}]` —
    so writes to the variant frame are invisible to the chrome.
    This event re-dispatches its payload into `:rf/causa` so the
    chrome variants render their declared state.

    Payload is a map: `{:trace-buffer ... :epoch-history ...
    :selected-tab ... :paused? ... :filters {:in [] :out []}
    :after-seeds [<event-vec> ...]}`. Each key writes the
    corresponding `:rf/causa` slot through the canonical Causa
    event. `:after-seeds` runs additional event vectors against
    `:rf/causa` after the canonical seeds — used by chrome-follow-
    on galleries (Settings / Filters edit-popup) that need to
    dispatch modal-open + section-select events into the same frame."
  []
  (rf/reg-event-fx :panel-gallery.chrome/seed!
    (fn [_cofx [_ {:keys [trace-buffer epoch-history selected-tab
                          paused? filters after-seeds]}]]
      ;; Re-dispatch into `:rf/causa` so the chrome's hardcoded
      ;; frame-provider sees the writes. Each :dispatch-later is
      ;; wrapped in a per-handler {:frame :rf/causa} so the
      ;; canonical handlers operate on the right db.
      (let [seeds
            (cond-> []
              ;; Trace buffer — drives event-list + every tab body
              ;; that derives from cascades.
              (some? trace-buffer)
              (conj [:rf.causa/sync-trace-buffer trace-buffer])

              ;; Epoch history — drives the App-db + Views tabs.
              (some? epoch-history)
              (conj [:rf.causa/sync-epoch-history epoch-history])

              ;; Tab selection.
              selected-tab
              (conj [:rf.causa/select-tab selected-tab])

              ;; Ribbon filters — wholesale overwrite via
              ;; remove-all + add-each. Done in a single dispatch
              ;; per IN / OUT mode by issuing add-filter events.
              true
              (into (when filters
                      (concat
                        (for [pill (:in filters)]
                          [:rf.causa/add-filter :in pill])
                        (for [pill (:out filters)]
                          [:rf.causa/add-filter :out pill]))))

              ;; Paused — toggle from the default :live to
              ;; :live (paused).
              paused?
              (conj [:rf.causa/toggle-live-pause])

              ;; Arbitrary follow-on dispatches into `:rf/causa`.
              ;; The chrome-follow-on galleries (rf2-mpn8m settings,
              ;; rf2-kbrkx auto-filter) use this lane to drive modal-
              ;; open + section-select events against the shared frame.
              (seq after-seeds)
              (into (vec after-seeds)))]
        (doseq [ev seeds]
          (rf/dispatch-sync ev {:frame :rf/causa}))
        {}))))

(defn- ensure-causa-frame!
  "Register the `:rf/causa` frame so the chrome gallery's
  `shell-view` (whose body hardcodes `[rf/frame-provider {:frame
  :rf/causa}]`) can resolve its subscribes without dereferencing
  nil.

  In production Causa lazily registers the frame in
  `mount.cljs/open!` on the first Ctrl+Shift+C — but the gallery
  testbed never opens the production shell, it embeds it through
  the chrome variant directly. Without this defensive reg-frame
  the first chrome variant render throws
  `No protocol method IDeref.-deref defined for type null` because
  the subscribe returns nil against the unregistered frame.
  Idempotent — `reg-frame`'s surgical-update-on-re-register
  semantics mean a second call is a no-op."
  []
  (rf/reg-frame :rf/causa {}))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Causa's :rf.causa/* events / subs / fxs land on the registry once.
  ;; The handlers operate on the current frame's app-db, so each
  ;; variant frame the Story canvas allocates becomes its own
  ;; isolated "Causa frame" for the duration of the variant render.
  ;; The chrome gallery additionally seeds `:rf/causa` via the
  ;; per-variant `:panel-gallery.chrome/seed!` event below — see
  ;; `gallery_chrome.cljs` §A note on shared :rf/causa state.
  (causa-registry/register-causa-handlers!)
  ;; Register `:rf/causa` so the chrome's hardcoded frame-provider
  ;; resolves to a real frame (not nil → throw on subscribe).
  (ensure-causa-frame!)
  (register-testbed-seed-events!)
  ;; Story's canonical vocabulary (seven reg-* macros / tags / modes
  ;; / canvas decorators) installed once at boot. Each
  ;; `gallery_<tab>.cljs` namespace also calls
  ;; `install-canonical-vocabulary!` defensively at registration so
  ;; ns reload after `:after-load` is safe.
  (story/install-canonical-vocabulary!)
  ;; Defensive — register the gallery view-ids even if the gallery
  ;; namespaces loaded before this fn ran (CLJS reload order isn't
  ;; guaranteed at the application level).
  (panel-views/register!)
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
