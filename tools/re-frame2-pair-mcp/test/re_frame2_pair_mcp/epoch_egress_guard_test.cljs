(ns re-frame2-pair-mcp.epoch-egress-guard-test
  "GUARD G3: an epoch record must reach the egress door STAMPED.

  `re-frame.core/project-egress` recognises an epoch record only by its
  `:kind :rf/epoch-record` stamp. An unstamped record (from an app whose
  epoch assembly predates the stamp) falls through to the bare-value walk,
  which starts at `:path []`, so no `:db-after`-prefixed slot can match a
  sensitive declaration and the whole record ships raw. Every emitted form
  therefore checks the stamp before the door call and throws otherwise.

  The guard is built by string concatenation, so these tests READ the
  rendered source back as CLJS data, which proves it is well-formed, and
  run the guard's predicate, as rendered, against stamped and unstamped
  records. Evaluating the whole form needs a live app: that is the
  conformance harness's job."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader :as reader]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]))

(def ^:private stamped
  {:kind      :rf/epoch-record
   :frame     :app/main
   :db-after  {:auth {:token "s3cr3t"}}
   :db-before {:auth {:token "old"}}})

(def ^:private unstamped (dissoc stamped :kind))

(defn- sources
  "Both render sites' source under posture `incl?`: the page `mapv` (record
  bound to `r#`) and the dispatch result (epoch bound to `e#`)."
  [incl?]
  [(egress/project-page-src "page" incl?)
   (egress/project-dispatch-result-src "(rf/dispatch-and-collect)" incl?)])

(defn- find-form
  "Depth-first: the first sub-form of `form` whose head is `head`."
  [head form]
  (cond
    (and (seq? form) (= head (first form))) form
    (coll? form)                            (some #(find-form head %) (seq form))
    :else                                   nil))

(defn- eval-kind-pred
  "Interpret the guard predicate READ out of the rendered source against
  `record`. Handles exactly the node shapes the guard renders and throws on
  anything else, so a rewritten guard fails loudly rather than passing on an
  unexercised predicate."
  [form record]
  (cond
    (seq? form)
    (let [op   (first form)
          args (rest form)]
      (cond
        (= '= op)    (apply = (map #(eval-kind-pred % record) args))
        (= :kind op) (:kind (eval-kind-pred (first args) record))
        :else        (throw (ex-info "unhandled op in rendered guard predicate"
                                     {:op op :form form}))))
    (keyword? form) form
    (symbol? form)  record
    :else           (throw (ex-info "unhandled node in rendered guard predicate"
                                    {:form form}))))

(defn- guard-of
  "Read `src` and return its `when-not` guard form."
  [src]
  (find-form 'when-not (reader/read-string src)))

(deftest the-guard-throws-before-the-door-at-both-render-sites
  ;; A guard that fires only once the record has been projected has leaked.
  (doseq [incl? [false true]
          [src sym] (map vector (sources incl?) ["r#" "e#"])]
    (is (< -1
           (.indexOf src "(throw (ex-info")
           (.indexOf src (str "(re-frame.core/project-egress " sym " {:rf.egress/profile")))
        (str sym ", include-sensitive " incl?))))

(deftest an-absent-epoch-slot-stays-legitimate
  ;; A degraded runtime and the `:ok? false` envelope carry no `:epoch`, so
  ;; the guard must sit inside the presence check or every such dispatch throws.
  (let [when-form (find-form 'when (reader/read-string (second (sources false))))]
    (is (= '(contains? r# :epoch) (second when-form)))
    (is (some? (find-form 'when-not when-form)))))

(deftest the-thrown-ex-info-carries-the-discriminator
  ;; Both slots the error envelope reads, so an agent can branch on the skew.
  (doseq [src (sources false)]
    (let [[_ _ [op [ctor _ data]]] (guard-of src)]
      (is (= ['throw 'ex-info] [op ctor]))
      (is (= [egress/unstamped-epoch-record-error-id egress/unstamped-epoch-record-error-id]
             ((juxt :rf.error/id :reason) data))))))

(deftest the-rendered-guard-passes-a-stamped-record-and-throws-on-an-unstamped-one
  ;; `when-not` throws when the predicate is FALSE. The other-kind row fails
  ;; a guard that only checks that SOME `:kind` is present.
  (doseq [src (concat (sources false) (sources true))]
    (let [pred (second (guard-of src))]
      (is (true? (eval-kind-pred pred stamped)))
      (is (false? (eval-kind-pred pred unstamped)))
      (is (false? (eval-kind-pred pred {:kind :rf.observe/handled-event}))))))
