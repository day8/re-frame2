(ns re-frame.http
  "Spec 014 — call-site ergonomics for `:rf.http/managed`.

  Pure helpers that synthesise `[:rf.http/managed args-map]` fx-vectors
  for the common HTTP verbs (`get` / `post` / `put` / `delete` /
  `patch` / `head` / `options`):

  ```clojure
  {:fx [(rf.http/get \"/api/items\"
         {:on-success [:items/loaded]})]}
  ```

  The helper pins `(:method (:request args-map))` to the verb and the
  `:url` to the helper's argument; all other slots pass through. Top-
  level `merge` (caller wins) for every key except `:request`, which
  merges with the helper's `{:method <verb> :url url}` pair (helper's
  `:method` / `:url` win — every other request slot is caller-controlled).

  Ships with the http artefact so dropping the dep drops the helpers
  alongside the fx they reference (rather than failing at dispatch
  time with `:rf.error/no-such-fx`). `get` collides with
  `clojure.core/get`; we `:refer-clojure :exclude [get]` — users alias
  the ns (`[re-frame.http :as rf.http]`)."
  (:refer-clojure :exclude [get])
  (:require [re-frame.http-privacy-headers :as privacy-headers]
            [re-frame.http-url             :as http-url]))

;; Privacy surface — Spec 014 §Privacy (rf2-bma05). Re-exported here so
;; users who alias `re-frame.http :as rf.http` get a uniform call site
;; for all the HTTP-side facilities (verbs + privacy helpers).
(def declare-sensitive-header!
  "Spec 014 §Privacy — extend the header-denylist with an app-specific
  sensitive header. Names stored lower-cased; matching is case-insensitive.
  See `re-frame.http-privacy-headers/declare-sensitive-header!`."
  privacy-headers/declare-sensitive-header!)

(def clear-sensitive-headers!
  "Spec 014 §Privacy — reset the app-extended header-denylist (defaults
  remain). Test-only; production code should not need this.
  See `re-frame.http-privacy-headers/clear-sensitive-headers!`."
  privacy-headers/clear-sensitive-headers!)

(def declare-sensitive-query-param!
  "Spec 014 §Privacy (rf2-2p8wr) — extend the query-string-param denylist
  with an app-specific sensitive parameter name. Names stored lower-
  cased; matching is case-insensitive. URLs carrying a denylisted param
  have the *value* redacted (preserving param name + position) in every
  `:rf.http/*` trace event regardless of the originating handler's
  `:sensitive?` flag.
  See `re-frame.http-url/declare-sensitive-query-param!`."
  http-url/declare-sensitive-query-param!)

(def clear-sensitive-query-params!
  "Spec 014 §Privacy (rf2-2p8wr) — reset the app-extended query-param
  denylist (defaults remain). Test-only; production code should not
  need this.
  See `re-frame.http-url/clear-sensitive-query-params!`."
  http-url/clear-sensitive-query-params!)

(defn- build
  "Build a `[:rf.http/managed args-map]` fx vector for the given verb,
  URL, and caller args. Internal — the public helpers are thin wrappers."
  [method url args]
  (let [req (assoc (clojure.core/get args :request {})
                   :method method
                   :url    url)]
    [:rf.http/managed (assoc args :request req)]))

(defn get
  "Spec 014 helper — build a GET `[:rf.http/managed args-map]` fx vector.

  Single-arity form is the minimal call: just the URL.

  Multi-arity form merges `args` into the canonical args map (top-level
  merge; `:request` itself is merged with `{:method :get :url url}`).
  Caller-supplied `:method` and `:url` under `:request` are overwritten
  by the helper.

  Example:
    {:fx [(rf.http/get \"/api/items\")
          (rf.http/get \"/api/items\" {:on-success [:items/loaded]})
          (rf.http/get \"/api/items\"
                       {:on-success [:items/loaded]
                        :retry      retry-policy
                        :decode     ItemListSchema})]}"
  ([url]      (build :get url {}))
  ([url args] (build :get url args)))

(defn post
  "Spec 014 helper — build a POST `[:rf.http/managed args-map]` fx vector.

  Pass `:body` under `:request` (and optionally `:request-content-type
  :json` to JSON-encode a clj coll, per Spec 014 §Body encoding):

    (rf.http/post \"/api/items\"
                  {:request    {:body new-item
                                :request-content-type :json}
                   :on-success [:items/created]})"
  ([url]      (build :post url {}))
  ([url args] (build :post url args)))

(defn put
  "Spec 014 helper — build a PUT `[:rf.http/managed args-map]` fx vector.

  Same shape as `post`; PUT semantics. See Spec 014 §The args map."
  ([url]      (build :put url {}))
  ([url args] (build :put url args)))

(defn delete
  "Spec 014 helper — build a DELETE `[:rf.http/managed args-map]` fx vector.

  Single-arity: just the URL. Multi-arity: merge `args` into the
  canonical envelope. Example:

    (rf.http/delete \"/api/items/42\"
                    {:on-success [:items/removed 42]})"
  ([url]      (build :delete url {}))
  ([url args] (build :delete url args)))

(defn patch
  "Spec 014 helper — build a PATCH `[:rf.http/managed args-map]` fx vector.

  Same shape as `post` / `put`; PATCH semantics."
  ([url]      (build :patch url {}))
  ([url args] (build :patch url args)))

(defn head
  "Spec 014 helper — build a HEAD `[:rf.http/managed args-map]` fx vector.

  HEAD requests typically don't carry `:decode` since the response has
  no body; the caller can still set `:on-success` / `:on-failure` to
  branch on status."
  ([url]      (build :head url {}))
  ([url args] (build :head url args)))

(defn options
  "Spec 014 helper — build an OPTIONS `[:rf.http/managed args-map]` fx
  vector. Rarely needed from user code (browsers issue OPTIONS as CORS
  preflight automatically), but provided for symmetry with the other
  verbs and the rare case of explicit capability discovery."
  ([url]      (build :options url {}))
  ([url args] (build :options url args)))
