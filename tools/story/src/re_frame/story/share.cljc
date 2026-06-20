(ns re-frame.story.share
  "Per-variant share URL — pure URL builder + parser. Per
  `005-SOTA-Features.md` §Share URL + Stage 6 + Phase-2 §5.2 #6.

  Each variant has a sharable URL that encodes the active modes and
  any cell-overrides so a teammate pasting the URL lands on the exact
  same cell the author is looking at. The URL is the browser's
  address-bar URL — `re-frame.story.ui.url-state` wires pushState /
  popstate against this encoder so back-button + bookmark + Cmd-L /
  Cmd-A / Cmd-C preserve state. There is no separate Share button or
  QR popover — the live address-bar URL is the whole sharability
  surface.

  ## URL scheme

  Per `005-SOTA-Features.md` §Share URL the scheme is:

      <base>?variant=<id>
            &workspace=<id>
            &mode-tab=<id>
            &modes=<list>
            &viewport=<id-or-WxH>
            &background=<id-or-#rrggbb>
            &tag-filter=<list>
            &overrides=<list>
            &substrate=<s>

  - `<id>` for variant / workspace — the id as `:story.foo/bar`
  - `<id>` for mode-tab — one of `dev` / `docs` / `test`
  - `<list>` for modes / tag-filter — comma-separated keyword ids
  - `<map>` for overrides — one `pr-str`-printed EDN map of
                            `{arg-key value}` (delimiter-safe;
                            a string value may contain commas).
  - `<id-or-WxH>` for viewport — preset keyword (`tablet`) or
                                  `WxH` custom (e.g. `800x600`)
  - `<id-or-#rrggbb>` for background — preset keyword (`dark`) or a
                                       hex colour string (`#abc123`)
  - `<s>` — substrate id, namespace-preserving like `<id>`
            (`:my.lib/uix` → `my.lib/uix`); omitted when `:reagent` default

  The URL is the sharability surface of the testbed: a teammate
  pasting a URL must land on the exact same view (workspace
  + mode-tab + viewport + background + tag-filter + modes + overrides
  + substrate).

  ## Modules in this ns

  Pure URL-building (`variant-share-url` and friends) — JVM-testable.

  ## Bundle isolation

  Share is part of the Story bundle but DCE'd under `:advanced` with
  `:rf.story/enabled?` false (per `005-SOTA-Features.md` §Production elision under `:advanced`)."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---- pure: URL encoding -------------------------------------------------

(defn- ^:no-doc url-encode
  "Percent-encode `s` for use in a URL query parameter. Round-trip-safe
  with the host platform's URL decoder."
  [s]
  #?(:clj  (java.net.URLEncoder/encode (str s) "UTF-8")
     :cljs (js/encodeURIComponent (str s))))

(defn- ^:no-doc kw->str
  "Render a keyword as a stable string `prefix:name` or `name`. Used so
  the URL params don't carry the `:` colon prefix Clojure keywords
  serialise to via `str` — that prefix doesn't survive URL parsing in
  every client."
  [k]
  (if (keyword? k)
    (str (when-let [n (namespace k)] (str n "/")) (name k))
    (str k)))

(defn parse-keyword-token
  "Parse the keyword token emitted by `kw->str`.

  Accepts both the wire form (`\"story.counter/loaded\"`) and the
  printed keyword form (`\":story.counter/loaded\"`) so old manually-
  copied URLs remain usable. Returns nil for blank / malformed input."
  [s]
  (when (string? s)
    (let [trimmed (str/trim s)
          token   (if (str/starts-with? trimmed ":")
                    (subs trimmed 1)
                    trimmed)]
      (when (seq token)
        (if-let [slash (str/index-of token "/")]
          (let [ns-part (subs token 0 slash)
                name-part (subs token (inc slash))]
            (when (and (seq ns-part) (seq name-part))
              (keyword ns-part name-part)))
          (keyword token))))))

(defn parse-modes-param
  "Parse a `modes=` URLSearchParams value into a vector of mode ids.
  The wire shape is the same comma-separated token list `build-params`
  emits."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (->> (str/split s #",")
         (map parse-keyword-token)
         (remove nil?)
         vec)))

