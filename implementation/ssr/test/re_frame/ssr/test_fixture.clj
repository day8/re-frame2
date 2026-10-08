(ns re-frame.ssr.test-fixture
  "The shared `:each` reset for the ssr JVM tests, one copy so the files
  cannot drift. It mirrors the per-request teardown
  (`re-frame.ssr/on-frame-destroyed!`): registrar, frame, flow and schema
  state, every per-frame SSR side-channel slot (request, response, pending
  error traces) and the install ledger are cleared; the SSR adapter is
  installed; and the namespaces whose ns-load registrations `clear-all!`
  wipes are reloaded so they come back."
  (:require [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]))

(defn reset-runtime
  "`(use-fixtures :each reset-runtime)`."
  [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; The producing sub-namespaces' atoms; the facade's aliases are private.
  (reset! rf.ssr.request/request-slots {})
  (reset! rf.ssr.response/response-slots {})
  (reset! rf.ssr.error-listener/pending-error-traces {})
  ;; Keyed by payload id (a frame id): a claim left by a prior test would make
  ;; the next test's first hydrate look like a sibling root's second one.
  (rf.ssr.install/reset-installed-payloads!)
  (rf.flows/reset-last-inputs!)
  (rf/init! rf.ssr/adapter)
  ;; clear-all! wiped these namespaces' ns-load registrations.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.ssr.head :reload)
  (require 're-frame.machines :reload)
  ;; `init!` makes no `:rf/default`, and dispatch and registration need a
  ;; frame; tests driving their own server frames re-bind with `with-frame`
  ;; or an explicit `{:frame …}`.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))
