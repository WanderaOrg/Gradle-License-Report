package com.github.jk1.license.reader

import com.github.jk1.license.*
import com.github.jk1.license.util.CachingArtifactResolver
import com.github.jk1.license.util.Files
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.util.zip.ZipEntry
import java.util.zip.ZipFile


class PomReader {

    private Logger LOGGER = Logging.getLogger(ReportTask.class)
    private CachingArtifactResolver resolver

    PomData readPomData(Project project, ResolvedArtifact artifact) {
        resolver = new CachingArtifactResolver(project)
        GPathResult pomContent = slurpPom(artifact.file)
        if (!pomContent) {
            Map pomId = [
                    "group"  : artifact.moduleVersion.id.group,
                    "name"   : artifact.moduleVersion.id.name,
                    "version": artifact.moduleVersion.id.version,
                    "ext"    : "pom"
            ]

            Collection<ResolvedArtifact> artifacts

            try {
                artifacts = resolver.resolveArtifacts(pomId)
            } catch (Exception e) {
                LOGGER.warn("Failed to retrieve artifacts for " + pomId, e)
                artifacts = Collections.emptyList()
            }

            pomContent = artifacts?.inject(pomContent) { GPathResult memo, ResolvedArtifact resolved ->
                try {
                    memo = memo ?: slurpPom(resolved.file)
                } catch (Exception e) {
                    LOGGER.warn("Error slurping pom from $resolved.file", e)
                }
                return memo
            }
        }

        if (!pomContent) {
            LOGGER.info("No POM content found for: $artifact.file")
            return null
        } else {
            return readPomFile(pomContent)
        }
    }

    private GPathResult slurpPom(File toSlurp) {
        if (toSlurp.name == "pom.xml") {
            LOGGER.debug("Slurping pom from pom.xml file: $toSlurp")
            return slurpPomItself(toSlurp)
        }

        String fileSuffix = Files.getExtension(toSlurp.name)?.toLowerCase()
        if (!fileSuffix) {
            LOGGER.debug("No file suffix on potential pom-containing file: $toSlurp")
            return null
        }
        switch (fileSuffix) {
            case "pom":
                LOGGER.debug("Slurping pom from *.pom file: $toSlurp")
                return slurpPomItself(toSlurp)
            case "zip":
            case "jar":
                LOGGER.debug("Processing pom from archive: $toSlurp")
                return slurpPomFromZip(toSlurp)
        }

        LOGGER.debug("No idea how to process a pom from: $toSlurp")
        return null
    }

    private GPathResult slurpPomFromZip(File archiveToSearch) {
        ZipFile archive = new ZipFile(archiveToSearch, ZipFile.OPEN_READ)
        ZipEntry pomEntry = archive.entries().toList().find { ZipEntry entry ->
            entry.name.endsWith("pom.xml") || entry.name.endsWith(".pom")
        }
        LOGGER.debug("Searching for POM file in $archiveToSearch -- found ${pomEntry?.name}")
        if (!pomEntry) return null
        return createParser().parse(archive.getInputStream(pomEntry))
    }

    private GPathResult slurpPomItself(File toSlurp) {
        return createParser().parse(toSlurp)
    }


    private PomData readPomFile(GPathResult pomContent) {
        return readPomFile(pomContent, new PomData())
    }

    private PomData readPomFile(GPathResult pomContent, PomData pomData) {
        if (!pomContent) {
            LOGGER.info("No content found in pom")
            return null
        }

        LOGGER.debug("POM content children: ${pomContent.children()*.name() as Set}")
        if (!pomContent.parent.children().isEmpty()) {
            LOGGER.debug("Processing parent POM: ${pomContent.parent.children()*.name()}")
            GPathResult parentContent = pomContent.parent
            Map<String, String> parent = [
                    "group"  : parentContent.groupId.text(),
                    "name"   : parentContent.artifactId.text(),
                    "version": parentContent.version.text(),
                    "ext"    : "pom"
            ]
            LOGGER.debug("Parent to fetch: $parent")
            Collection<ResolvedArtifact> parentArtifacts
            try {
                parentArtifacts = resolver.resolveArtifacts(parent)
            } catch (Exception e) {
                LOGGER.debug("Failed to retrieve parent artifact " + parent, e)
                parentArtifacts = Collections.emptyList()
            }
            if (parentArtifacts) {
                (parentArtifacts*.file as Set).each { File file ->
                    LOGGER.debug("Processing parent POM file: $file")
                    pomData = readPomFile(createParser().parse(file), pomData)
                }
            }
        }

        pomData.name = pomContent.name?.text()
        pomData.description = pomContent.description?.text()
        String projectUrl = pomContent.url?.text()
        pomData.projectUrl = (projectUrl == null || projectUrl.isEmpty()) ? pomData.projectUrl : projectUrl
        pomData.inceptionYear = pomContent.inceptionYear?.text()

        def organizationName = pomContent.organization?.name?.text()
        def organizationUrl = pomContent.organization?.url?.text()
        if (organizationName || organizationUrl) {
            pomData.organization = new PomOrganization(name: organizationName, url: organizationUrl)
        }

        pomData.developers = pomContent.developers?.developer?.collect { GPathResult developer ->
            new PomDeveloper(
                    name: developer.name?.text(),
                    email: developer.email?.text(),
                    url: developer.url?.text()
            )
        }

        LOGGER.debug("POM license : ${pomContent.licenses.children()*.name() as Set}")

        pomContent.licenses?.license?.each { GPathResult license ->
            LOGGER.debug("Processing license: ${license.name.text()}")
            pomData.licenses << new License(
                    name: license.name?.text(),
                    url: license.url?.text(),
                    distribution: license.distribution?.text(),
                    comments: license.comments?.text()
            )
        }

        LOGGER.info("Returning pom data: ${pomData.dump()}")
        return pomData
    }

    private XmlSlurper createParser() {
        // non-validating, non-namespace aware
        return new XmlSlurper(false, false)
    }
}
