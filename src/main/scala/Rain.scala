package deploylib

import java.io.{File}
import scala.collection.mutable.{ArrayBuffer}
import org.json._

class Rain(host: String = "localhost", user: String = "exo") {
  final val TARGET_URL_KEY = "targetUrl"
  final val GENERATOR_PARAMS_KEY = "generatorParameters"
  final val LOAD_PROFILE_CONFIG_KEY = "loadProfile"
  final val USERS_CONFIG_KEY = "users"
  final val TARGET_HOST_CONFIG_KEY = "target"
  final val HOSTNAME_CONFIG_KEY = "hostname"
  final val HOST_PORT_CONFIG_KEY = "port"

  final val PROFILES_CONFIG_KEY = "profiles"
  final val TIMING_CONFIG_KEY = "timing"
  final val RAMP_UP_CONFIG_KEY = "rampUp"
  final val DURATION_CONFIG_KEY = "duration"
  final val RAMP_DOWN_CONFIG_KEY = "rampDown"

  val machine = new RemoteMachine(host, user)
  val rainGitRepoUrl = "https://github.com/rean/rain-workload-toolkit.git"
  var rainExptFilename: String = ""
  val preReqPackages: ArrayBuffer[String] = new ArrayBuffer[String]()
  preReqPackages += "git"
  preReqPackages += "openjdk-7-jdk"
  preReqPackages += "ant"

  def installPreReqPackages() = {
    val installPreReqCmd: StringBuffer = new StringBuffer()
     installPreReqCmd.append("2>&1 nohup sudo apt-get install ")

    preReqPackages.foreach{ pkg => { installPreReqCmd.append(pkg).append(" ") }}
    installPreReqCmd.append("-y")
    // Pipe to tee
    installPreReqCmd
      .append(" | tee /tmp/rain-prep-pre-req.deploylib.out >> /dev/null")
    println("Prep install pre-req command: %s"
      .format(installPreReqCmd.toString))

    //machine.executeCommand("sudo apt-get update")
    machine.executeCommand(installPreReqCmd.toString)
  }

  def checkoutLatest(parentDirName: String, sourceDir: String,
    branchName: String = "master"): Boolean = {
    val parentDir: File = new File(parentDirName)
    // Check whether the parent dir exists, if not then create it
    if (!machine.dirExists(parentDir)) {
      if (!machine.mkdir(parentDir)) {
        return false
      }
    }

    val buf: StringBuffer = new StringBuffer()
    buf.append(parentDirName).append("/").append(sourceDir)

    if (machine.dirExists(new File(buf.toString))) {
      // cd into dir and do a git pull --rebase origin <branchname>
      val cmd: StringBuffer = new StringBuffer()
      cmd.append("cd %s && git pull --rebase origin %s"
        .format(buf.toString, branchName))
        .append(" | tee /tmp/rain-checkout-latest.deploylib.out >> /dev/null")
      machine.executeCommand(cmd.toString)
    } else {
      // cd into dir and do a git clone
      val cmd: StringBuffer = new StringBuffer()
      cmd.append("cd %s && git clone %s %s"
        .format(parentDirName, rainGitRepoUrl, sourceDir))
      machine.executeCommand(cmd.toString)
    }

    true
  } // end-checkoutLatest

