package com.swiftbrowser.ui

/**
 * 3.0: what Swift is made of, for About Swift → Open-source licences: each component in the app, its
 * licence as its own files name it, and its home page. NOTICE texts are the ones the components ship.
 */
internal object LicencesData {
    class Component(
        val name: String,
        val version: String,
        val licence: String,
        val licenceUrl: String,
        val home: String,
        val note: String? = null,
        val notice: String? = null,
    )

    val components: List<Component> = listOf(
        Component(
            name = "GeckoView",
            version = "143.0",
            licence = "Mozilla Public License 2.0",
            licenceUrl = "https://www.mozilla.org/MPL/2.0/",
            home = "https://mozilla.github.io/geckoview/",
            note = "Firefox's engine. It includes code from many other projects, listed on the page " +
                "about:license. Parts used: geckoview-armeabi-v7a, and geckoview-exoplayer2 (Mozilla's " +
                "copy of Google's ExoPlayer video player, Apache License 2.0). Source code of this " +
                "release: " +
                "https://hg.mozilla.org/releases/mozilla-release/rev/08388fb6b18c61dbb3d0baa9bee4e7440b85f671",
        ),
        Component(
            name = "uBlock Origin",
            version = "1.75.0",
            licence = "GNU General Public License v3.0",
            licenceUrl = "https://www.gnu.org/licenses/gpl-3.0.html",
            home = "https://github.com/gorhill/uBlock",
            note = "The ad blocker built into Swift, by Raymond Hill and contributors. Swift's copy is " +
                "modified, and its source code is in Swift's GitHub repository: " +
                "https://github.com/ibad20sohail/SwiftBrowser. It includes small libraries from other " +
                "projects (such as CodeMirror, CSSTree and js-beautify, MIT License), whose licences are " +
                "kept with them.",
        ),
        Component(
            name = "pdf.js",
            version = "6.4.299",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/mozilla/pdf.js",
            note = "Shows PDF files in the document viewer. It comes with Adobe's character maps (BSD " +
                "3-Clause License), the Foxit fonts from PDFium (BSD 3-Clause License) and the Liberation " +
                "Sans fonts (GNU General Public License v2.0 with a font exception).",
        ),
        Component(
            name = "docx-preview",
            version = "0.4.1",
            licence = "Apache License 2.0",
            licenceUrl = "https://github.com/VolodymyrBaydalka/docxjs/blob/master/LICENSE",
            home = "https://github.com/VolodymyrBaydalka/docxjs",
            note = "Shows Word documents (.docx) in the document viewer.",
        ),
        Component(
            name = "SheetJS Community Edition",
            version = "0.20.3",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://sheetjs.com",
            note = "Shows spreadsheets in the document viewer. It includes SheetJS's CFB and codepage parts.",
        ),
        Component(
            name = "pptx-preview",
            version = "1.0.7",
            licence = "ISC License",
            licenceUrl = "https://opensource.org/licenses/ISC",
            home = "https://www.npmjs.com/package/pptx-preview",
            note = "Shows PowerPoint slides (.pptx) in the document viewer. It includes Apache ECharts " +
                "(Apache License 2.0) with its drawing library ZRender (BSD 3-Clause License), and its " +
                "own copy of JSZip 3.10.1 (MIT License).",
        ),
        Component(
            name = "epub.js",
            version = "0.3.93",
            licence = "BSD 2-Clause License",
            licenceUrl = "https://opensource.org/licenses/BSD-2-Clause",
            home = "https://github.com/futurepress/epub.js",
            note = "Shows e-books (.epub) in the document viewer.",
        ),
        Component(
            name = "JSZip",
            version = "3.10.2",
            licence = "MIT License",
            licenceUrl = "https://github.com/Stuk/jszip/blob/main/LICENSE.markdown",
            home = "https://github.com/Stuk/jszip",
            note = "Opens the zip files that documents and e-books are made of. It is offered under the MIT " +
                "License or GPLv3, and Swift uses it under the MIT License. It includes pako (MIT " +
                "License).",
        ),
        Component(
            name = "Readability",
            version = "",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/mozilla/readability",
            note = "Reader view: finds the article in a page. Mozilla's library, based on Arc90's " +
                "readability.js (Copyright (c) 2010 Arc90 Inc).",
        ),
        Component(
            name = "AndroidX libraries",
            version = "",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://developer.android.com/jetpack/androidx",
            note = "Google's Android support libraries (Jetpack). Parts used: activity and activity-ktx " +
                "1.10.1; annotation (annotation-jvm) 1.9.1 and annotation-experimental 1.5.1; appcompat " +
                "and appcompat-resources 1.7.1; arch core-common and core-runtime 2.2.0; cardview 1.0.0; " +
                "collection (collection-jvm) 1.5.0; concurrent-futures 1.2.0; constraintlayout and " +
                "constraintlayout-solver 2.0.1; coordinatorlayout 1.1.0; core and core-ktx 1.16.0; " +
                "core-viewtree 1.0.0; cursoradapter 1.0.0; customview 1.1.0; customview-poolingcontainer " +
                "1.0.0; documentfile 1.1.0; drawerlayout 1.1.1; dynamicanimation 1.0.0; emoji2 and " +
                "emoji2-views-helper 1.3.0; exifinterface 1.4.2; fragment 1.5.4; interpolator 1.0.0; " +
                "legacy-support-core-utils 1.0.0; lifecycle 2.9.2 (lifecycle-common, " +
                "lifecycle-common-jvm, lifecycle-livedata, lifecycle-livedata-core, " +
                "lifecycle-livedata-core-ktx, lifecycle-process, lifecycle-runtime, " +
                "lifecycle-runtime-android, lifecycle-runtime-ktx, lifecycle-runtime-ktx-android, " +
                "lifecycle-viewmodel, lifecycle-viewmodel-android, lifecycle-viewmodel-ktx, " +
                "lifecycle-viewmodel-savedstate, lifecycle-viewmodel-savedstate-android); loader 1.0.0; " +
                "localbroadcastmanager 1.0.0; print 1.0.0; profileinstaller 1.4.0; recyclerview 1.4.0; " +
                "resourceinspection-annotation 1.0.1; savedstate, savedstate-android and savedstate-ktx " +
                "1.3.1; startup-runtime 1.1.1; tracing 1.2.0; transition 1.5.0; vectordrawable and " +
                "vectordrawable-animated 1.1.0; versionedparcelable 1.1.1; viewpager 1.0.0; viewpager2 " +
                "1.1.0-beta02.",
        ),
        Component(
            name = "AndroidX Media3",
            version = "1.11.1",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/androidx/media",
            note = "Swift's own video and music player (ExoPlayer). Parts used: common, container, database, " +
                "datasource, decoder, exoplayer, exoplayer-hls, extractor.",
        ),
        Component(
            name = "Apache Commons Codec",
            version = "1.15",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://commons.apache.org/proper/commons-codec/",
            note = "Used by Apache POI (commons-codec).",
            notice = "Apache Commons Codec\n" +
                "Copyright 2002-2020 The Apache Software Foundation\n" +
                "\n" +
                "This product includes software developed at\n" +
                "The Apache Software Foundation (https://www.apache.org/).\n" +
                "\n" +
                "src/test/org/apache/commons/codec/language/DoubleMetaphoneTest.java\n" +
                "contains test data from http://aspell.net/test/orig/batch0.tab.\n" +
                "Copyright (C) 2002 Kevin Atkinson (kevina@gnu.org)\n" +
                "\n" +
                "===============================================================================\n" +
                "\n" +
                "The content of package org.apache.commons.codec.language.bm has been translated\n" +
                "from the original php source code available at http://stevemorse.org/phoneticinfo.htm\n" +
                "with permission from the original authors.\n" +
                "Original source copyright:\n" +
                "Copyright (c) 2008 Alexander Beider & Stephen P. Morse.",
        ),
        Component(
            name = "Apache Commons Collections",
            version = "4.4",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://commons.apache.org/proper/commons-collections/",
            note = "Used by Apache POI (commons-collections4).",
            notice = "Apache Commons Collections\n" +
                "Copyright 2001-2019 The Apache Software Foundation\n" +
                "\n" +
                "This product includes software developed at\n" +
                "The Apache Software Foundation (http://www.apache.org/).",
        ),
        Component(
            name = "Apache Commons Compress",
            version = "1.20",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://commons.apache.org/proper/commons-compress/",
            note = "7z, tar, gz, bz2 and xz archives in Files (commons-compress).",
            notice = "Apache Commons Compress\n" +
                "Copyright 2002-2020 The Apache Software Foundation\n" +
                "\n" +
                "This product includes software developed at\n" +
                "The Apache Software Foundation (https://www.apache.org/).\n" +
                "\n" +
                "---\n" +
                "\n" +
                "The files in the package org.apache.commons.compress.archivers.sevenz\n" +
                "were derived from the LZMA SDK, version 9.20 (C/ and CPP/7zip/),\n" +
                "which has been placed in the public domain:\n" +
                "\n" +
                "\"LZMA SDK is placed in the public domain.\" (http://www.7-zip.org/sdk.html)\n" +
                "\n" +
                "---\n" +
                "\n" +
                "The test file lbzip2_32767.bz2 has been copied from libbzip2's source\n" +
                "repository:\n" +
                "\n" +
                "This program, \"bzip2\", the associated library \"libbzip2\", and all\n" +
                "documentation, are copyright (C) 1996-2019 Julian R Seward.  All\n" +
                "rights reserved.\n" +
                "\n" +
                "Redistribution and use in source and binary forms, with or without\n" +
                "modification, are permitted provided that the following conditions\n" +
                "are met:\n" +
                "\n" +
                "1. Redistributions of source code must retain the above copyright\n" +
                "   notice, this list of conditions and the following disclaimer.\n" +
                "\n" +
                "2. The origin of this software must not be misrepresented; you must \n" +
                "   not claim that you wrote the original software.  If you use this \n" +
                "   software in a product, an acknowledgment in the product \n" +
                "   documentation would be appreciated but is not required.\n" +
                "\n" +
                "3. Altered source versions must be plainly marked as such, and must\n" +
                "   not be misrepresented as being the original software.\n" +
                "\n" +
                "4. The name of the author may not be used to endorse or promote \n" +
                "   products derived from this software without specific prior written \n" +
                "   permission.\n" +
                "\n" +
                "THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS\n" +
                "OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED\n" +
                "WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE\n" +
                "ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY\n" +
                "DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL\n" +
                "DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE\n" +
                "GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS\n" +
                "INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,\n" +
                "WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING\n" +
                "NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS\n" +
                "SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.\n" +
                "\n" +
                "Julian Seward, jseward@acm.org",
        ),
        Component(
            name = "Apache Commons Math",
            version = "3.6.1",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://commons.apache.org/proper/commons-math/",
            note = "Used by Apache POI (commons-math3).",
            notice = "Apache Commons Math\n" +
                "Copyright 2001-2016 The Apache Software Foundation\n" +
                "\n" +
                "This product includes software developed at\n" +
                "The Apache Software Foundation (http://www.apache.org/).\n" +
                "\n" +
                "This product includes software developed for Orekit by\n" +
                "CS Systèmes d'Information (http://www.c-s.fr/)\n" +
                "Copyright 2010-2012 CS Systèmes d'Information",
        ),
        Component(
            name = "Apache POI",
            version = "5.0.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://poi.apache.org/",
            note = "Shows old Word files (.doc). Parts used: poi and poi-scratchpad.",
            notice = "Apache POI\n" +
                "Copyright 2003-2021 The Apache Software Foundation\n" +
                "\n" +
                "This product includes software developed at\n" +
                "The Apache Software Foundation (https://www.apache.org/).\n" +
                "\n" +
                "This product contains parts that were originally based on software from BEA.\n" +
                "Copyright (c) 2000-2003, BEA Systems, <http://www.bea.com/> (dead link),\n" +
                "which was acquired by Oracle Corporation in 2008.\n" +
                "<http://www.oracle.com/us/corporate/Acquisitions/bea/index.html>\n" +
                "<https://en.wikipedia.org/wiki/BEA_Systems>\n" +
                "\n" +
                "This product contains W3C XML Schema documents. Copyright 2001-2003 (c)\n" +
                "World Wide Web Consortium (Massachusetts Institute of Technology, European\n" +
                "Research Consortium for Informatics and Mathematics, Keio University)\n" +
                "\n" +
                "This product contains the chunks_parse_cmds.tbl file from the vsdump program.\n" +
                "Copyright (C) 2006-2007 Valek Filippov (frob@df.ru)\n" +
                "\n" +
                "This product contains parts of the eID Applet project\n" +
                "<http://eid-applet.googlecode.com> and <https://github.com/e-Contract/eid-applet>.\n" +
                "Copyright (c) 2009-2018\n" +
                "FedICT (federal ICT department of Belgium), e-Contract.be BVBA (https://www.e-contract.be),\n" +
                "Bart Hanssens from FedICT",
        ),
        Component(
            name = "desugar_jdk_libs",
            version = "2.1.5",
            licence = "GNU General Public License v2.0 with the Classpath Exception",
            licenceUrl = "https://github.com/google/desugar_jdk_libs/blob/master/LICENSE",
            home = "https://github.com/google/desugar_jdk_libs",
            note = "A small part of OpenJDK's Java library (such as dates and times, which the VPN needs), " +
                "built into the app because Android 7 lacks it.",
        ),
        Component(
            name = "Error Prone annotations",
            version = "2.15.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://errorprone.info",
            note = "Markers in the code of other libraries, such as Material Components.",
        ),
        Component(
            name = "Google Play services",
            version = "",
            licence = "Android Software Development Kit License",
            licenceUrl = "https://developer.android.com/studio/terms.html",
            home = "https://developers.google.com/android/guides/overview",
            note = "Google's own code, not open source. GeckoView uses it to sign in to sites with passkeys " +
                "and security keys. Parts used: play-services-fido 21.2.0, play-services-base 18.5.0, " +
                "play-services-basement 18.5.0 and play-services-tasks 18.2.0. They include open-source " +
                "parts that Google lists with them (such as Protocol Buffers, Guava, Tink, Dagger and " +
                "Kotlin), under the Apache License 2.0, BSD and MIT licences.",
        ),
        Component(
            name = "Guava",
            version = "33.3.1-android",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/google/guava",
            note = "Google's core libraries for Java, used by Media3. Parts used: guava 33.3.1-android and " +
                "failureaccess 1.0.2 (and listenablefuture, an empty placeholder).",
        ),
        Component(
            name = "Jellyfin Media3 FFmpeg decoder",
            version = "1.9.0+1",
            licence = "GNU General Public License v3.0",
            licenceUrl = "https://www.gnu.org/licenses/gpl-3.0.txt",
            home = "https://github.com/jellyfin/jellyfin-androidx-media",
            note = "Plays Dolby (AC-3, E-AC-3, TrueHD) and DTS sound in films. It is Media3's FFmpeg audio " +
                "decoder as Jellyfin builds it, with FFmpeg 6.0 (audio decoders only) inside. FFmpeg is " +
                "built without its GPL and non-free options, so FFmpeg's own code is under the GNU Lesser " +
                "General Public License v2.1 or later (source: https://ffmpeg.org).",
        ),
        Component(
            name = "JetBrains Java Annotations",
            version = "23.0.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/JetBrains/java-annotations",
            note = "Markers in the code of the Kotlin libraries.",
        ),
        Component(
            name = "JSpecify",
            version = "1.0.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/jspecify/jspecify",
            note = "Markers in the code of the AndroidX libraries.",
        ),
        Component(
            name = "Junrar",
            version = "7.4.1",
            licence = "UnRAR License",
            licenceUrl = "https://github.com/junrar/junrar/blob/master/LICENSE",
            home = "https://github.com/junrar/junrar",
            note = "Opens RAR archives in Files. The UnRAR licence allows free use, but its code may not be " +
                "used to make a program that creates RAR archives.",
        ),
        Component(
            name = "Kotlin standard library",
            version = "2.2.21",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://kotlinlang.org/",
            note = "The basic library of Kotlin, the language Swift is written in. Parts used: kotlin-stdlib " +
                "2.2.21 and kotlin-stdlib-jdk7 1.9.0 (empty, kept for older libraries).",
        ),
        Component(
            name = "kotlinx.coroutines",
            version = "1.10.2",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/Kotlin/kotlinx.coroutines",
            note = "Runs Swift's work in the background without freezing the screen. Parts used: " +
                "kotlinx-coroutines-core, kotlinx-coroutines-core-jvm and kotlinx-coroutines-android.",
        ),
        Component(
            name = "kotlinx.serialization",
            version = "1.7.3",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/Kotlin/kotlinx.serialization",
            note = "Used by the AndroidX libraries. Parts used: kotlinx-serialization-core and " +
                "kotlinx-serialization-core-jvm.",
        ),
        Component(
            name = "libtorrent4j",
            version = "2.1.0-38",
            licence = "MIT License",
            licenceUrl = "https://github.com/aldenml/libtorrent4j/blob/master/LICENSE.md",
            home = "https://github.com/aldenml/libtorrent4j",
            note = "Torrents: libtorrent, the engine of qBittorrent and Deluge, for Java. Parts used: " +
                "libtorrent4j and its native library, libtorrent4j-android-arm. The native library " +
                "includes libtorrent 2.1.0 (BSD 3-Clause License, with a few small parts under the zlib " +
                "licence or in the public domain), Boost 1.89 (Boost Software License 1.0), OpenSSL 3.5.2 " +
                "(Apache License 2.0) and, for WebTorrent, libdatachannel and libjuice (Mozilla Public " +
                "License 2.0, source: https://github.com/paullouisageneau), usrsctp (BSD 3-Clause " +
                "License) and plog (MIT License).",
        ),
        Component(
            name = "Material Components for Android",
            version = "1.12.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/material-components/material-components-android",
            note = "Buttons, menus, sheets and other parts of Swift's screens.",
        ),
        Component(
            name = "SLF4J API",
            version = "1.7.30",
            licence = "MIT License",
            licenceUrl = "https://www.opensource.org/licenses/mit-license.php",
            home = "https://www.slf4j.org",
            note = "A logging interface used by Apache POI and Junrar.",
        ),
        Component(
            name = "SnakeYAML",
            version = "2.2",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://bitbucket.org/snakeyaml/snakeyaml",
            note = "Reads YAML files; GeckoView uses it for its start-up settings.",
        ),
        Component(
            name = "SparseBitSet",
            version = "1.2",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/brettwooldridge/SparseBitSet",
            note = "Used by Apache POI.",
        ),
        Component(
            name = "WireGuard tunnel library",
            version = "1.0.20260102",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://www.wireguard.com/",
            note = "The VPN's engine. Its native part includes wireguard-go (MIT License), built with the Go " +
                "runtime and Go's x/crypto, x/net and x/sys libraries (BSD 3-Clause License), and the wg " +
                "and wg-quick tools from wireguard-tools (GNU General Public License v2.0, source: " +
                "https://git.zx2c4.com/wireguard-tools/).",
        ),
        Component(
            name = "XZ for Java",
            version = "1.9",
            licence = "Public domain",
            licenceUrl = "https://github.com/tukaani-project/xz-java/blob/v1.9/COPYING",
            home = "https://tukaani.org/xz/java.html",
            note = "Opens xz archives (with Commons Compress). Its authors put it in the public domain.",
        ),
        Component(
            name = "Zip4j",
            version = "2.11.5",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/srikanth-lingala/zip4j",
            note = "ZIP archives in Files, including ones with passwords.",
        ),
        Component(
            name = "ZXing",
            version = "3.4.1",
            licence = "Apache License 2.0",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt",
            home = "https://github.com/zxing/zxing",
            note = "Reads QR codes (its core library).",
        ),
        Component(
            name = "ZXing Android Embedded",
            version = "4.3.0",
            licence = "Apache License 2.0",
            licenceUrl = "https://github.com/journeyapps/zxing-android-embedded/blob/master/COPYING",
            home = "https://github.com/journeyapps/zxing-android-embedded",
            note = "The camera screen for scanning QR codes.",
        ),
    )
}
