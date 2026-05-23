(ns re-frame.mcp-base.cursor
  "Shared cursor-pagination machinery for the MCP pair (rf2-ee38b.19).

  Per `spec/Principles.md` §Pagination and `spec/Tool-Pair.md`
  §Cursor pagination, every read tool whose return size is a function
  of registry / ring size MUST accept a `:limit` arg and return an
  opaque `:cursor` for continuation. Cursors are OPAQUE on the wire —
  the agent passes them back verbatim and has no business decoding the
  format.

  ## Why this ns

  Both servers previously hand-rolled the SAME machinery:

    - `tools/story-mcp/.../cursor.cljc`     (offset/total/sig over
                                             append-mostly registries)
    - `tools/re-frame2-pair-mcp/.../cursor.cljs` (after-id/ms over the
                                             bounded epoch ring)

  The cursor PAYLOAD differs by domain — story carries
  `{:offset :total :sig}`, pair carries `{:after-id :ms :until-ms
  :frame}` — but the base64 codec, the EDN read with tagged-literal
  rejection + size guard, the `::malformed` recovery contract, the
  `:limit` clamp, and the `cursor-stale-result` envelope are identical.
  Earlier the README argued the codec was consumer-side because
  `js/Buffer` vs `java.util.Base64` differ — but story-mcp's
  `cursor.cljc` already proved the codec lifts cleanly as a `.cljc`
  reader-conditional, so it lives here now.

  ## What is parameterised vs fixed

  - The base64 codec, the tagged-literal-rejecting EDN reader, the
    1 KB size cap, and the `::malformed` sentinel are FIXED here.
  - The cursor SHAPE is the consumer's — `decode-cursor` takes a
    `valid?` predicate so each server validates its own payload map
    while sharing the codec + recovery flow.
  - `parse-limit-arg` takes the consumer's `default` + `max`.
  - `cursor-stale-result` takes the consumer's `error-result` fn so
    the envelope is shaped per the consumer's wire convention while the
    `:reason` stays the cross-MCP `vocab/cursor-stale-reason`.

  ## Cross-platform

  Pure CLJC. The base64 codec resolves to `java.util.Base64` on the
  JVM (story-mcp) and `js/Buffer` on CLJS (re-frame2-pair-mcp). The EDN
  read uses `clojure.edn/read-string` (JVM) / `cljs.reader/read-string`
  (CLJS) — both gated by a `:default` tagged-literal handler that
  throws so a hostile / corrupt cursor cannot smuggle a tagged literal
  past the reader."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn])
            [re-frame.mcp-base.args :as args]
            [re-frame.mcp-base.vocab :as vocab])
  #?(:cljs (:require [cljs.reader]))
  #?(:clj (:import (java.util Base64))))

(def ^:const max-cursor-bytes
  "Hard ceiling on a cursor token's character length. Cursors are short
  opaque tokens (a base64'd EDN map of a handful of scalar slots);
  anything longer is rejected before parsing as a cheap DoS / malformed
  guard. 1 KB is far past any legitimate cursor."
  1024)

;; ---------------------------------------------------------------------------
;; Base64 codec — the `.cljc` reader-conditional both servers share.
;; ---------------------------------------------------------------------------

(defn b64-encode
  "Base64-encode a UTF-8 string. `java.util.Base64` on the JVM,
  `js/Buffer` on CLJS."
  [^String s]
  #?(:clj  (-> (Base64/getEncoder)
               (.encodeToString (.getBytes s "UTF-8")))
     :cljs (-> (js/Buffer.from s "utf8")
               (.toString "base64"))))

(defn b64-decode
  "Decode a base64 string back to UTF-8. Inverse of `b64-encode`."
  [^String s]
  #?(:clj  (String. (.decode (Base64/getDecoder) s) "UTF-8")
     :cljs (-> (js/Buffer.from s "base64")
               (.toString "utf8"))))

;; ---------------------------------------------------------------------------
;; Limit clamp.
;; ---------------------------------------------------------------------------

(defn parse-limit-arg
  "Normalise the `:limit` MCP arg into an integer in `[1, max]`,
  defaulting to `default`. Caller-supplied values above `max` clamp
  DOWN (a legitimate large request still works, just capped). Delegates
  to `re-frame.mcp-base.args/parse-positive-int` for the shared
  string/number coercion + default posture, then applies the ceiling.

  Each server bakes its own `default` (story 25, pair 50) and `max`
  (story's 200; pair's wire-cap is the implicit ceiling) — the
  convention is the CLAMP behaviour, not the numbers."
  [raw default max]
  (min max (args/parse-positive-int raw default)))

;; ---------------------------------------------------------------------------
;; Opaque cursor codec — pr-str + base64 of an EDN payload map.
;; ---------------------------------------------------------------------------

