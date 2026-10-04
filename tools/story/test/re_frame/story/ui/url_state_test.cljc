(ns re-frame.story.ui.url-state-test
  "Pure tests for the URL-state engine.

  The CLJS browser surface (pushState / popstate / window.location) is
  exercised in `re-frame.story.ui.url-state-cljs-test` and in the
  Playwright browser scenario. This ns pins the pure pieces:

  - `params-from-state`     — project shell-state slots onto build-params shape.
  - `url-from-state`        — full path + query + hash composition, and the
                              ownership boundary it shares with the share
                              builder.
  - `url-relevant-slots-changed?` — diff for the state watcher.
  - `apply-parsed-to-state` — fold parsed slots back into shell-state."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.url-state :as rf.story.ui.url-state]))

;; ---- params-from-state ---------------------------------------------------

;; Exact `=` on the whole projection, so a nil-valued extra key fails a row.

(deftest params-from-state-projects-exactly-the-focused-slots
  (are [shell expected] (= expected (rf.story.ui.url-state/params-from-state shell))
    ;; an empty shell state projects to {}
    {}
    {}

    ;; a focused variant projects to {:variant-id ...} only
    {:selected-variant :story.foo/bar}
    {:variant-id :story.foo/bar}

    ;; a focused workspace projects to {:workspace-id ...} only
    {:selected-workspace :story.foo/grid}
    {:workspace-id :story.foo/grid}

    ;; mode-tab is projected from the focused variant's slot
    {:selected-variant :story.foo/bar
     :active-mode-tab  {:story.foo/bar :docs
                        :story.other/x :test}}
    {:variant-id :story.foo/bar
     :mode-tab   :docs}

    ;; cell-overrides only project for the focused variant
    {:selected-variant :story.foo/bar
     :cell-overrides   {:story.foo/bar  {:label "Hi"}
                        :story.other/x  {:label "Hidden"}}}
    {:variant-id     :story.foo/bar
     :cell-overrides {:label "Hi"}}))

;; ---- url-from-state ------------------------------------------------------

(deftest url-from-state-includes-every-populated-slot
  (testing "every URL-relevant shell slot reaches the composed query"
    (let [url (rf.story.ui.url-state/url-from-state
                {:selected-variant   :foo/bar
                 :active-mode-tab    {:foo/bar :docs}
                 :active-modes       [:m/dark]
                 :viewport           :tablet
                 :background         :dark
                 :tag-filter         #{:tag/a}
                 :substrate          :uix}
                {:pathname "/foo/" :hash "#/stories"})]
      (is (= (str "/foo/?variant=foo%2Fbar&mode-tab=docs&modes=m%2Fdark"
                  "&viewport=tablet&background=dark&tag-filter=tag%2Fa"
                  "&substrate=uix#/stories")
             url)))))

;; ---- the address-bar writer owns the Story vocabulary only --------------
;;
;; `url-from-state` is the SECOND writer of the address-bar URL — the
;; state-watcher pushes its output on every URL-relevant change. Composing
;; from `{:pathname :hash}` alone, rebuilding the query from shell state
;; and discarding `location.search` wholesale, would make the first state
;; change after mount erase every param Story does not own: `embed=1`
;; (chrome state, read once at mount and never round-tripped), a referrer
;; `from=`, a host page's analytics params.
;;
;; Story owns exactly `rf.story.share/story-query-keys` and preserves
;; everything else. These pin that this writer applies the SAME boundary as
;; the share builder — it calls the same merge — so the two cannot drift
;; about who owns what.

(deftest url-from-state-clears-every-stale-story-key
  (testing "the clear set is the whole vocabulary, not the
            subset this state emits: a slot the shell leaves empty must
            not leave a stale value standing for the hydrator to restore
            (the same omission rule the share builder applies)"
    (let [stale {"variant"    "story.old%2Fa"
                 "workspace"  "story.old%2Fws"
                 "mode-tab"   "docs"
                 "modes"      "Mode.app%2Fstale"
                 "viewport"   "tablet"
                 "background" "dark"
                 "tag-filter" "stale"
                 "overrides"  "%7B%3Afoo%201%7D"
                 "substrate"  "uix"}
          search (str "?"
                      (str/join "&" (map #(str (name %) "=" (get stale (name %)))
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1")
          url    (rf.story.ui.url-state/url-from-state
                   ;; Only `:selected-variant` populated — every other slot
                   ;; is omitted by `rf.story.share/build-params`.
                   {:selected-variant :story.new/b}
                   {:pathname "/p/" :search search :hash "#/stories"})]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "/p/?from=index&embed=1&variant=story.new%2Fb#/stories" url)
          "every stale Story key is gone; both unowned params survive"))))

(deftest url-from-state-no-bare-question-mark-when-query-empties
  (testing "clearing the last Story key off a search that held
            nothing else leaves no dangling `?`; a bare `?` differs from
            `location.search`'s empty string, so the watcher would push a
            cosmetic history entry the shell can never match again"
    (is (= "/p/#/stories"
           (rf.story.ui.url-state/url-from-state {} {:pathname "/p/"
                                  :search   "?variant=story.old%2Fa"
                                  :hash     "#/stories"})))))

(deftest url-from-state-and-share-builder-agree-on-ownership
  (testing "the two writers of this URL apply one boundary
            because they call one merge: given the same base and the same
            cell, the address-bar composer and the share-URL builder
            produce the same string"
    (let [pathname "/counter-with-stories/"
          search   "?from=index&embed=1&substrate=uix"
          hash     "#/stories"]
      (is (= (rf.story.share/variant-share-url
               :story.new/b
               (str pathname search hash)
               {:substrate :reagent})
             (rf.story.ui.url-state/url-from-state
               {:selected-variant :story.new/b :substrate :reagent}
               {:pathname pathname :search search :hash hash}))
          "same base + same cell ⇒ same URL from either writer"))))

;; ---- the address bar owns ESCAPED spellings too ------------------------
;;
;; Both writers share `rf.story.share/apply-story-params`. `URLSearchParams`
;; compares DECODED names, so ownership matched on raw key text would let a
;; `location.search` spelling a Story key with escapes survive, with the
;; generated value appended behind it. `.get` is first-value, so the next
;; reload would restore the stale cell — the very thing sharing one merge
;; exists to make impossible. The pin is on the address-bar writer
;; specifically: the key decoding lives in the shared helper, so this and
;; the share-builder half must move together or not at all.

(deftest url-from-state-clears-every-escaped-story-key
  (testing "the whole vocabulary, spelled with escapes:
            every Story key goes, both unowned params stay. Derived from
            `rf.story.share/story-query-keys` so a key added to the vocabulary is
            covered here without editing this test."
    (let [escape #(str "%" (format "%02X" (int (first %))) (subs % 1))
          stale  {"variant"    "story.old%2Fa"
                  "workspace"  "story.old%2Fws"
                  "mode-tab"   "docs"
                  "modes"      "Mode.app%2Fstale"
                  "viewport"   "tablet"
                  "background" "dark"
                  "tag-filter" "stale"
                  "overrides"  "%7B%3Afoo%201%7D"
                  "substrate"  "uix"}
          search (str "?"
                      (str/join "&" (map #(str (escape (name %))
                                               "="
                                               (get stale (name %)))
                                         rf.story.share/story-query-keys))
                      "&from=index&embed=1")]
      (is (= (set (map name rf.story.share/story-query-keys)) (set (keys stale)))
          "the fixture carries a stale value for every key in the vocabulary")
      (is (= "/p/?from=index&embed=1&variant=story.new%2Fb#/stories"
             (rf.story.ui.url-state/url-from-state
               {:selected-variant :story.new/b}
               {:pathname "/p/" :search search :hash "#/stories"}))
          "every escaped Story key is cleared; both unowned params survive"))))

;; ---- url-relevant-slots-changed? ----------------------------------------

(deftest url-relevant-slots-changed-diffs-only-url-slots
  (testing "a change to any URL-owned slot triggers a push"
    (are [old new] (rf.story.ui.url-state/url-relevant-slots-changed? old new)
      {:selected-variant :foo/a}       {:selected-variant :foo/b}
      {:selected-workspace :foo/a}     {:selected-workspace :foo/b}
      {:active-mode-tab {:foo/a :dev}} {:active-mode-tab {:foo/a :docs}}
      {:viewport :full}                {:viewport :tablet}
      {:background :light}             {:background :dark}
      {:tag-filter #{}}                {:tag-filter #{:tag/a}}
      {:active-modes []}               {:active-modes [:m/dark]}
      {:substrate :reagent}            {:substrate :uix}

      ;; a controls edit on the FOCUSED variant's overrides pushes, so the
      ;; live address bar round-trips the override
      {:selected-variant :foo/a :cell-overrides {}}
      {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}}

      {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}}
      {:selected-variant :foo/a :cell-overrides {:foo/a {:x 2}}}))
  (testing "changes to non-URL slots (hot-reload-tick, fingerprints,
            panel-visibility) do NOT trigger a push"
    (are [old new] (not (rf.story.ui.url-state/url-relevant-slots-changed? old new))
      {:selected-variant :foo/a :hot-reload-tick 0}
      {:selected-variant :foo/a :hot-reload-tick 99}

      {:selected-variant :foo/a :fingerprints {}}
      {:selected-variant :foo/a :fingerprints {:foo/a {:dec :h}}}

      {:selected-variant :foo/a :panel-visibility {:trace true}}
      {:selected-variant :foo/a :panel-visibility {:trace false}}

      ;; a NON-focused variant's overrides stay off the URL
      {:selected-variant :foo/a :cell-overrides {:foo/b {:x 1}}}
      {:selected-variant :foo/a :cell-overrides {:foo/b {:x 2}}}

      ;; with no focused variant, an overrides edit never pushes
      {:selected-variant nil :cell-overrides {}}
      {:selected-variant nil :cell-overrides {:foo/a {:x 1}}})))

