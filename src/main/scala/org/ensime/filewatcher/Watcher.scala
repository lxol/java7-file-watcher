// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.filewatcher

import java.io._
import java.nio.file.{ FileSystems, FileVisitResult, Files, LinkOption, Path, SimpleFileVisitor, WatchKey }
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.WatchEvent.Kind
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.concurrent.Map
import scala.collection.convert.decorateAsScala._
import scala.collection.immutable.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import scala.util.{ Failure, Success, Try }
import scala.util.control.Breaks._

import org.slf4j.{ Logger, LoggerFactory }

class FileWatcher {
  self =>
  val log = LoggerFactory.getLogger(getClass)

  /**
   * The low priority thread used for checking the files being monitored.
   */
  @volatile private var monitorThread: Option[Thread] = None

  /**
   * A flag used to determine if the monitor thread should be running.
   */
  @volatile private var shouldRun: Boolean = true

  /**
   * Construct a new Java7 WatchService
   */
  var watchService: WatchService = null

  implicit def keyToFile(k: WatchKey): File = k.watchable().asInstanceOf[Path].toFile
  implicit def keyToCanonicalPath(k: WatchKey): String = k.watchable().asInstanceOf[Path].toFile.getCanonicalPath()

  private def init(): Unit = {
    log.debug("init watcher")
    watchService = Try {
      FileSystems.getDefault().newWatchService()
    } match {
      case Success(w) => w
      case Failure(e) => throw new Exception("failed to create WatchService {}", e)
    }
    start()
  }

  /**
   * Start a background monitoring thread
   */
  private def start() = {
    log.debug("start a background monitoring thread")
    monitorThread match {
      case Some(t) => t.start()
      case None => {
        monitorThread =
          Some(
            new Thread {
              override def run() = {
                monitor()
              }
            }
          )
        monitorThread.get.start()
      }
    }
  }

  def watch(file: File, listeners: Set[WatcherListener]): Unit = {
    try {
      if (file.isDirectory) {
        log.debug(s"watch a directory ${file}")
        registerTree(file, listeners)
      } else if (file.isFile) {
        log.debug(s"watch a file ${file}")
        val fileBase = new File(file.getParent)
        registerTree(fileBase, listeners)
      } else {
        log.debug(s"watch a non-existent path ${file}")
        if (file.getParentFile.exists) {
          registerDir(file.getParentFile, listeners)
        } else {
          watch(file.getParentFile, listeners)
        }
      }
    } catch {
      case e: Throwable =>
        log.error(s"failed to watch ${file}")
    }

  }

  def registerDir(dir: File, listeners: Set[WatcherListener]): Unit = {
    log.debug(s"register ${dir} with a watch service")
    val observers = (listeners map { maybeBuildWatchKeyObserver(dir, _) }).flatten
    if (!observers.isEmpty) {
      val key: WatchKey = try {
        dir.toPath.register(
          watchService,
          ENTRY_CREATE,
          ENTRY_MODIFY,
          ENTRY_DELETE
        )
      } catch {
        case e: Throwable => {
          log.warn("can not register " + dir + " " + e)
          throw new Exception(e)
        }
      }
      log.debug(s"add ${observers.size} listeners to ${dir} ")
      observers foreach { WatchKeyManager.addObserver(key, _) }

      dir.listFiles.filter(f => f.isFile)
        .foreach {
          notifyListeners(
            _,
            ENTRY_CREATE,
            listeners.filter { _.treatExistingAsNew },
            key
          )
        }

      if (WatchKeyManager.hasProxy(key)) {
        dir.listFiles.filter(f => (f.isDirectory || f.isFile))
          .foreach {
            WatchKeyManager.maybeAdvanceProxy(key, _)
          }
      }

    } else {
      log.warn("No listeners for {}. Skip registration.")
    }
  }

  def registerTree(dir: File, listeners: Set[WatcherListener]) = {
    val hasRecursive = listeners.exists { _.recursive }
    implicit def makeFileVisitor(f: (Path) => Unit) = new SimpleFileVisitor[Path] {
      override def preVisitDirectory(p: Path, attrs: BasicFileAttributes) = {
        f(p)
        if (hasRecursive)
          FileVisitResult.CONTINUE
        else
          FileVisitResult.TERMINATE
      }
    }
    Files.walkFileTree(
      dir.toPath,
      (f: Path) => {
        val ls = if (dir.toPath == f) listeners else listeners.filter { _.recursive }
        registerDir(f.toFile, ls)
      }
    )
  }

