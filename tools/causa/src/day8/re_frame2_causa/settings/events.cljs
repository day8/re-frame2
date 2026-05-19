(ns day8.re-frame2-causa.settings.events
  "Events for the Causa Settings popup modal (rf2-9poxq; expanded
  by rf2-ttnst).

  Per `tools/causa/spec/018-Event-Spine.md` §9 Settings popup the
  modal is a transient overlay (NOT a sidebar panel). Events:

      :rf.causa/settings-open
      :rf.causa/settings-close
      :rf.causa/settings-update [section key value]
      :rf.causa/settings-select-tab tab-id
      :rf.causa/settings-clear-buffer        (rf2-ttnst — Buffer tab)
      :rf.causa/settings-confirm-clear-buffer (rf2-ttnst — open confirm)
      :rf.causa/settings-cancel-clear-buffer  (rf2-ttnst — close confirm)

  ## Open / close flag

  `:settings-open?` lives in Causa's app-db (`:rf/causa` frame). The
  Modal reg-view in `settings/popup.cljs` short-circuits to nil when
  the flag is false; closed-state cost is one subscribe + a `when`.

  ## Update path

  `:rf.causa/settings-update` writes through to the in-process atom
  in `config.cljc` via `config/update-setting!` — that fn round-trips
  the change to localStorage (CLJS only) so the next page-load reads
  the persisted value. The event ALSO mirrors the change into Causa's
  app-db so the popup's subscriptions re-fire IMMEDIATELY through the
  reactive surface (the same pattern `note-suppressed!` uses for the
  redaction counter — rf2-0vxdn). Without the dual-write the popup's
  radio buttons would not redraw until the user closed and reopened
  the modal.

  ## Active tab

  The modal's top tab strip (General | Filters | Theme) tracks
  `:settings-active-tab` in app-db. Default is `:general`.
  `:rf.causa/settings-open` resets the tab to `:general` so each
  reopen starts in a predictable place (the modal is transient per
  spec/018 §9 — the user is not 'browsing' it)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.settings.effects :as effects]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

(defn install!
  "Install the settings popup's events. Idempotent under re-frame's
  replace-in-place registrar semantics; the orchestrator
  (`registry/register-causa-handlers!`) gates the whole sequence
  with a sentinel so re-loads do not re-install."
  []

  (rf/reg-event-db :rf.causa/settings-open
    (fn [db _event]
      (-> db
          (assoc :settings-open? true)
          (assoc :settings-active-tab :general)
          ;; Lift the current atom snapshot into app-db so the
          ;; popup's `:rf.causa/setting` sub reads off the reactive
          ;; surface immediately on open. Without this seed, the
          ;; first render reads from the atom via the
          ;; `:rf.causa/setting` sub's fallback — works, but later
          ;; updates wouldn't propagate without a re-render trigger.
          (assoc :settings (config/get-settings)))))

  (rf/reg-event-db :rf.causa/settings-close
    (fn [db _event]
      (assoc db :settings-open? false)))

  (rf/reg-event-db :rf.causa/settings-toggle
    (fn [db _event]
      (if (get db :settings-open? false)
        (assoc db :settings-open? false)
        (-> db
            (assoc :settings-open? true)
            (assoc :settings-active-tab :general)
            (assoc :settings (config/get-settings))))))

  (rf/reg-event-db :rf.causa/settings-select-tab
    (fn [db [_ tab-id]]
      (assoc db :settings-active-tab tab-id)))

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
  (rf/reg-event-db :rf.causa/settings-update
    (fn [db [_ section key value]]
      (config/update-setting! section key value)
      (cond
        (and (= section :general) (= key :text-size))
        (effects/apply-text-size! value)

        (and (= section :general) (= key :panel-position))
        (effects/apply-panel-position! value)

        (and (= section :general) (= key :panel-width-px))
        (effects/apply-panel-width! value)

        (and (= section :theme) (nil? key))
        (effects/apply-theme! value)

        ;; rf2-ybjkx — reduced-motion override class flip. Three-value
        ;; enum (`:os | :always | :never`). The apply-fn clears prior
        ;; classes and writes the new one onto `<html>` so the next
        ;; paint reads the updated `--rf-causa-motion-scale` seam.
        (and (= section :general) (= key :reduced-motion-override))
        (effects/apply-reduced-motion-override! value)

        ;; Auto-open-on-error toggle — install the sub-watcher on
        ;; flip-on, detach on flip-off. The install is also attempted
        ;; from `mount/ensure-causa-frame!` so the persisted-true case
        ;; lands as soon as the user opens Causa. Both paths are
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
      (if (and (= section :theme) (nil? key))
        (assoc-in db [:settings :theme] value)
        (assoc-in db [:settings section key] value))))

  ;; rf2-ttnst — Buffer tab "Clear buffer now" confirm-modal events.
  ;; The button opens a nested confirmation dialog before clearing the
  ;; ring buffer; the dialog tracks its open state under
  ;; `:settings-clear-confirm-open?`.
  (rf/reg-event-db :rf.causa/settings-confirm-clear-buffer
    (fn [db _event]
      (assoc db :settings-clear-confirm-open? true)))

  (rf/reg-event-db :rf.causa/settings-cancel-clear-buffer
    (fn [db _event]
      (assoc db :settings-clear-confirm-open? false)))

  ;; rf2-ttnst — perform the clear. trace-bus/clear-buffer! empties the
  ;; ring + drops the redaction counter (see trace-bus §clear-buffer!).
  ;; We dismiss the confirm modal here; the parent Settings popup stays
  ;; open so the user lands back on the Buffer tab.
  (rf/reg-event-db :rf.causa/settings-clear-buffer
    (fn [db _event]
      (try (trace-bus/clear-buffer!) (catch :default _ nil))
      (assoc db :settings-clear-confirm-open? false)))

  nil)