;; ---- apply-parsed-to-state ----------------------------------------------

(deftest apply-parsed-workspace-only
  (let [out (rf.story.ui.url-state/apply-parsed-to-state
              {} {:workspace-id :foo/grid} {})]
    (is (= :foo/grid (:selected-workspace out)))
    (is (nil? (:selected-variant out)))))

(deftest apply-parsed-variant-wins-over-workspace
  (testing "variant click clears :selected-workspace. When the
            URL carries BOTH (a teammate crafted a URL or a stale
            bookmark), variant wins."
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {:selected-workspace :foo/old}
                {:variant-id   :foo/bar
                 :workspace-id :foo/grid}
                {})]
      (is (= :foo/bar (:selected-variant out)))
      (is (nil? (:selected-workspace out))))))

(deftest apply-parsed-validators-drop-unknown-workspace
  (testing "unknown workspace id is dropped"
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:workspace-id :ghost/grid}
                {:workspace? (fn [_] false)})]
      (is (nil? (:selected-workspace out))))))

;; ---- substrate authoritative-clear ---------------------------------------
;;
;; The build side omits `substrate=` to encode the `:reagent` default
;; (`rf.story.share/build-params` emits it only for a non-default substrate). So
;; an omitted/nil parsed substrate MUST hydrate as `:reagent`, not preserve
;; the recipient's stale in-memory `:uix`. This mirrors the
;; authoritative-clear discipline the other URL-owned chrome slots
;; get. The same single fn drives mount-time hydration AND
;; no-query popstate (via `parse-current-url-or-empty`), so both paths heal.

