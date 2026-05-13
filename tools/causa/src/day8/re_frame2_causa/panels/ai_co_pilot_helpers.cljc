(ns day8.re-frame2-causa.panels.ai-co-pilot-helpers
  "Pure-data helpers for Causa's AI Co-Pilot rail panel
  (Phase 5, rf2-rccf3).

  ## Why a separate `.cljc` ns

  The panel view in `ai_co_pilot.cljs` touches the DOM (focused input,
  pulsing cue glyph, slash-command popover, chip click handlers). The
  *logic* — parsing slash commands, parsing chip fragments out of
  streamed text, applying the redaction filter to outbound payloads,
  shaping conversation turns — is pure data → data. Splitting that
  logic out into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by `feedback_jvm_interop_must_work.md`.

  ## What this ns owns (per spec/009-AI-CoPilot.md)

    1. **Slash command parsing** (§Slash commands, L76-94): tokenise an
       input string into `{:command <kw> :args [...] :raw <str>}` or
       nil when the input is not a slash form. The catalogue is the
       8-entry table in the spec.

    2. **Chip parsing** (§Causa data chips §Encoding contract, L249-267):
       walk a streamed answer string and split it into a vector of
       text + chip segments. A chip is the literal edn form
       `{:rf.copilot/chip <key> <value>}`. Malformed fragments render
       as literal text (the model is rewarded for naming data
       precisely — failure is loud, not hidden).

    3. **Chip type resolution** (§Causa data chips §Chip types, L226-230):
       map a parsed chip's `:key` to its panel target + glyph. Four
       chip kinds: `:dispatch-id`, `:path`, `:epoch-number`, `:handler-id`.

    4. **Redaction filter** (§Redaction defaults, L330-368): given a
       payload + the per-category opt-in flags, return the payload with
       event-vector args and app-db slice values stamped `<redacted>`
       (or pass-through when the flag is on). Source coords, handler
       ids, and trace metadata always pass through.

    5. **Conversation shape** (§Ephemeral conversation, L309-328): a
       conversation is a vector of `{:role :question/:answer :text ...
       :streaming? <bool>}` turns; `append-token` is the pure walk that
       extends the trailing answer's text by one token.

  ## What this ns does NOT do

  - **No I/O.** The provider call is in `ai_co_pilot.cljs` (CLJS only —
    it touches `fetch` and `EventSource`); this ns prepares the
    payload that fn ships and parses the streamed reply it receives.
  - **No localStorage.** Lock 12 — conversation is ephemeral; the
    redaction-settings + provider config that DO persist read /
    write localStorage from the panel view side.
  - **No DOM.** The chip renderer is hiccup, built in the view ns from
    the parsed segments this ns returns.

  ## Pre-alpha posture

  Every shape here is internal to Causa — no `:rf.copilot/*` exports
  to host code, no public registry entries beyond the `:rf.causa/*`
  prefix the panel itself registers."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- slash command catalogue --------------------------------------------

(def slash-commands
  "The 8 slash commands per spec/009-AI-CoPilot.md §Slash commands.
  Each entry is `{:command <kw> :usage <str> :doc <str>}` — used both
  by the slash-popover renderer and the canonical-completion logic in
  `parse-slash-command`. Order is the order the popover lists them in;
  matches the spec's table top-to-bottom."
  [{:command :explain
    :usage   "/explain <event-id-or-epoch>"
    :doc     "Describe one epoch — the cause, the effect, the state delta."}
   {:command :diff
    :usage   "/diff <epoch-a> <epoch-b>"
    :doc     "What changed between two epochs?"}
   {:command :find
    :usage   "/find <pattern>"
    :doc     "Search the trace stream."}
   {:command :rewind
    :usage   "/rewind <event-or-epoch>"
    :doc     "Propose a rewind (executes only on user confirmation)."}
   {:command :state
    :usage   "/state <machine-id>"
    :doc     "Describe a machine's current state."}
   {:command :why
    :usage   "/why <epoch>"
    :doc     "Causal-ancestor walk."}
   {:command :whatif
    :usage   "/whatif <hypothetical>"
    :doc     "Speculative reasoning (the model labels the answer as reasoning)."}
   {:command :clear
    :usage   "/clear"
    :doc     "Clear conversation."}])

(def slash-command-set
  "Set of recognised slash-command keywords for O(1) lookup. Built off
  `slash-commands` so the table is the single source of truth."
  (into #{} (map :command slash-commands)))

(defn parse-slash-command
  "Parse `input` (a string) into `{:command <kw> :args [...] :raw <str>}`
  when it begins with `/` and the head token names a known command;
  nil otherwise.

  Examples:

    (parse-slash-command \"/explain epoch-42\")
    ;;=> {:command :explain :args [\"epoch-42\"] :raw \"/explain epoch-42\"}

    (parse-slash-command \"/clear\")
    ;;=> {:command :clear :args [] :raw \"/clear\"}

    (parse-slash-command \"why did x fire?\")
    ;;=> nil

    (parse-slash-command \"/notacommand foo\")
    ;;=> nil   ; head token not in `slash-command-set`

  Args are whitespace-split; the model is the layer that interprets
  them (the slash form is a typed shortcut, per spec §Slash commands
  'Slash commands are typed shortcuts for common questions; the model
  can still answer the same questions in plain English')."
  [input]
  (when (and (string? input)
             (str/starts-with? (str/triml input) "/"))
    (let [trimmed (str/triml input)
          [head & rest-toks] (str/split trimmed #"\s+")
          cmd-kw (some-> head (subs 1) not-empty keyword)]
      (when (contains? slash-command-set cmd-kw)
        {:command cmd-kw
         :args    (vec rest-toks)
         :raw     input}))))

(defn slash-popover-matches
  "Given a partial slash input like `\"/exp\"`, return the subset of
  `slash-commands` whose `:command` name starts with the partial.
  Empty `\"/\"` returns all commands.

  Returns `[]` when `input` is not a slash form."
  [input]
  (when (and (string? input)
             (str/starts-with? (str/triml input) "/"))
    (let [trimmed (str/triml input)
          stem    (subs trimmed 1)
          stem    (-> stem (str/split #"\s+") first (or ""))]
      (cond
        (= "" stem)
        slash-commands

        :else
        (filterv (fn [{:keys [command]}]
                   (str/starts-with? (name command) stem))
                 slash-commands)))))

;; ---- chip parsing -------------------------------------------------------

(def chip-key-set
  "The four supported chip keys per spec §Chip types. Any other key
  inside `{:rf.copilot/chip <key> <value>}` renders as literal text
  (the model is rewarded for naming data precisely)."
  #{:dispatch-id :path :epoch-number :handler-id})

(def chip-glyphs
  "Glyph per chip key — same set used in the sidebar and the
  causality strip (per spec §Chip types §Visual treatment)."
  {:dispatch-id  "◆"  ;; ◆
   :path         "▥"  ;; ▥
   :epoch-number "⏵"  ;; ⏵
   :handler-id   "⚙"}) ;; ⚙

(def chip-targets
  "Click target per chip key — the panel-jump the chip dispatches.
  Per spec §Chip types §Click target. Each target is a re-frame event
  vector template — the chip click handler in the view ns conj's the
  chip's value as the final positional arg."
  {:dispatch-id  :rf.causa/select-dispatch-id
   :path         :rf.causa.copilot/open-path
   :epoch-number :rf.causa/select-epoch
   :handler-id   :rf.causa.copilot/open-handler})

(def ^:private chip-prefix "{:rf.copilot/chip")

(defn- balanced-brace-length
  "Walk `s` (which starts with `{`) and return the 1-based index of
  the matching closing `}` — i.e. the length of the smallest balanced
  brace-prefix of `s`. Returns nil when no balanced prefix exists.

  Tracks brackets `()[]{}` together (so chip-values that are vectors,
  sets, or lists nest cleanly inside the chip fragment) and skips `\\`
  escapes inside strings. Pure-data; portable between clj / cljs
  without an edn reader."
  [s]
  (let [n (count s)]
    (loop [i 0
           depth 0
           in-str? false
           escaped? false]
      (cond
        (>= i n)
        nil

        in-str?
        (let [c (.charAt ^String s i)]
          (cond
            escaped?       (recur (inc i) depth true false)
            (= c \\)       (recur (inc i) depth true true)
            (= c \")       (recur (inc i) depth false false)
            :else          (recur (inc i) depth true false)))

        :else
        (let [c (.charAt ^String s i)]
          (cond
            (= c \")  (recur (inc i) depth true false)
            (or (= c \{) (= c \[) (= c \())
            (recur (inc i) (inc depth) false false)
            (or (= c \}) (= c \]) (= c \)))
            (let [d' (dec depth)]
              (if (zero? d')
                (inc i)
                (recur (inc i) d' false false)))
            :else     (recur (inc i) depth false false)))))))

(defn- read-chip-fragment
  "Read one chip fragment off the head of `s`. A chip fragment is the
  literal form

      {:rf.copilot/chip <key> <value>}

  per spec §Causa data chips §Encoding contract — three tokens inside
  brace delimiters. Note this is NOT a legal EDN map (an odd token
  count), so we parse the inner contents manually: strip the braces,
  pull the three tokens via `edn/read-string` on the wrapped vector
  form `[<contents>]`, and assert the head token is the sentinel
  `:rf.copilot/chip`.

  Returns `{:value {:rf.copilot/chip <sentinel> :key <kw> :value <v>}
            :rest <s>}` on success, nil on a parse error or unbalanced
  fragment. Returning the parsed shape as a map (rather than a
  vector) lets the caller dissoc the sentinel slot uniformly with
  `parse-streamed-answer`'s downstream logic."
  [s]
  (when-let [len (balanced-brace-length s)]
    (let [prefix   (subs s 0 len)
          rest-s   (subs s len)
          ;; Strip the leading `{` and trailing `}` to expose the three
          ;; tokens. Wrap in `[]` so the platform edn reader gives us a
          ;; vector of forms in one read call.
          inner    (subs prefix 1 (dec (count prefix)))
          wrapped  (str "[" inner "]")]
      (try
        (let [tokens #?(:clj  (edn/read-string wrapped)
                        :cljs (edn/read-string wrapped))]
          (when (and (vector? tokens)
                     (= 3 (count tokens))
                     (= :rf.copilot/chip (first tokens)))
            {:value {:rf.copilot/chip :rf.copilot/chip
                     (nth tokens 1)    (nth tokens 2)}
             :rest  rest-s}))
        (catch #?(:clj Exception :cljs :default) _
          nil)))))

(defn- looks-like-chip-prefix?
  "Cheap pre-check: does `s`, starting at `idx`, look like the head of
  a chip fragment? Avoids attempting an edn parse for every `{` in the
  text (which would be O(text * forms) in the worst case)."
  [s idx]
  (when (< idx (count s))
    (let [head-len (count chip-prefix)
          tail     (subs s idx (min (+ idx head-len) (count s)))]
      (= chip-prefix tail))))

(defn parse-streamed-answer
  "Walk `text` and return a vector of segments:

    [{:kind :text  :text <str>}
     {:kind :chip  :chip-key <kw> :value <any> :raw <str>}
     ...]

  Per spec §Causa data chips §Encoding contract, a chip is the literal
  edn form `{:rf.copilot/chip <key> <value>}`. The renderer parses
  these fragments at stream time and swaps them for the chip
  component; the rest of the text passes through.

  Failure modes (per spec §Why structured citations):
   - Malformed edn → literal text (the model's confusion is surfaced).
   - Unknown chip-key → literal text (only the 4 keys in
     `chip-key-set` are honoured; anything else degrades gracefully).
   - Partially-formed fragment at the stream tail → literal text;
     the next token may complete it (the caller may re-parse on each
     stream-token; partial chips will pop into existence once whole)."
  [text]
  (if-not (string? text)
    []
    (loop [idx 0
           ;; Accumulator for the in-progress :text segment. Held as a
           ;; vector of single-char strings; `apply str` joins it when
           ;; emitted. Portable cljc — no platform StringBuilder.
           buf []
           out []]
      (cond
        (>= idx (count text))
        (let [tail (apply str buf)]
          (if (empty? tail)
            out
            (conj out {:kind :text :text tail})))

        (looks-like-chip-prefix? text idx)
        (let [fragment (subs text idx)
              parsed   (read-chip-fragment fragment)]
          (if parsed
            (let [chip-map     (:value parsed)
                  entries      (seq (dissoc chip-map :rf.copilot/chip))
                  ;; The wrapper map produced by `read-chip-fragment`
                  ;; has exactly one non-sentinel entry — that's the
                  ;; chip's `<key> <value>` pair from the spec form
                  ;; `{:rf.copilot/chip <key> <value>}`.
                  [chip-key chip-value] (when entries (first entries))
                  consumed-len  (- (count fragment) (count (:rest parsed)))
                  raw           (subs fragment 0 consumed-len)
                  pre           (apply str buf)
                  out'          (cond-> out
                                  (seq pre) (conj {:kind :text :text pre}))]
              (if (contains? chip-key-set chip-key)
                (recur (+ idx consumed-len)
                       []
                       (conj out'
                             {:kind     :chip
                              :chip-key chip-key
                              :value    chip-value
                              :raw      raw}))
                ;; Unknown chip-key — render the literal text and
                ;; resume walking past the consumed fragment.
                (recur (+ idx consumed-len)
                       []
                       (conj out' {:kind :text :text raw}))))
            ;; Malformed — append the `{` and resume; the rest of the
            ;; fragment renders as text.
            (recur (inc idx)
                   (conj buf (subs text idx (inc idx)))
                   out)))

        :else
        (recur (inc idx)
               (conj buf (subs text idx (inc idx)))
               out)))))

(defn resolve-chip
  "Resolve a parsed chip into the metadata the view ns needs to render
  it: `{:glyph <str> :target <kw-or-nil> :chip-key <kw> :value <any>}`.

  When `chip-key` is unknown returns nil. The view ns is responsible
  for the click handler (which dispatches `[target value]`) and the
  greyed-out failure rendering when the value cannot be resolved
  against the live runtime (per spec §Failure modes — unresolvable
  identifier renders greyed-out with `⚠`)."
  [{:keys [chip-key value]}]
  (when (contains? chip-key-set chip-key)
    {:chip-key chip-key
     :value    value
     :glyph    (get chip-glyphs chip-key)
     :target   (get chip-targets chip-key)}))

;; ---- redaction filter ---------------------------------------------------

(def default-redaction-settings
  "Per spec §Redaction defaults §Defaults — privacy-by-default. Values
  default to redacted; the user opts in to unmask per category.

  Source coords, handler ids, and trace metadata always pass through
  (per the spec table — they are 'full' regardless of these flags)."
  {:unmask-event-args false   ;; :rf.causa.copilot/unmask-event-args
   :unmask-app-db     false}) ;; :rf.causa.copilot/unmask-app-db

(def ^:private redacted-token "<redacted>")

(defn- redact-event-vec
  "Redact a single event vector — `[event-id & args]`. The id is kept;
  every arg becomes `<redacted>`. Non-vector inputs pass through
  unchanged (defensive — the model sees data it doesn't always shape)."
  [ev]
  (if (and (vector? ev) (seq ev))
    (into [(first ev)] (repeat (dec (count ev)) redacted-token))
    ev))

(defn redact-trace-event
  "Stamp a single trace event's event-vector args with `<redacted>`
  unless `:unmask-event-args` is on. Source-coord + handler-id +
  trace-metadata slots pass through always.

  The shape of a trace event follows
  `re-frame.trace.projection` (per rf2-n6x4q): a map with
  `:op-type`, `:operation`, `:id`, `:tags { :dispatch-id <i>
  :event [...] :phase ... :fx-id ... :sub-id ... }`."
  [{:keys [unmask-event-args] :as _settings} ev]
  (if unmask-event-args
    ev
    (cond-> ev
      (some? (get-in ev [:tags :event]))
      (update-in [:tags :event] redact-event-vec))))

(defn- redact-walk-app-db
  "Walk an app-db value and replace leaf values with `<redacted>`.
  Keys are preserved (structural metadata); only the leaves are
  redacted. Numbers, strings, booleans, keywords-not-as-keys all
  become `<redacted>`. Maps and vectors recurse so the user can still
  see the shape (per spec rationale — 'Source coords + handler IDs +
  trace metadata are sufficient for the canonical why-did-this-render
  questions')."
  [v]
  (cond
    (map? v)    (into {} (map (fn [[k vv]] [k (redact-walk-app-db vv)]) v))
    (vector? v) (mapv redact-walk-app-db v)
    (set? v)    (into #{} (map redact-walk-app-db v))
    (seq? v)    (map redact-walk-app-db v)
    :else       redacted-token))

(defn redact-app-db
  "Stamp an app-db payload with `<redacted>` leaves unless
  `:unmask-app-db` is on. Per spec §Redaction defaults — App-db slice
  values default to redacted; opt-in via `:rf.causa.copilot/unmask-app-db`."
  [{:keys [unmask-app-db] :as _settings} db]
  (if unmask-app-db
    db
    (redact-walk-app-db db)))

(defn redact-payload
  "Apply the redaction filter to the full LLM-bound payload.

  Shape of `payload`:

    {:trace-events <[trace-event ...]>
     :app-db       <any>
     :question     <str>
     ;; ... other slots pass through unchanged
    }

  Per spec §Redaction defaults the trace metadata + source-coords +
  handler ids ride through always; only event-vector args + app-db
  values are masked under default settings."
  [settings payload]
  (cond-> payload
    (contains? payload :trace-events)
    (update :trace-events
            (fn [events] (mapv #(redact-trace-event settings %) events)))

    (contains? payload :app-db)
    (update :app-db #(redact-app-db settings %))))

;; ---- conversation shape -------------------------------------------------

(defn empty-conversation
  "The empty-conversation vector. Used both by the initial state and
  by `:rf.causa/copilot-clear-conversation` to reset the buffer.

  Per spec §Ephemeral conversation a conversation is per-session
  in-memory only — Lock 12. The vector is not persisted to localStorage,
  not exported, cleared on Causa close / tab reload."
  []
  [])

(defn append-question
  "Append a user-question turn to `conversation`. Returns the extended
  conversation vector — pure data → data; the live store is in
  Causa's app-db so the caller wraps this in an event handler."
  [conversation text]
  (conj (vec conversation)
        {:role :question
         :text (str text)
         :streaming? false}))

(defn start-answer
  "Append a streaming-answer turn to `conversation`. Returns the
  extended vector with the trailing answer marked `:streaming? true`
  + empty `:text` — `append-token` accretes the model's tokens onto
  this turn."
  [conversation]
  (conj (vec conversation)
        {:role       :answer
         :text       ""
         :streaming? true}))

(defn append-token
  "Append one streamed token to the last turn's `:text`. The last
  turn must be an in-flight answer (`{:role :answer :streaming? true}`);
  no-ops when it is not (defensive — avoids a stray token corrupting
  a finalised answer or polluting a question turn).

  Returns `conversation` unchanged when the precondition fails — the
  caller's event handler shape doesn't need a separate error path."
  [conversation token]
  (let [conv (vec conversation)
        n    (count conv)]
    (if (zero? n)
      conv
      (let [last-turn (nth conv (dec n))]
        (if (and (= :answer (:role last-turn))
                 (:streaming? last-turn))
          (assoc conv (dec n)
                 (update last-turn :text str token))
          conv)))))

(defn end-answer
  "Mark the trailing answer turn as no-longer streaming. Same
  precondition as `append-token` — no-ops when the trailing turn is
  not an in-flight answer."
  [conversation]
  (let [conv (vec conversation)
        n    (count conv)]
    (if (zero? n)
      conv
      (let [last-turn (nth conv (dec n))]
        (if (and (= :answer (:role last-turn))
                 (:streaming? last-turn))
          (assoc conv (dec n)
                 (assoc last-turn :streaming? false))
          conv)))))
