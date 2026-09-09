(ns fresco-counter.core
  "Fresco story testbed entry — the proof that a Fresco boundary paints
  in Story's canvas (rf2-kttom, Phase D of rf2-5czki).

  URL-hash-routed between two surfaces, like every other Story testbed:

    `#/`        → the live tally, a Reagent root carrying the boundary
                  through `rf.fresco/as-component`.
    `#/stories` → the Story shell. The deck's two variants declare
                  `:substrates #{:fresco}` and paint through the
                  registered `:fresco` render fn.

  ## The host installs the `:fresco` renderer — all five lines of it

  Story ships no `install-fresco-substrate!`, and that is a ruling
  (rf2-1gy4e placement 1) rather than an omission: the renderer's one
  dependency is the HOST's, so registering it here keeps Story core free
  of Fresco and `tools/story/deps.edn` untouched. `:uix` is host-registered
  for the same reason. [[install-fresco-substrate!]] below is byte-for-byte
  the recipe written out in `re-frame.story.ui.multi-substrate`'s ns
  docstring, which is also what
  `re-frame.story.ui.fresco-substrate-dom-cljs-test` drives.

  ## Reagent is the SHELL's adapter, and that is not a contradiction

  `rf/init!` installs ONE adapter and this testbed installs Reagent's,
  because the Story shell and the hash-routing host are Reagent trees.
  A substrate in Story names WHICH REGISTERED RENDER FN embeds the
  subject — the authoring layer — and not the adapter `rf/init!` seated;
  the two axes coincided while the members were `:reagent` and `:uix`,
  and `:fresco` is the member that separates them — Fresco does ship
  an adapter of its own (`:kind :rf.adapter/fresco`, rf2-hvr5h), and
  this testbed deliberately does not seat it, which is exactly the
  separation. The canvas wraps every variant in
  `[rf/frame-provider {:frame variant-id} …]`, whose provider is
  `re-frame.adapter.context/frame-context` — the single React context
  every React-shaped adapter reads — so a Fresco boundary spliced into
  that Reagent tree resolves the VARIANT's frame from it, with no second
  root and no props ABI.

  ## Elision

  Per `001-Authoring.md` §Registration macros, under `:advanced` with
  `:closure-defines {re-frame.story.config/enabled? false}` every `reg-*`
  in `fresco-counter.stories` elides to nil and `mount-shell!`
  short-circuits. This build leaves Story enabled; the elision path is
  gated elsewhere."
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.fresco :as rf.fresco]
            [re-frame.story :as rf.story]
            [re-frame.story.play.ci-runner :as rf.story.play.ci-runner]
            [day8.re-frame2-xray.config :as xray-config]
            [fresco-counter.events]
            [fresco-counter.subs]
            [fresco-counter.views :as views]
            [fresco-counter.stories]
            ;; Shared Story-host helper: owns the live-app↔Story-shell hash
            ;; router + React-root handle.
            [re-frame.testbed.story-host :as rf.testbed.story-host])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; The `:fresco` substrate render fn — the consumer's five lines
;; ---------------------------------------------------------------------------

(defn install-fresco-substrate!
  "Register the `:fresco` render fn with Story. Three decisions ride in
  these lines and each is ruled on rf2-2dbpd:

  - resolve LATE, per render, off `(rf/view id)`, so re-evaluating a
    `defview` — which replaces the registrar entry behind the same id —
    reaches the deck with no deck change;
  - use `rf/view`, the framework's own late-bind lookup, because the
    alias publishes its minted head at `:handler-fn` like every other
    substrate's `:view` entry (rf2-kuky.60). This read used to be
    `(:fresco/component (rf/handler-meta :view id))`, a private slot a
    host had to know about, because the entry deliberately carried no
    `:handler-fn` and `rf/view` answered nil for it (rf2-5qaf4);
  - mint the element with `rf.fresco/as-element` rather than bridging through
    `rf.fresco/as-component`. `defview` already mints ONE `React.memo` wrapper per
    head, so a fresh element per pass rides a stable TYPE and the boundary
    re-renders instead of remounting."
  []
  (rf.story/register-substrate! :fresco
    (fn [_variant-id view-id args]
      (if-let [head (rf/view view-id)]
        (rf.fresco/as-element [head args])
        [:div (str ":component " (pr-str view-id)
                   " is not registered as a fresco view")]))))

;; ---------------------------------------------------------------------------
;; Live-app root view — a Reagent tree carrying the boundary
;; ---------------------------------------------------------------------------

(reg-view tally-page []
  [:div {:style {:padding "2em" :font-family "system-ui, sans-serif"
                 :max-width "640px" :margin "0 auto"}}
   [:h2 {:style {:margin "0 0 0.5em 0"}} "Fresco tally (Story testbed)"]
   [:p {:style {:font-size "13px" :color "#666" :margin "0 0 1.5em 0"}}
    "Every element below this line was authored with "
    [:code {:style {:background "#f5f5f5" :padding "1px 4px"}} "rf.fresco/defview"]
    ". Open "
    [:a {:href "#/stories"} "#/stories"]
    " for the same views as Story variants under "
    [:code {:style {:background "#f5f5f5" :padding "1px 4px"}}
     ":substrates #{:fresco}"]
    "."]
   ;; `:>` places a real React component in Reagent's tree. The components
   ;; themselves are minted ONCE, at the top level of `views` — minting one
   ;; per render would hand React a fresh element type every pass, and React
   ;; answers a fresh type by unmounting the subtree.
   [:> views/tally-component {:heading "Tally"}]
   [:> views/readout-component {:label "count"}]])

;; EP-0002: the runtime never synthesises a frame from absence, so the
;; live-app surface renders under an explicit frame scope. The Story shell
;; side allocates its own per-variant frames; this wrapper scopes only the
;; `#/` surface to the testbed's `:rf/default` frame. Mirrors
;; login-form.core/live-app-root.
(defn live-app-root []
  [rf/frame-provider {:frame :rf/default} [tally-page]])

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn ^:export run []
  ;; Story owns this page's full-width browser-test canvas. Keep Xray's
  ;; collectors and keybinding installed but skip the default panel launch.
  (xray-config/configure! {:rf.xray/auto-open? false})
  (rf/init! rf.adapter.reagent/adapter)
  (install-fresco-substrate!)
  (rf/make-frame {:id  :rf/default
                  :doc "Fresco-counter testbed default frame."})
  ;; Seed the live page's frame. EP-0002: `init!` installs the adapter
  ;; only, and a frameless `dispatch-sync` raises `:rf.error/no-frame-context`.
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:fresco-counter/initialise 0]))
  ;; The `window.__rf2_story_ci` door the play-scripts runner enumerates
  ;; and drives. Installed unconditionally — the fn body is itself gated on
  ;; Story being enabled — and BEFORE the router mounts, so the global is
  ;; present by the time the page has finished loading.
  (rf.story.play.ci-runner/install-ci-hooks!)
  (rf.testbed.story-host/mount-with-hash-routing! live-app-root))
