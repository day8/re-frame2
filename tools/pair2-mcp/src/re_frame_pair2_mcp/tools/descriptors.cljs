(ns re-frame-pair2-mcp.tools.descriptors
  "MCP `tools/list` descriptor builder (rf2-vrbwx, rf2-47g8l).

  The descriptor *data* lives in `descriptors-data.cljs`; the *catalogue
  index* (name → descriptor + handler + cacheable?) lives in
  `registry.cljs` — the single source of truth that drives both the
  `tools/list` surface here and the `tools/call` dispatcher in
  `tools.cljs`.

  This file's only job is to surface that data to JS, splicing the
  universal `max-tokens` and `cache` knobs onto every descriptor via
  the `with-*-knob` composers below. Splicers live here (rather than in
  `descriptors-knobs.cljs`) so they can consult `registry/cacheable?`
  without dragging the property-data ns into a circular require chain
  with the registry."
  (:require [re-frame-pair2-mcp.tools.descriptors-knobs :as knobs]
            [re-frame-pair2-mcp.tools.registry :as registry]))

;; Re-export — `tools.cljs` and tests reach for the bare descriptor
;; vector via this façade name.
(def tool-descriptors registry/tool-descriptors)

(defn with-budget-knob
  "Splice `max-tokens` into a tool descriptor's inputSchema.properties.
  No-op if the descriptor already declares it (forward-compat)."
  [desc]
  (let [props (get-in desc [:inputSchema :properties])]
    (if (contains? props :max-tokens)
      desc
      (assoc-in desc [:inputSchema :properties :max-tokens]
                knobs/max-tokens-property))))

(defn with-cache-knob
  "Splice `cache` into a tool descriptor's inputSchema.properties — but
  only for the read tools that consult `cache/apply-cache`. Action and
  streaming tools don't list the knob because it has no effect there
  (bypassed in `registry/cacheable?`)."
  [desc]
  (let [name  (:name desc)
        props (get-in desc [:inputSchema :properties])]
    (if (or (contains? props :cache)
            (not (registry/cacheable? name)))
      desc
      (assoc-in desc [:inputSchema :properties :cache]
                knobs/cache-property))))

(defn tool-descriptors-js []
  (clj->js (mapv (comp with-cache-knob with-budget-knob) tool-descriptors)))
