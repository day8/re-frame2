(ns re-frame2-pair-mcp.tools.read-ui
  "Tool: read-ui — the typed `ui/read` (a.k.a. `view/rendered`) op
  (rf2-3bu3d.1).

  ## The two DOM-read planes (read-ui vs read-dom)

  re-frame2-pair carries TWO rendered-state reads, at different LAYERS —
  they are NOT duplicates:

  - `read-ui` (this op) = the **re-frame2 view plane**: rides the
    `data-rf-view` map → content PLUS the producing ENTITY (view-id,
    source-coord, subs-read, render-key). \"What is this view, and what
    produced it?\"
  - `read-dom` = the **raw DOM plane**: a CSS selector → matched nodes
    `{:tag :text :attrs}`. Multi-node, exact, NO re-frame2 awareness.
    \"What does this exact node SAY?\"

  Pick `read-ui` when you want a view's content AND its re-frame2
  provenance in one round-trip; pick `read-dom` when you have a selector
  and want raw content across N matched nodes.

  ## Why a view→content+entity read primitive

  re-frame2-pair has a rich DOM-to-SOURCE bridge (`dom/source-at`,
  `dom/describe`, `dom/find-by-src`) that maps a gesture/selector →
  source-coord + registration meta — \"where in the code did this come
  from?\". And `read-dom` returns raw rendered text/attrs by an explicit
  CSS selector. What neither answers is the most common UI-pairing
  question: \"what does the thing I'm looking at SHOW, and which re-frame2
  ENTITY produced it?\" — given a VIEW-ID (or a point / selector), in ONE
  round-trip, return BOTH the rendered subtree AND the producing entity
  (view-id, source-coord, render-key, subs-read).

  Before this op, that meant hand-rolling an `eval-cljs` querySelectorAll +
  textContent slice with GUESSED selectors, then a SECOND round-trip to
  map the node back to a view. `read-ui` first-classes the whole gesture.

  ## One shared DOM-read core with read-dom (rf2-q0r7e)

  Both planes route through the SAME runtime ns
  (`re-frame2-pair.runtime`): read-ui emits
  `(re-frame2-pair.runtime/ui-read {...})` and read-dom emits
  `(re-frame2-pair.runtime/dom-read {...})`, via the SAME `eval-form` DSL
  (`tools.eval-form`) and the SAME single-eval prelude
  (`probe/eval-after-runtime!`). The per-node projection (tag + capped
  text + attribute map) lives ONCE, in the runtime's `node->content`; the
  view plane layers entity-resolution + privacy elision ON TOP of it. A
  fix or a regression-guard on the shared form covers BOTH ops, so neither
  can silently break alone (the rf2-w2mjm failure mode, where read-dom's
  separately-inlined eval form nilled out while read-ui stayed green).

  ## Naming — the `ui/read` op, the `read-` verb

  The bead's conceptual op is `ui/read` (a.k.a. `view/rendered`). The MCP
  wire name is `read-ui`: it rides the catalogued `read-<thing>` verb
  prefix (NAMING.md §The verb table; the verb-vocab conformance linter
  classifies tool names by prefix), so it lands with zero catalogue churn
  — same posture as its `read-dom` sibling. A no-recompute read of state
  the substrate ALREADY rendered.

  ## Riding the view↔DOM map (zero testids)

  The browser-side work happens in `re-frame2-pair.runtime/ui-read`, which
  reuses the EXISTING view-id↔DOM-node mapping: every registered view's
  rendered root carries `data-rf-view=\"<id>\"` (Spec 006 §View tagging
  contract; Spec-Schemas §`:rf/view-id-attr`) — the SAME attribute the
  Xray pink hover-highlight resolves (`apply-view-highlight!`). The
  sibling `data-rf2-source-coord` lands on the same root. So `read-ui`
  works on ANY re-frame2 app with NO app-specific test ids — it never
  guesses a selector and never re-implements view discovery.

  ## Privacy — value-redact derived content (rf2-p9scds)

  The runtime value-redacts the WHOLE rendered `:content` (text AND attrs)
  through `re-frame.core/redact-derived-slots` against the frame's declared-
  `:sensitive?` app-db values, under the off-box egress posture (the
  published-build default). Rendered DOM text / attrs sit at a non-app-db
  position the path-based `elide-wire-value` walker can never reach — a bare
  `(elide-wire-value text {:frame …})` over the anonymous string was a no-op
  for a secret copied INTO the DOM, and attrs were never touched. The value-
  based dual catches both. A hard per-node `max-text` cap trims the common
  large case first; raw content is the trusted-local read (launched with
  `--allow-sensitive-reads`); an unresolvable frame fails closed with
  `:ambiguous-frame`. The wire-boundary cap step (`tools.cljs` §`:apply-cap`)
  remains the backstop.

  ## Read-only by construction

  The runtime fn only reads `textContent` / attribute strings /
  `elementFromPoint` / `querySelector` — never a write, a dispatch, or a
  node mutation. The descriptor carries the read-only annotations so agent
  hosts auto-approve it.

  ## Wire contract

  Input (MCP args — pass exactly one entry point; precedence
  view-id > point > selector):
    :view-id   keyword/string view id, e.g. \":my.app/header\" — resolves
               `[data-rf-view='<id>']`.
    :point     {:x N :y N} — `elementFromPoint`, then walk up to the
               producing view root. \"What's under the cursor?\"
    :selector  CSS selector — `querySelector`, then walk up to the view.
    :max-text  per-node textContent char cap (default 2000). Over-cap text
               collapses to a `:rf.size/large-elided` marker.
    :frame     operating-frame override (the `:subs-read` slice + elision
               registry).
    :build     shadow-cljs build id (as every other op).

  Output EDN (success):
    {:ok?     true
     :via     :view-id | :point | :selector
     :entity  {:view-id <id>
               :source-coord {:ns :handler-id :line :col :file}
               :render-key <int>
               :subs-read [<query-v> ...]}
     :content {:tag \"div\"
               :text <string | :rf.size/large-elided marker>
               :attrs {<name> <value> ...}}}

  Failure (each :ok? false, never a silent empty):
    :no-document             — no DOM (server-side / headless eval target).
    :no-target-arg           — none of :view-id / :point / :selector given.
    :no-element              — the entry point matched nothing.
    :rf.error/ui-read-bad-selector — a malformed CSS selector."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def default-max-text
  "Per-node `textContent` character cap (default). Text longer than this
  is replaced with the `:rf.size/large-elided` marker carrying `:chars`
  — the same shape `get-path` / `snapshot` emit for over-threshold app-db
  slots (rf2-urjnc), so the agent recognises an elision uniformly."
  2000)

