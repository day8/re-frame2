(ns re-frame.mcp-base.args
  "Argument-coercion helpers for MCP tools. The parsers in this ns
  take an ALREADY-RESOLVED raw value (extracted by the consumer from
  its platform-specific args object: a JS object for pair2-mcp, a
  Clojure map for story-mcp/causa-mcp) and normalise it into the
  Clojure-side type the tool body expects.

  ## Cross-server convention

  Argument names and default postures are a cross-MCP convention. An
  agent that learns `:dedup` defaults true on pair2-mcp must see the
  same default everywhere. These parsers encode the defaults so they
  can't drift across consumers."
  (:refer-clojure :exclude [parse-boolean])
  (:require [clojure.string :as str]))

(defn parse-boolean
  "Normalise a possibly-string MCP arg into a boolean. Accepts
  booleans (passthrough), strings (`\"true\"`/`\"false\"`/`\"1\"`/
  `\"0\"`/`\"yes\"`/`\"no\"`, case-insensitive), keywords
  (`:true`/`:false`), or nil. Unrecognised values fall back to
  `default`.

  Use a small wrapper at each call-site to bake the default and the
  rejection posture (default-suppress vs default-allow)."
  [raw default]
  (cond
    (nil? raw)       default
    (boolean? raw)   raw
    (= raw :true)    true
    (= raw :false)   false
    (string? raw)    (let [s (str/lower-case (str/trim raw))]
                       (cond
                         (contains? #{"true" "1" "yes" "y" "on"} s)   true
                         (contains? #{"false" "0" "no" "n" "off"} s)  false
                         :else default))
    :else            default))

(defn parse-positive-int
  "Normalise a possibly-string MCP arg into a positive integer. Accepts
  ints (passed through, clamped to ≥1), strings (parsed; non-numeric
  falls back to `default`), or nil (returns `default`). Non-positive
  numeric inputs clamp to 1.

  The `default` is the convention's documented cap (e.g. 50 for cursor
  pagination, 5000 for token caps)."
  [raw default]
  (cond
    (nil? raw)
    default

    (integer? raw)
    (max 1 (long raw))

    (number? raw)
    (let [n (long raw)]
      (if (zero? n) 1 (max 1 n)))

    (string? raw)
    #?(:clj (try
              (max 1 (Long/parseLong (str/trim raw)))
              (catch NumberFormatException _ default))
       :cljs (let [n (js/parseInt raw 10)]
               (if (and (number? n) (not (js/isNaN n)))
                 (max 1 (long n))
                 default)))

    :else
    default))

(defn parse-non-negative-int
  "Like `parse-positive-int` but admits zero (useful when zero means
  \"disabled\" — e.g. max-tokens=0 disables the cap)."
  [raw default]
  (cond
    (nil? raw)
    default

    (integer? raw)
    (max 0 (long raw))

    (number? raw)
    (max 0 (long raw))

    (string? raw)
    #?(:clj (try
              (max 0 (Long/parseLong (str/trim raw)))
              (catch NumberFormatException _ default))
       :cljs (let [n (js/parseInt raw 10)]
               (if (and (number? n) (not (js/isNaN n)))
                 (max 0 (long n))
                 default)))

    :else
    default))

(defn parse-keyword
  "Read a keyword from an agent-supplied argument. MCP arguments
  arrive as JSON, so keyword-typed ids come in as strings. Strips a
  leading `:` if present (some agents may serialise EDN-ish). Returns
  nil for nil / blank input.

  Namespaced keywords are supported (`\"ns/name\"` ⇒ `:ns/name`)."
  [v]
  (cond
    (keyword? v) v
    (nil? v)     nil
    (string? v)
    (let [s (if (and (> (count v) 0) (= \: (.charAt ^String v 0)))
              (subs v 1)
              v)]
      (cond
        (str/blank? s)
        nil

        (str/includes? s "/")
        (let [parts (str/split s #"/" 2)]
          (keyword (first parts) (second parts)))

        :else (keyword s)))
    :else nil))

(defn parse-mode
  "Normalise a possibly-string MCP arg into one of a small set of
  mode keywords. Accepts strings (`\"diff\"`/`\"full\"` etc),
  keywords (passthrough if recognised), or nil. Unrecognised values
  fall back to `default`.

  `recognised` is a set of accepted keywords (e.g. `#{:diff :full}`)."
  [raw default recognised]
  (cond
    (nil? raw)              default
    (contains? recognised raw)   raw
    (and (string? raw)
         (contains? recognised (keyword raw)))
    (keyword raw)
    :else                   default))
