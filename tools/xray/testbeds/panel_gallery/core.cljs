(ns panel-gallery.core
  "Boot for the Xray panel gallery testbed (rf2-sszlr — rebuilt for
  the new 7-tab Xray shape).

  ## What this testbed is

  A visual gallery of the seven L4 tab panels (Event / App-db / Views
  / Trace / Machines / Issues) plus the full 4-layer Xray chrome,
  framed exactly like Storybook frames UI components. Scroll the
  workspace; see what each panel looks like under varying state
  magnitude / payload shape / privacy posture. The gallery IS the
  surface a developer (Mike first) reaches for when asking 'what
  does this look like under load X?'.

  Per `tools/xray/spec/018-Event-Spine.md` the new chrome is four
  stacked layers — top ribbon + event list + tab bar + detail panel
  — with seven L4 tabs replacing the legacy sidebar's 16+ panels.
  Time Travel is folded into the spine.

  ## What this boot does

  1. Initialises re-frame with the Reagent adapter.
  2. Registers Xray's `:rf.xray/*` events / subs / fxs (without
     mounting Xray's own shell — we want the bare panels embedded
     in variants where relevant, plus the shell mounted via the
     chrome gallery's per-variant frame-provider, NOT auto-mounted).
  3. Installs Story's canonical vocabulary (the seven reg-* macros
     and the canonical tags / modes).
  4. Registers testbed-local seed events (notably
     `:panel-gallery.chrome/seed!` — cross-frame writer for the
     chrome gallery; see gallery_chrome.cljs §A note on shared
     :rf/xray state).
  5. Loads the per-tab gallery namespaces (their `register-all!`
     fires at namespace load).
  6. Mounts the Story shell into `#app` on `#/stories`; otherwise
     renders a tiny landing page with a link in.

  ## Why no Xray preload

  The Xray preload (`day8.re-frame2-xray.preload`) does three
  things: register handlers, register the trace collector, and
  auto-open the shell into the host's `[data-rf-xray-host]`. The
  gallery wants only the first; we don't want the shell auto-
  mounting and we don't need live trace collection (variants supply
  synthetic trace events). Calling `register-xray-handlers!`
  directly skips the parts we don't need.

  ## Bundle isolation

  This testbed is dev-only by construction: it `:requires` from
  `tools/xray/src/` and `tools/story/src/`, both of which are
  excluded from production builds via shadow-cljs build gates and
  the bundle-isolation enforcement at
  `implementation/scripts/test-bundle-isolation.sh`. Production app
  code never `:requires` anything under `tools/xray/testbeds/`."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-2c5xb — Xray's `configure!` to seed `:project-root`
            ;; so the source-coord chips on registered handlers /
            ;; views / machines resolve their classpath-relative
            ;; `:file` slot to an absolute on-disk URI. Without this
            ;; the panel-gallery's `[open]` affordances ship the bare
            ;; classpath-relative path (`panel_gallery/foo.cljs`) to
            ;; the OS scheme handler, which rejects it.
            ;;
            ;; rf2-ymnfx (Issue A) — Story's parallel slot
            ;; (`:rf.story/project-root`) is seeded via
            ;; `story/configure!` below so the Story variant-toolbar
            ;; 'Open' button (which reads `re-frame.story.config/
            ;; project-root`, not Xray's slot) also ships an absolute
            ;; on-disk path. The Story → Xray bridge in
            ;; `xray-preset/propagate-project-root!` would otherwise
            ;; mean the Story configure! call ALSO writes Xray's slot;
            ;; we keep the explicit xray-config call so the load order
            ;; is unambiguous (Xray seeded first; Story's idempotent
            ;; re-seed of Xray is a no-op via `reset!`).
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.registry :as xray-registry]
            ;; rf2-pqulr — Xray's `:root` CSS-variable installer. Required
            ;; here because the panel-gallery embeds bare Xray widgets
            ;; without mounting the Xray shell; the shell normally calls
            ;; `global-styles/install!` from its `shell-view` reg-view body.
            ;; Without this call the tokens in
            ;; `day8.re-frame2-xray.theme.tokens` resolve their
            ;; `var(--rf-xray-*)` references to CSS fallback defaults and
            ;; every variant paints unstyled.
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [panel-gallery.panel-views :as panel-views]
            ;; Side-effecting story registrations — namespaces fire
            ;; their `register-all!` at namespace load.
            ;; (rf2-5gl5r — `gallery-event` removed alongside the
            ;; retired Event/Handler panel; the Epoch gallery is the
            ;; canonical surface.)
            [panel-gallery.gallery-app-db]
            [panel-gallery.gallery-epoch]
            [panel-gallery.gallery-views]
            [panel-gallery.gallery-trace]
            [panel-gallery.gallery-machines]
            [panel-gallery.gallery-routing]
            [panel-gallery.gallery-issues]
            [panel-gallery.gallery-chrome]
            ;; Chrome follow-on galleries — rf2-mpn8m settings popup,
            ;; rf2-kbrkx auto-filter pill / edit-popup.
            [panel-gallery.gallery-settings]
            [panel-gallery.gallery-filters]
            ;; Widget galleries — rf2-hp4ow edn-inspector isolation
            ;; coverage. The widget is exercised indirectly by every
            ;; L4 panel that mounts it; this gallery gives it a
            ;; dedicated harness with one variant per input shape /
            ;; opts combination.
            [panel-gallery.gallery-edn-inspector]
            ;; rf2-n2jig — mode-3 diff grammar Story set (R1-R8 +
            ;; combination + edge + theme/density). Shares the
            ;; `:panel-gallery.edn-inspector/fixture` slot + Panel
            ;; mount with the edn-inspector gallery above; the
            ;; variants pass `:full-with-diff? true` to opt into
            ;; the mode-3 chrome (R3 chip + R4 rail).
            [panel-gallery.gallery-diff-mode-3]))

