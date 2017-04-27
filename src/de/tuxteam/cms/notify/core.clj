;; Notify

(ns de.tuxteam.cms.notify.core
	(:gen-class)
        (:use clojure.contrib.sql)
        (:use clojure-ini.core)
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

;;;;############################################################################
;;;;                                                           Globals
;;;;############################################################################

;;; A list of resource events that we want to report
(def interesting-events (hash-set "PROPPATCH" "MKCOL" "PUT" "DELETE" "MOVE"))

;;; Our connection to the jabber server
(def *jabber-connection* nil)

;;; The conference room
(def *conference* nil)

;;; How long should we sleep inbetween polls? (in ms.)
(def *sleep-time* 20000)

;;;;############################################################################
;;;;                                                      Mutable Data
;;;;############################################################################

;;; `last-event' holds the timestamp of the last time we looked at the
;;; event table. We initialize it to the current time at
;;; startup. During a poll to the database we mutate it to the
;;; transaction time of the query.  We might be able to get away
;;; without this by passing the last seen time to the next poll
;;; call. Maybe next time ;-/

(def last-event (ref (Timestamp. (.getTime (Date.)))))

;;; Should we run/sleep/stop?
(def server-state (ref :running))


;;;;############################################################################
;;;;                                                      Code Section
;;;;############################################################################

(defn patch-jabber-library
  "Dissable over-correct SAL methods for now"
  []
  (doseq [method ["EXTERNAL" "GSSAPI" "DIGEST-MD5" "CRAM-MD5"]]
    (SASLAuthentication/unregisterSASLMechanism method))
  (SASLAuthentication/supportSASLMechanism "PLAIN" 0))

(defn init-jabber-connection [config]
  "Initialize the connection to the jabber server"
  (let [conn (new XMPPConnection (:host config))]

    (doto conn
      (.connect)
      (.login (:user config) (:password config)))
    (def *jabber-connection* conn)))

(defn join-conference [config]
  (let [muc (MultiUserChat. *jabber-connection* (:muc config))]
    (.join muc (:user config) (:muc-password config))
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
  (println "Checking for changed resource since" @last-event)
  (transaction
   (with-query-results rs
     ["SELECT distinct(source), method, now() AS now FROM triggers WHERE logdate >= ?"  @last-event]
     (when (first rs)
       (dosync (ref-set last-event (:now (first rs)))))
     (if (first rs)
       (println "Have results") (println "No results"))
     (dorun (map #(process-interesting-events %) (into (hash-set)  rs))))))



(defn run-event-loop
  ""
  [config]
  (def database {:classname   "org.postgresql.Driver" ; must be in classpath
                 :subprotocol "postgresql"
                 :subname     (:url config)
                 :user        (:user config)
                 :password    (:password config)})
  (with-connection database
    (println "Connected to database")
    (while (= @server-state :running)
      (check-resources)
      (Thread/sleep *sleep-time*))))	


;;;;############################################################[ Main ]########

(defn -main [configfile]
  (def config (read-ini configfile :keywordize? true :comment-char \#))
  (patch-jabber-library)
  (init-jabber-connection (:jabber config))
  (join-conference (:jabber config))
  (run-event-loop (:postgres config)))
