(ns re-frame.freehand.host-hook-absence-jvm-test
  "rf2-1a9au — the compiled front end carries NO host-hook grammar, proved
  through the PRODUCTION resolution path.

  The analyzer once recognised nine host-hook heads — `local`, `effect`,
  `dispatch-fn`, the substrate `v/ref` and the five `re-frame.freehand.react`
  interop hooks — and lowered each to a `re-frame.freehand.hooks/*` runtime
  var that is defined nowhere. The arms are gone, and this suite pins WHY they
  could go: recognition ran off `env/resolves-to?`, and not one of those
  authoring vars is published, so no compiled declaration could ever reach an
  arm through the door.

  That last sentence is the whole point of the suite, and it is why nothing
  here injects a `:resolver`. A test resolver MANUFACTURES the missing
  definitions: it can make a removed arm look reachable and a shipped
  diagnostic look proven, which is exactly the mistake this Bead was reopened
  for. Every probe below runs the resolution the compiled door really runs.

  The suite is a TRIPWIRE as much as a proof. The day the host-hook slice
  publishes its authoring vars, `no-host-hook-authoring-var-is-published` goes
  red — and that is the signal that recognition, position law and lowering must
  land together, since a compiler that recognises a form it cannot lower is the
  state this Bead removed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.env :as env]))

(def ^:private host-hook-authoring-vars
  "The nine authoring heads the removed arms recognised, as the fully-qualified
  symbols an author's `v/local` / `react/use-effect` resolves to."
  '[re-frame.freehand/local
    re-frame.freehand/effect
    re-frame.freehand/dispatch-fn
    re-frame.freehand/ref
    re-frame.freehand.react/use-effect
    re-frame.freehand.react/use-layout-effect
    re-frame.freehand.react/use-effect-event
    re-frame.freehand.react/use-context
    re-frame.freehand.react/use-id])

(def ^:private host-hook-capabilities
  "The capability tokens a host-hook site would have contributed to a compiled
  manifest."
  #{:local :effect :dispatch-fn :ref :context})

(defn- production-env
  "The env the JVM structural door builds — `:clj` host, this namespace, no
  `:resolver`. `re-frame.freehand` is required above, so a resolution failure
  here is a MISSING VAR and not a missing namespace."
  []
  (env/make-env {:host   :clj
                 :ns-sym 're-frame.freehand.host-hook-absence-jvm-test
                 :self   'probe
                 :self-id :re-frame.freehand.host-hook-absence-jvm-test/probe}))

(defn- syms-in [form]
  (let [hits (atom #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! hits conj x)) x) form)
    @hits))

;; ---------------------------------------------------------------------------
;; The vars are unpublished, so the arms were unreachable
;; ---------------------------------------------------------------------------

(deftest no-host-hook-authoring-var-is-published
  (testing "Neither re-frame.freehand nor re-frame.freehand.react interns any of
            the nine host-hook authoring vars. This is the fact the removal
            rests on: an author writing (v/local 0) is writing an unresolvable
            symbol, not a form the compiled front end could see."
    (doseq [sym host-hook-authoring-vars]
      (is (nil? (try (ns-resolve 're-frame.freehand.host-hook-absence-jvm-test sym)
                     (catch Exception _ nil)))
          (str sym " is unpublished — when it becomes a real var, the host-hook "
               "slice has landed and its analyzer recognition, position law and "
               "lowering must land WITH it")))))

(deftest production-resolution-answers-nil-for-every-host-hook-head
  (testing "Through the resolution the compiled door actually performs —
            env/resolve-sym over a production env with no injected resolver —
            every host-hook head answers nil. An arm gated on
            env/resolves-to? therefore could not run, whatever body was
            compiled."
    (let [e (production-env)]
      (doseq [sym host-hook-authoring-vars]
        (is (nil? (env/resolve-sym e sym))
            (str "production resolution of " sym))))))

(deftest production-resolution-still-sees-the-vars-that-do-exist
  (testing "The control that makes the nils above mean something: the SAME env
            resolves the reactive authoring vars the analyzer really does
            recognise. A resolver that answered nil to everything would pass
            the test above while proving nothing."
    (let [e (production-env)]
      (is (= 're-frame.freehand/sub (:fqn (env/resolve-sym e 're-frame.freehand/sub))))
      (is (= 're-frame.freehand/slot (:fqn (env/resolve-sym e 're-frame.freehand/slot)))))))

;; ---------------------------------------------------------------------------
;; The door itself — what a host-hook body compiles to now
;; ---------------------------------------------------------------------------

(defn- compile-body
  "Lower `body` through the PRODUCTION compiled door — `compile-structural-view`
  with a nil `menv` (the JVM structural host) and no resolver, which is exactly
  what `v/defview {:compiled true}` calls. Returns its answer; the emitted
  `:body` is a FORM and is never evaluated here."
  [body]
  (compiler/compile-structural-view
   {:form            nil
    :menv            nil
    :ns-sym          're-frame.freehand.host-hook-absence-jvm-test
    :vname           'probe
    :view-id         :re-frame.freehand.host-hook-absence-jvm-test/probe
    :params          '[_]
    :body            body
    :children-policy :none}))

(deftest a-host-hook-body-compiles-to-no-phantom-hooks-symbol
  (testing "The regression this Bead names. A body spelling (v/local 0) once
            lowered to re-frame.freehand.hooks/local — a var defined nowhere —
            so every compiled view using a host hook carried an unresolvable
            symbol. Through the production door the head is now just an opaque
            call the analyzer passes through, and NOTHING in the emitted form
            names the phantom namespace."
    (let [{:keys [body]} (compile-body '[(let [[n set-n!] (re-frame.freehand/local 0)]
                                           [:div n])])
          syms           (syms-in body)]
      (is (not-any? #(= "re-frame.freehand.hooks" (namespace %)) syms)
          "no emitted symbol lives in the phantom hooks namespace")
      (is (contains? syms 're-frame.freehand/local)
          "the authored head survives verbatim — an ordinary unresolved symbol
           the host compiler reports, not a compiler-invented one"))))

(deftest a-host-hook-body-claims-no-host-hook-capability
  (testing "The manifest half. With the site buckets gone there is no way for a
            compiled declaration to claim `:local` / `:effect` / `:dispatch-fn` /
            `:ref` / `:context`, so a manifest cannot advertise a capability the
            substrate has no runtime for."
    (let [{:keys [manifest]} (compile-body '[(let [[n set-n!] (re-frame.freehand/local 0)]
                                               [:div n])])]
      (is (empty? (filter host-hook-capabilities (:capabilities manifest)))
          "the manifest claims no host-hook capability")
      (is (empty? (filter host-hook-capabilities (:caps (:static-facts manifest))))
          "and neither do its render-static facts")
      (is (= :elided (:view-cell manifest))
          "a body whose only 'hook' is an opaque call still proves the ViewCell
           elidable — the reactive four are what that verdict turns on"))))

(deftest an-effect-statement-is-an-ordinary-multi-form-body-refusal
  (testing "The `(effect …)` STATEMENT prefix went with the arms: a defview body
            is exactly one template again. A body with a leading (effect …) is
            refused as the two-form body it is — one grammar rule, not a
            host-hook rule the compiler cannot back."
    (let [ex (try (compile-body '[(re-frame.freehand/effect [] (fn [] nil))
                                  [:div]])
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a two-form body is refused")
      (is (= :rf.ui.compile/multi-form-body
             (:rf.ui.compile/error (ex-data ex)))
          "under the ordinary single-template-body diagnostic"))))