  /**
   *  Wait for Java7 WatchService event and notify the listeners.
   */
  private def monitor() = {
    log.debug("start monitoring WatchService events")
    breakable {
      while (true) {
        if (!continueMonitoring) break
        Try { watchService.take() } match {
          case Success(key) => {
            processEvents(key)
            if (!key.reset) {
              maybeRecoverFromDeletion(key)
            }
          }
          case Failure(e) => {
            log.error("unexpected WatchService take error. {}", e)
            break
          }
        }
      }
      closeWatchService()

      def processEvents(key: WatchKey) = {
        //log.debug("start processing events for {}", keyToFile(key))

        for (event <- key.pollEvents) {
          val kind = event.kind
          val file = key.watchable.asInstanceOf[Path]
            .resolve(event.context.asInstanceOf[Path]).toFile

          log.debug(s"event: ${kind} for ${file}")

          if (kind == ENTRY_CREATE
            && Files.isDirectory(file.toPath, LinkOption.NOFOLLOW_LINKS)
            && WatchKeyManager.hasRecursive(key)) {
            log.debug(s"watch a subdirectory ${file}")
            watch(
              file,
              WatchKeyManager.recListeners(key)
            )
          }

          if (kind == ENTRY_CREATE) {
            log.debug(s"maybeAdvanceProxy ${keyToFile(key)} ${file}")
            WatchKeyManager.maybeAdvanceProxy(key, file)
          }

          // if (kind == ENTRY_DELETE && keyToFile(key) == file) {
          //   log.debug(s"not running maybeRecoverFromDeletion ${keyToFile(key)} ${file}")
          //   //maybeRecoverFromDeletion(key)
          // }
          notifyListeners(file, kind, WatchKeyManager.nonProxyListeners(key), key)
        }
      }

      def maybeRecoverFromDeletion(key: WatchKey) = {
        if (WatchKeyManager.hasBase(key)
          || WatchKeyManager.hasBaseFile(key)
          || WatchKeyManager.hasProxy(key)) {

          if (!key.mkdirs) {
            log.error("Unable to re-create {} with parents", key)
          } else {
            //log.debug("re-watch {}", keyToCanonicalPath(key))
            val listeners = WatchKeyManager.listeners(key)
            val baseListeners = WatchKeyManager.baseListeners(key)
            listeners foreach { _.baseRemoved(key) }
            WatchKeyManager.removeKey(key)
            watch(key, listeners)
            baseListeners foreach { _.baseReCreated(key) }
          }
        }
      }

      def continueMonitoring() = {
        monitorThread match {
          case Some(t) => if (t.isInterrupted) {
            log.info("monitoring thread was interrupted")
            false
          }
          case None => {
            log.info("monitoring should run in a background thread")
            false
          }
        }
        if (!shouldRun) {
          log.info("request to stop monitoring")
          false
        }
        true
      }
    }
  }

  def closeWatchService() = {
    try {
      log.info("close  WatchService")
      watchService.close();
    } catch {
      case e: Throwable =>
        log.error("failed to close WatchService {}", e);
    }
  }

  def spawnWatcher() = {
    trait ChildWatcher {
      val watcherId = UUID.randomUUID()
      def register(file: File, listeners: Set[WatcherListener]): Unit = {
        self.watch(file, listeners)
      }
      def shutdown() = {
        self.WatchKeyManager.removeObservers(watcherId)
      }
    }
    new ChildWatcher() {
    }
  }

