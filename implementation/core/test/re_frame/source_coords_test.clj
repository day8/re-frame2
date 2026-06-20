(ns re-frame.source-coords-test
  "Per Spec 001 §Source-coordinate capture and Tool-Pair §Source-mapping:
  every reg-* registration's metadata carries :ns / :line / :file
  auto-supplied at compile time. This test registers one handler per
  registry kind via the public re-frame.core macro surface and asserts
  the resulting handler-meta carries non-nil :ns / :line / :file (rf2-k84s).

  The capture mechanism (see re-frame.source-coords) wraps each public
  reg-* macro at the re-frame.core boundary. (meta &form) supplies
  :line / :column; *ns* / *file* supply the namespace symbol and source
  filename. The macro binds re-frame.source-coords/*pending-coords*
  around the underlying registration fn, which merges the coords into
  the registry slot's metadata.

  Verification is JVM-only here because the source-coord-capture
  *macro* path is JVM-side per the current commit (CLJS source-coord
  capture rides a future re-frame.core-macros companion ns; the
  underlying merge mechanism in source-coords/merge-coords is shared).

  Coverage matches Spec 001 §Per-kind index and the bead's deliverable
  (`reg-event` is the ONE event-registration form per EP-0018; the
  three event rows below exercise its {:db ...} return, its effect-map
  return, and its interceptor-carrying shape):
    reg-event ({:db}) reg-event (fx) reg-event (interceptor)
    reg-sub        reg-fx         reg-cofx
    reg-frame      reg-view       reg-machine
    reg-flow       reg-route      reg-app-schema
    reg-error-projector"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-quir9 — exercise the view-macro expander directly to
            ;; assert the reader's symbol-position meta is stripped before
            ;; it can clobber the absolutised *pending-coords*.
            [re-frame.core-reg-view-macro :as rvm]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            ;; rf2-wvsxg — direct access to source-coords helpers + the
            ;; URI builder's compose-path predicate for the absolutise-
            ;; file regression coverage.
            [re-frame.source-coords :as sc]
            [re-frame.source-coords.editor-uri :as eu]
            [re-frame.substrate.plain-atom :as plain-atom]
            ;; rf2-st3ax4 — build a throwaway `+`-bearing classpath root to
            ;; assert absolutise-file preserves a literal `+` in the path.
            [clojure.java.io :as io])
  (:import [java.net URL URLClassLoader]
           [java.io File]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- shared assertion helper ---------------------------------------------

(defn- assert-coords [meta kind id]
  (is (some? meta) (str "handler-meta for " kind " " id " should be present"))
  (is (some? (:ns meta))
      (str "handler-meta for " kind " " id " should carry :ns"))
  (is (some? (:line meta))
      (str "handler-meta for " kind " " id " should carry :line"))
  (is (some? (:file meta))
      (str "handler-meta for " kind " " id " should carry :file"))
  ;; :ns is a symbol per Spec 001 §The metadata map.
  (is (symbol? (:ns meta))
      (str "handler-meta for " kind " " id " :ns should be a symbol"))
  ;; :line is an integer per Spec 001.
  (is (integer? (:line meta))
      (str "handler-meta for " kind " " id " :line should be an integer"))
  ;; :file is a string per Spec 001.
  (is (string? (:file meta))
      (str "handler-meta for " kind " " id " :file should be a string")))

;; ---- one assertion per reg-* kind ----------------------------------------

(deftest source-coords-on-reg-event
  (testing "EP-0018 C (rf2-xhfxcs.3): the ONE public `reg-event` macro stamps
  :ns / :line / :file — the same compile-time source-coord capture the legacy
  reg-event-{db,fx,ctx} macros do (consolidated macro layer: `reg-event` rides
  the identical defreg-event-macro coord-capture skeleton)"
    (rf/reg-event :rf2-k84s/reg-event-sample
                  (fn [{:keys [db]} _] {:db db}))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-sample)
                   :event :rf2-k84s/reg-event-sample)))

(deftest source-coords-on-reg-event-db-return
  (testing "reg-event with a {:db ...} return stamps :ns / :line / :file"
    (rf/reg-event :rf2-k84s/reg-event-db-sample
                     (fn [{:keys [db]} _] {:db db}))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-db-sample)
                   :event :rf2-k84s/reg-event-db-sample)))

(deftest source-coords-on-reg-event-fx-return
  (testing "reg-event with an effect-map return stamps :ns / :line / :file"
    (rf/reg-event :rf2-k84s/reg-event-fx-sample
                     (fn [_ _] {}))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-fx-sample)
                   :event :rf2-k84s/reg-event-fx-sample)))

(deftest source-coords-on-reg-event-with-interceptor
  (testing "reg-event with a full-context interceptor stamps :ns / :line / :file"
    (rf/reg-interceptor* :rf2-k84s/ctx-probe {:before (fn [ctx] ctx)})
    (rf/reg-event :rf2-k84s/reg-event-ctx-sample
                  {:interceptors [:rf2-k84s/ctx-probe]}
                  (fn [_ _] {}))
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-event-ctx-sample)
                   :event :rf2-k84s/reg-event-ctx-sample)))

(deftest source-coords-on-reg-sub
  (testing "reg-sub stamps :ns / :line / :file"
    (rf/reg-sub :rf2-k84s/reg-sub-sample
                (fn [db _] db))
    (assert-coords (rf/handler-meta :sub :rf2-k84s/reg-sub-sample)
                   :sub :rf2-k84s/reg-sub-sample)))

(deftest source-coords-on-reg-fx
  (testing "reg-fx stamps :ns / :line / :file"
    (rf/reg-fx :rf2-k84s/reg-fx-sample
               (fn [_ _] nil))
    (assert-coords (rf/handler-meta :fx :rf2-k84s/reg-fx-sample)
                   :fx :rf2-k84s/reg-fx-sample)))

(deftest source-coords-on-reg-cofx
  (testing "reg-cofx stamps :ns / :line / :file"
    (rf/reg-cofx :rf2-k84s/reg-cofx-sample
                 (fn [] :sample))
    (assert-coords (rf/handler-meta :cofx :rf2-k84s/reg-cofx-sample)
                   :cofx :rf2-k84s/reg-cofx-sample)))

(deftest source-coords-on-reg-frame
  (testing "reg-frame stamps :ns / :line / :file"
    (rf/reg-frame :rf2-k84s/reg-frame-sample {:doc "smoke"})
    (assert-coords (rf/handler-meta :frame :rf2-k84s/reg-frame-sample)
                   :frame :rf2-k84s/reg-frame-sample)))

(deftest source-coords-on-reg-view
  (testing "reg-view stamps :ns / :line / :file"
    ;; Per Spec 004 §reg-view, defn-shape with explicit id-meta override.
    ;; ^{:rf/id ...} keeps the legacy keyword for the assertion.
    (rf/reg-view ^{:rf/id :rf2-k84s/reg-view-sample} reg-view-sample []
      [:div "hi"])
    (assert-coords (rf/handler-meta :view :rf2-k84s/reg-view-sample)
                   :view :rf2-k84s/reg-view-sample)))

(deftest source-coords-on-reg-machine
  (testing "reg-machine stamps :ns / :line / :file (under :event kind, since
  reg-machine wraps the machine as an event handler per Spec 005 §Registration)"
    (rf/reg-machine :rf2-k84s/reg-machine-sample
                    {:initial :a :states {:a {} :b {}}})
    (assert-coords (rf/handler-meta :event :rf2-k84s/reg-machine-sample)
                   :event :rf2-k84s/reg-machine-sample)))

(deftest source-coords-on-reg-flow
  (testing "reg-flow stamps :ns / :line / :file"
    (rf/reg-flow {:id     :rf2-k84s/reg-flow-sample
                  :inputs [[:source]]
                  :output (fn [v] v)
                  :path   [:dest]})
    (assert-coords (rf/handler-meta :flow :rf2-k84s/reg-flow-sample)
                   :flow :rf2-k84s/reg-flow-sample)))

(deftest source-coords-on-reg-route
  (testing "reg-route stamps :ns / :line / :file"
    (rf/reg-route :rf2-k84s/reg-route-sample {} "/k84s")
    (assert-coords (rf/handler-meta :route :rf2-k84s/reg-route-sample)
                   :route :rf2-k84s/reg-route-sample)))

(deftest source-coords-on-reg-app-schema
  (testing "reg-app-schema stamps :ns / :line / :file"
    ;; Per rf2-0frdi / rf2-cq1ak the schemas artefact owns its own per-
    ;; frame side-table — app-db schemas are NOT a registrar kind.
    ;; Source-coords introspection reads through `schemas/app-schema-
    ;; meta-at` which returns the full meta map (including the stamped
    ;; coords) for the `(frame-id, path)` entry.
    (rf/reg-app-schema [:rf2-k84s/reg-app-schema-sample] {:schema :int})
    (assert-coords (schemas/app-schema-meta-at [:rf2-k84s/reg-app-schema-sample])
                   "app-schema" [:rf2-k84s/reg-app-schema-sample])))

(deftest source-coords-on-reg-error-projector
  (testing "reg-error-projector stamps :ns / :line / :file"
    (rf/reg-error-projector :rf2-k84s/reg-error-projector-sample
                            (fn [_]
                              {:status     500
                               :code       :internal-error
                               :message    "x"
                               :retryable? false}))
    (assert-coords (rf/handler-meta :error-projector
                                    :rf2-k84s/reg-error-projector-sample)
                   :error-projector :rf2-k84s/reg-error-projector-sample)))

;; ---- user-supplied :ns / :line / :file override auto-capture --------------

(deftest user-supplied-coords-win
  (testing "explicit :ns / :line / :file in user metadata override auto-capture"
    (rf/reg-event :rf2-k84s/explicit-coords
                     {:ns 'my.ns :line 42 :file "elsewhere.cljc"
                      :doc "hand-stamped coords from a code-gen pass"}
                     (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :rf2-k84s/explicit-coords)]
      (is (= 'my.ns                  (:ns meta)))
      (is (= 42                      (:line meta)))
      (is (= "elsewhere.cljc"        (:file meta)))
      (is (= "hand-stamped coords from a code-gen pass" (:doc meta))))))

;; ---- programmatic call (bypasses macro) -----------------------------------

(deftest fn-form-call-skips-coord-capture
  (testing "calling the underlying fn directly skips coord capture
  (so programmatic / fixture-synthesised registrations don't carry
  meaningless coords from inside the framework)"
    (let [reg-fn (requiring-resolve 're-frame.subs/reg-sub)]
      (reg-fn :rf2-k84s/no-coords (fn [db _] db)))
    (let [meta (rf/handler-meta :sub :rf2-k84s/no-coords)]
      (is (some? meta))
      (is (nil? (:ns   meta)) ":ns absent on direct fn call")
      (is (nil? (:line meta)) ":line absent on direct fn call")
      (is (nil? (:file meta)) ":file absent on direct fn call"))))

;; ---- rf2-wvsxg: :file is absolutised via classpath resolution -------------

(deftest absolutise-file-resolves-classpath-relative-paths
  (testing "rf2-wvsxg: absolutise-file returns absolute on-disk path for a
  classpath-relative source file"
    ;; This very test file lives on the classpath under its
    ;; classpath-relative path; absolutising it must yield a path that
    ;; matches the absolute-path predicate the URI builder uses.
    (let [rel  "re_frame/source_coords_test.clj"
          abs  (#'sc/absolutise-file rel)]
      (is (string? abs))
      (is (not= rel abs)
          "classpath-relative path should resolve to a different (absolute) path")
      ;; The result must look absolute to compose-path so the URI build
      ;; passes it through unchanged. compose-path is private — go
      ;; through the public editor-uri's 3-arg form (which calls
      ;; compose-path internally) by checking the URI carries the
      ;; absolute path without the project-root prepended.
      (let [uri (eu/editor-uri :vscode {:file abs :line 1 :column 1}
                               {:project-root "/fake/project/root"})]
        (is (.contains ^String uri abs)
            "URI must carry the absolute :file value")
        (is (not (.contains ^String uri "/fake/project/root"))
            "URI must NOT contain the project-root (absolute path passes through)")))))

(deftest absolutise-file-passes-through-already-absolute
  (testing "rf2-wvsxg: already-absolute paths pass through unchanged"
    ;; Windows-style drive letter
    (is (= "C:/foo/bar.cljs"
           (#'sc/absolutise-file "C:/foo/bar.cljs")))
    ;; POSIX absolute
    (is (= "/foo/bar.cljs"
           (#'sc/absolutise-file "/foo/bar.cljs")))
    ;; file: URL
    (is (= "file:/foo/bar.cljs"
           (#'sc/absolutise-file "file:/foo/bar.cljs")))))

(deftest absolutise-file-passes-through-unresolvable
  (testing "rf2-wvsxg: classpath-relative paths not on classpath fall
  through unchanged (synthetic coords, REPL eval, fabricated test
  paths shouldn't break)"
    (is (= "no/such/file/exists.cljs"
           (#'sc/absolutise-file "no/such/file/exists.cljs")))))

(deftest absolutise-file-handles-nil-and-blank
  (testing "rf2-wvsxg: nil / empty input passes through (the macro
  call-sites already gate on non-nil, but defense-in-depth)"
    (is (nil? (#'sc/absolutise-file nil)))
    (is (= "" (#'sc/absolutise-file "")))))

;; ---- rf2-st3ax4: a literal `+` in the classpath path survives -------------
;;
;; The macro-expansion-time twin of the open-in-editor server's resolve-file
;; had the same URLDecoder-+-plus bug: URLDecoder is a form-body decoder that
;; maps a literal `+` to a space, so a checkout/source path containing a
;; literal `+` (e.g. `C:/code/re-frame2+wip/core.cljs`) got the `+` turned
;; into a space, baking a nonexistent space-bearing absolute :file into the
;; emitted coord literal. The fix decodes via `URI.getPath` (which leaves a
;; literal `+` intact while still decoding `%20`/`%2B`), mirroring
;; `file-url->path` in re-frame.testbed.open-in-editor-server.

(deftest absolutise-file-preserves-literal-plus-in-classpath
  (testing "rf2-st3ax4: absolutise-file resolves a classpath-relative :file
  to its on-disk absolute path when the classpath root directory itself
  contains a literal + — the + is returned verbatim, NOT form-decoded to a
  space-bearing nonexistent path (the URLDecoder bug)"
    ;; Build a throwaway classpath root dir whose name carries a `+`, drop a
    ;; fake source file under it, push a class-loader rooted there onto the
    ;; context, and confirm absolutise-file finds the real on-disk file.
    (let [tmp      (File. (System/getProperty "java.io.tmpdir")
                          (str "scoords+test-" (System/nanoTime)))
          rel-path "fake_ns/core.cljs"
          src-file (io/file tmp "fake_ns" "core.cljs")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        (let [root-url (.toURL (.toURI tmp))
              cl       (URLClassLoader. (into-array URL [root-url])
                                        (.getContextClassLoader (Thread/currentThread)))
              prev     (.getContextClassLoader (Thread/currentThread))]
          (try
            (.setContextClassLoader (Thread/currentThread) cl)
            (let [resolved (#'sc/absolutise-file rel-path)]
              (is (string? resolved) "the classpath resource resolved")
              (is (not= rel-path resolved)
                  "classpath-relative path resolved to a different (absolute) path")
              (is (.contains ^String resolved "+")
                  "the literal + in the classpath root survived resolution")
              (is (not (.contains ^String resolved " "))
                  "the literal + was NOT form-decoded into a space")
              (is (= (.getCanonicalPath src-file)
                     (.getCanonicalPath (File. ^String resolved)))
                  "resolved to the REAL on-disk fixture file, not a
                   space-corrupted sibling that does not exist"))
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          ;; Best-effort cleanup of the throwaway tree.
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest absolutise-file-decodes-percent-escapes
  (testing "rf2-st3ax4: percent-escapes in the resource URL still decode
  correctly — a classpath root whose name contains a real space (URL-encoded
  as %20) round-trips to the space, and %2B (the encoded plus) decodes to a
  literal +. This is the other half of the URI.getPath contract: it decodes
  percent-escapes while leaving a literal + intact."
    (let [tmp      (File. (System/getProperty "java.io.tmpdir")
                          (str "scoords space-test-" (System/nanoTime)))
          rel-path "fake_ns/core.cljs"
          src-file (io/file tmp "fake_ns" "core.cljs")]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        (let [root-url (.toURL (.toURI tmp))
              cl       (URLClassLoader. (into-array URL [root-url])
                                        (.getContextClassLoader (Thread/currentThread)))
              prev     (.getContextClassLoader (Thread/currentThread))]
          (try
            (.setContextClassLoader (Thread/currentThread) cl)
            (let [resolved (#'sc/absolutise-file rel-path)]
              (is (string? resolved) "the classpath resource resolved")
              (is (.contains ^String resolved " ")
                  "%20 in the resource URL decoded back to a real space")
              (is (= (.getCanonicalPath src-file)
                     (.getCanonicalPath (File. ^String resolved)))
                  "resolved to the REAL on-disk fixture file"))
            (finally
              (.setContextClassLoader (Thread/currentThread) prev))))
        (finally
          (when (.exists src-file) (.delete src-file))
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest reg-event-emits-absolute-file
  (testing "rf2-wvsxg: a reg-* macro fired against a real classpath-
  resident file (this test ns) emits an ABSOLUTE :file, not a
  classpath-relative tail. This is the core regression: source-coord
  consumers (Story / Xray open-in-editor chips) can take the :file
  through compose-path unchanged and ship a URI that resolves on disk
  regardless of which project-root the host configured."
    (rf/reg-event :rf2-wvsxg/absolute-file-sample
                     (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :rf2-wvsxg/absolute-file-sample)
          f    (:file meta)]
      (is (string? f) ":file should be present")
      ;; The host's `re_frame/source_coords_test.clj` lives at
      ;; `<repo>/implementation/core/test/...`. The exact prefix is
      ;; environment-dependent — but the path must look absolute to
      ;; the URI builder so it won't double-prefix it.
      (let [uri (eu/editor-uri :vscode meta {:project-root "/wrong/project/root"})]
        (is (.contains ^String uri f)
            ":file should appear absolute in URI")
        (is (not (.contains ^String uri "/wrong/project/root"))
            ":file must be absolute (URI builder doesn't prepend project-root)"))
      ;; And the path must end in the test file's classpath-relative
      ;; tail — sanity that classpath resolution found the right
      ;; resource.
      (is (.endsWith ^String f "re_frame/source_coords_test.clj")
          ":file should end with the classpath-relative tail"))))

;; ---- rf2-quir9: reg-view PUBLIC meta carries the absolutised coord --------
;;
;; The asymmetry being fixed: `reg-event-*` ships an ABSOLUTE `:file` in its
;; public `(rf/handler-meta :event id)` (above), but `reg-view` was shipping
;; a CLASSPATH-RELATIVE `:file`. Root cause: the CLJS analyzer's indexing
;; reader stamps `:file` / `:line` / `:column` (+ `:source` / `:end-*`) onto
;; the view SYMBOL with a classpath-relative `:file`; the macro treated that
;; as user slot-meta and passed it to `reg-view*`, where
;; `source-coords/merge-coords` lets user-meta WIN over `*pending-coords*` —
;; so the relative reader `:file` clobbered the rf2-wvsxg-absolutised value.
;; The fix strips the reader's position keys from the slot-meta so
;; `*pending-coords*` is the single source of source-coords for the view's
;; public meta, symmetric with `reg-event`.

(deftest reg-view-emits-absolute-file-symmetric-with-reg-event
  (testing "rf2-quir9: reg-view fired against a real classpath-resident file
  (this test ns) ships an ABSOLUTE :file in its PUBLIC handler-meta, matching
  the always-on error-coord registry — symmetric with reg-event (so Xray /
  IDE open-in-editor resolves the path the same way for views)."
    (rf/reg-view ^{:rf/id :rf2-quir9/absolute-view-sample} quir9-view []
      [:div "hi"])
    (let [pub  (rf/handler-meta :view :rf2-quir9/absolute-view-sample)
          errc (sc/error-coords-for :view :rf2-quir9/absolute-view-sample)
          f    (:file pub)]
      (is (string? f) "public :file should be present")
      ;; The public :file must equal the error-coord registry's absolutised
      ;; value — the two source-coord sinks now agree (they previously
      ;; diverged: error-coord absolute, public relative).
      (is (= f (:file errc))
          "public handler-meta :file == error-coord :file (single source of truth)")
      ;; And it must look absolute to the URI builder (no project-root
      ;; prepend), exactly like the reg-event case above.
      (let [uri (eu/editor-uri :vscode pub {:project-root "/wrong/project/root"})]
        (is (.contains ^String uri f)
            "view :file should appear absolute in the URI")
        (is (not (.contains ^String uri "/wrong/project/root"))
            "view :file must be absolute (URI builder doesn't prepend project-root)"))
      (is (.endsWith ^String f "re_frame/source_coords_test.clj")
          "view :file should end with this test ns's classpath-relative tail"))))

(deftest reg-view-strips-reader-symbol-position-meta
  (testing "rf2-quir9: the reader's symbol-position meta (relative :file /
  :line / :column / :source / :end-*) must NOT leak into the registry slot —
  if it did, merge-coords would let the RELATIVE reader :file override the
  absolutised *pending-coords*. The expander is exercised directly with a
  view symbol carrying the exact reader-stamped shape the CLJS analyzer's
  indexing reader produces."
    (let [reader-sym (with-meta 'child-view
                       {:source 'child-view
                        :file   "standard_epochs/core.cljs"   ;; RELATIVE — the trap
                        :line   1 :column 11
                        :end-line 1 :end-column 21
                        :doc    "a real slot-meta key — must survive"})
          exp        (rvm/expand-reg-view {:line 1 :column 1 :file "standard_epochs/core.cljs"}
                                          'standard-epochs.core "standard_epochs/core.cljs"
                                          reader-sym '([] [:div]))
          ;; expansion: (do (binding [...] (reg-view* id slot-meta fn)) (def ...) id)
          binding-form (nth exp 1)
          regview-call (nth binding-form 2)        ;; (reg-view* id slot-meta fn)
          slot-meta    (nth regview-call 2)]
      (is (not (contains? slot-meta :file))
          ":file (relative reader key) must be stripped from slot-meta")
      (is (not (contains? slot-meta :line))
          ":line (reader key) must be stripped from slot-meta")
      (is (not (contains? slot-meta :column))
          ":column (reader key) must be stripped from slot-meta")
      (is (not (contains? slot-meta :source))
          ":source (reader whole-form-text key) must be stripped from slot-meta")
      (is (not (contains? slot-meta :end-line))
          ":end-line (reader key) must be stripped from slot-meta")
      (is (not (contains? slot-meta :end-column))
          ":end-column (reader key) must be stripped from slot-meta")
      ;; A genuine user slot-meta key (e.g. :doc on the symbol) survives —
      ;; the strip targets only the reader's position keys, not user meta.
      (is (= "a real slot-meta key — must survive" (:doc slot-meta))
          "genuine user slot-meta (:doc) must be preserved"))))
