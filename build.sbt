libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.21",
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.21",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.21" % Test,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.0",
  "com.typesafe.akka" %% "akka-http" % "10.1.7",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.1.7" % Test,
  "com.github.os72" % "protobuf-dynamic" % "0.9.3"
)

scalaVersion := "2.12.8"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerBaseImage := "openjdk:8u181-jdk-stretch"
dockerRepository := Some("deepsleep.lan:5000")
dockerExposedUdpPorts += 4124

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)