  def createExperimentConfig(rainConfigDir: String, exptName: String,
    targetUrl: String, targetHost: String, port: Integer, numUsers: Integer,
    rampUpSecs: Integer=10, durationSecs: Integer=300,
    rampDownSecs: Integer=10): Boolean = {

    // There are two config files we care about
    // 1) the experiment config file: rain.config.xxxx.json
    // 2) the workload config file: profiles.config.xxxx.json
    val exptTemplateFile = "rain.config.%s.json"
    val workloadTemplateFile = "profiles.config.%s.json"

    val origTag = "ukweb"

    val origExptFile: File = {
      val buf = new StringBuffer()
      buf.append(rainConfigDir)
        .append("/")
        .append(exptTemplateFile.format(origTag))
      new File(buf.toString)
    }

    val exptFile: File = {
      val buf = new StringBuffer()
      buf.append(rainConfigDir)
        .append("/")
        .append(exptTemplateFile.format(exptName))
      new File (buf.toString)
    }

    val origWorkloadFile: File = {
      val buf = new StringBuffer()
      buf.append(rainConfigDir)
        .append("/")
        .append(workloadTemplateFile.format(origTag))
      new File(buf.toString)
    }

    val workloadFile: File = {
      val buf = new StringBuffer()
      buf.append(rainConfigDir)
        .append("/")
        .append(workloadTemplateFile.format(exptName))
      new File(buf.toString)
    }

    // Save the name of the experiment file so we can use it later
    this.rainExptFilename = exptFile.getName

    println("Original expt file: %s".format(origExptFile))
    println("New expt file     : %s".format(exptFile))
    println("Original wkld file: %s".format(origWorkloadFile))
    println("New wkld file     : %s".format(workloadFile))

    // Make sure the original files exist - bail it they don't
    if (!machine.fileExists(origExptFile)) {
      return false
    }

    if (!machine.fileExists(origWorkloadFile)) {
      return false
    }

    // Cat the existing profiles file so we can swap out the
    // targeturl, hostname and port
    val profilesJson: JSONObject = new JSONObject(machine.cat(origWorkloadFile))
    val trackName: String = profilesJson.names().getString(0)
    //println(profilesJson)
    val workloadTrack: JSONObject = profilesJson
      .getJSONObject(trackName)

    // Get the generator parameters section
    val generatorParameters: JSONObject = workloadTrack
      .getJSONObject(GENERATOR_PARAMS_KEY)
    //println(generatorParameters.toString)

    // Set the new target URL
    generatorParameters.put(TARGET_URL_KEY, targetUrl)

    // Update the workload track with the new generator parameters
    workloadTrack.put(GENERATOR_PARAMS_KEY, generatorParameters)
    //println(generatorParameters.toString)

    val loadProfile: JSONArray = workloadTrack
      .getJSONArray(LOAD_PROFILE_CONFIG_KEY)

    (0 until loadProfile.length).map{ i => {
      val loadInterval: JSONObject = loadProfile.getJSONObject(i)
      // Set the number of users
      loadInterval.put(USERS_CONFIG_KEY, numUsers)
      // Update the number of users for this interval
      loadProfile.put(i, loadInterval)
    }}

    // Get the target host section
    val targetHostConfig: JSONObject = workloadTrack
      .getJSONObject(TARGET_HOST_CONFIG_KEY)
    println(targetHostConfig.toString)

    targetHostConfig.put(HOSTNAME_CONFIG_KEY, targetHost)
    targetHostConfig.put(HOST_PORT_CONFIG_KEY, port)
    println(targetHostConfig.toString)

    // Update the workload track with the new target details
    workloadTrack.put(TARGET_HOST_CONFIG_KEY, targetHostConfig)

    // Final profile file
    profilesJson.put(trackName, workloadTrack)

    if(!machine.echoStringToFile(profilesJson.toString(), workloadFile)) {
      return false
    }

    // Now modify the experiment file to point at the new profiles.config file
    // and optionally set the timing
    val rainJson: JSONObject = new JSONObject(machine.cat(origExptFile))
    rainJson.put(PROFILES_CONFIG_KEY, "config/" + workloadFile.getName())
    val timingConfig: JSONObject = rainJson.getJSONObject(TIMING_CONFIG_KEY)
    timingConfig.put(RAMP_UP_CONFIG_KEY, rampUpSecs)
    timingConfig.put(DURATION_CONFIG_KEY, durationSecs)
    timingConfig.put(RAMP_DOWN_CONFIG_KEY, rampDownSecs)

    rainJson.put(TIMING_CONFIG_KEY, timingConfig)

    println(rainJson.toString)

    if(!machine.echoStringToFile(rainJson.toString(), exptFile)) {
      return false
    }

    true
  }

