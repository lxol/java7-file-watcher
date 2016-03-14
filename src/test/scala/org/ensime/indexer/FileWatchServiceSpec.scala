// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import akka.testkit._
import akka.event.slf4j.SLF4JLogging
import com.google.common.io.Files
import file._
import java.util.UUID
import org.apache.commons.vfs2._
import org.ensime.fixture._
import org.ensime.util._
import org.scalatest.tagobjects.Retryable

import org.ensime.filewatcher._
import scala.collection.immutable.Set
import scala.language.implicitConversions
sealed trait FileWatcherMessage
case class Added(f: FileObject) extends FileWatcherMessage
case class Removed(f: FileObject) extends FileWatcherMessage
case class Changed(f: FileObject) extends FileWatcherMessage
case class BaseAdded(f: FileObject) extends FileWatcherMessage
case class BaseRemoved(f: FileObject) extends FileWatcherMessage

/**
 * These tests are insanely flakey so everything is retryable. The
 * fundamental problem is that file watching is impossible without
 * true OS and FS support, which is lacking on all major platforms.
 */
abstract class FileWatcherSpec extends EnsimeSpec
    with IsolatedTestKitFixture with IsolatedEnsimeVFSFixture {

  // variant that watches a jar file
  def createJarWatcher(jar: File)(implicit vfs: EnsimeVFS, tk: TestKit): Watcher

  // variant that recursively watches a directory of classes
  def createClassWatcher(base: File)(implicit vfs: EnsimeVFS, tk: TestKit): Watcher

  /**
   * The Linux ext2+ filesystems have a timestamp precision of 1
   * second, which means its impossible to tell if a newly created
   * file has been modified, or deleted and re-added, if it happens
   * sub-second (without looking at the contents).
   */
  def waitForLinus(): Unit = {
    Thread.sleep(1000)
  }

  def waitForOSX(): Unit = {
    Thread.sleep(10001)
  }

  "FileWatcher" should "detect added files" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          withClassWatcher(dir) { watcher =>
            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]
          }
        }
      }
    }

  it should "detect added / changed files" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          withClassWatcher(dir) { watcher =>
            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            foo.isFile() shouldBe true
            bar.isFile() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]

            waitForLinus()

            foo.writeString("foo")
            bar.writeString("bar")
            waitForOSX()
            tk.expectMsgType[Changed]
            tk.expectMsgType[Changed]
          }
        }
      }
    }

  it should "detect added / removed files" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          withClassWatcher(dir) { watcher =>
            tk.ignoreMsg {
              case msg: Changed => true
            }

            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]

            waitForLinus()

            foo.delete()
            bar.delete()
            waitForOSX()
            tk.expectMsgType[Removed]
            tk.expectMsgType[Removed]
          }
        }
      }
    }

  it should "detect removed base directory" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          withClassWatcher(dir) { watcher =>
            waitForLinus()

            dir.delete()
            waitForOSX()
            val createOrDelete: Fish = {
              case r: BaseRemoved => true
              case a: BaseAdded => true
            }

            tk.fishForMessage()(createOrDelete)
            tk.fishForMessage()(createOrDelete)
          }
        }
      }
    }

  it should "detect removed parent base directory" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        val parent = Files.createTempDir().canon
        val dir = parent / "base"
        dir.mkdirs()
        try {
          withClassWatcher(dir) { watcher =>
            // would be better if this was atomic (not possible from JVM?)
            dir.tree.reverse.foreach(_.delete())
            parent.delete()
            waitForOSX()
            val createOrDelete: Fish = {
              case r: BaseRemoved => true
              case a: BaseAdded => true
            }
            tk.fishForMessage()(createOrDelete)
            tk.fishForMessage()(createOrDelete)
          }
        } finally parent.tree.reverse.foreach(_.delete())
      }
    }

  it should "survive deletion of the watched directory" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          withClassWatcher(dir) { watcher =>
            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]

            waitForLinus()
            dir.tree.reverse.foreach(_.delete())
            waitForOSX()
            val createOrDelete: Fish = {
              case r: BaseRemoved => true
              case c: Changed => false
              case a: BaseAdded => true
              case r: Removed => false
            }

            tk.fishForMessage()(createOrDelete)
            tk.fishForMessage()(createOrDelete)

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            val nonDeterministicAdd: Fish = {
              case a: Added => true
              case c: Changed => true
              case r: Removed => false
            }
            tk.fishForMessage()(nonDeterministicAdd)
            tk.fishForMessage()(nonDeterministicAdd)
          }
        }
      }
    }

  it should "be able to start up from a non-existent directory" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        val dir = Files.createTempDir().canon
        dir.delete()
        try {
          withClassWatcher(dir) { watcher =>
            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            waitForLinus()

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]
          }
        } finally dir.tree.reverse.foreach(_.delete())
      }
    }

  it should "survive removed parent base directory and recreated base" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>

        val parent = Files.createTempDir().canon
        val dir = parent / "base"
        dir.mkdirs()
        try {
          withClassWatcher(dir) { watcher =>
            val foo = (dir / "foo.class")
            val bar = (dir / "b/bar.class")

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
            tk.expectMsgType[Added]

            waitForLinus()

            dir.tree.reverse.foreach(_.delete())
            Thread.sleep(300)
            parent.delete()
            waitForOSX()
            val createOrDelete: Fish = {
              case r: BaseRemoved => true
              case c: Changed => false
              case a: BaseAdded => true
              case r: Removed => false
            }
            tk.fishForMessage()(createOrDelete)
            tk.fishForMessage()(createOrDelete)

            foo.createWithParents() shouldBe true
            bar.createWithParents() shouldBe true
            waitForOSX()
            // non-deterministically receive zero, one or two more Removed
            // and either Added or Changed for foo / bar.
            val nonDeterministicAdd: Fish = {
              case a: Added => true
              case c: Changed => true
              case a: BaseAdded => false
              case r: Removed => false
            }
            tk.fishForMessage()(nonDeterministicAdd)
            tk.fishForMessage()(nonDeterministicAdd)
          }
        } finally dir.tree.reverse.foreach(_.delete())
      }
    }

  //////////////////////////////////////////////////////////////////////////////
  it should "detect changes to a file base" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>

          val jar = (dir / "jar.jar")
          jar.createWithParents() shouldBe true

          withJarWatcher(jar) { watcher =>
            waitForLinus()

            jar.writeString("binks")
            waitForOSX()
            tk.expectMsgType[Changed]
          }
        }
      }
    }

  it should "detect removal of a file base" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          tk.ignoreMsg {
            case msg: Changed => true
          }

          val jar = (dir / "jar.jar")
          jar.createWithParents() shouldBe true

          withJarWatcher(jar) { watcher =>
            waitForLinus()

            jar.delete()
            waitForOSX()
            tk.expectMsgType[Removed]
          }
        }
      }
    }

  it should "be able to start up from a non-existent base file" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          val jar = (dir / "jar.jar")
          withJarWatcher(jar) { watcher =>
            waitForLinus()

            jar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
          }
        }
      }
    }

  it should "survive removal of a file base" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          tk.ignoreMsg {
            case msg: Changed => true
          }

          val jar = (dir / "jar.jar")
          jar.createWithParents() shouldBe true

          withJarWatcher(jar) { watcher =>
            waitForLinus()

            jar.delete() // best thing for him, frankly
            waitForOSX()
            tk.expectMsgType[Removed]

            waitForLinus()
            jar.writeString("binks")
            waitForOSX()
            tk.expectMsgType[Added]
          }
        }
      }
    }

  // it should "survive removal of a parent of a file base" taggedAs (Retryable) in
  //   withVFS { implicit vfs =>
  //     withTestKit { implicit tk =>
  //       withTempDir { dir =>
  //         tk.ignoreMsg {
  //           case msg: Changed => true
  //         }
  //         val jar = (dir / "parent" / "jar.jar")
  //         jar.createWithParents() shouldBe true
  //         withJarWatcher(jar) { watcher =>
  //           waitForLinus()
  //           log.debug("remove recursively {}", dir)
  //           dir.tree.reverse.foreach(_.delete())
  //           waitForOSX()
  //           tk.expectMsgType[Removed]
  //           jar.createWithParents() shouldBe true
  //           waitForLinus()
  //           waitForOSX()
  //           tk.expectMsgType[Added]
  //         }
  //       }
  //     }
  //   }

  it should "be able to start up from a non-existent grandparent of a base file" taggedAs (Retryable) in
    withVFS { implicit vfs =>
      withTestKit { implicit tk =>
        withTempDir { dir =>
          val jar = (dir / "top" / "grand" / "parent" / "jar.jar")
          (dir / "top").tree.reverse.foreach(_.delete())
          withJarWatcher(jar) { watcher =>
            waitForLinus()

            jar.createWithParents() shouldBe true
            waitForOSX()
            tk.expectMsgType[Added]
          }
        }
      }
    }

  //////////////////////////////////////////////////////////////////////////////
  type -->[A, B] = PartialFunction[A, B]
  type Fish = PartialFunction[Any, Boolean]

  def withClassWatcher[T](base: File)(code: Watcher => T)(implicit vfs: EnsimeVFS, tk: TestKit) = {
    val w = createClassWatcher(base)
    try code(w)
    finally w.shutdown()
  }

  def withJarWatcher[T](jar: File)(code: Watcher => T)(implicit vfs: EnsimeVFS, tk: TestKit) = {
    val w = createJarWatcher(jar)
    try code(w)
    finally w.shutdown()
  }

  def listeners(implicit vfs: EnsimeVFS, tk: TestKit) = List(
    new FileChangeListener {
      def fileAdded(f: FileObject): Unit = { tk.testActor ! Added(f) }
      def fileRemoved(f: FileObject): Unit = { tk.testActor ! Removed(f) }
      def fileChanged(f: FileObject): Unit = { tk.testActor ! Changed(f) }
      override def baseReCreated(f: FileObject): Unit = { tk.testActor ! BaseAdded(f) }
      override def baseRemoved(f: FileObject): Unit = { tk.testActor ! BaseRemoved(f) }
    }
  )

}

