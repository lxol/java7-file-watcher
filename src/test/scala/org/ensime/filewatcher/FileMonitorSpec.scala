package org.ensime.filewatcher

import java.io.File
import java.nio.file.{Files, Path}
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions._
import org.scalatest.concurrent.ConductorFixture
import org.scalatest.fixture
import org.scalatest.time.{Seconds, Span}

class FileMonitorSpec extends fixture.FunSuite with ConductorFixture
  with Matchers with TempDirFixtures {

  test("FileMonitor should detect a new file") { conductor =>

    import conductor._

    withTempDirs { rootPath =>
      info("watch dir:" + rootPath)
      @volatile var w: Waiter = null

      val watcher = new FileMonitor()
      watcher.addSelector("scala")
      watcher.setRecursive(true);
      watcher.addWatchedDir(rootPath.toFile)

      watcher.start()
      thread {
        w = new Waiter
        val listener = new FileListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded: " + f)
            w { assert(true) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fileChanged" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemovded" + f)
            w { assert(false) }
            w.dismiss()
          }
          def onOverflow(f: File): Unit = {
            info("onOverflow:" + f)
            w { assert(false) }
            w.dismiss()
          }
        }
        watcher.addListener(listener)
        waitForBeat(5)
        Files.createTempFile(rootPath, "new-file", ".scala")
        w.await(timeout(Span(60, Seconds)), dismissals(1))
      }
      conductor.conduct(timeout(Span(1, Seconds)), interval(Span(1, Seconds)))
    }
  }

  test("FileMonitor should detect a new file in a new directory") { conductor =>

    import conductor._

    withTempSubdir { (rootPath, subdirPath) =>
      info("watch dir:" + subdirPath)
      @volatile var w: Waiter = null

      val watcher = new FileMonitor()
      watcher.start()
      watcher.addSelector("scala")
      watcher.setRecursive(true);

      watcher.addWatchedDir(rootPath.toFile)

      thread {
        w = new Waiter
        val listener = new FileListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded:" + f)
            w { assert(true) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fileChanged:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemoved:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def onOverflow(f: File): Unit = {
            info("onOverflow:" + f)
            w { assert(false) }
            w.dismiss()
          }
        }
        watcher.addListener(listener)
        waitForBeat(2)
        beat should be(2)
        info("Create a new dir.")
        val sub: Path = Files.createTempDirectory(rootPath, "sub")
        info("Create a file in a new dir.")
        Thread.sleep(1000)
        Files.createTempFile(sub, "new", ".scala")
        w.await(timeout(Span(20, Seconds)), dismissals(1))
      }
      conductor.conduct(timeout(Span(2, Seconds)), interval(Span(2, Seconds)))
    }
  }

  test("FileMonitor should detect removed base directory") { conductor =>

    import conductor._
    withTempRootOnly { (rootPath) =>
      @volatile var w: Waiter = null
      val watcher = new FileMonitor()
      watcher.addSelector("scala")
      watcher.setRecursive(true);
      watcher.addWatchedDir(rootPath.toFile)
      watcher.start()
      thread {
        w = new Waiter
        val listener = new FileListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fileChanged:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemoved:" + f)
            w { assert(true) }
            w.dismiss()
          }
          def onOverflow(f: File): Unit = {
            info("onOverflow:" + f)
            w { assert(false) }
            w.dismiss()
          }

        }
        watcher.addListener(listener)
        waitForBeat(2)
        beat should be(2)
        info("Remove root dir.")
        Files.delete(rootPath)
        if (!rootPath.toFile.exists()) { info("deleted ") }
        info("Remove root dir.")
        w.await(timeout(Span(30, Seconds)), dismissals(1))
      }

      conductor.conduct(timeout(Span(1, Seconds)), interval(Span(1, Seconds)))
    }
  }

  test("FileMonitor should detect removed parent base directory") { conductor =>
    import conductor._
    withTempParentAndRoot { (parentPath, rootPath) =>
      @volatile var w: Waiter = null
      val watcher = new FileMonitor()
      watcher.addSelector("scala")
      watcher.setRecursive(true);
      watcher.start()

      thread {
        w = new Waiter
        val listener = new FileListener {
          def fileAdded(f: File): Unit = {
            info("fileAdded:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileChanged(f: File): Unit = {
            info("fileChanged:" + f)
            w { assert(false) }
            w.dismiss()
          }
          def fileRemoved(f: File): Unit = {
            info("fileRemoved:" + f)
            w { assert(true) }
            w.dismiss()
          }
          def onOverflow(f: File): Unit = {
            info("onOverflow:" + f)
            w { assert(false) }
            w.dismiss()
          }
        }

        watcher.addListener(listener)
        waitForBeat(2)
        beat should be(2)
        info("parent dir.")
        watcher.addWatchedDir(rootPath.toFile)
        Files.delete(rootPath)
        Thread.sleep(500)
        //parentPath.canon.tree.reverse.foreach(_.delete())
        Files.delete(parentPath)

        info("Remove parent dir.")
        w.await(timeout(Span(30, Seconds)), dismissals(1))
      }

      conductor.conduct(timeout(Span(1, Seconds)), interval(Span(1, Seconds)))
    }
  }

}

trait TempDirFixtures {
  def withTempDirs(testCode: Path => Any): Any = {
    val root1: Path = Files.createTempDirectory("root")
    val sub11: Path = Files.createTempDirectory(root1, "sub1")
    val tempFile111: Path = Files.createTempFile(sub11, "file", ".tmp");
    val sub12: Path = Files.createTempDirectory(root1, "sub2")
    testCode(root1)
    // Files.delete(sub11)
    // Files.delete(sub12)
    // Files.delete(root1)
  }
  def withTempSubdir(testCode: (Path, Path) => Any): Any = {
    val root1: Path = Files.createTempDirectory("root")
    val sub11: Path = Files.createTempDirectory(root1, "sub1")
    //val tempFile111: Path = Files.createTempFile(sub11, "file", ".tmp");
    val sub12: Path = Files.createTempDirectory(root1, "sub2")
    testCode(root1, sub11)
    // Files.delete(sub11)
    // Files.delete(sub12)
    // Files.delete(root1)
  }
  def withTempRootOnly(testCode: (Path) => Any): Any = {
    val root: Path = Files.createTempDirectory("root")
    testCode(root)
  }

  def withTempParentAndRoot(testCode: (Path, Path) => Any): Any = {
    val parent: Path = Files.createTempDirectory("parent")
    val root: Path = Files.createTempDirectory(parent, "root")
    testCode(parent, root)
  }
}
