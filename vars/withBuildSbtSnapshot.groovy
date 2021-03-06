#!/usr/bin/env groovy
import com.terradatum.jenkins.workflow.TerradatumCommands
import com.terradatum.jenkins.workflow.Version

/**
 * @author rbellamy@terradatum.com 
 * @date 8/30/16
 */
def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  def flow = new TerradatumCommands()

  String projectPart = config.projectPart
  List<String> projectParts = config.projectParts
  Version version = config.version
  Closure cmds = config.cmds

  if (projectPart) {
    if (!projectParts) {
      projectParts = new ArrayList<>()
    }
    projectParts.add(projectPart)
  }

  if (projectParts) {
    projectParts.each { p ->
      flow.updateSbtDependencies(p)
    }
  }

  flow.updateBuildSbtSnapshotToVersion(version)
  cmds.call()
  flow.gitResetBranch()
}
