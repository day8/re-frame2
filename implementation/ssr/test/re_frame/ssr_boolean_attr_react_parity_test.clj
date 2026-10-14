(ns re-frame.ssr-boolean-attr-react-parity-test
  "The SSR boolean attribute-value rosters pinned against react-dom ITSELF.

  The hiccup emitter and the structural-tree serialiser share one classifier
  (`html-helpers/boolean-attr-class`), so a parity test between them cannot
  validate it: a wrong roster makes both wrong the same way. The evidence is
  react-dom's own markup, recorded by `react_dom_probe/boolean_attr_classes.cjs`
  into `react_dom_probe/boolean_attr_classes.edn` (committed and regenerated
  by hand; the `.edn` header carries the command), with candidate names taken
  from react-dom's `possibleStandardNames` rather than from our roster.

  Each attribute is probed with `true`, `false` and four non-boolean values:
  `:presence` and `:overloaded` render identically for a boolean and part
  only on a non-boolean value, and JavaScript truthiness disagrees with
  Clojure's about `\"\"` and `0`. The class is derived from React's bytes,
  compared with the roster in both directions, and every row is driven
  through both public emitters. `value` and `ismap` are the documented
  divergences."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

;; ---------------------------------------------------------------------------
;; The external evidence.
;; ---------------------------------------------------------------------------

(def ^:private fixture-resource
  "react_dom_probe/boolean_attr_classes.edn")

(def ^:private react-evidence
  (delay
    (if-let [url (io/resource fixture-resource)]
      (edn/read-string (slurp url))
      (throw (ex-info (str "the react-dom evidence fixture is not on the test "
                           "classpath — without it every assertion below "
                           "iterates nothing and passes vacuously")
                      {:resource fixture-resource})))))

(defn- rows [] (:rows @react-evidence))

(defn- collapse
  "The roster's key shape: hyphens removed, lower-cased."
  [attribute-name]
  (str/lower-case (str/replace attribute-name "-" "")))

;; ---------------------------------------------------------------------------
;; Deriving React's class from React's bytes.
;;
;; Case-insensitive because `pushBooleanAttribute` lower-cases three names on
;; the way out (`autoFocus` -> `autofocus=""`); anchored on the leading space
;; react-dom writes before every attribute so a short name cannot match inside
;; a tag name or another attribute's value.
;; ---------------------------------------------------------------------------

(defn- react-writes? [attribute-name markup suffix]
  (str/includes? (str/lower-case markup)
                 (str " " (str/lower-case attribute-name) suffix)))

(defn- react-class
  "One evidence row -> the class react-dom applied: `:stringify` (both
  booleans reach markup as `=\"true\"` / `=\"false\"`), `:presence` (`true`
  reaches markup as `=\"\"`, `false` does not, and a NON-boolean value is
  collapsed to `=\"\"` as well), `:overloaded` (the same two booleans, but a
  non-boolean value is KEPT — `download=\"report.pdf\"`), or `:ordinary`
  (neither boolean reaches markup).

  THE BOOLEAN PAIR ALONE CANNOT SEPARATE THE MIDDLE TWO. Presence
  and overloaded render identically for `true` and for `false`; they part
  only on a non-boolean value, the case a boolean-only probe never reaches.
  `:string-markup` — react-dom's own bytes for the
  value `\"yes\"`, on the same element — is what tells them apart, so the
  distinction is derived from React here rather than declared by us."
  [{:keys [attribute true-markup false-markup string-markup]}]
  (let [mentions? #(react-writes? attribute % "=\"")]
    (cond
      (and (react-writes? attribute true-markup "=\"true\"")
           (react-writes? attribute false-markup "=\"false\""))
      :stringify

      (and (react-writes? attribute true-markup "=\"\"")
           (not (mentions? false-markup)))
      (if (react-writes? attribute string-markup "=\"yes\"")
        :overloaded
        :presence)

      (and (not (mentions? true-markup)) (not (mentions? false-markup)))
      :ordinary

      :else
      (throw (ex-info "react-dom markup this test cannot classify"
                      {:attribute attribute
                       :true-markup true-markup
                       :false-markup false-markup
                       :string-markup string-markup})))))

;; ---------------------------------------------------------------------------
;; The two documented divergences, each ruled with its reason in 004B.
;; ---------------------------------------------------------------------------

(def ^:private divergences
  "Roster names whose class deliberately differs from react-dom's. `value`
  is a 004B form-control special form rather than a booleanish name, and
  `ismap` is a real HTML boolean attribute react-dom refuses a boolean for."
  {"value" :ordinary
   "ismap" :presence})

(defn- expected-class
  "What `boolean-attr-class` must say for `attribute-name`: React's class,
  unless this is one of the documented divergences."
  [attribute-name react-verdict]
  (get divergences attribute-name react-verdict))

;; ---------------------------------------------------------------------------
;; The classifier agrees with React — in both directions.
;; ---------------------------------------------------------------------------

(deftest boolean-attr-class-agrees-with-installed-react-dom
  (testing "Every attribute react-dom accepts a boolean for is
            classified the way react-dom classifies it. A comparison between
            the two serialisers structurally cannot make this check: the
            evidence comes from outside both consumers of the roster"
    (doseq [row (rows)]
      (let [attribute (:attribute row)
            react     (react-class row)]
        (is (= (expected-class attribute react)
               (rf.ssr.html-helpers/boolean-attr-class attribute))
            (str attribute " — react-dom " (:react-dom-version @react-evidence)
                 " renders " (pr-str (:true-markup row)) " / "
                 (pr-str (:false-markup row))))))))

(deftest ismap-is-a-presence-attribute-as-004b-rules
  ;; react-dom accepts no boolean `ismap`, so no evidence row can hold the
  ;; roster to 004B's row.
  (is (= :presence (rf.ssr.html-helpers/boolean-attr-class "ismap"))))

(deftest every-roster-name-is-backed-by-react-evidence
  (testing "The REVERSE direction. Agreeing with React about the
            names React named is half a check: a name invented here, or one
            react-dom does not carry, would never appear in the evidence
            and would sail through the test above. Every roster member must be
            evidenced, or be a documented divergence"
    (let [evidenced (into {} (map (juxt (comp collapse :attribute) react-class))
                          (rows))
          excused   (set (map collapse (keys divergences)))]
      (doseq [[roster-name roster] [["boolean-attrs" rf.ssr.html-helpers/boolean-attrs]
                                    ["booleanish-attrs" rf.ssr.html-helpers/booleanish-attrs]
                                    ["overloaded-boolean-attrs"
                                     rf.ssr.html-helpers/overloaded-boolean-attrs]]
              attribute roster]
        (when-not (contains? excused attribute)
          (is (contains? evidenced attribute)
              (str roster-name " carries " (pr-str attribute)
                   ", which react-dom "
                   (:react-dom-version @react-evidence)
                   " accepts no boolean for")))))))

;; ---------------------------------------------------------------------------
;; The public emitters follow the roster — every row, both values.
;;
;; The class is compared rather than the bytes for the structural-tree
;; serialiser, whose presence spelling (`disabled=""`) and React prop-name
;; mapping differ from the hiccup emitter's by design (004B: one table, two
;; pipelines). The hiccup paths write the author's name verbatim, so those
;; two are asserted as exact strings.
;; ---------------------------------------------------------------------------

