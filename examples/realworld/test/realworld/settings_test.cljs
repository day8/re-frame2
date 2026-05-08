(ns realworld.settings-test
  "Headless test for realworld.settings — settings save propagates new
   user data into the auth slice. Retrofitted under rf2-o8t6 to use
   Spec 014's `:rf.http/managed` substrate via the framework-shipped
   canned-stub fxs."
  (:require [re-frame.core :as rf]
            [realworld.settings]
            [realworld.test-helpers :as th])
  (:require-macros [re-frame.views-macros :refer [with-frame]]))

(defn settings-test []
  (th/reg-canned-success! :rf.http/managed.canned-settings-save
                          {:user {:email "alice@example.com"
                                  :token "jwt-2"
                                  :username "alice"
                                  :bio "New bio"
                                  :image nil}})

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:rf.http/managed :rf.http/managed.canned-settings-save}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (assert (= "New bio" (get-in (rf/get-frame-db f) [:auth :user :bio])))))