(defn- frame-edn
  "Render the `:frame` opt for the runtime call. A supplied frame string
  (`\":stories\"` / `\"stories\"`) coerces to a keyword; nil drops the key
  so the runtime resolves the operating frame itself."
  [frame]
  (some-> frame (str/replace #"^:" "") keyword))

(defn- read-ui-form
  "Compose the single `(re-frame2-pair.runtime/ui-read {...})` form via the
  shared `eval-form` DSL (rf2-q0r7e) — the SAME plumbing read-dom uses.
  Only present entry points / knobs ride (the runtime enforces the
  precedence view-id > point > selector). Pure form-builder, no
  connection: callable directly by the form-composition regression guard
  so the alias-trap check covers BOTH ops, not just read-dom."
  [vid pt selector max-text frame]
  (let [opts (cond-> {:max-text max-text}
               (some? vid)      (assoc :view-id vid)
               (some? pt)       (assoc :point pt)
               (some? selector) (assoc :selector selector)
               (some? frame)    (assoc :frame (frame-edn frame)))]
    (ef/emit (ef/rt-call 'ui-read opts))))

(defn read-ui-tool
  "MCP `read-ui` handler — the typed `ui/read` op. Composes a single
  `(re-frame2-pair.runtime/ui-read {...})` form and forwards the
  runtime's structured + elided content + producing-entity envelope.
  Read-only; the runtime does the elision (see the ns docstring).

  Exactly one entry point is required (`:view-id` / `:point` /
  `:selector`); the runtime enforces precedence and returns
  `:no-target-arg` when none is supplied."
  [conn raw-args]
  (let [view-id  (wire/arg raw-args :view-id)
        point    (wire/arg raw-args :point)
        selector (wire/arg raw-args :selector)
        max-text (let [m (wire/arg raw-args :max-text)]
                   (if (and (number? m) (pos? m)) (long m) default-max-text))
        frame    (wire/arg raw-args :frame)
        build-id (wire/arg-build conn raw-args)]
    (if-not (or (some? view-id) (some? point) (some? selector))
      (js/Promise.resolve
        (wire/err-text
          {:ok?  false :reason :no-target-arg
           :hint "usage: read-ui {view-id \":my.app/header\" | point {x 10 y 20} | selector \"#save\"} [max-text 2000] [frame \":stories\"] [build :app]"}))
      (let [;; Build the runtime opts map. view-id rides as a keyword when
            ;; it looks like one (the registry id is a keyword); a bare
            ;; string id passes through. point coerces its JS object to a
            ;; CLJS map. Only present keys are included so the runtime's
            ;; precedence (view-id > point > selector) is unambiguous.
            vid   (cond
                    (nil? view-id)    nil
                    (and (string? view-id)
                         (pos? (count view-id))
                         (= ":" (subs view-id 0 1)))
                    (let [body  (subs view-id 1)
                          slash (.indexOf body "/")]
                      (if (neg? slash)
                        (keyword body)
                        (keyword (subs body 0 slash) (subs body (inc slash)))))
                    :else view-id)
            pt    (when (some? point)
                    (let [m (js->clj point :keywordize-keys true)]
                      (when (map? m) (select-keys m [:x :y]))))
            form  (read-ui-form vid pt selector max-text frame)]
        ;; The runtime's `ui-read` ALWAYS returns a map, so a non-map at
        ;; `on-value` means a BLANK eval (rf2-r5erl, the sibling of
        ;; read-dom's bug). The shared `probe/map-result-or-blank` projects
        ;; a map → `ok-text` with the `:build` echo (rf2-8t3ct / rf2-fmho5),
        ;; or a blank → a structured `:ok? false` error so `ok-text` never
        ;; sees `nil` and emits the `null` structuredContent the SDK rejects.
        ;; read-dom carries the identical projection.
        (probe/eval-after-runtime!
          conn build-id form :rf.error/read-ui-failed
          (probe/map-result-or-blank
            build-id :rf.error/read-ui-blank-result
            (str "read-ui's browser eval returned a blank "
                 "value (no map envelope). Reload the app tab "
                 "so the re-frame2-pair runtime reconnects, "
                 "then retry; run discover-app to confirm "
                 ":liveness :fresh.")))))))