  def buildRainCore(rainDir: String): Boolean = {
    val cmd: StringBuffer = new StringBuffer()
    cmd.append("cd %s && ant package".format(rainDir))
      .append(" | tee /tmp/rain-build-rain-core.deploylib.out >> /dev/null")

    val retVal = machine.executeCommand(cmd.toString) match {
      case ExecutionResponse(Some(0), _, _) => true
      case res: ExecutionResponse => {
        false
      }
    }
    retVal
  }

  def buildUkWebGenerator(rainDir: String): Boolean = {
    val cmd: StringBuffer = new StringBuffer()
    cmd.append("cd %s && ant package-ukweb".format(rainDir))
      .append(" | tee /tmp/rain-build-ukweb-gen.deploylib.out >> /dev/null")

    val retVal = machine.executeCommand(cmd.toString) match {
      case ExecutionResponse(Some(0), _, _) => true
      case res: ExecutionResponse => {
        false
      }
    }
    retVal
  }

  def runExperiment(rainSourceDir: String,
    repeats: Integer = 5,
    outputFilenameTemplate: String): Boolean = {

    val rainCmdBase: StringBuffer = new StringBuffer
    rainCmdBase.append("cd %s && java -cp .:./rain.jar:workloads/ukweb.jar "
      .format(rainSourceDir))
      .append("radlab.rain.Benchmark config/%s".format(this.rainExptFilename))

    for(i <- 0 until repeats) {
      val outputFname = outputFilenameTemplate.format(i)
      val cmd: StringBuffer = new StringBuffer
      cmd.append(rainCmdBase.toString)
        .append(" | tee %s >> /dev/null".format(outputFname))
      println("Repeat %s - output file %s".format(i, outputFname))
      println("Cmd: %s".format(cmd.toString))

      machine.executeCommand(cmd.toString)
    }

    true
  }

  installPreReqPackages()
}

object Rain {
  def main(args: Array[String]) {
    val host: String = "localhost"
    val user: String = "rtgbim"

    val loadGen: Rain = new Rain(host, user)
    val parentDir = "/tmp"
    val srcDir = "rain"

    if (!loadGen.checkoutLatest(parentDir, srcDir)) {
      return // exit
    }

    val rainDir = {
      val buf = new StringBuffer()
      buf.append(parentDir).append("/").append(srcDir)
      buf.toString
    }

    val rainConfigDir = {
      val buf = new StringBuffer()
      buf.append(rainDir).append("/").append("config")
      buf.toString
    }

    // Everything after http://<host>:port/
    val targetUrl: String = "examples/servlets/servlet/HelloWorldExample"
    // What system are we testing, e.g., tomcat, nginx, wildfly
    val exptName: String = "tomcat"
    // Where is the target system running
    val targetHost: String = "192.168.122.89"
    val port: Integer = 8080
    val numUsers: Integer = 1
    // What runtime is the target system running in, e.g., osv, vm, docker,
    // or linux (bare metral)
    val appRuntime: String = "osv"
    // How many times to we want to repeat our experiment
    val numRepeats: Integer = 5

    // Set warmup, duration and warmdown
    val rampUpSecs: Integer = 10
    val durationSecs: Integer = 300
    val rampDownSecs: Integer = 10

    if (!loadGen.createExperimentConfig(rainConfigDir, exptName, targetUrl,
      targetHost, port, numUsers, rampUpSecs, durationSecs, rampDownSecs)) {
      return // exit
    }

    // Build rain
    if (!loadGen.buildRainCore(rainDir)) {
      return // exit
    }

    if (!loadGen.buildUkWebGenerator(rainDir)) {
      return // exit
    }

    // Launch experiment, + tee output to the right place
    val buf = new StringBuffer()
    buf.append("/tmp/rain-%s-%s-threads-%s")

    val outputFilenameTemplate = new StringBuffer()
    outputFilenameTemplate.append(buf.toString()
      .format(exptName, numUsers, appRuntime))
    outputFilenameTemplate.append("-run%02d.out")

    loadGen.runExperiment(rainDir, numRepeats, outputFilenameTemplate.toString)
  }
}
