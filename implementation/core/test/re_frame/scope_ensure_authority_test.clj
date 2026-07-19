(ns re-frame.scope-ensure-authority-test
  "rf2-kk2uf — the SCOPE/ENSURE authority guard.

  A BOUNDED, CLASSIFIED check over an ENUMERATED table of the exact stale
  forms this bead retired. It is deliberately NOT a prose-lint framework:
  there is no fence parser, no allow-list, no repo-wide sweep, and no
  general grammar. Each row names ONE file and the LITERAL forms that were
  wrong in it, so the table reads as the changelog it is.

  Two contracts are pinned:

    1. `subscribe` has ONE coherent no-default contract. The retired
       `:rf.warning/plain-fn-under-non-default-frame-once` fall-through
       paragraph (which claimed a 1-arity `subscribe` lands on
       `:rf/default`) must not come back — it contradicted the very
       docstring it sat in.

    2. `frame-provider` is SCOPE-only and `frame-root` is ENSURE. An
       EXHAUSTIVE frame-resolution inventory must name BOTH boundaries, and
       no active guidance may say `frame-provider` CREATES or DESTROYS a
       frame. (Statements that are genuinely provider-specific are NOT in
       this table and stay provider-only.)

  WHY EACH ROW CARRIES A `:required` ANCHOR. A `:forbidden` regex alone goes
  vacuously green the moment a file is renamed, moved, or has the region
  deleted — the failure mode where a guard only fires when its input is
  empty. Every row therefore also asserts the TRUTHFUL form is present, and
  every row asserts its file EXISTS. A row cannot pass by finding nothing.

  WHY THE TEXT IS WHITESPACE-NORMALIZED. Several of these claims were
  WRAPPED across source lines (`router.cljc` wrapped
  `` `with-frame` / frame-provider `` mid-phrase). A per-line scan is blind
  to exactly that, and returned a false-empty during this bead's own
  investigation. Each file is flattened to single-spaced text before
  matching, so a reintroduced claim is caught however it wraps.

  PER-ROW, NEVER A UNION. Every row emits its OWN assertion naming its own
  file and form. A union-shaped check (`some hit anywhere`) would stay
  silent when one source root is renamed and the rest still match.

  JVM-only (`.clj`): it `slurp`s repo markdown + source and asserts on text,
  so it carries no runtime behaviour and never loads a substrate."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Repo-root resolution
;; ---------------------------------------------------------------------------
;;
;; Core's JVM tests run from `implementation/core/`, so the repo root is
;; `../../`. The legacy single-nesting layout is tolerated as a fallback, and
;; an unresolvable root FAILS (never silently skips) — per the sibling
;; `error_catalogue_channel_conformance_test`.

(defn- repo-root
  []
  (->> ["../.." ".." "."]
       (map io/file)
       (filter #(.exists (io/file % "spec/009-Instrumentation.md")))
       first))

(defn- flattened
  "Slurp `rel` under the repo root and collapse every whitespace run to a
  single space, so a claim split across a line wrap still matches."
  [root rel]
  (let [f (io/file root rel)]
    (when (.exists f)
      (str/replace (slurp f) #"\s+" " "))))

;; ---------------------------------------------------------------------------
;; The enumerated table — one row per file, listing that file's exact forms
;; ---------------------------------------------------------------------------

(def ^:private stale-token
  "The shared stale spelling: a scope-chain inventory naming `frame-provider`
  as the only frame boundary. Normalized, it reads `` `with-frame` /
  frame-provider `` (with or without backticks on the provider)."
  #"`with-frame`\s*/\s*`?frame-provider`?")

(def ^:private authority-table
  [{:file      "implementation/core/src/re_frame/subs.cljc"
    :why       "subscribe's no-default contract — the retired fall-through"
    :forbidden [[#"fallen through to :rf/default"
                 "the retired `:rf/default` fall-through claim"]
                [#"Plain-fn-under-non-default-frame warning"
                 "a citation to the deleted Spec 006 warning section"]
                [#"plain-fn detection check"
                 "the retired plain-fn detection paragraph"]]
    :required  [[#"There is NO `:rf/default` floor"
                 "the no-default contract"]
                [#"`frame-provider`\s*\(SCOPE\) or a `frame-root` \(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/src/re_frame/core_http.cljc"
    :why       "clear-http-interceptor's carried-invariant scope chain"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/src/re_frame/core_machines.cljc"
    :why       "machine-by-system-id's scope/hold chain"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/src/re_frame/router.cljc"
    :why       "the dispatch envelope's frame-resolution inventory (x2)"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "implementation/core/test/re_frame/router_carried_frame_test.clj"
    :why       "the router envelope resolution-order comment"
    :forbidden [[stale-token "a provider-only scope-chain inventory"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`"
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

   {:file      "docs/api/re-frame.adapter.reagent.md"
    :why       "with-resource-lease's frame-resolution order"
    :forbidden [[#"the surrounding `frame-provider` context, then the dynamic"
                 "a provider-only resolution order"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) or a `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   {:file      "docs/api/re-frame.adapter.uix.md"
    :why       "use-subscribe's standard chain"
    :forbidden [[#"then the surrounding `frame-provider`\. It raises"
                 "a provider-only standard chain"]]
    :required  [[#"`frame-provider`\s*\(SCOPE\) / `frame-root`\s*\(ENSURE\)"
                 "the SCOPE/ENSURE boundary pair"]]}

   ;; ---- The CREATE / DESTROY claims (not inventories) ----------------------

   {:file      "spec/000-Vision.md"
    :why       "the scope/carry/ensure triad — frame-provider claimed to create AND destroy"
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

;; ---------------------------------------------------------------------------
;; The check — one assertion per row per form, never a union
;; ---------------------------------------------------------------------------

(deftest scope-ensure-authority-table-is-live
  (testing "the table's files all exist (a missing file must FAIL, not skip)"
    (let [root (repo-root)]
      (is (some? root)
          "repo root not resolvable from the JVM test CWD — cannot verify")
      (doseq [{:keys [file]} authority-table]
        (is (some? (flattened root file))
            (str "authority-table row names a file that does not exist: " file
                 " — the row is stale; fix the path rather than deleting it"))))))

(deftest no-retired-scope-ensure-forms
  (let [root (repo-root)]
    (doseq [{:keys [file why forbidden]} authority-table]
      (testing (str file " — " why)
        (when-let [text (flattened root file)]
          (doseq [[re label] forbidden]
            (is (nil? (re-find re text))
                (str "STALE FORM REINTRODUCED in " file ": " label
                     " (pattern " (pr-str (str re)) "). "
                     "`frame-provider` SCOPEs an already-live frame; "
                     "`frame-root` ENSUREs one. Per rf2-kk2uf."))))))))

(deftest truthful-scope-ensure-forms-are-present
  (let [root (repo-root)]
    (doseq [{:keys [file why required]} authority-table]
      (testing (str file " — " why)
        (when-let [text (flattened root file)]
          (doseq [[re label] required]
            (is (some? (re-find re text))
                (str "MISSING TRUTHFUL FORM in " file ": " label
                     " (pattern " (pr-str (str re)) "). "
                     "If this region moved, update this row — do NOT delete it; "
                     "a row that finds nothing is how this guard goes "
                     "vacuously green. Per rf2-kk2uf."))))))))