  private def notifyListeners(f: File, kind: Kind[_], listeners: Set[WatcherListener], key: WatchKey) = {
    //log.trace("got {event} for {f}")
    if (kind == ENTRY_CREATE) {
      //log.debug(s"detected ENTRY_CREATE ${f} for ${keyToFile(key)}")
      listeners filter { _.isWatched(f) } foreach { _.fileCreated(f) }
    }
    if (kind == ENTRY_MODIFY) {
      //log.debug(s"detected ENTRY_MODIFY ${f} for ${keyToFile(key)}")
      listeners filter { _.isWatched(f) } foreach { _.fileModified(f) }
    }
    if (kind == ENTRY_DELETE) {
      //log.debug(s"detected ENTRY_DELETE ${f} for ${keyToFile(key)}")
      listeners filter { _.isWatched(f) } foreach { _.fileDeleted(f) }
    }
    if (kind == OVERFLOW) {
      //log.debug(s"detected OVERFLOW ${f} for ${keyToFile(key)}")
    }
  }

  def maybeBuildWatchKeyObserver(f: File, l: WatcherListener): Option[WatchKeyObserver] = {
    if (!f.isDirectory) {
      log.warn("building a WatchKeyObserver for a non-existent {} doesn't make sense.", f)
      return None
    }
    if (l.base == f) {
      Some(new BaseObserver(l))
    } else if (l.base.isFile && l.base.getParentFile == f) {
      Some(new BaseFileObserver(l))
    } else if (l.recursive && f.getAbsolutePath.startsWith(l.base.getAbsolutePath)) {
      Some(new BaseSubdirObserver(l))
    } else if (l.base.getAbsolutePath.startsWith(f.getAbsolutePath)) {
      Some(new ProxyObserver(l))
    } else {
      None
    }
  }

  init()

  case class BaseObserver(val watcherListener: WatcherListener) extends WatchKeyObserver {
    override lazy val treatExistingAsNew = watcherListener.treatExistingAsNew
    override lazy val recursive = watcherListener.recursive
    override val observerType = "BaseListener"
  }
  case class BaseFileObserver(val watcherListener: WatcherListener) extends WatchKeyObserver {
    val treatExistingAsNew = false
    val recursive = false
    override val observerType = "BaseFileListener"
  }
  case class ProxyObserver(val watcherListener: WatcherListener) extends WatchKeyObserver {
    override val treatExistingAsNew = true
    val recursive = false
    override val observerType = "ProxyListener"
  }
  case class BaseSubdirObserver(val watcherListener: WatcherListener) extends WatchKeyObserver {
    override lazy val treatExistingAsNew = watcherListener.treatExistingAsNew
    override lazy val recursive = watcherListener.recursive
    override val observerType = "BaseSubdirListener"
  }

  trait WatchKeyObserver {
    val watcherListener: WatcherListener
    val treatExistingAsNew: Boolean
    val recursive: Boolean
    val observerType: String
  }

  object WatchKeyManager {
    val keymap: Map[WatchKey, Set[WatchKeyObserver]] = new ConcurrentHashMap().asScala

    @tailrec
    def addObserver(key: WatchKey, o: WatchKeyObserver): Unit = {
      log.debug(s"add a ${o.observerType} to ${keyToFile(key)} ")
      val l = Set[WatchKeyObserver]()
      val oldListeners = keymap.putIfAbsent(key, l).getOrElse(l)
      val newListeners = oldListeners + o
      val status = keymap.replace(key, oldListeners, newListeners)
      if (!status) {
        log.warn(s"retry adding ${o.observerType} to ${keyToFile(key)}")
        addObserver(key, o)
      }
    }

    @tailrec
    def removeObserver(key: WatchKey, o: WatchKeyObserver): Unit = {
      log.debug(s"remove ${o.observerType}  from ${keyToFile(key)}")
      keymap.get(key) match {
        case Some(oldObservers) => {
          val newObservers = oldObservers - o
          if (keymap.replace(key, oldObservers, newObservers)) {
            if (keymap.get(key).size == 0) {
              log.debug("no more listeners, cancel {}", keyToFile(key))
              key.cancel()
              keymap.remove(key)
            }
          } else {
            log.debug("retry removing a listener from {}", keyToFile(key))
            removeObserver(key, o)
          }
        }
        case None => log.warn(s"failed to remove ${o.observerType} from ${keyToFile(key)}")
      }
    }

