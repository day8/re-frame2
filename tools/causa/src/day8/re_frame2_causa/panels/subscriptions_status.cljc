(ns day8.re-frame2-causa.panels.subscriptions-status
  "Canonical status taxonomy for Causa's Subscriptions panel.")

(def status->token
  {:fresh             :green
   :re-running        :cyan
   :invalidated       :yellow
   :cached-no-watcher :text-tertiary
   :error             :red})

(def status->glyph
  {:fresh             "●"
   :re-running        "◐"
   :invalidated       "◌"
   :cached-no-watcher "○"
   :error             "▲"})

(def status->tooltip
  {:fresh             "Fresh"
   :re-running        "Re-running"
   :invalidated       "Invalidated"
   :cached-no-watcher "Cached, no watcher"
   :error             "Error"})

(def statuses
  [:error :re-running :invalidated :fresh :cached-no-watcher])
