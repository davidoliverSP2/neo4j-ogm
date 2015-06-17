/*
 * Copyright (c)  [2011-2015] "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.metadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.neo4j.ogm.metadata.info.ClassFileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Vince Bickers
 * @author Luanne Misquitta
 */
public class ClassPathScanner {

    private List<String> classPaths;
    private ClassFileProcessor processor;

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPathScanner.class);


    private void scanFile(File file, String relativePath) throws IOException {
        if (relativePath.endsWith(".class")) {
            try (InputStream inputStream = new FileInputStream(file)) {
                processor.process(inputStream);
            }
        }
    }

    private void scanFolder(File folder, int prefixSize) throws IOException {

        String absolutePath = folder.getPath();
        String relativePath = prefixSize > absolutePath.length() ? "" : absolutePath.substring(prefixSize);

        File[] subFiles = folder.listFiles();
        if (subFiles != null) {
            for (final File subFile : subFiles) {
                if (subFile.isDirectory()) {
                    scanFolder(subFile, prefixSize);
                } else if (subFile.isFile()) {
                    String leafSuffix = "/" + subFile.getName();
                    scanFile(subFile, relativePath + leafSuffix);
                }
            }
        }
    }

    private void scanZipFile(final ZipFile zipFile) throws IOException {
        LOGGER.debug("Scanning " + zipFile.getName());
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            final ZipEntry entry = entries.nextElement();
            scanZipEntry(entry, zipFile, null);
        }
    }

    private void scanZipEntry(ZipEntry zipEntry, ZipFile zipFile, ZipInputStream zipInputStream) throws IOException {
        LOGGER.debug("Scanning entry " + zipEntry.getName());
        if (!zipEntry.isDirectory()) {
            String path = zipEntry.getName();
            if (path.endsWith(".jar") || path.endsWith(".zip")) { //The zipFile contains a zip or jar
                InputStream inputStream = zipFile.getInputStream(zipEntry); //Attempt to read the nested zip
                if (inputStream != null) {
                    zipInputStream = new ZipInputStream(inputStream);
                }
                else {
                    LOGGER.info("Unable to scan " + zipEntry.getName());
                }
                ZipEntry entry = zipInputStream.getNextEntry();
                while (entry != null) { //Recursively scan each entry in the nested zip given its ZipInputStream
                    scanZipEntry(entry, zipFile, zipInputStream);
                    entry = zipInputStream.getNextEntry();
                }
            }
            boolean scanFile = false;
            for (String pathToScan : classPaths) {
                if (path.startsWith(pathToScan)) {
                    scanFile = true;
                    break;
                }
            }
            if (scanFile && path.endsWith(".class")) {
                if (zipInputStream == null) { //ZipEntry directly in the top level ZipFile
                    try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                        processor.process(inputStream);
                    }
                } else { //Nested ZipEntry, read from its ZipInputStream
                    processor.process(zipInputStream);
                    zipInputStream.closeEntry();
                }
            }

        }
    }

    public void scan(List<String> classPaths, ClassFileProcessor processor) {

        this.classPaths = classPaths;
        this.processor = processor;

        List<File> classPathElements = getUniqueClasspathElements(classPaths);
        try {
            for (File classPathElement : classPathElements) {
                String path = classPathElement.getPath();
                if (classPathElement.isDirectory()) {
                    scanFolder(classPathElement, path.length() + 1);
                } else if (classPathElement.isFile()) {
                    String pathLower = path.toLowerCase();
                    if (pathLower.endsWith(".jar") || pathLower.endsWith(".zip")) {
                        scanZipFile(new ZipFile(classPathElement));
                    } else {
                        scanFile(classPathElement, classPathElement.getName());
                    }
                }
            }
            processor.finish();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected List<File> getUniqueClasspathElements(List<String> classPaths) {
        return ClassUtils.getUniqueClasspathElements(classPaths);
    }

}
