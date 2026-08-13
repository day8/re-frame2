(ns re-frame.hicasso.mcp-runtime-query-spike-cljs-test
  "THE MCP-RUNTIME-QUERY SPIKE, DRIVEN ON AN APPLICATION IT DID NOT WRITE
  (rf2-hic-059).

  The criteria this file answers to were frozen first, in
  `docs/design/hicasso/product/mcp-runtime-query-spike.md`, and the
  verdict is recorded there. This is the measurement half and it decides
  nothing on its own.

  ## Why a second suite over the same four reads

  `re-frame.hicasso.tool-reads-cljs-test` already drives all four reads
  against a live runtime, and it is the better suite for what the
  projections SAY — its seeded-secret row, its determinism row and its
  two-loss-reason row are not repeated here and should not be.

  What it cannot answer is the only question this spike has. Its
  population is `:tr/left` and `:tr/right`, registered by the suite, read
  by bodies the suite wrote. A census over a population its own harness
  planted can report what the harness planted and nothing else, and this
  programme has already produced six of those — one that silently skipped
  106 files, one that dropped every non-keyword id, one that collected
  structurally nothing. **A query that can only answer what the fixture
  just told it proves nothing**, however green.

  So the population here is the `rf2-hic-025` SLICE APPLICATION, entire
  and unmodified: its routes, its seed, its six views, its events, its
  subscriptions. Nothing below registers a subscription, an event or a
  view. Every id that appears in a read's answer was written by another
  bead for another purpose, and the answers are asserted against the
  slice's own namespaced keywords — so a read that fabricated an empty
  roster, or answered out of the harness, fails here where it passes
  there.

  ## The application is booted through its OWN entry point

  [[boot!]] calls `re-frame.hicasso.examples.slice.app/make-frame!` — the
  function the slice's `-main` calls — so the frame, the route
  registration, the seed and the opening navigation are the
  application's, not this file's. What this file supplies is React's
  half: `collector/render-body` then `collector/commit-boundary!`, the
  same `subscribe` closure `useSyncExternalStore` calls, which is why a
  mount is answerable in Node at all. The bodies are reached with
  `codec/retained-body` off the minted heads, so they are the bodies the
  author wrote rather than copies.

  ## Every read is asked to fail before it is believed

  [[with-the-application-absent-every-read-answers-empty]] runs FIRST and
  is the whole licence for the rows after it. Each read is called against
  a booted-but-unmounted runtime and must answer empty. A read that
  answers non-empty whether or not the application is mounted is not
  reading the application, and its non-empty answer downstream would be a
  property of the reader rather than evidence about the app.

  ## And then asked to move

  Non-emptiness alone is a coincidence; **a number you cannot make move
  is not a measurement**. So each read is driven against two different
  populations of the same application and required to answer DIFFERENTLY:
  the feed route against the article route for the two rosters, and — the
  sharpest row here — a theme switch against a locale switch for
  `explain-render`, which are two real intents of this application whose
  answers must not be the same answer.

  Nothing under `src/` is changed by this suite, and it adds no export."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.evidence :as evidence]
            [re-frame.hicasso.examples.slice.app :as slice-app]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.hicasso.examples.slice.views :as views]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.tool :as tool]
            [re-frame.interop :as interop]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(def ^:private fid slice-app/frame-id)

;; ---------------------------------------------------------------------------
;; The harness — the slice's own entry, and React's half of a mount
;; ---------------------------------------------------------------------------

(defn- boot!
  "The slice application, started the way it starts itself.

  `make-frame!` registers the routes, mints the frame and dispatches the
  seed and the opening navigation — all of it the application's own code.
  The React-environment flag is set the way the sibling seam suites set
  it, because nothing here renders through React's act loop."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (slice-app/make-frame!)
  fid)

(defn- go!
  "Dispatch one of the application's own events into its own frame."
  [event-v]
  (rf/with-frame fid (rf/dispatch-sync event-v)))

(defn- mount!
  "Run one of the slice's OWN view bodies through the real commit seam and
  claim the reference React's passive effect would claim. Answers the
  unsubscribe React would hold."
  ([view] (mount! view {}))
  ([view props]
   (let [body (codec/retained-body view)]
     (collector/render-body fid body props)
     (collector/commit-boundary! (collector/last-reads) (fn [])))))

(defn- mount-feed!
  "The feed route as the application composes it: the shell, the chrome,
  the list, and one row per seeded article — the row props coming from
  the application's own `::subs/feed`, never from a literal here.

  rf2-hic-074 added two more boundaries to that route — the pager and the
  digest region — and they are deliberately NOT mounted here. This spike
  measures four envelopes over a known population, and every pin below is
  about the reads the bodies above make; widening the population would
  move each of them for a reason that has nothing to do with what the
  spike is asking. The slice's own suites own those two bodies."
  []
  (let [releases (atom [(mount! views/app) (mount! views/chrome) (mount! views/feed-page)])]
    (doseq [row @(rf/with-frame fid (rf/subscribe [::subs/feed]))]
      (swap! releases conj (mount! views/article-row row)))
    (fn [] (doseq [r @releases] (r)))))

