package io.github.xxfast.kotlin.native.nuget.test.mime

import mimemapping.MimeUtility

fun mimeTypeFor(fileName: String): String = MimeUtility.getMimeMapping(fileName)