;; ============================================================================
;; LANDING — the URL `/` view (no `#/stories` hash)
;; ============================================================================

(defn- landing-view []
  [:div {:class "gallery-landing"}
   [:h1 "Xray panel gallery"]
   [:p "A visual gallery of the new 7-tab Xray chrome (per "
    [:code "tools/xray/spec/018-Event-Spine.md"]
    ") and each of the seven L4 tab panels."]
   [:p "Open the gallery at "
    [:a {:href "#/stories"} [:code "#/stories"]]
    " to scroll the workspaces."]
   [:p {:style {:margin-top "2em" :font-size "13px" :color "#7c8088"}}
    "Each per-tab variant seeds its frame's slots (trace-buffer,
    epoch-history, machine overrides) via real Xray init events.
    The chrome gallery additionally re-seeds the global "
    [:code ":rf/xray"]
    " frame (the chrome's hardcoded frame-provider) via a testbed
    seed event; the chrome workspace uses "
    [:code ":layout :tabs"]
    " so state never bleeds between variants."]])

;; ============================================================================
;; MOUNT
;; ============================================================================

;; ----------------------------------------------------------------------------
;; Project-root resolution (rf2-2c5xb)
;; ----------------------------------------------------------------------------
;;
;; Source-coord `:file` slots captured at registration time are classpath-
;; relative — the panel-gallery's source root is
;; `tools/xray/testbeds/panel_gallery/`, so a coord on a panel-gallery handler
;; carries `:file "panel_gallery/foo.cljs"`. Xray's open-in-editor URI builder
;; (`re-frame.source-coords.editor-uri/editor-uri`) requires an on-disk root
;; to prepend — without it, the URI ships the bare relative path and the
;; OS-side editor handler rejects it ("Path does not exist").
;;
;; Per the rf2-5m5n2 contract Xray exposes `:rf.xray/project-root`. The
;; panel-gallery boots with its known on-disk repo position by default; a
;; `?project-root=<path>` query string overrides for cross-machine portability
;; (mirroring the `button_deck` testbed's resolver).
;;
;; The URI build is invariant to the host page URL — `resolve-uri` reads the
;; configured root, not `window.location`. The query-param branch only
;; influences which root we PASS to the configure! call; the resolver itself
;; never reaches into the document. The unit test
;; `panel-gallery-project-root-invariant-to-host-url` pins that contract.

(def ^:private default-project-root
  "Default on-disk root for the panel-gallery testbed. Matches the source-
  path declared in `implementation/shadow-cljs.edn` for the
  `:testbeds/panel-gallery` build."
  "C:/Users/miket/code/re-frame2/tools/xray/testbeds")

(defn- query-param
  "Return the named URL query param as a string, or nil when absent /
  blank. Pure data helper — kept private to this testbed since the
  query-string override is a per-host knob (not a Xray-API surface)."
  [param-name]
  (when (exists? js/window)
    (let [params (-> js/window .-location .-search (js/URLSearchParams.))
          v      (.get params param-name)]
      (when (and (string? v) (seq v)) v))))

(defn- resolve-project-root []
  (or (query-param "project-root") default-project-root))

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
  "Register testbed-local seed events that don't exist on Xray's
  public surface. Lives here, not in any panel — ZERO source-side
  changes to Xray panels per the rf2-5nvk2 contract (carried into
  rf2-sszlr).

  - `:panel-gallery.chrome/seed!` — chrome gallery cross-frame
    seeder. Story's variant runtime dispatches every `:events`
    entry into the variant frame, but the chrome (`shell/shell-view`)
    hardcodes its own `[rf/frame-provider {:frame :rf/xray}]` —
    so writes to the variant frame are invisible to the chrome.
    This event re-dispatches its payload into `:rf/xray` so the
    chrome variants render their declared state.

    Payload is a map: `{:trace-buffer ... :epoch-history ...
    :selected-tab ... :paused? ... :filters {:in [] :out []}
    :after-seeds [<event-vec> ...]}`. Each key writes the
    corresponding `:rf/xray` slot through the canonical Xray
    event. `:after-seeds` runs additional event vectors against
    `:rf/xray` after the canonical seeds — used by chrome-follow-
    on galleries (Settings / Filters edit-popup) that need to
    dispatch modal-open + section-select events into the same frame."
  []
  (rf/reg-event-fx :panel-gallery.chrome/seed!
    (fn [_cofx [_ {:keys [trace-buffer epoch-history selected-tab
                          paused? filters after-seeds]}]]
      ;; rf2-1w07r — re-dispatch into the SURROUNDING frame (the Story
      ;; per-variant frame this seed event was dispatched under), which
      ;; is the SAME frame the chrome cell now threads into
      ;; `[shell/shell-view {:frame-id …}]` (panel_views.cljs). The
      ;; seeds therefore land where the shell reads, per-variant — no
      ;; longer pinned to a global `:rf/xray` literal. (`current-frame`
      ;; in an event handler resolves to the dispatching frame.)
      (let [seed-frame (rf/current-frame)
            seeds
            (cond-> []
              ;; Trace buffer — drives event-list + every tab body
              ;; that derives from cascades.
              (some? trace-buffer)
              (conj [:rf.xray/sync-trace-buffer trace-buffer])

              ;; Epoch history — drives the App-db + Views tabs.
              (some? epoch-history)
              (conj [:rf.xray/sync-epoch-history epoch-history])

              ;; Tab selection.
              selected-tab
              (conj [:rf.xray/select-tab selected-tab])

              ;; Ribbon filters — wholesale overwrite via
              ;; remove-all + add-each. Done in a single dispatch
              ;; per IN / OUT mode by issuing add-filter events.
              true
              (into (when filters
                      (concat
                        (for [pill (:in filters)]
                          [:rf.xray/add-filter :in pill])
                        (for [pill (:out filters)]
                          [:rf.xray/add-filter :out pill]))))

              ;; Paused — toggle from the default :live to
              ;; :live (paused).
              paused?
              (conj [:rf.xray/toggle-live-pause])

              ;; Arbitrary follow-on dispatches into the surrounding
              ;; frame. The chrome-follow-on galleries (rf2-mpn8m
              ;; settings, rf2-kbrkx auto-filter) use this lane to drive
              ;; modal-open + section-select events against the cell's
              ;; own frame.
              (seq after-seeds)
              (into (vec after-seeds)))]
        (doseq [ev seeds]
          (rf/dispatch-sync ev {:frame seed-frame}))
        {}))))

