(ns api.werbe-email
  "Generates a downloadable .eml from the `werbe_email` Directus singleton.
   The singleton holds a hero image, body copy, and an ordered list of
   programmes; we render the email-templates/werbe-email.html template,
   wrap the result in an RFC822 message via nodemailer's MailComposer, and
   stream it back with Content-Disposition: attachment."
  (:require ["fs" :as fs]
            ["nodemailer" :as nodemailer]
            ["path" :as path]
            [clojure.string :as str]
            [db.schema :as s]
            [db.setup :as db]
            [directus.core :as d]
            [kitchen-async.promise :as p]
            [macchiato-async.core :refer-macros [defhandler]]
            [macchiato.util.response :as r]
            [psite-routing.core :as routing]))

(def ^:private de-locale :de)

(def ^:private template-path
  "Resolved relative to the running server.js. Built artefacts land in
   /media/.../server/, so we walk up to the repo root and into resources/."
  (.resolve path js/__dirname ".." "resources" "email-templates" "werbe-email.html"))

(defn- load-template []
  (.readFileSync fs template-path "utf8"))

(defn- escape-html [s]
  (when s
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;"))))

(defn- programme-url [req {:keys [id slug]}]
  (let [path (routing/reverse-match req :programm
                                    {:locale     (name de-locale)
                                     :programm-id   id
                                     :programm-slug (or slug (str id))})]
    (routing/make-path-absolute req path)))

(defn- program-block-html
  "Single icon-block row in the email body: programme image + linked title.
   Mirrors the festival template's icons_block structure so Outlook keeps
   its VML fallback happy."
  [req {:keys [bild titel] :as programme}]
  (let [img-src (d/image-by-preset "w200" bild)
        href    (programme-url req programme)
        title   (escape-html titel)]
    (str
     "<table class=\"icons_block\" role=\"presentation\""
     " style=\"mso-table-lspace:0;mso-table-rspace:0\" width=\"100%\""
     " cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tbody><tr>"
     "<td class=\"pad\" style=\"vertical-align:middle;color:#fff;"
     "font-family:Montserrat,'Trebuchet MS','Lucida Grande','Lucida Sans Unicode',"
     "'Lucida Sans',Tahoma,sans-serif;font-size:16px;text-align:left\">"
     "<table role=\"presentation\" style=\"mso-table-lspace:0;mso-table-rspace:0\""
     " width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tbody><tr>"
     "<td class=\"alignment\" style=\"vertical-align:middle;text-align:left\">"
     "<table class=\"icons-inner\""
     " style=\"mso-table-lspace:0;mso-table-rspace:0;display:inline-block;"
     "margin-right:-4px;padding-left:0;padding-right:0\""
     " role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\"><tbody><tr>"
     "<td style=\"vertical-align:middle;text-align:center;"
     "padding-top:5px;padding-bottom:5px;padding-left:25px;padding-right:25px\">"
     "<a href=\"" href "\">"
     "<img src=\"" img-src "\" alt=\"\" width=\"64\" height=\"64\""
     " style=\"display:block;height:64px;width:64px;border:0;margin:0 auto\""
     " moz-do-not-send=\"true\"></a></td>"
     "<td style=\"font-family:Montserrat,'Trebuchet MS','Lucida Grande',"
     "'Lucida Sans Unicode','Lucida Sans',Tahoma,sans-serif;font-size:16px;"
     "color:#fff;vertical-align:middle;text-align:left\">"
     "<a href=\"" href "\" style=\"color:#fff;text-decoration:none\">"
     title "</a></td>"
     "</tr></tbody></table></td></tr></tbody></table></td></tr></tbody></table>")))

(defn- render-template [req singleton programmes]
  (let [tpl (load-template)
        replacements {"{{hero_image}}"      (d/image-by-preset "w1600" (:hero_image singleton))
                      "{{thank_you}}"       (escape-html (:thank_you singleton))
                      "{{salutation}}"      (escape-html (:salutation singleton))
                      "{{main_text}}"       (some-> (:main_text singleton)
                                                    escape-html
                                                    (str/replace "\n" "<br>"))
                      "{{details_heading}}" (escape-html (:details_heading singleton))
                      "{{program_blocks}}"  (str/join "\n"
                                                      (map #(program-block-html req %) programmes))}]
    (reduce-kv (fn [acc k v] (str/replace acc k (or v ""))) tpl replacements)))

(defn- build-eml
  "Wraps the HTML body in an RFC822 message via nodemailer's MailComposer.
   Resolves to a Buffer containing the full .eml payload."
  [subject html]
  (js/Promise.
   (fn [resolve reject]
     (let [composer (nodemailer/MailComposer.
                     #js {:subject subject
                          :html    html
                          :from    "info@violina-petrychenko.de"
                          :to      ""})]
       (.build (.compile composer)
               (fn [err buf]
                 (if err (reject err) (resolve buf))))))))

(defn- singleton-query []
  {:select [s/werbe_email-id
            s/werbe_email-subject
            s/werbe_email-hero_image
            s/werbe_email-thank_you
            s/werbe_email-salutation
            s/werbe_email-main_text
            s/werbe_email-details_heading]
   :from   [s/werbe_email]
   :limit  1})

(defn- programmes-query [locale]
  {:select    [s/programme-id
               s/programme-slug
               s/programme-bild
               (db/localized s/programme-titel locale)]
   :from      [[:werbe_email_programme :wep]]
   :join      [[s/programme_t :programme] [:= :wep.programme_id s/programme-id]]
   :order-by  [[:wep.sort :asc]]})

(defhandler handler [req]
  (if-not (:directus-user req)
    (r/unauthorized "Directus admin session required.")
    (p/let [rows       (db/query (singleton-query))
            singleton  (first rows)
            programmes (db/query (programmes-query de-locale))
            html       (render-template req singleton programmes)
            eml        (build-eml (or (:subject singleton) "") html)]
      (-> (r/ok eml)
          (r/header "Content-Disposition" "attachment; filename=\"werbe-email.eml\"")
          (r/content-type "message/rfc822")))))

