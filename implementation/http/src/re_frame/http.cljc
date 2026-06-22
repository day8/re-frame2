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
  the ns (`[re-frame.http :as rf.http]`).

  ## Privacy — app-specific carriers ride the :rf.http/managed registration (EP-0025)

  This façade no longer re-exports `declare-sensitive-header!` /
  `declare-sensitive-query-param!` (and their `clear-*!` siblings). An app
  declares its sensitive HTTP carrier NAMES on the `:rf.http/managed`
  `reg-fx` registration metadata — the `:carriers` block (the EP-0025
  transient-payload case), not on the frame and not through a process-global
  mutation:

      (rf/reg-fx :rf.http/managed
        {:carriers {:headers      [\"X-Honeycomb-Team\"]
                    :query-params [\"shop_token\"]}}
        re-frame.http.managed/managed-handler)

  The immutable built-in header / query-param denylists still apply
  unconditionally; the registration's carrier extension set UNIONS onto them.
  See Spec 014 §Privacy and `re-frame.http.managed` / `re-frame.http.privacy`."
  (:refer-clojure :exclude [get]))

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