(defn- expected-hiccup-markup
  "The `<div>` markup the hiccup emitters must produce for `attribute` set to
  a BOOLEAN `value`, given the class. Presence and overloaded names are one
  arm here: for a boolean they are indistinguishable, which is precisely why
  the non-boolean drive below has to exist."
  [klass attribute value]
  (case klass
    :stringify              (str "<div " attribute "=\"" value "\"></div>")
    (:presence :overloaded) (if value (str "<div " attribute "></div>") "<div></div>")
    :ordinary               "<div></div>"))

;; ---------------------------------------------------------------------------
;; The NON-BOOLEAN drive.
;;
;; Everything above this line hands the emitters `true` and `false` only, and
;; that leaves a hole: a presence attribute given a non-boolean value that
;; took `attr-string`'s ordinary `:else` branch would emit `disabled="yes"`,
;; where react-dom collapses to `disabled=""` and the structural-tree
;; serialiser next door collapses too. Two SSR pipelines would disagree about
;; one input, with every boolean row green, because no such row can reach
;; the input.
;; ---------------------------------------------------------------------------

(def ^:private non-boolean-probe-values
  "The four non-boolean values `react_dom_probe/boolean_attr_classes.cjs`
  measures beside the booleans. Each carries the fixture field holding
  react-dom's own markup for it, and the string every class EXCEPT
  `:presence` must serialise it to (`0` is a JVM `Long` here, and
  `hash/canonical-number` prints it `\"0\"` — the same bytes CLJS produces)."
  [{:value "yes" :field :string-markup       :serialised "yes"}
   {:value ""    :field :empty-string-markup :serialised ""}
   {:value 0     :field :zero-markup         :serialised "0"}
   {:value "0"   :field :string-zero-markup  :serialised "0"}])

