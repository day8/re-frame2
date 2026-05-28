(ns re-frame.ssr.ring.headers
  "Header materialisation for the Ring host adapter.

  re-frame.ssr stores headers internally as an ordered vector of
  `[name value]` pairs (case-insensitive name match). Ring accepts
  headers as a map of name → string OR name → vector-of-strings;
  multiple values under one name go via a vector. We collapse repeated
  pairs into vectors so multi-valued headers (Set-Cookie, Vary,
  Link, ...) round-trip correctly."
  (:require [clojure.string :as str]
            [re-frame.ssr.ring.cookie :as cookie]))

(set! *warn-on-reflection* true)

(defn merge-pair-into-header-map
  "Fold a `[name value]` header pair into the accumulating Ring headers
  map. The accumulator only ever carries `nil`, `string`, or `vector`
  values per the contract upstream — no other shapes flow in — so the
  three arms here are exhaustive."
  [m [k v]]
  (let [existing (get m k)]
    (cond
      (nil? existing)        (assoc m k v)
      (string? existing)     (assoc m k [existing v])
      (vector? existing)     (assoc m k (conj existing v)))))

(defn append-set-cookies
  "For every cookie map in the response's :cookies vector, append one
  Set-Cookie header to the headers map. Returns the updated headers
  map."
  [headers-map cookies]
  (reduce
    (fn [m c]
      (merge-pair-into-header-map m ["Set-Cookie"
                                     (cookie/cookie->set-cookie-header c)]))
    headers-map
    cookies))

(defn headers->ring-map+default-content-type
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, defaulting `Content-Type` to
  `content-type` (case-insensitive) in the SAME single pass when the
  pairs don't already declare one (rf2-uj9z8). Folds each pair into the
  accumulator AND lower-cases each name once to flag whether
  `Content-Type` was seen, appending the default at the end iff not and
  iff `content-type` is non-nil.

  Case-insensitive Content-Type detection (rf2-depii) covers every
  casing variant (`CONTENT-TYPE`, `CoNtEnT-TyPe`, …) per RFC 7230 §3.2
  (header names are tokens; tokens are case-insensitive) — a mixed-case
  caller header must NOT get a duplicate default appended.

  Ordering: the PER-NAME ordering of multi-valued headers (multiple
  `Set-Cookie` entries) is preserved by `merge-pair-into-header-map`'s
  `conj`. The ACROSS-NAME ordering is the JDK's HAMT iteration order —
  stable but not first-seen order; Ring servers don't promise cross-name
  header order on the wire either."
  [pairs content-type]
  (let [step (fn [[m saw-ct?] [k v :as pair]]
               [(merge-pair-into-header-map m pair)
                (or saw-ct?
                    (= "content-type" (str/lower-case (str k))))])
        [m saw-ct?] (reduce step [{} false] pairs)]
    (if (or saw-ct? (nil? content-type))
      m
      (assoc m "Content-Type" content-type))))
