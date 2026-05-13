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

  Pre-rf2-h824v the seven `core_<artefact>.cljc` files carried ~740 LoC
  of structurally-identical late-bind boilerplate — 26 `ex-info`
  literals each spelling the same skeleton. This namespace replaces
  the boilerplate with a single `defwrapper` macro driven by a
  declarative per-row spec, paired with the `late-bind/require-fn!`
  helper (rf2-uchhp) that centralises the throw shape.

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
  (:require [re-frame.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

#?(:clj
   (defn- in-scope-ex-data
     "Filter `ex-data` to entries whose value-symbol appears in the
     arity's `args`. Lets a single spec-level `:ex-data` map scope
     itself correctly across multi-arity wrappers where shorter arities
     bind fewer locals (e.g. `active-head []` vs `active-head [frame-id]`)."
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

     When `:where` is omitted from the spec, it defaults to
     `'rf/<name>` — the common case. Wrappers whose public-facing
     symbol differs from the defn name (e.g. `-reg-error-projector` is
     surfaced as `rf/reg-error-projector`) supply `:where` explicitly."
     [name-sym docstring-or-attrs spec & arity-forms]
     (let [attrs (cond
                   (map? docstring-or-attrs)    docstring-or-attrs
                   (string? docstring-or-attrs) {:doc docstring-or-attrs}
                   :else (throw (ex-info "defwrapper: expected docstring or attr-map"
                                         {:got docstring-or-attrs})))
           attrs (cond-> attrs
                   (:arglists spec) (assoc :arglists (:arglists spec)))
           spec    (update spec :where #(or % (list 'quote (symbol "rf" (str name-sym)))))
           arities (map #(build-arity name-sym spec %) arity-forms)]
       `(defn ~name-sym
          ~attrs
          ~@arities))))
