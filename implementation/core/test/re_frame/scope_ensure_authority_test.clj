(ns re-frame.scope-ensure-authority-test
  "`frame-provider` SCOPEs an already-live frame and `frame-root` ENSUREs one;
  `subscribe` has one no-default contract. Each row of the table names one
  file, the literal stale forms that must not appear in it, and the truthful
  forms that must — the `:required` anchor keeps a row from passing vacuously
  once its file or region moves. Text is whitespace-flattened so a claim
  wrapped across lines still matches."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- repo-root
  "Core's JVM tests run from `implementation/core/`; the shallower layouts are
  tolerated, and an unresolvable root fails the test rather than skipping."
  []
  (->> ["../.." ".." "."]
       (map io/file)
       (filter #(.exists (io/file % "spec/009-Instrumentation.md")))
       first))

(defn- flattened
  "Slurp `rel` under `root` with every whitespace run collapsed to one space,
  or nil when the file does not exist."
  [root rel]
  (let [f (io/file root rel)]
    (when (.exists f)
      (str/replace (slurp f) #"\s+" " "))))

(def ^:private stale-token
  "A scope-chain inventory naming `frame-provider` as the only frame boundary."
  #"`with-frame`\s*/\s*`?frame-provider`?")

(def ^:private authority-table
  [{:file      "implementation/core/src/re_frame/subs.cljc"
    :why       "subscribe's no-default contract — no fall-through"
    :forbidden [[#"fallen through to :rf/default"
                 "the false `:rf/default` fall-through claim"]
                [#"Plain-fn-under-non-default-frame warning"
                 "a citation to a Spec 006 warning section that does not exist"]
                [#"plain-fn detection check"
                 "the false plain-fn detection paragraph"]]
    :required  [[#"There is NO `:rf/default` floor"
                 "the no-default contract"]
                [#"`frame-provider`\s*\(SCOPE\) or a `frame-root` \(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/src/re_frame/core_http.cljc"
    :why       "clear-http-interceptor's carried-invariant scope chain"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/src/re_frame/router.cljc"
    :why       "the dispatch envelope's frame-resolution inventory (x2)"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/http/src/re_frame/http/middleware.cljc"
    :why       "HTTP interceptor registration + clear (x2)"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\)"
                 "the SCOPE boundary named as SCOPE"]
                [#"`frame-root`\s*\(ENSURE\)"
                 "the ENSURE boundary named as ENSURE"]]}

   {:file      "spec/013-Flows.md"
    :why       "reg-flow's frame resolution (x2)"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]
                [#"surrounding `with-frame` scope or `frame-provider` —"
                 "a provider-only reg-flow resolution inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "spec/012-Routing.md"
    :why       "route-link's render-time scope"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) / `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "docs/api/re-frame.adapter.uix.md"
    :why       "use-sub's standard chain"
    :forbidden [[#"then the surrounding `frame-provider`\. It raises"
                 "a provider-only standard chain"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) / `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "spec/000-Vision.md"
    :why       "the scope/carry/ensure triad"
    :forbidden [[#"creates / provides / destroys a frame"
                 "the claim that `frame-provider` creates and destroys a frame"]]
    :required  [[#"`frame-root`\s*\(ENSURE\) creates the frame if absent"
                 "creation attributed to `frame-root`"]
                [#"`frame-provider`\s*\(SCOPE\) provides an already-created frame and creates nothing"
                 "`frame-provider` stated as SCOPE-only"]
                [#"Neither destroys the frame on unmount"
                 "the no-destroy-on-unmount contract"]]}

   {:file      "spec/009-Instrumentation.md"
    :why       ":rf.error/frame-construction-in-handler — creation recovery"
    :forbidden [[#"created by the VIEW \(`frame-provider`\)"
                 "the claim that the VIEW creates frames via `frame-provider`"]
                [#"move the frame creation to a `frame-provider`"
                 "a recovery pointing creation at `frame-provider`"]]
    :required  [[#"created by the VIEW \(`frame-root`"
                 "creation attributed to `frame-root`"]
                [#"move the frame creation to a `frame-root`"
                 "the recovery pointing creation at `frame-root`"]]}])

(deftest scope-ensure-forms-are-current
  (let [root (repo-root)]
    (is (some? root) "repo root not resolvable from the JVM test CWD")
    (doseq [{:keys [file why forbidden required]} authority-table]
      (testing (str file " — " why)
        (let [text (flattened root file)]
          (is (some? text) "the row names a file that does not exist; fix the path, keep the row")
          (when text
            (doseq [[re label] forbidden]
              (is (nil? (re-find re text))
                  (str "STALE FORM REINTRODUCED: " label " (pattern " (pr-str (str re)) ")")))
            (doseq [[re label] required]
              (is (some? (re-find re text))
                  (str "MISSING TRUTHFUL FORM: " label " (pattern " (pr-str (str re)) ")")))))))))
