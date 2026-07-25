(ns re-frame.freehand.custom-element-grammar-jvm-test
  "`:class` and `:style` are ATTRIBUTES — a `v/custom-element` declaration may
  not classify them as JS properties (`:rf.ui.compile/bad-custom-element`).

  WHY THIS IS A REFUSAL AND NOT A CURIOSITY. A property-classified name is
  omitted from server markup by contract — the serialiser reads the node's
  `:rf.ui/property-props` set and drops exactly those names, because a server
  cannot run a property setter and the client applies them at hydration. So
  `(v/custom-element :x {:properties #{:class :style}})` was ACCEPTED and then
  rendered `<x ok=\"a\">`: the element's class and style silently absent from
  the HTML, while the structural fold — which reads the same declaration but
  serialises nothing — still carried them. One declaration, two answers, wrong
  output, and no diagnostic anywhere, because both answers are structurally
  well-formed (rf2-oazgv).

  THE RULING (rf2-oazgv, delegated authority 2026-07-25) is to refuse the
  declaration rather than to coerce it: `:class` and `:style` join the v1
  refusal roster and the compiler rejects them at DECLARATION time with a
  diagnostic naming the attribute-versus-property distinction. Auto-coercing
  them into attributes would be the same class of sin as dropping them —
  silently doing what the author probably meant. Two names is the whole
  remedy: this is not a general attribute-versus-property taxonomy, and every
  other kebab name on a custom element remains a legitimate property the
  declaration is the sole classifier for.

  BOTH DOORS ARE TESTED, because the macro is not the only reader of a
  declaration. `re-frame.freehand.compiler.harvest` re-recognises the literal
  form SYNTACTICALLY at `:compile-prepare` so a view above its declaration
  still classifies correctly, and it promises to recognise exactly what the
  macro accepts. A refusal the harvest did not mirror would seed the very
  classification the macro is about to reject.

  JVM-only because macroexpansion is: the CLJS lowering paths of an ADMITTED
  declaration are `custom-element-cljs-test` / `-dom-cljs-test` /
  `-ssr-jvm-test` (FH-STRUCT-011), and the acceptance rows below exist to
  prove this refusal did not narrow them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.harvest :as harvest]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — the REAL macro body, off any Shadow pass (the plain-JVM door)
;; ---------------------------------------------------------------------------

