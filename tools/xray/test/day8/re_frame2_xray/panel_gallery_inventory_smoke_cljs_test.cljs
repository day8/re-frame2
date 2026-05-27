(ns day8.re-frame2-xray.panel-gallery-inventory-smoke-cljs-test
  "Regression smoke for the panel-gallery /#/stories empty-inventory
  defect (rf2-k1k87).

  Two cascading authoring errors had silently bricked the gallery shell:

  1. **Cascade A — `:rf.error/unknown-tag`**: every gallery's variants
     used `:state/*` tags (e.g. `:state/empty`, `:state/special`) but
     no `reg-tag` call ever registered the `:state/*` axis. The
     registrar's `validate-tag-membership!` threw on the FIRST gallery
     ns to load and aborted the cascade; subsequent galleries never
     ran their `register-all!`.

  2. **Cascade B — `:rf.error/story-id-shape`**: `gallery_diff_mode_
     universal.cljs` declared `(reg-story :story.xray.app-db/diff-
     mode ...)` — the slash separator violates the canonical id
     grammar `:story.<dotted-path>` (story ids MUST have nil
     namespace per `schemas/story-id?`).

  This smoke locks both regressions at the framework + author level:

  - **State-axis lock**: a synthetic `reg-variant` carrying every
    `:state/*` value at once succeeds without throwing — pinning that
    Story's canonical install covers the magnitude axis end-to-end.
  - **Gallery-id grammar lock**: every story / variant / workspace id
    that the broken gallery shipped (post-fix) passes the matching
    `schemas/*-id?` predicate. If a future edit reintroduces a slash
    into a story id, this test catches it before the gallery hits the
    runtime.

  Pure data → data; no React, no live runtime. Discovered by the
  `:node-test` build's `cljs-test$` regex via the `-cljs-test` ns
  suffix (`xray/test/` is on shadow's `:source-paths`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.schemas :as schemas]
            ;; rf2-nozqw — per-gallery register-all! drive. Each
            ;; namespace's bottom-of-file `(register-all!)` fires at
            ;; namespace load, but the smoke test calls each one
            ;; individually after a `clear-all!` to verify the gallery
            ;; produces non-empty inventory in isolation. This catches
            ;; the cascade-aborts-on-first-error pattern where a typo
            ;; in one gallery (e.g. lowercase `:workspace.*` id, or an
            ;; unregistered `:feature/*` tag) silently bricks that
            ;; gallery's contribution while the aggregate inventory
            ;; stays non-empty from the other twelve galleries.
            [panel-gallery.gallery-app-db          :as gallery-app-db]
            [panel-gallery.gallery-chrome          :as gallery-chrome]
            [panel-gallery.gallery-diff-mode-3     :as gallery-diff-mode-3]
            [panel-gallery.gallery-diff-mode-universal :as gallery-diff-mode-universal]
            [panel-gallery.gallery-edn-inspector   :as gallery-edn-inspector]
            [panel-gallery.gallery-epoch           :as gallery-epoch]
            [panel-gallery.gallery-filters         :as gallery-filters]
            [panel-gallery.gallery-issues          :as gallery-issues]
            [panel-gallery.gallery-machines        :as gallery-machines]
            [panel-gallery.gallery-routing         :as gallery-routing]
            [panel-gallery.gallery-settings        :as gallery-settings]
            [panel-gallery.gallery-trace           :as gallery-trace]
            [panel-gallery.gallery-views           :as gallery-views]))

;; ---- fixture ----------------------------------------------------------

(defn- reset-and-install [test-fn]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-and-install)

;; ---- Cascade A regression — `:state/*` axis is canonical -------------

