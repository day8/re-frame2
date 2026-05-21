(ns day8.re-frame2-machines-viz.scxml
  "SCXML (W3C State Chart XML) import/export for re-frame2 machine
  definitions (rf2-6urjd · v1.1).

  SCXML is the W3C standard for statecharts. Round-tripping through
  SCXML lets re-frame2 machines be shared with non-CLJS tooling —
  external workflow systems, Erlang `gen_statem`-derived tools,
  Stately's importers, the xstate-visualizer. Same pure-data posture
  as `mermaid.cljc`: a machine definition in, an XML string out;
  and the inverse on the read side.

  ## Input / output

  Two public fns:

  - `(spec->scxml machine-spec)` — produces an SCXML XML string for
    the given normalised machine definition (the same shape
    `(rf/machine-meta id)` returns).
  - `(scxml->spec scxml-string)` — parses an SCXML XML string into a
    re-frame machine spec.

  Round-trip is exact for the supported subset:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  ## Supported subset

  The SCXML mapping intentionally covers the static topology that
  has a direct SCXML equivalent. Other features (timer countdowns,
  `:spawn-all` rows, microstep semantics) survive as labelled edges
  but lose their runtime affordance — the same lossy-by-design
  posture the Mermaid emitter takes.

  | Re-frame2 | SCXML mapping |
  |---|---|
  | `:initial`                            | `<scxml initial=\"...\">` |
  | `:states` (flat)                      | `<state id=\"...\">` |
  | `:states` (compound)                  | nested `<state>` with `initial` |
  | `:final? true`                        | `<final id=\"...\">` |
  | `:on {:event :target}`                | `<transition event=\"event\" target=\"target\"/>` |
  | `:on {:event {:target ... :guard G}}` | `<transition cond=\"G\" .../>` |
  | `:after {ms :target}`                 | `<transition event=\"after.ms\" target=\"target\"/>` |
  | `:always [...]`                       | `<transition target=\"...\"/>` (eventless) |
  | `{:type :parallel :regions ...}`      | `<parallel>` containing region `<state>`s |
  | Namespaced ids (`:auth/login`)        | `auth.login` (dot-separated; SCXML id allows `.`) |
  | Vector-path targets                   | dot-joined `parent.child.grandchild` |

  ## Not supported (lossy or omitted)

  - `:spawn-all` rows — omitted; the parent state renders without
    spawn affordances.
  - `:tags` — re-frame2-specific; not part of W3C SCXML.
  - `:action`s and guard FN bodies — only the *names* survive
    (SCXML `cond=\"name\"` for guards; entry/exit `<script>` would
    require evaluation context, so names are preserved as XML
    comments on imports/exports).
  - Source-coord metadata — stripped at export time (same posture as
    share-URL encoding; see `Principles.md` §No session data in shares).

  Round-trip failure modes throw `(ex-info ... {:reason :scxml/...})`
  with a `:reason` keyword for programmatic dispatch.

  Per [`API.md`](../../spec/API.md) §SCXML import/export."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Id mapping
;;
;; Re-frame2 ids are typically keywords (`:idle`, `:auth/login-flow`)
;; or vector paths (`[:authenticated :browsing]`). SCXML state ids
;; are strings; the W3C grammar (XML Name) accepts `.` `_` `-` and
;; alphanumerics. We map: `:auth/login` → `"auth.login"`, hyphens
;; preserved, vector paths dot-joined.

(defn- keyword->id-string
  "Map a single keyword to its SCXML id string."
  [k]
  (if-let [ns (namespace k)]
    (str ns "." (name k))
    (name k)))

(defn- path->id-string
  "Map a re-frame2 id (keyword or vector path) to a single SCXML id
  string. Path separator is `.` — distinct from the `.` used inside a
  namespaced keyword because path components are full-segment-joined.
  Decoders disambiguate by walking the registered state hierarchy
  from the root, not by counting dots."
  [id]
  (cond
    (keyword? id) (keyword->id-string id)
    (vector? id)  (str/join "." (map keyword->id-string id))
    (string? id)  id
    :else         (str id)))

(defn- id-string->keyword
  "Inverse of `keyword->id-string`. Recovers `:ns/name` from
  `\"ns.name\"`; bare `\"name\"` round-trips to `:name`."
  [s]
  (when s
    (let [parts (str/split s #"\.")]
      (if (= 1 (count parts))
        (keyword s)
        (keyword (first parts) (str/join "." (rest parts)))))))

;; ---------------------------------------------------------------------------
;; XML emit — string-based, no external library

(defn- escape-xml-attr
  "Escape a string for safe inclusion inside an XML attribute value."
  [s]
  (-> s
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&apos;")))

(defn- indent-str
  [depth]
  (apply str (repeat depth "  ")))

(defn- transition-candidates
  "Normalise a transition spec to candidate maps. Same grammar
  walker as `mermaid.cljc/transition-candidates`."
  [spec]
  (cond
    (keyword? spec) [{:target spec}]
    (vector? spec)  (if (every? keyword? spec)
                      [{:target spec}]
                      (mapcat transition-candidates spec))
    (map? spec)     [spec]
    :else           []))

(defn- emit-transition
  "Emit a `<transition>` line for one candidate."
  [event-name {:keys [target guard action]} depth]
  (let [parts (cond-> []
                event-name (conj (str "event=\"" (escape-xml-attr event-name) "\""))
                target     (conj (str "target=\"" (escape-xml-attr (path->id-string target)) "\""))
                guard      (conj (str "cond=\"" (escape-xml-attr (keyword->id-string guard)) "\"")))
        attrs (str/join " " parts)
        self-close? (nil? action)]
    (str (indent-str depth)
         (if self-close?
           (str "<transition " attrs "/>")
           (str "<transition " attrs ">"
                "<!-- action: " (escape-xml-attr (keyword->id-string action)) " -->"
                "</transition>")))))

(defn- emit-transitions-for-on
  [on-map depth]
  (mapcat (fn [[event spec]]
            (map #(emit-transition (keyword->id-string event) % depth)
                 (transition-candidates spec)))
          on-map))

(defn- emit-transitions-for-after
  [after-map depth]
  (mapcat (fn [[delay spec]]
            (map #(emit-transition (str "after." (if (keyword? delay)
                                                  (keyword->id-string delay)
                                                  delay))
                                   % depth)
                 (transition-candidates spec)))
          after-map))

(defn- emit-transitions-for-always
  [always depth]
  (->> (transition-candidates always)
       (map #(emit-transition nil % depth))))

(defn- emit-state
  "Emit a `<state>` (or `<final>`) block for one state-node."
  [state-id state-node depth]
  (let [{:keys [final? initial states on after always]} state-node
        id-str (path->id-string state-id)
        tag    (if final? "final" "state")
        attrs  (cond-> (str "id=\"" (escape-xml-attr id-str) "\"")
                 (and (not final?) initial)
                 (str " initial=\"" (escape-xml-attr (path->id-string initial)) "\""))
        children
        (concat
          (emit-transitions-for-on on (inc depth))
          (emit-transitions-for-after after (inc depth))
          (emit-transitions-for-always always (inc depth))
          (mapcat (fn [[child-id child-node]]
                    (emit-state child-id child-node (inc depth)))
                  states))]
    (if (seq children)
      (concat [(str (indent-str depth) "<" tag " " attrs ">")]
              children
              [(str (indent-str depth) "</" tag ">")])
      [(str (indent-str depth) "<" tag " " attrs "/>")])))

(defn- emit-flat-or-compound
  [{:keys [initial states]} depth]
  (concat
    [(str (indent-str depth)
          "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\""
          " version=\"1.0\""
          " initial=\"" (escape-xml-attr (path->id-string initial)) "\">")]
    (mapcat (fn [[child-id child-node]]
              (emit-state child-id child-node (inc depth)))
            states)
    [(str (indent-str depth) "</scxml>")]))

(defn- emit-parallel
  [{:keys [regions]} depth]
  (concat
    [(str (indent-str depth)
          "<scxml xmlns=\"http://www.w3.org/2005/07/scxml\""
          " version=\"1.0\">")
     (str (indent-str (inc depth)) "<parallel id=\"rf2_parallel_root\">")]
    (mapcat (fn [[region-id region-node]]
              ;; Each region is a state with its own initial + states.
              (emit-state region-id region-node (+ depth 2)))
            regions)
    [(str (indent-str (inc depth)) "</parallel>")
     (str (indent-str depth) "</scxml>")]))

;; ---------------------------------------------------------------------------
;; Public emit fn

(defn spec->scxml
  "Convert a re-frame2 machine spec to an SCXML XML string.

  `machine-spec` is the normalised definition shape `(rf/machine-meta
  id)` returns (per Spec 005 §Transition table grammar):

  ```clojure
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}}
  ```

  Or a parallel definition:

  ```clojure
  {:type :parallel
   :regions {:data { ... } :form { ... }}}
  ```

  Throws `(ex-info ... {:reason :scxml/invalid-spec})` if the spec
  is missing required keys.

  Round-trips through `scxml->spec`:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  for the supported subset documented in the ns docstring."
  [machine-spec]
  (cond
    (= :parallel (:type machine-spec))
    (let [{:keys [regions]} machine-spec]
      (when-not (and (map? regions) (seq regions))
        (throw (ex-info ":scxml/invalid-spec"
                        {:rf.error/id :scxml/invalid-spec
                         :where    'machines-viz/spec->scxml
                         :recovery :no-recovery
                         :reason   "parallel spec requires non-empty :regions"
                         :spec     machine-spec})))
      (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           (str/join "\n" (emit-parallel machine-spec 0))))

    (and (:initial machine-spec) (map? (:states machine-spec)) (seq (:states machine-spec)))
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (str/join "\n" (emit-flat-or-compound machine-spec 0)))

    :else
    (throw (ex-info ":scxml/invalid-spec"
                    {:rf.error/id :scxml/invalid-spec
                     :where    'machines-viz/spec->scxml
                     :recovery :no-recovery
                     :reason   "spec must carry :initial + non-empty :states, or :type :parallel + :regions"
                     :spec     machine-spec}))))

;; ---------------------------------------------------------------------------
;; XML parse — minimal regex-based reader for the SCXML subset we
;; emit. This deliberately doesn't try to be a full XML parser; it
;; round-trips our own output and consumes the common SCXML shapes
;; external tools emit (single-line tags, attribute order is free,
;; whitespace tolerated). For unsupported XML constructs (CDATA,
;; namespaces beyond the default scxml ns, processing instructions
;; other than the leading `<?xml ... ?>`) the parser is best-effort
;; and may throw.

(defn- strip-prolog
  "Drop the leading `<?xml ... ?>` declaration if present."
  [s]
  (str/replace s #"(?s)^\s*<\?xml[^?]*\?>\s*" ""))

(defn- strip-comments
  "Drop `<!-- ... -->` comments. Used by the parser to discard the
  action-name comments the emitter injects on transitions with
  actions (the action survives the round-trip as a comment because
  SCXML proper has no action-id-on-transition slot)."
  [s]
  (str/replace s #"(?s)<!--.*?-->" ""))

(defn- parse-attrs
  "Parse a space-separated attribute string from a start-tag into a
  map of string keys to unescaped string values. Tolerant of any
  attribute order; recognises both single and double quotes."
  [^String attr-string]
  (let [pattern #"(\w+)\s*=\s*\"([^\"]*)\""
        matches (re-seq pattern attr-string)]
    (into {}
          (map (fn [[_ k v]]
                 [k (-> v
                        (str/replace "&apos;" "'")
                        (str/replace "&quot;" "\"")
                        (str/replace "&gt;"   ">")
                        (str/replace "&lt;"   "<")
                        (str/replace "&amp;"  "&"))]))
          matches)))

(defn- event-name->keyword
  "Convert an SCXML transition `event` attribute (e.g. `\"rf.load\"`)
  to a re-frame2 event keyword (`:rf/load`). Single dot ⇒ namespaced
  keyword; no dot ⇒ bare keyword. Multi-dot event names are kept as
  single keywords (`:a.b.c`) — they're not paths."
  [event-string]
  (when event-string
    (let [parts (str/split event-string #"\.")]
      (case (count parts)
        1 (keyword event-string)
        2 (keyword (first parts) (second parts))
        (keyword event-string)))))

(defn- unescape-id-string
  "Convert a SCXML id string back to a re-frame2 keyword (or vector
  path when the id has more than one logical segment).

  We can't distinguish `\"auth.login\"` (one namespaced keyword) from
  `\"auth.login\"` (a two-segment compound path) at the string level.
  The convention is: ids the encoder produces from compound paths use
  dot-joined segments where each segment is either bare (`name`) or
  namespaced (`ns.name`). Since the encoder only round-trips its own
  output structurally, we map back via the simplest unambiguous rule:
  a single dot = namespaced keyword; multi-dot = vector path."
  [s]
  (let [dot-count (count (filter #(= % \.) s))]
    (cond
      (= 0 dot-count) (keyword s)
      (= 1 dot-count) (id-string->keyword s)
      :else
      (let [parts (str/split s #"\.")]
        (mapv keyword parts)))))

(defn- tokenize
  "Walk an XML string and return a flat seq of token maps:
  `{:kind :start :tag t :attrs {}}`, `{:kind :end :tag t}`,
  `{:kind :self :tag t :attrs {}}`. Discards whitespace and text
  (this subset has no text content)."
  [^String xml]
  (let [tag-re #"(?s)<(/?)\s*([A-Za-z][A-Za-z0-9_:-]*)\s*([^>]*?)(/?)>"]
    (->> (re-seq tag-re xml)
         (map (fn [[_ closing tag attrs self-close]]
                (cond
                  (= "/" closing) {:kind :end :tag tag}
                  (= "/" self-close) {:kind :self :tag tag :attrs (parse-attrs attrs)}
                  :else {:kind :start :tag tag :attrs (parse-attrs attrs)}))))))

(defn- direct-transitions
  "From a flat seq of tokens that compose one state's body, return
  only those `<transition>` tokens that are *direct* children — i.e.
  not nested inside a deeper `<state>` / `<final>` / `<parallel>`.
  Self-closing transitions don't contribute to depth."
  [tokens]
  (loop [remaining tokens
         depth     0
         acc       []]
    (if (empty? remaining)
      acc
      (let [t (first remaining)]
        (cond
          (= :self (:kind t))
          (recur (rest remaining)
                 depth
                 (if (and (zero? depth)
                          (= "transition" (:tag t)))
                   (conj acc t)
                   acc))

          (and (= :start (:kind t)) (= "transition" (:tag t)))
          ;; Non-self-closing transition (we emit them self-closing
          ;; for clean output, but be tolerant on the parse side).
          ;; We treat its content as not affecting state-block depth.
          (recur (rest remaining)
                 depth
                 (if (zero? depth) (conj acc t) acc))

          (and (= :end (:kind t)) (= "transition" (:tag t)))
          (recur (rest remaining) depth acc)

          (= :start (:kind t))
          (recur (rest remaining) (inc depth) acc)

          (= :end (:kind t))
          (recur (rest remaining) (dec depth) acc)

          :else
          (recur (rest remaining) depth acc))))))

(defn- consume-transitions
  "Walk an open `<state>` body's tokens and split out direct-child
  transitions vs the rest (which group-children-by-state will turn
  into nested state blocks). Returns `{:on ... :after ... :always
  [...] :children-tokens [...]}`."
  [child-tokens]
  (let [ts              (direct-transitions child-tokens)
        ;; The remaining stream still contains the nested-state
        ;; tokens AND the direct-transition tokens (we filter the
        ;; latter out of children-tokens so they don't end up walked
        ;; as states).
        ts-set          (set ts)
        non-transitions (remove (fn [t]
                                  (or (ts-set t)
                                      ;; Drop the trailing </transition>
                                      ;; if a non-self-closing form was used.
                                      (and (= :end (:kind t))
                                           (= "transition" (:tag t)))))
                                child-tokens)
        coll
        (reduce
          (fn [acc t]
            (let [attrs    (:attrs t)
                  event    (get attrs "event")
                  target   (get attrs "target")
                  guard-s  (get attrs "cond")
                  cand-map (cond-> {}
                             target  (assoc :target (unescape-id-string target))
                             guard-s (assoc :guard (keyword guard-s)))
                  ;; Canonical shorthand: when a transition carries
                  ;; *only* :target, write it as the bare keyword/path
                  ;; in `:on` and `:after` maps. :always keeps the
                  ;; full candidate-map form so the vector-of-maps
                  ;; grammar lines up with `(transition-candidates ...)`.
                  simple-cand (if (= [:target] (keys cand-map))
                                (:target cand-map)
                                cand-map)]
              (cond
                (and event (str/starts-with? event "after."))
                (let [d-str (subs event 6)
                      d     (try
                              #?(:clj  (Long/parseLong d-str)
                                 :cljs (let [n (js/parseInt d-str 10)]
                                         (if (js/isNaN n) nil n)))
                              (catch #?(:clj Exception :cljs :default) _ nil))
                      k     (or d (keyword d-str))]
                  (update-in acc [:after k]
                             (fn [existing]
                               (if existing
                                 (if (vector? existing)
                                   (conj existing simple-cand)
                                   [existing simple-cand])
                                 simple-cand))))

                (nil? event)
                (update acc :always (fnil conj []) cand-map)

                :else
                (update-in acc [:on (unescape-id-string event)]
                           (fn [existing]
                             (if existing
                               (if (vector? existing)
                                 (conj existing simple-cand)
                                 [existing simple-cand])
                               simple-cand))))))
          {}
          ts)]
    (assoc coll :children-tokens non-transitions)))

(declare parse-state-block)

(defn- group-children-by-state
  "Given a flat seq of tokens that sit inside one `<state>` body
  (already excluding transitions), pair every `<state>` /
  `<final>` open token with its matching close + interior tokens.
  Returns a seq of `{:start ... :body ... :self? bool}` maps."
  [tokens]
  (loop [remaining tokens
         acc       []]
    (if (empty? remaining)
      acc
      (let [t (first remaining)]
        (cond
          ;; Transitions are not state blocks — skip them at this
          ;; level. consume-transitions picks up the direct
          ;; transitions before group-children-by-state is called
          ;; on the children-tokens.
          (and (= "transition" (:tag t))
               (or (= :self (:kind t))
                   (= :start (:kind t))
                   (= :end (:kind t))))
          (recur (rest remaining) acc)

          (= :self (:kind t))
          (recur (rest remaining)
                 (conj acc {:start t :body [] :self? true}))

          (= :start (:kind t))
          (let [tag (:tag t)
                [body rest-tokens] (loop [body []
                                          depth 1
                                          rs (rest remaining)]
                                     (cond
                                       (empty? rs)
                                       (throw (ex-info ":scxml/parse-error"
                                                       {:rf.error/id :scxml/parse-error
                                                        :where    'machines-viz/scxml->spec
                                                        :recovery :no-recovery
                                                        :reason   (str "unclosed <" tag ">")
                                                        :tag      tag}))

                                       (and (= :end (:kind (first rs)))
                                            (= tag (:tag (first rs)))
                                            (= 1 depth))
                                       [body (rest rs)]

                                       (and (= :start (:kind (first rs)))
                                            (= tag (:tag (first rs))))
                                       (recur (conj body (first rs)) (inc depth) (rest rs))

                                       (and (= :end (:kind (first rs)))
                                            (= tag (:tag (first rs))))
                                       (recur (conj body (first rs)) (dec depth) (rest rs))

                                       :else
                                       (recur (conj body (first rs)) depth (rest rs))))]
            (recur rest-tokens
                   (conj acc {:start t :body body :self? false})))

          :else
          (recur (rest remaining) acc))))))

(defn- parse-state-block
  "Parse one `<state>` / `<final>` block into a `[state-id state-node]`
  pair. `self?` indicates a self-closing tag with no children."
  [{:keys [start body self?]}]
  (let [tag        (:tag start)
        attrs      (:attrs start)
        id-str     (get attrs "id")
        initial-str (get attrs "initial")
        state-id   (unescape-id-string id-str)
        base       (cond-> {}
                     (= "final" tag) (assoc :final? true)
                     initial-str     (assoc :initial (unescape-id-string initial-str)))]
    (if (or self? (empty? body))
      [state-id base]
      (let [{:keys [on after always children-tokens]} (consume-transitions body)
            child-blocks (group-children-by-state children-tokens)
            child-states (when (seq child-blocks)
                           (into {}
                                 (map parse-state-block child-blocks)))
            node (cond-> base
                   (seq on)           (assoc :on on)
                   (seq after)        (assoc :after after)
                   (seq always)       (assoc :always always)
                   (seq child-states) (assoc :states child-states))]
        [state-id node]))))

(defn- parse-parallel-body
  "Parse the children of a `<parallel>` element into a `:regions`
  map. `group-children-by-state` already filters transition tokens
  at this depth."
  [parallel-body]
  (let [region-blocks (group-children-by-state parallel-body)]
    (into {}
          (map (fn [{:keys [start body self?]}]
                 (let [region-id (unescape-id-string (get (:attrs start) "id"))
                       [_ region-node] (parse-state-block {:start start :body body :self? self?})]
                   ;; Strip :final? off the region top-level — regions are
                   ;; not final states even when their own children are.
                   [region-id (dissoc region-node :final?)])))
          region-blocks)))

(defn scxml->spec
  "Parse an SCXML XML string into a re-frame2 machine spec.

  Inverse of `spec->scxml`:

  ```clojure
  (= machine-spec (-> machine-spec spec->scxml scxml->spec))
  ```

  for the supported subset documented in the ns docstring.

  Throws `(ex-info ... {:reason :scxml/parse-error})` when the input
  is not a valid SCXML document our parser recognises (missing root
  `<scxml>`, unclosed tags, etc.). Throws `:scxml/invalid-spec` if
  the parsed structure is missing required keys (no `:initial`, no
  `:states`)."
  [scxml-string]
  (when-not (string? scxml-string)
    (throw (ex-info ":scxml/parse-error"
                    {:rf.error/id :scxml/parse-error
                     :where    'machines-viz/scxml->spec
                     :recovery :no-recovery
                     :reason   "scxml->spec expects a string"
                     :input    scxml-string})))
  (let [tokens (-> scxml-string strip-prolog strip-comments tokenize vec)
        root-start (first (filter #(= "scxml" (:tag %)) tokens))]
    (when-not root-start
      (throw (ex-info ":scxml/parse-error"
                      {:rf.error/id :scxml/parse-error
                       :where    'machines-viz/scxml->spec
                       :recovery :no-recovery
                       :reason   "no <scxml> root element found"})))
    (let [token-vec (vec tokens)
          start-idx (some (fn [i] (when (identical? (nth token-vec i) root-start) i))
                          (range (count token-vec)))
          root-body
          (let [;; Walk to the matching </scxml>
                tail (subvec token-vec (inc start-idx))
                end-idx (loop [i 0 depth 1]
                          (cond
                            (>= i (count tail))
                            (throw (ex-info ":scxml/parse-error"
                                            {:rf.error/id :scxml/parse-error
                                             :where    'machines-viz/scxml->spec
                                             :recovery :no-recovery
                                             :reason   "unclosed <scxml>"}))

                            (and (= :start (:kind (nth tail i)))
                                 (= "scxml" (:tag (nth tail i))))
                            (recur (inc i) (inc depth))

                            (and (= :end (:kind (nth tail i)))
                                 (= "scxml" (:tag (nth tail i))))
                            (if (= 1 depth)
                              i
                              (recur (inc i) (dec depth)))

                            :else
                            (recur (inc i) depth)))]
            (subvec tail 0 end-idx))

          parallel-token
          (first (filter #(and (= "parallel" (:tag %))
                               (= :start (:kind %)))
                         root-body))]
      (if parallel-token
        ;; Parallel definition
        (let [p-start-idx (some (fn [i] (when (identical? (nth root-body i) parallel-token) i))
                                (range (count root-body)))
              tail (subvec root-body (inc p-start-idx))
              end-idx (loop [i 0 depth 1]
                        (cond
                          (>= i (count tail))
                          (throw (ex-info ":scxml/parse-error"
                                          {:rf.error/id :scxml/parse-error
                                           :where    'machines-viz/scxml->spec
                                           :recovery :no-recovery
                                           :reason   "unclosed <parallel>"}))

                          (and (= :start (:kind (nth tail i)))
                               (= "parallel" (:tag (nth tail i))))
                          (recur (inc i) (inc depth))

                          (and (= :end (:kind (nth tail i)))
                               (= "parallel" (:tag (nth tail i))))
                          (if (= 1 depth)
                            i
                            (recur (inc i) (dec depth)))

                          :else
                          (recur (inc i) depth)))
              parallel-body (subvec tail 0 end-idx)]
          {:type    :parallel
           :regions (parse-parallel-body parallel-body)})

        ;; Flat / compound definition
        (let [initial-str (get-in root-start [:attrs "initial"])
              ;; group-children-by-state itself skips <transition>
              ;; tokens at the current depth — so root-level
              ;; transitions are dropped (we have no slot for them
              ;; in our spec grammar) and nested transitions stay
              ;; inside their owning state's body for
              ;; consume-transitions to pick up.
              top-state-blocks (group-children-by-state root-body)
              states (into {}
                           (map parse-state-block top-state-blocks))]
          (when (empty? states)
            (throw (ex-info ":scxml/invalid-spec"
                            {:rf.error/id :scxml/invalid-spec
                             :where    'machines-viz/scxml->spec
                             :recovery :no-recovery
                             :reason   "scxml document has no <state> or <final> elements"})))
          (cond-> {:states states}
            initial-str (assoc :initial (unescape-id-string initial-str))))))))
