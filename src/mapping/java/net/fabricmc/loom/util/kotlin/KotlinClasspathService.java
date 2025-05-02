/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util.kotlin;

import org.gradle.api.Project;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.jetbrains.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

public class KotlinClasspathService implements KotlinClasspath {

    private static Map<Project, KotlinClasspathService> cache = new WeakHashMap<>();
    final Set<URL> classpath;
    final String version;

    public KotlinClasspathService(Set<URL> classpath, String version) {
        this.classpath = classpath;
        this.version = version;
    }

    @Nullable
    public static KotlinClasspathService getOrCreateIfRequired(Project project) {
        if (!KotlinPluginUtils.hasKotlinPlugin(project)) {
            return null;
        }

        return getOrCreate(
            project,
            KotlinPluginUtils.getKotlinPluginVersion(project),
            KotlinPluginUtils.getKotlinMetadataVersion()
        );
    }

    public static synchronized KotlinClasspathService getOrCreate(Project project, String kotlinVersion, String kotlinMetadataVersion) {
        return cache.computeIfAbsent(project, (i) -> create(project, kotlinVersion, kotlinMetadataVersion));
    }

    private static KotlinClasspathService create(Project project, String kotlinVersion, String kotlinMetadataVersion) {
        Dependency metadataDependency;
        if (kotlinVersion.startsWith("1.")) {
            // Load kotlinx-metadata-jvm like this to work around: https://github.com/gradle/gradle/issues/14727
            metadataDependency = project.getDependencies().create("org.jetbrains.kotlinx:kotlinx-metadata-jvm:" + kotlinMetadataVersion);
        } else {
            metadataDependency = project.getDependencies().create("org.jetbrains.kotlin:kotlin-metadata-jvm:" + kotlinMetadataVersion);
        }
        // Create a detached config to resolve the kotlin std lib for the provided version.
        Configuration detachedConfiguration = project.getConfigurations().detachedConfiguration(
            project.getDependencies().create("org.jetbrains.kotlin:kotlin-stdlib:" + kotlinVersion),
            metadataDependency
        );

        Set<URL> classpath = detachedConfiguration.getFiles().stream()
            .map(file -> {
                try {
                    return file.toURI().toURL();
                } catch (MalformedURLException e) {
                    throw new UncheckedIOException(e);
                }
            }).collect(Collectors.toSet());

        return new KotlinClasspathService(classpath, kotlinVersion);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Set<URL> classpath() {
        return classpath;
    }

}
