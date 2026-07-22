(ns re-frame.freehand.build-repl-hmr-convergence-jvm-test
  "S2e (rf2-vxgfnd.11) — REPL == file-save reload at the compiler-state
  authority (03 §10; 02 §8; df9873's ruled posture). A view re-registration
  reaches the build authority two ways:

    - a shadow FILE-SAVE watch pass: `begin-build!` opens the pass, the
      re-macroexpansion STAGES the contribution, `commit-build!` /
      `finish-build!` publishes it (build hooks fire only on a real compile —
      #5777);
    - a REPL re-eval: NO build hook fires, so no pass opens and the
      contribution UPSERTS one key straight into committed (the ruled REPL
      posture, build.cljc).

  Both paths must converge on the IDENTICAL committed `:views` aggregate for the
  same source — otherwise a REPL session and a saved file could diverge. This
  fixture drives the REAL `defview` macro body (the JVM emitter path) under a
  controlled declaring namespace, exactly as `re-frame.freehand.compiler-build-jvm-test`
  does, and compares the two committed aggregates."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.build :as build]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

(defn- declare-view!
  "Run the real `defview` macro body (JVM emitter path) with `ns-sym` bound as
  the declaring namespace — contributes `[template-fp hook-sig]` under the
  derived view-id, owned by `ns-sym`, into the ambient build's `views`
  registry."
  [ns-sym vname template]
  (binding [*ns* (create-ns ns-sym)]
    (compiler/defview* (with-meta (list 'defview vname [] template) {:line 1})
                       {} vname (list [] template))))

(deftest repl-upsert-and-file-save-pass-converge-on-identical-committed-views
  (let [declare! (fn [] (declare-view! 'hmr.conv.view 'widget
                                       [:section [:h1 "title"] [:p "body"]]))
        ;; REPL path: no pass open ⇒ each re-eval UPSERTS straight into committed.
        repl-views (binding [build/*build-id* ::repl]
                     (declare!)                                   ; initial eval
                     (declare!)                                   ; a REPL re-eval
                     (build/committed-aggregate build/views ::repl))
        ;; FILE-SAVE path: two watch passes (begin → stage → commit), i.e. an
        ;; initial compile then a reload of the same source.
        file-views (do (build/begin-build! ::file)
                       (binding [build/*build-id* ::file] (declare!))
                       (build/commit-build! ::file)
                       (build/begin-build! ::file)               ; the file-save reload
                       (binding [build/*build-id* ::file] (declare!))
                       (build/commit-build! ::file)
                       (build/committed-aggregate build/views ::file))]
    (is (seq repl-views) "the REPL upsert landed the view in committed (no pass)")
    (is (seq file-views) "the file-save pass published the view in committed")
    (testing "REPL re-eval and file-save reload converge on the IDENTICAL
              committed :views aggregate — one path (03 §10; df9873)"
      (is (= repl-views file-views)))))

(deftest repl-upsert-reregistration-replaces-in-place-no-sibling-eviction
  ;; The ruled REPL posture: an upsert is PER-KEY into committed — a re-eval
  ;; replaces the same view's row without opening a pass or evicting siblings a
  ;; sibling REPL form contributed.
  (binding [build/*build-id* ::repl2]
    (declare-view! 'hmr.a 'va [:div "a-v1"])
    (declare-view! 'hmr.b 'vb [:div "b-v1"])
    (let [after-two (build/committed-aggregate build/views ::repl2)]
      (is (= 2 (count after-two)) "two distinct views registered via REPL upsert")
      ;; re-eval ONLY va with a new template
      (declare-view! 'hmr.a 'va [:div [:strong "a-v2"]])
      (let [after-reeval (build/committed-aggregate build/views ::repl2)
            ;; the one key that differs is va's — vb is byte-identical, so the
            ;; only entry that moved is va's [tf hs] row (upsert replaced in place)
            changed (filterv (fn [[k v]] (not= v (get after-two k))) after-reeval)]
        (is (= 2 (count after-reeval)) "vb survives va's re-eval — no sibling eviction")
        (is (= (dissoc after-reeval (ffirst changed))
               (dissoc after-two (ffirst changed)))
            "every OTHER view row is untouched — no sibling eviction on upsert")
        (is (= 1 (count changed))
            "exactly va's row moved to the new template's [tf hs] (upsert in place)")))))
