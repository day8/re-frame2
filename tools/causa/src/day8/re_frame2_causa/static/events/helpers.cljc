(ns day8.re-frame2-causa.static.events.helpers
  "Pure helpers for the Static Events sub-tab (rf2-o5f5f.6).

  Split out into a CLJC ns so the JVM unit-test target can drive the
  data shape (`project-rows`, payload parsing, hermetic simulate) without
  a CLJS runtime.")

(defn sanitize-row-id
  "Build a stable DOM `data-testid` suffix from an event-id keyword /
  symbol / string. Keywords drop the leading `:` so the testid reads as
  `rf-causa-static-events-row-user/login` rather than
  `rf-causa-static-events-row-:user/login`. Non-keyword ids fall back
  to `(str id)` so unusual registrations remain addressable."
  [id]
  (let [s (pr-str id)]
    (cond
      (and (> (count s) 0) (= \: (first s)))
      (subs s 1)

      :else
      s)))

(defn parse-payload-edn
  "Parse the user-typed EDN payload string into a sequence of args that
  follow the event-id in the dispatched vector. Tolerates the common
  shapes:

    - empty / whitespace-only  → `[]` (no extra args)
    - `\"{:x 1}\"`             → `[{:x 1}]` (single map arg)
    - `\"42\"`                 → `[42]`
    - `\":bar\"`               → `[:bar]`
    - `\"{:x 1} :tag\"`        → `[{:x 1} :tag]`
    - any parse failure        → `{:parse-error <reason>}`

  `read-string-fn` is the EDN reader entry-point — CLJS callers pass
  `cljs.reader/read-string`; JVM tests pass `clojure.edn/read-string`.
  Both carry the same `read 1 form` semantics, so we wrap repeated
  reads against a pushback / index-tracking reader. To keep the helper
  trivially testable on the JVM we instead `read-string` a SEQUENCE
  by wrapping the input in `[ ... ]` parens — every well-shaped EDN
  series the user types is a vector when bracketed."
  [s read-string-fn]
  (try
    (let [trimmed (when s (clojure.string/trim s))]
      (if (or (nil? trimmed) (= "" trimmed))
        []
        (let [bracketed (str "[" trimmed "]")
              parsed    (read-string-fn bracketed)]
          (if (sequential? parsed)
            (vec parsed)
            ;; Theoretically unreachable — bracketing always yields a
            ;; vector — but return the parse-error shape so the caller
            ;; can render a consistent failure.
            {:parse-error (str "unexpected parse result: " (pr-str parsed))}))))
    (catch #?(:clj Exception :cljs :default) e
      {:parse-error (#?(:clj .getMessage :cljs ex-message) e)})))

(defn build-event-vector
  "Compose the dispatched event vector from a parsed payload. Per the
  re-frame contract, the dispatched event is `[event-id & args]`."
  [event-id parsed-args]
  (into [event-id] parsed-args))

(defn run-simulate
  "Hermetic single-step simulate. Invokes the registered handler-fn
  directly against a synthetic input — NO real dispatch, NO interceptor
  walk, NO app-db swap.

  Args:
    - `registrations`   `{event-id meta}` map from the `:event` registry
    - `event-id`        keyword id of the row being simulated
    - `payload-edn`     user-typed payload string (or nil)
    - `read-string-fn`  the EDN reader entry-point (see
                        `parse-payload-edn`)

  Returns a result map:

    - `{:ok? true  :kind <db|fx|ctx> :value <handler-return>}`
    - `{:ok? false :reason <human-readable string>}`

  Per `events.cljc` the `:invoke` signature is kind-specific:

    - `:db`  handler-fn → `(fn [db   event-vec])`
    - `:fx`  handler-fn → `(fn [cofx event-vec])` — cofx is a map
                          carrying at least `:db` and `:event`
    - `:ctx` handler-fn → `(fn [ctx])` where ctx is an interceptor
                          context

  We construct a deliberately minimal synthetic input for each kind so
  the simulate stays hermetic. Handlers that reach for cofx slots not
  in the synthetic shape will surface an exception, which we trap and
  render as a friendly failure."
  [registrations event-id payload-edn read-string-fn]
  (let [meta (get registrations event-id)]
    (cond
      (nil? meta)
      {:ok? false
       :reason (str "No registration found for event-id "
                    (pr-str event-id) ".")}

      :else
      (let [parsed (parse-payload-edn payload-edn read-string-fn)]
        (if (map? parsed)
          {:ok? false
           :reason (str "Could not parse payload as EDN: "
                        (:parse-error parsed))}
          (let [kind       (:event/kind meta)
                handler-fn (:handler-fn meta)
                event-vec  (build-event-vector event-id parsed)]
            (cond
              (nil? handler-fn)
              {:ok? false
               :reason (str "Registration for " (pr-str event-id)
                            " has no :handler-fn — cannot simulate.")}

              :else
              (try
                (let [value (case kind
                              :db  (handler-fn {} event-vec)
                              :fx  (handler-fn {:db {} :event event-vec}
                                               event-vec)
                              :ctx (handler-fn {:coeffects {:db {} :event event-vec}
                                                :effects   {}})
                              ;; Unknown kind — best-effort try as :db.
                              (handler-fn {} event-vec))]
                  {:ok?   true
                   :kind  kind
                   :value value})
                (catch #?(:clj Exception :cljs :default) e
                  {:ok? false
                   :reason (str "Handler threw: "
                                #?(:clj (.getMessage e)
                                   :cljs (ex-message e)))})))))))))
