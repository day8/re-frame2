(ns re-frame.http.stub-transport
  "Stub transports for `:rf.http/managed` (Spec 014 §Testing): the fake backend
  a demo runs on, and the stub a test answers its requests from.

  A stub transport replaces only the HOST SEND — Fetch on CLJS,
  `java.net.http.HttpClient` on the JVM. Everything else is the real pipeline:
  the `:before` / `:after` interceptors, status classification, `:decode`,
  `:accept`, retry and backoff, the privacy scrub, the reply envelope's work
  id, attempt and completed-at, supersession and abort. So a body that fails
  the request's `:decode` schema fails there, a retry policy re-sends, and a
  `:sensitive?` request is scrubbed from the trace, exactly as against a
  server. The contract a transport fulfils is described at the seam, in
  `re-frame.http.transport`.

  Two transports ship:

  - `route-transport` answers each request from a route map — or from one fn
    that answers every request, which is how a demo backend routes for
    itself — optionally after a delay.
  - `pending-transport` holds every request until the caller answers, fails
    or abandons it: for abort, late-completion and in-flight tests.

  And two ways to put one in place:

  - `with-transport` — a test scope. Every request ISSUED inside it goes
    through the transport, and keeps it after the scope exits.
  - `install-transport!` — a frame's own transport, for a demo. It lasts
    until the frame is destroyed.

  A response, wherever this namespace takes one, is a map:

      {:status   200                   ; default 200
       :headers  {\"x-total\" \"3\"}     ; optional
       :body     {:id 1}               ; a string rides verbatim; any other
                                       ; value is JSON-encoded, with
                                       ; Content-Type application/json unless
                                       ; :headers names one
       :delay-ms 20}                   ; optional, route-transport only

  `:body-binary` replaces `:body` for a binary read, and `{:failure {:kind
  :rf.http/transport}}` (or `:rf.http/timeout`) is a network failure instead
  of a response."
  (:require [re-frame.http.decode    :as rf.http.decode]
            [re-frame.http.json      :as rf.http.json]
            [re-frame.http.registry  :as rf.http.registry]
            [re-frame.frame          :as rf.frame]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.interop        :as rf.interop]))

(defn- wire-response
  "A response map (see the ns docstring) as the normalised response a
  transport's `respond` takes."
  [{:keys [status status-text headers body body-binary]}]
  (cond-> {:status (or status 200) :headers (or headers {})}
    status-text         (assoc :status-text status-text)
    (some? body-binary) (assoc :body-binary body-binary)
    (string? body)      (assoc :body-text body)
    (and (some? body) (not (string? body)))
    (-> (assoc :body-text (rf.http.json/json-stringify body))
        (cond-> (nil? (rf.http.decode/content-type-of headers))
          (assoc-in [:headers "content-type"] "application/json")))))

(defn- answer!
  "Settle a request with `response` through the transport `callbacks`: fail it
  for a `{:failure …}`, respond otherwise. Returns whether it settled."
  [response {:keys [respond fail]}]
  (if-let [failure (:failure response)]
    (fail failure)
    (respond (wire-response response))))

(defn route-transport
  "A transport that answers each request from `routes`.

  `routes` is a route map, `{[method url] response-or-fn}`, keyed by the
  request's method and the url it goes out on — after the `:before`
  interceptors, with any `:params` merged in as a query string. A route's
  value is a response, or `(fn [wire-request] response)`. A request no route
  matches fails as `:rf.http/transport`, naming the method and url.

  `routes` may instead be one `(fn [wire-request] response)` that answers
  every request: a fake backend that routes for itself.

  `opts`:
  - `:delay-ms` — how long each answer waits, so a demo's loading states show.
    A response's own `:delay-ms` wins. Absent or zero, the answer is inline:
    a `dispatch-sync` that issues the request has its reply before it
    returns.

  The `wire-request` a fn receives is the attempt as the host would send it:
  `{:method :url :headers :body :timeout-ms}`, with the body encoded."
  ([routes] (route-transport routes {}))
  ([routes {:keys [delay-ms]}]
   (fn route-transport [wire callbacks]
     (let [route    (if (map? routes)
                      (get routes [(:method wire) (:url wire)] ::unmatched)
                      routes)
           response (cond
                      (= ::unmatched route)
                      {:failure {:kind    :rf.http/transport
                                 :message (str "no stub route for " (:method wire) " " (:url wire))}}

                      (fn? route) (route wire)
                      :else       route)
           delay-ms (or (:delay-ms response) delay-ms)]
       (if (and delay-ms (pos? delay-ms))
         (let [timer (rf.interop/set-timeout! #(answer! response callbacks) delay-ms)]
           (fn cancel [] (rf.interop/clear-timeout! timer)))
         (do (answer! response callbacks)
             nil))))))

(defn pending-transport
  "A transport that holds every request until the caller settles it. Returns
  `{:transport t :exchanges a}`: `t` is the transport, and `a` an atom of the
  exchanges it has received, in issue order, each

      {:request    wire-request
       :respond!   (fn [response])   ; answer it (a response map, as above)
       :fail!      (fn [failure])    ; fail it: {:kind :rf.http/transport} or
                                     ; {:kind :rf.http/timeout}
       :cancelled? (fn [])}          ; true once the request was aborted or
                                     ; timed out

  `respond!` and `fail!` return whether they settled the request. Once the
  request has been answered, aborted or timed out they deliver nothing and
  return false — so a test can answer an aborted request late and see the
  answer fenced."
  []
  (let [exchanges (atom [])]
    {:transport (fn pending-transport [wire callbacks]
                  (let [cancelled (atom false)]
                    (swap! exchanges conj
                           {:request    wire
                            :respond!   #(answer! % callbacks)
                            :fail!      #(answer! {:failure %} callbacks)
                            :cancelled? #(deref cancelled)})
                    (fn cancel [] (reset! cancelled true))))
     :exchanges exchanges}))

(defn with-transport
  "Send every `:rf.http/managed` request ISSUED during `thunk`'s dynamic
  extent through `transport`, and return `thunk`'s value. The test scope.

  The transport is captured when the request issues, so its retries and an
  answer that arrives after the scope has exited — a delayed route, a held
  exchange answered later — still go through it. An inner scope shadows an
  outer one, and a scope wins over a frame's installed transport."
  [transport thunk]
  (binding [rf.http.transport/*transport* transport]
    (thunk)))

(defn install-transport!
  "Send every `:rf.http/managed` request `frame` issues through `transport` —
  a demo's fake backend — until the frame is destroyed. `frame` is a frame id
  or a live frame value, which names the same frame. `nil` uninstalls it. A
  `with-transport` scope still wins for the requests issued inside it.
  Returns nil."
  [frame transport]
  (rf.http.registry/install-frame-transport! (rf.frame/frame-value->id frame) transport))
