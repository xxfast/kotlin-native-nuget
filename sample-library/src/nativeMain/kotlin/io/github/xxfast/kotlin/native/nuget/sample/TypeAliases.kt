package io.github.xxfast.kotlin.native.nuget.sample

typealias Score = Int
typealias CatNames = List<String>
typealias CatScores = Map<String, Int>

fun topScore(): Score = 10

fun defaultNames(): CatNames = listOf("Oreo", "Mylo")

fun defaultScores(): CatScores = mapOf("Oreo" to 10, "Mylo" to 8)
