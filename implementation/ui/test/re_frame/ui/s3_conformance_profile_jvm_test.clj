(ns re-frame.ui.s3-conformance-profile-jvm-test
  "The S3 view-substrate CONFORMANCE PROFILE, executable (07 §5; EP-0035;
  rf2-vxgfnd.95.10).

  This is the single authoritative catalogue of the FROZEN S3 verb surface. For
  each verb it pins the direct-call contract (the `(defn …)` facade stub exists
  for symbol resolution only and fails loud when called outside compiler
  lowering) and names the gate that proves the verb's real runtime behaviour.
  The test asserts the CODE honours each direct-call contract on the JVM, and a
  drift guard asserts the human profile
  (spec/conformance/S3-view-conformance-profile.md) catalogues every frozen verb
  and every S3 gate — so the profile cannot silently omit one.

  Runtime behaviour itself is proven by the named gates (browser/JVM/elision
  suites), not re-asserted here; this profile fixes the SURFACE and keeps the
  catalogue honest against the shipped facade and its documentation."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui]))

;; The frozen S3 verb surface. verb-symbol -> the direct-call error id, the gate
;; that proves its runtime behaviour, and the one-line contract. This map is the
;; source of truth the profile document restates.
(def frozen-s3-verbs
  {'local          {:error :rf.error/ui-tree-malformed :gate "G-15"
                    :contract "[value set! update!] three-tuple; update! composes over the latest host state"}
   'effect         {:error :rf.error/ui-tree-malformed :gate "G-6"
                    :contract "value-deps / :connect host effect; cleanup honoured; JVM records capability only"}
   'dispatch-fn    {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "stable committed-frame dispatcher; fails loud after disconnect"}
   'event          {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "committed handler; a vector result rides the sync door at a controlled site; nil = no dispatch"}
   'handler        {:error :rf.error/ui-tree-malformed :gate "G-8"
                    :contract "imperative committed callback; per-site stable; result ignored"}
   'render-fn      {:error :rf.error/ui-tree-malformed :gate "G-16"
                    :contract "pure compiled render-slot callback for an internal library seam"}
   'slot           {:error :rf.error/ui-tree-malformed :gate "G-16"
                    :contract "compiler-owned ui/render-fn invocation; nil renders nothing"}
   'spread-safe    {:error :rf.error/ui-spread-outside-template :gate "G-17"
                    :contract "literal safe-spread policy; owned-key deny in every build; retains the sync door"}
   'error-boundary {:error :rf.error/ui-tree-malformed :gate "G-6"
                    :contract "explicit error component; :on-error dispatches after commit; :reset-key retries"}
   'client-only    {:error :rf.error/ui-tree-malformed :gate "G-7"
                    :contract "browser-only subtree with a mandatory capability-free fallback"}})

(defn- direct-call!
  "Invoke the facade stub for `verb` directly (outside compiler lowering) so it
  fails loud, and return the thrown `:rf.error/id`, or ::no-throw."
  [verb]
  (try
    (case verb
      local          (ui/local 0)
      effect         (ui/effect)
      dispatch-fn    (ui/dispatch-fn)
      event          (ui/event)
      handler        (ui/handler)
      render-fn      (ui/render-fn)
      slot           (ui/slot)
      spread-safe    (ui/spread-safe nil nil)
      error-boundary (ui/error-boundary)
      client-only    (ui/client-only))
    ::no-throw
    (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest every-frozen-s3-verb-resolves-in-the-facade
  (testing "each frozen S3 verb is a public var of re-frame.ui"
    (doseq [verb (keys frozen-s3-verbs)]
      (is (some? (ns-resolve 're-frame.ui verb))
          (str verb " must resolve in the re-frame.ui facade")))))

(deftest every-frozen-s3-verb-direct-call-fails-loud
  (testing "the facade stub exists for symbol resolution only and throws its documented id"
    (doseq [[verb {:keys [error]}] frozen-s3-verbs]
      (is (= error (direct-call! verb))
          (str "(" verb " …) called directly must throw " error)))))

(defn- repo-root []
  (loop [d (io/file (System/getProperty "user.dir"))]
    (cond
      (nil? d) nil
      (.exists (io/file d "spec" "conformance")) d
      :else (recur (.getParentFile d)))))

(deftest profile-document-catalogues-every-verb-and-gate
  (testing "the human S3 conformance profile stays in sync with this executable surface"
    (let [root (repo-root)]
      (is (some? root) "must locate the repo root (spec/conformance)")
      (let [doc (io/file root "spec" "conformance" "S3-view-conformance-profile.md")]
        (is (.exists doc) (str "the profile document must exist at " doc))
        (let [text (slurp doc)]
          (doseq [verb (keys frozen-s3-verbs)]
            (is (.contains text (str "`ui/" verb "`"))
                (str "the profile must catalogue ui/" verb)))
          ;; Every S3 gate must be named — these prove the profile's runtime rows.
          (doseq [gate ["G-7" "G-8" "G-11" "G-15" "G-16" "G-17" "G-18"]]
            (is (.contains text gate)
                (str "the profile must name gate " gate)))
          (testing "explicit non-surfaces (the wall) are documented"
            (is (.contains text "Non-surfaces")
                "the profile must enumerate explicit S3 non-surfaces")))))))
