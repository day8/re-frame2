(ns re-frame.story-mcp.tools.docs
  "Docs-category tool handlers — introspection over the Story
  registry. Per IMPL-SPEC §7.2 these are the pure-read surfaces:
  `list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-assertions`, `variant->edn`."
  (:require [clojure.set :as set]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.schemas :as s]))

(defn tool-list-stories
  "Docs: all registered stories (with optional tag filters).

  `args`:
    :tags — vector of tag ids (strings or `:keyword` forms); narrows
            the result to stories whose `:tags` set intersects this.

  HOT PATH (rf2-d3iso): agents spam this tool. The variant-id slot per
  story is read from `story/variants-by-story` — a single O(V) pass
  over the variant side-table — instead of the previous O(S × V) shape
  of calling `variants-of` once per story."
  [args]
  (let [stories  (story/handlers :story)
        tags     (when-let [ts (:tags args)]
                   (set (map args/parse-keyword ts)))
        filtered (if (seq tags)
                   (into {}
                         (filter (fn [[_id body]]
                                   (seq (set/intersection tags (set (:tags body))))))
                         stories)
                   stories)
        index    (story/variants-by-story)
        payload  {:stories (vec (for [[sid body] filtered]
                                  {:id   sid
                                   :doc  (:doc body)
                                   :tags (vec (:tags body))
                                   :variants (sort (get index sid #{}))}))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-get-story
  "Docs: one story's full body."
  [args]
  (let [[sid err] (h/required-arg args :story-id)]
    (if err err
      (let [sk   (args/parse-keyword sid)
            body (story/handler-meta :story sk)]
        (if (nil? body)
          (h/error-result (str "Story not found: " (pr-str sk)))
          (let [payload {:id sk :body body :variants (sort (story/variants-of sk))}]
            (h/text-result (h/pr-edn payload) payload)))))))

(defn tool-get-variant
  "Docs: one variant's full body (`handler-meta :variant id`)."
  [args]
  (h/with-variant args
    (fn [vk body]
      (let [payload {:id vk :body body}]
        (h/text-result (h/pr-edn payload) payload)))))

(defn tool-list-tags
  "Docs: canonical tags + custom tags."
  [_args]
  (let [registered (story/list-tags)
        canonical  story/canonical-tags
        custom     (set/difference (set registered) (set canonical))
        payload    {:canonical (vec (sort-by str canonical))
                    :custom    (vec (sort-by str custom))
                    :all       (vec (sort-by str registered))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-list-modes
  "Docs: registered modes (from `reg-mode`). Returns each mode's id +
  body so agents can see the `:args` saved tuple."
  [_args]
  (let [modes   (story/handlers :mode)
        payload {:modes (vec (for [[mid body] modes]
                               {:id mid :doc (:doc body) :args (:args body)}))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn- decorator-summary
  "Project one decorator body to the EDN-safe shape — id, kind, doc,
  and the kind-specific data slots. The `:wrap` slot of a `:hiccup`
  decorator carries a closure (Stage 2's one legal closure-bearing
  slot per `:hiccup` decorator's registration site); it is dropped
  here because closures don't transport over MCP. The `:app-db-patch`
  / `:init` (frame-setup) and `:fx-id` / `:response` (fx-override)
  slots are pure data and survive verbatim."
  [id body]
  (let [kind (:kind body)]
    (cond-> {:id   id
             :kind kind
             :doc  (:doc body)}
      (= kind :hiccup)       (assoc :has-wrap? (some? (:wrap body)))
      (= kind :frame-setup)  (assoc :init          (:init body)
                                    :app-db-patch  (:app-db-patch body))
      (= kind :fx-override)  (assoc :fx-id    (:fx-id body)
                                    :response (:response body)))))

(defn tool-list-decorators
  "Docs: read-only enumeration of registered decorators (rf2-mqp1u).
  Returns each decorator's id, kind, and doc plus the kind-specific
  pure-data slots. The `:wrap` closure on `:hiccup` decorators is
  not transported — only a `:has-wrap?` boolean — because closures
  don't survive EDN serialisation; agents inspecting the rendered
  result use `preview-variant` instead. This is the read-only peer
  of the deferred `register-decorator` write surface.

  Optional `args`:

  - `:kind` (string, optional) — narrow to one decorator kind. One
    of `\"hiccup\"`, `\"frame-setup\"`, `\"fx-override\"`."
  [args]
  (let [kind-filter (some-> (:kind args) args/parse-keyword)
        decorators  (story/handlers :decorator)
        entries     (cond->> (for [[did body] decorators]
                               (decorator-summary did body))
                      kind-filter (filter #(= kind-filter (:kind %))))
        payload     {:decorators (vec entries)}]
    (h/text-result (h/pr-edn payload) payload)))

(def canonical-assertion-docs
  "Per spec/007 line 304 + IMPL-SPEC §3.5 the canonical seven
  assertions' arities."
  [{:id :rf.assert/path-equals
    :payload "[path expected]"
    :semantics "(= (get-in @app-db path) expected)"}
   {:id :rf.assert/path-matches
    :payload "[path malli-schema]"
    :semantics "Malli validate at path"}
   {:id :rf.assert/sub-equals
    :payload "[sub-vec expected]"
    :semantics "(= @(subscribe sub-vec) expected)"}
   {:id :rf.assert/dispatched?
    :payload "[event-or-pred]"
    :semantics "Was this event dispatched during play?"}
   {:id :rf.assert/state-is
    :payload "[machine-id state]"
    :semantics "Machine in state?"}
   {:id :rf.assert/no-warnings
    :payload "[]"
    :semantics "No :warning trace events since play start"}
   {:id :rf.assert/effect-emitted
    :payload "[fx-id (optional pred)]"
    :semantics "fx-id emitted during play?"}])

(defn tool-list-assertions
  "Docs: the `:rf.assert/*` canonical vocabulary + arity docs."
  [_args]
  (let [registered (story/canonical-assertion-ids)
        payload    {:canonical canonical-assertion-docs
                    :registered (vec (sort-by str registered))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-variant->edn
  "Docs: round-trippable EDN of a registered variant. Identical payload
  to `get-variant` but framed as EDN-only — the `structuredContent`
  slot is omitted so agents that want strict EDN parse the text."
  [args]
  (h/with-variant args
    (fn [_vk body]
      (h/text-result (h/pr-edn body)))))

(defn- md-h1 [s] (str "# " s "\n\n"))
(defn- md-h2 [s] (str "\n## " s "\n\n"))
(defn- md-h3 [s] (str "\n### " s "\n\n"))

(defn- md-kv-table
  "Render a small map as a GitHub-flavoured markdown `| key | value |` table.
  Empty maps render as a single em-dash so the section never reads as a
  visual hole. Values that are themselves maps / vectors / sets are
  `pr-str`-rendered — agents pasting this can re-parse them as EDN."
  [m]
  (if (empty? m)
    "—\n"
    (str "| key | value |\n|---|---|\n"
         (apply str
                (for [[k v] (sort-by str m)]
                  (str "| `" (pr-str k) "` | `" (pr-str v) "` |\n"))))))

(defn- md-bullet-list [xs]
  (if (empty? xs)
    "—\n"
    (apply str (for [x xs] (str "- `" (pr-str x) "`\n")))))

(defn- render-story-markdown
  "Project one story's registered body + its variant ids to a
  GitHub-flavoured markdown document. Suitable for agent-paste into
  an issue tracker or chat. The variant bodies are NOT inlined —
  they get summary entries; an agent that wants per-variant detail
  calls `get-variant` for the EDN form."
  [story-id story-body variant-ids]
  (str
    (md-h1 (str "Story `" story-id "`"))
    (when (:doc story-body)
      (str (:doc story-body) "\n"))
    (md-h2 "Default args")
    (md-kv-table (:args story-body))
    (md-h2 "Argument types")
    (md-kv-table (:argtypes story-body))
    (md-h2 "Tags")
    (md-bullet-list (sort (:tags story-body)))
    (md-h2 "Decorators")
    (md-bullet-list (:decorators story-body))
    (md-h2 "Variants")
    (if (seq variant-ids)
      (apply str
             (for [vid (sort variant-ids)
                   :let [vbody (story/handler-meta :variant vid)]]
               (str (md-h3 (str "`" vid "`"))
                    (when (:doc vbody)
                      (str (:doc vbody) "\n\n"))
                    "**Args**\n\n"
                    (md-kv-table (:args vbody))
                    "**Tags**\n\n"
                    (md-bullet-list (sort (:tags vbody))))))
      "—\n")))

(defn tool-get-docs-markdown
  "Docs: render a story's documentation as GitHub-flavoured Markdown
  (rf2-i0kyy). The existing `get-story` / `get-variant` tools return
  EDN — useful for programmatic consumption but not the right shape
  when an agent wants to paste a docs blurb into an issue tracker or
  chat. This tool composes story `:doc` + per-variant `:doc` + args /
  argtypes / tags / decorators into a single GFM string.

  Returns the markdown text both in the wire-canonical `:content`
  slot and as a `:markdown` structuredContent slot (paste-target for
  agent hosts that surface structured content)."
  [args]
  (let [[sid err] (h/required-arg args :story-id)]
    (if err err
      (let [sk   (args/parse-keyword sid)
            body (story/handler-meta :story sk)]
        (if (nil? body)
          (h/error-result (str "Story not found: " (pr-str sk)))
          (let [variants (sort (story/variants-of sk))
                md       (render-story-markdown sk body variants)
                payload  {:story-id sk
                          :markdown md
                          :variants (vec variants)}]
            (h/text-result md payload)))))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Docs-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "list-stories"
    :category       :docs
    :description    "All registered stories, optionally filtered by tags. Each entry carries id, doc, tags, and child variant ids."
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:tags {:type "array" :items s/kw-or-string
                                        :description "Optional tag filter; story `:tags` set must intersect."}})
                  :additionalProperties false}
    :handler     tool-list-stories}

   {:name           "get-story"
    :category       :docs
    :description    "Return one story's full body (`:doc`, `:component`, `:decorators`, `:args`, ... + its variant ids)."
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:story-id s/kw-or-string})
                  :required ["story-id"]
                  :additionalProperties false}
    :handler     tool-get-story}

   {:name           "get-variant"
    :category       :docs
    :description    "Return one variant's full body (the resolved EDN, with `:extends` already applied at registration time)."
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-get-variant}

   {:name           "list-tags"
    :category       :docs
    :description    "Canonical tags (`:dev :docs :test :screenshot :experimental :internal :agent`) + any custom tags registered by the project."
    :typicalTokens  100
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-tags}

   {:name           "list-modes"
    :category       :docs
    :description    "Registered modes (Chromatic-style saved tuples of args). Each entry is `{:id :doc :args}`."
    :typicalTokens  200
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-modes}

   {:name           "list-decorators"
    :category       :docs
    :description    (str "Read-only enumeration of registered decorators (rf2-mqp1u). Each entry carries "
                         "`:id`, `:kind`, `:doc` plus the kind-specific pure-data slots: `:has-wrap?` "
                         "for `:hiccup` decorators (the `:wrap` closure itself doesn't transport over "
                         "MCP); `:init` + `:app-db-patch` for `:frame-setup`; `:fx-id` + `:response` "
                         "for `:fx-override`. The read-only peer of the deferred `register-decorator` "
                         "write surface — closures don't transport, so the write side stays out of "
                         "scope, but the read side is cheap. Optional `:kind` arg narrows to one "
                         "decorator kind.")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:kind {:type "string"
                                        :description "Optional filter — only return decorators of this kind."
                                        :enum ["hiccup" "frame-setup" "fx-override"]}})
                  :additionalProperties false}
    :handler     tool-list-decorators}

   {:name           "list-assertions"
    :category       :docs
    :description    "The seven canonical `:rf.assert/*` events with payload arity + semantics, plus any project-registered assertion ids."
    :typicalTokens  500
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-assertions}

   {:name           "variant->edn"
    :category       :docs
    :description    "Round-trippable EDN of a registered variant. Text result only (no structuredContent); use this when you want byte-stable EDN."
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-variant->edn}

   {:name           "get-docs-markdown"
    :category       :docs
    :description    (str "Render a story's documentation as GitHub-flavoured Markdown (rf2-i0kyy). "
                         "Composes the story `:doc` + per-variant `:doc` + args / argtypes / tags / "
                         "decorators into a single paste-ready string. The other docs tools "
                         "(`get-story`, `get-variant`, `variant->edn`) return EDN — useful for "
                         "programmatic consumption but not the right shape when an agent wants to drop "
                         "a docs blurb into an issue tracker or chat. The markdown is returned in the "
                         "wire-canonical `:content` text slot and as a `:markdown` structuredContent "
                         "slot for hosts that surface structured content separately.")
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:story-id s/kw-or-string})
                  :required ["story-id"]
                  :additionalProperties false}
    :handler     tool-get-docs-markdown}])
