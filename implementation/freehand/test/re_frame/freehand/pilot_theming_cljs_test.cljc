(ns re-frame.freehand.pilot-theming-cljs-test
  "THE THEMING PILOT, judged structurally — the F5b acceptance whose
  evidence a tree can hold, on BOTH hosts from one `.cljc` body.

  Two of the three F5b proofs live here because they are host-common:

  - **Part addresses appear in the structural tree** (acceptance 2). The
    `data-component` scope and each declared `data-part` are ordinary host
    values in the versioned tree, readable on the JVM and in ClojureScript
    alike. A private node carries none, and the emitted address set is
    EXACTLY the declared roster — no undeclared part leaks, no declared
    part goes missing.
  - **No transform seam is exported** (acceptance 3). The public-API
    manifest — the enumerated public surface — carries no re-theming or
    tree-transform verb on the Freehand namespaces. F5b adds tokens and
    addresses, both ordinary host values, and NO substrate verb.

  The third proof — a caller targets a part and the computed style changes
  — is a live-browser fact and is OWED to `pilot-theming-dom-cljs-test`; a
  structural stand-in for a cascade proves nothing.

  `chip` is props-only, so `t/render` renders it directly: there is no
  `v/sub` in its body and thus no render candidate to supply."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [re-frame.freehand.pilot-theming :as chip-lib]
            [re-frame.freehand.test :as t]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(def ^:private a-chip
  "One rendered chip carrying every plane: a variant class, an inline
  accent (the dynamic residue), and a label a caller reads."
  (t/render [chip-lib/chip {:label "Ready" :tone :ok :accent "rgb(1, 2, 3)"}]))

(defn- root-of [tree]
  (t/find tree #(= chip-lib/component-id (:data-component (:attrs %)))))

(defn- by-part [tree p]
  (t/find tree #(= p (:data-part (:attrs %)))))

(defn- emitted-parts [tree]
  (into #{}
        (keep #(:data-part (:attrs %)))
        (t/find-all tree (constantly true))))

(defn- custom-property
  "The value of style custom property named `css-name` on `node`, or nil —
  reading by the key's NAME so a keyword or string spelling both resolve."
  [node css-name]
  (some (fn [[k v]] (when (= css-name (name k)) v))
        (:style (t/attrs node))))

;; ===========================================================================
;; Acceptance 2 — part addresses appear in the structural tree
;; ===========================================================================

(deftest the-component-scope-and-part-addresses-appear-in-the-tree
  (testing "The root carries the `data-component` scope and the `root` part;
            `dot` and `label` carry their addresses; the label's text is
            reachable through its address."
    (let [root (root-of a-chip)]
      (is (some? root) "the component scope marker is in the tree")
      (is (= "root" (:data-part (:attrs root))) "the root part addresses the scope node")
      (is (some? (by-part a-chip "dot")) "the dot part is addressable")
      (is (= "Ready" (t/text (by-part a-chip "label")))
          "the label part is addressable and carries its text"))))

(deftest the-emitted-addresses-are-exactly-the-declared-roster
  (testing "Every declared part is emitted and nothing undeclared leaks — the
            roster is the closed public surface a stylesheet enumerates."
    (is (= (set (map name chip-lib/part-ids))
           (emitted-parts a-chip))
        "emitted data-part set == declared roster")))

(deftest a-private-node-carries-no-part-address
  (testing "The decorative glow is an implementation node the caller cannot
            address — part ids are a deliberate PUBLIC subset, not every node."
    (let [glow (t/find a-chip #(= "acme-chip__glow" (:class (:attrs %))))]
      (is (some? glow) "the private node is present")
      (is (nil? (:data-part (:attrs glow))) "and it carries no part address"))))

(deftest a-variant-rides-a-class-never-a-part-address
  (testing "`:tone` is a value prop lowered to a class; it never becomes a
            `data-part`, which would confuse a fixed choice with an open
            address."
    (let [root (root-of a-chip)]
      (is (str/includes? (:class (:attrs root)) "acme-chip--ok")
          "the variant is on the class")
      (is (not (contains? (emitted-parts a-chip) "ok"))
          "and the variant name is not a part address"))))

;; ===========================================================================
;; The CSS-token plane — the inline dynamic residue, and its absence by default
;; ===========================================================================

(deftest the-dynamic-accent-rides-an-inline-namespaced-custom-property
  (testing "The one value the cascade cannot know rides as an inline
            `--acme-chip-accent` custom property — a token, in the tree, as an
            ordinary host value."
    (is (= "rgb(1, 2, 3)" (custom-property (root-of a-chip) "--acme-chip-accent"))
        "the accent is an inline custom property")))

(deftest a-chip-with-no-accent-emits-no-inline-style
  (testing "The inline custom property is the dynamic-residue ESCAPE, not the
            default path: with no accent, the chip carries no inline style and
            takes every value from the cascade."
    (let [plain (t/render [chip-lib/chip {:label "Idle"}])]
      (is (nil? (:style (t/attrs (root-of plain))))
          "no accent -> no inline style"))))

;; ===========================================================================
;; The part contract has teeth — an undeclared address is refused
;; ===========================================================================

(deftest an-undeclared-part-address-is-refused
  (testing "The roster is the single source of truth: asking to address a part
            the component does not declare is refused with the roster in hand,
            rather than emitting an address no stylesheet was told about."
    (is (true?  (chip-lib/valid-part? :label)) "a declared part is valid")
    (is (false? (chip-lib/valid-part? :bogus)) "an undeclared part is not")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          (chip-lib/part :bogus))
        "and emitting an undeclared address throws")))

;; ===========================================================================
;; Acceptance 3 — no transform seam is exported
;; ===========================================================================

(defn- manifest-path
  "The repo's spec/api-manifest.edn, found by walking up from the process
  working directory — node under `npm run test:freehand` (cwd
  implementation/) and the JVM under `clojure -M:test` (cwd
  implementation/freehand/) both reach the same file."
  []
  #?(:clj
     (loop [dir (.getCanonicalFile (clojure.java.io/file (System/getProperty "user.dir")))]
       (when dir
         (let [f (clojure.java.io/file dir "spec" "api-manifest.edn")]
           (if (.exists f) (.getPath f) (recur (.getParentFile dir))))))
     :cljs
     (let [fs   (js/require "fs")
           path (js/require "path")]
       (loop [dir (js/process.cwd)]
         (let [f (.join path dir "spec" "api-manifest.edn")]
           (cond
             (.existsSync fs f) f
             :else (let [parent (.dirname path dir)]
                     (when-not (= parent dir) (recur parent)))))))))

(defn- manifest-vars []
  (let [p (manifest-path)]
    (when p
      (let [content #?(:clj  (slurp p)
                       :cljs (.readFileSync (js/require "fs") p "utf8"))
            ;; skip the generated leading `;;` banner — read from the map.
            body    (subs content (str/index-of content "{"))]
        (:vars (edn/read-string body))))))

(defn- transform-seam?
  [{:keys [var]}]
  (let [n (str/lower-case var)]
    (or (str/includes? n "transform")
        (str/includes? n "rewrite")
        (str/includes? n "retheme")
        (str/includes? n "restyle")
        (contains? #{"theme" "themes" "parts" "with-theme" "reparent" "rehome"} n))))

(deftest no-transform-seam-is-exported-on-the-freehand-surface
  (testing "The public-API manifest carries no re-theming or tree-transform verb
            on the Freehand namespaces — F5b is tokens and addresses, both
            ordinary host values, and no substrate verb (D018: transforms remain
            interpreted/test-only)."
    (let [vars      (manifest-vars)
          freehand  (filter #(str/starts-with? (:namespace %) "re-frame.freehand") vars)
          offenders (filter transform-seam? freehand)]
      (is (seq freehand)
          "the Freehand public surface is present in the manifest (path resolved)")
      (is (empty? offenders)
          (str "a theme/tree-transform seam leaked onto the public Freehand surface: "
               (mapv (juxt :namespace :var) offenders))))))