(deftest apply-parsed-explicit-non-default-substrate-round-trips
  (testing "an explicit non-default substrate hydrates to
            that substrate; only omitted/invalid values fall back.
            With a :substrate? validator that registers :uix it is kept; with
            no validator any present id is accepted (registry unreachable)."
    (let [with-validator (rf.story.ui.url-state/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :uix}
                           {:substrate? #{:reagent :uix}})
          no-validator   (rf.story.ui.url-state/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :custom} {})]
      (is (= :uix (:substrate with-validator))
          "registered non-default substrate is kept")
      (is (= :custom (:substrate no-validator))
          "with no validator a present substrate is accepted as-is"))))

(deftest apply-parsed-invalid-substrate-degrades-to-reagent
  (testing "a present-but-UNREGISTERED substrate (rejected by the
            :substrate? validator) degrades to the :reagent default rather than
            preserving the prior local substrate, so a stale URL can't pin a
            substrate the host app never registered."
    (let [stale {:substrate :uix}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:substrate :ghost-substrate}
                  {:substrate? #{:reagent :uix}})]
      (is (= :reagent (:substrate out))
          "unregistered substrate= clears to :reagent, not the stale :uix"))))

;; ---- cell-overrides hydration --------------------------------------------

(deftest apply-parsed-installs-cell-overrides-under-focused-variant
  (testing "parsed :cell-overrides are written under
            [:cell-overrides variant-id] so a shared URL / popstate
            restores the same effective args, not just the selection.
            A destructure that dropped :cell-overrides would restore the
            selection alone."
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:variant-id     :story.foo/bar
                    :cell-overrides {:label "Hi"}} {})]
      (is (= :story.foo/bar (:selected-variant out)))
      (is (= {:label "Hi"} (get-in out [:cell-overrides :story.foo/bar]))
          "overrides land under the focused variant"))))

