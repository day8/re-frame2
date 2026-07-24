(ns re-frame.freehand.data-attr-casing-probe-dom-cljs-test
  "rf2-hl7hr — the PLATFORM FACTS the `data-*` casing refusal rests on, PROBED
  against real React 19.2 in a browser rather than reasoned about (Spec 004B
  §Attribute names, ruled option (c), implementation obligations 1 and 2).

  Option (c) refuses a mixed-case `data-*` at the walk and at compile, so
  NOTHING warn-worthy ever mounts THROUGH Freehand — the refusal itself is
  proven host-neutrally in `data-attr-casing-cljs-test` and through the
  FH-STRUCT-009 `:rejected` rows. What a browser is needed for is the evidence
  the refusal is PREMISED on, read off React and the DOM directly (AROUND
  Freehand, via `react/createElement`), so the ruling is corroborated rather
  than asserted:

    1. THE NON-VACUOUS CONTROL. React's camelCase-prop warning latches
       `warnedProperties[name] = true` once per prop name per session, so any
       console assertion PASSES VACUOUSLY if the name mounted earlier. Every
       assertion here therefore uses a FRESH prop name, and the control proves
       the warning detector goes RED on known-bad input: a fresh mixed-case
       `data-*` handed straight to `react/createElement` warns; a fresh
       lowercase one does not. React's gate is namespace- and boundary-blind,
       so the warning fires across a component boundary too.

    2. THE DOM FACTS. HTML `setAttribute` ASCII-lowercases the name (so the
       casing and, in `.dataset`, nothing survives as the author meant); SVG
       and MathML PRESERVE the mixed name but make `getAttribute`
       case-sensitive and EXCLUDE any uppercase-bearing `data-*` from
       `.dataset` — so the word the author segmented is unreachable there too.
       A mixed-case `data-*` cannot mean what it says on any of the three.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it has
  no real React DOM and says so."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [re-frame.freehand.matrix-support :as ms]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private svg-ns "http://www.w3.org/2000/svg")
(def ^:private mathml-ns "http://www.w3.org/1998/Math/MathML")

(defn- fresh-mixed
  "A FRESH camelCase `data-*` name per call — never mounted before, so React's
  once-per-name warning latch cannot make an assertion pass for free."
  []
  (str "data-cdx" (gensym "Probe")))

(defn- lowered [n] (str/lower-case n))

(defn- dataset-key
  "The `.dataset` key an ALL-LOWERCASE `data-*` name reads back as: strip
  `data-`, camelCase the remainder. For `data-cdxprobe7` that is `cdxprobe7`."
  [lower-name]
  (str/replace (subs lower-name 5) #"-(\w)" (fn [[_ c]] (str/upper-case c))))

(defn- with-console-errors
  "Run async `thunk` with `js/console.error` captured; resolve to the collected
  message strings (React 19 warns through console.error). Always restores."
  [thunk]
  (let [msgs (atom [])
        orig (.-error js/console)]
    (set! (.-error js/console)
          (fn [& args] (swap! msgs conj (str/join " " (map str args)))))
    (-> (js/Promise.resolve (thunk))
        (.then (fn [_] (set! (.-error js/console) orig) @msgs)
               (fn [e] (set! (.-error js/console) orig) (throw e))))))

(defn- js-props
  "A React props object with a static id and a DYNAMIC data-* key set to
  \"x\". A `#js {}` literal cannot carry a runtime key, so the dynamic name is
  written with gobj/set."
  [id nm]
  (doto #js {"id" id} (gobj/set nm "x")))

(defn- render! [root el] (ms/act #(.render root el)))

;; ===========================================================================
;; Obligation 1 — the non-vacuous console control
;; ===========================================================================

(deftest react-warns-on-a-fresh-mixed-case-data-star-and-is-silent-on-lowercase
  (testing "The warning detector goes RED on known-bad input, proven with a
            fresh prop name so React's once-per-session latch cannot make it
            pass vacuously: a mixed-case data-* handed straight to
            react/createElement warns; a lowercase one does not; and the gate
            is boundary-blind, so a mixed-case name inside a child component
            warns too."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the React warning control")
      (async done
        (let [mixed (fresh-mixed)
              low   (lowered (fresh-mixed))
              [c1 r1] (ms/create-root!)
              [c2 r2] (ms/create-root!)
              [c3 r3] (ms/create-root!)
              Child (fn [] (react/createElement "rect" (js-props "bnd" (fresh-mixed))))]
          (-> (with-console-errors
                (fn [] (render! r1 (react/createElement "div" (js-props "p1" mixed)))))
              (.then (fn [msgs]
                       (is (some #(str/includes? % mixed) msgs)
                           "React warns about a fresh mixed-case data-* prop")))
              (.then (fn [_]
                       (with-console-errors
                         (fn [] (render! r2 (react/createElement "div" (js-props "p2" low)))))))
              (.then (fn [msgs]
                       (is (not (some #(str/includes? % low) msgs))
                           "and stays silent on a fresh lowercase data-*")))
              (.then (fn [_]
                       ;; boundary-blind: a mixed-case name inside a child
                       ;; component still warns (React's gate reads the name).
                       (with-console-errors
                         (fn [] (render! r3 (react/createElement
                                              "svg" nil (react/createElement Child)))))))
              (.then (fn [msgs]
                       (is (some #(str/includes? % "data-cdx") msgs)
                           "and warns across a component boundary too")
                       (ms/destroy-root! c1 r1)
                       (ms/destroy-root! c2 r2)
                       (ms/destroy-root! c3 r3)
                       (done)))
              (.catch (fn [e]
                        (ms/destroy-root! c1 r1) (ms/destroy-root! c2 r2) (ms/destroy-root! c3 r3)
                        (is false (str "probe failed: " e))
                        (done)))))))))

;; ===========================================================================
;; Obligation 2 — the DOM facts, read off a real react-dom mount
;; ===========================================================================

(deftest html-lowercases-a-mixed-case-data-star-name
  (testing "React writes the authored data-* name verbatim through
            setAttribute, and HTML ASCII-lowercases it: the DOM carries the
            lowercased attribute, getAttribute is case-insensitive, and
            .dataset keys off the lowercased name — the author's casing is
            gone."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the DOM probe")
      (async done
        (let [mixed (fresh-mixed)
              low   (lowered mixed)
              [container root] (ms/create-root!)]
          (-> (render! root (react/createElement "div" (js-props "hp" mixed)))
              (.then (fn [_]
                       (let [node  (.querySelector container "#hp")
                             names (vec (.getAttributeNames node))]
                         (is (some #{low} names)
                             "the DOM carries the LOWERCASED attribute name")
                         (is (not (some #{mixed} names))
                             "and not the authored mixed-case name")
                         (is (= "x" (.getAttribute node mixed))
                             "getAttribute is case-insensitive on HTML")
                         (is (= "x" (gobj/get (.-dataset node) (dataset-key low)))
                             ".dataset keys off the lowercased name — the casing is lost"))
                       (ms/destroy-root! container root)
                       (done)))
              (.catch (fn [e] (ms/destroy-root! container root)
                        (is false (str "probe failed: " e)) (done)))))))))

(deftest svg-and-mathml-preserve-the-name-but-lose-it-from-dataset
  (testing "SVG and MathML PRESERVE the mixed-case data-* name, so getAttribute
            is case-sensitive (the lowercase spelling misses) — and .dataset
            EXCLUDES any data-* name carrying an uppercase letter, so the word
            boundary the author meant is unreachable there too. Probed directly
            under the namespace root AND across a declared component boundary."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the DOM probe")
      (async done
        (let [svg-direct (fresh-mixed)
              svg-child  (fresh-mixed)
              math-name  (fresh-mixed)
              Child      (fn [] (react/createElement "rect" (js-props "svc" svg-child)))
              [c1 r1] (ms/create-root!)
              [c2 r2] (ms/create-root!)
              [c3 r3] (ms/create-root!)
              check-foreign
              (fn [node nm where]
                (is (= "x" (.getAttribute node nm))
                    (str where ": the mixed-case name is preserved, case-sensitively"))
                (is (nil? (.getAttribute node (lowered nm)))
                    (str where ": the lowercase spelling misses (getAttribute is case-sensitive)"))
                (is (nil? (gobj/get (.-dataset node) (dataset-key (lowered nm))))
                    (str where ": .dataset excludes the uppercase-bearing name")))]
          (-> (render! r1 (react/createElement
                            "svg" nil (react/createElement "rect" (js-props "svd" svg-direct))))
              (.then (fn [_]
                       (check-foreign (.querySelector c1 "#svd") svg-direct "SVG direct")
                       (render! r2 (react/createElement "svg" nil (react/createElement Child)))))
              (.then (fn [_]
                       (check-foreign (.querySelector c2 "#svc") svg-child "SVG across a boundary")
                       (render! r3 (react/createElement
                                     "math" nil (react/createElement "mi" (js-props "mth" math-name))))))
              (.then (fn [_]
                       (check-foreign (.querySelector c3 "#mth") math-name "MathML")
                       (ms/destroy-root! c1 r1) (ms/destroy-root! c2 r2) (ms/destroy-root! c3 r3)
                       (done)))
              (.catch (fn [e]
                        (ms/destroy-root! c1 r1) (ms/destroy-root! c2 r2) (ms/destroy-root! c3 r3)
                        (is false (str "probe failed: " e)) (done)))))))))
