(ns re-frame.ui.frame-context-hook-cljs-test
  "rf2-vxgfnd.24 — the pure compiled-view `:adapter/current-frame` publish,
  exercised through a REAL ViewCell sub-read (not a direct call) on the
  headless plain-atom runtime Node can run:

    - the reader IS published once `re-frame.ui.frames` (hence
      `re-frame.ui.adapter`) is on the classpath;
    - with the reader live, an ambient compiled sub-read with NO scope
      established still fails loud with `:rf.error/no-frame-context` (the
      fail-loud contract is PRESERVED — .24 is additive, not a default
      floor);
    - the published reader still honours the dynamic tier (`with-frame`).

  The React-CONTEXT tier itself (a `frame-provider` / `frame-root` scope
  read through `_currentValue` under a mounted root) is exercised in the DOM
  twin `re-frame.ui.frame-scope-resolve-dom-cljs-test`. CLJS-only: the reader
  is a React-context read and is published only on the plain-atom CLJS
  runtime."
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.frames :as frames]      ;; loads re-frame.ui.adapter (the publish)
            [re-frame.ui.reactive :as reactive]))

;; :ambient-frame nil — opt OUT of the fixture's default `:rf/default`
;; *current-frame* binding, so the no-scope arms genuinely have no dynamic
;; tier and the dynamic-tier arm establishes its own scope via `with-frame`.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil}))

(def ^:private fid :rf/default)

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

;; one ViewCell render pass — the REAL sub-read path (obs/resolve-target →
;; frame/require-current-frame! → the :adapter/current-frame hook), recorded
;; into a live cell's capture. NOT a direct sub-read.
(defn- render-cell! [cell queries]
  (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))

(deftest reader-is-published-under-plain-atom
  (is (some? (late-bind/get-fn :adapter/current-frame))
      "re-frame.ui.adapter routed the :adapter/current-frame reader")
  ;; with plain-atom installed and no scope established, the published reader
  ;; resolves nil — no :rf/default floor (EP-0002 carried invariant).
  (is (nil? (frame/resolve-current-frame))
      "no scope → the published reader resolves nil, not a synthesised default"))

(deftest ambient-sub-with-no-scope-fails-loud
  (rf/reg-sub :hook/n (fn [db _] (:n db)))
  (let [cell (reactive/make-cell ::v)]
    (is (= :rf.error/no-frame-context
           (throws-id #(render-cell! cell [[:hook/n]])))
        (str "with the reader PUBLISHED, an ambient ViewCell sub-read with no "
             "carried pin and no established scope still throws "
             ":rf.error/no-frame-context — the fail-loud contract is preserved"))))

(deftest ambient-sub-resolves-dynamic-tier
  (rf/reg-sub :hook/n (fn [db _] (:n db)))
  (rf/make-frame {:id fid})
  (frame/replace-app-db! fid {:n 7})
  (let [cell (reactive/make-cell ::v)]
    (is (= [7]
           (rf/with-frame fid
             (render-cell! cell [[:hook/n]])))
        (str "the published reader honours the dynamic-var tier: a with-frame "
             "binding resolves the compiled sub-read"))))
