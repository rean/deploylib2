package deploylib

import java.io.{File, BufferedReader, InputStreamReader}
// Use Ganymed ssh library
import ch.ethz.ssh2.{Connection, Session, ChannelCondition, SCPClient}
// Add logging
import com.typesafe.scalalogging._
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.io.FileInputStream
import java.math.BigInteger
import java.io.InputStream

case class ExecutionResponse(status: Option[Int],
  stdout: String,
  stderr: String) {

  override def toString(): String = {
    val buf: StringBuffer = new StringBuffer()
    buf.append("Exit-code: %s, stdout: %s, stderr: %s"
      .format(status, stdout, stderr))
    buf.toString
  }
}

case class UnknownResponse(ex: ExecutionResponse) extends Exception

case class RemoteFile(name: String,
  owner: String,
  permissions: String,
  modDate: String,
  size: String,
  isDir: Boolean,
  symLink:Boolean=false) {

  override def toString(): String = {
    val buf: StringBuffer = new StringBuffer()
    buf.append(name)
      .append(" ")
      .append(permissions)
      .append(" ")
      .append("dir? ")
      .append(isDir)
      .append(" ")
      .append("symlink? ")
      .append(symLink)
    buf.toString()
  }
}

object Util {
  def sha256sum(localFile: File): String = {
    val digest = MessageDigest.getInstance("SHA-256");
    val buffer = new Array[Byte](8*1024) // Use 8K buffer
    val is = new FileInputStream(localFile)
    var bytesRead: Long = 0

    var start = System.currentTimeMillis
    var len = is.read(buffer)
    while (len > 0) {
      bytesRead += len
      digest.update(buffer, 0, len)
      len = is.read(buffer)
    }

    val sha256sum = digest.digest()
    val bigInt = new BigInteger(1, sha256sum)
    var bigIntStr = bigInt.toString(16) // Hex result
    var end = System.currentTimeMillis

    val durationSecs: Double = (end-start)/1000.0
    println("Time (secs): " + durationSecs)
    println("Bytes read : " + bytesRead)
    println("B/W MBps   : " + (bytesRead.toDouble/(1024*1024))/durationSecs)

    // Do we need to pad? Just in case
    while (bigIntStr.length < 64) {
      bigIntStr = "0" + bigIntStr
    }
    bigIntStr
  }
}

object RemoteMachine {
  // Force "ls" command to be: ls -al --time-style="+%Y-%m-%d,%H:%M:%S"
  val PERMS_COL: Int    = 0
  val LINK_NUM_COL: Int = 1
  val USER_COL: Int     = 2
  val GROUP_COL: Int    = 3
  val SIZE_COL: Int     = 4
  val MOD_DATE_COL: Int = 5
  val NAME_COL: Int     = 6

  val SYMLINK_PATTERN:String = " -> "

  def findPrivateKey(keyPath: String = null): File = {
    // Inner func to look in well-known places: env variable or .ssh dir
    // by default
    def defaultKeyFiles = {
      val envKey = System.getenv("DEPLOYLIB_SSHKEY")
      val rsaKey = new File(System.getProperty("user.home"), ".ssh/id_rsa")
      val dsaKey = new File(System.getProperty("user.home"), ".ssh/id_dsa")

      if (envKey != null) new File(envKey)
      else if (rsaKey.exists) rsaKey
      else if (dsaKey.exists) dsaKey
      else throw new RuntimeException("No private key found")
    }

    // Use the key path passed in (if it's non null) otherwise
    // fallback to defaults
    if (keyPath != null) {
      val privateKey = new java.io.File(keyPath)
      if (privateKey.exists)
        privateKey
    }
    // Call inner func
    defaultKeyFiles
  }

  // Public key file names and private key file names share the same
  // root: id_rsa.pub vs. id_rsa. So find the private key name
  // and then add ".pub" to get the corresponding public key file
  def findPublicKey(keyPath: String = null): File = {
    new File(findPrivateKey(keyPath).getCanonicalPath + ".pub")
  }

  // Quick test
  def main(args: Array[String]) {
    /*
    val logger = Logger(LoggerFactory.getLogger("[DeployLib]"))
    //logger.debug("Convenient")
    println( RemoteMachine.findPrivateKey() )
    println( RemoteMachine.findPublicKey() )

    val haymans = new MachinePrep("haymans", "root")
    haymans.update_package_repos()
    haymans.installPreReqPackages()
     */
    //val m = new RemoteMachine(username="rtgbim")
    //println(m.ls())

    /*
    for (file <- m.ls())
      println("File: " + file)
     */

    //val apache: Apache = new Apache
    //apache.install()

    val rain: RainNginx = new RainNginx
    rain.runExpt(5, "/tmp/rain-tomcat-1-threads-docker-run%02d.out")

    /*
    val file1 = ".bashrc"
    //println("Hash : %s".format(m.sha256sum(new File(file1))))
    println("Local: %s".format(Util
      .sha256sum(new File("/tmp/%s".format(file1)))))

    println("Remote: %s".format(m.sha256sum(new File("/tmp/%s"
      .format(file1)))))

    val file2 = file1//".bashrc2"
    println("Local: %s".format(m.sha256sumLocal(new File("/tmp/%s"
      .format(file2)))))
     */
  }
}

