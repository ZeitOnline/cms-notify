(defproject de.tuxteam.cms.notify "1.0.0"
  :description "Sends notifications to jabber for changes to the CMS/DAV db."
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [clojure-ini "0.0.2"]
                 [postgresql "9.1-901.jdbc4"]
                 [org.clojars.amit/smack "3.1.0"]
                 [org.clojars.amit/smackx "3.1.0"]]

  :main de.tuxteam.cms.notify.core)
