#!/usr/bin/env groovy
package com.terradatum.jenkins.workflow

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurper
import groovy.util.slurpersupport.Node
import jenkins.model.Jenkins

/*
 * version processing
 */

// blocking call to get version, increment, and return
// persists current version in "${path to Jenkins full name}/currentVersion" file
// if a version is passed in, and is greater than the persisted version, then it overrides
// the persisted version and becomes the new version.
/**
 * Created by rbellamy on 8/15/16.
 *
 * When working on this script using IntelliJ, you'll want to run the following
 * commands from the command line:
 * 1. mvn dependency:get -Dartifact=com.cloudbees:groovy-cps:1.9
 * 2. mvn dependency:get -Dartifact=com.github.zafarkhaja:java-semver:0.10-SNAPSHOT
 * 3. mvn dependency:get -Dartifact=org.6wind.jenkins:lockable-resources:1.10
 * 4. mvn dependency:get -Dartifact=org.jenkins-ci.plugins.workflow:workflow-api:2.1
 *
 * And then add them to the module as 'Maven' libraries.
 */

static def String getPathFromJenkinsFullName(String fullName) {
  Jenkins.instance.getItemByFullName(fullName).rootDir
}

def removeTrailingSlash (String myString){
  if (myString.endsWith("/")) {
    return myString.substring(0, myString.length() - 1);
  }
  return myString
}

def getNexusLatestVersion(String artifact) {
  String latest = getNexusVersions(artifact).latest
  return Version.valueOf(latest)
}

def getNexusReleaseVersion(String artifact) {
  String release = getNexusVersions(artifact).release
  return Version.valueOf(release)
}

def getNexusVersions(String artifact) {
  def repo = 'https://nexus.terradatum.com/content/groups/public/com/terradatum'
  artifact = removeTrailingSlash(artifact)

  def metadataUrl = "${repo}/${artifact}/maven-metadata.xml"
  try {
    def modelMetadata = new XmlSlurper().parse(metadataUrl)
    return modelMetadata.versioning.versions
  } catch (err) {
    echo "There was an error retrieving ${metadataUrl}: ${err}"
    return [Version.valueOf('0.0.1')]
  }
}

def getMaxNexusVersion(String project, String artifact, Version version) {
  lock("${project}/maxNexusVersion") {
    List<Node> nexusVersions = getNexusVersions(artifact).version
    List<Version> versions = new ArrayList<>()
    for (int i = 0; i < nexusVersions.size(); i++) {
      Node nexusVersionNode = nexusVersions[i]
      try {
        if (nexusVersionNode) {
          def nexusVersion = Version.valueOf(nexusVersionNode.text())
          if (nexusVersion.majorVersion == version.majorVersion && nexusVersion.minorVersion == version.minorVersion) {
            versions.add(nexusVersion)
          }
        }
      } catch (err) {
        echo "Not valid semantic version: ${nexusVersionNode}, error: ${err}"
      }
    }
    if (versions && versions.size() > 0) {
      def maxVersion = versions.max()
      if (maxVersion.lessThan(version)) {
        return version
      } else {
        return maxVersion
      }
    } else {
      return Version.valueOf('0.0.1')
    }
  }
}

def incrementVersion(String project, VersionType versionType, Version version = null) {
  def path = "${getPathFromJenkinsFullName(project)}/currentVersion"
  def currentVersion = Version.valueOf('0.0.1')
  def nextVersion = currentVersion
  lock("${project}/currentVersion") {
    def versionString = getStringInFile(path)
    def persistedVersion = versionString ? Version.valueOf(versionString) : currentVersion
    if (version && version.lessThan(persistedVersion)) {
      currentVersion = persistedVersion
    } else if (version) {
      currentVersion = version
    }
    switch (versionType) {
      case VersionType.Major:
        nextVersion = currentVersion.incrementPatchVersion()
        break
      case VersionType.Minor:
        nextVersion = currentVersion.incrementPatchVersion()
        break
      case VersionType.Patch:
        nextVersion = currentVersion.incrementPatchVersion()
        break
      case VersionType.PreRelease:
        nextVersion = currentVersion.incrementPreReleaseVersion()
        break
      case VersionType.BuildMetadata:
        nextVersion = currentVersion.incrementBuildMetadata()
        break
    }
    setStringInFile(path, nextVersion.toString())
  }
  nextVersion
}

// TODO: wire this up for other project types.
def getProjectVersionString(ProjectType projectType) {
  def versionString = '0.0.1'
  switch (projectType) {
    case ProjectType.Maven:
      def pom = readMavenPom file: 'pom.xml'
      versionString = pom.version
      break
    case ProjectType.Sbt:
      def sbt = readFile 'build.sbt'
      def matcher = sbt =~ /version\s*:=\s*"([0-9A-Za-z.-]+)",?/
      //noinspection GroovyAssignabilityCheck
      versionString = matcher[0][1]
      break
    case ProjectType.Node:
      //noinspection GrUnresolvedAccess,GroovyAssignabilityCheck
      def packageJson = new JsonSlurper().parseText(readFile('package.json'))
      versionString = packageJson.version
      break
  }
  versionString
}

def getBuildMetadataVersion(Version version) {
  String commit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
  version.setBuildMetadata(commit)
}

