(ns day8.re-frame2-xray.settings.subs
  "Subscriptions for the Xray Settings popup modal.

  ## Sub tree

  - `:rf.xray/settings-open?`    — boolean. Drives the modal mount.
  - `:rf.xray/settings-active-tab` — keyword. Drives left-rail
                                      highlight. Default `:general`.
  - `:rf.xray/setting` `[section key]` — slot read. Falls back to
                                          the atom in `config.cljc`
                                          when app-db has not yet
                                          mirrored the slot (pre-
                                          first-open).

  The `:rf.xray/setting` sub is parameterised — the query vector
  carries `[section key]`. Layout views read `[:rf.xray/setting
  :general :text-size]` etc."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]))

(defn install!
  "Install the settings popup's subs. Idempotent under re-frame's
  replace-in-place registrar semantics."
  []

  (rf/reg-sub :rf.xray/settings-open?
    (fn [db _query]
      (boolean (get db :settings-open? false))))

  (rf/reg-sub :rf.xray/settings-active-tab
    (fn [db _query]
      (or (get db :settings-active-tab) :general)))

  ;; Buffer tab nested clear-confirm modal open state.
  (rf/reg-sub :rf.xray/settings-clear-confirm-open?
    (fn [db _query]
      (boolean (get db :settings-clear-confirm-open? false))))

  ;; Parameterised slot read. The `events/install!` `:settings-open`
  ;; handler seeds `:settings` from the atom; before that seed (or
  ;; for a consumer reading the sub before the user has opened the
  ;; modal) we fall through to `config/get-setting` so the value is
  ;; always the live setting, not nil.
  (rf/reg-sub :rf.xray/setting
    (fn [db [_ section key]]
      (or (get-in db [:settings section key])
          (config/get-setting section key))))

  ;; Whole-settings snapshot. Used by the persistence effect and by
  ;; tests that assert the full state. Defaults to the atom contents
  ;; when app-db has not been seeded.
  (rf/reg-sub :rf.xray/settings
    (fn [db _query]
      (or (get db :settings) (config/get-settings))))

  ;; Convenience sub for the diff opts map the
  ;; hiccup-diff engine consumes via `classify-prop`. Reads the
  ;; `:diff` slot of the settings map (in app-db when seeded, the
  ;; atom otherwise) and reshapes to the engine's opts vocabulary.
  ;; A single sub means subs that compose against the diff engine
  ;; read one canonical map rather than per-knob `:rf.xray/setting`
  ;; calls.
  (rf/reg-sub :rf.xray/diff-opts
    (fn [db _query]
      (let [diff (or (get-in db [:settings :diff])
                     (:diff (config/get-settings)))]
        {:highlight-fn-ref-changes? (boolean
                                      (:highlight-fn-ref-changes? diff))})))

  ;; Host editor default (the `editor` atom set by
  ;; `set-editor!` / `(xray-config/configure! {:rf.xray/editor …})`).
  ;; Read by the Settings popup's editor-override picker so the
  ;; "Project default: <name>" hint shows what flipping back to the
  ;; default will land on. Reads the atom directly — there is no
  ;; app-db mirror because the host atom is single-write at boot, not
  ;; a per-cascade reactive surface.
  (rf/reg-sub :rf.xray/editor-host-default
    (fn [_db _query]
      (config/get-host-editor-default)))

  ;; Convenience sub for the density knob. Reads
  ;; `:general :density` (`:cosy` or `:compact`). Views detail rows
  ;; + App-db diff rows branch padding/line-height off this value.
  ;; The density tiers are exactly `:cosy` and `:compact`; any
  ;; unrecognised value (e.g. a persisted `:comfy`) is treated as
  ;; `:cosy`.
  (rf/reg-sub :rf.xray/density
    (fn [db _query]
      (let [d (or (get-in db [:settings :general :density])
                  (config/get-setting :general :density)
                  :cosy)]
        (if (#{:cosy :compact} d) d :cosy))))

  ;; rf2-ttnst — convenience sub: should the L1 frame-picker dropdown
  ;; include tool frames (`:rf/xray`, `:rf/pair2`)? OFF by default
  ;; per spec/007-UX-IA.md §Frame-observation isolation invariant I1.
  (rf/reg-sub :rf.xray/show-tool-frames?
    (fn [db _query]
      (boolean (or (get-in db [:settings :general :show-tool-frames?])
                   (config/get-setting :general :show-tool-frames?)))))

  ;; rf2-r9lyy — convenience sub: should the L2 event list surface
  ;; the `:ungrouped` pseudo-cascade bucket (registry-time emits /
  ;; frame lifecycle / `:rf.ssr/hydration-mismatch` / REPL evals)?
  ;; OFF by default — Xray is silent-by-default per Mike's 2026-05-19
  ;; closure of rf2-q60yf. Flipping ON re-includes the bucket in L2
  ;; with a muted row treatment; clicking the row focuses it so
  ;; downstream panels (Event / App-db / Views / Trace) render against
  ;; the bucket's events. Useful when debugging SSR / REPL flows.
  (rf/reg-sub :rf.xray/show-ungrouped?
    (fn [db _query]
      (boolean (or (get-in db [:settings :general :show-ungrouped?])
                   (config/get-setting :general :show-ungrouped?)))))

  ;; rf2-ttnst — convenience sub: long-keyword wrap threshold (chars).
  ;; Default 24 (was a fixed constant; now user-tuneable per spec/007-
  ;; UX-IA.md §Long-keyword treatment).
  (rf/reg-sub :rf.xray/long-keyword-threshold
    (fn [db _query]
      (or (get-in db [:settings :general :long-keyword-threshold])
          (config/get-setting :general :long-keyword-threshold)
          24)))

  nil)
