(ns day8.re-frame2-xray.settings.events
  "Events for the Xray Settings popup modal (rf2-9poxq; expanded
  by rf2-ttnst).

  Per `tools/xray/spec/018-Event-Spine.md` §9 Settings popup the
  modal is a transient overlay (NOT a sidebar panel). Events:

      :rf.xray/settings-open
      :rf.xray/settings-close
      :rf.xray/settings-update [section key value]
      :rf.xray/settings-select-tab tab-id
      :rf.xray/settings-clear-buffer        (rf2-ttnst — Buffer tab)
      :rf.xray/settings-confirm-clear-buffer (rf2-ttnst — open confirm)
      :rf.xray/settings-cancel-clear-buffer  (rf2-ttnst — close confirm)

  ## Open / close flag

  `:settings-open?` lives in Xray's app-db (`:rf/xray` frame). The
  Modal reg-view in `settings/popup.cljs` short-circuits to nil when
  the flag is false; closed-state cost is one subscribe + a `when`.

  ## Update path

  `:rf.xray/settings-update` writes through to the in-process atom
  in `config.cljc` via `config/update-setting!` — that fn round-trips
  the change to localStorage (CLJS only) so the next page-load reads
  the persisted value. The event ALSO mirrors the change into Xray's
  app-db so the popup's subscriptions re-fire IMMEDIATELY through the
  reactive surface (the same pattern `note-suppressed!` uses for the
  redaction counter — rf2-0vxdn). Without the dual-write the popup's
  radio buttons would not redraw until the user closed and reopened
  the modal.

  ## Active tab

  The modal's top tab strip (General | Keybindings | Buffer | Diff)
  tracks `:settings-active-tab` in app-db. Default is `:general`.
  `:rf.xray/settings-open` resets the tab to `:general` so each
  reopen starts in a predictable place (the modal is transient per
  spec/018 §9 — the user is not 'browsing' it). The Theme tab was
  retired in rf2-ou3pn — the ribbon's sun/moon icon now drives
  `[:rf.xray/settings-update :theme nil <kw>]` directly. The
  Filters tab was retired in rf2-wknb3 — full filter management
  lives in the top-ribbon pill strip + per-pill edit popup + mute
  manager modal."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn install!
  "Install the settings popup's events. Idempotent under re-frame's
  replace-in-place registrar semantics; the orchestrator
  (`registry/register-xray-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []

  (rf/reg-event :rf.xray/settings-open
    (fn [{:keys [db]} _event]
      {:db (-> db
          (assoc :settings-open? true)
          (assoc :settings-active-tab :general)
          ;; Lift the current atom snapshot into app-db so the
          ;; popup's `:rf.xray/setting` sub reads off the reactive
          ;; surface immediately on open. Without this seed, the
          ;; first render reads from the atom via the
          ;; `:rf.xray/setting` sub's fallback — works, but later
          ;; updates wouldn't propagate without a re-render trigger.
          (assoc :settings (config/get-settings)))}))

  (rf/reg-event :rf.xray/settings-close
    (fn [{:keys [db]} _event]
      {:db (assoc db :settings-open? false)}))

  (rf/reg-event :rf.xray/settings-toggle
    (fn [{:keys [db]} _event]
      {:db (if (get db :settings-open? false)
        (assoc db :settings-open? false)
        (-> db
            (assoc :settings-open? true)
            (assoc :settings-active-tab :general)
            (assoc :settings (config/get-settings))))}))

  (rf/reg-event :rf.xray/settings-select-tab
    (fn [{:keys [db]} [_ tab-id]]
      {:db (assoc db :settings-active-tab tab-id)}))

  ;; Write a single setting. Dual-write: the atom (canonical, drives
  ;; localStorage round-trip) and app-db (drives the immediate
  ;; reactive re-render of the popup's controls). The `:theme` slot
  ;; addresses as `[:theme nil <kw>]` because the slot is a flat
  ;; keyword, not a nested map — `config/update-setting!` special-
  ;; cases that path and the db-write here mirrors the assoc shape.
  ;;
  ;; After the dual-write, the matching side-effect lands so the
  ;; user sees the change immediately (text-size CSS var, theme
  ;; class, panel-position route).
  (rf/reg-event :rf.xray/settings-update
    (fn [{:keys [db]} [_ section key value]]
      (config/update-setting! section key value)
      (cond
        (and (= section :general) (= key :text-size))
        (effects/apply-text-size! value)

        (and (= section :general) (= key :panel-position))
        (effects/apply-panel-position! value)

        (and (= section :general) (= key :panel-width-px))
        (effects/apply-panel-width! value)

        ;; rf2-i40us — density radio writes the resolved px into
        ;; `--rf-xray-font-size` so the whole `theme/tokens/type-scale`
        ;; rescales in lockstep (every entry is
        ;; `calc(var(--rf-xray-font-size, 13px) * <multiplier>)`).
        ;; Mapping: compact 12 / cosy 13 / comfy 14 — see
        ;; `effects/density->font-size-px`. The persisted value the
        ;; settings atom carries also drives the `:rf.xray/density`
        ;; sub (Views row padding + App-db diff-row line-height); the
        ;; font-size write here is the one-knob loop's missing leg.
        (and (= section :general) (= key :density))
        (effects/apply-density-font-size! value)

        (and (= section :theme) (nil? key))
        (effects/apply-theme! value)

        ;; rf2-ybjkx — reduced-motion override class flip. Three-value
        ;; enum (`:os | :always | :never`). The apply-fn clears prior
        ;; classes and writes the new one onto `<html>` so the next
        ;; paint reads the updated `--rf-xray-motion-scale` seam.
        (and (= section :general) (= key :reduced-motion-override))
        (effects/apply-reduced-motion-override! value)

        ;; rf2-846h2 — "Use system colors" opt-in. Stamps / clears
        ;; `data-rf-force-colors="active"` on the shell root + `<html>`
        ;; so the sibling selectors in `theme/global-styles/motion-css`
        ;; paint the same system-token chrome the `@media (forced-
        ;; colors: active)` block paints under OS HCM. Boolean enum;
        ;; the apply-fn handles truthy / falsey directly.
        (and (= section :general) (= key :use-system-colors?))
        (effects/apply-use-system-colors! value)

        ;; rf2-3zyyx — Epoch history slider. Writes through to the
        ;; substrate's per-frame ring depth via
        ;; `(rf/configure! {:epoch-history {:depth N}})`. See
        ;; `settings/effects.cljs §apply-epoch-history!` for the
        ;; late-bind contract; non-positive values are dropped at the
        ;; effect boundary.
        (and (= section :general) (= key :epoch-history))
        (effects/apply-epoch-history! value)

        ;; rf2-5u03ig — Events-retained field. Writes through to the
        ;; substrate's per-frame trace ring via
        ;; `(rf/configure! {:trace-buffer {:events-retained N}})`. See
        ;; `settings/effects.cljs §apply-cascades-retained!` for the
        ;; late-bind contract; non-positive values are dropped at the
        ;; effect boundary.
        (and (= section :buffer) (= key :events-retained))
        (effects/apply-cascades-retained! value)

        ;; Auto-open-on-error toggle — install the sub-watcher on
        ;; flip-on, detach on flip-off. The install is also attempted
        ;; from `mount/ensure-xray-frame!` so the persisted-true case
        ;; lands as soon as the user opens Xray. Both paths are
        ;; idempotent (the install no-ops when already wired, the
        ;; detach no-ops when nothing is wired). See
        ;; `settings/effects.cljs §install-auto-open-watcher!` for
        ;; the frame-presence guard that makes a pre-mount call here
        ;; a silent no-op (the watcher then lands on first open).
        (and (= section :general) (= key :auto-open-on-error?))
        (if value
          (effects/install-auto-open-watcher!)
          (effects/detach-auto-open-watcher!))

        :else nil)
      {:db (if (and (= section :theme) (nil? key))
        (assoc-in db [:settings :theme] value)
        (assoc-in db [:settings section key] value))}))

  ;; rf2-ttnst — Buffer tab "Clear buffer now" confirm-modal events.
  ;; The button opens a nested confirmation dialog before clearing the
  ;; ring buffer; the dialog tracks its open state under
  ;; `:settings-clear-confirm-open?`.
  (rf/reg-event :rf.xray/settings-confirm-clear-buffer
    (fn [{:keys [db]} _event]
      {:db (assoc db :settings-clear-confirm-open? true)}))

  (rf/reg-event :rf.xray/settings-cancel-clear-buffer
    (fn [{:keys [db]} _event]
      {:db (assoc db :settings-clear-confirm-open? false)}))

  ;; rf2-ttnst (rf2-43koh consumer substrate) — perform the clear.
  ;; `trace-collector/retroactive-scrub!` empties the framework's
  ;; per-frame trace rings + Xray's frameless secondary ring + Xray's
  ;; mirrored `:trace-buffer` slot, and drops the redaction counter.
  ;; We dismiss the confirm modal here; the parent Settings popup
  ;; stays open so the user lands back on the Buffer tab.
  (rf/reg-event :rf.xray/settings-clear-buffer
    (fn [{:keys [db]} _event]
      (try (trace-collector/retroactive-scrub!) (catch :default _ nil))
      {:db (assoc db :settings-clear-confirm-open? false)}))

  nil)