class RemoteMachine(host: String="localhost", username: String="root",
  privateKeyPath: String = null) {
  self => import RemoteMachine._

  val hostname: String = host
  val user: String = username
  val privateKey: File = findPrivateKey(privateKeyPath)
  //val rootDirectory: File
  //val javaCmd: File
  val logger = Logger(LoggerFactory.getLogger("[DeployLib]"))
  //var assignedServices: Set[Service] = Set()
  var connection: Connection = null

  protected implicit def toOption[A](a: A) = Option(a)
  protected def prepareCommand(rawCmd: String): String = {
    //val cmd: StringBuffer = new StringBuffer()
    // Prepend the redirect and the nohub
    //cmd.append("2>&1").append(" ")
      //.append("nohup").append(" ")
      //.append(rawCmd)

    // append the pipe to tee

    // Prepared command
    //cmd.toString
    rawCmd
  }

  protected def useConnection[ReturnType](func:(Connection)=>ReturnType,
    numRetries:Int=5): ReturnType = {

    // Failure handler
    def onFailure(e: Exception) = {
      connection = null
      logger.warn("Connection to %s failed: %s".format(this.hostname, e))

      if (numRetries <= 1)
        throw new RuntimeException("Max number of retries exceeded", e)
      // Wait for a bit
      Thread.sleep(30*1000)
      useConnection(func, numRetries - 1)
    }

    try {
      if (connection == null) synchronized {
        if (connection == null) {
          connection = new Connection(hostname)
          logger.info("Connecting to: %s".format(hostname))
          connection.connect()
          logger.info("Authenticating with username: %s, privateKey: %s"
            .format(username, privateKey))
          connection.authenticateWithPublicKey(username, privateKey, "")
        }
      }
      // Call the function using the connection
      func(connection)
    } catch {
      case e: java.io.IOException => onFailure(e)
      case e: java.net.SocketException => onFailure(e)
      case e: java.lang.IllegalStateException => onFailure(e)
    }
  }

  def !(cmd: String) = executeCommand(cmd) match {
    case ExecutionResponse(Some(0), stdout, stderr) =>
      logger.debug("%s: cmd: %s\nstdout: %s\nstderr: %s\n"
        .format(hostname, cmd, stdout, stderr))
    case ExecutionResponse(_, stdout, stderr) => {
      logger.debug("%s: cmd: %s\nstdout: %s\nstderr: %s\n"
        .format(hostname, cmd, stdout, stderr))
      throw new RuntimeException("Cmd failed: " + cmd)
    }
  }

  def !?(cmd:String) = executeCommand(cmd) match {
    case ExecutionResponse(Some(0), stdout, "") => stdout
    case err => throw new UnknownResponse(err)
  }

  def executeCommand(cmd: String, timeout: Long=0): ExecutionResponse = {
    useConnection((c) => {
      val stdout = new StringBuilder
      val stderr = new StringBuilder
      val session = connection.openSession
      val outReader = new BufferedReader(
        new InputStreamReader(session.getStdout()))
      val errReader = new BufferedReader(
        new InputStreamReader(session.getStderr()))
      val preparedCommand = prepareCommand(cmd)
      logger.debug("Raw cmd     : %s".format(cmd))
      logger.debug("Prepared cmd: %s".format(preparedCommand))
      session.execCommand(prepareCommand(preparedCommand))

      var continue = true
      var exitStatus: java.lang.Integer = null
      while (continue) {
        val status = session.waitForCondition(ChannelCondition.STDOUT_DATA |
          ChannelCondition.STDERR_DATA |
          ChannelCondition.EXIT_STATUS |
          ChannelCondition.EXIT_SIGNAL |
          ChannelCondition.EOF |
          ChannelCondition.CLOSED |
          ChannelCondition.TIMEOUT, timeout)

        // Read stdout
        if ((status & ChannelCondition.STDOUT_DATA) != 0) {
          while (outReader.ready) {
            val line = outReader.readLine()
            if (line != null) {
              logger.debug("Received STDOUT_DATA: %s".format(line))
              stdout.append(line).append("\n")
            }
          }
        }

        // Read stderr
        if ((status & ChannelCondition.STDERR_DATA) != 0) {
          while (errReader.ready) {
            val line = errReader.readLine()
            if (line != null) {
              logger.debug("Received STDERR_DATA: %s".format(line))
              stderr.append(line).append("\n")
            }
          }
        }

        // Check for exit status
        if ((status & ChannelCondition.EXIT_STATUS) != 0) {
          logger.debug("Received EXIT_STATUS")
          exitStatus = session.getExitStatus()
        }

        // Check for exit signal
        if ((status & ChannelCondition.EXIT_SIGNAL) != 0) {
          logger.debug("Received EXIT_SIGNAL: " + session.getExitSignal())
        }

        // Check for EOF
        if ((status & ChannelCondition.CLOSED) != 0) {
          logger.debug("Received CLOSED")
          continue = false // give up - no more retries
        }

        // Check for timeout
        if ((status & ChannelCondition.TIMEOUT) != 0) {
          logger.debug("Received TIMEOUT")
          continue = false // give up - no more retries
        }
      }
      session.close()
      ExecutionResponse(Some(exitStatus.intValue),
        stdout.toString,
        stderr.toString)
    }) // end-useConnection function call
  }

  // Commands
  def ls(dir: File=null): Array[RemoteFile] = {

    // Check whether dir is null or empty
    val targetDir = dir match {
      case null => "." //new File(".") // default to (some) homedir
      case _ => dir
    }

    val cmd: StringBuffer = new StringBuffer
    cmd.append("ls -al ")
      .append(targetDir)
      .append(" --time-style='+%Y-%m-%d,%H:%M:%S'")

    // Use a default of home dir if dir==null
    //executeCommand("ls -al %s --time-style='+%Y-%m-%d,%H:%M:%S'"
      //.format(targetDir)) match {
    executeCommand(cmd.toString) match {
      case ExecutionResponse(Some(0), data, "") => {
        // Have to deal with filenames with spaces in them
        data.split("\n").drop(1).map( line => {

          val parts = line.split(" ").flatMap {
            case "" => Nil
            case x => Some(x)
          }

          val fname = new StringBuffer();
          var symLink:Boolean = false
          // We actually need to be careful of splits on ' '
          // e.g., when the filename itself has spaces we'll
          // want to concat things together. We will want
          // to treat links as special these names contain '->'
          if (parts.size > 7) {
            //logger.info("*********special fname: %s".format(line))
            if (line.contains(RemoteMachine.SYMLINK_PATTERN)) {
              fname.append(parts(RemoteMachine.NAME_COL))
              symLink = true
            } else {
              // Concat fragments
              val buf = new StringBuffer()
              for(i <- RemoteMachine.NAME_COL until parts.length) {
                buf.append(parts(i))
                buf.append(" ")
              }
              fname.append(buf.toString)
              println("Reconstructed name: %s".format(buf.toString))
            }
          } else {
            fname.append(parts(RemoteMachine.NAME_COL))
          }

          println("Reconstructed name2: %s".format(fname.toString))

          val permissions = parts(RemoteMachine.PERMS_COL)
          RemoteFile(fname.toString,
              parts(RemoteMachine.USER_COL),
              permissions,
              parts(RemoteMachine.MOD_DATE_COL),
              parts(RemoteMachine.SIZE_COL),
              permissions.startsWith("d"),
              symLink)
        })
      }
      case er => throw new UnknownResponse(er)
    }// end-match on result from executeCommand
  }

  def dirExists(dir: File): Boolean = {
    executeCommand("stat %s".format(dir)) match {
      case ExecutionResponse(Some(0), data, "") => {
        val parts: Array[String] = data.split("\n")
        // The second line for stat has the info we need
        val fileType = parts(1).split(" ").last
        //println("Type line: " + fileType)
        if (fileType.equalsIgnoreCase("directory")) {
          //println("directory exists")
          true
        } else {
          //println("directory does not exist")
          false
        }
      }
      case res:ExecutionResponse => {
        //logger.error("Unable to stat file: %s. Reason: %s"
        //  .format(file, res))
        //println("directory does not exist***")
        false
      }
    }

  }

  def isDir(file: File): Boolean = {
    false
  }

  def mkdir(dir: File, useSudo: Boolean = false):Boolean = {
    val mkdirCmd: StringBuffer = new StringBuffer
    if (useSudo) {
      mkdirCmd.append("sudo ")
    }
    mkdirCmd.append("mkdir -p %s".format(dir))

    val retVal = executeCommand(mkdirCmd.toString) match {
      case ExecutionResponse(Some(0), _, _) => true
      case res: ExecutionResponse => {
        logger.error("Error creating directory: %s. Reason: %s"
          .format(dir, res))
        false
      }
    }
    retVal
  }

  def chown(file: File, user: String): Boolean = {
    executeCommand("sudo chown -R %s %s".format(user,
      file.getAbsolutePath)) match {
      case ExecutionResponse(Some(0), data, "") => true
      case res:ExecutionResponse => false
    }
  }

  def chgrp(file: File, group: String): Boolean = {
    executeCommand("sudo chgrp -R %s %s".format(group,
      file.getAbsolutePath)) match {
      case ExecutionResponse(Some(0), data, "") => true
      case res:ExecutionResponse => false
    }
  }

  def cd(file: File): Boolean = {
    executeCommand("cd %s".format(file.getAbsolutePath)) match {
      case ExecutionResponse(Some(0), data, "") => true
      case res:ExecutionResponse => false
    }
  }

  def fileExists(file: File): Boolean = {
    executeCommand("stat %s".format(file)) match {
      case ExecutionResponse(Some(0), data, "") => true
      case res:ExecutionResponse => {
        //logger.error("Unable to stat file: %s. Reason: %s"
        //  .format(file, res))
        false
      }
    }
  }

  def cat(remoteFile: File): String = {
    executeCommand("cat %s".format(remoteFile)) match {
      case ExecutionResponse(Some(0), data, "") => data
      case res:ExecutionResponse => {
        logger.error("Unable to cat file: %s. Reason: %s"
          .format(remoteFile, res))
        ""
      }
    }
  }

  def sha256sum(remoteFile: File, timeout:Long = 20): String = {
    val failureMessage = "sha256sum: %s: No such file or directory"
      .format(remoteFile)
    executeCommand("sha256sum %s".format(remoteFile)) match {
      case ExecutionResponse(Some(0), result, "") => {
        val hash = result.split(" ")(0)
        logger.debug("Got hash: %s for file: %s".format(hash, remoteFile))
        hash
      }
      case ExecutionResponse(Some(1), "", failureMessage) => {
        logger.error("Unable to get hash for non-existent file: %s"
          .format(remoteFile))
        ""
      }
      case res:ExecutionResponse => {
        logger.error("Unable to get hash for file. Reason: %s".format(res))
        ""
      }
    }
  }

  def executeLocalCommand(cmd: String): ExecutionResponse = {
    val output: StringBuffer = new StringBuffer()
    val error: StringBuffer = new StringBuffer()

    try {
      // Execute command
      val proc: Process = Runtime.getRuntime().exec(cmd);
      // Wait for command to completed
      proc.waitFor();
      val outReader: BufferedReader =
        new BufferedReader(new InputStreamReader(proc.getInputStream()))
      val errReader: BufferedReader =
        new BufferedReader(new InputStreamReader(proc.getErrorStream()))

      while (outReader.ready) {
        val line = outReader.readLine()
        if (line != null) {
          output.append(line).append("\n")
        }
      }

      while (errReader.ready) {
        val line = errReader.readLine()
        if (line != null) {
          error.append(line).append("\n")
        }
      }
      // Return execution response
      new ExecutionResponse(Some(0), output.toString(), error.toString())
    } catch {
      case e: Exception => new ExecutionResponse(Some(1), e.toString(), "")
    }
  }

  def sha256sumLocal(localFile: File): String = {
    val failureMessage = "sha256sum: %s: No such file or directory"
      .format(localFile)

    // Does file exist?
    if (!localFile.exists()) {
      logger.error("Unable to get hash for non-existent file: %s"
        .format(localFile))
      return ""
    }
    executeLocalCommand("sha256sum %s".format(localFile)) match {
      case ExecutionResponse(Some(0), result, "") => {
        val hash = result.split(" ")(0)
        logger.debug("Got hash: %s for file: %s".format(hash, localFile))
        hash
      }
      case ExecutionResponse(Some(1), "", failureMessage) => {
        logger.error("Unable to get hash for non-existent file: %s"
          .format(localFile))
        ""
      }
      case res:ExecutionResponse => {
        logger.error("Unable to get hash for file. Reason: %s".format(res))
        ""
      }
    }
  }

  def uploadFile(localFile: File, where: File): Unit = {
    // Make sure remote path (where exists) and is a directory
    if (where.exists() && where.isDirectory()) {
      val remoteFileName = "%s/%s".format(where.getAbsolutePath(),
        where.getName())

      val remoteFileHash = this.sha256sum(new File(remoteFileName))
      val localFileHash = this.sha256sumLocal(localFile)

      if (remoteFileHash != localFileHash) {
        // Do upload/copy
      }
    } else {
      // File either does not exist or is not a directory
    }

    // Create destination file name (where + localFile.name)

    // Get the hash for localFile, get the hash for remote file

    // If they match then don't do the scp/upload

    // If they don't match then do the scp/upload
  }

  def downloadFile(remoteFile: File, where: File): Unit = {

  }

}// end-RemoteMachine


/*
class Django(installDir: String, installURI: String) {

}

class Apache(installDir: String) {

}
 */
