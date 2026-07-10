(ns re-frame.testbed.config
  "Resolves the on-disk source root used by testbed open-in-editor links.

  The input is a checkout root, supplied by `?checkout-root=` or the
  build-seeded `checkout-root` define. `resolve-source-root` appends the
  caller's tool-relative source subdirectory. Paths are normalized to forward
  slashes; query decoding preserves literal `+` characters. Missing roots
  resolve to nil so open-in-editor can remain a no-op."
  (:require [clojure.string :as str]))

;; @define {string}
;; Seeded from RF2_TESTBED_PROJECT_ROOT by implementation/shadow-cljs.edn.
(goog-define checkout-root "")

(defn- non-blank
  [s]
  (when (and (string? s) (seq (str/trim s))) s))

(defn- decode-component
  "Decode percent escapes, leaving malformed input unchanged."
  [v]
  (try
    (js/decodeURIComponent v)
    (catch :default _ v)))

(defn- query-param-from-search
  "Return a decoded, trimmed query value or nil. Parsing is deliberately not
  form-urlencoded: a literal `+` in a checkout path must remain `+`."
  [search param-name]
  (when-let [s (non-blank search)]
    (let [s (cond-> s (str/starts-with? s "?") (subs 1))]
      (some (fn [pair]
              (let [eq  (str/index-of pair "=")
                    k   (decode-component (if eq (subs pair 0 eq) pair))]
                (when (= k param-name)
                  (when-let [raw (non-blank (decode-component
                                             (if eq (subs pair (inc eq)) "")))]
                    (str/trim raw)))))
            (str/split s #"&")))))

(defn- query-param
  "Read a query value from the browser, or nil outside a browser."
  [param-name]
  (when (exists? js/window)
    (query-param-from-search (-> js/window .-location .-search) param-name)))

(defn- to-forward-slashes
  "Canonicalize Windows and POSIX separators to `/`."
  [s]
  (str/replace s #"\\" "/"))

(defn- strip-trailing-slash
  "Strip one trailing slash, but preserve the POSIX root `/`."
  [s]
  (if (and (> (count s) 1) (str/ends-with? s "/"))
    (subs s 0 (dec (count s)))
    s))

(defn- join-root+subdir
  "Join a checkout root and source subdirectory using forward slashes and a
  single boundary separator. A blank subdirectory returns the normalized
  root. The POSIX root `/` remains significant."
  [root subdir]
  (let [normalized-root   (-> root str/trim to-forward-slashes strip-trailing-slash)
        normalized-subdir (-> (or subdir "")
                              str/trim
                              to-forward-slashes
                              (str/replace #"^/+" ""))]
    (if (seq normalized-subdir)
      ;; `/` is the only normalized root that already supplies the boundary.
      (if (str/ends-with? normalized-root "/")
        (str normalized-root normalized-subdir)
        (str normalized-root "/" normalized-subdir))
      normalized-root)))

(defn resolve-source-root
  "Resolve a testbed source root for tool-relative `subdir`.

  A non-blank `?checkout-root=` override wins over the build-time
  `checkout-root` define. Both values name the checkout root and receive the
  same normalized subdirectory join. Returns nil when neither is available."
  [subdir]
  (when-let [root (non-blank (or (query-param "checkout-root") checkout-root))]
    (join-root+subdir root subdir)))
