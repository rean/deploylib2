package deploylib

import java.io.{File}
import java.text.{NumberFormat, DecimalFormat}
import scala.collection.mutable.{ArrayBuffer}

class ApacheBench(host: String = "localhost", user: String = "exo") {
  val machine = new RemoteMachine(host, user)
  val preReqPackages: ArrayBuffer[String] = new ArrayBuffer[String]()
  preReqPackages += "apache2-utils"
  preReqPackages += "grep"
  preReqPackages += "gawk"

  def installPreReqPackages() = {
    val aptUpdateCmd: StringBuffer = new StringBuffer()
    aptUpdateCmd.append("2>&1 nohup sudo apt-get update ")
      .append(" | tee /tmp/ab-prep-apt-update.deploylib.out >> /dev/null")

    machine.executeCommand(aptUpdateCmd.toString)

    val installPreReqCmd: StringBuffer = new StringBuffer()
    installPreReqCmd.append("2>&1 nohup sudo apt-get install ")

    preReqPackages.foreach{ pkg => { installPreReqCmd.append(pkg).append(" ") }}
    installPreReqCmd.append("-y")
    // Pipe to tee
    installPreReqCmd
      .append(" | tee /tmp/ab-prep-pre-req.deploylib.out >> /dev/null")
    println("Prep install pre-req command: %s"
      .format(installPreReqCmd.toString))

    machine.executeCommand(installPreReqCmd.toString)
  }

  // WARMUP_ITER=10 NUM_ITER=100 IP=192.168.122.89:8002 THREADS=1 ./run-benchmark.sh
  def runExperiment(warmUpIterations: Integer, numIterations: Integer,
    numThreads: Integer, targetUrl: String,
    numRequests: Integer, warmUpOutputFile: File,
    runOutputFile: File, warmDownOutputFile: File): Boolean = {

    // Set warm-down iterations to the same value as warm-up iterations
    val warmDownIterations: Integer = warmUpIterations

    // Remove the output files
    if (machine.fileExists(warmUpOutputFile)) {
      machine.rmFile(warmUpOutputFile)
    }

    if (machine.fileExists(runOutputFile)) {
      machine.rmFile(runOutputFile)
    }

    if (machine.fileExists(warmDownOutputFile)) {
      machine.rmFile(warmDownOutputFile)
    }

    val cmdBase: StringBuffer = new StringBuffer()
    println("Running experiment")

    val warmUpCmd: StringBuffer = new StringBuffer()
    warmUpCmd.append("nohup ab -n %s -c %s -k -r %s 2>&1"
      .format(numRequests, numThreads, targetUrl))
    warmUpCmd.append(" | tee -a %s >> /dev/null".format(warmUpOutputFile))

    val runCmd: StringBuffer = new StringBuffer()
    runCmd.append("nohup ab -n %s -c %s -k -r %s 2>&1"
      .format(numRequests, numThreads, targetUrl))
    runCmd.append(" | tee -a %s >> /dev/null".format(runOutputFile))

    val warmDownCmd: StringBuffer = new StringBuffer()
    warmDownCmd.append("nohup ab -n %s -c %s -k -r %s 2>&1"
      .format(numRequests, numThreads, targetUrl))
    warmDownCmd.append(" | tee -a %s >> /dev/null".format(warmDownOutputFile))

    // Do warm up iterations
    for (i <- 0 until warmUpIterations) {
      println("Warm up iteration: %d".format(i))
      machine.executeCommand(warmUpCmd.toString)
    }

    // Do actual run
    for (i <- 0 until numIterations) {
      println("Iteration %d".format(i))
      machine.executeCommand(runCmd.toString)
    }

    // Do warm down iterations
    for (i <- 0 until warmDownIterations) {
      println("Warm down iteration: %d".format(i))
      machine.executeCommand(warmDownCmd.toString)
    }

    true
  }

  def postProcessResults(runOutputFile: File) = {
    // grep "Requests per second" | awk '{print $4}'
    val getRpsCmd: StringBuffer = new StringBuffer()
    getRpsCmd.append("cat %s | grep 'Requests per second' | awk '{print $4}'"
      .format(runOutputFile.getAbsolutePath()))

    val avgReqsPerSec: Double = machine
      .executeCommand(getRpsCmd.toString) match {
      case ExecutionResponse(Some(0), data, _) => {
        //println(data)
        val dataPoints = data.split("\n")
        val totalReqsPerSec: Double = dataPoints.map {
          pt => { pt.toDouble }
        }.sum
        totalReqsPerSec / dataPoints.size.toDouble
      }
      case res: ExecutionResponse => {
        0.0
      }
    }

    // grep "across all concurrent requests" | awk '{print $4}'
    val getLatencyCmd: StringBuffer = new StringBuffer()
    getLatencyCmd.append("cat %s | ".format(runOutputFile.getAbsolutePath()))
      .append("grep 'across all concurrent requests' | awk '{print $4}'")

    val avgLatencyMs: Double = machine
      .executeCommand(getLatencyCmd.toString) match {
      case ExecutionResponse(Some(0), data, _) => {
        //println(data)
        val dataPoints = data.split("\n")
        val totalLatency: Double = dataPoints.map {
          pt => { pt.toDouble }
        }.sum
        totalLatency / dataPoints.size.toDouble
      }
      case res: ExecutionResponse => {
        0.0
      }
    }

    (avgReqsPerSec, avgLatencyMs)
  }

  installPreReqPackages()
}

object ApacheBench {
  def main(args: Array[String]) {
    val host: String = "localhost"
    val user: String = "rtgbim"

    val targetPath: String = "/"
    val targetHost: String = "192.168.122.89"
    val targetPort: Integer = 8002
    val targetUrl: StringBuffer = new StringBuffer()
    val fmt: DecimalFormat = new DecimalFormat("#,###.###")

    targetUrl.append(targetHost)
      .append(":")
      .append(targetPort)
      .append(targetPath)

    // For experiment name use something that could be part of a filename
    val exptName: String = "nodejs"
    val warmUpIterations: Integer = 10
    val numIterations: Integer = 100
    val numThreads: Integer = 1
    val numRequests: Integer = 10000

    // File where we'll store the warmup, run and warmdown results
    val warmUpOutputFile: File = new File("/tmp/ab-warmup.deploylib.out")
    // Use the expt name in the output file name
    val runOutputFile: File = new File("/tmp/ab-run-%s.deploylib.out"
      .format(exptName))
    val warmDownOutputFile: File = new File("/tmp/ab-warmdown.deploylib.out")

    val loadGen: ApacheBench = new ApacheBench(host, user)
    /**/
    loadGen.runExperiment(warmUpIterations, numIterations, numThreads,
      targetUrl.toString, numRequests,
      warmUpOutputFile, runOutputFile, warmDownOutputFile)
    /**/
    val (avgReqsPerSec, avgLatencyMs) = loadGen
      .postProcessResults(runOutputFile)
    println("Average reqs/sec    : " + fmt.format(avgReqsPerSec))
    println("Average latency (ms): " + fmt.format(avgLatencyMs))
  }
}
