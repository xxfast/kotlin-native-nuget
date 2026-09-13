package io.github.xxfast.kotlin.native.nuget.test.unrouted

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// EXPERIMENT fixture, kept in its own file: research H predicts row 4 is a LIE (both halves emit,
// the C# does not compile), so this file can be disabled independently of the rest of the matrix.

/** row 4: FLOW_PROTOCOL, type-based return on a top-level function. Predicted LIE. */
fun flowReturnOnTopLevel(): Flow<Int> = flowOf(1)
