(ns re-frame.ui.parity-corpus-cljs-test
  "THE parity gate (S1f, rf2-vxgfnd.6): for EVERY corpus case, the
  CLJS emitter's output — rendered against real React via
  react-dom/server — must be NORMALIZED-STRUCTURALLY-EQUIVALENT to the
  JVM emitter's output under normalization N (jvm-tree contract
  §Semantic normalization N). Byte-identical HTML is NOT the contract;
  the semantic-node space is.

  The JVM side is computed at CLJS COMPILE time by
  `re-frame.ui.parity-embed/embedded-jvm-semantics` (the macro renders
  the same .cljc fixtures through the JVM emitter in the compiler's
  JVM) and embedded as literals — live cross-emitter parity on every
  run, no checked-in goldens.

  Trusted-HTML leaves on the N side are expanded through the harness
  parser so both sides meet in parsed space (`ph/expand-trusted`)."
  (:require [clojure.test :refer [deftest is]]
            ["react-dom/server" :as rds]
            [re-frame.ui.parity-fixtures :as fx]
            [re-frame.ui.parity-html :as ph]
            [re-frame.ui.runtime :as rt])
  (:require-macros [re-frame.ui.parity-embed :refer [embedded-jvm-semantics]]))

(def jvm-semantics (embedded-jvm-semantics))

(defn- ->props
  "Corpus EDN props -> JS props object per the frozen props ABI
  (deterministic quoted slot names preserving namespace+name)."
  [m]
  (reduce-kv (fn [o k v]
               (unchecked-set o (subs (str k) 1) v)
               o)
             #js {} m))

(defn- render-case [{:keys [view props]}]
  (rds/renderToStaticMarkup (rt/jsx2 (get fx/views view) (->props props))))

(deftest corpus-covers-every-case
  (is (= (count fx/cases) (count jvm-semantics)))
  (is (= (set (map :id fx/cases)) (set (keys jvm-semantics)))))

(deftest every-case-normalized-structural-equivalence
  (doseq [{:keys [id] :as c} fx/cases]
    (let [html   (render-case c)
          react  (ph/html->semantic html)
          jvm    (ph/expand-trusted (get jvm-semantics id))
          diff   (when (not= jvm react) (ph/first-divergence jvm react))]
      (is (= jvm react)
          (str "parity divergence in case " id
               "\n  first divergence: " (pr-str diff)
               "\n  react html: " html)))))

(deftest no-handler-attributes-ever
  ;; belt-and-braces over the parser's hard failure: the serialised
  ;; markup of handler-bearing cases carries no on* attribute names
  (doseq [id [:handlers-on :handlers-off :spread-override :page]]
    (let [c    (some #(when (= id (:id %)) %) fx/cases)
          html (render-case c)]
      (is (nil? (re-find #"\son[A-Za-z-]+=" html))
          (str "handler attribute leaked into HTML for " id ": " html)))))
