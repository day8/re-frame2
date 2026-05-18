(ns re-frame2-pair-mcp.tools.source-uri
  "Source-URI decoration pass (rf2-cibp8).

  Every re-frame2-pair-mcp tool whose response carries source-coord data walks
  the post-shrink payload through this transform. The decorator
  recognises two source-coord carriers — both shapes spec'd by
  `:rf/source-coord-meta` in Spec-Schemas:

  1. **Sub-map carrier** — trace events carry coords nested under a
     `:source-coord` key (per `:rf/trace-event` and `:rf.trace/
     trigger-handler`). When the walker descends into a map and that
     map has a `:source-coord` slot whose value carries a usable
     `:file` string, the URI is spliced on the OUTER map at
     `:rf.source/uri`.
  2. **Flat-key carrier** — `(rf/handler-meta kind id)` / `(rf/frame-
     meta id)` returns merge source-coords flat onto the registration-
     metadata map (`:ns` / `:line` / `:column` / `:file` at the top
     level — per Spec-Schemas `:rf/source-coord-meta` and rf2-4h8ny).
     When the walker descends into a map that itself carries a usable
     `:file` string alongside other source-coord keys (`:ns`/`:line`/
     `:column`), the URI is spliced on THAT map at `:rf.source/uri`.

  The two carrier shapes are deliberately disjoint at the schema
  level — trace events nest because the trigger-handler is a separate
  handler from the trace's own coords; handler-meta is flat because
  the call site IS the handler being introspected.

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
  - For the sub-map carrier: when the walker descends into a map M
    whose `:source-coord` value is itself a map carrying `:file`,
    the URI for that sub-map's `:file` is spliced onto M at
    `:rf.source/uri`.
  - For the flat-key carrier: when the walker descends into a map M
    that itself carries a non-blank `:file` string AND at least one
    other source-coord key (`:ns`/`:line`/`:column`) — guarding
    against accidental decoration of unrelated maps that happen to
    have a `:file` key — the URI for M's `:file` is spliced onto M
    at `:rf.source/uri`.
  - When `:file` is missing / blank, the underlying `editor-uri`
    returns nil; the decorator omits the `:rf.source/uri` key
    entirely (keeps the response shape tight — no `:rf.source/uri
    nil` slots).
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

(defn- flat-coord-carrier?
  "Is `m` itself a flat-key source-coord carrier? Match on `:file`
  being a non-blank string AND at least one of `:ns` / `:line` /
  `:column` being present — the multi-key guard rules out unrelated
  maps that happen to carry a `:file` key for some other reason
  (e.g. an MCP tool's `{:file \"/path/to/x.edn\"}` arg map).

  Per `:rf/source-coord-meta`: handler-meta / frame-meta returns merge
  these four keys flat onto the registration-metadata, so the carrier
  shape is `{:doc ... :file ... :ns ... :line ...}` etc."
  [m]
  (and (map? m)
       (string? (:file m))
       (seq (:file m))
       (or (contains? m :ns)
           (contains? m :line)
           (contains? m :column))))

(defn- decorate-map
  "Decorate `m` with `:rf.source/uri` when either:

  - `m` carries a `:source-coord` sub-map with a usable file string
    (trace-event carrier — per `:rf/trace-event`); the URI is built
    from the sub-map and spliced onto `m`. OR
  - `m` is itself a flat-key source-coord carrier (handler-meta /
    frame-meta return shape — per `:rf/source-coord-meta`); the URI
    is built from `m`'s own flat keys and spliced onto `m`.

  Returns `m` unchanged when neither shape matches.

  Pure data → data; no atoms read here — the editor is threaded in
  by the caller so this fn is testable without the config atom."
  [m editor]
  (let [coord (:source-coord m)]
    (cond
      (coord-map? coord)
      (if-let [uri (editor-uri/editor-uri editor coord)]
        (assoc m :rf.source/uri uri)
        m)

      (flat-coord-carrier? m)
      (if-let [uri (editor-uri/editor-uri editor m)]
        (assoc m :rf.source/uri uri)
        m)

      :else
      m)))

(defn decorate
  "Walk `tree`, splicing `:rf.source/uri` onto every map that carries
  a `:source-coord` sub-map or is itself a flat-key source-coord
  carrier. Returns the decorated tree.

  `editor` follows the `editor-uri/editor-uri` vocabulary — `:vscode`
  / `:cursor` / `:windsurf` / `:zed` / `:idea` / `{:custom <template>}`.

  Pure data → data. Idempotent: re-decoration replaces an existing
  `:rf.source/uri` with the URI for the current editor."
  [tree editor]
  (cond
    (map? tree)
    ;; `:source-coord` values are terminal coord-data carriers — preserve
    ;; them verbatim instead of recursing in (which would otherwise hit
    ;; `decorate-map`'s flat-carrier branch and splice a URI onto the
    ;; coord sub-map itself, double-decorating).
    (let [stepped (reduce-kv
                    (fn [acc k v]
                      (assoc acc k (if (= k :source-coord)
                                     v
                                     (decorate v editor))))
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