(deftest apply-parsed-overrides-dropped-when-variant-invalid
  (testing "overrides are variant-scoped: when the variant id
            is rejected by the validator (stale URL) the overrides are
            not installed (no orphan slice under an unfocused variant)"
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:variant-id     :ghost/x
                    :cell-overrides {:label "Hi"}}
                {:variant? (fn [vid] (= vid :foo/bar))})]
      (is (nil? (:selected-variant out)))
      (is (nil? (get-in out [:cell-overrides :ghost/x]))
          "no overrides installed for a rejected variant"))))

;; ---- URL is authoritative — clear stale overrides on hydrate -------------

(deftest apply-parsed-overwrites-stale-overrides-when-url-has-new
  (testing "a URL that carries DIFFERENT overrides for the kept
            variant overwrites the stale slice (not a deep-merge); the slice
            equals the URL payload exactly"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar {:label "stale" :keep "old"}}}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id     :story.foo/bar
                         :cell-overrides {:label "fresh"}} {})]
      (is (= {:label "fresh"} (get-in out [:cell-overrides :story.foo/bar]))
          "the slice is REPLACED by the URL payload, dropping the stale :keep"))))

(deftest apply-parsed-clear-leaves-other-variants-overrides-intact
  (testing "clearing the focused variant's overrides touches ONLY
            its slice; another variant's overrides survive (the URL speaks for
            the focused variant alone)"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar   {:label "stale"}
                                    :story.other/baz {:n 7}}}
          out   (rf.story.ui.url-state/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (nil? (get-in out [:cell-overrides :story.foo/bar]))
          "the focused variant's slice is cleared")
      (is (= {:n 7} (get-in out [:cell-overrides :story.other/baz]))
          "an unfocused variant's overrides are left intact"))))

;; ---- mode-tab is URL-authoritative too (per-variant) --------------------
;;
;; `:active-mode-tab` gets the SAME authoritative-clear treatment as
;; `:cell-overrides` above — it is per-variant, so the clear
;; is scoped to the focused variant via `dissoc`, NOT an unconditional
;; `:always` write like the global slots below.

(deftest apply-parsed-sets-mode-tab-when-url-carries-it
  (testing "a URL that DOES carry mode-tab= overwrites a stale
            entry (authoritative, not a merge) — the sibling of the clear
            test above"
    (let [stale {:selected-variant :story.foo/bar
                 :active-mode-tab  {:story.foo/bar :test}}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id :story.foo/bar :mode-tab :docs} {})]
      (is (= :docs (get-in out [:active-mode-tab :story.foo/bar]))))))

(deftest apply-parsed-clears-mode-tab-leaves-other-variants-intact
  (testing "clearing the focused variant's mode-tab touches
            ONLY its entry; another variant's mode-tab survives (mode-tab
            is per-variant, the URL speaks for the focused variant alone)"
    (let [stale {:selected-variant :story.foo/bar
                 :active-mode-tab  {:story.foo/bar   :docs
                                    :story.other/baz :test}}
          out   (rf.story.ui.url-state/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (nil? (get-in out [:active-mode-tab :story.foo/bar]))
          "the focused variant's stale mode-tab is cleared")
      (is (= :test (get-in out [:active-mode-tab :story.other/baz]))
          "an unfocused variant's mode-tab is left intact"))))

(deftest apply-parsed-mode-tab-untouched-when-variant-not-kept
  (testing "mode-tab is per-variant: when NO variant is kept
            (e.g. an invalid/rejected variant id, or none at all) there is
            no focused variant's entry to clear, so OTHER variants'
            mode-tab entries are left alone entirely (unlike the global
            slots below, this is not an unconditional :always write)"
    (let [stale {:active-mode-tab {:story.other/baz :test}}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id :ghost/x :mode-tab :docs}
                  {:variant? (fn [_] false)})]
      (is (nil? (:selected-variant out)))
      (is (= {:story.other/baz :test} (:active-mode-tab out))
          "no variant kept -> the URL's mode-tab is written nowhere and
           the mode-tab map is untouched"))))

;; ---- URL authoritative for ALL URL-owned chrome slots --------------------

(deftest apply-parsed-sets-active-modes-when-url-carries-them
  (testing "a URL that DOES carry modes= overwrites stale modes
            (authoritative, not a merge)"
    (let [stale {:active-modes [:m/light]}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id :foo/bar :active-modes [:m/dark]} {})]
      (is (= [:m/dark] (:active-modes out))))))

