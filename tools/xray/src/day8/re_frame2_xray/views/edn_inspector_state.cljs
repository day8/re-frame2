(ns day8.re-frame2-xray.views.edn-inspector-state
  "Expansion-state registration for the edn-inspector widget.

  This namespace owns ONE concern: the per-node expansion overrides the
  widget stores in re-frame app-db — the `expansion-slot` key, its
  `reg-sub`, the `toggle-node` / `set-node` / `reset-expansion` events,
  the pure `expansion-key` composer, and the `resolve-expanded?`
  projection.

  `views/edn-inspector` re-exports the public vars (`expansion-slot`,
  `expansion-key`, `resolve-expanded?`) so call sites and tests can
  resolve them against the widget namespace.

  The expansion state is a self-contained app-db slice with no
  dependency on the renderer (no tokens, no hiccup), so it lives apart
  from the renderer namespace with its own reviewable, testable home."
  (:require [re-frame.core :as rf]))

;; =========================================================================
;; expansion state — lives in :rf.xray.edn-inspector/expansion under the
;; SURROUNDING instance frame (the shell's frame-id; `:rf/xray` for the
;; production singleton). Per-frame so N shells keep independent expansion.
;; =========================================================================

(def expansion-slot
  "App-db slot holding the per-node expansion overrides. Public so
  the consuming panel's reset affordance can clear it."
  :rf.xray.edn-inspector/expansion)

(defn expansion-key
  "Compose the per-node expansion key. Pure data, JVM-portable."
  [panel-id mount-id path]
  [panel-id mount-id (vec path)])

(defn install!
  "Install the expansion-state sub + events. Reached from
  `views/edn-inspector`'s own `install!`, which the orchestrator
  (`registry/register-xray-handlers!`) calls — never at ns-load.

  Why a fn and not four top-level forms (rf2-y8doi.16): the preload's
  foundation block is wrapped in `(when rf.interop/debug-enabled? …)` so
  Closure folds it away under `:advanced` + `goog.DEBUG=false`, which is
  what `tools/xray/spec/API.md` §Installation API,
  `tools/xray/spec/011-Launch-Modes.md` §Production posture,
  `tools/xray/README.md` and `spec/Tool-Pair.md` §The Xray renderer all
  promise. A `:require`d namespace's top-level forms sit OUTSIDE that
  block — they run at ns-load unconditionally, and a registrar write is a
  side effect no dead-code eliminator may remove — so registering here
  would mutate the HOST's process-global registrar in a release bundle
  that merely carried the preload's bytes. Called-from-`install!` is
  therefore the pattern every Xray registration follows (`epoch.cljs` is
  the model); `registry_cljs_test.cljs` pins it."
  []
  (rf/reg-sub expansion-slot
    (fn [db _] (get db expansion-slot)))

  (rf/reg-event :rf.xray.edn-inspector/toggle-node
    (fn [{:keys [db]} [_ panel-id mount-id path rendered-expanded?]]
      ;; First click MUST invert the currently-visible state.
      ;;
      ;; The widget renders `default-expanded` paths (top-level nodes,
      ;; depth ≤ `default-expanded-depth`) open BEFORE the user clicks,
      ;; even though no override is stored. If the reducer flipped from
      ;; a hard-coded assumption (e.g. "first click opens") it would
      ;; emit the same state the user already sees — a silent no-op on
      ;; the first click.
      ;;
      ;; The dispatch payload carries `rendered-expanded?` — the
      ;; value `resolve-expanded?` returned for this path on the last
      ;; render (i.e. what the user currently sees). When no override
      ;; is stored the reducer flips from that visible state; when an
      ;; override IS stored it flips the override (idempotent given a
      ;; consistent dispatcher).
      {:db (let [k       (expansion-key panel-id mount-id path)
            current (get-in db [expansion-slot k])
            next?   (if (contains? current :expanded?)
                      (not (boolean (:expanded? current)))
                      (not (boolean rendered-expanded?)))]
        (assoc-in db [expansion-slot k] {:expanded? next?}))}))

  (rf/reg-event :rf.xray.edn-inspector/set-node
    (fn [{:keys [db]} [_ panel-id mount-id path expanded?]]
      {:db (assoc-in db [expansion-slot (expansion-key panel-id mount-id path)]
                {:expanded? (boolean expanded?)})}))

  (rf/reg-event :rf.xray.edn-inspector/reset-expansion
    (fn [{:keys [db]} _]
      {:db (dissoc db expansion-slot)})))

(defn resolve-expanded?
  "Pure projection — given the per-render expansion map, the path,
  and the default-heuristic result, return whether THIS node renders
  expanded. The operator's sticky override (if present) wins."
  [expansion-map panel-id mount-id path default?]
  (let [k        (expansion-key panel-id mount-id path)
        override (get expansion-map k)]
    (if (contains? override :expanded?)
      (boolean (:expanded? override))
      (boolean default?))))
