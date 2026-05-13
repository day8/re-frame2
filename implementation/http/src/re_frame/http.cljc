(ns re-frame.http
  "Spec 014 — call-site ergonomics for `:rf.http/managed` (rf2-pf4k).

  Pure helpers that synthesise the canonical `[:rf.http/managed args-map]`
  fx-vector for the common HTTP verbs. They take a URL + an optional map
  of additional args (per [Spec 014 §The args map]) and return a vector
  ready to drop into `:fx`. Result:

  ```clojure
  {:fx [(rf.http/get \"/api/items\"
         {:on-success [:items/loaded]})]}
  ```

  Reduces every call site by 4-6 lines vs spelling out the
  `[:rf.http/managed {:request {:method :get :url ...} ...}]` envelope
  directly.

  ## Surface

  - `(get url)` / `(get url args-map)`
  - `(post url)` / `(post url args-map)`
  - `(put url)` / `(put url args-map)`
  - `(delete url)` / `(delete url args-map)`
  - `(patch url)` / `(patch url args-map)`
  - `(head url)` / `(head url args-map)`
  - `(options url)` / `(options url args-map)`

  Each helper sets `(:method (:request args-map))` to the verb's
  keyword. Caller-supplied `:request` keys take precedence over the
  defaults except `:method`, which the helper always pins. The
  caller-supplied `:url` (if any) is overwritten with the helper's
  `url` argument so the call-site contract reads cleanly.

  All other top-level keys (`:decode`, `:accept`, `:retry`,
  `:timeout-ms`, `:on-success`, `:on-failure`, `:request-id`,
  `:abort-signal`, etc.) pass through to the canonical args map
  unchanged. See Spec 014 §The args map for the closed key set.

  ## Why ship in `day8/re-frame2-http`

  The helpers build `[:rf.http/managed ...]` fx vectors — calling them
  only makes sense when the http artefact is on the classpath. Shipping
  here couples the helpers to the artefact that supplies the fx they
  reference; an app that drops the http dep loses the helpers along
  with the fx, instead of failing at dispatch time with a stale
  `:rf.error/no-such-fx`.

  ## Naming

  `get` collides with `clojure.core/get`; we `:refer-clojure :exclude
  [get]`. Users alias the namespace (`[re-frame.http :as rf.http]`) and
  write `(rf.http/get ...)` — the bare symbol form is rare since the
  helpers are typically inside `:fx [...]` already-namespaced. The
  other verbs (`post`, `put`, `delete`, `patch`, `head`, `options`)
  don't collide with `clojure.core`.

  ## Args-map merging

  The helpers compose by `merge`:

  ```
  (rf.http/post \"/api/items\"
                {:request {:body item}
                 :on-success [:item/saved]})
  ;; →
  [:rf.http/managed
   {:request {:method :post :url \"/api/items\" :body item}
    :on-success [:item/saved]}]
  ```

  Top-level `merge` (caller wins) for every key except `:request`,
  which is itself merged with the helper's `{:method <verb> :url url}`
  pair (helper's `:method` and `:url` win). This lets callers supply
  the request envelope's other slots (`:headers`, `:body`, `:params`,
  `:credentials`, etc.) without losing the helper's verb-pinning.

  See Spec 014 for the canonical args-map shape."
  (:refer-clojure :exclude [get])
  (:require [re-frame.http-privacy :as privacy]))

;; Privacy surface — Spec 014 §Privacy (rf2-bma05). Re-exported here so
;; users who alias `re-frame.http :as rf.http` get a uniform call site
;; for all the HTTP-side facilities (verbs + privacy helpers).
(def declare-sensitive-header!
  "Spec 014 §Privacy — extend the header-denylist with an app-specific
  sensitive header. Names stored lower-cased; matching is case-insensitive.
  See `re-frame.http-privacy/declare-sensitive-header!`."
  privacy/declare-sensitive-header!)

(def clear-sensitive-headers!
  "Spec 014 §Privacy — reset the app-extended header-denylist (defaults
  remain). Test-only; production code should not need this.
  See `re-frame.http-privacy/clear-sensitive-headers!`."
  privacy/clear-sensitive-headers!)

(def declare-sensitive-query-param!
  "Spec 014 §Privacy (rf2-2p8wr) — extend the query-string-param denylist
  with an app-specific sensitive parameter name. Names stored lower-
  cased; matching is case-insensitive. URLs carrying a denylisted param
  have the *value* redacted (preserving param name + position) in every
  `:rf.http/*` trace event regardless of the originating handler's
  `:sensitive?` flag.
  See `re-frame.http-privacy/declare-sensitive-query-param!`."
  privacy/declare-sensitive-query-param!)

(def clear-sensitive-query-params!
  "Spec 014 §Privacy (rf2-2p8wr) — reset the app-extended query-param
  denylist (defaults remain). Test-only; production code should not
  need this.
  See `re-frame.http-privacy/clear-sensitive-query-params!`."
  privacy/clear-sensitive-query-params!)

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
