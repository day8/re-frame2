(ns day8.re-frame2-causa.panels.subscriptions-helpers
  "Stable facade for the Subscriptions panel's pure helpers."
  (:require [day8.re-frame2-causa.panels.subscriptions-chain-model
             :as chain]
            [day8.re-frame2-causa.panels.subscriptions-format :as fmt]
            [day8.re-frame2-causa.panels.subscriptions-projection
             :as projection]
            [day8.re-frame2-causa.panels.subscriptions-status
             :as status]))

(def status->token status/status->token)
(def status->glyph status/status->glyph)
(def status->tooltip status/status->tooltip)
(def statuses status/statuses)

(def compute-status projection/compute-status)
(def project-rows projection/project-rows)
(def status-counts projection/status-counts)
(def filter-by-status projection/filter-by-status)

(def compute-chain chain/compute-chain)

(def format-query-v fmt/format-query-v)
(def format-sub-id fmt/format-sub-id)
