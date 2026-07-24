(ns re-frame.freehand.compiled-source-delta-jvm-test
  "The other half of the promotion proof, read off the SOURCE.

  `compiled-promotion-cljs-test` proves the twins produce the same trees.
  That is only worth something if the twins really are the same
  declarations — a reviewer has no way to check 'and nothing else
  changed' by eye across two files, and a proof that depends on nobody
  quietly adjusting the compiled copy is not a proof.

  So the two files are read and their declarations diffed
  mechanically. Deleting `{:compiled true}` from each compiled
  declaration must yield the interpreted declaration EXACTLY: same
  docstring, same parameter vector, same body, same order. If promotion
  ever needed one more edit than the marker, this test is where the
  design claim fails, loudly, instead of quietly becoming untrue."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]))

(defn- read-forms
  "Every top-level form of a classpath resource. Read, not evaluated —
  the subject here is the text an author wrote."
  [resource]
  (let [url (io/resource resource)]
    (assert url (str "source not on the classpath: " resource))
    (let [r (rt/indexing-push-back-reader (slurp url))]
      (loop [acc []]
        (let [form (rdr/read {:eof ::eof :read-cond :allow :features #{:clj}} r)]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(defn- defviews [resource]
  (filterv #(and (seq? %) (= 'v/defview (first %))) (read-forms resource)))

(defn- strip-compiled-marker
  "Remove the compiled marker from a declaration, and assert it is the
  WHOLE options map. A twin carrying `{:compiled true :something-else …}`
  would be a second edit wearing the marker's clothes."
  [form]
  (let [[head sym & more] form
        [docstring more]  (if (string? (first more))
                            [(first more) (rest more)]
                            [nil more])
        opts              (first more)]
    (is (= {:compiled true} opts)
        (str sym ": the options map is exactly the compiled marker"))
    (concat [head sym] (when docstring [docstring]) (rest more))))

(def ^:private twin-files
  "Every interpreted/compiled twin PAIR whose sameness this suite holds
  down. A table, because the claim is about the relationship and not
  about one fixture: B1's measurement arms need it as much as the
  `FH-STRUCT` fixtures do — a timing comparison between two arms that are
  not the same template is a comparison of two templates."
  [{:what        "the FH-STRUCT fixture views"
    :interpreted "re_frame/freehand/tree_views.cljc"
    :compiled    "re_frame/freehand/compiled_views.cljc"}
   {:what        "the B1 measurement arms"
    :interpreted "re_frame/freehand/bench/b1/interpreted.cljc"
    :compiled    "re_frame/freehand/bench/b1/compiled.cljc"}
   {:what        "the B2 mount-storm roster"
    :interpreted "re_frame/freehand/bench/b2/interpreted.cljc"
    :compiled    "re_frame/freehand/bench/b2.cljc"}
   {:what        "the B4 edited surfaces"
    :interpreted "re_frame/freehand/bench/b4_views.cljc"
    :compiled    "re_frame/freehand/bench/b4_views_compiled.cljc"}])

(deftest promotion-is-exactly-one-option-map
  (testing "Every compiled twin is its interpreted original plus
            `{:compiled true}`, and nothing else — proven by deleting the
            marker and comparing the declarations as read."
    (doseq [{:keys [what] i-file :interpreted c-file :compiled} twin-files]
      (let [interpreted (defviews i-file)
            compiled    (defviews c-file)]
        (is (seq interpreted) (str what ": the interpreted declarations were read"))
        (is (= (count interpreted) (count compiled))
            (str what ": the two files declare the same number of views"))
        (doseq [[i c] (map vector interpreted compiled)]
          (is (= i (strip-compiled-marker c))
              (str what " / " (second i)
                   ": promotion added the marker and changed nothing else")))))))

(deftest the-diff-would-notice-a-second-edit
  (testing "A comparison that cannot fail proves nothing. An altered
            declaration must be caught by the same equality the suite
            trusts."
    (let [[panel] (defviews "re_frame/freehand/tree_views.cljc")
          tampered (concat (take 2 panel) ["a different docstring"] (drop 3 panel))]
      (is (not= panel tampered)
          "changing a declaration's text changes the form the diff compares"))))