(defn- react-emits?
  "Did react-dom write `attribute` at all in this markup?"
  [attribute markup]
  (react-writes? attribute markup "=\""))

;; Names react-dom never writes as an attribute at any probe value, which the
;; generic "keeps the value" arm below would model wrongly, and so agree with
;; an emitter that wrote one: `children` is React element content, and
;; `defaultValue` / `defaultChecked` are honoured only by the form-control
;; arms. The emitters drop all three on a `<div>`.
(def ^:private reserved-props
  #{"children"})

(def ^:private form-default-props
  #{"defaultValue" "defaultChecked"})

(defn- expected-hiccup-markup-for-value
  "The `<div>` markup the hiccup emitters must produce for a NON-boolean
  `value`. For the presence class the expectation is react-dom's OWN verdict
  on the same value — read off the row rather than restated — re-spelled in
  this grammar's bare presence form. For a RESERVED prop or a
  form-control default prop react-dom writes no attribute at any
  value and this emitter drops the prop, so the expectation is the bare
  element. Every other class keeps the value."
  [klass attribute {:keys [field serialised]} row]
  (cond
    (or (contains? reserved-props attribute)
        (contains? form-default-props attribute))
    "<div></div>"

    (= :presence klass)
    (if (react-emits? attribute (get row field))
      (str "<div " attribute "></div>")
      "<div></div>")

    :else
    (str "<div " attribute "=\"" serialised "\"></div>")))

(deftest render-to-string-follows-react-for-every-non-boolean-probe-value
  (testing "The PUBLIC hiccup render path over the whole evidence
            and all four non-boolean values. A presence name collapses a
            truthy value to its bare self and omits a JS-falsy one; an
            overloaded name keeps every value it is given; a stringifying or
            ordinary name keeps it too"
    (doseq [row   (rows)
            probe non-boolean-probe-values]
      (let [attribute (:attribute row)
            klass     (expected-class attribute (react-class row))]
        (is (= (expected-hiccup-markup-for-value klass attribute probe row)
               (rf.ssr.emit/render-to-string
                 [:div {(keyword attribute) (:value probe)}] {}))
            (str attribute " " (pr-str (:value probe)) " (" klass ") — react-dom "
                 (:react-dom-version @react-evidence) " renders "
                 (pr-str (get row (:field probe)))))))))

(deftest render-to-string-follows-react-for-every-evidenced-attribute
  (testing "The PUBLIC hiccup render path, table-driven over the
            whole react-dom evidence and both boolean values. A presence name
            emits a bare attribute for `true` and nothing for `false`; a
            stringifying name emits a quoted `\"true\"`/`\"false\"` and is
            NEVER omitted; an ordinary name never becomes a bare attribute"
    (doseq [row   (rows)
            value [true false]]
      (let [attribute (:attribute row)
            klass     (expected-class attribute (react-class row))]
        (is (= (expected-hiccup-markup klass attribute value)
               (rf.ssr.emit/render-to-string [:div {(keyword attribute) value}] {}))
            (str attribute " " value " (" klass ")"))))))

(defn- observed-class
  "Which class a serialiser applied, read off its own output and compared
  against its no-attribute baseline. Name-agnostic on purpose: the
  structural-tree serialiser renames attributes through the React prop
  vocabulary, so matching on the author's name would misread a rename as an
  omission."
  [baseline true-html false-html string-html]
  (cond
    (and (str/includes? true-html "=\"true\"")
         (str/includes? false-html "=\"false\""))
    :stringify

    (and (not= baseline true-html) (= baseline false-html))
    ;; The two middle classes are one shape for a boolean, so the split is
    ;; read off a NON-boolean value exactly as react-dom's own is:
    ;; a presence name collapses `"yes"` onto the same output `true` gave, an
    ;; overloaded name keeps it. Still name-agnostic — this compares the
    ;; serialiser's outputs with each other, never with the author's spelling.
    (if (= true-html string-html) :presence :overloaded)

    (and (= baseline true-html) (= baseline false-html))
    :ordinary

    :else
    (throw (ex-info "serialiser output this test cannot classify"
                    {:baseline baseline :true-html true-html
                     :false-html false-html :string-html string-html}))))

(defn- tree-html [attrs]
  (rf.ssr.ui-tree/emit-ui-tree {:rf.ui/tree-version 1 :tag :div :attrs attrs}))

(defn- tree-error-id
  "The `:rf.error/id` the structural-tree serialiser throws for `attrs`, or
  the markup it returns when it does not throw."
  [attrs]
  (try
    (tree-html attrs)
    (catch Throwable e
      (:rf.error/id (ex-data e)))))

(deftest structural-tree-serialiser-follows-react-for-every-evidenced-attribute
  (testing "The structural-tree serialiser is the parity
            REFERENCE, and it has to agree with react-dom too rather than
            merely with the hiccup emitter. Compared as classes, not bytes:
            the two pipelines stay separate (004B) and differ in presence
            spelling and attribute naming; what must not differ is the class.
            A reserved prop has no class to compare: react-dom renders it as
            CONTENT (see `reserved-props`), and the tree holds content in
            `:children`, so the tree refuses it in `:attrs` outright"
    (let [baseline (tree-html {})]
      (doseq [row (rows)]
        (let [attribute (:attribute row)
              klass     (expected-class attribute (react-class row))
              key       (keyword attribute)]
          (if (contains? reserved-props attribute)
            (is (= :rf.error/ui-tree-malformed (tree-error-id {key "yes"}))
                (str attribute " — a reserved prop in :attrs is refused, not classed"))
            (is (= klass
                   (observed-class baseline
                                   (tree-html {key true})
                                   (tree-html {key false})
                                   (tree-html {key "yes"})))
                (str attribute " (" klass ") — structural-tree serialiser"))))))))

(deftest structural-tree-serialiser-collapses-on-react-s-truthiness
  (testing "The structural-tree serialiser's presence collapse uses the one
            shared truthiness helper the hiccup emitter uses, so the two
            pipelines cannot answer one input two ways. An OWN predicate would
            disagree with react-dom on two of these four values — reading the
            number `0` as present (only `nil` absent) or a whitespace string
            as absent (`str/blank?`).
            Name-agnostic: the serialiser renames attributes through the React
            prop vocabulary, so every comparison is between its OWN outputs"
    (let [baseline (tree-html {})]
      (doseq [row   (rows)
              probe non-boolean-probe-values
              :let  [attribute (:attribute row)
                     klass     (expected-class attribute (react-class row))
                     key       (keyword attribute)]
              :when (contains? #{:presence :overloaded} klass)]
        (let [observed (tree-html {key (:value probe)})
              why      (str attribute " " (pr-str (:value probe)) " (" klass
                            ") — react-dom renders "
                            (pr-str (get row (:field probe))))]
          (if (= :presence klass)
            (if (react-emits? attribute (get row (:field probe)))
              (is (= (tree-html {key true}) observed)
                  (str why "; a truthy value collapses onto what `true` gives"))
              (is (= baseline observed)
                  (str why "; a JS-falsy value writes no attribute at all")))
            (is (str/includes? observed (str "=\"" (:serialised probe) "\""))
                (str why "; an overloaded name keeps the value"))))))))
