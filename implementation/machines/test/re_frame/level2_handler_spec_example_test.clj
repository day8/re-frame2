(ns re-frame.level2-handler-spec-example-test
  "Runs Spec 005 §Testing → Level 2 as the spec prints it. The `(handler …)`
  call is read straight out of `spec/005-StateMachines.md`, so its db-arg and
  event vector are the spec's own, and the handler `make-machine-handler`
  returns must answer them with the next snapshot — no registration, and no
  frame created under the db-arg's `:rf.frame/id`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.io PushbackReader StringReader]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- repo-root
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(defn- level2-handler-call
  "The first `(handler …)` form under the Level 2 heading of Spec 005's
  §Testing, read as data."
  []
  (let [spec    (slurp (io/file (repo-root) "spec/005-StateMachines.md") :encoding "UTF-8")
        section (str/index-of spec "### Level 2 — unregistered handler fn")
        call    (some->> section (str/index-of spec "(handler "))]
    (when-not call
      (throw (ex-info "Spec 005 §Testing → Level 2 no longer carries a (handler …) call"
                      {:section section})))
    (read (PushbackReader. (StringReader. (subs spec call))))))

(def ^:private drawer-editor
  "A machine that takes the example's `:right-click-circle` event — the spec
  elides its own definition as `{...}`."
  {:initial :idle
   :states  {:idle         {:on {:right-click-circle :context-menu}}
             :context-menu {}}})

(deftest level2-example-runs-as-printed
  (let [[op db-arg event] (level2-handler-call)
        handler           (rf.machines/make-machine-handler drawer-editor)
        machine-id        (first event)
        result            (handler db-arg event)]
    (is (= 'handler op))
    (is (= :drawer/editor machine-id))
    (is (nil? (rf.frame/frame (:rf.frame/id db-arg)))
        "the db-arg's frame id names no frame: it stamps traces only")
    (is (= :context-menu
           (get-in result [:rf.db/runtime :rf.runtime/machines :snapshots machine-id :state]))
        "the handler returns the next snapshot under :rf.db/runtime")))
