(ns re-frame.late-bind
  "Late-binding hook registry for cross-namespace forward references.

  Producing namespaces register a callable via `set-fn!` at load time;
  consumers look it up via `get-fn` at call time. The mechanism carries
  two kinds of forward reference:

  - Leaf-to-higher-level (avoids cyclic `:require`s) — e.g. `re-frame.frame`
    calling into `re-frame.router`.
  - Cross-artefact (avoids hard deps on optional Maven artefacts) — e.g.
    `re-frame.core` calling into `re-frame.schemas` only when the
    `day8/re-frame2-schemas` artefact is on the classpath; absent that,
    `get-fn` returns nil and consumers either no-op or throw a structured
    `:rf.error/<artefact>-artefact-missing` (see `require-fn!`).

  The authoritative inventory of published keys lives in
  `re-frame.late-bind.directory`; the drift test
  `late_bind_drift_test.clj` asserts the directory and the `set-fn!`
  call sites stay in sync.")

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "Map of hook-key → fn. Populated by producing namespaces at
   load time. Authoritative key inventory: `re-frame.late-bind.directory/hooks`."}
  hooks
  (atom {}))

(defn set-fn!
  "Register a fn under hook-key. The producing namespace calls this at
  the bottom of its file; consumers look it up via `get-fn`."
  [hook-key f]
  (swap! hooks assoc hook-key f)
  nil)

(defn get-fn
  "Return the fn registered under hook-key, or nil if no producer has
  registered it yet. Callers MUST handle the nil case (the common
  pattern is `(when-let [f (late-bind/get-fn ...)] (f args))`)."
  [hook-key]
  (get @hooks hook-key))

(defn require-fn!
  "Return the fn registered under `hook-key`, or throw a structured
  `:rf.error/<artefact>-artefact-missing` ex-info when the hook is
  unregistered.

  The throw shape is locked by the missing-artefact contract used by
  every `re-frame.core-<artefact>` wrapper:

    Message:  `<error-keyword>` printed as a string (e.g.
              \":rf.error/flows-artefact-missing\").
    ex-data:  {:where    <where-sym>            ;; user-facing fn symbol
               :recovery :no-recovery
               :reason   \"<where-sym> requires <maven> on the classpath;
                          add it to deps and require <require-ns> at app boot.\"
               & extra}                          ;; per-call slots

  Args:
    hook-key      — the late-bind hook key (e.g. `:flows/reg-flow`).
    where-sym     — the user-facing fn symbol stamped on the error
                    so greping for the symbol finds the call site.
    artefact-info — `{:error-keyword … :maven … :require-ns …}`.
    extra-data    — (optional) per-call ex-data slots."
  ([hook-key where-sym artefact-info]
   (require-fn! hook-key where-sym artefact-info nil))
  ([hook-key where-sym {:keys [error-keyword maven require-ns]} extra-data]
   (or (get @hooks hook-key)
       (throw (ex-info (str error-keyword)
                       (merge {:where    where-sym
                               :recovery :no-recovery
                               :reason   (str where-sym " requires " maven
                                              " on the classpath; add it to deps and require "
                                              require-ns " at app boot.")}
                              extra-data))))))
