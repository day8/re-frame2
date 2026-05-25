(ns day8.re-frame2-xray.views.edn-inspector-default-formatters
  "Default `IXrayEdnInspector` formatters for common CLJS types
  (rf2-x16b1 · follow-on to rf2-0qrcr phase 7).

  ## Why this exists

  Phase 7 of rf2-oqa60 shipped the `IXrayEdnInspector` protocol as the
  single extension seam for the closed phase-1 renderer. The widget
  ships ZERO default protocol implementations — consuming apps must
  opt in per domain type via `extend-type`.

  For common Clojure(Script) types where the raw repr is genuinely
  cramped or unhelpful, shipping opinionated defaults serves the
  primary lenses of CLARITY and ELEGANCE without forcing every app
  to repeat the same boilerplate. Mike's 2026-05-26 ruling: ship the
  curated set `uuid + inst (+ uri)`.

  ## Coverage

  - **`cljs.core/UUID`** — header compacts to `#uuid \"…last8\"`
    with a `title` carrying the full canonical 36-char form on hover;
    body expands to the full canonical form. Built-in scalar repr
    `#uuid \"<full>\"` is correct but visually noisy in long lists of
    uuids — the compact-with-hover form scans an inspector at a
    glance, the full form drops in on expand.

  - **`js/Date`** (i.e. `inst?` instances in CLJS) — header renders
    a friendly relative-time string (`just now`, `3m ago`, `2h ago`,
    `5d ago`, `in 3m`, ISO date for older than 30 days) with the
    full ISO carried on `title`; body expands to the ISO string.
    The relative-time formatter is a ~30-line pure function with no
    library dependency — see `format-relative` below.

  - **URI heuristic** — deliberately **NOT** shipped. CLJS has no
    built-in `uri?` predicate; extending `js/String` so non-URL
    strings can return `nil` would route every string through the
    protocol seam, which is invasive on a fundamental type. Domain
    URI types (`goog.Uri`, project-specific wrappers) can opt in
    via `extend-type` per the standard contract. Documented in
    spec/021 §10.0.9.

  ## Loading

  This namespace is `:require`d by `views.edn-inspector` itself —
  loading the renderer also loads the defaults. No call-site
  registration needed.

  ## Precedence

  Consumers who extend `IXrayEdnInspector` on the same types (e.g. a
  project that wants its own uuid rendering) **win** — `extend-type`
  installs the most-recently-loaded impl. The bundled defaults are
  inert if a consumer has already extended the type. (CLJS protocol
  dispatch is not class-hierarchy-based, so there is no `:default`
  ambiguity.)

  ## Why not extend in the protocol ns

  The protocol ns (`edn-inspector-protocol`) stays a pure contract
  surface — defprotocol + safe accessors + nothing else. Default
  formatters are a separate concern: opinionated UI choices that
  could plausibly be swapped out by a consumer's preferred
  conventions. Keeping them in a sibling ns keeps the contract crisp
  and lets the defaults be diff'd / replaced / studied as a single
  artefact."
  (:require [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack]]
            [day8.re-frame2-xray.views.edn-inspector-protocol
             :refer [IXrayEdnInspector]]))

;; =========================================================================
;; styles — read CSS-variable tokens so light/dark themes resolve at paint
;; =========================================================================

(defn- token-style
  [token-key]
  {:color       (get tokens token-key)
   :font-family mono-stack})

;; =========================================================================
;; UUID — compact header `#uuid "…<last-8>"`, full body
;; =========================================================================

(defn- uuid-compact-tail
  "Take the last 8 hex chars of a canonical uuid string. Pure data.
  Used to build the `#uuid \"…<tail>\"` collapsed header."
  [s]
  (let [n (count s)]
    (if (>= n 8) (subs s (- n 8)) s)))

(defn render-uuid-header
  "Build the collapsed-header hiccup for a uuid. Public for tests."
  [v]
  (let [s    (str v)
        tail (uuid-compact-tail s)]
    [:span {:data-rf-type        "uuid"
            :data-rf-default-fmt "uuid"
            :title               (str "#uuid \"" s "\"")
            :style               (token-style :info)}
     (str "#uuid \"…" tail "\"")]))

(defn render-uuid-body
  "Build the expanded-body hiccup for a uuid. Public for tests."
  [v]
  [:span {:data-rf-default-fmt-body "uuid"
          :style                    (token-style :info)}
   (str "#uuid \"" v "\"")])

