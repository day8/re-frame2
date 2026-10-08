(ns reagent2.dom.boolean-attr-react-parity-cljs-test
  "The external anchor for `reagent2.dom.server`'s boolean attribute rosters.
  A hand-written parity corpus only asks about the names it contains, so the
  candidate NAMES come from react-dom too: the values of its
  `possibleStandardNames`, measured live under `:node-test` so a react-dom bump
  reds with no fixture to regenerate. A candidate is boolean-class iff React's
  development build renders `{name: true}` without its non-boolean warning,
  and its class (presence / stringify / dropped) is read from React's bytes.
  The serializer's class is read from its emitted markup, never its private
  rosters, in BOTH directions over the whole candidate space.

  Two allow-listed divergences, applied by `classify`: presence names use the
  HTML5 short form (`disabled`, not `disabled=\"\"`), and presence names
  compare case-insensitively (HTML attributes, and the lowercase key lets
  `:read-only` classify) while the stringify class compares byte-exact,
  because four of its members are case-sensitive SVG attributes."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [reagent2.dom.server :as server]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Node seam
;;
;; `js/require` for node built-ins is established in this repo's
;; :node-test suites (re-frame.fresco.examples.fence-cljs-test,
;; re-frame.test-quiet.shadow-node). react-dom's root is found by
;; walking up from the compiled test's directory rather than through
;; `require.resolve`, so the probe does not depend on how the build
;; tool shapes `require`.
;; ---------------------------------------------------------------------------

(def ^:private node-fs (js/require "fs"))
(def ^:private node-path (js/require "path"))

(defn- react-dom-root
  "Absolute path of the installed react-dom package."
  []
  (loop [dir js/__dirname
         hops 0]
    (let [candidate (.join node-path dir "node_modules" "react-dom" "package.json")]
      (cond
        (.existsSync node-fs candidate)
        (.dirname node-path candidate)

        (> hops 12)
        (throw (js/Error.
                (str "no node_modules/react-dom found walking up from "
                     js/__dirname " — this probe needs the installed package, "
                     "and an empty candidate set would read as a clean pass.")))

        :else
        (recur (.dirname node-path dir) (inc hops))))))

(def ^:private react-dom-dir (react-dom-root))

(def ^:private react-dom-version
  (-> (.readFileSync node-fs (.join node-path react-dom-dir "package.json") "utf8")
      (js/JSON.parse)
      (.-version)))

;; The LEGACY node builds, because they are the pair exporting
;; `renderToStaticMarkup` — the one-shot string API this probe needs.
;; They carry the same `pushAttribute` code as the streaming builds, so
;; the verdicts are react-dom's and not a legacy dialect's.
(def ^:private dev-server-path
  (.join node-path react-dom-dir "cjs" "react-dom-server-legacy.node.development.js"))

(def ^:private prod-server-path
  (.join node-path react-dom-dir "cjs" "react-dom-server-legacy.node.production.js"))

(def ^:private dev-server (js/require dev-server-path))
(def ^:private prod-server (js/require prod-server-path))

;; ---------------------------------------------------------------------------
;; 1. Candidate names — react-dom's own `possibleStandardNames`.
;; ---------------------------------------------------------------------------

(defn- scrape-possible-standard-names
  "Every value of react-dom's `possibleStandardNames` object literal,
  read out of the development build's source text. Balanced-brace scan
  rather than a regex, because the literal spans hundreds of lines."
  [source]
  (let [marker "possibleStandardNames = {"
        start  (str/index-of source marker)
        _      (when-not start
                 (throw (js/Error.
                         (str "react-dom " react-dom-version " carries no "
                              "`possibleStandardNames = {` — the scrape anchor "
                              "moved; fix this probe rather than accepting an "
                              "empty candidate set."))))
        open   (+ start (dec (count marker)))
        end    (loop [i open depth 0]
                 (cond
                   (>= i (count source))
                   (throw (js/Error. "unbalanced `possibleStandardNames` literal"))

                   (= "{" (subs source i (inc i))) (recur (inc i) (inc depth))

                   (= "}" (subs source i (inc i)))
                   (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))

                   :else (recur (inc i) depth)))
        table  (js/JSON.parse
                (-> (subs source open end)
                    ;; A flat object literal of unquoted-or-quoted string
                    ;; keys to string values. Quote the bare keys so
                    ;; JSON.parse can read it without `eval`.
                    (str/replace #"([{,])\s*([A-Za-z_$][\w$-]*)\s*:" "$1\"$2\":")
                    (str/replace #",\s*\}" "}")))]
    (vec (distinct (vals (js->clj table))))))

(def ^:private candidates
  (let [names (scrape-possible-standard-names
               (.readFileSync node-fs dev-server-path "utf8"))]
    ;; `aria-*` / `data-*` are a PREFIX rule in react-dom's default
    ;; branch rather than named entries, so they cannot come out of the
    ;; table. Two representatives each keep the prefix rule pinned.
    (into names ["aria-expanded" "aria-hidden" "data-open" "data-collapsed"])))

;; ---------------------------------------------------------------------------
;; 2. Boolean-class filter — React's own dev warning is the verdict.
;; ---------------------------------------------------------------------------

(def ^:private non-boolean-warning "for a non-boolean attribute")

(defn- react-accepts-boolean?
  "True iff React's DEVELOPMENT build renders `{name true}` without its
  \"Received `true` for a non-boolean attribute\" warning."
  [attr-name]
  (let [original-error js/console.error
        original-warn  js/console.warn
        warned         (atom false)
        watch          (fn [& args]
                         (let [first-arg (first args)]
                           (when (and (string? first-arg)
                                      (str/includes? first-arg non-boolean-warning))
                             (reset! warned true))))]
    (set! js/console.error watch)
    (set! js/console.warn watch)
    (try
      (.renderToStaticMarkup dev-server
                             (react/createElement "div" (js-obj attr-name true)))
      (catch :default _
        ;; A prop React refuses outright is not a boolean-class attribute.
        (reset! warned true))
      (finally
        (set! js/console.error original-error)
        (set! js/console.warn original-warn)))
    (not @warned)))

;; ---------------------------------------------------------------------------
;; 3. Markup, and the class derived from it.
;; ---------------------------------------------------------------------------

(def ^:private probe-elements ["div" "input" "option" "select" "video"])

(defn- react-markup
  [element attr-name value]
  (try
    (.renderToStaticMarkup prod-server
                           (react/createElement element (js-obj attr-name value)))
    (catch :default _ nil)))

(defn- slim-markup
  [element attr-name value]
  (server/render-to-static-markup
   [(keyword element) {(keyword attr-name) value}]))

(defn- mentions?
  "Does `markup` carry ` <attr-name><suffix>`? Anchored on the leading
  space every attribute is written after, so a short name cannot match
  inside a tag name or another attribute's value."
  [markup attr-name suffix case-sensitive?]
  (boolean
   (and markup
        (if case-sensitive?
          (str/includes? markup (str " " attr-name suffix))
          (str/includes? (str/lower-case markup)
                         (str/lower-case (str " " attr-name suffix)))))))

(defn- classify
  "Derive the boolean class from a serialiser's own bytes.

  `presence` accepts BOTH spellings of the empty-value form — React's
  `name=\"\"` and the HTML5 short form `name` this serializer emits —
  which is allow-listed divergence 1. Case-sensitivity is the caller's
  choice: divergence 2 makes presence-class names case-insensitive."
  [attr-name true-markup false-markup case-sensitive?]
  (let [has?     (fn [markup suffix] (mentions? markup attr-name suffix case-sensitive?))
        valued?  (fn [markup] (has? markup "=\""))
        ;; Short form: the name is followed by a space or the tag close.
        short?   (fn [markup]
                   (or (has? markup ">") (has? markup " ") (has? markup "/")))]
    (cond
      (and (has? true-markup "=\"true\"") (has? false-markup "=\"false\""))
      :stringify

      (and (or (has? true-markup "=\"\"") (and (short? true-markup)
                                               (not (valued? true-markup))))
           (not (valued? false-markup))
           (not (short? false-markup)))
      :presence

      (and (not (valued? true-markup)) (not (short? true-markup))
           (not (valued? false-markup)) (not (short? false-markup)))
      :dropped

      :else
      :unclassifiable)))

(defn- react-class
  "React's class for `attr-name`, plus the element it was measured on.
  Returns `nil` when no probe element shows the attribute at all — the
  caller reads that as `:dropped` on a `<div>`.

  Derived CASE-INSENSITIVELY, because react-dom lowercases a handful of
  names on the way out (`autoFocus` → `autofocus=\"\"`) and a
  case-sensitive read of React's own bytes calls those absent — a wrong
  class that still looks like a measurement. A case-sensitive read would
  classify `autoFocus` as `:dropped`, and the reverse arm would then fail
  for emitting markup React supposedly never writes."
  [attr-name]
  (reduce
   (fn [_ element]
     (let [t (react-markup element attr-name true)
           f (react-markup element attr-name false)]
       (if (or (nil? t) (nil? f))
         nil
         (let [k (classify attr-name t f false)]
           (if (contains? #{:presence :stringify} k)
             (reduced {:element element :class k :true-markup t :false-markup f})
             nil)))))
   nil
   probe-elements))

;; ---------------------------------------------------------------------------
;; The React-derived evidence, measured once.
;; ---------------------------------------------------------------------------

(def ^:private evidence
  "One row per react-dom candidate name: React's boolean verdict, and
  where React classifies a boolean, the class and the element it shows
  on."
  (mapv (fn [attr-name]
          (let [boolean-attr? (react-accepts-boolean? attr-name)
                measured      (when boolean-attr? (react-class attr-name))]
            {:attribute attr-name
             :react-boolean? boolean-attr?
             :element   (or (:element measured) "div")
             :class     (if measured (:class measured) :dropped)}))
        candidates))

(def ^:private boolean-class-rows
  (filterv (fn [row] (and (:react-boolean? row) (= :stringify (:class row))))
           evidence))

(def ^:private presence-rows
  (filterv (fn [row] (and (:react-boolean? row) (= :presence (:class row))))
           evidence))

;; ---------------------------------------------------------------------------
;; Guards on the evidence itself.
;;
;; An empty or badly shrunken candidate set is the failure mode that
;; reads as a clean measurement: every table-driven assertion below
;; would iterate nothing and pass.
;; ---------------------------------------------------------------------------

(deftest react-evidence-is-plausible
  (testing "the probe actually measured the installed react-dom"
    (is (str/starts-with? react-dom-version "19.")
        (str "react-dom " react-dom-version
             " — this probe targets the 19.x builds"))
    (is (> (count candidates) 300)
        (str "only " (count candidates) " candidate names scraped from "
             "possibleStandardNames — the scrape is broken, and an empty "
             "candidate set would read as a clean pass"))
    (is (> (count presence-rows) 20)
        (str "only " (count presence-rows) " presence-class names measured"))
    (is (> (count boolean-class-rows) 5)
        (str "only " (count boolean-class-rows) " stringifying-class names "
             "measured"))
    (is (>= (+ (count presence-rows) (count boolean-class-rows)) 30)
        "react-dom 19 carries three dozen boolean-class names")))

;; ---------------------------------------------------------------------------
;; Direction 1 — every name React classifies, the serializer classifies
;; the same way.
;; ---------------------------------------------------------------------------

(deftest boolean-classes-agree-with-installed-react-dom
  (testing "react-dom's boolean classes, derived from react-dom's own bytes"
    (doseq [{:keys [attribute element class]} (concat presence-rows boolean-class-rows)]
      (let [;; Divergence 2: presence-class names compare
            ;; case-insensitively, stringifying names byte-exact.
            case-sensitive? (= :stringify class)
            t (slim-markup element attribute true)
            f (slim-markup element attribute false)]
        (is (= class (classify attribute t f case-sensitive?))
            (str "react-dom " react-dom-version " classifies `" attribute
                 "` as " class " on <" element ">; render-to-static-markup "
                 "emitted " (pr-str t) " / " (pr-str f)))))))

;; ---------------------------------------------------------------------------
;; Direction 2 — the serializer carries no boolean class React does not.
;; ---------------------------------------------------------------------------

(deftest non-boolean-react-attributes-carry-no-boolean-class
  (testing "a name React puts no boolean into markup for emits nothing either"
    ;; Both halves of "not a boolean class": the names React WARNS about
    ;; (`cols`, `size`, `href`, …) and the names it accepts a boolean for
    ;; yet never writes (`defaultChecked`, `children`, `is`, …).
    (doseq [{:keys [attribute]}
            (remove (fn [row] (contains? #{:presence :stringify} (:class row)))
                    evidence)]
      (let [t (slim-markup "div" attribute true)
            f (slim-markup "div" attribute false)]
        (is (= :dropped (classify attribute t f false))
            (str "react-dom " react-dom-version " puts no boolean into markup "
                 "for `" attribute "` — it either warns the name is not a "
                 "boolean attribute, or accepts one and writes nothing — so a "
                 "boolean value must not reach markup here either; "
                 "render-to-static-markup emitted " (pr-str t) " / " (pr-str f)))))))

;; ---------------------------------------------------------------------------
;; The OVERLOADED class, which boolean probing cannot see.
;;
;; `download` and `capture` are indistinguishable from the presence
;; class while only booleans are supplied — both emit the bare name on
;; true and nothing on false. The distinction is a NON-boolean value,
;; which the probe above deliberately does not supply, so it is pinned
;; here against live react-dom with a third value. (The SSR probe cannot
;; pin this: its fixture is boolean-only.)
;; ---------------------------------------------------------------------------

(deftest overloaded-booleans-keep-a-non-boolean-value
  (doseq [[element attribute value]
          [["a" "download" "report.pdf"]
           ["input" "capture" "user"]]]
    (testing (str "`" attribute "` is overloaded, not presence")
      (let [react-html (react-markup element attribute value)
            slim-html  (slim-markup element attribute value)]
        (is (str/includes? react-html (str " " attribute "=\"" value "\""))
            (str "control: react-dom " react-dom-version
                 " must keep the non-boolean value — got " (pr-str react-html)))
        (is (str/includes? slim-html (str " " attribute "=\"" value "\""))
            (str "a presence-class collapse would throw the value away — got "
                 (pr-str slim-html)))))))

;; ---------------------------------------------------------------------------
;; Attribute NAMES over the same candidate space.
;;
;; `react-attribute-name-overrides` is a hand-kept copy of the names react-dom
;; writes differently from the prop name — its `aliases` Map and its
;; `pushAttribute` special cases — and a missing row falls through to the
;; lowercase rule. A hand-written corpus such as `parity_cljs_test`'s asks
;; only about the names it contains, so a missing `xlink*` / `xml*` row or
;; `transformOrigin` could pass it. So the candidates above are asked a
;; second question: under which NAME does a
;; string value reach markup? Compared case-INSENSITIVELY, because HTML
;; attribute names are, and this serializer lowercases the camelCase names
;; outside its table (`hrefLang` → `hreflang`) where react-dom keeps them.
;; What this arm catches is a DIFFERENT attribute (`xlinkhref` for
;; `xlink:href`). A name reaching markup on one side only is a value-class
;; question, which the tests above own, so it is not compared here.
;; ---------------------------------------------------------------------------

(def ^:private name-sentinel "rf2NameSentinel")

(defn- sentinel-name
  "The attribute name `markup` carries `name-sentinel` under, or nil."
  [markup]
  (when markup
    (second (re-find (re-pattern (str " ([^\\s=<>\"]+)=\"" name-sentinel "\""))
                     markup))))

(deftest attribute-names-agree-with-installed-react-dom
  (let [rows (for [attribute candidates
                   :let [react-name (sentinel-name (react-markup "div" attribute name-sentinel))
                         slim-name  (sentinel-name (slim-markup "div" attribute name-sentinel))]
                   :when (and react-name slim-name)]
               {:attribute attribute :react react-name :slim slim-name})]
    (testing "the sweep compared real rows, including xlinkHref, xmlLang and transformOrigin"
      (is (> (count rows) 200)
          (str "only " (count rows) " names compared — an empty sweep reads as a pass"))
      (is (every? (set (map :attribute rows)) ["xlinkHref" "xmlLang" "transformOrigin"])))
    (testing "every name reaches markup under the attribute react-dom writes"
      (doseq [{:keys [attribute react slim]} rows]
        (is (= (str/lower-case react) (str/lower-case slim))
            (str "react-dom " react-dom-version " writes `" attribute "` as `"
                 react "`; render-to-static-markup wrote `" slim "`"))))))

;; ---------------------------------------------------------------------------
;; javascript: URLs over the same candidate space.
;;
;; react-dom blocks a `javascript:` URL in a fixed set of props (its
;; `sanitizeURL`), and this serializer carries a copy of that set. So the
;; candidates are asked a third question: does a `javascript:` value reach
;; markup live, or blocked? If react-dom blocks a name and this serializer does
;; not, the URL ships live. The reverse
;; would block a value react-dom writes. `data` is blocked only on an
;; `<object>`, which a `<div>` sweep cannot reach; `parity_cljs_test` pins it.
;; ---------------------------------------------------------------------------

(def ^:private blocked-url-marker "React has blocked a javascript: URL")

(deftest javascript-url-blocking-agrees-with-installed-react-dom
  (let [value "javascript:alert(1)"
        rows  (for [attribute candidates
                    :let [react-html (react-markup "div" attribute value)
                          slim-html  (slim-markup "div" attribute value)]
                    :when (and react-html slim-html)]
                {:attribute  attribute
                 :react?     (str/includes? react-html blocked-url-marker)
                 :slim?      (str/includes? slim-html blocked-url-marker)
                 :react-html react-html
                 :slim-html  slim-html})]
    (testing "the sweep compared real rows, and react-dom blocked the names it
              is known to"
      (is (> (count rows) 200)
          (str "only " (count rows) " names compared — an empty sweep reads as a pass"))
      (is (= #{"href" "src" "action" "formAction" "xlinkHref"}
             (set (map :attribute (filter :react? rows))))
          (str "react-dom " react-dom-version " blocks a javascript: URL on a "
               "<div> in exactly these props; if the set moved, read "
               "`sanitizeURL`'s call sites in the installed build")))
    (testing "every name is blocked by this serializer exactly when react-dom
              blocks it"
      (doseq [{:keys [attribute react? slim? react-html slim-html]} rows]
        (is (= react? slim?)
            (str "react-dom " react-dom-version (if react? " blocks " " writes ")
                 "a javascript: URL in `" attribute "` (" (pr-str react-html)
                 "); render-to-static-markup wrote " (pr-str slim-html)))))))

;; ---------------------------------------------------------------------------
;; Byte pins for what the react-dom sweeps above cannot reach: kebab-case keys
;; (the sweeps use react-dom's camelCase names), aria-*/data-* names (absent
;; from possibleStandardNames), and React-internal props.
;; ---------------------------------------------------------------------------

(deftest stringifying-names-emit-true-and-false
  (testing "a case-sensitive SVG member reached through a kebab key is not lowercased"
    (is (= "<feComposite preserveAlpha=\"true\"></feComposite>"
           (server/render-to-static-markup [:feComposite {:preserve-alpha true}])))))

(deftest aria-and-data-booleans-stringify
  (testing "a false that carries meaning stringifies rather than dropping"
    (is (= "<button aria-expanded=\"true\">x</button>"
           (server/render-to-static-markup [:button {:aria-expanded true} "x"])))
    (is (= "<button aria-expanded=\"false\">x</button>"
           (server/render-to-static-markup [:button {:aria-expanded false} "x"])))
    (is (= "<div data-collapsed=\"false\"></div>"
           (server/render-to-static-markup [:div {:data-collapsed false}])))))

(deftest controls-that-must-not-move
  (testing "a kebab presence key classifies through the lowercase roster"
    (is (= "<input readonly>"
           (server/render-to-static-markup [:input {:read-only true}]))))
  (testing "a boolean on an ordinary attribute does not become a bare attribute"
    (is (= "<div></div>" (server/render-to-static-markup [:div {:title true}])))
    (is (= "<div></div>" (server/render-to-static-markup [:div {:title false}]))))
  (testing "React-internal props stay dropped whatever their value"
    (is (= "<div></div>" (server/render-to-static-markup [:div {:key false}])))
    (is (= "<div></div>" (server/render-to-static-markup [:div {:suppressHydrationWarning true}])))))
