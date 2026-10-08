(ns re-frame.source-coords-test
  "Spec 001 §Source-coordinate capture and Tool-Pair §Source-mapping: every
  macro-path registration carries `:ns` / `:line` / `:file`, captured at compile
  time. `re-frame.source-coords-cljs-test` covers the CLJS `:file` resolution.

  ## Posture split

  Capture has two sinks. The public registry-meta (`rf/handler-meta`) is
  dev-only: under `-Dre-frame.debug=false` `merge-coords` returns user-meta
  unchanged. The always-on `error-coords-by-id` registry, which the error-emit
  substrate reads for off-box shippers, is filled by `rf.registrar/register!`
  in both postures. So each claim is asserted on the always-on sink, with the
  public-meta claim in a `(when rf.interop/debug-enabled? …)` arm. `reg-flow`
  and `reg-app-schema` store their coords in their own artefacts' side-tables,
  never through `register!`, so they have only the dev sink."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [clojure.java.io :as io]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.core-reg-view-macro :as rf.core-reg-view-macro]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.source-coords.editor-uri :as rf.source-coords.editor-uri]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.net URL URLClassLoader]
           [java.io File]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- assert-coords
  "Spec 001 §The metadata map: `:ns` a symbol, `:line` an integer, `:file` a
  string. Fails on an absent map or key too."
  [m kind id]
  (is (and (symbol? (:ns m)) (integer? (:line m)) (string? (:file m)))
      (str kind " " id " carries :ns / :line / :file — got " (pr-str m))))

(deftest source-coords-on-every-registration-kind
  (rf/reg-event :rf2-k84s/reg-event-sample (fn [{:keys [db]} _] {:db db}))
  (rf/reg-sub :rf2-k84s/reg-sub-sample (fn [db _] db))
  (rf/reg-fx :rf2-k84s/reg-fx-sample (fn [_ _] nil))
  (rf/reg-cofx :rf2-k84s/reg-cofx-sample (fn [] :sample))
  (rf/reg-view ^{:rf/id :rf2-k84s/reg-view-sample} reg-view-sample []
    [:div "hi"])
  ;; reg-machine registers the machine as an event handler, through the
  ;; metadata-map arity of reg-event (Spec 005 §Registration).
  (rf/reg-machine :rf2-k84s/reg-machine-sample {:initial :a :states {:a {} :b {}}})
  (rf/reg-route :rf2-k84s/reg-route-sample {} "/k84s")
  (rf/reg-error-projector :rf2-k84s/reg-error-projector-sample
                          (fn [_] {:status 500 :code :internal-error :message "x" :retryable? false}))
  (rf/reg-flow :rf2-k84s/reg-flow-sample {:inputs [[:source]] :output-path [:dest]} (fn [v] v))
  (rf/reg-app-schema [:rf2-k84s/reg-app-schema-sample] :int)
  (doseq [[kind id] [[:event           :rf2-k84s/reg-event-sample]
                     [:sub             :rf2-k84s/reg-sub-sample]
                     [:fx              :rf2-k84s/reg-fx-sample]
                     [:cofx            :rf2-k84s/reg-cofx-sample]
                     [:view            :rf2-k84s/reg-view-sample]
                     [:event           :rf2-k84s/reg-machine-sample]
                     [:route           :rf2-k84s/reg-route-sample]
                     [:error-projector :rf2-k84s/reg-error-projector-sample]]]
    (assert-coords (rf.source-coords/error-coords-for kind id) kind id)
    (when rf.interop/debug-enabled?
      (assert-coords (rf/handler-meta {:source :store :kind kind :id id}) kind id)))
  (when rf.interop/debug-enabled?
    (assert-coords (rf.flows/flow-meta {:frame :rf/default :id :rf2-k84s/reg-flow-sample})
                   :flow :rf2-k84s/reg-flow-sample)
    (assert-coords (rf.schemas/app-schema-meta {:frame :rf/default
                                                :path  [:rf2-k84s/reg-app-schema-sample]})
                   :app-schema [:rf2-k84s/reg-app-schema-sample])))

