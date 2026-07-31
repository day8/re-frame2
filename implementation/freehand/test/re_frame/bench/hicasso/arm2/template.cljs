(ns re-frame.bench.hicasso.arm2.template
  "TIER 1 — TEMPLATE EXTRACTION WITH DATA-ENCODED HOLE PLANS
  (rf2-2rtt6.10, architecture.md Arm 2: *template extraction with
  data-encoded hole plans as the static-shape fast path, CSP-safe, no
  `new Function`*).

  ## What a plan is

  A plan is what the renderer learns the *first* time it meets a static
  hiccup shape, so that every later meeting is cheap:

      {:sig      \"(li.row|(span.lbl|#)(span.cell|data-i|#))\"
       :template <a detached DOM subtree, built once>
       :holes    [{:kind :prop  :prop :data-i :props-path [2 1] :dom-path #js [1]}
                  {:kind :text  :hiccup-path [1 1]              :dom-path #js [0 0]}
                  …]}

  Everything in it is **data** — vectors, keywords, small JS index
  arrays, and one detached node. Nothing is compiled, nothing is
  `eval`ed, no `new Function` is called, and the plan survives a strict
  Content-Security-Policy because it is not code. Hicasso's fence is
  explicit that a *compiler* would kill the programme (K6); template
  extraction at runtime is the thing that gets a compiler's constant
  factor without one, which is exactly why architecture.md puts it in
  Arm 2's scope and out of Arm 1's.

  ## Why the signature is the entry, and why it is stamped on the node

  A hole plan is only usable when the renderer knows the shape did not
  change. Computing that from scratch on both sides would cost two
  structural walks per patch, which is the work the plan is trying to
  avoid.

  So the walk happens once — on the **new** tree, producing a signature
  string — and the *old* side answers in O(1): the node was stamped with
  the signature it was last rendered from ([[stamp!]]). A patch is tier 1
  when the new signature is `=` to the node's stamp. One string compare.

  The signature is deliberately cheap: it reads tag literals and prop
  **keys**, never prop values, never children's values. It is therefore
  linear in the shape and independent of the data — which is what makes
  it affordable to compute on every render of every element.

  ## What the plan refuses

  A shape is templatable only when its structure cannot vary with data.
  [[signature]] returns `nil` — and the differ falls to tier 3 — for:

  - a **seq** child, whose length is data (this is the `for` case, and it
    is the whole reason a keyed reconciler exists);
  - a **fragment** child, which splices and therefore has no fixed slot
    count;
  - a **boundary** head, which owns its own subtree and its own commit;
  - a `nil`/`false` child, whose presence is data even though the 1:1
    law gives it a stable slot — a conditional child changes what kind
    of node the slot holds, and a plan that assumed otherwise would
    write text into a comment;
  - a child declaring a **`:key`**, because a key says *position is not
    identity* and a hole plan is positional. Patching slot 0's text
    rather than moving the node that owns the key reads correctly and is
    wrong: every piece of DOM state a row holds — focus, scroll, a
    controlled field's live value — would end up on the wrong row.

  The refusal is per shape, not per tree: a `for` over rows refuses at
  the `<ul>` and every row inside it still runs tier 1.

  ## The two static positions

  `:id` and `:class` are the only props the tag literal can contribute
  to (`:div#main.wide`). When the author writes no `:id`/`:class` prop,
  the shorthand is **baked into the template** and is not a hole at all.
  When they do, the hole carries the shorthand so the merge happens
  without the map allocation `merge-shorthand` would cost per element per
  render. That allocation — one fresh map per element per render — is the
  single biggest thing tier 1 removes from the steady-state path."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.arm2.dom :as dom]
            [re-frame.bench.hicasso.front.codec :as codec]))

;; ---------------------------------------------------------------------------
;; The stamp
;; ---------------------------------------------------------------------------

(def ^:private sig-key "__hicassoSig")

(defn stamp!
  "Record on `node` the signature it now matches (or clear it with nil)."
  [node sig]
  (unchecked-set node sig-key sig)
  nil)

(defn stamped [node] (unchecked-get node sig-key))

(defn fits?
  "Was `node` last rendered from this exact plan's shape? One string
  comparison — the whole point of the stamp."
  [plan node]
  (and (some? plan) (= (:sig plan) (unchecked-get node sig-key))))

;; ---------------------------------------------------------------------------
;; The signature
;; ---------------------------------------------------------------------------

(defn- scalar? [x] (or (string? x) (number? x)))

(defn- tag-head? [head] (and (or (keyword? head) (string? head) (symbol? head)) (not= :<> head)))

(defn- props-at [argv] (let [p (nth argv 1 nil)] (when (map? p) p)))

(defn- keyed-child?
  "Does this child declare a `:key`? See the refusal in [[sig-of!]]."
  [c]
  (and (vector? c)
       (when-some [p (props-at c)] (contains? p :key))))

(defn- sig-of!
  "Append `form`'s signature to `out`, or return `false` to refuse the
  whole shape. Recursive, depth-first, in the order the DOM will hold."
  [out form]
  (cond
    (scalar? form) (do (.push out "#") true)

    (vector? form)
    (let [head (nth form 0 nil)]
      (if-not (tag-head? head)
        false
        (let [props (props-at form)
              from  (if props 2 1)
              n     (count form)]
          ;; A CHILD that declares `:key` refuses the parent's shape. A key
          ;; says *position is not identity*, and a hole plan is positional
          ;; by construction: it would patch the text of slot 0 rather than
          ;; move the node that owns that key, and every piece of DOM state
          ;; a row holds — focus, scroll, a controlled field's live value —
          ;; would end up on the wrong row. The keyed reconciler exists for
          ;; exactly this, so the plan stands aside for it.
          ;;
          ;; The common keyed shape is a seq, which already refuses; this
          ;; catches the literal keyed list, which is rarer and was wrong
          ;; in silence until `patch_dom_cljs_test/a-reorder-recreates-no-node`
          ;; asked which nodes survived rather than what the markup read.
          (.push out "(")
          (.push out (name head))
          (when props
            (reduce-kv (fn [_ k _] (when-not (= :key k) (.push out "|") (.push out (name k))) nil) nil props))
          (loop [i from]
            (if (>= i n)
              (do (.push out ")") true)
              (let [c (nth form i)]
                (if (keyed-child? c)
                  false
                  (if (sig-of! out c) (recur (inc i)) false))))))))

    :else false))

(defn signature
  "The shape signature of one hiccup element, or nil when the shape is
  not templatable. See the namespace docstring for the refusal list."
  [form]
  (let [out #js []]
    (when (sig-of! out form)
      (.join out ""))))

;; ---------------------------------------------------------------------------
;; Building a plan
;; ---------------------------------------------------------------------------

(defn- shorthand-class
  "The class string the tag literal contributes, or nil."
  [parsed]
  (.-className parsed))

(defn- collect!
  "Walk `form`, appending holes to `holes` and building the template
  subtree. `elem-path` is the hiccup path to this form; `dom-path` is the
  JS index array addressing the node inside the template."
  [form elem-path dom-path holes]
  (cond
    (scalar? form)
    (do (.push holes {:kind :text :hiccup-path elem-path :dom-path dom-path})
        (js/document.createTextNode ""))

    :else
    (let [parsed     (codec/cached-parse (nth form 0))
          props      (props-at form)
          props-path (conj elem-path 1)
          from       (if props 2 1)
          node       (js/document.createElement (.-tag parsed))
          shorthand  (shorthand-class parsed)
          declared?  (and props (or (contains? props :class) (contains? props :className)))]
      ;; The two static positions.
      (when-some [id (.-id parsed)]
        (when-not (and props (contains? props :id)) (.setAttribute node "id" id)))
      (when (and shorthand (not declared?)) (.setAttribute node "class" shorthand))
      (when declared?
        (.push holes {:kind :class :props-path props-path :shorthand shorthand :dom-path dom-path}))
      (when props
        (reduce-kv (fn [_ k _]
                     (when-not (or (= :key k) (= :class k) (= :className k))
                       (.push holes {:kind :prop :prop k :props-path props-path :dom-path dom-path}))
                     nil)
                   nil
                   props))
      (loop [i from j 0]
        (when (< i (count form))
          (.appendChild node (collect! (nth form i) (conj elem-path i) (.concat dom-path #js [j]) holes))
          (recur (inc i) (inc j))))
      node)))

(defn- build-plan [sig form]
  (let [holes #js []
        tmpl  (collect! form [] #js [] holes)]
    {:sig sig :template tmpl :holes (vec holes)}))

(defonce ^:private !plans (atom {}))

(defn plan-for
  "The plan for `form`'s shape, building it on first sight. Returns nil
  when the shape is not templatable."
  [form]
  (when-some [sig (signature form)]
    (or (get @!plans sig)
        (let [plan (build-plan sig form)]
          (swap! !plans assoc sig plan)
          plan))))

(defn plan-count "How many distinct shapes the cache holds. For the witnesses." [] (count @!plans))

(defn reset-plans! "Empty the plan cache. Test fixture door." [] (reset! !plans {}) nil)

;; ---------------------------------------------------------------------------
;; Reading a hole's value
;; ---------------------------------------------------------------------------

(defn- node-at
  "Walk `root` down a precomputed index path."
  [root path]
  (let [n (alength path)]
    (loop [node root i 0]
      (if (>= i n)
        node
        (recur (loop [c (.-firstChild node) k 0]
                 (if (< k (aget path i)) (recur (.-nextSibling c) (inc k)) c))
               (inc i))))))

(defn- hole-value [hole form]
  (case (:kind hole)
    :text  (get-in form (:hiccup-path hole))
    :class (let [props (get-in form (:props-path hole))]
             (codec/class-names (:shorthand hole) (or (:class props) (:className props))))
    :prop  (get (get-in form (:props-path hole)) (:prop hole))))

(defn- write-hole!
  [hole node value old-value lower]
  (case (:kind hole)
    :text  (set! (.-nodeValue node) (if (nil? value) "" (str value)))
    :class (if (nil? value) (.removeAttribute node "class") (.setAttribute node "class" value))
    :prop  (let [k (:prop hole)]
             (dom/set-prop! node k (lower k value) old-value)))
  nil)

;; ---------------------------------------------------------------------------
;; Mount and patch
;; ---------------------------------------------------------------------------

(defn mount-from-plan!
  "Clone the plan's template and write this instance's holes. One native
  `cloneNode(true)` replaces the whole element/attribute construction
  walk; only the holes are touched in JavaScript."
  [plan form lower]
  (let [root (.cloneNode (:template plan) true)]
    (doseq [hole (:holes plan)]
      (let [v (hole-value hole form)]
        (when (some? v) (write-hole! hole (node-at root (:dom-path hole)) v nil lower))))
    (stamp! root (:sig plan))
    root))

(defn patch-from-plan!
  "Patch `node` from `old` to `new` through the plan: one pass over the
  holes, comparing the author's own values, writing only where they
  differ. No map is allocated, no prop name is converted, no child list
  is realized, and nothing recurses."
  [plan node old new lower]
  (doseq [hole (:holes plan)]
    (let [o (hole-value hole old)
          v (hole-value hole new)]
      (when-not (= o v)
        (write-hole! hole (node-at node (:dom-path hole)) v o lower))))
  nil)

;; ---------------------------------------------------------------------------
;; Observation — for the tests and the witnesses, never for the runtime
;; ---------------------------------------------------------------------------

(defn hole-count [plan] (count (:holes plan)))

(defn describe
  "A plan, as printable data. Used by the tests to assert the extraction
  rather than its effects."
  [plan]
  (when plan
    {:sig   (:sig plan)
     :holes (mapv (fn [h] (-> h (dissoc :dom-path) (assoc :dom-path (vec (:dom-path h))))) (:holes plan))}))

(defn templatable?
  "Does this shape get a plan at all? The refusal list, as a predicate."
  [form]
  (some? (signature form)))

(defn sig-parts
  "The signature split on its element boundaries — a legibility helper
  for test failure output, not a runtime path."
  [sig]
  (when sig (str/split sig #"(?=\()")))
