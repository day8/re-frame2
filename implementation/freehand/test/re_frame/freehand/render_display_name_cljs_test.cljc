(ns re-frame.freehand.render-display-name-cljs-test
  "ONE IDENTIFIER — the name React DevTools shows and the `<id>` in
  `rf:render:<id>` (rf2-2rtt6.136).

  Spec 009 §Naming convention does not stop at naming the measure. It
  requires that the `<id>` in the measure name and **the id the substrate
  publishes to the developer** (React `displayName`, the registrar key) be
  ONE identifier, \"so a name read off the User-Timing stream is directly
  jumpable in the tooling\". The whole ergonomic point is a gesture: read a
  boundary's name in the React DevTools tree, paste it into a `rf:render:`
  User-Timing filter, and see that boundary's renders.

  ## What was wrong

  `v/defview` mints a KEYWORD view id (`re-frame.freehand`'s `(keyword (str
  ns-sym) (str sym))`), and the emitter stamped it with `(str view-id)` — a
  keyword stringifies WITH its leading colon. So DevTools showed
  `:app.todo/todo-row` while the bracket wrote
  `rf:render:app.todo/todo-row`, and the documented paste produced
  `rf:render::app.todo/todo-row` and matched NOTHING. A tool that returns
  silence to a developer using it exactly as documented is worse than one
  with no filter at all: the conclusion drawn is \"there is no trace\",
  not \"the name is wrong\".

  Nothing caught it. The Hicasso bench arm's `defview` mints a STRING
  view name, so its `displayName` and its measure id are byte-identical by
  construction and `arm1/render-measure-cljs-test` is green either way;
  every other `displayName` assertion in this tree is against that arm or
  against the `v/->react` codec. The product path — a keyword id through
  `re-frame.freehand.react` — had no row at all.

  ## Why the rows below are shaped the way they are

  Asserting that each string is WELL-FORMED is exactly the shape that let
  this through: `\":app.todo/todo-row\"` and `\"rf:render:app.todo/todo-row\"`
  are both perfectly well-formed, and they are still two identifiers. So
  the rows assert the two are **equal** — and the CLJS half performs the
  developer's actual gesture against a real User-Timing buffer, because the
  failure being pinned is a filter returning nothing, not a string
  comparison.

  Both hosts, deliberately. The shared builder is `.cljc`
  (`re-frame.performance/entry-id`), and a wrong arity there does not throw
  on ClojureScript — it silently binds something else — so a row green on
  the JVM has not exercised the host the emitter actually runs on."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.performance :as performance]
            #?(:cljs [re-frame.freehand.behaviors :as behaviors])
            #?(:cljs [re-frame.freehand.react :as fr])))

;; The product path, minted the ordinary way. Nothing here is a fixture
;; spelling: this is the declaration an application writes.
(v/defview todo-row [_] [:li "row"])

(def ^:private view-id (:view-id (descriptor/describe todo-row)))

;; ---------------------------------------------------------------------------
;; Cross-host — the shared builder, and the builder that is NOT shared
;; ---------------------------------------------------------------------------