(deftest apply-parsed-clears-invalid-viewport-rather-than-keeping-stale
  (testing "a present-but-INVALID viewport (rejected by the
            validator) clears the slot rather than preserving the stale
            in-memory value, so a poisoned URL still degrades to default"
    (let [stale {:viewport :tablet}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:viewport :nonsense}
                  {:viewport? (fn [v] (= v :phone))})]
      (is (nil? (:viewport out))
          "invalid viewport= clears the stale value, not preserves it"))))

(deftest apply-parsed-clears-invalid-background-rather-than-keeping-stale
  (testing "a present-but-INVALID background clears the slot"
    (let [stale {:background :dark}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:background :nonsense}
                  {:background? (fn [b] (= b :light))})]
      (is (nil? (:background out))))))

(deftest apply-parsed-empty-url-clears-every-url-owned-slot
  (testing "applying the all-nil parsed shape (a no-query
            popstate / share URL that omits everything) clears ALL
            URL-owned slots back to their defaults — selection, modes,
            viewport, background, tag-filter — so the address bar is the
            source of truth even when it carries no params."
    (let [stale {:selected-variant :foo/bar
                 :active-modes     [:m/dark]
                 :viewport         :tablet
                 :background       :dark
                 :tag-filter       #{:tag/a}
                 :substrate        :uix}
          ;; the shape rf.story.share/parse-params produces for an empty getter:
          empty-parsed {:variant-id nil :workspace-id nil :mode-tab nil
                        :active-modes nil :viewport nil :background nil
                        :tag-filter nil :cell-overrides nil :substrate nil}
          out   (rf.story.ui.url-state/apply-parsed-to-state stale empty-parsed {})]
      (is (nil? (:selected-variant out)))
      (is (nil? (:selected-workspace out)))
      (is (= [] (:active-modes out)))
      (is (nil? (:viewport out)))
      (is (nil? (:background out)))
      (is (= #{} (:tag-filter out)))
      ;; Substrate is a URL-owned slot too: a bare URL clears it
      ;; to the :reagent default rather than leaking the stale :uix.
      (is (= :reagent (:substrate out))))))

(deftest apply-parsed-share-counter-link-restores-default-chrome
  (testing "the concrete scenario: a recipient with prior
            localStorage-seeded chrome (modes/viewport/background/tag-filter)
            opens a share link `?variant=story.counter/loaded` that carries
            ONLY the variant. The recipient lands on the variant with the
            DEFAULT chrome, not their stale local framing."
    (let [recipient-local {:active-modes [:m/dark]
                           :viewport     :phone
                           :background   :light
                           :tag-filter   #{:tag/legacy}
                           :substrate    :uix}
          ;; rf.story.share/parse-params of just ?variant=story.counter/loaded
          shared {:variant-id :story.counter/loaded}
          out    (rf.story.ui.url-state/apply-parsed-to-state recipient-local shared {})]
      (is (= :story.counter/loaded (:selected-variant out)))
      (is (= [] (:active-modes out)) "recipient's local modes cleared")
      (is (nil? (:viewport out))     "recipient's local viewport cleared")
      (is (nil? (:background out))   "recipient's local background cleared")
      (is (= #{} (:tag-filter out))  "recipient's local tag-filter cleared")
      ;; The omitted substrate= means :reagent, so the
      ;; recipient's stale :uix is cleared to the default.
      (is (= :reagent (:substrate out)) "recipient's local substrate cleared"))))

(deftest apply-parsed-full-round-trip-through-state
  (testing "URL → parsed → state, then state → URL produces equivalent
            params (allowing for set vs vec re-ordering)"
    (let [parsed   {:variant-id   :foo/bar
                    :mode-tab     :docs
                    :active-modes [:m/dark]
                    :viewport     :tablet
                    :background   :dark
                    :tag-filter   #{:tag/a :tag/b}
                    :substrate    :uix}
          state    (rf.story.ui.url-state/apply-parsed-to-state {} parsed {})
          re-proj  (rf.story.ui.url-state/params-from-state state)]
      (is (= :foo/bar       (:variant-id   re-proj)))
      (is (= :docs          (:mode-tab     re-proj)))
      (is (= [:m/dark]      (:active-modes re-proj)))
      (is (= :tablet        (:viewport     re-proj)))
      (is (= :dark          (:background   re-proj)))
      (is (= #{:tag/a :tag/b} (:tag-filter  re-proj)))
      (is (= :uix           (:substrate    re-proj))))))
