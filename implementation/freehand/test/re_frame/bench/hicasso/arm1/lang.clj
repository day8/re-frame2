(ns re-frame.bench.hicasso.arm1.lang
  "`defview` and `hfn` — the two macros Arm 1 has (rf2-2rtt6.9,
  rf2-2rtt6.35).

  It exists because the authoring surface is a *deliverable*: HD-002 has
  the dogfood screen written in three renderings and judged on diff and
  preference, and a judgement taken against `(def todo-row (mint-view!
  \"todo-row\" (fn [props] …)))` would be judging the absence of a macro
  rather than the design.

  ## It is not a compiler, and the distinction is load-bearing

  The charter's hard fence is a compiler or an analyzer. This expands to
  a `def` and a `fn`: it reads no body form, rewrites no hiccup, resolves
  no subscription, plans no holes, and emits no code that depends on what
  the body contains. Everything the body does — hiccup interpretation,
  subscription reads, intent lowering — happens at runtime in
  `re-frame.bench.hicasso.arm1.runtime`, on forms the macro never
  inspected. The only thing it captures at expansion time is the view's
  *name*, for `displayName`.

      (defview todo-row [{:keys [id]}] …)
      ;; =>
      (def todo-row
        (runtime/mint-view! \"my.ns/todo-row\" (fn todo-row-body [{:keys [id]}] …)))

  ## Why it is a `.clj` and not a `.cljc`

  Macros only. The runtime it names is CLJS-only (it requires React), and
  a `.cljc` would invite a JVM-side implementation this arm does not have
  — HD-020(d) puts SSR out of v0 explicitly.")

(defmacro defview
  "Mint a boundary — a real React function component (HD-016).

  `argv` is the ordinary one-props-map argument vector, so destructuring
  reads as it does in any Clojure fn. In hiccup the resulting var is a
  legal head: `[todo-row {:key id :id id}]`. A plain function in head
  position is a loud error rather than a silent embedding, which is what
  makes the head's identity stable by construction and leaves the codec's
  stable-component-head cache with nothing to do."
  [sym & more]
  (let [doc       (when (string? (first more)) (first more))
        [argv & body] (if doc (rest more) more)
        view-name (str (ns-name *ns*) "/" sym)
        body-name (symbol (str sym "-body"))]
    `(def ~(if doc (vary-meta sym assoc :doc doc) sym)
       (re-frame.bench.hicasso.arm1.runtime/mint-view!
         ~view-name
         (fn ~body-name ~argv ~@body)))))

(defmacro hfn
  "**The one callback form** (HD-024). `h/fn` in the authoring surface;
  spelled `hfn` here because `h/fn` is qualified in the product and a
  bare `fn` would shadow `cljs.core/fn` on a `:refer`.

  Expands to nothing but a marked `fn`:

      (hfn [e] (js/Array.from (.. e -target -files)) …)
      ;; =>
      (re-frame.bench.hicasso.front.intent/callback (fn [e] …))

  The value is an ORDINARY FUNCTION — that is the point of the ruling.
  The contract comes from the position it is written at, so there is
  nothing to choose between and nothing that can fail to be callable
  where Hicasso does not walk. See
  [[re-frame.bench.hicasso.front.intent]] for the position table."
  [argv & body]
  `(re-frame.bench.hicasso.front.intent/callback (fn ~argv ~@body)))
