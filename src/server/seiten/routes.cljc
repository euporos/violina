(ns seiten.routes
  {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}}}}
  #?(:node (:require
             [seiten.admin :as admin]
             [seiten.cds :as cds]
             [seiten.galerie :as galerie]
             [seiten.home :as home]
             [seiten.konzertliste :as konzertliste]
             [seiten.kuenstler :as kuenstler]
             [seiten.medien :as medien]
             [seiten.page :as page]
             [seiten.presse :as presse]
             [seiten.programme :as programme]
             [seiten.termine :as termine]
             [setup.directus-auth :as directus-auth]))
  #?(:clj  (:require      [psite-routing.macros :as prm])
     :cljs (:require-macros [psite-routing.macros :as prm])))

(def routes
  (#?(:node identity :default prm/routes-reduced-for-matching)
   [["/" {:name    :home
          :handler home/handler}]

    ["/termine" {:name    :termine
                 :handler termine/handler}]

    ["/konzertliste" {:name    :konzertliste
                      :handler konzertliste/handler}]

    ["/programme"
     ["" {:name    :programme
          :handler programme/handler}]
     ["/{programm-id}" {:name       :programm
                        :handler    programme/single-handler
                        :directus   {:collection "programme"
                                     :params     {:programm-id "id"}}
                        :parameters {:path [[:programm-id :int]]}}]]

    ["/kuenstler"
     ["" {:name    :kuenstler
          :handler kuenstler/handler}]
     ["/{kuenstler-id}-{kuenstler-slug}" {:name       :kuenstler-single
                                          :handler    kuenstler/single-handler
                                          :directus   {:collection    "kuenstler"
                                                       :params        {:kuenstler-id "id"}
                                                       :static-params {:kuenstler-slug "view"}}
                                          :parameters {:path [[:kuenstler-id   :int]
                                                              [:kuenstler-slug :string]]}}]]

    ["/discs"
     ["" {:name    :cds
          :handler cds/handler}]
     ["/{cd-id}" {:name       :cd
                  :handler    cds/single-handler
                  :directus   {:collection "cds"
                               :params     {:cd-id "id"}}
                  :parameters {:path [[:cd-id :int]]}}]]

    ["/medien" {:name    :medien
                :handler medien/handler}]

    ["/galerie" {:name    :galerie
                 :handler galerie/handler}]

    ["/presse"
     ["" {:name    :presse
          :handler presse/handler}]
     ["/{artikel-id}" {:name       :presse-artikel
                       :handler    presse/single-handler
                       :directus   {:collection "presse"
                                    :params     {:artikel-id "id"}}
                       :parameters {:path [[:artikel-id :int]]}}]]

    ["/pg"
     ["/{page-id}-{page-slug}" {:handler    page/handler
                                :name       :single-page
                                :directus   {:collection    "sonderseiten"
                                             :params        {:page-id "id"}
                                             :static-params {:page-slug "view"}}
                                :parameters {:path [[:page-id  :int]
                                                    [:page-slug :string]]}}]]
    ["/admin" {:middleware [directus-auth/wrap-directus-user]}
     ["" {:handler admin/handler
          :name    :admin}]]]))
