(ns ems.db
  (:require [pod.babashka.go-sqlite3 :as sqlite]))

(def db-path "ems.db")

(def schema
  ["CREATE TABLE IF NOT EXISTS events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      timestamp TEXT NOT NULL,
      source TEXT NOT NULL,
      event_type TEXT NOT NULL,
      value REAL,
      context TEXT,
      energy_delta REAL,
      mood_delta REAL)"

   "CREATE TABLE IF NOT EXISTS screentime (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      timestamp TEXT NOT NULL,
      total_minutes REAL,
      app_distribution TEXT,
      pickups INTEGER,
      context_switches INTEGER)"

   "CREATE TABLE IF NOT EXISTS daily_state (
      date TEXT PRIMARY KEY,
      energy_start INTEGER,
      energy_end INTEGER,
      mood_start INTEGER,
      mood_end INTEGER,
      time_available_hours REAL,
      time_quality_ratio REAL,
      top_drains TEXT,
      summary TEXT)"])

(defn init! []
  (doseq [ddl schema]
    (sqlite/execute! db-path [ddl]))
  (println "✓ Database initialized:" db-path))

(defn query [sql & params]
  (sqlite/query db-path (into [sql] params)))

(defn execute! [sql & params]
  (sqlite/execute! db-path (into [sql] params)))
