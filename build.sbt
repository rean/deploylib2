name := "deploylib2"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.2"

initialCommands in console := "import deploylib._"
