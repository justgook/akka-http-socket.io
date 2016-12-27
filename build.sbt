import scala.language.postfixOps
import sbt._

ivyConfigurations += (config("external") hide)

name := "akka-http-socket.io"

version := "1.0"

scalaVersion := "2.12.0"

val akkaV = "2.4.14"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.0.0",
  "com.typesafe.akka" %% "akka-testkit" % akkaV
)



libraryDependencies += "com.github.socketio" % "socket.io-client" % "1.7.1" % "external->default" from "https://github.com/socketio/socket.io-client/archive/1.7.1.zip"
//libraryDependencies += "com.github.justgook" % "PrintersWorkshopUI" % "0.9.1" % "external->default" from "https://github.com/justgook/PrintersWorkshopUI/archive/0.9.1.zip"


resourceGenerators in Compile += Def.task {
  val externalArchives = update.value.select(configurationFilter("external"))
  externalArchives flatMap { zip =>
    IO.unzip(zip, (resourceManaged in Compile).value).toSeq
  }
}.taskValue