    def maybeAdvanceProxy(key: WatchKey, createdFile: File) = {
      proxies(key) foreach { o =>
        if (o.watcherListener.isBaseAncestor(createdFile)) {
          if (createdFile.isDirectory || createdFile.isFile) {
            log.debug(s"advance a proxy from ${keyToFile(key)} to ${createdFile}")
            removeObserver(key, o)
            watch(createdFile, Set(o.watcherListener))
          } else {
            log.warn("unable to advance a proxy {}", o)
          }
        }
      }
    }

    def removeObservers(id: UUID) = {
      keymap.keys foreach {
        key =>
          {
            val observers = keymap.get(key).getOrElse { Set() }
            val unneeded = observers filter { _.watcherListener.watcherId == id }
            val retained = observers filter { _.watcherListener.watcherId != id }

            if (observers.size == 0 || unneeded.size == observers.size) {
              log.debug(s"cancel a WatchKey ${keyToFile(key)} for ${id}")
              key.cancel()
              keymap.remove(key)
            } else {
              if (observers.size != retained.size) {
                if (keymap.replace(key, observers, retained)) {
                  log.debug(s"removed ${unneeded.size} listeners from  ${keyToFile(key)}")
                } else
                  log.error(s"failed to remove ${unneeded.size} listeners from  ${keyToFile(key)}")
              }
            }
          }
      }
      // TODO: shutdown watchService if no keys left in directories
    }

    def recListeners(key: WatchKey) = {
      listeners(key) filter { _.recursive }
    }

    def baseListeners(key: WatchKey) = {
      keymap getOrElse (key, Set()) filter {
        case _: BaseObserver => true
        case _ => false
      } map { _.watcherListener }
    }

    def proxyListeners(key: WatchKey) = {
      keymap getOrElse (key, Set()) filter {
        case _: ProxyObserver => true
        case _ => false
      } map { _.watcherListener }
    }

    def nonProxyListeners(key: WatchKey) = {
      keymap getOrElse (key, Set()) filter {
        case _: ProxyObserver => false
        case _ => true
      } map { _.watcherListener }
    }

    def proxies(key: WatchKey) = {
      keymap getOrElse (key, Set()) filter {
        case _: ProxyObserver => true
        case _ => false
      }
    }

    def listeners(key: WatchKey) = {
      keymap getOrElse (key, Set()) map { _.watcherListener }
    }

    def removeKey(key: WatchKey): Unit = {
      key.cancel()
      keymap.remove(key)
    }

    def hasRecursive(key: WatchKey) = {
      keymap.getOrElse(key, Set()) foreach { _.recursive }
      keymap.get(key) match {
        case Some(os) => os.exists { _.recursive }
        case None => false
      }
    }

    def hasBase(key: WatchKey) = {
      keymap.get(key) match {
        case Some(os) => os.exists {
          case _: BaseObserver => true
          case _ => false
        }
        case None => false
      }
    }

    def hasBaseFile(key: WatchKey) = {
      keymap.get(key) match {
        case Some(os) => os.exists {
          case _: BaseFileObserver => true
          case _ => false
        }
        case None => false
      }
    }

    def hasProxy(key: WatchKey) = {
      keymap.get(key) match {
        case Some(os) => os.exists {
          case _: ProxyObserver => true
          case _ => false
        }
        case None => false
      }
    }

    def hasBaseSubdir(key: WatchKey) = {
      keymap.get(key) match {
        case Some(os) => os.exists {
          case _: BaseSubdirObserver => true
          case _ => false
        }
        case None => false
      }
    }
  }
}

trait WatcherListener {
  //val log = LoggerFactory.getLogger(getClass)
  val base: File
  val recursive: Boolean
  val extensions: scala.collection.Set[String]
  val treatExistingAsNew: Boolean
  val watcherId: UUID

  def keyToFile(k: WatchKey): File = k.watchable().asInstanceOf[Path].toFile
  def isWatched(f: File) = {
    (extensions.exists(e => {
      f.getName.endsWith(e)
    })) && f.getPath.startsWith(base.getPath)
  }

  def isBaseAncestor(f: File) = {
    base.getAbsolutePath.startsWith(f.getAbsolutePath)
  }

  def fileCreated(f: File): Unit = {}
  def fileDeleted(f: File): Unit = {}
  def fileModified(f: File): Unit = {}
  def baseReCreated(f: File): Unit = {}
  def baseRemoved(f: File): Unit = {}
}
