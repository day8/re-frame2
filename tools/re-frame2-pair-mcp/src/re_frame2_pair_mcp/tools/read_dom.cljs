(ns re-frame2-pair-mcp.tools.read-dom
  "Tool: read-dom — the RAW DOM plane read (rf2-nfjil).

  ## The two DOM-read planes (read-dom vs read-ui)

  re-frame2-pair carries TWO rendered-state reads, at different LAYERS —
  they are NOT duplicates:

  - `read-dom` (this op) = the **raw DOM plane**: a CSS selector →
    matched nodes `{:tag :text :attrs}`. Multi-node, exact, NO re-frame2
    awareness. \"What does this exact node SAY?\"
  - `read-ui` = the **re-frame2 view plane**: rides the `data-rf-view`
    map → content PLUS the producing ENTITY (view-id, source-coord,
    subs-read, render-key). \"What is this view, and what produced it?\"

  Pick `read-dom` when you have a selector and want raw content across N
  matched nodes; pick `read-ui` when you want a view's content AND its
  re-frame2 provenance in one round-trip.

  ## Why a raw-DOM read primitive

  re-frame2-pair has rich DATA-plane reads — `snapshot` / `get-path`
  (app-db), `trace-window` / `watch-epochs` (epoch history),
  `list-subscriptions` (the reactive sub-cache). What it lacked: a way to
  ask what the app actually RENDERED, by an explicit selector, with no
  re-frame2 awareness. Answering \"did the UI update?\", \"what does the
  rendered node / attribute say?\" meant hand-rolling an `eval-cljs` form
  around `js/document.querySelectorAll` on every call — fiddly, easy to
  get wrong (text never capped → a multi-megabyte innerText blows the
  wire cap), and not discoverable in the catalogue. The App-db
  stale-frame bug (rf2-yng0y) was only diagnosable by reading the DOM
  repeatedly; this op makes that a first-class gesture.

  ## One shared DOM-read core with read-ui (rf2-q0r7e)

  Both planes now route through the SAME runtime ns
  (`re-frame2-pair.runtime`): read-dom emits
  `(re-frame2-pair.runtime/dom-read {...})` and read-ui emits
  `(re-frame2-pair.runtime/ui-read {...})`, via the SAME `eval-form` DSL
  (`tools.eval-form`) and the SAME single-eval prelude
  (`probe/eval-after-runtime!`). The per-node projection (tag + capped
  text + attribute map) lives ONCE, in the runtime's `node->content`.

  This folds away a maintenance hazard. read-dom previously INLINED its
  whole browser-side core as a raw eval string, while read-ui called the
  runtime fn — two divergent eval forms that rotted apart. rf2-w2mjm: the
  inlined read-dom form lowered the tag with `(str/lower-case …)`, but
  the BARE browser cljs-eval context the inlined string ran in aliases
  NOTHING (no `:require`), so `str` was unresolved, the whole form nilled
  out, and EVERY read-dom call came back `:read-dom-blank-result` — while
  read-ui, calling the runtime ns (which DOES require `clojure.string`),
  stayed green. Both ops gate on the preload runtime being present
  (`ensure-runtime!`), so the inlining bought nothing; folding read-dom
  onto the runtime fn removes the alias trap by construction — the core
  now runs in a namespace with real requires.

  ## Naming — the `read-` verb (NAMING.md §The verb table)

  Named `read-dom` (not `dom-read`) to ride the catalogued `read-<thing>`
  verb prefix: a cheap reflection of already-rendered state — the render
  already happened; this is a no-recompute read of its output, never a
  fetch that triggers a render. (The runtime fn it calls keeps the
  historical `dom-read` spelling — see the runtime ns's MCP-vs-runtime
  naming table.) The verb-vocab conformance linter
  (`tools/mcp-conformance/wire-vocab`) classifies tool names by prefix;
  `read-` is conformant with zero catalogue churn.

  ## Pairs with `:await-render` (rf2-gfu33)

  `dispatch {:await-render true}` resolves only after the substrate
  has flushed the new state to the DOM. The natural follow-up is
  `read-dom` — `dispatch → settle → read` is now a deterministic
  three-step gesture with no manual requestAnimationFrame dance.

  ## Read-only by construction

  The runtime fn calls `querySelectorAll` and reads `textContent` /
  attribute strings only — it never assigns a property, dispatches an
  event, or mutates a node. The descriptor carries the idempotent
  read-only annotations so agent hosts auto-approve it.

  ## Where the work happens — browser-side, capped at the source

  The whole read runs in the runtime fn in the tab. Crucially the
  per-node text cap and the matched-node `:limit` are applied INSIDE that
  fn, so only the already-bounded EDN crosses the wire — a 5 MB `<pre>`
  blob never leaves the tab. Over-cap text is replaced with the
  framework's size-elision marker shape `{:rf.size/large-elided {...}}`
  (the same convention `get-path` / `snapshot` emit, per rf2-urjnc) so
  the agent recognises an elision at a glance. The wire-boundary cap step
  (`tools.cljs` §`:apply-cap`) remains the backstop.

  ## Privacy — value-redact derived content (rf2-p9scds)

  Rendered DOM text / attribute values can carry a secret copied out of a
  declared-sensitive app-db slot — a non-app-db position the path-based
  `elide-wire-value` walker can never reach. Under the off-box egress
  posture (the published-build default) the runtime value-redacts the
  matched nodes through `re-frame.core/redact-derived-slots` against the
  operating frame's declared-`:sensitive?` values, so a rendered secret
  lands as `:rf/redacted` before crossing the off-box wire. The optional
  `:frame` arg names the source frame; raw rendered content is the trusted-
  local read (launched with `--allow-sensitive-reads`); an unresolvable
  frame under the off-box gate fails closed with `:ambiguous-frame`.

  ## Wire contract

  Input (MCP args):
    :selector      CSS selector (required), e.g. \"#app .counter\".
    :sub-selector  optional CSS selector run RELATIVE to each matched
                   node (`node.querySelectorAll(sub)`) — narrows a
                   coarse match (a card) to its inner parts (the
                   card's `.title`). When supplied, the result's
                   `:nodes` are the sub-matches.
    :limit         max matched nodes to return (default 50). Excess
                   nodes are dropped and `:truncated?` flips true; the
                   total match `:count` still reports the full tally.
    :max-text      per-node text-character cap (default 2000). Text
                   longer than this is replaced with a
                   `:rf.size/large-elided` marker carrying `:chars`.
    :attrs         vector / JS-array of attribute names to include
                   (e.g. [\"id\" \"class\" \"data-state\"]). When omitted,
                   a curated default set rides — the structural attrs
                   (id / class / role / type / name / value / href /
                   …) PLUS every `data-*` / `aria-*` attribute the node
                   carries (the re-frame2 view-plane idiom for
                   surfacing rendered state).
    :build         shadow-cljs build id (as every other op).

  Output EDN (success):
    {:ok?       true
     :selector  \"<selector>\"
     :sub-selector \"<sub>\"          ; only when supplied
     :count     <total matches>       ; full tally, pre-:limit
     :truncated? <bool>               ; true iff count > returned nodes
     :nodes [{:tag   \"div\"           ; lower-case tag name
              :text  \"Count: 3\"      ; textContent, capped
              :attrs {\"id\" \"c\" \"data-count\" \"3\" ...}}
             ...]}

  When no element is in the document (no app mounted, or the selector
  matched nothing) → {:ok? true :count 0 :nodes []}. A malformed CSS
  selector (querySelectorAll throws SyntaxError) →
  {:ok? false :reason :rf.error/read-dom-bad-selector :selector ...}.
  No DOM at all (server-side / headless eval target) →
  {:ok? false :reason :rf.error/read-dom-no-document}."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def default-limit
  "Max matched nodes returned by default. Excess nodes are dropped and
  `:truncated?` flips true; the full match `:count` is still reported."
  50)

(def default-max-text
  "Per-node `textContent` character cap (default). Text longer than this
  is replaced with the `:rf.size/large-elided` marker carrying `:chars`
  — same shape `get-path` / `snapshot` emit for over-threshold app-db
  slots (rf2-urjnc), so the agent recognises an elision uniformly."
  2000)

(defn- parse-attrs-arg
  "Normalise the `:attrs` arg into a vector of attribute-name strings,
  or nil when omitted (⇒ the curated default set + a `data-*` / `aria-*`
  sweep rides runtime-side). Accepts a JS array, a CLJS vector, or a
  comma / whitespace-separated string."
  [raw]
  (cond
    (nil? raw)        nil
    (array? raw)      (mapv str (js->clj raw))
    (vector? raw)     (mapv str raw)
    (sequential? raw) (mapv str raw)
    (string? raw)     (let [parts (->> (str/split raw #"[,\s]+")
                                       (remove str/blank?)
                                       vec)]
                        (when (seq parts) parts))
    :else             nil))

(defn- read-dom-form
  "Compose a single `(re-frame2-pair.runtime/dom-read {...})` form via the
  shared `eval-form` DSL (rf2-q0r7e) — the SAME plumbing read-ui uses. The
  runtime fn does the whole read (querySelectorAll, per-node text cap,
  attribute collection, node `:limit`) in the tab, so only bounded EDN
  crosses the wire.

  Only present keys ride: an explicit `attrs` vector ⇒ exactly those
  names (no `data-*`/`aria-*` sweep); omitting it (nil) ⇒ the curated
  default set + the sweep, resolved runtime-side. A `frame` keyword (when
  supplied) names the frame whose declared-sensitive app-db values the
  rendered nodes are value-redacted against (rf2-p9scds); omitting it lets
  the runtime resolve the operating frame. The runtime fn is read-only
  (querySelectorAll + textContent + attribute strings)."
  [selector sub-selector limit max-text attrs frame]
  (let [opts (cond-> {:selector selector
                      :limit    limit
                      :max-text max-text}
               (some? sub-selector) (assoc :sub-selector sub-selector)
               (some? attrs)        (assoc :attrs attrs)
               (some? frame)        (assoc :frame frame))]
    (ef/emit (ef/rt-call 'dom-read opts))))

(defn read-dom-tool
  "MCP `read-dom` handler — the raw DOM plane. Reads rendered DOM content
  by CSS selector and returns capped per-node text + attrs as EDN.
  Read-only.

  See the ns docstring for the full wire contract. The form is a thin
  `(re-frame2-pair.runtime/dom-read {...})` call (shared `eval-form`
  plumbing with read-ui); the runtime fn applies caps at the source and
  the wire-boundary cap step in `tools.cljs` is the backstop."
  [conn raw-args]
  (let [selector     (wire/arg raw-args :selector)
        sub-selector (let [s (wire/arg raw-args :sub-selector)]
                       (when (and (string? s) (not (str/blank? s))) s))
        limit        (let [l (wire/arg raw-args :limit)]
                       (if (and (number? l) (pos? l)) (long l) default-limit))
        max-text     (let [m (wire/arg raw-args :max-text)]
                       (if (and (number? m) (pos? m)) (long m) default-max-text))
        attrs        (parse-attrs-arg (wire/arg raw-args :attrs))
        ;; rf2-p9scds — the frame whose declared-sensitive app-db values the
        ;; rendered nodes are value-redacted against (off-box gate). Omitted
        ;; ⇒ the runtime resolves the operating frame and fails closed on an
        ;; ambiguous one.
        frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        build-id     (wire/arg-build conn raw-args)]
    (cond
      (or (nil? selector) (and (string? selector) (str/blank? selector)))
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :missing-selector
           :hint "usage: read-dom {selector '#app .counter' [sub-selector '.title'] [limit 50] [max-text 2000] [attrs [\"id\" \"data-state\"]] [build :app]}"}))

      :else
      (let [form (read-dom-form selector sub-selector limit max-text attrs frame)]
        ;; The runtime's `dom-read` ALWAYS returns a map, so a non-map at
        ;; `on-value` means a BLANK eval (rf2-r5erl). The shared
        ;; `probe/map-result-or-blank` projects a map → `ok-text` with the
        ;; `:build` echo, or a blank → a structured `:ok? false` error (so
        ;; `ok-text` never sees `nil` and emits the `null` structuredContent
        ;; the SDK rejects). read-ui carries the identical projection. The
        ;; per-tool blank `:reason` / `:hint` / `:selector` echo ride in here.
        (probe/eval-after-runtime!
          conn build-id form :read-dom-failed
          (probe/map-result-or-blank
            build-id :rf.error/read-dom-blank-result
            (str "read-dom's browser eval returned a blank / "
                 "non-map value. This is almost always the "
                 "connection, not the form: read-dom calls the "
                 "preloaded runtime fn (re-frame2-pair.runtime/"
                 "dom-read) — the same shared plumbing read-ui "
                 "uses — so an unresolved-alias miss can no "
                 "longer nil it out. A blank result means the "
                 "runtime did not answer the eval (a dropped "
                 "WebSocket, or the preload was wiped by a full "
                 "page refresh). Run discover-app to confirm "
                 ":liveness :fresh, then retry.")
            {:selector selector}))))))
