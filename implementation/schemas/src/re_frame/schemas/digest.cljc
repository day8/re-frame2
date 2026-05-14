(ns re-frame.schemas.digest
  "Schema-digest algorithm (Spec 010 §Digest algorithm).

  A stable, cross-runtime hash over a frame's registered `app-db`
  schema set. The wire form is `\"sha256:\" + first-16-hex-chars`. Two
  frames produce equal digests iff their `{path → schema-value}` maps
  serialise byte-for-byte identically.

  Per Spec 010 §Schema digest line 491 the per-schema serialisation
  step routes through the registered validator's `schema-print`
  companion fn — pluggable via `re-frame.schemas.validator/printer-fn`
  (rf2-wla45). The default is `validator/default-edn-print` (the
  Malli-EDN canonical `pr-str`); non-Malli ports register their own
  serialiser via `set-schema-printer!` so the digest reflects the
  registered validator's serialisation contract rather than the
  framework's Malli-EDN default. The rest of the pipeline — SHA-256
  per entry, `<path> <hex>\\n` line emission, lexicographic line sort,
  `\"sha256:\" + first-16-hex` truncation — is fixed by spec and shared
  across every port.

  Used by:
    - The epoch-restore schema-mismatch trace (epoch.cljc) — pinpoints
      when a frame's schema set drifted between record and restore.
    - SSR hydration (Spec 011) — server stamps its digest on the payload;
      the client compares its own digest at hydrate time.
    - Pair tools — flag attached REPL sessions whose runtime schemas
      have shifted under them."
  (:require #?(:cljs [goog.crypt :as gcrypt])
            #?(:cljs [goog.crypt.Sha256])
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator])
  #?(:clj (:import [java.security MessageDigest]
                   [java.nio.charset StandardCharsets])))

#?(:clj (set! *warn-on-reflection* true))

(defn- utf8-bytes
  "Encode a string as UTF-8 bytes."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (gcrypt/stringToUtf8ByteArray s)))

(defn- bytes->hex
  "Lowercase hex encoding of a byte sequence. Cross-runtime byte-stable."
  [bs]
  #?(:clj  (let [sb (StringBuilder.)]
             (doseq [b bs]
               (let [u (bit-and (long b) 0xff)]
                 (when (< u 0x10) (.append sb \0))
                 (.append sb (Long/toString u 16))))
             (.toString sb))
     :cljs (gcrypt/byteArrayToHex bs)))

(defn- sha256-hex
  "Return the lowercase 64-char hex SHA-256 of a UTF-8 string. Uses
  the platform's native SHA-256 (java.security.MessageDigest on JVM,
  goog.crypt.Sha256 on CLJS) — both are FIPS 180-3 compliant and
  produce byte-identical output for the same input."
  [s]
  #?(:clj
     (let [md (MessageDigest/getInstance "SHA-256")]
       (.update md ^bytes (utf8-bytes s))
       (bytes->hex (.digest md)))
     :cljs
     (let [h (goog.crypt.Sha256.)]
       (.update h (utf8-bytes s))
       (bytes->hex (.digest h)))))

(defn- digest-line
  "Per Spec 010 §Digest algorithm step 3 — emit one line per
  `(path, schema-value)` of the form `<path-string> <hex-of-sha256>\\n`.
  The per-schema serialisation routes through the registered
  `schema-print` companion (rf2-wla45) so non-Malli ports compute
  digests against their own canonical bytes."
  [path schema-value]
  (str (pr-str path)
       " "
       (sha256-hex (validator/run-printer schema-value))
       "\n"))

(defn- compute-digest
  "Run the Spec 010 §Digest algorithm against the supplied
  `{path → schema-value}` map. Returns the canonical wire form
  `\"sha256:\" + first-16-hex-chars`."
  [path->schema]
  (let [lines       (mapv (fn [[path schema]] (digest-line path schema))
                          path->schema)
        sorted      (sort lines)            ;; lexicographic byte order
        concatted   (apply str sorted)
        full-hex    (sha256-hex concatted)]
    (str "sha256:" (subs full-hex 0 16))))

(defn app-schemas-digest
  "Return a stable digest of the frame's registered `app-db` schema set
  — the same shape `app-schemas` would return — as the canonical wire
  form `\"sha256:\" + 16 lowercase-hex-chars`.

  Per Spec 010 §Digest algorithm the procedure is byte-deterministic
  and cross-runtime: a CLJS server and a CLJS client running the same
  schema set produce byte-identical digests. The empty schema set
  produces a stable, well-defined digest (the SHA-256 of the empty
  string — the lines list collapses to a single empty concatenation).

  Arities:
    (app-schemas-digest)              ;; sugar for (app-schemas-digest {})
    (app-schemas-digest frame-id)     ;; keyword sugar
    (app-schemas-digest opts)         ;; opts map; supports {:frame ...}

  Uses include the SSR hydration handshake (Spec 011 §The :rf/hydrate
  event), the epoch-restore schema-mismatch trace (Tool-Pair §Time-
  travel), and pair-tool drift detection."
  ([] (app-schemas-digest {}))
  ([opts-or-frame-id]
   (let [opts     (storage/coerce-opts opts-or-frame-id)
         frame-id (storage/resolve-frame opts)]
     (compute-digest (storage/app-schemas {:frame frame-id})))))
