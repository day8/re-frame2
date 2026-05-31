(ns re-frame2-pair-mcp.tools.read-dom
  "Tool: read-dom — first-class view-plane READ (rf2-nfjil).

  ## Why a view-plane read primitive

  re-frame2-pair has rich DATA-plane reads — `snapshot` / `get-path`
  (app-db), `trace-window` / `watch-epochs` (epoch history),
  `list-subscriptions` (the reactive sub-cache). What it lacked: a
  way to ask what the app actually RENDERED. Answering \"did the UI
  update?\", \"what does the rendered node / attribute say?\" meant
  hand-rolling an `eval-cljs` form around `js/document.querySelectorAll`
  on every call — fiddly, easy to get wrong (text never capped → a
  multi-megabyte innerText blows the wire cap), and not discoverable in
  the catalogue. The App-db stale-frame bug (rf2-yng0y) was only
  diagnosable by reading the DOM repeatedly; this op makes that a
  first-class gesture.

  `read-dom` generalises the source-coord reads (`handler-meta`'s
  `:rf.source/uri`, which return where a handler is DEFINED) to
  rendered-CONTENT reads — what's on screen NOW.

  ## Naming — the `read-` verb (NAMING.md §The verb table)

  Named `read-dom` (not `dom-read`) to ride the catalogued `read-<thing>`
  verb prefix: a cheap reflection of already-rendered state — the render
  already happened; this is a no-recompute read of its output, never a
  fetch that triggers a render. The verb-vocab conformance linter
  (`tools/mcp-conformance/wire-vocab`) classifies tool names by prefix;
  `read-` is conformant with zero catalogue churn.

  ## Pairs with `:await-render` (rf2-gfu33)

  `dispatch {:await-render true}` resolves only after the substrate
  has flushed the new state to the DOM. The natural follow-up is
  `read-dom` — `dispatch → settle → read` is now a deterministic
  three-step gesture with no manual requestAnimationFrame dance.

  ## Read-only by construction

  The eval form calls `querySelectorAll` and reads `textContent` /
  attribute strings only — it never assigns a property, dispatches an
  event, or mutates a node. The descriptor carries the idempotent
  read-only annotations so agent hosts auto-approve it.

  ## Where the work happens — browser-side, capped at the source

  The whole read is composed as a single CLJS form sent over nREPL
  and evaluated in the browser. Crucially the per-node text cap and
  the matched-node `:limit` are applied INSIDE that form, so only the
  already-bounded EDN crosses the wire — a 5 MB `<pre>` blob never
  leaves the tab. Over-cap text is replaced with the framework's
  size-elision marker shape `{:rf.size/large-elided {...}}` (the same
  convention `get-path` / `snapshot` emit, per rf2-urjnc) so the agent
  recognises an elision at a glance. The wire-boundary cap step
  (`tools.cljs` §`:apply-cap`) remains the backstop.

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

(def ^:private default-attrs
  "Curated default attribute set when the caller omits `:attrs`. The
  structural attrs that carry rendered identity / state, plus a
  `data-*` / `aria-*` prefix sweep applied browser-side (see
  `read-dom-form`). Kept small so the common read stays terse; callers
  wanting an exact set pass `:attrs` explicitly."
  ["id" "class" "role" "type" "name" "value" "href" "title" "placeholder"
   "disabled" "checked" "selected" "hidden"])

(defn- parse-attrs-arg
  "Normalise the `:attrs` arg into a vector of attribute-name strings,
  or nil when omitted (⇒ the curated default set rides browser-side).
  Accepts a JS array, a CLJS vector, or a comma / whitespace-separated
  string."
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
  "Build the browser-side CLJS eval form (rf2-nfjil). The whole read —
  querySelectorAll, per-node text cap, attribute collection, node
  `:limit` — runs in the tab so only bounded EDN crosses the wire.

  `attrs` is either an explicit vector of attribute-name strings or nil
  (⇒ the default set + a `data-*` / `aria-*` prefix sweep). The form is
  read-only: it only reads `textContent` and attribute strings."
  [selector sub-selector limit max-text attrs]
  (let [;; Either the explicit attr vector or the curated default. Both
        ;; emitted as EDN literals via pr-str so the browser-side form
        ;; gets a clean CLJS vector.
        attr-list      (or attrs default-attrs)
        attr-edn       (pr-str (vec attr-list))
        ;; When no explicit :attrs was passed, also sweep every data-* /
        ;; aria-* attribute the node carries — the view-plane idiom for
        ;; surfacing rendered state. With an explicit list the caller is
        ;; in control, so we do NOT add the sweep.
        prefix-sweep?  (nil? attrs)]
    (str
      "(let [doc (and (exists? js/document) js/document)]"
      "  (if-not doc"
      "    {:ok? false :reason :rf.error/read-dom-no-document}"
      "    (try"
      "      (let [sel " (pr-str selector)
      "            nodes (array-seq (.querySelectorAll doc sel))"
      "            scoped (if " (pr-str (some? sub-selector))
      "                     (mapcat (fn [n] (array-seq (.querySelectorAll n " (pr-str (or sub-selector "")) "))) nodes)"
      "                     nodes)"
      "            total (count scoped)"
      "            lim " limit
      "            want (take lim scoped)"
      "            max-text " max-text
      "            attr-names " attr-edn
      "            read-attr (fn [el nm] (when (.hasAttribute el nm) [nm (.getAttribute el nm)]))"
      "            named-attrs (fn [el] (into {} (keep #(read-attr el %) attr-names)))"
      "            prefix-attrs (fn [el]"
      "                           (if-not " (pr-str prefix-sweep?) " {}"
      "                             (into {}"
      "                               (keep (fn [a]"
      "                                       (let [nm (.-name a)]"
      "                                         (when (or (.startsWith nm \"data-\")"
      "                                                   (.startsWith nm \"aria-\"))"
      "                                           [nm (.-value a)])))"
      "                                     (array-seq (.-attributes el))))))"
      "            node->edn (fn [el]"
      "                        (let [t (.-textContent el)"
      "                              t (if (string? t) t \"\")"
      "                              tn (count t)"
      "                              text (if (> tn max-text)"
      "                                     {:rf.size/large-elided"
      "                                      {:type :dom-text :chars tn"
      "                                       :preview (subs t 0 (min tn 120))}}"
      "                                     t)]"
      "                          {:tag (str/lower-case (or (.-tagName el) \"\"))"
      "                           :text text"
      "                           :attrs (merge (named-attrs el) (prefix-attrs el))}))]"
      "        {:ok? true"
      "         :selector sel"
      (when sub-selector
        (str "         :sub-selector " (pr-str sub-selector)))
      "         :count total"
      "         :truncated? (> total (count want))"
      "         :nodes (mapv node->edn want)})"
      "      (catch :default e"
      "        {:ok? false"
      "         :reason :rf.error/read-dom-bad-selector"
      "         :selector " (pr-str selector)
      "         :message (.-message e)}))))")))

(defn read-dom-tool
  "MCP `read-dom` handler. Reads rendered DOM content by CSS selector
  and returns capped per-node text + attrs as EDN. Read-only.

  See the ns docstring for the full wire contract. The eval form runs
  browser-side (caps applied at the source); the wire-boundary cap step
  in `tools.cljs` is the backstop."
  [conn raw-args]
  (let [selector     (wire/arg raw-args :selector)
        sub-selector (let [s (wire/arg raw-args :sub-selector)]
                       (when (and (string? s) (not (str/blank? s))) s))
        limit        (let [l (wire/arg raw-args :limit)]
                       (if (and (number? l) (pos? l)) (long l) default-limit))
        max-text     (let [m (wire/arg raw-args :max-text)]
                       (if (and (number? m) (pos? m)) (long m) default-max-text))
        attrs        (parse-attrs-arg (wire/arg raw-args :attrs))
        build-id     (wire/arg-build conn raw-args)]
    (cond
      (or (nil? selector) (and (string? selector) (str/blank? selector)))
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :missing-selector
           :hint "usage: read-dom {selector '#app .counter' [sub-selector '.title'] [limit 50] [max-text 2000] [attrs [\"id\" \"data-state\"]] [build :app]}"}))

      :else
      (let [form (read-dom-form selector sub-selector limit max-text attrs)]
        (probe/eval-after-runtime!
          conn build-id form :read-dom-failed
          (fn [envelope] (wire/ok-text envelope)))))))
