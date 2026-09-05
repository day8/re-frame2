(ns re-frame.schemas-record-type-tag-cljs-test
  "CLJS half of the closed type-tag contract (rf2-xpd8, audit of PR #9208) —
  and the half where the pre-fix behaviour was a DISCLOSURE rather than merely
  an unbounded string.

  `cljs.core/type` is defined as `(.-constructor x)` — literally, docstring
  \"Return x's constructor\". `constructor` is an ordinary WRITABLE property
  name, so a foreign JS value carrying its own `constructor` field makes
  `(str (type v))` return that field's text verbatim. `type-of-value`'s
  fallback arm is exactly `(str (type v))`, and PR1's
  `emit-app-db-rejection-record!` concatenated it after `\"got \"` and
  published the result to the corpus-wide `:errors` listener registry.

  So the record whose entire justification is that it is CLOSED-SHAPE — no
  `:value`, no `:explain`, no `:schema`, no `:path`, every slot a framework
  keyword or a structural id — could carry arbitrary app- or
  attacker-controlled text through the one slot nobody was watching. PR1's
  own tests exercised `nil` and a ClojureScript string only, both of which
  take fast-path arms, so the fallback was unpinned.

  These tests plant a sentinel and assert it reaches NEITHER the tag NOR the
  emitted record. The JVM sibling
  (`re-frame.schemas-record-type-tag-test`) pins the same classifier's
  closedness host-agnostically; it cannot express this file's cases, because
  the JVM's `type` is `(class v)` and takes no instruction from the value."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.validate :as rf.schemas.validate]))

(def ^:private tag #'rf.schemas.validate/record-type-tag)

(def ^:private sentinel "rf2-xpd8-secret-from-value")

(def ^:private closed-vocabulary
  #{"nil" "string" "integer" "number" "boolean" "keyword" "map" "vector"
    "object"})

(defn- planted-constructor-obj
  "A foreign JS object carrying its own `constructor` field — the exact shape
  the audit measured returning the sentinel through `(str (type v))`."
  []
  (let [o (js-obj)]
    (aset o "constructor" sentinel)
    o))

(defn- hostile-proxy
  "A Proxy whose `get` trap THROWS. `map?` / `vector?` are protocol lookups,
  i.e. property GETs, so a classifier that reaches them can invoke this."
  []
  (js/Proxy. (js-obj)
             #js {:get (fn [_ k]
                         (throw (js/Error. (str "hostile accessor fired: " k))))}))

;; ---- the mechanism itself -------------------------------------------------

(deftest cljs-type-really-is-the-values-own-constructor
  (testing "the premise, asserted rather than assumed: an own `constructor`
            field wins, so `(str (type v))` IS caller-controlled on CLJS.
            If this ever stops holding, the fix below is still correct but
            this file's rationale needs rewriting rather than deleting."
    (let [o (planted-constructor-obj)]
      (is (= sentinel (str (type o)))
          "cljs.core/type returned the value's OWN constructor field")))

  (testing "and the obvious host-side repairs are value-controlled too —
            which is why the fallback is a constant, not a cleverer lookup"
    (let [tagged (js-obj)]
      (aset tagged js/Symbol.toStringTag "also-attacker-controlled")
      (is (str/includes? (.call (.-toString (.-prototype js/Object)) tagged)
                         "also-attacker-controlled")
          "Object.prototype.toString.call is steered by Symbol.toStringTag"))))

;; ---- the classifier does not disclose -------------------------------------

(deftest tag-never-returns-value-controlled-text
  (testing "rf2-xpd8 audit — a planted own `constructor` classifies to the
            CONSTANT fallback; the sentinel appears nowhere in the tag"
    (let [t (tag (planted-constructor-obj))]
      (is (= "object" t))
      (is (not (str/includes? t sentinel))
          (str "the value's own constructor text reached the type tag; got "
               (pr-str t)))
      (is (contains? closed-vocabulary t)))))

(deftest tag-survives-a-hostile-accessor
  (testing "a diagnostic that explodes while explaining a rejection is the
            rf2-9s68n failure one level up — the throwing Proxy classifies
            rather than propagating"
    (let [t (tag (hostile-proxy))]
      (is (= "object" t))
      (is (contains? closed-vocabulary t)))))

(deftest tag-is-closed-over-foreign-and-native-cljs-values
  (testing "every arm yields a framework literal — a masquerading value gets
            a WRONG tag, never caller text, which is the property that matters"
    (doseq [v [nil "s" 1 1.5 true false :kw 'sym {} {:a 1} [] [1 2]
               #{1 2} '(1 2) (range 3)
               (js-obj) #js [] (js/Date.) (fn [] nil)
               (planted-constructor-obj) (hostile-proxy)
               js/Math js/JSON]]
      (let [t (tag v)]
        (is (contains? closed-vocabulary t)
            (str "tag escaped the closed vocabulary — got " (pr-str t)))
        (is (not (str/includes? t sentinel)))))))

;; ---- end to end: the emitted record does not disclose ---------------------

(deftest ^:requires-debug rejection-record-never-carries-the-planted-constructor
  (testing "rf2-xpd8 audit, END TO END — a rejected app-db candidate whose
            failing leaf is a foreign JS object with a planted `constructor`
            emits a record carrying the sentinel NOWHERE: not in `:reason`,
            not in any other slot. Pre-fix the `:reason` read
            `got rf2-xpd8-secret-from-value`."
    (when rf.interop/debug-enabled?
      (let [records (atom [])]
        (rf/register-listener! :errors ::rec (fn [r] (swap! records conj r)))
        (try
          (rf/reg-app-schema [:tenant] [:map [:id :int]])
          (rf.schemas/validate-app-schema!
            {:tenant {:id (planted-constructor-obj)}}
            :tenant/set-bad)
          (finally
            (rf/unregister-listener! :errors ::rec)))
        (let [rejections (filterv
                           #(and (= :rf.error/schema-validation-failure (:error %))
                                 (= :app-db (:where %)))
                           @records)]
          (is (= 1 (count rejections))
              (str "expected one :errors record for the rejected candidate; got "
                   (count rejections)))
          (let [r      (first rejections)
                reason (:reason r)]
            (is (str/includes? reason "got object")
                (str "the reason carries the constant fallback tag; got "
                     (pr-str reason)))
            (is (not (str/includes? reason sentinel))
                (str "THE LEAK: the failing value's own `constructor` text "
                     "reached the always-on :errors stream; got "
                     (pr-str reason)))
            (is (not (str/includes? (pr-str r) sentinel))
                (str "the sentinel reached some OTHER slot of the record; got "
                     (pr-str r)))))))))
