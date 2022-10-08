import sbt._

object Version {
  lazy val akka            = "2.6.20"
  lazy val sttp            = "3.8.2"
  lazy val circe           = "0.14.3"
  lazy val enumeratum      = "1.7.0"
  lazy val alpakka         = "4.0.0"
  lazy val cassandraDriver = "4.15.0"
  lazy val caliban         = "2.0.1"
  lazy val cql4s           = "0.0.1"
}

object Dependencies {
  lazy val akkaActor  = "com.typesafe.akka" %% "akka-actor-typed"         % Version.akka cross CrossVersion.for3Use2_13
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream-typed"        % Version.akka cross CrossVersion.for3Use2_13
  lazy val akkaTest   = "com.typesafe.akka" %% "akka-actor-testkit-typed" % Version.akka % Test cross CrossVersion.for3Use2_13
  lazy val akkaSlf4j  = "com.typesafe.akka" %% "akka-slf4j"               % Version.akka
  lazy val scalaTest  = "org.scalatest"     %% "scalatest"                % "3.2.14"     % Test cross CrossVersion.for3Use2_13
  lazy val scalaCheck = "org.scalatestplus" %% "scalacheck-1-15"          % "3.2.11.0"   % Test cross CrossVersion.for3Use2_13

  lazy val scalaCheckShapeless =
    "com.github.alexarchambault" %% "scalacheck-shapeless_1.14" % "1.2.5" % Test cross CrossVersion.for3Use2_13
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.5" cross CrossVersion.for3Use2_13
  lazy val slf4jApi     = "org.slf4j"                   % "slf4j-api"       % "2.0.3"
  lazy val logback      = "ch.qos.logback"              % "logback-classic" % "1.4.3"

  lazy val ergoWallet =
    "org.ergoplatform" %% "ergo-wallet" % "4.0.42-153-3b68716c-20221008-0749-SNAPSHOT" cross CrossVersion.for3Use2_13
  lazy val pureConfig = "com.github.pureconfig"  %% "pureconfig"             % "0.17.1" cross CrossVersion.for3Use2_13
  lazy val diffx      = "com.softwaremill.diffx" %% "diffx-scalatest-should" % "0.7.1" % Test cross CrossVersion.for3Use2_13
  lazy val newtype    = "io.estatico"            %% "newtype"                % "0.4.4" cross CrossVersion.for3Use2_13

  lazy val logging = Seq(slf4jApi, logback, scalaLogging)
  lazy val akka    = Seq(akkaActor, akkaStream, akkaSlf4j, akkaTest)

  lazy val circe = List(
    "io.circe" %% "circe-core"    % Version.circe,
    "io.circe" %% "circe-generic" % Version.circe,
    "io.circe" %% "circe-parser"  % Version.circe,
    "io.circe" %% "circe-refined" % Version.circe
  ) map (_ cross CrossVersion.for3Use2_13)

  lazy val cats = List(
    "org.typelevel" %% "cats-kernel" % "2.8.0",
    "org.typelevel" %% "cats-core"   % "2.8.0"
  ) map (_ cross CrossVersion.for3Use2_13)

  lazy val refined: List[ModuleID] = List(
    "eu.timepit" %% "refined"      % "0.10.1",
    "eu.timepit" %% "refined-cats" % "0.10.1"
  ) map (_ cross CrossVersion.for3Use2_13)

  lazy val enumeratums: List[ModuleID] = List(
    "com.beachape" %% "enumeratum"       % Version.enumeratum,
    "com.beachape" %% "enumeratum-circe" % Version.enumeratum
  ) map (_ cross CrossVersion.for3Use2_13)

  lazy val sttp = List(
    "com.softwaremill.sttp.client3" %% "core"              % Version.sttp,
    "com.softwaremill.sttp.client3" %% "circe"             % Version.sttp,
    "com.softwaremill.sttp.client3" %% "akka-http-backend" % Version.sttp,
    "com.softwaremill.retry"        %% "retry"             % "0.3.6"
  ) map (_ cross CrossVersion.for3Use2_13)

  lazy val cql4s = Seq(
    "solutions.epifab" %% "cql4s-core" % Version.cql4s,
    "solutions.epifab" %% "cql4s-zio"  % Version.cql4s
  )

  lazy val caliban = Seq(
    "com.github.ghostdogpr" %% "caliban"            % Version.caliban,
    "com.github.ghostdogpr" %% "caliban-zio-http"   % Version.caliban,
    "com.github.ghostdogpr" %% "caliban-federation" % Version.caliban
  )

  lazy val cassandraDb = List(
    "com.lightbend.akka" %% "akka-stream-alpakka-cassandra" % Version.alpakka cross CrossVersion.for3Use2_13,
    "com.datastax.oss"    % "java-driver-core"              % Version.cassandraDriver,
    "com.datastax.oss"    % "java-driver-query-builder"     % Version.cassandraDriver,
    "io.netty"            % "netty-transport-native-epoll"  % "4.1.79.Final" classifier "linux-x86_64"
  )

  lazy val monocle =
    Seq(
      "dev.optics" %% "monocle-core"  % "3.1.0",
      "dev.optics" %% "monocle-macro" % "3.1.0"
    ) map (_ cross CrossVersion.for3Use2_13)
}