(defn parse-substrate-param
  "Parse the optional `substrate=` URLSearchParams value. Returns a
  keyword or nil."
  [s]
  (parse-keyword-token s))

(defn prune-unregistered-modes
  "Drop mode ids from `modes` that no longer resolve at the registrar
  (stale localStorage / a share-URL pointing at a renamed mode). Pure
  data → data; JVM-testable via an injected `registered?` predicate.

  `registered?` is a `(mode-id → boolean)` predicate — the production
  caller (`re-frame.story.ui.toolbar`) closes it over the live
  `re-frame.story.registrar`; tests pass a set / fn. Returns a vector
  (never nil); a nil/empty `modes` yields `[]`.

  Only the stored-modes (localStorage) hydration path prunes — URL-
  derived `:active-modes` ride the canonical `apply-parsed-to-state`
  writer verbatim (like every other URL-owned slot), so this helper is
  shared by `share.cljc`'s mode codec rather than duplicated in the
  toolbar or its tests."
  [modes registered?]
  (vec (filter registered? (or modes []))))

;; ---- workspace / mode-tab / viewport / background / tag-filter -----------

(defn parse-workspace-param
  "Parse the `workspace=` URLSearchParams value as a workspace id.
  Returns a qualified keyword or nil. Same shape as variant ids; the
  caller verifies registration."
  [s]
  (parse-keyword-token s))

(def ^:private mode-tab-tokens
  "Canonical mode-tab tokens emitted on the wire. Mirrors
  `re-frame.story.ui.state/mode-tabs` — kept here as a literal set
  rather than required to avoid a CLJS→CLJC dependency (the canonical
  tabs are stable; if a new tab ships the set is updated here)."
  #{:dev :docs :test})

(defn parse-mode-tab-param
  "Parse the `mode-tab=` URLSearchParams value into one of `:dev` /
  `:docs` / `:test`. Returns nil for unknown values so a stale URL
  degrades to the default tab rather than poisoning the slot."
  [s]
  (when-let [kw (parse-keyword-token s)]
    (when (contains? mode-tab-tokens kw) kw)))

(def ^:private viewport-wxh-re
  "WxH custom viewport on the wire — two positive integers joined by
  a literal `x`. Trailing whitespace is tolerated."
  #"(?i)^\s*(\d+)x(\d+)\s*$")

(defn parse-viewport-param
  "Parse the `viewport=` URLSearchParams value into one of:

  - a preset keyword (`:tablet` / `:desktop` / ...) when the value
    matches a recognised preset token,
  - a `{:width :height}` map when the value is the `WxH` custom shape,
  - nil otherwise.

  Validation of the preset keyword against the actual `viewport/presets`
  table happens at hydrate time — keeping this fn pure (no registrar
  dependency) means JVM tests can round-trip the wire shape without
  pulling the viewport ns in."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (if-let [[_ w h] (re-matches viewport-wxh-re trimmed)]
        (try
          (let [wi #?(:clj  (Integer/parseInt w)
                      :cljs (js/parseInt w 10))
                hi #?(:clj  (Integer/parseInt h)
                      :cljs (js/parseInt h 10))]
            (when (and (pos? wi) (pos? hi))
              {:width wi :height hi}))
          (catch #?(:clj Exception :cljs :default) _ nil))
        (parse-keyword-token trimmed)))))

(def ^:private hex-colour-re
  "Hex colour on the wire — 3, 6, or 8 hex digits prefixed with `#`."
  #"^#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

(defn parse-background-param
  "Parse the `background=` URLSearchParams value into one of:

  - a preset keyword (`:dark` / `:light` / ...),
  - a hex colour string (`#abc123`),
  - nil otherwise.

  Like `parse-viewport-param`, preset validation against
  `backgrounds/presets` is the hydrator's job."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [trimmed (str/trim s)]
      (if (re-matches hex-colour-re trimmed)
        trimmed
        (parse-keyword-token trimmed)))))

