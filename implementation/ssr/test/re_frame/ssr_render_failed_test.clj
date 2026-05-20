(ns re-frame.ssr-render-failed-test
  "Regression coverage for the `:rf.error/ssr-render-failed` trace category
  (Spec 009 §Error event catalogue, rf2-260pg — follow-up to rf2-zwgsv).

  Per Spec 011 §View-time exceptions, the SSR pipeline unifies render-time
  and drain-time failure surfaces under the same error projector. An SSR
  host adapter that catches a render-time `Throwable` (e.g. the
  `validate-tag-name!` rejection of `(keyword \"has space\")`, a view-fn
  `(throw (ex-info ...))`, a hiccup-walker structural error) calls
  `re-frame.ssr/project-render-exception!` to:

    1. Synthesise a `:rf.error/ssr-render-failed` trace event carrying
       `{:frame :exception :exception-message :ex-class}` tags (plus
       `:recovery :projected-to-public-error` on the trace envelope).
    2. Emit the event on the trace bus so monitoring listeners see it.
    3. Drive the active error projector against the synthesised event so
       the public-error's `:status` is stamped onto `:rf/response`.

  This suite pins the trace-emission contract — the catalogue invariant
  (Spec 009 §Error event catalogue) requires every catalogued category to
  fire from a documented emit-site with the documented tags. The status-
  stamping behaviour is covered by the cross-cutting end-to-end suites
  (`ssr_end_to_end_test`, `ssr_error_projector_substrate_test`,
  `ssr-ring/ring_e2e_validator_test`); this suite isolates the trace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(deftest project-render-exception-emits-ssr-render-failed-trace
  (testing "rf2-260pg / rf2-zwgsv: `project-render-exception!` against a
            server frame emits a `:rf.error/ssr-render-failed` trace
            carrying the catalogued tags (Spec 009 §Error event
            catalogue)."
    (let [traces (atom [])]
      (rf/register-listener! ::srf
                                   (fn [ev]
                                     (when (= :rf.error/ssr-render-failed
                                              (:operation ev))
                                       (swap! traces conj ev))))
      (try
        (let [f  (rf/make-frame
                   {:platform :server
                    :ssr      {:public-error-id   :rf.ssr/default-error-projector
                               :dev-error-detail? false}})
              t  (ex-info "synthetic render-time failure"
                          {:reason :test})
              public (ssr/project-render-exception! f t)]

          (testing "projector returned a public-error map (the
                    status-stamping path executed)"
            (is (map? public)
                "project-render-exception! returns the public-error map")
            (is (= 500 (:status public))
                "the default projector maps the synthesised category to 500"))

          (testing "exactly one `:rf.error/ssr-render-failed` trace
                    fired (Spec 009 §Error event catalogue)"
            (is (= 1 (count @traces))
                (str "expected one trace; saw " (count @traces)
                     " — operations: "
                     (pr-str (mapv :operation @traces)))))

          (when (seq @traces)
            (let [ev (first @traces)]
              (testing "envelope shape per Spec 009 §Error event catalogue"
                (is (= :error (:op-type ev))
                    "severity discriminator is `:error`")
                (is (= :rf.error/ssr-render-failed (:operation ev))
                    "category keyword names the catalogued operation"))

              (testing "tags shape per Spec 009 §Error event catalogue row
                        (`:frame`, `:exception`, `:exception-message`,
                        `:ex-class`)"
                (is (= f (-> ev :tags :frame))
                    "`:frame` identifies the server frame the render
                     failed on")
                (is (identical? t (-> ev :tags :exception))
                    "`:exception` carries the caught Throwable verbatim")
                (is (= "synthetic render-time failure"
                       (-> ev :tags :exception-message))
                    "`:exception-message` is the throwable's message
                     (cheap-to-log replica of the throw)")
                (is (= "clojure.lang.ExceptionInfo"
                       (-> ev :tags :ex-class))
                    "`:ex-class` carries the throwable's class name as a
                     string — class-aware filtering without ferrying
                     the live Throwable through trace consumers")
                (is (= :projected-to-public-error
                       (:recovery ev))
                    "`:recovery` is hoisted to the envelope top-level
                     (per Spec 009 §Error event shape) — the catalogue
                     row's locked policy")))))
        (finally
          (rf/unregister-listener! ::srf))))))

(deftest project-render-exception-noop-for-non-server-frame
  (testing "rf2-260pg: `project-render-exception!` against a non-server
            frame is a no-op — no trace fires, projector not invoked.
            Belt-and-braces against accidentally emitting the trace from
            client-side render paths."
    (let [traces (atom [])]
      (rf/register-listener! ::srf-client
                                   (fn [ev]
                                     (when (= :rf.error/ssr-render-failed
                                              (:operation ev))
                                       (swap! traces conj ev))))
      (try
        ;; Default platform is :client; project-render-exception! checks
        ;; `server-frame?` and short-circuits.
        (let [f      (rf/make-frame {})
              t      (ex-info "should not fire" {})
              result (ssr/project-render-exception! f t)]
          (is (nil? result)
              "client-frame call returns nil — projector not invoked")
          (is (zero? (count @traces))
              "no `:rf.error/ssr-render-failed` trace fires for a
               non-server frame"))
        (finally
          (rf/unregister-listener! ::srf-client))))))