(extend-type cljs.core/UUID
  IXrayEdnInspector
  (-xray-render-header [v _opts] (render-uuid-header v))
  (-xray-render-body   [v _opts] (render-uuid-body v)))

;; =========================================================================
;; inst (js/Date) — relative-time header, ISO body
;; =========================================================================

(def ^:private MS_PER_SECOND 1000)
(def ^:private MS_PER_MINUTE (* 60 MS_PER_SECOND))
(def ^:private MS_PER_HOUR   (* 60 MS_PER_MINUTE))
(def ^:private MS_PER_DAY    (* 24 MS_PER_HOUR))
(def ^:private MS_PER_30_DAYS (* 30 MS_PER_DAY))

(defn- pad2
  "Zero-pad a non-negative integer to 2 chars."
  [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- iso-date-only
  "`YYYY-MM-DD` from a js/Date in UTC. Used as the fallback when a
  date is older than 30 days — the relative-time formatter caps at
  `30d ago` to avoid implying false precision for ancient dates."
  [^js d]
  (str (.getUTCFullYear d) "-"
       (pad2 (inc (.getUTCMonth d))) "-"
       (pad2 (.getUTCDate d))))

(defn format-relative
  "Format a js/Date as a relative-time string against `now-ms`. Pure
  function — pass `now-ms` so tests can pin the reference.

  Bucketing:

  - `< 5s`           → `just now`
  - `< 60s`          → `Ns ago` / `in Ns`
  - `< 60m`          → `Nm ago` / `in Nm`
  - `< 24h`          → `Nh ago` / `in Nh`
  - `< 30d`          → `Nd ago` / `in Nd`
  - else             → ISO date `YYYY-MM-DD`

  The `in N…` future-tense form catches scheduled-event timestamps;
  the `30d` upper bound on relative-time avoids `247d ago` reading
  as precise when the eye actually wants `2025-09-12`.

  Public for tests."
  [^js d now-ms]
  (let [t      (.getTime d)
        diff   (- now-ms t)
        abs    (Math/abs diff)
        future? (neg? diff)]
    (cond
      (< abs (* 5 MS_PER_SECOND))
      "just now"

      (< abs MS_PER_MINUTE)
      (let [n (.floor js/Math (/ abs MS_PER_SECOND))]
        (if future? (str "in " n "s") (str n "s ago")))

      (< abs MS_PER_HOUR)
      (let [n (.floor js/Math (/ abs MS_PER_MINUTE))]
        (if future? (str "in " n "m") (str n "m ago")))

      (< abs MS_PER_DAY)
      (let [n (.floor js/Math (/ abs MS_PER_HOUR))]
        (if future? (str "in " n "h") (str n "h ago")))

      (< abs MS_PER_30_DAYS)
      (let [n (.floor js/Math (/ abs MS_PER_DAY))]
        (if future? (str "in " n "d") (str n "d ago")))

      :else
      (iso-date-only d))))

(defn- iso-string
  "ISO-8601 string for a js/Date. Uses the built-in `.toISOString` —
  always UTC, always `YYYY-MM-DDTHH:mm:ss.sssZ`."
  [^js d]
  (try (.toISOString d)
       (catch :default _ (str d))))

(defn render-inst-header
  "Build the collapsed-header hiccup for an inst. Public for tests.
  `now-ms` is the reference for the relative-time formatter; in the
  protocol-method path it defaults to `(.getTime (js/Date.))`."
  ([v] (render-inst-header v (.getTime (js/Date.))))
  ([v now-ms]
   (let [iso (iso-string v)
         rel (format-relative v now-ms)]
     [:span {:data-rf-type        "inst"
             :data-rf-default-fmt "inst"
             :title               iso
             :style               (token-style :info)}
      (str "#inst \"" rel "\"")])))

(defn render-inst-body
  "Build the expanded-body hiccup for an inst. Public for tests."
  [v]
  [:span {:data-rf-default-fmt-body "inst"
          :style                    (token-style :info)}
   (str "#inst \"" (iso-string v) "\"")])

(extend-type js/Date
  IXrayEdnInspector
  (-xray-render-header [v _opts] (render-inst-header v))
  (-xray-render-body   [v _opts] (render-inst-body v)))
