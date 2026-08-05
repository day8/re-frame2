(ns fixture.binder-heads-crossed-cleanly)

;; NEGATIVE (rf2-n6ijg) — one case per `_VECTOR_BINDER_HEADS` entry, each
;; crossing that head between a conformant `let`-bound message and the `throw`
;; WITHOUT the head's binding vector mentioning the message symbol. Every one
;; must stay GREEN: the crossing is provably transparent, so the outer binding
;; is still the one in scope. Zero findings expected.
;;
;; THIS IS THE DIRECTION THAT PROVES THE ROSTER. A head in
;; `_VECTOR_BINDER_HEADS` earns its place by letting `_crossing_is_transparent`
;; return True when the vector is clean; the SHADOWING fixtures next door prove
;; nothing about membership, because an unrecognised head fails closed and
;; refuses for exactly the same reason a recognised-but-shadowing one does.
;; Delete any head below from the roster and its case here fires — which is what
;; ten of the sixteen had no way of saying before.
;;
;; The bodies are minimal by design: each `defn` exists to place one head
;; between the binding and the throw, and nothing else.

;; `let` — an inner binding of a DIFFERENT name.
(defn crossing-let
  [ctx]
  (let [msg (str "boom [:rf.error/binder-let]")]
    (let [detail (pr-str ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-let
                           :detail      detail})))))

;; `loop`
(defn crossing-loop
  [xs]
  (let [msg (str "boom [:rf.error/binder-loop]")]
    (loop [remaining xs]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-loop
                           :remaining   remaining})))))

;; `fn` — clean parameter vector.
(defn crossing-fn
  []
  (let [msg (str "boom [:rf.error/binder-fn]")]
    (fn [x]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-fn
                           :x           x})))))

;; `if-let`
(defn crossing-if-let
  [ctx]
  (let [msg (str "boom [:rf.error/binder-if-let]")]
    (if-let [other (:other ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-if-let
                           :other       other}))
      nil)))

;; `when-let`
(defn crossing-when-let
  [ctx]
  (let [msg (str "boom [:rf.error/binder-when-let]")]
    (when-let [other (:other ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-when-let
                           :other       other})))))

;; `if-some`
(defn crossing-if-some
  [ctx]
  (let [msg (str "boom [:rf.error/binder-if-some]")]
    (if-some [other (:other ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-if-some
                           :other       other}))
      nil)))

;; `when-some`
(defn crossing-when-some
  [ctx]
  (let [msg (str "boom [:rf.error/binder-when-some]")]
    (when-some [other (:other ctx)]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-when-some
                           :other       other})))))

;; `when-first`
(defn crossing-when-first
  [xs]
  (let [msg (str "boom [:rf.error/binder-when-first]")]
    (when-first [head xs]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-when-first
                           :head        head})))))

;; `doseq`
(defn crossing-doseq
  [xs]
  (let [msg (str "boom [:rf.error/binder-doseq]")]
    (doseq [x xs]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-doseq
                           :x           x})))))

;; `for`
(defn crossing-for
  [xs]
  (let [msg (str "boom [:rf.error/binder-for]")]
    (for [x xs]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-for
                           :x           x})))))

;; `dotimes`
(defn crossing-dotimes
  [n]
  (let [msg (str "boom [:rf.error/binder-dotimes]")]
    (dotimes [i n]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-dotimes
                           :i           i})))))

;; `with-open`
(defn crossing-with-open
  [source]
  (let [msg (str "boom [:rf.error/binder-with-open]")]
    (with-open [reader source]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-with-open
                           :reader      reader})))))

;; `with-local-vars`
(defn crossing-with-local-vars
  []
  (let [msg (str "boom [:rf.error/binder-with-local-vars]")]
    (with-local-vars [counter 0]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-with-local-vars
                           :counter     counter})))))

;; `with-redefs`
(defn crossing-with-redefs
  [replacement]
  (let [msg (str "boom [:rf.error/binder-with-redefs]")]
    (with-redefs [pr-str replacement]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-with-redefs})))))

;; `binding`
(defn crossing-binding
  [writer]
  (let [msg (str "boom [:rf.error/binder-binding]")]
    (binding [*out* writer]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-binding})))))

;; `letfn` — the binding vector holds local fns, none of them the message.
(defn crossing-letfn
  []
  (let [msg (str "boom [:rf.error/binder-letfn]")]
    (letfn [(describe [x] (pr-str x))]
      (throw (ex-info msg {:rf.error/id :rf.error/binder-letfn
                           :described   (describe 1)})))))
