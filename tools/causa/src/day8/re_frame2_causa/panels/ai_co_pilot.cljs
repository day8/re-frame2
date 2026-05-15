(ns day8.re-frame2-causa.panels.ai-co-pilot
  "Stable facade for Causa's AI Co-Pilot panel.

  The panel is intentionally split by responsibility:

  - views live in `ai-co-pilot-views` and its section leaves.
  - registrations live in `ai-co-pilot-subs` and `ai-co-pilot-events`.
  - pure data helpers live behind `ai-co-pilot-helpers`.

  The public view vars stay here so shell/tests keep requiring one
  namespace while the implementation leaves stay below the leaf budget."
  (:require [day8.re-frame2-causa.panels.ai-co-pilot-events :as events]
            [day8.re-frame2-causa.panels.ai-co-pilot-subs :as subs]
            [day8.re-frame2-causa.panels.ai-co-pilot-views :as views]))

(def ai-co-pilot-rail
  "Open 320px right-rail form of the AI Co-Pilot."
  views/ai-co-pilot-rail)

(def ai-co-pilot-cue
  "Collapsed cue glyph form of the AI Co-Pilot."
  views/ai-co-pilot-cue)

(def ai-co-pilot-view
  "Canvas panel form of the AI Co-Pilot."
  views/ai-co-pilot-view)

(defn install!
  "Install the AI Co-Pilot panel's Causa-side registrations.

  Installation is pull-only: it registers handlers and a no-op LLM
  stream effect but never initiates an outbound provider request."
  []
  (subs/install!)
  (events/install!)
  nil)