(deftest the-product-mint-is-a-keyword-and-str-is-the-wrong-builder
  (testing "`v/defview` mints a keyword view id, and `(str view-id)` — the
            obvious way to publish it — is NOT the spelling the measure
            carries. This is the reproduction, host-independent: the two
            builders disagree on exactly the character the paste chokes on."
    (is (keyword? view-id)
        "non-vacuous: the product path really does mint a keyword — the
         string-minted bench arm is the case that cannot fail")
    (is (str/starts-with? (str view-id) ":")
        "and a keyword stringifies WITH its colon")
    (is (not (str/starts-with? (performance/entry-id view-id) ":"))
        "while the id the measure carries has none")
    (is (not= (str view-id) (performance/entry-id view-id))
        "so `(str view-id)` and the measure's own builder are two
         identifiers — a future simplification back to `str` reds here")))

(deftest the-shared-builder-strips-the-colon-for-every-bucket
  (testing "`build-name`'s id half is `entry-id`, and it is the SAME id half
            in all four Spec 009 buckets — there is no per-bucket arm. That
            is why the emitter's `displayName` moved rather than the name
            builder: stripping the colon in `:render` alone would have to
            special-case one of four correct buckets, and doing it in the
            emitter instead leaves all four untouched."
    (doseq [bucket [:event :sub :fx :render]]
      (is (= (str "rf:" (name bucket) ":app.todo/todo-row")
             (performance/build-name bucket :app.todo/todo-row))
          (str bucket " strips the colon and keeps the namespace")))
    (doseq [bucket [:event :sub :fx :render]]
      (is (= (performance/build-name bucket :app.todo/todo-row)
             (str "rf:" (name bucket) ":" (performance/entry-id :app.todo/todo-row)))
          (str bucket "'s id half IS entry-id — one builder, not four")))))

(deftest entry-id-leaves-a-string-minted-id-exactly-as-written
  (testing "The bench arm mints `\"<ns>/<view>\"` STRINGS and its
            `displayName`/measure agreement is already byte-identical. The
            shared builder must be the identity there, or a fix for the
            keyword path would break the path that was correct."
    (is (= "my.ns/todo-row" (performance/entry-id "my.ns/todo-row")))
    (is (= "bare-row"       (performance/entry-id "bare-row")))
    (is (= "my.ns/todo-row" (performance/entry-id 'my.ns/todo-row))
        "a symbol is stringified as written too")
    (is (= "rf:render:my.ns/todo-row"
           (performance/build-name :render "my.ns/todo-row"))
        "so the bench arm's row is unmoved")
    (is (= "login" (performance/entry-id :login))
        "and a keyword with no namespace invents none")))

;; ---------------------------------------------------------------------------
;; CLJS — what React is actually handed, and the developer's actual gesture
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- boundary-display-name
     "The `displayName` React DevTools reads off the component the emitter
      mounts `view` through — taken from a real element, which is the only
      thing React itself ever sees."
     [view]
     (.-displayName (.-type (fr/element [view {}])))))

#?(:cljs
   (deftest the-boundary-publishes-the-name-the-measure-carries
     (testing "The two strings are EQUAL, which is the assertion shape that
               would have caught this — `displayName` and the measure's id
               half are one identifier, not two that happen to be
               well-formed."
       (let [shown        (boundary-display-name todo-row)
             measure-name (performance/build-name :render view-id)]
         (is (= (performance/entry-id view-id) shown)
             "React is handed the measure's own id half")
         (is (= measure-name (str "rf:render:" shown))
             "so prefixing the name DevTools shows REBUILDS the measure name
              exactly — which is what 'directly jumpable' means")
         (is (not (str/starts-with? shown ":"))
             "non-vacuous: the colon really is gone from what React sees")))))

#?(:cljs
   (deftest the-behavior-boundary-publishes-one-name-across-both-tiers
     (testing "`[v/behavior …]` lowers to a component in the interpreted
               emitter and to `behaviors/behavior-el` in the compiled one.
               They are one construct, so they publish one name — an app
               mixing tiers must not show `re-frame.freehand/behavior` beside
               `:re-frame.freehand/behavior` in the same tree."
       (is (= (performance/entry-id behaviors/behavior-view-id)
              (.-displayName behaviors/behavior-el))
           "the compiled twin uses the shared builder too")
       (is (not (str/starts-with? (.-displayName behaviors/behavior-el) ":"))
           "non-vacuous: it carries no leading colon either"))))

#?(:cljs
   (defn- rf-measure-names []
     (->> (.getEntriesByType js/performance "measure")
          (map #(.-name %))
          (filterv #(str/starts-with? % "rf:")))))

#?(:cljs
   (deftest pasting-the-devtools-name-into-the-filter-finds-the-trace
     (testing "THE FAILURE, as the developer meets it. The guide says 'the id
               is the head's displayName, so the name you filter the profile
               on is the name React DevTools shows'. So: read the name off the
               boundary, prefix it with `rf:render:`, and ask the User-Timing
               stream. Before the fix this returned NOTHING and the developer
               concluded the trace was absent.

               The entry is written with the very call the bracket makes —
               `(.measure js/performance (build-name :render view-id) #js
               {start end})`. This build has the perf gate at its default-off
               goog-define, so React cannot be made to emit one here; the
               EMISSION is witnessed by the nightly `-emit-nightly-test`
               runners, and what is witnessed here is the NAME agreement the
               paste depends on."
       (.clearMeasures js/performance)
       (let [shown        (boundary-display-name todo-row)
             measure-name (performance/build-name :render view-id)]
         ;; The bracket's own emit, verbatim.
         (.measure js/performance measure-name #js {"start" 0 "end" 1})
         (is (= [measure-name] (rf-measure-names))
             "non-vacuous: exactly one rf: entry is on the stream, and it is
              the one the `:render` bracket would have written")
         ;; The gesture.
         (let [pasted (str "rf:render:" shown)
               hits   (filterv #(= % pasted) (rf-measure-names))]
           (is (= 1 (count hits))
               (str "pasting " (pr-str shown) " into the rf:render: filter "
                    "finds the boundary's trace"))
           (is (= [measure-name] hits)
               "and finds THAT boundary, not some other entry"))
         (.clearMeasures js/performance)))))
