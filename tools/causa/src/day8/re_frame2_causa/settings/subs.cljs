(ns day8.re-frame2-causa.settings.subs
  "Subscriptions for the Causa Settings popup modal (rf2-9poxq).

  ## Sub tree

  - `:rf.causa/settings-open?`    — boolean. Drives the modal mount.
  - `:rf.causa/settings-active-tab` — keyword. Drives left-rail
                                      highlight. Default `:general`.
  - `:rf.causa/setting` `[section key]` — slot read. Falls back to
                                          the atom in `config.cljc`
                                          when app-db has not yet
                                          mirrored the slot (pre-
                                          first-open).

  The `:rf.causa/setting` sub is parameterised — the query vector
  carries `[section key]`. Layout views read `[:rf.causa/setting
  :general :text-size]` etc."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.config :as config]))

(defn install!
  "Install the settings popup's subs. Idempotent under re-frame's
  replace-in-place registrar semantics."
  []

  (rf/reg-sub :rf.causa/settings-open?
    (fn [db _query]
      (boolean (get db :settings-open? false))))

  (rf/reg-sub :rf.causa/settings-active-tab
    (fn [db _query]
      (or (get db :settings-active-tab) :general)))

  ;; Parameterised slot read. The `events/install!` `:settings-open`
  ;; handler seeds `:settings` from the atom; before that seed (or
  ;; for a consumer reading the sub before the user has opened the
  ;; modal) we fall through to `config/get-setting` so the value is
  ;; always the live setting, not nil.
  (rf/reg-sub :rf.causa/setting
    (fn [db [_ section key]]
      (or (get-in db [:settings section key])
          (config/get-setting section key))))

  ;; Whole-settings snapshot. Used by the persistence effect and by
  ;; tests that assert the full state. Defaults to the atom contents
  ;; when app-db has not been seeded.
  (rf/reg-sub :rf.causa/settings
    (fn [db _query]
      (or (get db :settings) (config/get-settings))))

  nil)
