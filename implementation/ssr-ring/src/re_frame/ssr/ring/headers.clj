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
  three arms here are exhaustive (audit rf2-asmj1 R8 / cluster
  rf2-sljs1: the prior `:else` arm was dead)."
  [m [k v]]
  (let [existing (get m k)]
    (cond
      (nil? existing)        (assoc m k v)
      (string? existing)     (assoc m k [existing v])
      (vector? existing)     (assoc m k (conj existing v)))))

(defn headers->ring-map
  "Collapse an ordered vec-of-[name value] pairs into Ring's
  `{name string-or-vec}` shape, preserving the multi-valued case.

  Ordering note (audit rf2-asmj1 R9 / cluster rf2-sljs1) — the
  PER-NAME ordering of multi-valued headers (e.g. multiple `Set-Cookie`
  entries) is preserved by `merge-pair-into-header-map`'s `conj` onto
  the existing vector. The ACROSS-NAME ordering (the iteration order
  Ring's downstream wire-writer sees when it walks the map's keys) is
  the JDK's HAMT iteration order — stable per Clojure's persistent-map
  contract but not first-seen-pair order. Ring servers don't promise
  cross-name header order on the wire either; debug logs that compare
  serialised output across requests should sort the keys before
  diffing."
  [pairs]
  (reduce merge-pair-into-header-map {} pairs))

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

(defn ensure-content-type
  "If the response's header pairs already declare Content-Type
  (case-insensitive), return them unchanged; otherwise append a
  pair carrying `content-type`. Pure helper.

  rf2-depii — the prior check looked up `\"content-type\"` /
  `\"Content-Type\"` only, missing every other case variant
  (`\"CONTENT-TYPE\"`, `\"CoNtEnT-TyPe\"`, …) the caller might supply.
  An existing mixed-case header would slip past and the helper would
  duplicate the Content-Type, breaking Ring's multi-valued-header
  collapse downstream. We now lower-case every pair's name once and
  scan the lower-cased set — true case-insensitive match per RFC 7230
  §3.2 (header names are tokens; tokens are case-insensitive)."
  [pairs content-type]
  (let [has-ct? (some (fn [[k _v]]
                        (= "content-type"
                           (str/lower-case (str k))))
                      pairs)]
    (if has-ct?
      pairs
      (conj (vec pairs) ["Content-Type" content-type]))))

(defn headers->ring-map+default-content-type
  "Single-pass equivalent of `(-> pairs (ensure-content-type ct)
  headers->ring-map)` (rf2-uj9z8). Pre-fix the two helpers each
  walked the pairs vector — `ensure-content-type` lower-cased every
  name to scan for an existing `Content-Type`, then `headers->ring-map`
  re-walked the same pairs to fold them into the Ring header map. The
  combined form walks once: it folds each pair into the accumulator
  AND lower-cases each name once to flag whether `Content-Type` was
  seen, appending the default at the end iff not.

  Behaviour is identical to the two-step composition; the no-default
  path (caller didn't supply `content-type`) is a straight
  `headers->ring-map`."
  [pairs content-type]
  (let [step (fn [[m saw-ct?] [k v :as pair]]
               [(merge-pair-into-header-map m pair)
                (or saw-ct?
                    (= "content-type" (str/lower-case (str k))))])
        [m saw-ct?] (reduce step [{} false] pairs)]
    (if (or saw-ct? (nil? content-type))
      m
      (assoc m "Content-Type" content-type))))
