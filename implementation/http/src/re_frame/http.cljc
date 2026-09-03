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

(defn- build-managed-request-fx
  "Build a `[:rf.http/managed args-map]` fx vector for the given verb,
  URL, and caller args. Internal — the public helpers are thin wrappers."
  [method url args]
  (let [request (assoc (clojure.core/get args :request {})
                       :method method
                       :url    url)]
    [:rf.http/managed (assoc args :request request)]))

(defn get
  "Spec 014 helper — build a GET `[:rf.http/managed args-map]` fx vector.

  `args` merges into the canonical args map (top-level merge; `:request`
  itself is merged with `{:method :get :url url}`). Caller-supplied
  `:method` and `:url` under `:request` are overwritten by the helper.

  `args` MUST address the reply: supply `:reply-to`, `:on-success`, or
  `:on-failure` (rf2-et4c1s — an unaddressed request fails loud at dispatch
  with `:rf.error/http-no-reply-target`). `{:reply-to nil}` is the explicit
  fire-and-forget spelling. There is no one-argument arity: a URL-only call
  would build an effect guaranteed to fail its own boundary validation
  (rf2-3fc89f.9), so the args map is required.

  Example:
    {:fx [(rf.http/get \"/api/items\" {:on-success [:items/loaded]})
          (rf.http/get \"/api/items\"
                       {:on-success [:items/loaded]
                        :retry      retry-policy
                        :decode     ItemListSchema})]}"
  [url args] (build-managed-request-fx :get url args))

(defn post
  "Spec 014 helper — build a POST `[:rf.http/managed args-map]` fx vector.

  `args` is required and MUST address the reply (`:reply-to` /
  `:on-success` / `:on-failure`; `{:reply-to nil}` for explicit
  fire-and-forget). Pass `:body` under `:request` (and optionally
  `:request-content-type :json` to JSON-encode a clj coll, per Spec 014
  §Body encoding):

    (rf.http/post \"/api/items\"
                  {:request    {:body new-item
                                :request-content-type :json}
                   :on-success [:items/created]})"
  [url args] (build-managed-request-fx :post url args))

(defn put
  "Spec 014 helper — build a PUT `[:rf.http/managed args-map]` fx vector.

  Same shape as `post`; PUT semantics. `args` is required and MUST address
  the reply. See Spec 014 §The args map."
  [url args] (build-managed-request-fx :put url args))

(defn delete
  "Spec 014 helper — build a DELETE `[:rf.http/managed args-map]` fx vector.

  `args` is required and MUST address the reply (`:reply-to` /
  `:on-success` / `:on-failure`). Example:

    (rf.http/delete \"/api/items/42\"
                    {:on-success [:items/removed 42]})"
  [url args] (build-managed-request-fx :delete url args))

(defn patch
  "Spec 014 helper — build a PATCH `[:rf.http/managed args-map]` fx vector.

  Same shape as `post` / `put`; PATCH semantics. `args` is required and
  MUST address the reply."
  [url args] (build-managed-request-fx :patch url args))

(defn head
  "Spec 014 helper — build a HEAD `[:rf.http/managed args-map]` fx vector.

  HEAD requests typically don't carry `:decode` since the response has
  no body; the caller MUST still set `:on-success` / `:on-failure` (or
  `:reply-to`) to address the reply and branch on status. `args` is
  required."
  [url args] (build-managed-request-fx :head url args))

(defn options
  "Spec 014 helper — build an OPTIONS `[:rf.http/managed args-map]` fx
  vector. Rarely needed from user code (browsers issue OPTIONS as CORS
  preflight automatically), but provided for symmetry with the other
  verbs and the rare case of explicit capability discovery. `args` is
  required and MUST address the reply."
  [url args] (build-managed-request-fx :options url args))