class FileWatchServiceSpec extends FileWatcherSpec {

  override def createClassWatcher(base: File)(implicit vfs: EnsimeVFS, tk: TestKit): Watcher = {
    ClassWatcher.register(base, ClassfileSelector, true, listeners)
  }

  override def createJarWatcher(jar: File)(implicit vfs: EnsimeVFS, tk: TestKit): Watcher =
    JarWatcher.register(jar, JarSelector, false, listeners)
}

object JarWatcher extends BaseWatcher
object ClassWatcher extends BaseWatcher

class BaseWatcher extends SLF4JLogging {
  val fileWatchService: FileWatchService = new FileWatchService
  def register(
    base: File,
    selector: ExtSelector,
    recursive: Boolean,
    listeners: Seq[FileChangeListener]
  )(implicit vfs: EnsimeVFS) = {
    log.debug("watching {}", base)

    trait EnsimeWatcher extends Watcher {
      import scala.language.reflectiveCalls
      val w = fileWatchService.spawnWatcher()

      def create(): Unit = {
        log.debug(
          "create EnsimeWatcher {} for {}",
          w.watcherId.asInstanceOf[Any], base
        )
        w.register(
          base,
          toWatcherListeners(
            listeners,
            selector,
            recursive,
            vfs,
            base,
            w.watcherId
          )
        )
      }
      override def shutdown(): Unit = {
        log.debug("shutdown watcher {}", w.watcherId)
        w.shutdown()
      }
    }
    val ensimeWatcher = new EnsimeWatcher {}
    ensimeWatcher.create()
    ensimeWatcher
  }
  def toWatcherListeners(
    ws: Seq[FileChangeListener],
    selector: ExtSelector,
    rec: Boolean,
    vfs: EnsimeVFS,
    baseFile: File,
    uuid: UUID
  ): Set[WatcherListener] = {
    ws.toSet[FileChangeListener] map {

      l: FileChangeListener =>
        new WatcherListener() {
          override val base = baseFile
          override val recursive = rec
          override val extensions = selector.include
          override val treatExistingAsNew = !baseFile.isFile
          override val watcherId = uuid

          override def fileCreated(f: File) = {
            log.debug("fileAdded {}", f)
            l.fileAdded(vfs.vfile(f))
          }
          override def fileDeleted(f: File) = {
            log.debug("fileDeleted {}", f)
            l.fileRemoved(vfs.vfile(f))
          }
          override def fileModified(f: File) = {
            log.debug("fileModified {}", f)
            l.fileChanged(vfs.vfile(f))
          }
          override def baseReCreated(f: File) = {
            log.debug("baseReCreated {}", f)
            l.baseReCreated(vfs.vfile(f))
          }
          override def baseRemoved(f: File) = {
            log.debug("baseRemoved {}", f)
            l.baseRemoved(vfs.vfile(f))
          }
        }
    }
  }
}
