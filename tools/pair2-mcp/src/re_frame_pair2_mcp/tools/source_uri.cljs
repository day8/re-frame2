(ns re-frame-pair2-mcp.tools.source-uri
  "Source-URI decoration pass (rf2-cibp8).

  Every pair2-mcp tool whose response carries `:source-coord
  {:ns :file :line :column}` maps walks the post-shrink payload
  through this transform. For every `:source-coord` map encountered,
  a sibling `:rf.source/uri \"vscode://...\"` string is added (built
  via `re-frame.source-coords.editor-uri/editor-uri`).

  ## Why a sibling URI string, not just the coord map

  AI hosts (Claude Code, GPT, etc.) auto-render strings whose shape
  matches a registered URI scheme as clickable links — `vscode://...`,
  `cursor://...`, `idea://open?...` all open the editor at the
  declared location. A structured map like `{:ns :foo :file
  \"foo.cljs\" :line 42 :column 7}` does NOT auto-render — the host
  would have to construct the URI itself, and in practice doesn't.
  Shipping the pre-built URI alongside the coord turns a single
  source-coord into a one-click jump-to-definition affordance for
  every event / sub / fx the agent inspects.

  ## Walk policy

  - Recurses into maps, vectors, lists, seqs, sets.
  - When the walker descends into a map and that map has a
    `:source-coord` slot whose value is itself a map, the URI is
    spliced onto the map at the `:rf.source/uri` key.
  - When `:source-coord`'s `:file` slot is missing / blank, the
    underlying `editor-uri` returns nil; the decorator omits the
    `:rf.source/uri` key entirely (keeps the response shape tight
    — no `:rf.source/uri nil` slots).
  - The walk is idempotent. A map that already carries
    `:rf.source/uri` is overwritten with the current editor's URI
    (so a stale URI from a server-side decorator can be replaced if
    the agent reconfigures the editor mid-session).

  ## Ordering invariant

  Runs INSIDE `run-wire-pipeline` AFTER dedup + summary so the
  decoration operates on the post-shrink tree — no wasted URI
  builds on subtrees that will be replaced with summary markers,
  and no broken `:source-coord` maps inside dedup-table cells
  (dedup replaces equal subtrees with a single canonical instance,
  so each source-coord is visited once)."
  (:require [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---------------------------------------------------------------------------
;; The walker.
;;
;; We can't use `clojure.walk/postwalk` here — postwalk visits every
;; node bottom-up, but we need to inspect the VALUE under the
;; `:source-coord` key from the PARENT map's perspective (so we can
;; splice the URI as a sibling, not transform the coord itself). A
;; bespoke recursion is cheaper than walking twice.
;; ---------------------------------------------------------------------------

(defn- coord-map?
  "Is `v` a plausible source-coord map? Match on the load-bearing slot
  (`:file`) being a non-blank string. Per the editor-uri ns, a
  source-coord without `:file` produces a nil URI — so we don't bother
  splicing onto a map that has no `:file`. `:line` / `:column` /
  `:ns` are optional decorations editor-uri tolerates."
  [v]
  (and (map? v)
       (string? (:file v))
       (seq (:file v))))

(defn- decorate-map
  "Decorate `m` with `:rf.source/uri` when its `:source-coord` slot
  carries a usable file string. Otherwise return `m` unchanged.

  Pure data → data; no atoms read here — the editor is threaded in
  by the caller so this fn is testable without the config atom."
  [m editor]
  (let [coord (:source-coord m)]
    (if (coord-map? coord)
      (if-let [uri (editor-uri/editor-uri editor coord)]
        (assoc m :rf.source/uri uri)
        m)
      m)))

(defn decorate
  "Walk `tree`, splicing `:rf.source/uri` onto every map that carries
  a `:source-coord` slot with a usable `:file`. Returns the decorated
  tree.

  `editor` follows the `editor-uri/editor-uri` vocabulary — `:vscode`
  / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <template>}`.

  Pure data → data. Idempotent: re-decoration replaces an existing
  `:rf.source/uri` with the URI for the current editor."
  [tree editor]
  (cond
    (map? tree)
    (let [stepped (reduce-kv
                    (fn [acc k v] (assoc acc k (decorate v editor)))
                    {}
                    tree)]
      (decorate-map stepped editor))

    (vector? tree)
    (mapv #(decorate % editor) tree)

    (set? tree)
    (into #{} (map #(decorate % editor)) tree)

    (seq? tree)
    ;; Preserve the seq-ness so callers (and pr-str) see what they
    ;; emitted, not a magically-realised vector.
    (doall (map #(decorate % editor) tree))

    :else
    tree))
