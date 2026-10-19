(ns re-frame.story.network
  "Realize a compiled variant's authored `:network` fixture on the LIVE run
  paths (spec/017 §The network surface).

  A variant's `:network` is a route map, `{[method url] response}`, which the
  plan compiler keeps at `[:world :network]`. The runtime installs it on the
  variant's frame as a stub transport
  (`re-frame.http.stub-transport/route-transport`) BEFORE the frame is
  allocated, so the routes answer from the first `:frame-setup` `:init`,
  loader, `:setup` or script effect onward. A stub transport replaces only
  the network hop: the variant's requests run through the real
  `:rf.http/managed` — its `:decode`, `:accept`, retry and privacy scrub — and
  only the answer comes from the route map.

  The transport is installed per frame, so two mounted variants each answer
  from their own routes, and destroying the frame drops it. A variant with no
  `:network` installs nothing: its `:preset :story` frame sets
  `:rf.http/stub-only?`, so a managed request it issues fails as
  `:rf.http/transport` rather than reaching the network."
  ;; CLJS requires the stub transports directly. On the JVM they are resolved
  ;; LAZILY at call time, so requiring this ns from the `re-frame.story`
  ;; MACRO-ns does not drag the http artefact's JVM-only transitive deps onto
  ;; the macro classpath. Same boundary `rf.story.artifact` uses.
  #?(:cljs (:require [re-frame.http.stub-transport :as rf.http.stub-transport])))

(defn- stub-transport-fn
  "The `re-frame.http.stub-transport` fn `sym` names, or nil when the http
  artefact is not loaded and `require?` is false."
  [sym require?]
  #?(:clj  (let [qualified (symbol "re-frame.http.stub-transport" (name sym))]
             (if require?
               (requiring-resolve qualified)
               (some-> (find-ns 're-frame.http.stub-transport) (ns-resolve sym))))
     :cljs (case sym
             install-transport! rf.http.stub-transport/install-transport!
             route-transport    rf.http.stub-transport/route-transport)))

(defn install-for-frame!
  "Answer `frame-id`'s managed requests from `routes`, the variant's
  `:network` route map, through a route transport installed on the frame.

  Called by the runtime BEFORE the frame is allocated — so before any
  `:frame-setup` `:init`, loader, `:setup` or script effect can issue a
  managed request. Re-running the same variant re-installs the same routes.

  An empty/absent `routes` uninstalls any transport the frame held, so a
  variant recompiled without `:network` stops answering from a stale fixture.
  Returns `frame-id`."
  [frame-id routes]
  (if (seq routes)
    ((stub-transport-fn 'install-transport! true)
     frame-id ((stub-transport-fn 'route-transport true) routes))
    (when-let [install (stub-transport-fn 'install-transport! false)]
      (install frame-id nil)))
  frame-id)
