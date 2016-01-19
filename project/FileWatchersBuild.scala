import sbt._
import Keys._

import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import SbtScalariform._

object FileWatchersBuild extends Build {
  override val settings = super.settings ++ Seq(
    organization := "org.ensime",
    version := "1.0.0-SNAPSHOT",
    ivyLoggingLevel := UpdateLogging.Quiet,
    scalaVersion := "2.11.7",
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.6",
      "-feature",
      "-deprecation",
      "-Xlint",
      "-Yinline-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Xfuture"
    ) ++ {
      if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
      else Nil
    } ++ {
      // fatal warnings can get in the way during the DEV cycle
      if (sys.env.contains("CI")) Seq("-Xfatal-warnings")
      else Nil
    },

    // we must output JRE6 classfiles for safe ensime-server compiles
    // (even though it'll fail at runtime)
    javacOptions in (Compile, compile) ++= Seq(
      "-source", "1.6", "-target", "1.6", "-Xlint:all", "-Werror",
      "-Xlint:-options", "-Xlint:-path", "-Xlint:-processing"
    ),
    javacOptions in doc ++= Seq("-source", "1.6")
  )

  lazy val root = (project in file(".")).
    enablePlugins(SbtScalariform).
    settings(
      name := "java7-file-watchers",
      ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignSingleLineCaseStatements, true)
    )

}