def getTagVersion(Version version) {

  // if the repo has no tags this command will fail
  // if this command fails, and there is no config version, rethrow the error
  gitVersion = sh(returnStdout: true, script: 'git tag --sort version:refname | tail -1').trim()

  if (tag == null || tag.size() == 0){
    echo "No existing tag found. Using version: ${version}"
    return version
  }

  tag = tag.trim()

  def semanticVersion = Version.valueOf(tag)
  Version newVersion = version
  if (newVersion.compareWithBuildsTo(semanticVersion) < 0) {
    newVersion = semanticVersion
  }
  newVersion
}

def getCurrentVersion(String project) {
  def path = "${getPathFromJenkinsFullName(project)}/currentVersion"
  Version persistedVersion = Version.valueOf('0.0.1')
  lock("${project}/currentVersion") {
    def versionString = getStringInFile(path)
    if (versionString?.trim()) {
      persistedVersion = Version.valueOf(versionString)
    }
  }
  persistedVersion
}

def setCurrentVersion(String project, Version version) {
  def path = "${getPathFromJenkinsFullName(project)}/currentVersion"
  Version persistedVersion = version ?: Version.valueOf('0.0.1')
  lock("${project}/currentVersion") {
    setStringInFile(path, persistedVersion.toString())
  }
}

def searchAndReplacePomXmlRevision(Version version) {
  if (version) {
    if (version.buildMetadata) {
      sh "find -type f -name 'pom.xml' -exec sed -i -r 's/\\\$\\{revision\\}/${version.patchVersion}\\+${version.buildMetadata}/g' \"{}\" \\;"
    } else {
      sh "find -type f -name 'pom.xml' -exec sed -i -r 's/\\\$\\{revision\\}/${version.patchVersion}/g' \"{}\" \\;"
    }
  }
}

def searchAndReplaceBuildSbtSnapshot(Version version) {
  if (version) {
    if (version.buildMetadata) {
      sh "find . -type f -name 'build.sbt' -exec sed -i -r 's/(version[ \\t]*:=[ \\t]\"[0-9.]+)[0-9]-SNAPSHOT\"/\\1${version.patchVersion}\\+${version.buildMetadata}\"/g' \"{}\" \\;"
    } else {
      sh "find . -type f -name 'build.sbt' -exec sed -i -r 's/(version[ \\t]*:=[ \\t]\"[0-9.]+)[0-9]-SNAPSHOT\"/\\1${version.patchVersion}\"/g' \"{}\" \\;"
    }
  }
}

def searchAndReplacePackageJsonSnapshot(Version version) {
  if (version) {
    if (version.buildMetadata) {
      sh "find \\( -path \"./_build\" -o -path \"./_dist\" -o -path \"./node_modules\" \\) -prune -o -name \"package.json\" -exec sed -i -r 's/(\"version\"[ \\t]*:[ \\t]*\"[0-9.]+)[0-9]-SNAPSHOT\"/\\1${version.patchVersion}\\+${version.buildMetadata}\"/g' \"{}\" \\;"
    } else {
      sh "find \\( -path \"./_build\" -o -path \"./_dist\" -o -path \"./node_modules\" \\) -prune -o -name \"package.json\" -exec sed -i -r 's/(\"version\"[ \\t]*:[ \\t]*\"[0-9.]+)[0-9]-SNAPSHOT\"/\\1${version.patchVersion}\"/g' \"{}\" \\;"
    }
  }
}

def void gitMerge(String targetBranch, String sourceBranch) {
  sshagent(['devops_deploy_DEV']) {
    sh "git checkout ${targetBranch}"
    sh "git merge origin/${sourceBranch}"
  }
}

def void gitConfig(String project) {
  sh 'ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts'
  sh 'git config user.email sysadmin@terradatum.com'
  sh 'git config user.name terradatum-automation'
  sh "git remote set-url origin git@github.com:${project}"
}

def void gitTag(Version releaseVersion) {
  sshagent(['devops_deploy_DEV']) {
    sh 'git tag -d \$(git tag)'
    sh 'git fetch --tags'
    echo "New release version ${releaseVersion.normalVersion}"
    sh "git tag -fa ${releaseVersion.normalVersion} -m 'Release version ${releaseVersion.normalVersion}'"
  }
}

def void gitPush(String targetBranch) {
  sshagent(['devops_deploy_DEV']) {
    sh "git push origin ${targetBranch}"
    sh "git push --tags"
  }
}

def void gitCheckout(String targetBranch) {
  sshagent(['devops_deploy_DEV']) {
    sh "git checkout ${targetBranch}"
    sh 'git pull'
  }
}

def void gitResetBranch() {
  sh 'git checkout -- .'
}

def void dockerLogin(Boolean useSudo) {
  def dockerLogin = sh(returnStdout: true, script: 'aws ecr get-login --region us-west-1').trim()
  if (useSudo) {
    sh "sudo ${dockerLogin}"
  } else {
    sh "${dockerLogin}"
  }
}

/*
 * NonCPS - non-serializable methods
 */
// read full text from file
@NonCPS
static def String getStringInFile(String path) {
  def file = new File(path)
  file.exists() ? file.text : ''
}

// overwrite file with text
@NonCPS
static def void setStringInFile(String path, String value) {
  new File(path).newWriter().withWriter { w ->
    w << value
  }
}

return this