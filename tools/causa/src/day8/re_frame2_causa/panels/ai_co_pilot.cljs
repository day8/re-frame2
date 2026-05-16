(ns day8.re-frame2-causa.panels.ai-co-pilot
  "AI Co-Pilot panel shell.

  Follows the canonical panel facade pattern documented in
  `tools/causa/spec/Conventions.md` — the facade owns the panel's
  three public `reg-view`s; bodies delegate to plain Reagent fns in
  `ai-co-pilot-views`. Subs/events leaves expose `install!`.

  The panel is split by responsibility:

  - view shells (rail / cue / canvas) — bodies in `ai-co-pilot-views`.
  - chrome / conversation / input sub-views — leaf cljs files,
    plain Reagent fns.
  - registrations — `ai-co-pilot-subs` + `ai-co-pilot-events`.
  - pure data helpers — `ai-co-pilot-helpers` (a stable .cljc facade
    rolling up chips / conversation-model / redaction / slash leaves)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.ai-co-pilot-events :as events]
            [day8.re-frame2-causa.panels.ai-co-pilot-subs :as subs]
            [day8.re-frame2-causa.panels.ai-co-pilot-views :as views]))

(rf/reg-view ai-co-pilot-rail
  "Open 320px right-rail form of the AI Co-Pilot."
  []
  [views/ai-co-pilot-rail])

(rf/reg-view ai-co-pilot-cue
  "Collapsed cue glyph form of the AI Co-Pilot."
  []
  [views/ai-co-pilot-cue])

(rf/reg-view ai-co-pilot-view
  "Canvas panel form of the AI Co-Pilot."
  []
  [views/ai-co-pilot-view])

(defn install!
  "Idempotent install for the AI Co-Pilot panel's Causa-side
  registrations. Returns nil per the facade convention.

  Installation is pull-only: it registers handlers and a no-op LLM
  stream effect but never initiates an outbound provider request."
  []
  (subs/install!)
  (events/install!)
  nil)