(deftest user-supplied-coords-win
  ;; User coords are user-meta, which `merge-coords` keeps in both postures, so
  ;; a code-gen pass can stamp the originating coordinates.
  (rf/reg-event :rf2-k84s/explicit-coords
                {:ns 'my.ns :line 42 :file "elsewhere.cljc"}
                (fn [{:keys [db]} _] {:db db}))
  (is (= {:ns 'my.ns :line 42 :file "elsewhere.cljc"}
         (select-keys (rf/handler-meta {:source :store :kind :event :id :rf2-k84s/explicit-coords})
                      [:ns :line :file]))))

;; ---- :file is absolutised via classpath resolution ------------------------

(deftest absolutise-file-passes-through-what-it-cannot-resolve
  (are [path] (= path (rf.source-coords/absolutise-file path))
    "C:/foo/bar.cljs"
    "no/such/file/exists.cljs"))

(deftest absolutise-file-decodes-the-resource-url-path
  ;; Decoded with URI.getPath, not the form decoder URLDecoder: a literal `+` in
  ;; a checkout path survives, and a %20-escaped space decodes back to a space.
  (doseq [root-name ["scoords+test-" "scoords space-test-"]]
    (let [tmp      (File. (System/getProperty "java.io.tmpdir") (str root-name (System/nanoTime)))
          src-file (io/file tmp "fake_ns" "core.cljs")
          prev     (.getContextClassLoader (Thread/currentThread))]
      (try
        (io/make-parents src-file)
        (spit src-file ";; fixture\n")
        (.setContextClassLoader (Thread/currentThread)
                                (URLClassLoader. (into-array URL [(.toURL (.toURI tmp))]) prev))
        (is (= (.getCanonicalPath src-file)
               (.getCanonicalPath (File. ^String (rf.source-coords/absolutise-file "fake_ns/core.cljs"))))
            (str "resolves to the real on-disk file under " root-name))
        (finally
          (.setContextClassLoader (Thread/currentThread) prev)
          (.delete src-file)
          (.delete (io/file tmp "fake_ns"))
          (.delete tmp))))))

(deftest registration-file-is-absolute
  ;; A classpath-relative :file resolves nowhere once shipped, so the macros bake
  ;; the absolute on-disk path into both sinks.
  (rf/reg-event :rf2-wvsxg/absolute-file-sample (fn [{:keys [db]} _] {:db db}))
  (rf/reg-view ^{:rf/id :rf2-quir9/absolute-view-sample} quir9-view []
    [:div "hi"])
  (doseq [[kind id] [[:event :rf2-wvsxg/absolute-file-sample]
                     [:view  :rf2-quir9/absolute-view-sample]]]
    (let [f (:file (rf.source-coords/error-coords-for kind id))]
      (is (rf.source-coords.editor-uri/absolute-path? f) (str kind " :file is absolute"))
      (is (.endsWith ^String f "re_frame/source_coords_test.clj"))
      (when rf.interop/debug-enabled?
        (is (= f (:file (rf/handler-meta {:source :store :kind kind :id id})))
            (str kind " public meta carries the same absolutised :file"))))))

(deftest reg-view-strips-reader-symbol-position-meta
  ;; The CLJS indexing reader stamps a classpath-RELATIVE :file (plus :line,
  ;; :column, :source, :end-*) on the view symbol. As slot-meta it would win
  ;; over the absolutised *pending-coords* in merge-coords, so the expander
  ;; strips those keys and keeps genuine user slot-meta.
  (let [reader-sym (with-meta 'child-view
                     {:source     'child-view
                      :file       "standard_epochs/core.cljs"
                      :line       1 :column 11
                      :end-line   1 :end-column 21
                      :doc        "a real slot-meta key"})
        exp        (rf.core-reg-view-macro/expand-reg-view
                     {:line 1 :column 1 :file "standard_epochs/core.cljs"}
                     'standard-epochs.core "standard_epochs/core.cljs"
                     reader-sym '([] [:div]))
        ;; (do (binding [...] (reg-view* id slot-meta fn)) (def ...) id)
        slot-meta  (-> exp (nth 1) (nth 2) (nth 2))]
    (is (= {:doc "a real slot-meta key"} slot-meta))))
