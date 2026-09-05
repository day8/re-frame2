(ns re-frame.schemas-record-type-tag-test
  "The always-on `:errors` record's type tag is a CLOSED vocabulary
  (rf2-xpd8, audit of PR #9208).

  PR1 gave a rejected app-db candidate a structural-only record on the
  always-on `:errors` stream. Its `:reason` was composed rather than copied
  precisely so the failing VALUE could not ride it — every other value-bearing
  slot (`:value`, `:explain`, `:schema`, `:path`) is omitted outright for that
  reason. But the reason's type tag was `re-frame.error/type-of-value`, whose
  fallback arm is `(str (type v))`, and that arm is NOT closed:

    - On CLJS it is a DISCLOSURE. `cljs.core/type` is defined as
      `(.-constructor x)` — an ordinary writable property — so a foreign JS
      value carrying its own `constructor` field returns that field's text
      verbatim, straight onto the corpus-wide `:errors` listener registry.
      That half is pinned by the CLJS sibling
      (`re-frame.schemas-record-type-tag-cljs-test`), which plants a sentinel
      and a hostile Proxy; it cannot be written here because the JVM's `type`
      is `(class v)` and takes no instruction from the value.

    - On BOTH hosts it is UNBOUNDED — host class names are not a vocabulary,
      they are whatever the runtime happens to call the class. That half is
      what this namespace pins, and it is the structural property the CLJS
      leak violated: the record's reason carries framework literals ONLY,
      never text derived from the value's own class or constructor.

  So these tests are the host-agnostic control. They fail against the
  pre-fix code: a set-valued failing leaf put `clojure.lang.PersistentHashSet`
  into a record that is supposed to be closed-shape.

  Deliberately NOT asserted here: the eight named tags' spellings for the
  ordinary shapes are `error/type-of-value`'s existing documented vocabulary
  and are pinned by the PR1 tests already (`got string`, `got nil`). This
  namespace owns the NINTH arm — the constant fallback — and the guarantee
  that nothing else can appear."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.schemas.validate :as validate]))

(use-fixtures :each tf/reset-runtime)

(def ^:private tag #'validate/record-type-tag)

(def ^:private closed-vocabulary
  "Every string `record-type-tag` is permitted to return. The record's
  closed-shape guarantee is exactly this set being exhaustive."
  #{"nil" "string" "integer" "number" "boolean" "keyword" "map" "vector"
    "object"})

(defn- capture-errors
  "Run `body-fn` with a listener on the always-on `:errors` stream and return
  the captured records. Unregisters in a `finally` so a thrown body cannot
  leak the listener into the next deftest and silently invert its counts."
  [body-fn]
  (let [errors (atom [])]
    (rf/register-listener! :errors ::rec (fn [r] (swap! errors conj r)))
    (try
      (body-fn)
      (finally
        (rf/unregister-listener! :errors ::rec)))
    @errors))

(defn- rejection-records [records]
  (filterv #(and (= :rf.error/schema-validation-failure (:error %))
                 (= :app-db (:where %)))
           records))

;; ---- the classifier is total and closed ----------------------------------

(deftest tag-is-drawn-from-the-closed-vocabulary
  (testing "every shape a failing app-db leaf can take classifies to one of
            the nine literals — nothing else is reachable"
    (doseq [v [nil "s" 1 1.5 3/4 true false :kw 'sym
               {} {:a 1} [] [1 2] #{1 2} '(1 2) (range 3)
               (java.util.Date.) (Object.) (java.util.HashMap.)
               (byte-array 2) #"re" (fn [] nil) \c
               (java.net.URI. "https://example.com/secret-path")]]
      (is (contains? closed-vocabulary (tag v))
          (str "tag escaped the closed vocabulary for " (pr-str (class v))
               " — got " (pr-str (tag v)))))))

(deftest fallback-is-a-constant-never-the-host-class-name
  (testing "rf2-xpd8 audit — the fallback arm returns the literal \"object\",
            NOT `(str (type v))`. This is the arm the CLJS `constructor`
            disclosure came through; on the JVM the same arm is merely
            unbounded, and both are closed by the same constant."
    (doseq [v [#{:a} '(1) (Object.) (java.util.Date.) (java.net.URI. "x:y")]]
      (is (= "object" (tag v))
          (str "expected the constant fallback for " (pr-str (class v))))
      (is (not (str/includes? (tag v) "class"))
          "no `class ` prefix — that is `(str (type v))` leaking through")
      (is (not (str/includes? (tag v) (.getName (class v))))
          "the host class name never appears in the tag"))))

(deftest tag-never-throws
  (testing "a diagnostic that explodes while explaining a rejection is the
            rf2-9s68n failure one level up. The protocol arms (`map?`,
            `vector?`) are property reads, and on CLJS a Proxy get-trap can
            raise there — the cond is wrapped for that. Nothing on the JVM
            reaches the catch, so this pins totality rather than the catch
            arm; the CLJS sibling exercises the throwing path directly."
    (doseq [v [nil (Object.) (reify clojure.lang.IDeref (deref [_] nil))]]
      (is (string? (tag v))
          "returns a string for every input, never propagates"))))

;; ---- end-to-end: the emitted record carries no host class name ------------

(deftest rejection-record-reason-carries-no-host-class-name
  (testing "rf2-xpd8 audit, END TO END — a rejected candidate whose failing
            leaf is outside the eight-tag vocabulary emits a record whose
            :reason says `got object`, never the leaf's class name. Pre-fix
            this reason read `got class clojure.lang.PersistentHashSet`."
    (when interop/debug-enabled?
      (rf/reg-app-schema [:tenant] [:map [:id :int]])
      (let [records (rejection-records
                      (capture-errors
                        #(schemas/validate-app-schema!
                           {:tenant {:id #{:not :an :int}}}
                           :tenant/set-bad)))]
        (is (= 1 (count records))
            (str "expected one :errors record for the rejected candidate; got "
                 (count records) " — " (pr-str records)))
        (let [reason (:reason (first records))]
          (is (str/includes? reason "got object")
              (str "the reason carries the constant fallback tag; got "
                   (pr-str reason)))
          (is (not (str/includes? reason "PersistentHashSet"))
              (str "the failing leaf's CLASS NAME reached a record whose whole "
                   "contract is that it is built from framework literals and "
                   "structural ids; got " (pr-str reason)))
          (is (not (str/includes? reason "clojure.lang"))
              "no host package name on a closed-shape record")
          (is (str/includes? reason "[:tenant]")
              "the reason still names its registered path — the tag change
               narrows the vocabulary, it does not drop the locator"))))))

(deftest rejection-record-keeps-the-eight-named-tags
  (testing "the fix REPLACES the unbounded fallback only — the documented
            vocabulary the PR1 tests pin (`got nil`, `got string`) is
            unchanged, so no consumer learns a new word"
    (when interop/debug-enabled?
      (rf/reg-app-schema [:acct] [:map [:n :int]])
      (let [reason (fn [db]
                     (-> (rejection-records
                           (capture-errors
                             #(schemas/validate-app-schema! db :acct/bad)))
                         first
                         :reason))]
        (is (str/includes? (reason {:acct {:n "text"}}) "got string"))
        (is (str/includes? (reason {:acct {:n nil}})  "got nil"))))))