(defn- mount-article!
  "The article route as the application composes it. `slug` reaches the
  editor the way `article-page` hands it over."
  [slug]
  (let [releases [(mount! views/app)
                  (mount! views/chrome)
                  (mount! views/article-page)
                  (mount! views/editor {:slug slug})]]
    (fn [] (doseq [r releases] (r)))))

;; ---------------------------------------------------------------------------
;; Readers over the four envelopes
;; ---------------------------------------------------------------------------

(defn- roster-sub-ids
  "Every registration id the mounted roster names, over every row."
  [envelope]
  (into #{} (mapcat (fn [row] (map :sub-id (:reads row)))) (:boundaries envelope)))

(defn- attribution-sub-ids [envelope]
  (into #{} (map :sub-id) (:edges envelope)))

(defn- intent-event-ids [envelope]
  (into #{} (map :event-id) (:intents envelope)))

(defn- holds?
  "Does this row's boundary key hold a read of `sub-id`?

  **This is the only way in.** A boundary is keyed by its edge set and
  carries no name — `:view` and `:source` are the explicit unknown,
  permanently — so a caller that can name a subscription reaches the
  boundary through the key rather than by asking for it. Each element of
  the key is the exported read identity `[frame-id sub-id projected-query]`."
  [row sub-id]
  (boolean (some (fn [read-identity] (= sub-id (nth read-identity 1)))
                 (:key (:boundary row)))))

(defn- explanation-for
  "The one explanation whose boundary holds a read of `sub-id`."
  [envelope sub-id]
  (first (filter #(holds? % sub-id) (:explanations envelope))))

(defn- latest-sub-ids [explanation]
  (into #{} (map :sub-id) (:latest-reads explanation)))

(defn- peak-of
  "The `:peak-epoch` of the boundary holding `sub-id`, right now."
  [sub-id]
  (:peak-epoch (explanation-for (tool/explain-render) sub-id)))

(defn- candidate-event-ids [explanation]
  (let [c (:candidates explanation)]
    (if (vector? c) (into #{} (map :event-id) c) c)))

;; ---------------------------------------------------------------------------
;; The suite states its own basis
;; ---------------------------------------------------------------------------

(deftest this-build-has-the-reads-enabled
  (testing "every row below is about a DEV build; production erasure is rf2-hic-024's"
    (is (true? interop/debug-enabled?))))

(deftest the-population-is-the-slice-applications-own-bodies
  (testing "each of the six views hands back the body its author wrote"
    (doseq [v [views/app views/chrome views/feed-page views/article-row
               views/article-page views/editor]]
      (is (fn? (codec/retained-body v))
          "a nil body would mean this suite mounted something else"))))

;; ---------------------------------------------------------------------------
;; A2c — the control. Every read must be able to answer empty.
;; ---------------------------------------------------------------------------

(deftest with-the-application-absent-every-read-answers-empty
  (boot!)
  (testing "the application is booted and its state is real — but nothing is mounted"
    (is (some? @(rf/with-frame fid (rf/subscribe [::subs/feed])))
        "the frame really is seeded, so an empty roster below is about MOUNTS"))
  (testing "the mounted roster is empty"
    (is (= [] (:boundaries (tool/read-mounted-boundaries)))))
  (testing "attribution has no edge, because no boundary holds a cell"
    (is (= [] (:edges (tool/read-read-attribution)))))
  (testing "explain-render has nothing to explain"
    (is (= [] (:explanations (tool/explain-render)))))
  (testing "and the reads still answer an ENVELOPE — empty is a result, not a failure"
    (let [e (tool/read-mounted-boundaries)]
      (is (= evidence/schema (:schema e)))
      (is (true? (:complete? e))
          "an empty roster on a basis that CAN see is a clean bill, not a loss"))))

;; ---------------------------------------------------------------------------
;; A2 — non-empty, on a population this suite did not author
;; ---------------------------------------------------------------------------

(deftest the-roster-names-the-slice-applications-own-subscriptions
  (boot!)
  (let [release (mount-feed!)
        e       (tool/read-mounted-boundaries)
        ids     (roster-sub-ids e)]
    (testing "the roster is NON-EMPTY on a real application"
      (is (pos? (count (:boundaries e))))
      (is (pos? (count ids))))
    (testing "and every id it names belongs to the slice, not to this suite"
      (is (contains? ids ::subs/feed))
      (is (contains? ids ::subs/t))
      (is (contains? ids ::subs/token))
      (is (contains? ids ::subs/tags-open?))
      (is (contains? ids :rf.route/id)
          "routing's own subscription — the slice keeps no route copy in app-db"))
    (testing "each row carries the frame the application minted"
      (is (every? #(contains? #{fid evidence/unknown} (:frame %)) (:boundaries e))))
    (release)))

(deftest attribution-names-the-slice-applications-own-subscriptions
  (boot!)
  (let [release (mount-feed!)
        e       (tool/read-read-attribution)
        ids     (attribution-sub-ids e)]
    (testing "the reverse edge is NON-EMPTY and exact"
      (is (pos? (count (:edges e))))
      (is (true? (:complete? e)))
      (is (nil? (:loss e))))
    (testing "the slice's own strings and tokens are among the keys held"
      (is (contains? ids ::subs/t))
      (is (contains? ids ::subs/token))
      (is (contains? ids ::subs/feed)))
    (testing "fan-out counts real readers — the string table is read by more than one boundary"
      (let [t-edges (filter #(= ::subs/t (:sub-id %)) (:edges e))]
        (is (pos? (count t-edges)))
        (is (every? #(pos? (:fan-out %)) t-edges))))
    (testing "a subscription the slice registers but nothing on this route reads is ABSENT"
      (is (not (contains? ids ::subs/save-state))
          "the editor is not on the feed route, so its save projection is unheld"))
    (release)))

(deftest the-intent-stream-names-the-slice-applications-own-events
  (boot!)
  (let [release (mount-feed!)]
    (go! [::events/set-theme {:theme :dark}])
    (let [e   (tool/read-intents)
          ids (intent-event-ids e)]
      (testing "the stream is NON-EMPTY and names the application's own event"
        (is (pos? (count (:intents e))))
        (is (contains? ids ::events/set-theme)))
      (testing "a run carries what it recomputed — the slice's own subscription ids"
        (let [row (first (filter #(= ::events/set-theme (:event-id %)) (:intents e)))]
          (is (some? row))
          ;; `:sub-ids` egresses as an ORDERED VECTOR, not the set
          ;; `intent-row` builds — `ordered` imposes one total order so two
          ;; calls in a quiescent turn `pr-str` identically. A consumer that
          ;; reaches for `contains?` is asking about INDICES and gets a
          ;; quiet false; this suite made that mistake first.
          (is (= [::subs/listed ::subs/locale ::subs/tags-open? ::subs/theme ::subs/token]
                 (:sub-ids row))
              "the run recomputed the whole feed route's read set, ordered.

               `::subs/listed` stands where `::subs/feed` did before
               rf2-hic-074, and the swap is the substrate working rather
               than the roster drifting: pagination made `::feed` a LAYER-2
               read chaining from `::listed`, so a theme change moves
               app-db, recomputes the layer-1 projection, finds its value
               `=` to the last one and stops there. The rows the list
               renders are not recomputed at all when the theme changes,
               which is the whole point of putting a layer between the db
               and the view")))
      (testing "no row carries an event VECTOR, on this application as on any other"
        (is (not-any? #(contains? % :event) (:intents e)))))
    (release)))

;; ---------------------------------------------------------------------------
;; A3 — divergence. The same read, two populations, two answers.
;; ---------------------------------------------------------------------------

(deftest the-two-rosters-diverge-between-the-applications-two-routes
  (boot!)
  (let [feed-release (mount-feed!)
        feed-ids     (roster-sub-ids (tool/read-mounted-boundaries))
        feed-edges   (attribution-sub-ids (tool/read-read-attribution))]
    (feed-release)
    (go! [:rf.route/navigate {:to routes/article :params {:slug "intents"}}])
    (let [art-release (mount-article! "intents")
          art-ids     (roster-sub-ids (tool/read-mounted-boundaries))
          art-edges   (attribution-sub-ids (tool/read-read-attribution))]
      (testing "the two answers are DIFFERENT, and the difference is the route"
        (is (not= feed-ids art-ids))
        (is (not= feed-edges art-edges)))
      (testing "what only the feed holds"
        (is (contains? feed-ids ::subs/feed))
        (is (not (contains? art-ids ::subs/feed))))
      ;; THREE, and it was four until rf2-36bd. `::subs/revision` was the
      ;; fourth, and the slice no longer has one: the counter behind it was
      ;; measured inert and removed. See the slice's `db` namespace docstring.
      (testing "what only the article holds — the editor's three reads"
        (is (contains? art-ids ::subs/draft))
        (is (contains? art-ids ::subs/dirty?))
        (is (contains? art-ids ::subs/save-state))
        (is (not (contains? feed-ids ::subs/draft))))
      (testing "and the shell's reads are on BOTH — a divergence that moved everything
                would be a reset rather than a difference"
        (is (contains? feed-ids ::subs/token))
        (is (contains? art-ids ::subs/token)))
      (art-release))))

(deftest explain-render-tells-a-theme-switch-from-a-locale-switch
  (testing "ONE boundary — the chrome — two real intents of this application,
            and the two answers must not be the same answer"
    (boot!)
    (let [release (mount-feed!)]
      (go! [::events/set-theme {:theme :dark}])
      (let [ex (explanation-for (tool/explain-render) ::subs/theme)]
        (is (some? ex) "the chrome holds the theme read, so it has an explanation")
        (is (= #{::subs/theme} (latest-sub-ids ex))
            "a theme switch moved the theme read and nothing else the chrome holds")
        (is (contains? (candidate-event-ids ex) ::events/set-theme)
            "and the run that moved it is offered as a lead, by its own id"))
      (release))

    (boot!)
    (let [release (mount-feed!)]
      (go! [::events/set-locale :fr])
      (let [ex (explanation-for (tool/explain-render) ::subs/theme)]
        (is (some? ex))
        (is (= #{::subs/t} (latest-sub-ids ex))
            "the locale moved the STRING TABLE and not the theme read — and not
             the locale read either, which sits one stamp lower because `::t`
             is a layer-2 read over it and was re-stamped after it. The same
             boundary, a different intent, a different answer.")
        (is (contains? (candidate-event-ids ex) ::events/set-locale)))
      (release))))

(deftest a-locale-switch-moves-the-root-too-and-this-spike-expected-otherwise
  (testing "MEASURED, against this spike's own written expectation, which was wrong.

            The slice's shell reads two THEME tokens for its background and ink
            and nothing else that a locale could touch, so the expectation
            written here first was that a locale switch would leave the root's
            `:peak-epoch` standing while a theme switch moved it. It does not:
            the root's peak rose on the locale switch too. The reading is not
            offered as a diagnosis of the slice — an epoch is a re-stamp, and
            whether React then bailed out on the memo comparator is
            `:host-opaque` and stays so. What is measured is narrower and is
            the point: `:peak-epoch` on THIS boundary does not separate these
            two intents, and a spike that had asserted its expectation without
            driving it would have published the opposite.

            The row BELOW says why the root moves, and the reason is a read the
            root's body does not look like it makes."
    (boot!)
    (let [release (mount-feed!)
          root-0  (peak-of ::subs/token)]
      (is (number? root-0) "the shell holds the token reads")
      (go! [::events/set-locale :fr])
      (let [root-1 (peak-of ::subs/token)]
        (is (> root-1 root-0)
            "the locale moved the root's peak — the expectation written here was
             that it would not")
        (go! [::events/set-theme {:theme :dark}])
        (is (> (peak-of ::subs/token) root-1)
            "and so does the theme, so the two are not told apart by this number"))
      (release))))

(deftest the-root-holds-a-string-read-its-own-body-does-not-look-like-it-holds
  (testing "WHY the root moves on a locale switch, which the row above measured
            and could not explain — and the sharpest thing this spike found.

            `views/app` reads two theme tokens and the route id. It also reads
            the string table, and the reason is not visible where a reader
            looks for reads: the `h/error-boundary` fallback is MARKUP written
            in the root's own body, so `(h/sub [::subs/t :app/pane-error])`
            inside it is evaluated when the root runs — not when the boundary
            catches. The root therefore holds a `::t` edge and re-stamps on
            every locale change, for a string that is on screen only after a
            pane has thrown.

            Nothing is wrong here and the slice is not being accused of a bug:
            the fallback has to be built somewhere and its own docstring
            explains why it is built there. What is being recorded is that the
            edge is a RUNTIME fact this read states outright and a reading of
            the body does not, which is the whole question the spike was set."
    (boot!)
    (let [release (mount-feed!)
          root    (explanation-for (tool/explain-render) ::subs/token)]
      (is (some? root))
      (is (holds? root ::subs/t)
          "the root's own edge set holds the string table")
      (is (holds? root :rf.route/id)
          "beside the route id, which its body reads plainly")
      (release))

    (boot!)
    (let [release-a    (mount-feed!)
          _            (go! [::events/set-locale :fr])
          after-locale (latest-sub-ids (explanation-for (tool/explain-render) ::subs/token))]
      (release-a)
      (boot!)
      (let [release-b   (mount-feed!)
            _           (go! [::events/set-theme {:theme :dark}])
            after-theme (latest-sub-ids (explanation-for (tool/explain-render) ::subs/token))]
        (is (= #{::subs/t} after-locale)
            "so the ROOT diverges too: a locale switch leaves the string table
             standing at its maximum")
        (is (= #{::subs/token} after-theme)
            "and a theme switch leaves the tokens there instead")
        (is (not= after-locale after-theme)
            "two intents, one boundary, two answers")
        (release-b)))))