(deftest cascade-a-state-axis-tags-validate-on-variant-registration
  (testing "the canonical install registers every :state/* tag (rf2-k1k87)"
    (is (every? #(story/registered? :tag %) schemas/canonical-state-tags))
    (is (= 5 (count schemas/canonical-state-tags))
        "the axis ships five magnitude values"))
  (testing "registering a variant tagged with every :state/* value at once
            does NOT raise :rf.error/unknown-tag — the regression that
            bricked the panel-gallery"
    (story/reg-story :story.cascade-a.smoke
      {:doc       "rf2-k1k87 Cascade-A regression."
       :component :smoke/comp
       :tags      #{:dev}})
    (doseq [state-tag schemas/canonical-state-tags]
      (let [vid (keyword "story.cascade-a.smoke" (name state-tag))]
        (story/reg-variant vid
          {:doc    (str "Variant carrying " (pr-str state-tag))
           :events []
           :tags   #{:dev state-tag}})
        (is (story/registered? :variant vid)
            (str "variant for " (pr-str state-tag)
                 " registered without unknown-tag error")))))
  (testing "each :state/* tag carries the :state axis classifier
            so the sidebar tag-filter UI can group it (SB9 facet parity)"
    (is (= schemas/canonical-state-tags (story/tags-by-axis :state)))))

;; ---- Cascade B regression — gallery_diff_mode_universal.cljs ids -----

(def ^:private universal-diff-mode-story-ids
  "The three story ids that `gallery_diff_mode_universal.cljs` declares
  post-fix. Before rf2-k1k87 each carried a slash separator
  (`:story.xray.app-db/diff-mode`) violating the canonical
  `:story.<path>` grammar; the rename collapses them under
  dotted-path names."
  [:story.xray.app-db.diff-mode-toggle
   :story.xray.machine-inspector.snapshot-diff-mode-toggle
   :story.xray.epoch.subs-value-diff-mode-toggle])

(def ^:private universal-diff-mode-variant-ids
  "The nine variant ids that `gallery_diff_mode_universal.cljs` declares
  post-fix — three modes × three surfaces, each `:story.<path>/<mode>`."
  [:story.xray.app-db.diff-mode-toggle/full+diff
   :story.xray.app-db.diff-mode-toggle/full
   :story.xray.app-db.diff-mode-toggle/diff
   :story.xray.machine-inspector.snapshot-diff-mode-toggle/full+diff
   :story.xray.machine-inspector.snapshot-diff-mode-toggle/full
   :story.xray.machine-inspector.snapshot-diff-mode-toggle/diff
   :story.xray.epoch.subs-value-diff-mode-toggle/full+diff
   :story.xray.epoch.subs-value-diff-mode-toggle/full
   :story.xray.epoch.subs-value-diff-mode-toggle/diff])

(def ^:private universal-diff-mode-workspace-id
  :Workspace.xray.diff-mode-universal/all)

(deftest cascade-b-gallery-diff-mode-universal-ids-match-canonical-grammar
  (testing "every story id from gallery_diff_mode_universal.cljs passes
            schemas/story-id? (no slash separator — rf2-k1k87)"
    (doseq [sid universal-diff-mode-story-ids]
      (is (schemas/story-id? sid)
          (str (pr-str sid) " must match the :story.<dotted-path> grammar"))
      (is (nil? (namespace sid))
          (str (pr-str sid) " must have nil namespace — slash separator forbidden"))))
  (testing "every variant id from gallery_diff_mode_universal.cljs passes
            schemas/variant-id?"
    (doseq [vid universal-diff-mode-variant-ids]
      (is (schemas/variant-id? vid)
          (str (pr-str vid) " must match the :story.<path>/<variant> grammar"))))
  (testing "the workspace id passes schemas/workspace-id?"
    (is (schemas/workspace-id? universal-diff-mode-workspace-id))))

(deftest cascade-b-variants-are-claimed-by-their-stories
  (testing "every variant id from the gallery namespaces ties back to a
            registered story via the `(namespace vid) = (name sid)` rule
            (per `story/variants-of`). Story canvas walks this edge to
            list a story's variants; a mismatched name breaks the panel."
    ;; Register each story + each variant under canonical ids; assert
    ;; the registrar's `variants-of` picks them up. Catches the failure
    ;; mode where a variant id's namespace doesn't match its story id's
    ;; name (the original bug had `:story.xray.app-db/diff-mode-full+diff`
    ;; whose namespace was `story.xray.app-db`, NOT the registered story
    ;; `:story.xray.app-db/diff-mode` — so even with the grammar bug
    ;; sidestepped, the variants would have been orphaned).
    (doseq [[sid vid-prefix variant-tail]
            [[:story.xray.app-db.diff-mode-toggle
              "story.xray.app-db.diff-mode-toggle"
              ["full+diff" "full" "diff"]]
             [:story.xray.machine-inspector.snapshot-diff-mode-toggle
              "story.xray.machine-inspector.snapshot-diff-mode-toggle"
              ["full+diff" "full" "diff"]]
             [:story.xray.epoch.subs-value-diff-mode-toggle
              "story.xray.epoch.subs-value-diff-mode-toggle"
              ["full+diff" "full" "diff"]]]]
      (story/reg-story sid
        {:doc       (str "Universal diff-mode toggle — " (name sid))
         :component :smoke/comp
         :tags      #{:dev}})
      (doseq [tail variant-tail]
        (let [vid (keyword vid-prefix tail)]
          (story/reg-variant vid
            {:doc    (str (name sid) " in " tail " mode")
             :events []
             :tags   #{:dev :state/special}})))
      (let [variants (story/variants-of sid)]
        (is (= 3 (count variants))
            (str "story " (pr-str sid)
                 " owns 3 variants via the variants-of edge"))))))

;; ---- inventory-non-empty smoke (the headline contract) ---------------

(deftest gallery-inventory-non-empty-after-bulk-register
  (testing "after registering the post-fix story + variant ids, the
            Story-shell's variant inventory is non-empty. The bug shape
            was: an exception in one gallery aborted the load and the
            shell rendered with zero variants. The smoke shape: drive
            registrations that mirror the gallery shape and confirm the
            registrar reports non-empty inventory."
    (story/reg-story :story.smoke.parent
      {:doc       "Inventory smoke parent story."
       :component :smoke/comp
       :tags      #{:dev}})
    (doseq [tag schemas/canonical-state-tags]
      (story/reg-variant (keyword "story.smoke.parent" (name tag))
        {:doc    (str "Variant carrying " (pr-str tag))
         :events []
         :tags   #{:dev tag}}))
    (let [variants (story/variants-of :story.smoke.parent)]
      (is (seq variants) "registrar's variants-of reports non-empty")
      (is (= 5 (count variants))
          "every :state/* tag drove a variant — no :rf.error/unknown-tag aborts"))))

;; ---- per-gallery register-all! drive (rf2-nozqw) ---------------------

(def ^:private galleries
  "Every panel-gallery namespace's `register-all!` paired with a human
  label. Mirrors `panel-gallery.core`'s `:require` block — every
  gallery the boot loads must appear here so a future gallery addition
  fails this smoke until the registrar maintainer hooks it up.

  The pair-form (label + fn ref) keeps the assertion failure messages
  human-readable when one gallery's `register-all!` throws or yields
  empty inventory."
  [["gallery-app-db"               gallery-app-db/register-all!]
   ["gallery-chrome"                gallery-chrome/register-all!]
   ["gallery-diff-mode-3"           gallery-diff-mode-3/register-all!]
   ["gallery-diff-mode-universal"   gallery-diff-mode-universal/register-all!]
   ["gallery-edn-inspector"         gallery-edn-inspector/register-all!]
   ["gallery-epoch"                 gallery-epoch/register-all!]
   ["gallery-filters"               gallery-filters/register-all!]
   ["gallery-issues"                gallery-issues/register-all!]
   ["gallery-machines"              gallery-machines/register-all!]
   ["gallery-routing"               gallery-routing/register-all!]
   ["gallery-settings"              gallery-settings/register-all!]
   ["gallery-trace"                 gallery-trace/register-all!]
   ["gallery-views"                 gallery-views/register-all!]])

(deftest each-gallery-register-all-drops-non-empty-inventory
  (testing "every panel-gallery namespace's `register-all!` runs without
            throwing AND produces non-empty per-gallery inventory.

            Background — the rf2-k1k87 smoke caught the headline
            `:state/*` tag + diff-mode-id grammar cascade, but
            rf2-nozqw landed three follow-on bugs (lowercase
            `:workspace.xray.routing/all` typo; unregistered
            `:feature/opts` tag) that aborted ONE gallery's
            register-all! while the aggregate inventory stayed
            non-empty from the other galleries. That's exactly the
            shape this per-gallery loop catches: one gallery returns
            zero stories + zero variants + zero workspaces, the rest
            stay healthy.

            Mechanic — clear-all + install canonical tags ONCE per
            gallery, invoke the gallery's `register-all!`, snapshot
            the three side-table id sets, assert each is non-empty.
            Re-introducing Bug A (gallery_routing.cljs:137 →
            `:workspace.xray.routing/all` lowercase) drops the
            workspace count to zero for that gallery; re-introducing
            Bug B (gallery_edn_inspector.cljs `:feature/opts`
            unregistered) raises `:rf.error/unknown-tag` on the first
            variant tagged with it, halting the cascade and dropping
            story/variant counts."
    (doseq [[label register-all!] galleries]
      (story/clear-all!)
      (story/install-canonical-vocabulary!)
      ;; The gallery's register-all! is allowed to throw — that is
      ;; itself an authoring bug. `is` records the throw as a failure
      ;; with the label so the panel-gallery maintainer can spot
      ;; which gallery is broken without scanning the boot log.
      (is (try
            (register-all!)
            true
            (catch :default e
              (println "[panel-gallery-inventory-smoke]" label
                       "register-all! threw:" (ex-message e))
              false))
          (str label ": register-all! must not throw"))
      ;; Inventory delta — at least one story, at least one variant,
      ;; at least one workspace under the registrar after this
      ;; gallery's register-all! ran. Each L4 gallery contributes
      ;; exactly one story + N variants + one workspace; the smoke
      ;; pins the lower bound (NOT the exact count — a future
      ;; addition variant must not break the smoke).
      (let [story-ids     (story/ids :story)
            variant-ids   (story/ids :variant)
            workspace-ids (story/ids :workspace)]
        (is (seq story-ids)
            (str label ": at least one story registered"))
        (is (seq variant-ids)
            (str label ": at least one variant registered"))
        (is (seq workspace-ids)
            (str label ": at least one workspace registered"))))))
