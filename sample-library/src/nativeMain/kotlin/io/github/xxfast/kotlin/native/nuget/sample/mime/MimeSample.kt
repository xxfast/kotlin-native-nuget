package io.github.xxfast.kotlin.native.nuget.sample.mime

import mimemapping.MimeUtility

fun mimeTypeFor(fileName: String): String = MimeUtility.getMimeMapping(fileName)