(defn parse-tag-filter-param
  "Parse the `tag-filter=` URLSearchParams value into a set of tag
  keywords. The wire shape is a comma-separated list of `(kw->str)`
  tokens — the same shape `parse-modes-param` consumes. Returns a
  (possibly empty) set, or nil when the input is blank."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [tokens (->> (str/split s #",")
                      (map parse-keyword-token)
                      (remove nil?))]
      (when (seq tokens)
        (into #{} tokens)))))

(defn- ^:no-doc read-edn
  [s]
  (try
    [true (edn/read-string s)]
    (catch #?(:clj Exception :cljs :default) _
      [false nil])))

;; ---- overrides codec ----------------------------------------------------
;;
;; The overrides payload is a single EDN map, `pr-str`-printed then
;; percent-encoded as one token (`build-overrides-token`). Decoding
;; reads the whole token back as one EDN map (`parse-overrides-param`).
;;
;; Printing one EDN map sidesteps delimiter collisions entirely — the
;; reader balances strings / collections — so any EDN-valued override
;; round-trips faithfully, including a string value carrying the list
;; separator (e.g. "Save, continue").

(defn- ^:no-doc keywordish-key
  "Coerce a parsed override key to a keyword. Override arg-keys are
  keywords; a value read as a symbol (e.g. an unquoted token) coerces
  to the same keyword so author-typed maps stay forgiving. Returns nil
  for anything that can't be a keyword."
  [k]
  (cond
    (keyword? k) k
    (symbol? k)  (keyword (namespace k) (name k))
    (string? k)  (parse-keyword-token k)
    :else        nil))

(defn- ^:no-doc parse-overrides-edn-map
  "Read the EDN-map wire form. Returns `{:overrides <map-or-nil>
  :dropped [<token> ...]}`. A non-map / unreadable payload reports the
  whole token as dropped; per-entry keys that can't coerce to a keyword
  are dropped individually (their `pr-str` entry naming the drop)."
  [s]
  (let [[ok? data] (read-edn s)]
    (if (and ok? (map? data))
      (let [parsed  (mapv (fn [[k v]] [(keywordish-key k) k v]) data)
            kept    (into {} (keep (fn [[kk _ v]] (when kk [kk v])) parsed))
            dropped (->> parsed
                         (remove (fn [[kk _ _]] kk))
                         (mapv (fn [[_ k v]] (str (pr-str k) " " (pr-str v)))))]
        {:overrides (not-empty kept) :dropped dropped})
      {:overrides nil :dropped [s]})))

(defn build-overrides-token
  "Encode a `{arg-key → value}` cell-overrides map into the single
  percent-encoded `overrides=` wire token. The map is
  printed via `pr-str` (keys sorted for deterministic output) so any
  EDN value round-trips faithfully — including string values that
  carry the list separator. Returns nil for an empty/nil map."
  [cell-overrides]
  (when (seq cell-overrides)
    (url-encode
      (pr-str (into (sorted-map-by (fn [a b] (compare (kw->str a) (kw->str b))))
                    cell-overrides)))))

(defn parse-overrides-param*
  "Parse an `overrides=` URLSearchParams value into a cell-overrides
  map AND report any entries that were dropped (unparseable / invalid
  key). Returns a map `{:overrides ... :dropped [<token> ...]}` — both
  keys always present so callers can pattern-match without an
  else-branch.

  The wire form is the EDN-map form: the whole payload is
  one `pr-str`-printed map, read back as one EDN map (delimiter-safe —
  string values may contain commas).

  Powers the share-import hint: the share UI surfaces a
  non-blocking note when N>0 overrides from a stale share URL no
  longer apply (variant args refactored, renamed, removed). Pure;
  JVM-testable."
  [s]
  (if (and (string? s) (seq (str/trim s)))
    (parse-overrides-edn-map s)
    {:overrides nil :dropped []}))

(defn parse-overrides-param
  "Parse an `overrides=` URLSearchParams value into a cell-overrides
  map (silent-drop). Reads the EDN-map wire form (see
  `parse-overrides-param*`). Malformed entries are ignored so a stale
  share URL degrades to the valid subset instead of poisoning the shell
  state."
  [s]
  (:overrides (parse-overrides-param* s)))

(defn drop-stale-overrides
  "Second-stage filter over a `parse-overrides-param*` result: drop every
  parsed override whose TOP-LEVEL arg-key is NOT in `declared-keys` —
  the set of arg-keys the currently selected variant still declares.
  `parse-overrides-param*` only drops UNPARSEABLE entries
  (malformed EDN, non-keyword keys); it has no view of the live variant
  contract, so an override for an arg the variant RENAMED or REMOVED
  parses fine and — without this filter — gets installed as a live arg
  and silently merged by `args/resolve-args`, inflating the recipient's
  state with orphan args and HIDING the share-import drift (a run/share
  marked more reproducible than it is).

  Takes `parsed` (`{:overrides <map-or-nil> :dropped [<token> ...]}`, the
  `parse-overrides-param*` shape) and `declared-keys` (a set/coll of
  keyword arg-keys, or nil). Returns the SAME shape with stale-key
  overrides moved from `:overrides` into `:dropped` (as a `pr-str` token
  `\"<k> <v>\"` matching the parser's drop-token idiom), preserving the
  parser's own malformed-drop tokens.

  `declared-keys` nil → no contract known (an unregistered / uncompilable
  variant): every parsed override is kept verbatim, so this degrades to
  the parser's pre-filter behaviour rather than dropping everything.
  Pure data → data; JVM-testable."
  [{:keys [overrides dropped] :as _parsed} declared-keys]
  (if (nil? declared-keys)
    {:overrides (not-empty overrides) :dropped (vec dropped)}
    (let [declared (set declared-keys)
          stale    (->> overrides
                        (remove (fn [[k _]] (contains? declared k)))
                        (mapv (fn [[k v]] (str (pr-str k) " " (pr-str v)))))
          kept     (into {} (filter (fn [[k _]] (contains? declared k)) overrides))]
      {:overrides (not-empty kept)
       :dropped   (into (vec dropped) stale)})))

(defn- ^:no-doc kv-pair
  "Build a `k=v` URL parameter fragment. `k` is a keyword;
  `v-encoded` is the already-percent-encoded value string."
  [k v-encoded]
  (str (url-encode (name k)) "=" v-encoded))

(defn- ^:no-doc split-fragment
  "Split `url` into `[base fragment]`, preserving the fragment without
  its leading `#`. Share links must append query params before the hash
  route so browsers send `variant=` through `location.search` instead
  of burying it inside `location.hash`."
  [url]
  (if-let [idx (str/index-of (or url "") "#")]
    [(subs url 0 idx) (subs url (inc idx))]
    [(or url "") nil]))

(defn- ^:no-doc append-query-params
  "Append already-encoded query `params` to `base-url`, inserting them
  before any hash fragment."
  [base-url params]
  (let [[path fragment] (split-fragment base-url)
        query           (str/join "&" params)
        sep             (cond
                          (str/blank? path)        ""
                          (str/includes? path "?") "&"
                          :else                    "?")
        with-query      (str path sep query)]
    (if (some? fragment)
      (str with-query "#" fragment)
      with-query)))

;; ---- pure: param building -----------------------------------------------

(defn- ^:no-doc encode-viewport
  "Encode a viewport slot value into the wire form. Accepts the same
  shapes the encoder takes off the shell state: a preset keyword or a
  `{:width :height}` custom map. Returns the URL-encoded token (no `&`
  / `=` glue), or nil when the input is unusable."
  [v]
  (cond
    (keyword? v)
    (url-encode (kw->str v))

    (and (map? v) (some? (:width v)) (some? (:height v)))
    (url-encode (str (:width v) "x" (:height v)))

    :else nil))

(defn- ^:no-doc encode-background
  "Encode a background slot value into the wire form. Accepts a preset
  keyword or a colour string. Returns the URL-encoded token, or nil
  when unusable."
  [v]
  (cond
    (keyword? v) (url-encode (kw->str v))
    (string? v)  (url-encode v)
    :else        nil))

(defn build-params
  "Pure: build the ordered vector of `k=v` URL params for a share URL.
  Keys are stable across calls so the generated URL is deterministic.

  The param set covers the full sharability surface:

  - `:variant`    — the focused variant id (when set)
  - `:workspace`  — the focused workspace id (when set)
  - `:mode-tab`   — the focused variant's mode-tab when not the default
                    `:dev`
  - `:modes`      — comma-separated stable list of active mode ids
  - `:viewport`   — chrome-wide viewport selection (preset kw or `WxH`)
  - `:background` — chrome-wide background selection (preset kw or hex)
  - `:tag-filter` — comma-separated stable list of active tag-filter keys
  - `:overrides`  — one `pr-str`-printed EDN map (delimiter-safe)
  - `:substrate`  — substrate id (omitted when `:reagent` default)

  Returns a vector of `k=v` URL fragments. Pure data → data;
  JVM-testable. Slots whose value is empty/nil/default are omitted so
  the URL is the minimal canonical form."
  [{:keys [variant-id workspace-id mode-tab
           active-modes viewport background tag-filter
           cell-overrides substrate]}]
  (cond-> []
    variant-id
    (conj (kv-pair :variant (url-encode (kw->str variant-id))))

    workspace-id
    (conj (kv-pair :workspace (url-encode (kw->str workspace-id))))

    (and mode-tab (contains? mode-tab-tokens mode-tab) (not= mode-tab :dev))
    (conj (kv-pair :mode-tab (url-encode (name mode-tab))))

    (seq active-modes)
    (conj (kv-pair :modes
                   (url-encode
                     (str/join ","
                       (map kw->str (sort-by kw->str active-modes))))))

    (some? (encode-viewport viewport))
    (conj (kv-pair :viewport (encode-viewport viewport)))

    (some? (encode-background background))
    (conj (kv-pair :background (encode-background background)))

    (seq tag-filter)
    (conj (kv-pair :tag-filter
                   (url-encode
                     (str/join ","
                       (map kw->str (sort-by kw->str tag-filter))))))

    (seq cell-overrides)
    (conj (kv-pair :overrides (build-overrides-token cell-overrides)))

    (and substrate (not= substrate :reagent))
    (conj (kv-pair :substrate (url-encode (kw->str substrate))))))

(defn parse-params
  "Pure inverse of `build-params` — given a `{param-name → string}`
  getter map (e.g. the canonicalised output of `URLSearchParams`), return
  the parsed shape:

      {:variant-id    nil-or-kw
       :workspace-id  nil-or-kw
       :mode-tab      nil-or-kw  (one of :dev :docs :test)
       :active-modes  nil-or-vec
       :viewport      nil-or-kw-or-{:width :height}
       :background    nil-or-kw-or-string
       :tag-filter    nil-or-set
       :cell-overrides nil-or-map  (uses silent-drop `parse-overrides-param`)
       :substrate     nil-or-kw}

  Slot values that fail to parse degrade to nil — the caller decides
  whether to default or skip. Used by the URL-state hydrator and by
  tests for round-trip assertions. Pure data → data; JVM-testable."
  [params]
  (let [g (fn [k] (get params k))]
    {:variant-id     (parse-keyword-token   (g "variant"))
     :workspace-id   (parse-workspace-param (g "workspace"))
     :mode-tab       (parse-mode-tab-param  (g "mode-tab"))
     :active-modes   (parse-modes-param     (g "modes"))
     :viewport       (parse-viewport-param  (g "viewport"))
     :background     (parse-background-param (g "background"))
     :tag-filter     (parse-tag-filter-param (g "tag-filter"))
     :cell-overrides (parse-overrides-param  (g "overrides"))
     :substrate      (parse-substrate-param  (g "substrate"))}))

(defn variant-share-url
  "Build a sharable URL for `variant-id` against `base-url`.

  `opts`:
    :active-modes    coll of registered mode ids
    :cell-overrides  {arg-key → value} runtime overrides
    :substrate       active substrate

  Returns a string of the form
  `<base-url>?variant=<id>&modes=<list>&overrides=<list>&substrate=<s>`.
  Empty / nil parts are omitted (e.g. no modes → no `modes=` param).
  If `base-url` contains a hash route, params are inserted before `#`
  so the query remains available via `window.location.search`.
  Pure data → data; JVM + CLJS-portable."
  ([variant-id]                (variant-share-url variant-id "" nil))
  ([variant-id opts]           (variant-share-url variant-id "" opts))
  ([variant-id base-url opts]
   (append-query-params
     (or base-url "")
     (build-params (assoc opts :variant-id variant-id)))))

;; Privacy: the URL-builder above is redirect-free and never reaches a
;; third-party service. The share URL is the browser's own address bar,
;; so author-typed `:cell-overrides` stay on-box — there is no QR image
;; fetch or external encoder to leak the URL (or its embedded overrides)
;; off the machine.
