(ns realworld.settings-test
  "Headless tests for realworld.settings.

   Covers the `:settings/form` machine — the :form-region machine variant
   of Pattern-Forms:
   - lifecycle happy path (neutral → submitting → correct) — settings
     save propagates new user data into the auth slice;
   - failure path (submitting → incorrect) — the projected failure
     message lands in `:submit-error` and the in-flight tag drops;
   - validation path (neutral → incorrect → neutral) — direct
     broadcasts exercise the `:submit-invalid` / `:edit` transitions;
   - tag-shaped queries — `:settings/in-flight` replaces the slice's
     `:submitting?` boolean."
  (:require [re-frame.core :as rf]
            [realworld.settings]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn- snapshot [db]
  (get-in db [:rf/machines :settings/form]))

(defn- has-tag?
  "Read the machine's :tags union against a frame's app-db (browserless
   form of `rf/has-tag?` — uses `compute-sub` instead of a reactive
   deref so the test runs in any CLJS host)."
  [frame tag]
  (rf/compute-sub [:rf/machine-has-tag? :settings/form tag]
                  (rf/get-frame-db frame)))

(defn settings-test []
  ;; Happy-path lifecycle. The assertions below are the SAME questions
  ;; a slice-form reader would ask, but each answer comes from a
  ;; different surface:
  ;;
  ;;     SLICE FORM                              MACHINE FORM
  ;;     ----------                              ------------
  ;;     (:status slice) = :submitted            (:state snap)  = :correct
  ;;     (:draft slice)                          (-> snap :data :draft)
  ;;     :submitting? (a derived boolean sub)    (has-tag? :settings/in-flight)
  (th/reg-canned-success! :rf.http/managed.canned-settings-save
                          {:user {:email "alice@example.com"
                                  :token "jwt-2"
                                  :username "alice"
                                  :bio "New bio"
                                  :image nil}})

  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed    :rf.http/managed.canned-settings-save
                                                :auth.session/persist :rf/no-op}})]
    ;; After :app/initialise → :settings/initialise → [:reset], the
    ;; machine sits at :neutral with empty :data.
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :neutral (:state snap)))
      (assert (= ""       (get-in snap [:data :draft :bio])))
      (assert (false?     (has-tag? f :settings/in-flight))))

    ;; Seed the auth slice + load the settings draft from the user.
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :neutral (:state snap)))
      (assert (= "alice"  (get-in snap [:data :draft :username]))))

    ;; Edit a field. The :touched set tracks user interaction; the
    ;; region stays at :neutral (a fresh edit doesn't trigger a
    ;; transition out of :correct / :incorrect unless we were there).
    (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :neutral  (:state snap)))
      (assert (= "New bio" (get-in snap [:data :draft :bio])))
      (assert (contains?   (get-in snap [:data :touched]) :bio)))

    ;; Submit. The canned-success stub resolves synchronously, so we
    ;; observe the machine in :correct (not :submitting) after the
    ;; dispatch returns. The slice's `:status :submitted` and
    ;; `:submitted draft` are now the machine's `:state :correct` +
    ;; `:data :draft` (re-seeded from the server-returned user).
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (let [db   (rf/get-frame-db f)
          snap (snapshot db)]
      (assert (= :correct (:state snap)))
      (assert (= "New bio" (get-in snap [:data :draft :bio])))
      (assert (nil?        (get-in snap [:data :submit-error])))
      ;; tag-shaped query — this replaces the slice's `:settings/submitting?`
      ;; derived boolean sub. After the synchronous reply, the region
      ;; is in :correct and the in-flight tag has dropped.
      (assert (false? (has-tag? f :settings/in-flight)))
      (assert (true?  (has-tag? f :form/success)))
      ;; the :auth slice has the new user data (the side-effect the
      ;; original test asserted).
      (assert (= "New bio" (get-in db [:auth :user :bio])))
      ;; the `:settings/submitting?` sub returns false (same name a
      ;; slice-form reader would use; only the source changed).
      (assert (false? (rf/compute-sub [:settings/submitting?] db))))))

(defn settings-failure-test []
  ;; Failure path — the machine lands in :incorrect with the projected
  ;; failure message in :data :submit-error, the in-flight tag drops,
  ;; and the form-level error surface is the same one validation
  ;; would use (per Pattern-Forms — both paths render via :errors /
  ;; :submit-error).
  (th/reg-canned-failure! :rf.http/managed.canned-settings-failure
                          :rf.http/http-5xx
                          {:status 500 :body "server error"})
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                 :fx-overrides {:rf.http/managed    :rf.http/managed.canned-settings-failure
                                                :auth.session/persist :rf/no-op}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (rf/dispatch-sync [:settings/edit-field :bio "Doomed bio"] {:frame f})
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (let [db   (rf/get-frame-db f)
          snap (snapshot db)]
      (assert (= :incorrect (:state snap)))
      (assert (some? (get-in snap [:data :submit-error])))
      (assert (some? (rf/compute-sub [:settings/submit-error] db)))
      (assert (true?  (has-tag? f :form/invalid)))
      (assert (false? (has-tag? f :settings/in-flight)))
      ;; the auth slice was NOT updated; the user's :bio is still nil.
      (assert (nil? (get-in db [:auth :user :bio]))))))

(defn settings-validation-test []
  ;; Validation path — direct broadcasts exercise the
  ;; :submit-invalid / :edit transitions. The bead's machine spec
  ;; includes a :neutral → :incorrect transition (on :submit-invalid)
  ;; and an :incorrect → :neutral transition (on :edit) so the
  ;; lifecycle is complete; in a production app a client-side Malli
  ;; validate inside :settings/submit would dispatch :submit-invalid
  ;; when the draft failed validation, matching Pattern-Forms'
  ;; §Standard events table.
  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:auth.session/persist :rf/no-op}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})

    ;; Broadcast :submit-invalid with a per-field error map. The
    ;; region lands in :incorrect and the error fields are auto-added
    ;; to :touched (per Pattern-Forms §Error visibility — once submit
    ;; has been attempted, every error is shown regardless of
    ;; per-field touched state).
    (rf/dispatch-sync [:settings/form
                       [:submit-invalid {:errors {:email ["Email must contain @."]}}]]
                      {:frame f})
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :incorrect (:state snap)))
      (assert (= {:email ["Email must contain @."]} (get-in snap [:data :errors])))
      (assert (contains? (get-in snap [:data :touched]) :email))
      (assert (true? (has-tag? f :form/invalid))))

    ;; The first :edit on the offending field clears that field's
    ;; error entry and returns the region to :neutral.
    (rf/dispatch-sync [:settings/edit-field :email "alice@example.com"] {:frame f})
    (let [snap (snapshot (rf/get-frame-db f))]
      (assert (= :neutral (:state snap)))
      (assert (false? (has-tag? f :form/invalid)))
      (assert (not (contains? (get-in snap [:data :errors]) :email))))))
