/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state

import com.google.common.collect.ImmutableSet
import com.google.common.collect.Maps
import com.google.common.io.ByteStreams
import org.gradle.api.internal.file.archive.ZipEntry
import org.gradle.internal.hash.Hashing
import org.gradle.internal.hash.HashingOutputStream
import org.gradle.internal.snapshot.RegularFileSnapshot
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Supplier

@Unroll
class PropertiesFileAwareClasspathResourceHasherTest extends Specification {
    @Rule TemporaryFolder tmpdir = new TemporaryFolder()
    Map<String, ResourceEntryFilter> filters = Maps.newHashMap()
    ResourceHasher delegate = new RuntimeClasspathResourceHasher()
    ResourceHasher unfilteredHasher = new PropertiesFileAwareClasspathResourceHasher(delegate, PropertiesFileFilter.FILTER_NOTHING)

    def getFilteredHasher() {
        return new PropertiesFileAwareClasspathResourceHasher(delegate, filters)
    }

    def setup() {
        filters = [
            '**/*.properties': filter("created-by", "पशुपतिरपि")
        ]
    }

    def "properties are case sensitive (context: #context)"() {
        given:
        def propertiesEntry1 = contextFor(context, ["Created-By": "1.8.0_232-b18 (Azul Systems, Inc.)"])
        def propertiesEntry2 = contextFor(context, ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash1 != hash2
        hash2 != hash4
        hash3 != hash4
        hash1 != hash3

        where:
        context << SnapshotContext.values()
    }

    def "properties are normalized and filtered out (context: #context)"() {
        given:
        def propertiesEntry1 = contextFor(context, ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)", "foo": "true"])
        def propertiesEntry2 = contextFor(context, ["created-by": "1.8.0_232-b15 (Azul Systems, Inc.)", "foo": "true"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash1 != hash2
        hash1 != hash3
        hash2 != hash4

        and:
        hash3 == hash4

        where:
        context << SnapshotContext.values()
    }

    def "properties can have UTF-8 encoding (context: #context)"() {
        def propertiesEntry1 = contextFor(context, ["Created-By": "1.8.0_232-b18 (Azul Systems, Inc.)", "पशुपतिरपि": "some sanskrit", "तान्यहानि": "more sanskrit"])
        def propertiesEntry2 = contextFor(context, ["Created-By": "1.8.0_232-b18 (Azul Systems, Inc.)", "पशुपतिरपि": "changed sanskrit", "तान्यहानि": "more sanskrit"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash1 != hash2
        hash1 != hash3
        hash2 != hash4

        and:
        hash3 == hash4

        where:
        context << SnapshotContext.values()
    }

    def "properties are order insensitive (context: #context)"() {
        given:
        def propertiesEntry1 = contextFor(context, ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)", "foo": "true"])
        def propertiesEntry2 = contextFor(context, ["foo": "true", "created-by": "1.8.0_232-b15 (Azul Systems, Inc.)"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash1 != hash2
        hash1 != hash3
        hash2 != hash4

        and:
        hash3 == hash4

        where:
        context << SnapshotContext.values()
    }

    def "comments are always filtered out when filters are applied (context: #context)"() {
        def propertiesEntry1 = contextFor(context, ["foo": "true"], "Build information 1.0")
        def propertiesEntry2 = contextFor(context, ["foo": "true"], "Build information 1.1")

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash3 == hash4

        and:
        hash1 != hash2
        hash1 != hash3

        where:
        context << SnapshotContext.values()
    }

    def "can filter files selectively based on pattern (pattern: #fooPattern, context: #context)"() {
        given:
        filters = [
            '**/*.properties': ResourceEntryFilter.FILTER_NOTHING,
            (fooPattern): filter("created-by", "पशुपतिरपि")
        ]

        def propertiesEntry1 = contextFor(context, 'some/path/to/foo.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])
        def propertiesEntry2 = contextFor(context, 'some/path/to/bar.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = filteredHasher.hash(propertiesEntry1)
        def hash4 = filteredHasher.hash(propertiesEntry2)

        expect:
        hash2 != hash4
        hash3 != hash4
        hash1 != hash3

        and:
        hash1 == hash2

        where:
        [context, fooPattern] << [SnapshotContext.values(), ['**/foo.properties', '**/f*.properties', 'some/**/f*.properties', 'some/path/to/foo.properties']].combinations()*.flatten()
    }

    def "can filter multiple files selectively based on pattern (pattern: #fPattern, context: #context)"() {
        given:
        filters = [
            '**/*.properties': ResourceEntryFilter.FILTER_NOTHING,
            (fPattern.toString()): filter("created-by", "पशुपतिरपि")
        ]

        def propertiesEntry1 = contextFor(context, 'some/path/to/foo.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])
        def propertiesEntry2 = contextFor(context, 'some/path/to/bar.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])
        def propertiesEntry3 = contextFor(context, 'some/other/path/to/fuzz.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = unfilteredHasher.hash(propertiesEntry3)
        def hash4 = filteredHasher.hash(propertiesEntry1)
        def hash5 = filteredHasher.hash(propertiesEntry2)
        def hash6 = filteredHasher.hash(propertiesEntry3)

        expect:
        hash1 != hash4
        hash4 != hash5

        and:
        hash1 == hash2
        hash1 == hash3
        hash4 == hash6

        where:
        [context, fPattern] << [SnapshotContext.values(), ['**/f*.properties', 'some/**/f*.properties']].combinations()*.flatten()
    }

    def "multiple filters can be applied to the same file (context: #context)"() {
        given:
        filters = [
            '**/*.properties': filter("created-by"),
            '**/foo.properties': filter("पशुपतिरपि")
        ]

        def propertiesEntry1 = contextFor(context, 'some/path/to/foo.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)", "पशुपतिरपि": "some sanskrit"])
        def propertiesEntry2 = contextFor(context, 'some/path/to/bar.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])
        def propertiesEntry3 = contextFor(context, 'some/path/to/foo.properties', ["created-by": "1.8.0_232-b18 (Azul Systems, Inc.)"])

        def hash1 = unfilteredHasher.hash(propertiesEntry1)
        def hash2 = unfilteredHasher.hash(propertiesEntry2)
        def hash3 = unfilteredHasher.hash(propertiesEntry3)
        def hash4 = filteredHasher.hash(propertiesEntry1)
        def hash5 = filteredHasher.hash(propertiesEntry2)
        def hash6 = filteredHasher.hash(propertiesEntry3)

        expect:
        hash1 != hash2
        hash2 != hash4

        and:
        hash2 == hash3
        hash4 == hash5
        hash4 == hash6

        where:
        context << SnapshotContext.values()
    }

    def "delegates to file hasher when bad unicode escape sequences are present (error in: #location, context: #context)"() {
        given:
        filters = [ '**/*.properties': ResourceEntryFilter.FILTER_NOTHING ]
        def properties = contextFor(context, 'some/path/to/foo.properties', bytesFrom(property))

        expect:
        filteredHasher.hash(properties) == delegate.hash(properties)

        where:
        context                       | location | property
        SnapshotContext.ZIP_ENTRY     | "value"  | 'someKey=a value with bad escape sequence \\uxxxx'
        SnapshotContext.ZIP_ENTRY     | "key"    | 'keyWithBadEscapeSequence\\uxxxx=some value'
        SnapshotContext.FILE_SNAPSHOT | "value"  | 'someKey=a value with bad escape sequence \\uxxxx'
        SnapshotContext.FILE_SNAPSHOT | "key"    | 'keyWithBadEscapeSequence\\uxxxx=some value'
    }

    enum SnapshotContext {
        ZIP_ENTRY, FILE_SNAPSHOT
    }

    def contextFor(SnapshotContext context, String path, Map<String, String> attributes, String comments = "") {
        switch(context) {
            case SnapshotContext.ZIP_ENTRY:
                return zipEntry(path, attributes, comments)
            case SnapshotContext.FILE_SNAPSHOT:
                return fileSnapshot(path, attributes, comments)
            default:
                throw new IllegalAccessException()
        }
    }

    def contextFor(SnapshotContext context, String path, ByteArrayOutputStream bos) {
        switch(context) {
            case SnapshotContext.ZIP_ENTRY:
                return zipEntry(path, bos)
            case SnapshotContext.FILE_SNAPSHOT:
                return fileSnapshot(path, bos)
            default:
                throw new IllegalAccessException()
        }
    }

    def contextFor(SnapshotContext context, Map<String, String> attributes, String comments = "") {
        contextFor(context, "META-INF/build-info.properties", attributes, comments)
    }

    static ByteArrayOutputStream bytesFrom(String value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        bos.write(value.bytes)
        return bos
    }

    static filter(String... properties) {
        return new IgnoringResourceEntryFilter(ImmutableSet.copyOf(properties))
    }

    ZipEntryContext zipEntry(String path, Map<String, String> attributes, String comments = "") {
        Properties properties = new Properties()
        properties.putAll(attributes)
        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        properties.store(bos, comments)
        return zipEntry(path, bos)
    }

    ZipEntryContext zipEntry(String path, ByteArrayOutputStream bos) {
        def zipEntry = new ZipEntry() {
            @Override
            boolean isDirectory() {
                return false
            }

            @Override
            String getName() {
                return path
            }

            @Override
            byte[] getContent() throws IOException {
                return bos.toByteArray()
            }

            @Override
            InputStream getInputStream() {
                return new ByteArrayInputStream(bos.toByteArray())
            }

            @Override
            int size() {
                return bos.size()
            }
        }
        return new ZipEntryContext(zipEntry, path, "foo.zip")
    }

    RegularFileSnapshotContext fileSnapshot(String path, Map<String, String> attributes, String comments = "") {
        Properties properties = new Properties()
        properties.putAll(attributes)
        ByteArrayOutputStream bos = new ByteArrayOutputStream()
        properties.store(bos, comments)
        return fileSnapshot(path, bos)
    }

    RegularFileSnapshotContext fileSnapshot(String path, ByteArrayOutputStream bos) {
        def dir = tmpdir.newFolder()
        def file = new File(dir, path)
        file.parentFile.mkdirs()
        file << bos.toByteArray()
        return Mock(RegularFileSnapshotContext) {
            _ * getSnapshot() >> Mock(RegularFileSnapshot) {
                _ * getAbsolutePath() >> file.absolutePath
                _ * getHash() >> {
                    HashingOutputStream hasher = Hashing.primitiveStreamHasher()
                    ByteStreams.copy(new ByteArrayInputStream(bos.toByteArray()), hasher)
                    return hasher.hash()
                }
            }
            _ * getRelativePathSegments() >> new Supplier<String[]>() {
                @Override
                String[] get() {
                    return path.split('/')
                }
            }
        }
    }
}
