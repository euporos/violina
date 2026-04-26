(ns comp.snippets
  (:require
   [psite-i18n.core :refer [defsnips]]
   [comp.localization :as loc]))

(defsnips loc/fallback
  [[datenschutzerklärung
    {:de "Datenschutzerklärung"
     :en "privacy policy"
     :uk "Захист данних "}]
   [telefonnummer
    {:de "Telefonnummer"
     :en "Phone"
     :uk "Номер телефону"}]
   [name-snip
    {:de "Name"
     :en "Name"
     :uk "Ім'я "}]
   [ich-akzeptiere
    {:de "Ich akzeptiere die"
     :en "I accept the"
     :uk "Погодитися"}]
   [bitte-nicht-ausfuellen
    {:de "Bitte füllen Sie dieses Feld nicht aus."
     :en "Please do not fill out this field."
     :uk "Заповніть будь ласка це поле"}]
   [bitte-email
    {:de "Bitte geben Sie ein gültige Emailadresse ein."
     :en "Please enter a valid email address."
     :uk "вкажіть свій адрес електронної пошти"}]
   [bitte-name
    {:de "Bitte geben Sie Ihren Namen ein."
     :en "Please Enter your name."
     :uk "Вкажіть своє ім'я"}]
   [additional-message
    {:de "Zusätzliche Nachricht"
     :en "Additional message"
     :uk "Додаткове повідомлення"}]
   [datenschutzregelung
    {:de "Bitte akzeptieren Sie die Datenschutzregelung."
     :en "Please accept the privacy policy."
     :uk "Будь ласка дайте згоду з правилами безпеки сайту"}]
   [mehrerfahren
    {:de "mehr erfahren"
     :en "learn more"
     :uk "дізнатися більше"}]
   [impressum
    {:de "Impressum"
     :en "Imprint"
     :uk "Технічна інформація "}]
   [datenschutz
    {:de "Datenschutz"
     :en "Privacy"
     :uk "Захист даних"}]
   [kontakt
    {:de "Kontakt"
     :en "Contact"
     :uk "Контакти"}]
   [youtube-consent-text
    {:de "Dieses Video wird von YouTube gehostet. Durch Klicken auf \"Video laden\" akzeptieren Sie die "
     :en "This video is hosted by YouTube. By clicking \"Load video\" you accept the "
     :uk "Це відео розміщено на YouTube. Натискаючи \"Завантажити відео\", ви приймаєте "}]
   [google-privacy-policy
    {:de "Datenschutzerklärung von Google"
     :en "Google Privacy Policy"
     :uk "Політику конфіденційності Google"}]
   [load-video
    {:de "Video laden"
     :en "Load video"
     :uk "Завантажити відео"}]])

(defsnips loc/fallback
  [[home
    {:en "Home"
     :de "Startseite"
     :uk "Головна"
     :it "Pagina iniziale"}]])
