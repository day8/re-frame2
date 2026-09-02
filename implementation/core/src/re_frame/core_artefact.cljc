(ns re-frame.core-artefact
  "Factory for the optional-artefact wrapper convention (rf2-h824v).

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention):
  each per-feature carve-out (`flows`, `routing`, `schemas`, `machines`,
  `ssr`, `epoch`, `http`) ships its public-API in a sibling
  `re-frame.core-<artefact>` namespace whose fns look the producing impl
  up through the late-bind hook registry at call time. Apps that omit
  the artefact see either a silent safe-default (nil / [] / false) or
  a structured `:rf.error/<artefact>-artefact-missing` ex-info,
  depending on the surface's contract.

  The seven `core_<artefact>.cljc` files share their late-bind shape —
  per-row declarative spec, structured throw, optional safe-default —
  through a single `defwrapper` macro defined here, paired with the
  `late-bind/require-fn!` helper (rf2-uchhp) that centralises the throw
  skeleton. One macro + one helper replaces what would otherwise be ~26
  `ex-info` literals copied across the artefact wrappers.

  ## `defwrapper` shape

  Each wrapper is one `defwrapper` form: a name, a docstring (or
  metadata map carrying `:doc` / `:arglists`), a spec map, and a series
  of arity bodies:

  ```
  (defwrapper clear-flow
    \"Per Spec 013 §Lifecycle: clear a flow from a frame's registry.
    Late-bound via :flows/clear-flow.\"
    {:hook      :flows/clear-flow
     :where     'rf/clear-flow
     :artefact  flows-artefact
     :on-absent :throw
     :ex-data   {:flow-id id}}
    ([id]      [id {}])     ;; shorter arity — recurse with these args
    ([id opts] :delegate))  ;; primary arity — call the hook fn
  ```

  ### Spec map keys

  | Key          | Required? | Meaning                                                                                  |
  |--------------|-----------|------------------------------------------------------------------------------------------|
  | `:hook`      | yes       | late-bind hook key (e.g. `:flows/clear-flow`)                                            |
  | `:where`     | no        | quoted user-facing fn symbol (e.g. `'rf/clear-flow`) stamped on the missing-artefact throw. Defaults to `'rf/<name>` (the common case) |
  | `:artefact`  | yes       | symbol resolving to an `artefact-info` map (see below) — typically a `def` in the same ns |
  | `:on-absent` | yes       | absent-fn policy — `:throw` / `:nil` / `:false` / `:empty-vec` / `:empty-map`, or any literal value |
  | `:ex-data`   | no        | extra ex-data slots merged onto the throw map (only meaningful when `:on-absent :throw`). Symbol values resolve in the arity's local scope and are dropped from shorter arities that don't bind the symbol |
  | `:arglists`  | no        | passed through to the public fn's `:arglists` metadata (for variadic forms)              |

  `:ex-data` is a map literal whose values are symbols that resolve in
  the arity's local scope (the arglist's bindings). Example:
  `{:flow-id id, :path path}`.

  ### Arity body kinds

  | Body               | Emits                                                            |
  |--------------------|------------------------------------------------------------------|
  | `:delegate`        | `(if-let [f (late-bind/get-fn :hook)] (f a b ...) <on-absent>)` for `:nil`/`:false`/`:empty-vec`; via `late-bind/require-fn!` for `:throw` |
  | `:apply`           | same as `:delegate` but `(apply f args)` — for variadic arglists |
  | `[expr expr ...]`  | recursion form — emits `(<name> expr expr ...)`                  |

  ### A hook value MUST be a FUNCTION, never a COMPONENT

  Both direct body kinds CALL the hook value — `:delegate` emits `(f a b)`,
  `:apply` emits `(apply f args)` — so whatever the producing namespace
  publishes is invoked as an ordinary function. That is correct for every
  hook in the table, and it is a real constraint rather than a stylistic one:
  a component that is CALLED never becomes a component. On Reagent it runs
  inside its CALLER's component instance, so it reads that instance's React
  context rather than its own, and any `:contextType` its head carries is
  inert.

  Nothing enforces this and nothing can warn about it. The failure is silent
  at registration, silent at call, and surfaces only when something
  downstream needs the context — in a real browser, since a unit suite that
  calls the render fn under `rf/with-frame` is answered by the dynamic-var
  tier and stays green.

  **The worked instance is rf2-nvcp.** `rf/route-link` is a `defwrapper` over
  `:routing/route-link`, and routing published the registered `:route/link`
  view head straight into it. The head was called rather than mounted, so
  `(.-context cmp)` answered React's empty default and the render-time
  `require-current-frame!` raised `:rf.error/no-frame-context` on FIRST
  render — every routed application blank, for about two and a half months,
  behind a fully green suite.

  The repair belongs at the PUBLICATION side, not here: publish a function
  that EMITS AN ELEMENT (`(fn [& args] (into [(views/view-head :route/link)]
  args))`) and the substrate componentizes the head exactly as it does a
  `reg-view` view. `defwrapper` is deliberately NOT taught a second calling
  convention for this — the `:apply` / `:delegate` arities are correct for
  functions, which is what every hook value is, and a component-aware body
  kind would fork a hot path to accommodate one case already fixed upstream
  of it.

  ### `artefact-info` map shape

  ```
  (def flows-artefact
    {:error-keyword :rf.error/flows-artefact-missing
     :maven         \"day8/re-frame2-flows\"
     :require-ns    \"re-frame.flows\"})
  ```

  These three slots feed the reason-string template used by
  `late-bind/require-fn!`:

      \"<where-sym> requires <maven> on the classpath; add it to deps and
       require <require-ns> at app boot.\"

  Convention: each `core_<artefact>.cljc` declares a private
  `<artefact>-artefact` at the top of the file and threads it through
  every `defwrapper` spec."
  (:require [re-frame.error :as error]
            [re-frame.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn- in-scope-ex-data
     "Filter `ex-data` to entries whose value-symbol appears in the
     arity's `args`. Lets a single spec-level `:ex-data` map scope
     itself correctly across multi-arity wrappers where shorter arities
     bind fewer locals (e.g. `render-head [head-id]` vs
     `render-head [head-id opts]`)."
     [ex-data args]
     (let [arg-set (set (remove #{'&} args))]
       (into {}
             (filter (fn [[_ v]]
                       (or (not (symbol? v))
                           (contains? arg-set v))))
             ex-data))))

#?(:clj
   (defn- build-body
     "Emit the body form for one arity. `args` is the parsed arglist
     (vector of symbols, possibly with `&`); `body-kind` is the user's
     literal — `:delegate`, `:apply`, or a vector recursion-args form.

     `on-absent` is the value the wrapper returns when the late-bind
     hook is unregistered. Accepts a few sugar keywords (`:throw` /
     `:nil` / `:false` / `:empty-vec` / `:empty-map`) or any literal
     value (e.g. `0`, `:rf/default`). `:throw` routes through
     `late-bind/require-fn!` with the structured missing-artefact
     ex-info shape."
     [name-sym {:keys [hook where artefact on-absent ex-data]} args body-kind]
     (let [call-args (vec (remove #{'&} args))
           absent    (case on-absent
                       :throw     nil    ;; require-fn! throws — no else-branch
                       :nil       nil
                       :false     false
                       :empty-vec []
                       :empty-map {}
                       on-absent)]
       (cond
         ;; Recursion: a literal vector of args to pass to the public surface.
         (vector? body-kind)
         `(~name-sym ~@body-kind)

         ;; Direct hook call — throw on absent via require-fn!.
         (= :throw on-absent)
         (let [extra (not-empty (in-scope-ex-data ex-data args))]
           (if (= :apply body-kind)
             `(apply (re-frame.late-bind/require-fn! ~hook ~where ~artefact ~extra)
                     ~(last args))
             `((re-frame.late-bind/require-fn! ~hook ~where ~artefact ~extra)
               ~@call-args)))

         ;; Direct hook call — silent absent-default.
         :else
         (if (= :apply body-kind)
           `(if-let [f# (re-frame.late-bind/get-fn ~hook)]
              (apply f# ~(last args))
              ~absent)
           `(if-let [f# (re-frame.late-bind/get-fn ~hook)]
              (f# ~@call-args)
              ~absent))))))

#?(:clj
   (defn- build-arity
     [name-sym spec [arglist body-kind]]
     (list arglist (build-body name-sym spec arglist body-kind))))

#?(:clj
   (defmacro defwrapper
     "See ns docstring. Emits a `defn` whose body delegates to the
     late-bind hook table per the declarative spec.

     Shape: `(defwrapper name docstring-or-attr-map spec & arity-forms)`.
     Each arity-form is `(arglist body-kind)` where body-kind is
     `:delegate`, `:apply`, or `[recursion-args ...]`.

     The hook value MUST be a FUNCTION. Both `:delegate` and `:apply` CALL
     it, and a component that is called never becomes a component — it
     renders inside its caller's instance and reads the caller's React
     context, leaving any `:contextType` inert. A component-valued hook
     therefore publishes an element-EMITTING fn instead. See the ns
     docstring §A hook value MUST be a FUNCTION, never a COMPONENT; rf2-nvcp
     is the worked instance.

     When `:where` is omitted from the spec, it defaults to
     `'rf/<name>` — the common case. Wrappers whose public-facing
     symbol differs from the defn name (e.g. `-reg-error-projector` is
     surfaced as `rf/reg-error-projector`) supply `:where` explicitly."
     [name-sym docstring-or-attrs spec & arity-forms]
     (let [attrs (cond
                   (map? docstring-or-attrs)    docstring-or-attrs
                   (string? docstring-or-attrs) {:doc docstring-or-attrs}
                   :else (error/throw-error!
                           :rf.error/defwrapper-bad-args
                           'defwrapper
                           "defwrapper's second argument must be a docstring or an attr-map"
                           {:recovery :fix-registration
                            :extra    {:got docstring-or-attrs}}))
           attrs (cond-> attrs
                   (:arglists spec) (assoc :arglists (:arglists spec)))
           spec    (update spec :where #(or % (list 'quote (symbol "rf" (str name-sym)))))
           arities (map #(build-arity name-sym spec %) arity-forms)]
       `(defn ~name-sym
          ~attrs
          ~@arities))))
