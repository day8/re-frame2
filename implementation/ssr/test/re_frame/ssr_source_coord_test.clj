(ns re-frame.ssr-source-coord-test
  "Server-side (JVM) dev-mode view annotation at the reg-view REGISTRATION
  boundary. A registered view reached through its callable head —
  `[(rf/view :id) …]` or a Var — renders with `data-rf2-source-coord`
  (Spec 006 §Source-coord annotation) and `data-rf-view` (Spec 006 §View
  tagging contract), the two attributes the dev client stamps, so a dev SSR
  page hydrates as a clean adoption. There is no emitter-side injection: the
  wrapper sits on the registered `:handler-fn`
  (`re-frame.views.jvm-source-coord-annotation`).

  The annotations are DEV-ONLY: the wrapper is installed behind
  `interop/debug-enabled?`, read once at namespace load, so under
  `-Dre-frame.debug=false` no view is wrapped. Each render below is pinned to
  its exact bytes in BOTH postures — annotated in dev, bare under the gate —
  so this namespace also runs in `scripts/test-ssr-prod-gate.sh`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.views.jvm-source-coord-annotation :as rf.views.jvm-source-coord-annotation]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- by-posture
  "The expected markup: `dev` when the annotation wrapper is installed, the
  bare `prod` markup under the production gate."
  [dev prod]
  (if rf.interop/debug-enabled? dev prod))

(deftest coordless-programmatic-registration-degrades-to-question-marks
  (testing "A `reg-view*` with no macro-captured coords still annotates,
            degrading the source-coord to <ns>:<sym>:?:? as the client does"
    (rf/reg-view* :ssr-coord-test/prog {} (fn [] [:div "prog"]))
    (is (= (by-posture (str "<div data-rf2-source-coord=\"ssr-coord-test:prog:?:?\""
                            " data-rf-view=\":ssr-coord-test/prog\">prog</div>")
                       "<div>prog</div>")
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/prog)] {})))))

(deftest author-supplied-annotation-values-are-preserved
  (testing "An author who set data-rf-view on the root keeps THEIR value; the
            wrapper only fills a missing key, as the client
            `inject-source-coord-attr` does"
    (rf/reg-view* :ssr-coord-test/authored {}
                  (fn [] [:div {:data-rf-view "author-owned"
                                :id           "x"} "a"]))
    (is (= (by-posture (str "<div data-rf-view=\"author-owned\" id=\"x\""
                            " data-rf2-source-coord=\"ssr-coord-test:authored:?:?\">a</div>")
                       "<div data-rf-view=\"author-owned\" id=\"x\">a</div>")
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/authored)] {})))))

(deftest form-2-view-inner-output-is-annotated
  (testing "A Form-2 view has its INNER output annotated, mirroring the
            client Form-2 wrapper"
    (rf/reg-view* :ssr-coord-test/form2 {}
                  (fn [] (fn [] [:section "inner"])))
    (is (= (by-posture (str "<section data-rf2-source-coord=\"ssr-coord-test:form2:?:?\""
                            " data-rf-view=\":ssr-coord-test/form2\">inner</section>")
                       "<section>inner</section>")
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/form2)] {})))))

(deftest fragment-root-is-not-annotated
  (testing "A fragment `:<>` root is a non-DOM root the wrapper skips, so its
            children emit unwrapped"
    (rf/reg-view* :ssr-coord-test/frag {}
                  (fn [] [:<> [:p "a"] [:p "b"]]))
    (is (= "<p>a</p><p>b</p>"
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/frag)] {})))))

(deftest nested-view-ref-root-is-not-doubly-annotated
  (testing "A view whose root is another view-ref skips at the outer wrapper;
            the inner view annotates its own root, once"
    (rf/reg-view* :ssr-coord-test/inner {} (fn [] [:span "in"]))
    (rf/reg-view* :ssr-coord-test/outer {}
                  (fn [] [(rf/view :ssr-coord-test/inner)]))
    (is (= (by-posture (str "<span data-rf2-source-coord=\"ssr-coord-test:inner:?:?\""
                            " data-rf-view=\":ssr-coord-test/inner\">in</span>")
                       "<span>in</span>")
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/outer)] {})))))

(deftest interop-head-roots-are-returned-untouched
  (testing "Reagent's `:r>` and `:f>` interop heads carry the COMPONENT at
            position 1, the slot a DOM root's attrs map is spliced into, so the
            walk passes them through untouched, like `:>`"
    ;; `annotate-root` is not itself gated on `interop/debug-enabled?`, so
    ;; this means the same thing in both postures.
    (let [comp-fn  (fn [& _] nil)
          annotate #(rf.views.jvm-source-coord-annotation/annotate-root
                      :ssr-coord-test/interop "ssr-coord-test:interop:1:1"
                      ":ssr-coord-test/interop" %)]
      (doseq [out [[:f> comp-fn "arg"]
                   [:r> comp-fn {:a 1} [:child]]
                   [:> comp-fn {:a 1}]]]
        (is (identical? out (annotate out))
            (str (first out) " root must come back untouched, component still at position 1")))
      (is (= [:div {:data-rf2-source-coord "ssr-coord-test:interop:1:1"
                    :data-rf-view          ":ssr-coord-test/interop"} "hi"]
             (annotate [:div "hi"]))
          "control: a DOM-tag root IS annotated by the same call"))))

(deftest streaming-shell-annotates-through-the-wrapped-handler
  (testing "The streaming walker resolves callable heads through the SAME
            wrapped handler-fn, so a streamed shell matches the sync render
            byte for byte in either posture"
    (rf/reg-view ^{:rf/id :ssr-coord-test/shell} shell-view []
      [:main "shell"])
    (let [{:keys [shell-html]} (rf.ssr.streaming/render-shell [shell-view])]
      (is (= (rf.ssr/render-to-string [shell-view] {}) shell-html))
      (is (if rf.interop/debug-enabled?
            (re-matches #"<main data-rf2-source-coord=\"[^\"]+\" data-rf-view=\":ssr-coord-test/shell\">shell</main>"
                        shell-html)
            (= "<main>shell</main>" shell-html))
          (pr-str shell-html)))))

;; The gate is read at REGISTRATION time, as the client `make-wrap-view`
;; decides whether to wrap when the view is registered, so the production arm
;; must REGISTER under a false `interop/debug-enabled?`.

(deftest production-build-emits-no-annotation
  (testing "A view REGISTERED under `interop/debug-enabled?` false stores an
            unwrapped handler-fn, so its markup carries neither annotation"
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/reg-view* :ssr-coord-test/gated-prod {} (fn [] [:h2 "g"])))
    (is (= "<h2>g</h2>"
           (rf.ssr/render-to-string [(rf/view :ssr-coord-test/gated-prod)] {}))))

  ;; `with-redefs` rebinds the Var after the framework has loaded, which a
  ;; load-time gate never sees; this arm runs only on a JVM actually started
  ;; with `-Dre-frame.debug=false`.
  (when-not rf.interop/debug-enabled?
    (testing "-Dre-frame.debug=false on the JVM — NOT annotated, for real"
      (rf/reg-view* :ssr-coord-test/real-gate {} (fn [] [:h2 "g"]))
      (is (= "<h2>g</h2>"
             (rf.ssr/render-to-string [(rf/view :ssr-coord-test/real-gate)] {})))
      (rf/reg-view ^{:rf/id :ssr-coord-test/real-gate-macro} real-gate-macro []
        [:h3 "m"])
      (is (= "<h3>m</h3>" (rf.ssr/render-to-string [real-gate-macro] {}))
          "including on the macro path, where the coords would come from"))))