(defn encode-cursor
  "Encode a cursor payload map as an opaque base64 string.

  This is the unconditional encoder — it always produces a token for a
  payload map. The caller decides WHEN to emit a cursor (i.e. when
  there are more entries / records); the absence-of-cursor IS the
  end-of-pagination signal, so the caller passes `nil` rather than
  calling this when pagination is over. Returns `nil` for a nil /
  non-map payload as a safety net."
  [payload]
  (when (map? payload)
    (b64-encode (pr-str payload))))

(defn- read-edn-no-tags
  "Read an EDN string with EVERY tagged literal rejected. The
  `:default` data-reader throws `:rf.error/mcp-cursor-bad-edn-tag` so a
  hostile / corrupt cursor cannot smuggle a `#js`, `#inst`, or custom
  tagged literal past the reader. Caught by the surrounding try in
  `decode-cursor` → `::malformed`."
  [s]
  (let [opts {:default (fn [_tag _val]
                         (throw (ex-info ":rf.error/mcp-cursor-bad-edn-tag"
                                         {:rf.error/id :rf.error/mcp-cursor-bad-edn-tag
                                          :where       'mcp-base/decode-cursor
                                          :recovery    :no-recovery
                                          :reason      "EDN cursor carried a tagged literal — none are permitted"})))}]
    #?(:clj  (edn/read-string opts s)
       :cljs (cljs.reader/read-string opts s))))

(defn decode-cursor
  "Decode an opaque base64 cursor back to its EDN payload map.

  Returns:
    - `nil`         — the cursor arg is absent (nil / undefined) or
                      blank. The caller treats this as 'start from the
                      beginning' (offset 0 / head).
    - `::malformed` — the cursor exists but is not a well-formed,
                      `valid?`-passing payload map: it failed to
                      base64/EDN-decode, carried a tagged literal,
                      exceeded `max-cursor-bytes`, or its payload map
                      failed the consumer's `valid?` predicate. Callers
                      treat `::malformed` exactly like a STALE cursor —
                      drop it and restart.

  `valid?` is the consumer's payload-shape predicate (e.g.
  `#(and (map? %) (integer? (:offset %)) (string? (:sig %)))` for
  story; `#(and (map? %) (string? (:after-id %)))` for pair). The
  codec, the size cap, and the tagged-literal rejection are shared; the
  shape check is the consumer's.

  Hardened: non-string input ⇒ `::malformed`; oversize input ⇒
  `::malformed` BEFORE parsing; the input is decoded exactly once."
  [s valid?]
  (cond
    (or (nil? s)
        #?(:cljs (undefined? s) :clj false)
        (and (string? s) (str/blank? s)))
    nil

    (not (string? s))
    ::malformed

    (> (count s) max-cursor-bytes)
    ::malformed

    :else
    (try
      (let [v (read-edn-no-tags (b64-decode s))]
        (if (and (map? v) (valid? v))
          v
          ::malformed))
      (catch #?(:clj Throwable :cljs :default) _ ::malformed))))

(defn malformed?
  "True when `decoded` is the `::malformed` sentinel returned by
  `decode-cursor`. Convenience for consumers that prefer a predicate
  over the qualified-keyword literal at the call site."
  [decoded]
  (= ::malformed decoded))

;; ---------------------------------------------------------------------------
;; Cursor-stale envelope.
;; ---------------------------------------------------------------------------

(defn cursor-stale-result
  "Build a structured cursor-stale error result via the consumer's
  `error-result` fn.

  The `:reason` slot is the cross-MCP `vocab/cursor-stale-reason`
  (`:rf.mcp/cursor-stale`) so an agent that learned the recovery path
  on one server reuses it on the other — whether staleness means
  ring-rotation (pair) or registry-change-between-pages (story).

  `error-result` is the consumer's error-envelope builder. It is
  called as `(error-result message data-map)` where `message` is a
  human-readable string and `data-map` carries the structured slots
  (`:ok? false`, `:reason`, `:tool`, `:hint`, plus any caller `extra`).
  Each server's `error-result` shapes the wire envelope its own way
  (story-mcp's `h/error-result`, pair-mcp's `wire/err-text`); this
  builder owns only the cross-MCP slot vocabulary.

  `opts`:
    :message — override the default human-readable message.
    :hint    — override the default recovery hint.
    :extra   — a map of additional structured slots to merge in
               (e.g. pair's `:requested-id` / `:head-id`)."
  [error-result tool {:keys [message hint extra]}]
  (error-result
    (or message
        (str "Cursor stale: it no longer addresses a valid position. "
             "Drop the cursor and restart `" tool "`."))
    (merge {:ok?    false
            :reason vocab/cursor-stale-reason
            :tool   tool
            :hint   (or hint "Drop :cursor and re-request from the start.")}
           extra)))