(defn- declare-element!
  "Run the real `v/custom-element` macro body for `tag`/`properties`. `nil`
  when admitted; the thrown `ExceptionInfo` when refused."
  [tag properties]
  (binding [*ns* (create-ns 'grammar.probe.ns)]
    (try
      (compiler/custom-element*
       (with-meta (list 'custom-element tag {:properties properties}) {:line 1})
       {} tag {:properties properties})
      nil
      (catch clojure.lang.ExceptionInfo ex ex))))

(defn- refusal
  "The `:rf.ui.compile/bad-custom-element` evidence of a refused declaration —
  `nil` when the declaration was ADMITTED, and nil rather than a half-answer
  when it was refused under some OTHER id, so a row cannot pass by throwing
  for an unrelated reason."
  [ex]
  (when ex
    (let [d (ex-data ex)]
      (when (= :rf.ui.compile/bad-custom-element (:rf.ui.compile/error d))
        {:message (ex-message ex)
         :tag (:tag d)
         :refused (:refused d)}))))

(defn- refuses
  "Declare `tag` with `properties`, asserting it is REFUSED as a bad
  declaration, that the diagnostic names the attribute-versus-property
  distinction and every offending name, and that NOTHING was seeded — a
  refusal that had already written would leave the build classifying props
  against a manifest nobody authored. Returns the evidence map."
  [tag properties expected-refused]
  (let [ev (refusal (declare-element! tag properties))]
    (is (some? ev)
        (str tag " " (pr-str properties)
             " must be refused as :rf.ui.compile/bad-custom-element"))
    (when ev
      (is (= expected-refused (:refused ev))
          "the evidence names every refused property, in a pinned order")
      (is (= tag (:tag ev)) "the evidence names the declared tag")
      (let [msg (:message ev)
            lower (str/lower-case msg)]
        (is (str/includes? lower "attribute")
            (str "the diagnostic names the ATTRIBUTE half of the distinction: " msg))
        (is (str/includes? lower "propert")
            (str "the diagnostic names the PROPERTY half of the distinction: " msg))
        (doseq [k expected-refused]
          (is (str/includes? msg (str k))
              (str "the diagnostic spells the offending name " k ": " msg)))))
    (is (= #{} (build/element-properties tag))
        (str tag " must not be seeded by a refused declaration"))
    ev))

;; ---------------------------------------------------------------------------
;; The refusal
;; ---------------------------------------------------------------------------

(deftest class-declared-as-a-property-is-refused
  (testing ":class is an attribute that composes with the `.class#id` tag
            sugar. Classified as a property it is dropped from markup, so
            the declaration is refused at the declaration."
    (refuses :ce-grammar-class #{:class} [:class])))

(deftest style-declared-as-a-property-is-refused
  (testing ":style is an attribute carrying the CSS grammar. Classified as a
            property it is dropped from markup, so the declaration is refused
            at the declaration."
    (refuses :ce-grammar-style #{:style} [:style])))

(deftest both-refused-names-are-reported-together
  (testing "a declaration naming both is ONE refusal naming both — an author
            fixing a diagnostic that reported only the first would be told
            about the second on the next build"
    (refuses :ce-grammar-both #{:class :style} [:class :style])))

(deftest a-refused-name-beside-legitimate-properties-refuses-the-WHOLE-declaration
  (testing "the offending name is not quietly dropped from an otherwise fine
            set: partial admission would leave the element carrying the
            property lowering for its real properties and no record that the
            author asked for something impossible"
    (let [ev (refuses :ce-grammar-mixed #{:help-text :scale :class} [:class])]
      (when ev
        (is (not (str/includes? (:message ev) ":help-text"))
            "and the diagnostic accuses only the refused name")))))

;; ---------------------------------------------------------------------------
;; The refusal did not narrow the grammar — a legitimate declaration is
;; still admitted, and still classifies
;; ---------------------------------------------------------------------------

(deftest a-legitimate-properties-set-is-still-admitted-and-still-classifies
  (testing "the roster is two names, not a policy about kebab props: an
            ordinary declaration is admitted and its compile-path
            classification (`build/element-properties`, what the template
            analyzer reads) is unchanged"
    (is (nil? (declare-element! :ce-grammar-ok #{:accent-color :help-text :scale}))
        "an ordinary declaration is admitted")
    (is (= #{:accent-color :help-text :scale}
           (build/element-properties :ce-grammar-ok))
        "and the compile-path read carries its declared properties")))

(deftest a-property-name-that-merely-LOOKS-attribute-ish-is-still-admitted
  (testing "the declaration remains the SOLE classifier for every other name.
            `:tab-index` is a standard HTML attribute spelling and
            `:class-name` shares a prefix with the refused `:class`; both are
            legitimate properties of an element whose author says so, and
            refusing them would be the general taxonomy this ruling excludes."
    (is (nil? (declare-element! :ce-grammar-attrish #{:tab-index :class-name :style-map}))
        "an attribute-ish or class/style-prefixed name is not the refused name")
    (is (= #{:tab-index :class-name :style-map}
           (build/element-properties :ce-grammar-attrish))
        "and it classifies as the property it was declared to be")))

;; ---------------------------------------------------------------------------
;; The harvest mirrors the refusal
;; ---------------------------------------------------------------------------

(def ^:private refused-source
  "A well-formed source whose ONLY defect is the refused property name — so a
  harvest that recognised it would be seeding a declaration the macro is
  about to reject, and a view expanded before the declaration form would bake
  the property lowering for `:class`."
  (str "(ns probe.refused (:require [re-frame.freehand :as v]))\n"
       "(v/custom-element :ce-harvest-refused {:properties #{:class :style}})\n"))

(def ^:private admitted-source
  "The positive control: the SAME shape with a legitimate property name. Its
  recognition is what proves the row above failed on the refused name rather
  than on the harness."
  (str "(ns probe.admitted (:require [re-frame.freehand :as v]))\n"
       "(v/custom-element :ce-harvest-ok {:properties #{:help-text}})\n"))

(deftest the-syntactic-harvest-does-not-seed-a-refused-declaration
  (testing "`harvest/declarations-in-source` promises to recognise exactly
            what the macro accepts — the macro stays the authority and
            reports the error with the declaration's own coordinates"
    (is (= [] (harvest/declarations-in-source refused-source))
        "a declaration the macro refuses is not harvested")
    (is (= [{:tag :ce-harvest-ok :properties #{:help-text}}]
           (harvest/declarations-in-source admitted-source))
        "and the recogniser still reads an ordinary declaration")))
