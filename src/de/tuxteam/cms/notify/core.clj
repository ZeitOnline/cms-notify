;; Notify

(ns de.tuxteam.cms.notify.core
	(:gen-class)
;;	(use clojure.contrib.repl-ln)
        (:use clojure.contrib.sql)
        (import (java.util Date))
        (import (java.sql Timestamp))
        (import (java.text DateFormat))
        (import (org.jivesoftware.smack ConnectionConfiguration 
                                        SASLAuthentication
                                        XMPPConnection 
                                        XMPPException))
        (import (org.jivesoftware.smack.packet Message Message$Type))
        (import (org.jivesoftware.smackx.muc MultiUserChat))
        (import (org.jivesoftware.smackx XHTMLManager XHTMLText)))

;;;;############################################################################################
;;;;                                                                     Customization
;;;;############################################################################################

;;;;###################################################################[ Jabber Server ]########

(def jabber-host     "zip6.zeit.de")
(def jabber-user     "cms-backend")
(def jabber-password "--arag0rn+")
(def conference-room "notifications@conference.zip6.zeit.de")
(def muc-password    "")
(def cms-room        "notifications")

;;;;###############################################################[ Database Settings ]########

(def database {:classname   "org.postgresql.Driver" ; must be in classpath
               :subprotocol "postgresql"
               :subname      (str "//" "localhost" ":" 5432 "/" "cms") ;"//localhost:5432/cms"
               :user        "cms-reader"
               :password    ""})


;;;;############################################################################################
;;;;                                                                           Globals
;;;;############################################################################################

;;; A list of resource events that we want to report
(def interesting-events (hash-set "PROPPATCH" "MKCOL" "PUT" "DELETE" "MOVE"))

;;; Our connection to the jabber server
(def *jabber-connection* nil)

;;; The conference room
(def *conference* nil)

;;; How long should we sleep inbetween polls? (in ms.)
(def *sleep-time* 20000)

;;;;############################################################################################
;;;;                                                                      Mutable Data
;;;;############################################################################################

;;; `last-event' holds the timestamp of the last time we looked at the
;;; event table. We initialize it to the current time at
;;; startup. During a poll to the database we mutate it to the
;;; transaction time of the query.  We might be able to get away
;;; without this by passing the last seen time to the next poll
;;; call. Maybe next time ;-/

(def last-event (ref (str (Timestamp. (.getTime (Date.))))))

;;; Should we run/sleep/stop?
(def server-state (ref :running))


;;;;############################################################################################
;;;;                                                                      Code Section
;;;;############################################################################################

(defn patch-jabber-library 
  "Dissable over-correct SAL methods for now"
  []
  (doseq [method ["EXTERNAL" "GSSAPI" "DIGEST-MD5" "CRAM-MD5"]]
    (SASLAuthentication/unregisterSASLMechanism method))
  (SASLAuthentication/supportSASLMechanism "PLAIN" 0))

(defn init-jabber-connection []
  "Initialize the connection to the jabber server"
  (let [conn (new XMPPConnection jabber-host)]
    
    (doto conn  
      (.connect)
      (.login jabber-user jabber-password))
    (def *jabber-connection* conn)))

(defn join-conference []
  (let [muc (MultiUserChat. *jabber-connection* conference-room)]
    (.join muc jabber-user muc-password)
    (def *conference* muc)))

(defn send-notification [text]
  (let [message (.createMessage *conference*)]
    ;; FIXME: we probably need to create more structured content here ...
    (doto message
      (.setBody (str "Resource changed: " text)))
    (.sendMessage *conference* message)))

(defn send-hello []
  (let [message (.createMessage *conference*)]
    ;; FIXME: we probably need to create more structured content here ...
    (doto message
      (.setBody (str "Hello from CMS-Backend")))
      (.sendMessage *conference* message)))

(defn process-interesting-events 
  [event]
  (when (contains?  interesting-events (:method event))
    (send-notification (:source event))))

(defn check-resources 
  ""
  []
  (println "Checking for changed resource")
  (transaction
   (with-query-results rs 
     ["SELECT distinct(source), method, now() AS now FROM triggers WHERE logdate >= ?::timestamp"  @last-event]
     (when (first rs)
       (dosync (ref-set last-event (str (:now (first rs))))))
     (dorun (map #(process-interesting-events %) (into (hash-set) (map  rs)))))))



(defn run-event-loop 
  ""
  []
  (with-connection database
    (println "Connected to database")
    (while (= @server-state :running)
      (check-resources) 
      (Thread/sleep *sleep-time*))))	


;;;;############################################################################[ Main ]########

(defn -main [& arguments]
  (patch-jabber-library)
  ;; connect to the jabber server
  (init-jabber-connection)
  (join-conference)
  (run-event-loop))

                    
 