(defn- ensure-xray-frame!
  "Register the `:rf/xray` frame so the chrome gallery's
  `shell-view` (whose body hardcodes `[rf/frame-provider {:frame
  :rf/xray}]`) can resolve its subscribes without dereferencing
  nil.

  In production Xray lazily registers the frame in
  `mount.cljs/open!` on the first Ctrl+Shift+C — but the gallery
  testbed never opens the production shell, it embeds it through
  the chrome variant directly. Without this defensive reg-frame
  the first chrome variant render throws
  `No protocol method IDeref.-deref defined for type null` because
  the subscribe returns nil against the unregistered frame.
  Idempotent — `reg-frame`'s surgical-update-on-re-register
  semantics mean a second call is a no-op."
  []
  (rf/reg-frame :rf/xray {}))

(defn ^:export run []
  ;; rf2-2c5xb — seed `:rf.xray/project-root` BEFORE the Xray handlers
  ;; register so the first chip render reads the configured root. The
  ;; resolver reads only the configured atom; the URI build is independent
  ;; of `window.location`, so the panel-gallery's source links resolve to
  ;; the same on-disk paths regardless of which port shadow-cljs serves
  ;; the testbed from.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  ;; rf2-ymnfx (Issue A) — seed `:rf.story/project-root` for the SAME
  ;; on-disk root so the Story shell's variant-toolbar 'Open' button
  ;; (re-frame.story.ui.open-in-editor/open-chip) ships an absolute
  ;; path. Story's own atom is independent of Xray's; without this
  ;; call Story's project-root stays nil and the Open chip falls back
  ;; to the bare classpath-relative `:file` (which the OS-side editor
  ;; URI handler rejects). The Story → Xray bridge re-writes Xray's
  ;; slot to the same value — idempotent under `set-project-root!`'s
  ;; `reset!`. Mirrors the pattern in
  ;; `tools/story/testbeds/login_form/core.cljs` and
  ;; `tools/story/testbeds/counter_with_stories/core.cljs`.
  (story/configure! {:rf.story/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Xray's :rf.xray/* events / subs / fxs land on the registry once.
  ;; The handlers operate on the current frame's app-db, so each
  ;; variant frame the Story canvas allocates becomes its own
  ;; isolated "Xray frame" for the duration of the variant render.
  ;; The chrome gallery additionally seeds `:rf/xray` via the
  ;; per-variant `:panel-gallery.chrome/seed!` event below — see
  ;; `gallery_chrome.cljs` §A note on shared :rf/xray state.
  (xray-registry/register-xray-handlers!)
  ;; Register `:rf/xray` so the chrome's hardcoded frame-provider
  ;; resolves to a real frame (not nil → throw on subscribe).
  (ensure-xray-frame!)
  ;; rf2-pqulr — install Xray theme CSS variables on :root so
  ;; embedded widgets paint with the themed palette rather than
  ;; browser defaults. Shell normally calls this from shell-view;
  ;; the gallery bypasses the shell so we call directly.
  ;; `global-styles/install!` is idempotent (defonce-guarded +
  ;; DOM-probed via fixed id attributes per its docstring) so this
  ;; call is safe to make regardless of boot order or hot-reload.
  (global-styles/install!)
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
